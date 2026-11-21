package dev.xross.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import dev.xross.launcher.data.SystemLogoStyle
import dev.xross.launcher.state.BrowserFocus
import dev.xross.launcher.state.BrowserRow
import dev.xross.launcher.state.LibraryBrowser
import dev.xross.launcher.state.LibraryBrowserState
import dev.xross.launcher.state.MediaFilter

/**
 * Settings ▸ Metadata ▸ Fix media… (0.2.2.0 § 3): the console strip, the filter row and the games
 * of the chosen system, each with one badge per art kind: filled when the file is there, hollow
 * when it is missing, underlined when it was chosen by hand. Everything comes from the state, so
 * scrolling a thousand rows reads no files.
 */
@Composable
internal fun LibraryBrowserScreen(
    browser: LibraryBrowserState?,
    m: XmbMetrics,
    logoStyle: SystemLogoStyle,
    confirmGlyph: String,
    backGlyph: String,
    onRowTap: (Int) -> Unit,
    onSystemTap: (Int) -> Unit,
    onFilterTap: (Int) -> Unit,
    onScrolled: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var shown by remember { mutableStateOf(browser) }
    if (browser != null) shown = browser
    val density = LocalDensity.current
    fun Float.dp() = with(density) { this@dp.toDp() }
    fun Float.sp() = with(density) { this@sp.toSp() }

    AnimatedVisibility(browser != null, enter = fadeIn(tween(180)), exit = fadeOut(tween(200))) {
        val b = shown ?: return@AnimatedVisibility
        val text = m.subtitleSize.sp()
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xCC000000))
                .clickable(remember { MutableInteractionSource() }, indication = null) {},
        ) {
            Column(Modifier.fillMaxSize().padding(horizontal = (m.s * 0.06f).dp(), vertical = (m.s * 0.04f).dp())) {
                Row(verticalAlignment = Alignment.Bottom) {
                    GlowText("Fix media", fontSize = (m.titleSize * 1.1f).sp(), glow = { 0.6f })
                    Spacer(Modifier.width(16.dp))
                    Text(
                        listOfNotNull(b.system?.name, LibraryBrowser.summary(b)).joinToString("  ·  "),
                        style = shadowed(text, color = TextDim),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height((m.s * 0.015f).dp()))

                // Console strip.
                val strip = rememberLazyListState()
                LaunchedEffect(b.systemIndex) { strip.animateScrollToItem((b.systemIndex - 2).coerceAtLeast(0)) }
                LazyRow(state = strip, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    itemsIndexed(b.systems, key = { _, s -> s.id ?: "all" }) { i, sys ->
                        val selected = i == b.systemIndex
                        Box(
                            Modifier
                                .height((m.s * 0.075f).dp())
                                .width((m.s * 0.16f).dp())
                                .background(if (selected) Color(0x33FFFFFF) else Color(0x14FFFFFF), RoundedCornerShape(10.dp))
                                .border(if (selected) 2.dp else 1.dp, Color(if (selected) 0xCCFFFFFF else 0x33FFFFFF), RoundedCornerShape(10.dp))
                                .clickable(remember { MutableInteractionSource() }, indication = null) { onSystemTap(i) }
                                .padding(6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            val logo = sys.id?.let { systemLogo(it, logoStyle) }
                            if (logo != null) {
                                Image(logo.painter, contentDescription = sys.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                            } else {
                                Text(sys.shortName, style = shadowed(text), maxLines = 1)
                            }
                        }
                    }
                }
                Spacer(Modifier.height((m.s * 0.012f).dp()))

                // Filter row.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    for ((i, f) in MediaFilter.BROWSER.withIndex()) {
                        val on = f == b.filter
                        val focused = on && b.focus == BrowserFocus.FILTERS
                        Text(
                            f.label,
                            style = shadowed(text, color = if (on) Color.White else TextDim),
                            modifier = Modifier
                                .background(if (on) Color(0x33FFFFFF) else Color.Transparent, RoundedCornerShape(50))
                                .border(if (focused) 2.dp else 1.dp, Color(if (focused) 0xFFFFFFFF else 0x33FFFFFF), RoundedCornerShape(50))
                                .clickable(remember { MutableInteractionSource() }, indication = null) { onFilterTap(i) }
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }
                }
                Spacer(Modifier.height((m.s * 0.012f).dp()))

                // Games.
                val list = rememberLazyListState(initialFirstVisibleItemIndex = b.firstVisible)
                LaunchedEffect(b.index, b.systemIndex, b.filter) {
                    val visible = list.layoutInfo.visibleItemsInfo
                    val first = visible.firstOrNull()?.index ?: 0
                    val last = visible.lastOrNull()?.index ?: 0
                    if (b.index < first + 1 || b.index > last - 1) list.scrollToItem((b.index - 3).coerceAtLeast(0))
                }
                LaunchedEffect(list) { snapshotFlow { list.firstVisibleItemIndex }.collect(onScrolled) }
                if (b.rows.isEmpty()) {
                    Text(
                        if (b.filter == MediaFilter.ALL) "No games here" else "No game here is ${b.filter.label.lowercase()}",
                        style = shadowed(text, color = TextDim),
                        modifier = Modifier.padding(16.dp),
                    )
                }
                LazyColumn(state = list, modifier = Modifier.weight(1f)) {
                    itemsIndexed(b.rows, key = { _, r -> r.gameId }) { i, row ->
                        BrowserLine(row, focused = i == b.index && b.focus == BrowserFocus.LIST, showPlatform = b.system?.id == null, textSize = text) { onRowTap(i) }
                    }
                }

                Spacer(Modifier.height((m.s * 0.012f).dp()))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.align(Alignment.End)) {
                    Hint(confirmGlyph, "Fix", text)
                    Hint("L1 R1", "System", text)
                    Hint(backGlyph, "Back", text)
                }
            }
        }
    }
}

@Composable
private fun BrowserLine(row: BrowserRow, focused: Boolean, showPlatform: Boolean, textSize: TextUnit, onTap: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                if (focused) drawRect(Brush.horizontalGradient(0f to Color.White.copy(alpha = 0.16f), 1f to Color.Transparent))
            }
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onTap)
            .padding(vertical = 5.dp, horizontal = 8.dp),
    ) {
        Text(
            row.title,
            style = shadowed(textSize),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (showPlatform) Text(row.platformShort, style = shadowed(textSize, color = TextDim), modifier = Modifier.padding(horizontal = 10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            for ((kind, label) in LibraryBrowserState.BADGES) {
                val has = row.has(kind)
                val pinned = kind in row.pinned
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        label,
                        style = shadowed(textSize * 0.62f, color = if (has) Color(0xFF101418) else TextDim),
                        maxLines = 1,
                        modifier = Modifier
                            .background(if (has) Color(0xE6FFFFFF) else Color.Transparent, RoundedCornerShape(4.dp))
                            .border(1.dp, Color(if (has) 0xE6FFFFFF else 0x66FFFFFF), RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                    Box(Modifier.padding(top = 2.dp).size(width = 16.dp, height = 2.dp).background(if (pinned) Pastel.Amber else Color.Transparent))
                }
            }
        }
    }
}
