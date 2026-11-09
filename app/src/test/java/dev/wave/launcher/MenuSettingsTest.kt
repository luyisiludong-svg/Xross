package dev.wave.launcher

import dev.wave.launcher.data.GameEntity
import dev.wave.launcher.data.WaveSettings
import dev.wave.launcher.state.Command
import dev.wave.launcher.state.GlyphKey
import dev.wave.launcher.state.MenuSnapshot
import dev.wave.launcher.state.MenuTree
import dev.wave.launcher.state.NodeAction
import dev.wave.launcher.state.NodeIcon
import dev.wave.launcher.theme.WaveTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MenuSettingsTest {
    @Test fun settingsRootListsEverySection() {
        val titles = MenuTree(MenuSnapshot()).list("root:settings").map { it.title }
        assertEquals(listOf("Library", "Controls", "Emulators", "Theme", "Sound", "System"), titles)
    }

    @Test fun soundSettingsReflectState() {
        val s = WaveSettings(soundEffects = false, soundVolume = 0.5f, bootSound = true, gameBootSound = false)
        val items = MenuTree(MenuSnapshot(settings = s)).list(MenuTree.SOUND).associate { it.title to it.subtitle }
        assertEquals("Off", items["Sound effects"])
        assertEquals("50%", items["Effects volume"])
        assertEquals("On", items["Startup sound"])
        assertEquals("Off", items["Game start sound"])
    }

    @Test fun themeSettingShowsThemeLabel() {
        val node = MenuTree(MenuSnapshot(settings = WaveSettings(theme = "CRIMSON"))).list(MenuTree.DISPLAY).first()
        assertEquals("Crimson", node.subtitle)
        assertEquals(NodeAction.Run(Command.CycleTheme), node.action)
        assertEquals(WaveTheme.LAVENDER, WaveTheme.byName("nonsense"))
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
                dev.wave.launcher.state.FolderEntry("/sdcard/ROMs", autoDetected = true),
                dev.wave.launcher.state.FolderEntry("/sdcard/Custom", autoDetected = false),
            ),
        )
        val folders = MenuTree(snapshot).list(MenuTree.LIBRARY).filter { it.id.startsWith("lib:folder:") }
        assertEquals(null, folders[0].options)
        assertEquals(Command.RemoveFolder("/sdcard/Custom"), folders[1].options)
    }
}
