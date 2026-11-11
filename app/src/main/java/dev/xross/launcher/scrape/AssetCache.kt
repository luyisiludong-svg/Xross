package dev.xross.launcher.scrape

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** Kinds of downloaded art, each stored at most once per game. */
enum class ArtKind(val fileStem: String, val maxBytes: Long, val isVideo: Boolean = false) {
    BOX_3D("box3d", 8L shl 20),
    BOX_2D("box2d", 8L shl 20),
    WHEEL("wheel", 4L shl 20),
    SCREENSHOT("screenshot", 4L shl 20),
    /** Wide banner for the details screen background. */
    HERO("hero", 8L shl 20),
    /** Portrait capsule (600×900). */
    GRID("grid", 8L shl 20),
    VIDEO("video", 40L shl 20, isVideo = true),
}

/**
 * Downloaded media on disk, keyed by game UUID: `<root>/<uuid>/<kind>.<ext>`. Images and videos
 * never enter the database; a row only records that scraping happened.
 *
 * - Writes are atomic (temp file + rename), so a cancelled download never leaves a half image.
 * - UUIDs and extensions are validated, so a hostile URL or API value can't steer a path.
 * - [prune] drops folders of games that no longer exist; [clear] wipes everything.
 * - [version] ticks on every change so the UI knows to reload art.
 */
class AssetCache(private val root: File) {

    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()

    /** The stored file for [kind], or null. Cheap: one directory listing at most. */
    fun file(uuid: String, kind: ArtKind): File? {
        if (!UUID.matches(uuid)) return null
        val dir = File(root, uuid)
        for (ext in EXTENSIONS) {
            val f = File(dir, "${kind.fileStem}.$ext")
            if (f.isFile) return f
        }
        return null
    }

    fun has(uuid: String, kind: ArtKind): Boolean = file(uuid, kind) != null

    /**
     * Stores media for [uuid]/[kind] by letting [write] fill a temp file, then renaming it into
     * place (replacing any earlier file of that kind, whatever its extension).
     */
    suspend fun store(uuid: String, kind: ArtKind, ext: String, write: suspend (File) -> Unit): File =
        withContext(Dispatchers.IO) {
            require(UUID.matches(uuid)) { "Not a UUID" }
            val cleanExt = ext.lowercase(Locale.ROOT)
            require(cleanExt in EXTENSIONS) { "Unsupported media type .$cleanExt" }
            val dir = File(root, uuid).apply { mkdirs() }
            val tmp = File.createTempFile("${kind.fileStem}.", ".part", dir)
            try {
                write(tmp)
                for (old in EXTENSIONS) File(dir, "${kind.fileStem}.$old").delete()
                val target = File(dir, "${kind.fileStem}.$cleanExt")
                if (!tmp.renameTo(target)) throw java.io.IOException("Couldn't move media into place")
                _version.value++
                target
            } finally {
                tmp.delete()
            }
        }

    /** A scratch file inside the cache (same volume, so [adopt] is a cheap rename). */
    suspend fun tempFile(): File = withContext(Dispatchers.IO) {
        val dir = File(root, ".tmp").apply { mkdirs() }
        File.createTempFile("download-", ".part", dir)
    }

    /** Moves an already-downloaded [source] into place as [uuid]/[kind]; [source] is consumed. */
    suspend fun adopt(uuid: String, kind: ArtKind, ext: String, source: File): File =
        store(uuid, kind, ext) { tmp ->
            if (!source.renameTo(tmp)) {
                source.copyTo(tmp, overwrite = true)
                source.delete()
            }
        }

    /** Deletes the folders of games not in [validUuids]; returns bytes freed. */
    suspend fun prune(validUuids: Set<String>): Long = withContext(Dispatchers.IO) {
        var freed = 0L
        root.listFiles()?.forEach { dir ->
            if (dir.isDirectory && dir.name !in validUuids && dir.name != ".tmp") {
                freed += dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
                dir.deleteRecursively()
            }
        }
        if (freed > 0) _version.value++
        freed
    }

    suspend fun remove(uuid: String) = withContext(Dispatchers.IO) {
        if (UUID.matches(uuid) && File(root, uuid).deleteRecursively()) _version.value++
    }

    suspend fun clear(): Long = withContext(Dispatchers.IO) {
        val size = sizeBytesBlocking()
        root.listFiles()?.forEach { it.deleteRecursively() }
        _version.value++
        size
    }

    suspend fun sizeBytes(): Long = withContext(Dispatchers.IO) { sizeBytesBlocking() }

    private fun sizeBytesBlocking(): Long = root.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    companion object {
        val EXTENSIONS = listOf("png", "jpg", "webp", "mp4")

        /**
         * The real type of a downloaded file from its first bytes, whatever the server claimed;
         * null when it isn't PNG, JPEG, WebP or MP4 (an error page, a script…).
         */
        fun sniff(file: File): String? {
            val head = ByteArray(12)
            val n = file.inputStream().use { it.read(head) }
            if (n < 12) return null
            fun at(i: Int, vararg bytes: Int) = bytes.withIndex().all { (j, b) -> head[i + j] == b.toByte() }
            return when {
                at(0, 0x89, 0x50, 0x4E, 0x47) -> "png"
                at(0, 0xFF, 0xD8, 0xFF) -> "jpg"
                at(0, 0x52, 0x49, 0x46, 0x46) && at(8, 0x57, 0x45, 0x42, 0x50) -> "webp"
                at(4, 0x66, 0x74, 0x79, 0x70) -> "mp4"
                else -> null
            }
        }
        private val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        /** File extension for a media URL / content type, or null when it isn't an allowed type. */
        fun extensionFor(url: String, contentType: String?, hint: String? = null): String? {
            val fromHint = hint?.lowercase(Locale.ROOT)?.removePrefix(".")?.let { if (it == "jpeg") "jpg" else it }
            if (fromHint in EXTENSIONS) return fromHint
            when (contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)) {
                "image/png" -> return "png"
                "image/jpeg", "image/jpg" -> return "jpg"
                "image/webp" -> return "webp"
                "video/mp4" -> return "mp4"
            }
            val path = url.substringBefore('?').substringAfterLast('/')
            val ext = path.substringAfterLast('.', "").lowercase(Locale.ROOT).let { if (it == "jpeg") "jpg" else it }
            return ext.takeIf { it in EXTENSIONS }
        }
    }
}
