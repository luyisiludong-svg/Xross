package dev.xross.launcher.theme

import dev.xross.launcher.theme.pack.ThemeManifest
import kotlin.math.roundToInt

/**
 * Color presets for the wave background. [THEME] draws the color theme's own palette; [CUSTOM]
 * draws the three colors in `settings.json` (`display.waveColors`); the rest are fixed tints.
 */
enum class WaveTint(val label: String, val palette: ThemePalette?) {
    THEME("Theme colors", null),
    AURORA("Aurora", ThemePalette(0xFF061A2C.toInt(), 0xFF1F7A8C.toInt(), 0xFFB8FFE4.toInt(), 0xFFE0FFF4.toInt())),
    SUNSET("Sunset", ThemePalette(0xFF2A0E2E.toInt(), 0xFFD2585C.toInt(), 0xFFFFD3A8.toInt(), 0xFFFFEBD6.toInt())),
    OCEAN("Ocean", ThemePalette(0xFF020B26.toInt(), 0xFF1650A6.toInt(), 0xFF9FD8FF.toInt())),
    SAKURA("Sakura", ThemePalette(0xFF3A1430.toInt(), 0xFFE7A1C7.toInt(), 0xFFFFF0F8.toInt(), 0xFFFFE6F4.toInt())),
    PASTEL("Pastel", ThemePalette(0xFF3B3560.toInt(), 0xFFA7C7E7.toInt(), 0xFFFFF5D6.toInt(), 0xFFFDE2FF.toInt())),
    MONO("Mono", ThemePalette(0xFF050505.toInt(), 0xFF4A4A52.toInt(), 0xFFE6E6EE.toInt())),
    CUSTOM("Custom", null),
    ;

    companion object {
        fun byName(name: String?): WaveTint = entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: THEME
    }
}

/** The three custom colors, as `#RRGGBB` text so `settings.json` stays hand-editable. */
data class WaveColors(
    val top: String = ThemeManifest.colorText(XrossTheme.LAVENDER.palette().top),
    val bottom: String = ThemeManifest.colorText(XrossTheme.LAVENDER.palette().bottom),
    val wave: String = ThemeManifest.colorText(XrossTheme.LAVENDER.palette().wave),
) {
    /** Null when any color doesn't parse. */
    fun toPalette(glow: Int = 0xFFFFFFFF.toInt()): ThemePalette? {
        val t = ThemeManifest.parseColor(top) ?: return null
        val b = ThemeManifest.parseColor(bottom) ?: return null
        val w = ThemeManifest.parseColor(wave) ?: return null
        return ThemePalette(t, b, w, glow)
    }
}

/**
 * Ranges and steps of the wave shader's uniforms, shared by `settings.json` clamping, the
 * Settings ▸ Theme ▸ Wave settings sliders and the renderer.
 */
object WaveLimits {
    val SPEED = 0.5f..2.0f
    const val SPEED_STEP = 0.1f
    val LINES = 0..12
    val TURBULENCE = 0f..2f
    const val TURBULENCE_STEP = 0.1f
    val THICKNESS = 0.5f..3f
    const val THICKNESS_STEP = 0.1f

    const val DEFAULT_SPEED = 1f
    const val DEFAULT_LINES = 5
    const val DEFAULT_TURBULENCE = 1f
    const val DEFAULT_THICKNESS = 1f

    /** Clamps and rounds to one decimal so repeated steps don't drift (0.30000001). */
    fun step(value: Float, delta: Float, range: ClosedFloatingPointRange<Float>): Float =
        (((value + delta) * 10f).roundToInt() / 10f).coerceIn(range.start, range.endInclusive)

    fun clampSpeed(v: Float) = v.finiteOr(DEFAULT_SPEED).coerceIn(SPEED.start, SPEED.endInclusive)
    fun clampLines(v: Int) = v.coerceIn(LINES.first, LINES.last)
    fun clampTurbulence(v: Float) = v.finiteOr(DEFAULT_TURBULENCE).coerceIn(TURBULENCE.start, TURBULENCE.endInclusive)
    fun clampThickness(v: Float) = v.finiteOr(DEFAULT_THICKNESS).coerceIn(THICKNESS.start, THICKNESS.endInclusive)

    private fun Float.finiteOr(fallback: Float) = if (isFinite()) this else fallback
}

/**
 * The palette the wave draws with: the color theme's (or the theme pack's) palette, unless a
 * tint preset or custom colors replace the gradient. The focus glow always stays the theme's.
 */
fun wavePalette(base: ThemePalette, tint: WaveTint, custom: WaveColors): ThemePalette = when (tint) {
    WaveTint.THEME -> base
    WaveTint.CUSTOM -> custom.toPalette(base.glow) ?: base
    else -> tint.palette!!.copy(glow = base.glow)
}
