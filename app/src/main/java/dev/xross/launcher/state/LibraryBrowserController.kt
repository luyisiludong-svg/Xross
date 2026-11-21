package dev.xross.launcher.state

import dev.xross.launcher.audio.UiSound
import dev.xross.launcher.audio.UiSounds
import dev.xross.launcher.data.GameEntity
import dev.xross.launcher.data.GameMetadataEntity
import dev.xross.launcher.input.NavAction
import dev.xross.launcher.model.PlatformRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The library browser (0.2.2.0 § 3): opens from Settings ▸ Metadata ▸ Fix media… or a system
 * folder's Options. While open it follows the library and its metadata, so a game fixed in the
 * Metadata & Media editor shows its new badges as soon as Back returns here. Pure layout and
 * cursor rules are [LibraryBrowser]'s.
 */
class LibraryBrowserController(
    private val scope: CoroutineScope,
    private val games: Flow<List<GameEntity>>,
    private val metadata: Flow<List<GameMetadataEntity>>,
    private val sounds: UiSounds,
    private val host: Host,
) {
    interface Host {
        /** Opens the Metadata & Media editor on [gameId], paging through the browser's list. */
        fun openEditor(gameId: Long)
    }

    private val _state = MutableStateFlow<LibraryBrowserState?>(null)
    val state: StateFlow<LibraryBrowserState?> = _state.asStateFlow()
    val isOpen: Boolean get() = _state.value != null

    private var latest: Pair<List<GameEntity>, Map<Long, GameMetadataEntity>> = emptyList<GameEntity>() to emptyMap()
    private var watch: Job? = null

    fun open(platformId: String?) {
        watch?.cancel()
        _state.value = null
        sounds.play(UiSound.ENTER)
        var first = true
        watch = scope.launch {
            combine(games, metadata) { g, m -> g to m.associateBy { it.gameId } }.collectLatest { data ->
                latest = data
                _state.value = LibraryBrowser.build(data.first, data.second, PlatformRegistry.all, _state.value, startOn = if (first) platformId else null)
                first = false
            }
        }
    }

    fun close() {
        if (_state.value == null) return
        watch?.cancel()
        watch = null
        _state.value = null
        sounds.play(UiSound.BACK)
    }

    private fun rebuild(s: LibraryBrowserState): LibraryBrowserState =
        LibraryBrowser.build(latest.first, latest.second, PlatformRegistry.all, s)

    /** The editor paged to [gameId]: the browser's cursor follows, so Back lands there. */
    fun follow(gameId: Long) {
        val s = _state.value ?: return
        val i = s.rows.indexOfFirst { it.gameId == gameId }
        if (i >= 0) _state.value = s.copy(index = i, focus = BrowserFocus.LIST)
    }

    /** Remembers the scroll when the editor opens over the list. */
    fun rememberScroll(firstVisible: Int) {
        _state.value = _state.value?.copy(firstVisible = firstVisible)
    }

    fun onAction(action: NavAction): Boolean {
        val s = _state.value ?: return false
        when (action) {
            NavAction.PREV_CATEGORY, NavAction.NEXT_CATEGORY -> system(s, if (action == NavAction.PREV_CATEGORY) -1 else 1)
            NavAction.LEFT, NavAction.RIGHT -> {
                val d = if (action == NavAction.LEFT) -1 else 1
                if (s.focus == BrowserFocus.FILTERS) filter(s, d) else system(s, d)
            }
            NavAction.UP, NavAction.DOWN -> set(s, LibraryBrowser.moveCursor(s, if (action == NavAction.UP) -1 else 1))
            NavAction.CONFIRM, NavAction.START -> when (s.focus) {
                BrowserFocus.FILTERS -> filter(s, 1)
                BrowserFocus.LIST -> s.focused?.let { host.openEditor(it.gameId) } ?: sounds.play(UiSound.ERROR)
            }
            NavAction.BACK, NavAction.OPTIONS -> close()
            NavAction.MEDIA -> Unit
        }
        return true
    }

    /** Touch: a game row opens it; a system or filter chip selects it. */
    fun onTapRow(index: Int) {
        val s = _state.value ?: return
        if (index == s.index && s.focus == BrowserFocus.LIST) return s.focused?.let { host.openEditor(it.gameId) } ?: Unit
        set(s, s.copy(index = index.coerceIn(0, (s.rows.size - 1).coerceAtLeast(0)), focus = BrowserFocus.LIST))
    }

    fun onTapSystem(index: Int) {
        val s = _state.value ?: return
        system(s, index - s.systemIndex)
    }

    fun onTapFilter(index: Int) {
        val s = _state.value ?: return
        filter(s, index - MediaFilter.BROWSER.indexOf(s.filter))
    }

    private fun system(s: LibraryBrowserState, delta: Int) = set(s, LibraryBrowser.moveSystem(s, delta, ::rebuild))

    private fun filter(s: LibraryBrowserState, delta: Int) = set(s.copy(focus = BrowserFocus.FILTERS), LibraryBrowser.moveFilter(s.copy(focus = BrowserFocus.FILTERS), delta, ::rebuild))

    private fun set(before: LibraryBrowserState, after: LibraryBrowserState) {
        if (after == before) return sounds.play(UiSound.ERROR)
        sounds.play(UiSound.MOVE)
        _state.value = after
    }
}
