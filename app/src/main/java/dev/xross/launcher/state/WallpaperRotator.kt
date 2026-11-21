package dev.xross.launcher.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Locale

/** How often a wallpaper folder moves on (0.2.2.0 § 5b). */
enum class RotationInterval(val minutes: Int?, val label: String) {
    OFF(null, "Off"),
    MIN_1(1, "Every minute"),
    MIN_5(5, "Every 5 minutes"),
    MIN_15(15, "Every 15 minutes"),
    MIN_60(60, "Every hour"),
    /** Each time Xross comes to the front (back from a game or another app). */
    FRONT(null, "Each time Xross comes to the front"),
    ;

    companion object {
        fun parse(text: String?): RotationInterval = entries.firstOrNull { it.name.equals(text?.trim(), ignoreCase = true) } ?: OFF
    }
}

/**
 * Settings ▸ Theme ▸ Xross wallpaper ▸ Pick a folder… (0.2.2.0 § 5b): which picture of the folder
 * shows. It never wakes a resting screen: a change that is due waits for the next input
 * ([onInput]) or the next time Xross comes to the front ([onFront]), and there is no timer.
 * Pictures go in file-name order; one deleted since the folder was read is skipped.
 */
class WallpaperRotator(private val exists: (String) -> Boolean = { File(it).isFile }) {
    private var files: List<String> = emptyList()
    private var interval = RotationInterval.OFF
    private var index = 0
    private var changedAt = 0L

    private val _current = MutableStateFlow<String?>(null)
    /** The picture to show, or null when no folder is set (the single wallpaper or the pack's shows). */
    val current: StateFlow<String?> = _current.asStateFlow()

    /** A new folder listing or interval. The same folder keeps its place. */
    fun configure(pictures: List<String>, interval: RotationInterval, now: Long) {
        this.interval = interval
        if (pictures == files) return
        val keep = _current.value?.let(pictures::indexOf)?.takeIf { it >= 0 }
        files = pictures
        index = keep ?: 0
        changedAt = now
        _current.value = if (files.isEmpty()) null else pick(from = index, step = 0)
    }

    fun onInput(now: Long) {
        val minutes = interval.minutes ?: return
        if (now - changedAt >= minutes * 60_000L) advance(now)
    }

    fun onFront(now: Long) {
        if (interval == RotationInterval.FRONT) advance(now) else onInput(now)
    }

    private fun advance(now: Long) {
        if (files.size < 2) return
        pick(from = index, step = 1)?.let { _current.value = it }
        changedAt = now
    }

    /** The first existing picture from [from] + [step] on, wrapping; sets [index]. */
    private fun pick(from: Int, step: Int): String? {
        for (i in 0 until files.size) {
            val j = (from + step + i).mod(files.size)
            if (exists(files[j])) {
                index = j
                return files[j]
            }
        }
        return null
    }

    companion object {
        private val TYPES = setOf("png", "jpg", "jpeg", "webp")

        /** A Photo album's pictures (file paths) that can be decoded as a wallpaper, by file name. */
        fun album(paths: List<String>): List<String> = paths
            .filter { it.isNotBlank() && File(it).extension.lowercase(Locale.ROOT) in TYPES }
            .distinct()
            .sortedWith(compareBy({ File(it).name.lowercase(Locale.ROOT) }, { it }))

        /** Blocking: the folder's pictures by file name. */
        fun list(folder: String): List<String> {
            if (folder.isBlank()) return emptyList()
            return File(folder).listFiles().orEmpty()
                .filter { it.isFile && !it.name.startsWith(".") && it.extension.lowercase(Locale.ROOT) in TYPES }
                .sortedBy { it.name.lowercase(Locale.ROOT) }
                .map { it.absolutePath }
        }
    }
}
