package dev.xross.launcher.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.util.Date
import java.util.Locale

private data class BatteryState(val percent: Int, val charging: Boolean)

/**
 * Date, time, battery percentage and a three-bar battery gauge, with room kept at the far right
 * ([trailingWidth] × [trailingHeight]) for the profile avatar, which is drawn over that space by
 * [AvatarHud]. While the avatar's mini-HUD expands over the clock and battery, [contentAlpha]
 * fades them out.
 */
@Composable
fun StatusBar(
    textSize: TextUnit,
    modifier: Modifier = Modifier,
    contentAlpha: () -> Float = { 1f },
    trailingWidth: Dp = 0.dp,
    trailingHeight: Dp = 0.dp,
) {
    val context = LocalContext.current
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            // Wake right after the next minute boundary.
            delay(60_000 - System.currentTimeMillis() % 60_000 + 50)
        }
    }
    val battery by rememberBattery(context)

    // Date and time in the pack's display font, the battery figure in its mono font.
    val mono = ThemeTypography.mono ?: ThemeTypography.family
    val text = remember(now, battery, mono) {
        val locale = Locale.getDefault()
        val date = DateFormat.format(DateFormat.getBestDateTimePattern(locale, "Md"), now)
        val time = DateFormat.getTimeFormat(context).format(now)
        androidx.compose.ui.text.buildAnnotatedString {
            append("$date  $time")
            battery?.let {
                pushStyle(androidx.compose.ui.text.SpanStyle(fontFamily = mono))
                append("  ${it.percent}%")
                pop()
            }
        }
    }
    val style = TextStyle(
        color = Color.White,
        fontSize = textSize,
        fontFamily = ThemeTypography.display ?: ThemeTypography.family,
        shadow = Shadow(Color(0x99000000), Offset(0f, 3f), 6f),
    )
    val heightDp = with(LocalDensity.current) { textSize.toDp() }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        // Read in the draw phase, so the HUD's expansion never recomposes or re-lays-out the bar.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fadeAlpha(contentAlpha)) {
            Text(text, style = style)
            battery?.let {
                Spacer(Modifier.width(10.dp))
                BatteryGauge(it, Modifier.size(width = heightDp * 1.9f, height = heightDp * 0.95f))
            }
        }
        if (trailingWidth > 0.dp) Spacer(Modifier.size(width = trailingWidth, height = trailingHeight))
    }
}

@Composable
private fun BatteryGauge(state: BatteryState, modifier: Modifier) {
    Canvas(modifier) {
        val stroke = size.height * 0.11f
        val nub = size.width * 0.07f
        val body = Size(size.width - nub, size.height)
        val shadow = Offset(0f, stroke)
        // Shadow, outline and terminal nub.
        drawRoundRect(Color(0x66000000), shadow, body, CornerRadius(stroke * 1.5f), style = Stroke(stroke))
        // The outline follows the accent halo (white unless game art or the battery tint color it);
        // read while drawing, so a morph redraws this canvas only.
        val rim = ThemeAccent.halo.value
        drawRoundRect(rim, Offset.Zero, body, CornerRadius(stroke * 1.5f), style = Stroke(stroke))
        drawRect(rim, Offset(body.width, size.height * 0.3f), Size(nub, size.height * 0.4f))
        // Three bars like the reference: 1-33% → 1 bar, 34-66% → 2, 67%+ → 3.
        val bars = when {
            state.percent > 66 -> 3
            state.percent > 33 -> 2
            state.percent > 5 -> 1
            else -> 0
        }
        val pad = stroke * 2f
        val gap = stroke
        val barW = (body.width - pad * 2 - gap * 2) / 3
        val barColor = if (state.percent <= 15 && !state.charging) Color(0xFFFF6B6B) else Color.White
        for (i in 0 until bars) {
            drawRect(barColor, Offset(pad + i * (barW + gap), pad), Size(barW, body.height - pad * 2))
        }
    }
}

@Composable
private fun rememberBattery(context: Context) = remember { mutableStateOf<BatteryState?>(null) }.also { state ->
    DisposableEffect(context) {
        fun read(intent: Intent?) {
            intent ?: return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level < 0 || scale <= 0) return
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            state.value = BatteryState(level * 100 / scale, charging)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) = read(intent)
        }
        // ACTION_BATTERY_CHANGED is sticky, so registering returns the current state immediately.
        read(
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            ),
        )
        onDispose { context.unregisterReceiver(receiver) }
    }
}
