package dev.xross.launcher.media

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the UI shows about music; position is read separately (see [MusicPlayer.positionMs]). */
data class NowPlaying(
    val song: Song? = null,
    val isPlaying: Boolean = false,
    val index: Int = 0,
    val queueSize: Int = 0,
    val shuffle: Boolean = false,
    val repeatAll: Boolean = true,
)

/**
 * App-wide music engine. Owns one ExoPlayer for audio (with audio focus, so videos and games
 * that take focus pause it) and connects [PlaybackService] the first time music plays, which
 * keeps playback alive with a media notification while games or other apps are in front.
 */
class MusicPlayer(private val context: Context) {
    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
            /* handleAudioFocus = */ true,
        )
        .setHandleAudioBecomingNoisy(true) // pause when headphones are unplugged
        .build()

    private var queue: List<Song> = emptyList()
    private var controller: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null

    private val _state = MutableStateFlow(NowPlaying())
    val state: StateFlow<NowPlaying> = _state.asStateFlow()

    /** True when music was paused by a game launch, so it can resume afterwards (BGM mode). */
    private var pausedForGame = false

    init {
        player.repeatMode = Player.REPEAT_MODE_ALL
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = publish()
        })
    }

    val positionMs: Long get() = player.currentPosition.coerceAtLeast(0)
    val durationMs: Long get() = player.duration.takeIf { it > 0 } ?: (_state.value.song?.durationMs ?: 0)

    fun play(songs: List<Song>, startIndex: Int, shuffle: Boolean = false) {
        if (songs.isEmpty()) return
        ensureService()
        queue = songs
        player.shuffleModeEnabled = shuffle
        player.setMediaItems(songs.map { it.toMediaItem() }, startIndex.coerceIn(0, songs.lastIndex), 0)
        player.prepare()
        player.play()
        pausedForGame = false
    }

    fun togglePlayPause() {
        if (player.mediaItemCount == 0) return
        if (player.isPlaying) player.pause() else player.play()
        pausedForGame = false
    }

    fun next() { if (player.hasNextMediaItem()) player.seekToNextMediaItem() }

    /** Restarts the track if more than 3 s in, like most players; otherwise goes back one. */
    fun previous() = player.seekToPrevious()

    fun seekBy(deltaMs: Long) {
        val target = (player.currentPosition + deltaMs).coerceIn(0, durationMs.coerceAtLeast(0))
        player.seekTo(target)
    }

    fun toggleShuffle() { player.shuffleModeEnabled = !player.shuffleModeEnabled }

    fun toggleRepeat() {
        player.repeatMode = if (player.repeatMode == Player.REPEAT_MODE_ALL) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ALL
    }

    fun stop() {
        player.stop()
        player.clearMediaItems()
        queue = emptyList()
        pausedForGame = false
    }

    /**
     * Lowers music under a video snap without pausing it (snaps don't take audio focus, so
     * nothing else is interrupted), and restores it afterwards.
     */
    fun setDucked(ducked: Boolean) {
        player.volume = if (ducked) DUCK_VOLUME else 1f
    }

    /** Game starting: pause and remember to resume. */
    fun pauseForGame() {
        if (player.isPlaying) {
            player.pause()
            pausedForGame = true
        }
    }

    /** Back in the launcher after a game. */
    fun resumeAfterGame() {
        if (pausedForGame) {
            pausedForGame = false
            player.play()
        }
    }

    private fun publish() {
        val index = player.currentMediaItemIndex
        _state.value = NowPlaying(
            song = queue.getOrNull(index),
            isPlaying = player.isPlaying,
            index = index,
            queueSize = player.mediaItemCount,
            shuffle = player.shuffleModeEnabled,
            repeatAll = player.repeatMode == Player.REPEAT_MODE_ALL,
        )
    }

    /** Binding a controller starts [PlaybackService]; Media3 promotes it to foreground while playing. */
    private fun ensureService() {
        if (controller != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        controller = MediaController.Builder(context, token).buildAsync()
    }

    private fun Song.toMediaItem() = MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(uri)
        .setMediaMetadata(
            // Embedded cover art is read from the file's tags by the extractor.
            MediaMetadata.Builder().setTitle(title).setArtist(artist).setAlbumTitle(album).build(),
        )
        .build()

    private companion object {
        const val DUCK_VOLUME = 0.2f
    }
}
