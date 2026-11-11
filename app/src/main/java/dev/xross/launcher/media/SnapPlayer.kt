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
 * - Doesn't take audio focus, so background music keeps playing, only ducked ([MusicPlayer.setDucked]).
 * - One player at a time, created on [play] and released on [stop]: an idle launcher holds no
 *   decoder, which keeps memory low and frees the hardware decoder before an emulator starts.
 * - Main thread only, like ExoPlayer itself.
 */
class SnapPlayer(private val context: Context, private val music: MusicPlayer) {

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
        p.volume = volume.coerceIn(0f, 1f)
        p.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(video)))
        p.prepare()
        p.playWhenReady = true
        music.setDucked(true)
        _player.value = p
    }

    fun stop() {
        val p = _player.value ?: return
        _player.value = null
        file = null
        p.release()
        music.setDucked(false)
    }
}
