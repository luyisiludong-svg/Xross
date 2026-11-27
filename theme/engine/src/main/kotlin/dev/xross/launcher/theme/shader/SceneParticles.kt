package dev.xross.launcher.theme.shader

import kotlin.math.floor

/**
 * A particle layer's arithmetic (Live backgrounds § 3d), the same as `scene_particles.vert` does it
 * on the GPU, so it is unit tested here. There is no CPU state: each particle is a pure function of
 * its index and the time, so nothing is simulated and nothing is lost across a pause.
 *
 *  - The layer has [SceneLayer.Particles.count] slots (`ceil(rate × lifeMax)`). Slot `i` is reborn
 *    every [period] seconds (`count / rate`, never shorter than the longest life), first at
 *    `i / rate`, so births are spread evenly at `rate` per second.
 *  - Each birth is a new particle: its life, spawn point, velocity and size come from a hash of the
 *    slot, the cycle (which rebirth it is, mod [CYCLES]) and the layer's seed.
 *  - The GPU gets the time as [phase]: wrapped, in double precision, to [CYCLES] periods. The
 *    mapping from time to (cycle mod [CYCLES], age) is periodic in exactly that span, so the wrap
 *    is seamless, and float precision stays fine (the longest span is 100 s × 256, ~2 ms steps).
 *  - At time 0 the field is already full: slots not yet born this cycle are in their last one.
 */
object SceneParticles {
    /** Rebirths before the seeds repeat (and the GPU's time wraps). */
    const val CYCLES = 256

    /** A fade-in over the first part of a life, so nothing pops in: at most this many seconds… */
    const val FADE_IN_MAX = 0.25f

    /** …and at most this fraction of the life. */
    const val FADE_IN_FRACTION = 0.1f

    /** Seconds between one slot's births. */
    fun period(layer: SceneLayer.Particles): Float = layer.count / layer.rate

    /** The time the GPU gets: [time] wrapped to [CYCLES] periods (in double precision, so it never drifts). */
    fun phase(time: Double, layer: SceneLayer.Particles): Float {
        val span = period(layer).toDouble() * CYCLES
        val p = time % span
        return (if (p < 0) p + span else p).toFloat()
    }

    /** One particle as the vertex shader sees it; mutable and reused. */
    class Particle {
        var alive = false
        /** Seconds since birth, and how long it lives. */
        var age = 0f
        var life = 0f
        /** Which rebirth of its slot, 0 until [CYCLES]. */
        var cycle = 0
        /** Where it was born, 0..1 across the emitter's box (y down). */
        var spawnX = 0f
        var spawnY = 0f
        /** Reference pixels per second, and across. */
        var velocityX = 0f
        var velocityY = 0f
        var size = 0f

        /** 0 at birth .. 1 at death: how far its colour has gone from the first to the last. */
        val progress: Float get() = if (life > 0f) (age / life).coerceIn(0f, 1f) else 1f
    }

    /**
     * Slot [index] of [layer] at [phase] (from [phase]); [seed] tells two layers apart (the layer's
     * index in the scene). The shader computes the same, in 32-bit floats and `uint`s.
     */
    fun particle(layer: SceneLayer.Particles, index: Int, phase: Float, seed: Int, out: Particle = Particle()): Particle {
        val period = period(layer)
        val rel = phase - index.toFloat() / layer.rate
        val cycleF = floor(rel / period)
        out.age = rel - cycleF * period
        out.cycle = Math.floorMod(cycleF.toInt(), CYCLES)
        val base = base(index, out.cycle, seed)
        out.life = mix(layer.life.x, layer.life.y, random(base, 0))
        out.spawnX = random(base, 1)
        out.spawnY = random(base, 2)
        out.velocityX = mix(layer.velocityX.x, layer.velocityX.y, random(base, 3))
        out.velocityY = mix(layer.velocityY.x, layer.velocityY.y, random(base, 4))
        out.size = mix(layer.size.x, layer.size.y, random(base, 5))
        out.alive = out.age >= 0f && out.age < out.life
        return out
    }

    /** How much of its colour's alpha it shows at [age] of [life]: the fade-in. */
    fun fadeIn(age: Float, life: Float): Float {
        val span = minOf(FADE_IN_MAX, FADE_IN_FRACTION * life)
        return if (span <= 0f) 1f else (age / span).coerceIn(0f, 1f)
    }

    /** The particle's seed: `hash(index ^ hash(cycle ^ seed × golden))`, as `uint`s. */
    fun base(index: Int, cycle: Int, seed: Int): Int = hash(index xor hash(cycle xor (seed * GOLDEN)))

    /** Its [n]-th random number, 0 ≤ r < 1: the top 24 bits of `hash(base + n)`. */
    fun random(base: Int, n: Int): Float = (hash(base + n) ushr 8) / 16777216f

    /** A 32-bit integer mix (lowbias32), the shader's `hash(uint)`. */
    fun hash(x: Int): Int {
        var h = x
        h = h xor (h ushr 16)
        h *= 0x7feb352d
        h = h xor (h ushr 15)
        h *= 0x846ca68b.toInt()
        h = h xor (h ushr 16)
        return h
    }

    private const val GOLDEN = 0x9E3779B9.toInt()

    private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t
}
