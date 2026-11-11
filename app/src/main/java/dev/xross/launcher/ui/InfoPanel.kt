package dev.xross.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.xross.launcher.state.GameInfoState
import dev.xross.launcher.state.InfoRow

/**
 * The Information screen: a centered sheet over a dimmed XMB listing a game's file, platform,
 * size, play history and fingerprints. Up/down moves the highlight (and scrolls); back closes.
 * Hash values wrap and use a monospace face so all 40 SHA-1 digits stay readable.
 */
@Composable
internal fun InfoPanel(
    info: GameInfoState?,
    m: XmbMetrics,
    backGlyph: String,
    onRowTap: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    // Keep the last content so the exit animation still has something to draw.
    var shown by remember { mutableStateOf(info) }
    if (info != null) shown = info
    val density = LocalDensity.current
    fun Float.dp() = with(density) { this@dp.toDp() }
    fun Float.sp() = with(density) { this@sp.toSp() }

    AnimatedVisibility(visible = info != null, enter = fadeIn(tween(160)), exit = fadeOut(tween(200))) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x99000000))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        )
    }
    AnimatedVisibility(
        visible = info != null,
        enter = fadeIn(tween(180)) + scaleIn(tween(220), initialScale = 0.96f),
        exit = fadeOut(tween(180)) + scaleOut(tween(200), targetScale = 0.96f),
    ) {
        val state = shown ?: return@AnimatedVisibility
        val sheetWidth = (m.width * 0.72f).coerceAtMost(m.s * 1.5f)
        val labelWidth = sheetWidth * 0.28f
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .width(sheetWidth.dp())
                    .heightIn(max = (m.height * 0.86f).dp())
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.verticalGradient(0f to Color(0xF2141420), 1f to Color(0xF20A0A10)),
                    )
                    .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(12.dp))
                    // Swallow taps inside the sheet so they don't dismiss it.
                    .clickable(remember { MutableInteractionSource() }, indication = null) {}
                    .padding(horizontal = (m.s * 0.05f).dp(), vertical = (m.s * 0.04f).dp()),
            ) {
                Text("Information", style = shadowed(m.subtitleSize.sp(), color = TextDim))
                Spacer(Modifier.height((m.s * 0.01f).dp()))
                GlowText(state.title, fontSize = (m.titleSize * 1.1f).sp(), glow = { 0.6f })
                Spacer(Modifier.height((m.s * 0.025f).dp()))

                val list = rememberLazyListState()
                LaunchedEffect(state.index, state.title) {
                    // Keep a couple of rows of context above the highlight.
                    list.animateScrollToItem((state.index - 2).coerceAtLeast(0))
                }
                LazyColumn(state = list, modifier = Modifier.weight(1f, fill = false)) {
                    itemsIndexed(state.rows, key = { _, row -> row.label }) { i, row ->
                        InfoLine(
                            row = row,
                            focused = i == state.index,
                            labelWidth = labelWidth.dp(),
                            textSize = m.subtitleSize.sp(),
                            onTap = { onRowTap(i) },
                        )
                    }
                }

                Spacer(Modifier.height((m.s * 0.02f).dp()))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.align(Alignment.End)) {
                    Hint("↕", "Scroll", m.subtitleSize.sp())
                    Hint(backGlyph, "Close", m.subtitleSize.sp())
                }
            }
        }
    }
}

@Composable
private fun InfoLine(
    row: InfoRow,
    focused: Boolean,
    labelWidth: androidx.compose.ui.unit.Dp,
    textSize: androidx.compose.ui.unit.TextUnit,
    onTap: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                if (focused) {
                    drawRect(Brush.horizontalGradient(0f to Color.White.copy(alpha = 0.14f), 1f to Color.Transparent))
                }
            }
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onTap)
            .padding(vertical = 5.dp, horizontal = 6.dp),
    ) {
        Text(
            row.label,
            style = shadowed(textSize, color = TextDim),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(labelWidth),
        )
        Text(
            row.value,
            style = shadowed(textSize).let { if (row.monospace) it.copy(fontFamily = FontFamily.Monospace) else it },
            modifier = Modifier.weight(1f),
        )
    }
}
