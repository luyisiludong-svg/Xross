package dev.xross.launcher.state

import dev.xross.launcher.data.AppEntry
import dev.xross.launcher.data.XrossSettings
import dev.xross.launcher.launch.EmulatorRegistry
import java.util.Locale

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
    const val EMULATORS = "Emulators"
    val PRESETS = listOf(EMULATORS, "Streaming", "Tools")
    const val MAX_NAME = 24

    /** Every package of every emulator Xross can launch. */
    val emulatorPackages: Set<String> by lazy { EmulatorRegistry.all.flatMapTo(HashSet()) { it.packages } }

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

    /**
     * A folder name as typed: trimmed, inner whitespace collapsed, at most [MAX_NAME] characters.
     * The gamepad keyboard types capitals, so all-caps words become title case ("MY TOOLS" →
     * "My Tools") except short ones that read as acronyms ("TV", "NES"), and a name equal to a
     * preset in another case becomes that preset. Null when nothing is left.
     */
    fun normalizeName(raw: String): String? {
        val collapsed = raw.trim().replace(Regex("\\s+"), " ").take(MAX_NAME).trim()
        if (collapsed.isEmpty()) return null
        PRESETS.firstOrNull { it.equals(collapsed, ignoreCase = true) }?.let { return it }
        return collapsed.split(' ').joinToString(" ") { word ->
            val allCaps = word == word.uppercase(Locale.ROOT) && word.any { it.isLetter() }
            // Short all-caps words are usually acronyms (TV, NES, PS2) and stay as typed.
            val titled = allCaps && (word.length >= 4 || word.uppercase(Locale.ROOT) in SHORT_WORDS)
            if (titled) word.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) } else word
        }
    }

    private val SHORT_WORDS = setOf("A", "AN", "AND", "FOR", "IN", "MY", "OF", "ON", "OR", "THE", "TO", "BY", "ALL", "NEW", "OLD", "APP", "FUN")
}
