package dev.xross.launcher.media

import dev.xross.launcher.config.AtomicFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** A playlist: its name (the file's name) and the songs' file paths, in order. */
data class Playlist(val name: String, val paths: List<String>)

/**
 * Plain `.m3u8` text: one file path per line, `#` lines are comments. Standard enough that the
 * files open in other players and can be written by hand or copied from a PC. Relative entries
 * are read against the playlist's own folder; Windows separators are accepted.
 */
object M3u {
    const val HEADER = "#EXTM3U"

    fun encode(paths: List<String>): String = buildString {
        append(HEADER).append('\n')
        for (p in paths) append(p).append('\n')
    }

    fun decode(text: String, baseDir: File?): List<String> = text.lineSequence()
        .map { it.trim().removePrefix("\uFEFF") }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { it.replace('\\', '/').removePrefix("file://") }
        .map { if (it.startsWith("/") || baseDir == null) it else File(baseDir, it).normalize().path }
        .distinct()
        .toList()
}

/**
 * Playlists as `Xross/playlists/<name>.m3u8` files, so they travel with the Xross folder (and
 * survive a reinstall). Every change rewrites the one file atomically and republishes
 * [playlists]; [reload] picks up files changed outside Xross.
 */
class PlaylistStore(private val dir: () -> File) {
    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    suspend fun reload() = withContext(Dispatchers.IO) {
        val folder = dir()
        _playlists.value = folder.listFiles { f -> f.isFile && f.extension.lowercase() in EXTENSIONS }.orEmpty()
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.nameWithoutExtension })
            .map { f -> Playlist(f.nameWithoutExtension, runCatching { M3u.decode(f.readText(), folder) }.getOrDefault(emptyList())) }
    }

    fun find(name: String): Playlist? = _playlists.value.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /**
     * Adds [paths] (those not in it yet) to playlist [name], creating it when missing. Returns how
     * many were added, or null when the file couldn't be written.
     */
    suspend fun add(name: String, paths: List<String>): Int? {
        val current = find(name)
        val fresh = paths.filter { it.isNotEmpty() && current?.paths?.contains(it) != true }.distinct()
        if (current != null && fresh.isEmpty()) return 0
        return if (write(current?.name ?: name, current?.paths.orEmpty() + fresh)) fresh.size else null
    }

    suspend fun remove(name: String, path: String): Boolean {
        val p = find(name) ?: return false
        return write(p.name, p.paths - path)
    }

    /** Moves [path] one place towards the start ([delta] -1) or the end (+1), past songs that are missing. */
    suspend fun move(name: String, path: String, delta: Int, present: (String) -> Boolean): Boolean {
        val p = find(name) ?: return false
        val from = p.paths.indexOf(path).takeIf { it >= 0 } ?: return false
        var to = from + delta
        while (to in p.paths.indices && !present(p.paths[to])) to += delta
        if (to !in p.paths.indices) return false
        val next = p.paths.toMutableList().apply { add(to, removeAt(from)) }
        return write(p.name, next)
    }

    suspend fun delete(name: String): Boolean = withContext(Dispatchers.IO) {
        val p = find(name) ?: return@withContext false
        val gone = EXTENSIONS.map { File(dir(), "${p.name}.$it") }.filter { it.exists() }.all { it.delete() }
        reload()
        gone
    }

    private suspend fun write(name: String, paths: List<String>): Boolean = withContext(Dispatchers.IO) {
        val folder = dir()
        val ok = try {
            if (!folder.isDirectory && !folder.mkdirs()) throw IOException("no folder")
            // An existing `.m3u` keeps its name; new playlists are `.m3u8` (UTF-8).
            val target = File(folder, "$name.m3u").takeIf { it.isFile } ?: File(folder, "$name.m3u8")
            AtomicFiles.writeText(target, M3u.encode(paths))
            true
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
        reload()
        ok
    }

    companion object {
        const val FOLDER = "playlists"
        val EXTENSIONS = listOf("m3u8", "m3u")
        const val MAX_NAME = 40

        /** A name safe as a file name on any volume (FAT SD cards included), or null when nothing is left. */
        fun normalizeName(raw: String): String? = raw
            .replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), " ")
            .replace(Regex("\\s+"), " ")
            .trim().trim('.')
            .take(MAX_NAME).trim()
            .takeIf { it.isNotEmpty() }
    }
}
