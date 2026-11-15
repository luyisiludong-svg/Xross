package dev.xross.launcher.theme.pack

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween as composeTween

// Compose animation specs for the engine's Motion (numbers only in :theme:engine).

/** The navigation spring (category moves, drilling in, list scrolling); [snap] for INSTANT. */
fun <T> Motion.nav(): AnimationSpec<T> = if (instant) snap() else spring(dampingRatio = dampingRatio, stiffness = stiffness)

/** A fixed-length fade or slide; [snap] for INSTANT. */
fun <T> Motion.tween(durationMs: Int, delayMs: Int = 0): AnimationSpec<T> =
    if (instant) snap() else composeTween(durationMs, delayMs)
