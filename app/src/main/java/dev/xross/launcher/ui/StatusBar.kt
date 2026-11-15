package dev.xross.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xross.launcher.media.NowPlaying
import dev.xross.launcher.state.NetworkKind
import dev.xross.launcher.state.NetworkStatus
import dev.xross.launcher.state.StatusBarState
import dev.xross.launcher.state.StatusLevels
import dev.xross.launcher.state.VolumeStatus
import dev.xross.launcher.theme.BatteryLevel
import dev.xross.launcher.theme.pack.BatteryDisplayMode
import dev.xross.launcher.theme.pack.ResolvedComponents
import dev.xross.launcher.theme.pack.StatusSlot
import dev.xross.launcher.theme.pack.ThemeIcon
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.max
import dev.xross.launcher.profile.Friend

/** The ticker's fixed width, in status-text heights (about 20 characters). */
private const val TICKER_WIDTH_EMS = 10f

/** How long `VOLUME` stays after the volume changes, unless `components.volumeAlways`. */
private const val VOLUME_SHOWN_MS = 3_000L

private val GlyphShadow = Color(0x66000000)
private val GlyphDim = Color(0x59FFFFFF)

/**
 * The status bar (2b.7a): the `statusBarLeft` slots at the top-left and the `statusBarRight` slots
 * at the top-right, in array order, each its own composable, and after the left slots the social stack (2b.7c). With an `AVATAR` slot the right
 * cluster keeps room at its far right ([trailingWidth] × [trailingHeight]) for the profile avatar,
 * which [AvatarHud] draws over that space; while the avatar's mini-HUD expands, [contentAlpha]
 * fades every right-cluster slot (read in the draw phase only). The left cluster never fades; it
 * gets the width the right cluster leaves and is clipped at [leftMaxHeight], before the category bar.
 *
 * The bar collects [source] itself, so a minute tick or a battery change recomposes this bar and
 * nothing around it; inside it, only the slots whose values changed recompose.
 */
@Composable
fun StatusBar(
    source: StateFlow<StatusBarState>,
    components: ResolvedComponents,
    textSize: TextUnit,
    modifier: Modifier = Modifier,
    contentAlpha: () -> Float = { 1f },
    trailingWidth: Dp = 0.dp,
    trailingHeight: Dp = 0.dp,
    leftMaxHeight: Dp = Dp.Infinity,
    /** `miniPlayerMode: STATUS_TICKER` (2b.7b): what plays, drawn last before the avatar; null: no ticker. */
    ticker: NowPlaying? = null,
    /** The mini player is covered (the same rule as the floating card's). */
    tickerCovered: Boolean = false,
    onTickerTap: () -> Unit = {},
    onTickerLongPress: () -> Unit = {},
    /** `topLeftCluster: SOCIAL_STACK` (2b.7c): the friends to stack after the left slots; empty: collapsed. */
    social: List<Friend> = emptyList(),
    /** The stack's unread badge ("", "1"–"9", "9+"). */
    socialBadge: String = "",
    socialOpen: Boolean = false,
    /** Profile customization ▸ Glow, for the stack's front avatar. */
    socialGlow: Boolean = true,
    onSocialTap: () -> Unit = {},
) {
    val bar by source.collectAsStateWithLifecycle()

    // Date and time in the pack's display font, the battery figure in its mono font.
    val mono = ThemeTypography.mono ?: ThemeTypography.family
    val style = TextStyle(
        color = Color.White,
        fontSize = textSize,
        fontFamily = ThemeTypography.display ?: ThemeTypography.family,
        shadow = Shadow(Color(0x99000000), Offset(0f, 3f), 6f),
    )
    val density = LocalDensity.current
    val heightDp = with(density) { textSize.toDp() }
    // Slots sit two mono spaces apart: the gap between the clock and "87%" before 2b.7.
    val measurer = rememberTextMeasurer()
    val gap = remember(textSize, mono, density) {
        with(density) { measurer.measure("  ", style.copy(fontFamily = mono, shadow = null)).size.width.toDp() }
    }
    val logo = LocalThemePack.current?.logos?.get(components.logo)
    val slot: @Composable (StatusSlot) -> Unit = { s ->
        when (s) {
            StatusSlot.CLOCK -> ClockSlot(bar.clock, style)
            StatusSlot.BATTERY -> BatterySlot(bar.battery, bar.batteryText(components.battery), components.battery, style, mono, heightDp)
            StatusSlot.WIFI -> bar.network?.let { NetworkSlot(it, heightDp) }
            StatusSlot.VOLUME -> bar.volume?.let { VolumeSlot(it, components.volumeAlways, heightDp) }
            StatusSlot.LOGO -> logo?.let { LogoSlot(it, heightDp * 0.72f) }
            StatusSlot.AVATAR -> Unit
        }
    }

    Layout(
        modifier = modifier,
        content = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(gap),
                modifier = Modifier.clipToBounds(),
            ) {
                for (s in components.left) slot(s)
                // The social stack follows the left slots in the same clipped row (2b.7c).
                if (social.isNotEmpty()) SocialStackSlot(social, socialBadge, heightDp * 1.15f, textSize, socialOpen, socialGlow, onSocialTap)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Read in the draw phase, so the HUD's expansion never recomposes or re-lays-out the bar.
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fadeAlpha(contentAlpha)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gap)) {
                        for (s in components.rightOfText) slot(s)
                    }
                    // The ticker brings its own gap, so the bar is unchanged while nothing plays.
                    if (ticker != null) {
                        MiniPlayerTicker(
                            nowPlaying = ticker,
                            covered = tickerCovered,
                            style = style.copy(fontSize = textSize * 0.85f),
                            height = heightDp,
                            width = heightDp * TICKER_WIDTH_EMS,
                            leading = if (components.rightOfText.isEmpty()) 0.dp else gap,
                            onClick = onTickerTap,
                            onLongClick = onTickerLongPress,
                        )
                    }
                }
                if (components.avatar && trailingWidth > 0.dp) Spacer(Modifier.size(width = trailingWidth, height = trailingHeight))
            }
        },
    ) { measurables, constraints ->
        val right = measurables[1].measure(constraints.copy(minWidth = 0, minHeight = 0))
        val between = gap.roundToPx()
        val leftWidth = (constraints.maxWidth - right.width - between).coerceAtLeast(0)
        val leftHeight = if (leftMaxHeight == Dp.Infinity) constraints.maxHeight else leftMaxHeight.roundToPx().coerceIn(0, constraints.maxHeight)
        val left = measurables[0].measure(Constraints(maxWidth = leftWidth, maxHeight = leftHeight))
        val height = max(left.height, right.height)
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else left.width + between + right.width
        layout(width, height) {
            left.place(0, (height - left.height) / 2)
            right.place(width - right.width, (height - right.height) / 2)
        }
    }
}

@Composable
private fun ClockSlot(text: String, style: TextStyle) {
    if (text.isNotEmpty()) Text(text, style = style, maxLines = 1, softWrap = false)
}

/** `BATTERY` in [mode]: [text] (from [StatusBarState.batteryText]) and/or the gauge; nothing before the first reading. */
@Composable
private fun BatterySlot(
    battery: BatteryLevel?,
    text: String?,
    mode: BatteryDisplayMode,
    style: TextStyle,
    mono: androidx.compose.ui.text.font.FontFamily?,
    heightDp: Dp,
) {
    battery ?: return
    val gauge = mode == BatteryDisplayMode.CAPSULE_ICON || mode == BatteryDisplayMode.BOTH
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (text != null) Text(text, style = style.copy(fontFamily = mono), maxLines = 1, softWrap = false)
        if (gauge) {
            if (text != null) Spacer(Modifier.width(10.dp))
            val bars = StatusBarState(battery = battery)
            val desc = "Battery gauge, ${bars.batteryBars} of 3 bars" + if (battery.charging) ", charging" else ""
            BatteryGauge(bars.batteryBars, bars.batteryLow, Modifier.size(width = heightDp * 1.9f, height = heightDp * 0.95f).semantics { contentDescription = desc })
        }
    }
}

@Composable
private fun BatteryGauge(bars: Int, low: Boolean, modifier: Modifier) {
    Canvas(modifier) {
        val stroke = size.height * 0.11f
        val nub = size.width * 0.07f
        val body = Size(size.width - nub, size.height)
        val shadow = Offset(0f, stroke)
        // Shadow, outline and terminal nub.
        drawRoundRect(GlyphShadow, shadow, body, CornerRadius(stroke * 1.5f), style = Stroke(stroke))
        // The outline follows the accent halo (white unless game art or the battery tint color it);
        // read while drawing, so a morph redraws this canvas only.
        val rim = ThemeAccent.halo.value
        drawRoundRect(rim, Offset.Zero, body, CornerRadius(stroke * 1.5f), style = Stroke(stroke))
        drawRect(rim, Offset(body.width, size.height * 0.3f), Size(nub, size.height * 0.4f))
        // Three bars like the reference ([StatusBarState.batteryBars]).
        val pad = stroke * 2f
        val gap = stroke
        val barW = (body.width - pad * 2 - gap * 2) / 3
        val barColor = if (low) Color(0xFFFF6B6B) else Color.White
        for (i in 0 until bars) {
            drawRect(barColor, Offset(pad + i * (barW + gap), pad), Size(barW, body.height - pad * 2))
        }
    }
}

/** `WIFI`: Wi-Fi in 4 levels, cellular, ethernet, or offline (a struck-through, dim Wi-Fi fan). */
@Composable
private fun NetworkSlot(status: NetworkStatus, heightDp: Dp) {
    val h = heightDp * 0.9f
    val desc = when (status.kind) {
        NetworkKind.WIFI -> "Wi-Fi, ${status.level} of ${StatusLevels.LEVELS - 1} bars"
        NetworkKind.CELLULAR -> "Mobile data"
        NetworkKind.ETHERNET -> "Ethernet"
        NetworkKind.OFFLINE -> "Offline"
    }
    Canvas(Modifier.size(width = h * 1.4f, height = h).semantics { contentDescription = desc }) {
        val stroke = size.height * 0.13f
        translate(0f, stroke * 0.8f) { drawNetwork(status, stroke, GlyphShadow, GlyphShadow) }
        drawNetwork(status, stroke, Color.White, GlyphDim)
    }
}

private fun DrawScope.drawNetwork(status: NetworkStatus, stroke: Float, lit: Color, dim: Color) {
    when (status.kind) {
        NetworkKind.WIFI, NetworkKind.OFFLINE -> {
            val offline = status.kind == NetworkKind.OFFLINE
            val cx = size.width / 2
            val cy = size.height * 0.94f
            val arcs = StatusLevels.LEVELS - 1
            drawCircle(if (offline) dim else lit, stroke * 0.9f, Offset(cx, cy - stroke * 0.6f))
            // The outer arc's stroke ends inside the slot: the row clips at its top.
            val outer = cy - stroke * 0.6f
            for (i in 1..arcs) {
                val r = outer * (0.12f + 0.27f * i) / (0.12f + 0.27f * arcs)
                val on = !offline && i <= status.level
                drawArc(
                    if (on) lit else dim, startAngle = -135f, sweepAngle = 90f, useCenter = false,
                    topLeft = Offset(cx - r, cy - r), size = Size(r * 2, r * 2),
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
            if (offline) {
                drawLine(lit, Offset(size.width * 0.18f, size.height * 0.05f), Offset(size.width * 0.82f, size.height * 0.95f), stroke, StrokeCap.Round)
            }
        }
        NetworkKind.CELLULAR -> {
            // Four rising bars: Android gives no level for the cellular link here.
            val n = 4
            val gap = stroke * 0.7f
            val w = (size.width * 0.8f - gap * (n - 1)) / n
            val x0 = size.width * 0.1f
            for (i in 0 until n) {
                val bh = size.height * (0.35f + 0.65f * (i + 1) / n)
                drawRect(lit, Offset(x0 + i * (w + gap), size.height - bh), Size(w, bh))
            }
        }
        NetworkKind.ETHERNET -> {
            // Three linked boxes: one on top, two below.
            val box = size.height * 0.32f
            val cx = size.width / 2
            drawRect(lit, Offset(cx - box / 2, 0f), Size(box, box))
            val y = size.height * 0.5f
            drawLine(lit, Offset(cx, box), Offset(cx, y), stroke)
            drawLine(lit, Offset(cx - size.width * 0.28f, y), Offset(cx + size.width * 0.28f, y), stroke)
            for (x in listOf(cx - size.width * 0.28f, cx + size.width * 0.28f)) {
                drawLine(lit, Offset(x, y), Offset(x, size.height - box), stroke)
                drawRect(lit, Offset(x - box / 2, size.height - box), Size(box, box))
            }
        }
    }
}

/**
 * `VOLUME`: a speaker with 1–3 waves, or crossed out at 0 (muted). Shown for 3 s after the volume
 * changes, or all the time with [always]; hidden, it takes no room.
 */
@Composable
private fun VolumeSlot(volume: VolumeStatus, always: Boolean, heightDp: Dp) {
    // The value the bar started with doesn't count as a change.
    val first = remember { volume.changes }
    var recent by remember { mutableStateOf(false) }
    LaunchedEffect(volume.changes) {
        if (volume.changes != first && volume.changes > 0) {
            recent = true
            delay(VOLUME_SHOWN_MS)
            recent = false
        }
    }
    AnimatedVisibility(visible = always || recent, enter = fadeIn(tween(120)), exit = fadeOut(tween(250))) {
        val h = heightDp * 0.9f
        val desc = if (volume.muted) "Volume muted" else "Volume ${volume.level} of ${StatusLevels.LEVELS - 1}"
        Canvas(Modifier.size(width = h * 1.45f, height = h).semantics { contentDescription = desc }) {
            val stroke = size.height * 0.12f
            translate(0f, stroke * 0.8f) { drawVolume(volume, stroke, GlyphShadow, GlyphShadow) }
            drawVolume(volume, stroke, Color.White, GlyphDim)
        }
    }
}

private fun DrawScope.drawVolume(volume: VolumeStatus, stroke: Float, lit: Color, dim: Color) {
    val h = size.height
    // Speaker: a small box and its cone.
    val body = Path().apply {
        moveTo(0f, h * 0.35f)
        lineTo(h * 0.25f, h * 0.35f)
        lineTo(h * 0.55f, h * 0.08f)
        lineTo(h * 0.55f, h * 0.92f)
        lineTo(h * 0.25f, h * 0.65f)
        lineTo(0f, h * 0.65f)
        close()
    }
    drawPath(body, lit)
    val cx = h * 0.55f
    val cy = h / 2
    if (volume.muted) {
        val x0 = h * 0.8f
        val x1 = size.width - stroke
        drawLine(lit, Offset(x0, h * 0.3f), Offset(x1, h * 0.7f), stroke, StrokeCap.Round)
        drawLine(lit, Offset(x0, h * 0.7f), Offset(x1, h * 0.3f), stroke, StrokeCap.Round)
        return
    }
    for (i in 1 until StatusLevels.LEVELS) {
        val r = h * (0.12f + 0.2f * i)
        drawArc(
            if (i <= volume.level) lit else dim, startAngle = -50f, sweepAngle = 100f, useCenter = false,
            topLeft = Offset(cx - r, cy - r), size = Size(r * 2, r * 2),
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
    }
}

/** `LOGO`: the pack's `components.logo`, [capHeight] tall (the status text's cap height). */
@Composable
private fun LogoSlot(icon: ThemeIcon, capHeight: Dp) {
    when (icon) {
        is ThemeIcon.Vector -> {
            val v = icon.image
            val aspect = if (v.defaultHeight.value > 0f) v.defaultWidth.value / v.defaultHeight.value else 1f
            Image(rememberVectorPainter(v), "Theme logo", Modifier.size(width = capHeight * aspect, height = capHeight), contentScale = ContentScale.Fit)
        }
        is ThemeIcon.Bitmap -> {
            val b = icon.image
            val aspect = if (b.height > 0) b.width.toFloat() / b.height else 1f
            Image(remember(b) { BitmapPainter(b) }, "Theme logo", Modifier.size(width = capHeight * aspect, height = capHeight), contentScale = ContentScale.Fit)
        }
    }
}
