package dev.xross.launcher.state

import dev.xross.launcher.media.NowPlaying
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Visibility rules for the floating mini player ([dev.xross.launcher.ui.MiniPlayerCard]) in the
 * lower-right corner of the XMB while music is loaded. (It replaced the "♪ Title — Artist" line
 * that used to sit in the top-left corner; [text] still renders that line for accessibility and
 * logs.)
 *
 * A paused song keeps its card for [PAUSED_HIDE_MS], then the card fades out so a forgotten
 * pause doesn't sit on the home screen forever. Resuming, or a new track, brings it straight back.
 */
object MiniPlayerLine {
    const val PAUSED_HIDE_MS = 30_000L

    /** Fade-out length when the paused timeout hides the card; fading in is quick. */
    const val FADE_OUT_MS = 900
    const val FADE_IN_MS = 220

    /**
     * [pausedTimedOut]: playback has been paused for at least [PAUSED_HIDE_MS] without resuming.
     * [overlayOpen]: something covers the corner (full-screen media, a panel, a dialog).
     */
    fun visible(now: NowPlaying, overlayOpen: Boolean, pausedTimedOut: Boolean): Boolean =
        now.song != null && !overlayOpen && (now.isPlaying || !pausedTimedOut)

    /** True when the paused timer should run: a song is loaded and not playing. */
    fun shouldTime(now: NowPlaying): Boolean = now.song != null && !now.isPlaying

    fun text(now: NowPlaying): String? =
        now.song?.let { "${if (now.isPlaying) "♪" else "❚❚"}  ${it.title} — ${it.artist}" }
}

/**
 * Drives the mini player's visibility from [NowPlaying] updates, including the paused timeout.
 *
 *  - Playing: visible (unless [update]'s `covered`), timer off.
 *  - Paused or ended with a track loaded: still visible; [timeoutMs] later it hides.
 *  - Resumed, or the track changed (skip while paused): visible again at once, timer restarted.
 *  - Nothing loaded (stopped and cleared): hidden.
 *
 * Covering the corner hides the card without touching the timer, so a pause that times out
 * behind Now Playing stays hidden when Now Playing closes.
 */
class MiniPlayerVisibility(
    private val scope: CoroutineScope,
    private val timeoutMs: Long = MiniPlayerLine.PAUSED_HIDE_MS,
) {
    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    /** True once a pause has outlasted [timeoutMs]. */
    var pausedTimedOut: Boolean = false
        private set

    private var timer: Job? = null
    private var songId: Long? = null
    private var playing = false
    private var hasSong = false
    private var covered = false

    fun update(now: NowPlaying, covered: Boolean) {
        this.covered = covered
        val nextId = now.song?.id
        val trackChanged = nextId != songId
        val playingChanged = now.isPlaying != playing
        songId = nextId
        playing = now.isPlaying
        hasSong = now.song != null

        when {
            !hasSong -> cancelTimer()
            now.isPlaying -> cancelTimer()
            trackChanged || playingChanged || (timer == null && !pausedTimedOut) -> restartTimer()
        }
        publish()
    }

    private fun cancelTimer() {
        timer?.cancel()
        timer = null
        pausedTimedOut = false
    }

    private fun restartTimer() {
        timer?.cancel()
        pausedTimedOut = false
        timer = scope.launch {
            delay(timeoutMs)
            pausedTimedOut = true
            timer = null
            publish()
        }
    }

    private fun publish() {
        _visible.value = hasSong && !covered && (playing || !pausedTimedOut)
    }
}
