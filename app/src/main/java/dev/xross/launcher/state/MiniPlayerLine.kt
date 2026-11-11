package dev.xross.launcher.state

import dev.xross.launcher.media.NowPlaying

/**
 * The "♪ Title — Artist" line in the top-left corner of the XMB while music is loaded.
 *
 * A paused song keeps its line for [PAUSED_HIDE_MS], then the line fades out so a forgotten
 * pause doesn't sit on the home screen forever. Resuming brings it straight back.
 */
object MiniPlayerLine {
    const val PAUSED_HIDE_MS = 30_000L

    /** Fade-out length when the paused timeout hides the line; fading in is quick. */
    const val FADE_OUT_MS = 900
    const val FADE_IN_MS = 220

    /**
     * [pausedTimedOut]: playback has been paused for at least [PAUSED_HIDE_MS] without resuming.
     * The line also hides under full-screen media (photo viewer, video player, Now Playing).
     */
    fun visible(now: NowPlaying, overlayOpen: Boolean, pausedTimedOut: Boolean): Boolean =
        now.song != null && !overlayOpen && (now.isPlaying || !pausedTimedOut)

    /** True when the paused timer should run: a song is loaded and not playing. */
    fun shouldTime(now: NowPlaying): Boolean = now.song != null && !now.isPlaying

    fun text(now: NowPlaying): String? =
        now.song?.let { "${if (now.isPlaying) "♪" else "❚❚"}  ${it.title} — ${it.artist}" }
}
