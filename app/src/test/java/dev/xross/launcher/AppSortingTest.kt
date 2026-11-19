package dev.xross.launcher

import android.content.pm.ApplicationInfo
import dev.xross.launcher.config.ConfigCodec
import dev.xross.launcher.data.AppEntry
import dev.xross.launcher.data.XrossSettings
import dev.xross.launcher.model.AppHome
import dev.xross.launcher.state.AppSorting
import dev.xross.launcher.state.Categories
import dev.xross.launcher.state.Command
import dev.xross.launcher.state.MenuSnapshot
import dev.xross.launcher.state.MenuTree
import dev.xross.launcher.state.NodeAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 0.2.1.0: Android apps sorted into Video, Music and Photo. */
@RunWith(RobolectricTestRunner::class)
class AppSortingTest {
    private val player = AppEntry("org.example.player", "org.example.player.Main", "Player", isGame = false, category = ApplicationInfo.CATEGORY_VIDEO)
    private val netflix = AppEntry("com.netflix.mediaclient", "com.netflix.Main", "Netflix", isGame = false)
    private val spotify = AppEntry("com.spotify.music", "com.spotify.Main", "Spotify", isGame = false)
    private val gallery = AppEntry("org.example.gallery", "org.example.gallery.Main", "Gallery", isGame = false, category = ApplicationInfo.CATEGORY_IMAGE)
    private val notes = AppEntry("org.example.notes", "org.example.notes.Main", "Notes", isGame = false, category = ApplicationInfo.CATEGORY_PRODUCTIVITY)
    private val racer = AppEntry("org.example.racer", "org.example.racer.Main", "Racer", isGame = true, category = ApplicationInfo.CATEGORY_VIDEO)
    private val apps = listOf(player, netflix, spotify, gallery, notes, racer)

    @Test fun declaredCategoryThenTheKnownTable() {
        val s = XrossSettings()
        assertEquals(AppHome.VIDEO, AppSorting.home(player, s))
        assertEquals(AppHome.VIDEO, AppSorting.home(netflix, s)) // no category: the table
        assertEquals(AppHome.MUSIC, AppSorting.home(spotify, s))
        assertEquals(AppHome.PHOTO, AppSorting.home(gallery, s))
        assertEquals(AppHome.APPS, AppSorting.home(notes, s))
        assertEquals(AppHome.APPS, AppSorting.home(racer, s)) // games never move
        // A declared category wins over the table.
        assertEquals(AppHome.MUSIC, AppSorting.home(netflix.copy(category = ApplicationInfo.CATEGORY_AUDIO), s))
    }

    @Test fun precedenceFolderThenChoiceThenSwitch() {
        // A folder in Apps keeps it there, whatever else says.
        val inFolder = XrossSettings(appFolders = mapOf(player.packageName to "Streaming"), appHomes = mapOf(player.packageName to AppHome.VIDEO))
        assertEquals(AppHome.APPS, AppSorting.home(player, inFolder))
        // The user's choice wins over the category and over the switch.
        assertEquals(AppHome.APPS, AppSorting.home(player, XrossSettings(appHomes = mapOf(player.packageName to AppHome.APPS))))
        assertEquals(AppHome.MUSIC, AppSorting.home(notes, XrossSettings(autoSortApps = false, appHomes = mapOf(notes.packageName to AppHome.MUSIC))))
        // Switched off: everything else stays in Apps.
        assertEquals(AppHome.APPS, AppSorting.home(player, XrossSettings(autoSortApps = false)))
        assertEquals(AppHome.APPS, AppSorting.home(spotify, XrossSettings(autoSortApps = false)))
    }

    private fun tree(settings: XrossSettings = XrossSettings()) = MenuTree(MenuSnapshot(apps = apps, storageGranted = true, settings = settings))

    @Test fun mediaRootsEndWithAnAppsFolderWhileItHoldsOne() {
        val video = tree().list(Categories.rootKey(Categories.VIDEO))
        val row = video.last()
        assertEquals("Apps", row.title)
        assertEquals("2 apps", row.subtitle)
        assertEquals(NodeAction.OpenList(MenuTree.MEDIA_APPS_PREFIX + "video"), row.action)
        assertEquals(listOf("Player", "Netflix"), tree().list(MenuTree.MEDIA_APPS_PREFIX + "video").map { it.title })
        assertEquals(listOf("Spotify"), tree().list(MenuTree.MEDIA_APPS_PREFIX + "music").map { it.title })
        assertEquals("Apps", tree().musicRoot(dev.xross.launcher.media.NowPlaying()).last().title)
        // Nothing to sort: no folder.
        val none = MenuTree(MenuSnapshot(apps = listOf(notes), storageGranted = true)).list(Categories.rootKey(Categories.PHOTO))
        assertFalse(none.any { it.title == "Apps" && it.id.startsWith(MenuTree.MEDIA_APPS_PREFIX) })
    }

    @Test fun appsKeepsOnlyWhatIsntSorted() {
        val titles = tree().list(Categories.rootKey(Categories.APPS)).map { it.title }
        assertEquals(listOf("Notes"), titles)
        val off = tree(XrossSettings(autoSortApps = false)).list(Categories.rootKey(Categories.APPS)).map { it.title }
        assertEquals(listOf("Player", "Netflix", "Spotify", "Gallery", "Notes"), off)
        assertFalse(tree(XrossSettings(autoSortApps = false)).list(Categories.rootKey(Categories.VIDEO)).any { it.id.startsWith(MenuTree.MEDIA_APPS_PREFIX) })
    }

    @Test fun hiddenAppsStayHidden() {
        val s = XrossSettings(hiddenApps = setOf(netflix.packageName, player.packageName))
        assertFalse(tree(s).list(Categories.rootKey(Categories.VIDEO)).any { it.id.startsWith(MenuTree.MEDIA_APPS_PREFIX) })
        assertTrue(tree(s).list(MenuTree.MEDIA_APPS_PREFIX + "video").isEmpty())
        assertFalse(tree(s).list(Categories.rootKey(Categories.APPS)).any { it.title == "Netflix" })
    }

    @Test fun optionsMoveBetweenAppsAndTheCategories() {
        val inVideo = tree().list(MenuTree.MEDIA_APPS_PREFIX + "video").first()
        val move = inVideo.menu.single { it.label == "Move to Apps" }
        assertEquals(Command.SetAppHome(player.packageName, AppHome.APPS), move.command)
        assertFalse(inVideo.menu.any { it.label == "Move to folder" })

        val moved = XrossSettings(appHomes = mapOf(player.packageName to AppHome.APPS))
        val inApps = tree(moved).list(Categories.rootKey(Categories.APPS)).single { it.title == "Player" }
        val sub = inApps.menu.single { it.label == "Move to Video, Music or Photo" }
        assertEquals("Suggested: Video", sub.detail)
        assertEquals(listOf("Video", "Music", "Photo", "Sort automatically"), sub.submenu.map { it.label })
        assertEquals(Command.SetAppHome(player.packageName, null), sub.submenu.last().command)
        assertTrue(inApps.menu.any { it.label == "Move to folder" })
    }

    @Test fun settingsFileKeepsTheSwitchAndTheChoices() {
        val s = XrossSettings(autoSortApps = false, appHomes = mapOf("com.example.a" to AppHome.PHOTO, "com.example.b" to AppHome.APPS))
        val back = ConfigCodec.decodeSettings(XrossSettings(), ConfigCodec.encodeSettings(s))
        assertEquals(s.autoSortApps, back.autoSortApps)
        assertEquals(s.appHomes, back.appHomes)
        val old = ConfigCodec.decodeSettings(XrossSettings(), """{"apps": {"homes": {"com.example.c": "radio", "com.example.d": "video"}}}""")
        assertTrue(old.autoSortApps)
        assertEquals(mapOf("com.example.d" to AppHome.VIDEO), old.appHomes)
        val row = MenuTree(MenuSnapshot()).list(MenuTree.APPS_SETTINGS).single { it.id == "apps:autosort" }
        assertEquals(NodeAction.Run(Command.ToggleAutoSortApps), row.action)
    }
}
