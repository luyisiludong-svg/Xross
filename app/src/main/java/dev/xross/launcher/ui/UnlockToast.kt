package dev.xross.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.xross.launcher.state.GlyphKey
import dev.xross.launcher.state.UnlockToastState

/**
 * Console-style unlock notification: a small frosted card that slides in at the top right with
 * the badge, a trophy, "Achievement unlocked", the title, game and points. It is drawn only (no
 * click or focus handling), so gamepad navigation underneath never pauses; the view model times it
 * and queues the next one.
 */
@Composable
internal fun UnlockToast(
    toast: UnlockToastState?,
    m: XmbMetrics,
    loadBadge: suspend (String, Int) -> ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    var shown by remember { mutableStateOf(toast) }
    if (toast != null) shown = toast
    val density = LocalDensity.current
    fun Float.dp() = with(density) { this@dp.toDp() }
    fun Float.sp() = with(density) { this@sp.toSp() }

    AnimatedVisibility(
        visible = toast != null,
        enter = slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) { it / 2 } + fadeIn(tween(260)),
        exit = slideOutHorizontally(tween(280)) { it / 3 } + fadeOut(tween(240)),
        modifier = modifier,
    ) {
        val t = shown ?: return@AnimatedVisibility
        val badge = t.badge
        val badgeName = badge.badgeUrl.substringAfterLast('/').substringBefore('.')
        val px = (m.s * 0.11f).toInt()
        val image by produceState<ImageBitmap?>(null, badgeName, px) {
            value = if (badgeName.isNotEmpty()) loadBadge(badgeName, px) else null
        }
        val shape = RoundedCornerShape(16.dp)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .widthIn(max = (m.width * 0.42f).dp())
                .semantics { liveRegion = LiveRegionMode.Polite }
                .xrossSurface(shape, borderAlpha = 0.8f)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            BadgeThumb(image, unlocked = true, size = (m.s * 0.11f).dp())
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f, fill = false)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        glyphPainter(GlyphKey.TROPHY),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(Pastel.Amber),
                        modifier = Modifier.size((m.subtitleSize * 1.05f).dp()),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (badge.hardcore) "Achievement unlocked · Hardcore" else "Achievement unlocked",
                        style = shadowed((m.subtitleSize * 0.85f).sp(), color = Pastel.Amber, weight = FontWeight.Bold),
                        maxLines = 1,
                    )
                }
                Text(badge.title, style = shadowed(m.subtitleSize.sp(), weight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                val footer = buildString {
                    if (badge.gameTitle.isNotBlank()) append(badge.gameTitle).append("  ·  ")
                    append("${badge.points} pts")
                    if (t.more > 0) append("  ·  +${t.more} more")
                }
                Text(footer, style = shadowed((m.subtitleSize * 0.85f).sp(), color = TextDim), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
