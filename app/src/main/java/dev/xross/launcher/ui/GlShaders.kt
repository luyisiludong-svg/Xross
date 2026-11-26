package dev.xross.launcher.ui

import android.opengl.GLES30
import dev.xross.launcher.theme.shader.FrameBudgetWatchdog
import dev.xross.launcher.theme.shader.ImageFilter
import dev.xross.launcher.theme.shader.ImageWrap
import dev.xross.launcher.theme.shader.ShaderImage
import dev.xross.launcher.theme.shader.ProgramState
import dev.xross.launcher.theme.shader.ShaderSpec
import dev.xross.launcher.theme.shader.ShaderSources
import dev.xross.launcher.theme.shader.ShaderStatus
import dev.xross.launcher.theme.pack.BackgroundFilter

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
    /** Feedback: last frame's picture, seconds since it, 1.0 when there is none. */
    var uPrev = -1; var uFrameDt = -1; var uFresh = -1

    /** Reads `uPrev`, so it is drawn through [FeedbackTargets]. */
    val usesFeedback: Boolean get() = FeedbackPolicy.wants(uPrev)

    /** Field pass: its own low-resolution first pass, and which pass is drawing. */
    var uField = -1; var uPass = -1

    /** The focused item's centre (Live backgrounds § 1). */
    var uFocus = -1
    /** `uImage0..3` and `uImageSize0..3`; -1 where unused. */
    val uImage = IntArray(ShaderImage.MAX) { -1 }
    val uImageSize = IntArray(ShaderImage.MAX) { -1 }

    /** The background video (Live backgrounds § 2): the copied frame, its size, where `videoFit` places it. */
    var uVideo = -1; var uVideoSize = -1; var uVideoRect = -1

    /** Reads any of the video uniforms, so the frame is bound for it. */
    val usesVideo: Boolean get() = uVideo >= 0 || uVideoSize >= 0 || uVideoRect >= 0

    /** Its `effects.shaderImages` on the GPU, once uploaded; null for a program without images. */
    var textures: ImageTextures? = null

    /**
     * Live backgrounds § 3b: a SCENE drawn layer by layer ([GlScene]) instead of as its poster. Set
     * when it starts: the scene has a layer the renderer draws, and the shared quad program linked.
     */
    var layered = false

    /** A layered scene's textures and draw; null until it is set up. */
    var scene: GlScene? = null

    /** Waiting for its images to be decoded and uploaded ([ImageTextures], or a layered scene's [GlScene]). */
    val awaitingImages: Boolean get() = if (layered) scene?.uploaded != true else spec.images.isNotEmpty() && textures == null

    /**
     * The state [ShaderFallback] sees: a linked program is still PENDING until its images are on
     * the GPU, so the one on screen stays and nothing flashes black.
     */
    val drawState: ProgramState get() = if (state == ProgramState.READY && awaitingImages) ProgramState.PENDING else state

    /** Reads `uField`, so each frame first draws its [FieldTarget]. */
    val usesField: Boolean get() = FieldPolicy.wants(uField)

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
        uPrev = loc("uPrev"); uFrameDt = loc("uFrameDt"); uFresh = loc("uFresh")
        uField = loc("uField"); uPass = loc("uPass")
        uFocus = loc("uFocus")
        uVideo = loc("uVideo"); uVideoSize = loc("uVideoSize"); uVideoRect = loc("uVideoRect")
        for (i in 0 until ShaderImage.MAX) {
            uImage[i] = loc("uImage$i")
            uImageSize[i] = loc("uImageSize$i")
        }
        state = ProgramState.READY
        return null
    }

    /** Frees its GL objects (the context must be current). */
    fun release() {
        if (fs != 0) GLES30.glDeleteShader(fs)
        if (handle != 0) GLES30.glDeleteProgram(handle)
        fs = 0
        handle = 0
        textures?.release()
        textures = null
        scene?.release()
        scene = null
    }

    companion object {
        /** `GL_KHR_parallel_shader_compile`. */
        const val GL_COMPLETION_STATUS_KHR = 0x91B1
    }
}

/**
 * One program's `effects.shaderImages` on the GPU (Live backgrounds § 1): up to four RGBA8
 * textures, bound to units [ShaderImage.FIRST_UNIT]–5 for each draw. Uploaded once from pixels
 * [ShaderImageLoader] decoded on IO (straight alpha, rows already bottom-up); the pixels aren't
 * kept, so a lost context decodes them again.
 */
internal class ImageTextures {
    private val tex = IntArray(ShaderImage.MAX)
    /** w, h, 1/w, 1/h per slot; zeros for an empty one (`uImageSizeN`). */
    val sizes = FloatArray(ShaderImage.MAX * 4)

    /** Uploads [slots] (null: an empty slot). False when the driver refused one (it stays empty). */
    fun upload(slots: List<DecodedImage?>): Boolean {
        var ok = true
        for ((i, img) in slots.withIndex()) {
            if (i >= ShaderImage.MAX || img == null) continue
            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
            GLES30.glGetError() // clear, so a refusal below is this upload's
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, img.width, img.height, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, img.pixels)
            if (GLES30.glGetError() != GLES30.GL_NO_ERROR) {
                android.util.Log.e("XrossShader", "shader image ${img.image.path} refused at ${img.width}x${img.height}")
                GLES30.glDeleteTextures(1, ids, 0)
                ok = false
                continue
            }
            val wrap = wrapOf(img.image.wrap)
            val mag = if (img.image.filter == ImageFilter.NEAREST) GLES30.GL_NEAREST else GLES30.GL_LINEAR
            val min = when {
                !img.image.mipmap -> mag
                img.image.filter == ImageFilter.NEAREST -> GLES30.GL_NEAREST_MIPMAP_NEAREST
                else -> GLES30.GL_LINEAR_MIPMAP_LINEAR
            }
            if (img.image.mipmap) GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, min)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, mag)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, wrap)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, wrap)
            tex[i] = ids[0]
            sizes[i * 4] = img.width.toFloat()
            sizes[i * 4 + 1] = img.height.toFloat()
            sizes[i * 4 + 2] = 1f / img.width
            sizes[i * 4 + 3] = 1f / img.height
        }
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        return ok
    }

    /** Puts every slot on its unit (an empty one unbinds, so it samples black), then unit 0 is active again. */
    fun bind() {
        for (i in 0 until ShaderImage.MAX) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + ShaderImage.FIRST_UNIT + i)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[i])
        }
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    fun release() {
        for (i in tex.indices) if (tex[i] != 0) GLES30.glDeleteTextures(1, tex, i)
        tex.fill(0)
        sizes.fill(0f)
    }

    private fun wrapOf(w: ImageWrap): Int = when (w) {
        ImageWrap.CLAMP -> GLES30.GL_CLAMP_TO_EDGE
        ImageWrap.REPEAT -> GLES30.GL_REPEAT
        ImageWrap.MIRROR -> GLES30.GL_MIRRORED_REPEAT
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

    fun draw(filter: BackgroundFilter, width: Int, height: Int, pxPerDp: Float, strength: Float = 1f) {
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
        fun mode(filter: BackgroundFilter): Int = when (filter) {
            BackgroundFilter.NONE -> 0
            BackgroundFilter.SCANLINES -> 1
            BackgroundFilter.CRT -> 2
            BackgroundFilter.LCD_GRID -> 3
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

/**
 * Last frame's picture for programs that read `uPrev` (ping-pong feedback): two RGBA8 textures at
 * the surface size that take turns. Each frame the program draws into [target] while sampling
 * [source], then [present] blits it to the screen and the roles swap. Allocated only while such a
 * program is on screen (~2 × 4 bytes per render pixel), freed when it leaves.
 */
internal class FeedbackTargets {
    private val tex = IntArray(2)
    private val fbo = IntArray(2)
    private var cur = 0
    var width = 0
        private set
    var height = 0
        private set

    val allocated: Boolean get() = fbo[0] != 0

    /** (Re)allocates at [w]×[h], cleared to black; true when it did (the history is gone). */
    fun ensure(w: Int, h: Int): Boolean {
        if (allocated && w == width && h == height) return false
        release()
        GLES30.glGenTextures(2, tex, 0)
        GLES30.glGenFramebuffers(2, fbo, 0)
        for (i in 0..1) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[i])
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[i])
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex[i], 0)
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        val complete = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        if (!complete) {
            android.util.Log.e("XrossShader", "feedback framebuffer incomplete at ${w}x$h; drawing without it")
            release()
            return false
        }
        width = w
        height = h
        clear()
        return true
    }

    /** Both pictures to black: the next frame starts without history. */
    fun clear() {
        if (!allocated) return
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        for (i in 0..1) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[i])
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    /** Binds this frame's render target and puts last frame's picture on texture unit 0. */
    fun bind() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[cur])
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[1 - cur])
    }

    /** Copies this frame to the screen (framebuffer 0, left bound) and swaps the roles. */
    fun present() {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0) // never sample the texture being written next frame
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, fbo[cur])
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0)
        GLES30.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        cur = 1 - cur
    }

    /** Frees the textures (the context must be current). */
    fun release() {
        if (fbo[0] != 0) GLES30.glDeleteFramebuffers(2, fbo, 0)
        if (tex[0] != 0) GLES30.glDeleteTextures(2, tex, 0)
        forget()
    }

    /** The context died with its handles: drop them without GL calls. */
    fun forget() {
        fbo.fill(0)
        tex.fill(0)
        width = 0
        height = 0
        cur = 0
    }
}

/**
 * When feedback history may be trusted (pure, so the rules are unit tested). A program opts in by
 * using `uniform sampler2D uPrev` (the driver gives an unused uniform no location).
 */
internal object FeedbackPolicy {
    /** A gap longer than this (paused, covered, a hitch) means last frame's picture is stale. */
    const val MAX_GAP_NS = 500_000_000L

    /** `uFrameDt`'s ceiling, so a long frame never fades a trail to nothing in one step. */
    const val MAX_DT_S = 0.1f

    fun wants(uPrevLocation: Int): Boolean = uPrevLocation >= 0

    /**
     * Start without history: a different program from the last feedback frame, new targets (a
     * resize or a lost context), or too long since the last feedback frame.
     */
    fun fresh(lastKey: String?, key: String, reallocated: Boolean, lastNs: Long, nowNs: Long): Boolean =
        reallocated || lastKey != key || lastNs == 0L || nowNs - lastNs > MAX_GAP_NS

    /** `uFrameDt`: seconds since the last frame, clamped to (0, [MAX_DT_S]]; 1/60 for the first. */
    fun frameDt(dtSeconds: Float): Float = if (dtSeconds <= 0f) 1f / 60f else dtSeconds.coerceAtMost(MAX_DT_S)
}

/**
 * A program's own low-resolution first pass (`uniform sampler2D uField`): one RGBA8 texture at
 * [FieldPolicy.size] of the surface, drawn with `uPass` = 0, then sampled (unit 1; linear, so
 * texture() blends texels while texelFetch reads one exactly) by
 * the full-size pass with `uPass` = 1. For work that is the same across a block of pixels, such
 * as one light ray per character cell. Allocated only while such a program shows.
 */
internal class FieldTarget {
    private val tex = IntArray(1)
    private val fbo = IntArray(1)
    var width = 0
        private set
    var height = 0
        private set

    val allocated: Boolean get() = fbo[0] != 0

    /** Allocates for a [w]×[h] surface; false when the driver refused it. */
    fun ensure(w: Int, h: Int): Boolean {
        val fw = FieldPolicy.size(w)
        val fh = FieldPolicy.size(h)
        if (allocated && fw == width && fh == height) return true
        release()
        GLES30.glGenTextures(1, tex, 0)
        GLES30.glGenFramebuffers(1, fbo, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0])
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, fw, fh, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        // Linear: texture() blends neighbouring texels (cheap blurs); texelFetch still reads one exactly.
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex[0], 0)
        val complete = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        if (!complete) {
            android.util.Log.e("XrossShader", "field framebuffer incomplete at ${fw}x$fh")
            release()
            return false
        }
        width = fw
        height = fh
        return true
    }

    /** Renders into the field: binds its framebuffer and viewport (the texture must not be bound). */
    fun bindForPass() {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
        GLES30.glViewport(0, 0, width, height)
    }

    /** Puts the field on texture unit 1 for the full-size pass. */
    fun bindForSampling() {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0])
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    fun release() {
        if (fbo[0] != 0) GLES30.glDeleteFramebuffers(1, fbo, 0)
        if (tex[0] != 0) GLES30.glDeleteTextures(1, tex, 0)
        forget()
    }

    fun forget() {
        fbo[0] = 0
        tex[0] = 0
        width = 0
        height = 0
    }
}

/** The field pass's contract (pure, unit tested). */
internal object FieldPolicy {
    /** Surface pixels per field texel along each axis. */
    const val DIVISOR = 4

    fun wants(uFieldLocation: Int): Boolean = uFieldLocation >= 0

    /** Field texels along an axis of [px] surface pixels: ceil(px / [DIVISOR]), at least 1. */
    fun size(px: Int): Int = maxOf(1, (px + DIVISOR - 1) / DIVISOR)
}
