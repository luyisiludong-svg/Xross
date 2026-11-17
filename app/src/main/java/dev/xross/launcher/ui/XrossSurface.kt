package dev.xross.launcher.ui

import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawModifierNode
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.DrawResult
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
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.xross.launcher.theme.pack.BorderKind
import dev.xross.launcher.theme.pack.Surface
import dev.xross.launcher.theme.pack.SurfacePaint
import dev.xross.launcher.theme.pack.SurfaceShape
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import dev.xross.launcher.theme.pack.withOpacity

/**
 * Draws a card, panel or toast with the active theme surface ([ThemeSurfaces.active] of the root's
 * [LocalThemeSurfaces], from the
 * pack's `effects.surfaceStyle` or Settings ▸ Theme ▸ Surface style). The component passes its own
 * [shape] (used unless the surface sets one) and how strongly it wants the surface:
 * [strength] scales the fill and sheen, [borderAlpha] and [borderScale] the borders' opacity and
 * width (1 = as the surface defines them).
 *
 * A surface with `backdropBlurDp` draws its slice of the pre-blurred hero art or wallpaper behind
 * it under the fill ([frostedSlice], 2b.4c); over the live shader it keeps the tint alone.
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
): Modifier = xrossSurface(shape = { it.shapeFor(shape) }, strength = { strength }, borderAlpha = { borderAlpha }, borderScale = borderScale)

/** The backing of a row of button hints: a light pill of the theme surface. */
internal fun Modifier.hintBar(): Modifier =
    xrossSurface(RoundedCornerShape(50), strength = 0.55f, borderAlpha = 0.45f, borderScale = 2f / 3f)

/**
 * [xrossSurface] for a component that animates it: [shape] picks the outline from the active
 * surface (the avatar HUD morphs from a circle into its capsule), [strength] and [borderAlpha] are
 * read while drawing, and [clip] turns the clip off (the collapsed avatar's ring isn't clipped).
 * The shape and [shadowAlpha] are read when the drawing cache is built, so it rebuilds as they change;
 * a component whose size changes with them rebuilds it then anyway.
 */
internal fun Modifier.xrossSurface(
    shape: (Surface) -> Shape,
    strength: () -> Float,
    borderAlpha: () -> Float = strength,
    borderScale: Float = 1f,
    clip: () -> Boolean = { true },
    /** Scales the glow and shadows; the HUD fades them in with its morph. */
    shadowAlpha: () -> Float = { 1f },
): Modifier = this
    .then(SurfaceGlowElement(shape, borderAlpha, borderScale, shadowAlpha))
    .then(SurfaceClipElement(shape, clip))
    // Frosted glass (backdropBlurDp): the pre-blurred slice of the art or wallpaper behind, under the fill.
    .frostedSlice(strength, clip)
    .then(SurfaceFillElement(shape, strength, borderAlpha, borderScale))

/**
 * The surface's glow, shadows and outer borders, outside the shape. Its drawing cache is built from
 * the root's [ThemeSurfaces] (read through [LocalThemeSurfaces]) and rebuilt when the surface or
 * anything else it read changes.
 */
private data class SurfaceGlowElement(
    val shape: (Surface) -> Shape,
    val borderAlpha: () -> Float,
    val borderScale: Float,
    val shadowAlpha: () -> Float,
) : ModifierNodeElement<SurfaceGlowNode>() {
    override fun create() = SurfaceGlowNode(shape, borderAlpha, borderScale, shadowAlpha)
    override fun update(node: SurfaceGlowNode) = node.update(shape, borderAlpha, borderScale, shadowAlpha)
}

private class SurfaceGlowNode(
    var shape: (Surface) -> Shape,
    var borderAlpha: () -> Float,
    var borderScale: Float,
    var shadowAlpha: () -> Float,
) : DelegatingNode(), CompositionLocalConsumerModifierNode {
    private val cache = delegate(CacheDrawModifierNode { build() })

    fun update(shape: (Surface) -> Shape, borderAlpha: () -> Float, borderScale: Float, shadowAlpha: () -> Float) {
        this.shape = shape
        this.borderAlpha = borderAlpha
        this.borderScale = borderScale
        this.shadowAlpha = shadowAlpha
        cache.invalidateDrawCache()
    }

    private fun CacheDrawScope.build(): DrawResult {
        val s = currentValueOf(LocalThemeSurfaces).active
        val k = shadowAlpha().coerceIn(0f, 1f)
        val outer = s.borders.filter { it.kind == BorderKind.OUTER }
        if (k <= 0f || (s.glow == null && s.shadows.isEmpty() && outer.isEmpty())) return onDrawBehind { }
        val path = Path().apply { addOutline(shape(s).createOutline(size, layoutDirection, this@build)) }
        val shadowPaints = (listOfNotNull(s.glow) + s.shadows).map { sh ->
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                // A translucent shadow color sets the shadow's own alpha (an opaque one would take the paint's).
                val faded = withOpacity(sh.argb, k)
                val argb = if (faded ushr 24 == 0xFF) (faded and 0x00FFFFFF) or (0xFE shl 24) else faded
                color = argb
                setShadowLayer(sh.blurDp.dp.toPx().coerceAtLeast(0.5f), sh.dxDp.dp.toPx(), sh.dyDp.dp.toPx(), argb)
            }
        }
        val outerBrushes = outer.map { it to it.paint.brush(size) }
        val scale = borderScale
        val alpha = borderAlpha
        return onDrawBehind {
            clipPath(path, ClipOp.Difference) {
                if (shadowPaints.isNotEmpty()) {
                    drawIntoCanvas { c -> shadowPaints.forEach { c.nativeCanvas.drawPath(path.asAndroidPath(), it) } }
                }
                val a = alpha().coerceIn(0f, 1f)
                for ((b, brush) in outerBrushes) {
                    drawPath(path, brush, alpha = a, style = Stroke(b.widthDp.dp.toPx() * scale * 2))
                }
            }
        }
    }
}

/**
 * The layer that clips the fill, the frosted slice and the content to the surface's shape: what
 * `Modifier.graphicsLayer { shape = …; clip = … }` does, with the shape taken from the root's
 * [ThemeSurfaces]. The layer block's reads are observed, so a new surface or [clip] updates it.
 */
private data class SurfaceClipElement(
    val shape: (Surface) -> Shape,
    val clip: () -> Boolean,
) : ModifierNodeElement<SurfaceClipNode>() {
    override fun create() = SurfaceClipNode(shape, clip)
    override fun update(node: SurfaceClipNode) = node.update(shape, clip)
}

private class SurfaceClipNode(
    private var shapeOf: (Surface) -> Shape,
    private var clipOn: () -> Boolean,
) : Modifier.Node(), LayoutModifierNode, CompositionLocalConsumerModifierNode {
    private var layerBlock = block()

    private fun block(): GraphicsLayerScope.() -> Unit {
        val shapeOf = shapeOf
        val clipOn = clipOn
        return {
            shape = shapeOf(currentValueOf(LocalThemeSurfaces).active)
            clip = clipOn()
        }
    }

    fun update(shape: (Surface) -> Shape, clip: () -> Boolean) {
        shapeOf = shape
        clipOn = clip
        // A new block makes the next placement apply it; the update itself re-places the node.
        layerBlock = block()
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) { placeable.placeWithLayer(0, 0, layerBlock = layerBlock) }
    }
}

/** The surface's fill, sheen and inner borders, inside the clip; the content draws between them. */
private data class SurfaceFillElement(
    val shape: (Surface) -> Shape,
    val strength: () -> Float,
    val borderAlpha: () -> Float,
    val borderScale: Float,
) : ModifierNodeElement<SurfaceFillNode>() {
    override fun create() = SurfaceFillNode(shape, strength, borderAlpha, borderScale)
    override fun update(node: SurfaceFillNode) = node.update(shape, strength, borderAlpha, borderScale)
}

private class SurfaceFillNode(
    var shape: (Surface) -> Shape,
    var strength: () -> Float,
    var borderAlpha: () -> Float,
    var borderScale: Float,
) : DelegatingNode(), CompositionLocalConsumerModifierNode {
    private val cache = delegate(CacheDrawModifierNode { build() })

    fun update(shape: (Surface) -> Shape, strength: () -> Float, borderAlpha: () -> Float, borderScale: Float) {
        this.shape = shape
        this.strength = strength
        this.borderAlpha = borderAlpha
        this.borderScale = borderScale
        cache.invalidateDrawCache()
    }

    private fun CacheDrawScope.build(): DrawResult {
        val s = currentValueOf(LocalThemeSurfaces).active
        val outline = shape(s).createOutline(size, layoutDirection, this)
        val fill = s.fill?.brush(size)
        val sheenHeight = s.sheen?.let { size.height * it.heightFraction } ?: 0f
        val sheen = s.sheen?.let { Brush.verticalGradient(it.colors.map(::Color), startY = 0f, endY = sheenHeight) }
        val inner = s.borders.filter { it.kind != BorderKind.OUTER }.map { it to it.paint.brush(size) }
        val strength = strength
        val borderAlpha = borderAlpha
        val scale = borderScale
        return onDrawWithContent {
            val k = strength().coerceIn(0f, 1f)
            val a = borderAlpha().coerceIn(0f, 1f)
            if (k > 0f) {
                fill?.let { drawRect(it, alpha = k) }
                sheen?.let { drawRect(it, size = Size(size.width, sheenHeight), alpha = k) }
            }
            drawContent()
            if (a <= 0f) return@onDrawWithContent
            for ((b, brush) in inner) {
                val w = b.widthDp.dp.toPx() * scale
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
