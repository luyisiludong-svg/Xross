package dev.xross.launcher.theme.shader

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Tilt parallax (Live backgrounds § 3e): how far the device is tilted from where it was held, as
 * -1..1 on the screen's two axes, from `TYPE_GAME_ROTATION_VECTOR` samples. Pure (no Android), so
 * it is unit tested; the app feeds it the sensor's quaternion and the display's rotation.
 *
 *  - **Relative.** The first sample is the rest pose. Each sample's rotation away from it is read
 *    in the device's own frame, so how the device is held (flat, upright, in a dock) doesn't matter.
 *  - **Recentred.** The rest pose follows the device with a time constant of [RECENTER_S], so a
 *    tilt held still drifts back to the middle and the art never stays pushed to one side.
 *  - **Screen axes.** X follows a turn about the screen's vertical axis (the right edge away from
 *    the viewer is +1, like the focus moving right), Y a turn about its horizontal axis (the top
 *    edge towards the viewer is +1, like the focus moving down), for each display rotation.
 *  - **Range.** [RANGE_DEG] degrees is full scale; more is clamped.
 *
 * [ScenePoser] adds the result to the focus's pull, clamped, so a layer never moves further than
 * its overscan allows.
 */
class SceneTilt(
    private val rangeDeg: Float = RANGE_DEG,
    private val recenterS: Float = RECENTER_S,
) {
    // The rest pose, a unit quaternion (x, y, z, w); w = 0 and all zero means "not set".
    private var rx = 0f
    private var ry = 0f
    private var rz = 0f
    private var rw = 0f
    private var hasRest = false

    /** Forget the rest pose: the next sample is the new middle. */
    fun reset() {
        hasRest = false
    }

    /**
     * One sensor sample: the device's orientation as a quaternion ([qx], [qy], [qz], [qw]; the
     * sensor's `values[0..3]`), the display's rotation in quarter turns (`Surface.ROTATION_*`, 0–3)
     * and the seconds since the last sample. Writes the tilt, -1..1, to [out] (x then y).
     */
    fun update(qx: Float, qy: Float, qz: Float, qw: Float, rotation: Int, dtSeconds: Float, out: FloatArray) {
        var x = qx
        var y = qy
        var z = qz
        var w = qw
        val n = sqrt(x * x + y * y + z * z + w * w)
        if (n < 1e-6f || n.isNaN()) {
            out[0] = 0f
            out[1] = 0f
            return
        }
        x /= n; y /= n; z /= n; w /= n
        if (!hasRest) {
            rx = x; ry = y; rz = z; rw = w
            hasRest = true
        } else if (dtSeconds > 0f) {
            // Normalised lerp towards the sample on the same hemisphere: a slow, steady recentre.
            val k = 1f - exp(-dtSeconds.coerceAtMost(1f) / recenterS)
            val s = if (rx * x + ry * y + rz * z + rw * w < 0f) -1f else 1f
            rx += (s * x - rx) * k
            ry += (s * y - ry) * k
            rz += (s * z - rz) * k
            rw += (s * w - rw) * k
            val m = sqrt(rx * rx + ry * ry + rz * rz + rw * rw)
            rx /= m; ry /= m; rz /= m; rw /= m
        }
        // The turn from the rest pose to this sample, in the device's frame: conj(rest) × sample.
        val dw = rw * w + rx * x + ry * y + rz * z
        var dx = rw * x - rx * w - ry * z + rz * y
        var dy = rw * y + rx * z - ry * w - rz * x
        // Small angles: the axis × angle is about twice the vector part (on the near hemisphere).
        val sign = if (dw < 0f) -2f else 2f
        dx *= sign
        dy *= sign
        // Device axes to the screen's: the turn about the screen's up axis and its right axis.
        val (aboutUp, aboutRight) = when (rotation and 3) {
            0 -> dy to dx
            1 -> -dx to dy
            2 -> -dy to -dx
            else -> dx to -dy
        }
        val range = Math.toRadians(rangeDeg.toDouble()).toFloat()
        // A positive turn about up sends the right edge to -z (away from the viewer); a positive
        // turn about right brings the top edge to +z (towards the viewer).
        out[0] = clamp(aboutUp / range)
        out[1] = clamp(aboutRight / range)
    }

    private fun clamp(v: Float): Float = if (abs(v) < 1e-5f) 0f else v.coerceIn(-1f, 1f)

    companion object {
        /** Degrees from the rest pose to full scale. */
        const val RANGE_DEG = 12f

        /** Time constant of the rest pose following the device, seconds. */
        const val RECENTER_S = 4f

        /** The sensor's sampling period while a scene with parallax shows, µs: 20 Hz, eased per frame by the renderer. */
        const val SAMPLE_PERIOD_US = 50_000
    }
}
