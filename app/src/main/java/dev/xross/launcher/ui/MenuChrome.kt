package dev.xross.launcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.xross.launcher.theme.pack.ClockFormat
import dev.xross.launcher.theme.pack.ClockLayout
import dev.xross.launcher.theme.pack.NineSlice
import dev.xross.launcher.theme.pack.ResolvedComponents
import dev.xross.launcher.theme.pack.Skin
import kotlin.math.tan

// The clock and hint styles (Menu styles slice 5): shared by every menu style, so they live in
// `components` and an XMB pack can use them too.

/** Slants this element's drawing by [degrees] about its middle (0: unchanged, no extra work). */
internal fun Modifier.skewX(degrees: Float): Modifier = if (degrees == 0f) this else drawWithContent {
    val k = tan(Math.toRadians(degrees.toDouble())).toFloat()
    withTransform({ transform(skewAbout(k, size.height / 2)) }) { this@drawWithContent.drawContent() }
}

/**
 * A plate behind this element: [skin] (a pack image, nine-sliced, with its outset) when it is
 * loaded, else a flat plate in [fallback] (null: nothing).
 */
internal fun Modifier.plate(skin: Skin?, fallback: Color?): Modifier = composed {
    val image = LocalMenuImages.current[skin?.path]
    val paint = LocalMenuPaint.current
    val dpx = LocalDensity.current.density
    val pieces = remember(skin, image) { HashMap<Long, List<NineSlice.Patch>>() }
    drawBehind {
        if (skin != null && image != null) {
            val k = (size.width.toLong() shl 32) or size.height.toLong()
            val p = pieces.getOrPut(k) {
                val o = skin.outset
                NineSlice.patches(image.width, image.height, skin.slice, size.width + (o.left + o.right) * dpx, size.height + (o.top + o.bottom) * dpx, dpx)
            }
            drawSkinAt(image, skin, size, dpx, paint.color(skin.tint), 1f, p)
        } else if (fallback != null) {
            drawRect(fallback)
        }
    }
}

/**
 * The status bar's clock in [c]'s style: one line at the bar's size ([ResolvedComponents.plainClock],
 * exactly the clock before slice 5), or stacked (the date over the time), larger, tilted and on a
 * plate.
 */
@Composable
internal fun StyledClock(text: String, style: TextStyle, c: ResolvedComponents) {
    if (text.isEmpty()) return
    if (c.plainClock) {
        Text(text, style = style, maxLines = 1, softWrap = false)
        return
    }
    val lines = ClockFormat.lines(text, c.clockLayout)
    val big = style.copy(fontSize = style.fontSize * c.clockSize)
    val plated = c.clockPlate != null
    val pad: Dp = with(LocalDensity.current) { (style.fontSize.toPx() * 0.45f).toDp() }
    Box(
        Modifier
            .graphicsLayer { rotationZ = c.clockTilt }
            .plate(c.clockPlate, null)
            .then(if (plated) Modifier.padding(horizontal = pad * 1.4f, vertical = pad) else Modifier),
    ) {
        Column(horizontalAlignment = Alignment.End) {
            lines.forEachIndexed { i, line ->
                // Stacked, the date sits smaller over the time.
                val s = if (lines.size > 1 && i == 0) big.copy(fontSize = big.fontSize * 0.62f) else big
                Text(line, style = s, maxLines = 1, softWrap = false)
            }
        }
    }
}

/**
 * The button hints [hints] (glyph, label) in [c]'s style: one backed pill (`PILL`, the hints
 * before slice 5), a skewed plate each (`PLATES`) or bare (`PLAIN`), slanted by `hintSkew`.
 */
@Composable
internal fun StyledHints(hints: List<Pair<String, String>>, size: androidx.compose.ui.unit.TextUnit, modifier: Modifier, c: ResolvedComponents) {
    if (hints.isEmpty()) return
    when (c.hintStyle) {
        dev.xross.launcher.theme.pack.HintStyle.PILL -> Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = modifier
                .skewX(c.hintSkew)
                .hintBar()
                .padding(horizontal = 10.dp, vertical = 4.dp)
                .graphicsLayer { alpha = 0.85f },
        ) {
            for ((g, l) in hints) Hint(g, l, size)
        }
        dev.xross.launcher.theme.pack.HintStyle.PLATES -> {
            val surface = LocalMenuPaint.current.color("surface")?.copy(alpha = 0.9f) ?: Color(0xE6000000)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier) {
                for ((g, l) in hints) {
                    Box(Modifier.skewX(c.hintSkew).plate(c.hintPlate, surface).padding(horizontal = 12.dp, vertical = 4.dp)) { Hint(g, l, size) }
                }
            }
        }
        dev.xross.launcher.theme.pack.HintStyle.PLAIN -> Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = modifier.skewX(c.hintSkew),
        ) {
            for ((g, l) in hints) Hint(g, l, size)
        }
    }
}
