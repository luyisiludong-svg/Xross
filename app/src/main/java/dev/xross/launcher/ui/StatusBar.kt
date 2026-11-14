package dev.xross.launcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xross.launcher.state.StatusBarState
import kotlinx.coroutines.flow.StateFlow

/**
 * Date, time, battery percentage and a three-bar battery gauge, with room kept at the far right
 * ([trailingWidth] × [trailingHeight]) for the profile avatar, which is drawn over that space by
 * [AvatarHud]. While the avatar's mini-HUD expands over the clock and battery, [contentAlpha]
 * fades them out.
 *
 * The bar collects [source] itself, so a minute tick or a battery change recomposes this bar and
 * nothing around it.
 */
@Composable
fun StatusBar(
    source: StateFlow<StatusBarState>,
    textSize: TextUnit,
    modifier: Modifier = Modifier,
    contentAlpha: () -> Float = { 1f },
    trailingWidth: Dp = 0.dp,
    trailingHeight: Dp = 0.dp,
) {
    val bar by source.collectAsStateWithLifecycle()

    // Date and time in the pack's display font, the battery figure in its mono font.
    val mono = ThemeTypography.mono ?: ThemeTypography.family
    val text = remember(bar.clock, bar.batteryText, mono) {
        androidx.compose.ui.text.buildAnnotatedString {
            append(bar.clock)
            bar.batteryText?.let {
                pushStyle(androidx.compose.ui.text.SpanStyle(fontFamily = mono))
                append("  $it")
                pop()
            }
        }
    }
    val style = TextStyle(
        color = Color.White,
        fontSize = textSize,
        fontFamily = ThemeTypography.display ?: ThemeTypography.family,
        shadow = Shadow(Color(0x99000000), Offset(0f, 3f), 6f),
    )
    val heightDp = with(LocalDensity.current) { textSize.toDp() }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        // Read in the draw phase, so the HUD's expansion never recomposes or re-lays-out the bar.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fadeAlpha(contentAlpha)) {
            Text(text, style = style)
            if (bar.battery != null) {
                Spacer(Modifier.width(10.dp))
                BatteryGauge(bar.batteryBars, bar.batteryLow, Modifier.size(width = heightDp * 1.9f, height = heightDp * 0.95f))
            }
        }
        if (trailingWidth > 0.dp) Spacer(Modifier.size(width = trailingWidth, height = trailingHeight))
    }
}

@Composable
private fun BatteryGauge(bars: Int, low: Boolean, modifier: Modifier) {
    Canvas(modifier) {
        val stroke = size.height * 0.11f
        val nub = size.width * 0.07f
        val body = Size(size.width - nub, size.height)
        val shadow = Offset(0f, stroke)
        // Shadow, outline and terminal nub.
        drawRoundRect(Color(0x66000000), shadow, body, CornerRadius(stroke * 1.5f), style = Stroke(stroke))
        // The outline follows the accent halo (white unless game art or the battery tint color it);
        // read while drawing, so a morph redraws this canvas only.
        val rim = ThemeAccent.halo.value
        drawRoundRect(rim, Offset.Zero, body, CornerRadius(stroke * 1.5f), style = Stroke(stroke))
        drawRect(rim, Offset(body.width, size.height * 0.3f), Size(nub, size.height * 0.4f))
        // Three bars like the reference ([StatusBarState.batteryBars]).
        val pad = stroke * 2f
        val gap = stroke
        val barW = (body.width - pad * 2 - gap * 2) / 3
        val barColor = if (low) Color(0xFFFF6B6B) else Color.White
        for (i in 0 until bars) {
            drawRect(barColor, Offset(pad + i * (barW + gap), pad), Size(barW, body.height - pad * 2))
        }
    }
}
