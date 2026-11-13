package dev.xross.launcher.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import dev.xross.launcher.BuildConfig
import dev.xross.launcher.theme.pack.ParticlePreset
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/** What the particle layer does right now (2b.4e). */
enum class ParticleState {
    /** Not drawn: no preset, E-Ink, a video snap or a full-screen media screen over it. */
    HIDDEN,

    /** Drawn where they are, no frames: the activity is stopped or nobody touched Xross for a while. */
    RESTING,

    /** Moving, one step per display frame. */
    RUNNING,
}

/** The particle layer's resting rules (Roadmap §5, "Foreground particles"). */
object ParticleRest {
    /** No input for this long and the particles settle and stop. Longer than the wave's 3 s rest. */
    const val IDLE_AFTER_MS = 10_000L

    fun of(
        preset: ParticlePreset,
        /** A still-motion quick profile (`MONOCHROME_EINK`). */
        stillMotion: Boolean,
        /** A video snap is playing behind the menu. */
        snapPlaying: Boolean,
        /** A full-screen photo or video, or the wave editor, is over the menu. */
        covered: Boolean,
        /** The activity is at least started. */
        started: Boolean,
        /** No input for [IDLE_AFTER_MS]. */
        idle: Boolean,
    ): ParticleState = when {
        preset == ParticlePreset.NONE || stillMotion || snapPlaying || covered -> ParticleState.HIDDEN
        !started || idle -> ParticleState.RESTING
        else -> ParticleState.RUNNING
    }
}

/**
 * The particles' state, in flat arrays so a frame allocates nothing. Positions are fractions of
 * the layer ([x] of its width, [y] of its height); speeds are in layer heights per second, so a
 * flake falls at the same pace on any screen, and horizontal motion is divided by [aspect].
 *
 * [advance] moves everything by one frame and wraps whatever leaves the layer (past a margin
 * that keeps a big disc from popping at the edge) back in at the opposite edge, at a new random
 * spot along it. Sways and twinkles are read at draw time from the field's own [clock] ([xAt],
 * [alphaAt]), so they freeze with the field when it rests.
 */
class ParticleField(
    val preset: ParticlePreset,
    count: Int = defaultCount(preset),
    seed: Int = 0x5EED,
    /** 0 starts at rest and eases in; 1 (default) starts at full speed. */
    gait: Float = 1f,
) {
    val count: Int = count.coerceIn(0, MAX_PARTICLES)
    private val random = Random(seed)

    /** Base position, before the sway (fraction of the width). */
    val x = FloatArray(this.count)
    val y = FloatArray(this.count)

    /** Vertical speed, layer heights per second: positive falls, negative rises. */
    val speed = FloatArray(this.count)

    /** Radius in dp. */
    val size = FloatArray(this.count)

    /** Peak opacity, 0..1. */
    val alpha = FloatArray(this.count)

    /** Horizontal drift, layer heights per second. */
    val drift = FloatArray(this.count)

    /** Sway amplitude (layer heights) and frequency (Hz), and each particle's phase (radians). */
    val sway = FloatArray(this.count)
    val freq = FloatArray(this.count)
    val phase = FloatArray(this.count)

    /** Width / height of the layer; set from the draw size. */
    var aspect: Float = 16f / 9f
        set(value) { if (value > 0f && value.isFinite()) field = value }

    /** Seconds of motion so far (stands still while resting). */
    var clock: Float = 0f
        private set

    /** 0 at rest, 1 at full speed; eases between the two over [GAIT_SECONDS]. */
    var gait: Float = gait.coerceIn(0f, 1f)
        private set

    /** How far past an edge a particle travels before it wraps (fraction of the height). */
    val margin: Float = when (preset) {
        ParticlePreset.BOKEH -> 0.16f
        else -> 0.02f
    }

    init {
        for (i in 0 until this.count) spawn(i, y = random.nextFloat())
    }

    private fun spawn(i: Int, y: Float) {
        val depth = random.nextFloat()
        x[i] = random.nextFloat()
        this.y[i] = y
        phase[i] = random.nextFloat() * TWO_PI
        when (preset) {
            ParticlePreset.SNOW -> {
                // Nearer flakes are bigger, brighter and faster.
                speed[i] = lerp(0.035f, 0.09f, depth)
                size[i] = lerp(1.2f, 3.2f, depth)
                alpha[i] = lerp(0.35f, 0.85f, depth)
                drift[i] = 0.004f
                sway[i] = lerp(0.004f, 0.012f, random.nextFloat())
                freq[i] = lerp(0.12f, 0.35f, random.nextFloat())
            }
            ParticlePreset.BOKEH -> {
                // Nearer discs are bigger, fainter and rise faster: depth of field.
                speed[i] = -lerp(0.006f, 0.02f, depth)
                size[i] = lerp(14f, 46f, depth)
                alpha[i] = lerp(0.15f, 0.06f, depth)
                drift[i] = lerp(-0.004f, 0.004f, random.nextFloat())
                sway[i] = 0.01f
                freq[i] = lerp(0.04f, 0.1f, random.nextFloat())
            }
            ParticlePreset.DUST_MOTES -> {
                speed[i] = lerp(-0.012f, 0.006f, random.nextFloat())
                size[i] = lerp(0.8f, 2f, depth)
                alpha[i] = lerp(0.3f, 0.75f, depth)
                drift[i] = lerp(-0.008f, 0.008f, random.nextFloat())
                sway[i] = lerp(0.003f, 0.008f, random.nextFloat())
                freq[i] = lerp(0.2f, 0.6f, random.nextFloat())
            }
            ParticlePreset.NONE -> Unit
        }
    }

    /**
     * One frame of [dtSeconds] (clamped to [MAX_STEP_SECONDS], so a frame after a long gap
     * doesn't jump). [running] false eases the field to a stop instead of halting it. Returns
     * true while another frame is needed: running, or still slowing down.
     */
    fun advance(dtSeconds: Float, running: Boolean): Boolean {
        val dt = dtSeconds.coerceIn(0f, MAX_STEP_SECONDS)
        val target = if (running) 1f else 0f
        val step = dt / GAIT_SECONDS
        gait = if (gait < target) minOf(target, gait + step) else maxOf(target, gait - step)
        val move = dt * smoothstep(gait)
        if (move > 0f) {
            clock += move
            val w = 1f / aspect
            val mx = margin * w
            for (i in 0 until count) {
                var yi = y[i] + speed[i] * move
                var xi = x[i] + drift[i] * move * w
                if (yi > 1f + margin) { yi = -margin; xi = random.nextFloat() }
                else if (yi < -margin) { yi = 1f + margin; xi = random.nextFloat() }
                if (xi > 1f + mx) xi -= 1f + 2 * mx
                else if (xi < -mx) xi += 1f + 2 * mx
                y[i] = yi
                x[i] = xi
            }
        }
        return running || gait > 0f
    }

    /** Where particle [i] is drawn across the width, its sway included. */
    fun xAt(i: Int): Float = x[i] + sway[i] * sin(TWO_PI * freq[i] * clock + phase[i]) / aspect

    /** Particle [i]'s opacity now: dust twinkles and is brightest inside a diagonal light shaft. */
    fun alphaAt(i: Int): Float = when (preset) {
        ParticlePreset.DUST_MOTES -> {
            val twinkle = 0.55f + 0.45f * sin(TWO_PI * freq[i] * 1.7f * clock + phase[i] * 2f)
            // The shaft falls from the top left; distance from its axis, in layer heights.
            val d = (xAt(i) - 0.3f) * aspect * 0.5f - y[i] * 0.45f
            val light = 0.25f + 0.75f * exp(-(d * d) / (SHAFT_WIDTH * SHAFT_WIDTH))
            alpha[i] * twinkle * light
        }
        else -> alpha[i]
    }

    companion object {
        /** The hard cap, whatever a preset or caller asks for (Roadmap §5). */
        const val MAX_PARTICLES = 48

        /** The longest step one frame may take (a resumed or dropped frame). */
        const val MAX_STEP_SECONDS = 0.05f

        /** Time to ease from rest to full speed, or back. */
        const val GAIT_SECONDS = 0.6f

        private const val TWO_PI = (2 * PI).toFloat()
        private const val SHAFT_WIDTH = 0.22f

        fun defaultCount(preset: ParticlePreset): Int = when (preset) {
            ParticlePreset.NONE -> 0
            ParticlePreset.SNOW -> 40
            ParticlePreset.BOKEH -> 14
            ParticlePreset.DUST_MOTES -> 28
        }

        private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
        private fun smoothstep(t: Float) = t * t * (3f - 2f * t)
    }
}

/**
 * Layer 4 (2b.4e): ambient particles over the wallpaper, hero art and snap (layer 3) and under the
 * menu's surfaces (layer 5). The field lives in [ParticleField]'s arrays; a `withFrameNanos` loop
 * steps it once per display frame and bumps a single long that only the draw lambda reads, so the
 * layer redraws its own `graphicsLayer` each frame and never recomposes. Resting eases the field
 * to a stop over [ParticleField.GAIT_SECONDS] and then draws no frames; the next input eases it
 * back. Hiding fades the layer out and removes it.
 */
@Composable
fun AmbientParticles(
    preset: ParticlePreset,
    state: ParticleState,
    /** The theme's accent; bokeh is drawn in it, snow and dust lean toward it. */
    tint: Color,
    stillMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    if (preset == ParticlePreset.NONE) return
    val shown = state != ParticleState.HIDDEN
    val fade = remember { Animatable(if (shown) 1f else 0f) }
    LaunchedEffect(shown, stillMotion) {
        val to = if (shown) 1f else 0f
        if (stillMotion) fade.snapTo(to) else fade.animateTo(to, tween(FADE_MS, easing = LinearEasing))
    }
    val present by remember { derivedStateOf { fade.value > 0f } }
    if (!present && !shown) return

    val field = remember(preset) { ParticleField(preset) }
    val frame = remember { mutableLongStateOf(0L) }
    val running = state == ParticleState.RUNNING
    LaunchedEffect(field, running) {
        var last = -1L
        var frames = 0
        while (true) {
            val now = withFrameNanos { it }
            val dt = if (last < 0) 0f else (now - last) / 1e9f
            last = now
            val more = field.advance(dt, running)
            frame.longValue = now
            frames++
            if (!more) break
        }
        if (BuildConfig.PERF_LOG) {
            android.util.Log.i("XrossParticles", "rest preset=$preset n=${field.count} settleFrames=$frames")
        }
    }
    LaunchedEffect(state, preset) {
        if (BuildConfig.PERF_LOG) android.util.Log.i("XrossParticles", "state=$state preset=$preset n=${field.count}")
    }

    val colors = remember(preset, tint) { ParticleColors.of(preset, tint) }
    Spacer(
        modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = fade.value
                // Discs barely overlap: fading each draw is cheaper than an offscreen buffer.
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            .drawBehind {
                frame.longValue // Redraw (only) when the field steps.
                drawField(field, colors)
            },
    )
}

private const val FADE_MS = 400

/** The two colors a preset draws with: the body, and the bokeh rim. */
internal class ParticleColors(val body: Color, val rim: Color) {
    companion object {
        private val SNOW = Color(0xFFF4F8FF)
        private val DUST = Color(0xFFFFF1D6)

        fun of(preset: ParticlePreset, tint: Color): ParticleColors = when (preset) {
            ParticlePreset.SNOW -> ParticleColors(lerp(SNOW, tint, 0.12f), SNOW)
            ParticlePreset.BOKEH -> ParticleColors(tint, lerp(tint, Color.White, 0.35f))
            ParticlePreset.DUST_MOTES -> ParticleColors(lerp(DUST, tint, 0.25f), DUST)
            ParticlePreset.NONE -> ParticleColors(Color.Transparent, Color.Transparent)
        }
    }
}

private fun DrawScope.drawField(field: ParticleField, colors: ParticleColors) {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return
    field.aspect = w / h
    val dp = 1.dp.toPx()
    when (field.preset) {
        ParticlePreset.BOKEH -> {
            val rim = Stroke(width = dp)
            for (i in 0 until field.count) {
                val c = Offset(field.xAt(i) * w, field.y[i] * h)
                val r = field.size[i] * dp
                val a = field.alpha[i]
                // A soft skirt, the flat disc, and the brighter rim real bokeh has.
                drawCircle(colors.body, r * 1.12f, c, alpha = a * 0.35f)
                drawCircle(colors.body, r, c, alpha = a)
                drawCircle(colors.rim, r, c, alpha = a * 1.4f, style = rim)
            }
        }
        else -> for (i in 0 until field.count) {
            drawCircle(colors.body, field.size[i] * dp, Offset(field.xAt(i) * w, field.y[i] * h), alpha = field.alphaAt(i).coerceIn(0f, 1f))
        }
    }
}
