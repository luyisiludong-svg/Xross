package dev.wave.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.wave.launcher.state.OptionsMenuState

/**
 * The Options (△ / Y) menu: a panel that slides in from the right over a dimmed XMB, in the
 * style of the console's side menu. Submenus replace the list and show their title above it.
 */
@Composable
internal fun OptionsPanel(
    menu: OptionsMenuState?,
    m: XmbMetrics,
    pulse: () -> Float,
    confirmGlyph: String,
    backGlyph: String,
    onTap: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    // Keep the last menu so the exit animation still has content to draw.
    var shown by remember { mutableStateOf(menu) }
    if (menu != null) shown = menu
    val density = LocalDensity.current
    fun Float.dp() = with(density) { this@dp.toDp() }
    fun Float.sp() = with(density) { this@sp.toSp() }

    AnimatedVisibility(visible = menu != null, enter = fadeIn(tween(160)), exit = fadeOut(tween(200))) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x66000000))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        )
    }
    AnimatedVisibility(
        visible = menu != null,
        enter = slideInHorizontally(tween(220)) { it } + fadeIn(tween(160)),
        exit = slideOutHorizontally(tween(200)) { it } + fadeOut(tween(200)),
    ) {
        val state = shown ?: return@AnimatedVisibility
        val level = state.current
        val landscape = m.width >= m.height
        val panelWidth = if (landscape) (m.width * 0.42f).coerceAtLeast(m.s * 0.75f) else m.width * 0.86f
        val rowHeight = m.titleSize * 2.5f
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
            Column(
                Modifier
                    .width(panelWidth.dp())
                    .fillMaxHeight()
                    .background(
                        Brush.horizontalGradient(
                            0f to Color.Transparent,
                            0.14f to Color(0xE60C0C14),
                            1f to Color(0xF20C0C14),
                        ),
                    )
                    // Swallow taps inside the panel so they don't dismiss it.
                    .clickable(remember { MutableInteractionSource() }, indication = null) {}
                    .padding(start = (panelWidth * 0.2f).dp(), end = (m.s * 0.04f).dp(), top = (m.s * 0.08f).dp(), bottom = (m.s * 0.04f).dp()),
            ) {
                Text(
                    if (state.depth > 1) "${state.title}  ›  ${level.title}" else state.title,
                    style = shadowed(m.subtitleSize.sp(), color = TextDim),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height((m.s * 0.03f).dp()))

                val scroll = rememberScrollState()
                LaunchedEffect(level.index, level.title) {
                    // Keep the focused row roughly a third of the way down once the list overflows.
                    scroll.animateScrollTo(((level.index - 2) * rowHeight).toInt().coerceAtLeast(0))
                }
                Column(Modifier.weight(1f).verticalScroll(scroll)) {
                    level.options.forEachIndexed { i, option ->
                        val focused = i == level.index
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(rowHeight.dp())
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
                                .clickable(remember { MutableInteractionSource() }, indication = null) { onTap(i) }
                                .padding(horizontal = (m.s * 0.025f).dp()),
                        ) {
                            // Marker column: active choice, so labels stay aligned.
                            Text(
                                if (option.selected) "✓" else "",
                                style = shadowed(m.subtitleSize.sp()),
                                modifier = Modifier.width((m.titleSize * 1.2f).dp()),
                            )
                            Column(Modifier.weight(1f)) {
                                GlowText(
                                    text = option.label,
                                    fontSize = m.titleSize.sp(),
                                    glow = { if (focused) pulse() else 0f },
                                )
                                option.detail?.let {
                                    Text(
                                        it,
                                        style = shadowed(m.subtitleSize.sp(), color = TextDim),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            if (option.submenu.isNotEmpty()) Text("›", style = shadowed(m.titleSize.sp()))
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.align(Alignment.End)) {
                    Hint(confirmGlyph, "Select", m.subtitleSize.sp())
                    Hint(backGlyph, "Back", m.subtitleSize.sp())
                }
            }
        }
    }
}
