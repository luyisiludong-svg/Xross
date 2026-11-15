package dev.xross.launcher.theme.pack

import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * A [PackPlatform] on plain files, for tests on the JVM: the bundled assets are the folder [assets]
 * (the app's `src/main/assets`), icons and fonts are opaque handles. Decoding is checked the way a
 * real decoder would fail: an SVG must hold an `<svg` element, a PNG or WebP its file signature,
 * and a font file must not be empty.
 */
class FilePackPlatform(private val assets: File) : PackPlatform {
    override fun openAsset(path: String): InputStream =
        asset(path)?.takeIf { it.isFile }?.inputStream() ?: throw IOException("$path is not an asset")

    override fun listAssets(path: String): List<String> = asset(path)?.list()?.sorted().orEmpty()

    override fun assetExists(path: String): Boolean = asset(path)?.isFile == true

    override fun assetSize(path: String): Long? = asset(path)?.takeIf { it.isFile }?.length()

    override fun decodeIcon(ext: String, id: String, data: ByteArray): ThemeIcon? {
        val ok = when (ext) {
            "svg" -> data.toString(Charsets.UTF_8).contains("<svg")
            "png" -> data.size > 8 && data.copyOfRange(0, 4).contentEquals(PNG)
            "webp" -> data.size > 12 && String(data, 0, 4, Charsets.US_ASCII) == "RIFF" && String(data, 8, 4, Charsets.US_ASCII) == "WEBP"
            else -> false
        }
        return if (ok) FileIcon(ext, id, data.size) else null
    }

    override fun assetFont(path: String): ThemeFont? = asset(path)?.takeIf { it.isFile && it.length() > 0 }?.let { FileFont(it.name) }

    override fun fileFont(file: File): ThemeFont? = file.takeIf { it.isFile && it.length() > 0 }?.let { FileFont(it.name) }

    private fun asset(path: String): File? =
        if (path.split('/').any { it == ".." }) null else File(assets, path).takeIf { it.exists() }

    /** An icon this platform "decoded": its type, the id it was read for and its size in bytes. */
    data class FileIcon(val ext: String, val id: String, val size: Int) : ThemeIcon

    /** A font file, by name. */
    data class FileFont(val name: String) : ThemeFont

    private companion object {
        val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
    }
}

/**
 * The app's bundled assets (the Default pack): the `xross.assets` property `:theme:engine`'s tests
 * get from the build, else `src/main/assets` (`:app`'s own tests run in `app/`).
 */
object TestAssets {
    val dir: File by lazy {
        val dir = listOfNotNull(System.getProperty("xross.assets"), "src/main/assets", "app/src/main/assets").map(::File)
            .firstOrNull { File(it, ThemePackLoader.DEFAULT_DIR).isDirectory }
        checkNotNull(dir) { "No Default pack found (set -Dxross.assets to the app's src/main/assets)" }
    }

    val platform: PackPlatform by lazy { FilePackPlatform(dir) }

    /** A loader on [platform] with folder packs in [themesDir]. */
    fun loader(themesDir: () -> File): ThemePackLoader = ThemePackLoader(platform, themesDir)
}
