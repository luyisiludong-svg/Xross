package dev.xross.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.xross.launcher.data.XrossSettings
import dev.xross.launcher.state.WaveEditor
import dev.xross.launcher.state.WaveEditorState
import dev.xross.launcher.state.WaveRow
import dev.xross.launcher.theme.WaveTint
import dev.xross.launcher.theme.ThemePalette

/**
 * Settings ▸ Theme ▸ Wave settings: a frosted panel on the right with sliders for the shader
 * uniforms (speed, ribbon lines, turbulence, thickness), the color preset, and a reset. The menu
 * fades away while it is open, so the live wave behind it is the preview; every change redraws it
 * on the next frame. D-pad: ↕ row, ↔ value; touch: drag or tap a slider, tap a row.
 */
@Composable
internal fun WaveSettingsPanel(
    editor: WaveEditorState?,
    settings: XrossSettings,
    basePalette: ThemePalette,
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
        val text = m.subtitleSize.sp()
        val shape = RoundedCornerShape(20.dp)
        Box(
            Modifier
                .fillMaxSize()
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Column(
                Modifier
                    .padding(end = (m.width * 0.04f).dp())
                    .width((m.width * 0.42f).coerceAtMost(m.s * 0.95f).dp())
                    .xrossSurface(shape, strength = 0.85f, borderAlpha = 0.6f)
                    .clickable(remember { MutableInteractionSource() }, indication = null) {}
                    .padding(horizontal = (m.s * 0.04f).dp(), vertical = (m.s * 0.035f).dp()),
            ) {
                Text("Wave settings", style = shadowed((m.titleSize * 1.1f).sp(), weight = FontWeight.Bold))
                Text("Changes preview live behind this panel", style = shadowed((m.subtitleSize * 0.85f).sp(), color = TextDim))
                Spacer(Modifier.height((m.s * 0.025f).dp()))
                WaveEditor.rows.forEachIndexed { i, row ->
                    WaveRowView(
                        row = row,
                        index = i,
                        focused = i == state.index,
                        settings = settings,
                        basePalette = basePalette,
                        pulse = pulse,
                        m = m,
                        onTap = { onRowTap(i) },
                        onSlider = { f -> onSlider(i, f) },
                    )
                }
                Spacer(Modifier.height((m.s * 0.02f).dp()))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.align(Alignment.End)) {
                    Hint("↔", "Adjust", text)
                    Hint(confirmGlyph, "Select", text)
                    Hint(backGlyph, "Done", text)
                }
            }
        }
    }
}

@Composable
private fun WaveRowView(
    row: WaveRow,
    index: Int,
    focused: Boolean,
    settings: XrossSettings,
    basePalette: ThemePalette,
    pulse: () -> Float,
    m: XmbMetrics,
    onTap: () -> Unit,
    onSlider: (Float) -> Unit,
) {
    val density = LocalDensity.current
    fun Float.sp() = with(density) { this@sp.toSp() }
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(shape)
            .background(if (focused) Color.White.copy(alpha = 0.12f + 0.06f * pulse()) else Color.Transparent)
            .border(1.dp, if (focused) Color.White.copy(alpha = 0.55f) else Color.Transparent, shape)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onTap)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                row.label,
                style = shadowed(m.subtitleSize.sp(), weight = if (focused) FontWeight.Bold else FontWeight.Normal),
                modifier = Modifier.weight(1f),
            )
            if (row == WaveRow.TINT) TintSwatch(settings, basePalette)
            Text(WaveEditor.value(row, settings), style = shadowed(m.subtitleSize.sp(), color = if (focused) Pastel.Amber else TextDim))
        }
        WaveEditor.fraction(row, settings)?.let { fraction ->
            Spacer(Modifier.height(5.dp))
            SettingSlider(fraction, focused, onSlider)
        }
    }
}

/** A thin track with an amber fill and a round knob; drag or tap to set. */
@Composable
internal fun SettingSlider(fraction: Float, focused: Boolean, onChange: (Float) -> Unit) {
    val change by rememberUpdatedState(onChange)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(18.dp)
            .pointerInput(Unit) {
                detectTapGestures { change((it.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { c, _ -> change((c.position.x / size.width).coerceIn(0f, 1f)) }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val h = 5.dp.toPx()
            val y = size.height / 2
            val r = CornerRadius(h / 2, h / 2)
            drawRoundRect(Color.White.copy(alpha = 0.18f), topLeft = Offset(0f, y - h / 2), size = size.copy(height = h), cornerRadius = r)
            drawRoundRect(
                Brush.horizontalGradient(listOf(Pastel.Lilac, Pastel.Sky, Pastel.Amber)),
                topLeft = Offset(0f, y - h / 2),
                size = size.copy(width = size.width * fraction, height = h),
                cornerRadius = r,
            )
            val knob = if (focused) 8.dp.toPx() else 6.dp.toPx()
            drawCircle(Color.Black.copy(alpha = 0.35f), radius = knob + 1.5f, center = Offset(size.width * fraction, y + 1.5f))
            drawCircle(Color.White, radius = knob, center = Offset(size.width * fraction, y))
        }
    }
}

/** The three colors the wave will draw with, as small dots. */
@Composable
private fun TintSwatch(settings: XrossSettings, base: ThemePalette) {
    val p = dev.xross.launcher.theme.wavePalette(base, WaveTint.byName(settings.waveTint), settings.waveColors)
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.padding(end = 8.dp)) {
        for (c in listOf(p.top, p.bottom, p.wave)) {
            Box(
                Modifier
                    .size(12.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(c))
                    .border(1.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(50)),
            )
        }
    }
}
