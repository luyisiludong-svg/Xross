package dev.xross.launcher.theme.shader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * Live backgrounds § 3e: tilt parallax from the game rotation vector, relative to where the device
 * was held, on the screen's axes for each display rotation, clamped, and recentred over time.
 */
class SceneTiltTest {
    private val out = FloatArray(2)

    /** A quaternion: [base] then a turn of [deg] degrees about the device axis ([ax], [ay], [az]). */
    private fun turn(base: FloatArray, ax: Float, ay: Float, az: Float, deg: Float): FloatArray {
        val h = Math.toRadians(deg.toDouble()) / 2
        val s = sin(h).toFloat()
        val bx = ax * s; val by = ay * s; val bz = az * s; val bw = cos(h).toFloat()
        val (x, y, z, w) = base.toList()
        // base × b: the turn in the device's own frame.
        return floatArrayOf(
            w * bx + x * bw + y * bz - z * by,
            w * by - x * bz + y * bw + z * bx,
            w * bz + x * by - y * bx + z * bw,
            w * bw - x * bx - y * by - z * bz,
        )
    }

    /** Held at 30° from flat about the device's x axis: a typical handheld pose. */
    private val held = turn(floatArrayOf(0f, 0f, 0f, 1f), 1f, 0f, 0f, 30f)

    private fun read(tilt: SceneTilt, q: FloatArray, rotation: Int = 0, dt: Float = 0.05f): Pair<Float, Float> {
        tilt.update(q[0], q[1], q[2], q[3], rotation, dt, out)
        return out[0] to out[1]
    }

    @Test fun theFirstSampleIsTheMiddle() {
        val tilt = SceneTilt()
        assertEquals(0f to 0f, read(tilt, held, dt = 0f))
        assertEquals("held still, it stays in the middle", 0f to 0f, read(tilt, held))
    }

    @Test fun turnsMapToTheScreensAxes() {
        val half = SceneTilt.RANGE_DEG / 2
        // Natural orientation: the right edge away from the viewer (+ about y) reads +x.
        SceneTilt().also { t ->
            read(t, held, dt = 0f)
            val (x, y) = read(t, turn(held, 0f, 1f, 0f, half), dt = 0f)
            assertEquals(0.5f, x, 0.01f)
            assertEquals(0f, y, 0.01f)
        }
        // The top edge towards the viewer (+ about x) reads +y.
        SceneTilt().also { t ->
            read(t, held, dt = 0f)
            val (x, y) = read(t, turn(held, 1f, 0f, 0f, half), dt = 0f)
            assertEquals(0f, x, 0.01f)
            assertEquals(0.5f, y, 0.01f)
        }
        // Landscape (ROTATION_90): the screen's up is the device's -x, its right the device's +y.
        SceneTilt().also { t ->
            read(t, held, 1, dt = 0f)
            val (x, y) = read(t, turn(held, -1f, 0f, 0f, half), 1, dt = 0f)
            assertEquals("a turn about the screen's up axis", 0.5f, x, 0.01f)
            assertEquals(0f, y, 0.01f)
            val (x2, y2) = read(t, turn(held, 0f, 1f, 0f, half), 1, dt = 0f)
            assertEquals(0f, x2, 0.01f)
            assertEquals("a turn about the screen's right axis", 0.5f, y2, 0.01f)
        }
        // ROTATION_180 and 270 mirror them.
        SceneTilt().also { t ->
            read(t, held, 2, dt = 0f)
            assertEquals(-0.5f, read(t, turn(held, 0f, 1f, 0f, half), 2, dt = 0f).first, 0.01f)
        }
        SceneTilt().also { t ->
            read(t, held, 3, dt = 0f)
            assertEquals(0.5f, read(t, turn(held, 1f, 0f, 0f, half), 3, dt = 0f).first, 0.01f)
        }
    }

    @Test fun moreThanTheRangeIsClampedAndNoSampleIsNaN() {
        val tilt = SceneTilt()
        read(tilt, held, dt = 0f)
        assertEquals(-1f, read(tilt, turn(held, 0f, 1f, 0f, -40f), dt = 0f).first, 0f)
        assertEquals("a zero quaternion reads the middle", 0f to 0f, read(tilt, floatArrayOf(0f, 0f, 0f, 0f), dt = 0f))
        val flipped = held.map { -it }.toFloatArray()
        assertEquals("q and -q are the same pose", 0f, read(tilt, flipped, dt = 0f).first, 1e-4f)
    }

    @Test fun aHeldTiltDriftsBackToTheMiddle() {
        val tilt = SceneTilt()
        read(tilt, held, dt = 0f)
        val tilted = turn(held, 0f, 1f, 0f, SceneTilt.RANGE_DEG)
        val first = read(tilt, tilted).first
        assertEquals(1f, first, 0.05f)
        var x = first
        repeat(20 * 4) { x = read(tilt, tilted).first } // 4 s at 20 Hz: one time constant
        assertTrue("about e⁻¹ of the way left after one time constant: $x", x in 0.3f..0.45f)
        repeat(20 * 20) { x = read(tilt, tilted).first }
        assertEquals(0f, x, 0.01f)
        tilt.reset()
        assertEquals("reset makes the next sample the middle", 0f, read(tilt, held, dt = 0f).first, 0f)
    }
}
