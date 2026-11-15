package dev.xross.launcher

import android.content.Context
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import dev.xross.launcher.input.NavAction
import dev.xross.launcher.state.NameEntry
import dev.xross.launcher.state.NamePurpose
import dev.xross.launcher.state.SearchController
import dev.xross.launcher.state.SearchFocus
import dev.xross.launcher.state.XmbNode
import dev.xross.launcher.state.XmbViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Search and name entry out of [XmbViewModel] (step 6). `SearchTest` still drives search through
 * the view model and the real FTS index; these drive [SearchController] on its own, on virtual
 * time, with a fake index.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SearchControllerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val sounds = AppContainer(context).sounds

    private class FakeHost : SearchController.Host {
        val activated = mutableListOf<XmbNode>()
        val options = mutableListOf<XmbNode>()
        val names = mutableListOf<Pair<NamePurpose, String>>()
        override fun activate(node: XmbNode) { activated += node }
        override fun openOptions(node: XmbNode) { options += node }
        override fun submitName(purpose: NamePurpose, text: String) { names += purpose to text }
    }

    private val host = FakeHost()
    private val queries = mutableListOf<String>()
    private var library = listOf(XmbNode("g:1", "Mario"), XmbNode("g:2", "Mario Kart"))

    private fun TestScope.controller() = SearchController(
        scope = backgroundScope,
        sounds = sounds,
        find = { fts -> queries += fts; library },
        host = host,
    )

    private fun SearchController.typeKeys(vararg keyCodes: Int) = keyCodes.forEach { assertTrue(onSearchRawKey(it)) }

    @Test fun typingIsDebouncedAndOneQueryAnswersTheLastText() = runTest {
        val search = controller()
        search.openSearch()
        search.typeKeys(KeyEvent.KEYCODE_M, KeyEvent.KEYCODE_A)
        assertEquals("MA", search.state.value.search!!.query)
        advanceTimeBy(100)
        search.typeKeys(KeyEvent.KEYCODE_R)
        advanceTimeBy(100)
        runCurrent()
        assertEquals("still within the debounce of the last key", emptyList<String>(), queries)
        advanceTimeBy(100)
        runCurrent()
        assertEquals(listOf("mar*"), queries)
        assertEquals(library, search.state.value.search!!.results)
    }

    @Test fun libraryChangesQueryOpenResultsAgainAtOnce() = runTest {
        val search = controller()
        search.onLibraryChanged() // closed: nothing to refresh
        search.openSearch()
        search.onLibraryChanged() // nothing typed yet
        runCurrent()
        assertEquals(emptyList<String>(), queries)

        search.typeKeys(KeyEvent.KEYCODE_M)
        advanceTimeBy(1_000)
        library = listOf(XmbNode("g:1", "Mario"))
        search.onLibraryChanged()
        runCurrent()
        assertEquals(listOf("m*", "m*"), queries)
        assertEquals(library, search.state.value.search!!.results)
    }

    @Test fun resultsGoBackToTheMenuAndCloseCancelsThePendingQuery() = runTest {
        val search = controller()
        search.openSearch()
        search.typeKeys(KeyEvent.KEYCODE_M)
        advanceTimeBy(1_000)
        search.onSearchResultTapped(1) // focus
        assertEquals(SearchFocus.RESULTS, search.state.value.search!!.focus)
        search.onSearchResultTapped(1) // the focused one: launch
        search.onSearchAction(NavAction.OPTIONS)
        assertEquals(listOf(library[1]), host.activated)
        assertEquals(listOf(library[1]), host.options)

        search.focusResult(0)
        assertEquals(0, search.state.value.search!!.resultIndex)

        search.typeKeys(KeyEvent.KEYCODE_DEL)
        search.typeKeys(KeyEvent.KEYCODE_K)
        search.closeSearch()
        advanceTimeBy(1_000)
        assertEquals(listOf("m*"), queries)
        assertNull(search.state.value.search)
        assertFalse(search.onSearchAction(NavAction.CONFIRM))
        assertFalse(search.onSearchRawKey(KeyEvent.KEYCODE_A))
    }

    @Test fun aConfirmedNameIsHandedBackWithItsPurpose() = runTest {
        val search = controller()
        val purpose = NamePurpose.NewAppFolder("org.example.app")
        search.openNameEntry(NameEntry("New folder", purpose))
        assertFalse("a key it doesn't use", search.onNameRawKey(KeyEvent.KEYCODE_TAB))
        search.onNameRawKey(KeyEvent.KEYCODE_G)
        search.onNameRawKey(KeyEvent.KEYCODE_O)
        assertEquals("GO", search.state.value.nameEntry!!.text)
        assertTrue(search.onNameRawKey(KeyEvent.KEYCODE_ENTER))
        assertNull(search.state.value.nameEntry)
        assertEquals(listOf<Pair<NamePurpose, String>>(purpose to "GO"), host.names)

        search.openNameEntry(NameEntry("New folder", purpose))
        assertTrue(search.onNameAction(NavAction.BACK))
        assertNull(search.state.value.nameEntry)
        assertFalse(search.onNameAction(NavAction.BACK))
        assertEquals(1, host.names.size)
    }
}
