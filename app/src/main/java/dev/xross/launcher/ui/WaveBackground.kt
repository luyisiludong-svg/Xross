package dev.xross.launcher.ui

import android.app.ActivityManager
import android.content.Context
import android.opengl.GLSurfaceView
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.xross.launcher.theme.ThemePalette
import kotlin.math.roundToInt

/**
 * GL wave background. The render loop is paused whenever the activity is paused (screen off,
 * emulator in front), so it costs nothing while a game runs.
 */
@Composable
fun WaveBackground(enabled: Boolean, speed: Float, palette: ThemePalette, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val supported = remember { supportsGles3(context) }
    if (!enabled || !supported) {
        Box(modifier.fillMaxSize().background(Brush.verticalGradient(listOf(palette.topColor, palette.bottomColor))))
        return
    }

    val renderer = remember {
        @Suppress("DEPRECATION")
        val hz = (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager)
            .defaultDisplay.refreshRate
        WaveRenderer(refreshRateHz = hz)
    }
    val params = remember(speed, palette) { WaveParams(speed = speed, palette = palette) }
    renderer.params = params

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = remember {
        GLSurfaceView(context).apply {
            setEGLContextClientVersion(3)
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
    DisposableEffect(lifecycle, view) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            view.onPause()
        }
    }
    AndroidView(factory = { view }, modifier = modifier.fillMaxSize())
}

private fun supportsGles3(context: Context): Boolean {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    return am.deviceConfigurationInfo.reqGlEsVersion >= 0x30000
}
