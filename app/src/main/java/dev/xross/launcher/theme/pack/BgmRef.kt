package dev.xross.launcher.theme.pack

import androidx.compose.runtime.Immutable
import java.io.File
import java.util.Locale

/**
 * A pack's ambient music loop (2b.5c): an audio file from the pack root, played on repeat behind
 * the menu while nothing else plays ([dev.xross.launcher.audio.BgmPolicy]). [path] null means "no
 * music" (a variant, override or rule turning the pack's off with `"bgmFile": "none"`).
 *
 * The pack's base is `audio.bgmFile` (alias `bgm`), else a `bgm.ogg` / `bgm.mp3` at the pack root
 * when one exists. Variants, overrides and rules may each pick another file or none; the last layer
 * that names one wins, as for the wallpaper, so a category override can give Games its own loop.
 */
@Immutable
data class BgmRef(val path: String?) {
    companion object {
        val NONE = BgmRef(null)

        /** Audio types a loop may be (what ExoPlayer decodes without extensions). */
        val TYPES = listOf("ogg", "mp3", "m4a", "aac", "opus", "flac", "wav")

        /** Names tried at the pack root when `audio.bgmFile` isn't set. */
        val DEFAULT_NAMES = listOf("bgm.ogg", "bgm.mp3")

        /** Loops larger than this aren't played (a 3-minute 160 kbps Ogg is ~4 MB). */
        const val MAX_BYTES = 20L * 1024 * 1024

        /**
         * `audio.bgmFile` as written at [where]: null when unset (blank), [NONE] for `none` / `off`,
         * else a pack-relative path. A path that leaves the pack or isn't audio is reported in
         * [problems] and treated as unset.
         */
        fun of(where: String, text: String?, problems: MutableList<String>): BgmRef? {
            val t = text?.trim().orEmpty()
            if (t.isEmpty()) return null
            if (t.equals("none", ignoreCase = true) || t.equals("off", ignoreCase = true)) return NONE
            val path = t.replace('\\', '/').trimStart('/')
            if (path.split('/').any { it == ".." }) {
                problems += "$where.audio.bgmFile: $t must stay inside the pack"
                return null
            }
            if (path.substringAfterLast('.', "").lowercase(Locale.ROOT) !in TYPES) {
                problems += "$where.audio.bgmFile: $t isn't an audio file (${TYPES.joinToString()})"
                return null
            }
            return BgmRef(path)
        }

        /** The pack's own default loop file name, if its folder has one. */
        fun defaultIn(dir: File): String? =
            DEFAULT_NAMES.firstNotNullOfOrNull { name -> ThemePackLoader.find(dir, name)?.takeIf { it.isFile }?.name }
    }
}

/**
 * A loop to play: the file and a [key] that changes when the file does (a hot reload of an edited
 * `bgm.ogg` restarts it).
 */
@Immutable
data class BgmTrack(val file: File, val key: String = "${file.path}:${file.lastModified()}:${file.length()}") {
    val name: String get() = file.name
}

/**
 * The files of every loop [refs] name, checked inside the folder pack [dir] (missing, oversized
 * and, for the built-in pack, any file are reported and left out, so they play nothing).
 */
internal fun bgmFiles(dir: File?, refs: Collection<BgmRef>, problems: MutableList<String>): Map<String, File> {
    val out = LinkedHashMap<String, File>()
    for (path in refs.mapNotNull { it.path }.distinct()) {
        if (dir == null) {
            problems += "Theme music $path: only folder packs can bring theme music"
            continue
        }
        val file = ThemePackLoader.inside(dir, path)?.takeIf { it.isFile }
        when {
            file == null -> problems += "Theme music $path not found in the pack"
            file.length() > BgmRef.MAX_BYTES -> problems += "Theme music $path is larger than ${BgmRef.MAX_BYTES shr 20} MB"
            else -> out[path] = file
        }
    }
    return out
}
