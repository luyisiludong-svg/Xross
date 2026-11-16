package dev.xross.launcher.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.xross.launcher.model.GlyphKey
import dev.xross.launcher.profile.FriendsState
import dev.xross.launcher.model.ArtKind
import dev.xross.launcher.state.FriendStrip
import dev.xross.launcher.state.GameTelemetry
import dev.xross.launcher.state.TelemetryCard
import dev.xross.launcher.state.TelemetrySlot
import dev.xross.launcher.theme.pack.TelemetryMode
import kotlinx.coroutines.delay

/**
 * The focused game's RetroAchievements telemetry in the bottom corner, as `telemetryMode` (2b.7d)
 * draws it: nothing ([TelemetryMode.OFF]), the compact pill (today's), or the rich card. [hidden]:
 * an overlay or the avatar's HUD covers the corner.
 */
@Composable
internal fun GameTelemetryCorner(
    mode: TelemetryMode,
    telemetry: GameTelemetry?,
    hidden: Boolean,
    friends: FriendsState,
    size: TextUnit,
    loadArt: suspend (String, List<ArtKind>, Int) -> ImageBitmap?,
    loadBadge: suspend (String, Int) -> ImageBitmap?,
) {
    when (mode) {
        TelemetryMode.OFF -> Unit
        TelemetryMode.COMPACT_PILL -> GameTelemetryBadge(telemetry.takeIf { !hidden }, size)
        TelemetryMode.RICH_CARD -> GameTelemetryRichCard(telemetry, hidden, friends, size, loadArt, loadBadge)
    }
}

/** How long the card stays (dimmed) after the focus leaves a game, so moving between games crossfades instead of collapsing. */
private const val CARD_LINGER_MS = 700L

private val CardShape = RoundedCornerShape(14.dp)
private val SlotShape = RoundedCornerShape(6.dp)
private val CARD_ART = listOf(ArtKind.BOX_2D, ArtKind.GRID, ArtKind.BOX_3D, ArtKind.SCREENSHOT)
private val Greyed = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

/**
 * `RICH_CARD`: the game's square art, its platform pill, the [TelemetryCard.SLOTS] newest unlocks
 * (locked ones greyed), a progress bar with "18/42 · 120/400 pts", and a `RECENTLY PLAYED` strip
 * of followed users whose latest game this is. The card keeps its last game for
 * [CARD_LINGER_MS] while the focus moves on (dimmed), then crossfades to the next game's or fades
 * out. Art and badges load off the main thread, one card's worth at a time: leaving a game
 * cancels its loads.
 */
@Composable
private fun GameTelemetryRichCard(
    telemetry: GameTelemetry?,
    hidden: Boolean,
    friends: FriendsState,
    size: TextUnit,
    loadArt: suspend (String, List<ArtKind>, Int) -> ImageBitmap?,
    loadBadge: suspend (String, Int) -> ImageBitmap?,
) {
    var held by remember { mutableStateOf(telemetry) }
    LaunchedEffect(telemetry) {
        if (telemetry != null) {
            held = telemetry
        } else {
            delay(CARD_LINGER_MS)
            held = null
        }
    }
    val stale by animateFloatAsState(if (telemetry == null) 0.55f else 1f, tween(180), label = "telemetryStale")
    AnimatedVisibility(
        visible = held != null && !hidden,
        enter = fadeIn(tween(220)) + expandVertically(tween(220), expandFrom = Alignment.Bottom),
        exit = fadeOut(tween(160)) + shrinkVertically(tween(200), shrinkTowards = Alignment.Bottom),
    ) {
        var last by remember { mutableStateOf(held) }
        held?.let { last = it }
        val shown = last ?: return@AnimatedVisibility
        // The top padding is the gap to the mini player above; it collapses with the card.
        // The last two games' telemetry, so the outgoing card keeps drawing while it fades.
        val recent = remember { LinkedHashMap<Long, GameTelemetry>() }
        recent.remove(shown.gameId)
        recent[shown.gameId] = shown
        while (recent.size > 2) recent.remove(recent.keys.first())
        Box(Modifier.padding(top = 8.dp).graphicsLayer { alpha = stale }) {
            // Keyed by game: a fresh copy of the same game's progress redraws in place. Every card
            // has the same size (only the strip adds a row), so a plain crossfade, no size animation.
            Crossfade(targetState = shown.gameId, animationSpec = tween(200), label = "telemetryCard") { id ->
                val t = recent[id] ?: return@Crossfade
                val strip = remember(t, friends) { TelemetryCard.friendStrip(t, friends) }
                RichCardBody(t, strip, size, loadArt, loadBadge)
            }
        }
    }
}

@Composable
private fun RichCardBody(
    t: GameTelemetry,
    strip: FriendStrip?,
    size: TextUnit,
    loadArt: suspend (String, List<ArtKind>, Int) -> ImageBitmap?,
    loadBadge: suspend (String, Int) -> ImageBitmap?,
) {
    val density = LocalDensity.current
    val text = with(density) { size.toDp() }
    val badge = text * 2.1f
    val gap = 5.dp
    val rowWidth = badge * TelemetryCard.SLOTS + gap * (TelemetryCard.SLOTS - 1)
    val pillRow = text * 1.6f
    val art = badge + pillRow + 6.dp
    val progress = TelemetryCard.progressText(t)
    Column(
        Modifier
            .semantics(mergeDescendants = true) {
                contentDescription = "RetroAchievements: $progress" + if (t.mastered) ", mastered" else ""
            }
            .xrossSurface(CardShape, strength = 0.92f, borderAlpha = if (t.mastered) 0.9f else 0.5f, borderScale = 2f / 3f)
            .padding(12.dp)
            .width(art + 10.dp + rowWidth),
    ) {
        Row {
            CardArt(t.uuid, art, loadArt)
            Spacer(Modifier.width(10.dp))
            Column {
                Row(Modifier.height(pillRow), verticalAlignment = Alignment.CenterVertically) {
                    if (t.platform.isNotBlank()) Pill(t.platform.uppercase(), size * 0.72f)
                    if (t.mastered) {
                        Spacer(Modifier.width(6.dp))
                        Pill("★ MASTERED", size * 0.72f, color = Gold, background = Gold.copy(alpha = 0.22f))
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    for (i in 0 until TelemetryCard.SLOTS) BadgeSlot(t.slots.getOrNull(i), badge, loadBadge)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        ProgressBar(TelemetryCard.completion(t), height = 6.dp, fill = if (t.mastered) androidx.compose.ui.graphics.SolidColor(Gold) else null)
        Spacer(Modifier.height(5.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(glyphPainter(GlyphKey.TROPHY), null, colorFilter = ColorFilter.tint(Pastel.Amber), modifier = Modifier.size(text * 1.05f))
            Spacer(Modifier.width(6.dp))
            Text(
                progress,
                style = shadowed(size * 0.92f, weight = FontWeight.Bold).copy(fontFamily = LocalThemeTypography.current.monoOrBody),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        strip?.let { FriendStripRow(it, size, text) }
    }
}

/** The library's box art, cropped square; the trophy while there is none. */
@Composable
private fun CardArt(uuid: String, side: Dp, loadArt: suspend (String, List<ArtKind>, Int) -> ImageBitmap?) {
    val px = with(LocalDensity.current) { side.roundToPx() }
    val image by produceState<ImageBitmap?>(null, uuid, px) { value = if (uuid.isEmpty()) null else loadArt(uuid, CARD_ART, px) }
    Box(
        Modifier
            .size(side)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x22FFFFFF))
            .border(1.dp, Color(0x55FFFFFF), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        image?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            ?: Image(
                glyphPainter(GlyphKey.TROPHY),
                null,
                colorFilter = ColorFilter.tint(Pastel.Amber.copy(alpha = 0.7f)),
                modifier = Modifier.fillMaxSize().padding(side * 0.22f),
            )
    }
}

/** One badge of the row: in color when unlocked, greyed when locked, an empty cell past the set's end. */
@Composable
private fun BadgeSlot(slot: TelemetrySlot?, side: Dp, loadBadge: suspend (String, Int) -> ImageBitmap?) {
    val px = with(LocalDensity.current) { side.roundToPx() }
    val image by produceState<ImageBitmap?>(null, slot?.badgeName, px) { value = slot?.let { loadBadge(it.badgeName, px) } }
    Box(
        Modifier
            .size(side)
            .clip(SlotShape)
            .background(Color(if (slot?.unlocked == true) 0x26FFFFFF else 0x14FFFFFF))
            .border(1.dp, Color(if (slot?.unlocked == true) 0x66FFFFFF else 0x26FFFFFF), SlotShape),
    ) {
        image?.let {
            Image(
                it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = if (slot?.unlocked == true) null else Greyed,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (slot?.unlocked == true) 1f else 0.38f },
            )
        }
    }
}

/** `RECENTLY PLAYED`: up to three friend avatars who last played this game, then "+N". */
@Composable
private fun FriendStripRow(strip: FriendStrip, size: TextUnit, text: Dp) {
    Spacer(Modifier.height(9.dp))
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x33FFFFFF)))
    Spacer(Modifier.height(7.dp))
    val names = strip.friends.joinToString { it.user } + if (strip.more > 0) " and ${strip.more} more" else ""
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics { contentDescription = "Recently played by $names" },
    ) {
        Text(
            "RECENTLY PLAYED",
            style = shadowed(size * 0.7f, color = TextDim, weight = FontWeight.Bold).copy(letterSpacing = 0.08.em),
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
            strip.friends.forEach { f -> ProfileAvatar(f.avatar, f.avatarVersion, text * 1.7f, contentDescription = null) }
            if (strip.more > 0) Text(strip.moreText, style = shadowed(size * 0.85f, color = TextDim, weight = FontWeight.Bold), maxLines = 1)
        }
    }
}
