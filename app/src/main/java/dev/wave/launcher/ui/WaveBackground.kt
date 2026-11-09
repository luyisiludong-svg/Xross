package dev.wave.launcher.ui

import android.app.ActivityManager
import android.content.Context
import android.opengl.GLSurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

private val FallbackGradient = Brush.verticalGradient(listOf(Color(0xFF0A1A4A), Color(0xFF050818)))

/**
 * GL wave background. The render loop is paused whenever the activity is paused (screen off,
 * emulator in front), so it costs nothing while a game runs.
 */
@Composable
fun WaveBackground(enabled: Boolean, speed: Float, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val supported = remember { supportsGles3(context) }
    if (!enabled || !supported) {
        Box(modifier.fillMaxSize().background(FallbackGradient))
        return
    }

    val renderer = remember { WaveRenderer() }
    renderer.params = renderer.params.copy(speed = speed)

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = remember {
        GLSurfaceView(context).apply {
            setEGLContextClientVersion(3)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
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
