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
) {
    val focused: XmbNode? get() = items.getOrNull(focusedIndex)
    val depth: Int get() = breadcrumb.size
}

private data class Transient(
    val toast: String? = null,
    val remap: RemapSession? = null,
    val family: ControllerFamily = ControllerFamily.KEYBOARD,
    val booting: String? = null,
)

class XmbViewModel(
    private val context: Context,
    private val container: AppContainer,
    private val coldBoot: Boolean = false,
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

    private val nav = MutableStateFlow(NavState(categoryIndex = categories.indexOfFirst { it.id == Categories.GAMES }))
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

    override fun onCleared() {
        media.close()
        super.onCleared()
    }

    init {
        container.media.watch(viewModelScope)
        viewModelScope.launch {
            settingsStore.settings.collect {
                sounds.enabled = it.soundEffects
                sounds.volume = it.soundVolume
            }
        }
        if (coldBoot && settingsStore.current.bootSound) sounds.playCue(UiCue.COLDBOOT, settingsStore.current.soundVolume)
        refreshEnvironment()
    }

    // region Input

    override fun onAction(action: NavAction) {
        if (transient.value.remap != null || transient.value.booting != null) return
        if (media.onAction(action)) return
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
            NavAction.OPTIONS -> ui.focused?.options?.let {
                sounds.play(UiSound.OPTION)
                run(it)
            }
        }
    }

    override fun onRawKeyDown(keyCode: Int): Boolean {
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
        viewModelScope.launch { container.media.refresh() }
        if (env.value.storageGranted && scanJob?.isActive != true) fingerprints.start(viewModelScope)
    }

    /** Activity paused (a game or another app is in front): stop background I/O. */
    fun onBackground() {
        foreground = false
        fingerprints.stop()
        media.onBackground()
    }

    fun refreshEnvironment() {
        val wasGranted = env.value.storageGranted
        val granted = hasStorageAccess()
        val firstLoad = !environmentLoaded
        environmentLoaded = true
        env.update { it.copy(storageGranted = granted, folders = effectiveFolders(granted)) }
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

    private fun hasStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    private fun effectiveFolders(granted: Boolean): List<FolderEntry> {
        val settings = settingsStore.current
        val custom = settings.romFolders.map { FolderEntry(it, autoDetected = false) }
        val auto = if (settings.autoDetectFolders && granted) {
            container.volumes.discoverRomFolders().map { FolderEntry(it.absolutePath, autoDetected = true) }
        } else {
            emptyList()
        }
        return (custom + auto).distinctBy { it.path.trimEnd('/') }
    }

    private fun refreshFoldersAndRescan() {
        env.update { it.copy(folders = effectiveFolders(it.storageGranted)) }
        rescan(quiet = false)
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
            Command.Rescan -> {
                env.update { it.copy(folders = effectiveFolders(it.storageGranted)) }
                rescan(quiet = false)
            }
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

    companion object {
        private val DEADZONES = listOf(0.15f, 0.25f, 0.35f, 0.45f, 0.55f)
        private val WAVE_SPEEDS = listOf(0.5f, 1f, 1.5f, 2f)
        private val VOLUMES = listOf(0.25f, 0.5f, 0.75f, 1f)
        private val SLIDESHOW_SECONDS = listOf(3, 5, 10, 20)
        private const val GAMEBOOT_HANDOFF_MS = 1100L

        private fun List<Float>.nextAfter(value: Float): Float {
            val i = indexOfFirst { kotlin.math.abs(it - value) < 0.001f }
            return this[(i + 1) % size]
        }
    }
}
