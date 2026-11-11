package dev.xross.launcher.state

import dev.xross.launcher.storage.StorageVolumes

/**
 * Decides which ROM folders make up the library.
 *
 * Auto-detected folders are remembered: when a card or USB drive is unplugged its `ROMs` folder
 * can no longer be discovered, but it must stay configured — otherwise the next rescan would
 * treat its games as belonging to no folder and delete them (favorites and playtime included).
 * A remembered folder is only forgotten when its volume is mounted and the folder is gone.
 */
object LibraryFolders {
    data class Plan(val folders: List<FolderEntry>, val knownAuto: List<String>)

    fun plan(
        custom: List<String>,
        discovered: List<String>,
        knownAuto: List<String>,
        mountedRoots: List<String>,
        autoDetect: Boolean,
    ): Plan {
        val mounted = mountedRoots.mapTo(HashSet()) { it.trimEnd('/') }
        fun connected(path: String) = StorageVolumes.volumeRootOf(path)?.let { it in mounted } ?: true

        val customEntries = custom.map { FolderEntry(it, autoDetected = false, connected = connected(it)) }
        if (!autoDetect) return Plan(customEntries, knownAuto)

        // Offline volumes keep their remembered folders; mounted ones are re-derived from discovery.
        val remembered = knownAuto.filterNot { connected(it) }
        val auto = (discovered + remembered).distinctBy { it.trimEnd('/') }
        val folders = (customEntries + auto.map { FolderEntry(it, autoDetected = true, connected = connected(it)) })
            .distinctBy { it.path.trimEnd('/') }
        return Plan(folders, auto)
    }
}
