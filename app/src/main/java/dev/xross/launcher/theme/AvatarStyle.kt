package dev.xross.launcher.theme

import dev.xross.launcher.theme.pack.ThemeManifest
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/**
 * How the profile avatar is drawn: `profile.avatarStyle` in `settings.json`, edited in
 * Settings ▸ Theme ▸ Profile customization. Ranges are in [AvatarStyleLimits]; values read from
 * disk go through [AvatarStyleLimits.clamp].
 */
@Serializable
data class AvatarStyleConfig(
    /** `#RRGGBB` ring and glow color. */
    val borderColorHex: String = "#FFB5C5",
    /** 1–6 dp. */
    val borderWidthDp: Float = 2.0f,
    val glowEnabled: Boolean = true,
    /** 0.5×–2.0× pulse rate. */
    val glowSpeed: Float = 1.0f,
    /** 0.1–1.0: how strong and how wide the glow is. */
    val glowIntensity: Float = 0.6f,
)

/** A named ring color in the Profile customization panel. */
enum class AvatarColorPreset(val label: String, val hex: String) {
    PASTEL_PINK("Pastel pink", "#FFB5C5"),
    NEON_CYAN("Neon cyan", "#00E5FF"),
    GOLD("Gold", "#FFD54A"),
    WHITE("White", "#FFFFFF"),
    ;

    companion object {
        fun of(hex: String): AvatarColorPreset? = entries.firstOrNull { it.hex.equals(hex.trim(), ignoreCase = true) }
    }
}

/** Ranges and steps of [AvatarStyleConfig], shared by `settings.json` clamping and the sliders. */
object AvatarStyleLimits {
    val WIDTH = 1f..6f
    const val WIDTH_STEP = 0.5f
    val SPEED = 0.5f..2f
    const val SPEED_STEP = 0.1f
    val INTENSITY = 0.1f..1f
    const val INTENSITY_STEP = 0.1f

    private val D = AvatarStyleConfig()

    /** Out-of-range numbers are clamped; an unreadable color falls back to the default on its own. */
    fun clamp(c: AvatarStyleConfig): AvatarStyleConfig = c.copy(
        borderColorHex = normalizeColor(c.borderColorHex) ?: D.borderColorHex,
        borderWidthDp = c.borderWidthDp.finiteOr(D.borderWidthDp).coerceIn(WIDTH.start, WIDTH.endInclusive),
        glowSpeed = c.glowSpeed.finiteOr(D.glowSpeed).coerceIn(SPEED.start, SPEED.endInclusive),
        glowIntensity = c.glowIntensity.finiteOr(D.glowIntensity).coerceIn(INTENSITY.start, INTENSITY.endInclusive),
    )

    /** "#fb5", "FFB5C5" or "#FFB5C5" → "#FFB5C5"; null when it isn't a color. */
    fun normalizeColor(text: String): String? = ThemeManifest.parseColor(text)?.let(ThemeManifest::colorText)

    /** ARGB of the ring color (opaque), the default when the text doesn't parse. */
    fun color(c: AvatarStyleConfig): Int =
        (ThemeManifest.parseColor(c.borderColorHex) ?: ThemeManifest.parseColor(D.borderColorHex)!!) or 0xFF000000.toInt()

    /** One slider step, rounded to [step] so repeated steps don't drift. */
    fun step(value: Float, delta: Float, step: Float, range: ClosedFloatingPointRange<Float>): Float =
        (((value + delta) / step).roundToInt() * step).let { (it * 100f).roundToInt() / 100f }
            .coerceIn(range.start, range.endInclusive)

    private fun Float.finiteOr(fallback: Float) = if (isFinite()) this else fallback
}
