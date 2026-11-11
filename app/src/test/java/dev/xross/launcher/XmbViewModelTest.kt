package dev.xross.launcher

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.xross.launcher.input.NavAction
import dev.xross.launcher.state.Categories
import dev.xross.launcher.state.XmbViewModel
import dev.xross.launcher.storage.VolumeEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Constructs the real view model over the real [AppContainer]. Guards against wiring and
 * initialization-order mistakes that pure reducer tests can't see.
 */
@RunWith(RobolectricTestRunner::class)
class XmbViewModelTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun viewModel(storageGranted: Boolean = false): XmbViewModel {
        return XmbViewModel(context, AppContainer(context), storageAccess = { storageGranted }).also { idle() }
    }

    @Test fun startsWithStorageAccessAlreadyGranted() {
        // Exercises folder discovery and the first quiet scan during construction.
        val vm = viewModel(storageGranted = true)
        idle()
        assertEquals(Categories.GAMES, vm.state.value.let { it.categories[it.categoryIndex].id })
    }

    @Test fun startsOnGamesWithoutCrashing() {
        val vm = viewModel()
        val state = vm.state.value
        assertEquals(Categories.GAMES, state.categories[state.categoryIndex].id)
        assertTrue(state.items.isNotEmpty()) // the empty-library hint
        assertNull(state.optionsMenu)
        assertNull(state.search)
    }

    @Test fun lifecycleAndVolumeEventsAreSafeBeforeAnyLibraryExists() {
        val vm = viewModel()
        vm.onForeground()
        vm.onVolumeEvent(VolumeEvent.Mounted("/storage/1A2B-3C4D"))
        vm.onBackground()
        vm.onVolumeEvent(VolumeEvent.Removed("/storage/1A2B-3C4D"))
        vm.onForeground()
        idle()
        assertNotNull(vm.state.value)
    }

    @Test fun categoriesAndSettingsNavigateAndBackReturns() {
        val vm = viewModel()
        repeat(6) { vm.onAction(NavAction.PREV_CATEGORY) }
        idle()
        assertEquals(Categories.SETTINGS, vm.state.value.let { it.categories[it.categoryIndex].id })
        assertEquals("Library", vm.state.value.focused?.title)
        vm.onAction(NavAction.CONFIRM)
        idle()
        assertEquals(listOf("Library"), vm.state.value.breadcrumb)
        assertTrue(vm.state.value.items.any { it.title == "Game fingerprints" })
        vm.onAction(NavAction.BACK)
        idle()
        assertTrue(vm.state.value.breadcrumb.isEmpty())
    }
}
