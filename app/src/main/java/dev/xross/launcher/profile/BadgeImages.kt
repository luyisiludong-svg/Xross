package dev.xross.launcher.profile

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Decoded achievement badges for the Achievements screen, Details and unlock toasts. Files come
 * from [AchievementsRepository.badgeFile] (downloaded once into `Xross/media/ra/badges`); decodes
 * are downsampled to the size drawn and kept in a small LRU (badges are 64 px squares, a full
 * 150-badge set fits in ~2.5 MB).
 */
class BadgeImages(
    private val file: suspend (String) -> File?,
    maxBytes: Int = 4 shl 20,
) {
    private val memory = object : LruCache<String, ImageBitmap>(maxBytes) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.asAndroidBitmap().allocationByteCount
    }

    /**
     * The badge at [px], or null when it can't be had. A failed download is tried again twice,
     * a few seconds apart, so a cell never stays blank after a passing network hiccup.
     */
    suspend fun load(badgeName: String, px: Int): ImageBitmap? {
        val key = "$badgeName@$px"
        memory.get(key)?.let { return it }
        var f = file(badgeName)
        var attempt = 0
        while (f == null && attempt < RETRIES) {
            kotlinx.coroutines.delay(RETRY_MS * ++attempt)
            f = file(badgeName)
        }
        if (f == null) return null
        val bitmap = withContext(Dispatchers.IO) { decode(f, px) }
        if (bitmap == null) {
            android.util.Log.w("XrossAchievements", "Badge $badgeName: can't decode ${f.length()} bytes")
            return null
        }
        memory.put(key, bitmap)
        return bitmap
    }

    fun trim() = memory.evictAll()

    private companion object {
        const val RETRIES = 2
        const val RETRY_MS = 2_000L
    }

    private fun decode(f: File, px: Int): ImageBitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= px.coerceAtLeast(1)) sample *= 2
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    }.getOrNull()
}
