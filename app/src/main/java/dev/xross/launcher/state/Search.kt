package dev.xross.launcher.state

import androidx.compose.runtime.Immutable
import dev.xross.launcher.input.NavAction

enum class KeyKind { CHAR, SPACE, BACKSPACE, CLEAR, DONE }

/** One key of the gamepad keyboard. [start] and [span] place it on the 10-column grid. */
@Immutable
data class Key(val label: String, val kind: KeyKind, val start: Int, val span: Int = 1, val char: Char? = null)

/**
 * Gamepad keyboard layout: digits, A–Z and a few title punctuation marks on a 10-column grid,
 * with wide action keys along the bottom. Built for D-pad/stick travel: no shift layer, and the
 * most common edits (space, delete) also sit on shoulder/face buttons.
 */
object SearchKeyboard {
    const val COLUMNS = 10

    val rows: List<List<Key>> = listOf(
        "1234567890", "ABCDEFGHIJ", "KLMNOPQRST", "UVWXYZ-'&.",
    ).map { row -> row.mapIndexed { i, c -> Key(c.toString(), KeyKind.CHAR, start = i, char = c) } } + listOf(
        listOf(
            Key("Space", KeyKind.SPACE, start = 0, span = 4),
            Key("Delete", KeyKind.BACKSPACE, start = 4, span = 2),
            Key("Clear", KeyKind.CLEAR, start = 6, span = 2),
            Key("Results", KeyKind.DONE, start = 8, span = 2),
        ),
    )

    fun keyAt(row: Int, column: Int): Key = rows[row].first { column in it.start until it.start + it.span }
}

enum class SearchFocus { KEYS, RESULTS }

sealed interface SearchEffect {
    data object None : SearchEffect
    data object Close : SearchEffect
    /** Confirm on a result: run its action (launch the game). */
    data class Activate(val node: XmbNode) : SearchEffect
    /** Options on a result: open its Options menu. */
    data class Options(val node: XmbNode) : SearchEffect
}

data class SearchOutcome(val state: SearchState, val effect: SearchEffect = SearchEffect.None)

/**
 * Pure state of the search overlay: the typed query, the keyboard cursor, the live results and
 * which of the two panes has focus.
 *
 * Controls — keys: D-pad moves, Confirm types, Options = space, L1 = delete, R1/Start = jump to
 * results, Back closes. Results: ↑/↓ move, Confirm launches, Options opens the game's menu,
 * ←/Back/L1 return to the keys.
 */
@Immutable
data class SearchState(
    val query: String = "",
    val row: Int = 1,
    /** Grid column (0–9). Kept while crossing wide keys so vertical moves stay in their column. */
    val column: Int = 0,
    val results: List<XmbNode> = emptyList(),
    val resultIndex: Int = 0,
    val focus: SearchFocus = SearchFocus.KEYS,
) {
    val focusedKey: Key get() = SearchKeyboard.keyAt(row, column)
    val focusedResult: XmbNode? get() = results.getOrNull(resultIndex)

    fun onAction(action: NavAction): SearchOutcome =
        if (focus == SearchFocus.KEYS) keysAction(action) else resultsAction(action)

    /** Applies results from the index. Ignored when they answer an older query. */
    fun withResults(forQuery: String, found: List<XmbNode>): SearchState {
        if (forQuery != query) return this
        return copy(
            results = found,
            resultIndex = resultIndex.coerceIn(0, (found.size - 1).coerceAtLeast(0)),
            focus = if (found.isEmpty()) SearchFocus.KEYS else focus,
        )
    }

    // region Editing (also used for hardware keyboards)

    fun type(c: Char): SearchState = when {
        query.length >= MAX_QUERY -> this
        // No leading or doubled spaces: they never change what matches.
        c == ' ' -> if (query.isEmpty() || query.endsWith(' ')) this else edited(query + ' ')
        else -> edited(query + c.uppercaseChar())
    }

    fun backspace(): SearchState = if (query.isEmpty()) this else edited(query.dropLast(1))

    private fun clear(): SearchState = edited("")

    private fun edited(text: String): SearchState =
        if (text.isBlank()) copy(query = text, results = emptyList(), resultIndex = 0) else copy(query = text)

    // endregion

    private fun keysAction(action: NavAction): SearchOutcome = when (action) {
        NavAction.UP -> SearchOutcome(copy(row = (row - 1).coerceAtLeast(0)))
        NavAction.DOWN -> SearchOutcome(copy(row = (row + 1).coerceAtMost(SearchKeyboard.rows.lastIndex)))
        NavAction.LEFT -> {
            val keys = SearchKeyboard.rows[row]
            val i = keys.indexOf(focusedKey)
            SearchOutcome(if (i > 0) copy(column = keys[i - 1].start) else this)
        }
        NavAction.RIGHT -> {
            val keys = SearchKeyboard.rows[row]
            val i = keys.indexOf(focusedKey)
            // Past the last key: over to the results.
            SearchOutcome(if (i < keys.lastIndex) copy(column = keys[i + 1].start) else toResults())
        }
        NavAction.CONFIRM -> SearchOutcome(press(focusedKey))
        NavAction.OPTIONS -> SearchOutcome(type(' '))
        NavAction.PREV_CATEGORY -> SearchOutcome(backspace())
        NavAction.NEXT_CATEGORY, NavAction.START -> SearchOutcome(toResults())
        NavAction.BACK -> SearchOutcome(this, SearchEffect.Close)
    }

    private fun press(key: Key): SearchState = when (key.kind) {
        KeyKind.CHAR -> type(key.char!!)
        KeyKind.SPACE -> type(' ')
        KeyKind.BACKSPACE -> backspace()
        KeyKind.CLEAR -> clear()
        KeyKind.DONE -> toResults()
    }

    private fun toResults(): SearchState = if (results.isEmpty()) this else copy(focus = SearchFocus.RESULTS)

    private fun resultsAction(action: NavAction): SearchOutcome = when (action) {
        NavAction.UP -> SearchOutcome(copy(resultIndex = (resultIndex - 1).coerceAtLeast(0)))
        NavAction.DOWN -> SearchOutcome(copy(resultIndex = (resultIndex + 1).coerceAtMost(results.lastIndex)))
        NavAction.CONFIRM, NavAction.START ->
            SearchOutcome(this, focusedResult?.let { SearchEffect.Activate(it) } ?: SearchEffect.None)
        NavAction.OPTIONS ->
            SearchOutcome(this, focusedResult?.let { SearchEffect.Options(it) } ?: SearchEffect.None)
        NavAction.LEFT, NavAction.BACK, NavAction.PREV_CATEGORY -> SearchOutcome(copy(focus = SearchFocus.KEYS))
        NavAction.RIGHT, NavAction.NEXT_CATEGORY -> SearchOutcome(this)
    }

    companion object {
        const val MAX_QUERY = 40
    }
}

/** Turns typed text into an FTS4 MATCH expression. */
object SearchQuery {
    /**
     * Each word becomes a prefix term ("chrono tri" → `chrono* tri*`, all must match). Only
     * letters and digits survive, lower-cased, so quotes, operators (OR/NOT/NEAR) and column
     * filters can never act as query syntax. Null when there is nothing to search for.
     */
    fun toFts(text: String): String? {
        val words = text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        return if (words.isEmpty()) null else words.joinToString(" ") { "$it*" }
    }
}
