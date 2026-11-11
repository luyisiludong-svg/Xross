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

    @Test fun axialDeadzoneIsOffByDefault() {
        // A crooked push: mostly up, a little sideways. Radial only reads the larger axis.
        assertEquals(NavAction.UP, StickTracker.direction(0.3f, -0.5f, 0.35f))
        assertEquals(NavAction.RIGHT, StickTracker.direction(0.5f, -0.45f, 0.35f))
    }

    @Test fun axialDeadzoneDropsSmallOffAxisComponents() {
        // With a 0.25 axial deadzone the 0.2 sideways drift is removed entirely.
        assertEquals(0f, StickTracker.applyAxial(0.2f, 0.25f))
        assertEquals(-0.3f, StickTracker.applyAxial(-0.3f, 0.25f))
        assertEquals(0.2f, StickTracker.applyAxial(0.2f, 0f))
        assertEquals(NavAction.DOWN, StickTracker.direction(0.2f, 0.4f, 0.35f, axialDeadzone = 0.25f))
    }

    @Test fun axialAndRadialCombine() {
        // Both axes under the axial threshold: nothing, even though the radial length (0.42) clears 0.35.
        assertNull(StickTracker.direction(0.3f, 0.3f, 0.35f, axialDeadzone = 0.32f))
        // Without the axial deadzone the same sample registers.
        assertEquals(NavAction.DOWN, StickTracker.direction(0.3f, 0.3f, 0.35f))
        // Axial filtering leaves a short vector: the radial check still applies to what remains.
        assertNull(StickTracker.direction(0.1f, 0.3f, 0.35f, axialDeadzone = 0.15f))
    }

    @Test fun stickDriftAlongOneAxisIsIgnoredWithAxialDeadzone() {
        val t = StickTracker(deadzone = 0.2f, axialDeadzone = 0.3f)
        // A worn stick resting at 0.25 on X never produces a press.
        assertNull(t.update(0.25f, 0f, 0))
        assertNull(t.current)
        assertEquals(NavAction.RIGHT, t.update(0.8f, 0.1f, 16))
    }

    @Test fun deliberateReversalAfterWindowCounts() {
        val t = StickTracker(deadzone = 0.35f, snapbackWindowMs = 70)
        t.update(0.9f, 0f, 0)
        t.update(0f, 0f, 100)
        assertEquals(NavAction.LEFT, t.update(-0.9f, 0f, 400))
    }
}
