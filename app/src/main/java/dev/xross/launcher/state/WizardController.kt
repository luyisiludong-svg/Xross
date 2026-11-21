package dev.xross.launcher.state

import android.net.Uri
import androidx.compose.runtime.Immutable
import dev.xross.launcher.audio.UiSound
import dev.xross.launcher.audio.UiSounds
import dev.xross.launcher.config.ConfigCodec
import dev.xross.launcher.config.CustomEmulator
import dev.xross.launcher.data.GameEntity
import dev.xross.launcher.data.SettingsStore
import dev.xross.launcher.input.NavAction
import dev.xross.launcher.launch.EmulatorRegistry
import dev.xross.launcher.launch.LaunchPlan
import dev.xross.launcher.model.PlatformRegistry
import dev.xross.launcher.storage.SurveyedFolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The open wizard, whichever it is. */
@Immutable
sealed interface OpenWizard {
    val view: WizardView

    data class Emulator(val wizard: EmulatorWizard) : OpenWizard {
        override val view: WizardView get() = wizard.view()
    }

    data class System(val wizard: SystemWizard) : OpenWizard {
        override val view: WizardView get() = wizard.view()
    }
}

/**
 * Settings ▸ Emulators ▸ Add emulator… and Add system… (0.2.2.0 § 1b). The steps are pure
 * ([EmulatorWizard], [SystemWizard]); this class fetches what they list (installed apps, their
 * activities, unclaimed folders), answers their effects and writes the result to
 * `emulators.json` / `systems.json` through [SettingsStore], marked `"source": "device"`.
 */
class WizardController(
    private val scope: CoroutineScope,
    private val settings: SettingsStore,
    private val sounds: UiSounds,
    private val env: Env,
    private val host: Host,
) {
    /** What the wizards read from the device. Blocking calls run on [Dispatchers.IO]. */
    interface Env {
        fun apps(): List<AppChoice>
        fun activities(packageName: String): List<ActivityChoice>
        fun romRoots(): List<java.io.File>
        fun survey(path: String, underRoot: Boolean): SurveyedFolder
        fun unclaimed(): List<SurveyedFolder>
        fun treeToPath(uri: Uri): String?
        /** Copies a picked image to the config folder as the system's logo; its path, or null. */
        fun copyLogo(uri: Uri, systemId: String): String?
        suspend fun firstGame(platformId: String): GameEntity?
        fun planWith(game: GameEntity, entryId: String, entry: CustomEmulator): LaunchPlan
        fun installedEmulators(): Set<String>
    }

    interface Host {
        fun toast(message: String)
        fun fail(message: String)
        fun emit(effect: UiEffect)
        fun rescan()
    }

    private val _state = MutableStateFlow<OpenWizard?>(null)
    val state: StateFlow<OpenWizard?> = _state.asStateFlow()

    val isOpen: Boolean get() = _state.value != null

    // region Opening

    fun openEmulator(forSystem: String? = null) = scope.launch {
        val apps = withContext(Dispatchers.IO) { env.apps() }
        sounds.play(UiSound.ENTER)
        _state.value = OpenWizard.Emulator(EmulatorWizard(apps = apps, systems = systemChoices(), chosen = setOfNotNull(forSystem)))
    }

    fun editEmulator(id: String) = scope.launch {
        val entry = settings.current.customEmulators[id]?.takeIf { it.fromDevice } ?: return@launch host.fail("Only emulators made here can be edited here")
        val apps = withContext(Dispatchers.IO) { env.apps() }
        val activities = entry.packages.firstOrNull()?.let { pkg -> withContext(Dispatchers.IO) { env.activities(pkg) } }.orEmpty()
        sounds.play(UiSound.ENTER)
        _state.value = OpenWizard.Emulator(EmulatorWizard.edit(id, entry, apps, systemChoices(), activities))
    }

    /** [folder]: a folder to start with (Options on an unknown folder ▸ Make a system from this folder). */
    fun openSystem(folder: String? = null) = scope.launch {
        val folders = withContext(Dispatchers.IO) { env.unclaimed().map(::choice) }
        val picks = emulatorPicks()
        sounds.play(UiSound.ENTER)
        val start = SystemWizard(folders = folders, emulators = picks)
        val chosen = folder?.let { path -> folders.firstOrNull { it.path == path } ?: withContext(Dispatchers.IO) { choice(env.survey(path, underRoot = true)) } }
        _state.value = OpenWizard.System(if (chosen != null) start.withFolder(chosen) else start)
    }

    fun editSystem(id: String) = scope.launch {
        val system = settings.current.customSystems[id]?.takeIf { it.fromDevice } ?: return@launch host.fail("Only systems made here can be edited here")
        val folders = withContext(Dispatchers.IO) { env.unclaimed().map(::choice) }
        sounds.play(UiSound.ENTER)
        _state.value = OpenWizard.System(SystemWizard.edit(id, system, folders, emulatorPicks()))
    }

    fun close() {
        if (_state.value == null) return
        _state.value = null
        sounds.play(UiSound.BACK)
    }

    private fun systemChoices(): List<SystemChoice> = PlatformRegistry.all.map { SystemChoice(it.id, it.name) }

    private fun choice(f: SurveyedFolder) = FolderChoice(f.path, f.name, f.underRoot, f.extensions)

    /** Recipes Xross has (installed first), then installed apps without one, then Android's chooser. */
    private suspend fun emulatorPicks(): List<EmulatorPick> {
        val installed = withContext(Dispatchers.IO) { env.installedEmulators() }
        val recipes = buildList {
            for (def in EmulatorRegistry.all) {
                if (def.id == "retroarch") continue
                add(EmulatorPick(def.id, def.name, if (def.id in installed) "Installed" else "Not installed"))
            }
        }.sortedBy { it.detail != "Installed" }
        val known = EmulatorRegistry.all.flatMapTo(HashSet()) { it.packages }
        val apps = withContext(Dispatchers.IO) { env.apps() }.filter { it.packageName !in known }
            .map { EmulatorPick("", it.label, "${it.packageName} · new recipe, file URI", newApp = it) }
        return recipes + apps + EmulatorPick("", "Any app that opens the file", "Android asks which app each time")
    }

    // endregion

    /** Input while a wizard is open; false when none is. */
    fun onAction(action: NavAction): Boolean {
        val open = _state.value ?: return false
        when (action) {
            NavAction.UP, NavAction.DOWN -> move(open, if (action == NavAction.UP) -1 else 1)
            NavAction.CONFIRM, NavAction.START, NavAction.RIGHT -> confirm(open)
            NavAction.BACK, NavAction.LEFT -> back(open)
            NavAction.OPTIONS -> close()
            NavAction.PREV_CATEGORY, NavAction.NEXT_CATEGORY, NavAction.MEDIA -> Unit
        }
        return true
    }

    /** A tap on row [index]: focus it, and confirm when it already had focus. */
    fun onTap(index: Int) {
        val open = _state.value ?: return
        if (open.view.index == index) return confirm(open)
        move(open, index - open.view.index)
    }

    private fun move(open: OpenWizard, delta: Int) {
        val next = when (open) {
            is OpenWizard.Emulator -> OpenWizard.Emulator(open.wizard.move(delta))
            is OpenWizard.System -> OpenWizard.System(open.wizard.move(delta))
        }
        if (next.view.index == open.view.index) return
        sounds.play(UiSound.MOVE)
        _state.value = next
    }

    private fun back(open: OpenWizard) {
        val previous = when (open) {
            is OpenWizard.Emulator -> open.wizard.back()?.let(OpenWizard::Emulator)
            is OpenWizard.System -> open.wizard.back()?.let(OpenWizard::System)
        }
        if (previous == null) return close()
        sounds.play(UiSound.BACK)
        _state.value = previous
    }

    private fun confirm(open: OpenWizard) {
        val (next, effect) = when (open) {
            is OpenWizard.Emulator -> open.wizard.confirm().let { (w, e) -> OpenWizard.Emulator(w) to e }
            is OpenWizard.System -> open.wizard.confirm().let { (w, e) -> OpenWizard.System(w) to e }
        }
        if (effect == WizardEffect.Refuse) return sounds.play(UiSound.ERROR)
        sounds.play(UiSound.ENTER)
        _state.value = next
        if (effect != null) handle(next, effect)
    }

    private fun handle(open: OpenWizard, effect: WizardEffect) {
        when (effect) {
            is WizardEffect.Prompt -> host.emit(UiEffect.PromptText(TextPrompt.WizardText(effect.field, effect.initial)))
            WizardEffect.PickFolder -> host.emit(UiEffect.PickWizardFolder)
            WizardEffect.PickLogo -> host.emit(UiEffect.PickWizardLogo)
            is WizardEffect.LoadActivities -> scope.launch {
                val list = withContext(Dispatchers.IO) { env.activities(effect.packageName) }
                update { w -> if (w is OpenWizard.Emulator && w.wizard.packageName == effect.packageName) OpenWizard.Emulator(w.wizard.withActivities(list)) else w }
            }
            WizardEffect.TryIt -> (open as? OpenWizard.Emulator)?.let { tryIt(it.wizard) }
            WizardEffect.Save -> when (open) {
                is OpenWizard.Emulator -> saveEmulator(open.wizard)
                is OpenWizard.System -> saveSystem(open.wizard)
            }
            WizardEffect.Delete -> delete(open)
            WizardEffect.Cancel -> close()
            WizardEffect.Refuse -> Unit
        }
    }

    private fun update(transform: (OpenWizard) -> OpenWizard) {
        val current = _state.value ?: return
        _state.value = transform(current)
    }

    // region Answers from the Activity

    fun onText(field: WizardField, text: String) = update { w ->
        when (w) {
            is OpenWizard.Emulator -> OpenWizard.Emulator(w.wizard.withText(field, text))
            is OpenWizard.System -> OpenWizard.System(w.wizard.withText(field, text))
        }
    }

    fun onFolderPicked(uri: Uri?) {
        if (uri == null) return
        val path = env.treeToPath(uri) ?: return host.fail("Xross can't read that folder directly")
        scope.launch {
            val roots = withContext(Dispatchers.IO) { env.romRoots().map { it.absolutePath.trimEnd('/') } }
            val underRoot = java.io.File(path).parent?.trimEnd('/') in roots
            val folder = withContext(Dispatchers.IO) { choice(env.survey(path, underRoot)) }
            if (folder.fileCount == 0) host.toast("${folder.name} is empty; type its file types by hand")
            update { w -> if (w is OpenWizard.System) OpenWizard.System(w.wizard.copy(folders = (w.wizard.folders + folder).distinctBy { it.path }).withFolder(folder)) else w }
        }
    }

    fun onLogoPicked(uri: Uri?) {
        if (uri == null) return
        val open = _state.value as? OpenWizard.System ?: return
        val id = open.wizard.editing ?: uniqueId(open.wizard.shortName.ifBlank { open.wizard.name }, takenSystemIds())
        scope.launch {
            val path = withContext(Dispatchers.IO) { env.copyLogo(uri, id) } ?: return@launch host.fail("Couldn't read that image")
            update { w -> if (w is OpenWizard.System) OpenWizard.System(w.wizard.withLogo(path)) else w }
        }
    }

    // endregion

    // region Try, save, delete

    private fun tryIt(w: EmulatorWizard) = scope.launch {
        val systemId = w.systems.firstOrNull { it.id in w.chosen }?.id ?: return@launch host.fail("Pick a system first")
        val game = env.firstGame(systemId) ?: return@launch host.fail("No ${w.systems.first { it.id == systemId }.name} games in the library to try")
        when (val plan = withContext(Dispatchers.IO) { env.planWith(game, w.editing ?: "try", w.entry()) }) {
            is LaunchPlan.Ready -> {
                host.toast("Trying ${game.title}…")
                host.emit(UiEffect.Launch(plan))
            }
            is LaunchPlan.Failed -> host.fail(plan.message)
        }
    }

    private fun takenEmulatorIds(): Set<String> =
        EmulatorRegistry.builtIn.mapTo(HashSet()) { it.id } + settings.current.customEmulators.keys + "try"

    private fun takenSystemIds(): Set<String> = PlatformRegistry.builtIn.mapTo(HashSet()) { it.id } + settings.current.customSystems.keys

    private fun saveEmulator(w: EmulatorWizard) {
        val entry = w.entry()
        val id = w.editing ?: uniqueId(entry.name, takenEmulatorIds())
        settings.update { s -> s.copy(customEmulators = LinkedHashMap(s.customEmulators).apply { put(id, entry) }) }
        _state.value = null
        sounds.play(UiSound.OK)
        val problems = EmulatorRegistry.problems[id]
        if (problems.isNullOrEmpty()) host.toast("${entry.name} saved to emulators.json") else host.fail("${entry.name} saved, but: ${problems.first()}")
    }

    private fun saveSystem(w: SystemWizard) {
        val folder = w.folder ?: return
        val id = w.editing ?: uniqueId(w.shortName.ifBlank { w.name }, takenSystemIds())
        // An installed app without a recipe gets one that hands over the file URI.
        val newApp = w.emulator?.newApp
        val emuId = newApp?.let { uniqueId(it.label, takenEmulatorIds()) }
        val recipe = newApp?.let { app ->
            CustomEmulator(
                name = app.label,
                packages = listOf(app.packageName),
                activity = env.activities(app.packageName).let { list -> list.firstOrNull { it.launcher }?.className ?: list.firstOrNull()?.className }.orEmpty(),
                systems = listOf(id),
                command = EmulatorWizard.URI_COMMAND,
                source = ConfigCodec.SOURCE_DEVICE,
            )
        }
        val system = w.entry(emulatorKey = emuId ?: w.emulator?.key)
        settings.update { s ->
            val roms = if (folder.underRoot || folder.path in s.romFolders) s.romFolders else s.romFolders + folder.path
            s.copy(
                customSystems = LinkedHashMap(s.customSystems).apply { put(id, system) },
                customEmulators = if (recipe != null && emuId != null) LinkedHashMap(s.customEmulators).apply { put(emuId, recipe) } else s.customEmulators,
                romFolders = roms,
            )
        }
        _state.value = null
        sounds.play(UiSound.OK)
        host.toast("${system.name} saved to systems.json · rescanning")
        host.rescan()
    }

    private fun delete(open: OpenWizard) {
        when (open) {
            is OpenWizard.Emulator -> {
                val id = open.wizard.editing ?: return
                settings.update { s ->
                    s.copy(
                        customEmulators = s.customEmulators.filterKeys { it != id },
                        emulatorChoice = s.emulatorChoice.filterValues { it != id },
                    )
                }
                host.toast("Emulator deleted")
            }
            is OpenWizard.System -> {
                val id = open.wizard.editing ?: return
                settings.update { s -> s.copy(customSystems = s.customSystems.filterKeys { it != id }, emulatorChoice = s.emulatorChoice - id) }
                host.toast("System deleted · rescanning")
                host.rescan()
            }
        }
        _state.value = null
        sounds.play(UiSound.OK)
    }

    // endregion
}
