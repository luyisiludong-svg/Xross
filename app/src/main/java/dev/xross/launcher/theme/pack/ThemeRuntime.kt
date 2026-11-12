package dev.xross.launcher.theme.pack

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The active theme pack and the packs available. Starts on the built-in Default pack (read
 * synchronously from the APK, so the first frame has icons and sounds) and switches with [select],
 * which reads a folder pack off the main thread.
 */
class ThemeRuntime(
    private val loader: ThemePackLoader,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _active = MutableStateFlow(loader.load(ThemePackLoader.DEFAULT_ID))
    val active: StateFlow<LoadedTheme> = _active.asStateFlow()

    private val _packs = MutableStateFlow(listOfNotNull(loader.builtIn()))
    /** Default first, then `Xross/themes/` packs; refreshed by [rediscover]. */
    val packs: StateFlow<List<ThemePackInfo>> = _packs.asStateFlow()

    private val _rejected = MutableStateFlow<List<RejectedPack>>(emptyList())
    /** Folders in `Xross/themes/` that aren't offered (v1 packs, broken JSON), from [rediscover]. */
    val rejected: StateFlow<List<RejectedPack>> = _rejected.asStateFlow()

    private val lock = Mutex()

    /** [ThemePackLoader.fingerprint] of the active pack when it was loaded. */
    private var loadedPrint = 0L

    /** Rescans `Xross/themes/`. */
    suspend fun rediscover(): List<ThemePackInfo> = withContext(io) {
        val scan = loader.scan()
        _rejected.value = scan.rejected
        scan.packs.also { _packs.value = it }
    }

    /**
     * Loads and activates [id]. An unknown or broken pack activates the Default pack; the return
     * value is what is active afterwards.
     */
    suspend fun select(id: String): LoadedTheme = lock.withLock {
        if (_active.value.id == id && id == ThemePackLoader.DEFAULT_ID) return@withLock _active.value
        val loaded = withContext(io) { loader.load(id).also { loadedPrint = loader.fingerprint(it.id) } }
        _active.value = loaded
        loaded
    }

    /** Reloads the active pack from disk (its files were edited). */
    suspend fun reload(): LoadedTheme = lock.withLock {
        val loaded = withContext(io) { loader.load(_active.value.id).also { loadedPrint = loader.fingerprint(it.id) } }
        _active.value = loaded
        loaded
    }

    /**
     * Hot reload after the active pack's files changed: the new theme replaces the active one only
     * if it loads. Returns null on success, or why the last good theme was kept.
     */
    suspend fun hotReload(): String? = lock.withLock {
        val id = _active.value.id
        if (id == ThemePackLoader.DEFAULT_ID) return@withLock null
        withContext(io) {
            val print = loader.fingerprint(id)
            when (val r = loader.loadStrict(id)) {
                is PackLoad.Ok -> {
                    loadedPrint = print
                    _active.value = r.theme
                    null
                }
                is PackLoad.Failed -> {
                    loadedPrint = print // don't repeat the same error on every resume
                    r.reason
                }
            }
        }
    }

    /** [hotReload] if the active pack changed on disk since it was loaded (missed file events). */
    suspend fun reloadIfChanged(): String? {
        val id = _active.value.id
        if (id == ThemePackLoader.DEFAULT_ID) return null
        val print = withContext(io) { loader.fingerprint(id) }
        return if (print != loadedPrint) hotReload() else null
    }
}
