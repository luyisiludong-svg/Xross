package dev.xross.launcher.ui

import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import dev.xross.launcher.theme.pack.BorderKind
import dev.xross.launcher.theme.pack.Surface
import dev.xross.launcher.theme.pack.SurfacePaint
import dev.xross.launcher.theme.pack.SurfaceShape
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Draws a card, panel or toast with the active theme surface ([ThemeSurfaces.active], from the
 * pack's `effects.surfaceStyle` or Settings ▸ Theme ▸ Surface style). The component passes its own
 * [shape] (used unless the surface sets one) and how strongly it wants the surface:
 * [strength] scales the fill and sheen, [borderAlpha] and [borderScale] the borders' opacity and
 * width (1 = as the surface defines them).
 *
 * The surface is read only in the draw phase, so switching it redraws without recomposing. Glows,
 * shadows and outer borders draw outside the shape and are clipped out under it, so they never
 * darken a translucent fill; the fill, sheen, content and inner borders are clipped to the shape.
 */
internal fun Modifier.xrossSurface(
    shape: Shape,
    strength: Float = 1f,
    borderAlpha: Float = 1f,
    borderScale: Float = 1f,
): Modifier = this
    .drawWithCache {
        val s = ThemeSurfaces.active
        val outer = s.borders.filter { it.kind == BorderKind.OUTER }
        if (s.glow == null && s.shadows.isEmpty() && outer.isEmpty()) return@drawWithCache onDrawBehind { }
        val path = Path().apply { addOutline(s.shapeFor(shape).createOutline(size, layoutDirection, this@drawWithCache)) }
        val shadowPaints = (listOfNotNull(s.glow) + s.shadows).map { sh ->
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                // A translucent shadow color sets the shadow's own alpha (an opaque one would take the paint's).
                val argb = if (sh.argb ushr 24 == 0xFF) (sh.argb and 0x00FFFFFF) or (0xFE shl 24) else sh.argb
                color = argb
                setShadowLayer(sh.blurDp.dp.toPx().coerceAtLeast(0.5f), sh.dxDp.dp.toPx(), sh.dyDp.dp.toPx(), argb)
            }
        }
        val outerBrushes = outer.map { it to it.paint.brush(size) }
        onDrawBehind {
            clipPath(path, ClipOp.Difference) {
                if (shadowPaints.isNotEmpty()) {
                    drawIntoCanvas { c -> shadowPaints.forEach { c.nativeCanvas.drawPath(path.asAndroidPath(), it) } }
                }
                for ((b, brush) in outerBrushes) {
                    drawPath(path, brush, alpha = borderAlpha.coerceIn(0f, 1f), style = Stroke(b.widthDp.dp.toPx() * borderScale * 2))
                }
            }
        }
    }
    .graphicsLayer {
        this.shape = ThemeSurfaces.active.shapeFor(shape)
        clip = true
    }
    .drawWithCache {
        val s = ThemeSurfaces.active
        val outline = s.shapeFor(shape).createOutline(size, layoutDirection, this)
        val fill = s.fill?.brush(size)
        val sheenHeight = s.sheen?.let { size.height * it.heightFraction } ?: 0f
        val sheen = s.sheen?.let { Brush.verticalGradient(it.colors.map(::Color), startY = 0f, endY = sheenHeight) }
        val inner = s.borders.filter { it.kind != BorderKind.OUTER }.map { it to it.paint.brush(size) }
        val a = borderAlpha.coerceIn(0f, 1f)
        val k = strength.coerceIn(0f, 1f)
        onDrawWithContent {
            fill?.let { drawRect(it, alpha = k) }
            sheen?.let { drawRect(it, size = Size(size.width, sheenHeight), alpha = k) }
            drawContent()
            for ((b, brush) in inner) {
                val w = b.widthDp.dp.toPx() * borderScale
                when (b.kind) {
                    // Centered on the clipped edge: the inner half shows.
                    BorderKind.INNER -> drawOutline(outline, brush, alpha = a, style = Stroke(w * 2))
                    BorderKind.TOP -> drawRect(brush, Offset.Zero, Size(size.width, w), alpha = a)
                    BorderKind.BOTTOM -> drawRect(brush, Offset(0f, size.height - w), Size(size.width, w), alpha = a)
                    BorderKind.LEFT -> drawRect(brush, Offset.Zero, Size(w, size.height), alpha = a)
                    BorderKind.RIGHT -> drawRect(brush, Offset(size.width - w, 0f), Size(w, size.height), alpha = a)
                    BorderKind.OUTER -> Unit
                }
            }
        }
    }

/** The surface's own shape, or [component] (the card's own corners) when it doesn't set one. */
internal fun Surface.shapeFor(component: Shape): Shape = when (val sh = shape) {
    SurfaceShape.Component -> component
    SurfaceShape.Rectangle -> RectangleShape
    is SurfaceShape.Rounded -> RoundedCornerShape(sh.radiusDp.dp)
    is SurfaceShape.Chamfered -> CutCornerShape(sh.cutsDp[0].dp, sh.cutsDp[1].dp, sh.cutsDp[2].dp, sh.cutsDp[3].dp)
}

/** A brush filling [size]; linear angles follow CSS (180° top to bottom, 90° left to right). */
internal fun SurfacePaint.brush(size: Size): Brush = when (this) {
    is SurfacePaint.Solid -> SolidColor(Color(argb))
    is SurfacePaint.Sweep -> Brush.sweepGradient(colors.map(::Color), center = Offset(size.width / 2, size.height / 2))
    is SurfacePaint.Linear -> {
        val rad = Math.toRadians(angleDeg.toDouble())
        val dx = sin(rad).toFloat()
        val dy = -cos(rad).toFloat()
        val half = (abs(size.width * dx) + abs(size.height * dy)) / 2
        val c = Offset(size.width / 2, size.height / 2)
        Brush.linearGradient(
            *stops.map { (pos, argb) -> pos to Color(argb) }.toTypedArray(),
            start = c - Offset(dx * half, dy * half),
            end = c + Offset(dx * half, dy * half),
        )
    }
}
