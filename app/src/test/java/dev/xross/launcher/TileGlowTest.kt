package dev.xross.launcher

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.xross.launcher.config.ConfigCodec
import dev.xross.launcher.data.XrossSettings
import dev.xross.launcher.state.Command
import dev.xross.launcher.state.MenuSnapshot
import dev.xross.launcher.state.MenuTree
import dev.xross.launcher.state.NodeAction
import dev.xross.launcher.theme.pack.ComponentOverrides
import dev.xross.launcher.theme.pack.ComponentRow
import dev.xross.launcher.theme.pack.LoadedTheme
import dev.xross.launcher.theme.pack.ThemePackLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** 0.2.1.0: `components.tileGlow` in a pack, the user's Follow theme · On · Off, and settings.json. */
@RunWith(RobolectricTestRunner::class)
class TileGlowTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule val tmp = TemporaryFolder()

    private val themes get() = File(tmp.root, "Xross/themes").apply { mkdirs() }
    private val loader by lazy { ThemePackLoader(context.assets) { themes } }

    private fun pack(id: String, body: String): LoadedTheme {
        val dir = File(themes, id).apply { mkdirs() }
        File(dir, "theme.json").writeText("""{"schemaVersion": 2, "name": "$id"${if (body.isBlank()) "" else ", $body"}}""")
        return loader.load(id)
    }

    @Test fun glowIsOnUnlessThePackTurnsItOff() {
        assertTrue(loader.load(ThemePackLoader.DEFAULT_ID).components().tileGlow)
        assertTrue(pack("plain", "").components().tileGlow)
        val flat = pack("flat", """"components": {"tileGlow": false}""")
        assertFalse(flat.components().tileGlow)
        assertEquals(emptyList<String>(), flat.problems)
        // The short alias reads the same.
        assertFalse(pack("alias", """"components": {"glow": false}""").components().tileGlow)
    }

    @Test fun aVariantCanTurnItBackOn() {
        val p = pack("night", """"components": {"tileGlow": false}, "variants": [{"id": "neon", "name": "Neon", "components": {"tileGlow": true}}]""")
        assertFalse(p.components().tileGlow)
        assertTrue(p.components("neon").tileGlow)
    }

    @Test fun aWrongTypeIsReportedAndKeepsTheGlow() {
        val p = pack("typo", """"components": {"tileGlow": "sometimes"}""")
        assertTrue(p.components().tileGlow)
        assertTrue(p.problems.toString(), p.problems.any { "theme.components.tileGlow" in it })
    }

    @Test fun theUserWinsOverThePack() {
        val flat = pack("flat", """"components": {"tileGlow": false}""")
        assertTrue(flat.components(user = ComponentOverrides(tileGlow = true)).tileGlow)
        val glow = pack("glow", "")
        assertFalse(glow.components(user = ComponentOverrides(tileGlow = false)).tileGlow)
    }

    @Test fun theRowCyclesFollowThemeOnOff() {
        assertEquals("ON", ComponentRow.TILE_GLOW.next(null))
        assertEquals("OFF", ComponentRow.TILE_GLOW.next("ON"))
        assertNull(ComponentRow.TILE_GLOW.next("OFF"))
        val off = ComponentOverrides.NONE.with(ComponentRow.TILE_GLOW, "OFF")
        assertEquals(false, off.tileGlow)
        assertEquals("OFF", off[ComponentRow.TILE_GLOW])
        assertNull(off.with(ComponentRow.TILE_GLOW, null).tileGlow)
        assertEquals(1, off.count)
    }

    @Test fun componentsMenuOffersTheRow() {
        fun row(o: ComponentOverrides) = MenuTree(MenuSnapshot(settings = XrossSettings(componentOverrides = o)))
            .list(MenuTree.DISPLAY_COMPONENTS).single { it.id == "disp:comp:tile_glow" }
        val follow = row(ComponentOverrides.NONE)
        assertTrue(follow.subtitle, follow.subtitle!!.startsWith("Theme pack (On)"))
        assertEquals(NodeAction.Run(Command.CycleComponent(ComponentRow.TILE_GLOW)), follow.action)
        assertEquals(listOf("Theme pack (On)", "On", "Off"), follow.menu.map { it.label })
        assertTrue(row(ComponentOverrides(tileGlow = false)).subtitle!!.startsWith("Off"))
    }

    @Test fun settingsFileKeepsThePick() {
        val s = XrossSettings(componentOverrides = ComponentOverrides(tileGlow = false))
        assertEquals(s.componentOverrides, ConfigCodec.decodeSettings(XrossSettings(), ConfigCodec.encodeSettings(s)).componentOverrides)
        assertNull(ConfigCodec.decodeSettings(XrossSettings(), """{"display": {"components": {}}}""").componentOverrides.tileGlow)
    }
}
