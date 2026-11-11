package dev.xross.launcher.theme.pack

import dev.xross.launcher.state.GlyphKey
import dev.xross.launcher.theme.ThemePalette
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `theme.json` at the root of a theme pack (`Xross/themes/<theme_id>/theme.json`). Every field is
 * optional except [name]; anything a pack leaves out comes from the built-in Default pack.
 *
 * ```json
 * {
 *   "name": "Sakura",
 *   "author": "you",
 *   "version": "1.0",
 *   "palette": { "top": "#2E0A22", "bottom": "#B04C8E", "wave": "#FFC6EA", "glow": "#FFE6F4" },
 *   "wave": { "speed": 0.8, "lineCount": 7, "turbulence": 1.2, "ribbonThickness": 1.4 },
 *   "font": "fonts/Rounded.ttf",
 *   "sounds": { "cursor": "tick.wav" },
 *   "soundGains": { "cursor": 0.6 },
 *   "icons": { "album": "disc.svg" }
 * }
 * ```
 */
@Serializable
data class ThemeManifest(
    val name: String,
    val author: String = "",
    val version: String = "",
    val description: String = "",
    val palette: Palette? = null,
    val wave: WaveDefaults? = null,
    /** Path inside the pack; empty picks the first .ttf/.otf in `fonts/`. */
    val font: String = "",
    /** [SoundRole.key] → file name in `sounds/`; missing roles use the canonical [SoundRole.file]. */
    val sounds: Map<String, String> = emptyMap(),
    /** [SoundRole.key] → 0–1 playback gain. */
    val soundGains: Map<String, Float> = emptyMap(),
    /** Icon id ([GlyphKey] name, lower case) → file name in `icons/`; missing ids use `<id>.svg/png/webp`. */
    val icons: Map<String, String> = emptyMap(),
) {
    @Serializable
    data class Palette(val top: String, val bottom: String, val wave: String, val glow: String = "#FFFFFF") {
        /** Null when a color isn't `#RRGGBB` / `#AARRGGBB`. */
        fun toPalette(): ThemePalette? {
            val t = parseColor(top) ?: return null
            val b = parseColor(bottom) ?: return null
            val w = parseColor(wave) ?: return null
            return ThemePalette(t, b, w, parseColor(glow) ?: 0xFFFFFFFF.toInt())
        }
    }

    @Serializable
    data class WaveDefaults(
        val speed: Float? = null,
        val lineCount: Int? = null,
        val turbulence: Float? = null,
        val ribbonThickness: Float? = null,
    )

    companion object {
        val JSON = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

        /** `#RGB`, `#RRGGBB` or `#AARRGGBB` → ARGB; null for anything else. */
        fun parseColor(text: String): Int? {
            val hex = text.trim().removePrefix("#")
            if (hex.isEmpty() || hex.any { it.digitToIntOrNull(16) == null }) return null
            return when (hex.length) {
                3 -> (0xFF000000 or hex.map { "$it$it" }.joinToString("").toLong(16)).toInt()
                6 -> (0xFF000000 or hex.toLong(16)).toInt()
                8 -> hex.toLong(16).toInt()
                else -> null
            }
        }

        fun colorText(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)
    }
}

/**
 * UI sound roles and their canonical file names in a pack's `sounds/` folder. [stream] roles are
 * long cues played with MediaPlayer; the rest are preloaded into a SoundPool.
 */
enum class SoundRole(val key: String, val file: String, val stream: Boolean = false) {
    /** Horizontal and vertical D-pad navigation (cursor and category moves). */
    CURSOR("cursor", "cursor.wav"),
    /** Opening a list or running an item. */
    CONFIRM("confirm", "confirm.wav"),
    BACK("back", "back.wav"),
    /** The Options (△ / Y) menu opening. */
    OPTION("option", "option.wav"),
    /** A setting saved or an action that succeeded. */
    OK("ok", "ok.wav"),
    /** Errors and actions that aren't allowed. */
    SYSTEM_NG("system_ng", "system_ng.wav"),
    TROPHY("trophy", "trophy.wav"),
    /** Startup sound, with the fade-in on a cold start. */
    OPENING("opening", "01. opening.wav", stream = true),
    /** Game start transition. */
    GAMEBOOT("gameboot", "gameboot.wav", stream = true),
    ;

    companion object {
        fun byKey(key: String): SoundRole? = entries.firstOrNull { it.key.equals(key.trim(), ignoreCase = true) }
    }
}

/** Icon id of a glyph in a pack: the [GlyphKey] name in lower case ("settings", "game_disc"). */
val GlyphKey.iconId: String get() = name.lowercase()
