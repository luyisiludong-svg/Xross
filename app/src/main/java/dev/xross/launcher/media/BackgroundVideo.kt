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

        /**
         * Paused: the last copied frame stays on screen (a poster in still mode). After
         * [HOLD_PARK_MS] the player is let go of too (parked), since a paused decoder still costs CPU.
         */
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
     * - [posterOnly]: the video is a scene's, and the scene is drawn as its poster (§ 3f), so no
     *   layer shows the video.
     */
    fun action(
        hasVideo: Boolean,
        started: Boolean,
        failed: Boolean = false,
        still: Boolean = false,
        covered: Boolean = false,
        standby: Boolean = false,
        otherVideo: Boolean = false,
        posterOnly: Boolean = false,
    ): Action = when {
        !hasVideo || !started || failed || posterOnly -> Action.RELEASE
        still || covered || standby || otherVideo -> Action.HOLD
        else -> Action.PLAY
    }

    /**
     * How long a held player keeps its decoder before it is parked (released, its position kept).
     * On the Galaxy A55 a paused ExoPlayer keeps the hardware decoder service at 4.5–16% of a core;
     * the grace keeps a brief cover (hero art, a snap's first second) from re-preparing.
     */
    const val HOLD_PARK_MS = 2_000L

    /** What [BackgroundVideo.apply] does with the player for an [Action]. */
    enum class Step {
        /** Prepare a player (paused for HOLD, so it shows one frame and is parked again). */
        CREATE,

        /** Use the player there is. */
        KEEP,

        /** No player: released, or parked with its frame already on screen. */
        NONE,
    }

    /**
     * - [hasPlayer]: a player is prepared.
     * - [framed]: a frame was copied into the current GL texture since it was made (or since the
     *   video changed), so a parked player leaves a picture on screen.
     */
    fun step(action: Action, hasPlayer: Boolean, framed: Boolean): Step = when {
        action == Action.RELEASE -> Step.NONE
        hasPlayer -> Step.KEEP
        action == Action.PLAY -> Step.CREATE
        // HOLD without a player: parked. A texture without a frame (still mode's poster, a new
        // GL context) gets one from a short-lived player.
        framed -> Step.NONE
        else -> Step.CREATE
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
 *  - Held, it pauses, then parks after [BackgroundVideoPolicy.HOLD_PARK_MS]: the player is
 *    released once one of its frames has been copied ([copies]), and the copy stays on screen.
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
    /** How many video frames the renderer has copied into its texture so far (any thread). */
    private val copies: () -> Long = { 0L },
) {
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var source: VideoSource? = null
    private var surface: Surface? = null
    private var action = BackgroundVideoPolicy.Action.RELEASE
    /** [copies] when the current texture (or video) arrived, and when the current player was made. */
    private var copiesAtSurface = 0L
    private var copiesAtCreate = 0L
    private val positions = HashMap<String, Long>()
    private val probed = HashSet<String>()
    private val failed = HashSet<String>()
    private val parkNow = Runnable { park() }

    /** The GL thread's surface (null while it has none). */
    fun setSurface(s: Surface?) {
        val changed = s !== surface
        surface = s
        if (changed) copiesAtSurface = copies()
        val p = player
        if (p != null) {
            if (s == null) p.clearVideoSurface() else p.setVideoSurface(s)
            return
        }
        // Parked while held: a new texture (a new GL context) has no frame yet; prepare once for one.
        val video = source ?: return
        if (s != null && changed && action == BackgroundVideoPolicy.Action.HOLD && video.key !in failed) {
            create(video).playWhenReady = false
            schedulePark()
        }
    }

    /** Puts the player in the state [action] asks for, for [video]. */
    fun apply(video: VideoSource?, action: BackgroundVideoPolicy.Action) {
        this.action = action
        main.removeCallbacks(parkNow)
        if (video == null || action == BackgroundVideoPolicy.Action.RELEASE || video.key in failed) {
            release()
            return
        }
        if (source?.key != video.key) {
            release()
            positions.keys.retainAll(setOf(video.key))
            source = video
            copiesAtSurface = copies()
            probe(video)
        }
        val framed = copies() > copiesAtSurface
        val p = when (BackgroundVideoPolicy.step(action, player != null, framed)) {
            BackgroundVideoPolicy.Step.CREATE -> create(video)
            BackgroundVideoPolicy.Step.KEEP -> player
            BackgroundVideoPolicy.Step.NONE -> null
        }
        p?.playWhenReady = action == BackgroundVideoPolicy.Action.PLAY
        if (p != null && action == BackgroundVideoPolicy.Action.HOLD) schedulePark()
        log("${action.name.lowercase()} ${video.file.name}${if (p == null) " (parked)" else ""}")
    }

    /** Lets go of the player (and its decoder), keeping the position for the next prepare. */
    fun release() {
        main.removeCallbacks(parkNow)
        val p = player ?: run {
            source = null
            return
        }
        source?.let { positions[it.key] = p.currentPosition }
        player = null
        p.release()
        log("released")
    }

    private fun schedulePark() {
        main.removeCallbacks(parkNow)
        main.postDelayed(parkNow, BackgroundVideoPolicy.HOLD_PARK_MS)
    }

    /**
     * Still held: releases the player but keeps its video and position, so the next PLAY prepares
     * where it was. Waits until a frame of this player has been copied, or the screen would lose
     * its picture (the GL loop may be stopped while the activity is paused).
     */
    private fun park() {
        val p = player ?: return
        if (action != BackgroundVideoPolicy.Action.HOLD) return
        if (copies() <= copiesAtCreate) {
            main.postDelayed(parkNow, PARK_RETRY_MS)
            return
        }
        source?.let { positions[it.key] = p.currentPosition }
        player = null
        p.release()
        log("parked")
    }

    private fun create(video: VideoSource): ExoPlayer {
        copiesAtCreate = copies()
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
        const val PARK_RETRY_MS = 1_000L
        val PROBE = Executors.newSingleThreadExecutor { r -> Thread(r, "XrossVideoProbe").apply { isDaemon = true } }
    }
}
