package dev.xross.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.xross.launcher.state.LayoutEditor
import dev.xross.launcher.state.LayoutEditorState
import dev.xross.launcher.state.LayoutRow
import dev.xross.launcher.theme.pack.LayoutOverrides
import dev.xross.launcher.theme.pack.ThemeLayout

/**
 * Settings ▸ Theme ▸ Layout (2b.8a, the Live Theme Mixer): a compact frosted panel in the
 * bottom-right corner with the crosshair, UI scale, list spacing and focused scale. Unlike Wave
 * settings the menu stays in full view behind it: it is the preview, and moves with every step.
 *
 * [m] is measured at 100% UI scale ([XmbMetrics] over [ThemeLayout.DEFAULT]) and the panel is
 * anchored to the corner, so changing the scale or the crosshair never moves the row under the
 * cursor. [pack] is the pack's layout, for the "Theme pack · …" values.
 */
@Composable
internal fun LayoutSettingsPanel(
    editor: LayoutEditorState?,
    user: LayoutOverrides,
    pack: ThemeLayout,
    m: XmbMetrics,
    pulse: () -> Float,
    confirmGlyph: String,
    backGlyph: String,
    onRowTap: (Int) -> Unit,
    onSlider: (Int, Float) -> Unit,
    onDismiss: () -> Unit,
) {
    var shown by remember { mutableStateOf(editor) }
    if (editor != null) shown = editor
    val density = LocalDensity.current
    fun Float.dp() = with(density) { this@dp.toDp() }
    fun Float.sp() = with(density) { this@sp.toSp() }

    AnimatedVisibility(
        editor != null,
        enter = slideInHorizontally(tween(260)) { it / 3 } + fadeIn(tween(200)),
        exit = slideOutHorizontally(tween(220)) { it / 3 } + fadeOut(tween(180)),
    ) {
        val state = shown ?: return@AnimatedVisibility
        val text = (m.subtitleSize * 0.92f).sp()
        val shape = RoundedCornerShape(18.dp)
        // A tap outside closes it. A plain gesture, not a clickable node: a clickable layer over the
        // whole screen would hide the menu behind it from the accessibility tree, and the menu is
        // what this panel is about.
        val dismiss by rememberUpdatedState(onDismiss)
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { dismiss() } },
            contentAlignment = Alignment.BottomEnd,
        ) {
            Column(
                Modifier
                    .padding(end = (m.width * 0.025f).dp(), bottom = (m.s * 0.04f).dp())
                    .width((m.width * 0.36f).coerceIn(m.s * 0.66f, m.s * 0.95f).coerceAtMost(m.width * 0.92f).dp())
                    .xrossSurface(shape, strength = 0.85f, borderAlpha = 0.6f)
                    // Taps on the panel itself stay here.
                    .pointerInput(Unit) { detectTapGestures { } }
                    .padding(horizontal = (m.s * 0.03f).dp(), vertical = (m.s * 0.025f).dp()),
            ) {
                Text("Layout", style = shadowed(m.titleSize.sp(), weight = FontWeight.Bold))
                Text("The menu behind moves as you adjust", style = shadowed((m.subtitleSize * 0.8f).sp(), color = TextDim))
                Spacer(Modifier.height((m.s * 0.015f).dp()))
                LayoutEditor.rows.forEachIndexed { i, row ->
                    LayoutRowView(
                        row = row,
                        focused = i == state.index,
                        user = user,
                        pack = pack,
                        pulse = pulse,
                        text = text,
                        onTap = { onRowTap(i) },
                        onSlider = { f -> onSlider(i, f) },
                    )
                }
                Spacer(Modifier.height((m.s * 0.015f).dp()))
                val confirmLabel = if (state.row == LayoutRow.RESET) "Reset" else "Theme pack"
                val hint = (m.subtitleSize * 0.78f).sp()
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.align(Alignment.End)) {
                    Hint("↔", "Adjust", hint)
                    Hint(confirmGlyph, confirmLabel, hint)
                    Hint(backGlyph, "Done", hint)
                }
            }
        }
    }
}

@Composable
private fun LayoutRowView(
    row: LayoutRow,
    focused: Boolean,
    user: LayoutOverrides,
    pack: ThemeLayout,
    pulse: () -> Float,
    text: androidx.compose.ui.unit.TextUnit,
    onTap: () -> Unit,
    onSlider: (Float) -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    val own = LayoutEditor.isSet(row, user)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .clip(shape)
            .background(if (focused) Color.White.copy(alpha = 0.12f + 0.06f * pulse()) else Color.Transparent)
            .border(1.dp, if (focused) Color.White.copy(alpha = 0.55f) else Color.Transparent, shape)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onTap)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                row.label,
                style = shadowed(text, weight = if (focused) FontWeight.Bold else FontWeight.Normal),
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            // The user's own values in full white; the pack's dimmed.
            Text(
                LayoutEditor.value(row, user, pack),
                style = shadowed(text, color = if (focused) Pastel.Amber else if (own) TextBright else TextDim),
                maxLines = 1,
            )
        }
        LayoutEditor.fraction(row, user, pack)?.let { fraction ->
            Spacer(Modifier.height(4.dp))
            SettingSlider(fraction, focused, onSlider)
        }
    }
}
