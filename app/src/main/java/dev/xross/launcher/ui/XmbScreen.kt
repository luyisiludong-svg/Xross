package dev.xross.launcher.ui

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.border
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import dev.xross.launcher.input.ConfirmLayout
import dev.xross.launcher.media.MusicPlayer
import dev.xross.launcher.media.ThumbKind
import dev.xross.launcher.state.MediaOverlayController
import dev.xross.launcher.input.NavAction
import dev.xross.launcher.state.NodeAction
import dev.xross.launcher.state.NodeIcon
import dev.xross.launcher.state.RemapSession
import dev.xross.launcher.state.XmbCategory
import dev.xross.launcher.state.XmbNode
import dev.xross.launcher.state.XmbUiState
import dev.xross.launcher.media.SnapPlayer
import dev.xross.launcher.scrape.ArtKind
import dev.xross.launcher.theme.ThemePalette
import dev.xross.launcher.theme.XrossTheme
import dev.xross.launcher.theme.rememberActivePalette
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

internal val TextBright = Color(0xFFFFFFFF)
internal val TextDim = Color(0xD9FFFFFF)
private val DropShadow = Color(0x99000000)
private val IconTop = Color(0xFFFFFFFF)
private val IconBottom = Color(0xFFC9C9D3)
internal val Pill = Color(0x59000000)

/**
 * Layout measured from the reference screenshots (16:9): focused category column at ~22% of the
 * width, category bar at ~25% of the height, focused item at ~52%.
 *
 * Everything else scales from [s], the usable short side, so 4:3 / 1:1 / 3:2 handhelds, 21:9
 * phones, portrait and 4K TVs keep the same proportions:
 *  - the focus column always leaves room for one whole category to its left;
 *  - vertical positions follow [s], not the raw height, so tall portrait screens don't open a
 *    gap between the bar and the list.
 */
internal class XmbMetrics(val width: Float, val height: Float) {
    val s = min(height, width * 0.9f)

    val categorySpacing = s * 0.34f
    val categoryIcon = s * 0.12f
    val categoryFocusScale = 1.38f
    val anchorX = maxOf(width * 0.22f, categorySpacing + categoryIcon * 0.8f)
        .coerceAtMost(width * 0.45f)
    val barY = min(height * 0.25f, s * 0.30f)
    val itemIcon = s * 0.085f
    val itemFocusScale = 1.32f
    val focusedY = barY + s * 0.27f
    val itemSpacing = s * 0.15f
    val focusGap = s * 0.05f
    val aboveY = barY - s * 0.19f
    val textGap = s * 0.06f
    val titleSize = s * 0.047f
    val subtitleSize = s * 0.036f
    val labelSize = s * 0.037f
    val statusSize = s * 0.042f

    /** How many rows fit above the bar and below the focus; only those are composed. */
    val rowsAbove = ceil(aboveY / itemSpacing).toInt() + 1
    val rowsBelow = ceil((height - focusedY - focusGap) / itemSpacing).toInt() + 1
}

@Composable
fun XmbScreen(
    state: XmbUiState,
    loadAppIcon: suspend (String) -> ImageBitmap?,
    loadThumb: suspend (ThumbKind, Long, Int) -> ImageBitmap?,
    loadArt: suspend (String, List<ArtKind>, Int) -> ImageBitmap?,
    media: MediaOverlayController,
    music: MusicPlayer,
    onAction: (NavAction) -> Unit,
    onCategoryTap: (Int) -> Unit,
    onItemTap: (Int) -> Unit,
    onCancelRemap: () -> Unit,
    onItemLongPress: (Int) -> Unit = {},
    onOptionTap: (Int) -> Unit = {},
    onCloseOptions: () -> Unit = {},
    onSearchKeyTap: (Int, Int) -> Unit = { _, _ -> },
    onSearchResultTap: (Int) -> Unit = {},
    onNameKeyTap: (Int, Int) -> Unit = { _, _ -> },
    onInfoRowTap: (Int) -> Unit = {},
    onCloseInfo: () -> Unit = {},
    snap: SnapPlayer? = null,
    videoFile: suspend (String) -> java.io.File? = { null },
    snapVolume: Float = 0.75f,
    onPlayDetails: () -> Unit = {},
    onCloseDetails: () -> Unit = {},
    onAvatarTap: () -> Unit = {},
    onProfileCardButtonTap: (Int) -> Unit = {},
    onCloseProfileCard: () -> Unit = {},
    onMiniPlayerTap: () -> Unit = {},
    onMiniPlayerLongPress: () -> Unit = {},
    loadBadge: suspend (String, Int) -> ImageBitmap? = { _, _ -> null },
    /** Opaque hero art or a video snap covers the screen: the wave can stop rendering. */
    onBackdropCoverChanged: (Boolean) -> Unit = {},
    onWaveRowTap: (Int) -> Unit = {},
    onWaveSlider: (Int, Float) -> Unit = { _, _ -> },
    onCloseWaveSettings: () -> Unit = {},
    onAchievementTap: (Int) -> Unit = {},
    onCloseAchievements: () -> Unit = {},
    onDetailsAchievements: () -> Unit = {},
) {
    val palette = rememberActivePalette(state.theme, LocalThemePack.current?.palette)

    // Slow "breathing" glow shared by everything that is focused.
    val pulse by rememberInfiniteTransition(label = "glow").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )

    // Cold boot: fade the whole interface in once per process start.
    val reveal = remember { Animatable(if (state.coldBoot) 0f else 1f) }
    LaunchedEffect(Unit) { reveal.animateTo(1f, tween(1400, delayMillis = 350, easing = LinearEasing)) }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = reveal.value }
            .swipeNavigation(onAction),
    ) {
        val m = remember(constraints.maxWidth, constraints.maxHeight) {
            XmbMetrics(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        }
        val density = LocalDensity.current
        fun Float.sp(): TextUnit = with(density) { this@sp.toSp() }
        fun Float.dp(): Dp = with(density) { this@dp.toDp() }

        // Focused game: hero art after 250 ms, then its video snap after 1.2 s, full-bleed behind
        // the menu (see BackdropTiming). The wave pauses while either fully covers the screen.
        val focusedArt = state.focused?.icon as? NodeIcon.Art
        val quiet = state.overlay == null && state.search == null && state.optionsMenu == null &&
            state.info == null && state.booting == null && state.remap == null && state.waveEditor == null &&
            state.achievements == null && state.profileCard == null && state.nameEntry == null
        var heroCovers by remember { mutableStateOf(false) }
        var videoCovers by remember { mutableStateOf(false) }
        FocusBackdrop(
            uuid = focusedArt?.uuid?.takeIf { quiet && state.details == null },
            artVersion = focusedArt?.version ?: 0L,
            maxPx = minOf(maxOf(m.width, m.height), 1600f).toInt(),
            loadArt = loadArt,
            onCovering = { heroCovers = it },
        )
        if (snap != null) {
            SnapDriver(
                uuid = state.details?.uuid ?: focusedArt?.uuid.takeIf { quiet },
                snap = snap,
                videoFile = videoFile,
                volume = snapVolume,
            )
            val playing by snap.player.collectAsState()
            playing?.let { SnapVideo(it, Modifier.fillMaxSize(), scrim = 0.55f, onCovering = { c -> videoCovers = c }) }
            if (playing == null) videoCovers = false
        }
        val covers = heroCovers || videoCovers
        val reportCover by rememberUpdatedState(onBackdropCoverChanged)
        LaunchedEffect(covers) { reportCover(covers) }

        val catPos by animateFloatAsState(state.categoryIndex.toFloat(), NavSpring, label = "category")
        val depth by animateFloatAsState(state.depth.coerceAtMost(1).toFloat(), NavSpring, label = "depth")

        // The menu fades out under media screens so only one set of text and hints is visible.
        /** Height of the button-hint row, so the mini player can float just above it. */
        var hintsHeightPx by remember { mutableIntStateOf(0) }
        val menuAlpha by animateFloatAsState(
            if (state.overlay == null && state.search == null && state.nameEntry == null && state.details == null &&
                state.waveEditor == null && state.achievements == null
            ) {
                1f
            } else {
                0f
            },
            tween(220),
            label = "menu",
        )
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = menuAlpha }) {
        CategoryBar(
            categories = state.categories,
            categoryIndex = state.categoryIndex,
            breadcrumb = state.breadcrumb.joinToString("  ›  "),
            m = m,
            catPos = { catPos },
            depth = { depth },
            pulse = { pulse },
            onCategoryTap = onCategoryTap,
        )
        ItemColumn(state, m, { pulse }, loadAppIcon, loadThumb, loadArt, onItemTap, onItemLongPress)

        // (The "now playing" line that used to sit top-left is the floating MiniPlayerCard below.)

        StatusBar(
            textSize = m.statusSize.sp(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = (m.s * 0.04f).dp(), end = (m.width * 0.025f).dp()),
            leading = {
                ProfileAvatar(
                    avatar = state.profile.avatar,
                    avatarVersion = state.profile.avatarVersion,
                    size = 32.dp,
                    focused = state.avatarFocused,
                    pulse = { pulse },
                    contentDescription = state.profile.username.ifEmpty { "Profile" },
                    onClick = onAvatarTap,
                )
            },
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding((m.s * 0.04f).dp())
                .onSizeChanged { hintsHeightPx = it.height },
        ) {
            BusyIndicator(busy = state.busy, size = (m.subtitleSize * 1.5f).dp())
            if (state.showButtonHints) ButtonHints(state, m.subtitleSize.sp(), Modifier)
        }
        }

        // Floating mini player: at the root, outside the menu's fade, so it rides across every
        // category; anything that takes over the screen or the corner covers it.
        val miniCovered = state.overlay != null || state.search != null || state.nameEntry != null ||
            state.details != null || state.optionsMenu != null || state.info != null ||
            state.profileCard != null || state.remap != null || state.booting != null ||
            state.waveEditor != null || state.achievements != null
        MiniPlayerCard(
            nowPlaying = state.nowPlaying,
            covered = miniCovered,
            loadThumb = loadThumb,
            onClick = onMiniPlayerTap,
            onLongClick = onMiniPlayerLongPress,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(
                    end = 16.dp,
                    bottom = maxOf(16.dp, (hintsHeightPx + m.s * 0.04f).dp() + 8.dp),
                ),
        )

        state.overlay?.let { overlay ->
            MediaOverlayLayer(
                overlay = overlay,
                controller = media,
                music = music,
                nowPlaying = state.nowPlaying,
                loadThumb = loadThumb,
                hints = { pairs -> OverlayHints(pairs, state, m.subtitleSize.sp()) },
            )
        }

        state.nameEntry?.let { entry ->
            NameEntryOverlay(
                entry = entry,
                m = m,
                pulse = { pulse },
                family = state.family,
                confirm = state.confirm,
                onKeyTap = onNameKeyTap,
            )
        }

        state.search?.let { search ->
            SearchOverlay(
                search = search,
                m = m,
                pulse = { pulse },
                family = state.family,
                confirm = state.confirm,
                onKeyTap = onSearchKeyTap,
                onResultTap = onSearchResultTap,
            )
        }

        OptionsPanel(
            menu = state.optionsMenu,
            m = m,
            pulse = { pulse },
            confirmGlyph = if (state.confirm == ConfirmLayout.EAST) state.family.east else state.family.south,
            backGlyph = if (state.confirm == ConfirmLayout.EAST) state.family.south else state.family.east,
            onTap = onOptionTap,
            onDismiss = onCloseOptions,
        )

        DetailsScreen(
            details = state.details,
            m = m,
            snapPlaying = snap?.player?.collectAsState()?.value != null,
            confirmGlyph = if (state.confirm == ConfirmLayout.EAST) state.family.east else state.family.south,
            backGlyph = if (state.confirm == ConfirmLayout.EAST) state.family.south else state.family.east,
            optionsGlyph = state.family.north,
            loadArt = loadArt,
            loadBadge = loadBadge,
            onPlay = onPlayDetails,
            onAchievements = onDetailsAchievements,
            onDismiss = onCloseDetails,
        )

        InfoPanel(
            info = state.info,
            m = m,
            backGlyph = if (state.confirm == ConfirmLayout.EAST) state.family.south else state.family.east,
            onRowTap = onInfoRowTap,
            onDismiss = onCloseInfo,
        )

        UserCard(
            card = state.profileCard,
            profile = state.profile,
            m = m,
            pulse = { pulse },
            confirmGlyph = if (state.confirm == ConfirmLayout.EAST) state.family.east else state.family.south,
            backGlyph = if (state.confirm == ConfirmLayout.EAST) state.family.south else state.family.east,
            onButtonTap = onProfileCardButtonTap,
            onDismiss = onCloseProfileCard,
            loadBadge = loadBadge,
        )

        AchievementsScreen(
            view = state.achievements,
            m = m,
            pulse = { pulse },
            backGlyph = if (state.confirm == ConfirmLayout.EAST) state.family.south else state.family.east,
            optionsGlyph = state.family.north,
            loadArt = loadArt,
            loadBadge = loadBadge,
            onBadgeTap = onAchievementTap,
            onDismiss = onCloseAchievements,
        )

        WaveSettingsPanel(
            editor = state.waveEditor,
            settings = state.settings,
            basePalette = palette,
            m = m,
            pulse = { pulse },
            confirmGlyph = if (state.confirm == ConfirmLayout.EAST) state.family.east else state.family.south,
            backGlyph = if (state.confirm == ConfirmLayout.EAST) state.family.south else state.family.east,
            onRowTap = onWaveRowTap,
            onSlider = onWaveSlider,
            onDismiss = onCloseWaveSettings,
        )

        state.remap?.let { RemapOverlay(it, m, onCancelRemap) }
        state.booting?.let { GameBootOverlay(it, palette, m) }

        // Unlock toasts: top right under the status bar; drawn only, never focused.
        UnlockToast(
            toast = state.unlockToast,
            m = m,
            loadBadge = loadBadge,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = (m.s * 0.12f).dp(), end = (m.width * 0.025f).dp()),
        )

        // Toasts sit above every overlay (search, Options, media) so feedback is never hidden.
        AnimatedVisibility(
            visible = state.toast != null,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(320)),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = (m.s * 0.04f).dp()),
        ) {
            var last by remember { mutableStateOf("") }
            state.toast?.let { last = it }
            Text(
                last,
                style = shadowed(m.subtitleSize.sp()),
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    // Nearly opaque: toasts can land on top of overlay text.
                    .background(Color(0xE6101018))
                    .padding(horizontal = (m.s * 0.04f).dp(), vertical = (m.s * 0.018f).dp()),
            )
        }
    }
}

// region Category bar

/** Critically damped: no overshoot, ~200 ms settle, and velocity carries across retargets. */
private val NavSpring = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 520f)

/** Takes only what it draws, so cursor moves in the item list don't recompose the bar. */
@Composable
private fun CategoryBar(
    categories: List<XmbCategory>,
    categoryIndex: Int,
    breadcrumb: String,
    m: XmbMetrics,
    catPos: () -> Float,
    depth: () -> Float,
    pulse: () -> Float,
    onCategoryTap: (Int) -> Unit,
) {
    val density = LocalDensity.current
    val iconDp = with(density) { m.categoryIcon.toDp() }
    val cellDp = with(density) { m.categorySpacing.toDp() }
    val labelSp = with(density) { m.labelSize.toSp() }

    categories.forEachIndexed { i, category ->
        val focus = { 1f - abs(i - catPos()).coerceAtMost(1f) }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .width(cellDp)
                .offset {
                    // Drilling into a list slides the bar left, leaving the open category's icon.
                    val shift = depth() * m.categorySpacing * 0.85f
                    val x = m.anchorX + (i - catPos()) * m.categorySpacing - shift - m.categorySpacing / 2
                    IntOffset(x.roundToInt(), (m.barY - m.categoryIcon / 2).roundToInt())
                }
                .fadeAlpha {
                    val f = focus()
                    (0.62f + 0.38f * f) * (1f - depth() * (1f - f))
                }
                .clickable(remember { MutableInteractionSource() }, indication = null) { onCategoryTap(i) },
        ) {
            XmbIcon(
                painter = glyphPainter(category.icon),
                size = iconDp,
                focus = focus,
                focusScale = m.categoryFocusScale,
                glow = { pulse() * focus() * (1f - depth()) },
            )
            Spacer(Modifier.height(with(density) { (m.categoryIcon * 0.32f).toDp() }))
            GlowText(
                text = category.title,
                fontSize = labelSp,
                glow = { pulse() * focus() },
                modifier = Modifier.fadeAlpha { focus() * (1f - depth()) },
            )
        }
    }

    // Breadcrumb beside the open category while drilled in.
    if (breadcrumb.isNotEmpty()) {
        GlowText(
            text = breadcrumb,
            fontSize = with(density) { m.titleSize.toSp() },
            glow = { 0f },
            modifier = Modifier
                .offset {
                    IntOffset(
                        (m.anchorX - m.categorySpacing * 0.85f + m.categoryIcon * 1.15f).roundToInt(),
                        (m.barY - m.titleSize * 0.7f).roundToInt(),
                    )
                }
                .fadeAlpha { 0.92f },
        )
    }
}

// endregion

// region Item column

@Composable
private fun ItemColumn(
    state: XmbUiState,
    m: XmbMetrics,
    pulse: () -> Float,
    loadAppIcon: suspend (String) -> ImageBitmap?,
    loadThumb: suspend (ThumbKind, Long, Int) -> ImageBitmap?,
    loadArt: suspend (String, List<ArtKind>, Int) -> ImageBitmap?,
    onItemTap: (Int) -> Unit,
    onItemLongPress: (Int) -> Unit,
) {
    // Scroll snaps when the list itself changes and glides when the cursor moves.
    val scroll = remember { Animatable(state.focusedIndex.toFloat()) }
    var lastKey by remember { mutableStateOf(state.listKey) }
    LaunchedEffect(state.listKey, state.focusedIndex) {
        if (state.listKey != lastKey) {
            lastKey = state.listKey
            scroll.snapTo(state.focusedIndex.toFloat())
        } else {
            scroll.animateTo(state.focusedIndex.toFloat(), NavSpring)
        }
    }
    val enter = remember(state.listKey) { Animatable(0f) }
    LaunchedEffect(state.listKey) { enter.animateTo(1f, tween(280, easing = FastOutSlowInEasing)) }

    // Compose only the rows that can be on screen for this display.
    val first by remember(m) { derivedStateOf { floor(scroll.value).toInt() - m.rowsAbove } }
    val last by remember(m) { derivedStateOf { ceil(scroll.value).toInt() + m.rowsBelow } }
    val range = first.coerceAtLeast(0)..last.coerceAtMost(state.items.lastIndex)

    val density = LocalDensity.current
    val iconDp = with(density) { m.itemIcon.toDp() }
    val textX = m.anchorX + m.itemIcon * m.itemFocusScale / 2 + m.textGap
    val textWidth = with(density) { (m.width - textX - m.width * 0.03f).coerceAtLeast(m.s * 0.3f).toDp() }

    // Read through State so the remembered row modifiers below never hold a stale callback.
    val tap by rememberUpdatedState(onItemTap)
    val longPress by rememberUpdatedState(onItemLongPress)

    for (i in range) {
        val node = state.items[i]
        key(state.listKey, node.id) {
            // Remembered per row so every argument is identical across cursor moves, and rows
            // whose focus didn't change skip recomposition. Position and fade are read in the
            // layout/draw lambdas, so scrolling itself never recomposes a row.
            val focus = remember(i) { { 1f - abs(i - scroll.value).coerceAtMost(1f) } }
            val interaction = remember { MutableInteractionSource() }
            val rowModifier = remember(i, m, enter) {
                Modifier
                    .offset {
                        val y = itemY(i - scroll.value, m)
                        val slide = (1f - enter.value) * m.s * 0.08f
                        IntOffset(
                            (m.anchorX - m.itemIcon / 2 + slide).roundToInt(),
                            (y - m.itemIcon / 2).roundToInt(),
                        )
                    }
                    .fadeAlpha {
                        // Items scrolled up past the top edge fade out instead of clipping.
                        val fade = (itemY(i - scroll.value, m) / m.itemSpacing).coerceIn(0f, 1f)
                        enter.value * fade
                    }
                    // Long-press opens the item's Options menu (touch equivalent of △ / Y).
                    .combinedClickable(
                        interactionSource = interaction,
                        indication = null,
                        onLongClick = { longPress(i) },
                        onClick = { tap(i) },
                    )
            }
            ItemRow(
                node = node,
                m = m,
                iconSize = iconDp,
                textWidth = textWidth,
                isFocused = i == state.focusedIndex,
                focus = focus,
                pulse = pulse,
                loadAppIcon = loadAppIcon,
                loadThumb = loadThumb,
                loadArt = loadArt,
                modifier = rowModifier,
            )
        }
    }
}

private fun itemY(d: Float, m: XmbMetrics): Float = when {
    d >= 0f -> m.focusedY + d * m.itemSpacing + m.focusGap * d.coerceAtMost(1f)
    d > -1f -> m.focusedY + (m.aboveY - m.focusedY) * -d
    else -> m.aboveY + (d + 1f) * m.itemSpacing
}

@Composable
private fun ItemRow(
    node: XmbNode,
    m: XmbMetrics,
    iconSize: Dp,
    textWidth: Dp,
    isFocused: Boolean,
    focus: () -> Float,
    pulse: () -> Float,
    loadAppIcon: suspend (String) -> ImageBitmap?,
    loadThumb: suspend (ThumbKind, Long, Int) -> ImageBitmap?,
    loadArt: suspend (String, List<ArtKind>, Int) -> ImageBitmap?,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val titleSp = with(density) { m.titleSize.toSp() }
    val subtitleSp = with(density) { m.subtitleSize.toSp() }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Box(Modifier.size(iconSize), contentAlignment = Alignment.Center) {
            when (val icon = node.icon) {
                is NodeIcon.Glyph -> XmbIcon(
                    painter = glyphPainter(icon.key),
                    size = iconSize,
                    focus = focus,
                    focusScale = m.itemFocusScale,
                    glow = { if (isFocused) pulse() * focus() else 0f },
                )
                is NodeIcon.App -> AppIcon(icon.packageName, iconSize, focus, m.itemFocusScale, loadAppIcon)
                is NodeIcon.Thumb -> ThumbIcon(icon, iconSize, focus, m.itemFocusScale, glow = { if (isFocused) pulse() * focus() else 0f }, loadThumb)
                is NodeIcon.Art -> ArtIcon(icon, iconSize, focus, m.itemFocusScale, glow = { if (isFocused) pulse() * focus() else 0f }, loadArt)
            }
        }
        Spacer(Modifier.width(with(density) { (m.textGap + m.itemIcon * (m.itemFocusScale - 1f) / 2).toDp() }))
        Column(Modifier.width(textWidth)) {
            GlowText(
                text = node.title,
                fontSize = titleSp,
                glow = { if (isFocused) pulse() * focus() else 0f },
                modifier = Modifier.fadeAlpha { 0.72f + 0.28f * focus() },
            )
            // Hairline under the focused title, as in the reference themes.
            Box(
                Modifier
                    .padding(top = with(density) { (m.s * 0.008f).toDp() })
                    .fillMaxWidth()
                    .height(1.dp)
                    .graphicsLayer { alpha = focus() }
                    .drawBehind {
                        drawRect(
                            Brush.horizontalGradient(
                                0f to Color.White.copy(alpha = 0.95f),
                                0.7f to Color.White.copy(alpha = 0.8f),
                                1f to Color.White.copy(alpha = 0.1f),
                            ),
                        )
                    },
            )
            node.subtitle?.let {
                Text(
                    it,
                    style = shadowed(subtitleSp, color = TextDim),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(top = with(density) { (m.s * 0.01f).toDp() })
                        .fadeAlpha { focus() },
                )
            }
        }
    }
}

// endregion

// region Icons and text

/**
 * A theme icon: glossy white-to-silver gradient fill, soft drop shadow, and an animated halo
 * when focused. The shadow and halo blur on Android 12+; older versions use offset copies and
 * a radial gradient, which read the same at handheld sizes.
 */
@Composable
private fun XmbIcon(
    painter: Painter,
    size: Dp,
    focus: () -> Float,
    focusScale: Float,
    glow: () -> Float,
) {
    val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    Box(
        Modifier
            .size(size)
            .graphicsLayer {
                val s = 1f + (focusScale - 1f) * focus()
                scaleX = s
                scaleY = s
            },
    ) {
        // Halo.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = glow()
                    scaleX = 1.9f
                    scaleY = 1.9f
                }
                .drawBehind {
                    drawCircle(
                        Brush.radialGradient(
                            0f to Color.White.copy(alpha = 0.42f),
                            0.45f to Color.White.copy(alpha = 0.14f),
                            1f to Color.Transparent,
                        ),
                    )
                },
        )
        if (canBlur) {
            BlurredCopy(painter, size, Color.White, blur = 6.dp, alpha = { glow() * 0.9f })
        }
        // Drop shadow.
        BlurredCopy(
            painter,
            size,
            Color.Black,
            blur = if (canBlur) 2.5.dp else 0.dp,
            alpha = { 0.38f },
            offset = IntOffset(1, 2),
        )
        // Glossy body.
        Image(
            painter,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(Brush.verticalGradient(listOf(IconTop, IconBottom)), blendMode = BlendMode.SrcIn)
                },
        )
    }
}

/**
 * A tinted, blurred copy of an icon. The blur runs in a layer twice the icon's size so the
 * spread is never clipped into a visible rectangle at the icon's bounds.
 */
@Composable
private fun BlurredCopy(
    painter: Painter,
    size: Dp,
    tint: Color,
    blur: Dp,
    alpha: () -> Float,
    offset: IntOffset = IntOffset.Zero,
) {
    Box(
        Modifier
            .wrapContentSize(unbounded = true)
            .requiredSize(size * 2)
            .offset { IntOffset((offset.x * density).roundToInt(), (offset.y * density).roundToInt()) }
            .graphicsLayer { this.alpha = alpha() }
            .then(if (blur > 0.dp) Modifier.blur(blur, BlurredEdgeTreatment.Unbounded) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Image(painter, contentDescription = null, colorFilter = ColorFilter.tint(tint), modifier = Modifier.size(size))
    }
}

/**
 * Scraped box art as a game's icon. The platform glyph shows until the art is decoded, then the
 * art crossfades in over it (and the glyph out), so a row never pops.
 */
@Composable
private fun ArtIcon(
    icon: NodeIcon.Art,
    size: Dp,
    focus: () -> Float,
    focusScale: Float,
    glow: () -> Float,
    loadArt: suspend (String, List<ArtKind>, Int) -> ImageBitmap?,
) {
    val px = with(LocalDensity.current) { (size * focusScale * 1.4f).roundToPx() }
    val bitmap by produceState<ImageBitmap?>(null, icon.uuid, icon.version) { value = loadArt(icon.uuid, icon.kinds, px) }
    val shown by animateFloatAsState(if (bitmap != null) 1f else 0f, tween(320), label = "art")
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        if (shown < 1f) {
            Box(Modifier.fadeAlpha { 1f - shown }) {
                XmbIcon(glyphPainter(icon.fallback), size, focus, focusScale, glow)
            }
        }
        val image = bitmap ?: return@Box
        Box(
            Modifier
                .size(size)
                .graphicsLayer {
                    val s = 1f + (focusScale - 1f) * focus()
                    scaleX = s
                    scaleY = s
                    alpha = shown
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = glow(); scaleX = 1.6f; scaleY = 1.6f }
                    .drawBehind { drawCircle(Brush.radialGradient(0f to Color.White.copy(alpha = 0.3f), 1f to Color.Transparent)) },
            )
            // Exactly the glyph's footprint, so tall capsules and wide boxes never reach the
            // neighbouring rows (the focus scale is applied by the layer above, as for glyphs).
            Image(
                image,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(size)
                    .shadow(6.dp, RoundedCornerShape(3.dp), clip = false),
            )
        }
    }
}

/**
 * Photo/video thumbnail or album art: a rounded, bordered tile like the reference themes' game
 * icons. Shows the glyph until the image loads (or if there is none).
 */
@Composable
private fun ThumbIcon(
    icon: NodeIcon.Thumb,
    size: Dp,
    focus: () -> Float,
    focusScale: Float,
    glow: () -> Float,
    loadThumb: suspend (ThumbKind, Long, Int) -> ImageBitmap?,
) {
    val px = with(LocalDensity.current) { (size * focusScale).roundToPx() }
    val bitmap by produceState<ImageBitmap?>(null, icon) { value = loadThumb(icon.kind, icon.id, px) }
    val image = bitmap
    if (image == null) {
        XmbIcon(glyphPainter(icon.fallback), size, focus, focusScale, glow)
        return
    }
    // Wider than tall for photos/videos, square for album art.
    val wide = icon.kind != ThumbKind.ALBUM_ART
    Box(
        Modifier
            .size(size)
            .graphicsLayer {
                val s = 1f + (focusScale - 1f) * focus()
                scaleX = s
                scaleY = s
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = glow(); scaleX = 1.7f; scaleY = 1.7f }
                .drawBehind {
                    drawCircle(Brush.radialGradient(0f to Color.White.copy(alpha = 0.35f), 1f to Color.Transparent))
                },
        )
        Image(
            image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(width = if (wide) size * 1.3f else size, height = size)
                .wrapContentSize(unbounded = true)
                .shadow(4.dp, RoundedCornerShape(4.dp))
                .clip(RoundedCornerShape(4.dp))
                .border(1.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(4.dp)),
        )
    }
}

/** Button hints for media screens; @confirm/@back/@options become the controller's symbols. */
@Composable
private fun OverlayHints(pairs: List<Pair<String, String>>, state: XmbUiState, size: TextUnit) {
    val confirm = if (state.confirm == ConfirmLayout.EAST) state.family.east else state.family.south
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0x40000000))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        for ((glyph, label) in pairs) {
            val g = when (glyph) {
                "@confirm" -> confirm
                "@options" -> state.family.north
                else -> glyph
            }
            Hint(g, label, size)
        }
    }
}

@Composable
private fun AppIcon(
    packageName: String,
    size: Dp,
    focus: () -> Float,
    focusScale: Float,
    loadAppIcon: suspend (String) -> ImageBitmap?,
) {
    val bitmap by produceState<ImageBitmap?>(null, packageName) { value = loadAppIcon(packageName) }
    bitmap?.let {
        Image(
            it,
            contentDescription = null,
            modifier = Modifier
                .size(size)
                .graphicsLayer {
                    val s = 1f + (focusScale - 1f) * focus()
                    scaleX = s
                    scaleY = s
                },
        )
    }
}

/**
 * Partial opacity for content whose glow, halo or text shadow spills past its own bounds.
 *
 * A plain `graphicsLayer { alpha = … }` below 1 renders into an offscreen buffer the size of
 * the layer, which cuts the spill off at the layer's edges. On a category cell or row that is
 * fading in, that shows up as a lighter box around the icon until the alpha reaches exactly 1.
 * Modulating each draw call's alpha instead never clips.
 */
internal fun Modifier.fadeAlpha(alpha: () -> Float): Modifier = graphicsLayer {
    this.alpha = alpha()
    compositingStrategy = CompositingStrategy.ModulateAlpha
}

internal fun shadowed(size: TextUnit, color: Color = TextBright, weight: FontWeight = FontWeight.Normal) = TextStyle(
    color = color,
    fontSize = size,
    fontWeight = weight,
    fontFamily = ThemeTypography.family,
    shadow = Shadow(DropShadow, Offset(0f, 3f), blurRadius = 6f),
)

/**
 * White text with a dark drop shadow, plus a second pass rendered as a soft white bloom whose
 * opacity follows [glow]. Only the bloom layer's alpha animates, so the pulse never re-lays-out
 * text.
 */
@Composable
internal fun GlowText(
    text: String,
    fontSize: TextUnit,
    glow: () -> Float,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        Text(
            text,
            style = TextStyle(
                color = Color.White.copy(alpha = 0.55f),
                fontSize = fontSize,
                fontFamily = ThemeTypography.family,
                shadow = Shadow(Color.White, Offset.Zero, blurRadius = 18f),
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .clearAndSetSemantics { } // decorative copy; screen readers read the text once
                .graphicsLayer {
                    alpha = glow()
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                },
        )
        Text(
            text,
            style = shadowed(fontSize),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// endregion

// region Chrome

@Composable
private fun ButtonHints(state: XmbUiState, size: TextUnit, modifier: Modifier) {
    val confirmGlyph = if (state.confirm == ConfirmLayout.EAST) state.family.east else state.family.south
    val backGlyph = if (state.confirm == ConfirmLayout.EAST) state.family.south else state.family.east
    val focused = state.focused
    if (focused == null || (focused.action == NodeAction.None && state.depth == 0 && focused.menu.isEmpty())) return
    // Backed so it stays legible where long titles run underneath on narrow (4:3, 1:1) screens.
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0x40000000))
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .graphicsLayer { alpha = 0.85f },
    ) {
        if (focused.action != NodeAction.None) Hint(confirmGlyph, "Select", size)
        if (state.depth > 0) Hint(backGlyph, "Back", size)
        if (focused.menu.isNotEmpty()) Hint(state.family.north, "Options", size)
    }
}

@Composable
internal fun Hint(glyph: String, label: String, size: TextUnit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            glyph,
            style = shadowed(size, weight = FontWeight.Bold),
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Pill)
                .padding(horizontal = 7.dp, vertical = 2.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(label, style = shadowed(size))
    }
}

@Composable
private fun RemapOverlay(session: RemapSession, m: XmbMetrics, onCancel: () -> Unit) {
    val density = LocalDensity.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xB0000000))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onCancel),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val device = session.controller?.name?.ifBlank { null }
                ?: if (session.deviceChosen) "Default mapping" else "Press a button on the controller to remap"
            Text("Remap buttons  ${session.progress}  ·  $device", style = shadowed(with(density) { m.subtitleSize.toSp() }, TextDim))
            Spacer(Modifier.height(10.dp))
            GlowText(
                "Press the button for: " + (session.target?.let(RemapSession::label) ?: ""),
                fontSize = with(density) { (m.titleSize * 1.3f).toSp() },
                glow = { 0.8f },
            )
            Spacer(Modifier.height(14.dp))
            Text("Tap the screen to cancel", style = shadowed(with(density) { m.subtitleSize.toSp() }, TextDim))
        }
    }
}

/** Game start transition: a bright bloom washes over the screen while the start cue plays. */
@Composable
private fun GameBootOverlay(title: String, palette: ThemePalette, m: XmbMetrics) {
    val progress = remember(title) { Animatable(0f) }
    LaunchedEffect(title) { progress.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
    val density = LocalDensity.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .drawBehind {
                val p = progress.value
                // Fade the menu out first so only the bloom and the game title remain.
                drawRect(Color.Black.copy(alpha = 0.92f * (p * 1.6f).coerceAtMost(1f)))
                drawCircle(
                    Brush.radialGradient(
                        0f to palette.glowColor.copy(alpha = 0.85f * p),
                        0.6f to palette.glowColor.copy(alpha = 0.25f * p),
                        1f to Color.Transparent,
                        center = center,
                        radius = size.maxDimension * (0.2f + 0.6f * p),
                    ),
                    radius = size.maxDimension,
                )
            },
    ) {
        GlowText(
            title,
            fontSize = with(density) { (m.titleSize * 1.4f).toSp() },
            glow = { 1f },
            modifier = Modifier.graphicsLayer { alpha = progress.value },
        )
    }
}

// endregion

/**
 * Touch parity: a horizontal swipe moves exactly one category (like a D-pad press); a vertical
 * drag scrolls items continuously, one item per [step] of finger travel.
 */
private fun Modifier.swipeNavigation(onAction: (NavAction) -> Unit): Modifier = pointerInput(Unit) {
    val step = 56.dp.toPx()
    val swipe = 48.dp.toPx()
    var dx = 0f
    var dy = 0f
    var axis = 0 // 0 = undecided, 1 = horizontal, 2 = vertical
    var categoryMoved = false
    detectDragGestures(
        onDragStart = { dx = 0f; dy = 0f; axis = 0; categoryMoved = false },
        onDrag = { change, amount ->
            change.consume()
            dx += amount.x
            dy += amount.y
            if (axis == 0) axis = if (abs(dx) > abs(dy)) 1 else 2
            if (axis == 1) {
                if (!categoryMoved && abs(dx) >= swipe) {
                    onAction(if (dx < 0) NavAction.RIGHT else NavAction.LEFT)
                    categoryMoved = true
                }
            } else {
                while (dy <= -step) { onAction(NavAction.DOWN); dy += step }
                while (dy >= step) { onAction(NavAction.UP); dy -= step }
            }
        },
    )
}
