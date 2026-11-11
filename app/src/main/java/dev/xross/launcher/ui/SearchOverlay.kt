package dev.xross.launcher.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.xross.launcher.input.ConfirmLayout
import dev.xross.launcher.input.ControllerFamily
import dev.xross.launcher.state.Key
import dev.xross.launcher.state.KeyKind
import dev.xross.launcher.state.SearchFocus
import dev.xross.launcher.state.SearchKeyboard
import dev.xross.launcher.state.SearchState

/**
 * Search: the query field and gamepad keyboard on one side, live results on the other (stacked
 * in portrait). Drawn over the wave with the XMB faded out.
 */
@Composable
internal fun SearchOverlay(
    search: SearchState,
    m: XmbMetrics,
    pulse: () -> Float,
    family: ControllerFamily,
    confirm: ConfirmLayout,
    onKeyTap: (Int, Int) -> Unit,
    onResultTap: (Int) -> Unit,
) {
    val density = LocalDensity.current
    fun Float.dp(): Dp = with(density) { this@dp.toDp() }
    fun Float.sp() = with(density) { this@sp.toSp() }
    val landscape = m.width >= m.height
    val margin = m.s * 0.05f

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x8C000000))
            // Swallow touches so the XMB underneath doesn't react.
            .clickable(remember { MutableInteractionSource() }, indication = null) {}
            .padding(margin.dp()),
    ) {
        val keyboardWidth = if (landscape) (m.width - margin * 2) * 0.52f else m.width - margin * 2
        val resultsWidth = if (landscape) (m.width - margin * 2) * 0.44f else m.width - margin * 2

        val keyboard: @Composable () -> Unit = {
            Column(Modifier.width(keyboardWidth.dp())) {
                QueryField(search, m)
                Spacer(Modifier.height((m.s * 0.03f).dp()))
                KeyGrid(search, m, keyboardWidth, pulse, onKeyTap)
            }
        }
        val results: @Composable (Modifier) -> Unit = { modifier ->
            ResultList(search, m, pulse, onResultTap, modifier.width(resultsWidth.dp()))
        }

        if (landscape) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceBetween) {
                keyboard()
                results(Modifier.padding(bottom = (m.s * 0.09f).dp()))
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                results(Modifier.weight(1f))
                Spacer(Modifier.height((m.s * 0.03f).dp()))
                keyboard()
                Spacer(Modifier.height((m.s * 0.1f).dp()))
            }
        }

        val confirmGlyph = if (confirm == ConfirmLayout.EAST) family.east else family.south
        val backGlyph = if (confirm == ConfirmLayout.EAST) family.south else family.east
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .clip(RoundedCornerShape(50))
                .background(Color(0x40000000))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            if (search.focus == SearchFocus.KEYS) {
                Hint(confirmGlyph, "Type", m.subtitleSize.sp())
                Hint(family.north, "Space", m.subtitleSize.sp())
                Hint("L1", "Delete", m.subtitleSize.sp())
                if (search.results.isNotEmpty()) Hint("R1", "Results", m.subtitleSize.sp())
                Hint(backGlyph, "Close", m.subtitleSize.sp())
            } else {
                Hint(confirmGlyph, "Play", m.subtitleSize.sp())
                Hint(family.north, "Options", m.subtitleSize.sp())
                Hint(backGlyph, "Keyboard", m.subtitleSize.sp())
            }
        }
    }
}

@Composable
private fun QueryField(search: SearchState, m: XmbMetrics) {
    val density = LocalDensity.current
    val caret by rememberInfiniteTransition(label = "caret").animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(520), RepeatMode.Reverse),
        label = "blink",
    )
    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                search.query.ifEmpty { "Search games" },
                style = shadowed(with(density) { (m.titleSize * 1.25f).toSp() }, color = if (search.query.isEmpty()) TextDim else TextBright),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (search.focus == SearchFocus.KEYS) {
                Box(
                    Modifier
                        .padding(start = 4.dp, bottom = 4.dp)
                        .width(2.dp)
                        .height(with(density) { (m.titleSize * 1.2f).toDp() })
                        .graphicsLayer { alpha = caret }
                        .background(Color.White),
                )
            }
        }
        Box(
            Modifier
                .padding(top = 4.dp)
                .fillMaxWidth()
                .height(1.dp)
                .background(Brush.horizontalGradient(0f to Color.White.copy(alpha = 0.95f), 1f to Color.White.copy(alpha = 0.15f))),
        )
    }
}

@Composable
private fun KeyGrid(
    search: SearchState,
    m: XmbMetrics,
    widthPx: Float,
    pulse: () -> Float,
    onKeyTap: (Int, Int) -> Unit,
) {
    val density = LocalDensity.current
    val gap = m.s * 0.012f
    val cell = (widthPx - gap * (SearchKeyboard.COLUMNS - 1)) / SearchKeyboard.COLUMNS
    val keyHeight = minOf(cell, m.s * 0.105f)
    val focusedKey = if (search.focus == SearchFocus.KEYS) search.focusedKey else null

    Column(verticalArrangement = Arrangement.spacedBy(with(density) { gap.toDp() })) {
        SearchKeyboard.rows.forEachIndexed { rowIndex, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(with(density) { gap.toDp() })) {
                for (key in row) {
                    val focused = key === focusedKey && rowIndex == search.row
                    KeyCap(
                        key = key,
                        focused = focused,
                        pulse = pulse,
                        width = with(density) { (cell * key.span + gap * (key.span - 1)).toDp() },
                        height = with(density) { keyHeight.toDp() },
                        textSize = with(density) { (if (key.kind == KeyKind.CHAR) m.titleSize else m.subtitleSize).toSp() },
                        onTap = { onKeyTap(rowIndex, key.start) },
                    )
                }
            }
        }
    }
}

@Composable
private fun KeyCap(
    key: Key,
    focused: Boolean,
    pulse: () -> Float,
    width: Dp,
    height: Dp,
    textSize: androidx.compose.ui.unit.TextUnit,
    onTap: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .width(width)
            .height(height)
            .graphicsLayer {
                val s = if (focused) 1.08f else 1f
                scaleX = s
                scaleY = s
            }
            .clip(RoundedCornerShape(6.dp))
            .drawBehind {
                drawRect(Color.White.copy(alpha = if (focused) 0.22f + 0.14f * pulse() else 0.08f))
            }
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onTap),
    ) {
        if (focused) {
            GlowText(key.label, textSize, glow = pulse)
        } else {
            Text(key.label, style = shadowed(textSize, color = TextDim), maxLines = 1, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun ResultList(
    search: SearchState,
    m: XmbMetrics,
    pulse: () -> Float,
    onResultTap: (Int) -> Unit,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val rowHeight = m.titleSize * 2.6f
    Column(modifier) {
        Text(
            when {
                search.query.isBlank() -> "Type to search your library"
                search.results.isEmpty() -> "No games match"
                search.results.size == 1 -> "1 game"
                else -> "${search.results.size} games"
            },
            style = shadowed(with(density) { m.subtitleSize.toSp() }, color = TextDim),
        )
        Spacer(Modifier.height(with(density) { (m.s * 0.02f).toDp() }))

        val scroll = rememberScrollState()
        val active = search.focus == SearchFocus.RESULTS
        LaunchedEffect(search.resultIndex, active, search.results.size) {
            scroll.animateScrollTo(if (active) ((search.resultIndex - 2) * rowHeight).toInt().coerceAtLeast(0) else 0)
        }
        Column(Modifier.weight(1f).verticalScroll(scroll)) {
            search.results.forEachIndexed { i, node ->
                val focused = active && i == search.resultIndex
                Column(
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(with(density) { rowHeight.toDp() })
                        .drawBehind {
                            if (focused) {
                                drawRect(
                                    Brush.horizontalGradient(
                                        0f to Color.White.copy(alpha = 0.20f * (0.6f + 0.4f * pulse())),
                                        1f to Color.Transparent,
                                    ),
                                )
                            }
                        }
                        .clickable(remember { MutableInteractionSource() }, indication = null) { onResultTap(i) }
                        .padding(horizontal = with(density) { (m.s * 0.02f).toDp() })
                        .graphicsLayer { alpha = if (active || focused) 1f else 0.8f },
                ) {
                    GlowText(node.title, with(density) { m.titleSize.toSp() }, glow = { if (focused) pulse() else 0f })
                    node.subtitle?.let {
                        Text(
                            it,
                            style = shadowed(with(density) { m.subtitleSize.toSp() }, color = TextDim),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
