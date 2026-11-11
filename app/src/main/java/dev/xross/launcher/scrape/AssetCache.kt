package dev.xross.launcher.scrape

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Kinds of downloaded art, each stored at most once per game. [folder] is the kind's folder in
 * `Xross/media/` (ES-DE's names where ES-DE has the kind); [fileStem] names it in the old private
 * cache, kept for the one-time migration.
 */
enum class ArtKind(val folder: String, val fileStem: String, val maxBytes: Long, val isVideo: Boolean = false) {
    BOX_3D("3dboxes", "box3d", 8L shl 20),
    BOX_2D("covers", "box2d", 8L shl 20),
    WHEEL("wheels", "wheel", 4L shl 20),
    SCREENSHOT("screenshots", "screenshot", 4L shl 20),
    /** Wide banner for the details screen background. */
    HERO("heroes", "hero", 8L shl 20),
    /** Portrait capsule (600×900). */
    GRID("capsules", "grid", 8L shl 20),
    /** Video snap. */
    VIDEO("snaps", "video", 40L shl 20, isVideo = true),
}

/**
 * Downloaded media on disk in the portable, ES-DE-compatible layout
 * `<media>/<kind>/<system>/<rom stem>.<ext>` (see [MediaNames]), for example
 * `Xross/media/covers/snes/Super Mario World (USA).png`. Callers still address art by game
 * UUID; [setLibrary] keeps the UUID → file name index current. Images and videos never enter the
 * database; a row only records that scraping happened.
 *
 * - [root] is read on every access, so art follows the Xross folder when it moves.
 * - Writes are atomic (temp file + rename), so a cancelled download never leaves a half image.
 *   A `.nomedia` marker is ensured in [root] before anything is written.
 * - UUIDs and extensions are validated, and names are cleaned, so a hostile URL or API value
 *   can't steer a path.
 * - Files Xross can't attribute (media copied in from ES-DE, say) are never deleted: [prune]
 *   only removes art of games that left the library, [clear] empties the kind folders.
 * - Art in the old private cache ([legacyRoot], `<uuid>/<kind>.<ext>`) is still found until
 *   [migrateLegacy] has moved it.
 * - [version] ticks on every change so the UI knows to reload art.
 */
class AssetCache(
    private val root: () -> File,
    private val legacyRoot: File? = null,
) {
    constructor(root: File, legacyRoot: File? = null) : this({ root }, legacyRoot)

    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()

    private val lock = Any()
    private var games: Map<String, MediaGame> = emptyMap()
    @Volatile private var slots: Map<String, MediaSlot> = emptyMap()

    /** Games that left the library since their art was last pruned. */
    private val departed = java.util.concurrent.ConcurrentHashMap<String, MediaSlot>()

    val mediaRoot: File get() = root()

    // region Library index

    /** Updates the UUID → file name index from the whole library. */
    fun setLibrary(library: List<dev.xross.launcher.data.GameEntity>) = synchronized(lock) {
        val next = library.filter { UUID.matches(it.uuid) }
            .associate { it.uuid to MediaGame(it.id, it.uuid, it.platformId, it.fileName) }
        for ((uuid, slot) in slots) if (uuid !in next) departed[uuid] = slot
        for (uuid in next.keys) departed.remove(uuid)
        games = next
        slots = MediaNames.assign(next.values)
    }

    /** Adds one game (a scrape can start before the library index has caught up). */
    fun register(game: dev.xross.launcher.data.GameEntity) = synchronized(lock) {
        if (!UUID.matches(game.uuid)) return
        val entry = MediaGame(game.id, game.uuid, game.platformId, game.fileName)
        if (games[game.uuid] == entry) return
        games = games + (game.uuid to entry)
        departed.remove(game.uuid)
        slots = MediaNames.assign(games.values)
    }

    /** Where [uuid]'s [kind] goes (the first of its names), or null for unknown games. */
    fun target(uuid: String, kind: ArtKind, ext: String): File? {
        val slot = slots[uuid] ?: return null
        return File(kindDir(kind, slot), "${slot.name}.$ext")
    }

    private fun kindDir(kind: ArtKind, slot: MediaSlot) = File(File(root(), kind.folder), slot.systemId)

    // endregion

    /** The stored file for [kind], or null. Cheap: a few `stat` calls at most. */
    fun file(uuid: String, kind: ArtKind): File? {
        if (!UUID.matches(uuid)) return null
        slots[uuid]?.let { slot ->
            val dir = kindDir(kind, slot)
            for (name in slot.names) for (ext in EXTENSIONS) {
                val f = File(dir, "$name.$ext")
                if (f.isFile) return f
            }
        }
        return legacyFile(uuid, kind)
    }

    private fun legacyFile(uuid: String, kind: ArtKind): File? {
        val dir = legacyRoot?.let { File(it, uuid) } ?: return null
        for (ext in EXTENSIONS) {
            val f = File(dir, "${kind.fileStem}.$ext")
            if (f.isFile) return f
        }
        return null
    }

    fun has(uuid: String, kind: ArtKind): Boolean = file(uuid, kind) != null

    /**
     * Stores media for [uuid]/[kind] by letting [write] fill a temp file, then renaming it into
     * place (replacing any earlier file of that kind, whatever its name variant or extension).
     */
    suspend fun store(uuid: String, kind: ArtKind, ext: String, write: suspend (File) -> Unit): File =
        withContext(Dispatchers.IO) {
            require(UUID.matches(uuid)) { "Not a UUID" }
            val cleanExt = ext.lowercase(Locale.ROOT)
            require(cleanExt in EXTENSIONS) { "Unsupported media type .$cleanExt" }
            val slot = slots[uuid] ?: throw IllegalStateException("Unknown game $uuid")
            ensureNomedia()
            val dir = kindDir(kind, slot).apply { mkdirs() }
            val tmp = File.createTempFile(".xross-", ".part", dir)
            try {
                write(tmp)
                deleteVariants(dir, slot, kind, uuid)
                val target = File(dir, "${slot.name}.$cleanExt")
                if (!tmp.renameTo(target)) throw java.io.IOException("Couldn't move media into place")
                _version.value++
                target
            } finally {
                tmp.delete()
            }
        }

    private fun deleteVariants(dir: File, slot: MediaSlot, kind: ArtKind, uuid: String) {
        for (name in slot.names) for (old in EXTENSIONS) File(dir, "$name.$old").delete()
        legacyFile(uuid, kind)?.delete()
    }

    /** The media folder never exists without its `.nomedia`, even if someone deleted it. */
    private fun ensureNomedia() {
        val media = root()
        if (!media.isDirectory) media.mkdirs()
        val marker = File(media, ".nomedia")
        if (!marker.exists()) runCatching { marker.createNewFile() }
    }

    /** A scratch file inside the media folder (same volume, so [adopt] is a cheap rename). */
    suspend fun tempFile(): File = withContext(Dispatchers.IO) {
        ensureNomedia()
        val dir = File(root(), ".tmp").apply { mkdirs() }
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

    /**
     * Deletes the art of games that left the library and aren't in [validUuids]; returns bytes
     * freed. Files that no known game ever owned are kept (they may be media copied in by hand).
     */
    suspend fun prune(validUuids: Set<String>): Long = withContext(Dispatchers.IO) {
        var freed = 0L
        for ((uuid, slot) in departed.entries.toList()) {
            if (uuid in validUuids) continue
            freed += deleteAll(uuid, slot)
            departed.remove(uuid)
        }
        // The old private cache only ever held Xross's own downloads: folders of games no longer
        // in the library can go, once the library index is loaded.
        if (slots.isNotEmpty()) {
            legacyRoot?.listFiles()?.forEach { dir ->
                if (dir.isDirectory && dir.name !in slots && dir.name !in validUuids) {
                    freed += dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
                    dir.deleteRecursively()
                }
            }
        }
        if (freed > 0) _version.value++
        freed
    }

    private fun deleteAll(uuid: String, slot: MediaSlot): Long {
        var freed = 0L
        for (kind in ArtKind.entries) {
            val dir = kindDir(kind, slot)
            for (name in slot.names) for (ext in EXTENSIONS) {
                val f = File(dir, "$name.$ext")
                if (f.isFile) {
                    freed += f.length()
                    f.delete()
                }
            }
        }
        legacyRoot?.let { File(it, uuid) }?.takeIf { it.isDirectory }?.let { dir ->
            freed += dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
            dir.deleteRecursively()
        }
        return freed
    }

    suspend fun remove(uuid: String) = withContext(Dispatchers.IO) {
        val slot = slots[uuid] ?: departed[uuid] ?: return@withContext
        if (deleteAll(uuid, slot) > 0) _version.value++
    }

    /** Empties every kind folder (and the old private cache); returns bytes freed. */
    suspend fun clear(): Long = withContext(Dispatchers.IO) {
        val size = sizeBytesBlocking()
        for (dir in managedDirs()) dir.deleteRecursively()
        legacyRoot?.deleteRecursively()
        _version.value++
        size
    }

    suspend fun sizeBytes(): Long = withContext(Dispatchers.IO) { sizeBytesBlocking() }

    private fun managedDirs(): List<File> = ArtKind.entries.map { File(root(), it.folder) } + File(root(), ".tmp")

    private fun sizeBytesBlocking(): Long =
        (managedDirs() + listOfNotNull(legacyRoot)).sumOf { dir -> dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() } }

    // region Moving art

    data class MoveReport(val moved: Int, val skipped: Int, val remaining: Int)

    /**
     * One-time move of the old private cache (`<legacyRoot>/<uuid>/<kind>.<ext>`) into the
     * portable layout. Runs in the background; art stays visible throughout, since [file] also
     * looks in the old cache. A file whose new place is already taken (media copied in by hand)
     * is dropped in favour of it. Folders of games that aren't in the library are left for
     * [prune]; nothing is done until the library index is loaded.
     */
    suspend fun migrateLegacy(): MoveReport = withContext(Dispatchers.IO) {
        val legacy = legacyRoot ?: return@withContext MoveReport(0, 0, 0)
        val dirs = legacy.listFiles()?.filter { it.isDirectory } ?: return@withContext MoveReport(0, 0, 0)
        if (slots.isEmpty()) return@withContext MoveReport(0, 0, dirs.size)
        var moved = 0
        var skipped = 0
        var remaining = 0
        for (dir in dirs) {
            ensureActive()
            if (dir.name == ".tmp") {
                dir.deleteRecursively()
                continue
            }
            val slot = slots[dir.name]
            if (slot == null) {
                remaining++
                continue
            }
            for (file in dir.listFiles().orEmpty()) {
                val kind = ArtKind.entries.firstOrNull { it.fileStem == file.nameWithoutExtension }
                val ext = file.extension.lowercase(Locale.ROOT)
                if (kind == null || ext !in EXTENSIONS || !file.isFile) {
                    file.delete() // stray temp file
                    continue
                }
                val existing = kindDir(kind, slot).let { d -> slot.names.flatMap { n -> EXTENSIONS.map { File(d, "$n.$it") } } }
                if (existing.any { it.isFile }) {
                    file.delete()
                    skipped++
                    continue
                }
                ensureNomedia()
                dev.xross.launcher.config.AtomicFiles.copy(file, File(kindDir(kind, slot), "${slot.name}.$ext"))
                file.delete()
                moved++
            }
            if (dir.list().isNullOrEmpty()) dir.delete()
        }
        if (legacy.list().isNullOrEmpty()) legacy.delete()
        if (moved > 0 || skipped > 0) _version.value++
        MoveReport(moved, skipped, remaining)
    }

    /**
     * Moves art from a previous media folder (the Xross folder moved to another drive) into
     * [root]. Files whose place is already taken stay where they are.
     */
    suspend fun relocateFrom(previous: File): MoveReport = withContext(Dispatchers.IO) {
        val current = root()
        if (previous.canonicalPath == current.canonicalPath || !previous.isDirectory) return@withContext MoveReport(0, 0, 0)
        var moved = 0
        var skipped = 0
        for (kind in ArtKind.entries) {
            val from = File(previous, kind.folder)
            if (!from.isDirectory) continue
            for (file in from.walkTopDown().filter { it.isFile && !it.name.startsWith(".") }) {
                ensureActive()
                val target = File(File(current, kind.folder), file.relativeTo(from).path)
                if (target.exists()) {
                    skipped++
                    continue
                }
                ensureNomedia()
                dev.xross.launcher.config.AtomicFiles.copy(file, target)
                file.delete()
                moved++
            }
        }
        if (moved > 0) _version.value++
        MoveReport(moved, skipped, 0)
    }

    // endregion

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
