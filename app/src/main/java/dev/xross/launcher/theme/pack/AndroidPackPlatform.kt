package dev.xross.launcher.theme.pack

import android.content.res.AssetManager
import android.graphics.BitmapFactory
import android.graphics.Typeface
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import java.io.File
import java.io.IOException
import java.io.InputStream

/** A pack icon from an SVG file. */
@Immutable
data class VectorIcon(val image: ImageVector) : ThemeIcon

/** A pack icon from a PNG or WebP file, decoded no larger than [ThemePackLoader.ICON_PX]. */
@Immutable
data class BitmapIcon(val image: ImageBitmap) : ThemeIcon

/** A pack font. */
@Immutable
data class TypefaceFont(val typeface: Typeface) : ThemeFont

/** The Android typeface of a pack font role; null when the pack has none (or it isn't Android's). */
val ThemeFont?.typeface: Typeface? get() = (this as? TypefaceFont)?.typeface

/** The theme engine's Android platform: the APK's assets, [SvgIcon], [BitmapFactory] and [Typeface]. */
class AndroidPackPlatform(private val assets: AssetManager) : PackPlatform {
    override fun openAsset(path: String): InputStream = assets.open(path)

    override fun listAssets(path: String): List<String> = assets.list(path)?.toList().orEmpty()

    override fun assetExists(path: String): Boolean = try {
        assets.openFd(path).close()
        true
    } catch (_: IOException) {
        // Compressed assets can't be opened as a descriptor; fall back to a stream check.
        runCatching { assets.open(path).close() }.isSuccess
    }

    override fun assetSize(path: String): Long? =
        runCatching { assets.openFd(path).use { it.length } }.getOrNull()
            ?: runCatching { assets.open(path).use { it.available().toLong() } }.getOrNull()

    override fun decodeIcon(ext: String, id: String, data: ByteArray): ThemeIcon? =
        if (ext == "svg") SvgIcon.parse(data.toString(Charsets.UTF_8), id)?.let(::VectorIcon)
        else decodeBitmap(data)?.let(::BitmapIcon)

    override fun assetFont(path: String): ThemeFont = TypefaceFont(Typeface.createFromAsset(assets, path))

    override fun fileFont(file: File): ThemeFont = TypefaceFont(Typeface.Builder(file).build())

    private fun decodeBitmap(data: ByteArray): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= ThemePackLoader.ICON_PX) sample *= 2
        return BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    }
}

/** A [ThemePackLoader] on the APK's [assets], with folder packs in [themesDir]. */
fun ThemePackLoader(assets: AssetManager, themesDir: () -> File): ThemePackLoader =
    ThemePackLoader(AndroidPackPlatform(assets), themesDir)
