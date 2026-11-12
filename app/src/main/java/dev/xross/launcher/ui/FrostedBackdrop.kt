package dev.xross.launcher.ui

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** The darkening drawn over a backdrop image, so menu text stays readable. */
enum class BackdropScrim {
    /** Focused-game hero art: dark behind the menu column and the status bar. */
    HERO,

    /** A wallpaper the user or the pack chose: lighter, so it still looks like itself. */
    WALLPAPER,
    ;

    /** Draws this scrim over [box] (the whole backdrop) at [alpha]. */
    fun draw(scope: DrawScope, box: Size, alpha: Float) = with(scope) {
        when (this@BackdropScrim) {
            HERO -> {
                drawRect(Brush.horizontalGradient(0f to Color(0xA6000000), 0.55f to Color(0x73000000), 1f to Color(0x4D000000), endX = box.width), size = box, alpha = alpha)
                drawRect(Brush.verticalGradient(0f to Color(0x59000000), 0.25f to Color.Transparent, endY = box.height), size = box, alpha = alpha)
            }
            WALLPAPER -> {
                drawRect(Brush.horizontalGradient(0f to Color(0x73000000), 0.55f to Color(0x40000000), 1f to Color(0x26000000), endX = box.width), size = box, alpha = alpha)
                drawRect(Brush.verticalGradient(0f to Color(0x40000000), 0.2f to Color.Transparent, endY = box.height), size = box, alpha = alpha)
            }
        }
    }
}

/** A pre-blurred backdrop image a frosted surface draws its slice of. */
class FrostLayer(
    val image: ImageBitmap,
    /** The backdrop's on-screen opacity (its fade), read while drawing. */
    val alpha: () -> Float,
    val scrim: BackdropScrim,
    /** The backdrop image has no transparent pixels, so layers below it needn't be drawn. */
    val opaque: Boolean,
)

/**
 * The backdrop images on screen, for frosted surfaces (2b.4c): the wallpaper (layer 3) and the
 * focused game's hero art above it, each as a pre-blurred [FrostLayer]. Surfaces read these in
 * the draw phase, so a backdrop appearing or fading redraws them without recomposing.
 *
 * Empty over the live shader: a surface then keeps its translucent tint alone. [suppressed]
 * while something that isn't a still image covers the backdrops (a video snap, a media screen).
 */
object FrostedBackdrop {
    var wallpaper: FrostLayer? by mutableStateOf(null)
    var hero: FrostLayer? by mutableStateOf(null)
    var suppressed: Boolean by mutableStateOf(false)

    /** The box the backdrops fill (the home screen's root), where slices are measured from. */
    internal var host: LayoutCoordinates? = null

    /** Layers to draw, bottom first, from the topmost fully opaque one. */
    fun layers(): List<FrostLayer> {
        if (suppressed) return emptyList()
        val all = listOfNotNull(wallpaper, hero)
        val top = all.indexOfLast { it.opaque && it.alpha() >= 0.999f }
        return if (top <= 0) all else all.subList(top, all.size)
    }

    private val cache = PreBlurCache<ImageBitmap>()

    private val ids = java.util.WeakHashMap<ImageBitmap, Long>()
    private var nextId = 0L

    /**
     * A stable name for a decoded [image] while it lives: the art loader hands back the same
     * instance for the same art, so revisiting a game reuses its blurred copy.
     */
    fun idOf(image: ImageBitmap): Long = synchronized(ids) { ids.getOrPut(image) { ++nextId } }

    /**
     * [image]'s pre-blurred copy ([PreBlur.SMALL_WIDTH] wide, box [radius]), from the cache or
     * made on [Dispatchers.Default]. [key] names the image (it changes when the image does).
     */
    suspend fun blurred(key: String, image: ImageBitmap, radius: Int): ImageBitmap? =
        cache.getOrPut(key, radius) {
            withContext(Dispatchers.Default) {
                runCatching {
                    val src = image.asAndroidBitmap()
                    val (w, h) = PreBlur.smallSize(src.width, src.height)
                    if (w <= 0) return@runCatching null
                    val small = Bitmap.createScaledBitmap(src, w, h, true)
                    val px = IntArray(w * h)
                    small.getPixels(px, 0, w, 0, 0, w, h)
                    if (small !== src) small.recycle()
                    Bitmap.createBitmap(PreBlur.blur(px, w, h, radius), w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
                }.getOrNull()
            }
        }

    /** Drops the cached copies (low memory, or the UI is hidden). */
    fun trim() = cache.clear()
}

/**
 * Publishes [image] (named [key], drawn with `ContentScale.Crop` into [viewport]) in [slot] of
 * [FrostedBackdrop] while this is composed, blurred for the active surface's `backdropBlurDp`.
 * Nothing is blurred while the active surface has no blur.
 */
@Composable
internal fun FrostLayerEffect(
    slot: FrostSlot,
    key: String,
    image: ImageBitmap,
    viewport: Size,
    opaque: Boolean,
    scrim: BackdropScrim,
    alpha: () -> Float,
) {
    val blurPx = ThemeSurfaces.active.backdropBlurDp * LocalDensity.current.density
    val currentAlpha by rememberUpdatedState(alpha)
    var blurred by remember(key) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(key, image, blurPx, viewport) {
        val drawnWidth = PreBlur.cropRect(image.width.toFloat(), image.height.toFloat(), viewport.width, viewport.height)[2]
        val radius = PreBlur.radiusFor(blurPx, drawnWidth, PreBlur.smallSize(image.width, image.height).first)
        val start = System.nanoTime()
        blurred = if (radius <= 0) null else FrostedBackdrop.blurred(key, image, radius)
        // Debug and profiling builds: what was blurred, how much, and how long it took (cache hits ~0).
        if (dev.xross.launcher.BuildConfig.PERF_LOG) {
            android.util.Log.i(
                "XrossFrost",
                "$slot radius=$radius copy=${blurred?.let { "${it.width}x${it.height}" } ?: "none"} in %.1f ms".format((System.nanoTime() - start) / 1e6),
            )
        }
    }
    val layer = remember(blurred, opaque, scrim) { blurred?.let { FrostLayer(it, { currentAlpha() }, scrim, opaque) } }
    DisposableEffect(slot, layer) {
        slot.set(layer)
        onDispose { if (slot.get() === layer) slot.set(null) }
    }
}

internal enum class FrostSlot {
    WALLPAPER,
    HERO,
    ;

    fun get(): FrostLayer? = if (this == WALLPAPER) FrostedBackdrop.wallpaper else FrostedBackdrop.hero

    fun set(layer: FrostLayer?) {
        if (this == WALLPAPER) FrostedBackdrop.wallpaper = layer else FrostedBackdrop.hero = layer
    }
}

/** Marks the box the backdrops fill, so frosted surfaces can find their slice. */
internal fun Modifier.frostHost(): Modifier = this then FrostHostElement

private data object FrostHostElement : ModifierNodeElement<FrostHostNode>() {
    override fun create() = FrostHostNode()
    override fun update(node: FrostHostNode) = Unit
}

private class FrostHostNode : Modifier.Node(), GlobalPositionAwareModifierNode {
    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        FrostedBackdrop.host = coordinates
    }

    override fun onDetach() {
        if (FrostedBackdrop.host?.isAttached != true) FrostedBackdrop.host = null
    }
}

/**
 * Draws the surface's slice of the pre-blurred backdrops under its fill (`backdropBlurDp` > 0
 * and a backdrop image on screen), at [strength]. Must sit inside the surface's clip; skipped
 * when [clip] is off. Repositions redraw it, so a moving card keeps its slice aligned.
 */
internal fun Modifier.frostedSlice(strength: () -> Float, clip: () -> Boolean): Modifier =
    this then FrostedSliceElement(strength, clip)

private data class FrostedSliceElement(val strength: () -> Float, val clip: () -> Boolean) : ModifierNodeElement<FrostedSliceNode>() {
    override fun create() = FrostedSliceNode(strength, clip)
    override fun update(node: FrostedSliceNode) {
        node.strength = strength
        node.clip = clip
    }
}

private class FrostedSliceNode(var strength: () -> Float, var clip: () -> Boolean) :
    Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode {
    private var coordinates: LayoutCoordinates? = null
    private var lastOffset = Offset.Unspecified

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        this.coordinates = coordinates
        if (ThemeSurfaces.active.backdropBlurDp <= 0f) return
        val host = FrostedBackdrop.host ?: return
        val offset = runCatching { host.localPositionOf(coordinates, Offset.Zero) }.getOrNull() ?: return
        if (offset != lastOffset) {
            lastOffset = offset
            invalidateDraw()
        }
    }

    override fun ContentDrawScope.draw() {
        drawSlice()
        drawContent()
    }

    private fun ContentDrawScope.drawSlice() {
        if (ThemeSurfaces.active.backdropBlurDp <= 0f || !clip()) return
        val layers = FrostedBackdrop.layers()
        if (layers.isEmpty()) return
        val k = strength().coerceIn(0f, 1f)
        if (k <= 0f) return
        val host = FrostedBackdrop.host?.takeIf { it.isAttached } ?: return
        val me = coordinates?.takeIf { it.isAttached } ?: return
        val offset = runCatching { host.localPositionOf(me, Offset.Zero) }.getOrNull() ?: return
        lastOffset = offset
        val box = Size(host.size.width.toFloat(), host.size.height.toFloat())
        clipRect {
            translate(-offset.x, -offset.y) {
                for (layer in layers) {
                    val a = (layer.alpha() * k).coerceIn(0f, 1f)
                    if (a <= 0f) continue
                    val r = PreBlur.cropRect(layer.image.width.toFloat(), layer.image.height.toFloat(), box.width, box.height)
                    drawImage(
                        layer.image,
                        dstOffset = IntOffset(r[0].roundToInt(), r[1].roundToInt()),
                        dstSize = IntSize(r[2].roundToInt(), r[3].roundToInt()),
                        alpha = a,
                        filterQuality = FilterQuality.Low,
                    )
                    layer.scrim.draw(this, box, a)
                }
            }
        }
    }
}
