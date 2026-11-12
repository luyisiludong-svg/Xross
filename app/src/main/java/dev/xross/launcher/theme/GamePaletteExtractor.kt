package dev.xross.launcher.theme

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The contextual game-art palette (Roadmap Appendix A.7): [focus] names the focused game's art,
 * and [colors] follows with its [GameColors] once the cursor has rested for [debounceMs] (the hero
 * backdrop's fade-in delay), so scrolling through a list extracts nothing.
 *
 * Extraction ([analyze] over [load]'s image) runs on [work], never on the caller's thread.
 * Results, including "no usable colors", are kept for the last [cacheSize] keys, so revisiting a
 * game costs a map lookup. The key should change when the art does (`uuid/version`).
 *
 * Generic over the image type so the debounce and cache are tested without bitmaps.
 */
class GamePaletteExtractor<I : Any>(
    scope: CoroutineScope,
    private val load: suspend (key: String) -> I?,
    private val analyze: (I) -> SwatchSet?,
    private val work: CoroutineDispatcher = Dispatchers.Default,
    private val debounceMs: Long = DEBOUNCE_MS,
    cacheSize: Int = CACHE_SIZE,
) {
    private val requests = MutableStateFlow<String?>(null)
    private val _colors = MutableStateFlow<GameColors?>(null)

    /** Colors of the art last focused (after the debounce); null for none or unusable art. */
    val colors: StateFlow<GameColors?> = _colors.asStateFlow()

    private val cache = LruCache<String, Entry>(cacheSize)

    /** Wrapper so a cached "no colors" is told apart from a miss. */
    private data class Entry(val colors: GameColors?)

    /** Extractions run so far (cache misses), for tests and the debug log. */
    @Volatile var extractions = 0
        private set

    init {
        scope.launch {
            requests.collectLatest { key ->
                delay(debounceMs)
                _colors.value = if (key == null) null else colorsOf(key)
            }
        }
    }

    /** The art now focused ([key]), or null when no game is. */
    fun focus(key: String?) {
        requests.value = key
    }

    /** Cached colors for [key], extracting them on [work] on a miss. */
    suspend fun colorsOf(key: String): GameColors? {
        synchronized(cache) { cache[key] }?.let { return it.colors }
        val found = withContext(work) {
            extractions++
            load(key)?.let { image -> analyze(image)?.let(GameColors::from) }
        }
        synchronized(cache) { cache[key] = Entry(found) }
        return found
    }

    /** True when [key] has been extracted and is still cached. */
    fun cached(key: String): Boolean = synchronized(cache) { cache.containsKey(key) }

    /** Drops every cached result (art was re-scraped, memory is low). */
    fun clear() = synchronized(cache) { cache.clear() }

    companion object {
        /** The hero backdrop's fade-in delay ([dev.xross.launcher.ui.BackdropTiming.HERO_DELAY_MS]). */
        const val DEBOUNCE_MS = 250L
        const val CACHE_SIZE = 100
    }
}

/** A plain access-ordered LRU map (android.util.LruCache isn't available in JVM tests). */
internal class LruCache<K, V>(private val max: Int) : LinkedHashMap<K, V>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > max
}
