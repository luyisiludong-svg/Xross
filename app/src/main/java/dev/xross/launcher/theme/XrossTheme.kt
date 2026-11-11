package dev.xross.launcher.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import java.time.LocalDate

/**
 * The colors one theme draws with. [top]/[bottom] form the base gradient, [wave] tints the light
 * sheets, [glow] is the accent used for focus glows in the UI. ARGB ints so the GL thread can read
 * them without touching Compose types.
 */
@Immutable
data class ThemePalette(
    val top: Int,
    val bottom: Int,
    val wave: Int,
    val glow: Int = 0xFFFFFFFF.toInt(),
) {
    val topColor: Color get() = Color(top)
    val bottomColor: Color get() = Color(bottom)
    val glowColor: Color get() = Color(glow)
}

/**
 * Background color themes. Fixed themes always draw [fixed]; [ORIGINAL] follows the calendar
 * (a color per month, see [SeasonalPalette]) the way the console's default theme does.
 */
enum class XrossTheme(
    val label: String,
    private val fixed: ThemePalette,
    /** True when the palette depends on the date; callers must refresh it as days pass. */
    val dynamic: Boolean = false,
) {
    LAVENDER("Lavender", ThemePalette(0xFF2B2850.toInt(), 0xFF5D5994.toInt(), 0xFFD9D6FF.toInt())),
    CRIMSON("Crimson", ThemePalette(0xFF2A0002.toInt(), 0xFFC20612.toInt(), 0xFFFF4A4A.toInt(), 0xFFFFE6E6.toInt())),
    SAPPHIRE("Sapphire", ThemePalette(0xFF04103A.toInt(), 0xFF2058B8.toInt(), 0xFFB4DCFF.toInt())),
    EMERALD("Emerald", ThemePalette(0xFF01261A.toInt(), 0xFF138A57.toInt(), 0xFF9CFFD2.toInt())),
    AMBER("Amber", ThemePalette(0xFF2E1600.toInt(), 0xFFC07010.toInt(), 0xFFFFD27A.toInt())),
    ROSE("Rose", ThemePalette(0xFF2E0A22.toInt(), 0xFFB04C8E.toInt(), 0xFFFFC6EA.toInt())),
    MIDNIGHT("Midnight", ThemePalette(0xFF040406.toInt(), 0xFF22222E.toInt(), 0xFFA6A6C0.toInt())),

    /** Changes with the month. [fixed] is only a fallback; [palette] computes the real colors. */
    ORIGINAL("Original", ThemePalette(0xFF2B2850.toInt(), 0xFF5D5994.toInt(), 0xFFD9D6FF.toInt()), dynamic = true);

    /** Colors for [date]. Fixed themes ignore the date. */
    fun palette(date: LocalDate = LocalDate.now()): ThemePalette =
        if (dynamic) SeasonalPalette.forDate(date) else fixed

    companion object {
        fun byName(name: String?): XrossTheme = entries.firstOrNull { it.name == name } ?: LAVENDER
    }
}

fun Int.rgb(): FloatArray = floatArrayOf(
    ((this shr 16) and 0xFF) / 255f,
    ((this shr 8) and 0xFF) / 255f,
    (this and 0xFF) / 255f,
)
