package dev.xross.launcher.state

import androidx.compose.runtime.Immutable
import dev.xross.launcher.config.ConfigCodec
import dev.xross.launcher.config.CustomSystem
import dev.xross.launcher.model.SystemOverride

/**
 * A folder the system wizard can use. [underRoot] is true for a folder inside a ROM folder (it
 * scans by its name); one elsewhere is added to the library as a folder of its own.
 */
@Immutable
data class FolderChoice(val path: String, val name: String, val underRoot: Boolean, val extensions: Map<String, Int> = emptyMap()) {
    val fileCount: Int get() = extensions.values.sum()
}

/**
 * How a custom system launches its games. [key] is a recipe key ("retroarch:mgba", "duckstation",
 * an emulators.json id); [newApp] is set for an installed app with no recipe yet, which gets a
 * device recipe made with the default hand-over when the system is saved. An empty key means
 * Android's "Open with" chooser.
 */
@Immutable
data class EmulatorPick(val key: String, val label: String, val detail: String? = null, val newApp: AppChoice? = null)

/**
 * Settings ▸ Emulators ▸ Add system (0.2.2.0 § 1b), as pure state: a folder, its name and file
 * types, the emulator, an optional logo, then a review page that saves. Nothing is written until
 * [WizardEffect.Save]; Back on the first step cancels.
 */
@Immutable
data class SystemWizard(
    val folders: List<FolderChoice>,
    val emulators: List<EmulatorPick>,
    val step: Step = Step.FOLDER,
    val folder: FolderChoice? = null,
    val name: String = "",
    val shortName: String = "",
    val extensions: String = "",
    val emulator: EmulatorPick? = null,
    val logo: String = "",
    val index: Int = 0,
    val editing: String? = null,
) {
    enum class Step(val title: String) { FOLDER("Folder"), DETAILS("Name and file types"), EMULATOR("Emulator"), REVIEW("Review") }

    val extensionList: List<String> get() =
        extensions.split(',', ' ', ';').map(SystemOverride::normalizeExtension).filter { it.isNotEmpty() }.distinct()

    fun problems(): List<String> = buildList {
        if (folder == null) add("Pick a folder")
        if (name.isBlank()) add("Type a name")
        if (extensionList.isEmpty()) add("List at least one file type")
    }

    /** The systems.json entry. [emulatorKey] is the recipe the controller settled on (a new device recipe's id). */
    fun entry(emulatorKey: String? = emulator?.key): CustomSystem = CustomSystem(
        name = name.trim(),
        shortName = shortName.trim().ifEmpty { name.trim().filter { it.isLetterOrDigit() }.uppercase().take(4) },
        folders = listOfNotNull(folder?.name),
        extensions = extensionList,
        emulators = listOfNotNull(emulatorKey?.takeIf { it.isNotBlank() }),
        logo = logo,
        source = ConfigCodec.SOURCE_DEVICE,
    )

    fun view(): WizardView {
        val rows = rows()
        return WizardView(
            title = if (editing != null) "Edit system" else "Add system",
            stepName = step.title,
            step = step.ordinal + 1,
            steps = Step.entries.size,
            hint = when (step) {
                Step.FOLDER -> "Folders in your ROM folders that no system claims are listed first."
                Step.DETAILS -> "File types are filled in from what the folder holds."
                Step.EMULATOR -> "A recipe Xross has, or an installed app (it gets a recipe that hands over the file URI)."
                Step.REVIEW -> problems().firstOrNull()?.let { "Not ready: $it" } ?: "Saving rescans the library."
            },
            rows = rows,
            index = clampIndex(index, rows),
        )
    }

    fun rows(): List<WizardRow> = when (step) {
        Step.FOLDER -> folders.map {
            WizardRow(
                "dir:${it.path}", it.name,
                (if (it.underRoot) it.path else "${it.path} · added as a ROM folder") + " · ${countOf(it.fileCount, "file")}",
                selected = it.path == folder?.path,
            )
        } + WizardRow(PICK, "Pick another folder…", "Any folder on this device", kind = WizardRowKind.ACTION)
        Step.DETAILS -> listOf(
            WizardRow("name", "Name", name.ifBlank { "—" }, kind = WizardRowKind.TEXT),
            WizardRow("short", "Short name", shortName.ifBlank { entry().shortName.ifBlank { "—" } }, kind = WizardRowKind.TEXT),
            WizardRow("ext", "File types", extensionList.joinToString(", ").ifEmpty { "—" }, kind = WizardRowKind.TEXT),
            WizardRow(LOGO, "Console logo", if (logo.isBlank()) "None · pick an image file" else logo.substringAfterLast('/'), kind = WizardRowKind.ACTION),
            WizardRow(NEXT, "Next", problems().firstOrNull { it != "Pick a folder" }, kind = WizardRowKind.ACTION),
        )
        Step.EMULATOR -> emulators.map { WizardRow("emu:${it.newApp?.packageName ?: it.key}", it.label, it.detail, selected = it == emulator) }
        Step.REVIEW -> listOf(
            WizardRow("goto:FOLDER", "Folder", folder?.path ?: "—", kind = WizardRowKind.ACTION),
            WizardRow("goto:DETAILS", "Name", listOf(name, entry().shortName).filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { "—" }, kind = WizardRowKind.ACTION),
            WizardRow("goto:DETAILS", "File types", extensionList.joinToString(", ").ifEmpty { "—" }, kind = WizardRowKind.ACTION),
            WizardRow("goto:EMULATOR", "Emulator", emulator?.label ?: "—", kind = WizardRowKind.ACTION),
            WizardRow(SAVE, "Save", if (problems().isEmpty()) "Write it to systems.json and rescan" else "Not ready yet", kind = WizardRowKind.ACTION),
        ) + if (editing != null) listOf(WizardRow(DELETE, "Delete", "Remove this system from systems.json", kind = WizardRowKind.ACTION)) else emptyList()
    }.let { rows -> rows.mapIndexed { i, r -> if (rows.count { it.key == r.key } > 1) r.copy(key = "${r.key}#$i") else r } }

    fun move(delta: Int): SystemWizard = copy(index = clampIndex(view().index + delta, rows()))

    private fun at(next: Step): SystemWizard {
        val moved = copy(step = next, index = 0)
        return moved.copy(index = moved.rows().indexOfFirst { it.selected }.coerceAtLeast(0))
    }

    fun back(): SystemWizard? = if (step == Step.FOLDER) null else at(Step.entries[step.ordinal - 1])

    fun confirm(): Pair<SystemWizard, WizardEffect?> {
        val row = view().focused ?: return this to WizardEffect.Refuse
        val key = row.key.substringBefore('#')
        return when {
            key.startsWith("dir:") -> withFolder(folders.first { it.path == key.removePrefix("dir:") }) to null
            key == PICK -> this to WizardEffect.PickFolder
            key == "name" -> this to WizardEffect.Prompt(WizardField.SYSTEM_NAME, name)
            key == "short" -> this to WizardEffect.Prompt(WizardField.SYSTEM_SHORT_NAME, shortName.ifBlank { entry().shortName })
            key == "ext" -> this to WizardEffect.Prompt(WizardField.SYSTEM_EXTENSIONS, extensionList.joinToString(", "))
            key == LOGO -> this to WizardEffect.PickLogo
            key == NEXT -> if (name.isBlank() || extensionList.isEmpty()) this to WizardEffect.Refuse else at(Step.EMULATOR) to null
            key.startsWith("emu:") -> {
                val id = key.removePrefix("emu:")
                copy(emulator = emulators.first { (it.newApp?.packageName ?: it.key) == id }).at(Step.REVIEW) to null
            }
            key.startsWith("goto:") -> at(Step.valueOf(key.removePrefix("goto:"))) to null
            key == SAVE -> this to if (problems().isEmpty()) WizardEffect.Save else WizardEffect.Refuse
            key == DELETE -> this to WizardEffect.Delete
            else -> this to WizardEffect.Refuse
        }
    }

    /**
     * Picks [choice] and moves on to the details, prefilling the name from the folder and the
     * file types from what it holds (the most common ones, without obvious non-game files).
     */
    fun withFolder(choice: FolderChoice): SystemWizard {
        val changed = choice.path != folder?.path
        val guessedExt = guessExtensions(choice.extensions)
        return copy(
            folder = choice,
            name = if (changed || name.isBlank()) choice.name.replaceFirstChar { it.titlecase() } else name,
            extensions = if (changed || extensions.isBlank()) guessedExt.joinToString(", ") else extensions,
            shortName = if (changed) "" else shortName,
        ).at(Step.DETAILS)
    }

    fun withText(field: WizardField, text: String): SystemWizard = when (field) {
        WizardField.SYSTEM_NAME -> copy(name = text.trim())
        WizardField.SYSTEM_SHORT_NAME -> copy(shortName = text.trim().take(8))
        WizardField.SYSTEM_EXTENSIONS -> copy(extensions = text)
        else -> this
    }

    fun withLogo(path: String): SystemWizard = copy(logo = path)

    companion object {
        private const val PICK = "pick"
        private const val LOGO = "logo"
        private const val NEXT = "next"
        private const val SAVE = "save"
        private const val DELETE = "delete"

        /** Not game files: art, text and metadata that sit beside ROMs. */
        val NOT_GAMES = setOf(
            "txt", "nfo", "xml", "json", "jpg", "jpeg", "png", "webp", "gif", "bmp", "pdf", "md", "ini", "cfg", "log",
            "srm", "sav", "state", "db", "dat", "htm", "html", "url", "lnk", "sfv", "md5", "sha1",
        )

        fun guessExtensions(counts: Map<String, Int>, max: Int = 6): List<String> =
            counts.filterKeys { it.isNotBlank() && it.lowercase() !in NOT_GAMES }
                .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .take(max).map { it.key.lowercase() }

        fun edit(id: String, c: CustomSystem, folders: List<FolderChoice>, emulators: List<EmulatorPick>): SystemWizard {
            val folder = folders.firstOrNull { it.name.equals(c.folders.firstOrNull(), ignoreCase = true) }
                ?: FolderChoice(c.folders.firstOrNull().orEmpty(), c.folders.firstOrNull().orEmpty(), underRoot = true)
            return SystemWizard(
                folders = folders,
                emulators = emulators,
                step = Step.REVIEW,
                folder = folder,
                name = c.name,
                shortName = c.shortName,
                extensions = c.extensions.joinToString(", "),
                emulator = emulators.firstOrNull { it.key == c.emulators.firstOrNull() },
                logo = c.logo,
                editing = id,
            )
        }

        private fun countOf(n: Int, what: String) = if (n == 1) "1 $what" else "$n ${what}s"
    }
}
