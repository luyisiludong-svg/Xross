package dev.xross.launcher.state

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.xross.launcher.AppContainer
import dev.xross.launcher.BuildConfig
import dev.xross.launcher.audio.UiCue
import dev.xross.launcher.audio.UiSound
import dev.xross.launcher.input.ConfirmLayout
import dev.xross.launcher.input.ConnectedController
import dev.xross.launcher.input.ControllerFamily
import dev.xross.launcher.input.ControllerProfile
import dev.xross.launcher.input.InputConfig
import dev.xross.launcher.input.InputSink
import dev.xross.launcher.input.NavAction
import dev.xross.launcher.input.resolve
import dev.xross.launcher.launch.GameLauncher
import dev.xross.launcher.launch.LaunchPlan
import dev.xross.launcher.storage.PlatformRegistry
import dev.xross.launcher.storage.StorageAccess
import dev.xross.launcher.storage.StorageVolumes
import dev.xross.launcher.storage.VolumeEvent
import dev.xross.launcher.scrape.ProviderId
import dev.xross.launcher.scrape.ScrapeProgress
import dev.xross.launcher.theme.XrossTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.withContext
import java.io.File

/** One-shot requests that need an Activity. */
sealed interface UiEffect {
    data class StartActivity(val intent: Intent) : UiEffect
    data object PickFolder : UiEffect
    data object PickDatFolder : UiEffect
    data object PickBaseRomsFolder : UiEffect
    data class PickMediaFolder(val kind: MediaFolderKind) : UiEffect
    /** Ask for text (account, key, title, search); the answer goes to [XmbViewModel.onTextEntered]. */
    data class PromptText(val prompt: TextPrompt) : UiEffect
    data object RequestStorageAccess : UiEffect
    data class SetWallpaper(val uri: Uri) : UiEffect

    /** Start a game; the activity hands the plan to [dev.xross.launcher.launch.GameLauncher.start]. */
    data class Launch(val plan: LaunchPlan.Ready) : UiEffect

    /** Restart the process (a restored library database is applied on the next start). */
    data object RestartApp : UiEffect
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
    val theme: String = XrossTheme.LAVENDER.name,
    /** Title of the game being started while the start transition plays. */
    val booting: String? = null,
    /** True on the first screen after process start: the UI fades in with the startup cue. */
    val coldBoot: Boolean = false,
    /** Full-screen photo viewer / video player / Now Playing, if open. */
    val overlay: MediaOverlay? = null,
    /** Music state for the mini indicator and Now Playing. */
    val nowPlaying: dev.xross.launcher.media.NowPlaying = dev.xross.launcher.media.NowPlaying(),
    /** The focused item's Options menu while it is open. */
    val optionsMenu: OptionsMenuState? = null,
    /** Search overlay (gamepad keyboard + live results) while it is open. */
    val search: SearchState? = null,
    /** Typing a name (a new app folder) on the gamepad keyboard. */
    val nameEntry: NameEntry? = null,
    /** Information screen for a game, opened from its Options menu. */
    val info: GameInfoState? = null,
    /** Game Details screen. */
    val details: GameDetailsState? = null,
    /** Reading storage: a library scan or background fingerprinting is running. */
    val busy: Boolean = false,
    val showButtonHints: Boolean = true,
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
    val nameEntry: NameEntry? = null,
    val info: GameInfoState? = null,
    val details: GameDetailsState? = null,
    /** A library scan is running (fingerprinting is tracked by [FingerprintStatus.current]). */
    val scanning: Boolean = false,
    /** DATs are being imported or matched. */
    val datWork: Boolean = false,
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

    /** DAT summaries and per-game metadata: one input so the tree's combine stays within five flows. */
    private data class Meta(
        val dats: List<dev.xross.launcher.data.DatSummary> = emptyList(),
        val metadata: Map<Long, dev.xross.launcher.data.GameMetadataEntity> = emptyMap(),
    )

    private val meta = combine(container.dats.summaries, container.database.metadata().observeAll()) { d, m ->
        Meta(d, m.associateBy { it.gameId })
    }

    private val tree: StateFlow<MenuTree> = combine(
        combine(env, meta, container.credentials.accounts, container.screenScraper.quota) { e, m, accounts, quota ->
            e.copy(
                dats = m.dats,
                metadata = m.metadata,
                scraperSetup = ScraperSetup(
                    screenScraperAvailable = BuildConfig.SCREENSCRAPER_DEV_ID.isNotBlank(),
                    screenScraperUser = accounts.screenScraperUser,
                    steamGridDbKeySet = accounts.steamGridDbKey.isNotBlank(),
                    screenScraperQuota = quota?.let { q -> (q.user?.let { "$it · " } ?: "") + q.summary },
                ),
            )
        },
        container.games.games,
        container.apps.apps,
        settingsStore.settings,
        container.media.library,
    ) { e, games, apps, settings, mediaLib ->
        MenuTree(
            e.copy(
                games = games,
                apps = apps,
                settings = settings,
                media = mediaLib,
                datFolder = datFolder(settings.datFolder).absolutePath,
                baseRomsFolder = baseRomsFolder(settings.baseRomsFolder).absolutePath,
            ),
        )
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
    private data class Live(
        val fingerprints: FingerprintStatus,
        val nowPlaying: dev.xross.launcher.media.NowPlaying,
        val scrape: ScrapeProgress = ScrapeProgress(),
    )

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private val live: StateFlow<Live> = combine(fingerprintStatus, music.state, container.scraper.progress.sample(500)) { fp, np, sc ->
        Live(fp, np, sc)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Live(FingerprintStatus(), music.state.value))

    private data class Settled(val transient: Transient, val settings: dev.xross.launcher.data.XrossSettings, val overlay: MediaOverlay?)

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
            nameEntry = tr.nameEntry,
            info = tr.info,
            details = tr.details,
            busy = tr.scanning || tr.datWork || lv.fingerprints.current != null || lv.scrape.running,
            showButtonHints = settings.showButtonHints,
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
            MenuTree.METADATA -> t.metadataSettings(lv.scrape)
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
        get() = settingsStore.current.let {
            InputConfig(
                confirm = it.confirmLayout,
                deadzone = it.deadzone,
                remap = it.keyRemap,
                axialDeadzone = it.axialDeadzone,
                profiles = it.controllerProfiles,
            )
        }

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
        transient.value.nameEntry?.let { return nameEntryAction(it, action) }
        if (action == NavAction.MEDIA) return toggleMediaControls()
        transient.value.info?.let { return infoAction(it, action) }
        transient.value.details?.let { return detailsAction(it, action) }
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
                ui.focused?.action is NodeAction.LaunchGame -> showDetails((ui.focused!!.action as NodeAction.LaunchGame).gameId)
            }
            NavAction.PREV_CATEGORY -> moveCategory(-1)
            NavAction.NEXT_CATEGORY -> moveCategory(+1)
            NavAction.CONFIRM, NavAction.START -> ui.focused?.let(::activate)
            NavAction.BACK -> back()
            NavAction.OPTIONS -> ui.focused?.let(::openOptions)
            NavAction.MEDIA -> Unit // handled above
        }
    }

    /**
     * Media controls shortcut (Select, or a long press of Guide): toggles Now Playing from the
     * XMB, an open Options menu, Information or Details. Search keeps the button for itself, and
     * the photo viewer and video player ignore it.
     */
    private fun toggleMediaControls() {
        if (transient.value.search != null) return
        when (media.toggleNowPlaying()) {
            MediaOverlayController.NowPlayingToggle.OPENED -> {
                sounds.play(UiSound.OPTION)
                infoJob?.cancel()
                detailsJob?.cancel()
                transient.update { it.copy(optionsMenu = null, info = null, details = null) }
            }
            MediaOverlayController.NowPlayingToggle.CLOSED -> sounds.play(UiSound.BACK)
            MediaOverlayController.NowPlayingToggle.NOTHING_PLAYING -> toast(NOTHING_PLAYING)
            MediaOverlayController.NowPlayingToggle.UNAVAILABLE -> Unit
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
            NavAction.PREV_CATEGORY, NavAction.NEXT_CATEGORY, NavAction.MEDIA -> Unit
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

    // region Scraping

    private fun scrapeNow() {
        val scraper = container.scraper
        when {
            !scraper.isConfigured -> toast("Turn on Hasheous or add a ScreenScraper or SteamGridDB account first")
            scraper.progress.value.running -> toast("Already scraping")
            settingsStore.current.scrapeWifiOnly && !dev.xross.launcher.scrape.Network.isUnmetered(context) ->
                toast("Connect to Wi-Fi, or turn off Wi-Fi only")
            else -> {
                scraper.start(viewModelScope)
                toast("Scraping in the background while Xross is open")
            }
        }
    }

    private fun rescrape(gameId: Long) {
        if (!container.scraper.isConfigured) {
            toast("Set up a metadata service first")
            return
        }
        datWork {
            when (container.scraper.scrapeOne(gameId)) {
                dev.xross.launcher.scrape.ScrapeStatus.OK -> "Updated from online sources"
                dev.xross.launcher.scrape.ScrapeStatus.NOT_FOUND -> "No service knows this game — try Find match"
                dev.xross.launcher.scrape.ScrapeStatus.ERROR -> "Couldn't reach the services; try again later"
                null -> "This game needs a fingerprint first; it's taken in the background"
            }
        }
    }

    /** The user picked a search result: lock the game to that identity and scrape it. */
    private fun chooseMatch(command: Command.ChooseMatch) = datWork {
        withContext(Dispatchers.IO) {
            container.database.metadata().edit(command.gameId) { row ->
                val base = dev.xross.launcher.data.DatRepository.withoutDatMatch(row).copy(locked = true, scrapeStatus = null)
                when (command.provider) {
                    ProviderId.SCREENSCRAPER -> base.copy(screenScraperId = command.id)
                    ProviderId.STEAMGRIDDB -> base.copy(steamGridDbId = command.id)
                    ProviderId.HASHEOUS -> base.copy(hasheousId = command.id)
                }
            }
            container.database.games().applyTitles()
        }
        when (container.scraper.scrapeOne(command.gameId)) {
            dev.xross.launcher.scrape.ScrapeStatus.OK -> "Match saved and scraped"
            else -> "Match saved; scraping didn't finish — try Scrape again later"
        }
    }

    /** Answers from the text dialog. Blank clears the value where that makes sense. */
    fun onTextEntered(prompt: TextPrompt, text: String) {
        val value = text.trim()
        when (prompt) {
            is TextPrompt.ScreenScraperUser ->
                if (value.isEmpty()) {
                    container.credentials.update { it.copy(screenScraperUser = "", screenScraperPassword = "") }
                    toast("Signed out of ScreenScraper")
                } else {
                    emit(UiEffect.PromptText(TextPrompt.ScreenScraperPassword(value)))
                }
            is TextPrompt.ScreenScraperPassword -> {
                container.credentials.update { it.copy(screenScraperUser = prompt.user, screenScraperPassword = text) }
                toast("ScreenScraper account saved")
            }
            TextPrompt.SteamGridDbKey -> {
                container.credentials.update { it.copy(steamGridDbKey = value) }
                toast(if (value.isEmpty()) "SteamGridDB key removed" else "SteamGridDB key saved")
            }
            is TextPrompt.GameTitle -> viewModelScope.launch {
                withContext(Dispatchers.IO) {
                    container.database.metadata().edit(prompt.gameId) { it.copy(titleOverride = value.ifEmpty { null }) }
                    container.database.games().applyTitles()
                }
                toast(if (value.isEmpty()) "Automatic title restored" else "Title changed")
            }
            is TextPrompt.MatchQuery -> {
                if (value.isEmpty()) return
                datWork {
                    val hits = container.scraper.search(value, prompt.platformId)
                    if (hits.isEmpty()) return@datWork "No matches for “$value”"
                    val options = hits.take(30).map { hit ->
                        MenuOption(
                            hit.title,
                            detail = listOfNotNull(hit.provider.label, hit.detail).joinToString(" · "),
                            command = Command.ChooseMatch(prompt.gameId, hit.provider, hit.id),
                        )
                    }
                    sounds.play(UiSound.OPTION)
                    transient.update { it.copy(optionsMenu = OptionsMenuState.open("Choose a match for “$value”", options)) }
                    "${hits.size} matches"
                }
            }
        }
    }

    // endregion

    // region Details screen

    private var detailsJob: Job? = null

    private fun showDetails(gameId: Long) {
        detailsJob?.cancel()
        detailsJob = viewModelScope.launch {
            val details = withContext(Dispatchers.IO) {
                val game = container.games.get(gameId) ?: return@withContext null
                GameDetailsState.build(
                    game,
                    PlatformRegistry.byId(game.platformId),
                    container.database.metadata().get(gameId),
                    container.assets.version.value,
                )
            } ?: return@launch
            sounds.play(UiSound.ENTER)
            transient.update { it.copy(details = details) }
        }
    }

    private fun detailsAction(details: GameDetailsState, action: NavAction) {
        when (action) {
            NavAction.UP, NavAction.DOWN -> {
                val moved = details.scrollBy(if (action == NavAction.UP) -1 else 1)
                if (moved != details) transient.update { it.copy(details = moved) }
            }
            NavAction.CONFIRM, NavAction.START -> {
                transient.update { it.copy(details = null) }
                launchGame(details.gameId)
            }
            NavAction.BACK, NavAction.LEFT -> closeDetails()
            NavAction.OPTIONS -> {
                closeDetails()
                current().items.firstOrNull { (it.action as? NodeAction.LaunchGame)?.gameId == details.gameId }?.let(::openOptions)
            }
            NavAction.RIGHT, NavAction.PREV_CATEGORY, NavAction.NEXT_CATEGORY, NavAction.MEDIA -> Unit
        }
    }

    fun closeDetails() {
        detailsJob?.cancel()
        if (transient.value.details != null) {
            sounds.play(UiSound.BACK)
            transient.update { it.copy(details = null) }
        }
    }

    // endregion

    // region DATs

    private var datJob: Job? = null

    /** Runs DAT work with the busy indicator on and toasts its summary; one job at a time. */
    private fun datWork(work: suspend () -> String) {
        if (datJob?.isActive == true) {
            toast("Still working on DATs…")
            return
        }
        datJob = viewModelScope.launch {
            transient.update { it.copy(datWork = true) }
            try {
                toast(work())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sounds.play(UiSound.ERROR)
                toast("DAT error: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                transient.update { it.copy(datWork = false) }
            }
        }
    }

    private fun importDats() {
        if (!env.value.storageGranted) {
            toast("Grant All files access first")
            return
        }
        val dir = datFolder(settingsStore.current.datFolder)
        datWork {
            withContext(Dispatchers.IO) { dir.mkdirs() }
            val result = container.dats.importFolder(dir)
            val verified = container.database.metadata().getAll().count { it.matchKind != null }
            when {
                result.failures.isNotEmpty() -> "Couldn't read ${result.failures.first()}"
                result.imported + result.unchanged == 0 -> "No DATs in ${dir.absolutePath}"
                else -> buildList {
                    if (result.imported > 0) add("Imported ${result.imported} DAT" + if (result.imported == 1) "" else "s")
                    if (result.imported == 0) add("DATs up to date")
                    if (result.removed > 0) add("${result.removed} removed")
                    add(gamesVerified(verified))
                }.joinToString(" · ")
            }
        }
    }

    fun onDatFolderPicked(uri: Uri?) {
        uri ?: return
        val path = container.volumes.treeUriToPath(uri)
        if (path == null) {
            toast("That location isn't a local folder")
            return
        }
        settingsStore.update { it.copy(datFolder = path) }
        importDats()
    }

    // endregion

    // region Information screen

    private var infoJob: Job? = null

    /**
     * Loads the game, its fingerprint and a file check off the main thread, then opens the
     * Information screen. Built from the tree as it is now, so it names the emulator that would
     * actually launch.
     */
    private fun showGameInfo(gameId: Long) {
        infoJob?.cancel()
        infoJob = viewModelScope.launch {
            val t = tree.value
            val info = withContext(Dispatchers.IO) {
                val game = container.games.get(gameId) ?: return@withContext null
                val db = container.database
                val hash = db.hashes().get(gameId)
                val metadata = db.metadata().get(gameId)
                val datRom = metadata?.datRomId?.let { db.dats().rom(it) }
                val offline = t.isOffline(game.path)
                GameInfo.build(
                    game = game,
                    hash = hash,
                    platform = PlatformRegistry.byId(game.platformId),
                    emulator = t.emulatorSummary(game),
                    offline = offline,
                    fileExists = if (offline) null else File(game.path).exists(),
                    metadata = metadata,
                    datRom = datRom,
                    datFile = datRom?.let { db.dats().file(it.datId) },
                    anyDats = db.dats().files().isNotEmpty(),
                )
            } ?: return@launch
            transient.update { it.copy(info = info) }
        }
    }

    private fun infoAction(info: GameInfoState, action: NavAction) {
        val delta = when (action) {
            NavAction.UP -> -1
            NavAction.DOWN -> 1
            NavAction.PREV_CATEGORY -> -INFO_PAGE
            NavAction.NEXT_CATEGORY -> INFO_PAGE
            NavAction.BACK, NavAction.CONFIRM, NavAction.OPTIONS, NavAction.START -> return closeInfo()
            NavAction.LEFT, NavAction.RIGHT, NavAction.MEDIA -> return
        }
        val moved = info.move(delta)
        if (moved != info) {
            sounds.play(UiSound.MOVE)
            transient.update { it.copy(info = moved) }
        }
    }

    fun closeInfo() {
        infoJob?.cancel()
        if (transient.value.info != null) {
            sounds.play(UiSound.BACK)
            transient.update { it.copy(info = null) }
        }
    }

    /** Touch: tap a row to highlight it. */
    fun onInfoRowTapped(index: Int) {
        val info = transient.value.info ?: return
        transient.update { it.copy(info = info.moveTo(index)) }
    }

    // endregion

    override fun onRawKeyDown(keyCode: Int, controller: ConnectedController?): Boolean {
        val tr = transient.value
        tr.nameEntry?.let { return nameRawKey(it, keyCode) }
        if (tr.remap == null && tr.optionsMenu == null && tr.info == null && tr.details == null && tr.booting == null && !media.isOpen) {
            tr.search?.let { return searchRawKey(it, keyCode) }
        }
        val session = transient.value.remap ?: return false
        val next = session.capture(keyCode, controller)
        if (next.isDone) {
            saveRemap(next)
            transient.update { it.copy(remap = null) }
            sounds.play(UiSound.OK)
            val name = next.controller?.name?.ifBlank { null }
            toast(if (name != null) "Buttons remapped for $name" else "Buttons remapped")
        } else {
            if (next.step != session.step) sounds.play(UiSound.MOVE)
            transient.update { it.copy(remap = next) }
        }
        return true
    }

    /**
     * An identified controller gets its own profile (other models keep theirs); a device without
     * IDs replaces the global mapping.
     */
    private fun saveRemap(session: RemapSession) {
        val controller = session.controller
        if (controller != null) {
            val profile = ControllerProfile(controller.name, session.captured)
            settingsStore.update { it.copy(controllerProfiles = it.controllerProfiles + (controller.key.id to profile)) }
        } else {
            settingsStore.update { it.copy(keyRemap = session.captured) }
        }
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
        if (settingsStore.current.scrapeAutomatically && scanJob?.isActive != true) container.scraper.start(viewModelScope)
    }

    /** Activity paused (a game or another app is in front): stop background I/O. */
    fun onBackground() {
        foreground = false
        container.sessions.onBackground()
        fingerprints.stop()
        container.scraper.stop()
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
        viewModelScope.launch { syncXrossFolder(firstLoad) }
    }

    // region Xross folder and backups

    /**
     * Follows the Xross folder: once shared storage can be written it moves out of app storage,
     * and config already there (an SD card from another device, a reinstall) is adopted. Config
     * files edited outside Xross are picked up. On a clean install a backup found in the folder
     * is offered for restore.
     */
    private suspend fun syncXrossFolder(firstLoad: Boolean) {
        val previous = container.environment.current
        val moved = withContext(Dispatchers.IO) {
            val changed = container.environment.refresh()
            if (changed) settingsStore.relocate(container.environment.current) else settingsStore.reloadIfChanged()
            changed
        }
        if (moved) moveArtFrom(previous)
        publishPortable()
        if (firstLoad || moved) {
            val restorable = container.backups.findRestorable()
            env.update { it.copy(portable = it.portable.copy(restorable = restorable)) }
            if (restorable != null && container.backups.announceOnce(restorable)) {
                toast("Found a library backup with ${countOf(restorable.games, "game")} — restore it from Games")
            }
        }
    }

    /** Art follows the Xross folder to its new place, in the background. */
    private fun moveArtFrom(previous: dev.xross.launcher.storage.XrossLayout) {
        if (previous.media.absolutePath == container.environment.current.media.absolutePath) return
        viewModelScope.launch {
            val report = runCatching { container.assets.relocateFrom(previous.media) }.getOrNull() ?: return@launch
            if (report.moved > 0) toast("Moved ${countOf(report.moved, "art file")} to the new Xross folder")
        }
    }

    private suspend fun publishPortable() {
        val layout = container.environment.current
        val onDisk = container.backups.currentBackup()
        env.update {
            it.copy(portable = it.portable.copy(root = layout.root.absolutePath, portable = layout.portable, backupOnDisk = onDisk))
        }
    }

    private fun cycleXrossVolume() {
        val layout = container.environment.current
        if (!layout.portable) {
            emit(UiEffect.RequestStorageAccess)
            return
        }
        viewModelScope.launch {
            val volumes = withContext(Dispatchers.IO) { container.volumes.volumes() }.sortedByDescending { it.primary }
            if (volumes.size < 2) {
                toast("No other drive is connected")
                return@launch
            }
            val previous = container.environment.current
            val currentVolume = StorageVolumes.volumeRootOf(layout.root.absolutePath)
            val index = volumes.indexOfFirst { it.root.path.trimEnd('/') == currentVolume }
            val next = volumes[(index + 1) % volumes.size]
            val moved = withContext(Dispatchers.IO) {
                container.environment.chooseVolume(if (next.primary) null else next.root).also { changed ->
                    if (changed) settingsStore.relocate(container.environment.current)
                }
            }
            val now = container.environment.current
            if (moved && now.root.absolutePath.startsWith(next.root.path)) {
                sounds.play(UiSound.OK)
                toast("Xross folder: ${now.root.absolutePath}")
                moveArtFrom(previous)
                container.backups.backupNow()
            } else {
                sounds.play(UiSound.ERROR)
                toast("${next.label} can't be written; the Xross folder stays at ${now.root.absolutePath}")
            }
            publishPortable()
        }
    }

    // region Apps

    private fun launchApp(packageName: String, activityName: String) {
        val app = container.apps.apps.value.firstOrNull { it.packageName == packageName && it.activityName == activityName } ?: return
        sounds.play(UiSound.ENTER)
        emit(UiEffect.StartActivity(container.apps.launchIntent(app)))
    }

    private fun appLabel(packageName: String): String =
        container.apps.apps.value.firstOrNull { it.packageName == packageName }?.label ?: packageName

    private fun moveAppToFolder(packageName: String, folder: String?) {
        val name = folder?.let { AppFolders.normalizeName(it) }
        settingsStore.update { s ->
            s.copy(appFolders = if (name == null) s.appFolders - packageName else s.appFolders + (packageName to name))
        }
        sounds.play(UiSound.OK)
        toast(if (name == null) "${appLabel(packageName)} is out of its folder" else "${appLabel(packageName)} moved to $name")
    }

    private fun nameEntryAction(entry: NameEntry, action: NavAction) {
        val outcome = entry.onAction(action)
        applyNameEntry(entry, outcome)
    }

    private fun applyNameEntry(before: NameEntry, outcome: NameEntryOutcome) {
        when (val effect = outcome.effect) {
            NameEntryEffect.None -> if (outcome.state != before) {
                sounds.play(if (outcome.state.text != before.text) UiSound.ENTER else UiSound.MOVE)
                transient.update { it.copy(nameEntry = outcome.state) }
            }
            NameEntryEffect.Close -> closeNameEntry()
            is NameEntryEffect.Submit -> {
                transient.update { it.copy(nameEntry = null) }
                when (val purpose = before.purpose) {
                    is NamePurpose.NewAppFolder -> {
                        val name = AppFolders.normalizeName(effect.text)
                        if (name == null) toast("Type a name first") else moveAppToFolder(purpose.packageName, name)
                    }
                }
            }
        }
    }

    fun closeNameEntry() {
        if (transient.value.nameEntry != null) {
            sounds.play(UiSound.BACK)
            transient.update { it.copy(nameEntry = null) }
        }
    }

    /** Touch: tap a key of the name keyboard. */
    fun onNameKeyTapped(row: Int, column: Int) {
        val entry = transient.value.nameEntry ?: return
        applyNameEntry(entry, entry.tap(row, column))
    }

    /** Hardware keyboards type names directly. */
    private fun nameRawKey(entry: NameEntry, keyCode: Int): Boolean {
        val next = when (keyCode) {
            in android.view.KeyEvent.KEYCODE_A..android.view.KeyEvent.KEYCODE_Z -> entry.type('A' + (keyCode - android.view.KeyEvent.KEYCODE_A))
            in android.view.KeyEvent.KEYCODE_0..android.view.KeyEvent.KEYCODE_9 -> entry.type('0' + (keyCode - android.view.KeyEvent.KEYCODE_0))
            android.view.KeyEvent.KEYCODE_SPACE -> entry.type(' ')
            android.view.KeyEvent.KEYCODE_DEL -> entry.backspace()
            android.view.KeyEvent.KEYCODE_ENTER -> return true.also { applyNameEntry(entry, entry.onAction(NavAction.START)) }
            else -> return false
        }
        applyNameEntry(entry, NameEntryOutcome(next))
        return true
    }

    // endregion

    // region Base ROMs folder and media folders

    /** The base ROMs folder setting, or `roms/` in the Xross folder in use. */
    private fun baseRomsFolder(setting: String): File =
        if (setting.isBlank()) container.environment.current.roms else File(setting)

    fun onBaseRomsFolderPicked(uri: Uri?) {
        uri ?: return
        val path = container.volumes.treeUriToPath(uri) ?: return toast("That location isn't a local folder")
        settingsStore.update { it.copy(baseRomsFolder = path) }
        toast("Base ROMs folder: $path")
        refreshFoldersAndRescan()
    }

    private fun createSystemFolders() {
        if (!env.value.storageGranted || !container.environment.current.portable) {
            emit(UiEffect.RequestStorageAccess)
            return
        }
        viewModelScope.launch {
            val base = baseRomsFolder(settingsStore.current.baseRomsFolder)
            val result = withContext(Dispatchers.IO) { dev.xross.launcher.storage.SystemFolders.create(base) }
            when {
                !result.ok -> {
                    sounds.play(UiSound.ERROR)
                    toast("Couldn't create ${countOf(result.failed.size, "folder")} in ${base.absolutePath}")
                }
                result.created.isEmpty() -> toast("All ${result.existing.size} system folders are already in ${base.absolutePath}")
                else -> {
                    sounds.play(UiSound.OK)
                    toast("Created ${countOf(result.created.size, "folder")} in ${base.absolutePath}")
                }
            }
            refreshFolders()
        }
    }

    fun onMediaFolderPicked(kind: MediaFolderKind, uri: Uri?) {
        uri ?: return
        val path = container.volumes.treeUriToPath(uri)?.trimEnd('/') ?: return toast("That location isn't a local folder")
        updateMediaFolders(kind) { (it + path).distinct() }
        toast("${kind.label} now includes $path")
    }

    private fun updateMediaFolders(kind: MediaFolderKind, transform: (List<String>) -> List<String>) {
        settingsStore.update {
            when (kind) {
                MediaFolderKind.PHOTO -> it.copy(photoFolders = transform(it.photoFolders))
                MediaFolderKind.VIDEO -> it.copy(videoFolders = transform(it.videoFolders))
            }
        }
    }

    /** Applies the folder lists to the media library and reloads it when they changed. */
    private fun syncMediaFolders(s: dev.xross.launcher.data.XrossSettings) {
        if (container.media.setFolders(dev.xross.launcher.media.MediaFolders(s.photoFolders, s.videoFolders))) {
            viewModelScope.launch { container.media.refresh() }
        }
    }

    // endregion

    /** The DAT folder setting, or `dats/` in the Xross folder in use. */
    private fun datFolder(setting: String): File =
        if (setting.isBlank()) container.environment.current.dats else File(setting)

    private fun backupToRestore(): dev.xross.launcher.data.BackupInfo? =
        env.value.portable.restorable ?: env.value.portable.backupOnDisk

    private fun confirmRestore() {
        val backup = backupToRestore() ?: return toast("No library backup in the Xross folder")
        sounds.play(UiSound.OPTION)
        val option = MenuOption(
            "Replace library and restart",
            detail = "${countOf(backup.games, "game")} · saved ${MenuTree.formatTime(backup.modifiedAt)}",
            command = Command.RestoreBackup,
        )
        transient.update { it.copy(optionsMenu = OptionsMenuState.open("Restore library backup", listOf(option))) }
    }

    private fun restoreBackup() {
        val backup = backupToRestore() ?: return
        viewModelScope.launch {
            fingerprints.stop()
            container.scraper.stop()
            withContext(Dispatchers.IO) { settingsStore.flush() }
            when (val result = container.backups.stageRestore(backup)) {
                is dev.xross.launcher.data.BackupResult.Done -> {
                    sounds.play(UiSound.OK)
                    toast("Restoring ${countOf(result.info.games, "game")}…")
                    delay(RESTART_DELAY_MS)
                    emit(UiEffect.RestartApp)
                }
                is dev.xross.launcher.data.BackupResult.Skipped -> toast(result.reason)
                is dev.xross.launcher.data.BackupResult.Failed -> {
                    sounds.play(UiSound.ERROR)
                    toast("Restore failed: ${result.reason}")
                }
            }
        }
    }

    private var backupJob: Job? = null

    /** The launcher is no longer visible: back up the library shortly after, if it changed. */
    fun onStopped() {
        backupJob?.cancel()
        backupJob = viewModelScope.launch {
            delay(BACKUP_DELAY_MS)
            container.backups.backupIfChanged()
            publishPortable()
        }
    }

    // endregion

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
            // Custom Photo/Video folders on that drive appear or disappear.
            container.media.refresh()
            // The Xross folder itself may have been on that drive.
            syncXrossFolder(firstLoad = false)
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
                base = baseRomsFolder(settings.baseRomsFolder).absolutePath,
            )
            if (plan.knownAuto != settings.knownAutoFolders) settingsStore.update { it.copy(knownAutoFolders = plan.knownAuto) }
            plan.folders
        } else {
            LibraryFolders.plan(
                settings.romFolders, emptyList(), settings.knownAutoFolders, roots, autoDetect = false,
                base = baseRomsFolder(settings.baseRomsFolder).absolutePath,
            ).folders
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
            val self = coroutineContext[Job]
            transient.update { it.copy(scanning = true) }
            try {
                scan(quiet)
            } finally {
                // A newer scan that replaced this one owns the flag now.
                if (scanJob == null || scanJob === self) transient.update { it.copy(scanning = false) }
            }
        }
    }

    /** One library scan: runs the repository scan (on Dispatchers.IO), then reports and resumes hashing. */
    private suspend fun scan(quiet: Boolean) {
        env.update { it.copy(scanStatus = "Scanning…") }
        val roots = env.value.folders.map { File(it.path) }
        val started = System.nanoTime()
        val result = runCatching { container.games.scan(roots) }
        // A newer scan replaced this one: not a failure, nothing to report.
        if (result.exceptionOrNull() is CancellationException) return
        if (BuildConfig.PERF_LOG) {
            val ms = (System.nanoTime() - started) / 1_000_000
            android.util.Log.i("XrossScan", "scan ${result.getOrNull() ?: result.exceptionOrNull()} in $ms ms")
        }
        val status = result.fold(
            onSuccess = { "${it.total} games · +${it.added} −${it.removed}" },
            onFailure = { "Scan failed: ${it.message}" },
        )
        env.update { it.copy(scanStatus = status) }
        if (!quiet || result.isFailure) toast(status)
        if (foreground) {
            fingerprints.start(viewModelScope)
            if (settingsStore.current.scrapeAutomatically) container.scraper.start(viewModelScope)
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
            is NodeAction.LaunchApp -> launchApp(action.packageName, action.activityName)
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
                emit(UiEffect.Launch(plan))
                delay(600)
                transient.update { it.copy(booting = null) }
            }
            is LaunchPlan.Failed -> {
                sounds.play(UiSound.ERROR)
                toast(plan.message)
            }
        }
    }

    /** The emulator couldn't be started after all (uninstalled meanwhile, refused the URI). */
    fun onLaunchFailed(message: String) {
        container.sessions.disarm()
        transient.update { it.copy(booting = null) }
        sounds.play(UiSound.ERROR)
        toast(message)
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
                settingsStore.update { it.copy(keyRemap = emptyMap(), controllerProfiles = emptyMap()) }
                toast("All button mappings reset")
            }
            Command.ResetGlobalRemap -> {
                settingsStore.update { it.copy(keyRemap = emptyMap()) }
                toast("Default mapping reset")
            }
            is Command.ResetControllerProfile -> {
                val name = settingsStore.current.controllerProfiles[command.id]?.name?.ifBlank { null }
                settingsStore.update { it.copy(controllerProfiles = it.controllerProfiles - command.id) }
                toast(if (name != null) "Mapping reset for $name" else "Controller mapping reset")
            }
            Command.CycleAxialDeadzone -> settingsStore.update {
                it.copy(axialDeadzone = AXIAL_DEADZONES.nextAfter(it.axialDeadzone))
            }
            Command.ToggleButtonHints -> settingsStore.update { it.copy(showButtonHints = !it.showButtonHints) }
            is Command.ShowGameInfo -> showGameInfo(command.gameId)
            Command.ImportDats -> importDats()
            Command.ScrapeNow -> scrapeNow()
            Command.ToggleScrapeAutomatically -> {
                settingsStore.update { it.copy(scrapeAutomatically = !it.scrapeAutomatically) }
                if (settingsStore.current.scrapeAutomatically) container.scraper.start(viewModelScope) else container.scraper.stop()
            }
            Command.ToggleScrapeWifiOnly -> settingsStore.update { it.copy(scrapeWifiOnly = !it.scrapeWifiOnly) }
            Command.ToggleScrapeVideos -> settingsStore.update { it.copy(scrapeVideos = !it.scrapeVideos) }
            Command.CycleScrapeRegion -> settingsStore.update { it.copy(scrapeRegion = MenuTree.REGIONS.nextOf(it.scrapeRegion)) }
            Command.CycleScrapeLanguage -> settingsStore.update { it.copy(scrapeLanguage = MenuTree.LANGUAGES.nextOf(it.scrapeLanguage)) }
            Command.ToggleHasheous -> settingsStore.update { it.copy(hasheousEnabled = !it.hasheousEnabled) }
            Command.EditScreenScraperAccount ->
                if (BuildConfig.SCREENSCRAPER_DEV_ID.isBlank()) {
                    toast("This build has no ScreenScraper developer credentials")
                } else {
                    emit(UiEffect.PromptText(TextPrompt.ScreenScraperUser(container.credentials.current.screenScraperUser)))
                }
            Command.EditSteamGridDbKey -> emit(UiEffect.PromptText(TextPrompt.SteamGridDbKey))
            Command.CheckScreenScraperAccount -> datWork {
                when (val check = container.screenScraper.checkAccount()) {
                    is dev.xross.launcher.scrape.providers.AccountCheck.Ok ->
                        "Signed in" + (check.quota.user?.let { " as $it" } ?: "") + " · " + check.quota.summary
                    is dev.xross.launcher.scrape.providers.AccountCheck.Failed -> check.reason
                }
            }
            Command.ClearArtwork -> viewModelScope.launch {
                container.scraper.stop()
                val freed = container.assets.clear()
                withContext(Dispatchers.IO) {
                    val db = container.database.metadata()
                    db.upsertAll(db.getAll().map { it.copy(scrapeStatus = null) })
                }
                toast("Freed ${MenuTree.formatSize(freed)} · art downloads again on the next scrape")
            }
            is Command.ShowDetails -> showDetails(command.gameId)
            is Command.EditTitle -> viewModelScope.launch {
                val game = container.games.get(command.gameId) ?: return@launch
                val current = container.database.metadata().get(game.id)?.titleOverride ?: game.title
                emit(UiEffect.PromptText(TextPrompt.GameTitle(game.id, current)))
            }
            is Command.FindMatch -> viewModelScope.launch {
                val game = container.games.get(command.gameId) ?: return@launch
                emit(UiEffect.PromptText(TextPrompt.MatchQuery(game.id, game.platformId, game.title)))
            }
            is Command.ChooseMatch -> chooseMatch(command)
            is Command.Rescrape -> rescrape(command.gameId)
            is Command.ResetMetadata -> viewModelScope.launch {
                withContext(Dispatchers.IO) {
                    container.database.metadata().edit(command.gameId) {
                        it.copy(locked = false, titleOverride = null, screenScraperId = null, steamGridDbId = null, scrapeStatus = null)
                    }
                }
                container.dats.matchAll()
                toast("Back to the automatic match")
                if (container.scraper.isConfigured) rescrape(command.gameId)
            }
            Command.PickDatFolder -> emit(UiEffect.PickDatFolder)
            Command.MatchDats -> datWork { container.dats.matchAll().let { "${gamesVerified(it.matched)}" } }
            is Command.RemoveDat -> datWork {
                container.dats.remove(command.id)
                "DAT removed"
            }
            is Command.CycleEmulator -> cycleEmulator(command.platformId)
            Command.ToggleWave -> settingsStore.update { it.copy(waveEnabled = !it.waveEnabled) }
            Command.CycleWaveSpeed -> settingsStore.update { it.copy(waveSpeed = WAVE_SPEEDS.nextAfter(it.waveSpeed)) }
            Command.CycleTheme -> settingsStore.update {
                val next = XrossTheme.entries[(XrossTheme.byName(it.theme).ordinal + 1) % XrossTheme.entries.size]
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
            Command.ToggleAllGamesFolder -> settingsStore.update { it.copy(allGamesFolder = !it.allGamesFolder) }
            Command.CycleXrossVolume -> cycleXrossVolume()
            Command.BackupNow -> viewModelScope.launch {
                when (val result = container.backups.backupNow()) {
                    is dev.xross.launcher.data.BackupResult.Done -> {
                        sounds.play(UiSound.OK)
                        toast("Library backed up (${countOf(result.info.games, "game")})")
                    }
                    is dev.xross.launcher.data.BackupResult.Skipped -> toast(result.reason)
                    is dev.xross.launcher.data.BackupResult.Failed -> {
                        sounds.play(UiSound.ERROR)
                        toast("Backup failed: ${result.reason}")
                    }
                }
                publishPortable()
            }
            Command.ConfirmRestore -> confirmRestore()
            Command.RestoreBackup -> restoreBackup()
            is Command.LaunchApp -> launchApp(command.packageName, command.activityName)
            is Command.HideApp -> {
                settingsStore.update { it.copy(hiddenApps = it.hiddenApps + command.packageName) }
                toast("${appLabel(command.packageName)} hidden — Settings ▸ Apps ▸ Hidden apps shows it again")
            }
            is Command.UnhideApp -> {
                settingsStore.update { it.copy(hiddenApps = it.hiddenApps - command.packageName) }
                toast("${appLabel(command.packageName)} is back")
            }
            Command.UnhideAllApps -> {
                val n = settingsStore.current.hiddenApps.size
                settingsStore.update { it.copy(hiddenApps = emptySet()) }
                toast(if (n == 1) "1 app is back" else "$n apps are back")
            }
            Command.ToggleHideSystemApps -> settingsStore.update { it.copy(hideSystemApps = !it.hideSystemApps) }
            is Command.MoveAppToFolder -> moveAppToFolder(command.packageName, command.folder)
            is Command.NewAppFolder -> {
                sounds.play(UiSound.OPTION)
                transient.update {
                    it.copy(nameEntry = NameEntry("New folder for ${appLabel(command.packageName)}", NamePurpose.NewAppFolder(command.packageName)))
                }
            }
            Command.GroupEmulators -> {
                val loose = AppFolders.ungroupedEmulators(container.apps.apps.value, settingsStore.current)
                if (loose.isEmpty()) {
                    toast("Every emulator is already in a folder")
                } else {
                    settingsStore.update { s -> s.copy(appFolders = s.appFolders + loose.associate { it.packageName to AppFolders.EMULATORS }) }
                    sounds.play(UiSound.OK)
                    toast("Moved ${countOf(loose.size, "emulator")} into ${AppFolders.EMULATORS}")
                }
            }
            Command.ClearAppFolders -> {
                settingsStore.update { it.copy(appFolders = emptyMap()) }
                toast("App folders removed; the apps are back in Apps")
            }
            Command.PickBaseRomsFolder -> emit(UiEffect.PickBaseRomsFolder)
            Command.ResetBaseRomsFolder -> {
                settingsStore.update { it.copy(baseRomsFolder = "") }
                toast("Base ROMs folder: ${baseRomsFolder("").absolutePath}")
                refreshFoldersAndRescan()
            }
            Command.CreateSystemFolders -> createSystemFolders()
            is Command.AddMediaFolder -> emit(UiEffect.PickMediaFolder(command.kind))
            is Command.RemoveMediaFolder -> {
                updateMediaFolders(command.kind) { it - command.path }
                toast("Removed ${command.path.substringAfterLast('/')}")
            }
            is Command.ClearMediaFolders -> {
                updateMediaFolders(command.kind) { emptyList() }
                toast("${command.kind.label} shows everything again")
            }
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
        container.backups.watch()
        // Art is named after each game's ROM: keep the index current, then move the old private cache once.
        viewModelScope.launch {
            var migrated = false
            container.games.games.collect { games ->
                container.assets.setLibrary(games)
                if (!migrated && games.isNotEmpty()) {
                    migrated = true
                    viewModelScope.launch { runCatching { container.assets.migrateLegacy() } }
                }
            }
        }
        syncMediaFolders(settingsStore.current)
        viewModelScope.launch { settingsStore.settings.collect(::syncMediaFolders) }
        viewModelScope.launch {
            container.backups.status.collect { status -> env.update { it.copy(portable = it.portable.copy(backup = status)) } }
        }
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
        /** 0 = "Off — hardware only": just the driver's flat zone (see [dev.xross.launcher.input.StickTracker]). */
        internal val DEADZONES = listOf(0f, 0.15f, 0.25f, 0.35f, 0.45f, 0.55f)
        private val AXIAL_DEADZONES = listOf(0f, 0.1f, 0.2f, 0.3f)
        private const val INFO_PAGE = 5
        private val WAVE_SPEEDS = listOf(0.5f, 1f, 1.5f, 2f)
        private val VOLUMES = listOf(0.25f, 0.5f, 0.75f, 1f)
        private val SLIDESHOW_SECONDS = listOf(3, 5, 10, 20)
        private const val GAMEBOOT_HANDOFF_MS = 1100L
        private const val VOLUME_SETTLE_MS = 1200L
        private const val SEARCH_DEBOUNCE_MS = 140L
        private const val BACKUP_DELAY_MS = 4_000L
        private const val RESTART_DELAY_MS = 900L

        private fun countOf(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"
        internal const val NOTHING_PLAYING = "Nothing is playing — choose music in the Music category"

        /** All files access on Android 11+, the legacy read permission before that. */
        fun hasStorageAccess(context: Context): Boolean = StorageAccess.canRead(context)

        private fun gamesVerified(n: Int) = if (n == 1) "1 game verified" else "$n games verified"

        private fun List<String>.nextOf(value: String): String = this[(indexOf(value) + 1) % size]

        private fun List<Float>.nextAfter(value: Float): Float {
            val i = indexOfFirst { kotlin.math.abs(it - value) < 0.001f }
            return this[(i + 1) % size]
        }
    }
}
