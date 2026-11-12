package dev.xross.launcher.theme.pack

import android.os.FileObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Watches the active folder pack and calls [onChange] once its files settle, so a pack edited in
 * Termux or pushed with adb reloads without leaving Xross. Editors that save through a temporary
 * file and a rename are caught by `MOVED_TO`. FUSE doesn't deliver every event on every device;
 * [ThemeRuntime.reloadIfChanged] on resume covers what is missed.
 */
class ThemeWatcher(scope: CoroutineScope, private val onChange: suspend () -> Unit) {
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private var observers: List<FileObserver> = emptyList()

    init {
        scope.launch {
            for (unit in signal) {
                delay(DEBOUNCE_MS)
                signal.tryReceive() // events during the wait are covered by this reload
                onChange()
            }
        }
    }

    /** Watches [dir] and its folders (two levels); null stops watching. */
    @Synchronized
    fun watch(dir: File?) {
        observers.forEach { it.stopWatching() }
        observers = emptyList()
        if (dir == null || !dir.isDirectory) return
        val dirs = ArrayList<File>()
        fun collect(d: File, depth: Int) {
            dirs += d
            if (depth < 2) d.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.forEach { collect(it, depth + 1) }
        }
        collect(dir, 0)
        observers = dirs.take(MAX_DIRS).map { d ->
            @Suppress("DEPRECATION") // FileObserver(File) needs API 29; minSdk is 28
            object : FileObserver(d.path, MASK) {
                override fun onEvent(event: Int, path: String?) {
                    if (path != null && !path.startsWith(".")) signal.trySend(Unit)
                }
            }.also { it.startWatching() }
        }
    }

    private companion object {
        const val DEBOUNCE_MS = 150L
        const val MAX_DIRS = 32
        const val MASK = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or FileObserver.MOVED_FROM or
            FileObserver.DELETE or FileObserver.CREATE
    }
}
