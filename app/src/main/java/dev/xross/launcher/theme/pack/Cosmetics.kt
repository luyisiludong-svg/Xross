package dev.xross.launcher.theme.pack

import androidx.compose.runtime.Immutable
import java.util.Locale

/**
 * How the focused item is framed (2b.4d, `effects.focusFrameStyle`). Only [BREATHING_HALO]
 * animates while the cursor rests; the others are drawn at a fixed strength, so a resting screen
 * with them draws no frames for the focus.
 */
enum class FocusFrameStyle {
    /** Soft rings that breathe with the shared pulse (the look before 2b.4d). */
    BREATHING_HALO,

    /** A crisp, bright glow in the accent color that holds still. */
    STATIC_NEON,

    /** A solid shadow 2 dp down and right, no blur. */
    HARD_SHADOW,

    /** A metallic frame in `palette.focusGold` around game tiles and RetroAchievements badges. */
    GOLD_BEZEL,
    ;

    /** True when the frame follows the breathing pulse (and so animates while focused). */
    val breathes: Boolean get() = this == BREATHING_HALO
}

/** The shape installed apps' icons are cut to (2b.4d, `effects.iconMask`). */
enum class IconMask {
    /** The icon as the app ships it. */
    NONE,

    /** A superellipse: a rounded square whose curvature changes continuously (no corner seam). */
    SQUIRCLE,
    CIRCLE,

    /** A squircle plate in a glossy chrome bezel. */
    Y2K_CHROME_FRAME,
}

/** A color filter over app icons (2b.4d, `effects.iconFilter`), from the active palette. */
enum class IconFilter {
    NONE,

    /** Shadows in the canvas color, highlights in the accent. */
    DUOTONE,

    /** Brightness only, from black to the accent. */
    MONOCHROME,
}

/**
 * Ambient particles over the hero art and wallpaper (2b.4e, layer 4, `effects.foregroundParticles`).
 * At most [dev.xross.launcher.ui.ParticleField.MAX_PARTICLES]; they rest with the screen.
 */
enum class ParticlePreset {
    NONE,

    /** Flakes falling gently, each swaying on its own slow sine. */
    SNOW,

    /** Large, faint, out-of-focus discs rising slowly; nearer ones are bigger, fainter and faster. */
    BOKEH,

    /** Specks drifting every way and twinkling, brightest inside a diagonal shaft of light. */
    DUST_MOTES,
}

/**
 * A full-screen filter drawn over the background shader on the GL side (2b.4f,
 * `effects.backgroundFilter`). The GL wave is its own hardware surface under the Compose UI, so
 * this is a second, multiplied pass inside the wave's pipeline: it darkens the background only,
 * never the menu above it. Line pitch and stripe widths are whole render pixels sized from the
 * display density and the render scale, so they neither shimmer nor alias.
 */
enum class BackgroundFilter {
    NONE,

    /** Horizontal scanlines about 1.5 dp apart. */
    SCANLINES,

    /** Scanlines, an RGB aperture grille and the tube's vignette, over any background program. */
    CRT,

    /** A handheld LCD's pixel grid: thin dark lines between cells about 3 dp across. */
    LCD_GRID,
}

/**
 * The experimental UI post-process (2b.4f, `effects.uiFilter`): an AGSL `RuntimeShader` applied as
 * a `RenderEffect` to the menu's root layer. Android 13 (API 33) and later only; off below.
 */
enum class UiFilter {
    NONE,

    /** Scanlines over the interface (text, icons, panels), drawn at native resolution. */
    SCANLINES_UI,

    /** Bright text and glows bleed a soft halo (bright pass, blur, added back). */
    BLOOM,

    /** The pack's own AGSL: `postprocess.agsl`, or `effects.uiFilterPath`. */
    CUSTOM,
}

/** A UI filter a theme layer asks for: a preset, or for [UiFilter.CUSTOM] a pack-relative `.agsl` file. */
@Immutable
data class UiFilterRef(val filter: UiFilter, val path: String = "") {
    companion object {
        /** What CUSTOM reads without `uiFilterPath`. */
        const val DEFAULT_FILE = "postprocess.agsl"

        val OFF = UiFilterRef(UiFilter.NONE)

        /**
         * `effects.uiFilter` / `effects.uiFilterPath` of one layer, or null when it sets neither
         * (or names an unknown filter, reported in [problems]). A path alone means CUSTOM; CUSTOM
         * alone reads `postprocess.agsl`.
         */
        fun of(where: String, filter: String?, path: String?, problems: MutableList<String>): UiFilterRef? {
            val name = filter?.trim().orEmpty()
            val file = path?.trim()?.replace('\\', '/').orEmpty()
            if (name.isEmpty() && file.isEmpty()) return null
            val kind = if (name.isEmpty()) UiFilter.CUSTOM else Cosmetics.enumOf<UiFilter>(where, "uiFilter", name, problems) ?: return null
            if (kind != UiFilter.CUSTOM) {
                if (file.isNotEmpty()) problems += "$where.effects.uiFilterPath is only read with \"uiFilter\": \"CUSTOM\""
                return UiFilterRef(kind)
            }
            val f = file.ifEmpty { DEFAULT_FILE }
            if (!f.lowercase(Locale.ROOT).endsWith(".agsl") || f.split('/').any { it == ".." } || f.startsWith("/")) {
                problems += "$where.effects.uiFilterPath: use an .agsl file inside the pack (not $f)"
                return null
            }
            return UiFilterRef(UiFilter.CUSTOM, f)
        }
    }
}

/**
 * The draw-phase cosmetics a theme layer sets (2b.4d): focus frame, icon mask and icon filter,
 * the foreground particles (2b.4e) and the post-process filters (2b.4f).
 * Each is null where the layer leaves it alone; [over] stacks layers like the rest of the
 * cascade (Appendix A.4), the last one that names a value winning.
 */
@Immutable
data class Cosmetics(
    val focusFrame: FocusFrameStyle? = null,
    val iconMask: IconMask? = null,
    val iconFilter: IconFilter? = null,
    val particles: ParticlePreset? = null,
    /** The GL-side filter over the background program (2b.4f). */
    val backgroundFilter: BackgroundFilter? = null,
    /** The AGSL filter over the menu (2b.4f, Android 13+). */
    val uiFilter: UiFilterRef? = null,
) {
    /** These values with every one [patch] sets replacing them. */
    fun over(patch: Cosmetics?): Cosmetics = if (patch == null) this else Cosmetics(
        focusFrame = patch.focusFrame ?: focusFrame,
        iconMask = patch.iconMask ?: iconMask,
        iconFilter = patch.iconFilter ?: iconFilter,
        particles = patch.particles ?: particles,
        backgroundFilter = patch.backgroundFilter ?: backgroundFilter,
        uiFilter = patch.uiFilter ?: uiFilter,
    )

    val empty: Boolean get() = this == NONE

    /** The frame drawn: [focusFrame], else [FocusFrameStyle.BREATHING_HALO]. */
    val frame: FocusFrameStyle get() = focusFrame ?: FocusFrameStyle.BREATHING_HALO

    /** The mask drawn: [iconMask], else [IconMask.NONE]. */
    val mask: IconMask get() = iconMask ?: IconMask.NONE

    /** The filter drawn: [iconFilter], else [IconFilter.NONE]. */
    val filter: IconFilter get() = iconFilter ?: IconFilter.NONE

    /** The particles drawn: [particles], else [ParticlePreset.NONE]. */
    val particleField: ParticlePreset get() = particles ?: ParticlePreset.NONE

    /** The background filter drawn: [backgroundFilter], else [BackgroundFilter.NONE]. */
    val bgFilter: BackgroundFilter get() = backgroundFilter ?: BackgroundFilter.NONE

    /** The UI filter asked for: [uiFilter], else off (whether it can run is [dev.xross.launcher.ui.UiFilterSupport]'s call). */
    val screenFilter: UiFilterRef get() = uiFilter ?: UiFilterRef.OFF

    companion object {
        val NONE = Cosmetics()

        /**
         * `effects.focusFrameStyle`, `iconMask`, `iconFilter`, `foregroundParticles`,
         * `backgroundFilter` and `uiFilter` / `uiFilterPath` as written at [where]; null when none is set. Names are matched ignoring case, `-` and spaces (`gold-bezel`); an unknown
         * one is reported in [problems] and left unset.
         */
        fun of(where: String, effects: ThemeManifest.Effects?, problems: MutableList<String>): Cosmetics? {
            if (effects == null) return null
            val c = Cosmetics(
                focusFrame = enumOf<FocusFrameStyle>(where, "focusFrameStyle", effects.focusFrameStyle, problems),
                iconMask = enumOf<IconMask>(where, "iconMask", effects.iconMask, problems),
                iconFilter = enumOf<IconFilter>(where, "iconFilter", effects.iconFilter, problems),
                particles = enumOf<ParticlePreset>(where, "foregroundParticles", effects.foregroundParticles, problems),
                backgroundFilter = enumOf<BackgroundFilter>(where, "backgroundFilter", effects.backgroundFilter, problems),
                uiFilter = UiFilterRef.of(where, effects.uiFilter, effects.uiFilterPath, problems),
            )
            return c.takeUnless { it.empty }
        }

        /** [text] as one of [E]'s names, matched ignoring case, `-` and spaces; null (and nothing reported) when blank or unknown. */
        inline fun <reified E : Enum<E>> parse(text: String?): E? {
            val t = text?.trim().orEmpty()
            if (t.isEmpty()) return null
            val norm = t.replace('-', '_').replace(' ', '_').uppercase(Locale.ROOT)
            return enumValues<E>().firstOrNull { it.name == norm }
        }

        @PublishedApi
        internal inline fun <reified E : Enum<E>> enumOf(where: String, key: String, text: String?, problems: MutableList<String>): E? {
            val t = text?.trim().orEmpty()
            if (t.isEmpty()) return null
            val values = enumValues<E>()
            return parse<E>(t) ?: run {
                problems += "$where.effects.$key: $t isn't one of ${values.joinToString { it.name }}"
                null
            }
        }
    }
}
