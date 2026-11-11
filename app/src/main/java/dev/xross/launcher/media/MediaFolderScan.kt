package dev.xross.launcher.media

import java.io.File
import java.util.Locale

/**
 * Helpers for custom Photo and Video folders: which paths belong to them, the MediaStore filter,
 * and a direct walk that also finds what MediaStore skips (folders with `.nomedia`, hidden
 * folders, drives it doesn't index).
 */
object MediaFolderScan {
    val PHOTO_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")
    val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mkv", "webm", "3gp", "mov", "avi", "ts")

    const val MAX_DEPTH = 8
    const val MAX_FILES = 20_000

    private fun base(folder: String) = folder.trimEnd('/')

    /** True when [path] is one of [folders] or inside one. */
    fun isUnder(path: String, folders: List<String>): Boolean = folders.any { f ->
        val b = base(f)
        b.isNotEmpty() && (path == b || path.startsWith("$b/"))
    }

    /** SQL for MediaStore's `_data` column: files inside any of [folders]. */
    fun selection(column: String, folders: List<String>): String =
        folders.joinToString(" OR ", prefix = "(", postfix = ")") { "$column LIKE ? ESCAPE '\\'" }

    fun selectionArgs(folders: List<String>): Array<String> =
        folders.map { escapeLike(base(it)) + "/%" }.toTypedArray()

    private fun escapeLike(s: String) = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    /**
     * Files with one of [extensions] in [folders] and their subfolders, nearest first, at most
     * [maxFiles]. Hidden files are included (a `.nomedia` folder is the point); symbolic links
     * to folders are not followed, so a link loop can't trap the walk.
     */
    fun walk(folders: List<String>, extensions: Set<String>, maxDepth: Int = MAX_DEPTH, maxFiles: Int = MAX_FILES): List<File> {
        val out = ArrayList<File>()
        val seen = HashSet<String>()
        for (folder in folders) {
            val root = File(base(folder))
            if (!root.isDirectory) continue
            val queue = ArrayDeque<Pair<File, Int>>()
            queue.addLast(root to 0)
            while (queue.isNotEmpty() && out.size < maxFiles) {
                val (dir, depth) = queue.removeFirst()
                val children = dir.listFiles() ?: continue
                for (child in children.sortedBy { it.name.lowercase(Locale.ROOT) }) {
                    if (out.size >= maxFiles) break
                    when {
                        child.isDirectory -> if (depth < maxDepth && !isSymlink(child)) queue.addLast(child to depth + 1)
                        child.isFile && child.extension.lowercase(Locale.ROOT) in extensions && child.name != ".nomedia" ->
                            if (seen.add(child.absolutePath)) out += child
                    }
                }
            }
        }
        return out
    }

    private fun isSymlink(file: File): Boolean = runCatching {
        java.nio.file.Files.isSymbolicLink(file.toPath())
    }.getOrDefault(false)

    /**
     * Stable negative id for a file MediaStore doesn't know (MediaStore ids are positive), from a
     * 64-bit FNV-1a hash of its path.
     */
    fun syntheticId(path: String): Long {
        var h = -0x340d631b7bdddcdbL // FNV offset basis
        for (b in path.toByteArray(Charsets.UTF_8)) {
            h = h xor (b.toLong() and 0xff)
            h *= 0x100000001b3L
        }
        val positive = h and Long.MAX_VALUE
        return -(if (positive == 0L) 1L else positive)
    }
}
