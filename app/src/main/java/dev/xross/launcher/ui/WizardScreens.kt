package dev.xross.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import dev.xross.launcher.state.WizardRow
import dev.xross.launcher.state.WizardRowKind
import dev.xross.launcher.state.WizardView

/**
 * Settings ▸ Emulators ▸ Add emulator… and Add system… (0.2.2.0 § 1b): a frosted sheet with the
 * step, a hint, and the step's rows. Ticked rows carry a check; rows that open the keyboard or
 * run something end in "…" or "›".
 */
@Composable
internal fun WizardScreen(
    wizard: WizardView?,
    m: XmbMetrics,
    confirmGlyph: String,
    backGlyph: String,
    onRowTap: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var shown by remember { mutableStateOf(wizard) }
    if (wizard != null) shown = wizard
    val density = LocalDensity.current
    fun Float.dp() = with(density) { this@dp.toDp() }
    fun Float.sp() = with(density) { this@sp.toSp() }

    AnimatedVisibility(wizard != null, enter = fadeIn(tween(160)), exit = fadeOut(tween(200))) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xA6000000))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        )
    }
    AnimatedVisibility(
        wizard != null,
        enter = fadeIn(tween(200)) + scaleIn(tween(240), initialScale = 0.97f),
        exit = fadeOut(tween(180)) + scaleOut(tween(200), targetScale = 0.97f),
    ) {
        val w = shown ?: return@AnimatedVisibility
        val text = m.subtitleSize.sp()
        val sheetWidth = (m.width * 0.72f).coerceAtMost(m.s * 1.6f)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .width(sheetWidth.dp())
                    .heightIn(max = (m.height * 0.88f).dp())
                    .xrossSurface(RoundedCornerShape(16.dp), strength = 0.95f, borderAlpha = 0.7f)
                    .clickable(remember { MutableInteractionSource() }, indication = null) {}
                    .padding(horizontal = (m.s * 0.045f).dp(), vertical = (m.s * 0.035f).dp()),
            ) {
                Text("${w.title}  ·  Step ${w.step} of ${w.steps}", style = shadowed(text, color = TextDim))
                Spacer(Modifier.height((m.s * 0.008f).dp()))
                GlowText(w.stepName, fontSize = (m.titleSize * 1.1f).sp(), glow = { 0.6f })
                w.hint?.let {
                    Text(it, style = shadowed(text, color = TextDim), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height((m.s * 0.02f).dp()))

                val list = rememberLazyListState()
                LaunchedEffect(w.index, w.stepName) { list.animateScrollToItem((w.index - 3).coerceAtLeast(0)) }
                LazyColumn(state = list, modifier = Modifier.weight(1f, fill = false)) {
                    itemsIndexed(w.rows, key = { _, row -> row.key }) { i, row ->
                        WizardLine(row, focused = i == w.index, textSize = text, onTap = { onRowTap(i) })
                    }
                }

                Spacer(Modifier.height((m.s * 0.02f).dp()))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.align(Alignment.End)) {
                    Hint(confirmGlyph, "Select", text)
                    Hint(backGlyph, if (w.step == 1) "Cancel" else "Back", text)
                }
            }
        }
    }
}

@Composable
private fun WizardLine(row: WizardRow, focused: Boolean, textSize: TextUnit, onTap: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                if (focused) drawRect(Brush.horizontalGradient(0f to Color.White.copy(alpha = 0.16f), 1f to Color.Transparent))
            }
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onTap)
            .padding(vertical = 6.dp, horizontal = 8.dp),
    ) {
        Text(
            if (row.selected) "✓" else " ",
            style = shadowed(textSize, color = Pastel.Amber),
            modifier = Modifier.width(22.dp),
        )
        val suffix = when {
            row.label.endsWith("…") -> ""
            row.kind == WizardRowKind.TEXT -> " …"
            row.kind == WizardRowKind.ACTION -> " ›"
            else -> ""
        }
        Text(
            row.label + suffix,
            style = shadowed(textSize, color = if (row.kind == WizardRowKind.INFO) TextDim else TextBright),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        row.detail?.let { detail ->
            Text(
                detail,
                style = shadowed(textSize, color = TextDim),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp).widthIn(max = 420.dp),
            )
        }
    }
}
