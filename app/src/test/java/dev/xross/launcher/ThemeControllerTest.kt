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
import dev.xross.launcher.state.NameEntry
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
import dev.xross.launcher.state.NamePurpose
import dev.xross.launcher.theme.XrossTheme
import dev.xross.launcher.theme.pack.ExportScope
import dev.xross.launcher.theme.pack.LayoutOverrides
import dev.xross.launcher.theme.pack.LookExporter
import java.io.File
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
        var nameEntry: NameEntry? = null
        override fun openNameEntry(entry: NameEntry) { nameEntry = entry }
        val busy = mutableListOf<Boolean>()
        override fun busy(on: Boolean) { busy += on }
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
            Command.ResetThemeDefaults, Command.OpenLayoutSettings, Command.ExportTheme, Command.ExportThemePack("default"),
            Command.ChooseExportScope("default", ExportScope.WholePack), Command.ConfirmThemeExport, Command.SwitchToExportedTheme,
            Command.ShareThemePack("default"), Command.ShareExportedTheme,
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

    // region Export (2b.8b)

    private fun labels(menu: OptionsMenuState) = menu.current.options.map { it.label }

    @Test fun theScopeListPerPack() {
        val container = AppContainer(context)
        val host = FakeHost()
        val c = controller(host, container)
        // The built-in pack: no variants, so no variant rows; it is active, so Current look.
        c.onCommand(Command.ExportTheme)
        val default = host.menus.last()
        assertEquals("Export Default", default.title)
        assertEquals(listOf("Whole pack", "Current look"), labels(default))
        assertEquals("Default, as it ships", default.current.options[0].detail)
        assertEquals("What is on screen", default.current.options[1].detail)
        container.settings.update { it.copy(effectOverrides = EffectOverrides(shaderPreset = "BOKEH"), layoutOverrides = LayoutOverrides(uiScale = 1.2f)) }
        c.onCommand(Command.ExportTheme)
        assertEquals("With your picks: 1 layout value, 1 effect", host.menus.last().current.options[1].detail)

        // Another pack: a row per variant (the base look included), and no Current look.
        StressPack.write(container.environment.current.themes)
        c.onCommand(Command.ExportThemePack(StressPack.ID))
        await("the stress pack's scopes") { host.menus.last().title == "Export Stress" }
        val stress = host.menus.last()
        assertEquals(listOf("Whole pack", "Base look only", "Night only", "Day only"), labels(stress))
        assertEquals("Stress, 2 variants", stress.current.options[0].detail)
        assertEquals(Command.ChooseExportScope(StressPack.ID, ExportScope.Variant("night")), stress.current.options[2].command)
        c.onCommand(Command.ChooseExportScope(StressPack.ID, ExportScope.CurrentLook))
        assertEquals("Current look is the active pack's only", 1, host.refused)
        assertNull(host.nameEntry)

        c.onCommand(Command.ExportThemePack("no-such-pack"))
        await("the failure") { host.fails.isNotEmpty() }
        assertEquals("Couldn't load no-such-pack to export it", host.fails.last())
    }

    @Test fun settingsThemeOffersExportAfterResetAndThePackRowForAnyPack() {
        val setup = dev.xross.launcher.state.ThemeSetup(packs = listOf("default" to "Default", "stress" to "Stress"))
        val rows = MenuTree(dev.xross.launcher.state.MenuSnapshot(themes = setup)).list(MenuTree.DISPLAY)
        val ids = rows.map { it.id }
        assertEquals(ids.indexOf("disp:reset") + 1, ids.indexOf("disp:export"))
        val export = rows.single { it.id == "disp:export" }
        assertEquals("Export theme…", export.title)
        assertEquals("Save Default or your look as a new pack in Xross/themes", export.subtitle)
        val packMenu = rows.single { it.id == "disp:pack" }.menu.single { it.label == "Export a pack…" }
        assertEquals(listOf(Command.ExportThemePack("default"), Command.ExportThemePack("stress")), packMenu.submenu.map { it.command })
    }

    /** Scope → name entry → confirm → write, as the menu drives it; returns the new pack's id. */
    private fun export(c: ThemeController, host: FakeHost, packId: String, scope: ExportScope, name: String? = null): String {
        c.onCommand(Command.ChooseExportScope(packId, scope))
        val entry = host.nameEntry!!
        assertEquals("Name the exported theme", entry.title)
        val typed = name ?: entry.text
        c.onExportNamed(entry.purpose as NamePurpose.ExportTheme, typed)
        await("the confirm panel") { host.menus.lastOrNull()?.title == "Export as $typed" }
        val confirm = host.menus.last()
        assertEquals(listOf("Export", "Licences"), labels(confirm))
        assertEquals(LookExporter.LICENCE_NOTE, confirm.current.options[1].detail)
        c.onCommand(Command.ConfirmThemeExport)
        await("the export") { host.toasts.lastOrNull() == "Saved as $typed in Xross/themes" }
        val saved = host.menus.last()
        assertEquals("$typed saved", saved.title)
        assertEquals("Switch to $typed", saved.current.options[0].label)
        return saved.current.options[1].detail!!.removePrefix("Xross/themes/")
    }

    @Test fun aWholePackExportKeepsTheVariantAndTheUserLayerOnSwitch() {
        val container = AppContainer(context)
        val host = FakeHost()
        val c = controller(host, container)
        container.settings.update { it.copy(effectOverrides = EffectOverrides(focusFrameStyle = "GOLD_BEZEL"), theme = XrossTheme.ROSE.name, waveSpeed = 1.7f) }
        val before = container.settings.current
        c.onCommand(Command.ExportTheme)
        val id = export(c, host, "default", ExportScope.WholePack)
        assertEquals("default-pack", id)
        assertEquals("Busy while building and while writing", listOf(true, false, true, false), host.busy)
        val confirm = host.menus[host.menus.size - 2]
        assertTrue(confirm.current.options[0].detail!!, Regex("""Default, \d+ files, [\d.]+ (KB|MB)""").matches(confirm.current.options[0].detail!!))
        assertTrue(File(container.environment.current.themes, "$id/theme.json").isFile)

        c.onCommand(Command.SwitchToExportedTheme)
        await("the switch") { container.settings.current.themePack == id }
        val after = container.settings.current
        assertEquals(id, container.themes.active.value.id)
        assertEquals(UserLook.of(before), UserLook.of(after))
        assertEquals(before.theme, after.theme)
        assertEquals(1.7f, after.waveSpeed)
        assertEquals("Theme pack: Default", host.toasts.last())
    }

    @Test fun aVariantExportStartsOnTheNewBaseAndKeepsTheUserLayer() {
        val container = AppContainer(context)
        val host = FakeHost()
        val c = controller(host, container)
        StressPack.write(container.environment.current.themes)
        c.onCommand(Command.ExportThemePack(StressPack.ID))
        await("the scopes") { host.menus.lastOrNull()?.title == "Export Stress" }
        container.settings.update { it.copy(themeVariant = "night", layoutOverrides = LayoutOverrides(itemSpacingDp = 6f)) }
        val id = export(c, host, StressPack.ID, ExportScope.Variant("night"))
        assertEquals("stress-night", id)
        assertEquals("Stress · Night", host.nameEntry!!.text)
        val detail = host.menus[host.menus.size - 2].current.options[0].detail!!
        assertTrue(detail, detail.startsWith("Stress · Night, "))
        c.onCommand(Command.SwitchToExportedTheme)
        await("the switch") { container.settings.current.themePack == id }
        assertEquals("", container.settings.current.themeVariant)
        assertEquals(LayoutOverrides(itemSpacingDp = 6f), container.settings.current.layoutOverrides)
    }

    @Test fun aCurrentLookExportResetsTheUserLayerOnSwitch() {
        val container = AppContainer(context)
        val host = FakeHost()
        val c = controller(host, container)
        container.settings.update {
            it.copy(
                effectOverrides = EffectOverrides(foregroundParticles = "SNOW"), layoutOverrides = LayoutOverrides(uiScale = 0.9f),
                theme = XrossTheme.TEAL.name, waveTint = dev.xross.launcher.theme.WaveTint.AURORA.name, quickProfile = QuickProfile.OLED_TRUE_BLACK.name,
            )
        }
        c.onCommand(Command.ExportTheme)
        val id = export(c, host, "default", ExportScope.CurrentLook, name = "My look")
        assertEquals("my-look", id)
        val detail = host.menus[host.menus.size - 2].current.options[0].detail!!
        assertTrue(detail, detail.startsWith("Default + 1 layout value, 1 effect, the Aurora wave colors, "))
        assertTrue(detail, detail.endsWith("; quick profile not included"))

        c.onCommand(Command.SwitchToExportedTheme)
        await("the switch") { container.settings.current.themePack == id }
        val after = container.settings.current
        assertTrue(UserLook.of(after).isNone)
        assertEquals("", after.themeVariant)
        assertEquals(dev.xross.launcher.theme.PACK_THEME, after.theme)
        assertEquals(dev.xross.launcher.theme.WaveTint.THEME.name, after.waveTint)
        assertEquals("The quick profile stays a setting", QuickProfile.OLED_TRUE_BLACK.name, after.quickProfile)
    }

    @Test fun cancellingAtEachStepWritesNothing() {
        val container = AppContainer(context)
        val host = FakeHost()
        val c = controller(host, container)
        val dir = container.environment.current.themes.apply { mkdirs() }
        val before = dir.list()!!.toSet()
        // Nothing pending: Export and Switch to say so.
        c.onCommand(Command.ConfirmThemeExport)
        assertEquals("Start the export again from Settings ▸ Theme", host.toasts.last())
        c.onCommand(Command.SwitchToExportedTheme)
        assertEquals("Nothing exported yet", host.toasts.last())
        // The scope list closed: no name entry. The name entry closed: no confirm panel.
        c.onCommand(Command.ExportTheme)
        c.onCommand(Command.ChooseExportScope("default", ExportScope.WholePack))
        assertEquals("Default", host.nameEntry!!.text)
        assertEquals(ThemeController.MAX_EXPORT_NAME, host.nameEntry!!.maxLength)
        c.onExportNamed(host.nameEntry!!.purpose as NamePurpose.ExportTheme, "   ")
        assertEquals("Type a name first", host.toasts.last())
        // The confirm panel closed, then another export started: the first one is gone.
        c.onExportNamed(host.nameEntry!!.purpose as NamePurpose.ExportTheme, "Dropped")
        await("the confirm panel") { host.menus.last().title == "Export as Dropped" }
        c.onCommand(Command.ExportTheme)
        c.onCommand(Command.ConfirmThemeExport)
        assertEquals("Start the export again from Settings ▸ Theme", host.toasts.last())
        idle()
        assertEquals(before, dir.list()!!.toSet())
    }

    // endregion

    // region Share as .xtheme (2b.8c)

    private fun archives(container: AppContainer) =
        dev.xross.launcher.theme.pack.ThemeArchive({ container.environment.current.themes }, container.themePacks::inspect)

    /** [file] stages as an import would: the archive is a valid `.xtheme`. */
    private fun stages(container: AppContainer, file: File): dev.xross.launcher.theme.pack.StagedTheme {
        val r = archives(container).stage(file, file.name)
        return (r as? dev.xross.launcher.theme.pack.ArchiveResult.Staged)?.theme ?: throw AssertionError("rejected: $r")
    }

    private fun shareFile(name: String) = File(context.cacheDir, "shared/$name").apply { parentFile!!.mkdirs(); delete() }

    @Test fun thePackRowSharesAnyPack() {
        val setup = dev.xross.launcher.state.ThemeSetup(packs = listOf("default" to "Default", "stress" to "Stress"))
        val rows = MenuTree(dev.xross.launcher.state.MenuSnapshot(themes = setup)).list(MenuTree.DISPLAY)
        val share = rows.single { it.id == "disp:pack" }.menu.single { it.label == "Share as .xtheme…" }
        assertEquals(listOf(Command.ShareThemePack("default"), Command.ShareThemePack("stress")), share.submenu.map { it.command })
    }

    @Test fun sharingThePackWritesAnArchiveThatImportsAndNoFolder() {
        val container = AppContainer(context)
        val host = FakeHost()
        val c = controller(host, container)
        val dir = container.environment.current.themes.apply { mkdirs() }
        val before = dir.list()!!.toSet()
        c.onCommand(Command.ShareThemePack("default"))
        val scopes = host.menus.last()
        assertEquals("Share Default", scopes.title)
        assertEquals(listOf("Whole pack", "Current look"), labels(scopes))
        assertEquals(Command.ChooseExportScope("default", ExportScope.WholePack, share = true), scopes.current.options[0].command)
        c.onCommand(Command.ChooseExportScope("default", ExportScope.WholePack, share = true))
        val entry = host.nameEntry!!
        assertEquals("Name the shared theme", entry.title)
        assertEquals(NamePurpose.ExportTheme("default", ExportScope.WholePack, share = true), entry.purpose)
        c.onExportNamed(entry.purpose as NamePurpose.ExportTheme, entry.text)
        await("the confirm panel") { host.menus.last().title == "Share as Default" }
        val confirm = host.menus.last()
        assertEquals(listOf("Share", "Licences"), labels(confirm))
        assertTrue(confirm.current.options[0].detail!!, confirm.current.options[0].detail!!.endsWith("pick where to save default-pack.xtheme"))
        assertFalse("Includes your wallpaper photo" in confirm.current.options[0].detail!!)
        c.onCommand(Command.ConfirmThemeExport)
        assertEquals(UiEffect.CreateThemeArchive("default-pack.xtheme"), host.effects.last())
        // The picker answers with the new document.
        val file = shareFile("default-pack.xtheme")
        c.onThemeArchiveCreated(android.net.Uri.fromFile(file))
        await("the archive") { host.toasts.lastOrNull()?.startsWith("Saved Default as ") == true }
        assertEquals(listOf(true, false, true, false), host.busy)
        assertEquals("No folder in Xross/themes", before, dir.list()!!.toSet())
        val staged = stages(container, file)
        assertEquals("default-pack", staged.id)
        assertTrue(File(staged.dir, "sounds/gameboot.wav").isFile)
        // Answered once: a second answer does nothing.
        c.onThemeArchiveCreated(android.net.Uri.fromFile(shareFile("again.xtheme")))
        idle()
        assertFalse(File(context.cacheDir, "shared/again.xtheme").exists())
    }

    @Test fun aCurrentLookWithAPhotoSaysSoBeforeSharing() {
        val container = AppContainer(context)
        val host = FakeHost()
        val c = controller(host, container)
        val photo = File(context.cacheDir, "photo.png").apply { writeBytes(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()) + ByteArray(64)) }
        container.settings.update { it.copy(customWallpaper = photo.absolutePath, effectOverrides = EffectOverrides(foregroundParticles = "SNOW")) }
        c.onCommand(Command.ShareThemePack("default"))
        c.onCommand(Command.ChooseExportScope("default", ExportScope.CurrentLook, share = true))
        c.onExportNamed(host.nameEntry!!.purpose as NamePurpose.ExportTheme, "Mine")
        await("the confirm panel") { host.menus.last().title == "Share as Mine" }
        val detail = host.menus.last().current.options[0].detail!!
        assertTrue(detail, detail.startsWith("Includes your wallpaper photo · Default + "))
        c.onCommand(Command.ConfirmThemeExport)
        assertEquals(UiEffect.CreateThemeArchive("mine.xtheme"), host.effects.last())
        val file = shareFile("mine.xtheme")
        c.onThemeArchiveCreated(android.net.Uri.fromFile(file))
        await("the archive") { host.toasts.lastOrNull()?.startsWith("Saved Mine as ") == true }
        assertTrue(File(stages(container, file).dir, "wallpaper.png").isFile)
    }

    @Test fun anExportedFolderCanBeSharedAfterwards() {
        val container = AppContainer(context)
        val host = FakeHost()
        val c = controller(host, container)
        c.onCommand(Command.ShareExportedTheme)
        assertEquals("Nothing exported yet", host.toasts.last())
        c.onCommand(Command.ExportTheme)
        val id = export(c, host, "default", ExportScope.WholePack)
        val saved = host.menus.last().current.options
        assertEquals("Share as .xtheme…", saved[2].label)
        assertEquals(Command.ShareExportedTheme, saved[2].command)
        c.onCommand(Command.ShareExportedTheme)
        assertEquals(UiEffect.CreateThemeArchive("$id.xtheme"), host.effects.last())
        // Cancelling the picker writes nothing and forgets the share.
        c.onThemeArchiveCreated(null)
        idle()
        c.onThemeArchiveCreated(android.net.Uri.fromFile(shareFile("late.xtheme")))
        idle()
        assertFalse(File(context.cacheDir, "shared/late.xtheme").exists())
        // Again, to the end: the folder as it is.
        c.onCommand(Command.ShareExportedTheme)
        val file = shareFile("$id.xtheme")
        c.onThemeArchiveCreated(android.net.Uri.fromFile(file))
        await("the archive") { host.toasts.lastOrNull()?.startsWith("Saved Default as ") == true }
        val folder = File(container.environment.current.themes, id)
        val staged = stages(container, file)
        assertEquals(
            folder.walkTopDown().filter { it.isFile }.map { it.relativeTo(folder).invariantSeparatorsPath }.toSet(),
            staged.dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(staged.dir).invariantSeparatorsPath }.toSet(),
        )
    }

    // endregion

    // endregion
}
