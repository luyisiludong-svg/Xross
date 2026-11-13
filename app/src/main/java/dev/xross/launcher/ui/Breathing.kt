package dev.xross.launcher.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * True while the screen rests (see [dev.xross.launcher.state.InactivityTracker]): breathing
 * glows settle and hold still so no frames are drawn until the next input.
 */
val LocalUiIdle = compositionLocalOf { false }

/**
 * A value breathing between [low] and [high], [halfPeriodMs] per leg with the same easing as an
 * `infiniteRepeatable(…, RepeatMode.Reverse)`. With [running] false it eases to [rest] and stops
 * (no frame callbacks); when it runs again it carries on from wherever it is, so neither edge
 * pops.
 */
@Composable
fun rememberBreathing(running: Boolean, low: Float, high: Float, halfPeriodMs: Int, rest: Float): State<Float> {
    val value = remember { Animatable(rest) }
    // Still-motion profiles (E-Ink): jump to rest and never animate.
    val still = LocalStillMotion.current
    if (still) {
        LaunchedEffect(Unit) { value.snapTo(rest) }
        return value.asState()
    }
    LaunchedEffect(running, low, high, halfPeriodMs, rest) {
        if (!running) {
            value.animateTo(rest, tween(BREATH_SETTLE_MS, easing = FastOutSlowInEasing))
            return@LaunchedEffect
        }
        val span = (high - low).coerceAtLeast(1e-3f)
        // Head for whichever bound is further away first, at the normal pace.
        var target = if (value.value - low < high - value.value) high else low
        var first = true
        while (true) {
            val ms = if (first) (halfPeriodMs * abs(target - value.value) / span).roundToInt() else halfPeriodMs
            value.animateTo(target, tween(ms.coerceAtLeast(1), easing = FastOutSlowInEasing))
            target = if (target == high) low else high
            first = false
        }
    }
    return value.asState()
}

private const val BREATH_SETTLE_MS = 600
