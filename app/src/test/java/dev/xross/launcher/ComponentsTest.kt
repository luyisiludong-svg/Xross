package dev.xross.launcher

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.xross.launcher.config.ConfigCodec
import dev.xross.launcher.data.XrossSettings
import dev.xross.launcher.input.NavAction
import dev.xross.launcher.state.Categories
import dev.xross.launcher.state.Command
import dev.xross.launcher.state.XmbViewModel
import dev.xross.launcher.state.ComponentMenus
import dev.xross.launcher.state.MenuSnapshot
import dev.xross.launcher.state.MenuTree
import dev.xross.launcher.state.NodeAction
import dev.xross.launcher.state.SlotEdit
import dev.xross.launcher.state.ThemeSetup
import dev.xross.launcher.theme.LookInputs
import dev.xross.launcher.theme.ThemeLookResolver
import dev.xross.launcher.theme.pack.BadgePill
import dev.xross.launcher.theme.pack.BatteryDisplayMode
import dev.xross.launcher.theme.pack.ComponentOverrides
import dev.xross.launcher.theme.pack.ComponentRow
import dev.xross.launcher.theme.pack.Components
import dev.xross.launcher.theme.pack.ComponentsPatch
import dev.xross.launcher.theme.pack.GlyphOverrideMode
import dev.xross.launcher.theme.pack.LoadedTheme
import dev.xross.launcher.theme.pack.MiniPlayerMode
import dev.xross.launcher.theme.pack.ResolvedComponents
import dev.xross.launcher.theme.pack.StatusSide
import dev.xross.launcher.theme.pack.StatusSlot
import dev.xross.launcher.theme.pack.StatusSlot.AVATAR
import dev.xross.launcher.theme.pack.StatusSlot.BATTERY
import dev.xross.launcher.theme.pack.StatusSlot.CLOCK
import dev.xross.launcher.theme.pack.StatusSlot.LOGO
import dev.xross.launcher.theme.pack.StatusSlot.VOLUME
import dev.xross.launcher.theme.pack.StatusSlot.WIFI
import dev.xross.launcher.theme.pack.TelemetryMode
import dev.xross.launcher.theme.pack.ThemeDefaults
import dev.xross.launcher.theme.pack.ThemeIcon
import dev.xross.launcher.theme.pack.ThemeManifest
import dev.xross.launcher.theme.pack.ThemePackLoader
import dev.xross.launcher.theme.pack.TopLeftClusterMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * Theme 2.0, 2b.7a: the `components` block (decoding, aliases, bad values, the schema), its
 * cascade (Default pack, pack, variant, user; never an override or a rule), slot placement, the
 * user's `display.components` and Settings ▸ Theme ▸ Components.
 */
@RunWith(RobolectricTestRunner::class)
class ComponentsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule val tmp = TemporaryFolder()

    private val themes get() = File(tmp.root, "Xross/themes").apply { mkdirs() }
    private val loader by lazy { ThemePackLoader(context.assets) { themes } }

    private fun pack(id: String, body: String, files: Map<String, String> = emptyMap()): LoadedTheme {
        val dir = File(themes, id).apply { mkdirs() }
        File(dir, "theme.json").writeText("""{"schemaVersion": 2, "name": "$id"${if (body.isBlank()) "" else ", $body"}}""")
        for ((name, text) in files) File(dir, name).apply { parentFile?.mkdirs() }.writeText(text)
        return loader.load(id)
    }

    private fun components(json: String): Components =
        ThemeManifest.JSON.decodeFromString(ThemeManifest.serializer(), """{"schemaVersion": 2, "name": "x", "components": $json}""").components!!

    // region Decoding

    @Test fun decodesEveryKeyWithAliases() {
        val c = components(
            """{"topLeft": "social", "statusBarLeft": ["logo", "wi-fi"], "statusBarRight": ["time", "Battery", "profile"],
               "battery": "percent", "clock": "EMMMd Hm", "telemetry": "card", "miniPlayer": "ticker", "glyphs": "ps",
               "badgePills": ["platform", "year", "ra"], "logo": "art/logo.svg", "volumeAlways": true}""",
        )
        val problems = ArrayList<String>()
        val p = ComponentsPatch.of("theme", c, problems)!!
        assertEquals(emptyList<String>(), problems)
        assertEquals(TopLeftClusterMode.SOCIAL_STACK, p.topLeft)
        assertEquals(listOf(LOGO, WIFI), p.left)
        assertEquals(listOf(CLOCK, BATTERY, AVATAR), p.right)
        assertEquals(BatteryDisplayMode.PERCENTAGE, p.battery)
        assertEquals("EMMMdHm", p.clock)
        assertEquals(TelemetryMode.RICH_CARD, p.telemetry)
        assertEquals(MiniPlayerMode.STATUS_TICKER, p.miniPlayer)
        assertEquals(GlyphOverrideMode.PLAYSTATION, p.glyphs)
        assertEquals(listOf(BadgePill.SYSTEM, BadgePill.YEAR, BadgePill.RA_PROGRESS), p.pills)
        assertEquals("art/logo.svg", p.logo)
        assertEquals(true, p.volumeAlways)
        // The canonical names decode too.
        val canonical = ComponentsPatch.of(
            "theme",
            components("""{"topLeftCluster": "EMPTY", "batteryMode": "TIME_REMAINING", "telemetryMode": "OFF", "miniPlayerMode": "FLOATING_CARD", "glyphOverride": "MONOCHROME_MINIMAL"}"""),
            problems,
        )!!
        assertEquals(TopLeftClusterMode.EMPTY, canonical.topLeft)
        assertEquals(BatteryDisplayMode.TIME_REMAINING, canonical.battery)
        assertEquals(TelemetryMode.OFF, canonical.telemetry)
        assertEquals(GlyphOverrideMode.MONOCHROME_MINIMAL, canonical.glyphs)
        assertTrue(problems.isEmpty())
    }

    @Test fun aBadValueFallsBackOnItsOwnAndIsReported() {
        val problems = ArrayList<String>()
        val p = ComponentsPatch.of(
            "theme",
            components("""{"batteryMode": "HUGE", "statusBarRight": ["CLOCK", "RADAR", "BATTERY"], "telemetryMode": "card", "clockFormat": "MdHmssZ", "logo": "../x.svg"}"""),
            problems,
        )!!
        assertNull(p.battery)
        assertEquals(listOf(CLOCK, BATTERY), p.right)
        assertEquals(TelemetryMode.RICH_CARD, p.telemetry)
        assertEquals("MdHm", p.clock)
        assertNull(p.logo)
        assertTrue(problems.any { "theme.components.batteryMode: HUGE isn't one of" in it })
        assertTrue(problems.any { "theme.components.statusBarRight: RADAR" in it })
        assertTrue(problems.any { "clockFormat: dropped \"ssZ\"" in it })
        assertTrue(problems.any { "components.logo" in it })
        // The rest of the pack still loads: Default's battery under a bad value.
        val loaded = pack("bad", """"components": {"batteryMode": "HUGE", "statusBarRight": ["BATTERY"]}""")
        assertEquals(BatteryDisplayMode.BOTH, loaded.components().battery)
        assertEquals(listOf(BATTERY), loaded.components().right)
        assertTrue(loaded.problems.any { "batteryMode" in it })
    }

    @Test fun unknownKeysAreReportedLikeEffects() {
        val loaded = pack(
            "unknown",
            """"components": {"statusBarRight": ["CLOCK"], "sparkles": true},
               "variants": [{"id": "night", "components": {"clokFormat": "Hm"}}]""",
        )
        assertTrue(loaded.problems.toString(), loaded.problems.any { "theme.components.sparkles isn't a components property" in it })
        assertTrue(loaded.problems.toString(), loaded.problems.any { "variants.night.components.clokFormat isn't a components property" in it })
        assertEquals(listOf(CLOCK), loaded.components().right)
        assertTrue(Components.KEYS.containsAll(listOf("statusBarLeft", "statusBarRight", "batteryMode", "clockFormat", "logo", "volumeAlways", "badgePills")))
    }

    // endregion

    // region Cascade

    @Test fun theDefaultPackDrawsTodaysBar() {
        val default = loader.load(ThemePackLoader.DEFAULT_ID)
        assertEquals(emptyList<String>(), default.problems.filter { "components" in it })
        assertEquals(ResolvedComponents.DEFAULT, default.components())
        assertEquals(emptyList<StatusSlot>(), default.components().left)
        assertEquals(listOf(CLOCK, BATTERY, AVATAR), default.components().right)
        assertEquals(BatteryDisplayMode.BOTH, default.components().battery)
        assertEquals("", default.components().clock)
        // A pack without a components block inherits it.
        assertEquals(ResolvedComponents.DEFAULT, pack("plain", "").components())
    }

    @Test fun cascadeIsDefaultPackThenPackThenVariantThenUser() {
        val p = pack(
            "layers",
            """"components": {"batteryMode": "PERCENTAGE", "statusBarLeft": ["WIFI"], "clockFormat": "MdHm"},
               "variants": [{"id": "night", "components": {"batteryMode": "CAPSULE_ICON"}}]""",
        )
        // Pack over Default: its own battery and left cluster, Default's right cluster.
        val base = p.components()
        assertEquals(BatteryDisplayMode.PERCENTAGE, base.battery)
        assertEquals(listOf(WIFI), base.left)
        assertEquals(listOf(CLOCK, BATTERY, AVATAR), base.right)
        // Variant over pack: only what it sets.
        val night = p.components("night")
        assertEquals(BatteryDisplayMode.CAPSULE_ICON, night.battery)
        assertEquals("MdHm", night.clock)
        assertEquals(listOf(WIFI), night.left)
        // User over variant.
        val user = ComponentOverrides(batteryMode = "TIME_REMAINING", statusBarLeft = emptyList())
        val mine = p.components("night", user)
        assertEquals(BatteryDisplayMode.TIME_REMAINING, mine.battery)
        assertEquals(emptyList<StatusSlot>(), mine.left)
        assertEquals("MdHm", mine.clock)
        // Unknown variant: the base.
        assertEquals(base, p.components("nope"))
        // ThemeLookResolver resolves the same cascade.
        assertEquals(mine, ThemeLookResolver.resolve(LookInputs(pack = p, variant = "night", components = user)).components)
    }

    @Test fun overridesAndRulesCantPatchComponents() {
        val p = pack(
            "contextual",
            """"components": {"batteryMode": "PERCENTAGE"},
               "categoryOverrides": {"GAMES": {"components": {"batteryMode": "CAPSULE_ICON"}}},
               "systemOverrides": {"dreamcast": {"components": {"statusBarRight": ["CLOCK"]}}},
               "rules": [{"match": {"category": "GAMES"}, "apply": {"components": {"batteryMode": "TIME_REMAINING"}}}]""",
        )
        assertTrue(p.problems.toString(), p.problems.any { it.startsWith("categoryOverrides.GAMES.components is ignored") })
        assertTrue(p.problems.toString(), p.problems.any { it.startsWith("systemOverrides.dreamcast.components is ignored") })
        assertTrue(p.problems.toString(), p.problems.any { it.startsWith("rules[0].components is ignored") })
        val inGames = ThemeLookResolver.resolve(
            LookInputs(pack = p, category = "games", system = "dreamcast", battery = dev.xross.launcher.theme.BatteryLevel(10, charging = false)),
        ).components
        assertEquals(BatteryDisplayMode.PERCENTAGE, inGames.battery)
        assertEquals(listOf(CLOCK, BATTERY, AVATAR), inGames.right)
        assertEquals(inGames, ThemeLookResolver.resolve(LookInputs(pack = p)).components)
    }

    // endregion

    // region Slots

    @Test fun avatarIsMovedToTheEndOfTheRightCluster() {
        val problems = ArrayList<String>()
        val inRight = ComponentsPatch.of("theme", components("""{"statusBarRight": ["AVATAR", "CLOCK", "BATTERY"]}"""), problems)!!
        assertEquals(listOf(CLOCK, BATTERY, AVATAR), inRight.resolve().right)
        assertTrue(problems.single().contains("AVATAR is always the rightmost slot (moved there)"))
        problems.clear()
        val inLeft = ComponentsPatch.of("theme", components("""{"statusBarLeft": ["AVATAR", "WIFI"], "statusBarRight": ["CLOCK"]}"""), problems)!!.resolve()
        assertEquals(listOf(WIFI), inLeft.left)
        assertEquals(listOf(CLOCK, AVATAR), inLeft.right)
        assertTrue(problems.single().contains("statusBarLeft: AVATAR is always the rightmost slot of statusBarRight"))
        // Left out: no avatar in the bar.
        val none = ComponentsPatch(right = listOf(CLOCK, BATTERY)).resolve()
        assertFalse(none.avatar)
        assertEquals(listOf(CLOCK, BATTERY), none.rightOfText)
    }

    @Test fun duplicateSlotsAreDropped() {
        val problems = ArrayList<String>()
        val p = ComponentsPatch.of("theme", components("""{"statusBarLeft": ["WIFI", "wifi", "CLOCK"], "statusBarRight": ["CLOCK", "BATTERY", "BATTERY", "AVATAR"]}"""), problems)!!
        assertEquals(listOf(WIFI, CLOCK), p.left)
        assertEquals(listOf(CLOCK, BATTERY, AVATAR), p.right)
        val r = p.resolve()
        // A slot in both clusters stays on the left.
        assertEquals(listOf(WIFI, CLOCK), r.left)
        assertEquals(listOf(BATTERY, AVATAR), r.right)
        assertTrue(problems.any { "WIFI is listed twice" in it })
        assertTrue(problems.any { "BATTERY is listed twice" in it })
        assertTrue(problems.any { "CLOCK is in both clusters" in it })
        // Placement is the same for the user's layer, which reports nothing.
        val user = ComponentOverrides(statusBarLeft = listOf("BATTERY"), statusBarRight = listOf("AVATAR", "BATTERY", "CLOCK", "CLOCK"))
        val mine = ComponentsPatch.NONE.over(user.patch).resolve()
        assertEquals(listOf(BATTERY), mine.left)
        assertEquals(listOf(CLOCK, AVATAR), mine.right)
    }

    @Test fun needsFollowTheSlotsShown() {
        val c = ComponentsPatch(left = listOf(WIFI), right = listOf(CLOCK, BATTERY, AVATAR), battery = BatteryDisplayMode.TIME_REMAINING, clock = "Hm").resolve()
        val n = c.needs
        assertTrue(n.network)
        assertFalse(n.volume)
        assertTrue(n.chargeTime)
        assertEquals("Hm", n.clock)
        assertFalse(ResolvedComponents.DEFAULT.needs.network)
        assertFalse(ResolvedComponents.DEFAULT.needs.chargeTime)
        assertFalse(ComponentsPatch(right = listOf(CLOCK), battery = BatteryDisplayMode.TIME_REMAINING).resolve().needs.chargeTime)
    }

    @Test fun logoIsReadThroughTheIconChecks() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 48 16"><path d="M0 0h48v16H0z" fill="#fff"/></svg>"""
        val p = pack("logo", """"components": {"logo": "art/Logo.svg", "statusBarLeft": ["LOGO"]}""", mapOf("art/logo.svg" to svg))
        val logo = (p.logos["art/Logo.svg"] as ThemeIcon.Vector).image
        // A wide SVG keeps its aspect (48 × 16 dp from a 48 × 16 viewBox).
        assertEquals(3f, logo.defaultWidth.value / logo.defaultHeight.value, 0.01f)
        assertEquals("art/Logo.svg", p.components().logo)
        val missing = pack("nologo", """"components": {"logo": "logo.png"}""")
        assertTrue(missing.logos.isEmpty())
        assertTrue(missing.problems.any { it.startsWith("Logo logo.png") })
    }

    // endregion

    // region User layer and Settings

    @Test fun displayComponentsRoundTripsAndNormalizes() {
        val user = ComponentOverrides(statusBarLeft = listOf("wi-fi", "radar"), batteryMode = "percent", clockFormat = "MdHmZ")
        val st = XrossSettings(componentOverrides = user.normalized())
        assertEquals(ComponentOverrides(statusBarLeft = listOf("WIFI"), batteryMode = "PERCENTAGE", clockFormat = "MdHm"), st.componentOverrides)
        val json = ConfigCodec.json.encodeToString(dev.xross.launcher.config.SettingsFile.serializer(), ConfigCodec.settingsFile(st))
        assertTrue(json, json.contains("\"components\""))
        val back = ConfigCodec.applySettings(XrossSettings(), ConfigCodec.json.decodeFromString(dev.xross.launcher.config.SettingsFile.serializer(), json))
        assertEquals(st.componentOverrides, back.componentOverrides)
        // A hand-edited file is normalized on read.
        val edited = json.replace("\"PERCENTAGE\"", "\"capsule\"")
        assertEquals("CAPSULE_ICON", ConfigCodec.applySettings(XrossSettings(), ConfigCodec.json.decodeFromString(dev.xross.launcher.config.SettingsFile.serializer(), edited)).componentOverrides.batteryMode)
    }

    @Test fun resetToThemeDefaultsClearsComponentsAndQuickProfilesDont() {
        val st = XrossSettings(componentOverrides = ComponentOverrides(batteryMode = "PERCENTAGE"), quickProfile = "OLED_TRUE_BLACK")
        assertFalse(ThemeDefaults.isDefault(st))
        val reset = ThemeDefaults.reset(st)
        assertEquals(ComponentOverrides.NONE, reset.componentOverrides)
        assertEquals("OLED_TRUE_BLACK", reset.quickProfile)
        val tree = MenuTree(MenuSnapshot(settings = st, themes = setup()))
        assertTrue(tree.list(MenuTree.DISPLAY).single { it.id == "disp:reset" }.subtitle!!.contains("1 component"))
        // A quick profile changes the look, never the components.
        val p = loader.load(ThemePackLoader.DEFAULT_ID)
        val profiled = ThemeLookResolver.resolve(LookInputs(pack = p, profile = dev.xross.launcher.theme.QuickProfile.MONOCHROME_EINK))
        assertEquals(ResolvedComponents.DEFAULT, profiled.components)
    }

    private fun setup(components: Map<String, ComponentsPatch> = mapOf("" to ComponentsPatch(right = listOf(CLOCK, BATTERY, AVATAR), battery = BatteryDisplayMode.BOTH))) =
        ThemeSetup(components = components)

    @Test fun componentsFolderHasTheSlotBatteryAndClockRows() {
        val tree = MenuTree(MenuSnapshot(themes = setup()))
        val folder = tree.list(MenuTree.DISPLAY).single { it.id == MenuTree.DISPLAY_COMPONENTS }
        assertEquals("Components", folder.title)
        assertEquals("Right: Clock · Battery · Avatar · Icon and percentage", folder.subtitle)
        val rows = tree.list(MenuTree.DISPLAY_COMPONENTS)
        assertEquals(
            listOf("disp:comp:left", "disp:comp:right", "disp:comp:battery", "disp:comp:clock", "disp:comp:mini_player", "disp:comp:glyphs", "disp:comp:pills", "disp:comp:top_left", "disp:comp:telemetry"),
            rows.map { it.id },
        )
        assertEquals("Empty (theme pack) · Top-left", rows[0].subtitle)
        assertEquals("Clock · Battery · Avatar (theme pack) · Top-right; the avatar stays last", rows[1].subtitle)
        assertEquals(NodeAction.Run(Command.ShowOptions), rows[1].action)
        val battery = rows[2]
        assertEquals("Theme pack (Icon and percentage) · Time to full shows the percentage unless charging", battery.subtitle)
        assertEquals(NodeAction.Run(Command.CycleComponent(ComponentRow.BATTERY)), battery.action)
        assertEquals(
            listOf(null, "CAPSULE_ICON", "PERCENTAGE", "BOTH", "TIME_REMAINING"),
            battery.menu.map { (it.command as Command.SetComponent).value },
        )
        assertTrue(battery.menu[0].selected)
        val clock = rows[3]
        assertEquals(listOf("Theme pack (Default)", "24 h", "12 h", "Date and time", "Time only"), clock.menu.map { it.label })
        // The user's picks show, and cycling goes Theme pack → each choice → back.
        val mine = MenuTree(MenuSnapshot(settings = XrossSettings(componentOverrides = ComponentOverrides(batteryMode = "PERCENTAGE", clockFormat = "MdHm")), themes = setup()))
            .list(MenuTree.DISPLAY_COMPONENTS)
        assertEquals("Percentage · Time to full shows the percentage unless charging", mine[2].subtitle)
        assertTrue(mine[3].subtitle!!.startsWith("24 h"))
        assertEquals("TIME_REMAINING", ComponentRow.BATTERY.next("BOTH"))
        assertNull(ComponentRow.BATTERY.next("TIME_REMAINING"))
        assertEquals("CAPSULE_ICON", ComponentRow.BATTERY.next(null))
    }

    @Test fun slotOptionsAddRemoveAndReorder() {
        val c = ResolvedComponents.DEFAULT
        val options = ComponentMenus.options(StatusSide.RIGHT, c, c, userSet = false, hasLogo = false)
        assertEquals(listOf("Theme pack", "Clock", "Battery", "Avatar", "Add"), options.map { it.label })
        assertTrue(options[0].selected)
        assertEquals(Command.SetStatusSlots(StatusSide.RIGHT, null), options[0].command)
        // The first slot can't move left; the avatar can only be removed.
        assertEquals(listOf("Move right", "Move to the left", "Remove"), options[1].submenu.map { it.label })
        assertEquals(listOf("Move left", "Move to the left", "Remove"), options[2].submenu.map { it.label })
        assertEquals(listOf("Remove"), options[3].submenu.map { it.label })
        assertEquals(listOf("Logo", "Network", "Volume"), options[4].submenu.map { it.label })
        assertEquals("This pack has no logo", options[4].submenu[0].detail)

        fun edit(c: ResolvedComponents, side: StatusSide, slot: StatusSlot, e: SlotEdit) =
            ComponentMenus.edit(c, side, slot, e)!!.let { (l, r) -> ComponentsPatch(left = l, right = r).resolve() }
        val swapped = edit(c, StatusSide.RIGHT, CLOCK, SlotEdit.LATER)
        assertEquals(listOf(BATTERY, CLOCK, AVATAR), swapped.right)
        // Moving right stops before the avatar.
        assertNull(ComponentMenus.edit(swapped, StatusSide.RIGHT, CLOCK, SlotEdit.LATER))
        val added = edit(swapped, StatusSide.RIGHT, VOLUME, SlotEdit.ADD)
        assertEquals(listOf(BATTERY, CLOCK, VOLUME, AVATAR), added.right)
        val moved = edit(added, StatusSide.RIGHT, VOLUME, SlotEdit.OTHER_SIDE)
        assertEquals(listOf(VOLUME), moved.left)
        assertEquals(listOf(BATTERY, CLOCK, AVATAR), moved.right)
        // Adding on the right takes it out of the left.
        val back = edit(moved, StatusSide.RIGHT, VOLUME, SlotEdit.ADD)
        assertEquals(emptyList<StatusSlot>(), back.left)
        val noAvatar = edit(back, StatusSide.RIGHT, AVATAR, SlotEdit.REMOVE)
        assertFalse(noAvatar.avatar)
        assertEquals(listOf(BATTERY, CLOCK, VOLUME, AVATAR), edit(noAvatar, StatusSide.RIGHT, AVATAR, SlotEdit.ADD).right)
        // The avatar never goes left.
        assertNull(ComponentMenus.edit(noAvatar, StatusSide.LEFT, AVATAR, SlotEdit.ADD))
        assertFalse(ComponentMenus.options(StatusSide.LEFT, noAvatar, c, userSet = true, hasLogo = true).last().submenu.any { it.label == "Avatar" })

        // After an edit the list reopens on the slot with its moves, ready to move again.
        val reopened = ComponentMenus.reopen("Status bar right", ComponentMenus.options(StatusSide.RIGHT, swapped, c, true, false), CLOCK, SlotEdit.LATER)
        assertEquals(2, reopened.depth)
        assertEquals("Clock", reopened.levels[0].options[reopened.levels[0].index].label)
        assertEquals("Move left", reopened.focused!!.label)
    }

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

    // endregion
}
