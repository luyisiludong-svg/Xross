package dev.xross.launcher.theme.shader

import java.io.File
import java.io.FileOutputStream

/**
 * Crash-loop guard for a pack's own shader (2b.4a). A shader that panics the GPU driver can take
 * the process down, and Android restarts the home launcher at once, into the same shader. So
 * before an untrusted program is compiled, and again before it is first drawn, its key is written
 * to [MARKER] (synced to disk); it is removed once the compile settles and once the program has
 * drawn [PROVEN_FRAMES] frames in a row (or is taken off screen cleanly: a pause, a switch).
 *
 * At process start [recover] moves any key still in the marker to the [BLOCKED] list: those
 * programs are not compiled again until their text changes (their key includes a digest), and
 * the wave is shown instead.
 *
 * All I/O failures are swallowed: a guard that can't write must never stop the launcher.
 */
class ShaderCanary(private val dir: () -> File) {
    private val armed = LinkedHashSet<String>()
    private var blocked: Set<String>? = null

    private val marker: File get() = File(dir(), MARKER)
    private val blockedFile: File get() = File(dir(), BLOCKED)

    /** Keys armed right now (for tests and the log). */
    @get:Synchronized val armedKeys: Set<String> get() = armed.toSet()

    @Synchronized
    fun arm(key: String) {
        if (armed.add(key)) write()
    }

    @Synchronized
    fun disarm(key: String) {
        if (armed.remove(key)) write()
    }

    @Synchronized
    fun disarmAll() {
        if (armed.isEmpty()) return
        armed.clear()
        write()
    }

    @Synchronized
    fun isBlocked(key: String): Boolean = key in blockedKeys()

    /** Adds [key] to the blocked list now (a GPU reset seen without the process dying). */
    @Synchronized
    fun block(key: String) {
        val now = blockedKeys() + key
        blocked = now
        saveBlocked(now)
        disarm(key)
    }

    /**
     * Process start: the keys a crash left in the marker, now blocked; empty after a clean exit.
     * Call before anything is armed.
     */
    @Synchronized
    fun recover(): List<String> {
        val left = runCatching { marker.takeIf { it.isFile }?.readLines() }.getOrNull()
            ?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        runCatching { marker.delete() }
        if (left.isEmpty()) return emptyList()
        val now = blockedKeys() + left
        blocked = now
        saveBlocked(now)
        return left
    }

    @Synchronized
    fun blockedKeys(): Set<String> = blocked ?: runCatching {
        blockedFile.takeIf { it.isFile }?.readLines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
    }.getOrNull().orEmpty().also { blocked = it }

    private fun write() {
        runCatching {
            val f = marker
            if (armed.isEmpty()) {
                f.delete()
                return
            }
            f.parentFile?.mkdirs()
            // Synced, so the marker is on disk before the driver sees the program.
            FileOutputStream(f).use { out ->
                out.write(armed.joinToString("\n", postfix = "\n").toByteArray())
                out.fd.sync()
            }
        }
    }

    private fun saveBlocked(keys: Set<String>) {
        runCatching {
            val f = blockedFile
            f.parentFile?.mkdirs()
            f.writeText(keys.toList().takeLast(MAX_BLOCKED).joinToString("\n", postfix = "\n"))
        }
    }

    companion object {
        const val MARKER = "shader_canary.tmp"
        const val BLOCKED = "shader_blocked.txt"
        const val PROVEN_FRAMES = 120
        private const val MAX_BLOCKED = 32
    }
}
