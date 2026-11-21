package dev.xross.launcher.ui

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import dev.xross.launcher.data.WallpaperFit
import dev.xross.launcher.theme.WallpaperSource
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import dev.xross.launcher.theme.LruCache

/** A decoded wallpaper; [opaque] when it has no transparent pixels (the shader can rest under it). */
@Immutable
class Wallpaper(val key: String, val image: ImageBitmap, val opaque: Boolean)

/**
 * Decodes wallpapers ([WallpaperSource]) on [Dispatchers.IO], to about the screen's size,
 * honoring EXIF rotation. Keeps the last two, so switching between a pack's wallpaper and the
 * user's, or a hot reload of other files, decodes nothing.
 */
class WallpaperLoader(private val resolver: ContentResolver, private val xrossRoot: () -> File) {
    private val decoded = LruCache<String, Wallpaper>(2)

    /** [source] decoded to about [maxPx] on its longer side; null when it can't be read. */
    suspend fun load(source: WallpaperSource, maxPx: Int): Wallpaper? {
        val key = "${source.key}@$maxPx"
        synchronized(decoded) { decoded[key] }?.let { return it }
        return withContext(Dispatchers.IO) {
            val bitmap = runCatching { decode(source, maxPx) }
                .onFailure { android.util.Log.w("XrossWallpaper", "Can't read ${source.key}: ${it.message}") }
                .getOrNull() ?: return@withContext null
            Wallpaper(source.key, bitmap.asImageBitmap(), opaque = !bitmap.hasAlpha() || opaque(bitmap))
        }?.also { synchronized(decoded) { decoded[key] = it } }
    }

    fun trim() = synchronized(decoded) { decoded.clear() }

    private fun decode(source: WallpaperSource, maxPx: Int): Bitmap {
        val src = when (source) {
            is WallpaperSource.PackFile -> ImageDecoder.createSource(source.file)
            is WallpaperSource.Custom -> if (source.isUri) {
                ImageDecoder.createSource(resolver, Uri.parse(source.location))
            } else {
                val f = File(source.location).let { if (it.isAbsolute) it else File(xrossRoot(), source.location) }
                ImageDecoder.createSource(f)
            }
        }
        return ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            // Software pixels: the pre-blur reads them. Sampled down by powers of two to ~maxPx.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            var sample = 1
            while (maxOf(info.size.width, info.size.height) / (sample * 2) >= maxPx) sample *= 2
            decoder.setTargetSampleSize(sample)
        }
    }

    /** Every pixel's alpha is 255 (a PNG with an alpha channel it doesn't use). */
    private fun opaque(bitmap: Bitmap): Boolean {
        val row = IntArray(bitmap.width)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            if (!PreBlur.isOpaque(row)) return false
        }
        return true
    }
}

/**
 * The wallpaper (layer 3): full-bleed behind the menu, over the background shader and under the
 * focused game's hero art. [source] null hides it; [shown] false fades it out (the wave editor
 * needs to see the wave). Reports through [onCovering] whether it is opaque and fully faded in,
 * so the shader underneath can stop rendering.
 */
@Composable
internal fun WallpaperBackdrop(
    source: WallpaperSource?,
    /** Shown when [source] can't be read (the photo set as Xross wallpaper was deleted): the pack's. */
    fallback: WallpaperSource?,
    shown: Boolean,
    maxPx: Int,
    viewport: Size,
    load: suspend (WallpaperSource, Int) -> Wallpaper?,
    onCovering: (Boolean) -> Unit,
    /** How a picture the user chose is placed; the pack's wallpaper (and the fallback) always fills. */
    fit: WallpaperFit = WallpaperFit.FILL,
) {
    var wallpaper by remember { mutableStateOf<Wallpaper?>(null) }
    var visible by remember { mutableStateOf<Wallpaper?>(null) }
    LaunchedEffect(source, fallback, maxPx) {
        wallpaper = source?.let { load(it, maxPx) } ?: fallback?.takeIf { it != source }?.let { load(it, maxPx) }
        // Debug and profiling builds: the device suites read this (the image isn't in the UI tree).
        if (dev.xross.launcher.BuildConfig.PERF_LOG) {
            val w = wallpaper
            val kind = when {
                source == null -> "none"
                w?.key == fallback?.key && source != fallback -> "pack (custom unreadable)"
                source is WallpaperSource.Custom -> "custom"
                else -> "pack"
            }
            android.util.Log.i(
                "XrossWallpaper",
                if (w == null) "wallpaper=$kind${if (source != null) " unreadable" else ""}"
                else "wallpaper=$kind ${w.image.width}x${w.image.height} opaque=${w.opaque}",
            )
        }
    }
    val target = wallpaper.takeIf { shown }
    // One picture replacing another (a rotating folder, § 5b, or a new pick): the old one stays
    // underneath while the new one fades in over it, already decoded and blurred.
    var outgoing by remember { mutableStateOf<Wallpaper?>(null) }
    val cross = remember { androidx.compose.animation.core.Animatable(1f) }
    val before = visible
    if (target != null && before != null && before.key != target.key && outgoing == null) outgoing = before
    LaunchedEffect(target?.key) {
        if (outgoing != null && target != null) {
            cross.snapTo(0f)
            cross.animateTo(1f, tween(CROSSFADE_MS))
        }
        outgoing = null
    }
    // Keep the last image while it fades out.
    target?.let { visible = it }
    val alpha by animateFloatAsState(
        targetValue = if (target != null) 1f else 0f,
        animationSpec = tween(if (target != null) WALLPAPER_FADE_MS else BackdropTiming.HERO_FADE_OUT_MS),
        label = "wallpaper",
    )
    val report by rememberUpdatedState(onCovering)
    fun fitOf(w: Wallpaper) = if (source is WallpaperSource.Custom && w.key == source.key) fit else WallpaperFit.FILL
    fun coversScreen(w: Wallpaper) = w.opaque && fitOf(w).covers(w.image.width.toFloat(), w.image.height.toFloat(), viewport.width, viewport.height)
    val covering = target != null && coversScreen(target) && alpha >= 0.999f
    LaunchedEffect(covering) { report(covering) }

    val w = visible ?: return
    if (alpha <= 0f) return
    val wFit = fitOf(w)
    FrostLayerEffect(FrostSlot.WALLPAPER, "wallpaper:${w.key}", w.image, viewport, coversScreen(w), BackdropScrim.WALLPAPER, wFit) { alpha }
    outgoing?.takeIf { it.key != w.key }?.let { old ->
        Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }) {
            Image(old.image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
    Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha * (if (outgoing != null) cross.value else 1f) }) {
        if (wFit == WallpaperFit.FILL) {
            Image(w.image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Box(
                Modifier.fillMaxSize().drawBehind {
                    val r = wFit.place(w.image.width.toFloat(), w.image.height.toFloat(), size.width, size.height)
                    drawImage(
                        w.image,
                        dstOffset = androidx.compose.ui.unit.IntOffset(r[0].roundToInt(), r[1].roundToInt()),
                        dstSize = androidx.compose.ui.unit.IntSize(r[2].roundToInt(), r[3].roundToInt()),
                    )
                },
            )
        }
        Box(Modifier.fillMaxSize().drawBehind { BackdropScrim.WALLPAPER.draw(this, size, 1f) })
    }
}

/** One picture into the next (a rotating folder, 0.2.2.0 § 5b). */
private const val CROSSFADE_MS = 600

/** Wallpaper fade-in, on a theme switch or Set as Xross wallpaper. */
private const val WALLPAPER_FADE_MS = 500
