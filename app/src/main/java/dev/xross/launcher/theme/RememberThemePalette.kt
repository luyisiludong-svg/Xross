package dev.xross.launcher.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The palette for [theme], kept current: date-driven themes recompute once just after each
 * midnight; fixed themes never schedule anything. The wait is a plain suspended delay, so it
 * costs nothing between days and stops with the composition.
 */
@Composable
fun rememberThemePalette(theme: XrossTheme): ThemePalette {
    if (!theme.dynamic) return remember(theme) { theme.palette() }
    val palette by produceState(initialValue = theme.palette(LocalDate.now()), theme) {
        while (true) {
            delay(millisUntilNextDay(LocalDateTime.now()))
            value = theme.palette(LocalDate.now())
        }
    }
    return palette
}

/**
 * [rememberThemePalette] for a theme setting value, which may be [PACK_THEME]: then the active
 * theme pack's palette ([packPalette]) when it has one.
 */
@Composable
fun rememberActivePalette(themeName: String, packPalette: ThemePalette?): ThemePalette {
    val theme = XrossTheme.byName(themeName)
    val dated = rememberThemePalette(theme)
    return if (themeName == PACK_THEME && packPalette != null) packPalette else dated
}

/**
 * The local minute of the day while [active] (null otherwise), checked once a minute with a plain
 * delay. The value only changes when [bucket] of the new minute differs from the shown one, so a
 * reader keyed to it (the context rules) updates only when its answer would change.
 */
@Composable
fun rememberMinuteOfDay(active: Boolean, key: Any? = null, bucket: (Int) -> Any? = { it }): androidx.compose.runtime.State<Int?> =
    produceState<Int?>(if (active) minuteOfDay() else null, active, key) {
        if (!active) {
            value = null
            return@produceState
        }
        value = minuteOfDay()
        while (true) {
            delay(millisUntilNextMinute(java.time.LocalTime.now()))
            val m = minuteOfDay()
            val shown = value
            if (shown == null || bucket(m) != bucket(shown)) value = m
        }
    }
