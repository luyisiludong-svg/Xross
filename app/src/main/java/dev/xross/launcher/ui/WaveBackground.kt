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
 */
@Composable
fun WaveBackground(
    enabled: Boolean,
    params: WaveParams,
    paused: Boolean = false,
    idle: Boolean = false,
    /** Still-motion profile (E-Ink): one frame per change of [params], no render loop at all. */
    frozen: Boolean = false,
    shaders: dev.xross.launcher.theme.shader.ShaderLibrary = dev.xross.launcher.theme.shader.ShaderLibrary.DEFAULT,
    health: dev.xross.launcher.theme.shader.ShaderHealth? = null,
    /** Music levels for `uAudioBass` / `uAudioBeat` (2b.4b); null binds 0. */
    audio: dev.xross.launcher.media.energy.AudioLevels? = null,
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
    val renderer = remember { WaveRenderer(refreshRateHz = refreshHz, assets = context.assets, health = health, audio = audio) }
    // The library before the params that may name one of its programs.
    renderer.library = shaders
    renderer.params = params
    renderer.frozen = frozen

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
                val w = r - l
                val h = b - t
                if (w > 0 && h > 0) {
                    val scale = WaveRenderer.renderScale(w, h)
                    (v as GLSurfaceView).holder.setFixedSize((w * scale).roundToInt(), (h * scale).roundToInt())
                }
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }
    val pacer = remember { VsyncPacer(view, refreshHz) }
    var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
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
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            pacer.stop()
            view.onPause()
            renderer.quiesce()
        }
    }
    // Ahead-of-time compile of the pack's programs (2b.4a), on the GL thread between frames.
    LaunchedEffect(view, shaders) {
        view.queueEvent { renderer.prepare() }
    }
    // Covered: stop drawing frames (the GL thread sleeps) but keep the surface. Otherwise run at
    // full rate when that is already at or under the ceiling, else paced to every n-th vsync.
    val stride = if (idle) WaveRenderer.idleStride(refreshHz) else WaveRenderer.activeStride(refreshHz)
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
