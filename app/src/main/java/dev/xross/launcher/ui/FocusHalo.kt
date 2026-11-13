package dev.xross.launcher.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import dev.xross.launcher.theme.pack.FocusFrameStyle

/**
 * The theme's focus frame (2b.4d, `effects.focusFrameStyle`) and its colors: [neon] for
 * [FocusFrameStyle.STATIC_NEON] (the palette's accent) and [gold] for
 * [FocusFrameStyle.GOLD_BEZEL] (`palette.focusGold`, else the mastery gold).
 *
 * Every frame is drawn in the draw phase from a `glow` strength (0–1). With [breathes] false the
 * shared pulse doesn't run and the strength is the focus alone, so a resting screen draws nothing.
 */
@Immutable
data class FocusFrame(
    val style: FocusFrameStyle = FocusFrameStyle.BREATHING_HALO,
    val neon: Color = Color.White,
    val gold: Color = Gold,
) {
    val breathes: Boolean get() = style.breathes

    /**
     * The neon color now: the game-art or battery accent while one tints the halos
     * ([ThemeAccent.tinted]), else [neon]. Read in the draw phase.
     */
    fun neonNow(): Color = if (ThemeAccent.tinted) ThemeAccent.halo.value else neon

    /**
     * The soft radial bloom behind a focused glyph, art or thumbnail: white for the breathing
     * halo, the neon or gold color for those styles, none under a hard shadow.
     */
    fun bloomColor(): Color? = when (style) {
        FocusFrameStyle.BREATHING_HALO -> Color.White
        FocusFrameStyle.STATIC_NEON -> neonNow()
        FocusFrameStyle.HARD_SHADOW -> null
        FocusFrameStyle.GOLD_BEZEL -> gold
    }

    /** True when a plate (tile, thumbnail, badge) gets [drawPlateFrame] rather than only the bloom. */
    val framesPlates: Boolean get() = style != FocusFrameStyle.BREATHING_HALO

    companion object {
        val DEFAULT = FocusFrame()

        /** The pulse value used instead of the breathing one when the frame doesn't breathe. */
        const val STEADY = 1f
    }
}

/** The focus frame in use; MainActivity provides the active look's. */
val LocalFocusFrame = compositionLocalOf { FocusFrame.DEFAULT }

/**
 * The shared focus pulse: breathing between 0.35 and 1 while [running] under
 * [FocusFrameStyle.BREATHING_HALO] (settling at [rest] otherwise), and a constant
 * [FocusFrame.STEADY] under every other frame, which never starts the breathing animation.
 */
@Composable
fun rememberFocusPulse(running: Boolean, rest: Float = PULSE_REST): State<Float> {
    val breathes = LocalFocusFrame.current.breathes
    val breathing = rememberBreathing(running = running && breathes, low = 0.35f, high = 1f, halfPeriodMs = 1700, rest = rest)
    return if (breathes) breathing else SteadyPulse
}

private object SteadyPulse : State<Float> {
    override val value: Float get() = FocusFrame.STEADY
}

/** Where the breathing glow holds while the screen rests: a little above the middle of its swing. */
const val PULSE_REST = 0.7f

/** The four stops of a polished metal rim in [base]: highlight, base, shade, base, highlight. */
fun metalBrush(base: Color, start: Offset, end: Offset): Brush {
    val light = lerp(base, Color.White, 0.6f)
    val dark = lerp(base, Color.Black, 0.45f)
    return Brush.linearGradient(
        0f to light,
        0.3f to base,
        0.5f to dark,
        0.7f to base,
        1f to light,
        start = start,
        end = end,
    )
}

/**
 * The focus frame around a rounded-rect [plate] at this scope's origin (by default the whole
 * scope; [corner] px radius), at strength [glow] (0–1), drawn outside the plate's bounds. [FocusFrameStyle.BREATHING_HALO]
 * is the soft white rings the game tiles always had.
 */
fun DrawScope.drawPlateFrame(frame: FocusFrame, glow: Float, corner: Float, plate: Size = size) {
    if (glow <= 0f) return
    when (frame.style) {
        FocusFrameStyle.BREATHING_HALO -> {
            val step = 2.5.dp.toPx()
            for (i in 1..4) ring(plate, Color.White.copy(alpha = 0.22f * glow / i), step * i, step * 1.2f, corner)
        }
        FocusFrameStyle.STATIC_NEON -> {
            val neon = frame.neonNow()
            val step = 2.dp.toPx()
            // A crisp, bright core hugging the plate, then a short, tight falloff.
            ring(plate, neon.copy(alpha = 0.95f * glow), 1.dp.toPx(), 1.5.dp.toPx(), corner)
            for (i in 1..3) ring(plate, neon.copy(alpha = 0.5f * glow / i), 1.dp.toPx() + step * i, step, corner)
        }
        FocusFrameStyle.HARD_SHADOW -> {
            val d = 2.dp.toPx()
            val outline = Path().apply { addRoundRect(RoundRect(0f, 0f, plate.width, plate.height, CornerRadius(corner))) }
            // Only where the plate doesn't cover it, so a translucent plate isn't darkened.
            clipPath(outline, ClipOp.Difference) {
                drawRoundRect(
                    Color.Black.copy(alpha = 0.85f * glow),
                    topLeft = Offset(d, d),
                    size = plate,
                    cornerRadius = CornerRadius(corner),
                )
            }
        }
        FocusFrameStyle.GOLD_BEZEL -> {
            val w = 3.dp.toPx()
            val line = 1.dp.toPx().coerceAtLeast(1f)
            val brush = metalBrush(frame.gold, Offset.Zero, Offset(plate.width, plate.height))
            val half = w / 2
            drawRoundRect(
                brush,
                topLeft = Offset(-half, -half),
                size = Size(plate.width + w, plate.height + w),
                cornerRadius = CornerRadius(corner + half),
                style = Stroke(w),
                alpha = glow,
            )
            // Bevel: a dark outer edge and a bright inner one.
            ring(plate, Color.Black.copy(alpha = 0.55f * glow), w + line / 2, line, corner)
            ring(plate, lerp(frame.gold, Color.White, 0.75f).copy(alpha = 0.8f * glow), line / 2, line, corner, inside = true)
        }
    }
}

/** A rounded-rect outline [spread] px outside [plate] (inside its edge when [inside]). */
private fun DrawScope.ring(plate: Size, color: Color, spread: Float, width: Float, corner: Float, inside: Boolean = false) {
    val s = if (inside) -spread else spread
    drawRoundRect(
        color,
        topLeft = Offset(-s, -s),
        size = Size(plate.width + s * 2, plate.height + s * 2),
        cornerRadius = CornerRadius((corner + s).coerceAtLeast(0f)),
        style = Stroke(width),
    )
}

/**
 * The [FocusFrame] of [style] in [palette]: neon in the palette's accent (its glow), or in the
 * wave color when the accent is plain white, so the built-in themes' neon still has a hue; gold
 * from `palette.focusGold` ([focusGold]) when the pack sets it.
 */
fun focusFrameOf(style: FocusFrameStyle, palette: dev.xross.launcher.theme.ThemePalette, focusGold: Int?): FocusFrame {
    val accent = Color(palette.glow)
    val neon = if (accent == Color.White) Color(palette.wave) else accent
    return FocusFrame(style, neon = neon, gold = focusGold?.let(::Color) ?: Gold)
}
