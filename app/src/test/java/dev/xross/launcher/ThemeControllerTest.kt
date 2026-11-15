package dev.xross.launcher

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.xross.launcher.input.NavAction
import dev.xross.launcher.state.Categories
import dev.xross.launcher.state.ColorSwitch
import dev.xross.launcher.state.Command
import dev.xross.launcher.state.MenuTree
import dev.xross.launcher.state.OptionsMenuState
import dev.xross.launcher.state.SlotEdit
import dev.xross.launcher.state.ThemeController
import dev.xross.launcher.state.UiEffect
import dev.xross.launcher.state.XmbViewModel
import dev.xross.launcher.theme.QuickProfile
import dev.xross.launcher.theme.pack.BadgePill
import dev.xross.launcher.theme.pack.BadgePill.SYSTEM
import dev.xross.launcher.theme.pack.BadgePill.YEAR
import dev.xross.launcher.theme.pack.ComponentRow
import dev.xross.launcher.theme.pack.EffectOverrides
import dev.xross.launcher.theme.pack.EffectSlot
import dev.xross.launcher.theme.pack.ResolvedComponents
import dev.xross.launcher.theme.pack.StatusSide
import dev.xross.launcher.theme.pack.StatusSlot.AVATAR
import dev.xross.launcher.theme.pack.StatusSlot.BATTERY
import dev.xross.launcher.theme.pack.StatusSlot.CLOCK
import dev.xross.launcher.theme.pack.ThemeDefaults
import dev.xross.launcher.theme.pack.UserLook
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Settings ▸ Theme out of [XmbViewModel] (2b.8-prep): the view model's theme tests, unchanged,
 * still pass through its facade; the rest drive [ThemeController] on its own.
 */
@RunWith(RobolectricTestRunner::class)
class ThemeControllerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    // region Through the view model (moved from ComponentsTest and ComponentsTwoBTest)

    @Test fun slotEditsThroughTheViewModelReopenTheListAndTheAvatarSlotGatesTheHud() {
        val container = AppContainer(context)
        val vm = XmbViewModel(context, container, storageAccess = { false })
        fun idle() = shadowOf(Looper.getMainLooper()).idle()
        fun press(a: NavAction) {
            vm.onAction(a)
            idle()
        }
        fun focus(id: String) {
            repeat(40) {
                if (vm.state.value.focused?.id == id) return
                press(NavAction.DOWN)
            }
            error("no row $id")
        }
        idle()
        while (vm.state.value.let { it.categories[it.categoryIndex].id } != Categories.SETTINGS) press(NavAction.LEFT)
        focus(MenuTree.DISPLAY)
        press(NavAction.CONFIRM)
        focus(MenuTree.DISPLAY_COMPONENTS)
        press(NavAction.CONFIRM)
        focus("disp:comp:right")
        // Confirm opens the slot list; Clock ▸ Move right swaps it with the battery.
        press(NavAction.CONFIRM)
        val menu = vm.state.value.optionsMenu!!
        assertEquals("Status bar right", menu.title)
        press(NavAction.DOWN)
        assertEquals("Clock", vm.state.value.optionsMenu!!.focused!!.label)
        press(NavAction.CONFIRM)
        assertEquals("Move right", vm.state.value.optionsMenu!!.focused!!.label)
        press(NavAction.CONFIRM)
        assertEquals(listOf("BATTERY", "CLOCK", "AVATAR"), container.settings.current.componentOverrides.statusBarRight)
        assertNull("The left keeps following the pack", container.settings.current.componentOverrides.statusBarLeft)
        // Reopened on the clock's moves: it can't go further right, so Move left has the cursor.
        val reopened = vm.state.value.optionsMenu!!
        assertEquals(2, reopened.depth)
        assertEquals("Move left", reopened.focused!!.label)
        press(NavAction.BACK)
        press(NavAction.BACK)
        assertNull(vm.state.value.optionsMenu)

        // Without an AVATAR slot, Up at the top of a list doesn't open the HUD.
        container.settings.update { it.copy(componentOverrides = it.componentOverrides.withSlots(StatusSide.RIGHT, listOf(CLOCK, BATTERY))) }
        idle()
        assertFalse(vm.components.value.avatar)
        press(NavAction.BACK)
        press(NavAction.BACK)
        repeat(20) { if (vm.state.value.focusedIndex > 0) press(NavAction.UP) }
        assertEquals(0, vm.state.value.depth)
        assertEquals(0, vm.state.value.focusedIndex)
        press(NavAction.UP)
        assertFalse(vm.state.value.avatarFocused)
        container.settings.update { ThemeDefaults.reset(it) }
        idle()
        assertTrue(vm.components.value.avatar)
        press(NavAction.UP)
        assertTrue(vm.state.value.avatarFocused)
    }

    @Test fun titleBadgesThroughTheViewModel() {
        val container = AppContainer(context)
        val vm = XmbViewModel(context, container, storageAccess = { false })
        fun idle() = shadowOf(Looper.getMainLooper()).idle()
        fun press(a: NavAction) {
            vm.onAction(a)
            idle()
        }
        fun focus(id: String) {
            repeat(40) {
                if (vm.state.value.focused?.id == id) return
                press(NavAction.DOWN)
            }
            error("no row $id")
        }
        idle()
        while (vm.state.value.let { it.categories[it.categoryIndex].id } != Categories.SETTINGS) press(NavAction.LEFT)
        focus(MenuTree.DISPLAY)
        press(NavAction.CONFIRM)
        focus(MenuTree.DISPLAY_COMPONENTS)
        press(NavAction.CONFIRM)
        focus("disp:comp:pills")
        assertEquals("None (theme pack) · Beside the focused game's title", vm.state.value.focused!!.subtitle)
        press(NavAction.CONFIRM)
        assertEquals("Title badges", vm.state.value.optionsMenu!!.title)
        // Release year, then System: drawn in the order they were turned on.
        repeat(3) { press(NavAction.DOWN) }
        assertEquals("Release year", vm.state.value.optionsMenu!!.focused!!.label)
        press(NavAction.CONFIRM)
        assertEquals(listOf("YEAR"), container.settings.current.componentOverrides.badgePills)
        assertEquals("Reopened on the badge", "Release year", vm.state.value.optionsMenu!!.focused!!.label)
        assertTrue(vm.state.value.optionsMenu!!.focused!!.selected)
        press(NavAction.UP)
        press(NavAction.CONFIRM)
        assertEquals(listOf(YEAR, SYSTEM), vm.components.value.pills)
        press(NavAction.CONFIRM)
        assertEquals("Off again", listOf(YEAR), vm.components.value.pills)
        container.settings.update { ThemeDefaults.reset(it) }
        idle()
        assertEquals(emptyList<BadgePill>(), vm.components.value.pills)
        assertNull(container.settings.current.componentOverrides.badgePills)
    }
    // endregion

    // region The controller on its own

    private class FakeHost : ThemeController.Host {
        val toasts = mutableListOf<String>()
        val fails = mutableListOf<String>()
        val effects = mutableListOf<UiEffect>()
        val menus = mutableListOf<OptionsMenuState>()
        var refused = 0
        val friends = mutableListOf<ResolvedComponents>()
        val photos = mutableMapOf<Long, Uri>()
        override fun toast(message: String) { toasts += message }
        override fun fail(message: String) { fails += message }
        override fun refuse() { refused++ }
        override fun emit(effect: UiEffect) { effects += effect }
        override fun showOptions(menu: OptionsMenuState) { menus += menu }
        override fun components() = ResolvedComponents.DEFAULT
        override fun friendsWanted(components: ResolvedComponents) { friends += components }
        override fun photoUri(photoId: Long) = photos[photoId]
        var optionsDismissed = 0
        override fun dismissOptions() { optionsDismissed++ }
    }

    private val scope = MainScope()

    @After fun tearDown() = scope.cancel()

    private fun controller(host: FakeHost, container: AppContainer = AppContainer(context)) = ThemeController(
        context = context,
        scope = scope,
        themes = container.themes,
        packs = container.themePacks,
        settings = container.settings,
        environment = container.environment,
        credentials = container.credentials,
        sounds = container.sounds,
        host = host,
    )

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun await(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            idle()
            if (condition()) return
            if (System.currentTimeMillis() > deadline) fail("Timed out waiting for $what")
            Thread.sleep(10)
        }
    }

    /** One of each theme command the view model forwards: the controller takes them all (the view model checks it). */
    @Test fun takesEveryThemeCommandAndNothingElse() {
        val container = AppContainer(context)
        val c = controller(FakeHost(), container)
        val theirs = listOf(
            Command.CycleTheme, Command.SetTheme("LAVENDER"), Command.CycleThemePack, Command.SetThemePack("default"),
            Command.ReloadThemePacks, Command.CycleThemeVariant, Command.SetThemeVariant(""), Command.ImportThemeArchive,
            Command.ReplaceImportedTheme, Command.KeepBothImportedThemes, Command.CycleQuickProfile, Command.SetQuickProfile("NONE"),
            Command.ToggleColorSwitch(ColorSwitch.CIRCADIAN), Command.SetColorSwitch(ColorSwitch.CIRCADIAN, null), Command.CycleSurfaceStyle,
            Command.SetXrossWallpaper(1L), Command.ResetXrossWallpaper, Command.PickXrossWallpaper,
            Command.SetStatusSlots(StatusSide.RIGHT, null), Command.EditStatusSlot(StatusSide.RIGHT, CLOCK, SlotEdit.LATER),
            Command.CycleComponent(ComponentRow.CLOCK), Command.SetComponent(ComponentRow.CLOCK, null), Command.SetBadgePills(null),
            Command.ToggleBadgePill(YEAR), Command.CycleEffect(EffectSlot.SHADER), Command.SetEffect(EffectSlot.SHADER, ""),
            Command.ResetThemeDefaults, Command.OpenLayoutSettings,
        )
        theirs.forEach { assertTrue("$it", c.onCommand(it)) }
        idle()
        val before = container.settings.current
        for (other in listOf(Command.ToggleWave, Command.OpenWaveSettings, Command.ShowOptions, Command.ToggleSoundEffects)) {
            assertFalse("$other", c.onCommand(other))
        }
        assertEquals(before, container.settings.current)
    }

    @Test fun resetSaysWhenThereIsNothingToClearAndKeepsWhatIsNotTheUserLayer() {
        val container = AppContainer(context)
        val host = FakeHost()
        val c = controller(host, container)
        c.onCommand(Command.ResetThemeDefaults)
        assertEquals(listOf("Already the theme's own look"), host.toasts)

        container.settings.update { it.copy(quickProfile = QuickProfile.OLED_TRUE_BLACK.name, waveSpeed = 1.6f) }
        c.onCommand(Command.SetEffect(EffectSlot.FOCUS_FRAME, "GOLD_BEZEL"))
        c.onCommand(Command.SetColorSwitch(ColorSwitch.BATTERY, true))
        c.onCommand(Command.CycleSurfaceStyle)
        assertEquals(listOf("1 effect", "surface", "1 color layer"), UserLook.of(container.settings.current).changes())
        c.onCommand(Command.ResetThemeDefaults)
        assertEquals("Back to ${container.themes.active.value.info.name}'s own look", host.toasts.last())
        val after = container.settings.current
        assertTrue(UserLook.of(after).isNone)
        assertEquals(QuickProfile.OLED_TRUE_BLACK.name, after.quickProfile)
        assertEquals(1.6f, after.waveSpeed)
    }

    @Test fun aColorSwitchTheQuickProfileHoldsOffIsSavedWithANote() {
        val container = AppContainer(context)
        val host = FakeHost()
        val c = controller(host, container)
        c.onCommand(Command.ToggleColorSwitch(ColorSwitch.CIRCADIAN))
        assertEquals(true, container.settings.current.circadianCurve)
        assertTrue(host.toasts.isEmpty())
        container.settings.update { it.copy(quickProfile = QuickProfile.MONOCHROME_EINK.name) }
        c.onCommand(Command.SetColorSwitch(ColorSwitch.ADAPTIVE, false))
        assertEquals(false, container.settings.current.adaptiveGamePalette)
        assertEquals(listOf("Saved; ${QuickProfile.MONOCHROME_EINK.label} keeps it off"), host.toasts)
    }

    @Test fun effectsSaveNormalizedWithANoteWhereTheyCantShow() {
        val container = AppContainer(context)
        val host = FakeHost()
        val c = controller(host, container)
        container.settings.update { it.copy(waveEnabled = false) }
        c.onCommand(Command.SetEffect(EffectSlot.SHADER, "bokeh"))
        assertEquals(EffectOverrides(shaderPreset = "BOKEH"), container.settings.current.effectOverrides)
        assertEquals("Turn Wave background on to see it", host.toasts.last())
        c.onCommand(Command.CycleEffect(EffectSlot.BOOT_INTRO))
        assertEquals("VIDEO", container.settings.current.effectOverrides.bootIntro)
        assertEquals("Shown on the next cold start", host.toasts.last())
    }

    @Test fun theTopLeftAndTelemetryRowsAskForFollowedUsers() {
        val host = FakeHost()
        val c = controller(host)
        c.onCommand(Command.SetComponent(ComponentRow.CLOCK, "H24"))
        assertTrue(host.friends.isEmpty())
        c.onCommand(Command.CycleComponent(ComponentRow.TOP_LEFT))
        c.onCommand(Command.CycleComponent(ComponentRow.TELEMETRY))
        assertEquals(2, host.friends.size)
    }

    @Test fun wallpaperAndPickersGoThroughTheHost() {
        val container = AppContainer(context)
        val host = FakeHost()
        val c = controller(host, container)
        c.onCommand(Command.SetXrossWallpaper(9L))
        assertEquals("No such photo: nothing saved", "", container.settings.current.customWallpaper)
        host.photos[7L] = Uri.parse("content://media/external/images/media/7")
        c.onCommand(Command.SetXrossWallpaper(7L))
        assertEquals("content://media/external/images/media/7", container.settings.current.customWallpaper)
        assertEquals("Xross wallpaper set", host.toasts.last())
        c.onCommand(Command.ResetXrossWallpaper)
        assertEquals("", container.settings.current.customWallpaper)

        c.onCommand(Command.ImportThemeArchive)
        c.onCommand(Command.PickXrossWallpaper)
        assertEquals(listOf(UiEffect.PickThemeArchive, UiEffect.PickWallpaperImage), host.effects)
        c.onCommand(Command.ReplaceImportedTheme)
        assertEquals("Pick the theme file again", host.toasts.last())
    }

    @Test fun aSlotThatCantMoveIsRefused() {
        val host = FakeHost()
        val c = controller(host)
        // The pack's right side ends on the avatar: it can't move further right.
        c.onCommand(Command.EditStatusSlot(StatusSide.RIGHT, AVATAR, SlotEdit.LATER))
        assertEquals(1, host.refused)
        assertTrue(host.menus.isEmpty())
        c.onCommand(Command.EditStatusSlot(StatusSide.RIGHT, CLOCK, SlotEdit.LATER))
        assertEquals("Status bar right", host.menus.single().title)
    }

    @Test fun anUnknownPackFallsBackToDefault() {
        val container = AppContainer(context)
        val host = FakeHost()
        val c = controller(host, container)
        c.onCommand(Command.SetThemePack("no-such-pack"))
        await("the fallback") { host.fails.isNotEmpty() }
        assertEquals(listOf("Couldn't load that theme pack; using Default"), host.fails)
        assertEquals("default", container.settings.current.themePack)
    }

    @Test fun anUnknownColorThemeIsIgnored() {
        val container = AppContainer(context)
        val c = controller(FakeHost(), container)
        val before = container.settings.current.theme
        c.onCommand(Command.SetTheme("NOT_A_THEME"))
        assertEquals(before, container.settings.current.theme)
    }

    // endregion
}
