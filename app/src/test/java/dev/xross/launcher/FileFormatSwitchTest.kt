package dev.xross.launcher

import dev.xross.launcher.config.ConfigCodec
import dev.xross.launcher.data.GameEntity
import dev.xross.launcher.data.XrossSettings
import dev.xross.launcher.state.Command
import dev.xross.launcher.state.MenuSnapshot
import dev.xross.launcher.state.MenuTree
import dev.xross.launcher.state.NodeAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Settings ▸ Display ▸ Menu and profile ▸ File format in game rows (0.2.1.0). */
@RunWith(RobolectricTestRunner::class)
class FileFormatSwitchTest {
    private val games = listOf(
        GameEntity(1, "/r/psx/d.chd", "psx", "D", "d.chd", 10, 0),
        GameEntity(2, "/r/gba/c.gba", "gba", "C", "c.gba", 10, 0),
    )

    private fun tree(settings: XrossSettings) = MenuTree(MenuSnapshot(games = games, storageGranted = true, settings = settings))

    @Test fun platformListShowsTheFileFormatByDefault() {
        val sub = tree(XrossSettings()).list("platform:psx").single().subtitle.orEmpty()
        assertTrue(sub, "CHD" in sub)
    }

    @Test fun switchedOffTheFileFormatLeavesTheSubtitle() {
        val sub = tree(XrossSettings(showFileFormat = false)).list("platform:psx").single().subtitle.orEmpty()
        assertFalse(sub, "CHD" in sub)
        // The extension is only one part: the rest of the subtitle stays.
        val shown = tree(XrossSettings()).list("platform:psx").single().subtitle.orEmpty()
        assertEquals(shown.split(" · ").filter { it != "CHD" }.joinToString(" · "), sub)
    }

    @Test fun searchRowsKeepThePlatformWithoutTheFormat() {
        val sub = tree(XrossSettings(showFileFormat = false)).gameNodes(games).single { it.title == "D" }.subtitle.orEmpty()
        assertTrue(sub, sub.startsWith("PS"))
        assertFalse(sub, "CHD" in sub)
    }

    @Test fun menuOffersTheSwitch() {
        fun row(settings: XrossSettings) = MenuTree(MenuSnapshot(settings = settings)).list(MenuTree.DISPLAY_MENU)
            .single { it.id == "disp:fileformat" }
        assertEquals("Shown (ISO, CHD…)", row(XrossSettings()).subtitle)
        assertEquals("Hidden", row(XrossSettings(showFileFormat = false)).subtitle)
        assertEquals(NodeAction.Run(Command.ToggleFileFormat), row(XrossSettings()).action)
    }

    @Test fun settingsFileWithoutTheKeyReadsAsShown() {
        val s = ConfigCodec.decodeSettings(XrossSettings(), """{"display": {"theme": "OCEAN"}}""")
        assertTrue(s.showFileFormat)
        val off = ConfigCodec.decodeSettings(XrossSettings(), """{"display": {"showFileFormat": false}}""")
        assertFalse(off.showFileFormat)
    }
}
