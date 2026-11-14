package dev.xross.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.xross.launcher.profile.Friend
import dev.xross.launcher.profile.FriendsState
import dev.xross.launcher.state.SocialPanelState
import dev.xross.launcher.state.SocialStack

/** The unread badge's color: the amber of the trophy figures. */
private val BadgeColor = Color(0xFFFFB347)

/** Rows of the panel shown at once; the list scrolls past that. */
private const val PANEL_ROWS = 6

/**
 * `topLeftCluster: SOCIAL_STACK` (2b.7c): up to [SocialStack.MAX_AVATARS] overlapping round
 * avatars, most recent activity first, with the count of friends with new activity (`1`–`9`,
 * then `9+`). [avatarSize] follows the status text; a tap opens or closes the panel.
 */
@Composable
internal fun SocialStackSlot(
    friends: List<Friend>,
    badge: String,
    avatarSize: Dp,
    textSize: TextUnit,
    open: Boolean,
    /** Profile customization ▸ Glow: the front avatar's halo while the panel is open. */
    glow: Boolean,
    onTap: () -> Unit,
) {
    val shown = friends.take(SocialStack.MAX_AVATARS)
    if (shown.isEmpty()) return
    val ring = avatarMargin(avatarSize)
    val step = avatarSize * 0.62f
    val desc = "Friends: ${friends.size}" + if (badge.isNotEmpty()) ", $badge with new activity" else ""
    Box(
        Modifier
            .semantics { contentDescription = desc }
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onTap),
    ) {
        // Later avatars sit under earlier ones: the most recent friend is on top, at the left.
        Box(Modifier.width(step * (shown.size - 1) + avatarSize + ring * 2).height(avatarSize + ring * 2)) {
            shown.forEachIndexed { i, f ->
                ProfileAvatar(
                    avatar = f.avatar,
                    avatarVersion = f.avatarVersion,
                    size = avatarSize,
                    focused = open && i == 0,
                    focusGlow = glow,
                    contentDescription = null,
                    modifier = Modifier.offset(x = step * i).zIndex((shown.size - i).toFloat()),
                )
            }
        }
        if (badge.isNotEmpty()) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = avatarSize * 0.18f, y = -avatarSize * 0.08f)
                    .zIndex(10f)
                    .defaultMinSize(minWidth = avatarSize * 0.58f, minHeight = avatarSize * 0.58f)
                    .clip(CircleShape)
                    .background(BadgeColor)
                    .padding(horizontal = 3.dp),
            ) {
                Text(
                    badge,
                    style = shadowed(textSize * 0.62f, color = Color(0xFF1C1408), weight = FontWeight.Bold).copy(shadow = null),
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

/**
 * The social stack's panel: friends, newest activity first, each with their latest game and how
 * long ago. [state] focuses one row; the list scrolls to keep it in view. Drawn in the pack's
 * surface below the stack.
 */
@Composable
internal fun SocialPanel(
    friends: FriendsState,
    state: SocialPanelState,
    textSize: TextUnit,
    width: Dp,
    nowMs: Long,
    backGlyph: String,
    modifier: Modifier = Modifier,
) {
    val list = friends.friends
    val index = state.index.coerceIn(0, (list.size - 1).coerceAtLeast(0))
    val first = (index - PANEL_ROWS / 2).coerceIn(0, (list.size - PANEL_ROWS).coerceAtLeast(0))
    val avatar = 30.dp
    Column(
        modifier
            .width(width)
            .xrossSurface(RoundedCornerShape(18.dp))
            // Swallows taps, so only a tap outside it closes the panel.
            .clickable(remember { MutableInteractionSource() }, indication = null) {}
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("FOLLOWING", style = shadowed(textSize * 0.72f, color = TextDim, weight = FontWeight.Bold).copy(letterSpacing = textSize * 0.1f))
            Spacer(Modifier.weight(1f))
            val note = when (friends) {
                is FriendsState.Offline -> "Offline · saved list"
                is FriendsState.Loading -> "Refreshing…"
                else -> "${list.size}"
            }
            Text(note, style = shadowed(textSize * 0.72f, color = TextDim))
        }
        Spacer(Modifier.height(8.dp))
        for (i in first until (first + PANEL_ROWS).coerceAtMost(list.size)) {
            val f = list[i]
            val focused = i == index
            val a = f.activity
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (focused) Color(0x33FFFFFF) else Color.Transparent)
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            ) {
                ProfileAvatar(f.avatar, f.avatarVersion, avatar, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            f.user,
                            style = shadowed(textSize * 0.92f, weight = FontWeight.Bold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (f.key in state.newKeys) {
                            Spacer(Modifier.width(6.dp))
                            Box(Modifier.size(7.dp).clip(CircleShape).background(BadgeColor))
                        }
                    }
                    val game = when {
                        a == null -> "Not fetched yet"
                        a.gameTitle.isNotEmpty() -> a.gameTitle + if (a.consoleName.isNotEmpty()) " · ${a.consoleName}" else ""
                        else -> "No games played yet"
                    }
                    Text(game, style = shadowed(textSize * 0.78f, color = TextDim), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val ago = a?.let { if (it.online) "online" else SocialStack.ago(it.lastActive, nowMs) }.orEmpty()
                if (ago.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        ago,
                        style = shadowed(textSize * 0.72f, color = if (a?.online == true) Pastel.Mint else TextDim),
                        maxLines = 1,
                        modifier = Modifier.widthIn(max = width * 0.25f),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Hint(backGlyph, "Close", textSize * 0.8f)
        }
    }
}
