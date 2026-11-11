package dev.xross.launcher

import dev.xross.launcher.data.GameEntity
import dev.xross.launcher.data.XrossSettings
import dev.xross.launcher.launch.GameLauncher
import dev.xross.launcher.state.Categories
import dev.xross.launcher.state.Frame
import dev.xross.launcher.state.MenuSnapshot
import dev.xross.launcher.state.MenuTree
import dev.xross.launcher.state.NavState
import dev.xross.launcher.state.NodeAction
import dev.xross.launcher.state.RemapSession
import dev.xross.launcher.storage.PlatformRegistry
import dev.xross.launcher.storage.StorageVolumes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationTest {
    @Test fun cursorClampsAndStacksArePerCategory() {
        var nav = NavState(categoryIndex = 1)
        nav = nav.moveCursor("games", "Games", +5, listSize = 3)
        assertEquals(2, nav.stack("games", "Games").last().index)
        nav = nav.push("games", "Games", Frame("platform:snes", "SNES"))
        nav = nav.moveCategory(-1, 3)
        assertEquals(1, nav.stack("settings", "Settings").size)
        nav = nav.moveCategory(+1, 3)
        assertEquals("platform:snes", nav.stack("games", "Games").last().key)
        nav = nav.pop("games", "Games")!!
        assertEquals(2, nav.stack("games", "Games").last().index)
        assertNull(nav.pop("games", "Games"))
    }

    @Test fun gamesRootGroupsByPlatform() {
        val games = listOf(
            GameEntity(1, "/r/snes/a.sfc", "snes", "A", "a.sfc", 10, 0, favorite = true),
            GameEntity(2, "/r/snes/b.sfc", "snes", "B", "b.sfc", 10, 0, lastPlayed = 5),
            GameEntity(3, "/r/gba/c.gba", "gba", "C", "c.gba", 10, 0),
        )
        // Classic flat layout: platform folders sit directly in Games.
        val flat = XrossSettings(allGamesFolder = false)
        val tree = MenuTree(MenuSnapshot(games = games, storageGranted = true, settings = flat))
        val root = tree.list(Categories.rootKey(Categories.GAMES)).map { it.title }
        assertEquals(listOf("Search", "Recently Played", "Favorites", "Super Nintendo", "Game Boy Advance"), root)
        val snes = tree.list("platform:snes")
        assertEquals(listOf("A", "B"), snes.map { it.title })
        assertTrue(snes.first().action is NodeAction.LaunchGame)
    }

    @Test fun crossPlatformRowsDoNotRepeatThePlatformAsTheExtension() {
        val games = listOf(
            GameEntity(1, "/r/gba/a.gba", "gba", "Golden Sun", "a.gba", 2048, 0, favorite = true),
            GameEntity(2, "/r/snes/b.sfc", "snes", "Super Metroid", "b.sfc", 2048, 0, favorite = true),
        )
        val favorites = MenuTree(MenuSnapshot(games = games)).list(MenuTree.FAVORITES)
        assertEquals(listOf("★ · GBA · 2 KB", "★ · SNES · SFC · 2 KB"), favorites.map { it.subtitle })
        // Inside the platform's own list the extension is the only format hint, so it stays.
        assertEquals("★ · GBA · 2 KB", MenuTree(MenuSnapshot(games = games)).list("platform:gba").single().subtitle)
    }

    @Test fun emptyLibraryPointsToSettings() {
        val root = MenuTree(MenuSnapshot()).list(Categories.rootKey(Categories.GAMES))
        assertEquals(1, root.size)
        assertEquals("Storage access needed", root.single().title)
    }

    @Test fun emulatorResolutionPrefersInstalledChoice() {
        val psx = PlatformRegistry.byId("psx")!!
        assertEquals("duckstation", GameLauncher.resolveOption(psx, null, setOf("duckstation", "retroarch"))?.key)
        assertEquals("retroarch:swanstation", GameLauncher.resolveOption(psx, "retroarch:swanstation", setOf("duckstation", "retroarch"))?.key)
        // Chosen emulator uninstalled → fall back to the first installed one.
        assertEquals("retroarch:swanstation", GameLauncher.resolveOption(psx, "duckstation", setOf("retroarch"))?.key)
        assertNull(GameLauncher.resolveOption(psx, null, emptySet()))
    }

    @Test fun remapSessionIgnoresRepeatedKeys() {
        var s = RemapSession()
        s = s.capture(19).capture(19).capture(20)
        assertEquals(2, s.step)
        assertEquals(2, s.captured.size)
    }

    @Test fun treeDocIdsResolveToPaths() {
        val roots = listOf("/storage/emulated/0", "/storage/1A2B-3C4D")
        assertEquals("/storage/emulated/0/ROMs", StorageVolumes.docIdToPath("primary:ROMs", roots))
        assertEquals("/storage/1A2B-3C4D/Games/snes", StorageVolumes.docIdToPath("1A2B-3C4D:Games/snes", roots))
        assertEquals("/storage/emulated/0", StorageVolumes.docIdToPath("primary:", roots))
        assertNull(StorageVolumes.docIdToPath("raw", roots))
    }
}
