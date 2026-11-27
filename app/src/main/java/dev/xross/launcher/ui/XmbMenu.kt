package dev.xross.launcher.ui

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.draw.clipToBounds
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
import dev.xross.launcher.theme.pack.FocusFrameStyle
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import dev.xross.launcher.theme.pack.LaunchCurtain
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.currentStateAsState
import dev.xross.launcher.input.ConfirmLayout
import dev.xross.launcher.media.MusicPlayer
import dev.xross.launcher.media.ThumbKind
import dev.xross.launcher.state.MediaOverlay
import dev.xross.launcher.state.MediaOverlayController
import dev.xross.launcher.input.NavAction
import dev.xross.launcher.model.Face
import dev.xross.launcher.model.GlyphKey
import dev.xross.launcher.model.GlyphStyle
import dev.xross.launcher.theme.pack.MiniPlayerMode
import dev.xross.launcher.state.BadgePills
import dev.xross.launcher.state.TitlePill
import dev.xross.launcher.state.NodeAction
import dev.xross.launcher.state.NodeIcon
import dev.xross.launcher.state.RemapSession
import dev.xross.launcher.state.XmbCategory
import dev.xross.launcher.state.XmbNode
import dev.xross.launcher.state.XmbEvent
import dev.xross.launcher.state.XmbUiState
import dev.xross.launcher.media.SnapPlayer
import dev.xross.launcher.model.ArtKind
import dev.xross.launcher.theme.ThemePalette
import dev.xross.launcher.theme.XrossTheme
import dev.xross.launcher.theme.rememberActivePalette
import dev.xross.launcher.theme.pack.Crosshair
import dev.xross.launcher.theme.pack.DrillDownStyle
import dev.xross.launcher.theme.pack.FocusDividerStyle
import dev.xross.launcher.theme.pack.Motion
import dev.xross.launcher.theme.pack.TextOverflowMode
import dev.xross.launcher.theme.pack.ThemeLayout
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import dev.xross.launcher.state.SocialStack
import dev.xross.launcher.state.StatusBarState
import dev.xross.launcher.theme.WallpaperSource
import dev.xross.launcher.theme.pack.Cosmetics
import dev.xross.launcher.theme.pack.ParticlePreset
import dev.xross.launcher.theme.pack.UiFilter
import dev.xross.launcher.theme.pack.UiFilterRef
import dev.xross.launcher.theme.pack.nav
import dev.xross.launcher.theme.pack.tween
import dev.xross.launcher.theme.glowColor

// The XMB menu style (Menu styles slice 1): the category bar and the item column, moved here
// unchanged from XmbScreen.kt, which picks a style and draws it.

/**
 * The XMB menu style: the horizontal category bar and the item column under the crosshair. Its
 * anchors for the rest of the screen are [MenuAnchors.xmb].
 */
@Composable
internal fun XmbMenu(
    state: XmbUiState,
    m: XmbMetrics,
    motion: Motion,
    drillDown: DrillDownStyle,
    catPos: () -> Float,
    depth: () -> Float,
    pulse: () -> Float,
    loadAppIcon: suspend (String) -> ImageBitmap?,
    loadThumb: suspend (ThumbKind, Long, Int) -> ImageBitmap?,
    loadArt: suspend (String, List<ArtKind>, Int) -> ImageBitmap?,
    onEvent: (XmbEvent) -> Unit,
) {
    CategoryBar(
        categories = state.categories,
        categoryIndex = state.categoryIndex,
        breadcrumb = state.breadcrumb.joinToString("  ›  "),
        folder = state.folderGlyph,
        style = drillDown,
        m = m,
        catPos = catPos,
        depth = depth,
        pulse = pulse,
        onCategoryTap = { onEvent(XmbEvent.Menu.CategoryTap(it)) },
    )
    ItemColumn(state, m, motion, depth, pulse, loadAppIcon, loadThumb, loadArt,
        onItemTap = { onEvent(XmbEvent.Menu.ItemTap(it)) },
        onItemLongPress = { onEvent(XmbEvent.Menu.ItemLongPress(it)) },
    )
}

/**
 * Takes only what it draws, so cursor moves in the item list don't recompose the bar.
 *
 * Drilled into a list, [style] decides what shows where it is: the [breadcrumb] text beside the
 * open category ([DrillDownStyle.BREADCRUMB]), the opened [folder]'s icon parked left of the list
 * ([DrillDownStyle.XMB_FOLDER_COLUMN]), or nothing: the whole bar collapses and the focused row
 * shows a ◀ ([DrillDownStyle.CHEVRON_COLLAPSE], drawn by the row).
 */
@Composable
private fun CategoryBar(
    categories: List<XmbCategory>,
    categoryIndex: Int,
    breadcrumb: String,
    folder: GlyphKey?,
    style: DrillDownStyle,
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
    val collapse = style == DrillDownStyle.CHEVRON_COLLAPSE

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
                    // Collapsing, the whole bar also rises a little as it fades.
                    val lift = if (collapse) depth() * m.s * 0.06f else 0f
                    IntOffset(x.roundToInt(), (m.barY - m.categoryIcon / 2 - lift).roundToInt())
                }
                .fadeAlpha {
                    val f = focus()
                    val d = depth()
                    (0.62f + 0.38f * f) * (1f - d * (1f - f)) * (if (collapse) 1f - d else 1f)
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
                fontFamily = LocalThemeTypography.current.displayOrBody,
                modifier = Modifier.fadeAlpha { focus() * (1f - depth()) },
            )
        }
    }

    // XMB folder column: the opened folder's icon parks left of the list, under the open
    // category, level with the focused row.
    // Kept while the list closes, so the icon fades out with the depth instead of popping.
    var parked by remember { mutableStateOf(folder) }
    if (folder != null) parked = folder
    val shownFolder = parked
    if (style == DrillDownStyle.XMB_FOLDER_COLUMN && shownFolder != null) {
        val size = with(density) { (m.itemIcon * 1.15f).toDp() }
        Box(
            Modifier
                .offset {
                    val x = m.anchorX - m.categorySpacing * 0.85f - m.itemIcon * 1.15f / 2
                    IntOffset(x.roundToInt(), (m.focusedY - m.itemIcon * 1.15f / 2).roundToInt())
                }
                .fadeAlpha { depth() * 0.9f },
        ) {
            XmbIcon(glyphPainter(shownFolder), size, focus = { 0f }, focusScale = 1f, glow = { 0f })
        }
    }

    // Breadcrumb beside the open category while drilled in.
    if (style == DrillDownStyle.BREADCRUMB && breadcrumb.isNotEmpty()) {
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
    motion: Motion,
    depth: () -> Float,
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
        if (state.listKey != lastKey || motion.instant) {
            lastKey = state.listKey
            scroll.snapTo(state.focusedIndex.toFloat())
        } else {
            scroll.animateTo(state.focusedIndex.toFloat(), motion.nav())
        }
    }
    val enter = remember(state.listKey) { Animatable(if (motion.instant) 1f else 0f) }
    LaunchedEffect(state.listKey) { if (!motion.instant) enter.animateTo(1f, tween(280, easing = FastOutSlowInEasing)) }

    // Compose only the rows that can be on screen for this display.
    val first by remember(m) { derivedStateOf { floor(scroll.value).toInt() - m.rowsAbove } }
    val last by remember(m) { derivedStateOf { ceil(scroll.value).toInt() + m.rowsBelow } }
    val range = first.coerceAtLeast(0)..last.coerceAtMost(state.items.lastIndex)

    val density = LocalDensity.current
    val iconDp = with(density) { m.itemIcon.toDp() }
    val textX = m.anchorX + m.itemIcon * m.itemFocusScale / 2 + m.textGap
    // At least a readable width, but never past the screen edge (a large UI scale on a square or
    // 4:3 screen can leave less than that), so long titles ellipsize instead of being cut off.
    val textWidth = with(density) {
        (m.width - textX - m.width * 0.03f).coerceAtLeast(m.s * 0.3f).coerceAtMost((m.width - textX).coerceAtLeast(1f)).toDp()
    }
    val layout = m.layout
    val aspect = state.categories.getOrNull(state.categoryIndex)?.id?.let(layout::aspectFor)
    val chevron: () -> Float = if (layout.drillDown == DrillDownStyle.CHEVRON_COLLAPSE) depth else NoChevron
    val dim = layout.unfocusedAlpha

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
                        // A pack's unfocused opacity dims whole rows, icon and text alike.
                        val rest = dim?.let { it + (1f - it) * focus() } ?: 1f
                        enter.value * fade * rest
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
                // A system override's tile aspect shapes that system's game tiles.
                aspect = if (layout.systemAspects.isEmpty()) aspect else {
                    (node.action as? NodeAction.LaunchGame)?.let { state.gamePlatforms[it.gameId] }?.let(layout.systemAspects::get) ?: aspect
                },
                isFocused = i == state.focusedIndex,
                focus = focus,
                pulse = pulse,
                chevron = chevron,
                loadAppIcon = loadAppIcon,
                loadThumb = loadThumb,
                loadArt = loadArt,
                modifier = rowModifier,
                pills = if (i != state.focusedIndex || node.pills.isEmpty()) emptyList() else {
                    val telemetry = state.focusedTelemetry
                    remember(node.pills, telemetry) { BadgePills.shown(node.pills, telemetry) }
                },
            )
        }
    }
}

private val NoChevron: () -> Float = { 0f }

private fun itemY(d: Float, m: XmbMetrics): Float = when {
    d >= 0f -> m.focusedY + d * m.itemSpacing + m.focusGap * d.coerceAtMost(1f)
    d > -1f -> m.focusedY + (m.aboveY - m.focusedY) * -d
    else -> m.aboveY + (d + 1f) * m.itemSpacing
}

/** Photo and video thumbnails without a pack aspect: a little wider than tall. */
private const val ThumbWideAspect = 1.3f

/** A console logo's widest and tallest, in icon slots; a near-square logo just fills the slot. */
private const val LogoMaxWidth = 2.4f
private const val LogoMaxHeight = 0.8f

/** [painter]'s size in an icon slot of [iconSize]: square stays the slot, wide fits [LogoMaxWidth] x [LogoMaxHeight]. */
private fun logoSize(painter: Painter, iconSize: Dp): DpSize {
    val intrinsic = painter.intrinsicSize
    val aspect = if (intrinsic.isSpecified && intrinsic.height > 0f) intrinsic.width / intrinsic.height else 1f
    if (aspect <= 1.25f) return DpSize(iconSize, iconSize)
    val height = minOf(iconSize * LogoMaxHeight, iconSize * LogoMaxWidth / aspect)
    return DpSize(height * aspect, height)
}

@Composable
private fun ItemRow(
    node: XmbNode,
    m: XmbMetrics,
    iconSize: Dp,
    textWidth: Dp,
    /** The category's tile aspect from the theme layout, or null for each icon's own. */
    aspect: Float?,
    isFocused: Boolean,
    focus: () -> Float,
    pulse: () -> Float,
    /** ◀ opacity beside the focused row ([DrillDownStyle.CHEVRON_COLLAPSE] while drilled in). */
    chevron: () -> Float,
    loadAppIcon: suspend (String) -> ImageBitmap?,
    loadThumb: suspend (ThumbKind, Long, Int) -> ImageBitmap?,
    loadArt: suspend (String, List<ArtKind>, Int) -> ImageBitmap?,
    modifier: Modifier,
    /** The title badges drawn (2b.7b): the focused game's [XmbNode.pills], empty on every other row. */
    pills: List<TitlePill> = emptyList(),
) {
    val density = LocalDensity.current
    val titleSp = with(density) { m.titleSize.toSp() }
    val subtitleSp = with(density) { m.subtitleSize.toSp() }
    val layout = m.layout
    val scale = m.itemFocusScale
    // A game tile (or a wide thumbnail) is wider than the square icon slot it is centered on: the
    // text starts further right by half the difference (at the focus scale) and gives up the same
    // width. Built-in thumbnails (1.3) keep their slight overhang into the gap.
    val tileHeight = iconSize * GameTileHeightScale
    val tileAspect = aspect ?: GameTileAspect
    val thumbAspect = (node.icon as? NodeIcon.Thumb)?.let { aspect ?: if (it.kind == ThumbKind.ALBUM_ART) 1f else ThumbWideAspect }
    // A console logo is a wordmark, wider than tall: it keeps its shape and overhangs like a tile.
    val logo = (node.icon as? NodeIcon.System)?.let { systemLogo(it.platformId, it.style) }
    val logoSize = logo?.let { logoSize(it.painter, iconSize) }
    val visualWidth = when (node.icon) {
        is NodeIcon.Tile -> tileHeight * tileAspect
        is NodeIcon.Thumb -> iconSize * thumbAspect!!
        is NodeIcon.System -> logoSize?.width ?: iconSize
        else -> iconSize
    }
    val tileOverhang = when (node.icon) {
        is NodeIcon.Tile, is NodeIcon.System -> ((visualWidth - iconSize) * scale / 2).coerceAtLeast(0.dp)
        is NodeIcon.Thumb -> ((visualWidth - iconSize * ThumbWideAspect) * scale / 2).coerceAtLeast(0.dp)
        else -> 0.dp
    }
    val gap = with(density) { (m.textGap + m.itemIcon * (scale - 1f) / 2).toDp() } + tileOverhang
    // How far the icon reaches past its slot on each side at full focus.
    val reach = (visualWidth * scale - iconSize) / 2
    val glow = { if (isFocused) pulse() * focus() else 0f }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Box(Modifier.size(iconSize), contentAlignment = Alignment.Center) {
            when (val icon = node.icon) {
                is NodeIcon.Tile -> GameTile(icon, tileHeight, focus, scale, glow, loadArt, aspect = tileAspect)
                is NodeIcon.Glyph -> XmbIcon(
                    painter = glyphPainter(icon.key),
                    size = iconSize,
                    focus = focus,
                    focusScale = scale,
                    glow = glow,
                )
                is NodeIcon.System -> XmbIcon(
                    painter = logo?.painter ?: glyphPainter(icon.fallback),
                    size = iconSize,
                    focus = focus,
                    focusScale = scale,
                    glow = glow,
                    width = logoSize?.width ?: iconSize,
                    height = logoSize?.height ?: iconSize,
                    gloss = logo?.keepsColors != true,
                )
                is NodeIcon.App -> AppIcon(icon.packageName, iconSize, focus, scale, loadAppIcon)
                is NodeIcon.Thumb -> ThumbIcon(icon, iconSize, thumbAspect!!, focus, scale, glow, loadThumb)
                is NodeIcon.Art -> ArtIcon(icon, iconSize, focus, scale, glow, loadArt)
            }
            if (isFocused && chevron !== NoChevron) BackChevron(subtitleSp * 1.4f, reach, { chevron() * focus() })
        }
        Spacer(Modifier.width(gap))
        Column(Modifier.width((textWidth - tileOverhang).coerceAtLeast(iconSize))) {
            GlowText(
                text = node.title,
                fontSize = titleSp,
                glow = glow,
                overflow = layout.textOverflow,
                marquee = isFocused,
                modifier = Modifier.fadeAlpha { (if (layout.unfocusedAlpha != null) 1f else 0.72f + 0.28f * focus()) },
            )
            FocusDivider(
                style = layout.focusDivider,
                // From the icon's right edge (at full focus) to the screen's right edge.
                lead = gap - reach,
                screenWidth = m.width,
                focus = focus,
                modifier = Modifier.padding(top = with(density) { (m.s * 0.008f).toDp() }),
            )
            node.subtitle?.let {
                SubtitleLine(
                    text = it,
                    pills = pills,
                    size = subtitleSp,
                    modifier = Modifier
                        .padding(top = with(density) { (m.s * 0.01f).toDp() })
                        .fadeAlpha { focus() },
                )
            }
        }
    }
}

/**
 * The line under a title: [pills] (2b.7b, the focused game's badges) then [text], ellipsized in
 * the width the pills leave. The line is as tall as its text whether or not pills are drawn, so
 * focusing a game with badges never moves its title.
 */
@Composable
private fun SubtitleLine(text: String, pills: List<TitlePill>, size: TextUnit, modifier: Modifier) {
    Layout(
        modifier = modifier,
        content = {
            Text(text, style = shadowed(size, color = TextDim), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (pills.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier.clipToBounds(),
                ) {
                    for (p in pills) TitlePillChip(p, size * 0.8f)
                }
            }
        },
    ) { measurables, constraints ->
        val pillRow = measurables.getOrNull(1)?.measure(Constraints(maxWidth = constraints.maxWidth))
        val used = pillRow?.let { it.width + if (text.isEmpty()) 0 else 8.dp.roundToPx() } ?: 0
        val line = measurables[0].measure(Constraints(maxWidth = (constraints.maxWidth - used).coerceAtLeast(0)))
        val width = (used + line.width).coerceAtMost(constraints.maxWidth)
        layout(width, line.height) {
            pillRow?.place(0, (line.height - pillRow.height) / 2)
            line.place(used, 0)
        }
    }
}

/** One title badge: the pack's surface at a small radius; figures in the `mono` font. */
@Composable
private fun TitlePillChip(pill: TitlePill, size: TextUnit) {
    val fonts = LocalThemeTypography.current
    val family = if (pill.mono) fonts.monoOrBody else fonts.family
    Text(
        pill.text,
        style = shadowed(size, weight = FontWeight.Medium).copy(fontFamily = family, shadow = null),
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .xrossSurface(RoundedCornerShape(4.dp), strength = 0.85f, borderAlpha = 0.5f, borderScale = 2f / 3f)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/**
 * The hairline between the focused title and its subtitle. [lead] is the distance from the
 * column back to the focused icon's right edge, where [FocusDividerStyle.FULL_BLEED_LINE] starts;
 * it runs on to the screen's edge (the window clips it).
 */
@Composable
private fun FocusDivider(style: FocusDividerStyle, lead: Dp, screenWidth: Float, focus: () -> Float, modifier: Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .then(
                when (style) {
                    FocusDividerStyle.NONE -> Modifier
                    FocusDividerStyle.TITLE_UNDERLINE -> Modifier
                        .graphicsLayer { alpha = focus() }
                        .drawBehind {
                            drawRect(
                                Brush.horizontalGradient(
                                    0f to Color.White.copy(alpha = 0.95f),
                                    0.7f to Color.White.copy(alpha = 0.8f),
                                    1f to Color.White.copy(alpha = 0.1f),
                                ),
                            )
                        }
                    // ModulateAlpha: the line draws outside the box, which a layer would clip.
                    FocusDividerStyle.FULL_BLEED_LINE -> Modifier
                        .fadeAlpha(focus)
                        .drawBehind {
                            val start = -lead.toPx()
                            val end = size.width + screenWidth
                            drawRect(
                                Brush.horizontalGradient(
                                    0f to Color.White.copy(alpha = 0.9f),
                                    1f to Color.White.copy(alpha = 0.55f),
                                    startX = start,
                                    endX = size.width,
                                ),
                                topLeft = Offset(start, 0f),
                                size = androidx.compose.ui.geometry.Size(end - start, size.height),
                            )
                        }
                },
            ),
    )
}

/**
 * The ◀ left of the focused icon while drilled in with [DrillDownStyle.CHEVRON_COLLAPSE]. Takes
 * no room: it is drawn [reach] past the icon slot's left edge, plus a small gap.
 */
@Composable
private fun BoxScope.BackChevron(size: TextUnit, reach: Dp, alpha: () -> Float) {
    Text(
        "◀",
        style = shadowed(size, weight = FontWeight.Bold),
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .align(Alignment.CenterStart)
            .layout { measurable, constraints ->
                val p = measurable.measure(constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity))
                val x = -p.width - reach.roundToPx() - 6.dp.roundToPx()
                // Its own size (the slot is fixed-size, so it takes no room), drawn left of the slot.
                layout(p.width, p.height) { p.place(x, 0) }
            }
            // Inside the placement, so the node's bounds are where the ◀ is drawn.
            .semantics { contentDescription = "Back" }
            .fadeAlpha(alpha),
    )
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
    /** A wide icon (a console logo) is drawn [width] x [height], centered on the [size] slot. */
    width: Dp = size,
    height: Dp = size,
    /** The white-to-silver fill; false keeps the icon's own colors. */
    gloss: Boolean = true,
) {
    val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val frame = LocalFocusFrame.current
    val accent = LocalThemeAccent.current
    val neon = frame.style == FocusFrameStyle.STATIC_NEON
    val wide = width > height
    Box(
        Modifier
            .requiredSize(width, height)
            .graphicsLayer {
                val s = 1f + (focusScale - 1f) * focus()
                scaleX = s
                scaleY = s
            },
    ) {
        // Halo (2b.4d: white and breathing, or the neon / gold frame's color held steady).
        if (frame.style != FocusFrameStyle.HARD_SHADOW) {
            // Drawn as a circle across the icon's width, then squashed to an ellipse for a wide logo.
            Box(
                Modifier
                    .align(Alignment.Center)
                    .requiredSize(width)
                    .graphicsLayer {
                        alpha = glow()
                        val spread = if (neon) 1.6f else 1.9f
                        scaleX = if (wide) (if (neon) 1.15f else 1.25f) else spread
                        scaleY = spread * (height / width)
                    }
                    .drawBehind {
                        val c = frame.bloomColor(accent) ?: return@drawBehind
                        val core = if (neon) 0.6f else 0.42f
                        drawCircle(
                            Brush.radialGradient(
                                0f to c.copy(alpha = core),
                                0.45f to c.copy(alpha = core / 3f),
                                1f to Color.Transparent,
                            ),
                        )
                    },
            )
            if (canBlur) {
                val tint = when (frame.style) {
                    FocusFrameStyle.STATIC_NEON -> frame.neon
                    FocusFrameStyle.GOLD_BEZEL -> frame.gold
                    else -> Color.White
                }
                BlurredCopy(painter, width, height, tint, blur = if (neon) 3.dp else 6.dp, alpha = { glow() * 0.9f })
            }
        } else {
            // A solid copy 2 dp down and right, no blur.
            BlurredCopy(painter, width, height, Color.Black, blur = 0.dp, alpha = { glow() * 0.85f }, offset = IntOffset(2, 2))
        }
        // Drop shadow.
        BlurredCopy(
            painter,
            width,
            height,
            Color.Black,
            blur = if (canBlur) 2.5.dp else 0.dp,
            alpha = { 0.38f },
            offset = IntOffset(1, 2),
        )
        // Glossy body (or the logo's own colors).
        Image(
            painter,
            contentDescription = null,
            modifier = if (!gloss) Modifier.fillMaxSize() else Modifier
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
    width: Dp,
    height: Dp,
    tint: Color,
    blur: Dp,
    alpha: () -> Float,
    offset: IntOffset = IntOffset.Zero,
) {
    Box(
        Modifier
            .wrapContentSize(unbounded = true)
            .requiredSize(width * 2, height * 2)
            .offset { IntOffset((offset.x * density).roundToInt(), (offset.y * density).roundToInt()) }
            .graphicsLayer { this.alpha = alpha() }
            .then(if (blur > 0.dp) Modifier.blur(blur, BlurredEdgeTreatment.Unbounded) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Image(painter, contentDescription = null, colorFilter = ColorFilter.tint(tint), modifier = Modifier.size(width, height))
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
            val frame = LocalFocusFrame.current
            val accent = LocalThemeAccent.current
            if (frame.style == FocusFrameStyle.BREATHING_HALO || frame.style == FocusFrameStyle.STATIC_NEON) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = glow(); scaleX = 1.6f; scaleY = 1.6f }
                        .drawBehind {
                            val c = frame.bloomColor(accent) ?: return@drawBehind
                            drawCircle(Brush.radialGradient(0f to c.copy(alpha = 0.3f), 1f to Color.Transparent))
                        },
                )
            }
            // Exactly the glyph's footprint, so tall capsules and wide boxes never reach the
            // neighbouring rows (the focus scale is applied by the layer above, as for glyphs).
            Image(
                image,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(size)
                    .then(if (frame.framesPlates) Modifier.drawBehind { fittedFrame(frame, accent, glow(), image.width, image.height) } else Modifier)
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
    /** Width over height of the thumbnail ([ItemRow] resolves the theme's tile aspect). */
    aspect: Float,
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
    // Wide thumbnails keep the row height; portrait ones keep the slot height and narrow.
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
        val frame = LocalFocusFrame.current
        val accent = LocalThemeAccent.current
        if (frame.style == FocusFrameStyle.BREATHING_HALO || frame.style == FocusFrameStyle.STATIC_NEON) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = glow(); scaleX = 1.7f; scaleY = 1.7f }
                    .drawBehind {
                        val c = frame.bloomColor(accent) ?: return@drawBehind
                        drawCircle(Brush.radialGradient(0f to c.copy(alpha = 0.35f), 1f to Color.Transparent))
                    },
            )
        }
        Image(
            image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(width = size * aspect, height = size)
                .wrapContentSize(unbounded = true)
                .then(if (frame.framesPlates) Modifier.drawBehind { drawPlateFrame(frame, accent, glow(), 4.dp.toPx()) } else Modifier)
                .shadow(4.dp, RoundedCornerShape(4.dp))
                .clip(RoundedCornerShape(4.dp))
                .border(1.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(4.dp)),
        )
    }
}

/**
 * [drawPlateFrame] around an image of [imageW] × [imageH] drawn with [ContentScale.Fit] in this
 * scope (centered), so the frame hugs the art rather than the square slot.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.fittedFrame(frame: FocusFrame, accent: ThemeAccent, glow: Float, imageW: Int, imageH: Int) {
    if (glow <= 0f || imageW <= 0 || imageH <= 0) return
    val k = minOf(size.width / imageW, size.height / imageH)
    val w = imageW * k
    val h = imageH * k
    translate((size.width - w) / 2, (size.height - h) / 2) {
        drawPlateFrame(frame, accent, glow, 3.dp.toPx(), androidx.compose.ui.geometry.Size(w, h))
    }
}
