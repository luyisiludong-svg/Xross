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
import dev.wave.launcher.input.ConfirmLayout
import dev.wave.launcher.input.ControllerFamily
import dev.wave.launcher.input.InputConfig
import dev.wave.launcher.input.InputSink
import dev.wave.launcher.input.NavAction
import dev.wave.launcher.input.resolve
import dev.wave.launcher.launch.GameLauncher
import dev.wave.launcher.launch.LaunchPlan
import dev.wave.launcher.storage.PlatformRegistry
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** One-shot requests that need an Activity. */
sealed interface UiEffect {
    data class StartActivity(val intent: Intent) : UiEffect
    data object PickFolder : UiEffect
    data object RequestStorageAccess : UiEffect
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
) {
    val focused: XmbNode? get() = items.getOrNull(focusedIndex)
    val depth: Int get() = breadcrumb.size
}

private data class Transient(
    val toast: String? = null,
    val remap: RemapSession? = null,
    val family: ControllerFamily = ControllerFamily.KEYBOARD,
)

class XmbViewModel(
    private val context: Context,
    private val container: AppContainer,
) : ViewModel(), InputSink {

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
    ) { e, games, apps, settings -> MenuTree(e.copy(games = games, apps = apps, settings = settings)) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, MenuTree(env.value))

    val state: StateFlow<XmbUiState> = combine(tree, nav, transient, settingsStore.settings) { t, n, tr, settings ->
        val view = resolve(t, n)
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
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, XmbUiState())

    private data class ListView(
        val breadcrumb: List<String>,
        val listKey: String,
        val items: List<XmbNode>,
        val focusedIndex: Int,
    ) {
        val focused: XmbNode? get() = items.getOrNull(focusedIndex)
        val depth: Int get() = breadcrumb.size
    }

    private fun resolve(t: MenuTree, n: NavState): ListView {
        val category = categories[n.categoryIndex]
        val stack = n.stack(category.id, category.title)
        val items = t.list(stack.last().key)
        return ListView(
            breadcrumb = stack.drop(1).map { it.title },
            listKey = stack.last().key,
            items = items,
            focusedIndex = stack.last().index.coerceIn(0, (items.size - 1).coerceAtLeast(0)),
        )
    }

    /** Read synchronously from the source flows so rapid inputs never act on a stale frame. */
    private fun current(): ListView = resolve(tree.value, nav.value)

    val inputConfig: InputConfig
        get() = settingsStore.current.let { InputConfig(it.confirmLayout, it.deadzone, it.keyRemap) }

    private var toastJob: Job? = null
    private var scanJob: Job? = null

    private var environmentLoaded = false

    init {
        refreshEnvironment()
    }

    // region Input

    override fun onAction(action: NavAction) {
        if (transient.value.remap != null) return
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
            NavAction.OPTIONS -> ui.focused?.options?.let(::run)
        }
    }

    override fun onRawKeyDown(keyCode: Int): Boolean {
        val session = transient.value.remap ?: return false
        val next = session.capture(keyCode)
        if (next.isDone) {
            settingsStore.update { it.copy(keyRemap = next.captured) }
            transient.update { it.copy(remap = null) }
            toast("Buttons remapped")
        } else {
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
            toast("Remapping cancelled")
        }
    }

    fun onCategoryTapped(index: Int) {
        if (index == nav.value.categoryIndex) {
            val category = categories[index]
            nav.value = nav.value.jumpTo(index, category.id, category.title, frame = null)
        } else {
            nav.update { it.moveCategory(index - it.categoryIndex, categories.size) }
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
        scanJob = viewModelScope.launch {
            env.update { it.copy(scanStatus = "Scanning…") }
            val roots = env.value.folders.map { File(it.path) }
            val result = runCatching { container.games.scan(roots) }
            val status = result.fold(
                onSuccess = { "${it.total} games · +${it.added} −${it.removed}" },
                onFailure = { "Scan failed: ${it.message}" },
            )
            env.update { it.copy(scanStatus = status) }
            if (!quiet || result.isFailure) toast(status)
        }
    }

    // endregion

    // region Navigation and actions

    private fun moveCursor(delta: Int) {
        val size = current().items.size
        val category = categories[nav.value.categoryIndex]
        nav.update { it.moveCursor(category.id, category.title, delta, size) }
    }

    private fun moveCategory(delta: Int) = nav.update { it.moveCategory(delta, categories.size) }

    private fun back() {
        val category = categories[nav.value.categoryIndex]
        nav.value.pop(category.id, category.title)?.let { nav.value = it }
    }

    private fun activate(node: XmbNode) {
        when (val action = node.action) {
            is NodeAction.OpenList -> {
                val category = categories[nav.value.categoryIndex]
                nav.update { it.push(category.id, category.title, Frame(action.key, node.title)) }
            }
            is NodeAction.LaunchGame -> launchGame(action.gameId)
            is NodeAction.LaunchApp -> {
                val app = container.apps.apps.value.firstOrNull {
                    it.packageName == action.packageName && it.activityName == action.activityName
                } ?: return
                emit(UiEffect.StartActivity(container.apps.launchIntent(app)))
            }
            is NodeAction.Run -> run(action.command)
            NodeAction.None -> Unit
        }
    }

    private fun launchGame(gameId: Long) = viewModelScope.launch {
        val game = container.games.get(gameId) ?: return@launch
        when (val plan = container.launcher.plan(game, settingsStore.current.emulatorChoice[game.platformId])) {
            is LaunchPlan.Ready -> {
                container.games.markPlayed(game.id)
                toast("Starting ${game.title} — ${plan.emulatorName}")
                emit(UiEffect.StartActivity(plan.intent))
            }
            is LaunchPlan.Failed -> toast(plan.message)
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
            Command.OpenHomeSettings -> emit(UiEffect.StartActivity(Intent(Settings.ACTION_HOME_SETTINGS)))
            Command.OpenAndroidSettings -> emit(UiEffect.StartActivity(Intent(Settings.ACTION_SETTINGS)))
            Command.OpenLibrarySettings -> {
                val index = categories.indexOfFirst { it.id == Categories.SETTINGS }
                val category = categories[index]
                nav.update { it.jumpTo(index, category.id, category.title, Frame(MenuTree.LIBRARY, "Library")) }
            }
            is Command.ToggleFavorite -> viewModelScope.launch { container.games.toggleFavorite(command.gameId) }
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

        private fun List<Float>.nextAfter(value: Float): Float {
            val i = indexOfFirst { kotlin.math.abs(it - value) < 0.001f }
            return this[(i + 1) % size]
        }
    }
}
