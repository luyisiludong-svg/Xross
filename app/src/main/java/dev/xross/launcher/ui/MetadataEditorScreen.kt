package dev.xross.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.xross.launcher.model.ArtKind
import dev.xross.launcher.scrape.MediaOffer
import dev.xross.launcher.state.EditorRow
import dev.xross.launcher.state.MediaPickerState
import dev.xross.launcher.state.MediaSlotStatus
import dev.xross.launcher.state.MetadataEditorState
import dev.xross.launcher.state.PickerChoice

/**
 * Options ▸ Metadata & Media: a frosted sheet with the game's title, platform and match status,
 * the text and match rows, then one row per media slot with a preview of the art in it and where
 * it came from. Confirm on a slot opens [MediaPicker] over it.
 */
@Composable
internal fun MetadataEditorScreen(
    editor: MetadataEditorState?,
    m: XmbMetrics,
    pulse: () -> Float,
    confirmGlyph: String,
    backGlyph: String,
    loadArt: suspend (String, List<ArtKind>, Int) -> ImageBitmap?,
    loadThumbnail: suspend (MediaOffer, Int) -> ImageBitmap?,
    onRowTap: (Int) -> Unit,
    onPickerTap: (Int) -> Unit,
    onClosePicker: () -> Unit,
    onDismiss: () -> Unit,
) {
    var shown by remember { mutableStateOf(editor) }
    if (editor != null) shown = editor
    val density = LocalDensity.current
    fun Float.dp() = with(density) { this@dp.toDp() }
    fun Float.sp() = with(density) { this@sp.toSp() }

    AnimatedVisibility(editor != null, enter = fadeIn(tween(160)), exit = fadeOut(tween(200))) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xA6000000))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        )
    }
    AnimatedVisibility(
        editor != null,
        enter = fadeIn(tween(200)) + scaleIn(tween(240), initialScale = 0.97f),
        exit = fadeOut(tween(180)) + scaleOut(tween(200), targetScale = 0.97f),
    ) {
        val e = shown ?: return@AnimatedVisibility
        val text = m.subtitleSize.sp()
        val sheetWidth = (m.width * 0.78f).coerceAtMost(m.s * 1.7f)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .width(sheetWidth.dp())
                    .heightIn(max = (m.height * 0.88f).dp())
                    .xrossSurface(RoundedCornerShape(16.dp), strength = 0.95f, borderAlpha = 0.7f)
                    .clickable(remember { MutableInteractionSource() }, indication = null) {}
                    .padding(horizontal = (m.s * 0.045f).dp(), vertical = (m.s * 0.035f).dp()),
            ) {
                Text("Metadata & Media", style = shadowed(text, color = TextDim))
                Spacer(Modifier.height((m.s * 0.008f).dp()))
                GlowText(e.title, fontSize = (m.titleSize * 1.1f).sp(), glow = { 0.6f })
                Text(
                    listOfNotNull(e.platform, e.matchStatus).joinToString("  ·  "),
                    style = shadowed(text, color = TextDim),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height((m.s * 0.02f).dp()))

                val list = rememberLazyListState()
                LaunchedEffect(e.index, e.gameId) { list.animateScrollToItem((e.index - 2).coerceAtLeast(0)) }
                val previewHeight = (m.subtitleSize * 2.6f)
                LazyColumn(state = list, modifier = Modifier.weight(1f, fill = false)) {
                    itemsIndexed(e.rows, key = { _, row -> row.key }) { i, row ->
                        EditorLine(
                            row = row,
                            focused = i == e.index && e.picker == null,
                            textSize = text,
                            previewHeight = previewHeight.dp(),
                            preview = {
                                if (row is EditorRow.Media) {
                                    SlotPreview(e.uuid, row, (previewHeight * 2.2f).toInt(), e.artVersion, loadArt)
                                }
                            },
                            onTap = { onRowTap(i) },
                        )
                    }
                }

                Spacer(Modifier.height((m.s * 0.02f).dp()))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.align(Alignment.End)) {
                    Hint(confirmGlyph, "Select", text)
                    if (e.count > 1) Hint("L1 R1", "Game ${e.position + 1} of ${e.count}", text)
                    Hint(backGlyph, "Back", text)
                }
            }
        }
    }

    MediaPicker(
        picker = editor?.picker,
        m = m,
        pulse = pulse,
        confirmGlyph = confirmGlyph,
        backGlyph = backGlyph,
        loadThumbnail = loadThumbnail,
        onTap = onPickerTap,
        onDismiss = onClosePicker,
    )
}

@Composable
private fun EditorLine(
    row: EditorRow,
    focused: Boolean,
    textSize: androidx.compose.ui.unit.TextUnit,
    previewHeight: androidx.compose.ui.unit.Dp,
    preview: @Composable () -> Unit,
    onTap: () -> Unit,
) {
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
        if (row is EditorRow.Media) {
            Box(
                Modifier
                    .height(previewHeight)
                    .aspectRatio(1.6f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0x22FFFFFF))
                    .border(1.dp, Color(0x44FFFFFF), RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) { preview() }
            Spacer(Modifier.width(12.dp))
        }
        Text(
            row.label,
            style = shadowed(textSize),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        row.detail?.let { detail ->
            val color = when ((row as? EditorRow.Media)?.status) {
                MediaSlotStatus.CHOSEN -> Pastel.Amber
                MediaSlotStatus.NONE, MediaSlotStatus.REMOVED -> TextDim.copy(alpha = 0.6f)
                else -> TextDim
            }
            Text(
                detail,
                style = shadowed(textSize, color = color),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp).widthIn(max = 320.dp),
            )
        }
    }
}

@Composable
private fun SlotPreview(
    uuid: String,
    row: EditorRow.Media,
    px: Int,
    version: Long,
    loadArt: suspend (String, List<ArtKind>, Int) -> ImageBitmap?,
) {
    if (row.kind.isVideo || !row.hasFile) {
        Text(if (row.kind.isVideo && row.hasFile) "▶" else "—", style = shadowed(androidx.compose.ui.unit.TextUnit.Unspecified, color = TextDim))
        return
    }
    val image by produceState<ImageBitmap?>(null, uuid, row.kind, version) { value = loadArt(uuid, listOf(row.kind), px) }
    image?.let { Image(it, contentDescription = row.label, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
}

/**
 * The media picker: the services' offers as a grid of thumbnails (label and size under each),
 * then "Use a file…" and "Remove". While offers load, or when there are none, a line says so.
 */
@Composable
private fun MediaPicker(
    picker: MediaPickerState?,
    m: XmbMetrics,
    pulse: () -> Float,
    confirmGlyph: String,
    backGlyph: String,
    loadThumbnail: suspend (MediaOffer, Int) -> ImageBitmap?,
    onTap: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var shown by remember { mutableStateOf(picker) }
    if (picker != null) shown = picker
    val density = LocalDensity.current
    fun Float.dp() = with(density) { this@dp.toDp() }
    fun Float.sp() = with(density) { this@sp.toSp() }

    AnimatedVisibility(picker != null, enter = fadeIn(tween(160)), exit = fadeOut(tween(180))) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x80000000))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        )
    }
    AnimatedVisibility(
        picker != null,
        enter = fadeIn(tween(180)) + scaleIn(tween(220), initialScale = 0.97f),
        exit = fadeOut(tween(160)) + scaleOut(tween(180), targetScale = 0.97f),
    ) {
        val p = shown ?: return@AnimatedVisibility
        val text = m.subtitleSize.sp()
        val sheetWidth = (m.width * 0.82f).coerceAtMost(m.s * 1.8f)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .width(sheetWidth.dp())
                    .heightIn(max = (m.height * 0.9f).dp())
                    .xrossSurface(RoundedCornerShape(16.dp), strength = 1f, borderAlpha = 0.8f)
                    .clickable(remember { MutableInteractionSource() }, indication = null) {}
                    .padding(horizontal = (m.s * 0.04f).dp(), vertical = (m.s * 0.03f).dp()),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GlowText(p.title, fontSize = m.titleSize.sp(), glow = { 0.5f })
                    Spacer(Modifier.weight(1f))
                    val status = p.busy ?: when {
                        p.loading -> "Asking the services…"
                        p.offers.isNotEmpty() -> "${p.offers.size} to choose from"
                        else -> null
                    }
                    status?.let { Text(it, style = shadowed(text, color = TextDim)) }
                }
                Spacer(Modifier.height((m.s * 0.02f).dp()))

                if (p.offers.isEmpty()) {
                    Text(
                        if (p.loading) "Loading offers…" else p.reason.orEmpty(),
                        style = shadowed(text, color = TextDim),
                        modifier = Modifier.padding(vertical = (m.s * 0.03f).dp()),
                    )
                } else {
                    val grid = rememberLazyGridState()
                    LaunchedEffect(p.index) {
                        if (p.index < p.offers.size) grid.animateScrollToItem(p.index)
                    }
                    val cellPx = (sheetWidth / MediaPickerState.COLUMNS).toInt()
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(MediaPickerState.COLUMNS),
                        state = grid,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f, fill = false),
                    ) {
                        itemsIndexed(p.offers, key = { _, o -> o.url }) { i, offer ->
                            OfferCell(offer, i == p.index, pulse, text, cellPx, loadThumbnail) { onTap(i) }
                        }
                    }
                }

                Spacer(Modifier.height((m.s * 0.015f).dp()))
                p.extras.forEachIndexed { j, choice ->
                    val i = p.offers.size + j
                    val focused = i == p.index
                    Text(
                        when (choice) {
                            PickerChoice.UseFile -> if (p.kind.isVideo) "Use a video file…" else "Use a file…"
                            PickerChoice.Remove -> "Remove"
                            is PickerChoice.Offer -> ""
                        },
                        style = shadowed(text),
                        modifier = Modifier
                            .fillMaxWidth()
                            .drawBehind {
                                if (focused) drawRect(Brush.horizontalGradient(0f to Color.White.copy(alpha = 0.16f), 1f to Color.Transparent))
                            }
                            .clickable(remember { MutableInteractionSource() }, indication = null) { onTap(i) }
                            .padding(vertical = 6.dp, horizontal = 8.dp),
                    )
                }

                Spacer(Modifier.height((m.s * 0.015f).dp()))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.align(Alignment.End)) {
                    Hint("✥", "Browse", text)
                    Hint(confirmGlyph, "Choose", text)
                    Hint(backGlyph, "Back", text)
                }
            }
        }
    }
}

@Composable
private fun OfferCell(
    offer: MediaOffer,
    focused: Boolean,
    pulse: () -> Float,
    textSize: androidx.compose.ui.unit.TextUnit,
    px: Int,
    loadThumbnail: suspend (MediaOffer, Int) -> ImageBitmap?,
    onTap: () -> Unit,
) {
    val image by produceState<ImageBitmap?>(null, offer.url, px) { value = loadThumbnail(offer, px) }
    val shape = RoundedCornerShape(8.dp)
    Column(Modifier.clickable(remember { MutableInteractionSource() }, indication = null, onClick = onTap)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(if (offer.kind == ArtKind.HERO) 2.1f else if (offer.kind == ArtKind.WHEEL) 2f else 0.8f)
                .clip(shape)
                .background(Color(0x22FFFFFF))
                .drawBehind {
                    if (focused) drawRect(Pastel.Amber.copy(alpha = 0.18f + 0.12f * pulse()))
                }
                .border(if (focused) 2.dp else 1.dp, if (focused) Pastel.Amber else Color(0x44FFFFFF), shape),
            contentAlignment = Alignment.Center,
        ) {
            val bitmap = image
            if (bitmap != null) {
                Image(bitmap, contentDescription = offer.label, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(4.dp))
            } else {
                Text(if (offer.kind.isVideo) "▶" else "…", style = shadowed(textSize, color = TextDim))
            }
        }
        Text(
            "${offer.provider.label} · ${offer.label}",
            style = shadowed(textSize * 0.8f, color = if (focused) TextBright else TextDim),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}
