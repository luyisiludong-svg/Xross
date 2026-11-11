package dev.xross.launcher.config

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Crash-safe file replacement: the new content goes to `<name>.tmp`, is flushed to the device,
 * and is then renamed over the target. A crash or power cut leaves either the old file or the new
 * one, never a half-written one. Works on FAT/exFAT SD cards, where an atomic rename over an
 * existing file may be refused: the target is then deleted and the rename retried.
 */
object AtomicFiles {
    const val TMP_SUFFIX = ".tmp"

    fun tmpFor(target: File): File = File(target.parentFile, target.name + TMP_SUFFIX)

    fun writeText(target: File, text: String) = write(target) { out -> out.write(text.toByteArray(Charsets.UTF_8)) }

    /** Streams [source] into [target] atomically; [beforeCommit] may patch the finished temp file. */
    fun copy(source: File, target: File, beforeCommit: (File) -> Unit = {}) =
        source.inputStream().use { input -> copy(input, target, beforeCommit) }

    fun copy(input: InputStream, target: File, beforeCommit: (File) -> Unit = {}) {
        write(target, beforeCommit) { out -> input.copyTo(out, BUFFER) }
    }

    /** The file's text, or null when it doesn't exist. A leftover temp file from a failed write is removed. */
    fun readText(file: File): String? {
        tmpFor(file).takeIf { it.exists() }?.delete()
        return if (file.isFile) file.readText(Charsets.UTF_8) else null
    }

    private inline fun write(target: File, beforeCommit: (File) -> Unit = {}, body: (FileOutputStream) -> Unit) {
        val dir = target.parentFile ?: throw IOException("No parent folder for $target")
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("Can't create $dir")
        val tmp = tmpFor(target)
        try {
            FileOutputStream(tmp).use { out ->
                body(out)
                out.flush()
                out.fd.sync()
            }
            beforeCommit(tmp)
            replace(tmp, target)
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
    }

    private fun replace(tmp: File, target: File) {
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            return
        } catch (_: AtomicMoveNotSupportedException) {
        } catch (_: IOException) {
        }
        // Filesystems without atomic replace: the old file is gone for a moment, the temp file stays complete.
        if (target.exists() && !target.delete()) throw IOException("Can't replace $target")
        if (!tmp.renameTo(target)) throw IOException("Can't rename $tmp to $target")
    }

    private const val BUFFER = 64 * 1024
}
