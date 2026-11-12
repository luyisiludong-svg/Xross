package dev.xross.launcher.theme.pack

import dev.xross.launcher.state.GlyphKey
import dev.xross.launcher.theme.ThemePalette
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * `theme.json` v2 at the root of a theme pack (`Xross/themes/<theme_id>/theme.json`). Only
 * `schemaVersion: 2` packs are read (Roadmap §5: no v1 reader). Every block is optional except
 * [name]; what a pack leaves out comes from the built-in Default pack.
 *
 * [imports] lists other JSON files in the pack (for example `surfaces.json`) merged under this
 * one before it is decoded, so a pack can split its blocks across files; `theme.json` wins.
 *
 * ```json
 * {
 *   "schemaVersion": 2,
 *   "name": "Sakura",
 *   "imports": ["surfaces.json"],
 *   "palette": { "canvas": "#2E0A22", "accent": "#FFE6F4", "accentSecondary": "#FFC6EA" },
 *   "wave": { "speed": 0.8, "lineCount": 7, "gradient": ["#FFC6EA", "#B04C8E"] },
 *   "typography": { "display": "fonts/Display.ttf", "body": "fonts/Rounded.ttf" },
 *   "audio": { "sounds": { "cursor": "tick.wav" }, "soundGains": { "cursor": 0.6 } },
 *   "icons": { "album": "disc.svg" },
 *   "effects": { "surfaceStyle": "petal" },
 *   "surfaces": { "petal": { "shape": { "type": "CHAMFERED", "cutDp": [10, 0, 10, 0] } } }
 * }
 * ```
 */
@Serializable
data class ThemeManifest(
    val schemaVersion: Int = 0,
    val name: String,
    val author: String = "",
    val version: String = "",
    val description: String = "",
    val imports: List<String> = emptyList(),
    val palette: Palette? = null,
    val wave: WaveDefaults? = null,
    val typography: Typography? = null,
    val audio: Audio? = null,
    /** Icon id ([GlyphKey] name, lower case) → file name in `icons/`; missing ids use `<id>.svg/png/webp`. */
    val icons: Map<String, String> = emptyMap(),
    val effects: Effects? = null,
    /** Named surfaces this pack defines ([SurfaceSpec]); `effects.surfaceStyle` may pick one. */
    val surfaces: Map<String, SurfaceSpec> = emptyMap(),
    /** Crosshair, tile shapes, spacing, focus divider, drill-down, text overflow and motion ([LayoutSpec]). */
    val layout: LayoutSpec? = null,
    /** Category id (`GAMES`, `MUSIC`, …) → patches while that category is open; only `cardAspectRatio` so far. */
    val categoryOverrides: Map<String, CategoryOverride> = emptyMap(),
) {
    val supported: Boolean get() = schemaVersion == SCHEMA_VERSION

    /** Palette roles (Roadmap Appendix A.7); colors are `#RGB`, `#RRGGBB` or `#AARRGGBB`. */
    @Serializable
    data class Palette(
        val canvas: String? = null,
        val surface: String? = null,
        val surfaceBorder: String? = null,
        val accent: String? = null,
        val accentSecondary: String? = null,
        val focusGold: String? = null,
        val textPrimary: String? = null,
        val textSecondary: String? = null,
    ) {
        /**
         * The colors today's renderer draws: `canvas` at the top, the wave gradient's last stop (or
         * `canvas`) at the bottom, `accentSecondary` (or `accent`) for the wave and `accent` for
         * the glow. Null when `canvas` is missing or a color doesn't parse.
         */
        fun toPalette(wave: WaveDefaults?): ThemePalette? {
            val top = parseColor(canvas ?: return null) ?: return null
            val bottom = wave?.gradient?.lastOrNull()?.let(::parseColor) ?: top
            val ribbon = (accentSecondary ?: accent)?.let { parseColor(it) ?: return null } ?: 0xFFFFFFFF.toInt()
            val glow = accent?.let { parseColor(it) ?: return null } ?: 0xFFFFFFFF.toInt()
            return ThemePalette(top, bottom, ribbon, glow)
        }
    }

    @Serializable
    data class WaveDefaults(
        val speed: Float? = null,
        val lineCount: Int? = null,
        val turbulence: Float? = null,
        val ribbonThickness: Float? = null,
        /** Background gradient stops, top to bottom; the last one is the canvas bottom. */
        val gradient: List<String> = emptyList(),
    )

    /** Font files, relative to the pack root. Missing roles fall back to `body`, then the default. */
    @Serializable
    data class Typography(val display: String = "", val body: String = "", val mono: String = "")

    @Serializable
    data class Audio(
        /** [SoundRole] key (or a spec alias) → a file in `sounds/`, or a path from the pack root. */
        val sounds: Map<String, String> = emptyMap(),
        /** [SoundRole] key → 0–1 playback gain. */
        val soundGains: Map<String, Float> = emptyMap(),
    )

    @Serializable
    data class Effects(
        /** A [SurfacePreset] id or a key of [surfaces]. */
        val surfaceStyle: String? = null,
    )

    companion object {
        const val SCHEMA_VERSION = 2

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

        /**
         * Deep merge for `imports`: objects merge key by key, anything else in [over] replaces
         * [base]. Used with the imports as [base] and `theme.json` as [over].
         */
        fun merge(base: JsonObject, over: JsonObject): JsonObject = JsonObject(
            (base.keys + over.keys).associateWith { key ->
                val b = base[key]
                val o = over[key]
                if (b is JsonObject && o is JsonObject) merge(b, o) else o ?: b!!
            },
        )
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
        /** Names the Theme 2.0 spec uses for existing roles (Roadmap §5). */
        private val ALIASES = mapOf(
            "cancel" to BACK,
            "optionopen" to OPTION,
            "gamelaunch" to GAMEBOOT,
            "achievementunlock" to TROPHY,
        )

        fun byKey(key: String): SoundRole? {
            val k = key.trim()
            return entries.firstOrNull { it.key.equals(k, ignoreCase = true) } ?: ALIASES[k.lowercase()]
        }
    }
}

/** Icon id of a glyph in a pack: the [GlyphKey] name in lower case ("settings", "game_disc"). */
val GlyphKey.iconId: String get() = name.lowercase()
