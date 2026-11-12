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
    /**
     * Named colorways over this pack ([ThemeVariant]): palette and effects patches sharing its
     * fonts, icons and sounds. Settings ▸ Theme ▸ Theme variant picks one (`display.themeVariant`).
     */
    val variants: List<ThemeVariant> = emptyList(),
    /** Category id (`GAMES`, `MUSIC`, …) → a patch applied while that category is open. */
    val categoryOverrides: Map<String, ThemeOverride> = emptyMap(),
    /**
     * Xross platform id (`dreamcast`, `ps2`, `gba`) or one of its folder aliases (`dc`) → a patch
     * applied while one of that system's games is focused. Its `cardAspectRatio` shapes that
     * system's game tiles.
     */
    val systemOverrides: Map<String, ThemeOverride> = emptyMap(),
    /**
     * Context rules ([ContextRule]): category, system, battery and time-of-day selectors, applied
     * in order above the variant and the overrides. Rules from `imports` (a `rules.json`) come
     * first, then these.
     */
    val rules: List<ContextRule> = emptyList(),
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
        /** Blend the canvas through the hourly circadian curve (Appendix A.7). Null: off unless the user turns it on. */
        val circadianCurve: Boolean? = null,
        /** Shift accent and glow toward amber/red below 20% battery, and pulse green while charging. */
        val batteryTint: Boolean? = null,
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

        /** These roles with every role [patch] sets replacing them (the precedence cascade, A.4). */
        fun over(patch: Palette?): Palette = if (patch == null) this else Palette(
            canvas = patch.canvas ?: canvas,
            surface = patch.surface ?: surface,
            surfaceBorder = patch.surfaceBorder ?: surfaceBorder,
            accent = patch.accent ?: accent,
            accentSecondary = patch.accentSecondary ?: accentSecondary,
            focusGold = patch.focusGold ?: focusGold,
            textPrimary = patch.textPrimary ?: textPrimary,
            textSecondary = patch.textSecondary ?: textSecondary,
            circadianCurve = patch.circadianCurve ?: circadianCurve,
            batteryTint = patch.batteryTint ?: batteryTint,
        )

        /** True when no role and no switch is set. */
        val empty: Boolean get() = this == Palette()

        /** True when any color role is set (the switches alone don't make a palette). */
        val hasColors: Boolean get() = copy(circadianCurve = null, batteryTint = null) != Palette()

        /** These roles minus any color that doesn't parse, each reported in [problems] under [where]. */
        fun valid(where: String, problems: MutableList<String>): Palette {
            fun check(role: String, value: String?): String? {
                if (value == null || parseColor(value) != null) return value
                problems += "$where.$role: $value isn't a color like #RRGGBB"
                return null
            }
            return Palette(
                canvas = check("canvas", canvas),
                surface = check("surface", surface),
                surfaceBorder = check("surfaceBorder", surfaceBorder),
                accent = check("accent", accent),
                accentSecondary = check("accentSecondary", accentSecondary),
                focusGold = check("focusGold", focusGold),
                textPrimary = check("textPrimary", textPrimary),
                textSecondary = check("textSecondary", textSecondary),
                circadianCurve = circadianCurve,
                batteryTint = batteryTint,
            )
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
        /**
         * Let the focused game's hero or wheel art drive the wave, accent and glows (Appendix A.7).
         * Null: on, unless the user turns it off.
         */
        val adaptiveGamePalette: Boolean? = null,
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
 * One entry of `variants`: a colorway of the pack ("Cyber Lilac", "BIOS Matrix") that patches its
 * [palette] and [effects]; everything else (fonts, icons, sounds, layout) is the pack's.
 */
@Serializable
data class ThemeVariant(
    val id: String,
    val name: String = "",
    val palette: ThemeManifest.Palette? = null,
    val effects: ThemeManifest.Effects? = null,
)

/**
 * A `categoryOverrides` or `systemOverrides` entry (Appendix A.4): palette roles, effects and the
 * tile aspect while that category is open or that system's game is focused.
 */
@Serializable
data class ThemeOverride(
    val palette: ThemeManifest.Palette? = null,
    val effects: ThemeManifest.Effects? = null,
    val cardAspectRatio: String? = null,
)

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
