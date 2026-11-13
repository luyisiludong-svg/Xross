package dev.xross.launcher

import dev.xross.launcher.theme.SeasonalPalette
import dev.xross.launcher.theme.ThemePalette
import dev.xross.launcher.theme.XrossTheme
import dev.xross.launcher.theme.millisUntilNextDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Month
import kotlin.math.abs

class SeasonalPaletteTest {

    @Test fun midMonthShowsThatMonthsOwnColor() {
        for (month in Month.entries) {
            val mid = LocalDate.of(2026, month, 15)
            assertEquals(month.name, SeasonalPalette.forMonth(month), SeasonalPalette.forDate(mid))
        }
    }

    @Test fun everyMonthHasADistinctColor() {
        val palettes = Month.entries.map { SeasonalPalette.forMonth(it) }
        assertEquals(12, palettes.toSet().size)
    }

    @Test fun paletteIsDarkAtTheTopAndPaleInTheWaves() {
        for (month in Month.entries) {
            val p = SeasonalPalette.forMonth(month)
            assertTrue("$month top darker than bottom", luma(p.top) < luma(p.bottom))
            assertTrue("$month wave lighter than bottom", luma(p.wave) > luma(p.bottom))
            assertEquals("$month opaque", 0xFF, (p.top ushr 24))
        }
    }

    @Test fun monthBoundariesCrossfadeWithoutJumps() {
        // Walk every day of two years (crossing the Dec → Jan wrap) and require small daily steps.
        var day = LocalDate.of(2025, 1, 1)
        var previous = SeasonalPalette.forDate(day)
        var largestStep = 0
        while (day.isBefore(LocalDate.of(2027, 1, 1))) {
            day = day.plusDays(1)
            val next = SeasonalPalette.forDate(day)
            largestStep = maxOf(largestStep, distance(previous, next))
            previous = next
        }
        // A hard switch between, say, December red and January silver is a step of 100+.
        assertTrue("largest daily step $largestStep", largestStep <= 60)
    }

    @Test fun boundaryDaysAreBlendsOfBothMonths() {
        val lastOfOctober = SeasonalPalette.forDate(LocalDate.of(2026, 10, 31))
        val firstOfNovember = SeasonalPalette.forDate(LocalDate.of(2026, 11, 1))
        val october = SeasonalPalette.forMonth(Month.OCTOBER)
        val november = SeasonalPalette.forMonth(Month.NOVEMBER)
        for (p in listOf(lastOfOctober, firstOfNovember)) {
            assertNotEquals(october, p)
            assertNotEquals(november, p)
        }
        // Each side of the boundary leans toward its own month.
        assertTrue(distance(lastOfOctober, october) < distance(lastOfOctober, november))
        assertTrue(distance(firstOfNovember, november) < distance(firstOfNovember, october))
        // Outside the window the month's color is pure again.
        assertEquals(november, SeasonalPalette.forDate(LocalDate.of(2026, 11, 1 + SeasonalPalette.BLEND_DAYS)))
    }

    @Test fun leapDayIsHandled() {
        val p = SeasonalPalette.forDate(LocalDate.of(2028, 2, 29))
        assertNotEquals(SeasonalPalette.forMonth(Month.FEBRUARY), p) // in the end-of-month blend
    }

    @Test fun originalThemeFollowsTheDateAndFixedThemesDoNot() {
        val june = LocalDate.of(2026, 6, 15)
        val december = LocalDate.of(2026, 12, 15)
        assertTrue(XrossTheme.ORIGINAL.dynamic)
        assertNotEquals(XrossTheme.ORIGINAL.palette(june), XrossTheme.ORIGINAL.palette(december))
        assertEquals(XrossTheme.CRIMSON.palette(june), XrossTheme.CRIMSON.palette(december))
        assertEquals(XrossTheme.ORIGINAL, XrossTheme.byName("ORIGINAL"))
        assertEquals(13, XrossTheme.entries.size)
    }

    @Test fun hsvConversionMatchesKnownColors() {
        assertEquals(0xFFFF0000.toInt(), SeasonalPalette.hsv(0f, 1f, 1f))
        assertEquals(0xFF00FF00.toInt(), SeasonalPalette.hsv(120f, 1f, 1f))
        assertEquals(0xFF0000FF.toInt(), SeasonalPalette.hsv(240f, 1f, 1f))
        assertEquals(0xFFFFFFFF.toInt(), SeasonalPalette.hsv(77f, 0f, 1f))
        assertEquals(0xFF000000.toInt(), SeasonalPalette.hsv(300f, 1f, 0f))
        assertEquals(0xFFFF0000.toInt(), SeasonalPalette.hsv(360f, 1f, 1f)) // wraps
    }

    @Test fun refreshIsScheduledJustAfterMidnight() {
        val evening = LocalDateTime.of(2026, 11, 11, 23, 0, 0)
        assertEquals(60 * 60_000L + 1_000L, millisUntilNextDay(evening))
        // Never a zero or negative wait, even right at midnight.
        assertTrue(millisUntilNextDay(LocalDateTime.of(2026, 11, 12, 0, 0, 0)) >= 1_000L)
    }

    private fun channel(c: Int, shift: Int) = (c shr shift) and 0xFF
    private fun luma(c: Int) = 0.2126 * channel(c, 16) + 0.7152 * channel(c, 8) + 0.0722 * channel(c, 0)

    /** Largest single-channel difference across all four palette colors. */
    private fun distance(a: ThemePalette, b: ThemePalette): Int =
        listOf(a.top to b.top, a.bottom to b.bottom, a.wave to b.wave, a.glow to b.glow).maxOf { (x, y) ->
            listOf(16, 8, 0).maxOf { abs(channel(x, it) - channel(y, it)) }
        }
}
