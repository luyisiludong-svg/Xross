package dev.xross.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.xross.launcher.profile.AchievementsState
import dev.xross.launcher.profile.RaAchievement
import dev.xross.launcher.profile.RaGameProgress
import dev.xross.launcher.profile.RaMatchKind
import dev.xross.launcher.scrape.ArtKind
import dev.xross.launcher.state.AchievementsViewState
import java.text.NumberFormat

/** Locked badges: greyscale and dimmed. */
internal val LockedBadgeFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

/**
 * A game's achievements, after the reference layout: a frosted sheet with the game's art, title,
 * system pill and an earned/total count over an amber progress bar; a grid of badges (unlocked in
 * color, locked greyed; the focused one ringed in amber and lifted); and the focused badge's
 * title, description, points, mode and date in a bar at the bottom.
 */
@Composable
internal fun AchievementsScreen(
    view: AchievementsViewState?,
    m: XmbMetrics,
    pulse: () -> Float,
    backGlyph: String,
    optionsGlyph: String,
    loadArt: suspend (String, List<ArtKind>, Int) -> ImageBitmap?,
    loadBadge: suspend (String, Int) -> ImageBitmap?,
    onBadgeTap: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var shown by remember { mutableStateOf(view) }
    if (view != null) shown = view
    val density = LocalDensity.current
    fun Float.dp() = with(density) { this@dp.toDp() }
    fun Float.sp() = with(density) { this@sp.toSp() }

    AnimatedVisibility(view != null, enter = fadeIn(tween(160)), exit = fadeOut(tween(200))) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xA6000000))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        )
    }
    AnimatedVisibility(
        view != null,
        enter = fadeIn(tween(200)) + scaleIn(tween(240), initialScale = 0.97f),
        exit = fadeOut(tween(180)) + scaleOut(tween(200), targetScale = 0.97f),
    ) {
        val v = shown ?: return@AnimatedVisibility
        val text = m.subtitleSize.sp()
        val shape = RoundedCornerShape(22.dp)
        Box(Modifier.fillMaxSize().padding(horizontal = (m.width * 0.05f).dp(), vertical = (m.height * 0.06f).dp()), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .fillMaxSize()
                    .xrossSurface(shape, borderAlpha = 0.55f)
                    .clickable(remember { MutableInteractionSource() }, indication = null) {}
                    .padding(horizontal = (m.s * 0.04f).dp(), vertical = (m.s * 0.03f).dp()),
            ) {
                Header(v, m, loadArt)
                Spacer(Modifier.height((m.s * 0.025f).dp()))
                when (val r = v.result) {
                    null -> Message("Loading achievements…", m, Modifier.weight(1f))
                    AchievementsState.NotLinked -> Message("Link your RetroAchievements account in Settings ▸ Accounts to see achievements here.", m, Modifier.weight(1f))
                    is AchievementsState.Unsupported -> Message("RetroAchievements has no achievements for this system.", m, Modifier.weight(1f))
                    AchievementsState.NoMatch -> Message(
                        "RetroAchievements doesn't recognize this file. Achievements need a supported dump (the hash must match); check the game's page on retroachievements.org.",
                        m,
                        Modifier.weight(1f),
                    )
                    is AchievementsState.Failed -> Message(r.reason + "\n$optionsGlyph tries again.", m, Modifier.weight(1f))
                    is AchievementsState.Ready -> if (r.progress.achievements.isEmpty()) {
                        Message("This game has no achievements yet.", m, Modifier.weight(1f))
                    } else {
                        BadgeGrid(v, m, pulse, loadBadge, onBadgeTap, Modifier.weight(1f))
                        Spacer(Modifier.height((m.s * 0.02f).dp()))
                        v.focused?.let { DetailBar(it, m, loadBadge) }
                    }
                }
                Spacer(Modifier.height((m.s * 0.015f).dp()))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.align(Alignment.End)) {
                    Hint("✥", "Browse", text)
                    Hint(optionsGlyph, "Refresh", text)
                    Hint(backGlyph, "Back", text)
                }
            }
        }
    }
}

@Composable
private fun Header(v: AchievementsViewState, m: XmbMetrics, loadArt: suspend (String, List<ArtKind>, Int) -> ImageBitmap?) {
    val density = LocalDensity.current
    fun Float.dp() = with(density) { this@dp.toDp() }
    fun Float.sp() = with(density) { this@sp.toSp() }
    val artPx = (m.s * 0.14f).toInt()
    val art by produceState<ImageBitmap?>(null, v.uuid, v.artVersion) { value = loadArt(v.uuid, HEADER_ART, artPx) }
    val ready = v.result as? AchievementsState.Ready
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size((m.s * 0.14f).dp())
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0x22FFFFFF))
                .border(1.dp, Color(0x55FFFFFF), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            art?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                ?: Image(glyphPainter(dev.xross.launcher.state.GlyphKey.TROPHY), null, modifier = Modifier.fillMaxSize().padding(10.dp))
        }
        Spacer(Modifier.width((m.s * 0.03f).dp()))
        Column(Modifier.weight(1f)) {
            Text(
                ready?.progress?.title?.takeIf { it.isNotBlank() } ?: v.title,
                style = shadowed((m.titleSize * 1.2f).sp()),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = buildList {
                ready?.let { r ->
                    add("${NumberFormat.getIntegerInstance().format(r.progress.earnedPoints)} of ${NumberFormat.getIntegerInstance().format(r.progress.totalPoints)} points")
                    if (r.progress.unlockedHardcore > 0) add("${r.progress.unlockedHardcore} hardcore")
                    if (r.matchedBy == RaMatchKind.TITLE) add("matched by title")
                    if (r.offline) add("offline copy")
                }
            }
            if (sub.isNotEmpty()) Text(sub.joinToString("  ·  "), style = shadowed(m.subtitleSize.sp(), color = TextDim), maxLines = 1)
        }
        Spacer(Modifier.width((m.s * 0.03f).dp()))
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.width((m.width * 0.26f).dp())) {
            val pill = ready?.progress?.consoleName?.takeIf { it.isNotBlank() } ?: v.platform
            pill?.let { Pill(it.uppercase(), (m.subtitleSize * 0.8f).sp()) }
            Spacer(Modifier.height(6.dp))
            if (ready != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TrophyCount(ready.progress.unlocked, ready.progress.total, m.subtitleSize.sp())
                    Spacer(Modifier.width(6.dp))
                    Image(glyphPainter(dev.xross.launcher.state.GlyphKey.TROPHY), null, colorFilter = ColorFilter.tint(Pastel.Amber), modifier = Modifier.size((m.subtitleSize * 1.1f).dp()))
                }
                Spacer(Modifier.height(5.dp))
                ProgressBar(ready.progress.completion, height = (m.s * 0.014f).dp().coerceAtLeast(6.dp))
                Text(percent(ready.progress), style = shadowed((m.subtitleSize * 0.8f).sp(), color = TextDim))
            }
        }
    }
}

private fun percent(p: RaGameProgress): String = "${Math.round(p.completion * 100)}% complete"

@Composable
private fun BadgeGrid(
    v: AchievementsViewState,
    m: XmbMetrics,
    pulse: () -> Float,
    loadBadge: suspend (String, Int) -> ImageBitmap?,
    onBadgeTap: (Int) -> Unit,
    modifier: Modifier,
) {
    val list = v.achievements
    val grid = rememberLazyGridState()
    // Keep the focused badge's row in view.
    LaunchedEffect(v.index) {
        val info = grid.layoutInfo
        val visible = info.visibleItemsInfo
        if (visible.isEmpty() || v.index < visible.first().index || v.index > visible.last().index) {
            grid.animateScrollToItem((v.index - AchievementsViewState.COLUMNS).coerceAtLeast(0))
        } else if (v.index >= visible.last().index - AchievementsViewState.COLUMNS + 1 && visible.last().index < list.lastIndex) {
            grid.animateScrollToItem((v.index - AchievementsViewState.COLUMNS * 2).coerceAtLeast(0))
        }
    }
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        // Cells as large as fit two and a half rows (wide screens) or the full width (tall ones).
        val cols = AchievementsViewState.COLUMNS
        val gap = 10.dp
        val byWidth = (maxWidth - 12.dp - gap * (cols - 1)) / cols
        val byHeight = (maxHeight - 16.dp - gap * 2) / 2.5f
        val cell = minOf(byWidth, byHeight).coerceAtLeast(36.dp)
        val badgePx = with(LocalDensity.current) { cell.roundToPx() }
        LazyVerticalGrid(
            columns = GridCells.Fixed(cols),
            state = grid,
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalArrangement = Arrangement.spacedBy(gap),
            modifier = Modifier.width(cell * cols + gap * (cols - 1) + 12.dp).fillMaxHeight().padding(horizontal = 6.dp, vertical = 8.dp),
        ) {
            itemsIndexed(list, key = { _, a -> a.id }) { i, a ->
                BadgeCell(a, focused = i == v.index, pulse = pulse, px = badgePx, loadBadge = loadBadge, onTap = { onBadgeTap(i) })
            }
        }
    }
}

@Composable
private fun BadgeCell(
    a: RaAchievement,
    focused: Boolean,
    pulse: () -> Float,
    px: Int,
    loadBadge: suspend (String, Int) -> ImageBitmap?,
    onTap: () -> Unit,
) {
    val image by produceState<ImageBitmap?>(null, a.badgeName, px) { value = loadBadge(a.badgeName, px) }
    val lift by animateFloatAsState(if (focused) 1f else 0f, tween(160), label = "badgeLift")
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .aspectRatio(1f)
            .graphicsLayer {
                val s = 1f + 0.12f * lift
                scaleX = s
                scaleY = s
            }
            .drawBehind {
                if (lift > 0f) {
                    drawCircle(
                        Brush.radialGradient(
                            0.5f to Pastel.Amber.copy(alpha = 0.45f * lift * (0.6f + 0.4f * pulse())),
                            1f to Color.Transparent,
                        ),
                        radius = size.minDimension * 0.85f,
                    )
                }
            }
            .clip(shape)
            .background(Color(0xFF1C1C28))
            .border(
                if (focused) 3.dp else 1.dp,
                if (focused) Brush.verticalGradient(listOf(Pastel.Amber, Pastel.AmberDeep)) else Brush.verticalGradient(listOf(Color(0x66FFB547), Color(0x33FFB547))),
                shape,
            )
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        image?.let {
            Image(
                it,
                contentDescription = a.title,
                contentScale = ContentScale.Crop,
                colorFilter = if (a.unlocked) null else LockedBadgeFilter,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (a.unlocked) 1f else 0.42f },
            )
        }
        if (a.hardcore) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Pastel.Amber),
            )
        }
    }
}

@Composable
private fun DetailBar(a: RaAchievement, m: XmbMetrics, loadBadge: suspend (String, Int) -> ImageBitmap?) {
    val density = LocalDensity.current
    fun Float.dp() = with(density) { this@dp.toDp() }
    fun Float.sp() = with(density) { this@sp.toSp() }
    val px = (m.s * 0.12f).toInt()
    val image by produceState<ImageBitmap?>(null, a.badgeName, px) { value = loadBadge(a.badgeName, px) }
    val shape = RoundedCornerShape(16.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0x40FFFFFF), Color(0x1AFFFFFF))))
            .border(1.dp, Color(0x40FFFFFF), shape)
            .padding(12.dp),
    ) {
        BadgeThumb(image, a.unlocked, (m.s * 0.12f).dp())
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(a.title, style = shadowed((m.titleSize * 1.05f).sp(), weight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(a.description, style = shadowed(m.subtitleSize.sp(), color = TextDim), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(14.dp))
        Column(horizontalAlignment = Alignment.End) {
            Pill("${a.points} PTS", (m.subtitleSize * 0.85f).sp())
            Spacer(Modifier.height(6.dp))
            val (label, color) = when {
                a.hardcore -> "HARDCORE" to Pastel.Amber
                a.unlocked -> "SOFTCORE" to Pastel.Sky
                else -> "LOCKED" to Color(0xB3FFFFFF)
            }
            Text(label, style = shadowed((m.subtitleSize * 0.9f).sp(), color = color, weight = FontWeight.Bold))
            a.unlockDate?.let { Text(GameDateFormat.short(it), style = shadowed((m.subtitleSize * 0.8f).sp(), color = TextDim)) }
        }
    }
}

@Composable
internal fun BadgeThumb(image: ImageBitmap?, unlocked: Boolean, size: Dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF1C1C28))
            .border(2.dp, if (unlocked) Pastel.Amber else Color(0x55FFFFFF), RoundedCornerShape(10.dp)),
    ) {
        image?.let {
            Image(it, null, contentScale = ContentScale.Crop, colorFilter = if (unlocked) null else LockedBadgeFilter, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun Message(text: String, m: XmbMetrics, modifier: Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(text, style = shadowed(with(LocalDensity.current) { m.subtitleSize.toSp() }, color = TextDim), modifier = Modifier.padding(24.dp))
    }
}

/** RetroAchievements dates ("2024-03-01 18:02:11", UTC) in the device's short date format. */
internal object GameDateFormat {
    fun short(raw: String): String {
        val parsed = runCatching {
            java.time.LocalDateTime.parse(raw.trim().replace(' ', 'T')).atZone(java.time.ZoneOffset.UTC)
                .withZoneSameInstant(java.time.ZoneId.systemDefault())
        }.getOrNull() ?: return raw.take(10)
        return parsed.format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM))
    }
}

private val HEADER_ART = listOf(ArtKind.BOX_2D, ArtKind.GRID, ArtKind.BOX_3D, ArtKind.SCREENSHOT)
