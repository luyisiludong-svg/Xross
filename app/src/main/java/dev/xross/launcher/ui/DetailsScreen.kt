package dev.xross.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.xross.launcher.scrape.ArtKind
import dev.xross.launcher.state.GameDetailsState

/**
 * Game Details: background art (or the playing video snap, which shows through), box art on the
 * left, the logo or title, release facts, rating, playtime and a scrollable description.
 */
@Composable
internal fun DetailsScreen(
    details: GameDetailsState?,
    m: XmbMetrics,
    snapPlaying: Boolean,
    confirmGlyph: String,
    backGlyph: String,
    optionsGlyph: String,
    loadArt: suspend (String, List<ArtKind>, Int) -> ImageBitmap?,
    onPlay: () -> Unit,
    onDismiss: () -> Unit,
) {
    var shown by remember { mutableStateOf(details) }
    if (details != null) shown = details
    val density = LocalDensity.current
    fun Float.dp() = with(density) { this@dp.toDp() }
    fun Float.sp() = with(density) { this@sp.toSp() }

    AnimatedVisibility(details != null, enter = fadeIn(tween(220)), exit = fadeOut(tween(220))) {
        val d = shown ?: return@AnimatedVisibility
        val bgPx = maxOf(m.width, m.height).toInt()
        val background = rememberArt(loadArt, d.uuid, BACKGROUND, bgPx, d.artVersion)
        val box = rememberArt(loadArt, d.uuid, BOX, (m.height * 0.7f).toInt(), d.artVersion)
        val logo = rememberArt(loadArt, d.uuid, listOf(ArtKind.WHEEL), (m.width * 0.4f).toInt(), d.artVersion)

        Box(
            Modifier
                .fillMaxSize()
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        ) {
            // Background: art under a scrim; with a snap playing, only the scrim (the video is below).
            val artAlpha by animateFloatAsState(if (background.value != null && !snapPlaying) 1f else 0f, tween(500), label = "bg")
            Box(Modifier.fillMaxSize().background(Color(if (snapPlaying) 0x00000000 else 0xE60A0A12.toInt())))
            background.value?.let {
                Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = artAlpha })
            }
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(0f to Color(0xD9000000), 0.55f to Color(0xB3000000), 1f to Color(0x80000000)),
                ),
            )

            Row(
                Modifier
                    .fillMaxSize()
                    .clickable(remember { MutableInteractionSource() }, indication = null) {}
                    .padding(horizontal = (m.width * 0.05f).dp(), vertical = (m.s * 0.07f).dp()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Box art, crossfading in.
                val boxAlpha by animateFloatAsState(if (box.value != null) 1f else 0f, tween(450), label = "box")
                Box(Modifier.width((m.width * 0.28f).dp()).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    box.value?.let {
                        Image(
                            it,
                            contentDescription = "Box art",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .heightIn(max = (m.height * 0.72f).dp())
                                .graphicsLayer { alpha = boxAlpha }
                                .shadow(16.dp, clip = false),
                        )
                    }
                }
                Spacer(Modifier.width((m.width * 0.04f).dp()))

                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                    val logoBitmap = logo.value
                    if (logoBitmap != null) {
                        Image(
                            logoBitmap,
                            contentDescription = d.title,
                            contentScale = ContentScale.Fit,
                            alignment = Alignment.CenterStart,
                            modifier = Modifier.height((m.s * 0.17f).dp()).width((m.width * 0.4f).dp()),
                        )
                    } else {
                        GlowText(d.title, fontSize = (m.titleSize * 1.5f).sp(), glow = { 0.5f })
                    }
                    Spacer(Modifier.height((m.s * 0.015f).dp()))
                    Text(
                        listOfNotNull(d.platform, d.playtime).joinToString("  ·  "),
                        style = shadowed(m.subtitleSize.sp(), color = TextDim),
                    )
                    d.rating?.let { r ->
                        val stars = (r * 5f).let { kotlin.math.round(it * 2f) / 2f }
                        Text(
                            "★".repeat(stars.toInt()) + (if (stars % 1f != 0f) "½" else "") + "  " +
                                String.format(java.util.Locale.ROOT, "%.1f/5", r * 5f),
                            style = shadowed(m.subtitleSize.sp(), color = Color(0xFFFFD970)),
                        )
                    }
                    Spacer(Modifier.height((m.s * 0.03f).dp()))
                    for ((label, value) in d.facts) {
                        Row {
                            Text(label, style = shadowed(m.subtitleSize.sp(), color = TextDim), modifier = Modifier.width((m.width * 0.12f).dp()))
                            Text(value, style = shadowed(m.subtitleSize.sp()), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Spacer(Modifier.height((m.s * 0.03f).dp()))
                    val synopsis = d.synopsis ?: "No description yet. Settings ▸ Library ▸ Metadata & artwork scrapes one."
                    val scroll = rememberScrollState()
                    val lineStep = with(density) { (m.subtitleSize * 1.4f * 2f).toInt() }
                    LaunchedEffect(d.scroll) { scroll.animateScrollTo(d.scroll * lineStep) }
                    Box(Modifier.weight(1f, fill = false).heightIn(max = (m.height * 0.32f).dp())) {
                        Text(
                            synopsis,
                            style = shadowed((m.subtitleSize * 1.05f).sp(), color = if (d.synopsis == null) TextDim else TextBright),
                            modifier = Modifier.verticalScroll(scroll, enabled = true),
                        )
                    }
                    Spacer(Modifier.height((m.s * 0.03f).dp()))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Box(Modifier.clickable(onClick = onPlay)) { Hint(confirmGlyph, "Play", m.subtitleSize.sp()) }
                        Hint(optionsGlyph, "Options", m.subtitleSize.sp())
                        Hint(backGlyph, "Back", m.subtitleSize.sp())
                        if (d.synopsis != null) Hint("↕", "Scroll", m.subtitleSize.sp())
                    }
                }
            }
        }
    }
}

private val BACKGROUND = listOf(ArtKind.HERO, ArtKind.SCREENSHOT)
private val BOX = listOf(ArtKind.BOX_3D, ArtKind.BOX_2D, ArtKind.GRID)

@Composable
private fun rememberArt(
    loadArt: suspend (String, List<ArtKind>, Int) -> ImageBitmap?,
    uuid: String,
    kinds: List<ArtKind>,
    px: Int,
    version: Long,
) = produceState<ImageBitmap?>(null, uuid, kinds, px, version) { value = loadArt(uuid, kinds, px) }
