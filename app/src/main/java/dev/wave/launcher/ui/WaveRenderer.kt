package dev.wave.launcher.ui

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.SystemClock
import java.util.Calendar
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.PI

/** Tunables for the ribbon background; read every frame so changes apply live. */
data class WaveParams(
    val speed: Float = 1f,
    val lines: Int = 14,
    val amplitude: Float = 1f,
)

/**
 * Full-screen GLSL ES 3.0 ribbon background. One oversized triangle, all work in the fragment
 * shader. Colors shift with the time of day (deep blue at night, lighter teal-blue at midday).
 */
class WaveRenderer(@Volatile var params: WaveParams = WaveParams()) : GLSurfaceView.Renderer {
    private var program = 0
    private var uRes = 0
    private var uTime = 0
    private var uTop = 0
    private var uBottom = 0
    private var uWave = 0
    private var uLines = 0
    private var uAmp = 0
    private var width = 1
    private var height = 1

    private var time = 0f
    private var lastFrameNs = 0L
    private var palette = paletteFor(Calendar.getInstance())
    private var paletteCheckedAt = 0L

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = link(VERTEX, FRAGMENT)
        uRes = GLES30.glGetUniformLocation(program, "uRes")
        uTime = GLES30.glGetUniformLocation(program, "uTime")
        uTop = GLES30.glGetUniformLocation(program, "uTop")
        uBottom = GLES30.glGetUniformLocation(program, "uBottom")
        uWave = GLES30.glGetUniformLocation(program, "uWave")
        uLines = GLES30.glGetUniformLocation(program, "uLines")
        uAmp = GLES30.glGetUniformLocation(program, "uAmp")
        lastFrameNs = 0L
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w
        height = h
        GLES30.glViewport(0, 0, w, h)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = SystemClock.elapsedRealtimeNanos()
        val p = params
        // Integrate time so changing speed never makes the waves jump.
        if (lastFrameNs != 0L) time += ((now - lastFrameNs) / 1e9f).coerceAtMost(0.1f) * p.speed
        lastFrameNs = now

        if (now - paletteCheckedAt > 60_000_000_000L) {
            palette = paletteFor(Calendar.getInstance())
            paletteCheckedAt = now
        }

        GLES30.glUseProgram(program)
        GLES30.glUniform2f(uRes, width.toFloat(), height.toFloat())
        GLES30.glUniform1f(uTime, time)
        GLES30.glUniform3fv(uTop, 1, palette.top, 0)
        GLES30.glUniform3fv(uBottom, 1, palette.bottom, 0)
        GLES30.glUniform3fv(uWave, 1, palette.wave, 0)
        GLES30.glUniform1f(uLines, p.lines.toFloat())
        GLES30.glUniform1f(uAmp, p.amplitude)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    }

    private class Palette(val top: FloatArray, val bottom: FloatArray, val wave: FloatArray)

    private fun paletteFor(cal: Calendar): Palette {
        val hour = cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60f
        // 0 at midnight, 1 at noon.
        val day = ((1 - cos(hour / 24f * 2 * PI)) / 2).toFloat()
        fun mix(a: FloatArray, b: FloatArray) = FloatArray(3) { a[it] + (b[it] - a[it]) * day }
        return Palette(
            top = mix(floatArrayOf(0.02f, 0.03f, 0.12f), floatArrayOf(0.10f, 0.28f, 0.62f)),
            bottom = mix(floatArrayOf(0.05f, 0.02f, 0.10f), floatArrayOf(0.02f, 0.08f, 0.24f)),
            wave = mix(floatArrayOf(0.55f, 0.60f, 1.00f), floatArrayOf(0.80f, 0.95f, 1.00f)),
        )
    }

    private fun link(vs: String, fs: String): Int {
        val v = compile(GLES30.GL_VERTEX_SHADER, vs)
        val f = compile(GLES30.GL_FRAGMENT_SHADER, fs)
        return GLES30.glCreateProgram().also {
            GLES30.glAttachShader(it, v)
            GLES30.glAttachShader(it, f)
            GLES30.glLinkProgram(it)
            val status = IntArray(1)
            GLES30.glGetProgramiv(it, GLES30.GL_LINK_STATUS, status, 0)
            check(status[0] != 0) { "Wave shader link failed: ${GLES30.glGetProgramInfoLog(it)}" }
        }
    }

    private fun compile(type: Int, src: String): Int = GLES30.glCreateShader(type).also {
        GLES30.glShaderSource(it, src)
        GLES30.glCompileShader(it)
        val status = IntArray(1)
        GLES30.glGetShaderiv(it, GLES30.GL_COMPILE_STATUS, status, 0)
        check(status[0] != 0) { "Wave shader compile failed: ${GLES30.glGetShaderInfoLog(it)}" }
    }

    private companion object {
        // Full-screen triangle generated from gl_VertexID: no vertex buffers needed.
        const val VERTEX = """#version 300 es
void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
"""

        const val FRAGMENT = """#version 300 es
precision highp float;
uniform vec2 uRes;
uniform float uTime;
uniform vec3 uTop;
uniform vec3 uBottom;
uniform vec3 uWave;
uniform float uLines;
uniform float uAmp;
out vec4 fragColor;

float ribbon(float x, float t, float seed) {
    return 0.10 * sin(x * 1.25 + t * 0.30 + seed)
         + 0.045 * sin(x * 2.60 - t * 0.47 + seed * 1.7)
         + 0.020 * sin(x * 5.10 + t * 0.81 + seed * 0.3);
}

void main() {
    vec2 uv = gl_FragCoord.xy / uRes;
    float x = uv.x * (uRes.x / uRes.y);
    vec3 col = mix(uBottom, uTop, smoothstep(0.0, 1.0, uv.y));

    // Broad soft sheet under the lines.
    float sheet = 0.48 + uAmp * ribbon(x, uTime, 0.0);
    col += uWave * 0.10 * exp(-pow((uv.y - sheet) * 5.0, 2.0));

    // Thin glowing strands fanning out of the sheet.
    for (int i = 0; i < 24; i++) {
        float fi = float(i);
        if (fi >= uLines) break;
        float spread = (fi / max(uLines - 1.0, 1.0) - 0.5);
        float y = sheet + uAmp * ribbon(x, uTime * (0.9 + 0.02 * fi), fi * 0.21) * 0.35
                + spread * 0.09 * (1.0 + 0.6 * sin(x * 0.9 + uTime * 0.2));
        float d = abs(uv.y - y) * uRes.y;          // distance in pixels
        float core = smoothstep(1.6, 0.0, d);       // crisp 1-2px line
        float glow = 0.9 / (1.0 + d * d * 0.08);    // soft halo
        col += uWave * (core * 0.20 + glow * 0.035);
    }

    // Vignette.
    vec2 c = uv - 0.5;
    col *= 1.0 - dot(c, c) * 0.55;
    fragColor = vec4(col, 1.0);
}
"""
    }
}
