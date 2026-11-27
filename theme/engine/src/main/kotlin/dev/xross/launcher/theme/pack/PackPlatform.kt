package dev.xross.launcher.theme.pack

import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * What [ThemePackLoader] needs from the platform it runs on: the bundled assets that hold the
 * built-in Default pack, and the decoding of icons and fonts into the platform's own types. Folder
 * packs are plain files the loader reads itself.
 *
 * On Android this is `AndroidPackPlatform` in `:app` (AssetManager, SvgIcon, BitmapFactory,
 * Typeface). A desktop preview or the engine's own tests use a directory and opaque handles.
 */
interface PackPlatform {
    /** Opens the bundled asset at [path] ("themes/default/theme.json"); throws [IOException] when it isn't there. */
    fun openAsset(path: String): InputStream

    /** The names in the asset folder [path]; empty for a file or a missing folder. */
    fun listAssets(path: String): List<String>

    /** True when the asset [path] exists and can be opened. */
    fun assetExists(path: String): Boolean

    /** The size of the asset [path] when it can be told; null otherwise. */
    fun assetSize(path: String): Long?

    /**
     * [data], the contents of an icon file of type [ext] ("svg", "png" or "webp"), as an icon; null
     * when it can't be decoded. [id] names the icon in logs. Bitmaps are decoded no larger than
     * [ThemePackLoader.ICON_PX].
     */
    fun decodeIcon(ext: String, id: String, data: ByteArray): ThemeIcon?

    /** The font in the asset [path]; null when it can't be read. */
    fun assetFont(path: String): ThemeFont?

    /** The font in [file] (a `.ttf` or `.otf` in a folder pack); null when it can't be read. */
    fun fileFont(file: File): ThemeFont?

    /**
     * The bundled asset at [path] as a file, for the layers drawn from files (a bundled pack's
     * wallpaper): on Android a copy made once in the app's cache. Null when it isn't there or the
     * platform can't make one, and that layer is then left out with a problem.
     */
    fun assetFile(path: String): File? = null
}
