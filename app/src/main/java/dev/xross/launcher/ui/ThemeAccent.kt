package dev.xross.launcher.ui

import androidx.compose.animation.Animatable
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Accent colors that morph with the focused game's art, the battery tint and the quick profile
 * (Theme 2.0, 2b.3). Animated values read only while drawing (focus halos, the avatar glow, the
 * status bar's battery outline), so a ~300 ms morph redraws those layers without recomposing any
 * row. Driven by [AdaptiveWave].
 */
object ThemeAccent {
    /** Color of focus halos and glow-text bloom: white unless a color layer tints it. */
    val halo = Animatable(Color.White)

    /** 0–1: how far the avatar's glow leans from its ring color toward [halo] (game art showing). */
    val adaptiveMix = Animatable(0f)

    /** True when [halo] isn't plain white, so tinting layers know to draw the tint. */
    val tinted: Boolean get() = halo.value != Color.White

    /** How long a color morph takes. */
    const val MORPH_MS = 300
}

/**
 * True under a still-motion quick profile (Monochrome E-Ink): every infinite animation (focus
 * pulse, avatar glow, busy dots, caret blink) holds still and the wave draws a single frame.
 */
val LocalStillMotion = compositionLocalOf { false }
