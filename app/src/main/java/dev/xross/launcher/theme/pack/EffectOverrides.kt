package dev.xross.launcher.theme.pack

import androidx.compose.runtime.Immutable
import dev.xross.launcher.theme.shader.ShaderPreset
import dev.xross.launcher.theme.shader.ShaderRef
import kotlinx.serialization.Serializable

/**
 * The user's own picks over the theme pack's effects (2b.4f, Settings ▸ Theme), saved as
 * `display.effects` in `settings.json`. Each value is an enum name, or empty to follow the pack
 * (its base, variant, overrides and context rules). A value set here wins in every context, like
 * the surface style: a pack's category override can't take the user's shader away.
 *
 * ```json
 * "effects": { "shaderPreset": "BOKEH", "focusFrameStyle": "GOLD_BEZEL", "iconMask": "NONE" }
 * ```
 * `iconMask: NONE` is the System choice: the apps' own icons even when the pack masks them.
 */
@Immutable
@Serializable
data class EffectOverrides(
    /** [ShaderPreset] other than CUSTOM (a pack's own code stays the pack's). */
    val shaderPreset: String = "",
    val backgroundFilter: String = "",
    /** [UiFilter] other than CUSTOM. */
    val uiFilter: String = "",
    val focusFrameStyle: String = "",
    val foregroundParticles: String = "",
    val iconMask: String = "",
) {
    val shader: ShaderPreset? get() = ShaderPreset.byName(shaderPreset)?.takeIf { it != ShaderPreset.CUSTOM }

    /** The overrides as a cascade patch: set values replace the look's. */
    val cosmetics: Cosmetics
        get() = Cosmetics(
            focusFrame = Cosmetics.parse<FocusFrameStyle>(focusFrameStyle),
            iconMask = Cosmetics.parse<IconMask>(iconMask),
            particles = Cosmetics.parse<ParticlePreset>(foregroundParticles),
            backgroundFilter = Cosmetics.parse<BackgroundFilter>(backgroundFilter),
            uiFilter = Cosmetics.parse<UiFilter>(uiFilter)?.takeIf { it != UiFilter.CUSTOM }?.let { UiFilterRef(it) },
        )

    /** True when nothing is overridden: the pack's look as it is. */
    val empty: Boolean get() = normalized() == NONE

    /** [look] with these choices on top: the shader and the cosmetics the user set. */
    fun applyTo(look: ThemeLook): ThemeLook {
        if (empty) return look
        val program = shader?.let(::ShaderRef) ?: look.shader
        return look.copy(shader = program, cosmetics = look.cosmetics.over(cosmetics))
    }

    /** The value of [slot] (empty: the pack's). */
    operator fun get(slot: EffectSlot): String = when (slot) {
        EffectSlot.SHADER -> shaderPreset
        EffectSlot.BACKGROUND_FILTER -> backgroundFilter
        EffectSlot.UI_FILTER -> uiFilter
        EffectSlot.FOCUS_FRAME -> focusFrameStyle
        EffectSlot.PARTICLES -> foregroundParticles
        EffectSlot.ICON_MASK -> iconMask
    }

    /** A copy with [slot] set to [value] (normalized; empty or unknown follows the pack). */
    fun with(slot: EffectSlot, value: String): EffectOverrides {
        val v = slot.normalize(value)
        return when (slot) {
            EffectSlot.SHADER -> copy(shaderPreset = v)
            EffectSlot.BACKGROUND_FILTER -> copy(backgroundFilter = v)
            EffectSlot.UI_FILTER -> copy(uiFilter = v)
            EffectSlot.FOCUS_FRAME -> copy(focusFrameStyle = v)
            EffectSlot.PARTICLES -> copy(foregroundParticles = v)
            EffectSlot.ICON_MASK -> copy(iconMask = v)
        }
    }

    /** Every value as its canonical enum name, or empty when blank or unknown (a hand-edited typo follows the pack). */
    fun normalized(): EffectOverrides = EffectSlot.entries.fold(NONE) { acc, slot -> acc.with(slot, get(slot)) }

    companion object {
        val NONE = EffectOverrides()
    }
}

/**
 * One Settings ▸ Theme row the user can set over the pack (2b.4f), with its choices in row order:
 * (enum name, label). The empty name, "Theme pack", always comes first.
 */
enum class EffectSlot(val title: String, val choices: List<Pair<String, String>>) {
    SHADER(
        "Background shader",
        listOf(
            ShaderPreset.WAVE.name to "Wave", ShaderPreset.ASCII_MATRIX.name to "Matrix", ShaderPreset.BOKEH.name to "Bokeh",
            ShaderPreset.STARFIELD.name to "Starfield", ShaderPreset.CRT.name to "CRT",
        ),
    ),
    BACKGROUND_FILTER(
        "Background filter",
        listOf(
            BackgroundFilter.NONE.name to "Off", BackgroundFilter.SCANLINES.name to "Scanlines",
            BackgroundFilter.CRT.name to "CRT grille", BackgroundFilter.LCD_GRID.name to "LCD grid",
        ),
    ),
    UI_FILTER(
        "UI filter (experimental)",
        listOf(UiFilter.NONE.name to "Off", UiFilter.SCANLINES_UI.name to "Scanlines", UiFilter.BLOOM.name to "Bloom"),
    ),
    FOCUS_FRAME(
        "Focus frame style",
        listOf(
            FocusFrameStyle.BREATHING_HALO.name to "Breathing halo", FocusFrameStyle.STATIC_NEON.name to "Static neon",
            FocusFrameStyle.HARD_SHADOW.name to "Hard shadow", FocusFrameStyle.GOLD_BEZEL.name to "Gold bezel",
        ),
    ),
    PARTICLES(
        "Ambient particles",
        listOf(
            ParticlePreset.NONE.name to "Off", ParticlePreset.SNOW.name to "Snow",
            ParticlePreset.BOKEH.name to "Bokeh", ParticlePreset.DUST_MOTES.name to "Dust motes",
        ),
    ),
    ICON_MASK(
        "App icon mask",
        listOf(
            IconMask.NONE.name to "System", IconMask.SQUIRCLE.name to "Squircle",
            IconMask.CIRCLE.name to "Circle", IconMask.Y2K_CHROME_FRAME.name to "Chrome",
        ),
    ),
    ;

    /** [text] as one of this row's names (ignoring case, `-` and spaces), else empty. */
    fun normalize(text: String): String {
        val t = text.trim().replace('-', '_').replace(' ', '_')
        if (t.isEmpty()) return ""
        return choices.firstOrNull { it.first.equals(t, ignoreCase = true) }?.first.orEmpty()
    }

    /** The label of [name] in this row; "Theme pack" for empty. */
    fun label(name: String): String = choices.firstOrNull { it.first == name }?.second ?: "Theme pack"

    /** The value after [current] in the row's cycle: theme pack, then each choice in turn. */
    fun next(current: String): String {
        val ids = listOf("") + choices.map { it.first }
        return ids[(ids.indexOf(normalize(current)).coerceAtLeast(0) + 1) % ids.size]
    }

    /** What the pack's [look] shows for this row, as a choice name. */
    fun packValue(look: ThemeLook): String = when (this) {
        SHADER -> look.shader.preset.name
        BACKGROUND_FILTER -> look.cosmetics.bgFilter.name
        UI_FILTER -> look.cosmetics.screenFilter.filter.name
        FOCUS_FRAME -> look.cosmetics.frame.name
        PARTICLES -> look.cosmetics.particleField.name
        ICON_MASK -> look.cosmetics.mask.name
    }

    /** "Theme pack (Bokeh)" for a pack value; CUSTOM names the pack's own code. */
    fun packLabel(look: ThemeLook): String {
        val v = packValue(look)
        val name = choices.firstOrNull { it.first == v }?.second ?: when (this) {
            SHADER -> if (look.shader.preset == ShaderPreset.CUSTOM) "the pack's shader" else v
            UI_FILTER -> if (v == UiFilter.CUSTOM.name) "the pack's AGSL" else v
            else -> v
        }
        return "Theme pack ($name)"
    }
}

/**
 * Settings ▸ Theme ▸ Reset to theme defaults (2b.4f): everything the user set over the active
 * pack's look goes back to the pack. Kept: which pack and variant, the theme color, the quick
 * profile (a look of its own, with its own row) and the wave's speed and shape.
 */
object ThemeDefaults {
    fun reset(s: dev.xross.launcher.data.XrossSettings): dev.xross.launcher.data.XrossSettings = s.copy(
        effectOverrides = EffectOverrides.NONE,
        surfaceStyle = "",
        customWallpaper = "",
        adaptiveGamePalette = null,
        circadianCurve = null,
        batteryTint = null,
    )

    /** True when [s] overrides nothing [reset] would clear. */
    fun isDefault(s: dev.xross.launcher.data.XrossSettings): Boolean = reset(s) == s
}
