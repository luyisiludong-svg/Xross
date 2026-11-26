package dev.xross.launcher.ui

import android.app.ActivityManager
import android.content.Context
import android.opengl.GLSurfaceView
import android.view.Choreographer
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.roundToInt
import dev.xross.launcher.media.BackgroundVideo
import dev.xross.launcher.media.BackgroundVideoPolicy
import dev.xross.launcher.media.energy.AudioLevels
import dev.xross.launcher.theme.pack.VideoSource
import dev.xross.launcher.theme.shader.ShaderHealth
import dev.xross.launcher.theme.shader.ShaderLibrary
import dev.xross.launcher.theme.topColor
import dev.xross.launcher.theme.bottomColor

/**
 * GL wave background. The render loop stops whenever the activity is paused (screen off,
 * emulator in front), so it costs nothing while a game runs, and while [paused] is set: something
 * opaque (focused-game hero art, a video snap, a full-screen photo) covers the whole screen. A
 * pause keeps the GL surface and context (and so the compiled programs), so resuming is instant.
 * While [idle] (no input for a few seconds) it drifts on at a standby frame rate; see
 * [WaveRenderer] for the rates.
 *
 * [shaders] is every program the active pack can show; a new library is compiled ahead of time on
 * the GL thread ([WaveRenderer.prepare]) while the current program keeps drawing. [params]'
 * `shader` picks the one shown.
 *
 * [video] (Live backgrounds § 2) is the look's background video: a [BackgroundVideo] player
 * decodes it into a surface the renderer made, and programs read it as `uVideo`. It follows
 * [BackgroundVideoPolicy]: released while the activity is stopped (a game is in front), held on
 * its last frame while covered, resting, still or while [otherVideo] plays, else playing.
 */
@Composable
fun WaveBackground(
    enabled: Boolean,
    params: WaveParams,
    paused: Boolean = false,
    idle: Boolean = false,
    /** Still-motion profile (E-Ink): one frame per change of [params], no render loop at all. */
    frozen: Boolean = false,
    shaders: ShaderLibrary = ShaderLibrary.DEFAULT,
    health: ShaderHealth? = null,
    /** Music levels for `uAudioBass` / `uAudioBeat` (2b.4b); null binds 0. */
    audio: AudioLevels? = null,
    /** In use, draw at the screen's full refresh rate instead of pacing 90 Hz+ screens to ~60 fps. */
    fullFrameRate: Boolean = false,
    /** The look's background video (Live backgrounds § 2); null for none. */
    video: VideoSource? = null,
    /** A video snap or the boot video is playing: the background video holds its frame meanwhile. */
    otherVideo: Boolean = false,
    /**
     * The background video rests (holds its frame): Standby motion's timeout, but never sooner than
     * the particles' 10 s ([dev.xross.launcher.data.StandbyMotion.particleTimeoutMs]), so a loop
     * doesn't freeze 3 s after each press. Null follows [idle].
     */
    videoResting: Boolean? = null,
    /** The player can't play [video] (why): the caller falls back to the wallpaper, else the wave. */
    onVideoFailed: (VideoSource, String) -> Unit = { _, _ -> },
    /** Settings ▸ System ▸ Performance ▸ Tilt parallax (Live backgrounds § 3e): a scene's parallax follows the device's tilt. */
    tiltParallax: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val supported = remember { supportsGles3(context) }
    val palette = params.palette
    if (!enabled || !supported) {
        Box(modifier.fillMaxSize().background(Brush.verticalGradient(listOf(palette.topColor, palette.bottomColor))))
        return
    }

    @Suppress("DEPRECATION")
    val refreshHz = remember {
        (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.refreshRate
    }
    val onVideoFailedState = remember { mutableStateOf(onVideoFailed) }
    val renderer = remember {
        WaveRenderer(
            refreshRateHz = refreshHz,
            assets = context.assets,
            health = health,
            audio = audio,
            imageDecoder = ShaderImageLoader(context.assets),
        )
    }
    val bgVideo = remember {
        BackgroundVideo(
            context,
            onFailed = { v, why -> onVideoFailedState.value(v, why) },
            onNotice = { v, text -> health?.notice("video:${v.key}", "Background video ${v.file.name}: $text") },
            onSize = renderer::videoSize,
        )
    }
    onVideoFailedState.value = onVideoFailed
    // The library before the params that may name one of its programs.
    renderer.library = shaders
    renderer.params = params
    renderer.frozen = frozen
    // Live backgrounds § 3b: a sharp scene renders at 1920 on the long side, everything else at 1280.
    val longSide = WaveRenderer.longSide(shaders, params.shader)
    val surfaceSize = remember { SurfaceSizer(renderer) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = remember {
        GLSurfaceView(context).apply {
            setEGLContextClientVersion(3)
            // Keep the compiled programs across an activity pause (back from a game without a recompile).
            preserveEGLContextOnPause = true
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            // Render below native resolution; the compositor upscales for free.
            addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ ->
                surfaceSize.layout(v as GLSurfaceView, r - l, b - t)
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            // A still screen draws one frame per change: the one that uploads a shader's images too.
            renderer.onImagesDecoded = { if (renderer.frozen) requestRender() }
            // The background video's surface comes from the GL thread; the player lives on the main one.
            val main = android.os.Handler(android.os.Looper.getMainLooper())
            renderer.onVideoSurface = { surface -> main.post { bgVideo.setSurface(surface) } }
            renderer.onVideoFrame = { if (renderer.frozen) requestRender() }
        }
    }
    val pacer = remember { VsyncPacer(view, refreshHz) }
    androidx.compose.runtime.SideEffect { surfaceSize.longSide(view, longSide) }
    var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var started by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle, view) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    view.onResume()
                    resumed = true
                }
                Lifecycle.Event.ON_PAUSE -> {
                    resumed = false
                    view.onPause()
                    renderer.quiesce() // the GL thread is parked: nothing of a pack shader is in flight
                }
                // A game or app in front: the background video lets go of its decoder (§ 2).
                Lifecycle.Event.ON_START -> started = true
                Lifecycle.Event.ON_STOP -> started = false
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            pacer.stop()
            view.onPause()
            renderer.quiesce()
            bgVideo.release()
            bgVideo.setSurface(null)
            renderer.abandonVideo()
        }
    }
    // Live backgrounds § 2: the background video's player follows the policy.
    val videoAction = BackgroundVideoPolicy.action(
        hasVideo = video != null,
        started = started,
        still = frozen,
        // Paused (not resumed) stops the GL loop too: nothing would show the frames.
        covered = paused || !resumed,
        standby = videoResting ?: idle,
        otherVideo = otherVideo,
    )
    LaunchedEffect(bgVideo, video, videoAction) {
        bgVideo.apply(video, videoAction)
        if (dev.xross.launcher.BuildConfig.PERF_LOG) android.util.Log.i("XrossVideo", "policy ${videoAction.name.lowercase()} video=${video?.file?.name} " +
            "started=$started resumed=$resumed still=$frozen covered=$paused resting=${videoResting ?: idle} other=$otherVideo")
    }
    // Live backgrounds § 3e: the tilt sensor only while it can move something on screen.
    val tiltSensor = remember { TiltSensor(context, view, renderer) }
    val tiltWanted = tiltParallax && !frozen && !paused && resumed &&
        shaders.spec(params.shader)?.scene?.scene?.hasParallax == true
    DisposableEffect(tiltSensor, tiltWanted) {
        if (tiltWanted) tiltSensor.start()
        onDispose { tiltSensor.stop() }
    }
    // Ahead-of-time compile of the pack's programs (2b.4a), on the GL thread between frames.
    LaunchedEffect(view, shaders) {
        view.queueEvent { renderer.prepare() }
    }
    // Covered: stop drawing frames (the GL thread sleeps) but keep the surface. Otherwise run at
    // full rate when that is already at or under the ceiling, else paced to every n-th vsync.
    val stride = when {
        idle -> WaveRenderer.idleStride(refreshHz)
        fullFrameRate -> 1
        else -> WaveRenderer.activeStride(refreshHz)
    }
    LaunchedEffect(view, paused, stride, resumed, frozen) {
        renderer.stride = stride
        val loop = waveLoop(paused = paused, resumed = resumed, stride = stride, frozen = frozen)
        // Debug and profiling builds: the smoke test reads this to check E-Ink stops the loop.
        if (dev.xross.launcher.BuildConfig.PERF_LOG) android.util.Log.i("WaveLoop", "mode=$loop stride=$stride")
        when (loop) {
            WaveLoop.STOPPED -> {
                pacer.stop()
                view.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                view.queueEvent { renderer.quiesce() }
            }
            WaveLoop.SINGLE_FRAME -> {
                pacer.stop()
                view.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                view.requestRender()
            }
            WaveLoop.CONTINUOUS -> {
                pacer.stop()
                view.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            }
            WaveLoop.PACED -> {
                view.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                pacer.start(stride)
            }
        }
    }
    // Frozen: redraw the one still frame when the colors or shape change.
    LaunchedEffect(params, frozen) {
        if (frozen && !paused && resumed) view.requestRender()
    }
    AndroidView(factory = { view }, modifier = modifier.fillMaxSize())
}

/** How the wave's GL loop runs. */
enum class WaveLoop {
    /** No frames (covered by art or video, or the activity isn't resumed). */
    STOPPED,

    /** One frame on request, then nothing: still-motion profiles (0 fps, no ghosting on E-Ink). */
    SINGLE_FRAME,

    /** Every vsync. */
    CONTINUOUS,

    /** Every n-th vsync, through [VsyncPacer]. */
    PACED,
}

/** The loop for a wave [paused] (covered) or not, [resumed] or not, at [stride], [frozen] or not. */
fun waveLoop(paused: Boolean, resumed: Boolean, stride: Int, frozen: Boolean): WaveLoop = when {
    paused || !resumed -> WaveLoop.STOPPED
    frozen -> WaveLoop.SINGLE_FRAME
    stride <= 1 -> WaveLoop.CONTINUOUS
    else -> WaveLoop.PACED
}

/**
 * Asks for a frame every [start]'s `stride`-th vsync. Each callback is posted with a delay just
 * short of the frame period, so Choreographer runs it on the vsync it is due and the main thread
 * wakes once per wave frame, not once per vsync. A (re)start renders a frame at once, so leaving
 * the standby rate shows motion on the next vsync.
 */
private class VsyncPacer(private val view: GLSurfaceView, refreshHz: Float) : Choreographer.FrameCallback {
    private val choreographer = Choreographer.getInstance()
    private val vsyncMs = 1000.0 / refreshHz.coerceIn(30f, 240f)
    private var delayMs = 0L
    private var running = false

    fun start(stride: Int) {
        delayMs = (vsyncMs * (stride - 0.5)).toLong()
        choreographer.removeFrameCallback(this)
        running = true
        view.requestRender()
        choreographer.postFrameCallbackDelayed(this, delayMs)
    }

    fun stop() {
        running = false
        choreographer.removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        view.requestRender()
        choreographer.postFrameCallbackDelayed(this, delayMs)
    }
}

private fun supportsGles3(context: Context): Boolean {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    return am.deviceConfigurationInfo.reqGlEsVersion >= 0x30000
}

/**
 * Sizes the GL surface below native resolution (the compositor upscales for free): the long side at
 * [WaveRenderer.renderScale] of the view's, 1280 px, or 1920 for a sharp scene (Live backgrounds
 * § 3b). A change of either resizes the buffer once; the renderer treats it like any resize.
 */
private class SurfaceSizer(private val renderer: WaveRenderer) {
    private var width = 0
    private var height = 0
    private var side = WaveRenderer.SOFT_LONG_SIDE
    private var applied = 0L

    fun layout(view: GLSurfaceView, w: Int, h: Int) {
        width = w
        height = h
        apply(view)
    }

    fun longSide(view: GLSurfaceView, longSide: Int) {
        side = longSide
        apply(view)
    }

    private fun apply(view: GLSurfaceView) {
        if (width <= 0 || height <= 0) return
        val scale = WaveRenderer.renderScale(width, height, side)
        val rw = (width * scale).roundToInt()
        val rh = (height * scale).roundToInt()
        val key = (rw.toLong() shl 32) or rh.toLong()
        if (key == applied) return
        applied = key
        view.holder.setFixedSize(rw, rh)
        // Lines and stripes are sized in dp, snapped to the render target's pixels (2b.4f).
        renderer.pxPerDp = view.resources.displayMetrics.density * rw / width
    }
}
