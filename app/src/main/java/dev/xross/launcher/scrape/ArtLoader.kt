package dev.xross.launcher.scrape

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Decodes cached art for the UI: downsampled to the size it is drawn at, decoded on
 * [Dispatchers.IO], and kept in a small byte-bounded memory cache so scrolling a long list
 * doesn't decode the same box twice (and the launcher stays well under its RAM budget).
 */
class ArtLoader(private val cache: AssetCache, maxBytes: Int = defaultBudget()) {

    private val memory = object : LruCache<String, ImageBitmap>(maxBytes) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.asAndroidBitmap().allocationByteCount
    }

    /** The first of [kinds] that is cached, decoded to about [maxPx] on its longer side. */
    suspend fun load(uuid: String, kinds: List<ArtKind>, maxPx: Int): ImageBitmap? {
        val (kind, file) = withContext(Dispatchers.IO) {
            kinds.firstNotNullOfOrNull { k -> cache.file(uuid, k)?.let { k to it } }
        } ?: return null
        val key = "$uuid/${kind.name}/$maxPx/${file.lastModified()}"
        memory.get(key)?.let { return it }
        return withContext(Dispatchers.IO) { decode(file, maxPx) }?.also { memory.put(key, it) }
    }

    fun file(uuid: String, kind: ArtKind): File? = cache.file(uuid, kind)

    private fun decode(file: File, maxPx: Int): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(file.path, opts)?.asImageBitmap()
    }

    private companion object {
        /** An eighth of the heap, at most 32 MB. */
        fun defaultBudget(): Int = (Runtime.getRuntime().maxMemory() / 8).coerceAtMost(32L shl 20).toInt()
    }
}
