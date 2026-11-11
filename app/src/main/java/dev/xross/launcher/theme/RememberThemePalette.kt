package dev.xross.launcher.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import java.time.Duration
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

/** Time until a moment just after the next local midnight (never less than a second). */
internal fun millisUntilNextDay(now: LocalDateTime): Long {
    val next = now.toLocalDate().plusDays(1).atStartOfDay().plusSeconds(1)
    return Duration.between(now, next).toMillis().coerceAtLeast(1_000L)
}
