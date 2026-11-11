package dev.xross.launcher.ui

import android.content.Context
import android.graphics.ImageDecoder
import android.util.LruCache
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import dev.xross.launcher.media.MusicPlayer
import dev.xross.launcher.media.NowPlaying
import dev.xross.launcher.media.Photo
import dev.xross.launcher.media.ThumbKind
import dev.xross.launcher.state.MediaMenus
import dev.xross.launcher.state.MediaOverlay
import dev.xross.launcher.state.MediaOverlayController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private val OverlayText: TextStyle
    get() = TextStyle(color = Color.White, fontFamily = ThemeTypography.family, shadow = Shadow(Color(0xAA000000), Offset(0f, 2f), 6f))

private fun style(size: TextUnit, weight: FontWeight = FontWeight.Normal, color: Color = Color.White) =
    OverlayText.copy(fontSize = size, fontWeight = weight, color = color)

/** The full-screen media layer above the XMB. */
@Composable
fun MediaOverlayLayer(
    overlay: MediaOverlay,
    controller: MediaOverlayController,
    music: MusicPlayer,
    nowPlaying: NowPlaying,
    loadThumb: suspend (ThumbKind, Long, Int) -> ImageBitmap?,
    hints: @Composable (List<Pair<String, String>>) -> Unit,
) {
    when (overlay) {
        is MediaOverlay.Photos -> PhotoViewer(overlay, controller, hints)
        is MediaOverlay.VideoPlayback -> VideoPlayerScreen(overlay, controller, hints)
        MediaOverlay.NowPlaying -> NowPlayingScreen(nowPlaying, music, loadThumb, hints)
    }
}

// region Photo viewer

/** Decodes photos at about screen resolution (EXIF orientation applied); keeps the last few. */
private object PhotoCache {
    private val cache = object : LruCache<Long, ImageBitmap>(3) {}

    suspend fun load(context: Context, photo: Photo, maxDim: Int): ImageBitmap? {
        cache.get(photo.id)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val source = ImageDecoder.createSource(context.contentResolver, photo.uri)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val longest = max(info.size.width, info.size.height)
                    if (longest > maxDim) {
                        val scale = maxDim.toFloat() / longest
                        decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
                    }
                }.asImageBitmap()
            }.getOrNull()
        }?.also { cache.put(photo.id, it) }
    }
}

@Composable
private fun PhotoViewer(
    o: MediaOverlay.Photos,
    controller: MediaOverlayController,
    hints: @Composable (List<Pair<String, String>>) -> Unit,
) {
    val context = LocalContext.current
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .photoGestures(controller, o.zoom > 1.01f),
    ) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        LaunchedEffect(w, h) { controller.setViewport(w, h) }
        // Up to 1.5× the screen's long side so zooming stays reasonably sharp.
        val maxDim = (max(w, h) * 1.5f).toInt().coerceAtMost(4096)
        val zoom by animateFloatAsState(o.zoom, spring(stiffness = Spring.StiffnessMediumLow), label = "zoom")
        val panX by animateFloatAsState(o.panX, spring(stiffness = Spring.StiffnessMediumLow), label = "panX")
        val panY by animateFloatAsState(o.panY, spring(stiffness = Spring.StiffnessMediumLow), label = "panY")

        // Preload neighbours so ←/→ is instant.
        LaunchedEffect(o.index) {
            o.photos.getOrNull(o.index + 1)?.let { PhotoCache.load(context, it, maxDim) }
            o.photos.getOrNull(o.index - 1)?.let { PhotoCache.load(context, it, maxDim) }
        }

        Crossfade(targetState = o.photo, animationSpec = tween(if (o.slideshow) 900 else 180), label = "photo") { photo ->
            val bitmap by produceState<ImageBitmap?>(null, photo.id) { value = PhotoCache.load(context, photo, maxDim) }
            bitmap?.let {
                Image(
                    it,
                    contentDescription = photo.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = zoom
                            scaleY = zoom
                            // Positive pan looks further right/down, so the image moves the other way.
                            translationX = -panX * w * zoom
                            translationY = -panY * h * zoom
                        },
                )
            }
        }

        val density = LocalDensity.current
        val s = min(w, h)
        AnimatedVisibility(
            visible = o.showInfo,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomStart),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                    .padding(horizontal = with(density) { (s * 0.05f).toDp() }, vertical = with(density) { (s * 0.04f).toDp() }),
            ) {
                Text(o.photo.name.substringBeforeLast('.'), style = style(with(density) { (s * 0.05f).toSp() }), maxLines = 1, overflow = TextOverflow.Ellipsis)
                val details = listOfNotNull(
                    o.photo.takenAt.takeIf { it > 0 }?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) },
                    if (o.photo.width > 0) "${o.photo.width}×${o.photo.height}" else null,
                    "${o.index + 1} / ${o.photos.size}",
                    if (o.zoom > 1.01f) "${"%.1f".format(o.zoom)}×" else null,
                ).joinToString("  ·  ")
                Text(details, style = style(with(density) { (s * 0.034f).toSp() }, color = Color(0xD9FFFFFF)))
            }
        }
        if (o.slideshow) {
            Text(
                "Slideshow",
                style = style(with(density) { (s * 0.032f).toSp() }),
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
                    .clip(RoundedCornerShape(50)).background(Color(0x66000000)).padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        if (o.showInfo) {
            Box(Modifier.align(Alignment.BottomEnd).padding(with(density) { (s * 0.04f).toDp() })) {
                hints(
                    listOf(
                        "←→" to "Photo",
                        "L1/R1" to "Zoom",
                        "Start" to "Slideshow",
                        "@options" to "Wallpaper",
                    ),
                )
            }
        }
    }
}

/**
 * Touch: one finger swipes between photos (or pans when zoomed), two fingers pinch-zoom, a tap
 * toggles the info bar.
 */
private fun Modifier.photoGestures(controller: MediaOverlayController, zoomed: Boolean): Modifier =
    pointerInput(zoomed) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var dx = 0f
            var moved = false
            var multiTouch = false
            do {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val pressed = event.changes.count { it.pressed }
                if (pressed >= 2) {
                    multiTouch = true
                    val zoom = event.calculateZoom()
                    if (zoom != 1f) controller.zoomBy(zoom)
                }
                if (pressed == 1 && !multiTouch) {
                    val change = event.changes.first { it.pressed }
                    val delta = change.positionChange()
                    dx += delta.x
                    if (abs(dx) > 12f || abs(delta.y) > 12f) moved = true
                    if (zoomed) {
                        val pan = event.calculatePan()
                        controller.pan(-pan.x / size.width, -pan.y / size.height)
                    }
                }
                event.changes.forEach { if (it.positionChange() != Offset.Zero) it.consume() }
            } while (event.changes.any { it.pressed })
            when {
                multiTouch -> Unit
                !moved -> controller.toggleInfo()
                !zoomed && dx < -size.width * 0.12f -> controller.stepPhoto(+1)
                !zoomed && dx > size.width * 0.12f -> controller.stepPhoto(-1)
            }
            down.consume()
        }
    }

// endregion

// region Video player

@Composable
private fun VideoPlayerScreen(
    o: MediaOverlay.VideoPlayback,
    controller: MediaOverlayController,
    hints: @Composable (List<Pair<String, String>>) -> Unit,
) {
    val player = controller.videoPlayer ?: return
    var videoSize by remember(player) { mutableStateOf(player.videoSize) }
    var playing by remember(player) { mutableStateOf(player.isPlaying) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(size: VideoSize) { videoSize = size }
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    controller.showOsd()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val aspect = if (videoSize.width > 0 && videoSize.height > 0) {
            videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
        } else {
            16f / 9f
        }
        PlayerSurface(
            player = player,
            surfaceType = SURFACE_TYPE_SURFACE_VIEW,
            modifier = Modifier.aspectRatio(aspect, matchHeightConstraintsFirst = aspect < maxWidth / maxHeight),
        )

        val density = LocalDensity.current
        val s = min(constraints.maxWidth, constraints.maxHeight).toFloat()
        AnimatedVisibility(
            visible = o.osdVisible || !playing,
            enter = fadeIn(),
            exit = fadeOut(tween(400)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xD0000000))))
                    .padding(horizontal = with(density) { (s * 0.05f).toDp() }, vertical = with(density) { (s * 0.04f).toDp() }),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        (if (playing) "▶  " else "❚❚  ") + o.video.name.substringBeforeLast('.'),
                        style = style(with(density) { (s * 0.048f).toSp() }),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (o.loop) Text("Loop", style = style(with(density) { (s * 0.032f).toSp() }))
                }
                Spacer(Modifier.height(with(density) { (s * 0.02f).toDp() }))
                SeekBar(position = { player.currentPosition }, duration = { player.duration }, textSize = with(density) { (s * 0.032f).toSp() })
                Spacer(Modifier.height(with(density) { (s * 0.02f).toDp() }))
                Box(Modifier.align(Alignment.End)) {
                    hints(listOf("@confirm" to "Play/Pause", "←→" to "10 s", "L1/R1" to "1 min", "↑↓" to "Prev/Next", "@options" to "Loop"))
                }
            }
        }
    }
}

/** Elapsed / remaining time with a progress bar; polls the player 4× a second. */
@Composable
private fun SeekBar(position: () -> Long, duration: () -> Long, textSize: TextUnit) {
    var pos by remember { mutableLongStateOf(position()) }
    var dur by remember { mutableLongStateOf(duration()) }
    LaunchedEffect(Unit) {
        while (isActive) {
            pos = position().coerceAtLeast(0)
            dur = duration().coerceAtLeast(0)
            delay(250)
        }
    }
    val fraction = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
    val animated by animateFloatAsState(fraction, tween(240), label = "seek")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(MediaMenus.duration(pos) ?: "0:00", style = style(textSize))
        Box(
            Modifier
                .weight(1f)
                .height(4.dp)
                .clip(RoundedCornerShape(50))
                .background(Color(0x55FFFFFF))
                .drawBehind { drawRect(Color.White, size = Size(size.width * animated, size.height)) },
        )
        Text(MediaMenus.duration(dur) ?: "--:--", style = style(textSize))
    }
}

// endregion

// region Now playing

@Composable
private fun NowPlayingScreen(
    now: NowPlaying,
    music: MusicPlayer,
    loadThumb: suspend (ThumbKind, Long, Int) -> ImageBitmap?,
    hints: @Composable (List<Pair<String, String>>) -> Unit,
) {
    val song = now.song ?: return
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color(0x99000000)),
        contentAlignment = Alignment.Center,
    ) {
        val density = LocalDensity.current
        val s = min(constraints.maxWidth, constraints.maxHeight).toFloat()
        val artPx = (s * 0.5f).toInt()
        val art by produceState<ImageBitmap?>(null, song.id) { value = loadThumb(ThumbKind.ALBUM_ART, song.id, artPx) }
        val landscape = constraints.maxWidth > constraints.maxHeight

        val artBox: @Composable () -> Unit = {
            Box(
                Modifier
                    .size(with(density) { artPx.toDp() })
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0x33FFFFFF)),
                contentAlignment = Alignment.Center,
            ) {
                art?.let { Image(it, contentDescription = song.album, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                    ?: Text("♪", style = style(with(density) { (s * 0.15f).toSp() }))
            }
        }
        val info: @Composable () -> Unit = {
            Column(Modifier.width(with(density) { (s * 0.9f).toDp() })) {
                Text(if (now.isPlaying) "Now playing" else "Paused", style = style(with(density) { (s * 0.032f).toSp() }, color = Color(0xB3FFFFFF)))
                Text(song.title, style = style(with(density) { (s * 0.06f).toSp() }, FontWeight.Medium), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${song.artist} · ${song.album}", style = style(with(density) { (s * 0.038f).toSp() }, color = Color(0xD9FFFFFF)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(with(density) { (s * 0.04f).toDp() }))
                SeekBar(position = { music.positionMs }, duration = { music.durationMs }, textSize = with(density) { (s * 0.032f).toSp() })
                Spacer(Modifier.height(with(density) { (s * 0.02f).toDp() }))
                Text(
                    listOfNotNull(
                        "Track ${now.index + 1} of ${now.queueSize}",
                        if (now.shuffle) "Shuffle" else null,
                        if (now.repeatAll) "Repeat all" else null,
                    ).joinToString("  ·  "),
                    style = style(with(density) { (s * 0.03f).toSp() }, color = Color(0xB3FFFFFF)),
                )
            }
        }
        if (landscape) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(with(density) { (s * 0.08f).toDp() })) {
                artBox()
                info()
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(with(density) { (s * 0.06f).toDp() })) {
                artBox()
                info()
            }
        }
        Box(Modifier.align(Alignment.BottomEnd).padding(with(density) { (s * 0.04f).toDp() })) {
            hints(listOf("@confirm" to "Play/Pause", "←→" to "Track", "L1/R1" to "Seek", "@options" to "Shuffle", "↑" to "Repeat"))
        }
    }
}

// endregion
