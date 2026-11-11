package dev.xross.launcher.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.util.Log

enum class UiSound(val file: String, val gain: Float = 1f) {
    /** Cursor and category moves. No dedicated cursor tick ships yet, so this reuses the option tick, quieter. */
    MOVE("snd_option.wav", gain = 0.55f),
    ENTER("snd_option.wav"),
    BACK("snd_cancel.wav"),
    OPTION("snd_option.wav"),
    OK("snd_system_ok.wav", gain = 0.8f),
    ERROR("snd_system_ng.wav", gain = 0.8f),
    TROPHY("snd_trophy.wav"),
}

/** Long cues are streamed with MediaPlayer instead of decoded into SoundPool memory. */
enum class UiCue(val file: String) {
    COLDBOOT("coldboot_stereo.wav"),
    GAMEBOOT("gameboot_stereo.wav"),
}

/**
 * Low-latency UI sound effects from `assets/sounds`. Short effects are preloaded into a
 * SoundPool; the boot cues are streamed. Missing files are skipped so a sound pack can be
 * partial.
 */
class UiSounds(private val context: Context) {
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private val pool = SoundPool.Builder().setMaxStreams(6).setAudioAttributes(attributes).build()
    private val ids = HashMap<String, Int>()
    private var cuePlayer: MediaPlayer? = null

    @Volatile var volume: Float = 0.8f
    @Volatile var enabled: Boolean = true

    init {
        for (file in UiSound.entries.map { it.file }.distinct()) {
            runCatching {
                context.assets.openFd("sounds/$file").use { fd -> ids[file] = pool.load(fd, 1) }
            }.onFailure { Log.w(TAG, "Missing UI sound $file") }
        }
    }

    fun play(sound: UiSound) {
        if (!enabled || volume <= 0f) return
        val id = ids[sound.file] ?: return
        val v = (volume * sound.gain).coerceIn(0f, 1f)
        pool.play(id, v, v, 1, 0, 1f)
    }

    fun playCue(cue: UiCue, cueVolume: Float = volume) {
        stopCue()
        if (cueVolume <= 0f) return
        cuePlayer = runCatching {
            context.assets.openFd("sounds/${cue.file}").use { fd ->
                MediaPlayer().apply {
                    setAudioAttributes(attributes)
                    setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                    setVolume(cueVolume, cueVolume)
                    setOnCompletionListener { player -> player.release(); if (cuePlayer === player) cuePlayer = null }
                    prepare()
                    start()
                }
            }
        }.onFailure { Log.w(TAG, "Can't play ${cue.file}", it) }.getOrNull()
    }

    fun stopCue() {
        cuePlayer?.runCatching { stop(); release() }
        cuePlayer = null
    }

    private companion object {
        const val TAG = "UiSounds"
    }
}
