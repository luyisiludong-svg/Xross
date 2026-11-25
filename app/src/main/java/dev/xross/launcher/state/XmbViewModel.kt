package dev.xross.launcher.state

import dev.xross.launcher.scrape.BuiltInCredentials
import dev.xross.launcher.theme.ResolvedLook
import dev.xross.launcher.model.GlyphKey
import dev.xross.launcher.data.MusicUnderSnaps
import dev.xross.launcher.audio.VolumeChannel
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
import dev.xross.launcher.model.GlyphSet
import dev.xross.launcher.input.ControllerProfile
import dev.xross.launcher.input.InputConfig
import dev.xross.launcher.input.InputSink
import dev.xross.launcher.input.NavAction
import dev.xross.launcher.input.resolve
import dev.xross.launcher.launch.GameLauncher
import dev.xross.launcher.launch.LaunchPlan
import dev.xross.launcher.model.PlatformRegistry
import dev.xross.launcher.storage.StorageAccess
import dev.xross.launcher.storage.VolumeEvent
import dev.xross.launcher.scrape.ProviderId
import dev.xross.launcher.scrape.ScrapeProgress
import dev.xross.launcher.theme.XrossTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
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
import dev.xross.launcher.data.DatRepository
import dev.xross.launcher.data.DatSummary
import dev.xross.launcher.data.GameMetadataEntity
import dev.xross.launcher.data.XrossSettings
import dev.xross.launcher.media.NowPlaying
import dev.xross.launcher.media.PlaylistStore
import dev.xross.launcher.media.Photo
import dev.xross.launcher.profile.FriendsState
import dev.xross.launcher.profile.ProfileState
import dev.xross.launcher.scrape.Network
import dev.xross.launcher.scrape.ScrapeStatus
import dev.xross.launcher.scrape.providers.AccountCheck
import dev.xross.launcher.theme.BatteryLevel
import dev.xross.launcher.theme.pack.PackSource
import dev.xross.launcher.theme.pack.ResolvedComponents
import dev.xross.launcher.theme.pack.ThemeWatcher

/** One-shot requests that need an Activity. */
sealed interface UiEffect {
    data class StartActivity(val intent: Intent) : UiEffect
    data object PickFolder : UiEffect
    data object PickDatFolder : UiEffect
    /** Pick a `.xtheme` file; answers [XmbViewModel.onThemeArchivePicked]. */
    data object PickThemeArchive : UiEffect
    /** Create a document named [fileName] for a shared theme (2b.8c); answers [XmbViewModel.onThemeArchiveCreated]. */
    data class CreateThemeArchive(val fileName: String) : UiEffect
    data object PickBaseRomsFolder : UiEffect
    data class PickMediaFolder(val kind: MediaFolderKind) : UiEffect
    /** Ask for text (account, key, title, search); the answer goes to [XmbViewModel.onTextEntered]. */
    data class PromptText(val prompt: TextPrompt) : UiEffect
    /** RetroAchievements link dialog (user name + Web API key); answers [XmbViewModel.onRetroAchievementsEntered]. */
    data class PromptRetroAchievements(val user: String, val linked: Boolean) : UiEffect
    data object RequestStorageAccess : UiEffect
    data class SetWallpaper(val uri: Uri) : UiEffect
    /** Pick an image (or, for the video slot, an MP4) for a game's media; answers [XmbViewModel.onMediaFilePicked]. */
    data class PickMediaFile(val video: Boolean) : UiEffect
    /** Pick an image for the Xross wallpaper; answers [XmbViewModel.onWallpaperPicked]. */
    data object PickWallpaperImage : UiEffect
    /** Xross wallpaper ▸ Pick a folder…; answers [XmbViewModel.onWallpaperFolderPicked]. */
    data object PickWallpaperFolder : UiEffect
    /** Theme music ▸ Pick a file…; answers [XmbViewModel.onThemeMusicFilePicked]. */
    data object PickThemeMusicFile : UiEffect
    /** Theme music ▸ Pick a folder…; answers [XmbViewModel.onThemeMusicFolderPicked]. */
    data object PickThemeMusicFolder : UiEffect
    /** Add system ▸ Pick another folder…; answers [XmbViewModel.onWizardFolderPicked]. */
    data object PickWizardFolder : UiEffect
    /** Add system ▸ Console logo; answers [XmbViewModel.onWizardLogoPicked]. */
    data object PickWizardLogo : UiEffect

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
    /** The detected controller: picks the AUTO confirm side (and, under `glyphOverride: AUTO`, the glyphs). */
    val family: ControllerFamily = ControllerFamily.KEYBOARD,
    /** Face-button labels every hint draws: the detected family's, or the set `glyphOverride` picks (2b.7b). */
    val glyphs: GlyphSet = ControllerFamily.KEYBOARD.glyphs,
    val confirm: ConfirmLayout = ConfirmLayout.SOUTH,
    val waveEnabled: Boolean = true,
    val waveSpeed: Float = 1f,
    val theme: String = XrossTheme.LAVENDER.name,
    /** Title of the game being started while the start transition plays. */
    val booting: String? = null,
    /** True on the first screen after process start: the UI fades in with the startup cue. */
    val coldBoot: Boolean = false,
    /** The cold-start intro on screen (2b.5d): the boot video or the shader ramp, or its crossfade out. */
    val boot: BootState? = null,
    /** Cold start, before the saved theme pack is loaded and the intro decided: the screen stays black. */
    val bootPending: Boolean = false,
    /** Full-screen photo viewer / video player / Now Playing, if open. */
    val overlay: MediaOverlay? = null,
    /** Music state for the mini indicator and Now Playing. */
    val nowPlaying: NowPlaying = NowPlaying(),
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
    /** Options ▸ Metadata & Media while it is open. */
    val metadataEditor: MetadataEditorState? = null,
    /** Settings ▸ Emulators ▸ Add emulator / Add system while one is open. */
    val wizard: WizardView? = null,
    /** Settings ▸ Metadata ▸ Fix media… while it is open (0.2.2.0 § 3). */
    val browser: LibraryBrowserState? = null,
    /** Reading storage: a library scan or background fingerprinting is running. */
    val busy: Boolean = false,
    val showButtonHints: Boolean = true,
    /** The linked RetroAchievements profile behind the status bar avatar. */
    val profile: ProfileState = ProfileState(),
    /** The User Card while it is open. */
    val profileCard: ProfileCardState? = null,
    /** The status bar avatar has the focus (Up from the top of a category). */
    val avatarFocused: Boolean = false,
    /** Followed users (2b.7c), for the social stack. */
    val friends: FriendsState = FriendsState.Unlinked,
    /** The social stack's panel while it is open. */
    val socialPanel: SocialPanelState? = null,
    /** Current settings (the wave uniforms and the Wave settings panel read them). */
    val settings: XrossSettings = XrossSettings(),
    /** Settings ▸ Theme ▸ Wave settings while it is open. */
    val waveEditor: WaveEditorState? = null,
    /** Settings ▸ Theme ▸ Layout while it is open (2b.8a); the menu stays in full view behind it. */
    val layoutEditor: LayoutEditorState? = null,
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
    val optionsMenu: OptionsMenuState? = null,
    val info: GameInfoState? = null,
    val details: GameDetailsState? = null,
    /** A theme is being exported (2b.8b). */
    val themeWork: Boolean = false,
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
    private val themeWatcher = ThemeWatcher(viewModelScope) {
        container.themes.hotReload()?.let { toast("Theme not reloaded: $it") }
    }
    private val music = container.music

    /** Battery level for the battery tint and `battery` context rules (Theme 2.0, 2b.3). */
    private val batteryMonitor = BatteryMonitor(context).also { it.start() }
    val battery: StateFlow<BatteryLevel?> get() = batteryMonitor.level

    /**
     * The widgets drawn (2b.7a): the active pack's `components`, its variant's, then the user's
     * `display.components`. The same cascade as [dev.xross.launcher.theme.ThemeLookResolver]'s,
     * here for the status bar's sources and the avatar's place in navigation.
     */
    val components: StateFlow<ResolvedComponents> =
        combine(container.themes.active, container.settings.settings) { pack, st -> pack.components(st.themeVariant, st.componentOverrides) }
            .stateIn(
                viewModelScope, SharingStarted.Eagerly,
                container.settings.current.let { st -> container.themes.active.value.components(st.themeVariant, st.componentOverrides) },
            )

    /** The status bar's clock, battery, network and volume, collected by the bar alone (Assessment #9). */
    val statusBar: StateFlow<StatusBarState> = StatusBarSource(
        context, viewModelScope, batteryMonitor.level,
        needs = components.map { it.needs }.stateIn(viewModelScope, SharingStarted.Eagerly, components.value.needs),
    ).state

    /** For tests: a battery reading, as the system would broadcast it. */
    internal fun reportBattery(level: BatteryLevel?) = batteryMonitor.report(level)

    val media = MediaOverlayController(
        context = context,
        scope = viewModelScope,
        music = music,
        settings = container.settings,
        sounds = container.sounds,
        photoOptions = { photo -> openPhotoOptions(photo) },
        toast = { toast(it) },
    )

    /** RetroAchievements, the linked profile and the avatar HUD (Assessment #6). */
    private val ra = RaController(
        scope = viewModelScope,
        profiles = container.profiles,
        achievements = container.achievements,
        friends = container.friends,
        credentials = container.credentials,
        games = container.games,
        sounds = container.sounds,
        artVersion = { container.assets.version.value },
        host = object : RaController.Host {
            override fun toast(message: String) = this@XmbViewModel.toast(message)
            override fun fail(message: String) = this@XmbViewModel.fail(message)
            override fun promptLink(user: String, linked: Boolean) = emit(UiEffect.PromptRetroAchievements(user, linked))
            override fun moveCategory(delta: Int) = this@XmbViewModel.moveCategory(delta)
            override fun dismissMenus() = transient.update { it.copy(optionsMenu = null, details = null) }
            override fun components() = this@XmbViewModel.components.value
            override fun revealGame(gameId: Long) = this@XmbViewModel.revealGame(gameId)
        },
    )

    /** Settings ▸ Theme: packs, variants, colours, effects, components, wallpaper, import, reset (Assessment #6, 2b.8-prep). */
    private val theme = ThemeController(
        context = context,
        scope = viewModelScope,
        themes = container.themes,
        packs = container.themePacks,
        settings = container.settings,
        environment = container.environment,
        credentials = container.credentials,
        sounds = container.sounds,
        host = object : ThemeController.Host {
            override fun toast(message: String) = this@XmbViewModel.toast(message)
            override fun fail(message: String) = this@XmbViewModel.fail(message)
            override fun refuse() = this@XmbViewModel.refuse()
            override fun emit(effect: UiEffect) = this@XmbViewModel.emit(effect)
            override fun showOptions(menu: OptionsMenuState) = transient.update { it.copy(optionsMenu = menu) }
            override fun components() = this@XmbViewModel.components.value
            override fun friendsWanted(components: ResolvedComponents) = ra.onFriendsWanted(components = components)
            override fun photoUri(photoId: Long) = container.media.library.value.photos.firstOrNull { it.id == photoId }?.uri
            override fun dismissOptions() = transient.update { it.copy(optionsMenu = null) }
            override fun openNameEntry(entry: NameEntry) {
                transient.update { it.copy(optionsMenu = null) }
                typing.openNameEntry(entry)
            }
            override fun busy(on: Boolean) = transient.update { it.copy(themeWork = on) }
        },
    )

    /** Settings ▸ Theme ▸ Wave settings and Profile customization (Assessment §7.1). */
    private val editors = EditorController(
        settings = container.settings,
        sounds = container.sounds,
        waveDefaults = { container.themes.active.value.wave },
        host = object : EditorController.Host {
            override fun toast(message: String) = this@XmbViewModel.toast(message)
            override fun fail(message: String) = this@XmbViewModel.fail(message)
            override fun refuse() = this@XmbViewModel.refuse()
            override fun promptText(prompt: TextPrompt) = emit(UiEffect.PromptText(prompt))
        },
    )

    /** Options ▸ Metadata & Media: the editor and its media picker. */
    private val metaEditor: MetadataEditorController = MetadataEditorController(
        scope = viewModelScope,
        db = container.database,
        media = container.mediaEditor,
        sounds = container.sounds,
        artVersion = container.assets.version,
        videos = { container.settings.current.scrapeVideos },
        open = { uri -> context.contentResolver.openInputStream(uri) },
        scratch = { File(context.cacheDir, "media-pick") },
        host = object : MetadataEditorController.Host {
            override fun toast(message: String) = this@XmbViewModel.toast(message)
            override fun fail(message: String) = this@XmbViewModel.fail(message)
            override fun refuse() = this@XmbViewModel.refuse()
            override fun emit(effect: UiEffect) = this@XmbViewModel.emit(effect)
            override fun run(command: Command) = this@XmbViewModel.run(command)
            override fun siblings() = if (browser.isOpen) browser.state.value?.ids.orEmpty() else detailsSiblings()
            override fun follow(gameId: Long) = if (browser.isOpen) browser.follow(gameId) else followList(gameId)
            override fun reopenOptions(gameId: Long) {
                // Opened from the library browser: Back returns there, on the same game.
                if (browser.isOpen) return browser.follow(gameId)
                (typing.state.value.search?.results ?: current().items)
                    .firstOrNull { (it.action as? NodeAction.LaunchGame)?.gameId == gameId }?.let(::openOptions)
            }
        },
    )

    /** Settings ▸ Metadata ▸ Fix media… (0.2.2.0 § 3). */
    private val browser: LibraryBrowserController = LibraryBrowserController(
        scope = viewModelScope,
        games = container.games.games,
        metadata = container.database.metadata().observeAll(),
        sounds = container.sounds,
        host = object : LibraryBrowserController.Host {
            override fun openEditor(gameId: Long) = metaEditor.open(gameId)
        },
    )

    /** Settings ▸ Emulators ▸ Add emulator… / Add system… (0.2.2.0 § 1b). */
    private val wizards = WizardController(
        scope = viewModelScope,
        settings = container.settings,
        sounds = container.sounds,
        env = WizardEnv(),
        host = object : WizardController.Host {
            override fun toast(message: String) = this@XmbViewModel.toast(message)
            override fun fail(message: String) = this@XmbViewModel.fail(message)
            override fun emit(effect: UiEffect) = this@XmbViewModel.emit(effect)
            override fun rescan() = library.refreshFoldersAndRescan()
        },
    )

    /** What the wizards read from the device. */
    private inner class WizardEnv : WizardController.Env {
        private val pm get() = context.packageManager

        override fun apps(): List<AppChoice> {
            val known = dev.xross.launcher.launch.EmulatorRegistry.builtIn.flatMapTo(HashSet()) { it.packages }
            return EmulatorWizard.sortApps(
                container.apps.apps.value.filter { it.packageName != context.packageName }
                    .map { AppChoice(it.packageName, it.label, known = it.packageName in known) },
            )
        }

        override fun activities(packageName: String): List<ActivityChoice> {
            val launcher = pm.getLaunchIntentForPackage(packageName)?.component?.className
            val viewers = runCatching {
                val probe = Intent(Intent.ACTION_VIEW).setPackage(packageName)
                    .setDataAndType(Uri.parse("content://${context.packageName}.files/rom"), "*/*")
                pm.queryIntentActivities(probe, 0).filter { it.activityInfo.exported }.map { it.activityInfo.name }
            }.getOrDefault(emptyList())
            val names = (listOfNotNull(launcher) + viewers).distinct()
            return names.map { ActivityChoice(it, launcher = it == launcher, acceptsView = it in viewers) }
        }

        override fun romRoots(): List<File> = library.state.value.folders.map { File(it.path) }
        override fun survey(path: String, underRoot: Boolean) = dev.xross.launcher.storage.FolderSurvey.survey(File(path), underRoot)
        override fun unclaimed() = dev.xross.launcher.storage.FolderSurvey.unclaimed(romRoots())
        override fun treeToPath(uri: Uri): String? = container.volumes.treeUriToPath(uri)

        override fun copyLogo(uri: Uri, systemId: String): String? {
            val dir = File(container.environment.current.config, "logos").apply { mkdirs() }
            val target = File(dir, "$systemId.png")
            val bitmap = runCatching { context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) } }.getOrNull()
                ?: return null
            return runCatching {
                target.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                target.absolutePath
            }.getOrNull()
        }

        override suspend fun firstGame(platformId: String): dev.xross.launcher.data.GameEntity? = withContext(Dispatchers.IO) {
            container.database.games().getAll().filter { it.platformId == platformId }.minByOrNull { it.title.lowercase() }
        }

        override fun planWith(game: dev.xross.launcher.data.GameEntity, entryId: String, entry: dev.xross.launcher.config.CustomEmulator): LaunchPlan {
            val (def, problems) = dev.xross.launcher.launch.EmulatorRegistry.customDef(entryId, entry, builtInIds = emptySet())
            return if (def == null) LaunchPlan.Failed(problems.firstOrNull() ?: "This recipe can't load") else container.launcher.planWith(game, def)
        }

        override fun installedEmulators(): Set<String> = env.value.installedEmulators
    }

    /** Search and name entry, the two gamepad keyboards (Assessment §7.1). */
    private val typing = SearchController(
        scope = viewModelScope,
        sounds = container.sounds,
        find = { fts -> tree.value.gameNodes(container.games.search(fts)) },
        host = object : SearchController.Host {
            override fun activate(node: XmbNode) = this@XmbViewModel.activate(node)
            override fun openOptions(node: XmbNode) = this@XmbViewModel.openOptions(node)
            override fun submitName(purpose: NamePurpose, text: String) = this@XmbViewModel.submitName(purpose, text)
        },
    )

    /** ROM, base ROMs, DAT and media folders (Assessment §7.1). */
    private val folders: FolderPickers = FolderPickers(
        scope = viewModelScope,
        settings = container.settings,
        sounds = container.sounds,
        media = container.media,
        treeToPath = container.volumes::treeUriToPath,
        layout = { container.environment.current },
        host = object : FolderPickers.Host {
            override fun toast(message: String) = this@XmbViewModel.toast(message)
            override fun emit(effect: UiEffect) = this@XmbViewModel.emit(effect)
            override fun storageGranted() = library.state.value.storageGranted
            override fun refreshFolders() = library.refreshFolders()
            override fun refreshFoldersAndRescan() = library.refreshFoldersAndRescan()
            override fun importDats() = library.importDats()
        },
    )

    /** Storage access, scans, hot-swap, fingerprints, DATs, the Xross folder and backups (Assessment §7.1). */
    private val library: LibraryJobs = LibraryJobs(
        scope = viewModelScope,
        settings = container.settings,
        sounds = container.sounds,
        environment = container.environment,
        volumes = container.volumes,
        games = container.games,
        fingerprints = container.fingerprints,
        scraper = container.scraper,
        dats = container.dats,
        database = container.database,
        backups = container.backups,
        assets = container.assets,
        media = container.media,
        folders = folders,
        storageAccess = { storageAccess(context) },
        host = object : LibraryJobs.Host {
            override fun toast(message: String) = this@XmbViewModel.toast(message)
            override fun fail(message: String) = this@XmbViewModel.fail(message)
            override fun emit(effect: UiEffect) = this@XmbViewModel.emit(effect)
            override fun showOptions(menu: OptionsMenuState) = transient.update { it.copy(optionsMenu = menu) }
            override suspend fun onXrossFolderMoved() = ra.reloadProfile()
        },
    )

    /** The cold-start intro and the game start transition (Assessment §7.1). */
    private val transitions = TransitionController(
        scope = viewModelScope,
        settings = container.settings,
        audio = container.audio,
        games = container.games,
        launcher = container.launcher,
        sessions = container.sessions,
        pack = { container.themes.active.value },
        category = { state.value.categoryId },
        coldBoot = coldBoot,
        host = object : TransitionController.Host {
            override fun fail(message: String) = this@XmbViewModel.fail(message)
            override fun emit(effect: UiEffect) = this@XmbViewModel.emit(effect)
        },
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

    /** The menu's snapshot with the library's status in it. */
    private val menuEnv = combine(env, library.state) { e, l -> l.into(e) }

    private val _effects = Channel<UiEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    /** DAT summaries and per-game metadata: one input so the tree's combine stays within five flows. */
    private data class Meta(
        val dats: List<DatSummary> = emptyList(),
        val metadata: Map<Long, GameMetadataEntity> = emptyMap(),
    )

    private val meta = combine(container.dats.summaries, container.database.metadata().observeAll()) { d, m ->
        Meta(d, m.associateBy { it.gameId })
    }

    private val tree: StateFlow<MenuTree> = combine(
        combine(menuEnv, meta, container.credentials.accounts, container.screenScraper.quota, container.profiles.state) { e, m, accounts, quota, profile ->
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
                    screenScraperAvailable = BuiltInCredentials.hasScreenScraper,
                    screenScraperUser = accounts.screenScraperUser,
                    steamGridDbKeySet = accounts.steamGridDbKey.isNotBlank(),
                    screenScraperQuota = quota?.let { q -> (q.user?.let { "$it · " } ?: "") + q.summary },
                ),
            )
        },
        container.games.games,
        container.apps.apps,
        settingsStore.settings,
        combine(container.media.library, container.playlists.playlists, ::Pair),
    ) { e, games, apps, settings, (mediaLib, playlists) ->
        MenuTree(
            e.copy(
                games = games,
                apps = apps,
                settings = settings,
                media = mediaLib,
                playlists = playlists,
                datFolder = folders.datFolder(settings.datFolder).absolutePath,
                baseRomsFolder = folders.baseRomsFolder(settings.baseRomsFolder).absolutePath,
            ),
        )
    }
        .stateIn(viewModelScope, SharingStarted.Eagerly, MenuTree(library.state.value.into(env.value)))

    private val fingerprints = container.fingerprints

    /** Sampled so per-file progress doesn't redraw the menu more than twice a second. */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private val fingerprintStatus: StateFlow<FingerprintStatus> =
        combine(fingerprints.stats, fingerprints.current) { st, cur -> FingerprintStatus(st, cur) }
            .sample(500)
            .stateIn(viewModelScope, SharingStarted.Eagerly, FingerprintStatus())

    /** Inputs that only some lists read live (fingerprint progress, Now Playing). */
    private data class Live(
        val fingerprints: FingerprintStatus,
        val nowPlaying: NowPlaying,
        val scrape: ScrapeProgress = ScrapeProgress(),
    )

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private val live: StateFlow<Live> = combine(fingerprintStatus, music.state, container.scraper.progress.sample(500)) { fp, np, sc ->
        Live(fp, np, sc)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Live(FingerprintStatus(), music.state.value))

    private data class Settled(
        val transient: Transient,
        val transitions: TransitionState,
        val layoutEditor: LayoutEditorState?,
        val editors: EditorsState,
        val typing: TypingState,
        val library: LibraryStatus,
        val settings: XrossSettings,
        val overlay: MediaOverlay?,
        val profile: ProfileState,
        val ra: RaState,
        val metadataEditor: MetadataEditorState?,
        val wizard: OpenWizard?,
        val browser: LibraryBrowserState?,
    )

    private val settled = combine(
        combine(
            combine(transient, transitions.state, metaEditor.state, wizards.state, browser.state) { tr, tn, me, wz, br -> Quad(tr, tn, me, wz, br) },
            theme.layoutEditor, editors.state, typing.state, library.state,
        ) { q, le, ed, ty, lib -> Panels(q.transient, q.transitions, le, ed, ty, lib, q.metadataEditor, q.wizard, q.browser) },
        settingsStore.settings, media.overlay, container.profiles.state, ra.state,
    ) { p, st, ov, pr, r ->
        Settled(p.transient, p.transitions, p.layoutEditor, p.editors, p.typing, p.library, st, ov, pr, r, p.metadataEditor, p.wizard, p.browser)
    }

    private data class Quad(
        val transient: Transient,
        val transitions: TransitionState,
        val metadataEditor: MetadataEditorState?,
        val wizard: OpenWizard?,
        val browser: LibraryBrowserState?,
    )

    /** The panels' own flows, combined first so [settled] stays within five inputs. */
    private data class Panels(
        val transient: Transient,
        val transitions: TransitionState,
        val layoutEditor: LayoutEditorState?,
        val editors: EditorsState,
        val typing: TypingState,
        val library: LibraryStatus,
        val metadataEditor: MetadataEditorState?,
        val wizard: OpenWizard?,
        val browser: LibraryBrowserState?,
    )

    /** The components' `glyphOverride` (2b.7b): which labels the hints draw. */
    private val glyphMode = components.map { it.glyphs }.distinctUntilChanged()

    val state: StateFlow<XmbUiState> = combine(tree, nav, settled, live, glyphMode) { t, n, sd, lv, glyphs ->
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
            glyphs = glyphs.glyphsFor(tr.family),
            confirm = settings.confirmLayout.resolve(tr.family),
            waveEnabled = settings.waveEnabled,
            waveSpeed = settings.waveSpeed,
            theme = settings.theme,
            booting = sd.transitions.booting,
            coldBoot = coldBoot,
            boot = sd.transitions.boot,
            bootPending = sd.transitions.bootPending,
            overlay = sd.overlay,
            nowPlaying = lv.nowPlaying,
            optionsMenu = tr.optionsMenu,
            search = sd.typing.search,
            nameEntry = sd.typing.nameEntry,
            info = tr.info,
            details = tr.details,
            metadataEditor = sd.metadataEditor,
            wizard = sd.wizard?.view,
            browser = sd.browser,
            busy = sd.library.scanning || sd.library.datWork || tr.themeWork || lv.fingerprints.current != null || lv.scrape.running,
            showButtonHints = settings.showButtonHints,
            profile = sd.profile,
            profileCard = sd.ra.profileCard,
            avatarFocused = sd.ra.avatarFocused,
            friends = sd.ra.friends,
            socialPanel = sd.ra.socialPanel,
            settings = settings,
            waveEditor = sd.editors.wave,
            layoutEditor = sd.layoutEditor,
            achievements = sd.ra.achievements,
            unlockToast = sd.ra.unlockToast,
            avatarEditor = sd.editors.avatar,
            raHub = sd.ra.raHub,
            telemetry = sd.ra.telemetry,
            gamePlatforms = t.platformByGame,
        )
    }.stateIn(
        viewModelScope, SharingStarted.Eagerly,
        XmbUiState(coldBoot = coldBoot, bootPending = coldBoot, glyphs = components.value.glyphs.glyphsFor(ControllerFamily.KEYBOARD)),
    )

    /**
     * The theme look the menu draws with (Assessment §7.4): pack, variant, the open category's and
     * the settled system's overrides, the context rules, the user's picks and the quick profile,
     * resolved by [ThemeController.resolvedLook]. The Activity collects it as one value.
     */
    val look: StateFlow<ResolvedLook> = theme.resolvedLook(
        focus = state.map { LookFocus(it.categoryId, it.focusedSystem) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, LookFocus(state.value.categoryId, state.value.focusedSystem)),
        battery = battery,
    )

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
                is NodeIcon.System -> icon.fallback
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

    override fun onCleared() {
        themeWatcher.watch(null)
        batteryMonitor.stop()
        media.close()
        super.onCleared()
    }

    // region Input

    /**
     * Touch and swipe input from `XmbScreen` and the boot overlay. [XmbEvent.Nav] takes the same
     * path as a key; the menu's own events are handled here, and each other group goes to the
     * controller that owns it. Touch skips the intro's input gate as it always has: the boot
     * overlay covers the menu while the intro runs, so nothing under it can be tapped.
     */
    fun onEvent(event: XmbEvent) {
        when (event) {
            is XmbEvent.Nav -> onAction(event.action)
            is XmbEvent.Menu -> onMenuEvent(event)
            is XmbEvent.Typing -> typing.onEvent(event)
            is XmbEvent.Editor -> editors.onEvent(event)
            is XmbEvent.MetadataEditor -> metaEditor.onEvent(event)
            is XmbEvent.Browser -> when (event) {
                is XmbEvent.Browser.RowTap -> browser.onTapRow(event.index)
                is XmbEvent.Browser.SystemTap -> browser.onTapSystem(event.index)
                is XmbEvent.Browser.FilterTap -> browser.onTapFilter(event.index)
                is XmbEvent.Browser.Scrolled -> browser.rememberScroll(event.firstVisible)
                XmbEvent.Browser.Close -> browser.close()
            }
            is XmbEvent.Wizard -> when (event) {
                is XmbEvent.Wizard.RowTap -> wizards.onTap(event.index)
                XmbEvent.Wizard.Close -> wizards.close()
            }
            is XmbEvent.Layout -> theme.onEvent(event)
            is XmbEvent.Ra -> ra.onEvent(event)
            is XmbEvent.Boot -> transitions.onEvent(event)
        }
    }

    private fun onMenuEvent(event: XmbEvent.Menu) {
        when (event) {
            is XmbEvent.Menu.CategoryTap -> onCategoryTapped(event.index)
            is XmbEvent.Menu.ItemTap -> onItemTapped(event.index)
            is XmbEvent.Menu.ItemLongPress -> onItemLongPressed(event.index)
            XmbEvent.Menu.CancelRemap -> cancelRemap()
            is XmbEvent.Menu.OptionTap -> onOptionTapped(event.index)
            XmbEvent.Menu.CloseOptions -> closeOptions()
            is XmbEvent.Menu.InfoRowTap -> onInfoRowTapped(event.index)
            XmbEvent.Menu.CloseInfo -> closeInfo()
            XmbEvent.Menu.PlayDetails -> onAction(NavAction.CONFIRM)
            XmbEvent.Menu.DetailsAchievements -> onDetailsAchievementsTapped()
            XmbEvent.Menu.CloseDetails -> closeDetails()
            XmbEvent.Menu.MiniPlayerTap -> onMiniPlayerTapped()
            XmbEvent.Menu.MiniPlayerLongPress -> onMiniPlayerLongPressed()
        }
    }

    override fun onAction(action: NavAction) {
        // The cold-start intro takes the first key (any key skips it); the menu gets the next one.
        // A key before the intro is decided (the saved pack still loading) skips it in advance.
        // The game start transition takes no input.
        if (transitions.takesInput()) return
        if (transient.value.remap != null) return
        if (typing.onNameAction(action)) return
        if (action == NavAction.MEDIA) return toggleMediaControls()
        if (editors.onWaveAction(action)) return
        if (theme.onLayoutAction(action)) return
        if (editors.onAvatarAction(action)) return
        if (ra.onScreenAction(action)) return
        if (wizards.onAction(action)) return
        if (metaEditor.onAction(action)) return
        if (browser.onAction(action)) return
        transient.value.info?.let { return infoAction(it, action) }
        transient.value.details?.let { return detailsAction(it, action) }
        // Before the media screens: the photo viewer's Options menu opens over it.
        transient.value.optionsMenu?.let { return optionsAction(it, action) }
        if (media.onAction(action)) return
        if (typing.onSearchAction(action)) return
        if (ra.onAvatarAction(action)) return
        val ui = current()
        when (action) {
            // Up from the top of a category root climbs into the status bar: the avatar opens its mini-HUD.
            // Without an AVATAR slot (2b.7a) there is nothing to climb into; the User Card stays in Settings ▸ Accounts.
            // Without one, the social stack (2b.7c) is what Up climbs into, when it shows.
            NavAction.UP -> when {
                ui.depth != 0 || ui.focusedIndex != 0 -> moveCursor(-1)
                components.value.avatar -> ra.focusAvatar()
                ra.socialStackShown() -> ra.openSocialPanel()
                else -> moveCursor(-1)
            }
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
        if (typing.state.value.search != null) return
        when (media.toggleNowPlaying()) {
            MediaOverlayController.NowPlayingToggle.OPENED -> {
                sounds.play(UiSound.OPTION)
                infoJob?.cancel()
                detailsJob?.cancel()
                ra.dismissAll()
                theme.dismissLayoutSettings()
                editors.dismissAll()
                metaEditor.close(sound = false)
                transient.update { it.copy(optionsMenu = null, info = null, details = null) }
            }
            MediaOverlayController.NowPlayingToggle.CLOSED -> sounds.play(UiSound.BACK)
            MediaOverlayController.NowPlayingToggle.NOTHING_PLAYING -> toast(NOTHING_PLAYING)
            MediaOverlayController.NowPlayingToggle.UNAVAILABLE -> Unit
        }
    }

    /** Touch: the floating mini player opens the full audio controls. */
    private fun onMiniPlayerTapped() = toggleMediaControls()

    /** Touch: a long press on the mini player plays or pauses without opening anything. */
    private fun onMiniPlayerLongPressed() {
        if (music.state.value.song == null) return
        music.togglePlayPause()
        sounds.play(UiSound.ENTER)
    }

    // region RetroAchievements (touch goes to [RaController] through [onEvent])

    /** Answer from the link dialog; see [RaController.onRetroAchievementsEntered]. */
    fun onRetroAchievementsEntered(user: String, key: String) = ra.onRetroAchievementsEntered(user, key)

    /** Games, with the cursor on [gameId] in its system's list (a friend's game from the hub's sidebar). */
    private fun revealGame(gameId: Long) {
        viewModelScope.launch {
            val game = container.games.get(gameId) ?: return@launch
            val index = categories.indexOfFirst { it.id == Categories.GAMES }
            val category = categories[index]
            val path = tree.value.pathToGame(gameId, game.platformId, category.title)
                ?: return@launch toast("${game.title} isn't listed in Games")
            nav.update { it.copy(categoryIndex = index).withStack(category.id, path) }
            sounds.play(UiSound.DRILL_IN)
        }
    }

    // endregion

    // region Theme packs and Wave settings

    /**
     * Theme music ▸ Pick a file…: a file on shared storage is used where it is (Xross has All
     * files access); one only reachable through its provider is copied to `Xross/media/bgm/`.
     */
    fun onThemeMusicFilePicked(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            val path = withContext(Dispatchers.IO) {
                container.volumes.documentUriToPath(uri)?.takeIf { File(it).canRead() } ?: copyThemeMusic(uri)
            } ?: return@launch fail("Couldn't read that file")
            val choice = withContext(Dispatchers.IO) { dev.xross.launcher.data.ThemeMusic.resolve(path, settingsStore.current.themeMusicOrder) }
            if ((choice as? dev.xross.launcher.data.ThemeMusic.Choice.Own)?.track == null) {
                return@launch fail("That isn't an audio file Xross can play (up to ${dev.xross.launcher.theme.pack.BgmRef.MAX_BYTES shr 20} MB)")
            }
            settingsStore.update { it.copy(themeMusic = path) }
            toast("Theme music: ${File(path).name}")
        }
    }

    private fun copyThemeMusic(uri: Uri): String? = runCatching {
        val name = (uri.lastPathSegment ?: "theme-music").substringAfterLast('/').substringAfterLast(':')
            .takeIf { n -> dev.xross.launcher.data.ThemeMusic.isAudio(File(n)) } ?: "theme-music.ogg"
        val dir = File(container.environment.current.media, "bgm").apply { mkdirs() }
        val target = File(dir, name)
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { out ->
                val copied = input.copyTo(out)
                if (copied > dev.xross.launcher.theme.pack.BgmRef.MAX_BYTES) error("too large")
            }
        } ?: return null
        target.absolutePath
    }.getOrNull()

    /** Theme music ▸ Pick a folder…: its audio files play as a list. */
    fun onThemeMusicFolderPicked(uri: Uri?) {
        if (uri == null) return
        val path = container.volumes.treeUriToPath(uri) ?: return fail("Xross can't read that folder directly")
        viewModelScope.launch {
            val choice = withContext(Dispatchers.IO) { dev.xross.launcher.data.ThemeMusic.resolve(path, settingsStore.current.themeMusicOrder) }
            val track = (choice as? dev.xross.launcher.data.ThemeMusic.Choice.Own)?.track ?: return@launch fail("No audio files Xross can play in ${File(path).name}")
            settingsStore.update { it.copy(themeMusic = path) }
            toast("Theme music: ${File(path).name}/ · ${track.files.size} tracks" + (choice.skipped.takeIf { it > 0 }?.let { " ($it skipped)" } ?: ""))
        }
    }

    /** Add system ▸ Pick another folder…: see [WizardController.onFolderPicked]. */
    fun onWizardFolderPicked(uri: Uri?) = wizards.onFolderPicked(uri)

    /** Add system ▸ Console logo: see [WizardController.onLogoPicked]. */
    fun onWizardLogoPicked(uri: Uri?) = wizards.onLogoPicked(uri)

    /** Metadata & Media ▸ a slot ▸ Use a file…: see [MetadataEditorController.onFilePicked]. */
    fun onMediaFilePicked(uri: Uri?) = metaEditor.onFilePicked(uri)

    /** A media picker thumbnail as a local file (downloaded once per editor session). */
    suspend fun mediaThumbnail(offer: dev.xross.launcher.scrape.MediaOffer): File? = metaEditor.thumbnail(offer)

    /** Settings ▸ Theme ▸ Xross wallpaper ▸ Pick a picture: see [ThemeController.onWallpaperPicked]. */
    fun onWallpaperPicked(uri: Uri?) = theme.onWallpaperPicked(uri)

    /** The picked `.xtheme`: see [ThemeController.onThemeArchivePicked]. */
    fun onThemeArchivePicked(uri: Uri?) = theme.onThemeArchivePicked(uri)

    /** The document created for a shared theme: see [ThemeController.onThemeArchiveCreated]. */
    fun onThemeArchiveCreated(uri: Uri?) = theme.onThemeArchiveCreated(uri)

    // endregion

    // region Search

    // Forwarded to [SearchController].

    fun closeSearch() = typing.closeSearch()

    // endregion

    // region Options menu

    /** The photo viewer's Options: its wallpaper choices, over the viewer. */
    private fun openPhotoOptions(photo: Photo) {
        val options = MediaMenus.photoOptions(photo.id, customWallpaper = settingsStore.current.customWallpaper.isNotBlank(), fit = settingsStore.current.wallpaperFit)
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

    private fun closeOptions() {
        if (transient.value.optionsMenu != null) {
            sounds.play(UiSound.BACK)
            transient.update { it.copy(optionsMenu = null) }
        }
    }

    /** Touch: tap a row to focus it, tap again (or tap the focused row) to select. */
    private fun onOptionTapped(index: Int) {
        val menu = transient.value.optionsMenu ?: return
        if (index == menu.current.index) selectOption(menu) else transient.update { it.copy(optionsMenu = menu.moveTo(index)) }
    }

    /** Touch: long-press an item to open its Options menu. */
    private fun onItemLongPressed(index: Int) {
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
            settingsStore.current.scrapeWifiOnly && !Network.isUnmetered(context) ->
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
                ScrapeStatus.OK -> "Updated from online sources"
                ScrapeStatus.NOT_FOUND -> "No service knows this game — try Find match"
                ScrapeStatus.ERROR -> "Couldn't reach the services; try again later"
                null -> "This game needs a fingerprint first; it's taken in the background"
            }
        }
    }

    /** The user picked a search result: lock the game to that identity and scrape it. */
    private fun chooseMatch(command: Command.ChooseMatch) = datWork {
        withContext(Dispatchers.IO) {
            container.database.metadata().edit(command.gameId) { row ->
                val base = DatRepository.withoutDatMatch(row).copy(locked = true, scrapeStatus = null)
                when (command.provider) {
                    ProviderId.SCREENSCRAPER -> base.copy(screenScraperId = command.id)
                    ProviderId.STEAMGRIDDB -> base.copy(steamGridDbId = command.id)
                    ProviderId.HASHEOUS -> base.copy(hasheousId = command.id)
                }
            }
            container.database.games().applyTitles()
        }
        when (container.scraper.scrapeOne(command.gameId)) {
            ScrapeStatus.OK -> "Match saved and scraped"
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
                // Sign in straight away so a typo shows now, not as a failed scrape later.
                datWork {
                    when (val check = container.screenScraper.checkAccount()) {
                        is AccountCheck.Ok -> "ScreenScraper account saved · " + check.quota.summary
                        is AccountCheck.Failed -> "Saved, but sign-in failed: ${check.reason}"
                        AccountCheck.SharedQuota -> "ScreenScraper account saved"
                    }
                }
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
            is TextPrompt.AvatarBorderColor -> editors.onAvatarColorEntered(text)
            is TextPrompt.GameDescription -> metaEditor.onDescriptionEntered(prompt.gameId, text)
            is TextPrompt.WizardText -> wizards.onText(prompt.field, text)
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
        val rows = typing.state.value.search?.results ?: current().items
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
        followList(next)
        showDetails(next, direction = delta)
    }

    /** The list cursor follows paging (Details, the metadata editor), so Back lands on the game that was on screen. */
    private fun followList(next: Long) {
        val search = typing.state.value.search
        if (search != null) {
            val i = search.results.indexOfFirst { (it.action as? NodeAction.LaunchGame)?.gameId == next }
            if (i >= 0) typing.focusResult(i)
        } else {
            val ui = current()
            val i = ui.items.indexOfFirst { (it.action as? NodeAction.LaunchGame)?.gameId == next }
            if (i >= 0 && i != ui.focusedIndex) {
                val category = categories[nav.value.categoryIndex]
                nav.value = nav.value.moveCursor(category.id, category.title, i - ui.focusedIndex, ui.items.size)
            }
        }
    }

    /** Touch: the achievements summary on Details. */
    private fun onDetailsAchievementsTapped() {
        val details = transient.value.details ?: return
        if (details.achievementsSupported) ra.showAchievements(details.gameId)
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
                (typing.state.value.search?.results ?: current().items)
                    .firstOrNull { (it.action as? NodeAction.LaunchGame)?.gameId == details.gameId }?.let(::openOptions)
            }
            NavAction.LEFT, NavAction.PREV_CATEGORY -> pageDetails(details, -1)
            NavAction.RIGHT, NavAction.NEXT_CATEGORY -> pageDetails(details, +1)
            NavAction.MEDIA -> Unit
        }
    }

    private fun closeDetails() {
        detailsJob?.cancel()
        if (transient.value.details != null) {
            sounds.play(UiSound.BACK)
            transient.update { it.copy(details = null) }
        }
    }

    // endregion

    // region DATs

    /** DAT and scraper-lookup work, one job at a time: see [LibraryJobs.datWork]. */
    private fun datWork(work: suspend () -> String) = library.datWork(work)

    /** Settings ▸ Library ▸ DAT folder: see [FolderPickers.onDatFolderPicked]. */
    fun onDatFolderPicked(uri: Uri?) = folders.onDatFolderPicked(uri)

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

    private fun closeInfo() {
        infoJob?.cancel()
        if (transient.value.info != null) {
            sounds.play(UiSound.BACK)
            transient.update { it.copy(info = null) }
        }
    }

    /** Touch: tap a row to highlight it. */
    private fun onInfoRowTapped(index: Int) {
        val info = transient.value.info ?: return
        transient.update { it.copy(info = info.moveTo(index)) }
    }

    // endregion

    override fun onRawKeyDown(keyCode: Int, controller: ConnectedController?): Boolean {
        val tr = transient.value
        val keyboards = typing.state.value
        if (keyboards.nameEntry != null) return typing.onNameRawKey(keyCode)
        if (tr.remap == null && tr.optionsMenu == null && tr.info == null && tr.details == null && !transitions.launching && !media.isOpen) {
            if (keyboards.search != null) return typing.onSearchRawKey(keyCode)
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

    private fun cancelRemap() {
        if (transient.value.remap != null) {
            transient.update { it.copy(remap = null) }
            sounds.play(UiSound.BACK)
            toast("Remapping cancelled")
        }
    }

    private fun onCategoryTapped(index: Int) {
        if (index == nav.value.categoryIndex) {
            val category = categories[index]
            if (nav.value.stack(category.id, category.title).size > 1) sounds.play(UiSound.DRILL_OUT)
            nav.value = nav.value.jumpTo(index, category.id, category.title, frame = null)
        } else {
            moveCategory(index - nav.value.categoryIndex)
        }
    }

    private fun onItemTapped(index: Int) {
        val ui = current()
        if (index == ui.focusedIndex) ui.focused?.let(::activate) else moveCursor(index - ui.focusedIndex)
    }

    // endregion

    // region Environment

    /** Activity resumed: refresh the environment and continue fingerprinting in the background. */
    fun onForeground() {
        library.setForeground(true)
        refreshEnvironment()
        audio.onReturnFromGame()
        recordFinishedSession()
        viewModelScope.launch { container.media.refresh() }
        // Playlists copied in or edited on a PC while Xross was away.
        viewModelScope.launch { container.playlists.reload() }
        library.resumeBackgroundWork()
        ra.onForeground()
        // Theme files edited while Xross was in the background (or events FUSE didn't deliver).
        viewModelScope.launch { container.themes.reloadIfChanged()?.let { toast("Theme not reloaded: $it") } }
    }

    /** Activity paused (a game or another app is in front): stop background I/O. */
    fun onBackground() {
        container.sessions.onBackground()
        library.onBackground()
        media.onBackground()
    }

    /** Back from a game: add the session to that game's playtime. */
    private fun recordFinishedSession() {
        val finished = container.sessions.onForeground() ?: return
        viewModelScope.launch {
            container.games.addPlayTime(finished.gameId, finished.durationMs)
            val game = container.games.get(finished.gameId) ?: return@launch
            ra.onReturnFromGame(game)
            val played = MenuTree.formatPlaytime(finished.durationMs) ?: "under a minute"
            toast("Played ${game.title} for $played")
        }
    }

    /**
     * Re-reads permission, ROM folders and installed apps/emulators. Called on resume. Scans
     * quietly on first load, and with a toast when access has just been granted.
     */
    fun refreshEnvironment() {
        val firstLoad = library.refresh()
        onAppsChanged()
        library.launchFolderSync(firstLoad)
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

    /** Options ▸ Move to Apps / Video / Music / Photo; null forgets the choice ([AppSorting]). */
    private fun setAppHome(packageName: String, home: dev.xross.launcher.model.AppHome?) {
        settingsStore.update { s ->
            s.copy(
                appHomes = if (home == null) s.appHomes - packageName else s.appHomes + (packageName to home),
                // A folder in Apps would keep it there: moving it to a category takes it out.
                appFolders = if (home != null && home != dev.xross.launcher.model.AppHome.APPS) s.appFolders - packageName else s.appFolders,
            )
        }
        sounds.play(UiSound.OK)
        val label = appLabel(packageName)
        toast(
            when (home) {
                null -> "$label is sorted automatically again"
                dev.xross.launcher.model.AppHome.APPS -> "$label moved to Apps"
                else -> with(AppSorting) { "$label moved to ${home.label} ▸ Apps" }
            },
        )
    }

    /** A name typed on the gamepad keyboard ([SearchController]) and confirmed. */
    private fun submitName(purpose: NamePurpose, text: String) {
        when (purpose) {
            is NamePurpose.NewAppFolder -> {
                val name = AppFolders.normalizeName(text)
                if (name == null) toast("Type a name first") else moveAppToFolder(purpose.packageName, name)
            }
            is NamePurpose.NewPlaylist -> {
                val name = PlaylistStore.normalizeName(text)
                if (name == null) toast("Type a name first") else addToPlaylist(name, purpose.paths, creating = true)
            }
            is NamePurpose.ExportTheme -> theme.onExportNamed(purpose, text)
        }
    }

    // region Music playlists

    private fun addToPlaylist(name: String, paths: List<String>, creating: Boolean = false) {
        val existed = container.playlists.find(name)
        viewModelScope.launch {
            val added = container.playlists.add(name, paths)
            val shown = container.playlists.find(name)?.name ?: name
            when {
                added == null -> refuse().also { toast("Couldn't save the playlist $shown") }
                paths.isEmpty() -> {
                    sounds.play(UiSound.OK)
                    toast(if (existed != null) "$shown already exists" else "Created $shown")
                }
                added == 0 -> toast(if (paths.size == 1) "Already in $shown" else "All already in $shown")
                else -> {
                    sounds.play(UiSound.OK)
                    val what = if (added == 1 && paths.size == 1) "Added" else "Added ${MediaMenus.count(added, "song")}"
                    toast(if (creating && existed == null) "Created $shown with ${MediaMenus.count(added, "song")}" else "$what to $shown")
                }
            }
        }
    }

    private fun playlistCommand(command: Command) {
        val store = container.playlists
        when (command) {
            is Command.NewPlaylist -> typing.openNameEntry(
                NameEntry(
                    if (command.paths.isEmpty()) "New playlist" else "New playlist with ${MediaMenus.count(command.paths.size, "song")}",
                    NamePurpose.NewPlaylist(command.paths),
                    maxLength = PlaylistStore.MAX_NAME,
                ),
            )
            is Command.AddToPlaylist -> addToPlaylist(command.name, command.paths)
            is Command.RemoveFromPlaylist -> viewModelScope.launch {
                if (store.remove(command.name, command.path)) {
                    sounds.play(UiSound.OK)
                    toast("Removed from ${command.name}")
                } else {
                    refuse()
                }
            }
            is Command.MoveInPlaylist -> viewModelScope.launch {
                val menus = tree.value.media
                if (store.move(command.name, command.path, command.delta, menus::hasSong)) sounds.play(UiSound.MOVE) else refuse()
            }
            is Command.DeletePlaylist -> viewModelScope.launch {
                if (store.delete(command.name)) {
                    sounds.play(UiSound.OK)
                    toast("Deleted ${command.name}")
                } else {
                    refuse()
                }
            }
            is Command.PlayPlaylist -> {
                val songs = tree.value.media.playlistSongs(command.name)
                if (songs.isEmpty()) return refuse()
                music.play(songs, if (command.shuffle) songs.indices.random() else 0, shuffle = command.shuffle)
                toast(if (command.shuffle) "Shuffling ${command.name}" else "Playing ${command.name}")
            }
            else -> error("not a playlist command: $command")
        }
    }

    // endregion

    fun closeNameEntry() = typing.closeNameEntry()

    // endregion

    // region Base ROMs folder and media folders

    // Forwarded to [FolderPickers].

    fun onBaseRomsFolderPicked(uri: Uri?) = folders.onBaseRomsFolderPicked(uri)

    fun onMediaFolderPicked(kind: MediaFolderKind, uri: Uri?) = folders.onMediaFolderPicked(kind, uri)

    // endregion

    /** The launcher is visible again (`onStart`): theme music may continue, a rotating wallpaper may move on. */
    fun onStarted() {
        audio.onStarted(true)
        rotator.onFront(android.os.SystemClock.elapsedRealtime())
    }

    /** Any key or touch (from the Activity): a wallpaper change that is due happens now (§ 5b). */
    fun onUserInput() = rotator.onInput(android.os.SystemClock.elapsedRealtime())

    /** Xross wallpaper ▸ Pick a folder… (0.2.2.0 § 5b). */
    private val rotator = WallpaperRotator()

    /** The rotating wallpaper's current picture, or null when no folder is set. */
    val rotatingWallpaper: StateFlow<String?> get() = rotator.current

    init {
        viewModelScope.launch {
            // A Photo album (its pictures by file name) wins over a folder.
            combine(
                settingsStore.settings.map { Triple(it.wallpaperFolder, it.wallpaperInterval, it.wallpaperAlbum) }.distinctUntilChanged(),
                container.media.library.map { it.photos }.distinctUntilChanged(),
            ) { setting, photos -> setting to photos }.collectLatest { (setting, photos) ->
                val (folder, interval, album) = setting
                val pictures = withContext(Dispatchers.IO) {
                    if (album.isNotBlank()) WallpaperRotator.album(photos.filter { it.folder == album }.map { it.path })
                    else WallpaperRotator.list(folder)
                }
                rotator.configure(pictures, RotationInterval.parse(interval), android.os.SystemClock.elapsedRealtime())
            }
        }
    }

    /** Xross wallpaper ▸ Pick a folder…: its pictures take turns. */
    fun onWallpaperFolderPicked(uri: Uri?) {
        if (uri == null) return
        val path = container.volumes.treeUriToPath(uri) ?: return fail("Xross can't read that folder directly")
        viewModelScope.launch {
            val n = withContext(Dispatchers.IO) { WallpaperRotator.list(path).size }
            if (n == 0) return@launch fail("No pictures (PNG, JPEG, WebP) in ${File(path).name}")
            settingsStore.update {
                it.copy(wallpaperFolder = path, wallpaperAlbum = "", wallpaperInterval = if (it.wallpaperInterval == RotationInterval.OFF.name) RotationInterval.MIN_15.name else it.wallpaperInterval)
            }
            toast("Wallpaper: ${File(path).name}/ · $n pictures, ${RotationInterval.parse(settingsStore.current.wallpaperInterval).label.lowercase()}")
        }
    }

    /** The launcher is no longer visible: back up the library shortly after, if it changed. */
    fun onStopped() {
        // Leaving during the intro ends it (and one not decided yet won't start): the video
        // doesn't play on behind another app.
        transitions.onStopped()
        audio.onStarted(false)
        library.onStopped()
    }

    fun onAppsChanged() {
        viewModelScope.launch {
            container.apps.refresh()
            env.update { it.copy(installedEmulators = container.launcher.installedEmulatorIds()) }
        }
    }

    /** Settings ▸ Library ▸ Add folder: see [FolderPickers.onFolderPicked]. */
    fun onFolderPicked(uri: Uri?) = folders.onFolderPicked(uri)

    /** Storage hot-swap: see [LibraryJobs.onVolumeEvent]. */
    fun onVolumeEvent(event: VolumeEvent) = library.onVolumeEvent(event)

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

    // region Game start (forwarded to [TransitionController]; the intro's touches go through [onEvent])

    private fun launchGame(gameId: Long) {
        transitions.launchGame(gameId)
    }

    fun onLaunchFailed(message: String) = transitions.onLaunchFailed(message)

    // endregion

    private fun run(command: Command) {
        when (command) {
            // Settings ▸ Theme (2b.8-prep): every theme command is the ThemeController's.
            Command.CycleTheme, is Command.SetTheme, Command.CycleThemePack, is Command.SetThemePack, Command.ReloadThemePacks,
            Command.CycleThemeVariant, is Command.SetThemeVariant, Command.ImportThemeArchive, Command.ReplaceImportedTheme,
            Command.KeepBothImportedThemes, Command.CycleQuickProfile, is Command.SetQuickProfile, is Command.ToggleColorSwitch,
            is Command.SetColorSwitch, Command.CycleSurfaceStyle, is Command.SetXrossWallpaper, Command.ResetXrossWallpaper,
            Command.PickXrossWallpaper, is Command.SetStatusSlots, is Command.EditStatusSlot, is Command.CycleComponent,
            is Command.SetComponent, is Command.SetBadgePills, is Command.ToggleBadgePill, is Command.CycleEffect,
            is Command.SetEffect, Command.ResetThemeDefaults, Command.OpenLayoutSettings, Command.ExportTheme,
            is Command.ExportThemePack, is Command.ChooseExportScope, Command.ConfirmThemeExport, Command.SwitchToExportedTheme,
            is Command.ShareThemePack, Command.ShareExportedTheme,
            -> check(theme.onCommand(command)) { "ThemeController didn't take $command" }
            // Library work (step 6): the LibraryJobs'.
            Command.RequestStorageAccess, Command.Rescan, Command.ImportDats, Command.MatchDats, is Command.RemoveDat,
            Command.ToggleLargeImageHashing, Command.RecheckFingerprints, Command.CycleXrossVolume, Command.BackupNow,
            Command.ConfirmRestore, Command.RestoreBackup,
            -> check(library.onCommand(command)) { "LibraryJobs didn't take $command" }
            // Settings ▸ Library and Media folders (step 6): the FolderPickers'.
            Command.AddFolder, is Command.RemoveFolder, Command.ToggleAutoDetect, Command.PickBaseRomsFolder,
            Command.ResetBaseRomsFolder, Command.CreateSystemFolders, Command.PickDatFolder, is Command.AddMediaFolder,
            is Command.RemoveMediaFolder, is Command.ClearMediaFolders,
            -> check(folders.onCommand(command)) { "FolderPickers didn't take $command" }
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
                if (!BuiltInCredentials.hasScreenScraper) {
                    toast("This build has no ScreenScraper developer credentials")
                } else {
                    emit(UiEffect.PromptText(TextPrompt.ScreenScraperUser(container.credentials.current.screenScraperUser)))
                }
            Command.EditSteamGridDbKey -> emit(UiEffect.PromptText(TextPrompt.SteamGridDbKey))
            Command.LinkRetroAchievements -> ra.promptLink()
            Command.RefreshRetroAchievements -> ra.refreshOrLink()
            Command.OpenProfileCard -> ra.openProfileCard()
            Command.CheckScreenScraperAccount -> datWork {
                when (val check = container.screenScraper.checkAccount()) {
                    is AccountCheck.Ok ->
                        "Signed in" + (check.quota.user?.let { " as $it" } ?: "") + " · " + check.quota.summary
                    is AccountCheck.Failed -> check.reason
                    AccountCheck.SharedQuota -> "ScreenScraper works · not signed in, so lookups share Xross's quota"
                }
            }
            Command.ClearArtwork -> viewModelScope.launch {
                container.scraper.stop()
                val freed = container.assets.clear()
                withContext(Dispatchers.IO) {
                    val db = container.database.metadata()
                    db.upsertAll(db.getAll().map { it.copy(scrapeStatus = null) })
                    db.clearMediaStatus()
                }
                toast("Freed ${MenuTree.formatSize(freed)} · art downloads again on the next scrape")
            }
            is Command.ShowAchievements -> ra.showAchievements(command.gameId)
            is Command.ShowDetails -> showDetails(command.gameId)
            is Command.OpenMetadataEditor -> metaEditor.open(command.gameId)
            is Command.SetMissingArtFilter -> {
                env.update { e -> e.copy(missingArtOnly = if (command.on) e.missingArtOnly + command.platformId else e.missingArtOnly - command.platformId) }
                sounds.play(UiSound.OK)
            }
            is Command.OpenLibraryBrowser -> browser.open(command.platformId)
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
                        it.copy(
                            locked = false, titleOverride = null, synopsisOverride = null,
                            screenScraperId = null, steamGridDbId = null, scrapeStatus = null,
                        )
                    }
                }
                container.dats.matchAll()
                toast("Back to the automatic match")
                if (container.scraper.isConfigured) rescrape(command.gameId)
            }
            is Command.CycleEmulator -> cycleEmulator(command.platformId)
            is Command.SetEmulatorChoice -> settingsStore.update { it.copy(emulatorChoice = it.emulatorChoice + (command.platformId to command.key)) }
            Command.OpenThemeMusicMenu -> current().focused?.let(::openOptions)
            Command.PickWallpaperFolder -> emit(UiEffect.PickWallpaperFolder)
            is Command.SetWallpaperAlbum -> {
                val n = container.media.library.value.photos.count { it.folder == command.album && it.path.isNotEmpty() }
                if (n == 0) return fail("${command.album} has no pictures Xross can read as files")
                settingsStore.update {
                    it.copy(
                        wallpaperAlbum = command.album, wallpaperFolder = "",
                        wallpaperInterval = if (it.wallpaperInterval == RotationInterval.OFF.name) RotationInterval.MIN_15.name else it.wallpaperInterval,
                    )
                }
                toast("Wallpaper: ${command.album} · ${if (n == 1) "1 picture" else "$n pictures"}, ${RotationInterval.parse(settingsStore.current.wallpaperInterval).label.lowercase()}")
            }
            is Command.SetWallpaperInterval -> {
                settingsStore.update { it.copy(wallpaperInterval = command.interval.name) }
                toast(if (settingsStore.current.wallpaperFolder.isBlank() && settingsStore.current.wallpaperAlbum.isBlank()) "Pick a folder or an album for the wallpaper to change" else "Wallpaper: ${command.interval.label.lowercase()}")
            }
            Command.PickThemeMusicFile -> emit(UiEffect.PickThemeMusicFile)
            Command.PickThemeMusicFolder -> emit(UiEffect.PickThemeMusicFolder)
            is Command.SetThemeMusic -> {
                settingsStore.update { it.copy(themeMusic = command.value) }
                val playlist = dev.xross.launcher.data.ThemeMusic.playlistName(command.value)
                toast(
                    when {
                        command.value.isBlank() -> "Theme music from ${container.themes.active.value.info.name}"
                        playlist != null -> "Theme music: playlist $playlist"
                        else -> "Theme music off"
                    },
                )
            }
            is Command.SetThemeMusicOrder -> settingsStore.update { it.copy(themeMusicOrder = command.order) }
            is Command.OpenEmulatorWizard -> wizards.openEmulator(command.forSystem)
            is Command.EditCustomEmulator -> wizards.editEmulator(command.id)
            is Command.OpenSystemWizard -> wizards.openSystem(command.folder)
            is Command.EditCustomSystem -> wizards.editSystem(command.id)
            Command.ToggleWave -> settingsStore.update { it.copy(waveEnabled = !it.waveEnabled) }
            Command.OpenWaveSettings -> {
                transient.update { it.copy(optionsMenu = null) }
                editors.openWave()
            }
            Command.OpenAvatarStyle -> {
                transient.update { it.copy(optionsMenu = null) }
                editors.openAvatar()
            }
            Command.OpenRaHub -> ra.openRaHub()
            Command.ToggleSoundEffects -> settingsStore.update { it.copy(soundEffects = !it.soundEffects) }
            is Command.CycleVolume -> setVolume(command.channel, VolumeChannel.next(command.channel.of(settingsStore.current)))
            is Command.SetVolume -> setVolume(command.channel, command.value)
            Command.CycleMusicUnderSnaps -> settingsStore.update {
                it.copy(musicUnderSnaps = MusicUnderSnaps.entries[(it.musicUnderSnaps.ordinal + 1) % MusicUnderSnaps.entries.size])
            }
            is Command.SetMusicUnderSnaps -> settingsStore.update { it.copy(musicUnderSnaps = command.mode) }
            Command.ToggleBootSound -> settingsStore.update { it.copy(bootSound = !it.bootSound) }
            Command.ToggleGameBootSound -> settingsStore.update { it.copy(gameBootSound = !it.gameBootSound) }
            Command.OpenHomeSettings -> emit(UiEffect.StartActivity(Intent(Settings.ACTION_HOME_SETTINGS)))
            Command.OpenAndroidSettings -> emit(UiEffect.StartActivity(Intent(Settings.ACTION_SETTINGS)))
            Command.OpenLibrarySettings -> {
                val index = categories.indexOfFirst { it.id == Categories.SETTINGS }
                val category = categories[index]
                nav.update { it.jumpTo(index, category.id, category.title, Frame(MenuTree.LIBRARY, "Library")) }
            }
            Command.OpenNowPlaying -> media.openNowPlaying()
            is Command.NewPlaylist, is Command.AddToPlaylist, is Command.RemoveFromPlaylist, is Command.MoveInPlaylist,
            is Command.DeletePlaylist, is Command.PlayPlaylist,
            -> playlistCommand(command)
            is Command.SetWallpaperFit -> {
                settingsStore.update { it.copy(wallpaperFit = command.fit) }
                sounds.play(UiSound.OK)
                toast(if (settingsStore.current.customWallpaper.isBlank()) "Wallpaper fit: ${command.fit.label} (for a picture you set as Xross wallpaper)" else "Wallpaper fit: ${command.fit.label}")
            }
            is Command.SetSongSort -> settingsStore.update { it.copy(songSort = command.sort) }
            is Command.SetAlbumSort -> settingsStore.update { it.copy(albumSort = command.sort) }
            Command.ShuffleAllSongs -> {
                val songs = tree.value.media.allSongs
                if (songs.isNotEmpty()) {
                    music.play(songs, songs.indices.random(), shuffle = true)
                    toast("Shuffling ${MediaMenus.count(songs.size, "song")}")
                }
            }
            is Command.SetWallpaper -> container.media.library.value.photos.firstOrNull { it.id == command.photoId }
                ?.let { emit(UiEffect.SetWallpaper(it.uri)) }
            Command.ShowOptions -> current().focused?.let(::openOptions) ?: refuse()
            Command.CycleSlideshowInterval -> settingsStore.update {
                it.copy(slideshowSeconds = SLIDESHOW_SECONDS[(SLIDESHOW_SECONDS.indexOf(it.slideshowSeconds) + 1) % SLIDESHOW_SECONDS.size])
            }
            Command.ToggleResumeMusicAfterGames -> settingsStore.update { it.copy(resumeMusicAfterGames = !it.resumeMusicAfterGames) }
            Command.ToggleVideoLoop -> settingsStore.update { it.copy(videoLoop = !it.videoLoop) }
            Command.ToggleAllGamesFolder -> settingsStore.update { it.copy(allGamesFolder = !it.allGamesFolder) }
            Command.ToggleFileFormat -> settingsStore.update { it.copy(showFileFormat = !it.showFileFormat) }
            Command.CycleStandbyMotion -> settingsStore.update { it.copy(standbyMotion = it.standbyMotion.next()) }
            is Command.SetStandbyMotion -> settingsStore.update { it.copy(standbyMotion = command.mode) }
            Command.ToggleFullFrameRate -> settingsStore.update { it.copy(fullFrameRate = !it.fullFrameRate) }
            Command.CycleSlowShaders -> setSlowShaders(settingsStore.current.slowShaders.next())
            is Command.SetSlowShaders -> setSlowShaders(command.mode)
            Command.RetrySlowShaders -> {
                container.shaderHealth.retryRetired()
                toast("Background shaders will be tried again")
            }
            Command.CycleSystemLogos -> settingsStore.update {
                val styles = dev.xross.launcher.data.SystemLogoStyle.entries
                when {
                    !it.showSystemLogos -> it.copy(showSystemLogos = true, systemLogoStyle = styles.first().name)
                    else -> when (val next = styles.getOrNull(dev.xross.launcher.data.SystemLogoStyle.of(it.systemLogoStyle).ordinal + 1)) {
                        null -> it.copy(showSystemLogos = false)
                        else -> it.copy(systemLogoStyle = next.name)
                    }
                }
            }
            is Command.SetSystemLogos -> settingsStore.update {
                if (command.style == null) it.copy(showSystemLogos = false) else it.copy(showSystemLogos = true, systemLogoStyle = command.style.name)
            }
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
            is Command.SetAppHome -> setAppHome(command.packageName, command.home)
            Command.ToggleAutoSortApps -> settingsStore.update { it.copy(autoSortApps = !it.autoSortApps) }
            is Command.NewAppFolder -> {
                sounds.play(UiSound.OPTION)
                typing.openNameEntry(NameEntry("New folder for ${appLabel(command.packageName)}", NamePurpose.NewAppFolder(command.packageName)))
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
            is Command.LaunchGame -> launchGame(command.gameId)
            Command.OpenSearch -> typing.openSearch()
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
        val platform = PlatformRegistry.byId(platformId)?.takeIf { it.emulators.isNotEmpty() } ?: return
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

    /**
     * A new Slow shaders rule also measures every shader again, so one retired under Switch to the
     * wave comes back under Keep and warn, and one kept is judged anew under Switch to the wave.
     */
    private fun setSlowShaders(mode: dev.xross.launcher.data.SlowShaders) {
        if (mode == settingsStore.current.slowShaders) return
        container.shaderHealth.retireSlow = mode == dev.xross.launcher.data.SlowShaders.RETIRE
        settingsStore.update { it.copy(slowShaders = mode) }
        container.shaderHealth.retrySlow()
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
        // The Xross folder can move (storage granted, SD card): read the playlists where it is now.
        viewModelScope.launch { container.environment.layout.map { it.root }.distinctUntilChanged().collect { container.playlists.reload() } }
        library.watch()
        // Art is named after each game's ROM: keep the index current, then move the old private cache once.
        viewModelScope.launch {
            var migrated = false
            var backfill: Job? = null
            container.games.games.collect { games ->
                container.assets.setLibrary(games)
                // Which art each game has (v6 `mediaStatus`): once after the upgrade, then for new games.
                if (backfill?.isActive != true) backfill = viewModelScope.launch { runCatching { container.mediaEditor.backfillStatus() } }
                if (!migrated && games.isNotEmpty()) {
                    migrated = true
                    viewModelScope.launch { runCatching { container.assets.migrateLegacy() } }
                }
            }
        }
        folders.watch()
        viewModelScope.launch {
            settingsStore.settings.collect(audio::onSettingsChanged)
        }
        // Theme music (2b.5c) waits for the music player's queue and the full-screen video player.
        viewModelScope.launch { music.state.collect { audio.onMusicChanged() } }
        viewModelScope.launch {
            media.overlay.map { it is MediaOverlay.VideoPlayback }.distinctUntilChanged().collect(audio::onFullScreenVideo)
        }
        // Keep open search results in step with the library (favorites, playtime, rescans).
        viewModelScope.launch { tree.collect { typing.onLibraryChanged() } }
        // Background shader failures (compile errors, the watchdog, a crash last time): one toast each.
        viewModelScope.launch { container.shaderHealth.notices.collect { toast(it) } }
        // Theme pack: discover, activate the saved one, and keep sounds and Settings ▸ Theme in step.
        viewModelScope.launch {
            try {
                container.themes.rediscover()
                val wanted = settingsStore.current.themePack
                if (wanted != container.themes.active.value.id) container.themes.select(wanted)
            } finally {
                // The intro and the startup sound once the pack is in place (01. opening.wav by
                // default); even if the scan failed, so a cold start never stays black.
                if (coldBoot) transitions.startBoot()
            }
        }
        viewModelScope.launch {
            combine(container.themes.packs, container.themes.active, container.themes.rejected) { packs, active, rejected ->
                Triple(packs, active, rejected)
            }.collect { (packs, active, rejected) ->
                sounds.useTheme(active)
                themeWatcher.watch((active.info.source as? PackSource.Folder)?.dir)
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
                            packMusic = active.hasBgm,
                            packBootVideo = active.bootVideo != null,
                            looks = (listOf("") + active.variants.map { v -> v.id }).associateWith { v -> active.look(v) },
                            components = (listOf("") + active.variants.map { v -> v.id })
                                .associateWith { v -> active.components.over(active.variant(v)?.components) },
                            logos = active.logos.keys,
                        ),
                    )
                }
            }
        }
        // Unlock toasts, and the RetroAchievements badge of the focused game.
        ra.watch(state.map { (it.focused?.action as? NodeAction.LaunchGame)?.gameId })
        refreshEnvironment()
        ra.loadProfile()
    }

    companion object {
        /** 0 = "Off — hardware only": just the driver's flat zone (see [dev.xross.launcher.input.StickTracker]). */
        internal val DEADZONES = listOf(0f, 0.15f, 0.25f, 0.35f, 0.45f, 0.55f)
        private val AXIAL_DEADZONES = listOf(0f, 0.1f, 0.2f, 0.3f)
        private const val INFO_PAGE = 5
        private val SLIDESHOW_SECONDS = listOf(3, 5, 10, 20)

        private fun countOf(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"
        internal const val NOTHING_PLAYING = "Nothing is playing — choose music in the Music category"

        /** All files access on Android 11+, the legacy read permission before that. */
        fun hasStorageAccess(context: Context): Boolean = StorageAccess.canRead(context)

        private fun List<String>.nextOf(value: String): String = this[(indexOf(value) + 1) % size]

        private fun List<Float>.nextAfter(value: Float): Float {
            val i = indexOfFirst { kotlin.math.abs(it - value) < 0.001f }
            return this[(i + 1) % size]
        }
    }
}
