package dev.wave.launcher.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/**
 * Resolves mounted volumes (internal, microSD, USB-OTG) and converts system folder-picker
 * results into real filesystem paths for the fast scanner.
 */
class StorageVolumes(private val context: Context) {

    /** Roots of every currently mounted volume, e.g. /storage/emulated/0, /storage/1A2B-3C4D. */
    fun mountedRoots(): List<File> =
        context.getExternalFilesDirs(null).filterNotNull().mapNotNull { appDir ->
            val path = appDir.absolutePath
            val idx = path.indexOf("/Android/data/")
            if (idx > 0) File(path.substring(0, idx)) else null
        }.distinctBy { it.absolutePath }

    /** Conventional ROM folders found on any mounted volume (case-insensitive). */
    fun discoverRomFolders(): List<File> = mountedRoots().flatMap { root ->
        root.listFiles().orEmpty().filter {
            it.isDirectory && normalizeFolderName(it.name) in CONVENTIONAL_ROOTS
        }
    }

    fun treeUriToPath(uri: Uri): String? {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
        return docIdToPath(docId, mountedRoots().map { it.absolutePath })
    }

    companion object {
        private val CONVENTIONAL_ROOTS = setOf("roms", "games", "emulation", "retro")
        private const val PRIMARY_ROOT = "/storage/emulated/0"

        /**
         * ExternalStorageProvider document IDs look like "primary:ROMs/snes" or
         * "1A2B-3C4D:ROMs". Pure so it can be unit-tested.
         */
        fun docIdToPath(docId: String, mountedRoots: List<String>): String? {
            val volume = docId.substringBefore(':', missingDelimiterValue = "")
            if (volume.isEmpty()) return null
            val relative = docId.substringAfter(':').trim('/')
            val base = if (volume.equals("primary", ignoreCase = true)) {
                mountedRoots.firstOrNull { it.endsWith("/emulated/0") } ?: PRIMARY_ROOT
            } else {
                mountedRoots.firstOrNull { it.endsWith("/$volume") } ?: "/storage/$volume"
            }
            return if (relative.isEmpty()) base else "$base/$relative"
        }
    }
}
