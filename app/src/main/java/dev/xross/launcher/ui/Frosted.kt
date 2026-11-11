package dev.xross.launcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import java.text.NumberFormat

/** Pastel accents from the reference designs: lilac, sky, mint, peach, rose. */
internal object Pastel {
    val Lilac = Color(0xFFC9B8FF)
    val Sky = Color(0xFF9FD8FF)
    val Mint = Color(0xFFA8F0D4)
    val Peach = Color(0xFFFFD3A8)
    val Rose = Color(0xFFFFB3D9)

    /** Trophy / points amber, as in the RetroAchievements panels. */
    val Amber = Color(0xFFFFB547)
    val AmberDeep = Color(0xFFFF8A1F)

    /** Closed loop for sweep gradients, so the seam is invisible. */
    val Iridescent = listOf(Lilac, Sky, Mint, Peach, Rose, Lilac)
}

/**
 * Frosted glass: a translucent cool tint with a top sheen and a hairline inner highlight, over a
 * deeper shade at the bottom. Compose can't blur what is behind a view, so the "frost" is a
 * layered tint that reads the same over the wave, hero art and video without hiding them.
 */
internal fun Modifier.frostedGlass(shape: Shape, strength: Float = 1f): Modifier = this
    .clip(shape)
    .background(
        Brush.verticalGradient(
            0f to Color(0xFF2A2A40).copy(alpha = 0.62f * strength),
            0.55f to Color(0xFF16161F).copy(alpha = 0.74f * strength),
            1f to Color(0xFF0A0A12).copy(alpha = 0.84f * strength),
        ),
    )
    .drawWithContent {
        // Sheen across the top third.
        drawRect(
            Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.10f * strength),
                0.35f to Color.White.copy(alpha = 0.02f * strength),
                0.36f to Color.Transparent,
            ),
        )
        drawContent()
    }

/** Soft iridescent pastel border (a sweep gradient around the shape). */
internal fun Modifier.iridescentBorder(shape: Shape, width: Dp = 1.5.dp, alpha: Float = 0.85f): Modifier =
    border(width, Brush.sweepGradient(Pastel.Iridescent.map { it.copy(alpha = alpha) }), shape)

/** Rounded progress bar: a dim track with an amber fill and a light top edge. */
@Composable
internal fun ProgressBar(fraction: Float, modifier: Modifier = Modifier, height: Dp = 8.dp, fill: Brush? = null) {
    val f = fraction.coerceIn(0f, 1f)
    Canvas(modifier.fillMaxWidth().height(height)) {
        val r = CornerRadius(size.height / 2, size.height / 2)
        drawRoundRect(Color.White.copy(alpha = 0.16f), cornerRadius = r)
        drawRoundRect(Color.Black.copy(alpha = 0.25f), cornerRadius = r, style = Stroke(1f))
        if (f > 0f) {
            val w = (size.width * f).coerceAtLeast(size.height)
            drawRoundRect(
                fill ?: Brush.horizontalGradient(listOf(Pastel.AmberDeep, Pastel.Amber)),
                size = size.copy(width = w),
                cornerRadius = r,
            )
            drawRoundRect(
                Color.White.copy(alpha = 0.35f),
                topLeft = Offset(size.height * 0.4f, size.height * 0.18f),
                size = size.copy(width = (w - size.height * 0.8f).coerceAtLeast(0f), height = size.height * 0.18f),
                cornerRadius = r,
            )
        }
    }
}

/** "41 / 147" with the earned count large and amber, the total small: the trophy-count style. */
@Composable
internal fun TrophyCount(earned: Int, total: Int, size: TextUnit, modifier: Modifier = Modifier) {
    val n = NumberFormat.getIntegerInstance()
    Text(
        buildAnnotatedCount(n.format(earned), n.format(total), size),
        style = shadowed(size, color = Pastel.Amber, weight = FontWeight.Bold),
        modifier = modifier,
        textAlign = TextAlign.End,
    )
}

private fun buildAnnotatedCount(earned: String, total: String, size: TextUnit) =
    androidx.compose.ui.text.buildAnnotatedString {
        pushStyle(androidx.compose.ui.text.SpanStyle(color = Pastel.Amber, fontWeight = FontWeight.Bold, fontSize = size * 1.15f))
        append(earned)
        pop()
        pushStyle(androidx.compose.ui.text.SpanStyle(color = Color.White.copy(alpha = 0.85f), fontWeight = FontWeight.Normal, fontSize = size * 0.8f))
        append(" / $total")
        pop()
    }

/** A small rounded label ("GBA", "HARDCORE", "5 pts"). */
@Composable
internal fun Pill(text: String, size: TextUnit, modifier: Modifier = Modifier, color: Color = Color.White, background: Color = Color(0x33FFFFFF)) {
    Box(
        modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
            .background(background)
            .border(1.dp, color.copy(alpha = 0.35f), androidx.compose.foundation.shape.RoundedCornerShape(50)),
    ) {
        Text(
            text,
            style = shadowed(size, color = color, weight = FontWeight.Bold),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
        )
    }
}
