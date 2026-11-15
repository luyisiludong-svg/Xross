package dev.xross.launcher.theme.pack

import androidx.compose.runtime.Immutable
import dev.xross.launcher.data.XrossSettings

/**
 * Every pick the user layers over the active theme pack (Phase 2b.8), in one value: what the
 * cascade reads ([dev.xross.launcher.theme.LookInputs.user]), what Settings ▸ Theme ▸ Reset to
 * theme defaults clears and counts, and (2b.8b) what a current-look export bakes in. A field
 * added here shows up in all three at once.
 *
 * Not part of it, because they are settings of their own that outlive a pack: which pack and
 * variant, the colour theme, the quick profile and the wave values.
 */
@Immutable
data class UserLook(
    /** Settings ▸ Theme effect picks (2b.4f); empty values follow the pack. */
    val effects: EffectOverrides = EffectOverrides.NONE,
    /** Settings ▸ Theme ▸ Components picks (2b.7a); null values follow the pack. */
    val components: ComponentOverrides = ComponentOverrides.NONE,
    /** Settings ▸ Theme ▸ Layout picks (2b.8a); null values follow the pack. */
    val layout: LayoutOverrides = LayoutOverrides.NONE,
    /** Settings ▸ Theme ▸ Surface style; blank follows the look. */
    val surfaceStyle: String = "",
    /** Xross's own wallpaper (a photo URI or file path); blank uses the pack's. */
    val customWallpaper: String = "",
    /** The colour layers; null follows the pack. */
    val adaptiveGamePalette: Boolean? = null,
    val circadianCurve: Boolean? = null,
    val batteryTint: Boolean? = null,
) {
    /** True when nothing is set: the pack as it is. */
    val isNone: Boolean get() = this == NONE

    /** [s] with these picks written back (the rest of [s] as it is). */
    fun writeTo(s: XrossSettings): XrossSettings = s.copy(
        effectOverrides = effects,
        componentOverrides = components,
        layoutOverrides = layout,
        surfaceStyle = surfaceStyle,
        customWallpaper = customWallpaper,
        adaptiveGamePalette = adaptiveGamePalette,
        circadianCurve = circadianCurve,
        batteryTint = batteryTint,
    )

    /**
     * What is set, one phrase per kind, in Reset's order: "2 layout values", "3 effects",
     * "1 component", "surface", "2 color layers", "wallpaper". Empty for [NONE].
     */
    fun changes(): List<String> = buildList {
        if (layout.count > 0) add(countOf(layout.count, "layout value"))
        val fx = EffectSlot.entries.count { effects[it].isNotEmpty() }
        if (fx > 0) add(countOf(fx, "effect"))
        if (components.count > 0) add(countOf(components.count, "component"))
        if (surfaceStyle.isNotEmpty()) add("surface")
        val layers = listOf(adaptiveGamePalette, circadianCurve, batteryTint).count { it != null }
        if (layers > 0) add(countOf(layers, "color layer"))
        if (customWallpaper.isNotBlank()) add("wallpaper")
    }

    companion object {
        /** The pack as it is: no user pick. */
        val NONE = UserLook()

        /** The user layer saved in [s]. */
        fun of(s: XrossSettings): UserLook = UserLook(
            effects = s.effectOverrides,
            components = s.componentOverrides,
            layout = s.layoutOverrides,
            surfaceStyle = s.surfaceStyle,
            customWallpaper = s.customWallpaper,
            adaptiveGamePalette = s.adaptiveGamePalette,
            circadianCurve = s.circadianCurve,
            batteryTint = s.batteryTint,
        )

        private fun countOf(n: Int, noun: String) =
            if (n == 1) "1 $noun" else "${java.text.NumberFormat.getIntegerInstance().format(n)} ${noun}s"
    }
}
