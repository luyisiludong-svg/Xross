package dev.xross.launcher

import dev.xross.launcher.data.GameEntity
import dev.xross.launcher.data.GameMetadataEntity
import dev.xross.launcher.data.XrossSettings
import dev.xross.launcher.model.ArtKind
import dev.xross.launcher.model.PlatformRegistry
import dev.xross.launcher.state.BrowserFocus
import dev.xross.launcher.state.Command
import dev.xross.launcher.state.LibraryBrowser
import dev.xross.launcher.state.MediaFilter
import dev.xross.launcher.state.MenuSnapshot
import dev.xross.launcher.state.MenuTree
import dev.xross.launcher.state.NodeAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.2.2.0 § 3 (library browser) and § 4 (Missing art on a system folder). */
class LibraryBrowserTest {
    private fun game(id: Long, platform: String, title: String) =
        GameEntity(id = id, path = "/r/$platform/$title", platformId = platform, title = title, fileName = title, sizeBytes = 1, lastModified = 0)

    private val games = listOf(
        game(1, "snes", "Chrono Trigger"), game(2, "snes", "Earthbound"), game(3, "snes", "Actraiser"),
        game(4, "psx", "Vagrant Story"), game(5, "psx", "Ape Escape"),
    )

    private fun mask(vararg kinds: ArtKind) = kinds.fold(0) { m, k -> m or k.bit }

    private val meta = mapOf(
        1L to GameMetadataEntity(1, mediaStatus = mask(ArtKind.BOX_2D, ArtKind.HERO, ArtKind.WHEEL), pinnedArt = "HERO"),
        2L to GameMetadataEntity(2, mediaStatus = mask(ArtKind.BOX_2D)),
        // 3: no metadata at all. 4: status not known yet.
        4L to GameMetadataEntity(4, mediaStatus = null),
        5L to GameMetadataEntity(5, mediaStatus = mask(ArtKind.HERO, ArtKind.WHEEL)),
    )

    private fun build(previous: dev.xross.launcher.state.LibraryBrowserState? = null, startOn: String? = null) =
        LibraryBrowser.build(games, meta, PlatformRegistry.builtIn, previous, startOn)

    // region Media filters

    @Test fun filtersReadOnlyTheStatusMask() {
        val m1 = meta[1L]
        assertFalse(MediaFilter.MISSING_ART.keeps(m1))
        assertTrue(MediaFilter.MISSING_ANY.keeps(m1))
        assertTrue(MediaFilter.MISSING_ART.keeps(meta[2L]))
        assertFalse(MediaFilter.MISSING_BOX.keeps(meta[2L]))
        assertTrue(MediaFilter.MISSING_HERO.keeps(meta[2L]))
        assertTrue(MediaFilter.MISSING_BOX.keeps(meta[5L]))
        assertFalse(MediaFilter.MISSING_LOGO.keeps(meta[5L]))
        // Nothing known counts as nothing there.
        for (f in MediaFilter.entries - MediaFilter.ALL) {
            assertTrue(f.name, f.keeps(null))
            assertTrue(f.name, f.keeps(GameMetadataEntity(9, mediaStatus = null)))
        }
        assertTrue(MediaFilter.ALL.keeps(m1))
    }

    // endregion

    // region Browser

    @Test fun theStripIsAllThenEverySystemWithGames() {
        val b = build()
        assertEquals(listOf(null, "snes", "psx"), b.systems.map { it.id })
        assertEquals(listOf(5, 3, 2), b.systems.map { it.count })
        assertEquals(listOf("Actraiser", "Ape Escape", "Chrono Trigger", "Earthbound", "Vagrant Story"), b.rows.map { it.title })
        assertEquals(setOf(ArtKind.HERO), b.rows.first { it.gameId == 1L }.pinned)
    }

    @Test fun opensOnTheSystemItWasOpenedFor() {
        val b = build(startOn = "psx")
        assertEquals("psx", b.system?.id)
        assertEquals(listOf(5L, 4L), b.ids)
    }

    @Test fun shouldersMoveAlongTheStripAndWrap() {
        var b = build()
        b = LibraryBrowser.moveSystem(b, 1) { build(it) }
        assertEquals("snes", b.system?.id)
        assertEquals(0, b.index)
        b = LibraryBrowser.moveSystem(b, -2) { build(it) }
        assertEquals("psx", b.system?.id)
    }

    @Test fun filtersNarrowTheListAndUpReachesTheFilterRow() {
        var b = build(startOn = "snes")
        b = LibraryBrowser.moveCursor(b, -1)
        assertEquals(BrowserFocus.FILTERS, b.focus)
        b = LibraryBrowser.moveFilter(b, 1) { build(it) }
        assertEquals(MediaFilter.MISSING_ANY, b.filter)
        b = LibraryBrowser.moveFilter(b, 2) { build(it) }
        assertEquals(MediaFilter.MISSING_HERO, b.filter)
        assertEquals(listOf(3L, 2L), b.ids) // Actraiser (nothing), Earthbound (box only)
        b = LibraryBrowser.moveCursor(b, 1)
        assertEquals(BrowserFocus.LIST, b.focus)
        assertEquals("Missing hero", b.filter.label)
        assertEquals("2 games · missing hero", LibraryBrowser.summary(b))
    }

    @Test fun backFromTheEditorLandsOnTheSameGameScrollAndFilter() {
        var b = build(startOn = "snes")
        b = LibraryBrowser.moveCursor(b, 1).copy(firstVisible = 1)
        val onGame = b.focused!!.gameId
        // The editor gave game 2 a hero meanwhile: the rebuilt browser keeps system, cursor and scroll.
        val fixed = meta + (2L to GameMetadataEntity(2, mediaStatus = mask(ArtKind.BOX_2D, ArtKind.HERO)))
        val after = LibraryBrowser.build(games, fixed, PlatformRegistry.builtIn, b)
        assertEquals("snes", after.system?.id)
        assertEquals(onGame, after.focused!!.gameId)
        assertEquals(1, after.firstVisible)
        assertTrue(after.rows.first { it.gameId == 2L }.has(ArtKind.HERO))
    }

    @Test fun aFixedGameLeavingTheFilterKeepsThePosition() {
        var b = LibraryBrowser.moveFilter(build(startOn = "snes").copy(focus = BrowserFocus.FILTERS), 3) { build(it) } // Missing hero
        b = LibraryBrowser.moveCursor(b, 1) // into the list, on the first
        b = b.copy(index = 1)
        val fixed = meta + (2L to GameMetadataEntity(2, mediaStatus = mask(ArtKind.BOX_2D, ArtKind.HERO)))
        val after = LibraryBrowser.build(games, fixed, PlatformRegistry.builtIn, b)
        assertEquals(listOf(3L), after.ids)
        assertEquals(0, after.index)
    }

    // endregion

    // region Missing art on a system folder (§ 4)

    private fun tree(missingArtOnly: Set<String> = emptySet()) =
        MenuTree(MenuSnapshot(games = games, metadata = meta, settings = XrossSettings(), missingArtOnly = missingArtOnly))

    private fun MenuTree.gameIds(platform: String) =
        list(MenuTree.PLATFORM_PREFIX + platform).mapNotNull { (it.action as? NodeAction.LaunchGame)?.gameId }

    @Test fun aSystemFolderShowsOnlyGamesMissingArtWhileFiltered() {
        assertEquals(listOf(1L, 2L, 3L), tree().gameIds("snes"))
        val filtered = tree(setOf("snes"))
        // The editor pages through the folder's rows, so it pages through the filtered games only.
        assertEquals(listOf(2L, 3L), filtered.gameIds("snes"))
        assertEquals(listOf(4L, 5L), filtered.gameIds("psx"))
    }

    @Test fun aFolderWithNothingMissingSaysSoAndOffersTheWayBack() {
        val all = games.filter { it.platformId == "snes" }.map { it.id }
        val full = mask(ArtKind.BOX_2D, ArtKind.HERO, ArtKind.WHEEL)
        val t = MenuTree(MenuSnapshot(games = games, metadata = all.associateWith { GameMetadataEntity(it, mediaStatus = full) }, missingArtOnly = setOf("snes")))
        val rows = t.list(MenuTree.PLATFORM_PREFIX + "snes")
        assertEquals(1, rows.size)
        assertEquals(NodeAction.Run(Command.SetMissingArtFilter("snes", false)), rows.single().action)
    }

    @Test fun theSystemFolderOptionsOfferTheFilterAndFixMedia() {
        val folder = tree(setOf("snes")).list(MenuTree.ALL_GAMES).first { it.id == MenuTree.PLATFORM_PREFIX + "snes" }
        assertEquals("2 of 3 titles · missing art", folder.subtitle)
        val show = folder.menu.first { it.label == "Show" }
        assertTrue(show.submenu.first { it.label == "Missing art" }.selected)
        assertEquals(Command.OpenLibraryBrowser("snes"), folder.menu.first { it.label == "Fix media…" }.command)
    }

    // endregion
}
