package dev.wave.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wave.launcher.input.ConfirmLayout
import dev.wave.launcher.input.NavAction
import dev.wave.launcher.state.NodeAction
import dev.wave.launcher.state.NodeIcon
import dev.wave.launcher.state.RemapSession
import dev.wave.launcher.state.XmbNode
import dev.wave.launcher.state.XmbUiState
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

private val TextBright = Color(0xFFFFFFFF)
private val TextDim = Color(0xB3FFFFFF)
private val Pill = Color(0x66000000)

/** Layout metrics for one screen size. Everything scales with the short side of the display. */
private class XmbMetrics(widthPx: Float, heightPx: Float, private val density: Float) {
    private val landscape = widthPx >= heightPx
    val unit = ((if (landscape) heightPx else widthPx) / density / 360f).coerceIn(0.8f, 2f)
    fun dp(v: Float) = v * unit * density

    val anchorX = widthPx * if (landscape) 0.26f else 0.16f
    val barY = heightPx * 0.28f
    val categorySpacing = dp(112f)
    val categoryIcon = dp(44f)
    val itemIcon = dp(40f)
    val itemSpacing = dp(54f)
    val focusedY = barY + dp(92f)
    val aboveY = barY - dp(58f)
    val focusGap = dp(26f)
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
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .swipeNavigation(onAction),
    ) {
        val density = LocalDensity.current.density
        val m = remember(constraints.maxWidth, constraints.maxHeight, density) {
            XmbMetrics(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(), density)
        }
        val unit = m.unit

        val catPos by animateFloatAsState(
            state.categoryIndex.toFloat(),
            tween(240, easing = FastOutSlowInEasing),
            label = "category",
        )
        val depthAnim by animateFloatAsState(
            state.depth.coerceAtMost(1).toFloat(),
            tween(220, easing = FastOutSlowInEasing),
            label = "depth",
        )

        CategoryBar(state, m, catPos = { catPos }, depth = { depthAnim }, onCategoryTap)
        ItemColumn(state, m, loadAppIcon, onItemTap)

        Clock(Modifier.align(Alignment.TopEnd).padding(top = (16 * unit).dp, end = (24 * unit).dp), unit)
        ButtonHints(state, unit, Modifier.align(Alignment.BottomEnd).padding((20 * unit).dp))

        AnimatedVisibility(
            visible = state.toast != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = (18 * unit).dp),
        ) {
            var last by remember { mutableStateOf("") }
            state.toast?.let { last = it }
            Text(
                last,
                color = TextBright,
                fontSize = (14 * unit).sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Pill)
                    .padding(horizontal = (18 * unit).dp, vertical = (8 * unit).dp),
            )
        }

        state.remap?.let { RemapOverlay(it, unit, onCancelRemap) }
    }
}

@Composable
private fun CategoryBar(
    state: XmbUiState,
    m: XmbMetrics,
    catPos: () -> Float,
    depth: () -> Float,
    onCategoryTap: (Int) -> Unit,
) {
    val unit = m.unit
    state.categories.forEachIndexed { i, category ->
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .width(with(LocalDensity.current) { m.categorySpacing.toDp() })
                .offset {
                    val shift = depth() * m.categorySpacing * 0.9f
                    val x = m.anchorX + (i - catPos()) * m.categorySpacing - shift - m.categorySpacing / 2
                    IntOffset(x.roundToInt(), (m.barY - m.categoryIcon / 2).roundToInt())
                }
                .graphicsLayer {
                    val focus = 1f - abs(i - catPos()).coerceAtMost(1f)
                    val d = depth()
                    alpha = (0.55f + 0.45f * focus) * (1f - d * (1f - focus))
                    val s = 1f + 0.2f * focus
                    scaleX = s
                    scaleY = s
                }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    onCategoryTap(i)
                },
        ) {
            Icon(
                category.icon.vector(),
                contentDescription = category.title,
                tint = TextBright,
                modifier = Modifier.size(with(LocalDensity.current) { m.categoryIcon.toDp() }),
            )
            Text(
                category.title,
                color = TextBright,
                fontSize = (13 * unit).sp,
                maxLines = 1,
                modifier = Modifier.graphicsLayer {
                    alpha = (1f - abs(i - catPos()).coerceAtMost(1f)) * (1f - depth())
                },
            )
        }
    }

    // Breadcrumb for drilled-in lists, sitting where the category label was.
    if (state.breadcrumb.isNotEmpty()) {
        val title = (listOf(state.categories[state.categoryIndex].title) + state.breadcrumb).joinToString("  ›  ")
        Text(
            title,
            color = TextBright,
            fontSize = (15 * unit).sp,
            fontWeight = FontWeight.Light,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .offset {
                    IntOffset(
                        (m.anchorX - m.categorySpacing * 0.9f + m.categoryIcon * 0.9f).roundToInt(),
                        (m.barY - m.dp(10f)).roundToInt(),
                    )
                }
                .widthIn(max = (420 * unit).dp),
        )
    }
}

@Composable
private fun ItemColumn(
    state: XmbUiState,
    m: XmbMetrics,
    loadAppIcon: suspend (String) -> ImageBitmap?,
    onItemTap: (Int) -> Unit,
) {
    // Scroll snaps when the list itself changes, and animates when the cursor moves.
    val scroll = remember { Animatable(state.focusedIndex.toFloat()) }
    var lastKey by remember { mutableStateOf(state.listKey) }
    LaunchedEffect(state.listKey, state.focusedIndex) {
        if (state.listKey != lastKey) {
            lastKey = state.listKey
            scroll.snapTo(state.focusedIndex.toFloat())
        } else {
            scroll.animateTo(state.focusedIndex.toFloat(), tween(150, easing = FastOutSlowInEasing))
        }
    }
    val enter = remember(state.listKey) { Animatable(0f) }
    LaunchedEffect(state.listKey) { enter.animateTo(1f, tween(220, easing = FastOutSlowInEasing)) }

    val first by remember { derivedStateOf { floor(scroll.value).toInt() - 6 } }
    val last by remember { derivedStateOf { ceil(scroll.value).toInt() + 10 } }
    val range = first.coerceAtLeast(0)..last.coerceAtMost(state.items.lastIndex)

    val iconDp = with(LocalDensity.current) { m.itemIcon.toDp() }
    for (i in range) {
        val node = state.items[i]
        key(state.listKey, node.id) {
            ItemRow(
                node = node,
                iconSize = iconDp,
                unit = m.unit,
                loadAppIcon = loadAppIcon,
                focus = { 1f - abs(i - scroll.value).coerceAtMost(1f) },
                modifier = Modifier
                    .offset {
                        val y = itemY(i - scroll.value, m)
                        val slide = (1f - enter.value) * m.dp(36f)
                        IntOffset((m.anchorX - m.itemIcon / 2 + slide).roundToInt(), (y - m.itemIcon / 2).roundToInt())
                    }
                    .graphicsLayer {
                        // Items scrolled up past the top edge fade out instead of clipping.
                        val fade = (itemY(i - scroll.value, m) / m.itemSpacing).coerceIn(0f, 1f)
                        alpha = enter.value * fade
                    }
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        onItemTap(i)
                    },
            )
        }
    }
}

@Composable
private fun ItemRow(
    node: XmbNode,
    iconSize: Dp,
    unit: Float,
    loadAppIcon: suspend (String) -> ImageBitmap?,
    focus: () -> Float,
    modifier: Modifier,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(iconSize)
                .graphicsLayer {
                    val s = 1f + 0.35f * focus()
                    scaleX = s
                    scaleY = s
                },
        ) {
            NodeIconView(node.icon, iconSize, loadAppIcon)
        }
        Spacer(Modifier.width((22 * unit).dp))
        Column(
            modifier = Modifier.graphicsLayer { alpha = 0.6f + 0.4f * focus() },
        ) {
            Text(
                node.title,
                color = TextBright,
                fontSize = (17 * unit).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = (520 * unit).dp),
            )
            node.subtitle?.let {
                Text(
                    it,
                    color = TextDim,
                    fontSize = (12 * unit).sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .widthIn(max = (520 * unit).dp)
                        .graphicsLayer { alpha = focus() },
                )
            }
        }
    }
}

@Composable
private fun NodeIconView(icon: NodeIcon, size: Dp, loadAppIcon: suspend (String) -> ImageBitmap?) {
    when (icon) {
        is NodeIcon.Glyph -> Icon(icon.key.vector(), contentDescription = null, tint = TextBright, modifier = Modifier.size(size))
        is NodeIcon.App -> {
            val bitmap by produceState<ImageBitmap?>(null, icon.packageName) { value = loadAppIcon(icon.packageName) }
            bitmap?.let { Image(it, contentDescription = null, modifier = Modifier.size(size)) }
        }
    }
}

@Composable
private fun Clock(modifier: Modifier, unit: Float) {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            delay(15_000)
        }
    }
    val text = remember(now) {
        DateFormat.getDateInstance(DateFormat.SHORT).format(now) + "  " +
            DateFormat.getTimeInstance(DateFormat.SHORT).format(now)
    }
    Text(text, color = TextBright, fontSize = (14 * unit).sp, fontWeight = FontWeight.Light, modifier = modifier)
}

@Composable
private fun ButtonHints(state: XmbUiState, unit: Float, modifier: Modifier) {
    val confirmGlyph = if (state.confirm == ConfirmLayout.EAST) state.family.east else state.family.south
    val backGlyph = if (state.confirm == ConfirmLayout.EAST) state.family.south else state.family.east
    val focused = state.focused
    Row(horizontalArrangement = Arrangement.spacedBy((14 * unit).dp), modifier = modifier) {
        if (focused != null && focused.action != NodeAction.None) Hint(confirmGlyph, "Select", unit)
        if (state.depth > 0) Hint(backGlyph, "Back", unit)
        focused?.optionsLabel?.let { Hint(state.family.north, it, unit) }
    }
}

@Composable
private fun Hint(glyph: String, label: String, unit: Float) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            glyph,
            color = TextBright,
            fontSize = (12 * unit).sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Pill)
                .padding(horizontal = (8 * unit).dp, vertical = (3 * unit).dp),
        )
        Spacer(Modifier.width((6 * unit).dp))
        Text(label, color = TextBright, fontSize = (13 * unit).sp)
    }
}

@Composable
private fun RemapOverlay(session: RemapSession, unit: Float, onCancel: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xB0000000))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onCancel),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Remap buttons  ${session.progress}", color = TextDim, fontSize = (14 * unit).sp)
            Spacer(Modifier.size((10 * unit).dp))
            Text(
                "Press the button for: " + (session.target?.let(RemapSession::label) ?: ""),
                color = TextBright,
                fontSize = (22 * unit).sp,
                fontWeight = FontWeight.Light,
            )
            Spacer(Modifier.size((14 * unit).dp))
            Text("Tap the screen to cancel", color = TextDim, fontSize = (12 * unit).sp)
        }
    }
}

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

private fun itemY(d: Float, m: XmbMetrics): Float = when {
    d >= 0f -> m.focusedY + d * m.itemSpacing + m.focusGap * d.coerceAtMost(1f)
    d > -1f -> m.focusedY + (m.aboveY - m.focusedY) * -d
    else -> m.aboveY + (d + 1f) * m.itemSpacing
}
