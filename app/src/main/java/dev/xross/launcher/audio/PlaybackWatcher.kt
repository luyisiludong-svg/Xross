package dev.xross.launcher.audio

import android.content.Context
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper

/**
 * The platform's active players by [PlaybackCensus.Kind] (2b.5c), from
 * `AudioManager.registerAudioPlaybackCallback`: every app's, Xross's own included (it can't tell
 * them apart, so [AudioDirector] subtracts its own). Watched only while the launcher is in front.
 * Main thread only.
 */
class PlaybackWatcher(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var listener: ((Map<PlaybackCensus.Kind, Int>, Boolean) -> Unit)? = null

    private val callback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            listener?.invoke(count(configs), false)
        }
    }

    /**
     * Starts watching: [onChange] gets the counts now (`snapshot` true) and on every change after.
     */
    fun start(onChange: (active: Map<PlaybackCensus.Kind, Int>, snapshot: Boolean) -> Unit) {
        if (listener != null || audio == null) return
        listener = onChange
        audio.registerAudioPlaybackCallback(callback, handler)
        onChange(count(audio.activePlaybackConfigurations), true)
    }

    fun stop() {
        if (listener == null) return
        listener = null
        audio?.unregisterAudioPlaybackCallback(callback)
    }

    private fun count(configs: List<AudioPlaybackConfiguration>): Map<PlaybackCensus.Kind, Int> {
        val out = HashMap<PlaybackCensus.Kind, Int>(2)
        for (c in configs) {
            val a = c.audioAttributes ?: continue
            val kind = PlaybackCensus.kindOf(a.usage, a.contentType) ?: continue
            out[kind] = (out[kind] ?: 0) + 1
        }
        return out
    }
}
