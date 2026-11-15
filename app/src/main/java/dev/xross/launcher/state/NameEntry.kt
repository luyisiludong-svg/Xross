package dev.xross.launcher.state

import androidx.compose.runtime.Immutable
import dev.xross.launcher.input.NavAction
import dev.xross.launcher.theme.pack.ExportScope

/** What a typed name is for. */
sealed interface NamePurpose {
    /** A new Apps folder for [packageName]. */
    data class NewAppFolder(val packageName: String) : NamePurpose

    /** The name of a theme exported from pack [packId] in [scope] (2b.8b), into an `.xtheme` file when [share] (2b.8c). */
    data class ExportTheme(val packId: String, val scope: ExportScope, val share: Boolean = false) : NamePurpose
}

sealed interface NameEntryEffect {
    data object None : NameEntryEffect
    data object Close : NameEntryEffect
    data class Submit(val text: String) : NameEntryEffect
}

data class NameEntryOutcome(val state: NameEntry, val effect: NameEntryEffect = NameEntryEffect.None)

/**
 * Typing a short name on the gamepad keyboard (the Search keyboard without results).
 *
 * Controls: D-pad moves, Confirm types (the Done key submits), Options = space, L1 = delete,
 * R1 / Start = done, Back closes without saving.
 */
@Immutable
data class NameEntry(
    val title: String,
    val purpose: NamePurpose,
    val keys: SearchState = SearchState(),
    /** The longest name this entry takes (Apps folder names are short; an exported theme's may be longer). */
    val maxLength: Int = AppFolders.MAX_NAME,
) {
    val text: String get() = keys.query

    fun onAction(action: NavAction): NameEntryOutcome = when (action) {
        NavAction.BACK -> NameEntryOutcome(this, NameEntryEffect.Close)
        NavAction.START, NavAction.NEXT_CATEGORY -> submit()
        NavAction.CONFIRM -> if (keys.focusedKey.kind == KeyKind.DONE) submit() else typed(keys.onAction(action).state)
        NavAction.MEDIA -> NameEntryOutcome(this)
        else -> typed(keys.onAction(action).state)
    }

    fun type(c: Char): NameEntry = if (text.length >= maxLength) this else copy(keys = keys.type(c))

    fun backspace(): NameEntry = copy(keys = keys.backspace())

    /** Touch: tap a key. */
    fun tap(row: Int, column: Int): NameEntryOutcome =
        copy(keys = keys.copy(row = row, column = column, focus = SearchFocus.KEYS)).onAction(NavAction.CONFIRM)

    private fun submit(): NameEntryOutcome =
        if (text.isBlank()) NameEntryOutcome(this) else NameEntryOutcome(this, NameEntryEffect.Submit(text))

    /** Keys only: this keyboard has no results pane, and names stay short. */
    private fun typed(next: SearchState): NameEntryOutcome {
        val clamped = if (next.query.length > maxLength) keys else next
        return NameEntryOutcome(copy(keys = clamped.copy(focus = SearchFocus.KEYS, results = emptyList())))
    }
}
