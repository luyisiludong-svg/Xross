package dev.wave.launcher.theme

import androidx.compose.ui.graphics.Color

/**
 * Background color themes. [top]/[bottom] form the base gradient, [wave] tints the light
 * sheets, [glow] is the accent used for focus glows in the UI.
 */
enum class WaveTheme(
    val label: String,
    val top: Int,
    val bottom: Int,
    val wave: Int,
    val glow: Int = 0xFFFFFFFF.toInt(),
) {
    LAVENDER("Lavender", 0xFF2B2850.toInt(), 0xFF5D5994.toInt(), 0xFFD9D6FF.toInt()),
    CRIMSON("Crimson", 0xFF2A0002.toInt(), 0xFFC20612.toInt(), 0xFFFF4A4A.toInt(), 0xFFFFE6E6.toInt()),
    SAPPHIRE("Sapphire", 0xFF04103A.toInt(), 0xFF2058B8.toInt(), 0xFFB4DCFF.toInt()),
    EMERALD("Emerald", 0xFF01261A.toInt(), 0xFF138A57.toInt(), 0xFF9CFFD2.toInt()),
    AMBER("Amber", 0xFF2E1600.toInt(), 0xFFC07010.toInt(), 0xFFFFD27A.toInt()),
    ROSE("Rose", 0xFF2E0A22.toInt(), 0xFFB04C8E.toInt(), 0xFFFFC6EA.toInt()),
    MIDNIGHT("Midnight", 0xFF040406.toInt(), 0xFF22222E.toInt(), 0xFFA6A6C0.toInt());

    val topColor get() = Color(top)
    val bottomColor get() = Color(bottom)
    val glowColor get() = Color(glow)

    companion object {
        fun byName(name: String?): WaveTheme = entries.firstOrNull { it.name == name } ?: LAVENDER
    }
}

fun Int.rgb(): FloatArray = floatArrayOf(
    ((this shr 16) and 0xFF) / 255f,
    ((this shr 8) and 0xFF) / 255f,
    (this and 0xFF) / 255f,
)
