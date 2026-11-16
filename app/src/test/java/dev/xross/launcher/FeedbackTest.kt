package dev.xross.launcher

import dev.xross.launcher.ui.FeedbackPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Ping-pong feedback for background programs that read `uPrev`. */
class FeedbackTest {
    private val ms = 1_000_000L

    @Test fun onlyAProgramThatReadsUPrevOptsIn() {
        assertTrue(FeedbackPolicy.wants(0))
        assertTrue(FeedbackPolicy.wants(7))
        assertFalse(FeedbackPolicy.wants(-1)) // unused uniforms get no location
    }

    @Test fun historyCarriesOverBetweenConsecutiveFramesOfOneProgram() {
        assertFalse(FeedbackPolicy.fresh("BLACK_HOLE", "BLACK_HOLE", reallocated = false, lastNs = 1_000 * ms, nowNs = 1_016 * ms))
        // Idle pacing (20 fps) is still a run.
        assertFalse(FeedbackPolicy.fresh("BLACK_HOLE", "BLACK_HOLE", reallocated = false, lastNs = 1_000 * ms, nowNs = 1_050 * ms))
    }

    @Test fun historyIsDroppedWhenItCantBeTrusted() {
        // The first frame, a different program, new targets (resize or lost context), a long gap.
        assertTrue(FeedbackPolicy.fresh(null, "BLACK_HOLE", reallocated = false, lastNs = 0, nowNs = 5 * ms))
        assertTrue(FeedbackPolicy.fresh("OTHER", "BLACK_HOLE", reallocated = false, lastNs = 1_000 * ms, nowNs = 1_016 * ms))
        assertTrue(FeedbackPolicy.fresh("BLACK_HOLE", "BLACK_HOLE", reallocated = true, lastNs = 1_000 * ms, nowNs = 1_016 * ms))
        assertTrue(FeedbackPolicy.fresh("BLACK_HOLE", "BLACK_HOLE", reallocated = false, lastNs = 1_000 * ms, nowNs = 1_000 * ms + FeedbackPolicy.MAX_GAP_NS + 1))
    }

    @Test fun frameDtIsClampedSoALongFrameNeverWipesATrail() {
        assertEquals(1f / 60f, FeedbackPolicy.frameDt(0f), 1e-6f)
        assertEquals(0.05f, FeedbackPolicy.frameDt(0.05f), 1e-6f)
        assertEquals(FeedbackPolicy.MAX_DT_S, FeedbackPolicy.frameDt(3f), 1e-6f)
    }

    @Test fun thePreludeDeclaresTheFeedbackUniformsAndOnlyBlackHoleReadsThem() {
        val dir = File("src/main/assets/shaders")
        val prelude = File(dir, "prelude.glsl").readText()
        for (u in listOf("uniform sampler2D uPrev;", "uniform float uFrameDt;", "uniform float uFresh;")) {
            assertTrue(u, prelude.contains(u))
        }
        assertTrue(File(dir, "black_hole.frag").readText().contains("texture(uPrev"))
        for (f in listOf("wave.frag", "starfield.frag", "bokeh.frag", "crt.frag", "ascii_matrix.frag")) {
            assertFalse("$f stays stateless", File(dir, f).readText().contains("uPrev"))
        }
    }
}
