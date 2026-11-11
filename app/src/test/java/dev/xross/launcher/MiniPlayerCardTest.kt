package dev.xross.launcher

import android.net.Uri
import dev.xross.launcher.media.NowPlaying
import dev.xross.launcher.media.Song
import dev.xross.launcher.state.MiniPlayerLine
import dev.xross.launcher.state.MiniPlayerVisibility
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The floating mini player's visibility and its 30-second pause timeout, on virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class) // Song holds an android.net.Uri
class MiniPlayerCardTest {
    private val songA = song(1, "Smoke Song")
    private val songB = song(2, "Second Song")

    private fun song(id: Long, title: String) =
        Song(id, Uri.parse("content://media/external/audio/media/$id"), title, "Smoke Artist", "Album", 9, 1, 180_000)

    private fun playing(s: Song = songA) = NowPlaying(song = s, isPlaying = true, queueSize = 2)
    private fun paused(s: Song = songA) = NowPlaying(song = s, isPlaying = false, queueSize = 2)

    private fun TestScope.visibility() = MiniPlayerVisibility(backgroundScope)
    private fun TestScope.wait(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test fun hiddenWithNothingLoadedAndShownWhilePlaying() = runTest {
        val v = visibility()
        v.update(NowPlaying(), covered = false)
        assertFalse(v.visible.value)
        v.update(playing(), covered = false)
        assertTrue(v.visible.value)
        wait(10 * 60_000L) // playing never times out
        assertTrue(v.visible.value)
    }

    @Test fun pauseHidesTheCardAfterThirtySeconds() = runTest {
        val v = visibility()
        v.update(playing(), covered = false)
        v.update(paused(), covered = false)
        assertTrue("still shown right after pausing", v.visible.value)
        wait(MiniPlayerLine.PAUSED_HIDE_MS - 1)
        assertTrue("shown at 29.999 s", v.visible.value)
        wait(1)
        assertFalse("hidden at 30 s", v.visible.value)
        assertTrue(v.pausedTimedOut)
    }

    @Test fun resumingBringsItBackAtOnceAndResetsTheTimer() = runTest {
        val v = visibility()
        v.update(paused(), covered = false)
        wait(MiniPlayerLine.PAUSED_HIDE_MS)
        assertFalse(v.visible.value)
        v.update(playing(), covered = false)
        assertTrue(v.visible.value)
        assertFalse(v.pausedTimedOut)
        // A new pause gets a fresh 30 s.
        v.update(paused(), covered = false)
        wait(20_000)
        assertTrue(v.visible.value)
        wait(10_000)
        assertFalse(v.visible.value)
    }

    @Test fun changingTrackWhilePausedShowsItAndRestartsTheCountdown() = runTest {
        val v = visibility()
        v.update(paused(songA), covered = false)
        wait(MiniPlayerLine.PAUSED_HIDE_MS)
        assertFalse(v.visible.value)
        v.update(paused(songB), covered = false) // skipped to the next track without playing
        assertTrue(v.visible.value)
        wait(MiniPlayerLine.PAUSED_HIDE_MS - 1)
        assertTrue(v.visible.value)
        wait(1)
        assertFalse(v.visible.value)
    }

    @Test fun repeatedUpdatesWithTheSameStateDoNotExtendThePause() = runTest {
        val v = visibility()
        v.update(paused(), covered = false)
        wait(20_000)
        v.update(paused(), covered = false) // e.g. a recomposition, or the queue size changing
        wait(10_000)
        assertFalse(v.visible.value)
    }

    @Test fun coveringHidesItWithoutTouchingTheTimer() = runTest {
        val v = visibility()
        v.update(playing(), covered = true)
        assertFalse("Now Playing / Options cover the corner", v.visible.value)
        v.update(playing(), covered = false)
        assertTrue(v.visible.value)

        v.update(paused(), covered = true)
        wait(MiniPlayerLine.PAUSED_HIDE_MS)
        v.update(paused(), covered = false)
        assertFalse("the pause timed out behind the overlay", v.visible.value)
    }

    @Test fun stoppingHidesItImmediately() = runTest {
        val v = visibility()
        v.update(playing(), covered = false)
        v.update(NowPlaying(), covered = false) // stop() clears the queue
        assertFalse(v.visible.value)
        assertFalse(v.pausedTimedOut)
    }

    @Test fun cardTextNamesTheTrackAndState() {
        assertTrue(MiniPlayerLine.text(playing())!!.startsWith("♪"))
        assertTrue(MiniPlayerLine.text(paused())!!.startsWith("❚❚"))
    }
}
