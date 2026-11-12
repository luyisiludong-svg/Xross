package dev.xross.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import dev.xross.launcher.profile.ProfileState
import dev.xross.launcher.profile.RaProfile
import dev.xross.launcher.state.ProfileCardState
import java.text.NumberFormat
import java.util.Locale

/**
 * The User Card: a console-style sheet over the dimmed XMB with the profile picture, rank,
 * points and recent unlocks of the linked RetroAchievements account, and Refresh / Unlink /
 * Close buttons (Link account… / Close when nothing is linked). Left/right picks a button.
 */
@Composable
internal fun UserCard(
    card: ProfileCardState?,
    profile: ProfileState,
    m: XmbMetrics,
    pulse: () -> Float,
    confirmGlyph: String,
    backGlyph: String,
    onButtonTap: (Int) -> Unit,
    onDismiss: () -> Unit,
    loadBadge: suspend (String, Int) -> androidx.compose.ui.graphics.ImageBitmap? = { _, _ -> null },
    avatarStyle: dev.xross.launcher.theme.AvatarStyleConfig? = null,
) {
    var shown by remember { mutableStateOf(card) }
    if (card != null) shown = card
    val density = LocalDensity.current
    fun Float.dp() = with(density) { this@dp.toDp() }
    fun Float.sp() = with(density) { this@sp.toSp() }

    AnimatedVisibility(visible = card != null, enter = fadeIn(tween(160)), exit = fadeOut(tween(200))) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x99000000))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        )
    }
    AnimatedVisibility(
        visible = card != null,
        enter = fadeIn(tween(180)) + scaleIn(tween(220), initialScale = 0.96f),
        exit = fadeOut(tween(180)) + scaleOut(tween(200), targetScale = 0.96f),
    ) {
        val state = shown ?: return@AnimatedVisibility
        val sheetWidth = (m.width * 0.6f).coerceAtMost(m.s * 1.2f)
        val text = m.subtitleSize.sp()
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val sheet = RoundedCornerShape(22.dp)
            Column(
                Modifier
                    .width(sheetWidth.dp())
                    .heightIn(max = (m.height * 0.88f).dp())
                    .xrossSurface(sheet, borderAlpha = 0.7f)
                    .clickable(remember { MutableInteractionSource() }, indication = null) {}
                    .padding(horizontal = (m.s * 0.05f).dp(), vertical = (m.s * 0.04f).dp()),
            ) {
                Text(
                    "USER CARD  ·  RETROACHIEVEMENTS",
                    style = shadowed(text * 0.8f, color = Pastel.Lilac, weight = FontWeight.Bold).copy(letterSpacing = text * 0.12f),
                )
                Spacer(Modifier.height((m.s * 0.02f).dp()))

                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    Header(profile, m, avatarStyle)
                    val p = profile.profile
                    if (profile.linked && p != null) {
                        Spacer(Modifier.height((m.s * 0.03f).dp()))
                        Stats(p, text)
                        if (p.recentBadges.isNotEmpty()) {
                            Spacer(Modifier.height((m.s * 0.03f).dp()))
                            Text("Recent unlocks", style = shadowed(text, color = TextDim))
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                val size = with(density) { (m.s * 0.085f).toDp() }
                                val px = (m.s * 0.085f).toInt()
                                for (badge in p.recentBadges) {
                                    val name = badge.badgeUrl.substringAfterLast('/').substringBefore('.')
                                    val image by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, name, px) {
                                        value = if (name.isNotEmpty()) loadBadge(name, px) else null
                                    }
                                    BadgeThumb(image, unlocked = true, size = size)
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            for (badge in p.recentBadges.take(3)) {
                                Text(
                                    "${badge.title} — ${badge.gameTitle}  ·  ${badge.points} pts" + if (badge.hardcore) "  ·  hardcore" else "",
                                    style = shadowed(text * 0.92f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height((m.s * 0.02f).dp()))
                    Text(statusLine(profile), style = shadowed(text * 0.9f, color = TextDim))
                }

                Spacer(Modifier.height((m.s * 0.03f).dp()))
                val buttons = state.buttons(profile.linked)
                val focused = state.index.coerceIn(0, buttons.lastIndex)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    buttons.forEachIndexed { i, button ->
                        CardButton(state.label(button), focused = i == focused, pulse = pulse, size = text, onTap = { onButtonTap(i) })
                    }
                }
                Spacer(Modifier.height((m.s * 0.02f).dp()))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.align(Alignment.End)) {
                    Hint(confirmGlyph, "Select", text)
                    Hint(backGlyph, "Close", text)
                }
            }
        }
    }
}

@Composable
private fun Header(profile: ProfileState, m: XmbMetrics, avatarStyle: dev.xross.launcher.theme.AvatarStyleConfig?) {
    val density = LocalDensity.current
    val number = remember { NumberFormat.getIntegerInstance() }
    Row(verticalAlignment = Alignment.CenterVertically) {
        ProfileAvatar(
            avatar = profile.avatar,
            avatarVersion = profile.avatarVersion,
            size = with(density) { (m.s * 0.17f).toDp() },
            ringWidth = 3.dp,
            style = avatarStyle,
        )
        Spacer(Modifier.width(with(density) { (m.s * 0.03f).toDp() }))
        Column {
            val name = profile.profile?.username ?: profile.username.ifEmpty { "Guest" }
            GlowText(name, fontSize = with(density) { (m.titleSize * 1.15f).toSp() }, glow = { 0.6f })
            val p = profile.profile
            val sub = when {
                !profile.linked -> "No RetroAchievements account linked"
                p == null -> "RetroAchievements"
                p.rank != null -> "Rank #${number.format(p.rank)}" + (p.totalRanked?.let { " of ${number.format(it)}" } ?: "")
                else -> "Unranked"
            }
            Text(sub, style = shadowed(with(density) { m.subtitleSize.toSp() }, color = if (p?.rank != null) Pastel.Sky else TextDim, weight = FontWeight.Bold))
            p?.motto?.let {
                Text("“$it”", style = shadowed(with(density) { (m.subtitleSize * 0.9f).toSp() }, color = TextDim), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun Stats(p: RaProfile, size: TextUnit) {
    val number = remember { NumberFormat.getIntegerInstance() }
    Row(horizontalArrangement = Arrangement.spacedBy(36.dp), verticalAlignment = Alignment.Bottom) {
        // Points lead, in the trophy amber, as on the reference cards.
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.Image(
                    glyphPainter(dev.xross.launcher.state.GlyphKey.TROPHY),
                    contentDescription = null,
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Pastel.Amber),
                    modifier = Modifier.height(with(LocalDensity.current) { (size * 1.3f).toDp() }).width(with(LocalDensity.current) { (size * 1.3f).toDp() }),
                )
                Spacer(Modifier.width(6.dp))
                Text(number.format(p.totalPoints), style = shadowed(size * 1.5f, color = Pastel.Amber, weight = FontWeight.Bold))
            }
            Text("POINTS", style = shadowed(size * 0.75f, color = TextDim, weight = FontWeight.Bold).copy(letterSpacing = size * 0.1f))
        }
        Stat("RETROPOINTS", number.format(p.truePoints), size)
        Stat("RETRORATIO", p.retroRatio?.let { String.format(Locale.getDefault(), "%.2f", it) } ?: "—", size)
        if (p.softcorePoints > 0) Stat("SOFTCORE", number.format(p.softcorePoints), size)
    }
}

@Composable
private fun Stat(label: String, value: String, size: TextUnit) {
    Column {
        Text(value, style = shadowed(size * 1.25f, weight = FontWeight.Bold))
        Text(label, style = shadowed(size * 0.75f, color = TextDim, weight = FontWeight.Bold).copy(letterSpacing = size * 0.1f))
    }
}

@Composable
private fun CardButton(label: String, focused: Boolean, pulse: () -> Float, size: TextUnit, onTap: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Text(
        label,
        style = shadowed(size, weight = if (focused) FontWeight.Bold else FontWeight.Normal),
        modifier = Modifier
            .clip(shape)
            .background(if (focused) Color.White.copy(alpha = 0.18f + 0.1f * pulse()) else Color(0x22FFFFFF))
            .border(1.dp, if (focused) Color.White.copy(alpha = 0.8f) else Color(0x33FFFFFF), shape)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onTap)
            .padding(horizontal = 16.dp, vertical = 7.dp),
    )
}

private fun statusLine(profile: ProfileState): String {
    val p = profile.profile
    return when {
        profile.refreshing -> "Refreshing…"
        !profile.linked -> "Link your account to show your avatar, rank and points here."
        p == null -> "Not fetched yet — press Refresh."
        else -> "Updated " + android.text.format.DateUtils.getRelativeTimeSpanString(
            p.fetchedAtMs,
            System.currentTimeMillis(),
            android.text.format.DateUtils.MINUTE_IN_MILLIS,
        ) + (p.memberSince?.let { " · member since ${it.take(10)}" } ?: "")
    }
}
