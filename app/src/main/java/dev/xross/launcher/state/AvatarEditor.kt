package dev.xross.launcher.state

import androidx.compose.runtime.Immutable
import dev.xross.launcher.data.XrossSettings
import dev.xross.launcher.input.NavAction
import dev.xross.launcher.theme.AvatarColorPreset
import dev.xross.launcher.theme.AvatarStyleConfig
import dev.xross.launcher.theme.AvatarStyleLimits

/** Rows of Settings ▸ Theme ▸ Profile customization. */
enum class AvatarRow(val label: String) {
    COLOR("Border color"),
    CUSTOM_COLOR("Custom color…"),
    WIDTH("Border width"),
    GLOW("Glow"),
    SPEED("Glow speed"),
    INTENSITY("Glow intensity"),
    RESET("Reset to defaults"),
}

/** The open Profile customization panel; [index] is the focused row. */
@Immutable
data class AvatarEditorState(val index: Int = 0) {
    val row: AvatarRow get() = AvatarRow.entries[index.coerceIn(0, AvatarRow.entries.lastIndex)]
}

/** What an input did: the new panel state, a style to save (null = unchanged), and what else to do. */
data class AvatarEditorOutcome(
    val state: AvatarEditorState,
    val style: AvatarStyleConfig? = null,
    val close: Boolean = false,
    /** The input changed nothing it could (a slider at its end, Up on the first row). */
    val blocked: Boolean = false,
    /** Ask for a hex color (the Custom color row). */
    val promptColor: Boolean = false,
)

/**
 * Pure logic of the Profile customization panel: Up/Down pick a row, Left/Right move a slider or
 * step a choice, Confirm toggles / steps forward / resets, Back closes. Every change is saved at
 * once; the avatar in the panel and the one in the status bar redraw with it, which is the preview.
 */
object AvatarEditor {
    val rows: List<AvatarRow> = AvatarRow.entries

    fun onAction(state: AvatarEditorState, action: NavAction, style: AvatarStyleConfig): AvatarEditorOutcome = when (action) {
        NavAction.UP -> move(state, -1)
        NavAction.DOWN -> move(state, +1)
        NavAction.LEFT -> change(state, adjust(state.row, style, -1))
        NavAction.RIGHT -> change(state, adjust(state.row, style, +1))
        NavAction.CONFIRM, NavAction.START ->
            if (state.row == AvatarRow.CUSTOM_COLOR) AvatarEditorOutcome(state, promptColor = true) else change(state, activate(state.row, style))
        NavAction.BACK, NavAction.OPTIONS -> AvatarEditorOutcome(state, close = true)
        NavAction.PREV_CATEGORY, NavAction.NEXT_CATEGORY, NavAction.MEDIA -> AvatarEditorOutcome(state)
    }

    private fun move(state: AvatarEditorState, delta: Int): AvatarEditorOutcome {
        val next = (state.index + delta).coerceIn(0, rows.lastIndex)
        return if (next == state.index) AvatarEditorOutcome(state, blocked = true) else AvatarEditorOutcome(state.copy(index = next))
    }

    private fun change(state: AvatarEditorState, next: AvatarStyleConfig?): AvatarEditorOutcome =
        if (next == null) AvatarEditorOutcome(state, blocked = true) else AvatarEditorOutcome(state, style = next)

    /** Left/Right: one step; null when the value can't move that way. */
    fun adjust(row: AvatarRow, s: AvatarStyleConfig, direction: Int): AvatarStyleConfig? {
        val l = AvatarStyleLimits
        val next = when (row) {
            AvatarRow.COLOR -> {
                val presets = AvatarColorPreset.entries
                // From a custom color, Right starts at the first preset and Left at the last.
                val i = AvatarColorPreset.of(s.borderColorHex)?.ordinal ?: if (direction > 0) -1 else 0
                s.copy(borderColorHex = presets[(i + direction).mod(presets.size)].hex)
            }
            AvatarRow.WIDTH -> s.copy(borderWidthDp = l.step(s.borderWidthDp, direction * l.WIDTH_STEP, l.WIDTH_STEP, l.WIDTH))
            AvatarRow.GLOW -> s.copy(glowEnabled = direction > 0)
            AvatarRow.SPEED -> s.copy(glowSpeed = l.step(s.glowSpeed, direction * l.SPEED_STEP, l.SPEED_STEP, l.SPEED))
            AvatarRow.INTENSITY -> s.copy(glowIntensity = l.step(s.glowIntensity, direction * l.INTENSITY_STEP, l.INTENSITY_STEP, l.INTENSITY))
            AvatarRow.CUSTOM_COLOR, AvatarRow.RESET -> return null
        }
        return next.takeIf { it != s }
    }

    /** Confirm: toggle, step a choice forward, or reset. */
    fun activate(row: AvatarRow, s: AvatarStyleConfig): AvatarStyleConfig? = when (row) {
        AvatarRow.COLOR -> adjust(row, s, +1)
        AvatarRow.GLOW -> s.copy(glowEnabled = !s.glowEnabled)
        AvatarRow.RESET -> AvatarStyleConfig().takeIf { it != s }
        else -> null
    }

    /** Text shown at the right of a row. */
    fun value(row: AvatarRow, s: AvatarStyleConfig): String = when (row) {
        AvatarRow.COLOR -> AvatarColorPreset.of(s.borderColorHex)?.label ?: "Custom"
        AvatarRow.CUSTOM_COLOR -> s.borderColorHex
        AvatarRow.WIDTH -> "${MenuTree.formatDecimal(s.borderWidthDp)} dp"
        AvatarRow.GLOW -> if (s.glowEnabled) "On" else "Off"
        AvatarRow.SPEED -> "${MenuTree.formatDecimal(s.glowSpeed)}×"
        AvatarRow.INTENSITY -> "${Math.round(s.glowIntensity * 100)}%"
        AvatarRow.RESET -> ""
    }

    /** Slider position 0–1, or null for rows that aren't sliders. */
    fun fraction(row: AvatarRow, s: AvatarStyleConfig): Float? {
        fun f(v: Float, r: ClosedFloatingPointRange<Float>) = ((v - r.start) / (r.endInclusive - r.start)).coerceIn(0f, 1f)
        return when (row) {
            AvatarRow.WIDTH -> f(s.borderWidthDp, AvatarStyleLimits.WIDTH)
            AvatarRow.SPEED -> f(s.glowSpeed, AvatarStyleLimits.SPEED)
            AvatarRow.INTENSITY -> f(s.glowIntensity, AvatarStyleLimits.INTENSITY)
            else -> null
        }
    }

    /** Touch: a slider dragged to [fraction] (0–1), snapped to the row's step. */
    fun setFraction(row: AvatarRow, s: AvatarStyleConfig, fraction: Float): AvatarStyleConfig {
        val x = fraction.coerceIn(0f, 1f)
        val l = AvatarStyleLimits
        fun at(r: ClosedFloatingPointRange<Float>, step: Float) = l.step(r.start + (r.endInclusive - r.start) * x, 0f, step, r)
        return when (row) {
            AvatarRow.WIDTH -> s.copy(borderWidthDp = at(l.WIDTH, l.WIDTH_STEP))
            AvatarRow.SPEED -> s.copy(glowSpeed = at(l.SPEED, l.SPEED_STEP))
            AvatarRow.INTENSITY -> s.copy(glowIntensity = at(l.INTENSITY, l.INTENSITY_STEP))
            else -> s
        }
    }

    /** [style] written into [settings]. */
    fun apply(settings: XrossSettings, style: AvatarStyleConfig): XrossSettings = settings.copy(avatarStyle = AvatarStyleLimits.clamp(style))
}
