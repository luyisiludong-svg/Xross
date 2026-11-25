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
 * the wave is shown instead. A death Android says wasn't a crash (a force-stop, an app update, a
 * memory kill) spares them once: their keys go to [SPARED], and a second armed death of a spared
 * key before it has [proven] itself blocks it whatever the reason. [unblockAll] (Settings ▸
 * System ▸ Performance ▸ Try retired shaders again) forgets both lists.
 *
 * All I/O failures are swallowed: a guard that can't write must never stop the launcher.
 */
class ShaderCanary(private val dir: () -> File) {
    private val armed = LinkedHashSet<String>()
    private var blocked: Set<String>? = null

    private val marker: File get() = File(dir(), MARKER)
    private val blockedFile: File get() = File(dir(), BLOCKED)
    private val sparedFile: File get() = File(dir(), SPARED)

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
     * Call before anything is armed. [wasCrash] says whether the process that died after the
     * marker was written (its time, wall clock, is the argument) crashed: false spares the keys
     * once, true or null (unknown) blocks them.
     */
    @Synchronized
    fun recover(wasCrash: (markedAtMs: Long) -> Boolean? = { null }): List<String> {
        val markedAt = runCatching { marker.lastModified() }.getOrDefault(0L)
        val left = runCatching { marker.takeIf { it.isFile }?.readLines() }.getOrNull()
            ?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        runCatching { marker.delete() }
        if (left.isEmpty()) return emptyList()
        val crashed = runCatching { wasCrash(markedAt) }.getOrNull() != false
        val spared = readLines(sparedFile)
        val block = if (crashed) left else left.filter { it in spared }
        val spare = left - block.toSet()
        if (spare.isNotEmpty()) saveLines(sparedFile, spared + spare)
        if (block.isEmpty()) return emptyList()
        val now = blockedKeys() + block
        blocked = now
        saveBlocked(now)
        return block
    }

    /** [key] drew its proving frames: a later non-crash death while it is armed spares it again. */
    @Synchronized
    fun proven(key: String) {
        val spared = readLines(sparedFile)
        if (key in spared) saveLines(sparedFile, spared - key)
    }

    /** Forgets every block (and spare); the keys it unblocked. */
    @Synchronized
    fun unblockAll(): Set<String> {
        val was = blockedKeys()
        blocked = emptySet()
        runCatching { blockedFile.delete() }
        runCatching { sparedFile.delete() }
        return was
    }

    @Synchronized
    fun blockedKeys(): Set<String> = blocked ?: readLines(blockedFile).also { blocked = it }

    private fun readLines(f: File): Set<String> = runCatching {
        f.takeIf { it.isFile }?.readLines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
    }.getOrNull().orEmpty()

    private fun saveLines(f: File, keys: Set<String>) {
        runCatching {
            if (keys.isEmpty()) {
                f.delete()
                return
            }
            f.parentFile?.mkdirs()
            f.writeText(keys.toList().takeLast(MAX_BLOCKED).joinToString("\n", postfix = "\n"))
        }
    }

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

    private fun saveBlocked(keys: Set<String>) = saveLines(blockedFile, keys)

    companion object {
        const val MARKER = "shader_canary.tmp"
        const val BLOCKED = "shader_blocked.txt"
        const val SPARED = "shader_spared.txt"
        const val PROVEN_FRAMES = 120
        private const val MAX_BLOCKED = 32
    }
}
