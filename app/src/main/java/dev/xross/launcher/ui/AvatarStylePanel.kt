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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.xross.launcher.profile.ProfileState
import dev.xross.launcher.state.AvatarEditor
import dev.xross.launcher.state.AvatarEditorState
import dev.xross.launcher.state.AvatarRow
import dev.xross.launcher.theme.AvatarColorPreset
import dev.xross.launcher.theme.AvatarStyleConfig
import dev.xross.launcher.theme.AvatarStyleLimits

/**
 * Settings ▸ Theme ▸ Profile customization: a frosted panel on the right with a live preview of
 * the avatar above its controls: ring color (presets or a custom hex), ring width, glow on/off,
 * glow speed and intensity, and a reset. Every change is saved at once, so the preview (and the
 * avatar in the status bar) redraws with it. D-pad: ↕ row, ↔ value; touch: drag or tap a slider,
 * tap a row.
 */
@Composable
internal fun AvatarStylePanel(
    editor: AvatarEditorState?,
    style: AvatarStyleConfig,
    profile: ProfileState,
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
                    .heightIn(max = (m.height * 0.94f).dp())
                    .xrossSurface(shape, strength = 0.85f, borderAlpha = 0.6f)
                    .clickable(remember { MutableInteractionSource() }, indication = null) {}
                    .padding(horizontal = (m.s * 0.04f).dp(), vertical = (m.s * 0.022f).dp()),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Live preview: the same composable the status bar draws, larger.
                    ProfileAvatar(
                        avatar = profile.avatar,
                        avatarVersion = profile.avatarVersion,
                        size = (m.s * 0.11f).dp(),
                        contentDescription = "Avatar preview",
                        style = style,
                        modifier = Modifier.padding((m.s * 0.012f).dp()),
                    )
                    Spacer(Modifier.width((m.s * 0.02f).dp()))
                    Column {
                        Text("Profile customization", style = shadowed((m.titleSize * 1.05f).sp(), weight = FontWeight.Bold))
                        Text("Live preview · saved as you change it", style = shadowed((m.subtitleSize * 0.85f).sp(), color = TextDim))
                    }
                }
                Spacer(Modifier.height((m.s * 0.008f).dp()))
                // On short screens the rows scroll under the fixed preview and hints.
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    AvatarEditor.rows.forEachIndexed { i, row ->
                        AvatarRowView(row, focused = i == state.index, style = style, pulse = pulse, m = m, onTap = { onRowTap(i) }, onSlider = { f -> onSlider(i, f) })
                    }
                }
                Spacer(Modifier.height((m.s * 0.01f).dp()))
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
private fun AvatarRowView(
    row: AvatarRow,
    focused: Boolean,
    style: AvatarStyleConfig,
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
            .padding(vertical = 1.dp)
            .clip(shape)
            .background(if (focused) Color.White.copy(alpha = 0.12f + 0.06f * pulse()) else Color.Transparent)
            .border(1.dp, if (focused) Color.White.copy(alpha = 0.55f) else Color.Transparent, shape)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onTap)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                row.label,
                style = shadowed(m.subtitleSize.sp(), weight = if (focused) FontWeight.Bold else FontWeight.Normal),
                modifier = Modifier.weight(1f),
            )
            if (row == AvatarRow.COLOR) ColorPresets(style)
            Text(AvatarEditor.value(row, style), style = shadowed(m.subtitleSize.sp(), color = if (focused) Pastel.Amber else TextDim))
        }
        AvatarEditor.fraction(row, style)?.let { fraction ->
            Spacer(Modifier.height(4.dp))
            SettingSlider(fraction, focused, onSlider)
        }
    }
}

/** The palette presets as dots, the active one ringed; a custom color adds its own dot. */
@Composable
private fun ColorPresets(style: AvatarStyleConfig) {
    val active = AvatarColorPreset.of(style.borderColorHex)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp)) {
        for (preset in AvatarColorPreset.entries) Dot(Color(AvatarStyleLimits.color(style.copy(borderColorHex = preset.hex))), preset == active)
        if (active == null) Dot(Color(AvatarStyleLimits.color(style)), selected = true)
    }
}

@Composable
private fun Dot(color: Color, selected: Boolean) {
    Box(
        Modifier
            .size(if (selected) 15.dp else 11.dp)
            .clip(CircleShape)
            .background(color)
            .border(if (selected) 2.dp else 1.dp, Color.White.copy(alpha = if (selected) 0.95f else 0.5f), CircleShape),
    )
}
