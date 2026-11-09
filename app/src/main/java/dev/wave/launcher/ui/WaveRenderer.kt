package dev.wave.launcher.ui

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.util.Log
import dev.wave.launcher.BuildConfig
import dev.wave.launcher.theme.WaveTheme
import dev.wave.launcher.theme.rgb
import java.util.Calendar
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToLong

/** Tunables for the background; read every frame so changes apply live. */
data class WaveParams(
    val speed: Float = 1f,
    val theme: WaveTheme = WaveTheme.LAVENDER,
)

/**
 * Full-screen GLSL ES 3.0 background: a theme gradient with broad, glossy light sheets and a few
 * fine strands. One oversized triangle; all work happens in the fragment shader.
 *
 * Smoothness:
 *  - Animation time advances in whole display-refresh periods, so sampling jitter in when the
 *    GL thread wakes up never shows up as uneven motion.
 *  - Crests are anti-aliased with screen-space derivatives and the output is dithered, so the
 *    soft gradients don't band.
 *  - The surface is rendered below native resolution (see [WaveBackground]); the content is soft
 *    enough that the upscale is invisible and the GPU cost drops ~4x.
 */
class WaveRenderer(
    @Volatile var params: WaveParams = WaveParams(),
    refreshRateHz: Float = 60f,
) : GLSurfaceView.Renderer {
    private val framePeriodNs = (1e9 / refreshRateHz.coerceIn(30f, 240f)).roundToLong()

    private var program = 0
    private var uRes = 0
    private var uTime = 0
    private var uTop = 0
    private var uBottom = 0
    private var uWave = 0
    private var uBright = 0
    private var width = 1
    private var height = 1

    private var time = 0.0
    private var lastFrameNs = 0L
    private var daylight = daylight(Calendar.getInstance())
    private var daylightCheckedAt = 0L
    private val stats = if (BuildConfig.PERF_LOG) FrameStats(framePeriodNs) else null

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = link(VERTEX, FRAGMENT)
        uRes = GLES30.glGetUniformLocation(program, "uRes")
        uTime = GLES30.glGetUniformLocation(program, "uTime")
        uTop = GLES30.glGetUniformLocation(program, "uTop")
        uBottom = GLES30.glGetUniformLocation(program, "uBottom")
        uWave = GLES30.glGetUniformLocation(program, "uWave")
        uBright = GLES30.glGetUniformLocation(program, "uBright")
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
        if (lastFrameNs != 0L) {
            stats?.record(now - lastFrameNs)
            // Snap to whole vsync periods; cap long gaps (resume, hitch) to a few frames.
            val frames = ((now - lastFrameNs).toDouble() / framePeriodNs).roundToLong().coerceIn(1, 4)
            time += frames * framePeriodNs / 1e9 * p.speed
        }
        lastFrameNs = now

        if (now - daylightCheckedAt > 60_000_000_000L) {
            daylight = daylight(Calendar.getInstance())
            daylightCheckedAt = now
        }

        GLES30.glUseProgram(program)
        GLES30.glUniform2f(uRes, width.toFloat(), height.toFloat())
        // Wrap time so float precision never degrades on a launcher that runs for weeks.
        GLES30.glUniform1f(uTime, (time % 10_000.0).toFloat())
        GLES30.glUniform3fv(uTop, 1, p.theme.top.rgb(), 0)
        GLES30.glUniform3fv(uBottom, 1, p.theme.bottom.rgb(), 0)
        GLES30.glUniform3fv(uWave, 1, p.theme.wave.rgb(), 0)
        GLES30.glUniform1f(uBright, 0.8f + 0.2f * daylight)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    }

    /**
     * Debug-only frame pacing probe for the GL thread (gfxinfo only covers the UI thread).
     * Logs every ~5 s under the "WaveFrames" tag.
     */
    private class FrameStats(private val periodNs: Long) {
        private val intervals = LongArray(300)
        private var n = 0

        fun record(intervalNs: Long) {
            intervals[n++] = intervalNs
            if (n < intervals.size) return
            n = 0
            val sorted = intervals.sorted()
            val p50 = sorted[sorted.size / 2] / 1e6
            val p95 = sorted[sorted.size * 95 / 100] / 1e6
            val max = sorted.last() / 1e6
            val hitches = sorted.count { it > periodNs * 3 / 2 }
            Log.i(
                "WaveFrames",
                "gl frames=${sorted.size} p50=%.1fms p95=%.1fms max=%.1fms missed=$hitches (period %.1fms)"
                    .format(p50, p95, max, periodNs / 1e6),
            )
        }
    }

    /** 0 at midnight → 1 at noon. */
    private fun daylight(cal: Calendar): Float {
        val hour = cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60f
        return ((1 - cos(hour / 24f * 2 * PI)) / 2).toFloat()
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

    companion object {
        /** Render-target scale: keep the long side near 1280 px. */
        fun renderScale(widthPx: Int, heightPx: Int): Float =
            (1280f / max(widthPx, heightPx)).coerceIn(0.4f, 1f)

        // Full-screen triangle generated from gl_VertexID: no vertex buffers needed.
        private const val VERTEX = """#version 300 es
void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
"""

        private const val FRAGMENT = """#version 300 es
precision highp float;
uniform vec2 uRes;
uniform float uTime;
uniform vec3 uTop;
uniform vec3 uBottom;
uniform vec3 uWave;
uniform float uBright;
out vec4 fragColor;

// Low-frequency curve: two long swells plus a slow ripple. All speeds are small so motion
// reads as drifting light rather than oscillation.
float curve(float x, float t, float base, float amp, float phase) {
    return base
        + amp * 0.62 * sin(x * 0.85 + t * 0.21 + phase)
        + amp * 0.28 * sin(x * 1.70 - t * 0.33 + phase * 1.9)
        + amp * 0.10 * sin(x * 3.10 + t * 0.47 + phase * 0.7);
}

// One glossy sheet: a bright anti-aliased crest, a soft halo, and a translucent body below the
// crest that fades downward.
vec3 sheet(vec2 uv, float x, float t, float base, float amp, float phase, float strength) {
    float c = curve(x, t, base, amp, phase);
    float d = uv.y - c;                         // > 0 above the crest
    float aa = max(fwidth(d), 1e-4);
    float crest = 1.0 - smoothstep(0.0, aa * 2.5 + 0.0025, abs(d));
    float halo = exp(-(d * d) / 0.0022);
    float body = smoothstep(aa, -0.03, d) * exp(d / 0.16);
    return uWave * strength * (crest * 0.32 + halo * 0.10 + body * 0.075);
}

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

void main() {
    vec2 uv = gl_FragCoord.xy / uRes;           // y = 0 at the bottom
    float x = uv.x * (uRes.x / uRes.y);
    float t = uTime;

    // Base: dark at the top, brightest toward the lower middle, as in the reference themes.
    float g = smoothstep(0.0, 1.0, uv.y);
    vec3 col = mix(uBottom, uTop, g);
    col += uBottom * 0.18 * exp(-pow((uv.y - 0.32) * 2.6, 2.0));

    // Broad glossy sheets.
    col += sheet(uv, x, t,        0.50, 0.10, 0.0, 1.00);
    col += sheet(uv, x, t * 0.8,  0.44, 0.08, 2.1, 0.70);
    col += sheet(uv, x, t * 1.15, 0.36, 0.12, 4.3, 0.55);
    col += sheet(uv, x, t * 0.6,  0.58, 0.06, 5.7, 0.35);

    // A handful of fine strands riding the main sheet.
    for (int i = 0; i < 5; i++) {
        float fi = float(i);
        float c = curve(x, t * (0.95 + fi * 0.03), 0.50 + (fi - 2.0) * 0.012, 0.10, fi * 0.18);
        float d = abs(uv.y - c);
        float aa = max(fwidth(uv.y - c), 1e-4);
        col += uWave * 0.10 * (1.0 - smoothstep(0.0, aa * 1.5, d));
    }

    col *= uBright;
    // Gentle vignette.
    vec2 v = uv - vec2(0.5, 0.45);
    col *= 1.0 - dot(v, v) * 0.45;
    // Dither to hide 8-bit banding in the long gradients.
    col += (hash(gl_FragCoord.xy + fract(t)) - 0.5) / 255.0;
    fragColor = vec4(col, 1.0);
}
"""
    }
}
