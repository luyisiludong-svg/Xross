package dev.xross.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.xross.launcher.media.NowPlaying
import dev.xross.launcher.media.Song
import dev.xross.launcher.media.ThumbKind
import dev.xross.launcher.state.MiniPlayerLine
import dev.xross.launcher.state.MiniPlayerVisibility

private val CardShape = RoundedCornerShape(14.dp)
private val ArtShape = RoundedCornerShape(8.dp)

/**
 * Floating "now playing" card for the lower-right corner: album art, title (scrolling when it
 * doesn't fit), artist and a play/pause glyph. Shown while a track is loaded; a pause hides it
 * after [MiniPlayerLine.PAUSED_HIDE_MS] (see [MiniPlayerVisibility]). [covered] hides it at once
 * while something else owns the screen. Tap opens the full audio controls; long press plays or
 * pauses.
 */
@Composable
fun MiniPlayerCard(
    nowPlaying: NowPlaying,
    covered: Boolean,
    loadThumb: suspend (ThumbKind, Long, Int) -> ImageBitmap?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val visibility = remember(scope) { MiniPlayerVisibility(scope) }
    LaunchedEffect(nowPlaying, covered) { visibility.update(nowPlaying, covered) }
    val visible by visibility.visible.collectAsState()

    // Keep the last track so the fade-out still has something to draw after the queue clears.
    var shown by remember { mutableStateOf<Pair<Song, Boolean>?>(null) }
    nowPlaying.song?.let { shown = it to nowPlaying.isPlaying }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(MiniPlayerLine.FADE_IN_MS)),
        exit = fadeOut(tween(MiniPlayerLine.FADE_OUT_MS)),
        modifier = modifier,
    ) {
        val (song, playing) = shown ?: return@AnimatedVisibility
        CardBody(song, playing, loadThumb, onClick, onLongClick)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CardBody(
    song: Song,
    playing: Boolean,
    loadThumb: suspend (ThumbKind, Long, Int) -> ImageBitmap?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val artPx = with(LocalDensity.current) { 40.dp.roundToPx() }
    // Same art source as Now Playing: the track's embedded cover through MediaStore.
    val art by produceState<ImageBitmap?>(null, song.id) { value = loadThumb(ThumbKind.ALBUM_ART, song.id, artPx) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .widthIn(min = 160.dp, max = 280.dp)
            .shadow(10.dp, CardShape, clip = false)
            .clip(CardShape)
            .background(Color(0xD9141420))
            .border(1.dp, Color(0x33FFFFFF), CardShape)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .semantics { contentDescription = MiniPlayerLine.text(NowPlaying(song = song, isPlaying = playing)).orEmpty() }
            .padding(8.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(40.dp).clip(ArtShape).background(Color(0x33FFFFFF)),
        ) {
            val image = art
            if (image != null) {
                Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Image(
                    glyphPainter(dev.xross.launcher.model.GlyphKey.MUSIC),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(Color(0xCCFFFFFF)),
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Column(Modifier.weight(1f, fill = false)) {
            Text(
                song.title,
                color = TextBright,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                // Scrolls only when the title overflows; Compose leaves short titles still.
                modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1500),
            )
            Text(
                song.artist,
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        PlayStateGlyph(playing, Modifier.size(12.dp))
    }
}

/** Play triangle while playing, pause bars while paused: subtle, like a status LED. */
@Composable
private fun PlayStateGlyph(playing: Boolean, modifier: Modifier) {
    val tint = Color.White.copy(alpha = 0.7f)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        if (playing) {
            val path = Path().apply {
                moveTo(w * 0.18f, h * 0.08f)
                lineTo(w * 0.92f, h * 0.5f)
                lineTo(w * 0.18f, h * 0.92f)
                close()
            }
            drawPath(path, tint)
        } else {
            val bar = w * 0.26f
            drawRect(tint, Offset(w * 0.16f, h * 0.1f), Size(bar, h * 0.8f))
            drawRect(tint, Offset(w * 0.58f, h * 0.1f), Size(bar, h * 0.8f))
        }
    }
}
