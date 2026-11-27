package dev.xross.launcher.state

import androidx.compose.runtime.Immutable
import dev.xross.launcher.input.NavAction
import dev.xross.launcher.theme.pack.CrosshairPreset
import dev.xross.launcher.theme.pack.LayoutLimits
import dev.xross.launcher.theme.pack.LayoutOverrides
import dev.xross.launcher.theme.pack.MenuStyle
import dev.xross.launcher.theme.pack.ThemeLayout
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Rows of Settings ▸ Theme ▸ Layout (2b.8a). Slider rows draw a track under the value. */
enum class LayoutRow(val label: String, val slider: Boolean) {
    CROSSHAIR("Crosshair", false),
    UI_SCALE("UI scale", true),
    SPACING("List spacing", false),
    FOCUSED_SCALE("Focused scale", true),
    RESET("Reset layout", false),

    /** The pack's menu styles (Menu styles slice 6): shown only when its variants offer more than one. */
    MENU_STYLE("Menu style", false),
}

/**
 * What the Layout panel depends on beyond the values: the menu [style] on screen (the XMB-only
 * rows hide under another style) and the pack's [styles], one per look (base and variants) that
 * draws a different style, so the "Menu style" row can switch between them.
 */
@Immutable
data class LayoutPanelContext(
    val style: MenuStyle = MenuStyle.XMB,
    /** Each style the pack offers and the look that draws it (variant id; "" is the base), in pack order. */
    val styles: List<Pair<MenuStyle, String>> = emptyList(),
) {
    /** The panel's rows here, in order. */
    val rows: List<LayoutRow> = buildList {
        if (styles.map { it.first }.distinct().size > 1) add(LayoutRow.MENU_STYLE)
        if (style == MenuStyle.XMB) add(LayoutRow.CROSSHAIR)
        add(LayoutRow.UI_SCALE)
        add(LayoutRow.SPACING)
        // SLASH sizes its focused row with its own `slash.focusedScale`.
        if (style == MenuStyle.XMB) add(LayoutRow.FOCUSED_SCALE)
        add(LayoutRow.RESET)
    }

    companion object {
        val XMB = LayoutPanelContext()

        /**
         * The context for a pack whose base draws [base] and whose variants draw [variants]
         * (id → its style, null keeping the base's), while [active] is on screen.
         */
        fun of(active: MenuStyle, base: MenuStyle, variants: List<Pair<String, MenuStyle?>>): LayoutPanelContext {
            val seen = LinkedHashMap<MenuStyle, String>()
            seen[base] = ""
            for ((id, st) in variants) if (st != null && st !in seen) seen[st] = id
            return LayoutPanelContext(active, seen.map { it.key to it.value })
        }
    }
}

/** The open Layout panel; [index] is the focused row of [LayoutPanelContext.rows]. */
@Immutable
data class LayoutEditorState(val index: Int = 0) {
    val row: LayoutRow get() = row(LayoutPanelContext.XMB)

    fun row(ctx: LayoutPanelContext): LayoutRow = ctx.rows[index.coerceIn(0, ctx.rows.lastIndex)]
}

/** What an input did: the new panel state, the layout values to save (null = unchanged), and whether to close. */
data class LayoutEditorOutcome(
    val state: LayoutEditorState,
    val overrides: LayoutOverrides? = null,
    val close: Boolean = false,
    /** The input changed nothing it could (a value at its end, Up on the first row). */
    val blocked: Boolean = false,
    /** The "Menu style" row picked another look: this variant id ("" the base) to switch to. */
    val variant: String? = null,
)

/**
 * Pure logic of the Layout panel (Phase 2b.8a, the Live Theme Mixer): Up/Down pick a row,
 * Left/Right step its value, Confirm puts a row back to the theme pack (or resets the panel on
 * Reset layout), Back closes. Every step is saved at once and the menu behind the panel moves with
 * it, which is the preview.
 *
 * Each value row is "Theme pack" (null) below its smallest value, and that is its left end: Right
 * from the pack steps up from the pack's own value, Left past the smallest value goes back to it,
 * and the largest value is the right end. [pack] is the pack's layout, before the user's.
 */
object LayoutEditor {
    /** The XMB's rows (no menu style choice). */
    val rows: List<LayoutRow> = LayoutPanelContext.XMB.rows

    /** Crosshair choices after "Theme pack", in row order. */
    val PRESETS: List<CrosshairPreset> = listOf(CrosshairPreset.CLASSIC_PS3, CrosshairPreset.PSP_CENTER, CrosshairPreset.MODERN_WIDESCREEN)

    /** 50–150% in 10% steps. */
    val UI_SCALES: List<Float> = (5..15).map { it / 10f }

    /** 1.00×–1.35× in 0.05 steps. */
    val FOCUS_SCALES: List<Float> = (20..27).map { it * 5 / 100f }

    /** Tight, Standard, Spacious (dp). */
    val SPACINGS: List<Pair<Float, String>> = listOf(6f to "Tight", 12f to "Standard", 20f to "Spacious")

    fun onAction(
        state: LayoutEditorState,
        action: NavAction,
        user: LayoutOverrides,
        pack: ThemeLayout,
        ctx: LayoutPanelContext = LayoutPanelContext.XMB,
    ): LayoutEditorOutcome {
        val row = state.row(ctx)
        if (row == LayoutRow.MENU_STYLE && (action == NavAction.LEFT || action == NavAction.RIGHT)) {
            return styleStep(state, ctx, if (action == NavAction.LEFT) -1 else +1)
        }
        return when (action) {
            NavAction.UP -> move(state, -1, ctx)
            NavAction.DOWN -> move(state, +1, ctx)
            NavAction.LEFT -> change(state, user, adjust(row, user, pack, -1))
            NavAction.RIGHT -> change(state, user, adjust(row, user, pack, +1))
            NavAction.CONFIRM, NavAction.START -> change(state, user, activate(row, user))
            NavAction.BACK, NavAction.OPTIONS -> LayoutEditorOutcome(state, close = true)
            NavAction.PREV_CATEGORY, NavAction.NEXT_CATEGORY, NavAction.MEDIA -> LayoutEditorOutcome(state)
        }
    }

    /** Left/Right on "Menu style": the look drawing the next style the pack offers; the ends hold. */
    fun styleStep(state: LayoutEditorState, ctx: LayoutPanelContext, direction: Int): LayoutEditorOutcome {
        val i = ctx.styles.indexOfFirst { it.first == ctx.style }
        val j = i + direction
        if (i < 0 || j !in ctx.styles.indices) return LayoutEditorOutcome(state, blocked = true)
        return LayoutEditorOutcome(state, variant = ctx.styles[j].second)
    }

    private fun move(state: LayoutEditorState, delta: Int, ctx: LayoutPanelContext = LayoutPanelContext.XMB): LayoutEditorOutcome {
        val rows = ctx.rows
        val next = (state.index + delta).coerceIn(0, rows.lastIndex)
        return if (next == state.index) LayoutEditorOutcome(state, blocked = true) else LayoutEditorOutcome(state.copy(index = next))
    }

    private fun change(state: LayoutEditorState, before: LayoutOverrides, next: LayoutOverrides?): LayoutEditorOutcome =
        if (next == null || next == before) LayoutEditorOutcome(state, blocked = true) else LayoutEditorOutcome(state, overrides = next)

    /** Left/Right: one step; null when the value can't move that way. */
    fun adjust(row: LayoutRow, user: LayoutOverrides, pack: ThemeLayout, direction: Int): LayoutOverrides? = when (row) {
        LayoutRow.CROSSHAIR -> {
            // Theme pack, then the presets in order; the ends hold.
            val ids = listOf<CrosshairPreset?>(null) + PRESETS
            val j = ids.indexOf(user.crosshair) + direction
            if (j in ids.indices) user.copy(preset = ids[j]?.name) else null
        }
        LayoutRow.UI_SCALE -> step(user.uiScale, pack.uiScale, UI_SCALES, direction)?.let { user.copy(uiScale = it.value) }
        LayoutRow.SPACING -> step(user.itemSpacingDp, pack.itemSpacingDp, SPACINGS.map { it.first }, direction)?.let { user.copy(itemSpacingDp = it.value) }
        LayoutRow.FOCUSED_SCALE -> step(user.focusedScale, pack.focusedScale, FOCUS_SCALES, direction)?.let { user.copy(focusedScale = it.value) }
        LayoutRow.RESET, LayoutRow.MENU_STYLE -> null
    }

    /** A step's result: [value] null is "Theme pack". */
    private class Step(val value: Float?)

    /**
     * One step of a numeric row from [own] (null: the pack's [packValue]) through [steps]
     * (ascending). The pack is the left end: Right from it is the step after its own value, Left
     * from the smallest step goes back to it; null at an end, so a held key stops there.
     */
    private fun step(own: Float?, packValue: Float, steps: List<Float>, direction: Int): Step? {
        if (own == null) return if (direction > 0) steps.firstOrNull { it > packValue + EPS }?.let(::Step) else null
        return if (direction > 0) {
            steps.firstOrNull { it > own + EPS }?.let(::Step)
        } else {
            Step(steps.lastOrNull { it < own - EPS })
        }
    }

    /** Confirm: a value row goes back to the pack; Reset layout clears the panel. */
    fun activate(row: LayoutRow, user: LayoutOverrides): LayoutOverrides? = when (row) {
        LayoutRow.CROSSHAIR -> user.copy(preset = null)
        LayoutRow.UI_SCALE -> user.copy(uiScale = null)
        LayoutRow.SPACING -> user.copy(itemSpacingDp = null)
        LayoutRow.FOCUSED_SCALE -> user.copy(focusedScale = null)
        LayoutRow.RESET -> LayoutOverrides.NONE
        LayoutRow.MENU_STYLE -> user
    }.takeIf { it != user }

    /** True when the user set [row] (Reset layout: anything at all). */
    fun isSet(row: LayoutRow, user: LayoutOverrides): Boolean = when (row) {
        LayoutRow.CROSSHAIR -> user.crosshair != null
        LayoutRow.UI_SCALE -> user.uiScale != null
        LayoutRow.SPACING -> user.itemSpacingDp != null
        LayoutRow.FOCUSED_SCALE -> user.focusedScale != null
        LayoutRow.RESET -> !user.empty
        LayoutRow.MENU_STYLE -> false
    }

    /** Text at the right of a row: the user's value, or "Theme pack · <the pack's>". */
    fun value(row: LayoutRow, user: LayoutOverrides, pack: ThemeLayout, ctx: LayoutPanelContext = LayoutPanelContext.XMB): String {
        fun packs(v: String) = "Theme pack · $v"
        return when (row) {
            LayoutRow.CROSSHAIR -> user.crosshair?.label ?: packs(crosshairLabel(pack))
            LayoutRow.UI_SCALE -> user.uiScale?.let(::percent) ?: packs(percent(pack.uiScale))
            LayoutRow.SPACING -> user.itemSpacingDp?.let(::spacingLabel) ?: packs(spacingLabel(pack.itemSpacingDp))
            LayoutRow.FOCUSED_SCALE -> user.focusedScale?.let(::times) ?: packs(times(pack.focusedScale))
            LayoutRow.RESET -> if (user.empty) "Nothing set" else countOf(user.count)
            LayoutRow.MENU_STYLE -> "Theme pack · ${ctx.style.label}"
        }
    }

    /** Slider position 0–1 of what is drawn (the pack's value under "Theme pack"), or null for rows that aren't sliders. */
    fun fraction(row: LayoutRow, user: LayoutOverrides, pack: ThemeLayout): Float? {
        fun f(v: Float, lo: Float, hi: Float) = ((v - lo) / (hi - lo)).coerceIn(0f, 1f)
        return when (row) {
            LayoutRow.UI_SCALE -> f(user.uiScale ?: pack.uiScale, LayoutLimits.MIN_SCALE, LayoutLimits.MAX_SCALE)
            LayoutRow.FOCUSED_SCALE -> f(user.focusedScale ?: pack.focusedScale, LayoutLimits.MIN_FOCUS_SCALE, LayoutLimits.MAX_FOCUS_SCALE)
            else -> null
        }
    }

    /** Touch: a slider dragged to [fraction] (0–1), snapped to the nearest step. */
    fun setFraction(row: LayoutRow, user: LayoutOverrides, fraction: Float): LayoutOverrides {
        val x = fraction.coerceIn(0f, 1f)
        fun nearest(steps: List<Float>) = steps[(x * steps.lastIndex).roundToInt()]
        return when (row) {
            LayoutRow.UI_SCALE -> user.copy(uiScale = nearest(UI_SCALES))
            LayoutRow.FOCUSED_SCALE -> user.copy(focusedScale = nearest(FOCUS_SCALES))
            else -> user
        }
    }

    /** "Crosshair PSP centre · UI scale 110%": what the user set, for the Layout row's subtitle; empty when nothing is. */
    fun summary(user: LayoutOverrides): String = buildList {
        user.crosshair?.let { add("Crosshair ${it.label}") }
        user.uiScale?.let { add("UI scale ${percent(it)}") }
        user.itemSpacingDp?.let { add("Spacing ${spacingLabel(it)}") }
        user.focusedScale?.let { add("Focused ${times(it)}") }
    }.joinToString(" · ")

    /** The preset whose anchor the pack uses, else its fractions ("22% × 25%"). */
    fun crosshairLabel(l: ThemeLayout): String =
        CrosshairPreset.entries.firstOrNull { abs(it.x - l.crosshairX) < EPS && abs(it.y - l.crosshairY) < EPS }?.label
            ?: "${(l.crosshairX * 100).roundToInt()}% × ${(l.crosshairY * 100).roundToInt()}%"

    fun percent(v: Float): String = "${(v * 100).roundToInt()}%"

    fun times(v: Float): String = String.format(Locale.ROOT, "%.2f×", v)

    /** "Tight (6 dp)" for a named spacing, else "15 dp". */
    fun spacingLabel(dp: Float): String {
        val named = SPACINGS.firstOrNull { abs(it.first - dp) < EPS }
        val n = if (abs(dp - dp.roundToInt()) < EPS) dp.roundToInt().toString() else MenuTree.formatDecimal(dp)
        return if (named != null) "${named.second} ($n dp)" else "$n dp"
    }

    private fun countOf(n: Int) = if (n == 1) "1 value" else "$n values"

    private const val EPS = 1e-3f
}
