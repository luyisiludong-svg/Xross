package dev.xross.launcher

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.xross.launcher.data.GameEntity
import dev.xross.launcher.data.XrossDatabase
import dev.xross.launcher.input.NavAction
import dev.xross.launcher.state.Categories
import dev.xross.launcher.state.Command
import dev.xross.launcher.state.KeyKind
import dev.xross.launcher.state.MenuSnapshot
import dev.xross.launcher.state.MenuTree
import dev.xross.launcher.state.NodeAction
import dev.xross.launcher.state.SearchEffect
import dev.xross.launcher.state.SearchFocus
import dev.xross.launcher.state.SearchQuery
import dev.xross.launcher.state.SearchState
import dev.xross.launcher.state.XmbNode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SearchTest {

    private fun SearchState.press(vararg actions: NavAction): SearchState = actions.fold(this) { s, a -> s.onAction(a).state }
    private fun results(n: Int) = List(n) { XmbNode("game:$it", "Game $it", action = NodeAction.LaunchGame(it.toLong())) }

    // region Query building

    @Test fun typedTextBecomesAPrefixQueryPerWord() {
        assertEquals("chrono* tri*", SearchQuery.toFts("Chrono Tri"))
        assertEquals("zelda*", SearchQuery.toFts("  ZELDA  "))
        assertNull(SearchQuery.toFts("   "))
        assertNull(SearchQuery.toFts("-'&."))
    }

    @Test fun ftsSyntaxInTheQueryIsNeutralised() {
        // Quotes, operators and column filters must never reach MATCH as syntax.
        assertEquals("mario* s* kart* or* title* x*", SearchQuery.toFts("Mario's \"Kart\" OR title:x*"))
    }

    // endregion

    // region Keyboard

    @Test fun startsOnTheLetterAAndTypesWithConfirm() {
        var s = SearchState()
        assertEquals("A", s.focusedKey.label)
        s = s.press(NavAction.CONFIRM, NavAction.RIGHT, NavAction.CONFIRM)
        assertEquals("AB", s.query)
        // Digits are the row above.
        s = s.press(NavAction.UP, NavAction.CONFIRM)
        assertEquals("AB2", s.query)
    }

    @Test fun shortcutsAddSpaceAndDelete() {
        var s = SearchState().press(NavAction.CONFIRM, NavAction.OPTIONS, NavAction.CONFIRM)
        assertEquals("A A", s.query)
        s = s.press(NavAction.PREV_CATEGORY)
        assertEquals("A ", s.query)
        // No leading or doubled spaces.
        assertEquals("A ", s.press(NavAction.OPTIONS).query)
        assertEquals("", SearchState().press(NavAction.OPTIONS).query)
    }

    @Test fun bottomRowKeysWork() {
        val typed = SearchState().press(NavAction.CONFIRM, NavAction.CONFIRM) // "AA"
        val onSpace = typed.press(NavAction.DOWN, NavAction.DOWN, NavAction.DOWN)
        assertEquals(KeyKind.SPACE, onSpace.focusedKey.kind)
        val onBackspace = onSpace.press(NavAction.RIGHT)
        assertEquals(KeyKind.BACKSPACE, onBackspace.focusedKey.kind)
        assertEquals("A", onBackspace.press(NavAction.CONFIRM).query)
        val onClear = onBackspace.press(NavAction.RIGHT)
        assertEquals(KeyKind.CLEAR, onClear.focusedKey.kind)
        assertEquals("", onClear.press(NavAction.CONFIRM).query)
    }

    @Test fun verticalMovesFollowTheColumnAcrossWideKeys() {
        // J is the last letter of its row; straight down lands on the wide key under that column.
        val j = SearchState().press(*Array(9) { NavAction.RIGHT })
        assertEquals("J", j.focusedKey.label)
        val bottom = j.press(NavAction.DOWN, NavAction.DOWN, NavAction.DOWN)
        assertEquals(KeyKind.DONE, bottom.focusedKey.kind)
        // And back up returns to the same column, not the wide key's first column.
        assertEquals(".", bottom.press(NavAction.UP).focusedKey.label)
        // Edges clamp.
        assertEquals("1", SearchState().press(NavAction.UP, NavAction.UP, NavAction.LEFT).focusedKey.label)
    }

    @Test fun queryLengthIsCapped() {
        val s = SearchState().press(*Array(80) { NavAction.CONFIRM })
        assertEquals(SearchState.MAX_QUERY, s.query.length)
    }

    @Test fun hardwareKeyboardTypesDirectly() {
        var s = SearchState().type('z').type('e').type(' ').type('1')
        assertEquals("ZE 1", s.query)
        s = s.backspace()
        assertEquals("ZE ", s.query)
    }

    // endregion

    // region Results

    @Test fun focusMovesToResultsAndBack() {
        val empty = SearchState().press(*Array(9) { NavAction.RIGHT }, NavAction.RIGHT)
        assertEquals(SearchFocus.KEYS, empty.focus) // nothing to move to yet

        var s = SearchState().press(NavAction.CONFIRM).withResults("A", results(3)).press(*Array(9) { NavAction.RIGHT }, NavAction.RIGHT)
        assertEquals(SearchFocus.RESULTS, s.focus)
        s = s.press(NavAction.DOWN, NavAction.DOWN, NavAction.DOWN)
        assertEquals(2, s.resultIndex)
        assertEquals(SearchEffect.Activate(results(3)[2]), s.onAction(NavAction.CONFIRM).effect)
        assertEquals(SearchEffect.Options(results(3)[2]), s.onAction(NavAction.OPTIONS).effect)
        s = s.press(NavAction.LEFT)
        assertEquals(SearchFocus.KEYS, s.focus)
        // R1 and the Results key jump across too.
        assertEquals(SearchFocus.RESULTS, s.press(NavAction.NEXT_CATEGORY).focus)
    }

    @Test fun backClosesFromKeysButOnlyLeavesResults() {
        val inResults = SearchState().press(NavAction.CONFIRM).withResults("A", results(2)).press(NavAction.NEXT_CATEGORY)
        val back = inResults.onAction(NavAction.BACK)
        assertEquals(SearchFocus.KEYS, back.state.focus)
        assertEquals(SearchEffect.None, back.effect)
        assertEquals(SearchEffect.Close, back.state.onAction(NavAction.BACK).effect)
    }

    @Test fun staleResultsAreIgnoredAndCursorStaysInRange() {
        val s = SearchState().press(NavAction.CONFIRM) // query "A"
        assertTrue(s.withResults("OLD", results(5)).results.isEmpty())
        val shown = s.withResults("A", results(5)).press(NavAction.NEXT_CATEGORY, NavAction.DOWN, NavAction.DOWN, NavAction.DOWN)
        assertEquals(3, shown.resultIndex)
        val fewer = shown.withResults("A", results(2))
        assertEquals(1, fewer.resultIndex)
        // Results vanish while browsing them: focus returns to the keys.
        assertEquals(SearchFocus.KEYS, fewer.withResults("A", emptyList()).focus)
    }

    // endregion

    // region Index

    @Test fun ftsIndexFindsGamesByWordPrefixAndTracksChanges() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, XrossDatabase::class.java).allowMainThreadQueries().build()
        val dao = db.games()
        dao.insertAll(
            listOf(
                GameEntity(1, "/a.sfc", "snes", "Chrono Trigger", "a.sfc", 1, 0),
                GameEntity(2, "/b.nds", "nds", "Chrono Trigger DS", "b.nds", 1, 0),
                GameEntity(3, "/c.sfc", "snes", "Super Metroid", "c.sfc", 1, 0),
                GameEntity(4, "/d.gba", "gba", "Metroid Fusion", "d.gba", 1, 0),
            ),
        )
        suspend fun find(text: String) = dao.search(SearchQuery.toFts(text)!!).map { it.title }
        assertEquals(listOf("Chrono Trigger", "Chrono Trigger DS"), find("chr"))
        assertEquals(listOf("Chrono Trigger DS"), find("trig ds"))
        assertEquals(listOf("Metroid Fusion", "Super Metroid"), find("METROID"))
        assertTrue(find("zelda").isEmpty())

        dao.deleteIds(listOf(3))
        assertEquals(listOf("Metroid Fusion"), find("metroid"))
        dao.updateFile(4, "gba", "Metroid Zero Mission", 1, 0)
        assertEquals(listOf("Metroid Zero Mission"), find("zero"))
        assertTrue(find("fusion").isEmpty())
        db.close()
    }

    // endregion

    // region Menu

    @Test fun gamesCategoryOffersSearchOnlyWhenThereAreGames() {
        val games = listOf(GameEntity(1, "/r/snes/a.sfc", "snes", "A", "a.sfc", 10, 0))
        val root = MenuTree(MenuSnapshot(games = games, storageGranted = true)).list(Categories.rootKey(Categories.GAMES))
        assertEquals("Search", root.first().title)
        assertEquals(NodeAction.Run(Command.OpenSearch), root.first().action)
        assertTrue(MenuTree(MenuSnapshot()).list(Categories.rootKey(Categories.GAMES)).none { it.title == "Search" })
    }

    // endregion
}
