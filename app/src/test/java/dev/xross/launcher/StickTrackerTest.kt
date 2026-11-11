package dev.xross.launcher

import dev.xross.launcher.input.NavAction
import dev.xross.launcher.input.StickTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StickTrackerTest {
    @Test fun radialDeadzone() {
        // 0.3 on both axes = 0.42 magnitude: outside a 0.35 radial deadzone.
        assertEquals(NavAction.RIGHT, StickTracker.direction(0.31f, 0.3f, 0.35f))
        assertNull(StickTracker.direction(0.2f, 0.2f, 0.35f))
        assertEquals(NavAction.UP, StickTracker.direction(0f, -0.9f, 0.35f))
    }

    @Test fun pressIsReportedOnceWhileHeld() {
        val t = StickTracker(deadzone = 0.35f)
        assertEquals(NavAction.DOWN, t.update(0f, 0.9f, 0))
        assertNull(t.update(0f, 0.95f, 16))
        assertEquals(NavAction.DOWN, t.current)
    }

    @Test fun snapbackIsIgnored() {
        val t = StickTracker(deadzone = 0.35f, snapbackWindowMs = 70)
        t.update(0.9f, 0f, 0)
        t.update(0f, 0f, 100) // released
        assertNull(t.update(-0.5f, 0f, 130)) // spring overshoot to the left
        assertNull(t.current)
    }

    @Test fun deliberateReversalAfterWindowCounts() {
        val t = StickTracker(deadzone = 0.35f, snapbackWindowMs = 70)
        t.update(0.9f, 0f, 0)
        t.update(0f, 0f, 100)
        assertEquals(NavAction.LEFT, t.update(-0.9f, 0f, 400))
    }
}
