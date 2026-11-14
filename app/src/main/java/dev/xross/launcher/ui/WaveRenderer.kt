package dev.xross.launcher.ui

import android.content.res.AssetManager
import android.opengl.EGL14
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.util.Log
import dev.xross.launcher.BuildConfig
import dev.xross.launcher.media.energy.AudioLevels
import dev.xross.launcher.theme.ThemePalette
import dev.xross.launcher.theme.XrossTheme
import dev.xross.launcher.theme.pack.BackgroundFilter
import dev.xross.launcher.theme.rgb
import dev.xross.launcher.theme.shader.FrameBudgetWatchdog
import dev.xross.launcher.theme.shader.ProgramState
import dev.xross.launcher.theme.shader.ShaderCanary
import dev.xross.launcher.theme.shader.ShaderFallback
import dev.xross.launcher.theme.shader.ShaderHealth
import dev.xross.launcher.theme.shader.ShaderLibrary
import dev.xross.launcher.theme.shader.ShaderPreset
import dev.xross.launcher.theme.shader.ShaderSpec
import dev.xross.launcher.theme.shader.ShaderStatus
import java.util.Calendar
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
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
    /** The program wanted ([ShaderLibrary.keyOf] of the look's shader); WAVE until it is ready. */
    val shader: String = ShaderLibrary.WAVE_KEY,
    /** Open category id: a change kicks `uTransition` (STARFIELD's warp). */
    val category: String? = null,
    /** `uBatteryLevel`, 0–1 (1 where unknown). */
    val battery: Float = 1f,
    /** The GL-side post-process over the background program (2b.4f, `effects.backgroundFilter`). */
    val filter: BackgroundFilter = BackgroundFilter.NONE,
    /** The cold-start shader intro (2b.5d) while it plays: `uTransition` follows its ramp instead of category switches. */
    val intro: WaveIntro? = null,
) {
    // Converted once per parameter change, not per frame, so the GL loop allocates nothing.
    internal val topRgb = palette.top.rgb()
    internal val bottomRgb = palette.bottom.rgb()
    internal val waveRgb = palette.wave.rgb()
    internal val glowRgb = palette.glow.rgb()
}

/**
 * The cold-start shader intro (2b.5d, [BootMode.SHADER]) as the renderer sees it: it ramps
 * `uTransition` from 0 to 1 over [durationMs] from [startedAtNanos] (`SystemClock.elapsedRealtimeNanos`),
 * or until [endedAtNanos] when it was skipped; from there `uTransition` eases back to 0 as after a
 * category switch, so STARFIELD's warp builds through the intro and settles as the menu appears.
 */
data class WaveIntro(val startedAtNanos: Long, val durationMs: Long, val endedAtNanos: Long = 0L) {
    /** The ramp at [nowNanos]: 0 → 1, eased in. */
    fun level(nowNanos: Long): Float {
        val t = ((nowNanos - startedAtNanos) / (durationMs * 1e6)).toFloat().coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** True once the ramp is over: it reached 1, or the intro was skipped. */
    fun over(nowNanos: Long): Boolean =
        nowNanos - startedAtNanos >= durationMs * 1_000_000L || (endedAtNanos > 0L && nowNanos >= endedAtNanos)
}

/**
 * Full-screen GLSL ES 3.0 background host (Theme 2.0, 2b.4a). One oversized triangle; all work
 * happens in a fragment program: the built-in [ShaderPreset.WAVE] (compiled first, never freed,
 * the fallback for everything), the other presets, or a pack's own `shader.frag`. Sources live in
 * `assets/shaders/` behind `prelude.glsl`, which declares the public uniforms.
 *
 * Programs:
 *  - Every program the active pack can show ([library]) is compiled ahead of time on the GL
 *    thread, through [GLSurfaceView.queueEvent], when the pack loads ([prepare]). With
 *    `GL_KHR_parallel_shader_compile` the link finishes in the driver's threads and is polled per
 *    frame. The program on screen stays until the wanted one is confirmed linked
 *    ([ShaderFallback]); a new one is drawn once into a 1×1 viewport first, so a category switch
 *    neither hitches nor shows a gap.
 *  - Compile and link errors never throw: the program is FAILED, the wave is drawn, the driver's
 *    first error line is logged and [ShaderHealth] posts the toast.
 *  - Frame-budget watchdog: a non-WAVE program's GPU time is measured for its first ~2 s on
 *    screen (`GL_EXT_disjoint_timer_query`, else a `glFinish` sample every third frame); a p90
 *    over 60% of the active frame budget retires it for the session ([FrameBudgetWatchdog]).
 *  - Crash-loop guard: a pack's own program is armed in the [ShaderCanary] while it compiles and
 *    until it has drawn [ShaderCanary.PROVEN_FRAMES] frames in a row.
 *
 * Background filter (2b.4f): [WaveParams.filter] draws `screen_filter.frag` as a second
 * full-screen pass multiplied into the frame (scanlines, a CRT aperture grille, an LCD grid). The
 * GL surface sits under the Compose UI, so this is the one place a filter can reach the
 * background; it never touches the menu. Its pitches are whole render pixels from [pxPerDp]. It
 * is compiled with the wave when the context is created and skipped over the CRT preset, which
 * draws its own lines.
 *
 * Audio (2b.4b): `uAudioBass` / `uAudioBeat` are read from [audio] once per frame, as heard at
 * the frame's time (latency-compensated, see [dev.xross.launcher.media.energy.MusicEnergy]); 0
 * with no music, paused, or in a still-motion profile.
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
    /** Where the built-in sources come from; null draws a plain gradient fill (previews, tests). */
    private val assets: AssetManager? = null,
    private val health: ShaderHealth? = null,
    /** The music player's levels; null binds 0 (previews, tests). */
    private val audio: AudioLevels? = null,
) : GLSurfaceView.Renderer {
    private val framePeriodNs = (1e9 / refreshRateHz.coerceIn(30f, 240f)).roundToLong()

    /** The watchdog's budget: one frame at the in-use rate (16.7 ms at 60 Hz, and at 120 Hz paced to 60). */
    private val activeBudgetNs = framePeriodNs * activeStride(refreshRateHz)

    /** Vsyncs per frame, set by [WaveBackground]'s pacer. */
    @Volatile var stride = 1

    /**
     * Still-motion profiles (E-Ink): every frame drawn shows the same moment and the target colors
     * at once, so the one frame drawn per change ([WaveBackground]) is final, with no drift.
     */
    @Volatile var frozen = false

    /**
     * Render pixels per dp: the display density times the surface's render scale ([renderScale]),
     * set by [WaveBackground] on layout. Bound as `uPxPerDp`, so CRT and the filter pass size their
     * lines in dp but snap them to whole pixels.
     */
    @Volatile var pxPerDp = 1f

    /** The programs the active pack can show; set from the main thread, compiled by [prepare]. */
    @Volatile var library: ShaderLibrary = ShaderLibrary.DEFAULT

    private val canary: ShaderCanary? get() = health?.canary

    /** This frame's `uAudioBass`, `uAudioBeat`. */
    private val audioLevels = FloatArray(2)
    private val audioStats = if (BuildConfig.PERF_LOG) AudioStats() else null

    /** [shownWave] scaled by the charging pulse, uploaded instead of it. */
    private val pulsedWave = FloatArray(3)

    // The colors on screen, eased toward params' so a theme variant, category or system override
    // crossfades (~300 ms) instead of popping. GL thread only; the first frame snaps.
    private val shownTop = FloatArray(3)
    private val shownBottom = FloatArray(3)
    private val shownWave = FloatArray(3)
    private val shownGlow = FloatArray(3)
    private var colorsShown = false

    // region GL thread: programs of the current context

    private var vertex = 0
    private var prelude: String? = null
    private val bodies = HashMap<ShaderPreset, String?>()
    private val programs = HashMap<String, GlProgram>()
    private var synced: ShaderLibrary? = null
    private var active: GlProgram? = null
    private var parallel = false
    private var timer: GpuTimer? = null
    private var finishSample = 0
    private var lastReport: ShaderStatus? = null
    private val filterPass = ScreenFilterPass()
    private var loggedFilter: BackgroundFilter? = null

    // Survive a context loss: a verdict is about the program's text on this GPU, not its handle.
    private val verdicts = HashMap<String, FrameBudgetWatchdog.Verdict>()
    private val slowReasons = HashMap<String, String>()
    private val proven = HashSet<String>()
    private var canaryKey: String? = null
    private var canaryFrames = 0

    // endregion

    private var width = 1
    private var height = 1

    private var time = 0.0
    private var lastFrameNs = 0L
    private var lastCategory: String? = null
    private var transition = 0f
    /** The intro whose ramp has run ([WaveIntro.startedAtNanos]), so a finished one isn't replayed. */
    private var introDone = 0L
    private var travel = 0.0
    private var daylight = daylight(Calendar.getInstance())
    private var daylightCheckedAt = 0L
    private val stats = if (BuildConfig.PERF_LOG) FrameStats(framePeriodNs) else null

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // A new context: every handle of the last one is gone. A pack's program that was drawing
        // (armed, no pause in between) when the context died most likely reset the GPU.
        canaryKey?.let { key ->
            if (canaryFrames > 0) {
                Log.w(TAG, "GL context lost while drawing $key; blocking it")
                canary?.block(key)
            } else {
                canary?.disarm(key)
            }
        }
        canaryKey = null
        canaryFrames = 0
        programs.clear()
        active = null
        synced = null
        lastReport = null
        val extensions = GLES30.glGetString(GLES30.GL_EXTENSIONS).orEmpty()
        parallel = "GL_KHR_parallel_shader_compile" in extensions
        timer = GpuTimer(extensions)
        vertex = compileVertex()
        if (vertex != 0) start(ShaderLibrary.WAVE_SPEC, blocking = true)
        // The filter pass is tiny; compiled with the wave so picking one never waits on the driver.
        if (vertex != 0) filterPass.compile(vertex, asset(FILTER_FILE))
        loggedFilter = null
        sync()
        lastFrameNs = 0L
        colorsShown = false
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w
        height = h
        GLES30.glViewport(0, 0, w, h)
    }

    /**
     * GL thread, through [GLSurfaceView.queueEvent]: compiles what [library] adds (ahead of time),
     * and frees what it dropped once off screen. Without a current context (paused) it waits for
     * [onSurfaceCreated], which compiles the whole library anyway.
     */
    fun prepare() {
        if (vertex == 0 || EGL14.eglGetCurrentContext() == EGL14.EGL_NO_CONTEXT) return
        sync()
    }

    /**
     * The wave stopped drawing (covered, paused, disposed): a pack program on screen isn't in the
     * middle of anything, so its canary comes down. GL thread, or the main thread once
     * [GLSurfaceView.onPause] has returned.
     */
    fun quiesce() {
        canaryKey?.let { canary?.disarm(it) }
        canaryKey = null
        canaryFrames = 0
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = SystemClock.elapsedRealtimeNanos()
        val p = params
        val still = frozen
        val dt = if (lastFrameNs == 0L) 0f else (now - lastFrameNs) / 1e9f
        val k = if (still || !colorsShown || lastFrameNs == 0L) 1f else colorStep(dt)
        easeColor(shownTop, p.topRgb, k)
        easeColor(shownBottom, p.bottomRgb, k)
        easeColor(shownWave, p.waveRgb, k)
        easeColor(shownGlow, p.glowRgb, k)
        colorsShown = true
        if (lastFrameNs != 0L && !still) {
            stats?.record(now - lastFrameNs, stride)
            // Snap to whole vsync periods; cap long gaps (resume, hitch) to a couple of paced frames.
            val maxFrames = max(4L, 2L * stride)
            val frames = ((now - lastFrameNs).toDouble() / framePeriodNs).roundToLong().coerceIn(1, maxFrames)
            time += frames * framePeriodNs / 1e9 * p.speed
        }
        lastFrameNs = now

        // Category switch: a warp impulse that eases out; STARFIELD flies forward through it.
        if (p.category != lastCategory) {
            if (lastCategory != null && !still) transition = 1f
            lastCategory = p.category
        }
        if (still) {
            transition = 0f
        } else if (dt > 0f) {
            val step = dt.coerceAtMost(0.1f)
            travel = (travel + step * transition * WARP_TRAVEL) % 1000.0
            transition *= exp(-step / TRANSITION_S)
        }
        // Cold-start shader intro (2b.5d): the ramp drives uTransition until it ends.
        val intro = p.intro
        if (intro != null && intro.startedAtNanos != introDone && !still) {
            transition = intro.level(now)
            if (intro.over(now)) {
                introDone = intro.startedAtNanos
                lastCategory = p.category
                if (BuildConfig.PERF_LOG) {
                    Log.i("XrossBoot", "uTransition=%.2f at=%d ms".format(transition, (now - intro.startedAtNanos) / 1_000_000))
                }
            }
        }

        val source = audio
        if (still || source == null) {
            audioLevels[0] = 0f
            audioLevels[1] = 0f
        } else {
            source.read(now, audioLevels)
        }
        audioStats?.record(audioLevels, now, source)

        if (now - daylightCheckedAt > 60_000_000_000L) {
            daylight = daylight(Calendar.getInstance())
            daylightCheckedAt = now
        }

        val program = try {
            if (library !== synced) sync()
            poll()
            select(p.shader)
        } catch (e: RuntimeException) {
            Log.e(TAG, "shader host", e)
            programs[ShaderLibrary.WAVE_KEY]?.takeIf { it.state == ProgramState.READY }
        }
        if (program == null) {
            // Not even the wave linked (a broken driver or APK): the theme gradient's average.
            GLES30.glClearColor((shownTop[0] + shownBottom[0]) / 2, (shownTop[1] + shownBottom[1]) / 2, (shownTop[2] + shownBottom[2]) / 2, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            return
        }
        draw(program, p, still, now)
        drawFilter(program, p.filter)
    }

    /** The background filter over [program]'s frame; none over CRT, which has its own lines. */
    private fun drawFilter(program: GlProgram, filter: BackgroundFilter) {
        val shown = if (program.spec.preset == ShaderPreset.CRT) BackgroundFilter.NONE else filter
        if (BuildConfig.PERF_LOG && shown != loggedFilter) {
            loggedFilter = shown
            val ppd = pxPerDp
            Log.i(
                "XrossFilter",
                "background=$shown program=${program.spec.key} pxPerDp=%.2f linePx=%d stripePx=%d cellPx=%d ready=%s"
                    .format(ppd, ScreenFilterPass.linePitchPx(ppd), ScreenFilterPass.stripePx(ppd), ScreenFilterPass.cellPx(ppd), filterPass.ready),
            )
        }
        if (shown == BackgroundFilter.NONE) return
        filterPass.draw(shown, width, height, pxPerDp)
    }

    // region Programs

    private fun compileVertex(): Int {
        val src = asset(VERTEX_FILE) ?: return 0
        val vs = GLES30.glCreateShader(GLES30.GL_VERTEX_SHADER)
        if (vs == 0) return 0
        GLES30.glShaderSource(vs, src)
        GLES30.glCompileShader(vs)
        val status = IntArray(1)
        GLES30.glGetShaderiv(vs, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "vertex shader: ${GLES30.glGetShaderInfoLog(vs)}")
            GLES30.glDeleteShader(vs)
            return 0
        }
        return vs
    }

    private fun asset(name: String): String? = assets?.let { a ->
        runCatching { a.open("$ASSET_DIR/$name").bufferedReader().use { it.readText() } }.getOrNull()
    }

    private fun body(preset: ShaderPreset): String? = bodies.getOrPut(preset) { preset.file?.let(::asset) }

    /** Compiles [library]'s new programs and frees the ones it no longer has (unless on screen). */
    private fun sync() {
        if (vertex == 0) return
        val lib = library
        for (spec in lib.specs) if (spec.key !in programs) start(spec, blocking = false)
        val keep = lib.keys
        val stale = programs.keys.filter { it !in keep && it != ShaderLibrary.WAVE_KEY && it != active?.spec?.key }
        for (key in stale) programs.remove(key)?.release()
        synced = lib
    }

    private fun start(spec: ShaderSpec, blocking: Boolean) {
        val program = GlProgram(spec)
        programs[spec.key] = program
        if (spec.untrusted && canary?.isBlocked(spec.key) == true) {
            program.fail(ShaderStatus.Cause.CRASHED, "blocked after a crash")
            return
        }
        if (verdicts[spec.key] == FrameBudgetWatchdog.Verdict.TRIPPED) {
            program.fail(ShaderStatus.Cause.TOO_SLOW, slowReasons[spec.key].orEmpty())
            return
        }
        val body = if (spec.untrusted) spec.source else body(spec.preset)
        val pre = prelude ?: asset(PRELUDE_FILE)?.also { prelude = it }
        when {
            body == null -> program.fail(ShaderStatus.Cause.MISSING, if (spec.untrusted) "can't read ${spec.label}" else "missing from the app")
            pre == null -> program.fail(ShaderStatus.Cause.MISSING, "the shader prelude is missing from the app")
            else -> {
                if (spec.untrusted) canary?.arm(spec.key) // a driver that crashes compiling it
                program.begin(vertex, pre, body)
                if (blocking || !parallel || program.state != ProgramState.PENDING) settle(program)
            }
        }
    }

    private fun settle(program: GlProgram) {
        val log = program.settle()
        if (program.spec.untrusted) canary?.disarm(program.spec.key)
        if (log != null) {
            // The driver's own line, verbatim, then the whole log for context.
            val raw = log.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("ERROR", true) } ?: log.lineSequence().firstOrNull().orEmpty()
            Log.e(TAG, "${program.spec.label} ${program.cause?.name?.lowercase()} failed: $raw")
            Log.w(TAG, log.trim())
        } else if (BuildConfig.PERF_LOG && program.state == ProgramState.READY) {
            Log.i(TAG, "linked ${program.spec.key}")
        }
    }

    /** Settles programs the driver finished linking in the background. */
    private fun poll() {
        if (!parallel) return
        for (program in programs.values) {
            if (program.state == ProgramState.PENDING && program.complete(parallel)) settle(program)
        }
    }

    /** The program to draw for [desired], reporting the status of [desired] when it changes. */
    private fun select(desired: String): GlProgram? {
        // A key not prepared yet (params can name the new pack's program a frame before its
        // library arrives) is reported as compiling; the program on screen stays meanwhile.
        val wanted = programs[desired]
        val status = ShaderFallback.status(wanted?.spec ?: library.spec(desired), desired, wanted?.state, wanted?.error.orEmpty(), wanted?.cause)
        if (status != lastReport) {
            lastReport = status
            health?.report(status)
            if (BuildConfig.PERF_LOG) {
                val kind = when (status) {
                    is ShaderStatus.Ready -> "Ready"
                    is ShaderStatus.Compiling -> "Compiling"
                    is ShaderStatus.Failed -> "Failed ${status.cause}"
                }
                Log.i(TAG, "status $kind ${status.key}")
            }
        }
        val key = ShaderFallback.choose(desired, active?.spec?.key) { programs[it]?.state }
        val next = programs[key]?.takeIf { it.state == ProgramState.READY }
        if (next !== active) switchTo(next)
        return next
    }

    private fun switchTo(next: GlProgram?) {
        val prev = active
        if (prev != null && canaryKey == prev.spec.key) quiesce()
        timer?.reset()
        finishSample = 0
        active = next
        if (prev != null && prev !== next) {
            val gone = prev.spec.key !in (synced?.keys ?: emptySet()) && prev.spec.key != ShaderLibrary.WAVE_KEY
            if (gone) programs.remove(prev.spec.key)
            // A dropped or retired program's handle isn't needed any more (its entry keeps the verdict).
            if (gone || prev.state == ProgramState.FAILED) prev.release()
        }
    }

    private fun draw(program: GlProgram, p: WaveParams, still: Boolean, now: Long) {
        val spec = program.spec
        if (spec.untrusted && spec.key !in proven && canaryKey != spec.key) {
            canary?.arm(spec.key)
            canaryKey = spec.key
            canaryFrames = 0
        }
        if (!program.warmed) {
            GLES30.glViewport(0, 0, 1, 1)
            drawCall(program, p, still)
            GLES30.glViewport(0, 0, width, height)
            program.warmed = true
        }
        val measuring = !still && spec.key != ShaderLibrary.WAVE_KEY && spec.key !in verdicts
        if (!measuring) {
            drawCall(program, p, still)
        } else {
            val dog = program.watchdog ?: FrameBudgetWatchdog(activeBudgetNs).also { program.watchdog = it }
            val t = timer
            if (t != null && t.supported) {
                t.collect { ns -> judge(program, dog, ns, now) }
                val timing = program.state == ProgramState.READY && t.begin()
                drawCall(program, p, still)
                if (timing) t.end()
            } else if (finishSample++ % FINISH_SAMPLE_EVERY == 0) {
                GLES30.glFinish() // drain earlier work so only this draw is timed
                val t0 = System.nanoTime()
                drawCall(program, p, still)
                GLES30.glFinish()
                judge(program, dog, System.nanoTime() - t0, now)
            } else {
                drawCall(program, p, still)
            }
        }
        if (canaryKey == spec.key) {
            canaryFrames++
            // A still (E-Ink) frame is final: once drawn, nothing is in flight. Otherwise wait for a run.
            if (still) {
                quiesce()
            } else if (canaryFrames >= ShaderCanary.PROVEN_FRAMES) {
                quiesce()
                proven += spec.key
            }
        }
    }

    private fun judge(program: GlProgram, dog: FrameBudgetWatchdog, ns: Long, now: Long) {
        when (dog.record(ns, now)) {
            FrameBudgetWatchdog.Verdict.MEASURING -> Unit
            FrameBudgetWatchdog.Verdict.PASSED -> {
                verdicts[program.spec.key] = FrameBudgetWatchdog.Verdict.PASSED
                program.watchdog = null
                if (BuildConfig.PERF_LOG) Log.i(TAG, "watchdog passed ${program.spec.key}: p90 %.2f ms of %.2f ms (%s, %d samples)".format(dog.p90Ns / 1e6, dog.limitNs / 1e6, method(), dog.sampleCount))
            }
            FrameBudgetWatchdog.Verdict.TRIPPED -> {
                val why = dog.describe()
                verdicts[program.spec.key] = FrameBudgetWatchdog.Verdict.TRIPPED
                slowReasons[program.spec.key] = why
                program.watchdog = null
                program.fail(ShaderStatus.Cause.TOO_SLOW, why)
                Log.w(TAG, "watchdog tripped ${program.spec.key}: $why (${method()}, ${dog.sampleCount} samples)")
            }
        }
    }

    private fun method() = if (timer?.supported == true) "timer query" else "glFinish"

    private fun drawCall(program: GlProgram, p: WaveParams, still: Boolean) {
        GLES30.glUseProgram(program.handle)
        GLES30.glUniform2f(program.uResolution, width.toFloat(), height.toFloat())
        // Wrap time so float precision never degrades on a launcher that runs for weeks.
        GLES30.glUniform1f(program.uTime, (time % 10_000.0).toFloat())
        GLES30.glUniform4f(program.uColorTop, shownTop[0], shownTop[1], shownTop[2], 1f)
        GLES30.glUniform4f(program.uColorBottom, shownBottom[0], shownBottom[1], shownBottom[2], 1f)
        val wave = if (p.chargePulse && !still) {
            val beat = chargePulse(SystemClock.elapsedRealtimeNanos() / 1e9)
            for (i in 0..2) pulsedWave[i] = (shownWave[i] * beat).coerceAtMost(1f)
            pulsedWave
        } else {
            shownWave
        }
        GLES30.glUniform4f(program.uAccent, wave[0], wave[1], wave[2], 1f)
        GLES30.glUniform4f(program.uGlow, shownGlow[0], shownGlow[1], shownGlow[2], 1f)
        GLES30.glUniform1f(program.uBatteryLevel, p.battery.coerceIn(0f, 1f))
        GLES30.glUniform1f(program.uAudioBass, audioLevels[0])
        GLES30.glUniform1f(program.uAudioBeat, audioLevels[1])
        GLES30.glUniform1f(program.uTransition, if (still) 0f else transition)
        GLES30.glUniform1f(program.uTravel, travel.toFloat())
        GLES30.glUniform1f(program.uPxPerDp, pxPerDp)
        // WAVE's own.
        GLES30.glUniform1f(program.uBright, 0.8f + 0.2f * daylight)
        GLES30.glUniform1i(program.uLines, p.lineCount.coerceIn(0, MAX_LINES))
        GLES30.glUniform1f(program.uTurb, p.turbulence.coerceIn(0f, 2f))
        GLES30.glUniform1f(program.uThick, p.thickness.coerceIn(0.5f, 3f))
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    }

    // endregion

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

    /**
     * Debug and profiling builds: the audio uniforms over each ~2 s under "XrossAudio" (peak bass,
     * beats started, and how far the analysis ran ahead of the speaker: the latency compensated),
     * while they move and once more when they fall back to 0, for the device tests.
     */
    private class AudioStats {
        private var windowStart = 0L
        private var peak = 0f
        private var beats = 0
        private var lastBeat = false
        private var wasLive = false

        fun record(levels: FloatArray, now: Long, source: AudioLevels?) {
            if (windowStart == 0L) windowStart = now
            peak = max(peak, levels[0])
            val beat = levels[1] > 0.5f
            if (beat && !lastBeat) beats++
            lastBeat = beat
            if (now - windowStart < 2_000_000_000L) return
            val live = peak > 0f || beats > 0
            if (live || wasLive) {
                val lead = source?.leadUs(now)?.let { " lead=%d ms".format(it / 1000) }.orEmpty()
                Log.i("XrossAudio", "bass max=%.2f beats=%d%s".format(peak, beats, lead))
            }
            wasLive = live
            windowStart = now
            peak = 0f
            beats = 0
        }
    }

    /** 0 at midnight → 1 at noon. */
    private fun daylight(cal: Calendar): Float {
        val hour = cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60f
        return ((1 - cos(hour / 24f * 2 * PI)) / 2).toFloat()
    }

    companion object {
        private const val TAG = "XrossShader"

        /** Built-in sources in the APK: `prelude.glsl`, `fullscreen.vert` and one `.frag` per preset. */
        const val ASSET_DIR = "shaders"
        const val PRELUDE_FILE = "prelude.glsl"
        const val VERTEX_FILE = "fullscreen.vert"

        /** The background filter pass (2b.4f), with its own `#version` and uniforms. */
        const val FILTER_FILE = "screen_filter.frag"

        /** Without timer queries, every n-th frame of a program under watch is timed with glFinish. */
        private const val FINISH_SAMPLE_EVERY = 3

        /** Time constant of `uTransition`'s ease-out after a category switch. */
        const val TRANSITION_S = 0.35f

        /** `uTravel` gained per second at a full `uTransition` (a warp flies ~40% of a layer). */
        private const val WARP_TRAVEL = 1.2f

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
    }
}
