package dev.xross.launcher

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.xross.launcher.config.ConfigCodec
import dev.xross.launcher.data.GameEntity
import dev.xross.launcher.data.XrossSettings
import dev.xross.launcher.model.GlyphKey
import dev.xross.launcher.state.Command
import dev.xross.launcher.state.MenuSnapshot
import dev.xross.launcher.state.MenuTree
import dev.xross.launcher.state.NodeAction
import dev.xross.launcher.state.NodeIcon
import dev.xross.launcher.theme.pack.LoadedTheme
import dev.xross.launcher.theme.pack.ThemePackLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** 0.2.1.0: console logos on system folders (`icons/systems/`) and their switch. */
@RunWith(RobolectricTestRunner::class)
class SystemLogosTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule val tmp = TemporaryFolder()

    private val themes get() = File(tmp.root, "Xross/themes").apply { mkdirs() }
    private val loader by lazy { ThemePackLoader(context.assets) { themes } }

    private val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path fill="#fff" d="M2 6h20v12H2z"/></svg>"""

    private fun pack(id: String, icons: Map<String, String>): LoadedTheme {
        val dir = File(themes, id).apply { mkdirs() }
        File(dir, "theme.json").writeText("""{"schemaVersion": 2, "name": "$id"}""")
        for ((name, text) in icons) File(dir, "icons/systems/$name").apply { parentFile?.mkdirs() }.writeText(text)
        return loader.load(id)
    }

    @Test fun aPackLogoIsKeptByPlatformId() {
        val p = pack("logos", mapOf("snes.svg" to svg, "gba.svg" to svg))
        assertEquals(setOf("snes", "gba"), p.systemIcons.keys - loader.load(ThemePackLoader.DEFAULT_ID).systemIcons.keys)
        assertTrue(p.problems.toString(), p.problems.isEmpty())
        // Listed with the pack's files, so a whole-pack export copies them.
        assertTrue(p.files.toString(), "icons/systems/snes.svg" in p.files)
    }

    @Test fun aFolderAliasNamesTheSystemAndTheExactIdWins() {
        val p = pack("aliases", mapOf("ps1.svg" to svg, "megadrive.svg" to svg))
        assertNotNull(p.systemIcons["psx"])
        assertTrue(p.problems.toString(), p.problems.isEmpty())
        val both = pack("both", mapOf("ps1.svg" to svg, "psx.svg" to svg))
        assertNotNull(both.systemIcons["psx"])
        assertTrue("icons/systems/psx.svg" in both.files)
        assertFalse("icons/systems/ps1.svg" in both.files)
    }

    @Test fun unknownNamesAndBadFilesAreReported() {
        val p = pack("typos", mapOf("snse.svg" to svg, "gba.txt" to "x", "nes.svg" to "not an svg"))
        assertNull(p.systemIcons["nes"]?.takeIf { "nes" !in loader.load(ThemePackLoader.DEFAULT_ID).systemIcons })
        assertTrue(p.problems.toString(), p.problems.any { "icons/systems/snse.svg: no system called snse" in it })
        assertTrue(p.problems.toString(), p.problems.any { "icons/systems/gba.txt" in it })
        assertTrue(p.problems.toString(), p.problems.any { "icons/systems/nes.svg: can't read it" in it })
    }

    @Test fun aPackWithoutLogosInheritsTheDefaultPacks() {
        val default = loader.load(ThemePackLoader.DEFAULT_ID)
        assertEquals(default.systemIcons.keys, pack("plain", emptyMap()).systemIcons.keys)
    }

    private val games = listOf(GameEntity(1, "/r/snes/a.sfc", "snes", "A", "a.sfc", 10, 0))

    private fun folder(settings: XrossSettings) =
        MenuTree(MenuSnapshot(games = games, storageGranted = true, settings = settings)).list(MenuTree.ALL_GAMES).single()

    @Test fun systemFoldersAskForTheLogoUnlessSwitchedOff() {
        assertEquals(NodeIcon.System("snes", GlyphKey.PLATFORM), folder(XrossSettings()).icon)
        assertEquals(NodeIcon.Glyph(GlyphKey.PLATFORM), folder(XrossSettings(showSystemLogos = false)).icon)
        // The folder still opens the system's list either way.
        assertEquals(NodeAction.OpenList("platform:snes"), folder(XrossSettings(showSystemLogos = false)).action)
    }

    @Test fun menuOffersTheSwitchAndSettingsKeepIt() {
        val row = MenuTree(MenuSnapshot()).list(MenuTree.DISPLAY_MENU).single { it.id == "disp:systemlogos" }
        assertEquals(NodeAction.Run(Command.ToggleSystemLogos), row.action)
        assertTrue(ConfigCodec.decodeSettings(XrossSettings(), """{"display": {}}""").showSystemLogos)
        val off = XrossSettings(showSystemLogos = false)
        assertFalse(ConfigCodec.decodeSettings(XrossSettings(), ConfigCodec.encodeSettings(off)).showSystemLogos)
    }
}
