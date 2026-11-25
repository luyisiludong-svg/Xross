package dev.xross.launcher.media

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import dev.xross.launcher.theme.pack.VideoRef
import dev.xross.launcher.theme.pack.VideoSource
import java.util.concurrent.Executors

/**
 * When the background video plays, holds its frame, or lets go of its decoder (Live backgrounds
 * § 2). Pure, so the rules are unit tested without Android, like `ParticleRest`.
 */
object BackgroundVideoPolicy {
    enum class Action {
        /** No player: no video, the activity is stopped (a game or app is in front), or it failed. */
        RELEASE,

        /** Prepared and paused: the last copied frame stays on screen (a poster in still mode). */
        HOLD,

        /** Playing, looping, muted. */
        PLAY,
    }

    /**
     * - [hasVideo]: the look shows a video ([dev.xross.launcher.theme.pack.LoadedTheme.videoSource]).
     * - [started]: the activity is at least STARTED. Stopped means a game or app is in front: the
     *   player is released so the decoder is free for it.
     * - [failed]: the decoder failed on this file (the look's wallpaper, else the wave, shows instead).
     * - [still]: still/E-Ink mode shows the first frame as a poster and never plays.
     * - [covered]: something opaque covers the background (hero art, a wallpaper), so the wave is paused.
     * - [standby]: Performance ▸ Standby motion says the screen is resting. Decoding costs the same
     *   at any GL frame rate, so playback pauses until the next input.
     * - [otherVideo]: a video snap or the boot video is playing: one hardware decoder in use at a time.
     */
    fun action(
        hasVideo: Boolean,
        started: Boolean,
        failed: Boolean = false,
        still: Boolean = false,
        covered: Boolean = false,
        standby: Boolean = false,
        otherVideo: Boolean = false,
    ): Action = when {
        !hasVideo || !started || failed -> Action.RELEASE
        still || covered || standby || otherVideo -> Action.HOLD
        else -> Action.PLAY
    }

    /** What the probe of a file says ([probe]): fine, worth a notice, or not playable here. */
    sealed interface Probe {
        data object Ok : Probe
        data class Notice(val text: String) : Probe
        data class Unplayable(val reason: String) : Probe
    }

    /**
     * The verdict on a file whose video track is [mime] at [width]×[height], where [decoder] says
     * whether this device has a decoder for it (null: the file has no video track).
     */
    fun probe(mime: String?, width: Int, height: Int, decoder: Boolean): Probe = when {
        mime == null -> Probe.Unplayable("no video track")
        !decoder -> Probe.Unplayable("this device has no decoder for $mime${if (width > 0) " at ${width}x$height" else ""}")
        maxOf(width, height) > VideoRef.MAX_SIDE ->
            Probe.Notice("${width}x$height is larger than ${VideoRef.MAX_SIDE} on its long side; 720p loops decode cheaper")
        else -> Probe.Ok
    }
}

/**
 * The background video's player (Live backgrounds § 2), main thread only like ExoPlayer itself:
 *  - Its own ExoPlayer, looping gaplessly (`REPEAT_MODE_ONE`), muted, with the audio track
 *    disabled through track selection, so there is no audio decoder and no audio focus request.
 *  - It renders into the [Surface] the GL thread made ([setSurface]); the renderer copies frames
 *    out of it into `uVideo`.
 *  - [apply] follows [BackgroundVideoPolicy]: released, it remembers where it was, and the next
 *    prepare seeks there, so returning from a game resumes the loop where it left off.
 *  - The first time a file is used it is probed off the main thread (track, decoder, size); an
 *    unplayable file or a player error calls [onFailed] once for that file.
 */
class BackgroundVideo(
    private val context: Context,
    /** The decoder can't play [VideoSource] (why); the look falls back to its wallpaper, else the wave. */
    private val onFailed: (VideoSource, String) -> Unit,
    /** Something worth a notice about [VideoSource] that still plays (an oversized frame). */
    private val onNotice: (VideoSource, String) -> Unit = { _, _ -> },
    /** The display size of the video (rotation and pixel aspect applied), for the copy and its fit. */
    private val onSize: (Int, Int) -> Unit = { _, _ -> },
) {
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var source: VideoSource? = null
    private var surface: Surface? = null
    private val positions = HashMap<String, Long>()
    private val probed = HashSet<String>()
    private val failed = HashSet<String>()

    /** The GL thread's surface (null while it has none). */
    fun setSurface(s: Surface?) {
        surface = s
        val p = player ?: return
        if (s == null) p.clearVideoSurface() else p.setVideoSurface(s)
    }

    /** Puts the player in the state [action] asks for, for [video]. */
    fun apply(video: VideoSource?, action: BackgroundVideoPolicy.Action) {
        if (video == null || action == BackgroundVideoPolicy.Action.RELEASE || video.key in failed) {
            release()
            return
        }
        if (source?.key != video.key) {
            release()
            positions.keys.retainAll(setOf(video.key))
            source = video
            probe(video)
        }
        val p = player ?: create(video)
        p.playWhenReady = action == BackgroundVideoPolicy.Action.PLAY
        log("${action.name.lowercase()} ${video.file.name}")
    }

    /** Lets go of the player (and its decoder), keeping the position for the next prepare. */
    fun release() {
        val p = player ?: run {
            source = null
            return
        }
        source?.let { positions[it.key] = p.currentPosition }
        player = null
        p.release()
        log("released")
    }

    private fun create(video: VideoSource): ExoPlayer {
        val p = ExoPlayer.Builder(context).build()
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
            .build()
        p.volume = 0f
        p.repeatMode = Player.REPEAT_MODE_ONE
        surface?.let(p::setVideoSurface)
        p.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (player !== p || videoSize.width <= 0 || videoSize.height <= 0) return
                val w = (videoSize.width * videoSize.pixelWidthHeightRatio).toInt().coerceAtLeast(1)
                onSize(w, videoSize.height)
            }

            override fun onPlayerError(error: PlaybackException) {
                if (player !== p) return
                fail(video, error.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' '))
            }
        })
        p.setMediaItem(MediaItem.fromUri(Uri.fromFile(video.file)))
        positions[video.key]?.let { p.seekTo(it) }
        p.prepare()
        player = p
        return p
    }

    private fun fail(video: VideoSource, reason: String) {
        if (!failed.add(video.key)) return
        Log.w(TAG, "background video ${video.file.name} failed: $reason")
        if (source?.key == video.key) release()
        onFailed(video, reason)
    }

    /** Checks the file's video track once, off the main thread. */
    private fun probe(video: VideoSource) {
        if (!probed.add(video.key)) return
        PROBE.execute {
            val verdict = runCatching { probeFile(video) }.getOrElse { BackgroundVideoPolicy.Probe.Unplayable("can't be read (${it.message})") }
            main.post {
                when (verdict) {
                    BackgroundVideoPolicy.Probe.Ok -> Unit
                    is BackgroundVideoPolicy.Probe.Notice -> onNotice(video, verdict.text)
                    is BackgroundVideoPolicy.Probe.Unplayable -> fail(video, verdict.reason)
                }
            }
        }
    }

    private fun probeFile(video: VideoSource): BackgroundVideoPolicy.Probe {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(video.file.path)
            val format = (0 until extractor.trackCount).map(extractor::getTrackFormat)
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                ?: return BackgroundVideoPolicy.probe(null, 0, 0, decoder = false)
            val mime = format.getString(MediaFormat.KEY_MIME)
            val w = format.int(MediaFormat.KEY_WIDTH)
            val h = format.int(MediaFormat.KEY_HEIGHT)
            // findDecoderForFormat refuses a format that carries a frame rate on API 21; drop it.
            val query = MediaFormat.createVideoFormat(mime!!, w, h)
            val decoder = MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(query) != null
            return BackgroundVideoPolicy.probe(mime, w, h, decoder)
        } finally {
            extractor.release()
        }
    }

    private fun MediaFormat.int(key: String): Int = if (containsKey(key)) getInteger(key) else 0

    private fun log(what: String) {
        if (dev.xross.launcher.BuildConfig.PERF_LOG) Log.i(TAG, what)
    }

    private companion object {
        const val TAG = "XrossVideo"
        val PROBE = Executors.newSingleThreadExecutor { r -> Thread(r, "XrossVideoProbe").apply { isDaemon = true } }
    }
}
