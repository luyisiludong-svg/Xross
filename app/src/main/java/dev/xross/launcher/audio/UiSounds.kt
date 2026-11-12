package dev.xross.launcher.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.util.Log
import dev.xross.launcher.theme.pack.LoadedTheme
import dev.xross.launcher.theme.pack.SoundRole
import dev.xross.launcher.theme.pack.SoundSource

/**
 * Short UI effects and the [SoundRole] each one plays. The mapping is fixed by design:
 *  - [MOVE] is every horizontal and vertical D-pad step (cursor and category moves): `cursor.wav`.
 *  - [ERROR] is every error and every action that isn't allowed: `system_ng.wav`.
 */
enum class UiSound(val role: SoundRole) {
    MOVE(SoundRole.CURSOR),
    ENTER(SoundRole.CONFIRM),
    BACK(SoundRole.BACK),
    OPTION(SoundRole.OPTION),
    OK(SoundRole.OK),
    ERROR(SoundRole.SYSTEM_NG),
    TROPHY(SoundRole.TROPHY),
}

/** Long cues, streamed with MediaPlayer instead of decoded into SoundPool memory. */
enum class UiCue(val role: SoundRole) {
    /** Startup: `01. opening.wav`, with the fade-in on a cold start. */
    OPENING(SoundRole.OPENING),
    GAMEBOOT(SoundRole.GAMEBOOT),
}

/**
 * Low-latency UI sound effects from the active theme pack ([useTheme]). Short effects are
 * preloaded into a SoundPool; the cues are streamed. A role the pack has no file for stays
 * silent rather than failing.
 */
class UiSounds(private val context: Context) {
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private val pool = SoundPool.Builder().setMaxStreams(6).setAudioAttributes(attributes).build()

    /** Loaded SoundPool id per source, so roles sharing a file share one sample. */
    private val ids = HashMap<SoundSource, Int>()
    @Volatile private var sources: Map<SoundRole, SoundSource> = emptyMap()
    @Volatile private var gains: Map<SoundRole, Float> = emptyMap()
    private var cuePlayer: MediaPlayer? = null
    private val lock = Any()

    @Volatile var volume: Float = 0.8f
    @Volatile var enabled: Boolean = true

    /** The pack whose sounds play now; null before the first theme is applied. */
    @Volatile var themeId: String? = null
        private set
    private var revision = -1L

    /** Swaps every sound to [theme]'s (missing roles fall back to the Default pack's, see the loader). */
    fun useTheme(theme: LoadedTheme) = synchronized(lock) {
        if (themeId == theme.id && sources == theme.sounds && gains == theme.gains && revision == theme.revision) return
        val wanted = theme.sounds.filterKeys { !it.stream }.values.toSet()
        // A reload of the same pack (hot reload) may have rewritten files under the same names.
        val reloaded = themeId == theme.id && revision != theme.revision
        // Unload samples the new theme doesn't use (or all disk samples on a reload); load the rest.
        for ((source, id) in ids.toMap()) if (source !in wanted || (reloaded && source is SoundSource.Disk)) {
            pool.unload(id)
            ids.remove(source)
        }
        for (source in wanted) if (source !in ids) load(source)?.let { ids[source] = it }
        sources = theme.sounds
        gains = theme.gains
        themeId = theme.id
        revision = theme.revision
    }

    private fun load(source: SoundSource): Int? = runCatching {
        when (source) {
            is SoundSource.Asset -> context.assets.openFd(source.path).use { fd -> pool.load(fd, 1) }
            is SoundSource.Disk -> pool.load(source.file.absolutePath, 1)
        }
    }.onFailure { Log.w(TAG, "Can't load UI sound $source") }.getOrNull()

    /** The source [sound] plays, for tests and Settings. */
    fun sourceOf(sound: UiSound): SoundSource? = sources[sound.role]

    fun play(sound: UiSound) {
        if (!enabled || volume <= 0f) return
        val source = sources[sound.role] ?: return
        val id = ids[source] ?: return
        val v = (volume * (gains[sound.role] ?: 1f)).coerceIn(0f, 1f)
        pool.play(id, v, v, 1, 0, 1f)
    }

    fun playCue(cue: UiCue, cueVolume: Float = volume) {
        stopCue()
        if (cueVolume <= 0f) return
        val source = sources[cue.role] ?: return
        val v = (cueVolume * (gains[cue.role] ?: 1f)).coerceIn(0f, 1f)
        cuePlayer = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(attributes)
                when (source) {
                    is SoundSource.Asset -> context.assets.openFd(source.path).use { fd ->
                        setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                    }
                    is SoundSource.Disk -> setDataSource(source.file.absolutePath)
                }
                setVolume(v, v)
                setOnCompletionListener { player -> player.release(); if (cuePlayer === player) cuePlayer = null }
                prepare()
                start()
            }
        }.onFailure { Log.w(TAG, "Can't play cue ${cue.name}", it) }.getOrNull()
    }

    fun stopCue() {
        cuePlayer?.runCatching { stop(); release() }
        cuePlayer = null
    }

    private companion object {
        const val TAG = "UiSounds"
    }
}
