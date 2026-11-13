package dev.xross.launcher.ui

import android.opengl.GLES30
import dev.xross.launcher.theme.shader.FrameBudgetWatchdog
import dev.xross.launcher.theme.shader.ProgramState
import dev.xross.launcher.theme.shader.ShaderSpec
import dev.xross.launcher.theme.shader.ShaderSources
import dev.xross.launcher.theme.shader.ShaderStatus

/**
 * One background program on the GL thread (2b.4a). [begin] hands the source to the driver and
 * returns at once; [settle] reads the compile and link results. With `GL_KHR_parallel_shader_compile`
 * the renderer polls [complete] each frame and settles only when the driver is done, so linking
 * never blocks a frame; without it the renderer settles right away, inside the `queueEvent` that
 * prepared the pack (ahead of time, not on a category switch).
 *
 * Nothing here throws: every failure ends as [ProgramState.FAILED] with the driver's message.
 */
internal class GlProgram(val spec: ShaderSpec) {
    var state = ProgramState.PENDING
        private set
    var error = ""
        private set
    var cause: ShaderStatus.Cause? = null
        private set

    var handle = 0
        private set
    private var fs = 0
    private var lineOffset = 0

    /** Drawn once into a 1×1 viewport before its first real frame, so the driver's first-use work isn't a visible hitch. */
    var warmed = false

    /** The watchdog measuring it, while it does. */
    var watchdog: FrameBudgetWatchdog? = null

    // Public uniforms (prelude.glsl) and the built-ins' private ones; -1 where a program doesn't use one.
    var uTime = -1; var uResolution = -1; var uColorTop = -1; var uColorBottom = -1
    var uAccent = -1; var uGlow = -1; var uBatteryLevel = -1; var uAudioBass = -1
    var uAudioBeat = -1; var uTransition = -1; var uTravel = -1
    var uBright = -1; var uLines = -1; var uTurb = -1; var uThick = -1
    /** CRT's own: render pixels per dp (2b.4f). */
    var uPxPerDp = -1

    fun fail(cause: ShaderStatus.Cause, error: String) {
        this.cause = cause
        this.error = error
        state = ProgramState.FAILED
    }

    /** Starts compiling [prelude] + [body] (or [body] alone when it has its own `#version`) with [vertex]. */
    fun begin(vertex: Int, prelude: String, body: String) {
        val composed = ShaderSources.compose(prelude, body)
        lineOffset = composed.lineOffset
        fs = GLES30.glCreateShader(GLES30.GL_FRAGMENT_SHADER)
        val program = GLES30.glCreateProgram()
        if (fs == 0 || program == 0) {
            if (fs != 0) GLES30.glDeleteShader(fs)
            if (program != 0) GLES30.glDeleteProgram(program)
            fs = 0
            fail(ShaderStatus.Cause.COMPILE, "the driver couldn't create a shader (GL error ${GLES30.glGetError()})")
            return
        }
        handle = program
        GLES30.glShaderSource(fs, composed.text)
        GLES30.glCompileShader(fs)
        GLES30.glAttachShader(program, vertex)
        GLES30.glAttachShader(program, fs)
        GLES30.glLinkProgram(program)
    }

    /** The driver finished compiling and linking (always true without the parallel-compile extension). */
    fun complete(parallel: Boolean): Boolean {
        if (state != ProgramState.PENDING || handle == 0) return true
        if (!parallel) return true
        val done = IntArray(1)
        GLES30.glGetProgramiv(handle, GL_COMPLETION_STATUS_KHR, done, 0)
        return done[0] != 0
    }

    /** Reads the results: READY with its uniform locations, or FAILED with the driver's first error line. */
    fun settle(): String? {
        if (state != ProgramState.PENDING) return null
        val status = IntArray(1)
        GLES30.glGetShaderiv(fs, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(fs)
            release()
            fail(ShaderStatus.Cause.COMPILE, ShaderSources.firstError(log, lineOffset))
            return log
        }
        GLES30.glGetProgramiv(handle, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(handle)
            release()
            fail(ShaderStatus.Cause.LINK, ShaderSources.firstError(log, lineOffset))
            return log
        }
        GLES30.glDetachShader(handle, fs)
        GLES30.glDeleteShader(fs)
        fs = 0
        fun loc(name: String) = GLES30.glGetUniformLocation(handle, name)
        uTime = loc("uTime"); uResolution = loc("uResolution"); uColorTop = loc("uColorTop")
        uColorBottom = loc("uColorBottom"); uAccent = loc("uAccent"); uGlow = loc("uGlow")
        uBatteryLevel = loc("uBatteryLevel"); uAudioBass = loc("uAudioBass"); uAudioBeat = loc("uAudioBeat")
        uTransition = loc("uTransition"); uTravel = loc("uTravel")
        uBright = loc("uBright"); uLines = loc("uLines"); uTurb = loc("uTurb"); uThick = loc("uThick")
        uPxPerDp = loc("uPxPerDp")
        state = ProgramState.READY
        return null
    }

    /** Frees its GL objects (the context must be current). */
    fun release() {
        if (fs != 0) GLES30.glDeleteShader(fs)
        if (handle != 0) GLES30.glDeleteProgram(handle)
        fs = 0
        handle = 0
    }

    companion object {
        /** `GL_KHR_parallel_shader_compile`. */
        const val GL_COMPLETION_STATUS_KHR = 0x91B1
    }
}

/**
 * The background filter (2b.4f): `screen_filter.frag` drawn over the frame with a multiplying
 * blend, so it only ever darkens what the background program drew. Compiled once per context;
 * if it fails the frame is drawn without it (logged, no toast: it is built in).
 */
internal class ScreenFilterPass {
    private var handle = 0
    private var uResolution = -1
    private var uPxPerDp = -1
    private var uMode = -1
    private var uStrength = -1

    val ready: Boolean get() = handle != 0

    /** Compiles [source] (null: missing from the APK) against [vertex]; the last context's handle is gone already. */
    fun compile(vertex: Int, source: String?) {
        handle = 0
        if (source == null) {
            android.util.Log.e("XrossShader", "screen_filter.frag is missing from the app")
            return
        }
        val fs = GLES30.glCreateShader(GLES30.GL_FRAGMENT_SHADER)
        val program = GLES30.glCreateProgram()
        if (fs == 0 || program == 0) {
            if (fs != 0) GLES30.glDeleteShader(fs)
            if (program != 0) GLES30.glDeleteProgram(program)
            return
        }
        GLES30.glShaderSource(fs, source)
        GLES30.glCompileShader(fs)
        GLES30.glAttachShader(program, vertex)
        GLES30.glAttachShader(program, fs)
        GLES30.glLinkProgram(program)
        val status = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        GLES30.glDetachShader(program, fs)
        GLES30.glDeleteShader(fs)
        if (status[0] == 0) {
            android.util.Log.e("XrossShader", "screen filter: ${GLES30.glGetProgramInfoLog(program).trim()}")
            GLES30.glDeleteProgram(program)
            return
        }
        handle = program
        uResolution = GLES30.glGetUniformLocation(program, "uResolution")
        uPxPerDp = GLES30.glGetUniformLocation(program, "uPxPerDp")
        uMode = GLES30.glGetUniformLocation(program, "uMode")
        uStrength = GLES30.glGetUniformLocation(program, "uStrength")
    }

    fun draw(filter: dev.xross.launcher.theme.pack.BackgroundFilter, width: Int, height: Int, pxPerDp: Float, strength: Float = 1f) {
        if (handle == 0) return
        val mode = mode(filter)
        if (mode == 0) return
        GLES30.glUseProgram(handle)
        GLES30.glUniform2f(uResolution, width.toFloat(), height.toFloat())
        GLES30.glUniform1f(uPxPerDp, pxPerDp)
        GLES30.glUniform1i(uMode, mode)
        GLES30.glUniform1f(uStrength, strength.coerceIn(0f, 1f))
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_DST_COLOR, GLES30.GL_ZERO)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    companion object {
        /** `uMode` of [filter]: 0 none, 1 scanlines, 2 CRT, 3 LCD grid (as in the shader). */
        fun mode(filter: dev.xross.launcher.theme.pack.BackgroundFilter): Int = when (filter) {
            dev.xross.launcher.theme.pack.BackgroundFilter.NONE -> 0
            dev.xross.launcher.theme.pack.BackgroundFilter.SCANLINES -> 1
            dev.xross.launcher.theme.pack.BackgroundFilter.CRT -> 2
            dev.xross.launcher.theme.pack.BackgroundFilter.LCD_GRID -> 3
        }

        /** The shader's `pitch(dp, minPx)`: [dp] in whole render pixels, at least [minPx]. */
        fun pitchPx(dp: Float, pxPerDp: Float, minPx: Int): Int = maxOf(minPx, kotlin.math.floor(dp * pxPerDp + 0.5f).toInt())

        /** Scanline pitch: 1.5 dp, at least 2 px. */
        fun linePitchPx(pxPerDp: Float) = pitchPx(1.5f, pxPerDp, 2)

        /** Aperture-grille stripe: 0.5 dp, at least 1 px. */
        fun stripePx(pxPerDp: Float) = pitchPx(0.5f, pxPerDp, 1)

        /** LCD cell: 3 dp, at least 3 px. */
        fun cellPx(pxPerDp: Float) = pitchPx(3f, pxPerDp, 3)
    }
}

/**
 * GPU time of single draws through `GL_EXT_disjoint_timer_query`: a few queries in flight, read
 * back a frame or two later when the driver has them. Results from a disjoint period (the GPU
 * changed clocks or was preempted) are dropped.
 */
internal class GpuTimer(extensions: String) {
    var supported = "GL_EXT_disjoint_timer_query" in extensions
        private set
    private val ids = IntArray(SLOTS)
    private val busy = BooleanArray(SLOTS)
    private var next = 0
    private var created = false
    private var open = -1
    private val tmp = IntArray(1)

    /** Starts timing; false when every query is still in flight (that frame isn't measured). */
    fun begin(): Boolean {
        if (!supported) return false
        if (!created) {
            GLES30.glGenQueries(SLOTS, ids, 0)
            created = true
        }
        if (busy[next]) return false
        GLES30.glGetError() // clear, so a refusal below is ours
        GLES30.glBeginQuery(GL_TIME_ELAPSED_EXT, ids[next])
        if (GLES30.glGetError() != GLES30.GL_NO_ERROR) {
            supported = false // advertised but refused: use glFinish sampling instead
            return false
        }
        open = next
        return true
    }

    fun end() {
        if (open < 0) return
        GLES30.glEndQuery(GL_TIME_ELAPSED_EXT)
        busy[open] = true
        next = (open + 1) % SLOTS
        open = -1
    }

    /** Hands each finished measurement (ns) to [sink], oldest first. */
    fun collect(sink: (Long) -> Unit) {
        if (!created) return
        GLES30.glGetIntegerv(GL_GPU_DISJOINT_EXT, tmp, 0)
        val disjoint = tmp[0] != 0
        for (k in 0 until SLOTS) {
            val i = (next + k) % SLOTS
            if (!busy[i]) continue
            GLES30.glGetQueryObjectuiv(ids[i], GLES30.GL_QUERY_RESULT_AVAILABLE, tmp, 0)
            if (tmp[0] == 0) break
            GLES30.glGetQueryObjectuiv(ids[i], GLES30.GL_QUERY_RESULT, tmp, 0)
            busy[i] = false
            if (!disjoint) sink(tmp[0].toLong() and 0xFFFFFFFFL)
        }
    }

    /** Drops queries in flight (a program switch: their results belong to the last one). */
    fun reset() {
        busy.fill(false)
        open = -1
    }

    private companion object {
        const val SLOTS = 4
        const val GL_TIME_ELAPSED_EXT = 0x88BF
        const val GL_GPU_DISJOINT_EXT = 0x8FBB
    }
}
