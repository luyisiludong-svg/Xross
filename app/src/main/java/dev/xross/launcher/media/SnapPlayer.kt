package dev.xross.launcher.media

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Short looping video previews ("snaps") for a focused game.
 *
 * - Doesn't take audio focus. It reports when it starts and stops through [onActive], and
 *   [dev.xross.launcher.audio.AudioDirector] decides what music does meanwhile.
 * - One player at a time, created on [play] and released on [stop]: an idle launcher holds no
 *   decoder, which keeps memory low and frees the hardware decoder before an emulator starts.
 * - Main thread only, like ExoPlayer itself.
 */
class SnapPlayer(private val context: Context, private val onActive: (Boolean) -> Unit) {

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var ramp: Runnable? = null

    private val _player = MutableStateFlow<ExoPlayer?>(null)
    /** The playing snap's player, for the surface; null when nothing plays. */
    val player: StateFlow<ExoPlayer?> = _player.asStateFlow()

    private var file: File? = null

    fun play(video: File, volume: Float) {
        if (file == video && _player.value != null) return
        stop()
        file = video
        val p = ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                /* handleAudioFocus = */ false,
            )
            .build()
        p.repeatMode = Player.REPEAT_MODE_ONE
        // Silent until the first frame is ready, then ramped up (see BackdropTiming.AUDIO_RAMP_MS).
        p.volume = 0f
        val target = volume.coerceIn(0f, 1f)
        p.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                p.removeListener(this)
                startRamp(p, target)
            }
        })
        p.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(video)))
        p.prepare()
        p.playWhenReady = true
        onActive(true)
        _player.value = p
    }

    /** Raises [p]'s volume from 0 to [target] over [RAMP_MS], in ~16 ms steps on the main thread. */
    private fun startRamp(p: ExoPlayer, target: Float) {
        ramp?.let(handler::removeCallbacks)
        val start = android.os.SystemClock.uptimeMillis()
        val step = object : Runnable {
            override fun run() {
                if (_player.value !== p) return
                val elapsed = android.os.SystemClock.uptimeMillis() - start
                p.volume = dev.xross.launcher.ui.BackdropTiming.rampVolume(elapsed, target)
                if (elapsed < RAMP_MS) handler.postDelayed(this, 16) else ramp = null
            }
        }
        ramp = step
        handler.post(step)
    }

    fun stop() {
        ramp?.let(handler::removeCallbacks)
        ramp = null
        val p = _player.value ?: return
        _player.value = null
        file = null
        p.release()
        onActive(false)
    }

    private companion object {
        const val RAMP_MS = dev.xross.launcher.ui.BackdropTiming.AUDIO_RAMP_MS
    }
}
