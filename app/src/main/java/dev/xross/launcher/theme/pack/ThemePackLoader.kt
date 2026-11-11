package dev.xross.launcher.theme.pack

import android.content.res.AssetManager
import android.graphics.BitmapFactory
import android.graphics.Typeface
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import dev.xross.launcher.state.GlyphKey
import dev.xross.launcher.theme.ThemePalette
import kotlinx.serialization.SerializationException
import java.io.File
import java.io.IOException
import java.util.Locale

/** Where a pack's files come from. */
sealed interface PackSource {
    /** Shipped inside the APK under `assets/<dir>`. */
    data class Builtin(val dir: String) : PackSource

    /** A folder in `Xross/themes/`. */
    data class Folder(val dir: File) : PackSource
}

/** A discovered pack: its id (folder name), manifest and location. */
@Immutable
data class ThemePackInfo(val id: String, val manifest: ThemeManifest, val source: PackSource) {
    val builtIn: Boolean get() = source is PackSource.Builtin
    val name: String get() = manifest.name
}

/** A sound file of a pack. */
sealed interface SoundSource {
    data class Asset(val path: String) : SoundSource
    data class Disk(val file: File) : SoundSource
}

/** A pack icon: vector (SVG) or bitmap (PNG/WebP). */
sealed interface ThemeIcon {
    data class Vector(val image: ImageVector) : ThemeIcon
    data class Bitmap(val image: ImageBitmap) : ThemeIcon
}

/**
 * A pack resolved into things the UI and the audio engine use. Every role, icon and default is
 * filled: what the pack lacks comes from the Default pack, so a pack may ship only a font, or only
 * a cursor sound.
 */
@Immutable
data class LoadedTheme(
    val info: ThemePackInfo,
    /** The pack's own colors, offered as the "Theme pack" color option; null when it has none. */
    val palette: ThemePalette?,
    val wave: ThemeManifest.WaveDefaults?,
    val icons: Map<GlyphKey, ThemeIcon>,
    val font: Typeface?,
    val sounds: Map<SoundRole, SoundSource>,
    val gains: Map<SoundRole, Float>,
    /** What the pack declared but couldn't be used (bad font, unreadable icon), for Settings. */
    val problems: List<String> = emptyList(),
) {
    val id: String get() = info.id
}

/**
 * Finds and loads theme packs: the built-in Default pack (`assets/themes/default`, the bundled
 * icons and sounds) and every folder in `Xross/themes/` with a readable `theme.json`:
 *
 * ```
 * Xross/themes/<theme_id>/
 *   theme.json   name, author, version, palette, wave defaults (see ThemeManifest)
 *   fonts/       one .ttf/.otf
 *   icons/       <icon id>.svg / .png / .webp, e.g. settings.svg, games.png
 *   sounds/      cursor.wav, confirm.wav, back.wav, option.wav, ok.wav, system_ng.wav,
 *                trophy.wav, 01. opening.wav, gameboot.wav
 * ```
 *
 * File lookups ignore case (FAT-formatted SD cards and hand-made packs disagree on it) and never
 * leave the pack's folder. Blocking I/O: call off the main thread, except for the small built-in
 * pack at startup.
 */
class ThemePackLoader(
    private val assets: AssetManager,
    private val themesDir: () -> File,
) {
    /** Default first, then folder packs by name. Unreadable folders are skipped. */
    fun discover(): List<ThemePackInfo> {
        val packs = ArrayList<ThemePackInfo>()
        builtIn()?.let(packs::add)
        val dir = themesDir()
        val folders = dir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }.orEmpty()
        for (folder in folders.sortedBy { it.name.lowercase(Locale.ROOT) }) {
            val id = folder.name
            if (!ID.matches(id) || id.equals(DEFAULT_ID, ignoreCase = true)) continue
            val manifestFile = find(folder, MANIFEST) ?: continue
            val manifest = readManifest { manifestFile.readText() } ?: continue
            packs += ThemePackInfo(id, manifest, PackSource.Folder(folder))
        }
        return packs.sortedWith(compareBy<ThemePackInfo>({ !it.builtIn }, { it.name.lowercase(Locale.ROOT) }))
    }

    /** The Default pack's manifest; null only if the APK is broken. */
    fun builtIn(): ThemePackInfo? = readManifest {
        assets.open("$DEFAULT_DIR/$MANIFEST").bufferedReader().use { it.readText() }
    }?.let { ThemePackInfo(DEFAULT_ID, it, PackSource.Builtin(DEFAULT_DIR)) }

    /** Loads [id], or the Default pack when [id] is unknown or unreadable. */
    fun load(id: String): LoadedTheme {
        val default = builtIn() ?: error("Built-in theme pack missing from the APK")
        val base = resolve(default, null)
        if (id.equals(DEFAULT_ID, ignoreCase = true) || id.isBlank()) return base
        val pack = discover().firstOrNull { it.id == id } ?: return base
        return resolve(pack, base)
    }

    private fun resolve(pack: ThemePackInfo, base: LoadedTheme?): LoadedTheme {
        val m = pack.manifest
        val problems = ArrayList<String>()

        val sounds = LinkedHashMap<SoundRole, SoundSource>()
        val gains = LinkedHashMap<SoundRole, Float>()
        for (role in SoundRole.entries) {
            val fileName = m.sounds.entries.firstOrNull { it.key.equals(role.key, ignoreCase = true) }?.value ?: role.file
            val own = soundFile(pack, fileName)
            val source = own ?: base?.sounds?.get(role)
            if (source != null) sounds[role] = source
            val gain = m.soundGains.entries.firstOrNull { it.key.equals(role.key, ignoreCase = true) }?.value
            gains[role] = when {
                gain != null && gain.isFinite() -> gain.coerceIn(0f, 1f)
                own == null -> base?.gains?.get(role) ?: 1f
                else -> 1f
            }
        }

        val icons = LinkedHashMap<GlyphKey, ThemeIcon>()
        for (key in GlyphKey.entries) {
            val declared = m.icons.entries.firstOrNull { it.key.equals(key.iconId, ignoreCase = true) }?.value
            val icon = icon(pack, declared, key.iconId)
            if (icon == null && declared != null) problems += "Icon ${key.iconId}: can't read $declared"
            (icon ?: base?.icons?.get(key))?.let { icons[key] = it }
        }

        val font = font(pack, m.font, problems)
        val palette = m.palette?.let { p -> p.toPalette().also { if (it == null) problems += "Palette colors must be #RRGGBB" } }

        return LoadedTheme(
            info = pack,
            palette = palette,
            wave = m.wave,
            icons = icons,
            font = font ?: if (m.font.isBlank()) base?.font else null,
            sounds = sounds,
            gains = gains,
            problems = problems,
        )
    }

    // region Files inside a pack

    private fun soundFile(pack: ThemePackInfo, name: String): SoundSource? = when (val s = pack.source) {
        is PackSource.Builtin -> "${s.dir}/$SOUNDS/$name".takeIf { assetExists(it) }?.let { SoundSource.Asset(it) }
        is PackSource.Folder -> inside(s.dir, "$SOUNDS/$name")?.let { SoundSource.Disk(it) }
    }

    private fun icon(pack: ThemePackInfo, declared: String?, id: String): ThemeIcon? {
        val candidates = if (declared != null) listOf(declared) else ICON_TYPES.map { "$id.$it" }
        for (name in candidates) {
            val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            if (ext !in ICON_TYPES) continue
            val icon = when (val s = pack.source) {
                is PackSource.Builtin -> "${s.dir}/$ICONS/$name".takeIf { assetExists(it) }?.let { path ->
                    runCatching { assets.open(path).use { stream -> readIcon(ext, id) { stream.readBytes() } } }.getOrNull()
                }
                is PackSource.Folder -> inside(s.dir, "$ICONS/$name")?.let { file -> readIcon(ext, id) { file.readBytes() } }
            }
            if (icon != null) return icon
        }
        return null
    }

    private fun readIcon(ext: String, id: String, bytes: () -> ByteArray): ThemeIcon? = try {
        val data = bytes()
        if (data.size > MAX_ICON_BYTES) {
            null
        } else if (ext == "svg") {
            SvgIcon.parse(data.toString(Charsets.UTF_8), id)?.let { ThemeIcon.Vector(it) }
        } else {
            decodeBitmap(data)?.let { ThemeIcon.Bitmap(it) }
        }
    } catch (_: IOException) {
        null
    }

    private fun decodeBitmap(data: ByteArray): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= ICON_PX) sample *= 2
        return BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    }

    private fun font(pack: ThemePackInfo, declared: String, problems: MutableList<String>): Typeface? = when (val s = pack.source) {
        is PackSource.Builtin -> {
            val path = if (declared.isNotBlank()) "${s.dir}/$declared" else firstFontAsset(s.dir)
            path?.let { p -> runCatching { Typeface.createFromAsset(assets, p) }.getOrNull() }
        }
        is PackSource.Folder -> {
            val file = if (declared.isNotBlank()) {
                inside(s.dir, declared)
            } else {
                find(s.dir, FONTS)?.listFiles()?.filter { it.isFile && it.extension.lowercase(Locale.ROOT) in FONT_TYPES }?.minByOrNull { it.name }
            }
            when {
                file == null -> {
                    if (declared.isNotBlank()) problems += "Font $declared not found"
                    null
                }
                file.length() > MAX_FONT_BYTES -> {
                    problems += "Font ${file.name} is larger than 16 MB"
                    null
                }
                else -> runCatching { Typeface.Builder(file).build() }.getOrNull()
                    .also { if (it == null) problems += "Font ${file.name} can't be read" }
            }
        }
    }

    private fun firstFontAsset(dir: String): String? =
        runCatching { assets.list("$dir/$FONTS") }.getOrNull()
            ?.filter { it.substringAfterLast('.').lowercase(Locale.ROOT) in FONT_TYPES }
            ?.minOrNull()
            ?.let { "$dir/$FONTS/$it" }

    private fun assetExists(path: String): Boolean = try {
        assets.openFd(path).close()
        true
    } catch (_: IOException) {
        // Compressed assets can't be opened as a descriptor; fall back to a stream check.
        runCatching { assets.open(path).close() }.isSuccess
    }

    // endregion

    private fun readManifest(text: () -> String): ThemeManifest? = try {
        ThemeManifest.JSON.decodeFromString(ThemeManifest.serializer(), text()).takeIf { it.name.isNotBlank() }
    } catch (_: IOException) {
        null
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    companion object {
        const val DEFAULT_ID = "default"
        const val DEFAULT_DIR = "themes/default"
        const val MANIFEST = "theme.json"
        const val SOUNDS = "sounds"
        const val ICONS = "icons"
        const val FONTS = "fonts"
        val ICON_TYPES = listOf("svg", "png", "webp")
        val FONT_TYPES = setOf("ttf", "otf")
        /** Pack icons are decoded no larger than this (they're drawn at most ~120 px). */
        const val ICON_PX = 256
        private const val MAX_ICON_BYTES = 2L * 1024 * 1024
        private const val MAX_FONT_BYTES = 16L * 1024 * 1024
        private val ID = Regex("[A-Za-z0-9_. -]{1,64}")

        /**
         * [relative] inside [root], matching each path segment without regard to case; null when
         * it doesn't exist or would leave [root] ("..", absolute paths, symlinks out).
         */
        fun inside(root: File, relative: String): File? {
            val parts = relative.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
            if (parts.isEmpty() || parts.any { it == ".." }) return null
            var current = root
            for (part in parts) current = find(current, part) ?: return null
            val canonicalRoot = runCatching { root.canonicalPath }.getOrNull() ?: return null
            val canonical = runCatching { current.canonicalPath }.getOrNull() ?: return null
            if (!canonical.startsWith(canonicalRoot + File.separator)) return null
            return current.takeIf { it.isFile || it.isDirectory }
        }

        /** The child of [dir] named [name], exact match first, then ignoring case. */
        fun find(dir: File, name: String): File? {
            val exact = File(dir, name)
            if (exact.exists()) return exact
            return dir.listFiles()?.firstOrNull { it.name.equals(name, ignoreCase = true) }
        }
    }
}
