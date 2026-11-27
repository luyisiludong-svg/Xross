package dev.xross.launcher

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.xross.launcher.input.NavAction
import dev.xross.launcher.state.LayoutEditor
import dev.xross.launcher.state.LayoutEditorState
import dev.xross.launcher.state.LayoutPanelContext
import dev.xross.launcher.state.LayoutRow
import dev.xross.launcher.theme.pack.ClockFormat
import dev.xross.launcher.theme.pack.ClockLayout
import dev.xross.launcher.theme.pack.Components
import dev.xross.launcher.theme.pack.ComponentsPatch
import dev.xross.launcher.theme.pack.HintStyle
import dev.xross.launcher.theme.pack.LayoutOverrides
import dev.xross.launcher.theme.pack.LookExporter
import dev.xross.launcher.theme.pack.MenuStyle
import dev.xross.launcher.theme.pack.ResolvedComponents
import dev.xross.launcher.theme.pack.Skin
import dev.xross.launcher.theme.pack.SlashArt
import dev.xross.launcher.theme.pack.SlashCategories
import dev.xross.launcher.theme.pack.SlashDecal
import dev.xross.launcher.theme.pack.SlashFocusedContent
import dev.xross.launcher.theme.pack.SlashGeometry
import dev.xross.launcher.theme.pack.SlashHighlight
import dev.xross.launcher.theme.pack.SlashLayout
import dev.xross.launcher.theme.pack.SlashTextAlign
import dev.xross.launcher.theme.pack.SlashTransition
import dev.xross.launcher.theme.pack.ThemeLayout
import dev.xross.launcher.theme.pack.ThemeManifest
import dev.xross.launcher.theme.pack.ThemePackLoader
import dev.xross.launcher.ui.MenuAnchors
import dev.xross.launcher.ui.SlashMetrics
import dev.xross.launcher.ui.XmbMetrics
import dev.xross.launcher.ui.slash
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.math.abs

/**
 * Menu styles slices 2–6: the `slash` block, the style's geometry and motion timing as pure
 * functions, the clock and hint styles in `components`, menu images found at load, and the
 * Layout panel's rows per style.
 */
@RunWith(RobolectricTestRunner::class)
class SlashStyleTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule val tmp = TemporaryFolder()

    private val themes get() = File(tmp.root, "Xross/themes").apply { mkdirs() }
    private val loader by lazy { ThemePackLoader(context.assets) { themes } }

    private fun pack(id: String, body: String, files: Map<String, ByteArray> = emptyMap()): File {
        val dir = File(themes, id).apply { mkdirs() }
        File(dir, "theme.json").writeText("""{"schemaVersion": 2, "name": "$id", $body}""")
        for ((path, bytes) in files) File(dir, path).apply { parentFile?.mkdirs() }.writeBytes(bytes)
        return dir
    }

    private fun slash(json: String, problems: MutableList<String> = ArrayList(), base: SlashLayout = SlashLayout.DEFAULT) =
        SlashLayout.of(Json.parseToJsonElement(json), base, problems)

    // region The slash block

    @Test fun defaultsWhenAbsent() {
        val p = ArrayList<String>()
        assertEquals(SlashLayout.DEFAULT, SlashLayout.of(null, SlashLayout.DEFAULT, p))
        assertEquals(SlashLayout.DEFAULT, slash("{}", p))
        assertTrue(p.isEmpty())
        assertEquals(SlashLayout.DEFAULT, ThemeLayout.DEFAULT.slash)
    }

    @Test fun everyFieldParses() {
        val p = ArrayList<String>()
        val l = slash(
            """{"skewDeg": -10, "rowTilt": [2, -3], "jitter": 0.2, "seed": 3, "listX": 0.16, "listY": 0.4, "listWidth": 0.24,
                "bleed": true, "textAlign": "end", "rowSkin": ["menu/a.webp", {"path": "menu/b.webp", "slice": [4, 4, 4, 4]}],
                "highlight": "slab", "highlightColors": ["#ff0000", "surface"], "focusedSkin": "menu/panel.webp",
                "focusedScale": 2, "focusedContent": "LOGO_TITLE", "focusedHeight": 0.2, "titleCase": "AS_IS", "outline": 3,
                "staggerMs": 40, "transition": "SHARDS", "pop": 0.1, "categories": "LABELS",
                "categoryDecal": {"path": "menu/star.webp", "size": 2.5, "frames": [4, 2], "fps": 8}, "categoryY": 0.2,
                "art": "HERO", "artRect": [0.5, 0.2, 0.4, 0.5], "artTilt": -4, "artFrame": {"path": "menu/frame.webp", "outset": [8, 8, 8, 8]}}""",
            p,
        )
        assertEquals(p.toString(), emptyList<String>(), p)
        assertEquals(-10f, l.skewDeg)
        assertEquals(-3f, l.tiltMin)
        assertEquals("a range written backwards is sorted", 2f, l.tiltMax)
        assertEquals(3, l.seed)
        assertTrue(l.bleed)
        assertEquals(SlashTextAlign.END, l.textAlign)
        assertEquals(listOf("menu/a.webp", "menu/b.webp"), l.rowSkins.map { it.path })
        assertEquals(SlashHighlight.SLAB, l.highlight)
        assertEquals(listOf("#ff0000", "surface"), l.highlightColors)
        assertEquals("menu/panel.webp", l.focusedSkin?.path)
        assertEquals(SlashFocusedContent.LOGO_TITLE, l.focusedContent)
        assertEquals(40, l.staggerMs)
        assertEquals(SlashTransition.SHARDS, l.transition)
        assertEquals(SlashCategories.LABELS, l.categories)
        assertEquals(SlashDecal(Skin("menu/star.webp"), 2.5f, 4, 2, 8f), l.categoryDecal)
        assertEquals(SlashArt.HERO, l.art)
        assertEquals(listOf(0.5f, 0.2f, 0.4f, 0.5f), l.artRect)
        assertEquals(Skin.Edges(8f, 8f, 8f, 8f), l.artFrame?.outset)
        assertEquals(
            "images in load order, the animated decal as a sheet",
            listOf("menu/panel.webp" to false, "menu/a.webp" to false, "menu/b.webp" to false, "menu/star.webp" to true, "menu/frame.webp" to false),
            l.images,
        )
    }

    @Test fun badValuesAreReportedAndClamped() {
        val p = ArrayList<String>()
        val l = slash(
            """{"skewDeg": 40, "rowTilt": [-9, 9], "jitter": "lots", "listWidth": 0.9, "textAlign": "LEFT", "focusedScale": 9,
                "outline": -1, "staggerMs": 500, "pop": 1, "artTilt": 30, "artRect": [0.9, 0.9, 0.5, 0.5], "highlightColors": ["#12"],
                "categoryDecal": {"path": "menu/star.webp", "frames": [0, 1], "size": 9}, "rowSkin": "../escape.png", "wobble": 1}""",
            p,
        )
        assertEquals(SlashLayout.MAX_SKEW, l.skewDeg)
        assertEquals(-SlashLayout.MAX_TILT, l.tiltMin)
        assertEquals(SlashLayout.MAX_TILT, l.tiltMax)
        assertEquals(SlashLayout.DEFAULT.jitter, l.jitter)
        assertEquals(SlashLayout.MAX_LIST_WIDTH, l.listWidth)
        assertEquals(SlashLayout.DEFAULT.textAlign, l.textAlign)
        assertEquals(SlashLayout.MAX_FOCUSED_SCALE, l.focusedScale)
        assertEquals(0f, l.outline)
        assertEquals(SlashLayout.MAX_STAGGER_MS, l.staggerMs)
        assertEquals(SlashLayout.MAX_POP, l.pop)
        assertEquals(SlashLayout.MAX_ART_TILT, l.artTilt)
        val r = l.artRect
        assertTrue("art stays on screen: $r", r[0] + r[2] <= 1f + 1e-6f && r[1] + r[3] <= 1f + 1e-6f)
        assertEquals("a bad color keeps the default front", SlashLayout.DEFAULT.highlightColors[0], l.highlightColors[0])
        assertEquals(1, l.categoryDecal?.frames)
        assertEquals(SlashDecal.MAX_SIZE, l.categoryDecal?.size)
        assertTrue(l.rowSkins.isEmpty())
        for (key in listOf("skewDeg", "rowTilt", "jitter", "listWidth", "textAlign", "focusedScale", "outline", "staggerMs", "pop", "artTilt", "artRect", "highlightColors[0]", "categoryDecal.frames", "categoryDecal.size", "rowSkin", "wobble")) {
            assertTrue("$key in $p", p.any { it.startsWith("layout.slash.$key") })
        }
    }

    @Test fun panelWidthCategoryStartAndRowOutline() {
        val p = ArrayList<String>()
        val l = slash("""{"focusedWidth": 1.55, "categoryX": 0.035, "rowOutline": 0, "outline": 3}""", p)
        assertEquals(p.toString(), emptyList<String>(), p)
        assertEquals(1.55f, l.focusedWidth)
        assertEquals(0.035f, l.categoryX)
        assertEquals(0f, l.rowOutline)
        assertNull("the category bar starts at listX by default", SlashLayout.DEFAULT.categoryX)
        assertNull("rows take the outline by default", SlashLayout.DEFAULT.rowOutline)
        val bad = ArrayList<String>()
        assertEquals(SlashLayout.MAX_FOCUSED_WIDTH, slash("""{"focusedWidth": 5}""", bad).focusedWidth)
        assertTrue(bad.toString(), bad.single().startsWith("layout.slash.focusedWidth"))
        val m = XmbMetrics(2400f, 1080f, ThemeLayout.DEFAULT.copy(menuStyle = MenuStyle.SLASH, slash = l))
        assertEquals("the bar starts at categoryX", 2400f * 0.035f + SlashMetrics(m, l, false).iconSize / 2, SlashMetrics(m, l, false).categoryLeft, 0.01f)
    }

    @Test fun aLayerKeepsWhatItLeavesOut() {
        val base = slash("""{"skewDeg": -5, "rowSkin": "menu/a.webp", "focusedSkin": "menu/p.webp"}""")
        val over = slash("""{"jitter": 0.9, "focusedSkin": null}""", base = base)
        assertEquals(-5f, over.skewDeg)
        assertEquals(base.rowSkins, over.rowSkins)
        assertEquals(0.9f, over.jitter)
        assertNull("null clears a skin", over.focusedSkin)
    }

    @Test fun onlyTheBaseLayoutCarriesTheSlashBlock() {
        pack(
            "slashy",
            """"layout": {"menuStyle": "SLASH", "slash": {"skewDeg": -8}},
               "variants": [{"id": "v", "name": "V", "layout": {"slash": {"skewDeg": 3}}}],
               "rules": [{"match": {"category": "games"}, "apply": {"layout": {"slash": {"skewDeg": 5}, "itemSpacingDp": 20}}}]""",
        )
        val t = loader.load("slashy")
        assertEquals(-8f, t.layout.slash.skewDeg)
        val games = t.look("", "games").layout ?: t.layout
        assertEquals("a rule can't change the slash block", -8f, games.slash.skewDeg)
        assertEquals(20f, games.itemSpacingDp)
        assertTrue(t.problems.toString(), t.problems.any { it.contains("rules[0].apply.layout.slash: only the pack's layout") })
        assertTrue(t.problems.toString(), t.problems.contains("variants.v.layout: a variant can only set menuStyle"))
    }

    // endregion

    // region Geometry and timing

    @Test fun rowVariationIsStable() {
        val l = SlashLayout.DEFAULT
        for (i in 0 until 50) {
            assertEquals(SlashGeometry.hash(7, i), SlashGeometry.hash(7, i))
            val t = SlashGeometry.rowTilt(l, i)
            assertTrue(t in l.tiltMin..l.tiltMax)
            assertTrue(abs(SlashGeometry.rowNudge(l, i)) <= l.jitter / 2 + 1e-6f)
        }
        assertNotEquals(SlashGeometry.hash(7, 3), SlashGeometry.hash(8, 3))
    }

    @Test fun rowSkinsSpreadAndNeighboursDiffer() {
        val l = slash("""{"rowSkin": ["a.png", "b.png", "c.png"]}""")
        val picks = (0 until 60).map { i -> l.rowSkin(i)!!.path }
        assertEquals(picks, (0 until 60).map { i -> l.rowSkin(i)!!.path })
        assertEquals(setOf("a.png", "b.png", "c.png"), picks.toSet())
        for (i in 1 until picks.size) assertNotEquals("rows $i-1 and $i", picks[i - 1], picks[i])
        assertNull(SlashLayout.DEFAULT.rowSkin(0))
        assertEquals("one skin is every row's", "a.png", slash("""{"rowSkin": "a.png"}""").rowSkin(5)?.path)
    }

    @Test fun highlightCornersStayInTheirBox() {
        val w = 400f
        val h = 60f
        val pad = 6f
        for (j in listOf(0f, 0.35f, 1f)) for (i in 0 until 40) {
            val c = SlashGeometry.highlightCorners(7, i, w, h, pad, j)
            assertTrue(c.contentEquals(SlashGeometry.highlightCorners(7, i, w, h, pad, j)))
            for (k in 0 until 4) {
                assertTrue(c[k * 2] in -pad..(w + pad))
                assertTrue(c[k * 2 + 1] in -pad..(h + pad))
            }
            // Never folds: left corners stay left of the right ones, top above bottom.
            assertTrue(c[0] < c[2] && c[6] < c[4] && c[1] < c[7] && c[3] < c[5])
        }
        val flat = SlashGeometry.highlightCorners(7, 0, w, h, pad, 0f)
        assertTrue("no jitter is the padded box", flat.contentEquals(floatArrayOf(-pad, -pad, w + pad, -pad, w + pad, h + pad, -pad, h + pad)))
    }

    @Test fun staggerIsCappedAndInstantIsZero() {
        assertEquals(0, SlashGeometry.staggerDelay(0, 10, 28, 180, false))
        assertEquals(28, SlashGeometry.staggerDelay(1, 4, 28, 180, false))
        assertEquals(0, SlashGeometry.staggerDelay(5, 10, 28, 180, true))
        assertEquals(0, SlashGeometry.staggerDelay(5, 10, 0, 180, false))
        for (count in listOf(2, 5, 12, 300)) {
            val total = SlashGeometry.cascadeMs(count, 80, 180, false)
            assertTrue("$count rows take $total ms", total <= SlashLayout.CASCADE_CAP_MS)
        }
        assertEquals(0, SlashGeometry.cascadeMs(12, 28, 180, true))
    }

    @Test fun shardsCoverTheList() {
        for (n in 3..5) {
            val shards = SlashGeometry.shards(7, 300f, 200f, n)
            assertEquals(n, shards.size)
            // Shoelace areas add up to the box.
            val area = shards.sumOf { q ->
                var a = 0.0
                for (k in 0 until 4) {
                    val (x0, y0) = q[k * 2] to q[k * 2 + 1]
                    val (x1, y1) = q[(k + 1) % 4 * 2] to q[(k + 1) % 4 * 2 + 1]
                    a += x0 * y1 - x1 * y0
                }
                abs(a) / 2
            }
            assertEquals(300.0 * 200.0, area, 0.5)
            assertEquals(0f, shards.first()[0])
            assertEquals(300f, shards.last()[2])
        }
    }

    @Test fun theListScrollsOnlyAtItsEdges() {
        assertEquals(0, SlashGeometry.nextTop(0, 3, 4, 6))
        assertEquals(0, SlashGeometry.nextTop(0, 4, 20, 6))
        assertEquals(1, SlashGeometry.nextTop(0, 5, 20, 6))
        assertEquals("holds still moving back up inside", 1, SlashGeometry.nextTop(1, 3, 20, 6))
        assertEquals(1, SlashGeometry.nextTop(2, 2, 20, 6))
        assertEquals("the end of the list", 14, SlashGeometry.nextTop(0, 19, 20, 6))
        assertEquals(0, SlashGeometry.nextTop(9, 0, 20, 6))
    }

    @Test fun rowBoxes() {
        val l = SlashLayout.DEFAULT.copy(listX = 0.1f, listWidth = 0.4f)
        assertEquals(SlashGeometry.RowBox(100f, 400f, 10f, 10f), SlashGeometry.rowBox(l, 1000f, 10f, 50f))
        val bled = SlashGeometry.rowBox(l.copy(bleed = true), 1000f, 10f, 50f)
        assertEquals(-50f, bled.left)
        assertEquals("the right edge stays", 500f, bled.left + bled.width)
        assertEquals("the text starts at listX", 100f, bled.left + bled.textStart)
    }

    @Test fun decalFrames() {
        val d = SlashDecal(Skin("s.png"), columns = 4, rows = 2, fps = 8f)
        assertTrue(d.animated)
        assertEquals(0, d.frameAt(0, false))
        assertEquals(1, d.frameAt(125, false))
        assertEquals("wraps", 0, d.frameAt(1000, false))
        assertEquals("rests on its first frame", 0, d.frameAt(500, true))
        assertEquals(0, SlashDecal(Skin("s.png")).frameAt(500, false))
        assertTrue(intArrayOf(64, 32, 32, 32).contentEquals(d.frameRect(6, 128, 64)))
    }

    @Test fun slashAnchorsSitOnTheFocusedRow() {
        val m = XmbMetrics(1920f, 1080f, ThemeLayout.DEFAULT.copy(menuStyle = MenuStyle.SLASH))
        val sm = SlashMetrics(m, m.layout.slash, logo = false)
        val a0 = MenuAnchors.slash(sm, 0, 0, 0)
        val a2 = MenuAnchors.slash(sm, 2, 0, 0)
        assertEquals(sm.listTop + sm.focusedH / 2, a0.focus.y, 0.01f)
        assertEquals(2 * sm.pitch, a2.focus.y - a0.focus.y, 0.01f)
        assertTrue("the status bar stops above the category bar", a0.statusClearance < sm.categoryY)
        assertEquals(a0, MenuAnchors.of(MenuStyle.SLASH, m))
        assertTrue(sm.visible >= 3)
    }

    // endregion

    // region Clock and hints

    @Test fun clockLines() {
        assertEquals(listOf("11/14  9:41 PM"), ClockFormat.lines("11/14  9:41 PM", ClockLayout.INLINE))
        assertEquals(listOf("11/14", "9:41 PM"), ClockFormat.lines("11/14  9:41 PM", ClockLayout.STACKED))
        assertEquals("date only", listOf("Fri, Nov 14"), ClockFormat.lines("Fri, Nov 14", ClockLayout.STACKED))
        assertEquals("time only", listOf("21:41"), ClockFormat.lines("21:41", ClockLayout.STACKED))
        assertEquals(emptyList<String>(), ClockFormat.lines("", ClockLayout.STACKED))
    }

    @Test fun platedClockParts() {
        assertEquals(listOf("11/25" to false, "WED" to true), ClockFormat.parts("Wed, 11/25", time = false))
        assertEquals(listOf("PM" to true, "7:44" to false), ClockFormat.parts("7:44\u202FPM", time = true))
        assertEquals("a 24 h time has no words", listOf("19:44" to false), ClockFormat.parts("19:44"))
        assertEquals("a lone line with a colon is the time", listOf("AM" to true, "9:05" to false), ClockFormat.parts("9:05 a.m."))
        assertEquals(listOf("11/25", "7:44 PM"), ClockFormat.lines("11/25  7:44 PM", ClockLayout.PLATES))
    }

    @Test fun batteryAndAvatarPlates() {
        val p = ArrayList<String>()
        val c = components("""{"clockLayout": "PLATES", "batteryPlate": "menu/b.png", "avatarPlate": {"path": "menu/a.png", "outset": [6, 6, 6, 6]}}""", p)
        assertEquals(p.toString(), emptyList<String>(), p)
        assertEquals(ClockLayout.PLATES, c.clockLayout)
        assertEquals("menu/b.png", c.batteryPlate?.path)
        assertEquals(Skin.Edges(6f, 6f, 6f, 6f), c.avatarPlate?.outset)
        assertFalse(c.plainClock)
        assertNull(ResolvedComponents.DEFAULT.batteryPlate)
    }

    private fun components(json: String, problems: MutableList<String> = ArrayList()): ResolvedComponents =
        (ComponentsPatch.of("theme", ThemeManifest.JSON.decodeFromString(Components.serializer(), json), problems) ?: ComponentsPatch.NONE).resolve()

    @Test fun clockAndHintStylesParse() {
        val p = ArrayList<String>()
        val c = components(
            """{"clockLayout": "stacked", "clockSize": 2.2, "clockTilt": -6, "clockPlate": "menu/clock.webp",
                "hintStyle": "PLATES", "hintSkew": -10, "hintPlate": {"path": "menu/hint.webp", "tint": "#000000"}}""",
            p,
        )
        assertEquals(p.toString(), emptyList<String>(), p)
        assertEquals(ClockLayout.STACKED, c.clockLayout)
        assertEquals(2.2f, c.clockSize)
        assertEquals(-6f, c.clockTilt)
        assertEquals("menu/clock.webp", c.clockPlate?.path)
        assertEquals(HintStyle.PLATES, c.hintStyle)
        assertEquals("#000000", c.hintPlate?.tint)
        assertFalse(c.plainClock)
        val bad = ArrayList<String>()
        val b = components("""{"clockSize": 9, "clockTilt": 40, "hintStyle": "BUBBLES", "hintSkew": -30, "clockLayout": "WIDE"}""", bad)
        assertEquals(3f, b.clockSize)
        assertEquals(12f, b.clockTilt)
        assertEquals(-15f, b.hintSkew)
        assertEquals(HintStyle.PILL, b.hintStyle)
        assertEquals(ClockLayout.INLINE, b.clockLayout)
        assertEquals(bad.toString(), 5, bad.size)
    }

    @Test fun defaultsAreTodaysClockAndPill() {
        val c = ResolvedComponents.DEFAULT
        assertTrue(c.plainClock)
        assertEquals(HintStyle.PILL, c.hintStyle)
        assertEquals(0f, c.hintSkew)
        assertTrue(ComponentsPatch.NONE.resolve().plainClock)
    }

    @Test fun platesSurviveTheExport() {
        pack("plates", """"components": {"clockLayout": "STACKED", "clockPlate": {"path": "p.png", "slice": [2, 2, 2, 2]}, "hintPlate": "h.png"}""")
        val t = loader.load("plates")
        val json = LookExporter.export(t, dev.xross.launcher.theme.pack.ExportScope.Variant("")).manifest["components"] as JsonObject
        assertEquals("STACKED", (json["clockLayout"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("p.png", ((json["clockPlate"] as JsonObject)["path"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("h.png", (json["hintPlate"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    // endregion

    // region Images found at load

    private val png1x1 = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 0x49, 0x48, 0x44, 0x52, 0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0,
        0x1F, 0x15, 0xC4.toByte(), 0x89.toByte(), 0, 0, 0, 0x0A, 0x49, 0x44, 0x41, 0x54, 0x78, 0x9C.toByte(), 0x63, 0, 1, 0, 0, 5, 0, 1,
        0x0D, 0x0A, 0x2D, 0xB4.toByte(), 0, 0, 0, 0, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
    )

    @Test fun menuImagesAreFoundAtLoad() {
        pack(
            "skins",
            """"layout": {"menuStyle": "SLASH", "slash": {"rowSkin": ["menu/a.png", "menu/missing.png"],
                 "categoryDecal": {"path": "menu/star.png", "frames": [2, 1]}}},
               "components": {"hintPlate": "menu/hint.png"}""",
            mapOf("menu/a.png" to png1x1, "menu/star.png" to png1x1, "menu/hint.png" to png1x1),
        )
        val t = loader.load("skins")
        assertEquals(listOf("menu/a.png", "menu/star.png", "menu/hint.png"), t.menuImages.keys.toList())
        assertEquals(setOf("menu/star.png"), t.menuSheets)
        assertTrue(t.problems.toString(), t.problems.contains("Menu image menu/missing.png not found in the pack"))
        assertTrue(t.files.containsAll(listOf("menu/a.png", "menu/star.png", "menu/hint.png")))
    }

    // endregion

    // region Layout panel

    @Test fun layoutPanelRowsFollowTheStyle() {
        assertEquals(LayoutEditor.rows, LayoutPanelContext.XMB.rows)
        assertEquals(listOf(LayoutRow.CROSSHAIR, LayoutRow.UI_SCALE, LayoutRow.SPACING, LayoutRow.FOCUSED_SCALE, LayoutRow.RESET), LayoutEditor.rows)
        val slash = LayoutPanelContext.of(MenuStyle.SLASH, MenuStyle.SLASH, emptyList())
        assertEquals("the XMB-only rows hide", listOf(LayoutRow.UI_SCALE, LayoutRow.SPACING, LayoutRow.RESET), slash.rows)
        val both = LayoutPanelContext.of(MenuStyle.SLASH, MenuStyle.SLASH, listOf("red" to null, "classic" to MenuStyle.XMB, "xmb2" to MenuStyle.XMB))
        assertEquals(LayoutRow.MENU_STYLE, both.rows.first())
        assertEquals(listOf(MenuStyle.SLASH to "", MenuStyle.XMB to "classic"), both.styles)
    }

    @Test fun theMenuStyleRowSwitchesVariant() {
        val ctx = LayoutPanelContext.of(MenuStyle.SLASH, MenuStyle.SLASH, listOf("classic" to MenuStyle.XMB))
        val s = LayoutEditorState(0)
        assertEquals(LayoutRow.MENU_STYLE, s.row(ctx))
        val right = LayoutEditor.onAction(s, NavAction.RIGHT, LayoutOverrides.NONE, ThemeLayout.DEFAULT, ctx)
        assertEquals("classic", right.variant)
        assertTrue(LayoutEditor.onAction(s, NavAction.LEFT, LayoutOverrides.NONE, ThemeLayout.DEFAULT, ctx).blocked)
        val back = LayoutPanelContext.of(MenuStyle.XMB, MenuStyle.SLASH, listOf("classic" to MenuStyle.XMB))
        assertEquals("", LayoutEditor.onAction(s, NavAction.LEFT, LayoutOverrides.NONE, ThemeLayout.DEFAULT, back).variant)
        assertEquals("Theme pack · Slash", LayoutEditor.value(LayoutRow.MENU_STYLE, LayoutOverrides.NONE, ThemeLayout.DEFAULT, ctx))
        assertTrue("Down walks the style's rows", LayoutEditor.onAction(LayoutEditorState(ctx.rows.lastIndex), NavAction.DOWN, LayoutOverrides.NONE, ThemeLayout.DEFAULT, ctx).blocked)
    }

    // endregion
}
