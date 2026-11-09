package dev.wave.launcher.state

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dev.wave.launcher.audio.UiSound
import dev.wave.launcher.audio.UiSounds
import dev.wave.launcher.data.SettingsStore
import dev.wave.launcher.input.NavAction
import dev.wave.launcher.media.MusicPlayer
import dev.wave.launcher.media.Photo
import dev.wave.launcher.media.Video
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Full-screen media modes layered over the XMB. */
sealed interface MediaOverlay {
    data class Photos(
        val photos: List<Photo>,
        val index: Int,
        /** 1 = fit to screen. */
        val zoom: Float = 1f,
        /** Pan as a fraction of the screen, clamped so the photo's edges never come inside the screen. */
        val panX: Float = 0f,
        val panY: Float = 0f,
        val slideshow: Boolean = false,
        val showInfo: Boolean = true,
    ) : MediaOverlay {
        val photo: Photo get() = photos[index]
    }

    data class VideoPlayback(
        val videos: List<Video>,
        val index: Int,
        val osdVisible: Boolean = true,
        val loop: Boolean = false,
    ) : MediaOverlay {
        val video: Video get() = videos[index]
    }

    data object NowPlaying : MediaOverlay
}

/**
 * Drives the photo viewer, video player and Now Playing screen: controller mapping, slideshow
 * timing, the video ExoPlayer's lifetime, and handing audio back to music afterwards.
 *
 * Controls
 * - Photos: ←/→ previous/next (pan when zoomed) · ↑/↓ pan when zoomed · R1/L1 zoom in/out ·
 *   Confirm info · Start slideshow · Options set as wallpaper · Back un-zoom, then close
 * - Video: Confirm/Start play/pause · ←/→ seek 10 s · L1/R1 seek 60 s · ↑/↓ previous/next video ·
 *   Options loop · Back close
 * - Now Playing: Confirm/Start play/pause · ←/→ previous/next · L1/R1 seek 10 s ·
 *   Options shuffle · ↑ repeat · Back close (music keeps playing)
 */
class MediaOverlayController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val music: MusicPlayer,
    private val settings: SettingsStore,
    private val sounds: UiSounds,
    private val setWallpaper: (Photo) -> Unit,
    private val toast: (String) -> Unit,
) {
    private val _overlay = MutableStateFlow<MediaOverlay?>(null)
    val overlay: StateFlow<MediaOverlay?> = _overlay.asStateFlow()

    /** Player for the open video, exposed so the UI can attach a surface. */
    var videoPlayer: ExoPlayer? = null
        private set

    private var viewportW = 16f
    private var viewportH = 9f

    /** Screen size, so pan limits follow the photo's fitted size (letter/pillarboxing). */
    fun setViewport(width: Float, height: Float) {
        if (width > 0 && height > 0) {
            viewportW = width
            viewportH = height
        }
    }

    /**
     * Largest pan (fraction of the screen) that keeps the zoomed photo covering the screen on that
     * axis; 0 while the zoomed photo is still smaller than the screen there.
     */
    fun panLimits(photo: Photo, zoom: Float): Pair<Float, Float> = panLimits(
        photo.displayWidth.takeIf { it > 0 }?.toFloat() ?: viewportW,
        photo.displayHeight.takeIf { it > 0 }?.toFloat() ?: viewportH,
        viewportW, viewportH, zoom,
    )

    private var slideshowJob: Job? = null
    private var osdJob: Job? = null
    private var musicWasPlaying = false

    val isOpen: Boolean get() = _overlay.value != null

    // region Open / close

    fun openPhotos(photos: List<Photo>, index: Int) {
        if (photos.isEmpty()) return
        _overlay.value = MediaOverlay.Photos(photos, index.coerceIn(0, photos.lastIndex))
    }

    fun openVideo(videos: List<Video>, index: Int) {
        if (videos.isEmpty()) return
        musicWasPlaying = music.state.value.isPlaying
        val player = videoPlayer ?: ExoPlayer.Builder(context)
            // Takes audio focus, which pauses music; music resumes on close (see close()).
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            .build()
            .also { p ->
                p.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED) showOsd(sticky = true)
                    }
                })
                videoPlayer = p
            }
        val loop = settings.current.videoLoop
        _overlay.value = MediaOverlay.VideoPlayback(videos, index.coerceIn(0, videos.lastIndex), loop = loop)
        startVideo(player, videos[index.coerceIn(0, videos.lastIndex)], loop)
        showOsd()
    }

    fun openNowPlaying() {
        if (music.state.value.song == null) return
        _overlay.value = MediaOverlay.NowPlaying
    }

    fun close() {
        stopSlideshow()
        osdJob?.cancel()
        videoPlayer?.let {
            it.release()
            videoPlayer = null
            if (musicWasPlaying) music.player.play()
            musicWasPlaying = false
        }
        _overlay.value = null
    }

    /** Launcher went to the background: pause the video, stop the slideshow. */
    fun onBackground() {
        videoPlayer?.pause()
        stopSlideshow()
        _overlay.update { if (it is MediaOverlay.Photos) it.copy(slideshow = false) else it }
    }

    // endregion

    /** Returns true when an overlay consumed the action. */
    fun onAction(action: NavAction): Boolean {
        when (val o = _overlay.value) {
            null -> return false
            is MediaOverlay.Photos -> photoAction(o, action)
            is MediaOverlay.VideoPlayback -> videoAction(o, action)
            MediaOverlay.NowPlaying -> nowPlayingAction(action)
        }
        return true
    }

    // region Photos

    private fun photoAction(o: MediaOverlay.Photos, action: NavAction) {
        val zoomed = o.zoom > 1.01f
        when (action) {
            NavAction.LEFT -> if (zoomed) pan(-PAN_STEP, 0f) else stepPhoto(-1)
            NavAction.RIGHT -> if (zoomed) pan(PAN_STEP, 0f) else stepPhoto(+1)
            NavAction.UP -> if (zoomed) pan(0f, -PAN_STEP)
            NavAction.DOWN -> if (zoomed) pan(0f, PAN_STEP)
            NavAction.NEXT_CATEGORY -> zoomBy(1.5f)
            NavAction.PREV_CATEGORY -> zoomBy(1 / 1.5f)
            NavAction.CONFIRM -> _overlay.update { (it as? MediaOverlay.Photos)?.copy(showInfo = !it.showInfo) ?: it }
            NavAction.START -> if (o.slideshow) stopSlideshow(announce = true) else startSlideshow()
            NavAction.OPTIONS -> {
                sounds.play(UiSound.OPTION)
                setWallpaper(o.photo)
            }
            NavAction.BACK -> if (zoomed) {
                _overlay.update { (it as? MediaOverlay.Photos)?.copy(zoom = 1f, panX = 0f, panY = 0f) ?: it }
            } else {
                sounds.play(UiSound.BACK)
                close()
            }
        }
    }

    fun stepPhoto(delta: Int) {
        val o = _overlay.value as? MediaOverlay.Photos ?: return
        val next = o.index + delta
        if (next !in o.photos.indices) return
        sounds.play(UiSound.MOVE)
        _overlay.value = o.copy(index = next, zoom = 1f, panX = 0f, panY = 0f)
        if (o.slideshow) startSlideshow() // restart the timer from this photo
    }

    fun zoomBy(factor: Float) {
        _overlay.update { o ->
            if (o !is MediaOverlay.Photos) return@update o
            val zoom = (o.zoom * factor).coerceIn(1f, MAX_ZOOM)
            val (lx, ly) = panLimits(o.photo, zoom)
            o.copy(zoom = zoom, panX = o.panX.coerceIn(-lx, lx), panY = o.panY.coerceIn(-ly, ly))
        }
    }

    /** Pan by a fraction of the screen (touch drags and D-pad steps). */
    fun pan(dx: Float, dy: Float) {
        _overlay.update { o ->
            if (o !is MediaOverlay.Photos || o.zoom <= 1.01f) return@update o
            val (lx, ly) = panLimits(o.photo, o.zoom)
            o.copy(panX = (o.panX + dx).coerceIn(-lx, lx), panY = (o.panY + dy).coerceIn(-ly, ly))
        }
    }

    fun toggleInfo() = _overlay.update { (it as? MediaOverlay.Photos)?.copy(showInfo = !it.showInfo) ?: it }

    private fun startSlideshow() {
        slideshowJob?.cancel()
        _overlay.update { (it as? MediaOverlay.Photos)?.copy(slideshow = true, showInfo = false, zoom = 1f, panX = 0f, panY = 0f) ?: it }
        slideshowJob = scope.launch {
            while (isActive) {
                delay(settings.current.slideshowSeconds * 1000L)
                _overlay.update { o ->
                    if (o !is MediaOverlay.Photos) return@update o
                    // Wraps around at the end.
                    o.copy(index = (o.index + 1) % o.photos.size)
                }
            }
        }
    }

    private fun stopSlideshow(announce: Boolean = false) {
        slideshowJob?.cancel()
        slideshowJob = null
        if (announce) _overlay.update { (it as? MediaOverlay.Photos)?.copy(slideshow = false, showInfo = true) ?: it }
    }

    // endregion

    // region Video

    private fun startVideo(player: ExoPlayer, video: Video, loop: Boolean) {
        player.setMediaItem(MediaItem.fromUri(video.uri))
        player.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        player.prepare()
        player.play()
    }

    private fun videoAction(o: MediaOverlay.VideoPlayback, action: NavAction) {
        val player = videoPlayer ?: return
        when (action) {
            NavAction.CONFIRM, NavAction.START -> {
                if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                if (player.isPlaying) player.pause() else player.play()
            }
            NavAction.LEFT -> seekVideo(-10_000)
            NavAction.RIGHT -> seekVideo(10_000)
            NavAction.PREV_CATEGORY -> seekVideo(-60_000)
            NavAction.NEXT_CATEGORY -> seekVideo(60_000)
            NavAction.UP, NavAction.DOWN -> {
                val next = o.index + if (action == NavAction.UP) -1 else 1
                if (next in o.videos.indices) {
                    sounds.play(UiSound.MOVE)
                    _overlay.value = o.copy(index = next)
                    startVideo(player, o.videos[next], o.loop)
                }
            }
            NavAction.OPTIONS -> {
                val loop = !o.loop
                player.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                _overlay.value = o.copy(loop = loop)
                toast(if (loop) "Loop on" else "Loop off")
            }
            NavAction.BACK -> {
                sounds.play(UiSound.BACK)
                close()
                return
            }
        }
        showOsd()
    }

    private fun seekVideo(deltaMs: Long) {
        val p = videoPlayer ?: return
        val duration = p.duration.takeIf { it > 0 } ?: return
        p.seekTo((p.currentPosition + deltaMs).coerceIn(0, duration))
    }

    /** Shows the on-screen controls; they hide after 3 s of no input unless [sticky]. */
    fun showOsd(sticky: Boolean = false) {
        _overlay.update { (it as? MediaOverlay.VideoPlayback)?.copy(osdVisible = true) ?: it }
        osdJob?.cancel()
        if (sticky) return
        osdJob = scope.launch {
            delay(3000)
            if (videoPlayer?.isPlaying == true) {
                _overlay.update { (it as? MediaOverlay.VideoPlayback)?.copy(osdVisible = false) ?: it }
            }
        }
    }

    // endregion

    // region Now Playing

    private fun nowPlayingAction(action: NavAction) {
        when (action) {
            NavAction.CONFIRM, NavAction.START -> music.togglePlayPause()
            NavAction.LEFT -> music.previous()
            NavAction.RIGHT -> music.next()
            NavAction.PREV_CATEGORY -> music.seekBy(-10_000)
            NavAction.NEXT_CATEGORY -> music.seekBy(10_000)
            NavAction.OPTIONS -> {
                music.toggleShuffle()
                toast(if (music.player.shuffleModeEnabled) "Shuffle on" else "Shuffle off")
            }
            NavAction.UP -> {
                music.toggleRepeat()
                toast(if (music.player.repeatMode == Player.REPEAT_MODE_ALL) "Repeat all" else "Repeat off")
            }
            NavAction.DOWN -> Unit
            NavAction.BACK -> {
                sounds.play(UiSound.BACK)
                close()
            }
        }
    }

    // endregion

    companion object {
        private const val MAX_ZOOM = 5f

        /**
         * Pan limits (fraction of the screen) for a [pw]×[ph] photo fitted into a [vw]×[vh]
         * screen at [zoom]: how far it can move before an edge comes inside the screen.
         */
        fun panLimits(pw: Float, ph: Float, vw: Float, vh: Float, zoom: Float): Pair<Float, Float> {
            val fit = minOf(vw / pw, vh / ph)
            val overflowX = (pw * fit * zoom - vw).coerceAtLeast(0f) / 2
            val overflowY = (ph * fit * zoom - vh).coerceAtLeast(0f) / 2
            return overflowX / (vw * zoom) to overflowY / (vh * zoom)
        }
        private const val PAN_STEP = 0.08f
    }
}
