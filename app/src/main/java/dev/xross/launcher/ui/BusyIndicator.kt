package dev.xross.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The XMB-style "reading data" icon: a small ring of dots with a bright head that steps around
 * while the library is being scanned or fingerprinted.
 *
 * - Appears only once work has lasted [SHOW_DELAY_MS], so quick rescans don't flash it.
 * - Steps ten times a second from a plain delay loop instead of a per-frame animation: only the
 *   tiny canvas is redrawn (no recomposition), and the loop exists only while it is visible.
 */
@Composable
internal fun BusyIndicator(busy: Boolean, size: Dp, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(busy) {
        if (busy) {
            delay(SHOW_DELAY_MS)
            visible = true
        } else {
            visible = false
        }
    }
    AnimatedVisibility(visible, enter = fadeIn(tween(240)), exit = fadeOut(tween(360)), modifier = modifier) {
        var step by remember { mutableIntStateOf(0) }
        LaunchedEffect(Unit) {
            while (true) {
                delay(STEP_MS)
                step = (step + 1) % DOTS
            }
        }
        Canvas(Modifier.size(size).semantics { contentDescription = "Reading data" }) {
            val ring = this.size.minDimension / 2f
            val dot = ring * 0.17f
            val radius = ring - dot
            for (i in 0 until DOTS) {
                // Distance behind the head: 0 = brightest, fading along the tail.
                val behind = (step - i + DOTS) % DOTS
                val alpha = (1f - behind / DOTS.toFloat()).let { 0.18f + 0.82f * it * it }
                val angle = (i / DOTS.toFloat()) * 2 * PI - PI / 2
                val c = Offset(center.x + radius * cos(angle).toFloat(), center.y + radius * sin(angle).toFloat())
                drawCircle(Color.Black.copy(alpha = 0.35f * alpha), dot * 1.35f, c + Offset(0f, dot * 0.4f))
                drawCircle(Color.White.copy(alpha = alpha), dot, c)
            }
        }
    }
}

private const val DOTS = 8
private const val STEP_MS = 100L
private const val SHOW_DELAY_MS = 400L
