package dev.xross.launcher.theme

import androidx.compose.runtime.Immutable
import dev.xross.launcher.theme.pack.LoadedTheme
import dev.xross.launcher.theme.pack.ThemeLook
import java.io.File

/**
 * Where the wallpaper behind the menu comes from (2b.4c, layer 3). [key] changes whenever the
 * image may have, so decoded and pre-blurred copies are cached by it.
 */
@Immutable
sealed interface WallpaperSource {
    val key: String

    /** The active theme pack's wallpaper file (`wallpaper.png` or `effects.wallpaperPath`). */
    data class PackFile(val file: File, val modified: Long = file.lastModified(), val bytes: Long = file.length()) : WallpaperSource {
        override val key: String get() = "pack:${file.path}:$modified:$bytes"
    }

    /**
     * Set as Xross wallpaper (`display.customWallpaper`): a `content://` or `file://` URI, or a
     * file path (absolute, or relative to the Xross folder) when the file was edited by hand.
     */
    data class Custom(val location: String) : WallpaperSource {
        override val key: String get() = "custom:$location"

        /** True for a URI the content resolver opens; false for a plain path. */
        val isUri: Boolean get() = location.startsWith("content://") || location.startsWith("file://")
    }

    companion object {
        /**
         * The wallpaper to show: the user's [custom] one (Set as Xross wallpaper) when set, else
         * the [look]'s from [pack], else none (the background shader shows).
         */
        fun resolve(custom: String, pack: LoadedTheme?, look: ThemeLook?): WallpaperSource? =
            custom.trim().takeIf { it.isNotEmpty() }?.let(::Custom) ?: packWallpaper(pack, look)

        /** The [look]'s wallpaper from [pack] alone (what shows when the user's can't be read). */
        fun packWallpaper(pack: LoadedTheme?, look: ThemeLook?): WallpaperSource? {
            val ref = (look ?: pack?.look())?.wallpaper ?: return null
            return pack?.wallpaperFile(ref)?.let { PackFile(it) }
        }
    }
}
