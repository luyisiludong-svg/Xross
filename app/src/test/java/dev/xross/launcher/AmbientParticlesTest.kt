package dev.xross.launcher

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.xross.launcher.state.InactivityTracker
import dev.xross.launcher.theme.pack.Cosmetics
import dev.xross.launcher.theme.pack.ParticlePreset
import dev.xross.launcher.theme.pack.ThemeManifest
import dev.xross.launcher.theme.pack.ThemePackLoader
import dev.xross.launcher.ui.ParticleField
import dev.xross.launcher.ui.ParticleRest
import dev.xross.launcher.ui.ParticleState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.math.abs

/**
 * Theme 2.0, 2b.4e: `effects.foregroundParticles` through the cascade; the particle field's
 * start, motion and wrapping; and when the layer hides, rests and runs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AmbientParticlesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule val tmp = TemporaryFolder()

    private val themes get() = File(tmp.root, "Xross/themes").apply { mkdirs() }
    private val loader by lazy { ThemePackLoader(context.assets) { themes } }

    private fun pack(id: String, body: String) {
        val dir = File(themes, id).apply { mkdirs() }
        File(dir, "theme.json").writeText("""{"schemaVersion": 2, "name": "$id", $body}""")
    }

    /** Runs [field] for [seconds] at 60 Hz. */
    private fun run(field: ParticleField, seconds: Float, running: Boolean = true): Boolean {
        var more = true
        repeat((seconds * 60).toInt()) { more = field.advance(1f / 60, running) }
        return more
    }

    // region Manifest and cascade

    @Test fun presetNamesAreReadIgnoringCaseAndDashes() {
        val problems = mutableListOf<String>()
        for ((text, preset) in listOf("snow" to ParticlePreset.SNOW, "Bokeh" to ParticlePreset.BOKEH, "dust-motes" to ParticlePreset.DUST_MOTES, "none" to ParticlePreset.NONE)) {
            assertEquals(text, preset, Cosmetics.of("theme", ThemeManifest.Effects(foregroundParticles = text), problems)?.particles)
        }
        assertTrue(problems.toString(), problems.isEmpty())
        assertNull(Cosmetics.of("theme", ThemeManifest.Effects(foregroundParticles = " "), problems))
    }

    @Test fun anUnknownPresetIsReportedAndLeftUnset() {
        val problems = mutableListOf<String>()
        assertNull(Cosmetics.of("rules[0]", ThemeManifest.Effects(foregroundParticles = "CONFETTI"), problems))
        assertEquals(1, problems.size)
        assertTrue(problems[0], problems[0].startsWith("rules[0].effects.foregroundParticles: CONFETTI"))
    }

    @Test fun particlesCascadeThroughVariantsOverridesAndRules() {
        pack(
            "winter",
            """
            "effects": {"foregroundParticles": "SNOW", "iconMask": "CIRCLE"},
            "variants": [{"id": "soft", "effects": {"particles": "BOKEH"}}],
            "categoryOverrides": {"MUSIC": {"effects": {"foregroundParticles": "NONE"}}},
            "rules": [{"match": {"category": "VIDEO"}, "apply": {"effects": {"foregroundParticles": "DUST_MOTES"}}}]
            """,
        )
        val t = loader.load("winter")
        assertTrue(t.problems.toString(), t.problems.isEmpty())
        assertEquals(ParticlePreset.SNOW, t.look().cosmetics.particleField)
        assertEquals(ParticlePreset.BOKEH, t.look("soft").cosmetics.particleField)
        // Naming the particles leaves the other cosmetics alone.
        assertEquals(dev.xross.launcher.theme.pack.IconMask.CIRCLE, t.look("soft").cosmetics.mask)
        assertEquals(ParticlePreset.NONE, t.look("soft", category = "music").cosmetics.particleField)
        assertEquals(ParticlePreset.DUST_MOTES, t.look("soft", category = "video").cosmetics.particleField)
    }

    @Test fun theDefaultPackHasNoParticles() {
        assertEquals(ParticlePreset.NONE, loader.load("default").look().cosmetics.particleField)
    }

    // endregion

    // region Field: start

    @Test fun eachPresetStartsWithItsCountUnderTheCap() {
        assertEquals(0, ParticleField(ParticlePreset.NONE).count)
        for (p in ParticlePreset.entries - ParticlePreset.NONE) {
            val f = ParticleField(p)
            assertTrue("$p: ${f.count}", f.count in 1..ParticleField.MAX_PARTICLES)
            for (arr in listOf(f.x, f.y, f.speed, f.size, f.alpha, f.drift, f.sway, f.freq, f.phase)) assertEquals(f.count, arr.size)
        }
        assertEquals(ParticleField.MAX_PARTICLES, ParticleField(ParticlePreset.SNOW, count = 500).count)
        assertEquals(48, ParticleField.MAX_PARTICLES)
        assertEquals(0, ParticleField(ParticlePreset.SNOW, count = -3).count)
    }

    @Test fun particlesStartSpreadOverTheWholeLayer() {
        for (p in ParticlePreset.entries - ParticlePreset.NONE) {
            val f = ParticleField(p)
            for (i in 0 until f.count) {
                assertTrue("$p x ${f.x[i]}", f.x[i] in 0f..1f)
                assertTrue("$p y ${f.y[i]}", f.y[i] in 0f..1f)
                assertTrue("$p alpha ${f.alpha[i]}", f.alpha[i] in 0.01f..1f)
                assertTrue("$p size ${f.size[i]}", f.size[i] > 0f)
            }
            // Not bunched at one edge: both halves of the screen get some.
            assertTrue(p.name, f.y.count { it < 0.5f } in 1 until f.count)
        }
    }

    @Test fun theSameSeedGivesTheSameField() {
        val a = ParticleField(ParticlePreset.DUST_MOTES, seed = 7)
        val b = ParticleField(ParticlePreset.DUST_MOTES, seed = 7)
        assertArrayEquals(a.x, b.x, 0f)
        assertArrayEquals(a.y, b.y, 0f)
        assertArrayEquals(a.speed, b.speed, 0f)
        run(a, 3f); run(b, 3f)
        assertArrayEquals(a.y, b.y, 0f)
    }

    @Test fun presetsMoveTheirOwnWay() {
        // Snow falls; bokeh rises; nearer (bigger) bokeh is fainter and faster.
        val snow = ParticleField(ParticlePreset.SNOW)
        assertTrue(snow.speed.all { it > 0f })
        val bokeh = ParticleField(ParticlePreset.BOKEH)
        assertTrue(bokeh.speed.all { it < 0f })
        val big = bokeh.size.indices.maxBy { bokeh.size[it] }
        val small = bokeh.size.indices.minBy { bokeh.size[it] }
        assertTrue(bokeh.alpha[big] < bokeh.alpha[small])
        assertTrue(abs(bokeh.speed[big]) > abs(bokeh.speed[small]))
    }

    @Test fun snowSwaysAroundItsPath() {
        val f = ParticleField(ParticlePreset.SNOW, count = 1)
        val xs = (0 until 240).map { f.advance(1f / 60, true); f.xAt(0) - f.x[0] }
        assertTrue(xs.max() > 0f && xs.min() < 0f)
        assertTrue(xs.all { abs(it) <= f.sway[0] / f.aspect + 1e-6f })
    }

    @Test fun dustIsBrightestInTheShaft() {
        val f = ParticleField(ParticlePreset.DUST_MOTES)
        for (i in 0 until f.count) {
            assertTrue(f.alphaAt(i) in 0f..f.alpha[i])
        }
    }

    // endregion

    // region Field: wrapping

    @Test fun aFlakeLeavingTheBottomComesBackAtTheTop() {
        val f = ParticleField(ParticlePreset.SNOW, count = 1)
        f.y[0] = 1f + f.margin - 1e-4f
        f.advance(1f / 60, true)
        assertEquals(-f.margin, f.y[0], 1e-6f)
        assertTrue(f.x[0] in 0f..1f)
    }

    @Test fun aDiscLeavingTheTopComesBackAtTheBottom() {
        val f = ParticleField(ParticlePreset.BOKEH, count = 1)
        f.y[0] = -f.margin + 1e-5f
        f.advance(1f / 60, true)
        assertEquals(1f + f.margin, f.y[0], 1e-6f)
    }

    @Test fun driftWrapsAcrossTheSides() {
        val f = ParticleField(ParticlePreset.SNOW, count = 1)
        f.y[0] = 0.5f
        f.x[0] = 1.02f // Past the right margin after one step of drift.
        f.advance(ParticleField.MAX_STEP_SECONDS, true)
        assertTrue("x ${f.x[0]}", f.x[0] < 0.05f)
    }

    @Test fun aLongRunKeepsEveryParticleInsideTheMargins() {
        for (p in ParticlePreset.entries - ParticlePreset.NONE) {
            val f = ParticleField(p)
            f.aspect = 0.5f // Portrait: horizontal motion counts double.
            repeat(60 * 120) {
                f.advance(1f / 60, true)
                for (i in 0 until f.count) {
                    val mx = f.margin / f.aspect
                    assertTrue("$p y ${f.y[i]}", f.y[i] in -f.margin..1f + f.margin)
                    assertTrue("$p x ${f.x[i]}", f.x[i] in -mx..1f + mx)
                }
            }
        }
    }

    @Test fun aLongGapIsOneShortStep() {
        val a = ParticleField(ParticlePreset.SNOW, seed = 1)
        val b = ParticleField(ParticlePreset.SNOW, seed = 1)
        a.advance(30f, true) // Back from the background: no 30 s leap.
        b.advance(ParticleField.MAX_STEP_SECONDS, true)
        assertArrayEquals(b.y, a.y, 0f)
    }

    // endregion

    // region Resting

    @Test fun theLayerHidesRestsAndRuns() {
        fun of(
            preset: ParticlePreset = ParticlePreset.SNOW, still: Boolean = false, snap: Boolean = false,
            covered: Boolean = false, started: Boolean = true, idle: Boolean = false,
        ) = ParticleRest.of(preset, still, snap, covered, started, idle)
        assertEquals(ParticleState.RUNNING, of())
        assertEquals(ParticleState.HIDDEN, of(preset = ParticlePreset.NONE))
        assertEquals(ParticleState.HIDDEN, of(still = true)) // MONOCHROME_EINK
        assertEquals(ParticleState.HIDDEN, of(snap = true))
        assertEquals(ParticleState.HIDDEN, of(covered = true))
        assertEquals(ParticleState.RESTING, of(started = false))
        assertEquals(ParticleState.RESTING, of(idle = true))
        // Hidden wins over resting: E-Ink in the background is still hidden.
        assertEquals(ParticleState.HIDDEN, of(still = true, started = false, idle = true))
        assertEquals(ParticleState.HIDDEN, of(snap = true, idle = true))
    }

    @Test fun restingEasesToAStopThenAsksForNoMoreFrames() {
        val f = ParticleField(ParticlePreset.SNOW)
        assertTrue(run(f, 1f))
        // Still easing down for most of the gait...
        assertTrue(run(f, ParticleField.GAIT_SECONDS * 0.5f, running = false))
        // ...then stopped: no more frames wanted, and nothing moves.
        assertFalse(run(f, ParticleField.GAIT_SECONDS, running = false))
        assertEquals(0f, f.gait, 0f)
        val y = f.y.copyOf()
        val clock = f.clock
        assertFalse(f.advance(1f / 60, false))
        assertArrayEquals(y, f.y, 0f)
        assertEquals(clock, f.clock, 0f)
    }

    @Test fun inputResumesSmoothly() {
        val f = ParticleField(ParticlePreset.SNOW, count = 1, gait = 0f)
        f.y[0] = 0.5f
        val full = f.speed[0] / 60
        // The first frames back move much less than a full-speed frame, then speed builds up.
        val steps = (0 until 60).map { val y = f.y[0]; f.advance(1f / 60, true); f.y[0] - y }
        assertTrue(steps.first() < full * 0.05f)
        assertTrue(steps.zipWithNext().all { (a, b) -> b >= a - 1e-7f })
        assertEquals(1f, f.gait, 0f)
        assertEquals(full, steps.last(), full * 1e-3f)
    }

    @Test fun particlesRestAfterTenSecondsWithoutInput() = runTest {
        val tracker = InactivityTracker(timeoutMs = ParticleRest.IDLE_AFTER_MS) { testScheduler.currentTime }
        val job = launch { tracker.run() }
        runCurrent()
        advanceTimeBy(9_999)
        assertFalse(tracker.idle.value)
        advanceTimeBy(2)
        assertTrue(tracker.idle.value)
        assertEquals(ParticleState.RESTING, ParticleRest.of(ParticlePreset.BOKEH, false, false, false, true, tracker.idle.value))
        tracker.onInput()
        assertEquals(ParticleState.RUNNING, ParticleRest.of(ParticlePreset.BOKEH, false, false, false, true, tracker.idle.value))
        job.cancel()
    }

    // endregion
}
