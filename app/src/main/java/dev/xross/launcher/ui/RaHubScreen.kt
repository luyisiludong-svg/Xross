package dev.xross.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import dev.xross.launcher.profile.ProfileState
import dev.xross.launcher.profile.RaBadge
import dev.xross.launcher.profile.RaLibrarySummary
import dev.xross.launcher.profile.RaMasteredGame
import dev.xross.launcher.scrape.ArtKind
import dev.xross.launcher.model.GlyphKey
import dev.xross.launcher.profile.FriendsState
import dev.xross.launcher.state.HubFocus
import dev.xross.launcher.state.HubSidebar
import dev.xross.launcher.state.RaController
import dev.xross.launcher.state.RaHubState
import dev.xross.launcher.state.SidebarRow
import dev.xross.launcher.state.SocialStack
import dev.xross.launcher.theme.AvatarStyleConfig
import java.text.NumberFormat
import java.util.Locale

/**
 * Games ▸ RetroAchievements: the profile dashboard, after the reference layout. A frosted
 * identity card on the left (avatar, user name, rank, member since, hardcore / softcore points,
 * RetroRatio); on the right, scrolling: the completion gauge across the library, the mastery
 * showcase (fully mastered games in gold laurel frames) and the recent activity feed.
 *
 * Without a linked account, or before anything could be fetched, the right side explains what
 * to do instead; everything shown otherwise comes from the offline copies in the Xross folder,
 * so the screen works without a network.
 */
@Composable
internal fun RaHubScreen(
    hub: RaHubState?,
    profile: ProfileState,
    style: AvatarStyleConfig,
    m: XmbMetrics,
    confirmGlyph: String,
    backGlyph: String,
    loadArt: suspend (String, List<ArtKind>, Int) -> ImageBitmap?,
    loadBadge: suspend (String, Int) -> ImageBitmap?,
    onDismiss: () -> Unit,
    /** Followed users (2b.7c), for the `FOLLOWING` sidebar (2b.7e). */
    friends: FriendsState = FriendsState.Unlinked,
    onPillTap: (Int) -> Unit = {},
    onRowTap: (Int) -> Unit = {},
) {
    var shown by remember { mutableStateOf(hub) }
    if (hub != null) shown = hub
    val density = LocalDensity.current
    fun Float.dp() = with(density) { this@dp.toDp() }
    fun Float.sp() = with(density) { this@sp.toSp() }

    AnimatedVisibility(hub != null, enter = fadeIn(tween(160)), exit = fadeOut(tween(200))) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xA6000000))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        )
    }
    AnimatedVisibility(
        hub != null,
        enter = fadeIn(tween(200)) + scaleIn(tween(240), initialScale = 0.97f),
        exit = fadeOut(tween(180)) + scaleOut(tween(200), targetScale = 0.97f),
    ) {
        val state = shown ?: return@AnimatedVisibility
        val text = m.subtitleSize.sp()
        val gap = (m.s * 0.03f).dp()
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = (m.width * 0.045f).dp(), vertical = (m.height * 0.055f).dp())
                .clickable(remember { MutableInteractionSource() }, indication = null) {},
        ) {
            val rows = remember(friends, profile) { HubSidebar.rows(friends, RaController.selfRow(profile)) }
            val portrait = m.height > m.width
            val pills = state.pills
            Row(Modifier.weight(1f)) {
                val identity = if (portrait) m.width * 0.32f else (m.width * 0.3f).coerceAtLeast(m.s * 0.5f)
                IdentityCard(profile, style, m, Modifier.width(identity.dp()).fillMaxHeight())
                Spacer(Modifier.width(gap))
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    // L1/R1 platform filter (2b.7e): All, then each system with tracked games.
                    if (profile.linked && pills.size > 1) {
                        FilterPills(pills, state.filter, m, onPillTap)
                        Spacer(Modifier.height((m.s * 0.02f).dp()))
                    }
                    val scroll = rememberScrollState()
                    val step = (m.s * 0.14f).toInt()
                    LaunchedEffect(state.scroll, state.filter) { scroll.animateScrollTo(state.scroll * step) }
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(gap)) {
                    val p = profile.profile
                    val filtered = state.shown
                    when {
                        !profile.linked -> Placeholder(
                            "No RetroAchievements account linked",
                            "Link your account with your user name and Web API key to see your rank, points, mastered games and recent unlocks here.\n\n$confirmGlyph  Link account",
                            m,
                        )
                        else -> {
                            if (p == null) {
                                Placeholder(
                                    if (profile.refreshing) "Fetching your profile…" else "Profile not available offline yet",
                                    "Nothing has been fetched for this account so far. Check the network connection, then press $confirmGlyph to try again.",
                                    m,
                                )
                            }
                            Completion(filtered, m)
                            Mastery(filtered?.mastered.orEmpty(), state.summary != null, state.artVersion, m, loadArt)
                            Activity(state.activity(p?.recentBadges.orEmpty()), p != null, m, loadBadge)
                        }
                    }
                    }
                }
                if (rows.isNotEmpty()) {
                    Spacer(Modifier.width(gap))
                    FollowingSidebar(
                        rows = rows,
                        friends = friends,
                        focused = if (state.focus == HubFocus.SIDEBAR) state.row.coerceIn(0, rows.size - 1) else -1,
                        m = m,
                        onRowTap = onRowTap,
                        modifier = Modifier.width((m.width * if (portrait) 0.3f else 0.24f).dp()).fillMaxHeight(),
                    )
                }
            }
            Spacer(Modifier.height((m.s * 0.015f).dp()))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.align(Alignment.End)) {
                val inSidebar = state.focus == HubFocus.SIDEBAR && rows.isNotEmpty()
                if (profile.linked && pills.size > 1) Hint("L1 R1", "Platform", text)
                when {
                    inSidebar -> {
                        Hint("↕", "Browse", text)
                        Hint("←", "Dashboard", text)
                        Hint(confirmGlyph, "Open game", text)
                    }
                    else -> {
                        if (profile.linked) Hint("↕", "Scroll", text)
                        if (rows.isNotEmpty()) Hint("→", "Following", text)
                        Hint(confirmGlyph, if (profile.linked) "Refresh" else "Link account", text)
                    }
                }
                Hint(backGlyph, "Back", text)
            }
        }
    }
}

private val CardShape = RoundedCornerShape(20.dp)

@Composable
private fun Section(title: String, m: XmbMetrics, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val text = with(density) { m.subtitleSize.toSp() }
    Column(
        modifier
            .fillMaxWidth()
            .xrossSurface(CardShape, borderAlpha = 0.45f, borderScale = 2f / 3f)
            .padding(horizontal = with(density) { (m.s * 0.035f).toDp() }, vertical = with(density) { (m.s * 0.028f).toDp() }),
    ) {
        Text(title, style = shadowed(text * 0.8f, color = Pastel.Lilac, weight = FontWeight.Bold).copy(letterSpacing = text * 0.12f))
        Spacer(Modifier.height(with(density) { (m.s * 0.018f).toDp() }))
        content()
    }
}

@Composable
private fun Placeholder(title: String, body: String, m: XmbMetrics) {
    val text = with(LocalDensity.current) { m.subtitleSize.toSp() }
    Section("RETROACHIEVEMENTS", m) {
        Text(title, style = shadowed(text * 1.2f, weight = FontWeight.Bold))
        Spacer(Modifier.height(6.dp))
        Text(body, style = shadowed(text, color = TextDim))
    }
}

/** Header card: full player identity. */
@Composable
private fun IdentityCard(profile: ProfileState, style: AvatarStyleConfig, m: XmbMetrics, modifier: Modifier) {
    val density = LocalDensity.current
    fun Float.dp() = with(density) { this@dp.toDp() }
    val text = with(density) { m.subtitleSize.toSp() }
    val number = remember { NumberFormat.getIntegerInstance() }
    val p = profile.profile
    // The status line is pinned to the bottom; on a short screen only the identity above it scrolls.
    Column(
        modifier
            .xrossSurface(CardShape, borderAlpha = 0.6f)
            .padding(horizontal = (m.s * 0.035f).dp(), vertical = (m.s * 0.035f).dp()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "RETROACHIEVEMENTS",
                style = shadowed(text * 0.8f, color = Pastel.Lilac, weight = FontWeight.Bold).copy(letterSpacing = text * 0.12f),
            )
            Spacer(Modifier.height((m.s * 0.02f).dp()))
            ProfileAvatar(
                avatar = profile.avatar,
                avatarVersion = profile.avatarVersion,
                size = (m.s * 0.18f).dp(),
                contentDescription = profile.username.ifEmpty { "Profile" },
                style = style,
                modifier = Modifier.padding((m.s * 0.015f).dp()),
            )
            Spacer(Modifier.height((m.s * 0.01f).dp()))
            GlowText(p?.username ?: profile.username.ifEmpty { "Guest" }, fontSize = with(density) { (m.titleSize * 1.2f).toSp() }, glow = { 0.6f })
            Text(
                when {
                    !profile.linked -> "Not linked"
                    p?.rank != null -> "Global rank #${number.format(p.rank)}" + (p.totalRanked?.let { " of ${number.format(it)}" } ?: "")
                    p != null -> "Unranked"
                    else -> "Offline"
                },
                style = shadowed(text, color = if (p?.rank != null) Pastel.Sky else TextDim, weight = FontWeight.Bold),
                textAlign = TextAlign.Center,
            )
            p?.memberSince?.let {
                Text("Member since ${GameDateFormat.short(it)}", style = shadowed(text * 0.88f, color = TextDim))
            }
            if (p != null) {
                Spacer(Modifier.height((m.s * 0.03f).dp()))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val icon = (m.subtitleSize * 1.4f).dp()
                    Image(glyphPainter(GlyphKey.TROPHY), null, colorFilter = ColorFilter.tint(Pastel.Amber), modifier = Modifier.size(icon))
                    Spacer(Modifier.width(6.dp))
                    Text(number.format(p.totalPoints), style = shadowed(text * 1.6f, color = Pastel.Amber, weight = FontWeight.Bold))
                }
                Label("HARDCORE POINTS", text)
                Spacer(Modifier.height((m.s * 0.02f).dp()))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Figure(number.format(p.softcorePoints), "SOFTCORE", text)
                    Figure(number.format(p.truePoints), "RETROPOINTS", text)
                    Figure(p.retroRatio?.let { String.format(Locale.getDefault(), "%.2f", it) } ?: "—", "RATIO", text)
                }
                p.motto?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height((m.s * 0.02f).dp()))
                    Text("“$it”", style = shadowed(text * 0.9f, color = TextDim), maxLines = 3, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                }
            }
        }
        Spacer(Modifier.height((m.s * 0.02f).dp()))
        Text(
            when {
                profile.refreshing -> "Refreshing…"
                !profile.linked -> "Settings ▸ Accounts links an account"
                p == null -> "Not fetched yet"
                else -> "Updated " + android.text.format.DateUtils.getRelativeTimeSpanString(
                    p.fetchedAtMs,
                    System.currentTimeMillis(),
                    android.text.format.DateUtils.MINUTE_IN_MILLIS,
                )
            },
            style = shadowed(text * 0.85f, color = TextDim),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Label(label: String, size: TextUnit) {
    Text(label, style = shadowed(size * 0.72f, color = TextDim, weight = FontWeight.Bold).copy(letterSpacing = size * 0.1f), maxLines = 1, softWrap = false)
}

@Composable
private fun Figure(value: String, label: String, size: TextUnit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = shadowed(size * 1.15f, weight = FontWeight.Bold))
        Label(label, size)
    }
}

/** Completion matrix: the unlock rate across every library game whose progress is known. */
@Composable
private fun Completion(summary: RaLibrarySummary?, m: XmbMetrics) {
    val density = LocalDensity.current
    val text = with(density) { m.subtitleSize.toSp() }
    val number = remember { NumberFormat.getIntegerInstance() }
    Section("LIBRARY COMPLETION", m) {
        when {
            summary == null -> Text("Reading the library…", style = shadowed(text, color = TextDim))
            summary.trackedGames == 0 -> Text(
                if (summary.supportedGames == 0) {
                    "No games in the library are on a system RetroAchievements covers yet."
                } else {
                    "No progress recorded yet. A game is counted here once its achievements have been shown " +
                        "(focus it in a list, or open its Details): ${number.format(summary.supportedGames)} games are waiting."
                },
                style = shadowed(text, color = TextDim),
            )
            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                Gauge(summary.completion, with(density) { (m.s * 0.2f).toDp() }, text)
                Spacer(Modifier.width(with(density) { (m.s * 0.04f).toDp() }))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        TrophyCount(summary.unlocked, summary.achievements, text * 1.25f)
                        // One line, also beside the sidebar (2b.7e): the gauge already says what is counted.
                        Text("  unlocked", style = shadowed(text, color = TextDim), maxLines = 1, softWrap = false)
                    }
                    Spacer(Modifier.height(6.dp))
                    ProgressBar(summary.completion, height = 9.dp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${number.format(summary.earnedPoints)} of ${number.format(summary.points)} points  ·  " +
                            "${number.format(summary.mastered.size)} mastered",
                        style = shadowed(text, weight = FontWeight.Bold),
                    )
                    Text(
                        "Across ${number.format(summary.trackedGames)} of ${number.format(summary.supportedGames)} supported games checked so far",
                        style = shadowed(text * 0.88f, color = TextDim),
                    )
                }
            }
        }
    }
}

/** A ring gauge: amber arc over a dim track, the percentage in the middle. */
@Composable
private fun Gauge(fraction: Float, size: Dp, text: TextUnit) {
    val f = fraction.coerceIn(0f, 1f)
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = this.size.minDimension * 0.11f
            val inset = Offset(w / 2, w / 2)
            val arc = Size(this.size.width - w, this.size.height - w)
            drawArc(Color.White.copy(alpha = 0.16f), 0f, 360f, useCenter = false, topLeft = inset, size = arc, style = Stroke(w))
            if (f > 0f) {
                drawArc(
                    Brush.sweepGradient(listOf(Pastel.AmberDeep, Pastel.Amber, Gold, Pastel.AmberDeep)),
                    startAngle = -90f,
                    sweepAngle = 360f * f,
                    useCenter = false,
                    topLeft = inset,
                    size = arc,
                    style = Stroke(w, cap = StrokeCap.Round),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${Math.round(f * 100)}%", style = shadowed(text * 1.5f, color = Pastel.Amber, weight = FontWeight.Bold))
            Label("UNLOCKED", text)
        }
    }
}

/** Mastery showcase: a horizontal carousel of fully mastered games in gold laurel frames. */
@Composable
private fun Mastery(
    games: List<RaMasteredGame>,
    loaded: Boolean,
    artVersion: Long,
    m: XmbMetrics,
    loadArt: suspend (String, List<ArtKind>, Int) -> ImageBitmap?,
) {
    val density = LocalDensity.current
    val text = with(density) { m.subtitleSize.toSp() }
    Section("MASTERY SHOWCASE", m) {
        if (games.isEmpty()) {
            Text(
                if (loaded) "No mastered games yet. Unlock every achievement of a game and it takes its place here." else "Reading the library…",
                style = shadowed(text, color = TextDim),
            )
            return@Section
        }
        val art = m.s * 0.17f
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(with(density) { (m.s * 0.035f).toDp() })) {
            for (game in games) {
                val px = art.toInt()
                val image by produceState<ImageBitmap?>(null, game.uuid, artVersion, px) { value = loadArt(game.uuid, MASTERY_ART, px) }
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(with(density) { (art * 1.5f).toDp() })) {
                    LaurelFrame(with(density) { art.toDp() }) {
                        image?.let { Image(it, game.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                            ?: Image(
                                glyphPainter(GlyphKey.TROPHY),
                                null,
                                colorFilter = ColorFilter.tint(Gold),
                                modifier = Modifier.fillMaxSize().padding(with(density) { (art * 0.22f).toDp() }),
                            )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(game.title, style = shadowed(text * 0.88f, weight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${game.achievements} / ${game.achievements}  ·  ${game.points} pts", style = shadowed(text * 0.78f, color = Gold))
                }
            }
        }
    }
}

/** Square art in a gold frame flanked by two laurel branches (arcs of leaf-shaped dashes). */
@Composable
private fun LaurelFrame(art: Dp, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(Modifier.size(width = art * 1.5f, height = art * 1.12f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val leaf = this.size.height * 0.085f
            val stroke = Stroke(leaf, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(leaf * 0.9f, leaf * 1.5f)))
            val d = this.size.height * 0.98f
            val top = (this.size.height - d) / 2
            val brush = Brush.verticalGradient(listOf(Color(0xFFFFF1A8), Gold, Color(0xFFC98A12)))
            // Left branch curls around the left edge, right branch mirrors it; they meet at the bottom.
            drawArc(brush, startAngle = 100f, sweepAngle = 150f, useCenter = false, topLeft = Offset(leaf, top), size = Size(d, d), style = stroke)
            drawArc(brush, startAngle = 80f, sweepAngle = -150f, useCenter = false, topLeft = Offset(this.size.width - d - leaf, top), size = Size(d, d), style = stroke)
        }
        Box(
            Modifier
                .size(art)
                .clip(shape)
                .background(Color(0xFF1C1C28))
                .border(2.5.dp, Brush.verticalGradient(listOf(Color(0xFFFFF1A8), Gold, Color(0xFFC98A12))), shape),
        ) { content() }
    }
}

/** Recent activity feed: badge, title, description, game, points and when. */
@Composable
private fun Activity(badges: List<RaBadge>, fetched: Boolean, m: XmbMetrics, loadBadge: suspend (String, Int) -> ImageBitmap?) {
    val density = LocalDensity.current
    val text = with(density) { m.subtitleSize.toSp() }
    Section("RECENT ACTIVITY", m) {
        if (badges.isEmpty()) {
            Text(
                if (fetched) "No recent unlocks. Achievements earned in the last days appear here." else "Recent unlocks appear here once the profile has been fetched.",
                style = shadowed(text, color = TextDim),
            )
            return@Section
        }
        val thumb = m.s * 0.1f
        Column(verticalArrangement = Arrangement.spacedBy(with(density) { (m.s * 0.018f).toDp() })) {
            for (badge in badges) {
                val name = badge.badgeUrl.substringAfterLast('/').substringBefore('.')
                val px = thumb.toInt()
                val image by produceState<ImageBitmap?>(null, name, px) { value = if (name.isNotEmpty()) loadBadge(name, px) else null }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BadgeThumb(image, unlocked = true, size = with(density) { thumb.toDp() })
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(badge.title, style = shadowed(text * 1.05f, weight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (badge.description.isNotBlank()) {
                            Text(badge.description, style = shadowed(text * 0.9f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Text(
                            listOf(badge.gameTitle, GameDateFormat.short(badge.date)).filter { it.isNotBlank() }.joinToString("  ·  "),
                            style = shadowed(text * 0.85f, color = TextDim),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${badge.points} pts", style = shadowed(text * 1.05f, color = Pastel.Amber, weight = FontWeight.Bold))
                        if (badge.hardcore) Pill("HARDCORE", text * 0.65f, color = Pastel.Rose)
                    }
                }
            }
        }
    }
}

/** The platform filter's pills; the selected one in amber, kept in view as L1/R1 move it. */
@Composable
private fun FilterPills(pills: List<String>, selected: Int, m: XmbMetrics, onTap: (Int) -> Unit) {
    val density = LocalDensity.current
    val text = with(density) { (m.subtitleSize * 0.85f).toSp() }
    val list = rememberLazyListState()
    LaunchedEffect(selected) {
        val visible = list.layoutInfo.visibleItemsInfo
        if (visible.none { it.index == selected && it.offset >= 0 && it.offset + it.size <= list.layoutInfo.viewportEndOffset }) {
            list.animateScrollToItem((selected - 1).coerceAtLeast(0))
        }
    }
    LazyRow(state = list, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        itemsIndexed(pills) { i, label ->
            val on = i == selected
            val shape = RoundedCornerShape(50)
            Box(
                Modifier
                    .clip(shape)
                    .background(if (on) Pastel.Amber.copy(alpha = 0.9f) else Color(0x26FFFFFF))
                    .border(1.dp, if (on) Pastel.Amber else Color(0x40FFFFFF), shape)
                    .semantics { contentDescription = "Platform filter: $label" + if (on) ", selected" else "" }
                    .clickable(remember { MutableInteractionSource() }, indication = null) { onTap(i) }
                    .padding(horizontal = 14.dp, vertical = 5.dp),
            ) {
                Text(
                    label,
                    style = shadowed(text, color = if (on) Color(0xFF1C1408) else Color.White, weight = FontWeight.Bold).let { if (on) it.copy(shadow = null) else it },
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

/**
 * `FOLLOWING` (2b.7e): followed users by points with the user's own row in its place, each with
 * the avatar, name, points and latest game. [focused] is the focused row, -1 while the focus is
 * on the dashboard; the list scrolls to keep it in view.
 */
@Composable
private fun FollowingSidebar(
    rows: List<SidebarRow>,
    friends: FriendsState,
    focused: Int,
    m: XmbMetrics,
    onRowTap: (Int) -> Unit,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val text = with(density) { m.subtitleSize.toSp() }
    val number = remember { NumberFormat.getIntegerInstance() }
    val list = rememberLazyListState()
    val nowMs = remember(rows) { System.currentTimeMillis() }
    LaunchedEffect(focused) {
        if (focused < 0) return@LaunchedEffect
        val visible = list.layoutInfo.visibleItemsInfo
        val first = visible.firstOrNull()?.index ?: 0
        val last = visible.lastOrNull()?.index ?: 0
        if (focused <= first || focused >= last) list.animateScrollToItem((focused - 2).coerceAtLeast(0))
    }
    val avatar = (m.subtitleSize * 2.2f).let { with(density) { it.toDp() } }
    Column(
        modifier
            .xrossSurface(CardShape, borderAlpha = 0.45f, borderScale = 2f / 3f)
            .padding(horizontal = with(density) { (m.s * 0.022f).toDp() }, vertical = with(density) { (m.s * 0.028f).toDp() }),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp)) {
            Text("FOLLOWING", style = shadowed(text * 0.8f, color = Pastel.Lilac, weight = FontWeight.Bold).copy(letterSpacing = text * 0.12f))
            Spacer(Modifier.weight(1f))
            val note = when (friends) {
                is FriendsState.Offline -> "Offline"
                is FriendsState.Loading -> "Refreshing…"
                else -> number.format(friends.friends.size)
            }
            Text(note, style = shadowed(text * 0.75f, color = TextDim), maxLines = 1)
        }
        Spacer(Modifier.height(with(density) { (m.s * 0.014f).toDp() }))
        LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f).fillMaxWidth()) {
            itemsIndexed(rows, key = { _, r -> (if (r.self) "self:" else "") + r.user.lowercase() }) { i, r ->
                val on = i == focused
                val shape = RoundedCornerShape(12.dp)
                val a = r.activity
                val ago = a?.let { if (it.online) "online" else SocialStack.ago(it.lastActive, nowMs) }.orEmpty()
                val game = when {
                    r.self -> "You"
                    a == null -> "Not fetched yet"
                    a.gameTitle.isNotEmpty() -> a.gameTitle
                    else -> "No games played yet"
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(
                            when {
                                on -> Color(0x40FFFFFF)
                                r.self -> Pastel.Lilac.copy(alpha = 0.18f)
                                else -> Color.Transparent
                            },
                        )
                        .border(if (on) 2.dp else 0.dp, if (on) Pastel.Amber else Color.Transparent, shape)
                        .semantics(mergeDescendants = true) {
                            contentDescription = listOf(
                                if (r.self) "${r.user} (you)" else r.user,
                                "${number.format(r.points)} points",
                                game.takeIf { !r.self }.orEmpty(),
                                ago,
                            ).filter { it.isNotEmpty() }.joinToString(", ")
                        }
                        .clickable(remember { MutableInteractionSource() }, indication = null) { onRowTap(i) }
                        .padding(horizontal = 6.dp, vertical = 5.dp),
                ) {
                    ProfileAvatar(r.avatar, r.avatarVersion, avatar, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                r.user,
                                style = shadowed(text * 0.92f, color = if (r.self) Pastel.Lilac else Color.White, weight = FontWeight.Bold),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(number.format(r.points), style = shadowed(text * 0.8f, color = Pastel.Amber, weight = FontWeight.Bold), maxLines = 1, softWrap = false)
                        }
                        Text(
                            listOf(game, ago).filter { it.isNotEmpty() }.joinToString(" · "),
                            style = shadowed(text * 0.76f, color = if (a?.online == true) Pastel.Mint else TextDim),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

private val MASTERY_ART = listOf(ArtKind.BOX_2D, ArtKind.GRID, ArtKind.BOX_3D, ArtKind.SCREENSHOT)
