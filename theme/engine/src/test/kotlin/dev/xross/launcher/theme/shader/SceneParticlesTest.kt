package dev.xross.launcher.theme.shader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Live backgrounds § 3d: a particle layer's birth and life arithmetic, the same as
 * `scene_particles.vert` computes on the GPU. No CPU state: a particle is a function of its slot
 * and the time.
 */
class SceneParticlesTest {
    /** The plan's embers: 30 a second, living 3–6 s. */
    private val embers = SceneLayer.Particles(
        path = "ember.png", rate = 30f, life = Vec2(3f, 6f),
        velocityX = Vec2(-5f, 5f), velocityY = Vec2(-40f, -20f), size = Vec2(4f, 10f),
        colors = 0xFFFFB354.toInt() to 0x00FF8024,
    )

    private fun alive(layer: SceneLayer.Particles, phase: Float, seed: Int = 0): Int {
        val p = SceneParticles.Particle()
        return (0 until layer.count).count { SceneParticles.particle(layer, it, phase, seed, p).alive }
    }

    @Test fun slotsCoverTheLongestLifeAndBirthsAreSpreadAtTheRate() {
        assertEquals(180, embers.count)
        val period = SceneParticles.period(embers)
        assertEquals(6f, period, 1e-5f)
        // Slot i is born at i / rate, then every period.
        for (i in listOf(0, 1, 59, 179)) {
            val born = i / embers.rate
            val p = SceneParticles.particle(embers, i, born + 0.01f, 0)
            assertEquals("slot $i", 0.01f, p.age, 1e-3f)
            assertTrue(p.alive)
            assertEquals(0, p.cycle)
            assertEquals(1, SceneParticles.particle(embers, i, born + period + 0.01f, 0).cycle)
        }
        // An odd rate: the period still holds the longest life.
        val odd = embers.copy(rate = 7f, life = Vec2(1f, 2.3f))
        assertEquals(ceil(7 * 2.3).toInt(), odd.count)
        assertTrue(SceneParticles.period(odd) >= 2.3f)
    }

    @Test fun aboutRateTimesTheMeanLifeAreAliveFromTheFirstFrame() {
        val expected = embers.rate * (embers.life.x + embers.life.y) / 2f // 135
        assertEquals("already full at time 0, nothing to warm up", expected, alive(embers, 0f).toFloat(), expected * 0.2f)
        val samples = (0 until 200).map { alive(embers, SceneParticles.phase(it * 3.7, embers)) }
        assertEquals(expected, samples.average().toFloat(), expected * 0.05f)
        assertTrue("never more than the slots", samples.all { it <= embers.count })
    }

    @Test fun eachRebirthIsANewParticleAndLayersDiffer() {
        val period = SceneParticles.period(embers)
        val a = SceneParticles.particle(embers, 5, 1f, 0)
        val again = SceneParticles.particle(embers, 5, 1f, 0)
        assertEquals("pure: the same time, the same particle", a.spawnX, again.spawnX)
        val next = SceneParticles.particle(embers, 5, 1f + period, 0)
        assertEquals(a.age, next.age, 1e-4f)
        assertNotEquals(a.spawnX, next.spawnX)
        assertNotEquals(a.spawnX, SceneParticles.particle(embers, 5, 1f, 1).spawnX)
        assertNotEquals(a.spawnX, SceneParticles.particle(embers, 6, 1f, 0).spawnX)
    }

    @Test fun valuesStayInTheirRanges() {
        val p = SceneParticles.Particle()
        var sumX = 0.0
        val n = embers.count * 40
        for (k in 0 until 40) for (i in 0 until embers.count) {
            SceneParticles.particle(embers, i, k * 1.3f, 2, p)
            assertTrue(p.life in 3f..6f)
            assertTrue(p.spawnX >= 0f && p.spawnX < 1f && p.spawnY >= 0f && p.spawnY < 1f)
            assertTrue(p.velocityX in -5f..5f && p.velocityY in -40f..-20f)
            assertTrue(p.size in 4f..10f)
            assertTrue(p.cycle in 0 until SceneParticles.CYCLES)
            sumX += p.spawnX
        }
        assertEquals("spread evenly", 0.5, sumX / n, 0.02)
    }

    @Test fun theWrapIsSeamlessAndTimeAfterDaysIsPrecise() {
        val span = SceneParticles.period(embers).toDouble() * SceneParticles.CYCLES
        val dt = 1.0 / 60
        for (time in listOf(span, 3 * span, 3 * 86_400.0)) {
            val before = SceneParticles.phase(time - dt, embers)
            val after = SceneParticles.phase(time + dt, embers)
            assertTrue(before in 0f..span.toFloat() && after in 0f..span.toFloat())
            val p = SceneParticles.Particle()
            val q = SceneParticles.Particle()
            var compared = 0
            for (i in 0 until embers.count) {
                SceneParticles.particle(embers, i, before, 0, p)
                SceneParticles.particle(embers, i, after, 0, q)
                if (!p.alive || !q.alive || q.age < p.age) continue // born or died in between
                compared++
                assertEquals("slot $i at $time: the same particle", p.spawnX, q.spawnX)
                assertEquals("slot $i at $time: ${2 * dt} s older", (2 * dt).toFloat(), q.age - p.age, 2e-3f)
            }
            assertTrue("most particles live across the step ($compared)", compared > 100)
        }
        assertEquals(0f, SceneParticles.phase(span, embers), 1e-3f)
        assertTrue(abs(SceneParticles.phase(-1.0, embers) - (span - 1).toFloat()) < 1e-2f)
    }

    @Test fun particlesFadeInAndTheirColourFollowsTheirAge() {
        assertEquals(0f, SceneParticles.fadeIn(0f, 4f))
        assertEquals(0.5f, SceneParticles.fadeIn(0.125f, 4f), 1e-5f) // 0.25 s at most
        assertEquals(1f, SceneParticles.fadeIn(1f, 4f))
        assertEquals(0.5f, SceneParticles.fadeIn(0.05f, 1f), 1e-5f) // 10 % of a short life
        val p = SceneParticles.particle(embers, 0, 1.5f, 0)
        assertEquals(1.5f / p.life, p.progress, 1e-4f)
    }

    @Test fun deadSlotsAreNotAlive() {
        // One long-lived slot, born rarely: between death and rebirth it is not drawn.
        val rare = embers.copy(rate = 0.1f, life = Vec2(2f, 2f))
        assertEquals(1, rare.count)
        assertEquals(10f, SceneParticles.period(rare), 1e-5f)
        assertTrue(SceneParticles.particle(rare, 0, 1f, 0).alive)
        assertFalse(SceneParticles.particle(rare, 0, 5f, 0).alive)
        assertTrue(SceneParticles.particle(rare, 0, 10.5f, 0).alive)
    }

    @Test fun theEmittersBoxIsPosedWithItsMotionScale() {
        val inputs = SceneInputs()
        val pose = LayerPose()
        val layer = embers.copy(look = LayerLook(rect = LayerRect(0.25f, 0.5f, 0.5f, 0.5f), motion = LayerMotion(pulse = Pulse(scale = 0.2f, period = 4f))))
        inputs.time = 1.0 // a quarter period: the pulse at its top
        assertTrue(ScenePoser.pose(layer, 1280, 720, 1280, 720, inputs, pose))
        assertEquals(1.2f, pose.scale, 1e-4f)
        assertEquals("the box before the scale", 640f, pose.axisXx / pose.scale, 0.01f)
        assertEquals(360f, pose.axisYy / pose.scale, 0.01f)
    }
}
