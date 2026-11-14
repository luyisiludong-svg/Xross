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
 * - Doesn't take audio focus. It reports where it is through [onState] (loading, audible once
 *   its first frame is up, stopped), and [dev.xross.launcher.audio.AudioDirector] decides what
 *   music does meanwhile.
 * - Plays at [volume] (Settings ▸ Sound ▸ Video snaps), read when its fade-in starts; [setVolume]
 *   changes a playing snap's level live.
 * - One player at a time, created on [play] and released on [stop]: an idle launcher holds no
 *   decoder, which keeps memory low and frees the hardware decoder before an emulator starts.
 * - Main thread only, like ExoPlayer itself.
 */
class SnapPlayer(
    private val context: Context,
    private val volume: () -> Float,
    private val onState: (dev.xross.launcher.audio.SnapState) -> Unit,
) {

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var ramp: Runnable? = null

    /** The level the playing snap ramps to (or sits at once the ramp is over). */
    private var target = 0f

    private val _player = MutableStateFlow<ExoPlayer?>(null)
    /** The playing snap's player, for the surface; null when nothing plays. */
    val player: StateFlow<ExoPlayer?> = _player.asStateFlow()

    private var file: File? = null

    fun play(video: File) {
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
        p.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                p.removeListener(this)
                if (_player.value !== p) return
                target = volume().coerceIn(0f, 1f)
                log("audible")
                onState(dev.xross.launcher.audio.SnapState.AUDIBLE)
                startRamp(p)
            }
        })
        p.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(video)))
        p.prepare()
        p.playWhenReady = true
        _player.value = p
        onState(dev.xross.launcher.audio.SnapState.LOADING)
    }

    /** The video snaps volume changed: a playing snap follows (mid-ramp, the ramp heads for it). */
    fun setVolume(v: Float) {
        target = v.coerceIn(0f, 1f)
        val p = _player.value ?: return
        // Before the first frame it stays silent; during the ramp the next step reads [target].
        if (rampStarted && ramp == null) p.volume = target
        log("volume")
    }

    // Debug and profiling builds: the level a snap plays at, for the device checks.
    private fun log(event: String) {
        if (dev.xross.launcher.BuildConfig.PERF_LOG) {
            android.util.Log.i("XrossSnap", "%s target=%.2f file=%s".format(java.util.Locale.ROOT, event, target, file?.name))
        }
    }

    /** The first frame is up and the fade-in has begun. */
    private var rampStarted = false

    /** Raises [p]'s volume from 0 to [target] over [RAMP_MS], in ~16 ms steps on the main thread. */
    private fun startRamp(p: ExoPlayer) {
        rampStarted = true
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
        rampStarted = false
        val p = _player.value ?: return
        _player.value = null
        file = null
        p.release()
        onState(dev.xross.launcher.audio.SnapState.NONE)
    }

    private companion object {
        const val RAMP_MS = dev.xross.launcher.ui.BackdropTiming.AUDIO_RAMP_MS
    }
}
