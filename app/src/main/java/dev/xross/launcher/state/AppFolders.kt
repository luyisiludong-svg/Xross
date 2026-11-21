package dev.xross.launcher.state

import dev.xross.launcher.data.AppEntry
import dev.xross.launcher.data.XrossSettings
import dev.xross.launcher.launch.EmulatorRegistry
import java.util.Locale
import dev.xross.launcher.model.FolderNames

/**
 * Hiding and folders for Android apps (pure, from [XrossSettings]).
 *
 *  - Hidden apps (by package) and, optionally, every system app leave Apps and Games ▸ Android
 *    Games; Settings ▸ Apps ▸ Hidden apps brings them back.
 *  - Apps can be put in folders inside Apps. [PRESETS] are always offered so no typing is needed;
 *    any other name is typed on the gamepad keyboard. A folder exists while it holds an app.
 *  - Known emulator packages ([EmulatorRegistry]) are suggested for the Emulators folder.
 */
object AppFolders {
    const val EMULATORS = FolderNames.EMULATORS
    val PRESETS = FolderNames.PRESETS
    const val MAX_NAME = FolderNames.MAX_NAME

    /** Every package of every emulator Xross can launch. */
    // Built-in recipes only: a custom recipe may use a spoofed package (a benchmark app's name).
    val emulatorPackages: Set<String> by lazy { EmulatorRegistry.builtIn.flatMapTo(HashSet()) { it.packages } }

    fun isHidden(app: AppEntry, s: XrossSettings): Boolean =
        app.packageName in s.hiddenApps || (s.hideSystemApps && app.isSystem)

    fun visible(apps: List<AppEntry>, s: XrossSettings): List<AppEntry> = apps.filterNot { isHidden(it, s) }

    /** Folder names in display order: presets first (in their order), then custom names A–Z. */
    fun folderNames(assignments: Collection<String>): List<String> {
        val used = assignments.toSet()
        val presets = PRESETS.filter { it in used }
        val custom = used.filterNot { it in PRESETS }.sortedBy { it.lowercase(Locale.ROOT) }
        return presets + custom
    }

    /** Names offered in Options ▸ Move to folder: the presets plus custom folders already in use. */
    fun offeredNames(s: XrossSettings): List<String> = (PRESETS + folderNames(s.appFolders.values)).distinct()

    fun suggestedFolder(packageName: String): String? = EMULATORS.takeIf { packageName in emulatorPackages }

    /** Visible, installed emulators not in any folder yet: what "Group emulators" would move. */
    fun ungroupedEmulators(apps: List<AppEntry>, s: XrossSettings): List<AppEntry> =
        visible(apps, s).filter { !it.isGame && it.packageName in emulatorPackages && it.packageName !in s.appFolders }
            .distinctBy { it.packageName }

    /** A folder name as typed, cleaned up; null when nothing is left ([FolderNames.normalize]). */
    fun normalizeName(raw: String): String? = FolderNames.normalize(raw)
}
