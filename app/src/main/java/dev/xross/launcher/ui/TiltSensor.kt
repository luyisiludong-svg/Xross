package dev.xross.launcher.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.View
import dev.xross.launcher.theme.shader.SceneTilt

/**
 * Tilt parallax (Live backgrounds § 3e): reads `TYPE_GAME_ROTATION_VECTOR` at
 * [SceneTilt.SAMPLE_PERIOD_US] (20 Hz) while [start]ed and hands [SceneTilt]'s reading to the
 * renderer, which eases it per frame. [WaveBackground] starts it only while the setting is on and
 * a scene with parallax shows, uncovered, in a resumed activity; a device without the sensor
 * reads 0. The game rotation vector has no magnetometer, so it never jumps near metal or
 * speakers; its slow yaw drift doesn't matter, as [SceneTilt] recentres anyway.
 */
internal class TiltSensor(context: Context, private val view: View, private val renderer: WaveRenderer) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor: Sensor? = manager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val tilt = SceneTilt()
    private val out = FloatArray(2)
    private var lastNs = 0L
    private var running = false

    val available: Boolean get() = sensor != null

    fun start() {
        if (running || sensor == null || manager == null) return
        tilt.reset()
        lastNs = 0L
        running = manager.registerListener(this, sensor, SceneTilt.SAMPLE_PERIOD_US)
    }

    /** Unregisters and lets the layers ease back to the middle. */
    fun stop() {
        if (running) manager?.unregisterListener(this)
        running = false
        renderer.tiltX = 0f
        renderer.tiltY = 0f
    }

    override fun onSensorChanged(event: SensorEvent) {
        val v = event.values
        if (v.size < 3) return
        // values[3] (w) is optional on old devices: it follows from the unit length.
        val w = if (v.size > 3) v[3] else kotlin.math.sqrt((1f - v[0] * v[0] - v[1] * v[1] - v[2] * v[2]).coerceAtLeast(0f))
        val dt = if (lastNs == 0L) 0f else (event.timestamp - lastNs) / 1e9f
        lastNs = event.timestamp
        tilt.update(v[0], v[1], v[2], w, view.display?.rotation ?: 0, dt, out)
        renderer.tiltX = out[0]
        renderer.tiltY = out[1]
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
