package dev.xross.launcher

import dev.xross.launcher.data.AppEntry
import dev.xross.launcher.data.GameEntity
import dev.xross.launcher.data.XrossSettings
import dev.xross.launcher.state.Categories
import dev.xross.launcher.state.Command
import dev.xross.launcher.state.MenuSnapshot
import dev.xross.launcher.state.MenuTree
import dev.xross.launcher.state.NodeAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AllGamesFolderTest {
    private val games = listOf(
        GameEntity(1, "/r/snes/a.sfc", "snes", "A", "a.sfc", 10, 0, favorite = true),
        GameEntity(2, "/r/snes/b.sfc", "snes", "B", "b.sfc", 10, 0, lastPlayed = 5),
        GameEntity(3, "/r/gba/c.gba", "gba", "C", "c.gba", 10, 0),
        GameEntity(4, "/r/psx/d.cue", "psx", "D", "d.cue", 10, 0),
    )
    private val androidGame = AppEntry("com.example.runner", "com.example.runner.Main", "Runner", isGame = true)

    private fun tree(settings: XrossSettings = XrossSettings(), apps: List<AppEntry> = listOf(androidGame)) =
        MenuTree(MenuSnapshot(games = games, apps = apps, storageGranted = true, settings = settings))

    private fun MenuTree.root() = list(Categories.rootKey(Categories.GAMES))

    @Test fun platformsAreGroupedInAllGamesByDefault() {
        val root = tree().root()
        assertEquals(listOf("Search", "Recently Played", "Favorites", "All Games", "Android Games", "RetroAchievements"), root.map { it.title })
        val allGames = root.single { it.title == "All Games" }
        assertEquals(NodeAction.OpenList(MenuTree.ALL_GAMES), allGames.action)
        assertEquals("3 systems · 4 titles", allGames.subtitle)
    }

    @Test fun allGamesListsEachPlatformWithGamesInRegistryOrder() {
        val platforms = tree().list(MenuTree.ALL_GAMES)
        assertEquals(listOf("Super Nintendo", "Game Boy Advance", "PlayStation"), platforms.map { it.title })
        assertEquals(NodeAction.OpenList("platform:snes"), platforms.first().action)
        assertEquals("2 titles", platforms.first().subtitle)
        // Platform lists themselves are unchanged.
        assertEquals(listOf("A", "B"), tree().list("platform:snes").map { it.title })
    }

    @Test fun classicLayoutKeepsPlatformsAtTheTopLevel() {
        val root = tree(XrossSettings(allGamesFolder = false)).root().map { it.title }
        assertEquals(
            listOf("Search", "Recently Played", "Favorites", "Super Nintendo", "Game Boy Advance", "PlayStation", "Android Games", "RetroAchievements"),
            root,
        )
    }

    @Test fun allGamesStillResolvesInTheClassicLayout() {
        // A navigation stack saved before switching layouts must not land on an empty list.
        assertEquals(3, tree(XrossSettings(allGamesFolder = false)).list(MenuTree.ALL_GAMES).size)
    }

    @Test fun noAllGamesFolderWithoutRomGames() {
        val onlyApps = MenuTree(MenuSnapshot(apps = listOf(androidGame), storageGranted = true))
            .list(Categories.rootKey(Categories.GAMES)).map { it.title }
        assertTrue("All Games" !in onlyApps)
        assertTrue("Android Games" in onlyApps)
    }

    @Test fun singleSystemSummaryIsSingular() {
        val one = MenuTree(MenuSnapshot(games = games.take(1), storageGranted = true))
            .list(Categories.rootKey(Categories.GAMES)).single { it.title == "All Games" }
        assertEquals("1 system · 1 title", one.subtitle)
    }

    @Test fun themeSettingsOfferTheLayoutToggle() {
        fun row(settings: XrossSettings) = MenuTree(MenuSnapshot(settings = settings)).themeRows()
            .single { it.id == "disp:allgames" }
        assertEquals("Inside All Games", row(XrossSettings()).subtitle)
        assertEquals("At the top of Games (classic)", row(XrossSettings(allGamesFolder = false)).subtitle)
        assertEquals(NodeAction.Run(Command.ToggleAllGamesFolder), row(XrossSettings()).action)
    }
}
