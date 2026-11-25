package dev.xross.launcher

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build

/**
 * How Xross's last process ended, for the shader crash guard ([dev.xross.launcher.theme.shader.ShaderCanary]):
 * a pack shader armed when the process died is only blocked if it may have crashed it. A
 * force-stop, an app update or a memory kill isn't the shader's fault.
 */
object ProcessExits {
    /** The file times and Android's exit times are both wall clock; allow for coarse file times. */
    private const val SLACK_MS = 2_000L

    /**
     * Whether the main process's last death, at or after [since] (wall clock), was a crash; null
     * when Android can't say (before Android 11, or no record that new).
     */
    fun wasCrash(context: Context, since: Long): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val am = context.getSystemService(ActivityManager::class.java) ?: return null
        val exit = runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, 8) }.getOrNull()
            ?.firstOrNull { it.processName == context.packageName }
            ?: return null
        if (since > 0 && exit.timestamp < since - SLACK_MS) return null // an older death, not this one
        return crashed(exit.reason)
    }

    /**
     * Anything not known to be harmless counts as a crash: a GPU reset can show as a native crash,
     * a signal, an ANR or an unknown reason.
     */
    fun crashed(reason: Int): Boolean = reason !in HARMLESS

    private val HARMLESS = setOf(
        ApplicationExitInfo.REASON_EXIT_SELF,
        ApplicationExitInfo.REASON_LOW_MEMORY,
        ApplicationExitInfo.REASON_PERMISSION_CHANGE,
        ApplicationExitInfo.REASON_USER_REQUESTED, // force-stop, adb install on older Android
        ApplicationExitInfo.REASON_USER_STOPPED,
        14, // REASON_FREEZER (Android 13)
        15, // REASON_PACKAGE_STATE_CHANGE (Android 13)
        16, // REASON_PACKAGE_UPDATED (Android 13)
    )
}
