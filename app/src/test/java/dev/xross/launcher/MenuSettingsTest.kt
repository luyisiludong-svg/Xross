package dev.xross.launcher

import dev.xross.launcher.data.GameEntity
import dev.xross.launcher.data.XrossSettings
import dev.xross.launcher.input.ControllerProfile
import dev.xross.launcher.input.NavAction
import dev.xross.launcher.state.Command
import dev.xross.launcher.state.GlyphKey
import dev.xross.launcher.state.MenuSnapshot
import dev.xross.launcher.state.MenuTree
import dev.xross.launcher.state.NodeAction
import dev.xross.launcher.state.NodeIcon
import dev.xross.launcher.theme.XrossTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MenuSettingsTest {
    @Test fun settingsRootListsEverySection() {
        val titles = MenuTree(MenuSnapshot()).list("root:settings").map { it.title }
        assertEquals(listOf("Library", "Controls", "Emulators", "Theme", "Sound", "Media", "Apps", "Accounts", "System"), titles)
    }

    @Test fun systemSettingsShowTheXrossFolderAndBackup() {
        fun rows(p: dev.xross.launcher.state.PortableSetup) =
            MenuTree(MenuSnapshot(portable = p)).list(MenuTree.SYSTEM).associateBy { it.id }
        val internal = rows(dev.xross.launcher.state.PortableSetup(root = "/data/user/0/dev.xross.launcher/files/Xross", portable = false))
        assertEquals("In app storage until All files access is granted — press to grant", internal.getValue("sys:folder").subtitle)
        assertTrue(internal.getValue("sys:backup").subtitle!!.startsWith("Needs All files access"))
        assertTrue("sys:restore" !in internal)

        val backup = dev.xross.launcher.data.BackupInfo(java.io.File("/storage/emulated/0/Xross/data/xross.db"), 42, 5, 0L, 1024)
        val shared = rows(dev.xross.launcher.state.PortableSetup(root = "/storage/emulated/0/Xross", portable = true, backupOnDisk = backup))
        assertEquals("/storage/emulated/0/Xross · press to use another drive", shared.getValue("sys:folder").subtitle)
        assertEquals(NodeAction.Run(Command.BackupNow), shared.getValue("sys:backup").action)
        assertTrue(shared.getValue("sys:restore").subtitle!!.startsWith("42 games · saved "))
        assertEquals(NodeAction.Run(Command.ConfirmRestore), shared.getValue("sys:restore").action)
    }

    @Test fun cleanInstallOffersTheBackupInGames() {
        val backup = dev.xross.launcher.data.BackupInfo(java.io.File("/storage/emulated/0/Xross/data/xross.db"), 3, 5, 0L, 1024)
        val root = MenuTree(MenuSnapshot(storageGranted = true, portable = dev.xross.launcher.state.PortableSetup(portable = true, restorable = backup)))
            .list("root:games")
        assertEquals(listOf("Restore library backup", "No games found"), root.map { it.title })
        assertEquals("3 games found in the Xross folder", root.first().subtitle)
        assertEquals(NodeAction.Run(Command.ConfirmRestore), root.first().action)
    }

    @Test fun deadzoneRowOffersHardwareOnly() {
        fun row(deadzone: Float) = MenuTree(MenuSnapshot(settings = XrossSettings(deadzone = deadzone)))
            .list(MenuTree.CONTROLS).single { it.id == "ctl:deadzone" }.subtitle
        assertEquals("Off — hardware only", row(0f))
        assertEquals("15%", row(0.15f))
        assertEquals("35%", row(0.35f))
        assertEquals("55%", row(0.55f))
    }

    @Test fun soundSettingsReflectState() {
        val s = XrossSettings(soundEffects = false, soundVolume = 0.5f, bootSound = true, gameBootSound = false)
        val items = MenuTree(MenuSnapshot(settings = s)).list(MenuTree.SOUND).associate { it.title to it.subtitle }
        assertEquals("Off", items["Sound effects"])
        assertEquals("50%", items["Effects volume"])
        assertEquals("On", items["Startup sound"])
        assertEquals("Off", items["Game start sound"])
    }

    @Test fun themeSettingShowsThemeLabel() {
        val node = MenuTree(MenuSnapshot(settings = XrossSettings(theme = "CRIMSON"))).list(MenuTree.DISPLAY).single { it.id == "disp:theme" }
        assertEquals("Crimson", node.subtitle)
        assertEquals(NodeAction.Run(Command.CycleTheme), node.action)
        assertEquals(XrossTheme.LAVENDER, XrossTheme.byName("nonsense"))
    }

    @Test fun originalThemeSaysItChangesMonthly() {
        val node = MenuTree(MenuSnapshot(settings = XrossSettings(theme = "ORIGINAL"))).list(MenuTree.DISPLAY).single { it.id == "disp:theme" }
        assertEquals("Original · changes every month", node.subtitle)
    }

    @Test fun buttonHintsCanBeHidden() {
        fun row(show: Boolean) = MenuTree(MenuSnapshot(settings = XrossSettings(showButtonHints = show)))
            .list(MenuTree.DISPLAY).single { it.id == "disp:hints" }
        assertEquals(NodeAction.Run(Command.ToggleButtonHints), row(true).action)
        assertEquals("Hidden", row(false).subtitle)
        assertTrue(row(true).subtitle!!.startsWith("Shown"))
    }

    @Test fun controlsShowAxialDeadzoneAndEachSavedController() {
        val settings = XrossSettings(
            axialDeadzone = 0.2f,
            keyRemap = mapOf(99 to NavAction.CONFIRM),
            controllerProfiles = mapOf(
                "054c:0ce6" to ControllerProfile("DualSense Wireless Controller", mapOf(96 to NavAction.CONFIRM, 97 to NavAction.BACK)),
                "045e:0b13" to ControllerProfile("", mapOf(96 to NavAction.BACK)),
            ),
        )
        val rows = MenuTree(MenuSnapshot(settings = settings)).list(MenuTree.CONTROLS)
        val byId = rows.associateBy { it.id }
        assertEquals("20% per axis", byId.getValue("ctl:axial").subtitle)
        assertEquals(NodeAction.Run(Command.CycleAxialDeadzone), byId.getValue("ctl:axial").action)
        assertEquals("2 controllers · default mapping changed", byId.getValue("ctl:reset").subtitle)

        val pad = byId.getValue("ctl:profile:054c:0ce6")
        assertEquals("DualSense Wireless Controller", pad.title)
        assertEquals("2 custom bindings · 054C:0CE6", pad.subtitle)
        assertEquals(listOf(Command.ResetControllerProfile("054c:0ce6")), pad.menu.map { it.command })
        // A controller that reported no name is labelled by its IDs.
        assertEquals("Controller 045E:0B13", byId.getValue("ctl:profile:045e:0b13").title)
        assertEquals(listOf(Command.ResetGlobalRemap), byId.getValue("ctl:profile:global").menu.map { it.command })

        val defaults = MenuTree(MenuSnapshot()).list(MenuTree.CONTROLS).associateBy { it.id }
        assertEquals("Off — radial only", defaults.getValue("ctl:axial").subtitle)
        assertEquals("Using defaults", defaults.getValue("ctl:reset").subtitle)
        assertTrue(defaults.keys.none { it.startsWith("ctl:profile:") })
    }

    @Test fun discAndCartridgeGamesGetDifferentIcons() {
        val games = listOf(
            GameEntity(1, "/r/psx/a.chd", "psx", "A", "a.chd", 1, 0),
            GameEntity(2, "/r/snes/b.sfc", "snes", "B", "b.sfc", 1, 0),
        )
        val tree = MenuTree(MenuSnapshot(games = games))
        assertEquals(NodeIcon.Glyph(GlyphKey.GAME_DISC), tree.list("platform:psx").single().icon)
        assertEquals(NodeIcon.Glyph(GlyphKey.GAME_CART), tree.list("platform:snes").single().icon)
    }

    @Test fun emulatorSettingFlagsMissingEmulator() {
        val tree = MenuTree(MenuSnapshot(installedEmulators = setOf("retroarch")))
        val rows = tree.list(MenuTree.EMULATORS).associate { it.title to it.subtitle }
        assertEquals("RetroArch · swanstation", rows["PlayStation"])
        assertTrue(rows.getValue("PlayStation 2")!!.endsWith("(not installed)"))
    }

    @Test fun libraryShowsAutoDetectedFoldersAsNotRemovable() {
        val snapshot = MenuSnapshot(
            folders = listOf(
                dev.xross.launcher.state.FolderEntry("/sdcard/ROMs", autoDetected = true),
                dev.xross.launcher.state.FolderEntry("/sdcard/Custom", autoDetected = false),
            ),
        )
        val folders = MenuTree(snapshot).list(MenuTree.LIBRARY).filter { it.id.startsWith("lib:folder:") }
        assertTrue(folders[0].menu.isEmpty())
        assertEquals(listOf(Command.RemoveFolder("/sdcard/Custom")), folders[1].menu.map { it.command })
        assertEquals("Remove from library", folders[1].menu.single().label)
    }
}
