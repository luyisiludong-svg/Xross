package dev.xross.launcher

import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.test.core.app.ApplicationProvider
import dev.xross.launcher.theme.pack.CrosshairPreset
import dev.xross.launcher.theme.pack.ExportScope
import dev.xross.launcher.theme.pack.LayoutSpec
import dev.xross.launcher.theme.pack.LookExporter
import dev.xross.launcher.theme.pack.MenuImagePlan
import dev.xross.launcher.theme.pack.MenuStyle
import dev.xross.launcher.theme.pack.NineSlice
import dev.xross.launcher.theme.pack.Skin
import dev.xross.launcher.theme.pack.ThemeLayout
import dev.xross.launcher.theme.pack.ThemePackLoader
import dev.xross.launcher.theme.pack.resolveLayout
import dev.xross.launcher.theme.shader.ImageSize
import dev.xross.launcher.ui.MenuAnchors
import dev.xross.launcher.ui.XmbMetrics
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Menu styles slice 1: `layout.menuStyle` (base, variants, rules), the export, the XMB's
 * [MenuAnchors] matching the numbers it used before the seam, skins, nine-slice geometry and the
 * menu image budget.
 */
@RunWith(RobolectricTestRunner::class)
class MenuStylesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule val tmp = TemporaryFolder()

    private val themes get() = File(tmp.root, "Xross/themes").apply { mkdirs() }
    private val loader by lazy { ThemePackLoader(context.assets) { themes } }

    private fun pack(id: String, body: String) {
        val dir = File(themes, id).apply { mkdirs() }
        File(dir, "theme.json").writeText("""{"schemaVersion": 2, "name": "$id", $body}""")
    }

    private fun style(json: String, problems: MutableList<String> = ArrayList()): MenuStyle =
        resolveLayout(Json.decodeFromString(LayoutSpec.serializer(), json), emptyMap(), ThemeLayout.DEFAULT, problems).menuStyle

    // region Format

    @Test fun menuStyleNames() {
        assertEquals(MenuStyle.XMB, ThemeLayout.DEFAULT.menuStyle)
        assertEquals(MenuStyle.SLASH, style("""{"menuStyle": "SLASH"}"""))
        assertEquals(MenuStyle.SLASH, style("""{"menuStyle": " slash "}"""))
        val problems = ArrayList<String>()
        assertEquals("unknown falls back to XMB", MenuStyle.XMB, style("""{"menuStyle": "RING"}""", problems))
        assertEquals(listOf("layout.menuStyle: unknown value RING"), problems)
    }

    @Test fun xmbFieldsUnderSlashAreNoProblem() {
        val problems = ArrayList<String>()
        val s = style("""{"menuStyle": "SLASH", "preset": "PSP_CENTER", "drillDownStyle": "CHEVRON_COLLAPSE", "focusDivider": "NONE", "cardAspectRatio": "1:1"}""", problems)
        assertEquals(MenuStyle.SLASH, s)
        assertEquals(emptyList<String>(), problems)
    }

    @Test fun baseAndVariantsSetTheStyleAndRulesCannot() {
        pack(
            "styles",
            """"layout": {"menuStyle": "SLASH"},
               "variants": [{"id": "classic", "name": "Classic", "layout": {"menuStyle": "XMB"}},
                            {"id": "odd", "name": "Odd", "layout": {"menuStyle": "SLASH", "uiScale": 1.2}},
                            {"id": "bad", "name": "Bad", "layout": {"menuStyle": "RING"}}],
               "rules": [{"match": {"category": "games"}, "apply": {"layout": {"menuStyle": "XMB", "itemSpacingDp": 20}}},
                         {"match": {"category": "music"}, "apply": {"layout": {"menuStyle": "XMB"}}}]""",
        )
        val t = loader.load("styles")
        fun layoutOf(variant: String = "", category: String? = null) = t.look(variant, category).layout ?: t.layout

        assertEquals(MenuStyle.SLASH, t.layout.menuStyle)
        assertEquals(MenuStyle.XMB, layoutOf("classic").menuStyle)
        assertEquals("the variant wins under a rule's other layout keys", MenuStyle.XMB, layoutOf("classic", "games").menuStyle)
        assertEquals(20f, layoutOf("classic", "games").itemSpacingDp)
        assertEquals("a rule can't switch the style", MenuStyle.SLASH, layoutOf("", "games").menuStyle)
        assertEquals("its other keys still apply", 20f, layoutOf("", "games").itemSpacingDp)
        assertEquals(MenuStyle.SLASH, layoutOf("", "music").menuStyle)
        assertEquals("an unknown variant style keeps the pack's", MenuStyle.SLASH, layoutOf("bad").menuStyle)
        assertEquals("a variant can't set other layout keys", 1f, layoutOf("odd").uiScale)

        val p = t.problems
        assertTrue(p.toString(), p.any { it.contains("rules[0].apply.layout.menuStyle: only the pack's layout and its variants") })
        assertTrue(p.toString(), p.any { it.contains("rules[1].apply.layout.menuStyle") })
        assertTrue(p.toString(), p.contains("variants.odd.layout: a variant can only set menuStyle"))
        assertTrue(p.toString(), p.contains("variants.bad.layout.menuStyle: unknown value RING"))
        assertTrue("a rule whose only change was the style is left out", p.any { it.endsWith("rules[1]: \"apply\" changes nothing") })
    }

    @Test fun aFlattenedVariantCarriesItsStyle() {
        pack("share", """"layout": {"menuStyle": "SLASH"}, "variants": [{"id": "x", "name": "X", "layout": {"menuStyle": "XMB"}}]""")
        val t = loader.load("share")
        val x = LookExporter.export(t, ExportScope.Variant("x")).manifest["layout"] as JsonObject
        assertEquals(JsonPrimitive("XMB"), x["menuStyle"])
        val whole = LookExporter.export(t, ExportScope.WholePack, available = loader.packFiles(t.info)).manifest["layout"] as JsonObject
        assertEquals(JsonPrimitive("SLASH"), whole["menuStyle"])
    }

    // endregion

    // region Anchors

    /** The numbers XmbScreen used before the seam: the crosshair, and the status bar's old formula. */
    @Test fun xmbAnchorsMatchTheOldNumbers() {
        val screens = listOf(1920f to 1080f, 2340f to 1080f, 640f to 480f, 1080f to 1920f)
        for ((w, h) in screens) for (preset in CrosshairPreset.entries) for (scale in listOf(0.8f, 1f, 1.3f)) {
            val m = XmbMetrics(w, h, ThemeLayout.DEFAULT.copy(crosshairX = preset.x, crosshairY = preset.y, uiScale = scale))
            val a = MenuAnchors.of(MenuStyle.XMB, m)
            val label = "${w}x$h ${preset.name} $scale"
            assertEquals(label, Offset(m.anchorX, m.focusedY), a.focus)
            assertEquals(label, (m.barY - m.categoryIcon * m.categoryFocusScale / 2 - m.s * 0.04f).coerceAtLeast(0f), a.statusClearance, 0f)
            assertEquals(label, Offset(m.anchorX / w, m.focusedY / h), a.focusFraction(w, h))
        }
        assertEquals(Offset(0.5f, 0.5f), MenuAnchors.xmb(XmbMetrics(1920f, 1080f)).focusFraction(0f, 0f))
    }

    // endregion

    // region Skins and images

    private fun skin(json: String, problems: MutableList<String> = ArrayList()): Skin? =
        Skin.of("slash.rowSkin", Json.parseToJsonElement(json), problems)

    @Test fun skinsParse() {
        assertEquals(Skin("menu/row.webp"), skin("\"/menu/row.webp\""))
        val full = skin("""{"path": "menu\\row.png", "slice": [48, 14, 48, 14], "tint": "accent", "outset": [2, 4.5, 2, 4.5]}""")!!
        assertEquals("menu/row.png", full.path)
        assertEquals(Skin.Edges(48f, 14f, 48f, 14f), full.slice)
        assertEquals("accent", full.tint)
        assertEquals(Skin.Edges(2f, 4.5f, 2f, 4.5f), full.outset)

        fun problems(json: String) = ArrayList<String>().also { skin(json, it) }
        assertNull(skin("\"../out.png\""))
        assertTrue(problems("\"../out.png\"").single().contains("must stay inside the pack"))
        assertTrue(problems("\"row.gif\"").single().contains("isn't a PNG, JPEG or WebP"))
        assertTrue(problems("""{"slice": [1, 2, 3, 4]}""").single().contains("an image path is missing"))
        assertTrue(problems("42.5").isNotEmpty())

        // A bad part is dropped on its own; the skin stays.
        val p = ArrayList<String>()
        val partly = skin("""{"path": "a.png", "slice": [1, 2, 3], "tint": "#12345", "outset": [100, 0, 0, 0], "corner": 3}""", p)!!
        assertNull(partly.slice)
        assertNull(partly.tint)
        assertEquals(Skin.MAX_OUTSET, partly.outset.left)
        assertEquals(p.toString(), 4, p.size)
        assertTrue(p.any { it.contains("slice: give four whole numbers") })
        assertTrue(p.any { it.contains("corner: unknown key") })
        assertNull("whole numbers only for slices", skin("""{"path": "a.png", "slice": [1.5, 2, 3, 4]}""")!!.slice)
    }

    @Test fun nineSliceKeepsCornersAndStretchesTheRest() {
        val slice = Skin.Edges(10f, 5f, 20f, 5f)
        val parts = NineSlice.patches(100, 40, slice, 300f, 60f, scale = 2f)
        assertEquals(9, parts.size)
        val tl = parts.first()
        assertEquals(NineSlice.Patch(0, 0, 10, 5, 0f, 0f, 20f, 10f), tl)
        val br = parts.last()
        assertEquals(NineSlice.Patch(80, 35, 20, 5, 260f, 50f, 40f, 10f), br)
        val middle = parts[4]
        assertEquals(NineSlice.Patch(10, 5, 70, 30, 20f, 10f, 240f, 40f), middle)
        // The pieces cover the box exactly.
        assertEquals(300f * 60f, parts.sumOf { (it.dstW * it.dstH).toDouble() }.toFloat(), 0.01f)

        // A box smaller than its corners scales them down evenly, so they meet.
        val small = NineSlice.patches(100, 40, slice, 30f, 60f, scale = 2f)
        val left = small.first { it.srcX == 0 && it.srcY == 0 }
        val right = small.first { it.srcX == 80 && it.srcY == 0 }
        assertEquals(30f, left.dstW + right.dstW, 0.01f)
        assertEquals(left.dstW / 10f, right.dstW / 20f, 0.01f)
        assertEquals("corners keep their aspect", left.dstW / 10f, left.dstH / 5f, 0.01f)

        // No slice: one piece; insets larger than the image leave a pixel between them.
        assertEquals(listOf(NineSlice.Patch(0, 0, 100, 40, 0f, 0f, 50f, 20f)), NineSlice.patches(100, 40, null, 50f, 20f))
        val huge = NineSlice.patches(10, 10, Skin.Edges(9f, 9f, 9f, 9f), 100f, 100f)
        assertTrue(huge.all { it.srcX + it.srcW <= 10 && it.srcY + it.srcH <= 10 })
        assertTrue(NineSlice.patches(0, 10, slice, 10f, 10f).isEmpty())
    }

    @Test fun menuImagesFitTheBudgetInOrder() {
        val sizes = listOf(ImageSize(4096, 1024), null, ImageSize(2048, 2048), ImageSize(4096, 4096), ImageSize(4096, 2048), ImageSize(512, 512))
        val plan = MenuImagePlan.fit(sizes, sheets = setOf(3, 4))
        assertEquals(ImageSize(1024, 256), plan[0])
        assertNull(plan[1])
        assertEquals(ImageSize(1024, 1024), plan[2])
        assertEquals("a sheet keeps 2048", ImageSize(2048, 2048), plan[3])
        assertNull("5.25 MP used: a 2 MP sheet doesn't fit", plan[4])
        assertEquals("a smaller one after it still fits", ImageSize(512, 512), plan[5])
        assertEquals(ImageSize(2048, 2048), MenuImagePlan.fit(listOf(ImageSize(4096, 4096)), sheets = setOf(0))[0])
        assertEquals(6L * 1024 * 1024, MenuImagePlan.BUDGET_PIXELS)
    }

    // endregion
}
