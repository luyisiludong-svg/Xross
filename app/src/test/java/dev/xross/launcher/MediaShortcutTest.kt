package dev.xross.launcher

import android.net.Uri
import android.view.KeyEvent
import dev.xross.launcher.input.ConfirmLayout
import dev.xross.launcher.input.KeyMapper
import dev.xross.launcher.input.LongPressDetector
import dev.xross.launcher.input.NavAction
import dev.xross.launcher.media.NowPlaying
import dev.xross.launcher.media.Song
import dev.xross.launcher.state.MiniPlayerLine
import dev.xross.launcher.state.RemapSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Media controls shortcut (Select / held Guide) and the auto-hiding mini-player line. */
@RunWith(RobolectricTestRunner::class)
class MediaShortcutTest {

    // region Key mapping

    @Test fun selectOpensMediaControlsByDefault() {
        assertEquals(NavAction.MEDIA, KeyMapper.map(KeyEvent.KEYCODE_BUTTON_SELECT, ConfirmLayout.SOUTH))
        assertEquals(NavAction.MEDIA, KeyMapper.map(KeyEvent.KEYCODE_BUTTON_SELECT, ConfirmLayout.EAST))
        assertFalse(NavAction.MEDIA.isDirectional)
        assertNull(NavAction.MEDIA.opposite())
    }

    @Test fun mediaControlsCanBeRemapped() {
        val remap = mapOf(KeyEvent.KEYCODE_BUTTON_L2 to NavAction.MEDIA, KeyEvent.KEYCODE_BUTTON_SELECT to NavAction.OPTIONS)
        assertEquals(NavAction.MEDIA, KeyMapper.map(KeyEvent.KEYCODE_BUTTON_L2, ConfirmLayout.SOUTH, remap))
        assertEquals(NavAction.OPTIONS, KeyMapper.map(KeyEvent.KEYCODE_BUTTON_SELECT, ConfirmLayout.SOUTH, remap))
    }

    @Test fun guideHasNoShortPressMapping() {
        // Guide is handled as a long press in GamepadHandler, never as a plain mapped key.
        assertNull(KeyMapper.map(KeyEvent.KEYCODE_BUTTON_MODE, ConfirmLayout.SOUTH))
    }

    @Test fun remapWizardEndsWithTheMediaButton() {
        assertEquals(NavAction.MEDIA, RemapSession.STEPS.last())
        assertEquals("Media controls (Select)", RemapSession.label(NavAction.MEDIA))
        var s = RemapSession()
        RemapSession.STEPS.forEachIndexed { i, _ -> s = s.capture(500 + i) }
        assertTrue(s.isDone)
        assertEquals(NavAction.MEDIA, s.captured[500 + RemapSession.STEPS.lastIndex])
    }

    @Test fun searchKeepsTheMediaButtonForItself() {
        val search = dev.xross.launcher.state.SearchState()
        val outcome = search.onAction(NavAction.MEDIA)
        assertEquals(search, outcome.state)
        assertEquals(dev.xross.launcher.state.SearchEffect.None, outcome.effect)
    }

    // endregion

    // region Guide long press

    @Test fun holdFiresOnceFromKeyRepeats() {
        val d = LongPressDetector(thresholdMs = 500)
        assertFalse(d.onDown(eventTimeMs = 1000, downTimeMs = 1000, repeatCount = 0))
        assertFalse(d.onDown(eventTimeMs = 1400, downTimeMs = 1000, repeatCount = 1))
        assertTrue(d.onDown(eventTimeMs = 1550, downTimeMs = 1000, repeatCount = 2))
        assertFalse(d.onDown(eventTimeMs = 1600, downTimeMs = 1000, repeatCount = 3))
        // Already fired: releasing doesn't fire again.
        assertFalse(d.onUp(eventTimeMs = 1700))
    }

    @Test fun holdFiresOnReleaseWhenTheKeyNeverRepeats() {
        val d = LongPressDetector(thresholdMs = 500)
        assertFalse(d.onDown(eventTimeMs = 0, downTimeMs = 0, repeatCount = 0))
        assertTrue(d.onUp(eventTimeMs = 650))
    }

    @Test fun tapNeverFires() {
        val d = LongPressDetector(thresholdMs = 500)
        assertFalse(d.onDown(eventTimeMs = 0, downTimeMs = 0, repeatCount = 0))
        assertFalse(d.onUp(eventTimeMs = 120))
    }

    @Test fun eachHoldStartsAfresh() {
        val d = LongPressDetector(thresholdMs = 500)
        d.onDown(0, 0, 0)
        assertTrue(d.onDown(600, 0, 1))
        d.onUp(700)
        assertFalse(d.onDown(2000, 2000, 0))
        assertTrue(d.onDown(2600, 2000, 1))
    }

    @Test fun releaseWithoutPressIsIgnored() {
        assertFalse(LongPressDetector().onUp(10_000))
    }

    @Test fun resetForgetsAHoldInProgress() {
        val d = LongPressDetector(thresholdMs = 500)
        d.onDown(0, 0, 0)
        d.reset()
        assertFalse(d.onUp(900))
    }

    // endregion

    // region Mini-player line

    private val song = Song(1, Uri.EMPTY, "Smoke Song", "Smoke Artist", "Album", 1, 1, 30_000)

    @Test fun playingSongIsShownWithANote() {
        val now = NowPlaying(song = song, isPlaying = true)
        assertTrue(MiniPlayerLine.visible(now, overlayOpen = false, pausedTimedOut = false))
        assertEquals("♪  Smoke Song — Smoke Artist", MiniPlayerLine.text(now))
        assertFalse(MiniPlayerLine.shouldTime(now))
    }

    @Test fun pausedSongStaysUntilTheTimeout() {
        val paused = NowPlaying(song = song, isPlaying = false)
        assertTrue(MiniPlayerLine.shouldTime(paused))
        assertTrue(MiniPlayerLine.visible(paused, overlayOpen = false, pausedTimedOut = false))
        assertEquals("❚❚  Smoke Song — Smoke Artist", MiniPlayerLine.text(paused))
        assertFalse(MiniPlayerLine.visible(paused, overlayOpen = false, pausedTimedOut = true))
        assertEquals(30_000L, MiniPlayerLine.PAUSED_HIDE_MS)
    }

    @Test fun resumingShowsTheLineEvenIfTheTimerHadExpired() {
        // The timer flag resets on resume, but playing must win even before it does.
        assertTrue(MiniPlayerLine.visible(NowPlaying(song = song, isPlaying = true), overlayOpen = false, pausedTimedOut = true))
    }

    @Test fun hiddenUnderMediaScreensAndWithoutMusic() {
        assertFalse(MiniPlayerLine.visible(NowPlaying(song = song, isPlaying = true), overlayOpen = true, pausedTimedOut = false))
        assertFalse(MiniPlayerLine.visible(NowPlaying(), overlayOpen = false, pausedTimedOut = false))
        assertFalse(MiniPlayerLine.shouldTime(NowPlaying()))
        assertNull(MiniPlayerLine.text(NowPlaying()))
    }

    // endregion
}
