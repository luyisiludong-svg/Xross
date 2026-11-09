package dev.wave.launcher

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.wave.launcher.input.GamepadHandler
import dev.wave.launcher.input.NavAction
import dev.wave.launcher.state.UiEffect
import dev.wave.launcher.state.XmbViewModel
import dev.wave.launcher.theme.WaveTheme
import dev.wave.launcher.ui.WaveBackground
import dev.wave.launcher.ui.XmbScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val container get() = (application as WaveApplication).container

    private val viewModel: XmbViewModel by viewModels {
        viewModelFactory {
            initializer {
                val app = application as WaveApplication
                val coldBoot = app.coldBootPending
                app.coldBootPending = false
                XmbViewModel(applicationContext, container, coldBoot)
            }
        }
    }

    private lateinit var gamepad: GamepadHandler

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
        viewModel.onFolderPicked(it)
    }

    private val legacyStoragePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            viewModel.refreshEnvironment()
        }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = viewModel.onAppsChanged()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        gamepad = GamepadHandler(lifecycleScope, viewModel) { viewModel.inputConfig }

        // System back (gesture / nav bar). Gamepad back buttons arrive via dispatchKeyEvent.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = viewModel.onAction(NavAction.BACK)
        })

        ContextCompat.registerReceiver(
            this,
            packageReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addDataScheme("package")
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.effects.collect(::handleEffect)
            }
        }

        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            Box(Modifier.fillMaxSize()) {
                WaveBackground(
                    enabled = state.waveEnabled,
                    speed = state.waveSpeed,
                    theme = WaveTheme.byName(state.theme),
                )
                XmbScreen(
                    state = state,
                    loadAppIcon = { pkg -> container.apps.icon(pkg, ICON_PX) },
                    onAction = viewModel::onAction,
                    onCategoryTap = viewModel::onCategoryTapped,
                    onItemTap = viewModel::onItemTapped,
                    onCancelRemap = viewModel::cancelRemap,
                )
            }
        }

        // All key input is routed through dispatchKeyEvent, so no view needs focus. Without this,
        // the first D-pad press after a touch is swallowed by ViewRootImpl to "leave touch mode"
        // by focusing the Compose view, and the user's input is lost.
        (window.decorView as ViewGroup).descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
    }

    override fun onResume() {
        super.onResume()
        // Picks up permission grants, installed emulators, and newly mounted drives, and resumes
        // background fingerprinting.
        viewModel.onForeground()
    }

    override fun onPause() {
        gamepad.release()
        viewModel.onBackground()
        super.onPause()
    }

    override fun onDestroy() {
        unregisterReceiver(packageReceiver)
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        gamepad.onKeyEvent(event) || super.dispatchKeyEvent(event)

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        gamepad.onMotionEvent(event) || super.dispatchGenericMotionEvent(event)

    private fun handleEffect(effect: UiEffect) {
        when (effect) {
            is UiEffect.StartActivity -> safeStart(effect.intent)
            UiEffect.PickFolder -> runCatching { pickFolder.launch(null) }
            UiEffect.RequestStorageAccess -> requestStorageAccess()
        }
    }

    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val perApp = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName"),
            )
            if (!safeStart(perApp)) safeStart(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        } else {
            legacyStoragePermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun safeStart(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private companion object {
        const val ICON_PX = 144
    }
}
