package dev.xross.launcher

import dev.xross.launcher.data.GameEntity
import dev.xross.launcher.data.XrossSettings
import dev.xross.launcher.input.NavAction
import dev.xross.launcher.profile.AchievementsState
import dev.xross.launcher.profile.Friend
import dev.xross.launcher.profile.FriendsState
import dev.xross.launcher.profile.RaAchievement
import dev.xross.launcher.profile.RaBadge
import dev.xross.launcher.profile.RaFriendActivity
import dev.xross.launcher.profile.RaGameProgress
import dev.xross.launcher.profile.RaHubSummary
import dev.xross.launcher.profile.RaLibrarySummary
import dev.xross.launcher.profile.RaMatchKind
import dev.xross.launcher.state.AchievementsGrid
import dev.xross.launcher.state.AchievementsLayout
import dev.xross.launcher.state.AchievementsViewState
import dev.xross.launcher.state.Categories
import dev.xross.launcher.state.Frame
import dev.xross.launcher.state.HubFocus
import dev.xross.launcher.state.HubSidebar
import dev.xross.launcher.state.MenuSnapshot
import dev.xross.launcher.state.MenuTree
import dev.xross.launcher.state.RaHub
import dev.xross.launcher.state.RaHubEffect
import dev.xross.launcher.state.RaHubState
import dev.xross.launcher.state.SidebarRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 2b.7e: the Achievements grid and banner, the hub's platform filter and its `FOLLOWING` sidebar. */
class HubUpgradeTest {

    // region Achievements: three rows, columns from the width

    private fun view(n: Int, index: Int = 0, columns: Int = 8): AchievementsViewState {
        val list = (1..n).map { RaAchievement(id = it, title = "A$it", displayOrder = it) }
        return AchievementsViewState(
            1, "u", "Game", "MD",
            AchievementsState.Ready(RaGameProgress(1, "Game", achievements = list), RaMatchKind.HASH),
            index,
            columns = columns,
        )
    }

    @Test fun gridColumnsFollowTheWidthAndCellsFitExactlyThreeRows() {
        // Landscape phone: 3 rows of cells as tall as the area allows, as many across as fit.
        val (cols, cell) = AchievementsLayout.fit(width = 800f, height = 300f, gap = 10f)
        assertEquals(((300f - 20f) / 3), cell, 0.01f)
        assertEquals(((800f + 10f) / (cell + 10f)).toInt(), cols)
        assertTrue("Three rows fill the height", 3 * cell + 2 * 10f <= 300f + 0.01f)
        assertTrue("The columns fit the width", cols * cell + (cols - 1) * 10f <= 800f + 0.01f)
        // A wider display takes more columns of the same height.
        assertTrue(AchievementsLayout.fit(1200f, 300f, 10f).first > cols)
        // Portrait: at least four across; the cells shrink to fit and three rows take less height.
        val (narrow, small) = AchievementsLayout.fit(width = 300f, height = 500f, gap = 10f)
        assertEquals(AchievementsLayout.MIN_COLUMNS, narrow)
        assertEquals((300f - 30f) / 4, small, 0.01f)
        // Very wide and short: capped.
        assertEquals(AchievementsLayout.MAX_COLUMNS, AchievementsLayout.fit(5000f, 90f, 10f).first)
    }

    @Test fun gridScrollsByRowAndStopsAtTheEdges() {
        var v = view(40) // 8 across: rows 0..4
        assertEquals(5, v.rowCount)
        repeat(2) { v = AchievementsGrid.onAction(v, NavAction.DOWN).state }
        assertEquals("Rows 0-2 show: no scroll yet", 0, v.top)
        v = AchievementsGrid.onAction(v, NavAction.DOWN).state
        assertEquals(24, v.index)
        assertEquals("Crossing the bottom row scrolls one row", 1, v.top)
        v = AchievementsGrid.onAction(v, NavAction.DOWN).state
        assertEquals(2, v.top)
        assertEquals("Last row: Down stays", v, AchievementsGrid.onAction(v, NavAction.DOWN).state)
        v = AchievementsGrid.onAction(v, NavAction.UP).state
        assertEquals("Up inside the shown rows doesn't scroll", 2, v.top)
        repeat(3) { v = AchievementsGrid.onAction(v, NavAction.UP).state }
        assertEquals(0, v.index / 8)
        assertEquals(0, v.top)
        // L1/R1 page by three rows; the last page never leaves empty rows below.
        v = AchievementsGrid.onAction(view(40), NavAction.NEXT_CATEGORY).state
        assertEquals(24, v.index)
        assertEquals(1, v.top)
        // Right off the end of a row moves to the next and scrolls when that row is hidden.
        v = AchievementsGrid.onAction(view(40, index = 23), NavAction.RIGHT).state
        assertEquals(24, v.index)
        assertEquals(1, v.top)
        // Few badges: never scrolls.
        assertEquals(0, AchievementsGrid.onAction(view(6, index = 5), NavAction.RIGHT).state.top)
    }

    @Test fun newColumnsKeepTheFocusedBadgeInView() {
        val v = view(60, index = 35, columns = 10).settled() // row 3
        assertEquals(1, v.top)
        val narrow = v.withColumns(4) // row 8 of 15
        assertEquals(35, narrow.index)
        assertTrue(narrow.index / 4 in narrow.top until narrow.top + AchievementsViewState.ROWS)
        assertEquals(narrow, narrow.withColumns(4))
        assertEquals("Tap scrolls to the tapped row (the last three rows)", 3, AchievementsGrid.tap(view(60, columns = 10), 59).top)
    }

    @Test fun bannerStatusIsTheModeOrLocked() {
        assertEquals("HARDCORE", AchievementsGrid.status(RaAchievement(1, "a", unlockedAt = "x", unlockedHardcoreAt = "x")))
        assertEquals("SOFTCORE", AchievementsGrid.status(RaAchievement(1, "a", unlockedAt = "x")))
        assertEquals("Locked", AchievementsGrid.status(RaAchievement(1, "a")))
    }

    // endregion

    // region Per-system summary

    private fun game(id: Long, platform: String, title: String, lastPlayed: Long? = null) =
        GameEntity(id, "/r/$platform/$title", platform, title, "$title.rom", 10, 0, lastPlayed = lastPlayed)

    private fun progress(raId: Int, title: String, unlocked: Int, total: Int, points: Int = 5) = RaGameProgress(
        gameId = raId,
        title = title,
        achievements = (1..total).map { RaAchievement(raId * 100 + it, "A$it", points = points, unlockedAt = if (it <= unlocked) "2024-01-01 00:00:00" else null) },
    )

    private val snesA = game(1, "snes", "Alpha")
    private val snesB = game(2, "snes", "Beta")
    private val gbaC = game(3, "gba", "Gamma")
    private val gbaUntracked = game(4, "gba", "Delta")
    private val known = listOf(
        snesA to progress(10, "Alpha", 2, 4),
        snesB to progress(11, "Beta", 3, 3),
        gbaC to progress(12, "Gamma", 1, 6),
    )

    private fun summary() = RaHubSummary.of(
        supported = listOf(snesA, snesB, gbaC, gbaUntracked),
        known = known,
        order = { listOf("snes", "gba").indexOf(it) },
        tag = { mapOf("snes" to "SNES", "gba" to "GBA")[it] ?: it },
    )

    @Test fun perSystemSummaryFromCachedProgressAndAllEqualsTheLibrarySummary() {
        val s = summary()
        assertEquals("All is today's library summary", RaLibrarySummary.of(4, known), s.all)
        assertEquals(listOf("SNES", "GBA"), s.systems.map { it.tag })
        val snes = s.systems[0].summary
        assertEquals(2, snes.supportedGames)
        assertEquals(2, snes.trackedGames)
        assertEquals(7, snes.achievements)
        assertEquals(5, snes.unlocked)
        assertEquals(listOf("Beta"), snes.mastered.map { it.title })
        val gba = s.systems[1].summary
        assertEquals("The untracked GBA game still counts as supported", 2, gba.supportedGames)
        assertEquals(1, gba.trackedGames)
        assertTrue(gba.mastered.isEmpty())
        assertEquals(s.all, s.summaryFor(0))
        assertEquals(gba, s.summaryFor(2))
        assertEquals("Out of range falls back to All", s.all, s.summaryFor(9))
        assertEquals(mapOf(10 to 1L, 11 to 2L, 12 to 3L), s.libraryGames)
    }

    @Test fun systemsWithoutTrackedGamesGetNoPillAndTheNewestDumpOwnsASet() {
        val dumpOld = game(5, "snes", "Alpha (old)", lastPlayed = 1)
        val dumpNew = game(6, "snes", "Alpha (new)", lastPlayed = 9)
        val s = RaHubSummary.of(
            supported = listOf(dumpOld, dumpNew, gbaUntracked),
            known = listOf(dumpOld to progress(10, "Alpha", 1, 2), dumpNew to progress(10, "Alpha", 1, 2)),
        )
        assertEquals(listOf("snes"), s.systems.map { it.platformId })
        assertEquals("Two dumps of one set count once", 1, s.all.trackedGames)
        assertEquals(6L, s.libraryGames[10])
    }

    // endregion

    // region Platform filter (L1/R1)

    private fun hub(rows: Int = 0) = RaHubState(summary = summary()) to rows

    @Test fun l1R1MoveTheFilterClampedAndNeverScroll() {
        var (h, rows) = hub()
        assertEquals(listOf("All", "SNES", "GBA"), h.pills)
        h = RaHub.onAction(h, NavAction.DOWN, rows).state
        assertEquals(1, h.scroll)
        assertEquals("Clamped at All", h, RaHub.onAction(h, NavAction.PREV_CATEGORY, rows).state)
        h = RaHub.onAction(h, NavAction.NEXT_CATEGORY, rows).state
        assertEquals(1, h.filter)
        assertEquals("A new filter starts at the top; L1/R1 never scroll", 0, h.scroll)
        h = RaHub.onAction(h, NavAction.NEXT_CATEGORY, rows).state
        assertEquals(2, h.filter)
        assertEquals("Clamped at the last system", h, RaHub.onAction(h, NavAction.NEXT_CATEGORY, rows).state)
        assertEquals(h.summary!!.systems[1].summary, h.shown)
        // Up/Down still scroll.
        assertEquals(1, RaHub.onAction(h, NavAction.DOWN, rows).state.scroll)
        // No summary yet: only All, and L1/R1 do nothing.
        assertEquals(RaHubState(), RaHub.onAction(RaHubState(), NavAction.NEXT_CATEGORY).state)
    }

    @Test fun activityFollowsTheFilter() {
        val badges = listOf(RaBadge("x", gameTitle = "Alpha"), RaBadge("y", gameTitle = "Gamma"), RaBadge("z", gameTitle = "Elsewhere"))
        val h = RaHubState(summary = summary())
        assertEquals(badges, h.activity(badges))
        assertEquals(listOf("x"), h.copy(filter = 1).activity(badges).map { it.title })
        assertEquals(listOf("y"), h.copy(filter = 2).activity(badges).map { it.title })
    }

    @Test fun aNewSummaryKeepsTheFilterOnItsSystem() {
        val h = RaHubState(summary = summary(), filter = 2, scroll = 3) // GBA
        val withoutSnes = RaHubSummary.of(listOf(gbaC), listOf(gbaC to progress(12, "Gamma", 2, 6)))
        val next = RaHub.withSummary(h, withoutSnes)
        assertEquals("GBA moved to the first system pill", 1, next.filter)
        assertEquals(0, next.scroll)
        assertEquals("A system that left: back to All", 0, RaHub.withSummary(h, RaHubSummary()).filter)
        assertEquals("Same place: the scroll stays", 3, RaHub.withSummary(h, summary()).scroll)
    }

    // endregion

    // region FOLLOWING sidebar

    private fun friend(user: String, points: Long, gameId: Int = 0, title: String = "") =
        Friend(user, user.lowercase(), points, activity = RaFriendActivity(user, gameId = gameId, gameTitle = title))

    private val self = SidebarRow("Me", 500, self = true)

    @Test fun sidebarListsFollowedUsersByPointsWithTheUserInPlace() {
        val f = FriendsState.Ready(listOf(friend("Low", 10), friend("High", 900), friend("Mid", 500)))
        val rows = HubSidebar.rows(f, self)
        assertEquals(listOf("High", "Me", "Mid", "Low"), rows.map { it.user })
        assertEquals(1, HubSidebar.selfIndex(rows))
        assertTrue(rows[1].self)
        assertEquals("Before the profile was fetched: friends only", listOf("High", "Mid", "Low"), HubSidebar.rows(f, null).map { it.user })
        // The cached list while offline or refreshing.
        assertEquals(4, HubSidebar.rows(FriendsState.Offline(f.friends), self).size)
        assertEquals(4, HubSidebar.rows(FriendsState.Loading(f.friends), self).size)
    }

    @Test fun sidebarCollapsesWhenUnlinkedOfflineWithoutCacheOrFollowingNobody() {
        assertFalse(HubSidebar.shows(FriendsState.Unlinked))
        assertFalse(HubSidebar.shows(FriendsState.Offline()))
        assertFalse(HubSidebar.shows(FriendsState.Ready()))
        assertTrue("Offline with a cache shows it", HubSidebar.shows(FriendsState.Offline(listOf(friend("A", 1)))))
        assertTrue(HubSidebar.rows(FriendsState.Unlinked, self).isEmpty())
        assertTrue("The user's row alone isn't a sidebar", HubSidebar.rows(FriendsState.Ready(), self).isEmpty())
    }

    @Test fun rightAndLeftMoveBetweenTheDashboardAndTheSidebar() {
        var h = RaHubState(summary = summary())
        assertEquals("Collapsed: Right stays", h, RaHub.onAction(h, NavAction.RIGHT, rows = 0).state)
        assertEquals(RaHubEffect.Close, RaHub.onAction(h, NavAction.LEFT, rows = 3).effect)
        h = RaHub.onAction(h, NavAction.RIGHT, rows = 3).state
        assertEquals(HubFocus.SIDEBAR, h.focus)
        h = RaHub.onAction(h, NavAction.DOWN, rows = 3).state
        assertEquals(1, h.row)
        assertEquals("Down in the sidebar doesn't scroll the dashboard", 0, h.scroll)
        repeat(5) { h = RaHub.onAction(h, NavAction.DOWN, rows = 3).state }
        assertEquals("Clamped at the last row", 2, h.row)
        assertEquals(RaHubEffect.OpenFriend(2), RaHub.onAction(h, NavAction.CONFIRM, rows = 3).effect)
        assertEquals("L1/R1 still filter from the sidebar", 1, RaHub.onAction(h, NavAction.NEXT_CATEGORY, rows = 3).state.filter)
        assertEquals(RaHubEffect.Close, RaHub.onAction(h, NavAction.BACK, rows = 3).effect)
        h = RaHub.onAction(h, NavAction.LEFT, rows = 3).state
        assertEquals(HubFocus.DASHBOARD, h.focus)
        assertEquals(RaHubEffect.Primary, RaHub.onAction(h, NavAction.CONFIRM, rows = 3).effect)
        // Back in, the sidebar remembers its row.
        assertEquals(2, RaHub.onAction(h, NavAction.RIGHT, rows = 3).state.row)
        // The sidebar collapsed under the focus (unlinked meanwhile): the dashboard takes it back.
        val lost = RaHubState(focus = HubFocus.SIDEBAR, row = 2)
        assertEquals(1, RaHub.onAction(lost, NavAction.DOWN, rows = 0).state.scroll)
        assertEquals(RaHubEffect.Primary, RaHub.onAction(lost, NavAction.CONFIRM, rows = 0).effect)
    }

    @Test fun confirmOnAFriendFindsTheirLatestGameInTheLibrary() {
        val games = listOf(snesA, snesB, gbaC, game(7, "gba", "The Legend of Zelda: The Minish Cap", lastPlayed = 3), game(8, "wii", "Wii Sports"))
        val byRaId = mapOf(10 to 1L, 11 to 2L, 12 to 3L)
        fun find(gameId: Int, title: String) = HubSidebar.libraryGame(RaFriendActivity("F", gameId = gameId, gameTitle = title), byRaId, games)?.id
        assertEquals("By the matched set", 2L, find(11, "Whatever RA calls it"))
        assertEquals("By title when never matched", 7L, find(99, "The Legend of Zelda - The Minish Cap"))
        assertNull("Not in the library", find(98, "Sonic"))
        assertNull("A system RetroAchievements doesn't cover", find(97, "Wii Sports"))
        assertNull("A title already matched to another set isn't guessed", find(96, "Alpha"))
        assertNull("No game played", HubSidebar.libraryGame(RaFriendActivity("F"), byRaId, games))
        assertNull(HubSidebar.libraryGame(null, byRaId, games))
    }

    @Test fun pathToGameEndsOnTheGameInItsSystemsList() {
        val games = listOf(snesA, snesB, gbaC)
        val grouped = MenuTree(MenuSnapshot(games = games, storageGranted = true))
        val path = grouped.pathToGame(2, "snes", "Games")!!
        assertEquals(listOf(Categories.rootKey(Categories.GAMES), MenuTree.ALL_GAMES, "platform:snes"), path.map { it.key })
        assertEquals(grouped.list(Categories.rootKey(Categories.GAMES)).indexOfFirst { it.id == MenuTree.ALL_GAMES }, path[0].index)
        assertEquals("Beta", (grouped.list("platform:snes")[path.last().index]).title)
        // All Games folder off: the system's folder sits in the root.
        val flat = MenuTree(MenuSnapshot(games = games, storageGranted = true, settings = XrossSettings(allGamesFolder = false)))
        val flatPath = flat.pathToGame(3, "gba", "Games")!!
        assertEquals(2, flatPath.size)
        assertEquals(Frame("platform:gba", "Game Boy Advance", 0), flatPath.last())
        assertEquals("platform:gba", flat.list(Categories.rootKey(Categories.GAMES))[flatPath[0].index].id)
        assertNull("Not listed", grouped.pathToGame(42, "snes", "Games"))
    }

    // endregion
}
