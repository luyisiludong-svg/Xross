package dev.xross.launcher.ui

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
    volume: Float,
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
        snap.play(file, volume)
    }
    DisposableEffect(Unit) { onDispose { snap.stop() } }
}

/**
 * The playing snap, cropped to fill its box, fading in over [BackdropTiming.VIDEO_FADE_MS] once
 * the first frame is ready. [onCovering] reports when it is fully opaque.
 */
@Composable
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
    BoxWithConstraints(modifier.clipToBounds().graphicsLayer { this.alpha = alpha }, contentAlignment = Alignment.Center) {
        val aspect = if (videoSize.width > 0 && videoSize.height > 0) {
            videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
        } else {
            4f / 3f
        }
        // Cover: match the side that would leave a gap; the other overflows and is clipped.
        val boxW = maxWidth
        val boxH = maxHeight
        val (w, h) = if (boxW / boxH > aspect) boxW to boxW / aspect else boxH * aspect to boxH
        PlayerSurface(
            player = player,
            surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
            modifier = Modifier
                .wrapContentSize(unbounded = true)
                .requiredSize(w, h),
        )
        if (scrim > 0f) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(0f to Color.Black.copy(alpha = scrim), 1f to Color.Black.copy(alpha = scrim * 0.55f)),
                ),
            )
        }
    }
}
