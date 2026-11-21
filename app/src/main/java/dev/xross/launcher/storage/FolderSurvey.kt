package dev.xross.launcher.storage

import dev.xross.launcher.model.PlatformRegistry
import java.io.File

/** A folder and the file types in it, for Settings ▸ Emulators ▸ Add system (0.2.2.0 § 1b). */
data class SurveyedFolder(val path: String, val name: String, val underRoot: Boolean, val extensions: Map<String, Int>)

/** Blocking file I/O; call off the main thread. */
object FolderSurvey {
    private const val MAX_FILES = 2_000

    /** Folders in the ROM [roots] that no system claims, with what they hold; empty ones left out. */
    fun unclaimed(roots: Collection<File>): List<SurveyedFolder> = roots.asSequence()
        .filter { it.isDirectory && PlatformRegistry.forFolder(it.name) == null }
        .flatMap { root -> root.listFiles().orEmpty().asSequence() }
        .filter { it.isDirectory && !it.name.startsWith(".") && PlatformRegistry.forFolder(it.name) == null }
        .map { survey(it, underRoot = true) }
        .filter { it.extensions.isNotEmpty() }
        .distinctBy { it.path }
        .sortedBy { it.name.lowercase() }
        .toList()

    /** The file types in [dir] and its subfolders (up to a few thousand files), by count. */
    fun survey(dir: File, underRoot: Boolean): SurveyedFolder {
        val counts = HashMap<String, Int>()
        var seen = 0
        dir.walkTopDown().maxDepth(3).onEnter { !it.name.startsWith(".") || it == dir }.forEach { f ->
            if (seen >= MAX_FILES || !f.isFile || f.name.startsWith(".")) return@forEach
            seen++
            val ext = f.extension.lowercase()
            if (ext.isNotEmpty()) counts[ext] = (counts[ext] ?: 0) + 1
        }
        return SurveyedFolder(dir.absolutePath, dir.name, underRoot, counts)
    }
}
