package dev.xross.launcher.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import dev.xross.launcher.R
import dev.xross.launcher.theme.AvatarStyleConfig
import dev.xross.launcher.theme.AvatarStyleLimits
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Round profile picture. Decodes the cached avatar from disk (no network, so it shows at once
 * and offline); without one, or while decoding, draws the silhouette placeholder. When
 * [focused], a white ring and a soft glow that breathes with [pulse] mark it.
 *
 * With a [style] (`profile.avatarStyle` in settings.json) the ring takes that color and width
 * and, when its glow is on, a halo in the same color pulses behind the picture at the style's
 * speed and intensity. The pulse is read only while drawing, so it never recomposes anything;
 * with [animateGlow] false (screen idle or covered) the halo holds still and no animation runs.
 */
@Composable
fun ProfileAvatar(
    avatar: File?,
    avatarVersion: Int,
    size: Dp,
    modifier: Modifier = Modifier,
    focused: Boolean = false,
    pulse: () -> Float = { 1f },
    contentDescription: String? = "Profile",
    onClick: (() -> Unit)? = null,
    /** Width of the iridescent pastel ring around the picture (used when there is no [style]). */
    ringWidth: Dp = 1.5.dp,
    style: AvatarStyleConfig? = null,
    animateGlow: Boolean = true,
) {
    val px = with(LocalDensity.current) { size.roundToPx() }
    val bitmap by produceState<ImageBitmap?>(null, avatar, avatarVersion, px) {
        value = avatar?.let { file -> withContext(Dispatchers.IO) { decodeAvatar(file, px) } }
    }
    val focus by animateFloatAsState(if (focused) 1f else 0f, tween(180), label = "avatarFocus")
    val ring = avatarMargin(size)
    val pictureSize = size // DrawScope has its own `size`

    val styleColor = remember(style?.borderColorHex) { style?.let { Color(AvatarStyleLimits.color(it)) } }
    // Larger pictures (User Card, dashboard) get a proportionally heavier ring.
    val styleWidth = style?.let { it.borderWidthDp.dp * sqrt((size / 32.dp).coerceAtLeast(1f)) }
    val glowOn = style?.glowEnabled == true
    val glowPhase: State<Float>? = if (glowOn && animateGlow) {
        rememberInfiniteTransition(label = "avatarGlow").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween((GLOW_PERIOD_MS / style!!.glowSpeed).roundToInt().coerceAtLeast(200), easing = FastOutSlowInEasing),
                RepeatMode.Reverse,
            ),
            label = "avatarGlowPhase",
        )
    } else {
        null
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size + ring * 2)
            .drawBehind {
                val center = Offset(this.size.width / 2, this.size.height / 2)
                if (glowOn && styleColor != null) {
                    // Halo in the ring color: both its reach (the "blur") and its opacity breathe.
                    val phase = glowPhase?.value ?: GLOW_REST
                    val k = style!!.glowIntensity
                    val picture = pictureSize.toPx() / 2
                    val reach = picture * (1.15f + 0.55f * k * (0.65f + 0.35f * phase))
                    drawCircle(
                        Brush.radialGradient(
                            0f to styleColor.copy(alpha = (0.3f + 0.7f * k) * (0.45f + 0.55f * phase)),
                            (picture / reach) * 0.92f to styleColor.copy(alpha = (0.25f + 0.65f * k) * (0.4f + 0.6f * phase)),
                            1f to Color.Transparent,
                            center = center,
                            radius = reach,
                        ),
                        radius = reach,
                        center = center,
                    )
                }
                if (focus <= 0f) return@drawBehind
                val r = this.size.minDimension / 2
                // Glow: a white halo fading outwards, its strength following the shared pulse.
                drawCircle(
                    Brush.radialGradient(
                        0.55f to Color.White.copy(alpha = 0.45f * focus * (0.5f + 0.5f * pulse())),
                        1f to Color.Transparent,
                        center = center,
                        radius = r,
                    ),
                    radius = r,
                )
                drawCircle(Color.White.copy(alpha = focus), radius = r - ring.toPx() * 0.45f, style = Stroke(ring.toPx() * 0.35f))
            }
            .then(
                if (onClick != null) {
                    Modifier.clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            ),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(Brush.verticalGradient(0f to Color(0xFF5A5A70), 1f to Color(0xFF2E2E3C)))
                .drawWithContent {
                    drawContent()
                    val w = (styleWidth ?: ringWidth).toPx()
                    drawCircle(
                        // The style's color, or the iridescent pastel ring of the reference pictures.
                        styleColor?.let(::SolidColor) ?: Brush.sweepGradient(Pastel.Iridescent),
                        radius = this.size.minDimension / 2 - w / 2,
                        style = Stroke(w),
                    )
                },
        ) {
            val image = bitmap
            if (image != null) {
                Image(image, contentDescription, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Image(
                    painterResource(R.drawable.profile_silhouette),
                    contentDescription,
                    colorFilter = ColorFilter.tint(Color(0xE6FFFFFF)),
                    modifier = Modifier.fillMaxSize().padding(size * 0.06f),
                )
            }
        }
    }
}

/** Space [ProfileAvatar] keeps around a picture of [size] for its focus ring. */
internal fun avatarMargin(size: Dp): Dp = size * 0.14f

/** One glow pulse (dim → bright) at 1× speed. */
private const val GLOW_PERIOD_MS = 1700f

/** Where the halo rests while its animation is paused. */
private const val GLOW_REST = 0.6f

/** Downsampled decode of a cached avatar; null when the file is missing or not an image. */
internal fun decodeAvatar(file: File, sizePx: Int): ImageBitmap? = runCatching {
    if (!file.isFile) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= sizePx.coerceAtLeast(1)) sample *= 2
    BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}.getOrNull()
