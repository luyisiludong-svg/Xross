package dev.xross.launcher.audio

/** What the theme music player is doing (2b.5c). */
enum class BgmState {
    /** Not playing; the next start begins at position 0. */
    STOPPED,
    /** Held where it is, to continue from there. */
    PAUSED,
    PLAYING,
}

/** Why theme music is where it is: the first rule that holds, for the `XrossBgm` log and tests. */
enum class BgmReason {
    /** It plays. */
    ALLOWED,
    /** The pack (or the look's variant, override or rule) has no loop. */
    NO_TRACK,
    /** Settings ▸ Sound ▸ Theme music is at 0%. */
    MUTED,
    /** An emulator is starting or running: stopped, back from position 0 after the game. */
    GAME,
    /** Xross's music player has a song loaded (playing or paused): the user is listening to music. */
    MUSIC,
    /** The launcher isn't in front (`onStop`). */
    BACKGROUND,
    /** The full-screen video player is open. */
    VIDEO,
    /** The pack's boot video is playing (2b.5d). */
    BOOT_VIDEO,
    /** Another app is playing media. */
    OTHER_APP,
    /** A video snap is heard with [MusicUnderSnaps.PAUSE]. */
    SNAP,
    /** A video snap is heard with [MusicUnderSnaps.DUCK]: playing at [AudioMixer.SNAP_DUCK]. */
    SNAP_DUCK,
}

/** Everything theme music depends on. */
data class BgmInputs(
    /** The look has a loop the pack could read. */
    val hasTrack: Boolean = true,
    /** Settings ▸ Sound ▸ Theme music. */
    val bgmVolume: Float = 0.6f,
    /** The activity is started (between `onStart` and `onStop`). */
    val started: Boolean = true,
    /** Xross's music player has a song in its queue, playing or paused. */
    val musicLoaded: Boolean = false,
    /** Another app plays media ([PlaybackCensus]). */
    val otherAppPlaying: Boolean = false,
    /** A game launch is under way or the emulator is in front. */
    val gameRunning: Boolean = false,
    /** The full-screen video player is open. */
    val fullScreenVideo: Boolean = false,
    /** The pack's boot video plays (2b.5d). */
    val bootVideo: Boolean = false,
    val snap: SnapState = SnapState.NONE,
    /** Video snaps' level: a snap at 0% is silent and changes nothing. */
    val snapVolume: Float = 0.75f,
    val musicUnderSnaps: MusicUnderSnaps = MusicUnderSnaps.PAUSE,
)

/** Where theme music should be, at which level (0–1, already ducked), and why. */
data class BgmDecision(val state: BgmState, val volume: Float, val reason: BgmReason)

/**
 * The rules for the pack's ambient music (2b.5c), pure: it plays only when the pack has a loop,
 * Theme music is above 0%, the launcher is in front, Xross's music player has no song loaded, no
 * other app plays media, no game runs and no full-screen video (or boot video) is up. Under a
 * heard video snap it follows Music during video snaps like a song does.
 *
 * Stopping (the next start is from 0) is for what replaces the launcher's own soundscape: no
 * loop, Theme music muted, a game, the user's music. Everything else pauses, to continue where it
 * was. [AudioDirector] applies the decision through [BgmOutput].
 */
object BgmPolicy {
    /** A starting loop fades in, and a track change crossfades, over this. */
    const val FADE_IN_MS = 600L

    /** Music starting or a game launching: theme music fades out over this and stops. */
    const val FADE_OUT_MS = 300L

    /** Pausing for (and resuming after) a snap, like a song ([AudioMixer.SNAP_FADE_MS]). */
    const val PAUSE_FADE_MS = AudioMixer.SNAP_FADE_MS

    fun decide(i: BgmInputs): BgmDecision {
        val volume = i.bgmVolume.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        val snapHeard = i.snap == SnapState.AUDIBLE && i.snapVolume.isFinite() && i.snapVolume > 0f
        fun stop(r: BgmReason) = BgmDecision(BgmState.STOPPED, 0f, r)
        fun pause(r: BgmReason) = BgmDecision(BgmState.PAUSED, 0f, r)
        return when {
            !i.hasTrack -> stop(BgmReason.NO_TRACK)
            volume <= 0f -> stop(BgmReason.MUTED)
            i.gameRunning -> stop(BgmReason.GAME)
            i.musicLoaded -> stop(BgmReason.MUSIC)
            !i.started -> pause(BgmReason.BACKGROUND)
            i.fullScreenVideo -> pause(BgmReason.VIDEO)
            i.bootVideo -> pause(BgmReason.BOOT_VIDEO)
            i.otherAppPlaying -> pause(BgmReason.OTHER_APP)
            snapHeard && i.musicUnderSnaps == MusicUnderSnaps.PAUSE -> pause(BgmReason.SNAP)
            snapHeard -> BgmDecision(BgmState.PLAYING, volume * AudioMixer.SNAP_DUCK, BgmReason.SNAP_DUCK)
            else -> BgmDecision(BgmState.PLAYING, volume, BgmReason.ALLOWED)
        }
    }

    /**
     * How long the move from [from] to [to] fades: a fresh start fades in, a stop fades out, a
     * pause for a snap (and the way back) fades like a song; the launcher leaving the screen cuts at once.
     */
    fun fadeMs(from: BgmDecision?, to: BgmDecision): Long = when {
        to.reason == BgmReason.BACKGROUND -> 0L
        to.state == BgmState.STOPPED -> FADE_OUT_MS
        to.state == BgmState.PAUSED -> PAUSE_FADE_MS
        // Playing: from a stop (or nothing) it fades in; from a pause or a level change it ramps.
        from == null || from.state == BgmState.STOPPED -> FADE_IN_MS
        else -> PAUSE_FADE_MS
    }
}

/**
 * Who else is playing (2b.5c), from the platform's active playback configurations
 * (`AudioManager.registerAudioPlaybackCallback`), which list every app's players including
 * Xross's own: [othersPlaying] subtracts the players Xross knows it is running. Not
 * `isMusicActive()`, which counts Xross's players and would silence theme music by its own sound.
 */
object PlaybackCensus {
    /** The usages counted: media players, and games' music (not their sound effects). */
    enum class Kind { MEDIA, GAME_MUSIC }

    /**
     * The kind of an active player with [usage] and [contentType] (`android.media.AudioAttributes`
     * values), or null for one that doesn't count (notifications, UI clicks, speech).
     */
    fun kindOf(usage: Int, contentType: Int): Kind? = when {
        usage == USAGE_MEDIA -> Kind.MEDIA
        usage == USAGE_GAME && (contentType == CONTENT_TYPE_MUSIC || contentType == CONTENT_TYPE_MOVIE) -> Kind.GAME_MUSIC
        else -> null
    }

    /**
     * True when more players of a kind are active than Xross runs: [active] by kind from the
     * platform, [own] by kind from Xross's own players (music, snap, video: media; theme music: game music).
     */
    fun othersPlaying(active: Map<Kind, Int>, own: Map<Kind, Int>): Boolean =
        Kind.entries.any { (active[it] ?: 0) > (own[it] ?: 0) }

    // android.media.AudioAttributes constants, so this stays plain Kotlin.
    const val USAGE_MEDIA = 1
    const val USAGE_GAME = 14
    const val CONTENT_TYPE_MOVIE = 3
    const val CONTENT_TYPE_MUSIC = 2
}
