package dev.xross.launcher.ui

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.util.Log
import dev.xross.launcher.BuildConfig
import dev.xross.launcher.theme.ThemePalette
import dev.xross.launcher.theme.XrossTheme
import dev.xross.launcher.theme.rgb
import java.util.Calendar
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Tunables for the background, read every frame so changes apply live (Settings ▸ Theme ▸
 * Wave settings previews them as the sliders move). Ranges: [dev.xross.launcher.theme.WaveLimits].
 */
data class WaveParams(
    val speed: Float = 1f,
    val palette: ThemePalette = XrossTheme.LAVENDER.palette(),
    /** Fine strands riding the main sheet, 0–12 (`uLines`). */
    val lineCount: Int = 5,
    /** Amplitude multiplier, 0–2 (`uTurb`). */
    val turbulence: Float = 1f,
    /** Crest, halo and strand width multiplier, 0.5–3 (`uThick`). */
    val thickness: Float = 1f,
    /** Battery tint while charging: the sheets' color breathes gently ([WaveRenderer.CHARGE_PULSE_S]). */
    val chargePulse: Boolean = false,
) {
    // Converted once per parameter change, not per frame, so the GL loop allocates nothing.
    internal val topRgb = palette.top.rgb()
    internal val bottomRgb = palette.bottom.rgb()
    internal val waveRgb = palette.wave.rgb()
}

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
 *
 * Frame rate: a slow ambient sheet gains nothing from 120 Hz, so on high-refresh panels frames are
 * paced to a whole fraction of the display rate near [ACTIVE_MAX_FPS] (120 → 60, 144 → 72;
 * 90 Hz and below run at full rate). While the screen rests it drops to about [IDLE_FPS].
 * [WaveBackground] paces frames on vsync; [stride] tells the renderer how many vsyncs a frame
 * spans, so a paced frame is not mistaken for a hitch.
 */
class WaveRenderer(
    @Volatile var params: WaveParams = WaveParams(),
    refreshRateHz: Float = 60f,
) : GLSurfaceView.Renderer {
    private val framePeriodNs = (1e9 / refreshRateHz.coerceIn(30f, 240f)).roundToLong()

    /** Vsyncs per frame, set by [WaveBackground]'s pacer. */
    @Volatile var stride = 1

    /**
     * Still-motion profiles (E-Ink): every frame drawn shows the same moment and the target colors
     * at once, so the one frame drawn per change ([WaveBackground]) is final, with no drift.
     */
    @Volatile var frozen = false

    /** [shownWave] scaled by the charging pulse, uploaded instead of it. */
    private val pulsedWave = FloatArray(3)

    // The colors on screen, eased toward params' so a theme variant, category or system override
    // crossfades (~300 ms) instead of popping. GL thread only; the first frame snaps.
    private val shownTop = FloatArray(3)
    private val shownBottom = FloatArray(3)
    private val shownWave = FloatArray(3)
    private var colorsShown = false

    private var program = 0
    private var uRes = 0
    private var uTime = 0
    private var uTop = 0
    private var uBottom = 0
    private var uWave = 0
    private var uBright = 0
    private var uLines = 0
    private var uTurb = 0
    private var uThick = 0
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
        uLines = GLES30.glGetUniformLocation(program, "uLines")
        uTurb = GLES30.glGetUniformLocation(program, "uTurb")
        uThick = GLES30.glGetUniformLocation(program, "uThick")
        lastFrameNs = 0L
        colorsShown = false
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w
        height = h
        GLES30.glViewport(0, 0, w, h)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = SystemClock.elapsedRealtimeNanos()
        val p = params
        val still = frozen
        val k = if (still || !colorsShown || lastFrameNs == 0L) 1f else colorStep((now - lastFrameNs) / 1e9f)
        easeColor(shownTop, p.topRgb, k)
        easeColor(shownBottom, p.bottomRgb, k)
        easeColor(shownWave, p.waveRgb, k)
        colorsShown = true
        if (lastFrameNs != 0L && !still) {
            stats?.record(now - lastFrameNs, stride)
            // Snap to whole vsync periods; cap long gaps (resume, hitch) to a couple of paced frames.
            val maxFrames = max(4L, 2L * stride)
            val frames = ((now - lastFrameNs).toDouble() / framePeriodNs).roundToLong().coerceIn(1, maxFrames)
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
        GLES30.glUniform3fv(uTop, 1, shownTop, 0)
        GLES30.glUniform3fv(uBottom, 1, shownBottom, 0)
        if (p.chargePulse && !still) {
            val beat = chargePulse(now / 1e9)
            for (i in 0..2) pulsedWave[i] = (shownWave[i] * beat).coerceAtMost(1f)
            GLES30.glUniform3fv(uWave, 1, pulsedWave, 0)
        } else {
            GLES30.glUniform3fv(uWave, 1, shownWave, 0)
        }
        GLES30.glUniform1f(uBright, 0.8f + 0.2f * daylight)
        GLES30.glUniform1i(uLines, p.lineCount.coerceIn(0, MAX_LINES))
        GLES30.glUniform1f(uTurb, p.turbulence.coerceIn(0f, 2f))
        GLES30.glUniform1f(uThick, p.thickness.coerceIn(0.5f, 3f))
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    }

    /**
     * Debug-only frame pacing probe for the GL thread (gfxinfo only covers the UI thread).
     * Logs every ~5 s under the "WaveFrames" tag.
     */
    private class FrameStats(private val periodNs: Long) {
        private val intervals = LongArray(300)
        private var n = 0

        private var stride = 1

        fun record(intervalNs: Long, stride: Int) {
            if (stride != this.stride) { // a new pace: start a fresh window
                this.stride = stride
                n = 0
            }
            intervals[n++] = intervalNs
            if (n < intervals.size) return
            n = 0
            val sorted = intervals.sorted()
            val p50 = sorted[sorted.size / 2] / 1e6
            val p95 = sorted[sorted.size * 95 / 100] / 1e6
            val max = sorted.last() / 1e6
            val expected = periodNs * stride
            val hitches = sorted.count { it > expected + periodNs / 2 }
            Log.i(
                "WaveFrames",
                "gl frames=${sorted.size} p50=%.1fms p95=%.1fms max=%.1fms missed=$hitches (period %.1fms)"
                    .format(p50, p95, max, expected / 1e6),
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
        /** Upper bound of `uLines`; the shader's strand loop is unrolled to this. */
        const val MAX_LINES = 12

        /** Frame-rate ceiling while in use; see the class comment. */
        const val ACTIVE_MAX_FPS = 60f

        /** Standby frame rate while the screen rests: the sheet still drifts, at a fraction of the cost. */
        const val IDLE_FPS = 20f

        /** Vsyncs per frame in use: 1 up to ~90 Hz, 2 at 120 Hz, 2 at 144 Hz (72 fps). */
        fun activeStride(refreshRateHz: Float): Int = max(1, floor(refreshRateHz / ACTIVE_MAX_FPS + 0.25f).toInt())

        /** Vsyncs per frame at rest: 3 at 60 Hz, 4 at 90 Hz, 6 at 120 Hz. */
        fun idleStride(refreshRateHz: Float): Int = max(1, (refreshRateHz / IDLE_FPS).roundToInt())

        /** Period of the charging pulse. */
        const val CHARGE_PULSE_S = 2.4

        /** Brightness factor of the sheets [seconds] into the charging pulse: 1.0–1.18. */
        fun chargePulse(seconds: Double): Float =
            (1.09 + 0.09 * kotlin.math.sin(seconds * 2 * PI / CHARGE_PULSE_S)).toFloat()

        /** Time constant of the color crossfade: ~95% of the way in 300 ms. */
        const val COLOR_FADE_S = 0.1f

        /** Fraction of the way to the target colors to move after [dtSeconds] (frame-rate independent). */
        fun colorStep(dtSeconds: Float): Float =
            if (dtSeconds <= 0f) 0f else (1f - kotlin.math.exp(-dtSeconds / COLOR_FADE_S)).coerceIn(0f, 1f)

        /** Moves [shown] [k] of the way to [target], snapping the last hair so it settles exactly. */
        fun easeColor(shown: FloatArray, target: FloatArray, k: Float) {
            for (i in 0..2) {
                val d = target[i] - shown[i]
                shown[i] = if (abs(d) < 1f / 512f) target[i] else shown[i] + d * k
            }
        }

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
uniform int uLines;      // strands riding the main sheet, 0-12
uniform float uTurb;     // amplitude multiplier, 0-2
uniform float uThick;    // crest/halo/strand width multiplier, 0.5-3
out vec4 fragColor;

// Low-frequency curve: two long swells plus a slow ripple. All speeds are small so motion
// reads as drifting light rather than oscillation.
float curve(float x, float t, float base, float amp, float phase) {
    amp *= uTurb;
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
    float crest = 1.0 - smoothstep(0.0, (aa * 2.5 + 0.0025) * uThick, abs(d));
    float halo = exp(-(d * d) / (0.0022 * uThick * uThick));
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

    // Fine strands riding the main sheet, centred on it whatever their number.
    float mid = (float(uLines) - 1.0) * 0.5;
    for (int i = 0; i < 12; i++) {
        if (i >= uLines) break;
        float fi = float(i);
        float c = curve(x, t * (0.95 + fi * 0.03), 0.50 + (fi - mid) * 0.012, 0.10, fi * 0.18);
        float d = abs(uv.y - c);
        float aa = max(fwidth(uv.y - c), 1e-4);
        col += uWave * 0.10 * (1.0 - smoothstep(0.0, aa * 1.5 * uThick, d));
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
