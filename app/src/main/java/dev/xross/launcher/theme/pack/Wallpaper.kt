package dev.xross.launcher.theme.pack

import androidx.compose.runtime.Immutable
import java.io.File
import java.util.Locale

/**
 * A pack's static wallpaper (2b.4c, layer 3): an image file from the pack root drawn full-bleed
 * behind the menu, over the background shader. [path] null means "no wallpaper" (a variant,
 * override or rule turning the pack's off with `"wallpaperPath": "none"`).
 *
 * The pack's base is `effects.wallpaperPath` (alias `wallpaper`), else a `wallpaper.png` /
 * `.jpg` / `.webp` at the pack root when one exists. Variants, overrides and rules may each pick
 * another file or none; the last layer that names one wins, as for the shader.
 */
@Immutable
data class WallpaperRef(val path: String?) {
    companion object {
        val NONE = WallpaperRef(null)

        /** Image types a wallpaper may be. */
        val TYPES = listOf("png", "jpg", "jpeg", "webp")

        /** Names tried at the pack root when `effects.wallpaperPath` isn't set. */
        val DEFAULT_NAMES = TYPES.map { "wallpaper.$it" }

        /** Wallpapers larger than this aren't read (a 4K PNG is ~10 MB). */
        const val MAX_BYTES = 24L * 1024 * 1024

        /**
         * `effects.wallpaperPath` as written at [where]: null when unset (blank), [NONE] for
         * `none` / `off`, else a pack-relative image path. A path that leaves the pack or isn't an
         * image is reported in [problems] and treated as unset.
         */
        fun of(where: String, text: String?, problems: MutableList<String>): WallpaperRef? {
            val t = text?.trim().orEmpty()
            if (t.isEmpty()) return null
            if (t.equals("none", ignoreCase = true) || t.equals("off", ignoreCase = true)) return NONE
            val path = t.replace('\\', '/').trimStart('/')
            if (path.split('/').any { it == ".." }) {
                problems += "$where.effects.wallpaperPath: $t must stay inside the pack"
                return null
            }
            if (path.substringAfterLast('.', "").lowercase(Locale.ROOT) !in TYPES) {
                problems += "$where.effects.wallpaperPath: $t isn't a PNG, JPEG or WebP image"
                return null
            }
            return WallpaperRef(path)
        }

        /** The pack's own default wallpaper file name, if its folder has one. */
        fun defaultIn(dir: File): String? =
            DEFAULT_NAMES.firstNotNullOfOrNull { name -> ThemePackLoader.find(dir, name)?.takeIf { it.isFile }?.name }
    }
}

/**
 * The files of every wallpaper [refs] name, checked inside the folder pack [dir] (missing,
 * oversized and, for the built-in pack, any file are reported and left out, so they show no
 * wallpaper).
 */
internal fun wallpaperFiles(dir: File?, refs: Collection<WallpaperRef>, problems: MutableList<String>): Map<String, File> {
    val out = LinkedHashMap<String, File>()
    for (path in refs.mapNotNull { it.path }.distinct()) {
        if (dir == null) {
            problems += "Wallpaper $path: only folder packs can bring a wallpaper"
            continue
        }
        val file = ThemePackLoader.inside(dir, path)?.takeIf { it.isFile }
        when {
            file == null -> problems += "Wallpaper $path not found in the pack"
            file.length() > WallpaperRef.MAX_BYTES -> problems += "Wallpaper $path is larger than ${WallpaperRef.MAX_BYTES shr 20} MB"
            else -> out[path] = file
        }
    }
    return out
}
