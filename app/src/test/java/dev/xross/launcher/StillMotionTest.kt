package dev.xross.launcher

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.ui.test.junit4.createComposeRule
import dev.xross.launcher.ui.LocalStillMotion
import dev.xross.launcher.ui.LocalUiIdle
import dev.xross.launcher.ui.rememberBreathing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Monochrome E-Ink (Theme 2.0, 2b.3; Roadmap Appendix A.1 invariant 4): under still motion the
 * shared breathing animation behind the focus pulse and the avatar glow never moves.
 */
@RunWith(RobolectricTestRunner::class)
class StillMotionTest {
    @get:Rule val compose = createComposeRule()

    private fun sample(still: Boolean, idle: Boolean = false): List<Float> {
        var breath: State<Float>? = null
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalStillMotion provides still, LocalUiIdle provides idle) {
                breath = rememberBreathing(running = true, low = 0.35f, high = 1f, halfPeriodMs = 1700, rest = REST)
            }
        }
        return List(12) {
            compose.mainClock.advanceTimeBy(250)
            breath!!.value
        }
    }

    @Test fun thePulseBreathesNormally() {
        val values = sample(still = false)
        assertTrue(values.toString(), values.distinct().size > 6)
        assertTrue(values.toString(), values.any { it > 0.9f } && values.any { it < 0.5f })
    }

    @Test fun theEinkProfileHoldsThePulseAtRest() {
        val values = sample(still = true)
        assertEquals(List(values.size) { REST }, values)
    }

    private companion object {
        const val REST = 0.6f
    }
}
