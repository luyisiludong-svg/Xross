package dev.wave.launcher.ui

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import dev.wave.launcher.input.ConfirmLayout
import dev.wave.launcher.input.NavAction
import dev.wave.launcher.state.NodeAction
import dev.wave.launcher.state.NodeIcon
import dev.wave.launcher.state.RemapSession
import dev.wave.launcher.state.XmbNode
import dev.wave.launcher.state.XmbUiState
import dev.wave.launcher.theme.WaveTheme
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

private val TextBright = Color(0xFFFFFFFF)
private val TextDim = Color(0xD9FFFFFF)
private val DropShadow = Color(0x99000000)
private val IconTop = Color(0xFFFFFFFF)
private val IconBottom = Color(0xFFC9C9D3)
private val Pill = Color(0x59000000)

/**
 * Layout in fractions of the screen, measured from the reference screenshots: the focused
 * category column sits at ~22% width, the category bar at ~25% height and the focused item at
 * ~52% height. Sizes scale with the short side so portrait and odd aspect ratios still work.
 */
private class XmbMetrics(val width: Float, val height: Float) {
    val s = min(height, width * 0.75f)

    val anchorX = width * if (width >= height) 0.22f else 0.14f
    val barY = height * 0.25f
    val categorySpacing = s * 0.34f
    val categoryIcon = s * 0.12f
    val categoryFocusScale = 1.38f
    val itemIcon = s * 0.085f
    val itemFocusScale = 1.32f
    val focusedY = height * 0.52f
    val itemSpacing = s * 0.15f
    val focusGap = s * 0.05f
    val aboveY = barY - s * 0.19f
    val textGap = s * 0.06f
    val titleSize = s * 0.047f
    val subtitleSize = s * 0.036f
    val labelSize = s * 0.037f
    val statusSize = s * 0.042f
}

@Composable
fun XmbScreen(
    state: XmbUiState,
    loadAppIcon: suspend (String) -> ImageBitmap?,
    onAction: (NavAction) -> Unit,
    onCategoryTap: (Int) -> Unit,
    onItemTap: (Int) -> Unit,
    onCancelRemap: () -> Unit,
) {
    val theme = WaveTheme.byName(state.theme)

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

        val catPos by animateFloatAsState(
            state.categoryIndex.toFloat(),
            tween(260, easing = FastOutSlowInEasing),
            label = "category",
        )
        val depth by animateFloatAsState(
            state.depth.coerceAtMost(1).toFloat(),
            tween(260, easing = FastOutSlowInEasing),
            label = "depth",
        )

        CategoryBar(state, m, { catPos }, { depth }, { pulse }, onCategoryTap)
        ItemColumn(state, m, { pulse }, loadAppIcon, onItemTap)

        StatusBar(
            textSize = m.statusSize.sp(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = (m.s * 0.04f).dp(), end = (m.width * 0.025f).dp()),
        )
        ButtonHints(
            state,
            m.subtitleSize.sp(),
            Modifier.align(Alignment.BottomEnd).padding((m.s * 0.04f).dp()),
        )

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
                    .background(Pill)
                    .padding(horizontal = (m.s * 0.04f).dp(), vertical = (m.s * 0.018f).dp()),
            )
        }

        state.remap?.let { RemapOverlay(it, m, onCancelRemap) }
        state.booting?.let { GameBootOverlay(it, theme, m) }
    }
}

// region Category bar

@Composable
private fun CategoryBar(
    state: XmbUiState,
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

    state.categories.forEachIndexed { i, category ->
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
                .graphicsLayer {
                    val f = focus()
                    alpha = (0.62f + 0.38f * f) * (1f - depth() * (1f - f))
                }
                .clickable(remember { MutableInteractionSource() }, indication = null) { onCategoryTap(i) },
        ) {
            XmbIcon(
                painter = painterResource(category.icon.drawable()),
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
                modifier = Modifier.graphicsLayer { alpha = focus() * (1f - depth()) },
            )
        }
    }

    // Breadcrumb beside the open category while drilled in.
    if (state.breadcrumb.isNotEmpty()) {
        GlowText(
            text = state.breadcrumb.joinToString("  ›  "),
            fontSize = with(density) { m.titleSize.toSp() },
            glow = { 0f },
            modifier = Modifier
                .offset {
                    IntOffset(
                        (m.anchorX - m.categorySpacing * 0.85f + m.categoryIcon * 1.15f).roundToInt(),
                        (m.barY - m.titleSize * 0.7f).roundToInt(),
                    )
                }
                .graphicsLayer { alpha = 0.92f },
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
    onItemTap: (Int) -> Unit,
) {
    // Scroll snaps when the list itself changes and glides when the cursor moves.
    val scroll = remember { Animatable(state.focusedIndex.toFloat()) }
    var lastKey by remember { mutableStateOf(state.listKey) }
    LaunchedEffect(state.listKey, state.focusedIndex) {
        if (state.listKey != lastKey) {
            lastKey = state.listKey
            scroll.snapTo(state.focusedIndex.toFloat())
        } else {
            scroll.animateTo(state.focusedIndex.toFloat(), tween(190, easing = FastOutSlowInEasing))
        }
    }
    val enter = remember(state.listKey) { Animatable(0f) }
    LaunchedEffect(state.listKey) { enter.animateTo(1f, tween(280, easing = FastOutSlowInEasing)) }

    val first by remember { derivedStateOf { floor(scroll.value).toInt() - 4 } }
    val last by remember { derivedStateOf { ceil(scroll.value).toInt() + 6 } }
    val range = first.coerceAtLeast(0)..last.coerceAtMost(state.items.lastIndex)

    val density = LocalDensity.current
    val iconDp = with(density) { m.itemIcon.toDp() }
    val textX = m.anchorX + m.itemIcon * m.itemFocusScale / 2 + m.textGap
    val textWidth = with(density) { (m.width - textX - m.width * 0.03f).coerceAtLeast(m.s * 0.3f).toDp() }

    for (i in range) {
        val node = state.items[i]
        val focus = { 1f - abs(i - scroll.value).coerceAtMost(1f) }
        key(state.listKey, node.id) {
            ItemRow(
                node = node,
                m = m,
                iconSize = iconDp,
                textWidth = textWidth,
                isFocused = i == state.focusedIndex,
                focus = focus,
                pulse = pulse,
                loadAppIcon = loadAppIcon,
                modifier = Modifier
                    .offset {
                        val y = itemY(i - scroll.value, m)
                        val slide = (1f - enter.value) * m.s * 0.08f
                        IntOffset(
                            (m.anchorX - m.itemIcon / 2 + slide).roundToInt(),
                            (y - m.itemIcon / 2).roundToInt(),
                        )
                    }
                    .graphicsLayer {
                        // Items scrolled up past the top edge fade out instead of clipping.
                        val fade = (itemY(i - scroll.value, m) / m.itemSpacing).coerceIn(0f, 1f)
                        alpha = enter.value * fade
                    }
                    .clickable(remember { MutableInteractionSource() }, indication = null) { onItemTap(i) },
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
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val titleSp = with(density) { m.titleSize.toSp() }
    val subtitleSp = with(density) { m.subtitleSize.toSp() }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Box(Modifier.size(iconSize), contentAlignment = Alignment.Center) {
            when (val icon = node.icon) {
                is NodeIcon.Glyph -> XmbIcon(
                    painter = painterResource(icon.key.drawable()),
                    size = iconSize,
                    focus = focus,
                    focusScale = m.itemFocusScale,
                    glow = { if (isFocused) pulse() * focus() else 0f },
                )
                is NodeIcon.App -> AppIcon(icon.packageName, iconSize, focus, m.itemFocusScale, loadAppIcon)
            }
        }
        Spacer(Modifier.width(with(density) { (m.textGap + m.itemIcon * (m.itemFocusScale - 1f) / 2).toDp() }))
        Column(Modifier.width(textWidth)) {
            GlowText(
                text = node.title,
                fontSize = titleSp,
                glow = { if (isFocused) pulse() * focus() else 0f },
                modifier = Modifier.graphicsLayer { alpha = 0.72f + 0.28f * focus() },
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
                        .graphicsLayer { alpha = focus() },
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

private fun shadowed(size: TextUnit, color: Color = TextBright, weight: FontWeight = FontWeight.Normal) = TextStyle(
    color = color,
    fontSize = size,
    fontWeight = weight,
    shadow = Shadow(DropShadow, Offset(0f, 3f), blurRadius = 6f),
)

/**
 * White text with a dark drop shadow, plus a second pass rendered as a soft white bloom whose
 * opacity follows [glow]. Only the bloom layer's alpha animates, so the pulse never re-lays-out
 * text.
 */
@Composable
private fun GlowText(
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
                shadow = Shadow(Color.White, Offset.Zero, blurRadius = 18f),
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.graphicsLayer {
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
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.graphicsLayer { alpha = 0.8f },
    ) {
        if (focused != null && focused.action != NodeAction.None) Hint(confirmGlyph, "Select", size)
        if (state.depth > 0) Hint(backGlyph, "Back", size)
        focused?.optionsLabel?.let { Hint(state.family.north, it, size) }
    }
}

@Composable
private fun Hint(glyph: String, label: String, size: TextUnit) {
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
            Text("Remap buttons  ${session.progress}", style = shadowed(with(density) { m.subtitleSize.toSp() }, TextDim))
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
private fun GameBootOverlay(title: String, theme: WaveTheme, m: XmbMetrics) {
    val progress = remember(title) { Animatable(0f) }
    LaunchedEffect(title) { progress.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
    val density = LocalDensity.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .drawBehind {
                val p = progress.value
                drawRect(Color.Black.copy(alpha = 0.55f * p))
                drawCircle(
                    Brush.radialGradient(
                        0f to theme.glowColor.copy(alpha = 0.85f * p),
                        0.6f to theme.glowColor.copy(alpha = 0.25f * p),
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

/** Touch parity: swipe between categories horizontally and through items vertically. */
private fun Modifier.swipeNavigation(onAction: (NavAction) -> Unit): Modifier = pointerInput(Unit) {
    val step = 56.dp.toPx()
    var dx = 0f
    var dy = 0f
    var axis = 0 // 0 = undecided, 1 = horizontal, 2 = vertical
    detectDragGestures(
        onDragStart = { dx = 0f; dy = 0f; axis = 0 },
        onDrag = { change, amount ->
            change.consume()
            dx += amount.x
            dy += amount.y
            if (axis == 0) axis = if (abs(dx) > abs(dy)) 1 else 2
            if (axis == 1) {
                while (dx <= -step) { onAction(NavAction.RIGHT); dx += step }
                while (dx >= step) { onAction(NavAction.LEFT); dx -= step }
            } else {
                while (dy <= -step) { onAction(NavAction.DOWN); dy += step }
                while (dy >= step) { onAction(NavAction.UP); dy -= step }
            }
        },
    )
}
