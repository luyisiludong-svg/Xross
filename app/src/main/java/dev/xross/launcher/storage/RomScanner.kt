package dev.xross.launcher.storage

import java.io.File

data class ScannedRom(
    val path: String,
    val platformId: String,
    val title: String,
    val fileName: String,
    val sizeBytes: Long,
    val lastModified: Long,
)

/**
 * Walks ROM roots with plain file I/O (fast path, needs All-files access on API 30+).
 *
 * A root is either a platform folder itself (`.../snes`) or a parent of platform folders
 * (`.../ROMs/snes`, `.../ROMs/psx`). Files referenced by a `.cue`, `.gdi` or `.m3u` in the same
 * folder are hidden so multi-track and multi-disc games show up once.
 */
class RomScanner(private val maxDepth: Int = 4) {

    fun scan(roots: Collection<File>): List<ScannedRom> {
        val out = LinkedHashMap<String, ScannedRom>()
        for (root in roots) {
            if (!root.isDirectory) continue
            val rootPlatform = PlatformRegistry.forFolder(root.name)
            if (rootPlatform != null) {
                scanPlatformDir(root, rootPlatform, 0, out)
                continue
            }
            for (child in root.listFiles().orEmpty()) {
                if (!child.isDirectory || child.isIgnored()) continue
                val platform = PlatformRegistry.forFolder(child.name) ?: continue
                scanPlatformDir(child, platform, 0, out)
            }
        }
        return out.values.toList()
    }

    private fun scanPlatformDir(
        dir: File,
        platform: Platform,
        depth: Int,
        out: MutableMap<String, ScannedRom>,
    ) {
        val entries = dir.listFiles() ?: return
        val files = entries.filter { it.isFile && !it.name.startsWith(".") }
        val hidden = if (platform.isDiscBased) referencedTracks(files) else emptySet()

        for (file in files) {
            val ext = file.extension.lowercase()
            if (ext !in platform.extensions) continue
            if (file.name.lowercase() in hidden) continue
            val path = file.absolutePath
            out.putIfAbsent(
                path,
                ScannedRom(
                    path = path,
                    platformId = platform.id,
                    title = cleanTitle(file.nameWithoutExtension),
                    fileName = file.name,
                    sizeBytes = file.length(),
                    lastModified = file.lastModified(),
                ),
            )
        }

        if (depth >= maxDepth) return
        for (sub in entries) {
            if (sub.isDirectory && !sub.isIgnored()) scanPlatformDir(sub, platform, depth + 1, out)
        }
    }

    /** Lower-cased names of files that a playlist/cue sheet in the same folder points at. */
    internal fun referencedTracks(files: List<File>): Set<String> {
        val hidden = HashSet<String>()
        for (sheet in files) {
            val lines = when (sheet.extension.lowercase()) {
                "cue", "gdi", "m3u" -> runCatching { sheet.readLines() }.getOrNull() ?: continue
                else -> continue
            }
            hidden += parseReferences(sheet.extension.lowercase(), lines)
        }
        return hidden
    }

    private fun File.isIgnored(): Boolean {
        val n = name.lowercase()
        return n.startsWith(".") || n in IGNORED_DIRS
    }

    companion object {
        private val IGNORED_DIRS = setOf(
            "media", "images", "videos", "manuals", "snaps", "boxart", "covers",
            "saves", "states", "screenshots", "bios", "system", "android",
        )
        private val CUE_FILE = Regex("""^\s*FILE\s+"?(.+?)"?\s+\S+\s*$""", RegexOption.IGNORE_CASE)
        private val TAGS = Regex("""\s*[(\[][^)\]]*[)\]]""")
        private val SPACES = Regex("""\s{2,}""")

        internal fun parseReferences(sheetExt: String, lines: List<String>): Set<String> {
            val names = when (sheetExt) {
                "cue" -> lines.mapNotNull { CUE_FILE.find(it)?.groupValues?.get(1) }
                // GDI: "<track> <lba> <type> <sector size> <filename> <offset>"
                "gdi" -> lines.drop(1).mapNotNull { line ->
                    line.trim().split(Regex("""\s+""")).getOrNull(4)?.trim('"')
                }
                "m3u" -> lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
                else -> emptyList()
            }
            return names.map { it.substringAfterLast('/').substringAfterLast('\\').lowercase() }.toSet()
        }

        /** "Chrono Trigger (USA) [!]" → "Chrono Trigger". Falls back to the raw name. */
        fun cleanTitle(raw: String): String {
            val cleaned = raw.replace('_', ' ').replace(TAGS, "").replace(SPACES, " ").trim()
            return cleaned.ifEmpty { raw }
        }
    }
}
