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
import dev.xross.launcher.theme.pack.FocusFrameStyle
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import dev.xross.launcher.state.AchievementsGrid
import dev.xross.launcher.state.AchievementsLayout
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
    /** The grid measured how many badges fit across (2b.7e). */
    onColumns: (Int) -> Unit = {},
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
                        BadgeGrid(v, pulse, loadBadge, onBadgeTap, onColumns, Modifier.weight(1f))
                        Spacer(Modifier.height((m.s * 0.02f).dp()))
                        v.focused?.let { Banner(it, m, loadBadge) }
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
                ?: Image(glyphPainter(dev.xross.launcher.model.GlyphKey.TROPHY), null, modifier = Modifier.fillMaxSize().padding(10.dp))
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
                    if (r.progress.unlockedHardcore > 0) add("${r.progress.unlockedHardcore} hardcore")
                    if (r.matchedBy == RaMatchKind.TITLE) add("matched by title")
                    if (r.offline) add("offline copy")
                }
            }
            if (sub.isNotEmpty()) Text(sub.joinToString("  ·  "), style = shadowed(m.subtitleSize.sp(), color = TextDim), maxLines = 1)
        }
        Spacer(Modifier.width((m.s * 0.03f).dp()))
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.width((m.width * 0.3f).dp())) {
            val pill = ready?.progress?.consoleName?.takeIf { it.isNotBlank() } ?: v.platform
            pill?.let { Pill(it.uppercase(), (m.subtitleSize * 0.8f).sp()) }
            Spacer(Modifier.height(6.dp))
            if (ready != null) {
                // "39/151 🏆" large, the points beside it (2b.7e).
                val n = NumberFormat.getIntegerInstance()
                val count = "${ready.progress.unlocked}/${ready.progress.total}"
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.semantics(mergeDescendants = true) {
                        contentDescription = "$count achievements, ${n.format(ready.progress.earnedPoints)} of ${n.format(ready.progress.totalPoints)} points"
                    },
                ) {
                    Text(
                        "${n.format(ready.progress.earnedPoints)}/${n.format(ready.progress.totalPoints)} pts",
                        style = shadowed((m.subtitleSize * 0.85f).sp(), color = TextDim, weight = FontWeight.Bold),
                        maxLines = 1,
                        softWrap = false,
                    )
                    Spacer(Modifier.width(10.dp))
                    TrophyCount(ready.progress.unlocked, ready.progress.total, (m.titleSize * 1.25f).sp())
                    Spacer(Modifier.width(6.dp))
                    Image(glyphPainter(dev.xross.launcher.model.GlyphKey.TROPHY), null, colorFilter = ColorFilter.tint(Pastel.Amber), modifier = Modifier.size((m.titleSize * 1.15f).dp()))
                }
                Spacer(Modifier.height(5.dp))
                ProgressBar(ready.progress.completion, height = (m.s * 0.014f).dp().coerceAtLeast(6.dp))
                Text(percent(ready.progress), style = shadowed((m.subtitleSize * 0.8f).sp(), color = TextDim))
            }
        }
    }
}

private fun percent(p: RaGameProgress): String = "${Math.round(p.completion * 100)}% complete"

/**
 * Exactly [AchievementsViewState.ROWS] rows of badges, as many across as the width takes
 * ([AchievementsLayout]); the view's [AchievementsViewState.top] row is the first shown, so the
 * grid scrolls a row at a time as the focus crosses its top or bottom row.
 */
@Composable
private fun BadgeGrid(
    v: AchievementsViewState,
    pulse: () -> Float,
    loadBadge: suspend (String, Int) -> ImageBitmap?,
    onBadgeTap: (Int) -> Unit,
    onColumns: (Int) -> Unit,
    modifier: Modifier,
) {
    val list = v.achievements
    val grid = rememberLazyGridState()
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        val gap = 10.dp
        // Room for the focused badge's lift (12%) and bloom at the edges.
        val pad = 10.dp
        val (cols, cellValue) = AchievementsLayout.fit((maxWidth - pad * 2).value, (maxHeight - pad * 2).value, gap.value)
        val cell = cellValue.dp
        LaunchedEffect(cols) { onColumns(cols) }
        // The state's columns lag the first measure by a frame; lay out by what fits now.
        val top = if (v.columns == cols) v.top else v.copy(columns = cols).settled().top
        LaunchedEffect(top, cols) {
            if (grid.firstVisibleItemIndex != top * cols) grid.animateScrollToItem(top * cols)
        }
        val badgePx = with(LocalDensity.current) { cell.roundToPx() }
        LazyVerticalGrid(
            columns = GridCells.Fixed(cols),
            state = grid,
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalArrangement = Arrangement.spacedBy(gap),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(pad),
            modifier = Modifier
                .width(cell * cols + gap * (cols - 1) + pad * 2)
                .height(cell * AchievementsViewState.ROWS + gap * (AchievementsViewState.ROWS - 1) + pad * 2),
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
    // The theme's focus frame (2b.4d): the amber bloom (breathing or steady), a hard shadow or a gold bezel.
    val frame = LocalFocusFrame.current
    val bloom = frame.style == FocusFrameStyle.BREATHING_HALO || frame.style == FocusFrameStyle.STATIC_NEON
    Box(
        Modifier
            .aspectRatio(1f)
            .graphicsLayer {
                val s = 1f + 0.12f * lift
                scaleX = s
                scaleY = s
            }
            .drawBehind {
                if (lift <= 0f) return@drawBehind
                if (bloom) {
                    drawCircle(
                        Brush.radialGradient(
                            0.5f to Pastel.Amber.copy(alpha = 0.45f * lift * (0.6f + 0.4f * pulse())),
                            1f to Color.Transparent,
                        ),
                        radius = size.minDimension * 0.85f,
                    )
                } else {
                    drawPlateFrame(frame, lift, 12.dp.toPx())
                }
            }
            .clip(shape)
            .background(Color(0xFF1C1C28))
            .border(
                if (focused) 3.dp else 1.dp,
                when {
                    !focused -> Brush.verticalGradient(listOf(Color(0x66FFB547), Color(0x33FFB547)))
                    frame.style == FocusFrameStyle.GOLD_BEZEL -> metalBrush(frame.gold, androidx.compose.ui.geometry.Offset.Zero, androidx.compose.ui.geometry.Offset.Infinite)
                    else -> Brush.verticalGradient(listOf(Pastel.Amber, Pastel.AmberDeep))
                },
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

/**
 * The focused badge's details as a glossy banner (2b.7e): the pack's surface with a sheen over its
 * top half, the badge, title and description, then the points, hardcore or softcore and the
 * unlock date, or "Locked".
 */
@Composable
private fun Banner(a: RaAchievement, m: XmbMetrics, loadBadge: suspend (String, Int) -> ImageBitmap?) {
    val density = LocalDensity.current
    fun Float.dp() = with(density) { this@dp.toDp() }
    fun Float.sp() = with(density) { this@sp.toSp() }
    val px = (m.s * 0.12f).toInt()
    val image by produceState<ImageBitmap?>(null, a.badgeName, px) { value = loadBadge(a.badgeName, px) }
    val shape = RoundedCornerShape(18.dp)
    val status = AchievementsGrid.status(a)
    val date = a.unlockDate?.let { GameDateFormat.short(it) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = listOfNotNull(a.title, a.description.ifBlank { null }, "${a.points} points", status, date).joinToString(", ")
            }
            .xrossSurface(shape, borderAlpha = 0.7f)
            .drawBehind {
                // Over the surface, under the text. The gloss: a soft white sheen fading out across the top half, and a bright rim line.
                drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.2f), 0.5f to Color.White.copy(alpha = 0.04f), 0.5001f to Color.Transparent), size = size)
                drawLine(Color.White.copy(alpha = 0.45f), androidx.compose.ui.geometry.Offset(size.height * 0.4f, 1.5f), androidx.compose.ui.geometry.Offset(size.width - size.height * 0.4f, 1.5f), strokeWidth = 1.5f)
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        BadgeThumb(image, a.unlocked, (m.s * 0.12f).dp())
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(a.title, style = shadowed((m.titleSize * 1.05f).sp(), weight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(a.description, style = shadowed(m.subtitleSize.sp(), color = TextDim), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(14.dp))
        Column(horizontalAlignment = Alignment.End) {
            Pill("${a.points} PTS", (m.subtitleSize * 0.85f).sp(), color = Pastel.Amber)
            Spacer(Modifier.height(6.dp))
            val color = when {
                a.hardcore -> Pastel.Amber
                a.unlocked -> Pastel.Sky
                else -> Color(0xB3FFFFFF)
            }
            Text(status, style = shadowed((m.subtitleSize * 0.9f).sp(), color = color, weight = FontWeight.Bold))
            date?.let { Text(it, style = shadowed((m.subtitleSize * 0.8f).sp(), color = TextDim)) }
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
