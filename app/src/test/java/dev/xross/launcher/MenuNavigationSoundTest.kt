package dev.xross.launcher

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.xross.launcher.audio.UiSound
import dev.xross.launcher.input.NavAction
import dev.xross.launcher.state.Categories
import dev.xross.launcher.state.XmbViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Which effect the XMB asks for (Phase 2b.5a): Left / Right between categories plays [UiSound.CATEGORY]
 * leaning toward the move, entering and leaving a folder play [UiSound.DRILL_IN] / [UiSound.DRILL_OUT],
 * and running an item still plays [UiSound.ENTER].
 */
@RunWith(RobolectricTestRunner::class)
class MenuNavigationSoundTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private val container = AppContainer(context)
    private val played = ArrayList<Pair<UiSound, Float>>()
    private val vm: XmbViewModel by lazy {
        container.sounds.requested = { sound, pan -> played += sound to pan }
        XmbViewModel(context, container, storageAccess = { false }).also { idle() }
    }

    private fun press(action: NavAction, times: Int = 1): List<UiSound> {
        played.clear()
        repeat(times) {
            vm.onAction(action)
            idle()
        }
        return played.map { it.first }
    }

    private val category get() = vm.state.value.let { it.categories[it.categoryIndex].id }

    @Test fun leftAndRightBetweenCategoriesPlayTheCategorySwitch() {
        vm
        container.sounds.setCrosshair(0.5f)
        assertEquals(Categories.GAMES, category)
        assertEquals(listOf(UiSound.CATEGORY), press(NavAction.RIGHT))
        assertEquals(0.15f, played.single().second, 1e-6f)
        assertEquals(listOf(UiSound.CATEGORY), press(NavAction.LEFT))
        assertEquals(-0.15f, played.single().second, 1e-6f)
        assertEquals(listOf(UiSound.CATEGORY), press(NavAction.NEXT_CATEGORY))
        assertEquals(listOf(UiSound.CATEGORY), press(NavAction.PREV_CATEGORY))
        // A vertical step is still the cursor tick.
        assertEquals(listOf(UiSound.MOVE), press(NavAction.DOWN))
    }

    @Test fun enteringAndLeavingAFolderPlayTheDrillRoles() {
        press(NavAction.PREV_CATEGORY, times = 6)
        assertEquals(Categories.SETTINGS, category)
        assertEquals("Library", vm.state.value.focused?.title)
        assertEquals(listOf(UiSound.DRILL_IN), press(NavAction.CONFIRM))
        assertEquals(listOf("Library"), vm.state.value.breadcrumb)
        // Into a folder inside it with Right, out again with Left, then Back to the root.
        val dats = vm.state.value.items.indexOfFirst { it.title == "DAT files" }
        press(NavAction.DOWN, times = dats - vm.state.value.focusedIndex)
        assertEquals(listOf(UiSound.DRILL_IN), press(NavAction.RIGHT))
        assertEquals(listOf("Library", "DAT files"), vm.state.value.breadcrumb)
        assertEquals(listOf(UiSound.DRILL_OUT), press(NavAction.LEFT))
        assertEquals(listOf(UiSound.DRILL_OUT), press(NavAction.BACK))
        assertTrue(vm.state.value.breadcrumb.isEmpty())
        // Back at a category root has nothing to leave: no sound.
        assertEquals(emptyList<UiSound>(), press(NavAction.BACK))
    }

    @Test fun tappingTheOpenCategoryToLeaveItsFolderPlaysDrillOut() {
        press(NavAction.PREV_CATEGORY, times = 6)
        press(NavAction.CONFIRM)
        played.clear()
        vm.onCategoryTapped(vm.state.value.categoryIndex)
        idle()
        assertEquals(listOf(UiSound.DRILL_OUT), played.map { it.first })
        assertTrue(vm.state.value.breadcrumb.isEmpty())
    }

    @Test fun runningAnItemStillPlaysEnter() {
        press(NavAction.PREV_CATEGORY, times = 6)
        press(NavAction.CONFIRM)
        val row = vm.state.value.items.indexOfFirst { it.title == "Game fingerprints" }
        press(NavAction.DOWN, times = row - vm.state.value.focusedIndex)
        assertEquals("Game fingerprints", vm.state.value.focused?.title)
        val sounds = press(NavAction.CONFIRM)
        assertEquals(UiSound.ENTER, sounds.first())
        assertTrue(UiSound.DRILL_IN !in sounds)
    }
}
