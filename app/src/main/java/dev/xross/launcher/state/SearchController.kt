package dev.xross.launcher.state

import android.view.KeyEvent
import dev.xross.launcher.audio.UiSound
import dev.xross.launcher.audio.UiSounds
import dev.xross.launcher.input.NavAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The gamepad keyboards [SearchController] owns. */
data class TypingState(
    /** Search overlay (gamepad keyboard + live results) while it is open. */
    val search: SearchState? = null,
    /** Typing a name (a new app folder, an exported theme) on the gamepad keyboard. */
    val nameEntry: NameEntry? = null,
)

/**
 * Search and name entry, split out of [XmbViewModel] (Architecture Assessment §7.1, step 6): the
 * two gamepad keyboards, typed by pad, touch or a hardware keyboard. Search runs the library's FTS
 * query (debounced while typing, dropped when the text has moved on) and keeps open results in
 * step with the library; a picked result or a finished name goes back to the menu through [Host].
 */
class SearchController(
    private val scope: CoroutineScope,
    private val sounds: UiSounds,
    /** The library's game rows matching an FTS query (see [SearchQuery.toFts]). */
    private val find: suspend (fts: String) -> List<XmbNode>,
    private val host: Host,
) {
    /** What the controller needs from the menu around it. */
    interface Host {
        /** Confirm on a result: what Confirm on that row in its list would do. */
        fun activate(node: XmbNode)
        /** Options on a result: that row's Options menu. */
        fun openOptions(node: XmbNode)
        /** A name was typed and confirmed; the keyboard is already closed. */
        fun submitName(purpose: NamePurpose, text: String)
    }

    private val _state = MutableStateFlow(TypingState())
    val state: StateFlow<TypingState> = _state.asStateFlow()

    private var searchJob: Job? = null

    // region Search

    fun openSearch() = _state.update { it.copy(search = SearchState()) }

    /** Input while search is open; false when it isn't. */
    fun onSearchAction(action: NavAction): Boolean {
        val search = _state.value.search ?: return false
        val outcome = search.onAction(action)
        applySearch(search, outcome.state)
        when (val effect = outcome.effect) {
            SearchEffect.None -> Unit
            SearchEffect.Close -> closeSearch()
            is SearchEffect.Activate -> host.activate(effect.node)
            is SearchEffect.Options -> host.openOptions(effect.node)
        }
        return true
    }

    private fun applySearch(before: SearchState, after: SearchState) {
        if (after == before) return
        sounds.play(if (after.query != before.query) UiSound.ENTER else UiSound.MOVE)
        _state.update { it.copy(search = after) }
        if (after.query != before.query) runSearch(SEARCH_DEBOUNCE_MS)
    }

    /**
     * Queries the FTS index for the current text. Debounced while typing; Room runs the query on
     * its own background executor, and a result for text that has since changed is dropped.
     */
    private fun runSearch(delayMs: Long) {
        searchJob?.cancel()
        searchJob = scope.launch {
            delay(delayMs)
            val query = _state.value.search?.query ?: return@launch
            val fts = SearchQuery.toFts(query)
            val nodes = if (fts == null) emptyList() else find(fts)
            _state.update { it.copy(search = it.search?.withResults(query, nodes)) }
        }
    }

    /** The library changed (favorites, playtime, a rescan): open results are queried again. */
    fun onLibraryChanged() {
        if (_state.value.search?.query?.isNotBlank() == true) runSearch(0)
    }

    fun closeSearch() {
        if (_state.value.search != null) {
            searchJob?.cancel()
            sounds.play(UiSound.BACK)
            _state.update { it.copy(search = null) }
        }
    }

    /** Details paged to [index] of the results: the cursor follows, so Back lands on that game. */
    fun focusResult(index: Int) = _state.update { it.copy(search = it.search?.copy(resultIndex = index)) }

    /** A touch on the search or name keyboard, or on a search result. */
    fun onEvent(event: XmbEvent.Typing) {
        when (event) {
            is XmbEvent.Typing.SearchKeyTap -> onSearchKeyTapped(event.row, event.column)
            is XmbEvent.Typing.SearchResultTap -> onSearchResultTapped(event.index)
            is XmbEvent.Typing.NameKeyTap -> onNameKeyTapped(event.row, event.column)
        }
    }

    /** Touch: tap a key to type it. */
    fun onSearchKeyTapped(row: Int, column: Int) {
        val search = _state.value.search ?: return
        val moved = search.copy(row = row, column = column, focus = SearchFocus.KEYS)
        applySearch(search, moved.onAction(NavAction.CONFIRM).state)
    }

    /** Touch: tap a result to focus it, tap the focused one to launch it. */
    fun onSearchResultTapped(index: Int) {
        val search = _state.value.search ?: return
        if (search.focus == SearchFocus.RESULTS && search.resultIndex == index) {
            search.focusedResult?.let(host::activate)
        } else {
            applySearch(search, search.copy(focus = SearchFocus.RESULTS, resultIndex = index))
        }
    }

    /** Hardware keyboards type straight into the search field; false for a key it doesn't use. */
    fun onSearchRawKey(keyCode: Int): Boolean {
        val search = _state.value.search ?: return false
        val next = when (keyCode) {
            in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> search.type('A' + (keyCode - KeyEvent.KEYCODE_A))
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> search.type('0' + (keyCode - KeyEvent.KEYCODE_0))
            KeyEvent.KEYCODE_SPACE -> search.type(' ')
            KeyEvent.KEYCODE_DEL -> search.backspace()
            KeyEvent.KEYCODE_ENTER -> search.onAction(NavAction.START).state
            else -> return false
        }
        applySearch(search, next.copy(focus = if (keyCode == KeyEvent.KEYCODE_ENTER) next.focus else SearchFocus.KEYS))
        return true
    }

    // endregion

    // region Name entry

    fun openNameEntry(entry: NameEntry) = _state.update { it.copy(nameEntry = entry) }

    /** Input while the name keyboard is open; false when it isn't. */
    fun onNameAction(action: NavAction): Boolean {
        val entry = _state.value.nameEntry ?: return false
        applyNameEntry(entry, entry.onAction(action))
        return true
    }

    private fun applyNameEntry(before: NameEntry, outcome: NameEntryOutcome) {
        when (val effect = outcome.effect) {
            NameEntryEffect.None -> if (outcome.state != before) {
                sounds.play(if (outcome.state.text != before.text) UiSound.ENTER else UiSound.MOVE)
                _state.update { it.copy(nameEntry = outcome.state) }
            }
            NameEntryEffect.Close -> closeNameEntry()
            is NameEntryEffect.Submit -> {
                _state.update { it.copy(nameEntry = null) }
                host.submitName(before.purpose, effect.text)
            }
        }
    }

    fun closeNameEntry() {
        if (_state.value.nameEntry != null) {
            sounds.play(UiSound.BACK)
            _state.update { it.copy(nameEntry = null) }
        }
    }

    /** Touch: tap a key of the name keyboard. */
    fun onNameKeyTapped(row: Int, column: Int) {
        val entry = _state.value.nameEntry ?: return
        applyNameEntry(entry, entry.tap(row, column))
    }

    /** Hardware keyboards type names directly; false for a key it doesn't use. */
    fun onNameRawKey(keyCode: Int): Boolean {
        val entry = _state.value.nameEntry ?: return false
        val next = when (keyCode) {
            in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> entry.type('A' + (keyCode - KeyEvent.KEYCODE_A))
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> entry.type('0' + (keyCode - KeyEvent.KEYCODE_0))
            KeyEvent.KEYCODE_SPACE -> entry.type(' ')
            KeyEvent.KEYCODE_DEL -> entry.backspace()
            KeyEvent.KEYCODE_ENTER -> return true.also { applyNameEntry(entry, entry.onAction(NavAction.START)) }
            else -> return false
        }
        applyNameEntry(entry, NameEntryOutcome(next))
        return true
    }

    // endregion

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 140L
    }
}
