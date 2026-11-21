package dev.xross.launcher.data

import dev.xross.launcher.theme.pack.LookPicks
import dev.xross.launcher.theme.pack.UserLook
import dev.xross.launcher.theme.pack.WaveValues

/*
 * The theme engine's user-layer values read from and written to [XrossSettings]. They live here,
 * not in `theme/pack/`, so the engine never depends on the settings model: settings may depend on
 * the pack schema's types, never the other way round (docs/design/ARCHITECTURE_ASSESSMENT.md §4).
 */

/** The user layer saved in [s]. */
fun UserLook.Companion.of(s: XrossSettings): UserLook = UserLook(
    effects = s.effectOverrides,
    components = s.componentOverrides,
    layout = s.layoutOverrides,
    surfaceStyle = s.surfaceStyle,
    customWallpaper = s.customWallpaper,
    adaptiveGamePalette = s.adaptiveGamePalette,
    circadianCurve = s.circadianCurve,
    batteryTint = s.batteryTint,
    themeMusic = s.themeMusic,
    wallpaperFolder = s.wallpaperFolder,
    wallpaperAlbum = s.wallpaperAlbum,
)

/** [s] with these picks written back (the rest of [s] as it is). */
fun UserLook.writeTo(s: XrossSettings): XrossSettings = s.copy(
    effectOverrides = effects,
    componentOverrides = components,
    layoutOverrides = layout,
    surfaceStyle = surfaceStyle,
    customWallpaper = customWallpaper,
    adaptiveGamePalette = adaptiveGamePalette,
    circadianCurve = circadianCurve,
    batteryTint = batteryTint,
    themeMusic = themeMusic,
    wallpaperFolder = wallpaperFolder,
    wallpaperAlbum = wallpaperAlbum,
)

/** The wave values of Settings ▸ Theme ▸ Wave settings in [s]. */
fun WaveValues.Companion.of(s: XrossSettings): WaveValues =
    WaveValues(s.waveSpeed, s.waveLineCount, s.waveTurbulence, s.waveThickness, s.waveTint, s.waveColors)

/** Everything over the pack in [s] that a current-look export bakes in. */
fun LookPicks.Companion.of(s: XrossSettings, wallpaperType: String? = null): LookPicks =
    LookPicks(s.themeVariant, UserLook.of(s), s.theme, WaveValues.of(s), wallpaperType)

/**
 * Settings ▸ Theme ▸ Reset to theme defaults (2b.4f): everything the user set over the active
 * pack's look ([UserLook]) goes back to the pack. Kept: which pack and variant, the theme color,
 * the quick profile (a look of its own, with its own row) and the wave's speed and shape.
 */
object ThemeDefaults {
    fun reset(s: XrossSettings): XrossSettings = UserLook.NONE.writeTo(s)

    /** True when [s] overrides nothing [reset] would clear. */
    fun isDefault(s: XrossSettings): Boolean = UserLook.of(s).isNone
}
