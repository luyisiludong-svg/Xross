package dev.wave.launcher.storage

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.DocumentsContract
import java.io.File

/** A mounted storage volume: internal storage, a microSD card or a USB-OTG drive. */
data class VolumeInfo(
    val root: File,
    val label: String,
    val removable: Boolean,
    val primary: Boolean,
    val uuid: String?,
)

/**
 * Resolves mounted volumes (internal, microSD, USB-OTG) and converts system folder-picker
 * results into real filesystem paths for the fast scanner.
 */
class StorageVolumes(private val context: Context) {

    /**
     * Every currently mounted volume. `StorageManager.getStorageVolumes()` is the primary source
     * because it also reports USB-OTG drives; roots implied by the app's external files dirs are
     * merged in for devices where a card is only visible that way.
     */
    fun volumes(): List<VolumeInfo> {
        val manager = context.getSystemService(StorageManager::class.java)
        val fromManager = manager?.storageVolumes.orEmpty()
            .filter { it.state == Environment.MEDIA_MOUNTED || it.state == Environment.MEDIA_MOUNTED_READ_ONLY }
            .mapNotNull { volume ->
                directoryOf(volume)?.let { dir ->
                    VolumeInfo(
                        root = dir,
                        label = volume.getDescription(context)?.takeIf { it.isNotBlank() } ?: dir.name,
                        removable = volume.isRemovable,
                        primary = volume.isPrimary,
                        uuid = volume.uuid,
                    )
                }
            }
        return mergeVolumes(fromManager, appDirRoots())
    }

    /** Roots of every currently mounted volume, e.g. /storage/emulated/0, /storage/1A2B-3C4D. */
    fun mountedRoots(): List<File> = volumes().map { it.root }

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

    private fun appDirRoots(): List<File> =
        context.getExternalFilesDirs(null).filterNotNull().mapNotNull { appDir ->
            val path = appDir.absolutePath
            val idx = path.indexOf("/Android/data/")
            if (idx > 0) File(path.substring(0, idx)) else null
        }

    /** `StorageVolume.getDirectory()` is API 30+; older releases expose the same path via a hidden getter. */
    private fun directoryOf(volume: StorageVolume): File? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            volume.directory
        } else {
            runCatching { StorageVolume::class.java.getMethod("getPathFile").invoke(volume) as? File }.getOrNull()
        }

    companion object {
        private val CONVENTIONAL_ROOTS = setOf("roms", "games", "emulation", "retro")
        private const val PRIMARY_ROOT = "/storage/emulated/0"
        private val VOLUME_ROOT = Regex("""^(/storage/emulated/\d+|/storage/[^/]+|/mnt/media_rw/[^/]+)(/.*)?$""")

        /** Manager-reported volumes first, then any app-dir root they don't already cover. */
        fun mergeVolumes(fromManager: List<VolumeInfo>, appDirRoots: List<File>): List<VolumeInfo> {
            val known = fromManager.mapTo(HashSet()) { it.root.path.trimEnd('/') }
            val extras = appDirRoots.distinctBy { it.path.trimEnd('/') }
                .filter { it.path.trimEnd('/') !in known }
                .map { root ->
                    val isPrimary = root.path.contains("/emulated/")
                    VolumeInfo(
                        root = root,
                        label = if (isPrimary) "Internal storage" else root.name,
                        removable = !isPrimary,
                        primary = isPrimary,
                        uuid = root.name.takeUnless { isPrimary },
                    )
                }
            return fromManager.distinctBy { it.root.path.trimEnd('/') } + extras
        }

        /** The volume root a path lives on, from Android's mount layout; null if it isn't shared storage. */
        fun volumeRootOf(path: String): String? = VOLUME_ROOT.matchEntire(path)?.groupValues?.get(1)

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
