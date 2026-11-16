package dev.xross.launcher

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.xross.launcher.input.NavAction
import dev.xross.launcher.state.Categories
import dev.xross.launcher.state.MenuTree
import dev.xross.launcher.state.NodeAction
import dev.xross.launcher.state.XmbEvent
import dev.xross.launcher.state.XmbUiState
import dev.xross.launcher.state.XmbViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The one event sink (step 7 of the architecture assessment): every touch reaches its owner
 * through [XmbViewModel.onEvent], the view model's own and each controller's group alike.
 */
@RunWith(RobolectricTestRunner::class)
class XmbEventTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun newViewModel() = XmbViewModel(context, AppContainer(context), storageAccess = { false }).also { idle() }

    private fun XmbViewModel.send(event: XmbEvent) {
        onEvent(event)
        idle()
    }

    private fun XmbViewModel.await(what: String, condition: (XmbUiState) -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (true) {
            idle()
            if (condition(state.value)) return
            if (System.currentTimeMillis() > deadline) fail("Timed out waiting for $what: ${state.value}")
            Thread.sleep(10)
        }
    }

    private fun XmbViewModel.toSettings() {
        while (state.value.let { it.categories[it.categoryIndex].id } != Categories.SETTINGS) send(XmbEvent.Nav(NavAction.LEFT))
        await("the Settings root") { it.items.size > 1 }
    }

    @Test fun navTakesTheSamePathAsAKey() {
        val vm = newViewModel()
        val start = vm.state.value.categoryIndex
        vm.send(XmbEvent.Nav(NavAction.RIGHT))
        assertEquals(start + 1, vm.state.value.categoryIndex)
        vm.onAction(NavAction.LEFT)
        idle()
        assertEquals(start, vm.state.value.categoryIndex)
    }

    @Test fun menuEventsMoveTheColumns() {
        val vm = newViewModel()
        vm.toSettings()
        val settings = vm.state.value.categoryIndex

        // A tap on another item moves the cursor there; it doesn't activate it.
        vm.send(XmbEvent.Menu.ItemTap(1))
        assertEquals(1, vm.state.value.focusedIndex)
        assertEquals(settings, vm.state.value.categoryIndex)

        // A tap on another category goes there.
        vm.send(XmbEvent.Menu.CategoryTap(settings + 1))
        assertEquals(settings + 1, vm.state.value.categoryIndex)

        // Closing panels that aren't open does nothing.
        listOf(XmbEvent.Menu.CloseOptions, XmbEvent.Menu.CloseInfo, XmbEvent.Menu.CloseDetails, XmbEvent.Menu.CancelRemap)
            .forEach { vm.send(it) }
        assertNull(vm.state.value.optionsMenu)
        assertEquals(settings + 1, vm.state.value.categoryIndex)
    }

    @Test fun controllerGroupsReachTheirControllers() {
        val vm = newViewModel()
        vm.toSettings()

        // Editor: open Profile customization with keys, then a slider and the close go by touch.
        val theme = vm.state.value.items.indexOfFirst { (it.action as? NodeAction.OpenList)?.key == MenuTree.DISPLAY }
        vm.send(XmbEvent.Menu.ItemTap(theme))
        vm.send(XmbEvent.Menu.ItemTap(theme)) // the focused one: open it
        vm.await("the Theme list") { s -> s.items.any { it.id == MenuTree.DISPLAY_MENU } }
        val menu = vm.state.value.items.indexOfFirst { it.id == MenuTree.DISPLAY_MENU }
        vm.send(XmbEvent.Menu.ItemTap(menu))
        vm.send(XmbEvent.Menu.ItemTap(menu))
        vm.await("Theme ▸ Menu and profile") { s -> s.items.any { it.id == "disp:avatar" } }
        val avatar = vm.state.value.items.indexOfFirst { it.id == "disp:avatar" }
        vm.send(XmbEvent.Menu.ItemTap(avatar))
        vm.send(XmbEvent.Menu.ItemTap(avatar))
        assertNotNull(vm.state.value.avatarEditor)
        vm.send(XmbEvent.Editor.AvatarRowTap(1))
        assertEquals(1, vm.state.value.avatarEditor!!.index)
        vm.send(XmbEvent.Editor.CloseAvatar)
        assertNull(vm.state.value.avatarEditor)

        // RetroAchievements: the avatar HUD opens and closes.
        vm.send(XmbEvent.Ra.AvatarTap)
        assertTrue(vm.state.value.avatarFocused)
        vm.send(XmbEvent.Ra.CloseAvatarHud)
        assertFalse(vm.state.value.avatarFocused)

        // The other groups with nothing open: no panel appears, nothing throws.
        listOf(
            XmbEvent.Layout.Close, XmbEvent.Editor.CloseWave, XmbEvent.Ra.CloseHub, XmbEvent.Ra.CloseAchievements,
            XmbEvent.Typing.SearchResultTap(0), XmbEvent.Boot.Skip,
        ).forEach { vm.send(it) }
        assertNull(vm.state.value.layoutEditor)
        assertNull(vm.state.value.waveEditor)
        assertNull(vm.state.value.search)
        assertNull(vm.state.value.boot)
    }
}
