package dev.xross.launcher

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
import dev.xross.launcher.input.GamepadHandler
import dev.xross.launcher.input.NavAction
import dev.xross.launcher.state.MediaOverlay
import dev.xross.launcher.state.UiEffect
import dev.xross.launcher.storage.VolumeMonitor
import dev.xross.launcher.state.XmbViewModel
import dev.xross.launcher.theme.XrossTheme
import dev.xross.launcher.theme.rememberThemePalette
import dev.xross.launcher.ui.WaveBackground
import dev.xross.launcher.ui.XmbScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val container get() = (application as XrossApplication).container

    private val viewModel: XmbViewModel by viewModels {
        viewModelFactory {
            initializer {
                val app = application as XrossApplication
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

    /** Storage hot-swap: microSD and USB-OTG mounts/removals. */
    private val volumeMonitor = VolumeMonitor { viewModel.onVolumeEvent(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()

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

        volumeMonitor.register(this)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.effects.collect(::handleEffect)
            }
        }

        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            Box(Modifier.fillMaxSize()) {
                // The wave is hidden (and its GL loop stopped) under full-screen photos and video.
                val fullscreenMedia = state.overlay is MediaOverlay.Photos || state.overlay is MediaOverlay.VideoPlayback
                WaveBackground(
                    enabled = state.waveEnabled && !fullscreenMedia,
                    speed = state.waveSpeed,
                    palette = rememberThemePalette(XrossTheme.byName(state.theme)),
                )
                XmbScreen(
                    state = state,
                    loadAppIcon = { pkg -> container.apps.icon(pkg, ICON_PX) },
                    loadThumb = { kind, id, px -> container.media.thumbnail(kind, id, px) },
                    media = viewModel.media,
                    music = container.music,
                    onAction = viewModel::onAction,
                    onCategoryTap = viewModel::onCategoryTapped,
                    onItemTap = viewModel::onItemTapped,
                    onCancelRemap = viewModel::cancelRemap,
                    onItemLongPress = viewModel::onItemLongPressed,
                    onOptionTap = viewModel::onOptionTapped,
                    onCloseOptions = viewModel::closeOptions,
                    onSearchKeyTap = viewModel::onSearchKeyTapped,
                    onSearchResultTap = viewModel::onSearchResultTapped,
                    onInfoRowTap = viewModel::onInfoRowTapped,
                    onCloseInfo = viewModel::closeInfo,
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

    /** Other apps (emulators, the wallpaper cropper) can bring the system bars back; hide them again. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onPause() {
        gamepad.release()
        viewModel.onBackground()
        super.onPause()
    }

    override fun onDestroy() {
        unregisterReceiver(packageReceiver)
        volumeMonitor.unregister(this)
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
            is UiEffect.SetWallpaper -> setWallpaper(effect.uri)
        }
    }

    /** System crop-and-set screen (it doubles as confirmation); falls back to "Set picture as". */
    private fun setWallpaper(uri: Uri) {
        val crop = runCatching {
            android.app.WallpaperManager.getInstance(this).getCropAndSetWallpaperIntent(uri)
        }.getOrNull()
        if (crop != null && safeStart(crop.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))) return
        val attach = Intent(Intent.ACTION_ATTACH_DATA)
            .setDataAndType(uri, "image/*")
            .putExtra("mimeType", "image/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        safeStart(Intent.createChooser(attach, "Set as wallpaper"))
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
