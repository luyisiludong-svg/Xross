package dev.xross.launcher.config

import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Writes config files off the main thread (SD cards can take tens of milliseconds to sync).
 * Writes to the same file coalesce: only the newest content is written. Writes run one at a
 * time, in order, on a single background thread.
 */
object ConfigWriter {
    private const val TAG = "XrossConfig"

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "xross-config").apply { isDaemon = true }
    }
    private val pending = ConcurrentHashMap<String, Pair<File, String>>()

    /** Called with a file that couldn't be written; the in-memory settings are unaffected. */
    @Volatile var onError: (File, Throwable) -> Unit = { file, e ->
        runCatching { Log.w(TAG, "Couldn't write $file", e) }
    }

    fun write(file: File, text: String) {
        val key = file.absolutePath
        // Only the first write since the last flush schedules a task; later ones replace its content.
        if (pending.put(key, file to text) == null) {
            executor.execute {
                val (target, latest) = pending.remove(key) ?: return@execute
                try {
                    AtomicFiles.writeText(target, latest)
                } catch (e: Exception) {
                    onError(target, e)
                }
            }
        }
    }

    /** Blocks until every write requested so far is on disk (or has failed). */
    fun awaitIdle() {
        executor.submit {}.get()
    }
}
