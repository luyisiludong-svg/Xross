package dev.xross.launcher.platform

import android.os.Build

/**
 * Whether this device can run AGSL ([android.graphics.RuntimeShader], Android 13 / API 33 and
 * later). The UI filter ([dev.xross.launcher.ui.UiFilterSupport]) and the Settings rows that offer
 * it both ask here, so the state layer never reaches into `ui/` for an SDK check.
 */
object AgslSupport {
    const val MIN_SDK = Build.VERSION_CODES.TIRAMISU

    fun supported(sdk: Int = Build.VERSION.SDK_INT): Boolean = sdk >= MIN_SDK
}
