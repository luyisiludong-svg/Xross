package dev.xross.launcher.theme.pack

import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile

/** An unpacked, checked `.xtheme` waiting in a hidden staging folder to be installed. */
data class StagedTheme(
    /** Folder name it installs as in `Xross/themes/`. */
    val id: String,
    val name: String,
    /** The hidden staging folder (`Xross/themes/.import-…`), skipped by pack discovery. */
    val dir: File,
    /** A pack with [id] is already installed: ask before replacing it. */
    val exists: Boolean,
)

sealed interface ArchiveResult {
    data class Staged(val theme: StagedTheme) : ArchiveResult
    data class Rejected(val reason: String) : ArchiveResult
}

sealed interface InstallResult {
    data class Installed(val id: String) : InstallResult
    data class Failed(val reason: String) : InstallResult
}

/**
 * Imports a theme pack from a single-file `.xtheme` (a zip) into `Xross/themes/<id>/` (Roadmap
 * §4.3 and Appendix A.4). Packs are data only, so the archive is checked as a whole before
 * anything is written:
 *
 * - **Zip slip:** an entry whose path contains `..`, starts with `/` or `\`, has a drive letter
 *   or a NUL is rejected, and every written file is checked to stay inside the staging folder.
 * - **Known folders only:** `.json` (and `.md` / `.txt` notes), `wallpaper.png` / `.jpg` /
 *   `.webp`, `bgm.ogg` / `bgm.mp3` (at most [BgmRef.MAX_BYTES]), `boot.mp4` (at most [BootVideoRef.MAX_BYTES]), `shader.frag` and `postprocess.agsl` at the root; fonts, sounds (also `audio/`, as in the spec's manifest
 *   example), icons, shaders and wallpapers in their folders, with their own file types. Programs (`.apk`, `.dex`, `.so`, `.sh`, …) and anything else abort the import.
 *   macOS `__MACOSX/` and `.DS_Store` litter is skipped.
 * - **Size cap:** at most [maxBytes] (50 MB) uncompressed, checked against the declared sizes and
 *   again while unpacking (a header can lie), and at most [MAX_ENTRIES] files.
 * - **A valid pack:** `theme.json` at the root (or inside one top-level folder, as zipping a
 *   folder makes) that [check] accepts (`schemaVersion: 2`, a name).
 *
 * [stage] unpacks into a hidden `.import-*` folder in [themesDir]; [install] renames it into
 * place, replacing an installed pack only when asked. Blocking I/O: call off the main thread.
 */
class ThemeArchive(
    private val themesDir: () -> File,
    /** The pack's manifest, or why the staged folder isn't a pack. */
    private val check: (File) -> FolderCheck,
    private val maxBytes: Long = MAX_BYTES,
) {
    /** Checks and unpacks [archive] (a local copy of the picked file, named [fileName]). */
    fun stage(archive: File, fileName: String): ArchiveResult {
        val root = themesDir().apply { mkdirs() }
        clearStaging(root)
        return try {
            ZipFile(archive).use { z -> stageFrom(z, fileName, root) }
        } catch (e: ZipException) {
            // Android 14+ refuses traversal names itself ("Invalid zip entry path").
            val path = e.message?.contains("path", ignoreCase = true) == true
            ArchiveResult.Rejected(if (path) "$fileName: path with .. or an absolute path" else "$fileName isn't a zip archive")
        } catch (e: IOException) {
            ArchiveResult.Rejected("Couldn't read $fileName")
        }
    }

    private fun stageFrom(zip: ZipFile, fileName: String, root: File): ArchiveResult {
        val entries = zip.entries().toList()
        if (entries.size > MAX_ENTRIES) return ArchiveResult.Rejected("$fileName has more than $MAX_ENTRIES files")

        // Pass 1: names and declared sizes, before anything is written.
        val files = ArrayList<Pair<ZipEntry, List<String>>>()
        var declared = 0L
        for (entry in entries) {
            val raw = entry.name
            unsafe(raw)?.let { return ArchiveResult.Rejected("$fileName: $it (${raw.take(80)})") }
            val parts = raw.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
            if (entry.isDirectory || parts.isEmpty() || junk(parts)) continue
            if (entry.size > 0) declared += entry.size
            if (rootBgm(parts) && entry.size > BgmRef.MAX_BYTES) return ArchiveResult.Rejected(bgmTooLarge(fileName, parts))
            if (rootBootVideo(parts) && entry.size > BootVideoRef.MAX_BYTES) return ArchiveResult.Rejected(bootVideoTooLarge(fileName, parts))
            if (declared > maxBytes) return ArchiveResult.Rejected("$fileName is larger than ${maxBytes / MB} MB unpacked")
            files += entry to parts
        }
        if (files.isEmpty()) return ArchiveResult.Rejected("$fileName is empty")

        // One top-level folder holding theme.json (a zipped pack folder): unpack its contents.
        val atRoot = files.any { (_, p) -> p.size == 1 && p[0].equals(ThemePackLoader.MANIFEST, ignoreCase = true) }
        val top = files.first().second.first()
        val wrapped = !atRoot && files.all { (_, p) -> p.size > 1 && p[0] == top } &&
            files.any { (_, p) -> p.size == 2 && p[1].equals(ThemePackLoader.MANIFEST, ignoreCase = true) }
        val inPack = files.map { (e, p) -> e to if (wrapped) p.drop(1) else p }
        if (inPack.none { (_, p) -> p.size == 1 && p[0].equals(ThemePackLoader.MANIFEST, ignoreCase = true) }) {
            return ArchiveResult.Rejected("$fileName has no theme.json at its top level")
        }
        val seen = HashSet<String>()
        for ((_, p) in inPack) {
            disallowed(p)?.let { return ArchiveResult.Rejected("$fileName: $it") }
            if (!seen.add(p.joinToString("/").lowercase(Locale.ROOT))) {
                return ArchiveResult.Rejected("$fileName lists ${p.joinToString("/")} twice")
            }
        }

        // Pass 2: unpack into a hidden staging folder, counting the bytes actually inflated.
        val staging = File(root, STAGING_PREFIX + System.nanoTime().toString(36))
        if (!staging.mkdirs()) return ArchiveResult.Rejected("Can't write to ${root.absolutePath}")
        val stagingPath = staging.canonicalPath + File.separator
        var written = 0L
        try {
            for ((entry, p) in inPack) {
                val out = File(staging, p.joinToString(File.separator))
                if (!out.canonicalPath.startsWith(stagingPath)) return reject(staging, "$fileName: ${entry.name} leaves the pack")
                out.parentFile?.mkdirs()
                val bgm = rootBgm(p)
                val boot = rootBootVideo(p)
                var fileBytes = 0L
                zip.getInputStream(entry).use { input ->
                    out.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            written += n
                            fileBytes += n
                            if (written > maxBytes) return reject(staging, "$fileName is larger than ${maxBytes / MB} MB unpacked")
                            if (bgm && fileBytes > BgmRef.MAX_BYTES) return reject(staging, bgmTooLarge(fileName, p))
                            if (boot && fileBytes > BootVideoRef.MAX_BYTES) return reject(staging, bootVideoTooLarge(fileName, p))
                            output.write(buf, 0, n)
                        }
                    }
                }
            }
        } catch (e: IOException) {
            return reject(staging, "$fileName couldn't be unpacked (${e.message ?: "read error"})")
        }

        val manifest = when (val c = check(staging)) {
            is FolderCheck.Ok -> c.manifest
            is FolderCheck.Bad -> return reject(staging, c.reason)
        }
        val id = idFor(manifest.name, fileName, if (wrapped) top else null)
        return ArchiveResult.Staged(StagedTheme(id, manifest.name, staging, exists = File(root, id).exists()))
    }

    /**
     * Moves [staged] into `Xross/themes/<id>/`. An installed pack of that id is replaced only when
     * [replace] is true (the old folder is removed after the new one is in place).
     */
    fun install(staged: StagedTheme, replace: Boolean): InstallResult {
        val root = themesDir()
        if (!staged.dir.isDirectory) return InstallResult.Failed("The import expired; pick the file again")
        val target = File(root, staged.id)
        var old: File? = null
        if (target.exists()) {
            if (!replace) return InstallResult.Failed("${staged.id} is already installed")
            old = File(root, ".replaced-${staged.id}-${System.nanoTime().toString(36)}")
            if (!target.renameTo(old)) return InstallResult.Failed("Couldn't replace ${staged.id}")
        }
        if (!staged.dir.renameTo(target)) {
            old?.renameTo(target)
            return InstallResult.Failed("Couldn't move the pack into ${root.absolutePath}")
        }
        old?.deleteRecursively()
        return InstallResult.Installed(staged.id)
    }

    /** [staged] under a free id next to the installed one ("sakura-2"). */
    fun asCopy(staged: StagedTheme): StagedTheme {
        val root = themesDir()
        val id = (2..999).asSequence().map { "${staged.id.take(44)}-$it" }.firstOrNull { !File(root, it).exists() } ?: staged.id
        return staged.copy(id = id, exists = File(root, id).exists())
    }

    /** Drops a staged import that won't be installed. */
    fun discard(staged: StagedTheme) {
        if (staged.dir.name.startsWith(STAGING_PREFIX)) staged.dir.deleteRecursively()
    }

    private fun reject(staging: File, reason: String): ArchiveResult {
        staging.deleteRecursively()
        return ArchiveResult.Rejected(reason)
    }

    companion object {
        const val MB = 1024L * 1024
        /** Uncompressed size cap of an archive. */
        const val MAX_BYTES = 50L * MB
        const val MAX_ENTRIES = 2000
        const val STAGING_PREFIX = ".import-"

        /** File types per pack folder; root files are manifests and notes. */
        private val FOLDERS = mapOf(
            "fonts" to setOf("ttf", "otf"),
            "sounds" to setOf("wav", "ogg", "mp3", "flac", "m4a", "aac", "opus"),
            "audio" to setOf("wav", "ogg", "mp3", "flac", "m4a", "aac", "opus"),
            "icons" to setOf("svg", "png", "webp"),
            "shaders" to setOf("frag", "glsl", "agsl"),
            "wallpapers" to WallpaperRef.TYPES.toSet(),
        )
        private val ROOT_TYPES = setOf("json", "md", "txt")

        /** Files the loader looks for at the pack root by name: the wallpaper, the music loop, the boot video and the CUSTOM shader. */
        private val ROOT_FILES = WallpaperRef.DEFAULT_NAMES.toSet() + BgmRef.DEFAULT_NAMES + BootVideoRef.DEFAULT_NAMES +
            "shader.frag" + UiFilterRef.DEFAULT_FILE

        /** The pack's root music loop (`bgm.ogg` / `bgm.mp3`), capped at [BgmRef.MAX_BYTES]. */
        private fun rootBgm(parts: List<String>) = parts.size == 1 && parts[0].lowercase(Locale.ROOT) in BgmRef.DEFAULT_NAMES

        private fun bgmTooLarge(fileName: String, parts: List<String>) =
            "$fileName: ${parts.joinToString("/")} is larger than ${BgmRef.MAX_BYTES / MB} MB (theme music)"

        /** The pack's root boot video (`boot.mp4`, 2b.5d), capped at [BootVideoRef.MAX_BYTES]. */
        private fun rootBootVideo(parts: List<String>) = parts.size == 1 && parts[0].lowercase(Locale.ROOT) in BootVideoRef.DEFAULT_NAMES

        private fun bootVideoTooLarge(fileName: String, parts: List<String>) =
            "$fileName: ${parts.joinToString("/")} is larger than ${BootVideoRef.MAX_BYTES / MB} MB (boot video)"
        private val PROGRAMS = setOf(
            "apk", "apks", "xapk", "aab", "dex", "odex", "vdex", "oat", "jar", "class", "so", "elf", "bin",
            "sh", "bash", "zsh", "bat", "cmd", "ps1", "exe", "dll", "msi", "py", "js", "lua", "pl", "rb",
        )

        /** Why an entry name is unsafe to unpack anywhere (zip slip), or null. */
        internal fun unsafe(name: String): String? = when {
            name.startsWith("/") || name.startsWith("\\") -> "absolute path"
            name.contains("..") -> "path with .."
            name.contains('\u0000') -> "invalid name"
            Regex("^[A-Za-z]:").containsMatchIn(name) -> "absolute path"
            else -> null
        }

        /** Why a file at [parts] (inside the pack) isn't allowed, or null. */
        internal fun disallowed(parts: List<String>): String? {
            val path = parts.joinToString("/")
            val ext = parts.last().substringAfterLast('.', "").lowercase(Locale.ROOT)
            if (ext in PROGRAMS) return "$path is a program; theme packs hold data only"
            if (parts.size == 1) {
                val ok = ext in ROOT_TYPES || parts[0].lowercase(Locale.ROOT) in ROOT_FILES
                return if (ok) null else "unexpected file $path (only .json, wallpaper.png, bgm.ogg, boot.mp4, shader.frag and postprocess.agsl at the top level)"
            }
            val types = FOLDERS[parts[0].lowercase(Locale.ROOT)]
                ?: return "unexpected folder ${parts[0]}/ (use fonts/, sounds/, audio/, icons/, shaders/ or wallpapers/)"
            if (parts.size > 4) return "$path is nested too deep"
            return if (ext in types) null else "unexpected file $path (${parts[0]}/ holds ${types.sorted().joinToString()})"
        }

        /** OS litter zip tools add (macOS resource forks, Finder and Explorer metadata). */
        private fun junk(parts: List<String>): Boolean =
            parts[0] == "__MACOSX" || parts.last().let { it == ".DS_Store" || it.equals("Thumbs.db", ignoreCase = true) || it.startsWith("._") }

        /**
         * Folder id for a pack: a slug of its [name] ("Y2K Cyber Dream" → "y2k-cyber-dream"),
         * else of the archive's folder or file name; never "default".
         */
        internal fun idFor(name: String, fileName: String, folder: String?): String {
            fun slug(s: String) = s.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-').take(48).trim('-')
            val base = listOf(name, folder.orEmpty(), fileName.substringBeforeLast('.')).map(::slug).firstOrNull { it.isNotEmpty() } ?: "theme"
            return if (base == ThemePackLoader.DEFAULT_ID) "$base-pack" else base
        }

        /** Removes staging folders a cancelled or interrupted import left behind. */
        fun clearStaging(root: File) {
            root.listFiles()?.filter { it.isDirectory && (it.name.startsWith(STAGING_PREFIX) || it.name.startsWith(".replaced-")) }
                ?.forEach { it.deleteRecursively() }
        }
    }
}

/** A folder checked as a theme pack: its manifest, or why it isn't one. */
sealed interface FolderCheck {
    data class Ok(val manifest: ThemeManifest) : FolderCheck
    data class Bad(val reason: String) : FolderCheck
}
