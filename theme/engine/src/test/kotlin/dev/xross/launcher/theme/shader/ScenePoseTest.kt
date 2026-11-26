package dev.xross.launcher.theme.shader

import dev.xross.launcher.theme.pack.VideoFit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Live backgrounds § 3b: a scene layer's box (rect, fit, anchor, parallax overscan), its motions
 * and drivers as pure functions of time and inputs, sprite cells and scrolling.
 */
class ScenePoseTest {
    private val inputs = SceneInputs()
    private val pose = LayerPose()

    private fun image(look: LayerLook = LayerLook()) = SceneLayer.Image("a.png", look = look)

    private fun posed(layer: SceneLayer, texW: Int = 200, texH: Int = 100, w: Int = 1280, h: Int = 720): LayerPose {
        assertTrue(ScenePoser.pose(layer, texW, texH, w, h, inputs, pose))
        return pose
    }

    private fun box(p: LayerPose) = listOf(p.originX, p.originY, p.axisXx, p.axisYy)

    private fun assertBox(expected: List<Float>, p: LayerPose) {
        val actual = box(p)
        for (i in expected.indices) assertEquals("$actual vs $expected", expected[i], actual[i], 0.01f)
    }

    @Test fun rectsAreScreenFractionsFromTheTopLeft() {
        val p = posed(image(LayerLook(rect = LayerRect(0.5f, 0.25f, 0.25f, 0.5f))))
        assertBox(listOf(640f, 180f, 320f, 360f), p)
        assertEquals(0f, p.axisXy, 0f)
        assertEquals(0f, p.axisYx, 0f)
    }

    @Test fun fitsAndAnchors() {
        // A 2:1 image on a 16:9 screen.
        assertBox(listOf(-80f, 0f, 1440f, 720f), posed(image()))
        assertBox(listOf(0f, 40f, 1280f, 640f), posed(image(LayerLook(fit = VideoFit.FIT))))
        assertBox(listOf(0f, 80f, 1280f, 640f), posed(image(LayerLook(fit = VideoFit.FIT, anchor = LayerAnchor.BOTTOM))))
        assertBox(listOf(0f, 0f, 1280f, 720f), posed(image(LayerLook(fit = VideoFit.STRETCH))))
        assertBox("CENTER: texels at 1280 px on the long side", listOf(540f, 310f, 200f, 100f), posed(image(LayerLook(fit = VideoFit.CENTER))))
        assertBox("CENTER scales with the screen", listOf(1080f, 620f, 400f, 200f), posed(image(LayerLook(fit = VideoFit.CENTER)), w = 2560, h = 1440))
        assertBox(
            "FILL anchored at the bottom right overflows to the left",
            listOf(-160f, 0f, 1440f, 720f),
            posed(image(LayerLook(anchor = LayerAnchor.BOTTOM_RIGHT))),
        )
    }

    private fun assertBox(message: String, expected: List<Float>, p: LayerPose) {
        val actual = box(p)
        for (i in expected.indices) assertEquals("$message: $actual", expected[i], actual[i], 0.01f)
    }

    @Test fun parallaxMovesAwayFromTheFocusAndOverscans() {
        val layer = image(LayerLook(fit = VideoFit.STRETCH, parallax = 1f))
        inputs.focusX = 0.5f
        inputs.focusY = 0.5f
        val shift = ScenePoser.PARALLAX_SHIFT
        val centred = box(posed(layer))
        assertEquals("enlarged so no edge shows", 1280f * (1 + 2 * shift), centred[2], 0.01f)
        assertEquals(-1280f * shift, centred[0], 0.01f)
        inputs.focusX = 1f // focus at the right edge: the layer moves left by the full shift
        val right = box(posed(layer))
        assertEquals(-1280f * shift * 2, right[0], 0.01f)
        assertTrue("its right edge still covers the screen", right[0] + right[2] >= 1280f - 0.01f)
        val still = box(posed(image(LayerLook(fit = VideoFit.STRETCH, parallax = 0f))))
        assertEquals("parallax 0 stays still", 0f, still[0], 0f)
    }

    @Test fun swayAndPulseAreSinesOfTheirPeriods() {
        val layer = image(LayerLook(rect = LayerRect(0f, 0f, 0.5f, 0.5f), motion = LayerMotion(sway = Sway(angle = 90f, period = 4f), pulse = Pulse(scale = 0.5f, opacity = -0.5f, period = 4f))))
        inputs.time = 0.0
        val rest = posed(layer)
        assertEquals(640f, rest.axisXx, 0.01f)
        assertEquals(1f, rest.opacity, 0f)
        inputs.time = 1.0 // a quarter period: the sines peak
        val peak = posed(layer)
        assertEquals("rotated a quarter turn", 0f, peak.axisXx, 0.01f)
        assertEquals("1.5× its width, now pointing down", 960f, peak.axisXy, 0.01f)
        assertEquals(0.5f, peak.opacity, 1e-4f)
        // Rotation and scale are about the centre (320, 180).
        assertEquals(320f, peak.originX + (peak.axisXx + peak.axisYx) / 2, 0.01f)
        assertEquals(180f, peak.originY + (peak.axisXy + peak.axisYy) / 2, 0.01f)
        inputs.time = 4.0 * 1_000_000 + 1.0
        assertEquals("no drift after days", 0.5f, posed(layer).opacity, 1e-3f)
    }

    @Test fun driversAddTheirAmountTimesTheInput() {
        val layer = image(LayerLook(rect = LayerRect(0f, 0f, 0.5f, 0.5f), opacity = 0.5f, motion = LayerMotion(
            audio = Drive(scale = 1f),
            transition = Drive(offset = Vec2(10f, 0f)),
            daylight = Drive(opacity = 0.5f),
        )))
        inputs.time = 0.0
        inputs.bass = 0.4f
        inputs.beat = 1f
        inputs.transition = 0.5f
        inputs.daylight = 1f
        val p = posed(layer)
        assertEquals("bass 0.4 + half a beat", 640f * 1.9f, p.axisXx, 0.01f)
        assertEquals(1f, p.opacity, 0f)
        assertEquals("offset in px at 1280", 320f + 5f, p.originX + p.axisXx / 2, 0.01f)
        inputs.bass = 0f
        inputs.beat = 0f
        inputs.transition = 0f
        inputs.daylight = 0f
        assertFalse("opacity 0 draws nothing", ScenePoser.pose(image(LayerLook(opacity = 0f)), 10, 10, 100, 100, inputs, pose))
    }

    @Test fun spritesStepThroughTheirCells() {
        val sprite = SceneLayer.Sprite("s.png", columns = 4, rows = 2, fps = 8f, count = 6, look = LayerLook(rect = LayerRect(0f, 0f, 0.1f, 0.1f)))
        inputs.time = 0.0
        posed(sprite, 400, 100)
        assertEquals(listOf(0f, 0f, 0.25f, 0.5f), listOf(pose.u0, pose.v0, pose.du, pose.dv))
        inputs.time = 5.0 / 8 // cell 5: second row, second column
        posed(sprite, 400, 100)
        assertEquals(0.25f, pose.u0, 1e-6f)
        assertEquals(0.5f, pose.v0, 1e-6f)
        inputs.time = 6.0 / 8 // back to the first of 6
        posed(sprite, 400, 100)
        assertEquals(0f, pose.u0, 1e-6f)
        assertEquals("before time 0 it wraps backwards", 5, ScenePoser.frame(-0.1, 8f, 6))
        assertEquals("a sprite fits one cell (100×50), not the sheet", 1440f, posed(SceneLayer.Sprite("s.png", 4, 2), 400, 100).axisXx, 0.01f)
    }

    @Test fun scrollingMovesTheWindowInTexelsPerSecond() {
        val layer = image(LayerLook(motion = LayerMotion(scroll = Vec2(50f, -25f))))
        inputs.time = 1.0
        posed(layer, 200, 100)
        assertEquals("50 texels of 200 to the right: the window moves left", -0.25f, pose.u0, 1e-6f)
        assertEquals(-0.75f, pose.v0, 1e-6f)
        assertEquals(0.25f, ScenePoser.scrolled(1e7 + 1.0, 50f, 200), 1e-3f)
    }
}
