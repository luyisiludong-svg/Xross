package dev.xross.launcher.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import dev.xross.launcher.scrape.ArtKind
import kotlinx.coroutines.delay

/**
 * Timing of the focused-game background (Games ribbon):
 *  1. after [HERO_DELAY_MS] of continuous focus the game's scraped hero art (or a screenshot)
 *     crossfades in over [HERO_FADE_MS], full-bleed behind the menu;
 *  2. after [VIDEO_DELAY_MS] its video snap, if any, fades in over the art in [VIDEO_FADE_MS]
 *     while its audio ramps up over [AUDIO_RAMP_MS] (background music stays ducked);
 *  3. while either fully covers the screen, the GL wave stops rendering ([covers]).
 * Moving the cursor resets the clock.
 */
object BackdropTiming {
    const val HERO_DELAY_MS = 250L
    const val HERO_FADE_MS = 400
    const val HERO_FADE_OUT_MS = 220
    const val VIDEO_DELAY_MS = 1_200L
    const val VIDEO_FADE_MS = 300
    const val AUDIO_RAMP_MS = 300L

    /** Art kinds tried for the background, best first. */
    val HERO_ART = listOf(ArtKind.HERO, ArtKind.SCREENSHOT)

    /** Volume [elapsedMs] into the ramp towards [target]. */
    fun rampVolume(elapsedMs: Long, target: Float): Float =
        target.coerceIn(0f, 1f) * (elapsedMs.toFloat() / AUDIO_RAMP_MS).coerceIn(0f, 1f)

    /** True when opaque art or video covers every pixel, so the wave underneath needn't draw. */
    fun covers(heroAlpha: Float, videoAlpha: Float): Boolean = heroAlpha >= 0.999f || videoAlpha >= 0.999f
}

/**
 * Full-bleed hero art of the focused game, under the menu and over the wave. [uuid] null hides it
 * (another kind of row is focused, or an overlay is open). Reports through [onCovering] whether it
 * is fully opaque on screen.
 */
@Composable
internal fun FocusBackdrop(
    uuid: String?,
    artVersion: Long,
    maxPx: Int,
    /** The box the art fills, for the frosted surfaces' blur radius. */
    viewport: Size,
    loadArt: suspend (String, List<ArtKind>, Int) -> ImageBitmap?,
    onCovering: (Boolean) -> Unit,
) {
    var hero by remember { mutableStateOf<ImageBitmap?>(null) }
    var shown by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(uuid, artVersion) {
        hero = null
        if (uuid == null) return@LaunchedEffect
        delay(BackdropTiming.HERO_DELAY_MS)
        hero = loadArt(uuid, BackdropTiming.HERO_ART, maxPx)
    }
    // Keep the last image while it fades out.
    hero?.let { shown = it }
    val alpha by animateFloatAsState(
        targetValue = if (hero != null) 1f else 0f,
        animationSpec = tween(if (hero != null) BackdropTiming.HERO_FADE_MS else BackdropTiming.HERO_FADE_OUT_MS),
        label = "hero",
    )
    val report by rememberUpdatedState(onCovering)
    val covering = hero != null && BackdropTiming.covers(alpha, 0f)
    LaunchedEffect(covering) { report(covering) }

    val image = shown ?: return
    if (alpha <= 0f) return
    // Frosted surfaces draw a pre-blurred slice of this art (2b.4c).
    val key = "hero:${FrostedBackdrop.idOf(image)}"
    val opaque = remember(image) { !image.asAndroidBitmap().hasAlpha() }
    FrostLayerEffect(FrostSlot.HERO, key, image, viewport, opaque, BackdropScrim.HERO) { alpha }
    Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }) {
        Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        // Scrim: darker behind the menu column and the status bar, so text stays readable.
        Box(Modifier.fillMaxSize().drawBehind { BackdropScrim.HERO.draw(this, size, 1f) })
    }
}
