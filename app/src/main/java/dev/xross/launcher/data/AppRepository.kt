package dev.xross.launcher.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

data class AppEntry(
    val packageName: String,
    val activityName: String,
    val label: String,
    val isGame: Boolean,
) {
    val component: ComponentName get() = ComponentName(packageName, activityName)
}

/** Installed launchable Android apps, plus game/app triage. */
class AppRepository(private val context: Context) {
    private val pm: PackageManager = context.packageManager
    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps.asStateFlow()

    private val iconCache = LruCache<String, ImageBitmap>(128)

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        _apps.value = pm.queryIntentActivities(launcher, 0)
            .asSequence()
            .filter { it.activityInfo.packageName != context.packageName }
            .map { info ->
                val appInfo = info.activityInfo.applicationInfo
                AppEntry(
                    packageName = info.activityInfo.packageName,
                    activityName = info.activityInfo.name,
                    label = info.loadLabel(pm).toString(),
                    isGame = appInfo.isGame(),
                )
            }
            .distinctBy { it.packageName to it.activityName }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    fun isInstalled(packageName: String): Boolean =
        runCatching { pm.getPackageInfo(packageName, 0) }.isSuccess

    suspend fun icon(packageName: String, sizePx: Int): ImageBitmap? {
        iconCache.get(packageName)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                pm.getApplicationIcon(packageName).toBitmap(sizePx, sizePx).asImageBitmap()
            }.getOrNull()?.also { iconCache.put(packageName, it) }
        }
    }

    fun launchIntent(app: AppEntry): Intent =
        Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(app.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

    @Suppress("DEPRECATION")
    private fun ApplicationInfo.isGame(): Boolean =
        category == ApplicationInfo.CATEGORY_GAME || flags and ApplicationInfo.FLAG_IS_GAME != 0
}
