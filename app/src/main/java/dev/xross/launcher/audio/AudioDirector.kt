package dev.xross.launcher.audio

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.xross.launcher.data.XrossSettings

/**
 * Applies [AudioMixer]'s rules to Xross's players: UI effects and cues ([UiSounds]), the music
 * player, and video snaps (which report where they are through [onSnapState] and take their level
 * from [snapVolume] / [snapVolumeListener]). Every volume and every pause/resume decision goes
 * through here, so the ViewModel, the Activity and the snap player don't set each other's levels.
 *
 * Under a heard snap with [MusicUnderSnaps.PAUSE], a playing song fades out over
 * [AudioMixer.SNAP_FADE_MS] and pauses; when the snap ends it fades back in, only if the snap
 * paused it ([MusicOutput.pausedForSnap]).
 *
 * Theme music ([bgm], 2b.5c) follows [BgmPolicy]: what the look plays ([onBgmTrack]), the
 * launcher in front ([onStarted]), the music player's queue ([onMusicChanged]), games, the
 * full-screen video player ([onFullScreenVideo]), snaps, and other apps' playback ([playback],
 * minus Xross's own players). Main thread only.
 */
class AudioDirector(
    private val music: MusicOutput,
    private val sounds: UiSounds,
    private val bgm: BgmOutput? = null,
    private val playback: PlaybackWatcher? = null,
    private val settings: () -> XrossSettings,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var snap = SnapState.NONE

    // Theme music inputs (2b.5c).
    private var bgmTrack: dev.xross.launcher.theme.pack.BgmTrack? = null
    private var started = false
    private var gameRunning = false
    private var fullScreenVideo = false
    private var bootVideo = false
    private var othersPlaying = false
    private var census: Map<PlaybackCensus.Kind, Int> = emptyMap()
    /** Another app seems to play: confirmed after [OTHERS_SETTLE_MS], so Xross's own pauses and starts don't flicker it. */
    private var othersPending: Runnable? = null
    private var lastBgm: BgmDecision? = null
    /** The last decision the player has acted on audibly: null (or a stop) until it first plays, so its first start fades in. */
    private var heardBgm: BgmDecision? = null
    private var lastBgmTrack: dev.xross.launcher.theme.pack.BgmTrack? = null

    init {
        bgm?.onActiveChanged = ::recount
    }

    /** A heard snap is holding the music ([Mix.holdMusic]). */
    private var holding = false

    /** The music fade in progress (out for a snap, or back in after it). */
    private var fade: Runnable? = null

    /** Told the snap level whenever it changes, so a playing snap follows Settings ▸ Sound live. */
    var snapVolumeListener: ((Float) -> Unit)? = null

    private fun mix(s: XrossSettings = settings()) = AudioMixer.resolve(
        MixInputs(
            effectsVolume = s.soundVolume,
            cueVolume = s.cueVolume,
            snapVolume = s.snapVolume,
            musicVolume = s.musicVolume,
            musicUnderSnaps = s.musicUnderSnaps,
            snap = snap,
        ),
    )

    /** The level a snap starting now ramps up to. */
    val snapVolume: Float get() = mix().snap

    /** Settings changed (or were just loaded): effects on/off and every level, applied live. */
    fun onSettingsChanged(s: XrossSettings) {
        sounds.enabled = s.soundEffects
        val m = mix(s)
        sounds.volume = m.effects
        if (m.snap != lastSnap) {
            lastSnap = m.snap
            snapVolumeListener?.invoke(m.snap)
        }
        applyMusic()
        applyBgm()
    }

    /** The snap player moved to [state] (started loading, became audible, stopped). */
    fun onSnapState(state: SnapState) {
        if (state == snap) return
        snap = state
        applyMusic()
        recount()
        applyBgm()
    }

    /** The look's music loop (variant, category, system, rules): null when it has none. */
    fun onBgmTrack(track: dev.xross.launcher.theme.pack.BgmTrack?) {
        if (track == bgmTrack) return
        bgmTrack = track
        applyBgm()
    }

    /** The launcher came to the front (`onStart`, true) or left it (`onStop`, false). */
    fun onStarted(inFront: Boolean) {
        if (inFront == started) return
        if (inFront) {
            // A fresh look at who plays before theme music may start, without the settle delay.
            playback?.start(::onPlaybackChanged)
        } else {
            playback?.stop()
            cancelOthers()
            othersPlaying = false
        }
        started = inFront
        applyBgm()
    }

    /** Xross's music player changed (a song loaded or cleared, play or pause). */
    fun onMusicChanged() {
        recount()
        applyBgm()
    }

    /** The full-screen video player opened or closed. */
    fun onFullScreenVideo(open: Boolean) {
        if (open == fullScreenVideo) return
        fullScreenVideo = open
        recount()
        applyBgm()
    }

    /** The pack's boot video started or ended (2b.5d): theme music waits for it. */
    fun onBootVideo(playing: Boolean) {
        if (playing == bootVideo) return
        bootVideo = playing
        applyBgm()
    }

    /** The emulator couldn't be started: back in the menu without a game. */
    fun onLaunchFailed() {
        gameRunning = false
        applyBgm()
    }

    /** The startup cue, on a cold boot only, once the theme pack's sounds are loaded. */
    fun onColdBoot() {
        val s = settings()
        if (s.bootSound) sounds.playCue(UiCue.OPENING, mix(s).cue)
    }

    /** A game is starting: music pauses (to resume later, or not) and the start cue plays. */
    fun onGameLaunch() {
        gameRunning = true
        applyBgm()
        val s = settings()
        val resume = AudioMixer.musicOnLaunch(s.resumeMusicAfterGames) == MusicOnLaunch.PAUSE_AND_RESUME
        if (resume) music.pauseForGame() else music.pause()
        // A song a snap is holding (or fading out) is the game's to resume now, not the snap's.
        music.handSnapPauseToGame(resume)
        cancelFade()
        setMusic(mix(s).music)
        if (s.gameBootSound) sounds.playCue(UiCue.GAMEBOOT, mix(s).cue) else sounds.play(UiSound.OK)
    }

    /** Back in Xross: music a game launch paused picks up again, and theme music starts over. */
    fun onReturnFromGame() {
        music.resumeAfterGame()
        gameRunning = false
        applyBgm()
    }

    private fun bgmInputs(s: XrossSettings = settings()) = BgmInputs(
        hasTrack = bgmTrack != null,
        bgmVolume = s.bgmVolume,
        started = started,
        musicLoaded = music.loaded,
        otherAppPlaying = othersPlaying,
        gameRunning = gameRunning,
        fullScreenVideo = fullScreenVideo,
        bootVideo = bootVideo,
        snap = snap,
        snapVolume = s.snapVolume,
        musicUnderSnaps = s.musicUnderSnaps,
    )

    /** What theme music does now ([BgmPolicy]), for tests and the device checks. */
    val bgmDecision: BgmDecision get() = BgmPolicy.decide(bgmInputs())

    private fun applyBgm() {
        val out = bgm ?: return
        val d = bgmDecision
        val track = bgmTrack
        if (d == lastBgm && track == lastBgmTrack) return
        out.apply(track, d.state, d.volume, BgmPolicy.fadeMs(heardBgm, d))
        // A loop paused before it ever played (or a new one, not crossfaded) still starts from silence.
        val fresh = track != lastBgmTrack && d.state != BgmState.PLAYING
        heardBgm = when {
            fresh -> null
            d.state == BgmState.PAUSED && heardBgm?.state != BgmState.PLAYING && heardBgm?.state != BgmState.PAUSED -> heardBgm
            else -> d
        }
        lastBgm = d
        lastBgmTrack = track
        logBgm(d)
    }

    /** Players by kind that Xross itself runs: the music player, a snap and the video player; theme music. */
    private fun ownPlayers(): Map<PlaybackCensus.Kind, Int> = mapOf(
        PlaybackCensus.Kind.MEDIA to (if (music.playing) 1 else 0) + (if (snap != SnapState.NONE) 1 else 0) + (if (fullScreenVideo) 1 else 0),
        PlaybackCensus.Kind.GAME_MUSIC to (bgm?.activePlayers ?: 0),
    )

    /** The platform's active players changed ([PlaybackWatcher]); [snapshot] is the first count after [onStarted]. */
    internal fun onPlaybackChanged(active: Map<PlaybackCensus.Kind, Int>, snapshot: Boolean) {
        census = active
        if (snapshot) {
            othersPlaying = PlaybackCensus.othersPlaying(census, ownPlayers())
            logCensus()
        } else {
            recount()
        }
    }

    /**
     * Re-reads whether another app plays, after the census or one of Xross's own players changed.
     * Silence applies at once; playback must hold for [OTHERS_SETTLE_MS], since the platform
     * reports Xross's own players starting and pausing a moment after Xross knows.
     */
    private fun recount() {
        if (!started) return
        if (!PlaybackCensus.othersPlaying(census, ownPlayers())) {
            cancelOthers()
            if (othersPlaying) {
                othersPlaying = false
                logCensus()
                applyBgm()
            }
            return
        }
        if (othersPlaying || othersPending != null) return
        val check = Runnable {
            othersPending = null
            if (started && PlaybackCensus.othersPlaying(census, ownPlayers())) {
                othersPlaying = true
                logCensus()
                applyBgm()
            }
        }
        othersPending = check
        handler.postDelayed(check, OTHERS_SETTLE_MS)
    }

    private fun cancelOthers() {
        othersPending?.let(handler::removeCallbacks)
        othersPending = null
    }

    private fun logBgm(d: BgmDecision) {
        if (!dev.xross.launcher.BuildConfig.PERF_LOG) return
        android.util.Log.i(
            "XrossBgm",
            "state=%s file=%s vol=%.2f reason=%s".format(java.util.Locale.ROOT, d.state, bgmTrack?.name ?: "none", d.volume, d.reason),
        )
    }

    private fun logCensus() {
        if (!dev.xross.launcher.BuildConfig.PERF_LOG) return
        val own = ownPlayers()
        android.util.Log.i(
            "XrossBgm",
            "others=%s media=%d/%d gameMusic=%d/%d".format(
                java.util.Locale.ROOT, othersPlaying,
                census[PlaybackCensus.Kind.MEDIA] ?: 0, own[PlaybackCensus.Kind.MEDIA] ?: 0,
                census[PlaybackCensus.Kind.GAME_MUSIC] ?: 0, own[PlaybackCensus.Kind.GAME_MUSIC] ?: 0,
            ),
        )
    }

    /** Frees the theme music player (the activity is destroyed). */
    fun release() {
        onStarted(false)
        bgm?.release()
        lastBgm = null
        heardBgm = null
        lastBgmTrack = null
    }

    private fun applyMusic() {
        val m = mix()
        when {
            m.holdMusic && !holding -> {
                holding = true
                if (music.playing) {
                    fadeMusic(to = { 0f }) {
                        if (holding && music.playing) music.pauseForSnap()
                        // Paused (or paused by the user meanwhile): back at its level for whoever plays it next.
                        setMusic(mix().music)
                    }
                }
            }
            !m.holdMusic && holding -> {
                holding = false
                when {
                    music.pausedForSnap -> {
                        setMusic(0f)
                        if (music.resumeAfterSnap()) fadeMusic(to = { mix().music }) else setMusic(m.music)
                    }
                    // The snap ended while the song was still fading out: bring it back up.
                    fade != null -> fadeMusic(to = { mix().music })
                    else -> setMusic(m.music)
                }
            }
            // A fade reads its target as it goes; otherwise apply the level now.
            fade == null -> setMusic(m.music)
        }
    }

    /** Moves the music's level linearly from where it is to [to] (read every step) over [AudioMixer.SNAP_FADE_MS]. */
    private fun fadeMusic(to: () -> Float, then: () -> Unit = {}) {
        cancelFade()
        val from = music.volume
        val start = SystemClock.uptimeMillis()
        val step = object : Runnable {
            override fun run() {
                if (fade !== this) return
                val t = ((SystemClock.uptimeMillis() - start).toFloat() / AudioMixer.SNAP_FADE_MS).coerceIn(0f, 1f)
                music.volume = from + (to() - from) * t
                if (t < 1f) {
                    handler.postDelayed(this, FADE_STEP_MS)
                } else {
                    fade = null
                    log(music.volume)
                    then()
                }
            }
        }
        fade = step
        handler.post(step)
    }

    private fun cancelFade() {
        fade?.let(handler::removeCallbacks)
        fade = null
    }

    private fun setMusic(v: Float) {
        music.volume = v
        log(v)
    }

    // Debug and profiling builds: one line per change, for the device checks (a player's own
    // volume isn't visible in dumpsys audio).
    private fun log(musicVolume: Float) {
        if (!dev.xross.launcher.BuildConfig.PERF_LOG || musicVolume == lastMusic) return
        lastMusic = musicVolume
        val s = settings()
        android.util.Log.i(
            "XrossMix",
            "music=%.2f playing=%s snap=%s snapVolume=%.2f mode=%s held=%s".format(
                java.util.Locale.ROOT, musicVolume, music.playing, snap, mix(s).snap, s.musicUnderSnaps, music.pausedForSnap,
            ),
        )
    }

    private var lastMusic = -1f
    private var lastSnap = -1f

    private companion object {
        const val FADE_STEP_MS = 16L
        const val OTHERS_SETTLE_MS = 500L
    }
}
