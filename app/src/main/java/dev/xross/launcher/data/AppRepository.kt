package dev.xross.launcher.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
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
    /** Preinstalled with the system (including updated system apps); Settings ▸ Apps can hide these. */
    val isSystem: Boolean = false,
) {
    val component: ComponentName get() = ComponentName(packageName, activityName)
}

/**
 * The key of one rendered app icon (2b.4d): the same app in another mask, filter, palette or
 * size is another bitmap.
 */
data class IconKey(val packageName: String, val style: IconStyle, val sizePx: Int)

/**
 * Installed launchable Android apps, plus game/app triage, and their icons drawn in the theme's
 * mask and filter ([IconStyler]). [loadIcon] fetches an app's drawable (the package manager's by
 * default; tests swap it).
 */
class AppRepository(
    private val context: Context,
    private val loadIcon: (String) -> Drawable? = { pkg -> context.packageManager.getApplicationIcon(pkg) },
) {
    private val pm: PackageManager = context.packageManager
    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps.asStateFlow()

    /**
     * Rendered icons by [IconKey], sized in KiB: scrolling the Apps list hits this on the main
     * thread and never redraws an icon it has drawn in that style.
     */
    private val iconCache = object : LruCache<IconKey, ImageBitmap>(ICON_CACHE_KIB) {
        override fun sizeOf(key: IconKey, value: ImageBitmap): Int = (value.width * value.height * 4 / 1024).coerceAtLeast(1)
    }

    /** Icons drawn since start (cache misses that produced a bitmap); for tests and logs. */
    private val renderCount = java.util.concurrent.atomic.AtomicInteger()
    val rendered: Int get() = renderCount.get()

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
                    isSystem = appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                )
            }
            .distinctBy { it.packageName to it.activityName }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    fun isInstalled(packageName: String): Boolean =
        runCatching { pm.getPackageInfo(packageName, 0) }.isSuccess

    /** [packageName]'s icon drawn [sizePx] square in [style], from the cache when it was drawn before. */
    suspend fun icon(packageName: String, sizePx: Int, style: IconStyle = IconStyle.PLAIN): ImageBitmap? {
        val key = IconKey(packageName, style, sizePx)
        cachedIcon(key)?.let { return it }
        val drawable = withContext(Dispatchers.IO) { runCatching { loadIcon(packageName) }.getOrNull() } ?: return null
        return withContext(Dispatchers.Default) {
            // Another caller may have drawn it meanwhile.
            cachedIcon(key)?.let { return@withContext it }
            val start = System.nanoTime()
            runCatching { IconStyler.render(drawable, style, sizePx).asImageBitmap() }.getOrNull()?.also {
                iconCache.put(key, it)
                renderCount.incrementAndGet()
                if (dev.xross.launcher.BuildConfig.PERF_LOG) {
                    android.util.Log.i(
                        "XrossIcons",
                        "drew $packageName ${style.mask}/${style.filter} ${sizePx}px in %.1f ms".format((System.nanoTime() - start) / 1e6),
                    )
                }
            }
        }
    }

    /** The icon for [key] if it has been drawn (no loading); safe on the main thread. */
    fun cachedIcon(key: IconKey): ImageBitmap? = iconCache.get(key)

    /** Drops every rendered icon (the process is in the background); they are redrawn on demand. */
    fun trimIcons() = iconCache.evictAll()

    fun launchIntent(app: AppEntry): Intent =
        Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(app.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

    @Suppress("DEPRECATION")
    private fun ApplicationInfo.isGame(): Boolean =
        category == ApplicationInfo.CATEGORY_GAME || flags and ApplicationInfo.FLAG_IS_GAME != 0

    companion object {
        /** About 12 MB: every icon of a large app drawer in one style, at the 144 px row size. */
        const val ICON_CACHE_KIB = 12 * 1024
    }
}
