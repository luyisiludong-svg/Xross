package dev.xross.launcher.storage

import java.io.File

/**
 * Scaffolds a ROM library: one folder per system under the base ROMs folder, named by the
 * system's id. The ids are ES-DE's folder names (`snes`, `psx`, `n3ds`, …), so a library laid out
 * for ES-DE works unchanged, and every folder is recognised by [PlatformRegistry.forFolder].
 */
object SystemFolders {
    data class Result(
        val base: File,
        /** Folders made by this call. */
        val created: List<String>,
        /** Folders that were already there. */
        val existing: List<String>,
        /** Folders that couldn't be made (read-only or unplugged drive). */
        val failed: List<String>,
    ) {
        val ok: Boolean get() = failed.isEmpty()
    }

    fun folderNames(platforms: List<Platform> = PlatformRegistry.builtIn): List<String> = platforms.map { it.id }

    /** Creates any missing system folders in [base] (and [base] itself). Existing folders and files are left alone. */
    fun create(base: File, platforms: List<Platform> = PlatformRegistry.builtIn): Result {
        val created = ArrayList<String>()
        val existing = ArrayList<String>()
        val failed = ArrayList<String>()
        val baseReady = base.isDirectory || base.mkdirs() || base.isDirectory
        for (name in folderNames(platforms)) {
            val dir = File(base, name)
            when {
                dir.isDirectory -> existing += name
                baseReady && (dir.mkdir() || dir.isDirectory) -> created += name
                else -> failed += name
            }
        }
        return Result(base, created, existing, failed)
    }
}
