package dev.xross.launcher

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.xross.launcher.data.GameEntity
import dev.xross.launcher.input.ConnectedController
import dev.xross.launcher.input.ControllerKey
import dev.xross.launcher.input.NavAction
import dev.xross.launcher.state.Categories
import dev.xross.launcher.state.RemapSession
import dev.xross.launcher.state.XmbUiState
import dev.xross.launcher.state.XmbViewModel
import dev.xross.launcher.storage.VolumeEvent
import kotlinx.coroutines.runBlocking
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
 * Constructs the real view model over the real [AppContainer]. Guards against wiring and
 * initialization-order mistakes that pure reducer tests can't see.
 */
@RunWith(RobolectricTestRunner::class)
class XmbViewModelTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun viewModel(storageGranted: Boolean = false, container: AppContainer = AppContainer(context)): XmbViewModel {
        return XmbViewModel(context, container, storageAccess = { storageGranted }).also { idle() }
    }

    /** Room and Dispatchers.IO work finishes off the main thread; pump the looper until it lands. */
    private fun XmbViewModel.await(what: String, timeoutMs: Long = 5_000, condition: (XmbUiState) -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            idle()
            if (condition(state.value)) return
            if (System.currentTimeMillis() > deadline) fail("Timed out waiting for $what: ${state.value}")
            Thread.sleep(10)
        }
    }

    private fun XmbViewModel.press(action: NavAction, times: Int = 1) = repeat(times) {
        onAction(action)
        idle()
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

    @Test fun informationScreenOpensFromTheOptionsMenuAndCloses() {
        val container = AppContainer(context)
        runBlocking {
            container.database.games().insertAll(
                listOf(GameEntity(path = "/storage/emulated/0/ROMs/snes/ct.sfc", platformId = "snes", title = "Chrono Trigger", fileName = "ct.sfc", sizeBytes = 4096, lastModified = 0)),
            )
        }
        val vm = viewModel(container = container)
        // Platforms are grouped under All Games, which the Games root focuses first (below Search).
        vm.await("the All Games folder") { s -> s.items.any { it.title == "All Games" } }
        assertEquals("All Games", vm.state.value.focused?.title)
        vm.press(NavAction.CONFIRM)
        assertEquals("Super Nintendo", vm.state.value.focused?.title)
        assertEquals(listOf("All Games"), vm.state.value.breadcrumb)
        vm.press(NavAction.CONFIRM)
        assertEquals("Chrono Trigger", vm.state.value.focused?.title)

        vm.press(NavAction.OPTIONS)
        val menu = vm.state.value.optionsMenu!!
        val infoIndex = menu.current.options.indexOfFirst { it.label == "Information" }
        vm.press(NavAction.DOWN, times = infoIndex)
        vm.press(NavAction.CONFIRM)
        assertNull(vm.state.value.optionsMenu)

        vm.await("the Information screen") { it.info != null }
        val info = vm.state.value.info!!
        assertEquals("Chrono Trigger", info.title)
        assertTrue(info.rows.any { it.label == "Platform" && it.value == "Super Nintendo" })
        assertTrue(info.rows.any { it.label == "File" && it.value == "ct.sfc" })

        // Up/down move the highlight and don't leak into the list underneath.
        vm.press(NavAction.DOWN, times = 2)
        assertEquals(2, vm.state.value.info?.index)
        assertEquals("Chrono Trigger", vm.state.value.focused?.title)

        vm.press(NavAction.BACK)
        assertNull(vm.state.value.info)
        // Back only closed the sheet: still inside the platform list.
        assertEquals(listOf("All Games", "Super Nintendo"), vm.state.value.breadcrumb)
    }

    @Test fun remapWizardSavesAProfileForTheControllerUsed() {
        val container = AppContainer(context)
        val vm = viewModel(container = container)
        vm.press(NavAction.PREV_CATEGORY, times = 6)
        vm.press(NavAction.DOWN) // Controls
        assertEquals("Controls", vm.state.value.focused?.title)
        vm.press(NavAction.CONFIRM)
        val remapRow = vm.state.value.items.indexOfFirst { it.id == "ctl:remap" }
        vm.press(NavAction.DOWN, times = remapRow)
        vm.press(NavAction.CONFIRM)
        assertNotNull(vm.state.value.remap)

        val pad = ConnectedController(ControllerKey(0x054C, 0x0CE6), "DualSense Wireless Controller")
        val other = ConnectedController(ControllerKey(0x045E, 0x0B13), "Xbox Wireless Controller")
        RemapSession.STEPS.forEachIndexed { i, _ ->
            assertTrue(vm.onRawKeyDown(100 + i, pad))
            idle()
            if (i < RemapSession.STEPS.lastIndex) {
                // Presses from a second pad mid-session are swallowed and change nothing.
                assertTrue(vm.onRawKeyDown(300 + i, other))
                assertEquals(i + 1, vm.state.value.remap?.step)
            }
        }
        assertNull(vm.state.value.remap)
        // Once the wizard is done, the other pad's presses reach normal input again.
        assertFalse(vm.onRawKeyDown(400, other))
        val settings = container.settings.current
        assertTrue(settings.keyRemap.isEmpty())
        val profile = settings.controllerProfiles.getValue("054c:0ce6")
        assertEquals("DualSense Wireless Controller", profile.name)
        assertEquals((100 until 100 + RemapSession.STEPS.size).toSet(), profile.remap.keys)

        // The input config hands that profile to the DualSense only.
        assertEquals(profile.remap, vm.inputConfig.remapFor(pad.key))
        assertTrue(vm.inputConfig.remapFor(other.key).isEmpty())
        vm.await("the saved controller row") { s -> s.items.any { it.id == "ctl:profile:054c:0ce6" } }
    }

    @Test fun deadzoneCyclesThroughHardwareOnly() {
        val container = AppContainer(context)
        val vm = viewModel(container = container)
        vm.press(NavAction.PREV_CATEGORY, times = 6) // Settings is the first category
        vm.await("the Settings root") { s -> s.items.any { it.title == "Controls" } }
        vm.press(NavAction.DOWN, times = vm.state.value.items.indexOfFirst { it.title == "Controls" } - vm.state.value.focusedIndex)
        vm.press(NavAction.CONFIRM)
        vm.await("the Controls list") { s -> s.items.any { it.id == "ctl:deadzone" } }
        val row = vm.state.value.items.indexOfFirst { it.id == "ctl:deadzone" }
        vm.press(NavAction.DOWN, times = row - vm.state.value.focusedIndex)
        assertEquals(0.35f, container.settings.current.deadzone)
        vm.press(NavAction.CONFIRM, times = 3) // 45% → 55% → off
        assertEquals(0f, container.settings.current.deadzone)
        vm.await("the hardware-only label") { s -> s.focused?.subtitle == "Off — hardware only" }
        assertEquals(0f, vm.inputConfig.deadzone)
        vm.press(NavAction.CONFIRM)
        assertEquals(0.15f, container.settings.current.deadzone)
    }

    /** Settings ▸ Theme ▸ Wave settings: the panel opens, D-pad changes save live, Back closes. */
    @Test fun waveSettingsPanelAdjustsTheShaderLive() {
        val container = AppContainer(context)
        val vm = viewModel(container = container)
        vm.press(NavAction.PREV_CATEGORY, times = 6)
        vm.await("the Settings root") { s -> s.items.any { it.title == "Theme" } }
        vm.press(NavAction.DOWN, times = vm.state.value.items.indexOfFirst { it.title == "Theme" } - vm.state.value.focusedIndex)
        vm.press(NavAction.CONFIRM)
        vm.await("the Theme list") { s -> s.items.any { it.id == "disp:wavesettings" } }
        assertEquals("disp:pack", vm.state.value.items.first().id)
        vm.press(NavAction.DOWN, times = vm.state.value.items.indexOfFirst { it.id == "disp:wavesettings" } - vm.state.value.focusedIndex)
        vm.press(NavAction.CONFIRM)
        assertNotNull(vm.state.value.waveEditor)

        vm.press(NavAction.RIGHT, times = 2) // Speed 1.0 → 1.2
        assertEquals(1.2f, container.settings.current.waveSpeed)
        assertEquals(1.2f, vm.state.value.settings.waveSpeed)
        vm.press(NavAction.DOWN) // Ribbon lines
        vm.press(NavAction.LEFT)
        assertEquals(4, container.settings.current.waveLineCount)
        vm.press(NavAction.BACK)
        assertNull(vm.state.value.waveEditor)
        // Saved to settings.json like any setting.
        container.settings.flush()
        val json = container.environment.current.settingsFile.readText()
        assertTrue(json, json.contains("\"waveSpeed\": 1.2") && json.contains("\"waveLines\": 4"))
    }

    /** Confirm on an information row is refused (system_ng) rather than ignored, and nothing opens. */
    @Test fun refusedActionsLeaveTheMenuAsItWas() {
        val vm = viewModel()
        vm.press(NavAction.PREV_CATEGORY, times = 6)
        vm.await("the Settings root") { s -> s.items.any { it.title == "System" } }
        vm.press(NavAction.DOWN, times = vm.state.value.items.indexOfFirst { it.title == "System" } - vm.state.value.focusedIndex)
        vm.press(NavAction.CONFIRM)
        vm.await("the System list") { s -> s.items.any { it.id == "sys:version" } }
        vm.press(NavAction.UP, times = vm.state.value.focusedIndex)
        assertEquals("sys:version", vm.state.value.focused?.id)
        val before = vm.state.value
        vm.press(NavAction.CONFIRM)
        vm.press(NavAction.OPTIONS)
        assertNull(vm.state.value.optionsMenu)
        assertEquals(before.items, vm.state.value.items)
        assertEquals(before.focusedIndex, vm.state.value.focusedIndex)
    }

    @Test fun mediaButtonWithNothingPlayingExplainsItself() {
        val vm = viewModel()
        vm.press(NavAction.MEDIA)
        assertNull(vm.state.value.overlay)
        assertEquals("Nothing is playing — choose music in the Music category", vm.state.value.toast)
    }

    @Test fun buttonHintSettingReachesTheUiState() {
        val container = AppContainer(context)
        val vm = viewModel(container = container)
        assertTrue(vm.state.value.showButtonHints)
        container.settings.update { it.copy(showButtonHints = false) }
        vm.await("hints hidden") { !it.showButtonHints }
    }

    @Test fun busyClearsOnceTheFirstScanFinishes() {
        val vm = viewModel(storageGranted = true)
        vm.await("the scan to finish") { !it.busy }
        assertFalse(vm.state.value.busy)
    }
}
