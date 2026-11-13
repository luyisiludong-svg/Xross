package dev.xross.launcher.state

import dev.xross.launcher.audio.MusicUnderSnaps
import dev.xross.launcher.audio.VolumeChannel
import androidx.core.net.toUri
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.xross.launcher.AppContainer
import dev.xross.launcher.BuildConfig
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import dev.xross.launcher.profile.RaResult

/** One-shot requests that need an Activity. */
sealed interface UiEffect {
    data class StartActivity(val intent: Intent) : UiEffect
    data object PickFolder : UiEffect
    data object PickDatFolder : UiEffect
    /** Pick a `.xtheme` file; answers [XmbViewModel.onThemeArchivePicked]. */
    data object PickThemeArchive : UiEffect
    data object PickBaseRomsFolder : UiEffect
    data class PickMediaFolder(val kind: MediaFolderKind) : UiEffect
    /** Ask for text (account, key, title, search); the answer goes to [XmbViewModel.onTextEntered]. */
    data class PromptText(val prompt: TextPrompt) : UiEffect
    /** RetroAchievements link dialog (user name + Web API key); answers [XmbViewModel.onRetroAchievementsEntered]. */
    data class PromptRetroAchievements(val user: String, val linked: Boolean) : UiEffect
    data object RequestStorageAccess : UiEffect
    data class SetWallpaper(val uri: Uri) : UiEffect
    /** Pick an image for the Xross wallpaper; answers [XmbViewModel.onWallpaperPicked]. */
    data object PickWallpaperImage : UiEffect

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
    /** Icon of the open sub-list's folder (the row it was opened from); null at a category root. */
    val folderGlyph: GlyphKey? = null,
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
    /** The linked RetroAchievements profile behind the status bar avatar. */
    val profile: dev.xross.launcher.profile.ProfileState = dev.xross.launcher.profile.ProfileState(),
    /** The User Card while it is open. */
    val profileCard: ProfileCardState? = null,
    /** The status bar avatar has the focus (Up from the top of a category). */
    val avatarFocused: Boolean = false,
    /** Current settings (the wave uniforms and the Wave settings panel read them). */
    val settings: dev.xross.launcher.data.XrossSettings = dev.xross.launcher.data.XrossSettings(),
    /** Settings ▸ Theme ▸ Wave settings while it is open. */
    val waveEditor: WaveEditorState? = null,
    /** A game's Achievements screen while it is open. */
    val achievements: AchievementsViewState? = null,
    /** The unlock toast on screen (it never takes input). */
    val unlockToast: UnlockToastState? = null,
    /** Settings ▸ Theme ▸ Profile customization while it is open. */
    val avatarEditor: AvatarEditorState? = null,
    /** Games ▸ RetroAchievements while it is open. */
    val raHub: RaHubState? = null,
    /** RetroAchievements numbers of the focused game, for the badge in the bottom corner. */
    val telemetry: GameTelemetry? = null,
    /** Game id → platform id of the library (the theme's system overrides and tile aspects). */
    val gamePlatforms: Map<Long, String> = emptyMap(),
) {
    val focused: XmbNode? get() = items.getOrNull(focusedIndex)

    /** Platform id of the focused game, or null when the focus isn't on a game. */
    val focusedSystem: String? get() = (focused?.action as? NodeAction.LaunchGame)?.gameId?.let(gamePlatforms::get)

    /** Id of the open category ("games"). */
    val categoryId: String? get() = categories.getOrNull(categoryIndex)?.id

    /** [telemetry] when it belongs to the focused row (it trails the cursor by a moment). */
    val focusedTelemetry: GameTelemetry?
        get() = telemetry?.takeIf { it.gameId == (focused?.action as? NodeAction.LaunchGame)?.gameId }
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
    val profileCard: ProfileCardState? = null,
    val avatarFocused: Boolean = false,
    val waveEditor: WaveEditorState? = null,
    val achievements: AchievementsViewState? = null,
    val unlockToast: UnlockToastState? = null,
    val avatarEditor: AvatarEditorState? = null,
    val raHub: RaHubState? = null,
    val telemetry: GameTelemetry? = null,
)

class XmbViewModel(
    private val context: Context,
    private val container: AppContainer,
    private val coldBoot: Boolean = false,
    /** Whether shared storage can be read; a seam so tests don't depend on platform app-ops. */
    private val storageAccess: (Context) -> Boolean = ::hasStorageAccess,
) : ViewModel(), InputSink {

    private val sounds = container.sounds
    private val audio = container.audio

    /** Hot reload: the active folder pack's files changed; a broken edit keeps the last good theme. */
    private val themeWatcher = dev.xross.launcher.theme.pack.ThemeWatcher(viewModelScope) {
        container.themes.hotReload()?.let { toast("Theme not reloaded: $it") }
    }
    private val music = container.music

    /** Battery level for the battery tint and `battery` context rules (Theme 2.0, 2b.3). */
    private val batteryMonitor = BatteryMonitor(context).also { it.start() }
    val battery: StateFlow<dev.xross.launcher.theme.BatteryLevel?> get() = batteryMonitor.level

    /** For tests: a battery reading, as the system would broadcast it. */
    internal fun reportBattery(level: dev.xross.launcher.theme.BatteryLevel?) = batteryMonitor.report(level)

    val media = MediaOverlayController(
        context = context,
        scope = viewModelScope,
        music = music,
        settings = container.settings,
        sounds = container.sounds,
        photoOptions = { photo -> openPhotoOptions(photo) },
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
        combine(env, meta, container.credentials.accounts, container.screenScraper.quota, container.profiles.state) { e, m, accounts, quota, profile ->
            e.copy(
                accounts = AccountsSetup(
                    retroAchievementsUser = accounts.retroAchievementsUser,
                    retroAchievementsLinked = accounts.retroAchievementsLinked,
                    retroAchievementsPoints = profile.profile?.totalPoints,
                    retroAchievementsRank = profile.profile?.rank,
                ),
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

    private data class Settled(
        val transient: Transient,
        val settings: dev.xross.launcher.data.XrossSettings,
        val overlay: MediaOverlay?,
        val profile: dev.xross.launcher.profile.ProfileState,
    )

    private val settled = combine(transient, settingsStore.settings, media.overlay, container.profiles.state) { tr, st, ov, pr ->
        Settled(tr, st, ov, pr)
    }

    val state: StateFlow<XmbUiState> = combine(tree, nav, settled, live) { t, n, sd, lv ->
        val tr = sd.transient
        val settings = sd.settings
        val view = resolve(t, n, lv)
        XmbUiState(
            categories = categories,
            categoryIndex = n.categoryIndex,
            breadcrumb = view.breadcrumb,
            folderGlyph = view.folderGlyph,
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
            profile = sd.profile,
            profileCard = tr.profileCard,
            avatarFocused = tr.avatarFocused,
            settings = settings,
            waveEditor = tr.waveEditor,
            achievements = tr.achievements,
            unlockToast = tr.unlockToast,
            avatarEditor = tr.avatarEditor,
            raHub = tr.raHub,
            telemetry = tr.telemetry,
            gamePlatforms = t.platformByGame,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, XmbUiState(coldBoot = coldBoot))

    private data class ListView(
        val breadcrumb: List<String>,
        val folderGlyph: GlyphKey?,
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
        fun itemsOf(key: String) = when (key) {
            MenuTree.LIBRARY -> t.library(lv.fingerprints)
            MenuTree.METADATA -> t.metadataSettings(lv.scrape)
            Categories.rootKey(Categories.MUSIC) -> t.musicRoot(lv.nowPlaying)
            else -> t.list(key)
        }
        val items = itemsOf(stack.last().key)
        // The row the open list came from, for the XMB folder column drill-down style.
        val folder = stack.getOrNull(stack.size - 2)?.let { parent ->
            when (val icon = itemsOf(parent.key).getOrNull(parent.index)?.icon) {
                is NodeIcon.Glyph -> icon.key
                is NodeIcon.Art -> icon.fallback
                is NodeIcon.Tile -> icon.fallback
                is NodeIcon.Thumb -> icon.fallback
                is NodeIcon.App, null -> GlyphKey.FOLDER
            }
        }
        return ListView(
            breadcrumb = stack.drop(1).map { it.title },
            folderGlyph = folder,
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
        themeWatcher.watch(null)
        batteryMonitor.stop()
        media.close()
        super.onCleared()
    }

    // region Input

    override fun onAction(action: NavAction) {
        if (transient.value.remap != null || transient.value.booting != null) return
        transient.value.nameEntry?.let { return nameEntryAction(it, action) }
        if (action == NavAction.MEDIA) return toggleMediaControls()
        transient.value.waveEditor?.let { return waveEditorAction(it, action) }
        transient.value.avatarEditor?.let { return avatarEditorAction(it, action) }
        transient.value.achievements?.let { return achievementsAction(it, action) }
        transient.value.profileCard?.let { return profileCardAction(it, action) }
        transient.value.raHub?.let { return raHubAction(it, action) }
        transient.value.info?.let { return infoAction(it, action) }
        transient.value.details?.let { return detailsAction(it, action) }
        // Before the media screens: the photo viewer's Options menu opens over it.
        transient.value.optionsMenu?.let { return optionsAction(it, action) }
        if (media.onAction(action)) return
        transient.value.search?.let { return searchAction(it, action) }
        if (transient.value.avatarFocused) return avatarAction(action)
        val ui = current()
        when (action) {
            // Up from the top of a category root climbs into the status bar: the avatar opens its mini-HUD.
            NavAction.UP -> if (ui.depth == 0 && ui.focusedIndex == 0) focusAvatar() else moveCursor(-1)
            NavAction.DOWN -> moveCursor(+1)
            NavAction.LEFT -> if (ui.depth > 0) back() else moveCategory(-1)
            NavAction.RIGHT -> when {
                ui.depth == 0 -> moveCategory(+1)
                ui.focused?.action is NodeAction.OpenList -> activate(ui.focused!!)
                ui.focused?.action is NodeAction.LaunchGame -> showDetails((ui.focused!!.action as NodeAction.LaunchGame).gameId)
            }
            NavAction.PREV_CATEGORY -> moveCategory(-1)
            NavAction.NEXT_CATEGORY -> moveCategory(+1)
            NavAction.CONFIRM, NavAction.START -> ui.focused?.let(::activate) ?: refuse()
            NavAction.BACK -> back()
            NavAction.OPTIONS -> ui.focused?.let(::openOptions) ?: refuse()
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
                achievementsJob?.cancel()
                transient.update {
                    it.copy(
                        optionsMenu = null, info = null, details = null, profileCard = null, avatarFocused = false,
                        waveEditor = null, achievements = null, avatarEditor = null, raHub = null,
                    )
                }
            }
            MediaOverlayController.NowPlayingToggle.CLOSED -> sounds.play(UiSound.BACK)
            MediaOverlayController.NowPlayingToggle.NOTHING_PLAYING -> toast(NOTHING_PLAYING)
            MediaOverlayController.NowPlayingToggle.UNAVAILABLE -> Unit
        }
    }

    /** Touch: the floating mini player opens the full audio controls. */
    fun onMiniPlayerTapped() = toggleMediaControls()

    /** Touch: a long press on the mini player plays or pauses without opening anything. */
    fun onMiniPlayerLongPressed() {
        if (music.state.value.song == null) return
        music.togglePlayPause()
        sounds.play(UiSound.ENTER)
    }

    // region Profile (status bar avatar, User Card, RetroAchievements)

    private val profiles = container.profiles
    private var profileJob: Job? = null

    /** Expands the avatar into its mini-HUD (the avatar "has the focus" while the HUD is open). */
    private fun focusAvatar() {
        sounds.play(UiSound.MOVE)
        transient.update { it.copy(avatarFocused = true) }
        if (profiles.current.linked && profiles.isStale()) refreshProfile(quiet = true)
    }

    /** Collapses the mini-HUD back into the avatar (Back, Down, or a tap outside it). */
    fun closeAvatarHud() {
        if (transient.value.avatarFocused) {
            sounds.play(UiSound.MOVE)
            transient.update { it.copy(avatarFocused = false) }
        }
    }

    private fun avatarAction(action: NavAction) {
        when (action) {
            // The HUD stays in place: Confirm refreshes it, or starts linking when there is no account.
            NavAction.CONFIRM, NavAction.START -> {
                sounds.play(UiSound.ENTER)
                if (profiles.current.linked) {
                    refreshProfile(quiet = false)
                } else {
                    transient.update { it.copy(avatarFocused = false) }
                    promptRetroAchievements()
                }
            }
            NavAction.DOWN, NavAction.BACK -> closeAvatarHud()
            NavAction.LEFT, NavAction.RIGHT, NavAction.PREV_CATEGORY, NavAction.NEXT_CATEGORY -> {
                transient.update { it.copy(avatarFocused = false) }
                moveCategory(if (action == NavAction.LEFT || action == NavAction.PREV_CATEGORY) -1 else +1)
            }
            // Account management (unlink, link another) lives on the full User Card.
            NavAction.OPTIONS -> openProfileCard()
            NavAction.UP, NavAction.MEDIA -> Unit
        }
    }

    /** Touch: tap the avatar to expand its mini-HUD, tap it again to collapse. */
    fun onAvatarTapped() {
        if (transient.value.avatarFocused) closeAvatarHud() else focusAvatar()
    }

    private fun openProfileCard() {
        sounds.play(UiSound.OPTION)
        transient.update { it.copy(profileCard = ProfileCardState(), avatarFocused = false, optionsMenu = null) }
    }

    fun closeProfileCard() {
        if (transient.value.profileCard != null) {
            sounds.play(UiSound.BACK)
            transient.update { it.copy(profileCard = null) }
        }
    }

    private fun profileCardAction(card: ProfileCardState, action: NavAction) =
        applyProfileCard(card, ProfileCard.onAction(card, action, profiles.current.linked))

    /** Touch: tap a User Card button. */
    fun onProfileCardButtonTapped(index: Int) {
        val card = transient.value.profileCard ?: return
        applyProfileCard(card, ProfileCard.tap(card, index, profiles.current.linked))
    }

    private fun applyProfileCard(before: ProfileCardState, outcome: ProfileCardOutcome) {
        if (outcome.state != before) {
            sounds.play(UiSound.MOVE)
            transient.update { it.copy(profileCard = outcome.state) }
        }
        when (val effect = outcome.effect) {
            ProfileCardEffect.None -> Unit
            ProfileCardEffect.Close -> closeProfileCard()
            is ProfileCardEffect.Press -> {
                sounds.play(UiSound.ENTER)
                when (effect.button) {
                    ProfileCardButton.REFRESH -> refreshProfile(quiet = false)
                    ProfileCardButton.LINK -> {
                        transient.update { it.copy(profileCard = null) }
                        promptRetroAchievements()
                    }
                    ProfileCardButton.UNLINK -> unlinkRetroAchievements()
                    ProfileCardButton.CLOSE -> closeProfileCard()
                }
            }
        }
    }

    private fun promptRetroAchievements() {
        val account = container.credentials.current
        emit(UiEffect.PromptRetroAchievements(account.retroAchievementsUser, account.retroAchievementsLinked))
    }

    /**
     * Answer from the link dialog. An empty [key] keeps the saved key when the user name is
     * unchanged; an empty [user] unlinks.
     */
    fun onRetroAchievementsEntered(user: String, key: String) {
        val name = user.trim()
        val saved = container.credentials.current
        if (name.isEmpty()) {
            if (saved.retroAchievementsLinked) unlinkRetroAchievements()
            return
        }
        val apiKey = key.trim().ifEmpty {
            if (name.equals(saved.retroAchievementsUser, ignoreCase = true)) saved.retroAchievementsKey else ""
        }
        if (apiKey.isEmpty()) return toast("Enter your Web API key (retroachievements.org ▸ Settings ▸ Keys)")
        profileJob?.cancel()
        profileJob = viewModelScope.launch {
            toast("Checking RetroAchievements account…")
            when (val result = profiles.link(name, apiKey)) {
                is RaResult.Ok -> toast("Linked RetroAchievements as ${result.profile.username}")
                else -> fail(raFailure(result))
            }
        }
    }

    private fun refreshProfile(quiet: Boolean) {
        if (!profiles.current.linked) return
        if (profileJob?.isActive == true) return
        profileJob = viewModelScope.launch {
            val result = profiles.refresh()
            if (!quiet) toast(if (result is RaResult.Ok) "Profile updated" else raFailure(result))
        }
    }

    private fun unlinkRetroAchievements() {
        profileJob?.cancel()
        profileJob = viewModelScope.launch {
            profiles.unlink()
            withContext(Dispatchers.IO) { container.achievements.clear() }
            transient.update { it.copy(profileCard = it.profileCard?.let { ProfileCardState() }) }
            toast("RetroAchievements account unlinked")
        }
    }

    private fun raFailure(result: RaResult): String = when (result) {
        is RaResult.Ok -> ""
        RaResult.InvalidCredentials -> "RetroAchievements refused that Web API key"
        RaResult.UserNotFound -> "No RetroAchievements user by that name"
        is RaResult.Failed -> result.reason
    }

    // endregion

    // region Unlock toasts

    private val unlockQueue = UnlockQueue()
    private var unlockJob: Job? = null

    /** Shows the next queued unlock unless one is on screen; each stays [UNLOCK_TOAST_MS]. */
    private fun showNextUnlock() {
        if (unlockJob?.isActive == true) return
        val next = unlockQueue.next() ?: return
        unlockJob = viewModelScope.launch {
            sounds.play(UiSound.TROPHY)
            transient.update { it.copy(unlockToast = next) }
            delay(UNLOCK_TOAST_MS)
            transient.update { it.copy(unlockToast = null) }
            delay(UNLOCK_TOAST_GAP_MS)
            unlockJob = null
            showNextUnlock()
        }
    }

    // endregion

    // region Theme packs and Wave settings

    private fun setQuickProfile(profile: dev.xross.launcher.theme.QuickProfile) {
        settingsStore.update { it.copy(quickProfile = profile.name) }
        toast("Quick profile: ${profile.label}")
    }

    /** Why a color switch has no effect right now (the quick profile turns it off), if it doesn't. */
    private fun colorSwitchBlockedNote(which: ColorSwitch): String? {
        val profile = dev.xross.launcher.theme.QuickProfile.byName(settingsStore.current.quickProfile)
        val blocked = if (which == ColorSwitch.ADAPTIVE) !profile.allowsArt else !profile.allowsAmbient
        return if (blocked) "Saved; ${profile.label} keeps it off" else null
    }

    private fun applyThemePack(id: String) {
        viewModelScope.launch {
            val loaded = container.themes.select(id)
            settingsStore.update { s ->
                // Another pack starts on its base look; the same pack keeps its variant.
                var next = s.copy(themePack = loaded.id, themeVariant = if (loaded.id == s.themePack) s.themeVariant else "")
                next = withPackColors(next, loaded.look(next.themeVariant).palette != null)
                // Its wave defaults, keeping the user's color tint.
                loaded.wave?.let { w -> next = WaveEditor.reset(next, w).copy(waveTint = s.waveTint, waveEnabled = s.waveEnabled) }
                next
            }
            sounds.useTheme(loaded)
            if (loaded.id != id) {
                fail("Couldn't load that theme pack; using Default")
            } else {
                sounds.play(UiSound.OK)
                toast("Theme pack: ${loaded.info.name}" + (loaded.problems.firstOrNull()?.let { " · $it" } ?: ""))
            }
        }
    }

    /** A pack look with its own colors switches to them; one without falls back to a fixed theme. */
    private fun withPackColors(s: dev.xross.launcher.data.XrossSettings, hasPalette: Boolean) = when {
        hasPalette -> s.copy(theme = dev.xross.launcher.theme.PACK_THEME)
        s.theme == dev.xross.launcher.theme.PACK_THEME -> s.copy(theme = XrossTheme.LAVENDER.name)
        else -> s
    }

    private fun setThemeVariant(id: String) {
        val pack = container.themes.active.value
        val variant = pack.variant(id)
        settingsStore.update { withPackColors(it.copy(themeVariant = variant?.id.orEmpty()), pack.look(variant?.id.orEmpty()).palette != null) }
        sounds.play(UiSound.OK)
        toast("Theme variant: ${variant?.name ?: "Standard"}")
    }

    // region .xtheme import

    private val themeArchive by lazy {
        dev.xross.launcher.theme.pack.ThemeArchive(
            themesDir = { container.environment.current.themes },
            check = { dir -> container.themePacks.inspect(dir) },
        )
    }

    /** A checked import waiting for "Replace" / "Keep both" because its id is installed. */
    private var pendingImport: dev.xross.launcher.theme.pack.StagedTheme? = null

    /** The picked `.xtheme`: copied to the cache, checked, unpacked, and installed or held for a prompt. */
    /**
     * Settings ▸ Theme ▸ Xross wallpaper ▸ Pick a picture: keeps read access to [uri] across
     * restarts (a document the picker granted), saves it as the Xross wallpaper and lets go of the
     * last picked one.
     */
    fun onWallpaperPicked(uri: Uri?) {
        uri ?: return
        val resolver = context.contentResolver
        val kept = runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }.isSuccess
        val old = settingsStore.current.customWallpaper
        settingsStore.update { it.copy(customWallpaper = uri.toString()) }
        if (old != uri.toString()) releaseWallpaperGrant(old)
        toast(if (kept) "Xross wallpaper set" else "Xross wallpaper set (until Xross restarts: the picker gave no lasting access)")
    }

    /** Lets go of a picked wallpaper's lasting read grant once nothing uses it (Photo's MediaStore URIs hold none). */
    private fun releaseWallpaperGrant(location: String) {
        if (!location.startsWith("content://")) return
        val resolver = context.contentResolver
        val uri = location.toUri()
        if (resolver.persistedUriPermissions.none { it.uri == uri }) return
        runCatching { resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    /** Settings ▸ Theme (2b.4f): [slot] set to [value] over the pack, with a note where it can't show. */
    private fun setEffect(slot: dev.xross.launcher.theme.pack.EffectSlot, value: String) {
        settingsStore.update { it.copy(effectOverrides = it.effectOverrides.with(slot, value)) }
        val v = settingsStore.current.effectOverrides[slot]
        val note = when {
            slot == dev.xross.launcher.theme.pack.EffectSlot.UI_FILTER && v.isNotEmpty() && v != "NONE" &&
                !dev.xross.launcher.ui.UiFilterSupport.supported() -> "UI filters need Android 13 or later"
            slot == dev.xross.launcher.theme.pack.EffectSlot.SHADER && !settingsStore.current.waveEnabled -> "Turn Wave background on to see it"
            else -> null
        }
        note?.let { toast(it) }
    }

    fun onThemeArchivePicked(uri: Uri?) {
        uri ?: return
        viewModelScope.launch {
            pendingImport?.let { old -> withContext(Dispatchers.IO) { themeArchive.discard(old) } }
            pendingImport = null
            toast("Checking theme file…")
            val result = withContext(Dispatchers.IO) { stageThemeArchive(uri) }
            when (result) {
                is dev.xross.launcher.theme.pack.ArchiveResult.Rejected -> fail("Theme not imported: ${result.reason}")
                is dev.xross.launcher.theme.pack.ArchiveResult.Staged -> {
                    val staged = result.theme
                    if (!staged.exists) return@launch installTheme(staged, replace = false)
                    pendingImport = staged
                    sounds.play(UiSound.OPTION)
                    val options = listOf(
                        MenuOption("Replace ${staged.id}", detail = "Overwrites the installed pack's files", command = Command.ReplaceImportedTheme),
                        MenuOption("Keep both", detail = "Installs it as ${themeArchive.asCopy(staged).id}", command = Command.KeepBothImportedThemes),
                    )
                    transient.update { it.copy(optionsMenu = OptionsMenuState.open("${staged.name} is already installed", options)) }
                }
            }
        }
    }

    private fun stageThemeArchive(uri: Uri): dev.xross.launcher.theme.pack.ArchiveResult {
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "theme.xtheme"
        val copy = File(context.cacheDir, "import.xtheme")
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: return dev.xross.launcher.theme.pack.ArchiveResult.Rejected("Couldn't open $name")
            // The compressed file can't be meaningfully bigger than the 50 MB it may unpack to.
            val cap = dev.xross.launcher.theme.pack.ThemeArchive.MAX_BYTES + dev.xross.launcher.theme.pack.ThemeArchive.MB
            var total = 0L
            input.use { inp ->
                copy.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > cap) return dev.xross.launcher.theme.pack.ArchiveResult.Rejected("$name is larger than 50 MB")
                        out.write(buf, 0, n)
                    }
                }
            }
            return themeArchive.stage(copy, name)
        } catch (e: java.io.IOException) {
            return dev.xross.launcher.theme.pack.ArchiveResult.Rejected("Couldn't read $name")
        } catch (e: SecurityException) {
            return dev.xross.launcher.theme.pack.ArchiveResult.Rejected("No permission to read $name")
        } finally {
            copy.delete()
        }
    }

    private fun resolvePendingImport(keepBoth: Boolean) {
        val staged = pendingImport ?: return toast("Pick the theme file again")
        pendingImport = null
        viewModelScope.launch { installTheme(if (keepBoth) themeArchive.asCopy(staged) else staged, replace = !keepBoth) }
    }

    /** Installs [staged], rescans the packs and switches to it. */
    private suspend fun installTheme(staged: dev.xross.launcher.theme.pack.StagedTheme, replace: Boolean) {
        when (val r = withContext(Dispatchers.IO) { themeArchive.install(staged, replace) }) {
            is dev.xross.launcher.theme.pack.InstallResult.Failed -> {
                withContext(Dispatchers.IO) { themeArchive.discard(staged) }
                fail("Theme not imported: ${r.reason}")
            }
            is dev.xross.launcher.theme.pack.InstallResult.Installed -> {
                container.themes.rediscover()
                // Replacing the pack in use: read its new files even though the id is the same.
                if (container.themes.active.value.id == r.id) container.themes.reload()
                applyThemePack(r.id)
            }
        }
    }

    // endregion

    private fun openWaveSettings() {
        transient.update { it.copy(waveEditor = WaveEditorState(), optionsMenu = null) }
    }

    private fun waveEditorAction(editor: WaveEditorState, action: NavAction) {
        val outcome = WaveEditor.onAction(editor, action, settingsStore.current, container.themes.active.value.wave)
        applyWaveOutcome(editor, outcome)
    }

    private fun applyWaveOutcome(before: WaveEditorState, outcome: WaveEditorOutcome) {
        when {
            outcome.close -> return closeWaveSettings()
            outcome.blocked -> refuse()
            outcome.settings != null -> {
                settingsStore.update { outcome.settings }
                sounds.play(UiSound.MOVE)
            }
            outcome.state != before -> sounds.play(UiSound.MOVE)
        }
        if (outcome.state != before) transient.update { it.copy(waveEditor = outcome.state) }
    }

    fun closeWaveSettings() {
        if (transient.value.waveEditor != null) {
            sounds.play(UiSound.BACK)
            transient.update { it.copy(waveEditor = null) }
        }
    }

    /** Touch: tap a row to focus it; tapping the focused row acts like Confirm. */
    fun onWaveRowTapped(index: Int) {
        val editor = transient.value.waveEditor ?: return
        if (index == editor.index) {
            waveEditorAction(editor, NavAction.CONFIRM)
        } else if (index in WaveEditor.rows.indices) {
            sounds.play(UiSound.MOVE)
            transient.update { it.copy(waveEditor = editor.copy(index = index)) }
        }
    }

    /** Touch: a slider dragged or tapped at [fraction] of its track. */
    fun onWaveSliderChanged(index: Int, fraction: Float) {
        val editor = transient.value.waveEditor ?: return
        val row = WaveEditor.rows.getOrNull(index) ?: return
        if (editor.index != index) transient.update { it.copy(waveEditor = editor.copy(index = index)) }
        settingsStore.update { WaveEditor.setFraction(row, it, fraction) }
    }

    // endregion

    // region Profile customization (avatar ring and glow)

    private fun openAvatarStyle() {
        transient.update { it.copy(avatarEditor = AvatarEditorState(), optionsMenu = null) }
    }

    private fun avatarEditorAction(editor: AvatarEditorState, action: NavAction) =
        applyAvatarOutcome(editor, AvatarEditor.onAction(editor, action, settingsStore.current.avatarStyle))

    private fun applyAvatarOutcome(before: AvatarEditorState, outcome: AvatarEditorOutcome) {
        when {
            outcome.close -> return closeAvatarStyle()
            outcome.promptColor -> {
                sounds.play(UiSound.ENTER)
                emit(UiEffect.PromptText(TextPrompt.AvatarBorderColor(settingsStore.current.avatarStyle.borderColorHex)))
            }
            outcome.blocked -> refuse()
            outcome.style != null -> {
                settingsStore.update { AvatarEditor.apply(it, outcome.style) }
                sounds.play(UiSound.MOVE)
            }
            outcome.state != before -> sounds.play(UiSound.MOVE)
        }
        if (outcome.state != before) transient.update { it.copy(avatarEditor = outcome.state) }
    }

    fun closeAvatarStyle() {
        if (transient.value.avatarEditor != null) {
            sounds.play(UiSound.BACK)
            transient.update { it.copy(avatarEditor = null) }
        }
    }

    /** Touch: tap a row to focus it; tapping the focused row acts like Confirm. */
    fun onAvatarRowTapped(index: Int) {
        val editor = transient.value.avatarEditor ?: return
        if (index == editor.index) {
            avatarEditorAction(editor, NavAction.CONFIRM)
        } else if (index in AvatarEditor.rows.indices) {
            sounds.play(UiSound.MOVE)
            transient.update { it.copy(avatarEditor = editor.copy(index = index)) }
        }
    }

    /** Touch: a slider dragged or tapped at [fraction] of its track. */
    fun onAvatarSliderChanged(index: Int, fraction: Float) {
        val editor = transient.value.avatarEditor ?: return
        val row = AvatarEditor.rows.getOrNull(index) ?: return
        if (editor.index != index) transient.update { it.copy(avatarEditor = editor.copy(index = index)) }
        settingsStore.update { AvatarEditor.apply(it, AvatarEditor.setFraction(row, it.avatarStyle, fraction)) }
    }

    // endregion

    // region RetroAchievements dashboard (Games ▸ RetroAchievements)

    private var raHubJob: Job? = null

    private fun openRaHub() {
        transient.update { it.copy(raHub = RaHubState(artVersion = container.assets.version.value), optionsMenu = null) }
        loadRaSummary()
        if (profiles.current.linked && profiles.isStale()) refreshProfile(quiet = true)
    }

    /** Library-wide progress from what is cached in the Xross folder; never the network. */
    private fun loadRaSummary() {
        raHubJob?.cancel()
        raHubJob = viewModelScope.launch {
            val summary = container.achievements.librarySummary(container.games.games.first())
            transient.update { t -> t.copy(raHub = t.raHub?.copy(summary = summary)) }
        }
    }

    private fun raHubAction(hub: RaHubState, action: NavAction) {
        val outcome = RaHub.onAction(hub, action)
        when (outcome.effect) {
            RaHubEffect.Close -> return closeRaHub()
            RaHubEffect.Primary -> {
                sounds.play(UiSound.ENTER)
                if (profiles.current.linked) {
                    refreshProfile(quiet = false)
                    loadRaSummary()
                } else {
                    transient.update { it.copy(raHub = null) }
                    promptRetroAchievements()
                }
                return
            }
            RaHubEffect.None -> Unit
        }
        if (outcome.state != hub) {
            sounds.play(UiSound.MOVE)
            transient.update { it.copy(raHub = outcome.state) }
        }
    }

    fun closeRaHub() {
        raHubJob?.cancel()
        if (transient.value.raHub != null) {
            sounds.play(UiSound.BACK)
            transient.update { it.copy(raHub = null) }
        }
    }

    // endregion

    // region Achievements screen

    private var achievementsJob: Job? = null

    private fun showAchievements(gameId: Long) {
        achievementsJob?.cancel()
        achievementsJob = viewModelScope.launch {
            val game = container.games.get(gameId) ?: return@launch
            val platform = PlatformRegistry.byId(game.platformId)
            val view = AchievementsViewState(
                gameId = game.id,
                uuid = game.uuid,
                title = game.title,
                platform = platform?.name,
                result = container.achievements.cached(game),
                artVersion = container.assets.version.value,
            )
            sounds.play(UiSound.ENTER)
            transient.update { it.copy(achievements = view, details = null, optionsMenu = null) }
            val result = container.achievements.load(game)
            transient.update { t -> t.copy(achievements = t.achievements?.takeIf { it.gameId == gameId }?.withResult(result)) }
        }
    }

    private fun refreshAchievements(gameId: Long, quiet: Boolean) {
        if (achievementsJob?.isActive == true) return
        achievementsJob = viewModelScope.launch {
            val game = container.games.get(gameId) ?: return@launch
            if (!quiet) toast("Refreshing achievements…")
            val result = container.achievements.load(game, maxAgeMs = 0)
            transient.update { t -> t.copy(achievements = t.achievements?.takeIf { it.gameId == gameId }?.withResult(result)) }
            if (!quiet && result is dev.xross.launcher.profile.AchievementsState.Failed) fail(result.reason)
        }
    }

    private fun achievementsAction(view: AchievementsViewState, action: NavAction) {
        val outcome = AchievementsGrid.onAction(view, action)
        when (outcome.effect) {
            AchievementsEffect.Close -> return closeAchievements()
            AchievementsEffect.Refresh -> {
                sounds.play(UiSound.OPTION)
                return refreshAchievements(view.gameId, quiet = false)
            }
            AchievementsEffect.None -> Unit
        }
        if (outcome.state != view) {
            sounds.play(UiSound.MOVE)
            transient.update { it.copy(achievements = outcome.state) }
        } else if (action == NavAction.CONFIRM || action == NavAction.START) {
            // Nothing to run on a badge; its details are already shown.
            Unit
        } else if (view.achievements.isNotEmpty() && action in GRID_MOVES) {
            refuse()
        }
    }

    fun closeAchievements() {
        achievementsJob?.cancel()
        if (transient.value.achievements != null) {
            sounds.play(UiSound.BACK)
            transient.update { it.copy(achievements = null) }
        }
    }

    /** Touch: tap a badge to show its details. */
    fun onAchievementTapped(index: Int) {
        val view = transient.value.achievements ?: return
        val next = AchievementsGrid.tap(view, index)
        if (next != view) {
            sounds.play(UiSound.MOVE)
            transient.update { it.copy(achievements = next) }
        }
    }

    // endregion

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

    /** The photo viewer's Options: its wallpaper choices, over the viewer. */
    private fun openPhotoOptions(photo: dev.xross.launcher.media.Photo) {
        val options = MediaMenus.photoOptions(photo.id, customWallpaper = settingsStore.current.customWallpaper.isNotBlank())
        transient.update { it.copy(optionsMenu = OptionsMenuState.open(photo.name.substringBeforeLast('.'), options)) }
    }

    private fun openOptions(node: XmbNode) {
        if (node.menu.isEmpty()) return refuse()
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
            is TextPrompt.AvatarBorderColor -> {
                if (value.isEmpty()) return
                val color = dev.xross.launcher.theme.AvatarStyleLimits.normalizeColor(value)
                    ?: return fail("That isn't a hex color — use #RRGGBB, like #FFB5C5")
                settingsStore.update { AvatarEditor.apply(it, it.avatarStyle.copy(borderColorHex = color)) }
                toast("Avatar ring color: $color")
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

    /** Game ids of the list Details pages through: the search results while searching, else the open list. */
    private fun detailsSiblings(): List<Long> {
        val rows = transient.value.search?.results ?: current().items
        return rows.mapNotNull { (it.action as? NodeAction.LaunchGame)?.gameId }
    }

    /** Opens Details for [gameId]; [direction] is ±1 when paging from a neighbour (it picks the slide). */
    private fun showDetails(gameId: Long, direction: Int = 0) {
        detailsJob?.cancel()
        val (position, count) = DetailsPager.position(detailsSiblings(), gameId)
        detailsJob = viewModelScope.launch {
            val details = withContext(Dispatchers.IO) {
                val game = container.games.get(gameId) ?: return@withContext null
                GameDetailsState.build(
                    game,
                    PlatformRegistry.byId(game.platformId),
                    container.database.metadata().get(gameId),
                    container.assets.version.value,
                )
            }?.copy(position = position, count = count, pageDirection = direction) ?: return@launch
            sounds.play(if (direction == 0) UiSound.ENTER else UiSound.MOVE)
            transient.update { it.copy(details = details) }
            if (details.achievementsSupported) {
                val game = container.games.get(gameId) ?: return@launch
                val result = container.achievements.load(game)
                transient.update { t -> t.copy(details = t.details?.takeIf { it.gameId == gameId }?.copy(achievements = result)) }
            }
        }
    }

    /** L1/R1 or Left/Right on Details: the previous or next game of the list, without leaving the screen. */
    private fun pageDetails(details: GameDetailsState, delta: Int) {
        val next = DetailsPager.neighbor(detailsSiblings(), details.gameId, delta) ?: return refuse()
        // The list cursor follows, so Back lands on the game that was on screen.
        val search = transient.value.search
        if (search != null) {
            val i = search.results.indexOfFirst { (it.action as? NodeAction.LaunchGame)?.gameId == next }
            if (i >= 0) transient.update { it.copy(search = it.search?.copy(resultIndex = i)) }
        } else {
            val ui = current()
            val i = ui.items.indexOfFirst { (it.action as? NodeAction.LaunchGame)?.gameId == next }
            if (i >= 0 && i != ui.focusedIndex) {
                val category = categories[nav.value.categoryIndex]
                nav.value = nav.value.moveCursor(category.id, category.title, i - ui.focusedIndex, ui.items.size)
            }
        }
        showDetails(next, direction = delta)
    }

    /** Touch: the achievements summary on Details. */
    fun onDetailsAchievementsTapped() {
        val details = transient.value.details ?: return
        if (details.achievementsSupported) showAchievements(details.gameId)
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
            NavAction.BACK -> closeDetails()
            NavAction.OPTIONS -> {
                closeDetails()
                (transient.value.search?.results ?: current().items)
                    .firstOrNull { (it.action as? NodeAction.LaunchGame)?.gameId == details.gameId }?.let(::openOptions)
            }
            NavAction.LEFT, NavAction.PREV_CATEGORY -> pageDetails(details, -1)
            NavAction.RIGHT, NavAction.NEXT_CATEGORY -> pageDetails(details, +1)
            NavAction.MEDIA -> Unit
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
            fail("Grant All files access first")
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
            if (nav.value.stack(category.id, category.title).size > 1) sounds.play(UiSound.DRILL_OUT)
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
        audio.onReturnFromGame()
        recordFinishedSession()
        viewModelScope.launch { container.media.refresh() }
        if (volumesChangedInBackground) {
            volumesChangedInBackground = false
            refreshFoldersAndRescan(quiet = true)
        }
        if (env.value.storageGranted && scanJob?.isActive != true) fingerprints.start(viewModelScope)
        if (settingsStore.current.scrapeAutomatically && scanJob?.isActive != true) container.scraper.start(viewModelScope)
        if (profileLoaded && profiles.isStale()) refreshProfile(quiet = true)
        // Theme files edited while Xross was in the background (or events FUSE didn't deliver).
        viewModelScope.launch { container.themes.reloadIfChanged()?.let { toast("Theme not reloaded: $it") } }
    }

    /** The offline profile cache has been read (see init). */
    private var profileLoaded = false

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
            // Unlocks earned in the emulator: fetch them now so their toasts appear on return.
            container.achievements.invalidate(game)
            if (profiles.current.linked) {
                profileJob?.cancel()
                refreshProfile(quiet = true)
            }
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
        if (moved) {
            moveArtFrom(previous)
            profiles.load()
        }
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
            fail("Grant All files access first")
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
            // Leans toward the direction of travel (2b.5a).
            sounds.play(UiSound.CATEGORY, sounds.pan(travel = delta))
        }
    }

    private fun back() {
        val category = categories[nav.value.categoryIndex]
        nav.value.pop(category.id, category.title)?.let {
            nav.value = it
            sounds.play(UiSound.DRILL_OUT)
        }
    }

    private fun activate(node: XmbNode) {
        when (val action = node.action) {
            is NodeAction.OpenList -> {
                val category = categories[nav.value.categoryIndex]
                nav.update { it.push(category.id, category.title, Frame(action.key, node.title)) }
                sounds.play(UiSound.DRILL_IN)
            }
            is NodeAction.LaunchGame -> launchGame(action.gameId)
            is NodeAction.LaunchApp -> launchApp(action.packageName, action.activityName)
            is NodeAction.Run -> {
                sounds.play(UiSound.ENTER)
                run(action.command)
            }
            is NodeAction.OpenMedia -> openMedia(action)
            NodeAction.None -> refuse()
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

    /** Settings ▸ Sound: saves [channel]'s level; a playing song or snap follows through the settings flow. */
    private fun setVolume(channel: VolumeChannel, value: Float) {
        settingsStore.update { channel.set(it, value) }
        if (channel == VolumeChannel.EFFECTS) {
            audio.onSettingsChanged(settingsStore.current)
            sounds.play(UiSound.OK) // preview the new level
        }
    }

    private fun launchGame(gameId: Long) = viewModelScope.launch {
        val game = container.games.get(gameId) ?: return@launch
        when (val plan = container.launcher.plan(game, settingsStore.current.emulatorChoice[game.platformId])) {
            is LaunchPlan.Ready -> {
                container.games.markPlayed(game.id)
                // Start transition: bloom + start cue, then hand off to the emulator.
                transient.update { it.copy(booting = game.title) }
                // Music pauses (to resume on return, or not) and the start cue plays.
                audio.onGameLaunch()
                delay(GAMEBOOT_HANDOFF_MS)
                container.sessions.arm(game.id)
                emit(UiEffect.Launch(plan))
                delay(600)
                transient.update { it.copy(booting = null) }
            }
            is LaunchPlan.Failed -> fail(plan.message)
        }
    }

    /** The emulator couldn't be started after all (uninstalled meanwhile, refused the URI). */
    fun onLaunchFailed(message: String) {
        container.sessions.disarm()
        transient.update { it.copy(booting = null) }
        fail(message)
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
            Command.LinkRetroAchievements -> promptRetroAchievements()
            Command.RefreshRetroAchievements ->
                if (profiles.current.linked) refreshProfile(quiet = false) else promptRetroAchievements()
            Command.OpenProfileCard -> openProfileCard()
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
            is Command.ShowAchievements -> showAchievements(command.gameId)
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
            Command.OpenWaveSettings -> openWaveSettings()
            Command.OpenAvatarStyle -> openAvatarStyle()
            Command.OpenRaHub -> openRaHub()
            Command.CycleTheme -> settingsStore.update {
                // The theme pack's own colors join the cycle when the active pack has them.
                val names = XrossTheme.entries.map { t -> t.name } +
                    listOfNotNull(dev.xross.launcher.theme.PACK_THEME.takeIf { _ -> container.themes.active.value.look(it.themeVariant).palette != null })
                val i = names.indexOf(it.theme).coerceAtLeast(-1)
                it.copy(theme = names[(i + 1) % names.size])
            }
            is Command.SetTheme -> {
                val known = command.name == dev.xross.launcher.theme.PACK_THEME || XrossTheme.entries.any { it.name == command.name }
                if (known) settingsStore.update { it.copy(theme = command.name) }
            }
            Command.CycleThemePack -> {
                val packs = container.themes.packs.value
                if (packs.size <= 1) {
                    viewModelScope.launch {
                        val found = container.themes.rediscover()
                        if (found.size <= 1) fail("No theme packs in ${container.environment.current.themes.absolutePath}")
                        else applyThemePack(found[(found.indexOfFirst { it.id == settingsStore.current.themePack } + 1) % found.size].id)
                    }
                } else {
                    val i = packs.indexOfFirst { it.id == settingsStore.current.themePack }
                    applyThemePack(packs[(i + 1) % packs.size].id)
                }
            }
            is Command.SetThemePack -> applyThemePack(command.id)
            Command.ReloadThemePacks -> viewModelScope.launch {
                val found = container.themes.rediscover()
                container.themes.reload()
                val skipped = container.themes.rejected.value
                toast(
                    buildString {
                        append(if (found.size == 1) "No theme packs in Xross/themes" else "${found.size} theme packs")
                        if (skipped.isNotEmpty()) append(" · skipped ${skipped.size}: ${skipped.first().id} (${skipped.first().reason})")
                    },
                )
            }
            Command.CycleThemeVariant -> {
                val ids = listOf("") + container.themes.active.value.variants.map { it.id }
                val i = ids.indexOf(container.themes.active.value.variant(settingsStore.current.themeVariant)?.id.orEmpty())
                setThemeVariant(ids[(i + 1) % ids.size])
            }
            is Command.SetThemeVariant -> setThemeVariant(command.id)
            Command.ImportThemeArchive -> emit(UiEffect.PickThemeArchive)
            Command.ReplaceImportedTheme -> resolvePendingImport(keepBoth = false)
            Command.KeepBothImportedThemes -> resolvePendingImport(keepBoth = true)
            Command.CycleQuickProfile -> {
                val all = dev.xross.launcher.theme.QuickProfile.entries
                val next = all[(dev.xross.launcher.theme.QuickProfile.byName(settingsStore.current.quickProfile).ordinal + 1) % all.size]
                setQuickProfile(next)
            }
            is Command.SetQuickProfile -> setQuickProfile(dev.xross.launcher.theme.QuickProfile.byName(command.name))
            is Command.ToggleColorSwitch -> {
                val pack = container.themes.active.value.flags
                settingsStore.update {
                    when (command.which) {
                        ColorSwitch.ADAPTIVE -> it.copy(adaptiveGamePalette = !(it.adaptiveGamePalette ?: pack.adaptiveGamePalette ?: true))
                        ColorSwitch.CIRCADIAN -> it.copy(circadianCurve = !(it.circadianCurve ?: pack.circadianCurve ?: false))
                        ColorSwitch.BATTERY -> it.copy(batteryTint = !(it.batteryTint ?: pack.batteryTint ?: false))
                    }
                }
                colorSwitchBlockedNote(command.which)?.let { toast(it) }
            }
            is Command.SetColorSwitch -> {
                settingsStore.update {
                    when (command.which) {
                        ColorSwitch.ADAPTIVE -> it.copy(adaptiveGamePalette = command.on)
                        ColorSwitch.CIRCADIAN -> it.copy(circadianCurve = command.on)
                        ColorSwitch.BATTERY -> it.copy(batteryTint = command.on)
                    }
                }
                colorSwitchBlockedNote(command.which)?.let { toast(it) }
            }
            Command.CycleSurfaceStyle -> settingsStore.update {
                // "" (the pack's own) first, then every surface the active pack offers.
                val ids = listOf("") + container.themes.active.value.surfaces.keys
                val i = ids.indexOf(it.surfaceStyle).coerceAtLeast(0)
                it.copy(surfaceStyle = ids[(i + 1) % ids.size])
            }
            Command.ToggleSoundEffects -> settingsStore.update { it.copy(soundEffects = !it.soundEffects) }
            is Command.CycleVolume -> setVolume(command.channel, VolumeChannel.next(command.channel.of(settingsStore.current)))
            is Command.SetVolume -> setVolume(command.channel, command.value)
            Command.CycleMusicUnderSnaps -> settingsStore.update {
                it.copy(musicUnderSnaps = MusicUnderSnaps.entries[(it.musicUnderSnaps.ordinal + 1) % MusicUnderSnaps.entries.size])
            }
            is Command.SetMusicUnderSnaps -> settingsStore.update { it.copy(musicUnderSnaps = command.mode) }
            Command.ToggleBootSound -> settingsStore.update { it.copy(bootSound = !it.bootSound) }
            Command.ToggleGameBootSound -> settingsStore.update { it.copy(gameBootSound = !it.gameBootSound) }
            Command.ToggleLargeImageHashing -> {
                settingsStore.update { it.copy(hashLargeImages = !it.hashLargeImages) }
                if (foreground && scanJob?.isActive != true) fingerprints.restart(viewModelScope)
            }
            Command.RecheckFingerprints -> {
                if (!env.value.storageGranted) {
                    fail("Grant All files access first")
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
            is Command.SetXrossWallpaper -> container.media.library.value.photos.firstOrNull { it.id == command.photoId }?.let { photo ->
                settingsStore.update { it.copy(customWallpaper = photo.uri.toString()) }
                toast("Xross wallpaper set")
            }
            Command.ResetXrossWallpaper -> {
                val old = settingsStore.current.customWallpaper
                settingsStore.update { it.copy(customWallpaper = "") }
                releaseWallpaperGrant(old)
                toast(if (container.themes.active.value.hasWallpaper) "Using the theme's wallpaper" else "Xross wallpaper removed")
            }
            Command.PickXrossWallpaper -> emit(UiEffect.PickWallpaperImage)
            is Command.CycleEffect -> setEffect(command.slot, command.slot.next(settingsStore.current.effectOverrides[command.slot]))
            is Command.SetEffect -> setEffect(command.slot, command.value)
            Command.ResetThemeDefaults -> {
                val before = settingsStore.current
                val after = dev.xross.launcher.theme.pack.ThemeDefaults.reset(before)
                if (after == before) {
                    toast("Already the theme's own look")
                } else {
                    settingsStore.update { dev.xross.launcher.theme.pack.ThemeDefaults.reset(it) }
                    releaseWallpaperGrant(before.customWallpaper)
                    toast("Back to ${container.themes.active.value.info.name}'s own look")
                }
            }
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

    /** An action that isn't allowed here (Options on an item without a menu, Confirm on an information row). */
    private fun refuse() = sounds.play(UiSound.ERROR)

    /** Something failed: the error sound, then the message. */
    private fun fail(message: String) {
        sounds.play(UiSound.ERROR)
        toast(message)
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
            settingsStore.settings.collect(audio::onSettingsChanged)
        }
        // Keep open search results in step with the library (favorites, playtime, rescans).
        viewModelScope.launch {
            tree.collect { if (transient.value.search?.query?.isNotBlank() == true) runSearch(0) }
        }
        // Background shader failures (compile errors, the watchdog, a crash last time): one toast each.
        viewModelScope.launch { container.shaderHealth.notices.collect { toast(it) } }
        // Theme pack: discover, activate the saved one, and keep sounds and Settings ▸ Theme in step.
        viewModelScope.launch {
            container.themes.rediscover()
            val wanted = settingsStore.current.themePack
            if (wanted != container.themes.active.value.id) container.themes.select(wanted)
            // Startup sound once the pack's sounds are in place (01. opening.wav by default).
            if (coldBoot) audio.onColdBoot()
        }
        viewModelScope.launch {
            combine(container.themes.packs, container.themes.active, container.themes.rejected) { packs, active, rejected ->
                Triple(packs, active, rejected)
            }.collect { (packs, active, rejected) ->
                sounds.useTheme(active)
                themeWatcher.watch((active.info.source as? dev.xross.launcher.theme.pack.PackSource.Folder)?.dir)
                env.update {
                    it.copy(
                        themes = ThemeSetup(
                            packs = packs.map { p -> p.id to p.name }.ifEmpty { listOf(active.id to active.info.name) },
                            activeId = active.id,
                            activeName = active.info.name,
                            activeAuthor = active.info.manifest.author,
                            packPalette = active.palette != null || active.variants.any { v -> v.palette != null },
                            variants = active.variants.map { v -> v.id to v.name },
                            problems = active.problems,
                            surfaces = active.surfaces.values.map { s -> s.id to s.label },
                            packSurface = active.surfaceId,
                            rejected = rejected.map { r -> "${r.id}: ${r.reason}" },
                            packFlags = active.flags,
                            packWallpaper = active.hasWallpaper,
                            looks = (listOf("") + active.variants.map { v -> v.id }).associateWith { v -> active.look(v) },
                        ),
                    )
                }
            }
        }
        // Unlock toasts: new unlocks found by any profile refresh, one at a time.
        viewModelScope.launch {
            profiles.unlocks.collect { badges ->
                container.achievements.invalidateAll()
                unlockQueue.offer(badges)
                showNextUnlock()
                transient.value.achievements?.let { refreshAchievements(it.gameId, quiet = true) }
            }
        }
        // RetroAchievements badge for the focused game: what is cached at once, then a fresh copy
        // once the cursor has rested on it (so scrolling a list never queues a request per row).
        viewModelScope.launch {
            state.map { (it.focused?.action as? NodeAction.LaunchGame)?.gameId }.distinctUntilChanged().collectLatest { id ->
                if (transient.value.telemetry != null) transient.update { it.copy(telemetry = null) }
                if (id == null || !profiles.current.linked) return@collectLatest
                delay(TELEMETRY_PEEK_MS)
                val game = container.games.get(id) ?: return@collectLatest
                if (dev.xross.launcher.profile.RaConsoles.of(game.platformId) == null) return@collectLatest
                GameTelemetry.of(id, container.achievements.peek(game))?.let { known -> transient.update { it.copy(telemetry = known) } }
                delay(TELEMETRY_LOAD_MS)
                when (val fresh = container.achievements.load(game)) {
                    is dev.xross.launcher.profile.AchievementsState.Ready,
                    dev.xross.launcher.profile.AchievementsState.NoMatch,
                    -> transient.update { it.copy(telemetry = GameTelemetry.of(id, fresh)) }
                    else -> Unit // offline or refused: keep what the Xross folder had
                }
            }
        }
        refreshEnvironment()
        // Avatar and profile from the Xross folder first (instant, offline), then the network if stale.
        viewModelScope.launch {
            profiles.load()
            profileLoaded = true
            if (profiles.isStale()) refreshProfile(quiet = true)
        }
    }

    companion object {
        /** 0 = "Off — hardware only": just the driver's flat zone (see [dev.xross.launcher.input.StickTracker]). */
        internal val DEADZONES = listOf(0f, 0.15f, 0.25f, 0.35f, 0.45f, 0.55f)
        private val AXIAL_DEADZONES = listOf(0f, 0.1f, 0.2f, 0.3f)
        private const val INFO_PAGE = 5
        private val SLIDESHOW_SECONDS = listOf(3, 5, 10, 20)
        private const val GAMEBOOT_HANDOFF_MS = 1100L
        internal const val UNLOCK_TOAST_MS = 4_500L
        private const val UNLOCK_TOAST_GAP_MS = 350L
        private const val TELEMETRY_PEEK_MS = 250L
        private const val TELEMETRY_LOAD_MS = 450L
        private val GRID_MOVES = setOf(NavAction.LEFT, NavAction.RIGHT, NavAction.UP, NavAction.DOWN)
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
