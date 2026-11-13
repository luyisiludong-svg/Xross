package dev.xross.launcher.theme

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.palette.graphics.Palette
import dev.xross.launcher.scrape.ArtKind
import java.io.File

/**
 * `androidx.palette` over a game's art, for [GamePaletteExtractor]: which art is read, at what
 * size, and the swatches it yields. Everything here blocks; the extractor calls it on a worker.
 */
object ArtSwatches {
    /** Art read for the palette, best first: the hero banner, the wheel logo, then a screenshot. */
    val KINDS = listOf(ArtKind.HERO, ArtKind.WHEEL, ArtKind.SCREENSHOT)

    /** Long side of the decoded art; Palette scales to ~112×112 anyway. */
    const val DECODE_PX = 192

    /** Extractor key for a game's art: its uuid and art version, so re-scraped art is read again. */
    fun key(uuid: String, artVersion: Long): String = "$uuid/$artVersion"

    fun uuidOf(key: String): String = key.substringBefore('/')

    /** [file] decoded to about [DECODE_PX] (a fresh bitmap, not the UI's cached one). */
    fun decode(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= DECODE_PX) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** The swatches in [bitmap]; null when Palette finds nothing. */
    fun analyze(bitmap: Bitmap): SwatchSet? {
        val p = Palette.from(bitmap).maximumColorCount(16).generate()
        val set = SwatchSet(
            dominant = p.dominantSwatch?.rgb,
            vibrant = p.vibrantSwatch?.rgb,
            lightVibrant = p.lightVibrantSwatch?.rgb,
            darkVibrant = p.darkVibrantSwatch?.rgb,
            muted = p.mutedSwatch?.rgb,
            lightMuted = p.lightMutedSwatch?.rgb,
            darkMuted = p.darkMutedSwatch?.rgb,
        )
        return set.takeUnless { it == SwatchSet() }
    }
}
