package dev.xross.launcher.ui

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.util.Log
import dev.xross.launcher.theme.shader.BoundImage
import dev.xross.launcher.theme.shader.ImageSize
import dev.xross.launcher.theme.shader.ShaderImage
import dev.xross.launcher.theme.shader.ShaderImagePlan
import dev.xross.launcher.theme.shader.ShaderImageSource
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** One slot's pixels, ready for `glTexImage2D(RGBA8)`: straight alpha (premultiplied for a scene's layers), the bottom row first. */
class DecodedImage(val image: ShaderImage, val width: Int, val height: Int, val pixels: ByteBuffer)

/** A program's slots as decoded (null: empty), and what went wrong, for a notice. */
class DecodedImages(val slots: List<DecodedImage?>, val problems: List<String>)

/**
 * Decodes a program's images off the GL thread; [done] runs on the decoder's thread. [budget] is
 * the pixels the slots may share ([ShaderImage.BUDGET_PIXELS] for a shader); [premultiplied] is
 * for a scene's layers, which are blended, so their edges don't fringe.
 */
fun interface ShaderImageDecoder {
    fun decode(images: List<BoundImage?>, maxSide: Int, budget: Long, premultiplied: Boolean, done: (DecodedImages) -> Unit)
}

/**
 * Decodes `effects.shaderImages` for [WaveRenderer] (Live backgrounds § 1), on one background
 * thread, next to [WallpaperLoader]:
 *  - The sizes are read first (bounds only), so [ShaderImagePlan] can cap each long side and drop
 *    the slots past the 8 MP budget before any pixels are decoded.
 *  - `ImageDecoder` with unpremultiplied output, so data kept in alpha (masks, packed depth)
 *    survives, and a software bitmap at the planned size.
 *  - The pixels are copied bottom row first into a direct buffer (GL's row 0 is the bottom), then
 *    the bitmap is recycled. Nothing is cached: the renderer uploads and drops them.
 */
class ShaderImageLoader(
    private val assets: AssetManager?,
    private val executor: Executor = Executors.newSingleThreadExecutor { r -> Thread(r, "XrossShaderImages").apply { isDaemon = true } },
) : ShaderImageDecoder {

    override fun decode(images: List<BoundImage?>, maxSide: Int, budget: Long, premultiplied: Boolean, done: (DecodedImages) -> Unit) {
        executor.execute { done(load(images, maxSide, budget, premultiplied)) }
    }

    /** Blocking: [images] decoded at most [maxSide] on the long side, within [budget] pixels. */
    fun load(
        images: List<BoundImage?>,
        maxSide: Int,
        budget: Long = ShaderImage.BUDGET_PIXELS,
        premultiplied: Boolean = false,
    ): DecodedImages {
        val problems = ArrayList<String>()
        val sizes = images.map { b ->
            val src = b?.source
            when {
                b == null -> null
                src == null -> null.also { problems += "${b.image.path} not found" }
                else -> runCatching { bounds(src) }.getOrNull()
                    .also { if (it == null) problems += "${b.image.path} can't be read as an image" }
            }
        }
        val plan = ShaderImagePlan.fit(sizes, maxSide, budget)
        val slots = images.mapIndexed { i, b ->
            val target = plan[i]
            if (b == null || b.source == null || sizes[i] == null) return@mapIndexed null
            if (target == null) {
                problems += "${b.image.path} is over the ${budget / (1024 * 1024)} MP budget for ${if (premultiplied) "a scene's" else "one shader's"} images; left out"
                return@mapIndexed null
            }
            runCatching { pixels(b.image, b.source!!, target, premultiplied) }
                .onFailure { Log.w(TAG, "can't decode ${b.image.path}: ${it.message}") }
                .getOrNull()
                .also { if (it == null) problems += "${b.image.path} can't be decoded" }
        }
        return DecodedImages(slots, problems)
    }

    private fun open(src: ShaderImageSource): InputStream = src.file?.inputStream()
        ?: src.asset?.let { a -> assets?.open(a) }
        ?: error("no source")

    private fun bounds(src: ShaderImageSource): ImageSize? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(src).use { BitmapFactory.decodeStream(it, null, opts) }
        return if (opts.outWidth > 0 && opts.outHeight > 0) ImageSize(opts.outWidth, opts.outHeight) else null
    }

    private fun pixels(image: ShaderImage, src: ShaderImageSource, target: ImageSize, premultiplied: Boolean): DecodedImage {
        val source = src.file?.let { ImageDecoder.createSource(it) }
            ?: src.asset?.let { a -> ImageDecoder.createSource(assets ?: error("no assets"), a) }
            ?: error("no source")
        var bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isUnpremultipliedRequired = !premultiplied
            if (info.size.width != target.width || info.size.height != target.height) decoder.setTargetSize(target.width, target.height)
        }
        if (bitmap.config != Bitmap.Config.ARGB_8888) {
            // 16-bit PNGs decode to RGBA_F16; GL gets 8 bits per channel.
            val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
            bitmap.recycle()
            bitmap = copy
            if (!premultiplied) bitmap.isPremultiplied = false
        }
        try {
            return DecodedImage(image, bitmap.width, bitmap.height, flipped(bitmap))
        } finally {
            bitmap.recycle()
        }
    }

    /** The bitmap's RGBA bytes with the rows reversed, so the image is upright in GL's bottom-up texture space. */
    private fun flipped(bitmap: Bitmap): ByteBuffer {
        val row = bitmap.width * 4
        val top = ByteBuffer.allocate(row * bitmap.height)
        bitmap.copyPixelsToBuffer(top)
        val src = top.array()
        val out = ByteBuffer.allocateDirect(row * bitmap.height).order(ByteOrder.nativeOrder())
        for (y in bitmap.height - 1 downTo 0) out.put(src, y * row, row)
        out.flip()
        return out
    }

    private companion object {
        const val TAG = "XrossShaderImages"
    }
}
