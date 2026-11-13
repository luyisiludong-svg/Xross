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
 * The draw-phase cosmetics a theme layer sets (2b.4d): focus frame, icon mask and icon filter.
 * Each is null where the layer leaves it alone; [over] stacks layers like the rest of the
 * cascade (Appendix A.4), the last one that names a value winning.
 */
@Immutable
data class Cosmetics(
    val focusFrame: FocusFrameStyle? = null,
    val iconMask: IconMask? = null,
    val iconFilter: IconFilter? = null,
) {
    /** These values with every one [patch] sets replacing them. */
    fun over(patch: Cosmetics?): Cosmetics = if (patch == null) this else Cosmetics(
        focusFrame = patch.focusFrame ?: focusFrame,
        iconMask = patch.iconMask ?: iconMask,
        iconFilter = patch.iconFilter ?: iconFilter,
    )

    val empty: Boolean get() = this == NONE

    /** The frame drawn: [focusFrame], else [FocusFrameStyle.BREATHING_HALO]. */
    val frame: FocusFrameStyle get() = focusFrame ?: FocusFrameStyle.BREATHING_HALO

    /** The mask drawn: [iconMask], else [IconMask.NONE]. */
    val mask: IconMask get() = iconMask ?: IconMask.NONE

    /** The filter drawn: [iconFilter], else [IconFilter.NONE]. */
    val filter: IconFilter get() = iconFilter ?: IconFilter.NONE

    companion object {
        val NONE = Cosmetics()

        /**
         * `effects.focusFrameStyle`, `iconMask` and `iconFilter` as written at [where]; null when
         * none is set. Names are matched ignoring case, `-` and spaces (`gold-bezel`); an unknown
         * one is reported in [problems] and left unset.
         */
        fun of(where: String, effects: ThemeManifest.Effects?, problems: MutableList<String>): Cosmetics? {
            if (effects == null) return null
            val c = Cosmetics(
                focusFrame = enumOf<FocusFrameStyle>(where, "focusFrameStyle", effects.focusFrameStyle, problems),
                iconMask = enumOf<IconMask>(where, "iconMask", effects.iconMask, problems),
                iconFilter = enumOf<IconFilter>(where, "iconFilter", effects.iconFilter, problems),
            )
            return c.takeUnless { it.empty }
        }

        private inline fun <reified E : Enum<E>> enumOf(where: String, key: String, text: String?, problems: MutableList<String>): E? {
            val t = text?.trim().orEmpty()
            if (t.isEmpty()) return null
            val norm = t.replace('-', '_').replace(' ', '_').uppercase(Locale.ROOT)
            val values = enumValues<E>()
            return values.firstOrNull { it.name == norm } ?: run {
                problems += "$where.effects.$key: $t isn't one of ${values.joinToString { it.name }}"
                null
            }
        }
    }
}
