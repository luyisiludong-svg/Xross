package dev.xross.launcher

import dev.xross.launcher.data.GameEntity
import dev.xross.launcher.data.XrossSettings
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
        assertEquals(listOf("Library", "Controls", "Emulators", "Theme", "Sound", "Media", "System"), titles)
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
        val node = MenuTree(MenuSnapshot(settings = XrossSettings(theme = "CRIMSON"))).list(MenuTree.DISPLAY).first()
        assertEquals("Crimson", node.subtitle)
        assertEquals(NodeAction.Run(Command.CycleTheme), node.action)
        assertEquals(XrossTheme.LAVENDER, XrossTheme.byName("nonsense"))
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
