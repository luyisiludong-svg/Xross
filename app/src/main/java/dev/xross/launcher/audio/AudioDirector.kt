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
 * paused it ([MusicOutput.pausedForSnap]). Main thread only.
 */
class AudioDirector(
    private val music: MusicOutput,
    private val sounds: UiSounds,
    private val settings: () -> XrossSettings,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var snap = SnapState.NONE

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
    }

    /** The snap player moved to [state] (started loading, became audible, stopped). */
    fun onSnapState(state: SnapState) {
        if (state == snap) return
        snap = state
        applyMusic()
    }

    /** The startup cue, on a cold boot only, once the theme pack's sounds are loaded. */
    fun onColdBoot() {
        val s = settings()
        if (s.bootSound) sounds.playCue(UiCue.OPENING, mix(s).cue)
    }

    /** A game is starting: music pauses (to resume later, or not) and the start cue plays. */
    fun onGameLaunch() {
        val s = settings()
        val resume = AudioMixer.musicOnLaunch(s.resumeMusicAfterGames) == MusicOnLaunch.PAUSE_AND_RESUME
        if (resume) music.pauseForGame() else music.pause()
        // A song a snap is holding (or fading out) is the game's to resume now, not the snap's.
        music.handSnapPauseToGame(resume)
        cancelFade()
        setMusic(mix(s).music)
        if (s.gameBootSound) sounds.playCue(UiCue.GAMEBOOT, mix(s).cue) else sounds.play(UiSound.OK)
    }

    /** Back in Xross: music a game launch paused picks up again. */
    fun onReturnFromGame() = music.resumeAfterGame()

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
    }
}
