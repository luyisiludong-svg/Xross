package dev.xross.launcher.ui

import android.os.Build
import android.view.SurfaceView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import dev.xross.launcher.media.SnapPlayer
import kotlinx.coroutines.delay
import java.io.File

/**
 * Plays [uuid]'s video snap after the cursor has rested on it for [delayMs], while the launcher
 * is resumed. Moving on, an overlay, or leaving Xross stops it (and releases the decoder).
 */
@Composable
internal fun SnapDriver(
    uuid: String?,
    snap: SnapPlayer,
    videoFile: suspend (String) -> File?,
    delayMs: Long = BackdropTiming.VIDEO_DELAY_MS,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val resumed by lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val active = resumed.isAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(uuid, active) {
        snap.stop()
        if (uuid == null || !active) return@LaunchedEffect
        delay(delayMs)
        val file = videoFile(uuid) ?: return@LaunchedEffect
        snap.play(file)
    }
    DisposableEffect(Unit) { onDispose { snap.stop() } }
}

/**
 * The playing snap, cropped to fill its box, fading in over [BackdropTiming.VIDEO_FADE_MS] once
 * the first frame is ready. [onCovering] reports when it is fully opaque.
 *
 * From API 34 it draws on a `SurfaceView` behind the window, above the wave's (media overlay): the
 * scrim and the menu draw over it, and frames go to the compositor as the codec releases them.
 * Through a `TextureView` each frame waited for one of Xross's UI frames, and a 30 fps snap was held
 * for 3, 4 or 5 vsyncs at 120 Hz on the A55 instead of an even 4. The view's alpha fades both the
 * surface and the hole it clears in the window, so the snap crossfades over the hero art and
 * nothing shows before its first frame; that partial hole is what needs API 34, and older
 * versions keep the `TextureView`.
 */
@Composable
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun SnapVideo(player: ExoPlayer, modifier: Modifier = Modifier, scrim: Float = 0f, onCovering: (Boolean) -> Unit = {}) {
    var videoSize by remember(player) { mutableStateOf(player.videoSize) }
    var ready by remember(player) { mutableStateOf(false) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(size: VideoSize) { videoSize = size }
            override fun onRenderedFirstFrame() { ready = true }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    val alpha by animateFloatAsState(if (ready) 1f else 0f, tween(BackdropTiming.VIDEO_FADE_MS), label = "snap")
    val covering = ready && BackdropTiming.covers(0f, alpha)
    val report by androidx.compose.runtime.rememberUpdatedState(onCovering)
    LaunchedEffect(covering) { report(covering) }
    DisposableEffect(Unit) { onDispose { report(false) } }
    BoxWithConstraints(modifier.clipToBounds(), contentAlignment = Alignment.Center) {
        val aspect = if (videoSize.width > 0 && videoSize.height > 0) {
            videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
        } else {
            4f / 3f
        }
        // Cover: match the side that would leave a gap; the other overflows and is clipped.
        val boxW = maxWidth
        val boxH = maxHeight
        val (w, h) = if (boxW / boxH > aspect) boxW to boxW / aspect else boxH * aspect to boxH
        val surface = Modifier.wrapContentSize(unbounded = true).requiredSize(w, h)
        if (SNAP_SURFACE_VIEW) {
            key(player) {
                AndroidView(
                    factory = { ctx ->
                        SurfaceView(ctx).apply {
                            setZOrderMediaOverlay(true)
                            this.alpha = 0f
                            player.setVideoSurfaceView(this)
                        }
                    },
                    update = { it.alpha = alpha },
                    onRelease = { player.clearVideoSurfaceView(it) },
                    modifier = surface,
                )
            }
        } else {
            PlayerSurface(
                player = player,
                surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
                modifier = surface.graphicsLayer { this.alpha = alpha },
            )
        }
        if (scrim > 0f) {
            Box(
                Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }.background(
                    Brush.horizontalGradient(0f to Color.Black.copy(alpha = scrim), 1f to Color.Black.copy(alpha = scrim * 0.55f)),
                ),
            )
        }
    }
}

/** Snaps draw on a `SurfaceView` where its alpha also fades the hole it clears (see [SnapVideo]). */
private val SNAP_SURFACE_VIEW = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
