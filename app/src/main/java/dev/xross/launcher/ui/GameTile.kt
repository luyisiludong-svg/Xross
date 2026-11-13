package dev.xross.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import dev.xross.launcher.scrape.ArtKind
import dev.xross.launcher.state.GameTelemetry
import dev.xross.launcher.state.GlyphKey
import dev.xross.launcher.state.NodeIcon
import java.text.NumberFormat

/** Default width over height of a game tile (the theme layout may set another): the wide plate of the PS3 / PSP XMB game items. */
internal const val GameTileAspect = 16f / 9f

/** Tile height relative to a row's square icon; with the focus scale it still clears the rows around it. */
internal const val GameTileHeightScale = 1.15f

private val TileShape = RoundedCornerShape(6.dp)

/** What a tile shows: the clear logo (fitted, centered) or wide art (cropped to the plate). */
private data class TileArt(val image: ImageBitmap, val logo: Boolean)

private val LOGO = listOf(ArtKind.WHEEL)

/** Wide art first; a portrait capsule or box is cropped to its middle when that is all there is. */
private val WIDE = listOf(ArtKind.HERO, ArtKind.GRID, ArtKind.SCREENSHOT, ArtKind.BOX_2D)

/** Square and portrait tiles (box-art layouts) show the box first. */
private val BOXY = listOf(ArtKind.BOX_2D, ArtKind.GRID, ArtKind.SCREENSHOT, ArtKind.HERO)

/**
 * A game's rectangular XMB tile, [height] tall and [aspect] (16:9 by default) wide, centered on (and wider than) the
 * row's square icon slot. Shows, in order of preference: the clear logo centered on a frosted
 * plate; wide hero / capsule art filling the plate; the title set on the plate when the game has
 * no art. Art crossfades in over the title plate, so a row never pops.
 *
 * The focus scale and glow are applied in the draw phase, around the tile's own bounds, so
 * nothing is clipped and neighbouring rows are never touched.
 */
@Composable
internal fun GameTile(
    icon: NodeIcon.Tile,
    height: Dp,
    focus: () -> Float,
    focusScale: Float,
    glow: () -> Float,
    loadArt: suspend (String, List<ArtKind>, Int) -> ImageBitmap?,
    /** Width over height, from the theme layout's `cardAspectRatio` (Appendix A.5). */
    aspect: Float = GameTileAspect,
) {
    val density = LocalDensity.current
    val width = height * aspect
    val px = with(density) { (maxOf(width, height) * focusScale).roundToPx() }
    val boxy = aspect < 1.2f
    val art by produceState<TileArt?>(null, icon.uuid, icon.version, icon.scraped, boxy) {
        value = when {
            !icon.scraped -> null
            boxy -> loadArt(icon.uuid, BOXY, px)?.let { TileArt(it, logo = false) }
                ?: loadArt(icon.uuid, LOGO, px)?.let { TileArt(it, logo = true) }
            else -> loadArt(icon.uuid, LOGO, px)?.let { TileArt(it, logo = true) }
                ?: loadArt(icon.uuid, WIDE, px)?.let { TileArt(it, logo = false) }
        }
    }
    val shown by animateFloatAsState(if (art != null) 1f else 0f, tween(320), label = "tileArt")
    val frame = LocalFocusFrame.current

    Box(
        Modifier
            .requiredSize(width, height)
            .graphicsLayer {
                val s = 1f + (focusScale - 1f) * focus()
                scaleX = s
                scaleY = s
            }
            // Focus frame (2b.4d): soft white rings breathing with the pulse by default, or the
            // theme's steady neon, hard shadow or gold bezel.
            .drawBehind { drawPlateFrame(frame, glow(), 6.dp.toPx()) },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .shadow(6.dp, TileShape, clip = false)
                .clip(TileShape)
                // Frosted translucent plate.
                .background(
                    Brush.verticalGradient(
                        0f to Color(0xFF3A3A55).copy(alpha = 0.78f),
                        0.5f to Color(0xFF1C1C2A).copy(alpha = 0.84f),
                        1f to Color(0xFF0C0C14).copy(alpha = 0.9f),
                    ),
                )
                .drawWithContent {
                    drawContent()
                    // Top sheen and a hairline border that brightens with the focus.
                    drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.12f), 0.4f to Color.Transparent))
                    val w = 1.dp.toPx()
                    drawRoundRect(
                        Color.White.copy(alpha = 0.35f + 0.55f * focus()),
                        topLeft = Offset(w / 2, w / 2),
                        size = Size(size.width - w, size.height - w),
                        cornerRadius = CornerRadius(6.dp.toPx() - w / 2),
                        style = Stroke(w),
                    )
                },
        ) {
            if (shown < 1f) {
                // No art (yet): the disc / cartridge glyph faint behind the title.
                Image(
                    glyphPainter(icon.fallback),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(Color.White),
                    modifier = Modifier.size(minOf(height, width) * 0.7f).graphicsLayer { alpha = 0.12f * (1f - shown) },
                )
                Text(
                    icon.label,
                    style = shadowed(with(density) { (height * 0.2f).toSp() }, weight = FontWeight.Bold),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = height * 0.1f).fadeAlpha { 1f - shown },
                )
            }
            art?.let { a ->
                Image(
                    a.image,
                    contentDescription = null,
                    contentScale = if (a.logo) ContentScale.Fit else ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(if (a.logo) height * 0.1f else 0.dp)
                        .graphicsLayer { alpha = shown },
                )
            }
        }
    }
}

/**
 * The focused game's RetroAchievements numbers, in the bottom corner above the button hints:
 * trophy and unlock ratio, earned / total points, and a mastery ribbon at 100%. Hidden (and
 * taking no room) for games RetroAchievements doesn't know.
 */
@Composable
internal fun GameTelemetryBadge(telemetry: GameTelemetry?, size: TextUnit, modifier: Modifier = Modifier) {
    var shown by remember { mutableStateOf(telemetry) }
    if (telemetry != null) shown = telemetry
    AnimatedVisibility(
        visible = telemetry != null,
        enter = fadeIn(tween(220)) + expandVertically(tween(220)),
        exit = fadeOut(tween(160)) + shrinkVertically(tween(200)),
        modifier = modifier,
    ) {
        val t = shown ?: return@AnimatedVisibility
        val number = remember { NumberFormat.getIntegerInstance() }
        val shape = RoundedCornerShape(50)
        val density = LocalDensity.current
        // The top padding is the gap to the mini player above; it collapses with the badge.
        Box(Modifier.padding(top = 8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .xrossSurface(shape, strength = 0.9f, borderAlpha = if (t.mastered) 0.9f else 0.45f, borderScale = 2f / 3f)
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            ) {
                val icon = with(density) { (size * 1.15f).toDp() }
                Image(glyphPainter(GlyphKey.TROPHY), null, colorFilter = ColorFilter.tint(Pastel.Amber), modifier = Modifier.size(icon))
                Spacer(Modifier.width(6.dp))
                TrophyCount(t.unlocked, t.total, size)
                Text(" unlocked", style = shadowed(size * 0.85f, color = TextDim))
                Box(Modifier.padding(horizontal = 10.dp).width(1.dp).height(icon).background(Color(0x55FFFFFF)))
                Text(
                    "${number.format(t.earnedPoints)} / ${number.format(t.totalPoints)} pts",
                    style = shadowed(size * 0.92f, weight = FontWeight.Bold),
                )
                if (t.mastered) {
                    Spacer(Modifier.width(10.dp))
                    Pill("★ MASTERED", size * 0.75f, color = Gold, background = Gold.copy(alpha = 0.22f))
                }
            }
        }
    }
}

/** Mastery gold: the laurel frames and the 100% ribbon. */
internal val Gold = Color(0xFFFFD54A)
