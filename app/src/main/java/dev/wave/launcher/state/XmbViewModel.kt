package dev.wave.launcher.state

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.wave.launcher.AppContainer
import dev.wave.launcher.BuildConfig
import dev.wave.launcher.audio.UiCue
import dev.wave.launcher.audio.UiSound
import dev.wave.launcher.input.ConfirmLayout
import dev.wave.launcher.input.ControllerFamily
import dev.wave.launcher.input.InputConfig
import dev.wave.launcher.input.InputSink
import dev.wave.launcher.input.NavAction
import dev.wave.launcher.input.resolve
import dev.wave.launcher.launch.GameLauncher
import dev.wave.launcher.launch.LaunchPlan
import dev.wave.launcher.storage.PlatformRegistry
import dev.wave.launcher.storage.VolumeEvent
import dev.wave.launcher.theme.WaveTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** One-shot requests that need an Activity. */
sealed interface UiEffect {
    data class StartActivity(val intent: Intent) : UiEffect
    data object PickFolder : UiEffect
    data object RequestStorageAccess : UiEffect
    data class SetWallpaper(val uri: Uri) : UiEffect
}

data class XmbUiState(
    val categories: List<XmbCategory> = Categories.all,
    val categoryIndex: Int = 1,
    /** Titles of opened sub-lists below the category root. */
    val breadcrumb: List<String> = emptyList(),
    val listKey: String = "",
    val items: List<XmbNode> = emptyList(),
    val focusedIndex: Int = 0,
    val toast: String? = null,
    val remap: RemapSession? = null,
    val family: ControllerFamily = ControllerFamily.KEYBOARD,
    val confirm: ConfirmLayout = ConfirmLayout.SOUTH,
    val waveEnabled: Boolean = true,
    val waveSpeed: Float = 1f,
    val theme: String = WaveTheme.LAVENDER.name,
    /** Title of the game being started while the start transition plays. */
    val booting: String? = null,
    /** True on the first screen after process start: the UI fades in with the startup cue. */
    val coldBoot: Boolean = false,
    /** Full-screen photo viewer / video player / Now Playing, if open. */
    val overlay: MediaOverlay? = null,
    /** Music state for the mini indicator and Now Playing. */
    val nowPlaying: dev.wave.launcher.media.NowPlaying = dev.wave.launcher.media.NowPlaying(),
    /** The focused item's Options menu while it is open. */
    val optionsMenu: OptionsMenuState? = null,
    /** Search overlay (gamepad keyboard + live results) while it is open. */
    val search: SearchState? = null,
) {
    val focused: XmbNode? get() = items.getOrNull(focusedIndex)
    val depth: Int get() = breadcrumb.size
}

private data class Transient(
    val toast: String? = null,
    val remap: RemapSession? = null,
    val family: ControllerFamily = ControllerFamily.KEYBOARD,
    val booting: String? = null,
    val optionsMenu: OptionsMenuState? = null,
    val search: SearchState? = null,
)

class XmbViewModel(
    private val context: Context,
    private val container: AppContainer,
    private val coldBoot: Boolean = false,
    /** Whether shared storage can be read; a seam so tests don't depend on platform app-ops. */
    private val storageAccess: (Context) -> Boolean = ::hasStorageAccess,
) : ViewModel(), InputSink {

    private val sounds = container.sounds
    private val music = container.music

    val media = MediaOverlayController(
        context = context,
        scope = viewModelScope,
        music = music,
        settings = container.settings,
        sounds = container.sounds,
        setWallpaper = { photo -> emit(UiEffect.SetWallpaper(photo.uri)) },
        toast = { toast(it) },
    )

    private val settingsStore = container.settings
    private val categories = Categories.all

    private val nav = MutableStateFlow(
        NavState(
            categoryIndex = categories.indexOfFirst { it.id == Categories.GAMES },
            // Games opens on its first collection, one below the Search entry.
            stacks = mapOf(Categories.GAMES to listOf(Frame(Categories.rootKey(Categories.GAMES), "Games", index = 1))),
        ),
    )
    private val transient = MutableStateFlow(Transient())
    private val env = MutableStateFlow(
        MenuSnapshot(versionName = BuildConfig.VERSION_NAME, settings = settingsStore.current),
    )

    private val _effects = Channel<UiEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    private val tree: StateFlow<MenuTree> = combine(
        env,
        container.games.games,
        container.apps.apps,
        settingsStore.settings,
        container.media.library,
    ) { e, games, apps, settings, mediaLib ->
        MenuTree(e.copy(games = games, apps = apps, settings = settings, media = mediaLib))
    }
        .stateIn(viewModelScope, SharingStarted.Eagerly, MenuTree(env.value))

    private val fingerprints = container.fingerprints

    /** Sampled so per-file progress doesn't redraw the menu more than twice a second. */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private val fingerprintStatus: StateFlow<FingerprintStatus> =
        combine(fingerprints.stats, fingerprints.current) { st, cur -> FingerprintStatus(st, cur) }
            .sample(500)
            .stateIn(viewModelScope, SharingStarted.Eagerly, FingerprintStatus())

    private var foreground = false

    /** Inputs that only some lists read live (fingerprint progress, Now Playing). */
    private data class Live(val fingerprints: FingerprintStatus, val nowPlaying: dev.wave.launcher.media.NowPlaying)

    private val live: StateFlow<Live> = combine(fingerprintStatus, music.state) { fp, np -> Live(fp, np) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Live(FingerprintStatus(), music.state.value))

    private data class Settled(val transient: Transient, val settings: dev.wave.launcher.data.WaveSettings, val overlay: MediaOverlay?)

    private val settled = combine(transient, settingsStore.settings, media.overlay) { tr, st, ov -> Settled(tr, st, ov) }

    val state: StateFlow<XmbUiState> = combine(tree, nav, settled, live) { t, n, sd, lv ->
        val tr = sd.transient
        val settings = sd.settings
        val view = resolve(t, n, lv)
        XmbUiState(
            categories = categories,
            categoryIndex = n.categoryIndex,
            breadcrumb = view.breadcrumb,
            listKey = view.listKey,
            items = view.items,
            focusedIndex = view.focusedIndex,
            toast = tr.toast,
            remap = tr.remap,
            family = tr.family,
            confirm = settings.confirmLayout.resolve(tr.family),
            waveEnabled = settings.waveEnabled,
            waveSpeed = settings.waveSpeed,
            theme = settings.theme,
            booting = tr.booting,
            coldBoot = coldBoot,
            overlay = sd.overlay,
            nowPlaying = lv.nowPlaying,
            optionsMenu = tr.optionsMenu,
            search = tr.search,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, XmbUiState(coldBoot = coldBoot))

    private data class ListView(
        val breadcrumb: List<String>,
        val listKey: String,
        val items: List<XmbNode>,
        val focusedIndex: Int,
    ) {
        val focused: XmbNode? get() = items.getOrNull(focusedIndex)
        val depth: Int get() = breadcrumb.size
    }

    private fun resolve(t: MenuTree, n: NavState, lv: Live): ListView {
        val category = categories[n.categoryIndex]
        val stack = n.stack(category.id, category.title)
        val key = stack.last().key
        val items = when (key) {
            MenuTree.LIBRARY -> t.library(lv.fingerprints)
            Categories.rootKey(Categories.MUSIC) -> t.musicRoot(lv.nowPlaying)
            else -> t.list(key)
        }
        return ListView(
            breadcrumb = stack.drop(1).map { it.title },
            listKey = stack.last().key,
            items = items,
            focusedIndex = stack.last().index.coerceIn(0, (items.size - 1).coerceAtLeast(0)),
        )
    }

    /** Read synchronously from the source flows so rapid inputs never act on a stale frame. */
    private fun current(): ListView = resolve(tree.value, nav.value, live.value)

    val inputConfig: InputConfig
        get() = settingsStore.current.let { InputConfig(it.confirmLayout, it.deadzone, it.keyRemap) }

    private var toastJob: Job? = null
    private var scanJob: Job? = null

    private var environmentLoaded = false

    /** A volume was mounted or removed while a game was in front; rescan on the next resume. */
    private var volumesChangedInBackground = false
    private var volumeJob: Job? = null

    override fun onCleared() {
        media.close()
        super.onCleared()
    }

    // region Input

    override fun onAction(action: NavAction) {
        if (transient.value.remap != null || transient.value.booting != null) return
        if (media.onAction(action)) return
        transient.value.optionsMenu?.let { return optionsAction(it, action) }
        transient.value.search?.let { return searchAction(it, action) }
        val ui = current()
        when (action) {
            NavAction.UP -> moveCursor(-1)
            NavAction.DOWN -> moveCursor(+1)
            NavAction.LEFT -> if (ui.depth > 0) back() else moveCategory(-1)
            NavAction.RIGHT -> when {
                ui.depth == 0 -> moveCategory(+1)
                ui.focused?.action is NodeAction.OpenList -> activate(ui.focused!!)
            }
            NavAction.PREV_CATEGORY -> moveCategory(-1)
            NavAction.NEXT_CATEGORY -> moveCategory(+1)
            NavAction.CONFIRM, NavAction.START -> ui.focused?.let(::activate)
            NavAction.BACK -> back()
            NavAction.OPTIONS -> ui.focused?.let(::openOptions)
        }
    }

    // region Search

    private var searchJob: Job? = null

    private fun searchAction(search: SearchState, action: NavAction) {
        val outcome = search.onAction(action)
        applySearch(search, outcome.state)
        when (val effect = outcome.effect) {
            SearchEffect.None -> Unit
            SearchEffect.Close -> closeSearch()
            is SearchEffect.Activate -> activate(effect.node)
            is SearchEffect.Options -> openOptions(effect.node)
        }
    }

    private fun applySearch(before: SearchState, after: SearchState) {
        if (after == before) return
        sounds.play(if (after.query != before.query) UiSound.ENTER else UiSound.MOVE)
        transient.update { it.copy(search = after) }
        if (after.query != before.query) runSearch(SEARCH_DEBOUNCE_MS)
    }

    /**
     * Queries the FTS index for the current text. Debounced while typing; Room runs the query on
     * its own background executor, and a result for text that has since changed is dropped.
     */
    private fun runSearch(delayMs: Long) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(delayMs)
            val query = transient.value.search?.query ?: return@launch
            val fts = SearchQuery.toFts(query)
            val nodes = if (fts == null) emptyList() else tree.value.gameNodes(container.games.search(fts))
            transient.update { it.copy(search = it.search?.withResults(query, nodes)) }
        }
    }

    fun closeSearch() {
        if (transient.value.search != null) {
            searchJob?.cancel()
            sounds.play(UiSound.BACK)
            transient.update { it.copy(search = null) }
        }
    }

    /** Touch: tap a key to type it. */
    fun onSearchKeyTapped(row: Int, column: Int) {
        val search = transient.value.search ?: return
        val moved = search.copy(row = row, column = column, focus = SearchFocus.KEYS)
        applySearch(search, moved.onAction(NavAction.CONFIRM).state)
    }

    /** Touch: tap a result to focus it, tap the focused one to launch it. */
    fun onSearchResultTapped(index: Int) {
        val search = transient.value.search ?: return
        if (search.focus == SearchFocus.RESULTS && search.resultIndex == index) {
            search.focusedResult?.let(::activate)
        } else {
            applySearch(search, search.copy(focus = SearchFocus.RESULTS, resultIndex = index))
        }
    }

    /** Hardware keyboards type straight into the search field. */
    private fun searchRawKey(search: SearchState, keyCode: Int): Boolean {
        val next = when (keyCode) {
            in android.view.KeyEvent.KEYCODE_A..android.view.KeyEvent.KEYCODE_Z ->
                search.type('A' + (keyCode - android.view.KeyEvent.KEYCODE_A))
            in android.view.KeyEvent.KEYCODE_0..android.view.KeyEvent.KEYCODE_9 ->
                search.type('0' + (keyCode - android.view.KeyEvent.KEYCODE_0))
            android.view.KeyEvent.KEYCODE_SPACE -> search.type(' ')
            android.view.KeyEvent.KEYCODE_DEL -> search.backspace()
            android.view.KeyEvent.KEYCODE_ENTER -> search.onAction(NavAction.START).state
            else -> return false
        }
        applySearch(search, next.copy(focus = if (keyCode == android.view.KeyEvent.KEYCODE_ENTER) next.focus else SearchFocus.KEYS))
        return true
    }

    // endregion

    // region Options menu

    private fun openOptions(node: XmbNode) {
        if (node.menu.isEmpty()) return
        sounds.play(UiSound.OPTION)
        transient.update { it.copy(optionsMenu = OptionsMenuState.open(node.title, node.menu)) }
    }

    private fun optionsAction(menu: OptionsMenuState, action: NavAction) {
        when (action) {
            NavAction.UP, NavAction.DOWN -> {
                val moved = menu.move(if (action == NavAction.UP) -1 else 1)
                if (moved != menu) {
                    sounds.play(UiSound.MOVE)
                    transient.update { it.copy(optionsMenu = moved) }
                }
            }
            NavAction.CONFIRM, NavAction.RIGHT, NavAction.START -> selectOption(menu)
            NavAction.BACK, NavAction.LEFT -> {
                sounds.play(UiSound.BACK)
                transient.update { it.copy(optionsMenu = menu.back()) }
            }
            NavAction.OPTIONS -> closeOptions()
            NavAction.PREV_CATEGORY, NavAction.NEXT_CATEGORY -> Unit
        }
    }

    private fun selectOption(menu: OptionsMenuState) {
        when (val selection = menu.select()) {
            is OptionsMenuState.Selection.Open -> {
                sounds.play(UiSound.ENTER)
                transient.update { it.copy(optionsMenu = selection.menu) }
            }
            is OptionsMenuState.Selection.Run -> {
                sounds.play(UiSound.ENTER)
                transient.update { it.copy(optionsMenu = null) }
                run(selection.command)
            }
            OptionsMenuState.Selection.None -> Unit
        }
    }

    fun closeOptions() {
        if (transient.value.optionsMenu != null) {
            sounds.play(UiSound.BACK)
            transient.update { it.copy(optionsMenu = null) }
        }
    }

    /** Touch: tap a row to focus it, tap again (or tap the focused row) to select. */
    fun onOptionTapped(index: Int) {
        val menu = transient.value.optionsMenu ?: return
        if (index == menu.current.index) selectOption(menu) else transient.update { it.copy(optionsMenu = menu.moveTo(index)) }
    }

    /** Touch: long-press an item to open its Options menu. */
    fun onItemLongPressed(index: Int) {
        val ui = current()
        if (index != ui.focusedIndex) moveCursor(index - ui.focusedIndex)
        current().focused?.let(::openOptions)
    }

    // endregion

    override fun onRawKeyDown(keyCode: Int): Boolean {
        val tr = transient.value
        if (tr.remap == null && tr.optionsMenu == null && tr.booting == null && !media.isOpen) {
            tr.search?.let { return searchRawKey(it, keyCode) }
        }
        val session = transient.value.remap ?: return false
        val next = session.capture(keyCode)
        if (next.isDone) {
            settingsStore.update { it.copy(keyRemap = next.captured) }
            transient.update { it.copy(remap = null) }
            sounds.play(UiSound.OK)
            toast("Buttons remapped")
        } else {
            if (next.step != session.step) sounds.play(UiSound.MOVE)
            transient.update { it.copy(remap = next) }
        }
        return true
    }

    override fun onControllerUsed(family: ControllerFamily) {
        transient.update { it.copy(family = family) }
    }

    fun cancelRemap() {
        if (transient.value.remap != null) {
            transient.update { it.copy(remap = null) }
            sounds.play(UiSound.BACK)
            toast("Remapping cancelled")
        }
    }

    fun onCategoryTapped(index: Int) {
        if (index == nav.value.categoryIndex) {
            val category = categories[index]
            if (nav.value.stack(category.id, category.title).size > 1) sounds.play(UiSound.BACK)
            nav.value = nav.value.jumpTo(index, category.id, category.title, frame = null)
        } else {
            moveCategory(index - nav.value.categoryIndex)
        }
    }

    fun onItemTapped(index: Int) {
        val ui = current()
        if (index == ui.focusedIndex) ui.focused?.let(::activate) else moveCursor(index - ui.focusedIndex)
    }

    // endregion

    // region Environment

    /**
     * Re-reads permission, ROM folders and installed apps/emulators. Called on resume. Scans
     * quietly on first load, and with a toast when access has just been granted.
     */
    /** Activity resumed: refresh the environment and continue fingerprinting in the background. */
    fun onForeground() {
        foreground = true
        refreshEnvironment()
        music.resumeAfterGame()
        recordFinishedSession()
        viewModelScope.launch { container.media.refresh() }
        if (volumesChangedInBackground) {
            volumesChangedInBackground = false
            refreshFoldersAndRescan(quiet = true)
        }
        if (env.value.storageGranted && scanJob?.isActive != true) fingerprints.start(viewModelScope)
    }

    /** Activity paused (a game or another app is in front): stop background I/O. */
    fun onBackground() {
        foreground = false
        container.sessions.onBackground()
        fingerprints.stop()
        media.onBackground()
    }

    /** Back from a game: add the session to that game's playtime. */
    private fun recordFinishedSession() {
        val finished = container.sessions.onForeground() ?: return
        viewModelScope.launch {
            container.games.addPlayTime(finished.gameId, finished.durationMs)
            val game = container.games.get(finished.gameId) ?: return@launch
            val played = MenuTree.formatPlaytime(finished.durationMs) ?: "under a minute"
            toast("Played ${game.title} for $played")
        }
    }

    fun refreshEnvironment() {
        val wasGranted = env.value.storageGranted
        val granted = storageAccess(context)
        val firstLoad = !environmentLoaded
        environmentLoaded = true
        env.update { it.copy(storageGranted = granted) }
        refreshFolders()
        if (granted && (firstLoad || !wasGranted)) rescan(quiet = firstLoad)
        onAppsChanged()
    }

    fun onAppsChanged() {
        viewModelScope.launch {
            container.apps.refresh()
            env.update { it.copy(installedEmulators = container.launcher.installedEmulatorIds()) }
        }
    }

    fun onFolderPicked(uri: Uri?) {
        uri ?: return
        val path = container.volumes.treeUriToPath(uri)
        if (path == null) {
            toast("That location isn't a local folder")
            return
        }
        settingsStore.update { it.copy(romFolders = (it.romFolders + path).distinct()) }
        refreshFoldersAndRescan()
    }

    /**
     * Storage hot-swap (microSD / USB-OTG mounted or removed). In front: rescan quietly after the
     * burst of mount broadcasts settles. With a game running: do nothing now, rescan on return.
     * Navigation state is never touched, and games on a removed volume stay in the library.
     */
    fun onVolumeEvent(event: VolumeEvent) {
        if (!foreground) {
            volumesChangedInBackground = true
            return
        }
        volumeJob?.cancel()
        volumeJob = viewModelScope.launch {
            delay(VOLUME_SETTLE_MS)
            val before = env.value.mountedRoots.toSet()
            refreshFolders()
            val after = env.value.mountedRoots.toSet()
            val label = { root: String -> volumeLabels[root] ?: root.substringAfterLast('/') }
            (after - before).firstOrNull()?.let { toast("${label(it)} connected") }
            (before - after).firstOrNull()?.let { toast("${label(it)} removed — its games are kept") }
            if (env.value.storageGranted) rescan(quiet = true)
        }
    }

    /** Last known label per volume root, so a removal can still name the drive. */
    private val volumeLabels = HashMap<String, String>()

    /** Recomputes mounted volumes and the library's folders (see [LibraryFolders]). */
    private fun refreshFolders() {
        val granted = env.value.storageGranted
        val volumes = container.volumes.volumes()
        volumes.forEach { volumeLabels[it.root.absolutePath] = it.label }
        val roots = volumes.map { it.root.absolutePath }
        val settings = settingsStore.current
        // Without storage access nothing can be discovered; keep what we know instead of forgetting it.
        val folders = if (granted) {
            val plan = LibraryFolders.plan(
                custom = settings.romFolders,
                discovered = if (settings.autoDetectFolders) container.volumes.discoverRomFolders().map { it.absolutePath } else emptyList(),
                knownAuto = settings.knownAutoFolders,
                mountedRoots = roots,
                autoDetect = settings.autoDetectFolders,
            )
            if (plan.knownAuto != settings.knownAutoFolders) settingsStore.update { it.copy(knownAutoFolders = plan.knownAuto) }
            plan.folders
        } else {
            LibraryFolders.plan(settings.romFolders, emptyList(), settings.knownAutoFolders, roots, autoDetect = false).folders
        }
        env.update { it.copy(folders = folders, mountedRoots = roots) }
    }

    private fun refreshFoldersAndRescan(quiet: Boolean = false) {
        refreshFolders()
        rescan(quiet)
    }

    private fun rescan(quiet: Boolean) {
        if (!env.value.storageGranted) {
            toast("Grant All files access first")
            return
        }
        scanJob?.cancel()
        fingerprints.stop() // resume after the scan, against the updated library
        scanJob = viewModelScope.launch {
            env.update { it.copy(scanStatus = "Scanning…") }
            val roots = env.value.folders.map { File(it.path) }
            val started = System.nanoTime()
            val result = runCatching { container.games.scan(roots) }
            // A newer scan replaced this one: not a failure, nothing to report.
            if (result.exceptionOrNull() is CancellationException) return@launch
            if (BuildConfig.PERF_LOG) {
                val ms = (System.nanoTime() - started) / 1_000_000
                android.util.Log.i("WaveScan", "scan ${result.getOrNull() ?: result.exceptionOrNull()} in $ms ms")
            }
            val status = result.fold(
                onSuccess = { "${it.total} games · +${it.added} −${it.removed}" },
                onFailure = { "Scan failed: ${it.message}" },
            )
            env.update { it.copy(scanStatus = status) }
            if (!quiet || result.isFailure) toast(status)
            if (foreground) fingerprints.start(viewModelScope)
        }
    }

    // endregion

    // region Navigation and actions

    private fun moveCursor(delta: Int) {
        val size = current().items.size
        val category = categories[nav.value.categoryIndex]
        val before = nav.value
        val after = before.moveCursor(category.id, category.title, delta, size)
        if (after != before) {
            nav.value = after
            sounds.play(UiSound.MOVE)
        }
    }

    private fun moveCategory(delta: Int) {
        val before = nav.value
        val after = before.moveCategory(delta, categories.size)
        if (after.categoryIndex != before.categoryIndex) {
            nav.value = after
            sounds.play(UiSound.MOVE)
        }
    }

    private fun back() {
        val category = categories[nav.value.categoryIndex]
        nav.value.pop(category.id, category.title)?.let {
            nav.value = it
            sounds.play(UiSound.BACK)
        }
    }

    private fun activate(node: XmbNode) {
        when (val action = node.action) {
            is NodeAction.OpenList -> {
                val category = categories[nav.value.categoryIndex]
                nav.update { it.push(category.id, category.title, Frame(action.key, node.title)) }
                sounds.play(UiSound.ENTER)
            }
            is NodeAction.LaunchGame -> launchGame(action.gameId)
            is NodeAction.LaunchApp -> {
                val app = container.apps.apps.value.firstOrNull {
                    it.packageName == action.packageName && it.activityName == action.activityName
                } ?: return
                sounds.play(UiSound.ENTER)
                emit(UiEffect.StartActivity(container.apps.launchIntent(app)))
            }
            is NodeAction.Run -> {
                sounds.play(UiSound.ENTER)
                run(action.command)
            }
            is NodeAction.OpenMedia -> openMedia(action)
            NodeAction.None -> Unit
        }
    }

    private fun openMedia(action: NodeAction.OpenMedia) {
        val menus = tree.value.media
        sounds.play(UiSound.ENTER)
        when (action.kind) {
            MediaKind.PHOTO -> media.openPhotos(menus.photos(action.collection), action.index)
            MediaKind.VIDEO -> media.openVideo(menus.videos(action.collection), action.index)
            MediaKind.SONG -> {
                val songs = menus.songs(action.collection)
                music.play(songs, action.index)
                songs.getOrNull(action.index)?.let { toast("Playing ${it.title}") }
            }
        }
    }

    private fun launchGame(gameId: Long) = viewModelScope.launch {
        val game = container.games.get(gameId) ?: return@launch
        when (val plan = container.launcher.plan(game, settingsStore.current.emulatorChoice[game.platformId])) {
            is LaunchPlan.Ready -> {
                container.games.markPlayed(game.id)
                // Start transition: bloom + start cue, then hand off to the emulator.
                transient.update { it.copy(booting = game.title) }
                // Background music: pause for the game (and resume on return) or stop.
                if (settingsStore.current.resumeMusicAfterGames) music.pauseForGame() else music.player.pause()
                if (settingsStore.current.gameBootSound) {
                    sounds.playCue(UiCue.GAMEBOOT, settingsStore.current.soundVolume)
                } else {
                    sounds.play(UiSound.OK)
                }
                delay(GAMEBOOT_HANDOFF_MS)
                container.sessions.arm(game.id)
                emit(UiEffect.StartActivity(plan.intent))
                delay(600)
                transient.update { it.copy(booting = null) }
            }
            is LaunchPlan.Failed -> {
                sounds.play(UiSound.ERROR)
                toast(plan.message)
            }
        }
    }

    private fun run(command: Command) {
        when (command) {
            Command.RequestStorageAccess ->
                if (env.value.storageGranted) toast("Already granted") else emit(UiEffect.RequestStorageAccess)
            Command.AddFolder -> emit(UiEffect.PickFolder)
            is Command.RemoveFolder -> {
                settingsStore.update { it.copy(romFolders = it.romFolders - command.path) }
                toast("Removed ${command.path.substringAfterLast('/')}")
                refreshFoldersAndRescan()
            }
            Command.Rescan -> refreshFoldersAndRescan()
            Command.ToggleAutoDetect -> {
                settingsStore.update { it.copy(autoDetectFolders = !it.autoDetectFolders) }
                refreshFoldersAndRescan()
            }
            Command.CycleConfirmLayout -> settingsStore.update {
                it.copy(confirmLayout = ConfirmLayout.entries[(it.confirmLayout.ordinal + 1) % ConfirmLayout.entries.size])
            }
            Command.CycleDeadzone -> settingsStore.update { it.copy(deadzone = DEADZONES.nextAfter(it.deadzone)) }
            Command.StartRemap -> transient.update { it.copy(remap = RemapSession()) }
            Command.ResetRemap -> {
                settingsStore.update { it.copy(keyRemap = emptyMap()) }
                toast("Button mapping reset")
            }
            is Command.CycleEmulator -> cycleEmulator(command.platformId)
            Command.ToggleWave -> settingsStore.update { it.copy(waveEnabled = !it.waveEnabled) }
            Command.CycleWaveSpeed -> settingsStore.update { it.copy(waveSpeed = WAVE_SPEEDS.nextAfter(it.waveSpeed)) }
            Command.CycleTheme -> settingsStore.update {
                val next = WaveTheme.entries[(WaveTheme.byName(it.theme).ordinal + 1) % WaveTheme.entries.size]
                it.copy(theme = next.name)
            }
            Command.ToggleSoundEffects -> settingsStore.update { it.copy(soundEffects = !it.soundEffects) }
            Command.CycleSoundVolume -> {
                settingsStore.update { it.copy(soundVolume = VOLUMES.nextAfter(it.soundVolume)) }
                sounds.volume = settingsStore.current.soundVolume
                sounds.play(UiSound.OK) // preview the new level
            }
            Command.ToggleBootSound -> settingsStore.update { it.copy(bootSound = !it.bootSound) }
            Command.ToggleGameBootSound -> settingsStore.update { it.copy(gameBootSound = !it.gameBootSound) }
            Command.ToggleLargeImageHashing -> {
                settingsStore.update { it.copy(hashLargeImages = !it.hashLargeImages) }
                if (foreground && scanJob?.isActive != true) fingerprints.restart(viewModelScope)
            }
            Command.RecheckFingerprints -> {
                if (!env.value.storageGranted) {
                    toast("Grant All files access first")
                } else if (scanJob?.isActive != true) {
                    fingerprints.restart(viewModelScope)
                    toast("Checking for files without fingerprints")
                }
            }
            Command.OpenHomeSettings -> emit(UiEffect.StartActivity(Intent(Settings.ACTION_HOME_SETTINGS)))
            Command.OpenAndroidSettings -> emit(UiEffect.StartActivity(Intent(Settings.ACTION_SETTINGS)))
            Command.OpenLibrarySettings -> {
                val index = categories.indexOfFirst { it.id == Categories.SETTINGS }
                val category = categories[index]
                nav.update { it.jumpTo(index, category.id, category.title, Frame(MenuTree.LIBRARY, "Library")) }
            }
            Command.OpenNowPlaying -> media.openNowPlaying()
            Command.ShuffleAllSongs -> {
                val songs = tree.value.media.allSongs
                if (songs.isNotEmpty()) {
                    music.play(songs, songs.indices.random(), shuffle = true)
                    toast("Shuffling ${MediaMenus.count(songs.size, "song")}")
                }
            }
            is Command.SetWallpaper -> container.media.library.value.photos.firstOrNull { it.id == command.photoId }
                ?.let { emit(UiEffect.SetWallpaper(it.uri)) }
            Command.CycleSlideshowInterval -> settingsStore.update {
                it.copy(slideshowSeconds = SLIDESHOW_SECONDS[(SLIDESHOW_SECONDS.indexOf(it.slideshowSeconds) + 1) % SLIDESHOW_SECONDS.size])
            }
            Command.ToggleResumeMusicAfterGames -> settingsStore.update { it.copy(resumeMusicAfterGames = !it.resumeMusicAfterGames) }
            Command.ToggleVideoLoop -> settingsStore.update { it.copy(videoLoop = !it.videoLoop) }
            is Command.LaunchGame -> launchGame(command.gameId)
            Command.OpenSearch -> transient.update { it.copy(search = SearchState()) }
            is Command.SetGameEmulator -> viewModelScope.launch {
                container.games.setEmulatorOverride(command.gameId, command.key)
                sounds.play(UiSound.OK)
                val game = container.games.get(command.gameId) ?: return@launch
                toast(if (command.key == null) "${game.title} uses the platform default" else "Emulator set for ${game.title}")
            }
            is Command.ToggleFavorite -> viewModelScope.launch {
                container.games.toggleFavorite(command.gameId)
                sounds.play(UiSound.OK)
            }
        }
    }

    private fun cycleEmulator(platformId: String) {
        val platform = PlatformRegistry.byId(platformId) ?: return
        val settings = settingsStore.current
        val current = GameLauncher.resolveOption(platform, settings.emulatorChoice[platformId], env.value.installedEmulators)
            ?: platform.emulators.firstOrNull { it.key == settings.emulatorChoice[platformId] }
            ?: platform.emulators.first()
        val next = platform.emulators[(platform.emulators.indexOf(current) + 1) % platform.emulators.size]
        settingsStore.update { it.copy(emulatorChoice = it.emulatorChoice + (platformId to next.key)) }
    }

    private fun emit(effect: UiEffect) {
        _effects.trySend(effect)
    }

    private fun toast(message: String) {
        toastJob?.cancel()
        transient.update { it.copy(toast = message) }
        toastJob = viewModelScope.launch {
            delay(2600)
            transient.update { it.copy(toast = null) }
        }
    }

    // endregion

    // Kept last on purpose: Kotlin initializes properties in textual order, and this block reads
    // state declared throughout the class (a property declared below it would still be null).
    init {
        container.media.watch(viewModelScope)
        viewModelScope.launch {
            settingsStore.settings.collect {
                sounds.enabled = it.soundEffects
                sounds.volume = it.soundVolume
            }
        }
        // Keep open search results in step with the library (favorites, playtime, rescans).
        viewModelScope.launch {
            tree.collect { if (transient.value.search?.query?.isNotBlank() == true) runSearch(0) }
        }
        if (coldBoot && settingsStore.current.bootSound) sounds.playCue(UiCue.COLDBOOT, settingsStore.current.soundVolume)
        refreshEnvironment()
    }

    companion object {
        private val DEADZONES = listOf(0.15f, 0.25f, 0.35f, 0.45f, 0.55f)
        private val WAVE_SPEEDS = listOf(0.5f, 1f, 1.5f, 2f)
        private val VOLUMES = listOf(0.25f, 0.5f, 0.75f, 1f)
        private val SLIDESHOW_SECONDS = listOf(3, 5, 10, 20)
        private const val GAMEBOOT_HANDOFF_MS = 1100L
        private const val VOLUME_SETTLE_MS = 1200L
        private const val SEARCH_DEBOUNCE_MS = 140L

        /** All files access on Android 11+, the legacy read permission before that. */
        fun hasStorageAccess(context: Context): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_GRANTED
            }

        private fun List<Float>.nextAfter(value: Float): Float {
            val i = indexOfFirst { kotlin.math.abs(it - value) < 0.001f }
            return this[(i + 1) % size]
        }
    }
}
