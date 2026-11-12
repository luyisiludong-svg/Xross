package dev.xross.launcher.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

/** How long the cursor rests on a game before its system's theme override applies. */
const val SYSTEM_LOOK_DELAY_MS = 250L

/**
 * [system] once it has held for [SYSTEM_LOOK_DELAY_MS], so scrolling through a list that mixes
 * systems doesn't flash between their overrides; a new [category] takes its system at once.
 */
@Composable
fun rememberSettledSystem(system: String?, category: String?): String? {
    var settled by remember { mutableStateOf(system) }
    var settledCategory by remember { mutableStateOf(category) }
    LaunchedEffect(system, category) {
        if (category != settledCategory) {
            settledCategory = category
            settled = system
        } else if (system != settled) {
            delay(SYSTEM_LOOK_DELAY_MS)
            settled = system
        }
    }
    // Same frame as the category switch: don't show the old system's override in the new category.
    return if (category != settledCategory) system else settled
}
