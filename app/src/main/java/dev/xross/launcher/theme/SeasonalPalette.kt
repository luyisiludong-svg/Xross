package dev.xross.launcher.theme

import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The "Original" theme: one color per month, like the console's default theme.
 *
 * Each month is an anchor hue, saturation and brightness. The four palette colors are derived
 * from the anchor the same way for every month (a dark top, a mid-tone bottom, a pale wave tint
 * and a near-white glow), so every month reads as part of one theme. The month's own color holds
 * for most of the month; over the last and first [BLEND_DAYS] days around a month boundary it
 * crossfades into the next month in equal daily steps, so the change is never a jump.
 *
 * Pure: the caller supplies the date (see [rememberThemePalette]), so it is fully unit-testable.
 */
object SeasonalPalette {

    /** Days on each side of a month boundary over which neighboring months blend. */
    const val BLEND_DAYS = 3

    /** Month anchor: hue in degrees, saturation 0–1, brightness scale 0–1. */
    internal data class Anchor(val hue: Float, val saturation: Float, val value: Float = 1f)

    /** January first. Seasonal for the northern hemisphere, as on the original hardware. */
    internal val ANCHORS: List<Anchor> = listOf(
        Anchor(hue = 215f, saturation = 0.16f, value = 0.92f), // January: frost silver
        Anchor(hue = 48f, saturation = 0.72f, value = 0.92f), //  February: winter gold
        Anchor(hue = 92f, saturation = 0.62f), //                  March: new leaves
        Anchor(hue = 332f, saturation = 0.48f), //                 April: blossom pink
        Anchor(hue = 140f, saturation = 0.70f, value = 0.88f), //  May: deep green
        Anchor(hue = 268f, saturation = 0.52f), //                 June: rain violet
        Anchor(hue = 182f, saturation = 0.74f, value = 0.88f), //  July: lagoon teal
        Anchor(hue = 204f, saturation = 0.80f), //                 August: summer sky
        Anchor(hue = 288f, saturation = 0.58f, value = 0.90f), //  September: dusk purple
        Anchor(hue = 33f, saturation = 0.86f), //                  October: harvest amber
        Anchor(hue = 20f, saturation = 0.62f, value = 0.66f), //   November: russet
        Anchor(hue = 356f, saturation = 0.86f, value = 0.95f), //  December: winter red
    )

    /** The palette shown on [date]. */
    fun forDate(date: LocalDate): ThemePalette {
        val month = date.monthValue - 1
        val pos = date.dayOfMonth - 1
        val days = date.lengthOfMonth()
        val here = paletteOf(ANCHORS[month])
        return when {
            pos < BLEND_DAYS -> {
                // Arriving from the previous month: just past halfway at day 1, fully here after the window.
                val weightHere = 0.5f + (pos + 0.5f) / (2f * BLEND_DAYS)
                blend(paletteOf(ANCHORS[(month + 11) % 12]), here, weightHere)
            }
            pos >= days - BLEND_DAYS -> {
                // Leaving for the next month: just short of halfway on the last day.
                val weightNext = 0.5f - (days - pos - 0.5f) / (2f * BLEND_DAYS)
                blend(here, paletteOf(ANCHORS[(month + 1) % 12]), weightNext)
            }
            else -> here
        }
    }

    /** The month's pure color, ignoring the boundary blend; used for previews and tests. */
    fun forMonth(month: Month): ThemePalette = paletteOf(ANCHORS[month.value - 1])

    /** Month name for [date] ("November"), for the theme settings row. */
    fun describe(date: LocalDate, locale: Locale = Locale.getDefault()): String =
        date.month.getDisplayName(TextStyle.FULL, locale)

    internal fun paletteOf(a: Anchor): ThemePalette = ThemePalette(
        top = hsv(a.hue, a.saturation * 0.95f, 0.16f * a.value),
        bottom = hsv(a.hue, a.saturation * 0.92f, 0.64f * a.value),
        wave = hsv(a.hue, a.saturation * 0.38f, 1f),
        glow = hsv(a.hue, a.saturation * 0.12f, 1f),
    )

    /** Linear RGB crossfade; [t] = 0 gives [a], 1 gives [b]. */
    internal fun blend(a: ThemePalette, b: ThemePalette, t: Float): ThemePalette = ThemePalette(
        top = lerpArgb(a.top, b.top, t),
        bottom = lerpArgb(a.bottom, b.bottom, t),
        wave = lerpArgb(a.wave, b.wave, t),
        glow = lerpArgb(a.glow, b.glow, t),
    )

    internal fun lerpArgb(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int): Int {
            val x = (a shr shift) and 0xFF
            val y = (b shr shift) and 0xFF
            return (x + (y - x) * t).roundToInt().coerceIn(0, 255) shl shift
        }
        return (0xFF shl 24) or ch(16) or ch(8) or ch(0)
    }

    /** HSV → opaque ARGB. Pure Kotlin so it runs in plain JVM tests (no android.graphics). */
    internal fun hsv(hueDegrees: Float, saturation: Float, value: Float): Int {
        val h = ((hueDegrees % 360f) + 360f) % 360f
        val s = saturation.coerceIn(0f, 1f)
        val v = value.coerceIn(0f, 1f)
        val c = v * s
        val x = c * (1 - abs((h / 60f) % 2f - 1))
        val m = v - c
        val (r, g, b) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun byte(f: Float) = ((f + m) * 255f).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (byte(r) shl 16) or (byte(g) shl 8) or byte(b)
    }
}
