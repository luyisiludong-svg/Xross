package dev.xross.launcher.state

import android.content.pm.ApplicationInfo
import dev.xross.launcher.data.AppEntry
import dev.xross.launcher.data.XrossSettings
import dev.xross.launcher.model.AppHome
import dev.xross.launcher.model.MediaApps

/**
 * Which category a non-game Android app is listed in (0.2.1.0, pure). Video, music and photo apps
 * go to the Apps folder at the end of Video, Music or Photo; everything else stays in Apps.
 *
 * Precedence: a folder the user picked in Apps keeps it in Apps → the user's own Move to … choice
 * ([XrossSettings.appHomes]) → the sorting switch off ([XrossSettings.autoSortApps]) → the app's
 * declared category → [MediaApps.KNOWN] → Apps. Hidden apps stay hidden everywhere (callers list
 * [AppFolders.visible] apps), and games never move.
 */
object AppSorting {
    fun home(app: AppEntry, s: XrossSettings): AppHome {
        if (app.isGame) return AppHome.APPS
        if (app.packageName in s.appFolders) return AppHome.APPS
        s.appHomes[app.packageName]?.let { return it }
        if (!s.autoSortApps) return AppHome.APPS
        return natural(app)
    }

    /** Where [app] goes on its own: its declared category, else the known-app table, else Apps. */
    fun natural(app: AppEntry): AppHome = when (app.category) {
        ApplicationInfo.CATEGORY_VIDEO -> AppHome.VIDEO
        ApplicationInfo.CATEGORY_AUDIO -> AppHome.MUSIC
        ApplicationInfo.CATEGORY_IMAGE -> AppHome.PHOTO
        else -> MediaApps.KNOWN[app.packageName] ?: AppHome.APPS
    }

    /** The visible apps listed in [home], in the given order. */
    fun appsIn(home: AppHome, apps: List<AppEntry>, s: XrossSettings): List<AppEntry> =
        AppFolders.visible(apps, s).filter { !it.isGame && home(it, s) == home }

    /** "Video", "Music", "Photo", "Apps". */
    val AppHome.label: String
        get() = when (this) {
            AppHome.APPS -> "Apps"
            AppHome.VIDEO -> "Video"
            AppHome.MUSIC -> "Music"
            AppHome.PHOTO -> "Photo"
        }
}
