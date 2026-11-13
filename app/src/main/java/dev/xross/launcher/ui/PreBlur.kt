package dev.xross.launcher.ui

import dev.xross.launcher.theme.LruCache
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Frosted surfaces without a live blur (2b.4c). Hero art and wallpapers are static images, so each
 * is blurred once, small ([SMALL_WIDTH] px wide) and off the main thread, when it is shown. A
 * surface with `backdropBlurDp` then draws its aligned, clipped slice of that bitmap, scaled up
 * with bilinear filtering: no per-frame blur and no `RenderEffect`, so it works on Android 9 too.
 *
 * Pure functions over ARGB `IntArray`s, so the math is tested on the JVM.
 */
object PreBlur {
    /** Width of the pre-blurred copy (its height follows the image's aspect). */
    const val SMALL_WIDTH = 192

    /** Largest box radius, in small pixels (about a quarter of the copy's width). */
    const val MAX_RADIUS = 48

    /** Box passes: three approximate a Gaussian whose sigma is about the box radius. */
    const val PASSES = 3

    /** The pre-blurred copy's size for a [w]×[h] image: [SMALL_WIDTH] wide (never upscaled). */
    fun smallSize(w: Int, h: Int, width: Int = SMALL_WIDTH): Pair<Int, Int> {
        if (w <= 0 || h <= 0) return 0 to 0
        val sw = minOf(w, width)
        return sw to max(1, (h.toLong() * sw / w).toInt())
    }

    /**
     * Box radius in small pixels for a blur of [blurPx] screen pixels (the surface's
     * `backdropBlurDp`, as a Gaussian sigma), when the [smallWidth]-wide copy is drawn
     * [drawnWidthPx] wide on screen. 0 for no blur; at least 1 otherwise.
     */
    fun radiusFor(blurPx: Float, drawnWidthPx: Float, smallWidth: Int): Int {
        if (blurPx <= 0f || drawnWidthPx <= 0f || smallWidth <= 0) return 0
        return (blurPx * smallWidth / drawnWidthPx).roundToInt().coerceIn(1, MAX_RADIUS)
    }

    /**
     * Where an [imageW]×[imageH] image lands when drawn with `ContentScale.Crop`, centered, into
     * a [boxW]×[boxH] box: (left, top, width, height), in box pixels. Both the backdrop and every
     * surface's slice use this, so the slice lines up with the image behind it.
     */
    fun cropRect(imageW: Float, imageH: Float, boxW: Float, boxH: Float): FloatArray {
        if (imageW <= 0f || imageH <= 0f) return floatArrayOf(0f, 0f, boxW, boxH)
        val scale = max(boxW / imageW, boxH / imageH)
        val w = imageW * scale
        val h = imageH * scale
        return floatArrayOf((boxW - w) / 2, (boxH - h) / 2, w, h)
    }

    /** True when every pixel is fully opaque (the backdrop can stop the shader under it). */
    fun isOpaque(argb: IntArray): Boolean = argb.all { it ushr 24 == 0xFF }

    /**
     * [argb] ([w]×[h], non-premultiplied, row-major) blurred with [PASSES] box passes of
     * [radius] each way. Edges clamp; alpha is blurred premultiplied, so transparent pixels
     * don't darken their neighbors. Returns a new array; [radius] 0 returns a copy.
     */
    fun blur(argb: IntArray, w: Int, h: Int, radius: Int, passes: Int = PASSES): IntArray {
        require(argb.size >= w * h) { "need $w×$h pixels, got ${argb.size}" }
        val n = w * h
        if (radius <= 0 || n == 0) return argb.copyOf(n)
        val a = FloatArray(n)
        val r = FloatArray(n)
        val g = FloatArray(n)
        val b = FloatArray(n)
        for (i in 0 until n) {
            val p = argb[i]
            val alpha = (p ushr 24) / 255f
            a[i] = alpha
            r[i] = ((p shr 16) and 0xFF) * alpha
            g[i] = ((p shr 8) and 0xFF) * alpha
            b[i] = (p and 0xFF) * alpha
        }
        val tmp = FloatArray(max(w, h))
        repeat(passes) {
            for (ch in arrayOf(a, r, g, b)) {
                for (y in 0 until h) boxLine(ch, y * w, 1, w, radius, tmp)
                for (x in 0 until w) boxLine(ch, x, w, h, radius, tmp)
            }
        }
        val out = IntArray(n)
        for (i in 0 until n) {
            val alpha = a[i]
            if (alpha <= 1f / 512) continue
            val ai = (alpha * 255f).roundToInt().coerceIn(0, 255)
            val ri = (r[i] / alpha).roundToInt().coerceIn(0, 255)
            val gi = (g[i] / alpha).roundToInt().coerceIn(0, 255)
            val bi = (b[i] / alpha).roundToInt().coerceIn(0, 255)
            out[i] = (ai shl 24) or (ri shl 16) or (gi shl 8) or bi
        }
        return out
    }

    /** One box pass over [len] values of [data] from [start], [stride] apart, edges clamped. */
    private fun boxLine(data: FloatArray, start: Int, stride: Int, len: Int, radius: Int, tmp: FloatArray) {
        val window = 2 * radius + 1
        fun at(i: Int) = data[start + i.coerceIn(0, len - 1) * stride]
        var sum = 0f
        for (i in -radius..radius) sum += at(i)
        for (i in 0 until len) {
            tmp[i] = sum / window
            sum += at(i + radius + 1) - at(i - radius)
        }
        for (i in 0 until len) data[start + i * stride] = tmp[i]
    }
}

/**
 * Pre-blurred copies by (image key, radius), least recently used evicted past [maxEntries]. A
 * copy is ~150 KB, so the default keeps a few wallpapers and the last heroes. Thread-safe;
 * generic so eviction is tested without bitmaps.
 */
class PreBlurCache<V : Any>(private val maxEntries: Int = MAX_ENTRIES) {
    data class Key(val image: String, val radius: Int)

    private val map = LruCache<Key, V>(maxEntries)

    val size: Int get() = synchronized(map) { map.size }

    operator fun get(image: String, radius: Int): V? = synchronized(map) { map[Key(image, radius)] }

    fun put(image: String, radius: Int, value: V) = synchronized(map) { map[Key(image, radius)] = value }

    fun contains(image: String, radius: Int): Boolean = synchronized(map) { map.containsKey(Key(image, radius)) }

    /** The cached copy, or [make]'s (cached unless null). [make] runs outside the lock. */
    suspend fun getOrPut(image: String, radius: Int, make: suspend () -> V?): V? {
        get(image, radius)?.let { return it }
        return make()?.also { put(image, radius, it) }
    }

    /** Drops every copy of [image] (its file changed). */
    fun evict(image: String) = synchronized(map) { map.keys.removeAll { it.image == image } }

    fun clear() = synchronized(map) { map.clear() }

    companion object {
        const val MAX_ENTRIES = 8
    }
}
