package dev.xross.launcher.audio

/**
 * What [AudioDirector] needs from Xross's music player ([dev.xross.launcher.media.MusicPlayer]):
 * its level and the pauses Xross makes on its own (for a game, for a video snap). Each "paused
 * for" mark is cleared by anything the user does to playback, so Xross only resumes what it
 * paused itself. Main thread only.
 */
interface MusicOutput {
    /** Xross's own level, 0–1, on top of the system media volume. */
    var volume: Float

    /** A song is playing or about to (not paused by anyone, queue not empty). */
    val playing: Boolean

    /** A song is in the queue, playing or paused (theme music waits until the queue is cleared, 2b.5c). */
    val loaded: Boolean

    /** A video snap paused the song and nothing has touched playback since. */
    val pausedForSnap: Boolean

    fun pause()

    /** Pauses a playing song and remembers to resume it after the game. */
    fun pauseForGame()

    /** Resumes what [pauseForGame] paused. */
    fun resumeAfterGame()

    /** Pauses a playing song for a snap ([MusicUnderSnaps.PAUSE]) and marks it [pausedForSnap]. */
    fun pauseForSnap()

    /** Resumes a song a snap paused; false (and nothing happens) when there is none. */
    fun resumeAfterSnap(): Boolean

    /**
     * A game starts while a snap holds the song: the game's pause takes it over, to be resumed on
     * return when [resume], else it stays paused. The snap ending afterwards no longer resumes it.
     */
    fun handSnapPauseToGame(resume: Boolean)
}

/**
 * What [AudioDirector] needs from the theme music player ([dev.xross.launcher.media.ThemeBgm],
 * 2b.5c). Main thread only.
 */
interface BgmOutput {
    /**
     * Moves to [state] at [volume] for [track] (null: the look has none, release the player),
     * fading over [fadeMs]. A different [track] while playing crossfades over [BgmPolicy.FADE_IN_MS].
     */
    fun apply(track: dev.xross.launcher.theme.pack.BgmTrack?, state: BgmState, volume: Float, fadeMs: Long)

    /** Theme music players actually playing now (two during a crossfade), for [PlaybackCensus]. */
    val activePlayers: Int

    /** Told when [activePlayers] changes. */
    var onActiveChanged: (() -> Unit)?

    /** Frees the players (the activity is destroyed). */
    fun release()
}
