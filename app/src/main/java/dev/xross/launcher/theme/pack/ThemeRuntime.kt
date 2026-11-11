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

    private val lock = Mutex()

    /** Rescans `Xross/themes/`. */
    suspend fun rediscover(): List<ThemePackInfo> = withContext(io) {
        loader.discover().also { _packs.value = it }
    }

    /**
     * Loads and activates [id]. An unknown or broken pack activates the Default pack; the return
     * value is what is active afterwards.
     */
    suspend fun select(id: String): LoadedTheme = lock.withLock {
        if (_active.value.id == id && id == ThemePackLoader.DEFAULT_ID) return@withLock _active.value
        val loaded = withContext(io) { loader.load(id) }
        _active.value = loaded
        loaded
    }

    /** Reloads the active pack from disk (its files were edited). */
    suspend fun reload(): LoadedTheme = lock.withLock {
        val loaded = withContext(io) { loader.load(_active.value.id) }
        _active.value = loaded
        loaded
    }
}
