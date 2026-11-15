package dev.xross.launcher.theme.pack

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Locale

/** Where an export's bytes are read from: the pack's APK assets or folder, and the Xross wallpaper. */
interface ExportReader {
    /** Size of [source] in bytes, or null when it isn't known until it is read. */
    fun size(source: ExportSource): Long?

    /** Opens [source]; throws [IOException] or [SecurityException] when it can't be read. */
    fun open(source: ExportSource): InputStream
}

/** The sizes of an export before it is written, for the confirm panel and the cap. */
data class ExportPlan(
    /** Files besides `theme.json`. */
    val files: Int,
    /** Known bytes, `theme.json` included. */
    val bytes: Long,
    /** The biggest files, largest first (path, bytes). */
    val largest: List<Pair<String, Long>>,
)

sealed interface ExportResult {
    /** Written to `Xross/themes/<id>/`. */
    data class Saved(val id: String, val dir: File, val files: Int, val bytes: Long) : ExportResult

    /** Nothing was written; [reason] says why. */
    data class Refused(val reason: String) : ExportResult
}

/**
 * Writes a [LookExport] as a new pack folder in `Xross/themes/` (Phase 2b.8b): `theme.json` and the
 * files, staged in a hidden `.export-<nanos>/` folder (pack discovery and the [ThemeWatcher] skip
 * dot-folders) and renamed to `<id>/` only once everything is in it, so a failure leaves nothing
 * half-written. The id comes from the name ([ThemeArchive.idFor]); an installed pack is never
 * overwritten (`-2`, `-3`, …). At most [maxBytes] (the import cap, 50 MB), so the folder can be
 * shared and imported again. Blocking I/O: call off the main thread.
 */
class ThemeExport(
    private val themesDir: () -> File,
    private val maxBytes: Long = ThemeArchive.MAX_BYTES,
) {
    fun plan(export: LookExport, reader: ExportReader): ExportPlan {
        val sizes = export.files.map { it.path to (reader.size(it.source) ?: 0L) }
        return ExportPlan(
            files = export.files.size,
            bytes = manifestBytes(export.manifest).size + sizes.sumOf { it.second },
            largest = sizes.sortedByDescending { it.second }.take(3),
        )
    }

    fun write(export: LookExport, reader: ExportReader): ExportResult {
        val root = themesDir()
        if (!root.isDirectory && !root.mkdirs()) return ExportResult.Refused("Can't write to ${root.absolutePath}")
        clearStaging(root)
        val plan = plan(export, reader)
        if (plan.bytes > maxBytes) return ExportResult.Refused(tooLarge(plan.bytes, plan.largest))
        export.files.firstOrNull { it.source == ExportSource.UserWallpaper }?.let { f ->
            val size = reader.size(f.source)
            if (size != null && size > WallpaperRef.MAX_BYTES) {
                return ExportResult.Refused("Your wallpaper is larger than ${WallpaperRef.MAX_BYTES / ThemeArchive.MB} MB, which packs can't show")
            }
        }

        val staging = File(root, STAGING_PREFIX + System.nanoTime().toString(36))
        if (!staging.mkdirs()) return ExportResult.Refused("Can't write to ${root.absolutePath}")
        val stagingPath = staging.canonicalPath + File.separator
        var written = 0L
        var current = ThemePackLoader.MANIFEST
        try {
            val json = manifestBytes(export.manifest)
            File(staging, ThemePackLoader.MANIFEST).writeBytes(json)
            written += json.size
            for (file in export.files) {
                current = file.path
                val out = File(staging, file.path.replace('/', File.separatorChar))
                if (!out.canonicalPath.startsWith(stagingPath)) return refuse(staging, "${file.path} leaves the pack")
                out.parentFile?.mkdirs()
                reader.open(file.source).use { input ->
                    out.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            written += n
                            if (written > maxBytes) return refuse(staging, tooLarge(written, plan.largest))
                            output.write(buf, 0, n)
                        }
                    }
                }
            }
        } catch (e: IOException) {
            return refuse(staging, "Couldn't copy $current (${e.message ?: "read error"})")
        } catch (e: SecurityException) {
            return refuse(staging, "No permission to read $current")
        }

        val id = freeId(root, export.name)
        val target = File(root, id)
        if (!staging.renameTo(target)) return refuse(staging, "Couldn't move the pack into ${root.absolutePath}")
        return ExportResult.Saved(id, target, export.files.size, written)
    }

    private fun refuse(staging: File, reason: String): ExportResult {
        staging.deleteRecursively()
        return ExportResult.Refused(reason)
    }

    private fun tooLarge(bytes: Long, largest: List<Pair<String, Long>>): String =
        "The pack would be ${megabytes(bytes)}, over the ${maxBytes / ThemeArchive.MB} MB a theme can be" +
            largest.filter { it.second > 0 }.takeIf { it.isNotEmpty() }
                ?.joinToString(prefix = " (largest: ", postfix = ")") { (path, n) -> "$path ${megabytes(n)}" }.orEmpty()

    companion object {
        /** Hidden staging folders an export writes into before renaming. */
        const val STAGING_PREFIX = ".export-"

        private val JSON = Json {
            prettyPrint = true
            @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
            prettyPrintIndent = "  "
        }

        fun manifestBytes(manifest: JsonObject): ByteArray =
            (JSON.encodeToString(JsonObject.serializer(), manifest) + "\n").toByteArray(Charsets.UTF_8)

        /** A folder id for [name] that no pack in [root] has (names compared ignoring case, for FAT cards). */
        fun freeId(root: File, name: String): String {
            val taken = root.listFiles()?.mapTo(HashSet()) { it.name.lowercase(Locale.ROOT) }.orEmpty()
            val base = ThemeArchive.idFor(name, "theme", null)
            if (base !in taken) return base
            return (2..9999).asSequence().map { "${base.take(44)}-$it" }.first { it !in taken }
        }

        /** Removes staging folders an interrupted export left behind. */
        fun clearStaging(root: File) {
            root.listFiles()?.filter { it.isDirectory && it.name.startsWith(STAGING_PREFIX) }?.forEach { it.deleteRecursively() }
        }

        /** "2.1 MB", "640 KB". */
        fun megabytes(bytes: Long): String = when {
            bytes >= ThemeArchive.MB -> String.format(Locale.US, "%.1f MB", bytes / ThemeArchive.MB.toDouble())
            else -> "${(bytes + 1023) / 1024} KB"
        }
    }
}
