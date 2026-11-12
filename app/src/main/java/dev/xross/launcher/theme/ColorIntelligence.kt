package dev.xross.launcher.theme

import androidx.compose.runtime.Immutable
import dev.xross.launcher.theme.pack.MotionProfile
import dev.xross.launcher.theme.pack.SurfacePreset
import dev.xross.launcher.theme.pack.TextOverflowMode
import dev.xross.launcher.theme.pack.ThemeLayout
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Battery charge (0–100) and whether it is charging (or full on the charger). */
@Immutable
data class BatteryLevel(val percent: Int, val charging: Boolean)

/** Pure color helpers over opaque ARGB ints, so the color logic runs in plain JVM tests. */
object ColorMath {
    /** Hue (degrees), saturation and value (0–1) of [argb]. */
    fun hsv(argb: Int): FloatArray {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val d = mx - mn
        val h = when {
            d == 0f -> 0f
            mx == r -> 60f * (((g - b) / d) % 6f)
            mx == g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }
        return floatArrayOf((h + 360f) % 360f, if (mx == 0f) 0f else d / mx, mx)
    }

    fun fromHsv(h: Float, s: Float, v: Float): Int = SeasonalPalette.hsv(h, s, v)

    /** WCAG relative luminance, 0 (black) – 1 (white). */
    fun luminance(argb: Int): Float {
        fun ch(shift: Int): Double {
            val c = ((argb shr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return (0.2126 * ch(16) + 0.7152 * ch(8) + 0.0722 * ch(0)).toFloat()
    }

    /** WCAG contrast ratio, 1 – 21. */
    fun contrast(a: Int, b: Int): Float {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
    }

    fun lerp(a: Int, b: Int, t: Float): Int = SeasonalPalette.lerpArgb(a, b, t.coerceIn(0f, 1f))

    /** [argb] with its HSV value set to [value] (and saturation scaled by [saturation]). */
    fun withValue(argb: Int, value: Float, saturation: Float = 1f): Int {
        val (h, s, _) = hsv(argb)
        return fromHsv(h, (s * saturation).coerceIn(0f, 1f), value.coerceIn(0f, 1f))
    }

    /** [argb] mixed toward white by [t]. */
    fun lighten(argb: Int, t: Float): Int = lerp(argb, WHITE, t)

    const val WHITE = 0xFFFFFFFF.toInt()
    const val BLACK = 0xFF000000.toInt()
}

/**
 * The optional hourly circadian curve (`palette.circadianCurve`, Appendix A.7): four phases,
 * 06–11 cool cyan / sky blue, 11–18 azure / cobalt, 18–21 amber / rose gold, 21–06 indigo /
 * midnight purple. Each phase color is derived like the Original theme's months
 * ([SeasonalPalette.paletteOf]), and neighbouring phases crossfade over [BLEND_MINUTES] either
 * side of each boundary, so the change through the day is never a jump. The wave's day/night
 * brightness shift stays separate (it dims every theme).
 */
object Circadian {
    /** Minutes either side of a phase boundary over which two phases blend. */
    const val BLEND_MINUTES = 45

    /** How far the theme's colors move toward the phase's: canvas, wave sheets, glow. */
    const val CANVAS_WEIGHT = 0.5f
    const val WAVE_WEIGHT = 0.35f
    const val GLOW_WEIGHT = 0.2f

    enum class Phase(val startMinute: Int, internal val anchor: SeasonalPalette.Anchor) {
        MORNING(6 * 60, SeasonalPalette.Anchor(hue = 192f, saturation = 0.62f)),
        DAY(11 * 60, SeasonalPalette.Anchor(hue = 214f, saturation = 0.78f)),
        SUNSET(18 * 60, SeasonalPalette.Anchor(hue = 22f, saturation = 0.70f, value = 0.95f)),
        NIGHT(21 * 60, SeasonalPalette.Anchor(hue = 255f, saturation = 0.62f, value = 0.72f)),
        ;

        val palette: ThemePalette get() = SeasonalPalette.paletteOf(anchor)
    }

    /** The phase [minuteOfDay] falls in. */
    fun phaseAt(minuteOfDay: Int): Phase {
        val m = wrap(minuteOfDay)
        return Phase.entries.lastOrNull { m >= it.startMinute } ?: Phase.NIGHT
    }

    /** The curve's own colors at [minuteOfDay], blended across phase boundaries. */
    fun paletteAt(minuteOfDay: Int): ThemePalette {
        val m = wrap(minuteOfDay)
        val phases = Phase.entries
        for ((i, next) in phases.withIndex()) {
            val prev = phases[(i + phases.size - 1) % phases.size]
            val d = signedDistance(m, next.startMinute)
            if (abs(d) < BLEND_MINUTES) {
                // d = -45 → all prev, 0 → half, +45 → all next.
                val t = smooth((d + BLEND_MINUTES) / (2f * BLEND_MINUTES))
                return SeasonalPalette.blend(prev.palette, next.palette, t)
            }
        }
        return phaseAt(m).palette
    }

    /** [base] tinted toward the curve at [minuteOfDay]. */
    fun apply(base: ThemePalette, minuteOfDay: Int): ThemePalette {
        val c = paletteAt(minuteOfDay)
        return ThemePalette(
            top = ColorMath.lerp(base.top, c.top, CANVAS_WEIGHT),
            bottom = ColorMath.lerp(base.bottom, c.bottom, CANVAS_WEIGHT),
            wave = ColorMath.lerp(base.wave, c.wave, WAVE_WEIGHT),
            glow = ColorMath.lerp(base.glow, c.glow, GLOW_WEIGHT),
        )
    }

    private fun wrap(m: Int) = ((m % DAY) + DAY) % DAY

    /** Minutes from [boundary] to [m] on the clock face, in -720..720. */
    private fun signedDistance(m: Int, boundary: Int): Int {
        var d = m - boundary
        if (d > DAY / 2) d -= DAY
        if (d < -DAY / 2) d += DAY
        return d
    }

    private fun smooth(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    private const val DAY = 24 * 60
}

/**
 * The optional battery-level tint (`palette.batteryTint`, Appendix A.7): below [LOW_PERCENT] the
 * accent and glow shift toward amber, reaching red at [CRITICAL_PERCENT]; while charging they lean
 * toward green, and the wave pulses that green gently on the GL thread ([WaveParams.chargePulse]).
 */
object BatteryTint {
    const val LOW_PERCENT = 20
    const val CRITICAL_PERCENT = 5
    val AMBER = 0xFFFFB347.toInt()
    val RED = 0xFFFF4A3D.toInt()
    val GREEN = 0xFF7CFFB2.toInt()

    /** True while the battery is low enough to tint. */
    fun low(b: BatteryLevel?): Boolean = b != null && !b.charging && b.percent <= LOW_PERCENT

    /** The warning color at [percent]: amber at 20%, red at 5% and below. */
    fun warning(percent: Int): Int =
        ColorMath.lerp(AMBER, RED, (LOW_PERCENT - percent).toFloat() / (LOW_PERCENT - CRITICAL_PERCENT))

    fun apply(base: ThemePalette, battery: BatteryLevel?): ThemePalette = when {
        battery == null -> base
        battery.charging -> base.copy(
            wave = ColorMath.lerp(base.wave, GREEN, 0.25f),
            glow = ColorMath.lerp(base.glow, GREEN, 0.3f),
        )
        low(battery) -> {
            val depth = ((LOW_PERCENT - battery.percent).toFloat() / (LOW_PERCENT - CRITICAL_PERCENT)).coerceIn(0f, 1f)
            val warn = warning(battery.percent)
            val k = 0.4f + 0.35f * depth
            base.copy(
                bottom = ColorMath.lerp(base.bottom, ColorMath.withValue(warn, 0.5f), k * 0.2f),
                wave = ColorMath.lerp(base.wave, warn, k * 0.8f),
                glow = ColorMath.lerp(base.glow, warn, k),
            )
        }
        else -> base
    }
}

/**
 * Swatches `androidx.palette` found in a piece of art (ARGB, null where it found none). A plain
 * value so the mapping to theme colors ([GameColors.from]) runs in JVM tests.
 */
@Immutable
data class SwatchSet(
    val dominant: Int? = null,
    val vibrant: Int? = null,
    val lightVibrant: Int? = null,
    val darkVibrant: Int? = null,
    val muted: Int? = null,
    val lightMuted: Int? = null,
    val darkMuted: Int? = null,
)

/**
 * Theme colors from a focused game's art (Appendix A.7, the contextual palette): [accent] from the
 * vibrant (or dominant) swatch, [accentSecondary] from the light-vibrant (or muted) one, and the
 * dark [canvas] from the dark-muted swatch (or the dominant color darkened).
 */
@Immutable
data class GameColors(val accent: Int, val accentSecondary: Int, val canvas: Int) {

    /** The palette the art draws: its canvas hue top and bottom, its accents in the wave and glow. */
    fun toPalette(): ThemePalette {
        val (h, s, _) = ColorMath.hsv(canvas)
        return ThemePalette(
            top = ColorMath.fromHsv(h, s.coerceAtMost(0.85f), TOP_VALUE),
            bottom = ColorMath.fromHsv(h, (s * 1.05f).coerceIn(0.2f, 0.9f), BOTTOM_VALUE),
            wave = ColorMath.lighten(accentSecondary, 0.35f),
            glow = ColorMath.lighten(accent, 0.55f),
        )
    }

    companion object {
        /** Canvas brightness for the top of the screen and the bright band near the bottom. */
        const val TOP_VALUE = 0.16f
        const val BOTTOM_VALUE = 0.52f

        /** The darkest a canvas swatch may be used as-is; brighter ones are darkened to this. */
        const val MAX_CANVAS_VALUE = 0.35f

        /** Below this saturation every swatch is grey: the art says nothing about color. */
        const val MIN_SATURATION = 0.12f

        /** The glow must stand out this much (WCAG ratio) against the canvas top. */
        const val MIN_CONTRAST = 3f

        /**
         * Theme colors from [s], or null to keep the theme's palette: no swatches, all grey, or
         * an accent that wouldn't stand out from the canvas.
         */
        fun from(s: SwatchSet): GameColors? {
            val dominant = s.dominant ?: s.vibrant ?: s.muted ?: return null
            val accent = s.vibrant ?: s.lightVibrant ?: s.darkVibrant ?: dominant
            val secondary = s.lightVibrant ?: s.muted ?: s.lightMuted ?: accent
            val canvasSource = s.darkMuted ?: s.darkVibrant ?: dominant
            val all = listOfNotNull(s.dominant, s.vibrant, s.lightVibrant, s.darkVibrant, s.muted, s.lightMuted, s.darkMuted)
            if (all.all { ColorMath.hsv(it)[1] < MIN_SATURATION }) return null
            val cv = ColorMath.hsv(canvasSource)
            val canvas = if (cv[2] > MAX_CANVAS_VALUE) ColorMath.fromHsv(cv[0], cv[1], MAX_CANVAS_VALUE) else canvasSource
            val colors = GameColors(accent, secondary, canvas)
            val p = colors.toPalette()
            if (ColorMath.contrast(p.glow, p.top) < MIN_CONTRAST) return null
            return colors
        }
    }
}

/**
 * Built-in looks under Settings ▸ Theme ▸ Quick profile (Appendix A.7). A profile sits in the
 * user's layer: over the pack and the theme color, under the game-art palette, which it may turn
 * off ([allowsArt]). [stillMotion] profiles freeze every infinite animation and the wave's
 * render loop, for E-Ink panels that ghost on continuous redraws.
 */
enum class QuickProfile(
    val label: String,
    val palette: ThemePalette?,
    val surface: SurfacePreset?,
    val motion: MotionProfile? = null,
    val stillMotion: Boolean = false,
    val allowsArt: Boolean = true,
    val allowsAmbient: Boolean = true,
) {
    NONE("None", null, null),

    /** Pure black canvas, no surface fills, crisp glowing 1 dp accent borders, no haze. */
    OLED_TRUE_BLACK(
        "OLED True Black",
        ThemePalette(top = ColorMath.BLACK, bottom = ColorMath.BLACK, wave = 0xFF3A3A48.toInt(), glow = 0xFFE6E6FF.toInt()),
        SurfacePreset.OLED_TRUE_BLACK,
    ),

    /** Lavender canvas with lilac and strawberry accents and glossy bezels. */
    Y2K_CYBER_PASTEL(
        "Y2K Cyber-Pastel",
        ThemePalette(top = 0xFF1B1829.toInt(), bottom = 0xFF5A4C8C.toInt(), wave = 0xFFC8B6FF.toInt(), glow = 0xFFFFB5C5.toInt()),
        SurfacePreset.Y2K_GLOSSY_BEZEL,
    ),

    /** Black and white, no translucency, no motion: the wave draws one still frame. */
    MONOCHROME_EINK(
        "Monochrome E-Ink",
        ThemePalette(top = ColorMath.BLACK, bottom = 0xFF1A1A1A.toInt(), wave = ColorMath.WHITE, glow = ColorMath.WHITE),
        SurfacePreset.EINK_HIGH_CONTRAST,
        motion = MotionProfile.INSTANT,
        stillMotion = true,
        allowsArt = false,
        allowsAmbient = false,
    ),
    ;

    /** [base] (the pack's layout) as this profile draws it: E-Ink moves instantly and never scrolls text. */
    fun layout(base: ThemeLayout): ThemeLayout {
        if (motion == null && !stillMotion) return base
        var l = base
        motion?.let { if (l.motion.profile != it) l = l.copy(motion = dev.xross.launcher.theme.pack.Motion(it)) }
        if (stillMotion && l.textOverflow == TextOverflowMode.MARQUEE) l = l.copy(textOverflow = TextOverflowMode.ELLIPSIS)
        return l
    }

    companion object {
        fun byName(name: String?): QuickProfile =
            entries.firstOrNull { it.name.equals(name?.trim(), ignoreCase = true) } ?: NONE

        /** The canonical spelling for `settings.json`. */
        fun normalize(name: String?): String = byName(name?.uppercase(Locale.ROOT)).name
    }
}

/**
 * The live inputs the color layers above the theme read.
 */
@Immutable
data class AmbientState(
    val minuteOfDay: Int = 12 * 60,
    val battery: BatteryLevel? = null,
)

/** Which of the optional color layers are on, after the pack's defaults and the user's choice. */
@Immutable
data class ColorSwitches(
    val circadian: Boolean = false,
    val batteryTint: Boolean = false,
    val adaptive: Boolean = true,
) {
    companion object {
        /** The user's choice (null: follow the pack), else the pack's, else the defaults of A.7. */
        fun resolve(
            pack: dev.xross.launcher.theme.pack.ColorFlags,
            userCircadian: Boolean?,
            userBatteryTint: Boolean?,
            userAdaptive: Boolean?,
            profile: QuickProfile,
        ) = ColorSwitches(
            circadian = profile.allowsAmbient && (userCircadian ?: pack.circadianCurve ?: false),
            batteryTint = profile.allowsAmbient && (userBatteryTint ?: pack.batteryTint ?: false),
            adaptive = profile.allowsArt && (userAdaptive ?: pack.adaptiveGamePalette ?: true),
        )
    }
}

/**
 * The colors on screen, layered lowest to highest (Appendix A.4 and A.7):
 *  1. [themed]: the pack's look (Default → pack → variant → overrides → context rules) or the
 *     user's theme color (`display.theme`) when it isn't "Theme pack", with the wave tint;
 *  2. the user's quick profile ([QuickProfile.palette]);
 *  3. the circadian curve and the battery tint, when [switches] turn them on;
 *  4. the focused game's [art] colors, when [ColorSwitches.adaptive] is on.
 * OLED True Black keeps its black canvas under game art (the art drives the accents only).
 */
object ColorPipeline {
    fun resolve(
        themed: ThemePalette,
        profile: QuickProfile,
        switches: ColorSwitches,
        ambient: AmbientState,
        art: GameColors?,
    ): ThemePalette {
        var p = profile.palette ?: themed
        if (switches.circadian) p = Circadian.apply(p, ambient.minuteOfDay)
        if (switches.batteryTint) p = BatteryTint.apply(p, ambient.battery)
        if (art != null && switches.adaptive) {
            val a = art.toPalette()
            p = if (profile == QuickProfile.OLED_TRUE_BLACK) p.copy(wave = ColorMath.withValue(a.wave, 0.35f), glow = a.glow) else a
            // A low battery still shows over game art.
            if (switches.batteryTint && BatteryTint.low(ambient.battery)) p = BatteryTint.apply(p, ambient.battery)
        }
        return p
    }

    /** The GL wave pulses green while charging, when the battery tint is on. */
    fun chargePulse(switches: ColorSwitches, ambient: AmbientState): Boolean =
        switches.batteryTint && ambient.battery?.charging == true
}
