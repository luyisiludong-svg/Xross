package dev.wave.launcher

import dev.wave.launcher.data.GameEntity
import dev.wave.launcher.launch.GameLauncher
import dev.wave.launcher.state.Categories
import dev.wave.launcher.state.Frame
import dev.wave.launcher.state.MenuSnapshot
import dev.wave.launcher.state.MenuTree
import dev.wave.launcher.state.NavState
import dev.wave.launcher.state.NodeAction
import dev.wave.launcher.state.RemapSession
import dev.wave.launcher.storage.PlatformRegistry
import dev.wave.launcher.storage.StorageVolumes
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
        val tree = MenuTree(MenuSnapshot(games = games, storageGranted = true))
        val root = tree.list(Categories.rootKey(Categories.GAMES)).map { it.title }
        assertEquals(listOf("Recently Played", "Favorites", "Super Nintendo", "Game Boy Advance"), root)
        val snes = tree.list("platform:snes")
        assertEquals(listOf("A", "B"), snes.map { it.title })
        assertTrue(snes.first().action is NodeAction.LaunchGame)
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
