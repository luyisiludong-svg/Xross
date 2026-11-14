package dev.xross.launcher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.xross.launcher.profile.ProfileState
import dev.xross.launcher.model.GlyphKey
import dev.xross.launcher.theme.AvatarStyleConfig
import dev.xross.launcher.theme.pack.Surface
import dev.xross.launcher.theme.pack.SurfaceShape
import androidx.compose.ui.graphics.Shape
import java.text.NumberFormat
import kotlin.math.roundToInt

/** Diameter of the status bar avatar. */
internal val StatusAvatarSize = 32.dp

/** The avatar with the margin [ProfileAvatar] keeps for its ring: what the status bar leaves room for. */
internal val StatusAvatarBox: Dp = StatusAvatarSize + avatarMargin(StatusAvatarSize) * 2

private val HudPadding = 14.dp
private val HudCorner = 22.dp

/**
 * The profile avatar at the far right of the status bar, and the mini-HUD it grows into.
 *
 * Collapsed, this is just the round avatar. As [expansion] goes 0 → 1 the same container morphs
 * in place, anchored to the top-right corner, into a capsule drawn with the active surface style
 * (`Modifier.xrossSurface`) holding the
 * RetroAchievements user name, rank, hardcore and softcore points and the latest badges; the
 * avatar itself never moves. The status bar fades its clock and battery with the same value, so
 * the HUD replaces them and hands the space back when it collapses.
 *
 * [expansion] is read only in the layout and draw phases: the morph re-measures nothing and
 * recomposes nothing, so neighbours can't jitter.
 */
@Composable
internal fun AvatarHud(
    expansion: () -> Float,
    expanded: Boolean,
    profile: ProfileState,
    style: AvatarStyleConfig,
    animateGlow: Boolean,
    m: XmbMetrics,
    confirmGlyph: String,
    backGlyph: String,
    optionsGlyph: String,
    onAvatarTap: () -> Unit,
    loadBadge: suspend (String, Int) -> ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    fun Float.sp() = with(density) { this@sp.toSp() }
    val text = m.subtitleSize.sp()
    val hudWidth = with(density) { (m.s * 0.86f).coerceAtMost(m.width * 0.7f).toDp() }
    val collapsedPx = with(density) { StatusAvatarBox.toPx() }
    val padPx = with(density) { HudPadding.toPx() }
    val cornerPx = with(density) { HudCorner.toPx() }

    Box(
        modifier
            // The active surface style, fading in with the morph; its shape grows from the
            // avatar's circle (rounded surfaces) or scales its corner cuts in with the expansion.
            .xrossSurface(
                shape = { surface -> hudShape(surface, expansion(), collapsedPx, cornerPx) },
                strength = expansion,
                borderAlpha = { expansion() * 0.7f },
                shadowAlpha = expansion,
                clip = { expansion() > 0.01f },
            )
            // Expanded, the capsule swallows taps so only a tap outside it collapses the HUD.
            .clickable(remember { MutableInteractionSource() }, indication = null, enabled = expanded) {}
            .layout { measurable, constraints ->
                val t = expansion()
                val content = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                val c = collapsedPx.roundToInt()
                val w = lerp(c.toFloat(), content.width.toFloat(), t).roundToInt()
                val h = lerp(c.toFloat(), content.height.toFloat(), t).roundToInt()
                // The avatar sits in the content's top-right corner, inset by the padding; slide the
                // content so that corner stays exactly where the collapsed avatar is.
                val inset = (padPx * (1f - t)).roundToInt()
                layout(w, h) { content.place(w - content.width + inset, -inset) }
            },
    ) {
        Column(Modifier.width(hudWidth).padding(HudPadding)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f).padding(end = 10.dp).fadeAlpha(expansion)) {
                    val p = profile.profile
                    val number = remember { NumberFormat.getIntegerInstance() }
                    GlowText(p?.username ?: profile.username.ifEmpty { "Guest" }, fontSize = text * 1.25f, glow = { 0.5f })
                    Text(
                        when {
                            !profile.linked -> "No RetroAchievements account linked"
                            p == null -> "RetroAchievements · not fetched yet"
                            p.rank != null -> "Rank #${number.format(p.rank)}" + (p.totalRanked?.let { " of ${number.format(it)}" } ?: "")
                            else -> "Unranked"
                        },
                        style = shadowed(text * 0.92f, color = if (p?.rank != null) Pastel.Sky else TextDim, weight = FontWeight.Bold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                ProfileAvatar(
                    avatar = profile.avatar,
                    avatarVersion = profile.avatarVersion,
                    size = StatusAvatarSize,
                    contentDescription = profile.username.ifEmpty { "Profile" },
                    onClick = onAvatarTap,
                    style = style,
                    animateGlow = animateGlow,
                )
            }
            Column(Modifier.fadeAlpha(expansion)) {
                val p = profile.profile
                if (profile.linked && p != null) {
                    val number = remember { NumberFormat.getIntegerInstance() }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.Bottom) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val icon = with(density) { (m.subtitleSize * 1.15f).toDp() }
                                Image(glyphPainter(GlyphKey.TROPHY), null, colorFilter = ColorFilter.tint(Pastel.Amber), modifier = Modifier.size(icon))
                                Spacer(Modifier.width(5.dp))
                                Text(number.format(p.totalPoints), style = shadowed(text * 1.2f, color = Pastel.Amber, weight = FontWeight.Bold))
                            }
                            HudLabel("HARDCORE", text)
                        }
                        Column {
                            Text(number.format(p.softcorePoints), style = shadowed(text * 1.2f, weight = FontWeight.Bold))
                            HudLabel("SOFTCORE", text)
                        }
                    }
                    if (p.recentBadges.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            val thumb = (m.s * 0.07f)
                            val px = thumb.toInt()
                            for (badge in p.recentBadges.take(HUD_BADGES)) {
                                val name = badge.badgeUrl.substringAfterLast('/').substringBefore('.')
                                val image by produceState<ImageBitmap?>(null, name, px) {
                                    value = if (name.isNotEmpty()) loadBadge(name, px) else null
                                }
                                BadgeThumb(image, unlocked = true, size = with(density) { thumb.toDp() })
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.align(Alignment.End)) {
                    if (profile.refreshing) Text("Refreshing…", style = shadowed(text * 0.85f, color = TextDim))
                    Hint(confirmGlyph, if (profile.linked) "Refresh" else "Link account", text * 0.85f)
                    Hint(optionsGlyph, "User Card", text * 0.85f)
                    Hint(backGlyph, "Close", text * 0.85f)
                }
            }
        }
    }
}

@Composable
private fun HudLabel(label: String, size: androidx.compose.ui.unit.TextUnit) {
    Text(label, style = shadowed(size * 0.7f, color = TextDim, weight = FontWeight.Bold).copy(letterSpacing = size * 0.1f))
}

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

/**
 * The HUD outline at expansion [t]: the surface's shape for a capsule with [cornerPx] corners,
 * morphing from the [collapsedPx] avatar circle. Rounded corners shrink from the circle's radius;
 * chamfer cuts grow from nothing (the inscribed avatar ring is never touched); a rectangle squares off.
 */
internal fun hudShape(surface: Surface, t: Float, collapsedPx: Float, cornerPx: Float): Shape = when (val sh = surface.shape) {
    SurfaceShape.Component -> RoundedCornerShape(lerp(collapsedPx / 2, cornerPx, t))
    is SurfaceShape.Rounded -> RoundedCornerShape(lerp(collapsedPx / 2, sh.radiusDp * cornerPx / HudCorner.value, t))
    SurfaceShape.Rectangle -> RoundedCornerShape(lerp(collapsedPx / 2, 0f, t))
    is SurfaceShape.Chamfered -> CutCornerShape(
        (sh.cutsDp[0] * t).dp,
        (sh.cutsDp[1] * t).dp,
        (sh.cutsDp[2] * t).dp,
        (sh.cutsDp[3] * t).dp,
    )
}

/** Badge thumbnails in the HUD's recent row. */
private const val HUD_BADGES = 6
