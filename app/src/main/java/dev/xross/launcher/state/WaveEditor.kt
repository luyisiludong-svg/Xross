package dev.xross.launcher.state

import androidx.compose.runtime.Immutable
import dev.xross.launcher.data.XrossSettings
import dev.xross.launcher.input.NavAction
import dev.xross.launcher.theme.WaveLimits
import dev.xross.launcher.theme.WaveTint
import dev.xross.launcher.theme.pack.ThemeManifest

/** Rows of Settings ▸ Theme ▸ Wave settings. Slider rows have a [min]–[max] range. */
enum class WaveRow(val label: String, val slider: Boolean) {
    ENABLED("Wave background", false),
    SPEED("Speed", true),
    LINES("Ribbon lines", true),
    TURBULENCE("Turbulence", true),
    THICKNESS("Ribbon thickness", true),
    TINT("Colors", false),
    RESET("Reset to theme defaults", false),
}

/** The open Wave settings panel; [index] is the focused row. */
@Immutable
data class WaveEditorState(val index: Int = 1) {
    val row: WaveRow get() = WaveRow.entries[index.coerceIn(0, WaveRow.entries.lastIndex)]
}

/** What an input did: the new panel state, settings to save (null = unchanged), and whether to close. */
data class WaveEditorOutcome(
    val state: WaveEditorState,
    val settings: XrossSettings? = null,
    val close: Boolean = false,
    /** The input changed nothing it could (a slider at its end, Up on the first row). */
    val blocked: Boolean = false,
)

/**
 * Pure logic of the Wave settings panel: Up/Down pick a row, Left/Right move a slider or step a
 * choice, Confirm toggles / steps forward / resets, Back closes. Every change is saved at once, and
 * the wave behind the panel redraws with it, which is the preview.
 */
object WaveEditor {
    val rows: List<WaveRow> = WaveRow.entries

    fun onAction(state: WaveEditorState, action: NavAction, s: XrossSettings, defaults: ThemeManifest.WaveDefaults?): WaveEditorOutcome =
        when (action) {
            NavAction.UP -> move(state, -1)
            NavAction.DOWN -> move(state, +1)
            NavAction.LEFT -> change(state, adjust(state.row, s, -1))
            NavAction.RIGHT -> change(state, adjust(state.row, s, +1))
            NavAction.CONFIRM, NavAction.START -> change(state, activate(state.row, s, defaults))
            NavAction.BACK, NavAction.OPTIONS -> WaveEditorOutcome(state, close = true)
            NavAction.PREV_CATEGORY, NavAction.NEXT_CATEGORY, NavAction.MEDIA -> WaveEditorOutcome(state)
        }

    private fun move(state: WaveEditorState, delta: Int): WaveEditorOutcome {
        val next = (state.index + delta).coerceIn(0, rows.lastIndex)
        return if (next == state.index) WaveEditorOutcome(state, blocked = true) else WaveEditorOutcome(state.copy(index = next))
    }

    private fun change(state: WaveEditorState, next: XrossSettings?): WaveEditorOutcome =
        if (next == null) WaveEditorOutcome(state, blocked = true) else WaveEditorOutcome(state, settings = next)

    /** Left/Right: one step; null when the value can't move that way. */
    fun adjust(row: WaveRow, s: XrossSettings, direction: Int): XrossSettings? {
        val next = when (row) {
            WaveRow.ENABLED -> s.copy(waveEnabled = direction > 0)
            WaveRow.SPEED -> s.copy(waveSpeed = WaveLimits.step(s.waveSpeed, direction * WaveLimits.SPEED_STEP, WaveLimits.SPEED))
            WaveRow.LINES -> s.copy(waveLineCount = WaveLimits.clampLines(s.waveLineCount + direction))
            WaveRow.TURBULENCE -> s.copy(waveTurbulence = WaveLimits.step(s.waveTurbulence, direction * WaveLimits.TURBULENCE_STEP, WaveLimits.TURBULENCE))
            WaveRow.THICKNESS -> s.copy(waveThickness = WaveLimits.step(s.waveThickness, direction * WaveLimits.THICKNESS_STEP, WaveLimits.THICKNESS))
            WaveRow.TINT -> {
                val tints = WaveTint.entries
                val i = tints.indexOf(WaveTint.byName(s.waveTint))
                s.copy(waveTint = tints[(i + direction).mod(tints.size)].name)
            }
            WaveRow.RESET -> return null
        }
        return next.takeIf { it != s }
    }

    /** Confirm: toggle, step a choice forward, or reset. */
    fun activate(row: WaveRow, s: XrossSettings, defaults: ThemeManifest.WaveDefaults?): XrossSettings? = when (row) {
        WaveRow.ENABLED -> s.copy(waveEnabled = !s.waveEnabled)
        WaveRow.TINT -> adjust(row, s, +1)
        WaveRow.RESET -> reset(s, defaults).takeIf { it != s }
        else -> null
    }

    /** The active theme pack's wave defaults (or the built-in ones), with the theme's own colors. */
    fun reset(s: XrossSettings, defaults: ThemeManifest.WaveDefaults?): XrossSettings = s.copy(
        waveEnabled = true,
        waveSpeed = WaveLimits.clampSpeed(defaults?.speed ?: WaveLimits.DEFAULT_SPEED),
        waveLineCount = WaveLimits.clampLines(defaults?.lineCount ?: WaveLimits.DEFAULT_LINES),
        waveTurbulence = WaveLimits.clampTurbulence(defaults?.turbulence ?: WaveLimits.DEFAULT_TURBULENCE),
        waveThickness = WaveLimits.clampThickness(defaults?.ribbonThickness ?: WaveLimits.DEFAULT_THICKNESS),
        waveTint = WaveTint.THEME.name,
    )

    /** Text shown at the right of a row. */
    fun value(row: WaveRow, s: XrossSettings): String = when (row) {
        WaveRow.ENABLED -> if (s.waveEnabled) "On" else "Off"
        WaveRow.SPEED -> "${MenuTree.formatDecimal(s.waveSpeed)}×"
        WaveRow.LINES -> s.waveLineCount.toString()
        WaveRow.TURBULENCE -> "${MenuTree.formatDecimal(s.waveTurbulence)}×"
        WaveRow.THICKNESS -> "${MenuTree.formatDecimal(s.waveThickness)}×"
        WaveRow.TINT -> WaveTint.byName(s.waveTint).let { if (it == WaveTint.CUSTOM) "Custom (settings.json)" else it.label }
        WaveRow.RESET -> ""
    }

    /** Slider position 0–1, or null for rows that aren't sliders. */
    fun fraction(row: WaveRow, s: XrossSettings): Float? {
        fun f(v: Float, r: ClosedFloatingPointRange<Float>) = ((v - r.start) / (r.endInclusive - r.start)).coerceIn(0f, 1f)
        return when (row) {
            WaveRow.SPEED -> f(s.waveSpeed, WaveLimits.SPEED)
            WaveRow.LINES -> s.waveLineCount.toFloat() / WaveLimits.LINES.last
            WaveRow.TURBULENCE -> f(s.waveTurbulence, WaveLimits.TURBULENCE)
            WaveRow.THICKNESS -> f(s.waveThickness, WaveLimits.THICKNESS)
            else -> null
        }
    }

    /** Touch: a slider dragged to [fraction] (0–1). */
    fun setFraction(row: WaveRow, s: XrossSettings, fraction: Float): XrossSettings {
        val x = fraction.coerceIn(0f, 1f)
        fun lerp(r: ClosedFloatingPointRange<Float>) = Math.round((r.start + (r.endInclusive - r.start) * x) * 10f) / 10f
        return when (row) {
            WaveRow.SPEED -> s.copy(waveSpeed = WaveLimits.clampSpeed(lerp(WaveLimits.SPEED)))
            WaveRow.LINES -> s.copy(waveLineCount = WaveLimits.clampLines(Math.round(x * WaveLimits.LINES.last)))
            WaveRow.TURBULENCE -> s.copy(waveTurbulence = WaveLimits.clampTurbulence(lerp(WaveLimits.TURBULENCE)))
            WaveRow.THICKNESS -> s.copy(waveThickness = WaveLimits.clampThickness(lerp(WaveLimits.THICKNESS)))
            else -> s
        }
    }
}
