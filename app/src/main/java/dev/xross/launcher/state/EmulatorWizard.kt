package dev.xross.launcher.state

import androidx.compose.runtime.Immutable
import dev.xross.launcher.config.ConfigCodec
import dev.xross.launcher.config.CustomEmulator
import dev.xross.launcher.launch.EsdeCommand

/** An installed app the emulator wizard can pick. [known] marks a package Xross ships a recipe for. */
@Immutable
data class AppChoice(val packageName: String, val label: String, val known: Boolean = false)

/** An activity of the picked app: its launcher activity, or an exported one that accepts VIEW. */
@Immutable
data class ActivityChoice(val className: String, val launcher: Boolean, val acceptsView: Boolean)

@Immutable
data class SystemChoice(val id: String, val name: String)

/** How the game reaches the emulator (step 3). Each preset writes a `command` string. */
enum class Handover(val label: String, val detail: String) {
    URI("File URI with a read grant", "VIEW with the file as data · works for most emulators"),
    PATH_DATA("File path as data", "VIEW with the plain path · the emulator needs storage access"),
    PATH_EXTRA("File path as an extra…", "You type the extra's name · storage access needed"),
    CUSTOM("Type an ES-DE command…", "Any tags from ES-DE's launch rules"),
}

/**
 * Settings ▸ Emulators ▸ Add emulator (0.2.2.0 § 1b), as pure state: pick an installed app, its
 * activity, how the game is handed over and which systems it runs, then try it and save. Nothing
 * is written until [WizardEffect.Save]; Back on the first step cancels.
 */
@Immutable
data class EmulatorWizard(
    val apps: List<AppChoice>,
    val systems: List<SystemChoice>,
    val step: Step = Step.APP,
    val packageName: String? = null,
    val name: String = "",
    val activities: List<ActivityChoice> = emptyList(),
    val activity: String? = null,
    val handover: Handover? = null,
    val extraName: String = "",
    val customCommand: String = "",
    val chosen: Set<String> = emptySet(),
    val index: Int = 0,
    /** The id of the device entry being edited; null for a new one. */
    val editing: String? = null,
) {
    enum class Step(val title: String) { APP("App"), ACTIVITY("Activity"), HANDOVER("Hand over the game"), SYSTEMS("Systems"), REVIEW("Review") }

    /** The `command` the chosen hand-over writes. */
    val command: String get() = when (handover) {
        Handover.URI -> URI_COMMAND
        Handover.PATH_DATA -> PATH_DATA_COMMAND
        Handover.PATH_EXTRA -> "%EXTRA_${extraName.trim()}%=%ROM%"
        Handover.CUSTOM -> customCommand.trim()
        null -> ""
    }

    /** The emulators.json entry this wizard makes. */
    fun entry(): CustomEmulator = CustomEmulator(
        name = name.trim().ifEmpty { packageName.orEmpty() },
        packages = listOfNotNull(packageName),
        activity = activity.orEmpty(),
        systems = systems.map { it.id }.filter { it in chosen },
        command = command,
        source = ConfigCodec.SOURCE_DEVICE,
    )

    /** Problems that keep Save and Try it disabled. */
    fun problems(): List<String> = buildList {
        if (packageName == null) add("Pick an app")
        if (activity.isNullOrBlank()) add("Pick an activity")
        if (handover == null) add("Pick how the game is handed over")
        if (handover == Handover.PATH_EXTRA && extraName.isBlank()) add("Type the extra's name")
        addAll(EsdeCommand.parse(command).problems)
        if (chosen.isEmpty()) add("Pick at least one system")
    }

    fun view(): WizardView {
        val rows = rows()
        return WizardView(
            title = if (editing != null) "Edit emulator" else "Add emulator",
            stepName = step.title,
            step = step.ordinal + 1,
            steps = Step.entries.size,
            hint = hint(),
            rows = rows,
            index = clampIndex(index, rows),
        )
    }

    private fun hint(): String? = when (step) {
        Step.APP -> "Known emulators first. Any app can be picked; Try it shows whether it starts the game."
        Step.ACTIVITY -> "The launcher activity usually works. Pick another if the emulator documents one for games."
        Step.HANDOVER -> null
        Step.SYSTEMS -> "Confirm ticks a system; Next when done."
        Step.REVIEW -> problems().firstOrNull()?.let { "Not ready: $it" } ?: "Try it starts the first game of ${firstSystemName() ?: "the system"}."
    }

    private fun firstSystemName() = systems.firstOrNull { it.id in chosen }?.name

    fun rows(): List<WizardRow> = when (step) {
        Step.APP -> apps.map { WizardRow("app:${it.packageName}", it.label, it.packageName + if (it.known) " · known emulator" else "", selected = it.packageName == packageName) }
            .ifEmpty { listOf(WizardRow("none", "No apps found", kind = WizardRowKind.INFO)) }
        Step.ACTIVITY -> activities.map {
            WizardRow(
                "act:${it.className}",
                it.className.substringAfterLast('.'),
                it.className + when {
                    it.launcher && it.acceptsView -> " · launcher, opens files"
                    it.launcher -> " · launcher"
                    else -> " · opens files"
                },
                selected = it.className == activity,
            )
        }.ifEmpty { listOf(WizardRow("none", "This app has no activity Xross can start", kind = WizardRowKind.INFO)) }
        Step.HANDOVER -> Handover.entries.map { h ->
            val detail = when {
                h == Handover.PATH_EXTRA && handover == h && extraName.isNotBlank() -> "Extra \"${extraName.trim()}\""
                h == Handover.CUSTOM && handover == h && customCommand.isNotBlank() -> customCommand.trim()
                else -> h.detail
            }
            WizardRow("ho:${h.name}", h.label, detail, kind = if (h == Handover.PATH_EXTRA || h == Handover.CUSTOM) WizardRowKind.TEXT else WizardRowKind.CHOICE, selected = h == handover)
        }
        Step.SYSTEMS -> systems.map { WizardRow("sys:${it.id}", it.name, it.id, selected = it.id in chosen) } +
            WizardRow(NEXT, "Next", if (chosen.isEmpty()) "Pick at least one" else "${chosen.size} picked", kind = WizardRowKind.ACTION)
        Step.REVIEW -> buildList {
            add(WizardRow("name", "Name", name.ifBlank { "—" }, kind = WizardRowKind.TEXT))
            add(WizardRow("goto:APP", "App", packageName ?: "—", kind = WizardRowKind.ACTION))
            add(WizardRow("goto:ACTIVITY", "Activity", activity ?: "—", kind = WizardRowKind.ACTION))
            add(WizardRow("goto:HANDOVER", "Hand over", command.ifBlank { "—" }, kind = WizardRowKind.ACTION))
            add(WizardRow("goto:SYSTEMS", "Systems", systems.filter { it.id in chosen }.joinToString { it.name }.ifEmpty { "—" }, kind = WizardRowKind.ACTION))
            val ready = problems().isEmpty()
            add(WizardRow(TRY, "Try it", if (ready) "Launch ${firstSystemName()}'s first game with this recipe" else "Not ready yet", kind = WizardRowKind.ACTION))
            add(WizardRow(SAVE, "Save", if (ready) "Write it to emulators.json" else "Not ready yet", kind = WizardRowKind.ACTION))
            if (editing != null) add(WizardRow(DELETE, "Delete", "Remove this emulator from emulators.json", kind = WizardRowKind.ACTION))
        }
    }

    fun move(delta: Int): EmulatorWizard = copy(index = clampIndex(view().index + delta, rows()))

    private fun at(next: Step): EmulatorWizard {
        // Land on the current choice of the step, so going back and forth keeps the place.
        val moved = copy(step = next, index = 0)
        val i = moved.rows().indexOfFirst { it.selected }.coerceAtLeast(0)
        return moved.copy(index = i)
    }

    /** Back: the previous step; null from the first step (cancel, nothing written). */
    fun back(): EmulatorWizard? = when (step) {
        Step.APP -> null
        else -> at(Step.entries[step.ordinal - 1])
    }

    /** Confirm on the focused row. */
    fun confirm(): Pair<EmulatorWizard, WizardEffect?> {
        val row = view().focused ?: return this to WizardEffect.Refuse
        val key = row.key
        return when {
            row.kind == WizardRowKind.INFO -> this to WizardEffect.Refuse
            key.startsWith("app:") -> {
                val pkg = key.removePrefix("app:")
                val app = apps.first { it.packageName == pkg }
                val same = pkg == packageName
                copy(
                    packageName = pkg,
                    name = if (same && name.isNotBlank()) name else app.label,
                    activities = if (same) activities else emptyList(),
                    activity = if (same) activity else null,
                ) to WizardEffect.LoadActivities(pkg)
            }
            key.startsWith("act:") -> copy(activity = key.removePrefix("act:")).at(Step.HANDOVER) to null
            key == "ho:${Handover.PATH_EXTRA.name}" -> copy(handover = Handover.PATH_EXTRA) to WizardEffect.Prompt(WizardField.EXTRA_NAME, extraName)
            key == "ho:${Handover.CUSTOM.name}" -> copy(handover = Handover.CUSTOM) to
                WizardEffect.Prompt(WizardField.COMMAND, customCommand.ifBlank { command.ifBlank { URI_COMMAND } })
            key.startsWith("ho:") -> copy(handover = Handover.valueOf(key.removePrefix("ho:"))).at(Step.SYSTEMS) to null
            key.startsWith("sys:") -> {
                val id = key.removePrefix("sys:")
                copy(chosen = if (id in chosen) chosen - id else chosen + id) to null
            }
            key == NEXT -> if (chosen.isEmpty()) this to WizardEffect.Refuse else at(Step.REVIEW) to null
            key == "name" -> this to WizardEffect.Prompt(WizardField.EMULATOR_NAME, name)
            key.startsWith("goto:") -> at(Step.valueOf(key.removePrefix("goto:"))) to null
            key == TRY -> this to if (problems().isEmpty()) WizardEffect.TryIt else WizardEffect.Refuse
            key == SAVE -> this to if (problems().isEmpty()) WizardEffect.Save else WizardEffect.Refuse
            key == DELETE -> this to WizardEffect.Delete
            else -> this to WizardEffect.Refuse
        }
    }

    /** The activities of the picked app arrived: preselect the launcher one and move on. */
    fun withActivities(list: List<ActivityChoice>): EmulatorWizard {
        val keep = activity?.takeIf { a -> list.any { it.className == a } }
        val pick = keep ?: list.firstOrNull { it.launcher }?.className ?: list.firstOrNull()?.className
        return copy(activities = list, activity = pick).at(Step.ACTIVITY)
    }

    /** A text prompt answered. Typed text after a hand-over choice moves on to Systems. */
    fun withText(field: WizardField, text: String): EmulatorWizard = when (field) {
        WizardField.EMULATOR_NAME -> copy(name = text.trim())
        WizardField.EXTRA_NAME -> copy(extraName = text.trim().filter { !it.isWhitespace() && it != '%' }).let { if (it.extraName.isBlank()) it else it.at(Step.SYSTEMS) }
        WizardField.COMMAND -> copy(customCommand = text.trim()).let { if (it.customCommand.isBlank()) it else it.at(Step.SYSTEMS) }
        else -> this
    }

    companion object {
        const val URI_COMMAND = "%ACTION%=android.intent.action.VIEW %DATA%=%ROMPROVIDER%"
        const val PATH_DATA_COMMAND = "%ACTION%=android.intent.action.VIEW %DATA%=%ROM%"
        private const val NEXT = "next"
        private const val TRY = "try"
        private const val SAVE = "save"
        private const val DELETE = "delete"

        /** Opens the wizard on a device entry, on its review page. */
        fun edit(id: String, e: CustomEmulator, apps: List<AppChoice>, systems: List<SystemChoice>, activities: List<ActivityChoice>): EmulatorWizard {
            val handover = when {
                e.command.trim() == URI_COMMAND -> Handover.URI
                e.command.trim() == PATH_DATA_COMMAND -> Handover.PATH_DATA
                Regex("^%EXTRA_([^%\\s]+)%=%ROM%$").matches(e.command.trim()) -> Handover.PATH_EXTRA
                else -> Handover.CUSTOM
            }
            val extra = Regex("^%EXTRA_([^%\\s]+)%=%ROM%$").find(e.command.trim())?.groupValues?.get(1).orEmpty()
            return EmulatorWizard(
                apps = apps,
                systems = systems,
                step = Step.REVIEW,
                packageName = e.packages.firstOrNull(),
                name = e.name,
                activities = activities,
                activity = e.activity,
                handover = handover,
                extraName = extra,
                customCommand = if (handover == Handover.CUSTOM) e.command else "",
                chosen = e.systems.toSet(),
                editing = id,
            )
        }

        /** Known emulators first, then the rest, each by label. */
        fun sortApps(apps: List<AppChoice>): List<AppChoice> =
            apps.distinctBy { it.packageName }.sortedWith(compareBy({ !it.known }, { it.label.lowercase() }))
    }
}
