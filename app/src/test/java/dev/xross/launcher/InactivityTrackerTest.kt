package dev.xross.launcher

import dev.xross.launcher.state.InactivityTracker
import dev.xross.launcher.ui.WaveRenderer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InactivityTrackerTest {

    @Test
    fun `goes idle after the timeout and wakes on the next input`() = runTest {
        val tracker = InactivityTracker(timeoutMs = 3_000) { testScheduler.currentTime }
        val job = launch { tracker.run() }
        runCurrent()
        assertFalse(tracker.idle.value)

        advanceTimeBy(2_999)
        assertFalse(tracker.idle.value)
        advanceTimeBy(2)
        assertTrue(tracker.idle.value)

        tracker.onInput()
        assertFalse(tracker.idle.value)
        runCurrent()
        advanceTimeBy(2_999)
        assertFalse(tracker.idle.value)
        advanceTimeBy(2)
        assertTrue(tracker.idle.value)
        job.cancel()
    }

    @Test
    fun `each input pushes the timeout back`() = runTest {
        val tracker = InactivityTracker(timeoutMs = 3_000) { testScheduler.currentTime }
        val job = launch { tracker.run() }
        repeat(5) {
            advanceTimeBy(2_000)
            tracker.onInput()
        }
        assertFalse(tracker.idle.value)
        advanceTimeBy(3_001)
        assertTrue(tracker.idle.value)
        job.cancel()
    }

    @Test
    fun `wave paces to 60 Hz on fast panels and drops to about 20 Hz at rest`() {
        assertEquals(1, WaveRenderer.activeStride(60f))
        assertEquals(1, WaveRenderer.activeStride(90f))
        assertEquals(2, WaveRenderer.activeStride(120f))
        assertEquals(2, WaveRenderer.activeStride(144f))
        assertEquals(3, WaveRenderer.idleStride(60f))
        assertEquals(6, WaveRenderer.idleStride(120f))
    }
}
