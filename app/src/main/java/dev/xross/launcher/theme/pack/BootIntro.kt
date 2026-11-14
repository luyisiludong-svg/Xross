package dev.xross.launcher.theme.pack

import androidx.compose.runtime.Immutable
import java.io.File
import java.util.Locale

/**
 * What a cold start shows before the menu (2b.5d, `effects.bootIntro`). Which one actually plays
 * is [dev.xross.launcher.ui.BootSequence]'s call: a pack's boot video beats its shader intro, and
 * E-Ink, a muted startup and warm starts turn it off.
 */
enum class BootIntro {
    /** The pack's `boot.mp4` (or `audio.bootVideo`), full-screen, with its own sound. */
    VIDEO,

    /** The pack's background shader ramped up from black, `uTransition` 0 → 1, under the `opening` cue. */
    SHADER,

    /** The menu's fade-in, as before 2b.5d. */
    OFF,
    ;

    companion object {
        /** Other spellings packs use for [OFF]. */
        private val ALIASES = mapOf("FADE" to OFF, "FADE_IN" to OFF, "NONE" to OFF, "MOVIE" to VIDEO)

        /** [text] as an intro, ignoring case, `-` and spaces, with [ALIASES]; null when blank or unknown. */
        fun byName(text: String?): BootIntro? = aliasedEnum(text, ALIASES)
    }
}

/**
 * The curtain between the menu and a starting game (2b.5d, `effects.launchCurtain`), drawn in the
 * start transition over the [dev.xross.launcher.state.XmbViewModel] hand-off (1100 ms) while the
 * `gameboot` cue plays. E-Ink draws plain black at once whatever the curtain.
 */
enum class LaunchCurtain {
    /** A glow blooming from the centre over a dimmed menu (the look before 2b.5d). */
    BLOOM,

    /** The menu fades to black. */
    FADE_BLACK,

    /** A circle closing on the focused tile, black outside it. */
    IRIS,

    /** A white flash that falls to black. */
    FLASH,
    ;

    companion object {
        private val ALIASES = mapOf(
            "FADE" to FADE_BLACK, "BLACK" to FADE_BLACK, "FADE_TO_BLACK" to FADE_BLACK,
            "CIRCLE" to IRIS, "IRIS_OUT" to IRIS, "WHITE_FLASH" to FLASH, "GLOW" to BLOOM,
        )

        /** [text] as a curtain, ignoring case, `-` and spaces, with [ALIASES]; null when blank or unknown. */
        fun byName(text: String?): LaunchCurtain? = aliasedEnum(text, ALIASES)
    }
}

/** [text] as one of [E]'s names (or an [aliases] key), ignoring case, `-` and spaces; null when blank or unknown. */
internal inline fun <reified E : Enum<E>> aliasedEnum(text: String?, aliases: Map<String, E>): E? {
    val t = text?.trim().orEmpty()
    if (t.isEmpty()) return null
    val norm = t.replace('-', '_').replace(' ', '_').uppercase(Locale.ROOT)
    return enumValues<E>().firstOrNull { it.name == norm } ?: aliases[norm]
}

/**
 * A pack's boot video (2b.5d): `audio.bootVideo` from the pack root, else its `boot.mp4` when the
 * folder has one. Folder packs only, at most [MAX_BYTES]; it plays for at most
 * [dev.xross.launcher.ui.BootSequence.MAX_MS] (a longer file is cut there).
 */
object BootVideoRef {
    /** Video types the boot video may be (what ExoPlayer decodes without extensions). */
    val TYPES = listOf("mp4", "m4v", "webm", "mkv", "3gp")

    /** Names tried at the pack root when `audio.bootVideo` isn't set. */
    val DEFAULT_NAMES = listOf("boot.mp4")

    const val MAX_BYTES = 30L * 1024 * 1024

    /** What [of] returns for `none` / `off`: the pack turns its own `boot.mp4` off. */
    const val NONE = ""

    /**
     * `audio.bootVideo` as written: null when unset (blank), [NONE] for `none` / `off`, else a
     * pack-relative path. A path that leaves the pack or isn't a video is reported in [problems]
     * and treated as unset.
     */
    fun of(text: String?, problems: MutableList<String>): String? {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return null
        if (t.equals("none", ignoreCase = true) || t.equals("off", ignoreCase = true)) return NONE
        val path = t.replace('\\', '/').trimStart('/')
        if (path.split('/').any { it == ".." }) {
            problems += "theme.audio.bootVideo: $t must stay inside the pack"
            return null
        }
        if (path.substringAfterLast('.', "").lowercase(Locale.ROOT) !in TYPES) {
            problems += "theme.audio.bootVideo: $t isn't a video file (${TYPES.joinToString()})"
            return null
        }
        return path
    }

    /**
     * The boot video file of a pack in [dir] (null for the built-in pack): [declared]
     * (`audio.bootVideo`, from [of]), else `boot.mp4` at the root. Missing and oversized files are
     * reported in [problems] and left out.
     */
    fun resolve(dir: File?, declared: String?, problems: MutableList<String>): File? {
        if (declared == NONE) return null
        if (dir == null) {
            if (declared != null) problems += "Boot video $declared: only folder packs can bring a boot video"
            return null
        }
        val path = declared ?: DEFAULT_NAMES.firstNotNullOfOrNull { n -> ThemePackLoader.find(dir, n)?.takeIf { it.isFile }?.name } ?: return null
        val file = ThemePackLoader.inside(dir, path)?.takeIf { it.isFile }
        return when {
            file == null -> null.also { problems += "Boot video $path not found in the pack" }
            file.length() > MAX_BYTES -> null.also { problems += "Boot video $path is larger than ${MAX_BYTES shr 20} MB" }
            else -> file
        }
    }
}
