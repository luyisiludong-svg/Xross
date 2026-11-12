package dev.xross.launcher

import android.content.Context
import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.SpringSpec
import androidx.test.core.app.ApplicationProvider
import dev.xross.launcher.theme.pack.CategoryOverride
import dev.xross.launcher.theme.pack.Crosshair
import dev.xross.launcher.theme.pack.CrosshairPreset
import dev.xross.launcher.theme.pack.DrillDownStyle
import dev.xross.launcher.theme.pack.FocusDividerStyle
import dev.xross.launcher.theme.pack.LayoutSpec
import dev.xross.launcher.theme.pack.MotionProfile
import dev.xross.launcher.theme.pack.TextOverflowMode
import dev.xross.launcher.theme.pack.ThemeLayout
import dev.xross.launcher.theme.pack.ThemeManifest
import dev.xross.launcher.theme.pack.ThemePackLoader
import dev.xross.launcher.theme.pack.parseAspect
import dev.xross.launcher.theme.pack.resolveLayout
import dev.xross.launcher.ui.XmbMetrics
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
 * Theme 2.0 `layout` block (Roadmap Appendix A.5): parsing and clamping, crosshair clamping on
 * the 12 display configurations of the display matrix, tile aspects per category, motion
 * profiles and their aliases, drill-down and divider names, and inheritance from the Default pack.
 */
@RunWith(RobolectricTestRunner::class)
class ThemeLayoutTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule val tmp = TemporaryFolder()

    private val themes get() = File(tmp.root, "Xross/themes").apply { mkdirs() }
    private val loader by lazy { ThemePackLoader(context.assets) { themes } }

    private fun pack(id: String, body: String) {
        val dir = File(themes, id).apply { mkdirs() }
        File(dir, "theme.json").writeText("""{"schemaVersion": 2, "name": "$id", $body}""")
    }

    private fun spec(json: String): LayoutSpec = ThemeManifest.JSON.decodeFromString(LayoutSpec.serializer(), json)

    private fun layout(json: String, problems: MutableList<String> = ArrayList()) =
        resolveLayout(spec(json), emptyMap(), ThemeLayout.DEFAULT, problems)

    /** Display matrix configurations (docs/STATUS.md §4.1): width × height in pixels. */
    private val displays = listOf(
        "4:3 retro" to (640f to 480f),
        "4:3" to (1024f to 768f),
        "5:3" to (800f to 480f),
        "1:1" to (720f to 720f),
        "3:2" to (1080f to 720f),
        "16:9 small" to (1334f to 750f),
        "16:9 FHD" to (1920f to 1080f),
        "16:10" to (2560f to 1600f),
        "20:9" to (2400f to 1080f),
        "21:9" to (2560f to 1080f),
        "4K" to (3840f to 2160f),
        "portrait" to (1080f to 2400f),
    )

    // region Parsing

    @Test fun layoutBlockParsesAndClamps() {
        val problems = ArrayList<String>()
        val l = layout(
            """{"crosshairX": 0.3, "crosshairY": 0.38, "cardAspectRatio": "2:1", "itemSpacingDp": 20,
               "focusedScale": 1.6, "unfocusedAlpha": 0.05, "uiScale": 3, "focusDivider": "full_bleed_line",
               "drillDownStyle": "CHEVRON_COLLAPSE", "textOverflow": "MARQUEE", "motion": "SNAPPY"}""",
            problems,
        )
        assertEquals(0.30f, l.crosshairX, 1e-6f)
        assertEquals(0.38f, l.crosshairY, 1e-6f)
        assertEquals(2f, l.cardAspect!!, 1e-6f)
        assertEquals(20f, l.itemSpacingDp)
        // Out of range: clamped, and reported.
        assertEquals(1.35f, l.focusedScale)
        assertEquals(0.10f, l.unfocusedAlpha!!)
        assertEquals(1.5f, l.uiScale)
        assertEquals(3, problems.size)
        assertEquals(FocusDividerStyle.FULL_BLEED_LINE, l.focusDivider)
        assertEquals(DrillDownStyle.CHEVRON_COLLAPSE, l.drillDown)
        assertEquals(TextOverflowMode.MARQUEE, l.textOverflow)
        assertEquals(MotionProfile.SNAPPY, l.motion.profile)
    }

    @Test fun presetsSetTheCrosshairAndExplicitValuesWin() {
        val ps3 = layout("""{"preset": "CLASSIC_PS3"}""")
        assertEquals(0.30f, ps3.crosshairX)
        assertEquals(0.35f, ps3.crosshairY)
        val psp = layout("""{"preset": "psp centre", "crosshairY": 0.4}""")
        assertEquals(0.50f, psp.crosshairX)
        assertEquals(0.40f, psp.crosshairY)
        assertEquals(CrosshairPreset.MODERN_WIDESCREEN, CrosshairPreset.byName("modern-widescreen"))
        assertEquals(0.20f, CrosshairPreset.MODERN_WIDESCREEN.y)
        val problems = ArrayList<String>()
        val bad = layout("""{"preset": "DIAGONAL"}""", problems)
        assertEquals(ThemeLayout.DEFAULT.crosshairX, bad.crosshairX)
        assertTrue(problems.single().contains("DIAGONAL"))
    }

    @Test fun drillDownStylesParseAndUnknownNamesKeepTheBase() {
        assertEquals(DrillDownStyle.XMB_FOLDER_COLUMN, layout("""{"drillDownStyle": "xmb_folder_column"}""").drillDown)
        assertEquals(DrillDownStyle.CHEVRON_COLLAPSE, layout("""{"drillDownStyle": "Chevron-Collapse"}""").drillDown)
        assertEquals(DrillDownStyle.BREADCRUMB, layout("""{"drillDownStyle": "BREADCRUMB"}""").drillDown)
        val problems = ArrayList<String>()
        val base = ThemeLayout.DEFAULT.copy(drillDown = DrillDownStyle.XMB_FOLDER_COLUMN)
        val l = resolveLayout(spec("""{"drillDownStyle": "SIDEWAYS", "textOverflow": "WRAP"}"""), emptyMap(), base, problems)
        assertEquals(DrillDownStyle.XMB_FOLDER_COLUMN, l.drillDown)
        assertEquals(TextOverflowMode.ELLIPSIS, l.textOverflow)
        assertEquals(2, problems.size)
        assertTrue(problems[0].startsWith("layout.drillDownStyle"))
    }

    @Test fun aspectRatiosParsePerCategory() {
        assertEquals(16f / 9f, parseAspect("16:9")!!, 1e-6f)
        assertEquals(1f, parseAspect("1:1")!!, 1e-6f)
        assertEquals(0.75f, parseAspect("3:4")!!, 1e-6f)
        assertEquals(4f, parseAspect(" 4:1 ")!!, 1e-6f)
        assertEquals(2f, parseAspect("2/1")!!, 1e-6f)
        assertEquals(1.3f, parseAspect("1.3")!!, 1e-6f)
        for (junk in listOf("", "wide", "16:0", "-1", "1:2:3")) assertNull(junk, parseAspect(junk))

        val problems = ArrayList<String>()
        val l = resolveLayout(
            spec("""{"cardAspectRatio": "16:9", "tile": {"photo": "3:4", "games": "2:1", "video": "huge"}}"""),
            mapOf("MUSIC" to CategoryOverride(cardAspectRatio = "1:1"), "GAMES" to CategoryOverride(cardAspectRatio = "4:1")),
            ThemeLayout.DEFAULT,
            problems,
        )
        assertEquals(0.75f, l.aspectFor("photo")!!, 1e-6f)
        assertEquals(1f, l.aspectFor("music")!!, 1e-6f)
        // categoryOverrides win over layout.tile.
        assertEquals(4f, l.aspectFor("games")!!, 1e-6f)
        // No entry: the general cardAspectRatio; the bad one is reported.
        assertEquals(16f / 9f, l.aspectFor("video")!!, 1e-6f)
        assertTrue(problems.single().contains("huge"))
        assertNull(ThemeLayout.DEFAULT.aspectFor("games"))
    }

    // endregion

    // region Motion

    @Test fun motionProfilesMapToSprings() {
        fun spring(p: MotionProfile) = layout("""{"motionProfile": "${p.name}"}""").motion.nav<Float>() as SpringSpec<Float>
        assertEquals(520f, spring(MotionProfile.CRISP).stiffness)
        assertEquals(1f, spring(MotionProfile.CRISP).dampingRatio)
        assertEquals(800f, spring(MotionProfile.SNAPPY).stiffness)
        assertEquals(1f, spring(MotionProfile.SNAPPY).dampingRatio)
        assertEquals(450f, spring(MotionProfile.BOUNCY).stiffness)
        assertEquals(0.75f, spring(MotionProfile.BOUNCY).dampingRatio)
        val instant = layout("""{"motionProfile": "INSTANT"}""").motion
        assertTrue(instant.instant)
        assertTrue(instant.nav<Float>() is SnapSpec)
        assertTrue(instant.tween<Float>(220) is SnapSpec)
    }

    @Test fun motionAliasesResolve() {
        assertEquals(MotionProfile.CRISP, MotionProfile.byName("PS3_CRISP"))
        assertEquals(MotionProfile.SNAPPY, MotionProfile.byName("psp_snappy"))
        assertEquals(MotionProfile.BOUNCY, MotionProfile.byName("Y2K_BOUNCY"))
        assertEquals(MotionProfile.INSTANT, MotionProfile.byName("EINK_INSTANT"))
        assertEquals(MotionProfile.INSTANT, MotionProfile.byName("e-ink instant"))
        assertNull(MotionProfile.byName("WOBBLY"))
        assertEquals(MotionProfile.BOUNCY, layout("""{"motionProfile": "Y2K_BOUNCY"}""").motion.profile)
    }

    @Test fun springParametersOverrideTheProfile() {
        val problems = ArrayList<String>()
        val m = layout("""{"motionProfile": "BOUNCY", "spring": {"stiffness": 600, "dampingRatio": 0.1}}""", problems).motion
        assertEquals(MotionProfile.BOUNCY, m.profile)
        assertEquals(600f, (m.nav<Float>() as SpringSpec<Float>).stiffness)
        assertEquals(0.3f, (m.nav<Float>() as SpringSpec<Float>).dampingRatio) // clamped
        assertEquals(1, problems.size)
    }

    // endregion

    // region Crosshair clamping

    /** A whole category icon fits beside the column: on the left always, on both sides at 100%. */
    private fun assertNeighbours(name: String, m: XmbMetrics, bothSides: Boolean) {
        val leftNeighbour = m.anchorX - m.categorySpacing
        assertTrue("$name: left neighbour cut (${leftNeighbour - m.categoryIcon / 2})", leftNeighbour - m.categoryIcon / 2 >= 0f)
        if (bothSides) {
            val right = m.anchorX + m.categorySpacing + m.categoryIcon / 2
            assertTrue("$name: right neighbour cut ($right > ${m.width})", right <= m.width)
        }
    }

    private fun assertRows(name: String, m: XmbMetrics) {
        assertTrue("$name: rows above the bar leave the screen", m.aboveY >= 0f)
        val below = m.focusedY + m.focusGap + m.itemSpacing + m.itemIcon / 2
        assertTrue("$name: the row under the focus is cut ($below > ${m.height})", below <= m.height + 0.5f)
    }

    @Test fun crosshairKeepsANeighbourVisibleOnEveryDisplay() {
        val anchors = CrosshairPreset.entries.map { it.x to it.y } +
            listOf(0f to 0f, 1f to 1f, 0.30f to 0.38f, 0.22f to 0.25f, 0.9f to 0.1f)
        for ((name, size) in displays) {
            for ((x, y) in anchors) {
                val m = XmbMetrics(size.first, size.second, ThemeLayout.DEFAULT.copy(crosshairX = x, crosshairY = y))
                val label = "$name @ $x/$y"
                assertNeighbours(label, m, bothSides = true)
                assertRows(label, m)
            }
            for (scale in listOf(0.5f, 1.5f)) {
                val m = XmbMetrics(size.first, size.second, ThemeLayout.DEFAULT.copy(crosshairX = 0f, uiScale = scale))
                assertNeighbours("$name @ ${scale}x", m, bothSides = false)
            }
        }
    }

    @Test fun crosshairClampingOn4by3And1by1And16by9() {
        // 16:9 FHD: the bar's 25% is used as is; 22% across would cut the left neighbour, so the
        // column sits just far enough right for it (as before the layout block).
        val fhd = XmbMetrics(1920f, 1080f, ThemeLayout.DEFAULT.copy(crosshairX = 0.22f, crosshairY = 0.25f))
        val room = Crosshair.neighbourRoom(fhd.categorySpacing, fhd.categoryIcon)
        assertEquals(maxOf(1920f * 0.22f, room), fhd.anchorX, 0.5f)
        assertEquals(1080f * 0.25f, fhd.barY, 0.5f)
        // 21:9 has the width for 22%.
        assertEquals(2560f * 0.22f, XmbMetrics(2560f, 1080f, fhd.layout).anchorX, 0.5f)
        // PSP centre: centred across; the bar stops just high enough for the row under the focus.
        val psp = XmbMetrics(1920f, 1080f, ThemeLayout.DEFAULT.copy(crosshairX = 0.5f, crosshairY = 0.5f))
        assertEquals(960f, psp.anchorX, 0.5f)
        assertEquals(1080f - (psp.focusedY - psp.barY + psp.focusGap + psp.itemSpacing + psp.itemIcon / 2), psp.barY, 0.5f)
        assertTrue(psp.barY > 1080f * 0.45f)
        // 4:3: 22% would cut the left neighbour; the column moves right to make room for it.
        val retro = XmbMetrics(640f, 480f, ThemeLayout.DEFAULT.copy(crosshairX = 0.22f))
        assertEquals(Crosshair.neighbourRoom(retro.categorySpacing, retro.categoryIcon), retro.anchorX, 0.5f)
        assertTrue(retro.anchorX > 640f * 0.22f)
        // 1:1: far right is pulled back to the middle, and a low bar up so the list still fits.
        val square = XmbMetrics(720f, 720f, ThemeLayout.DEFAULT.copy(crosshairX = 0.95f, crosshairY = 0.95f))
        assertEquals(360f, square.anchorX, 0.5f)
        assertEquals(720f - (square.focusedY - square.barY + square.focusGap + square.itemSpacing + square.itemIcon / 2), square.barY, 0.5f)
    }

    @Test fun spacingAndScaleFollowTheLayout() {
        val standard = XmbMetrics(1920f, 1080f)
        val tight = XmbMetrics(1920f, 1080f, ThemeLayout.DEFAULT.copy(itemSpacingDp = 6f))
        val spacious = XmbMetrics(1920f, 1080f, ThemeLayout.DEFAULT.copy(itemSpacingDp = 20f))
        assertEquals(1080f * 0.15f, standard.itemSpacing, 0.01f)
        assertTrue(tight.itemSpacing < standard.itemSpacing && standard.itemSpacing < spacious.itemSpacing)
        val half = XmbMetrics(1920f, 1080f, ThemeLayout.DEFAULT.copy(uiScale = 0.5f))
        assertEquals(standard.s / 2, half.s, 0.01f)
        assertEquals(standard.titleSize / 2, half.titleSize, 0.01f)
        assertEquals(1.1f, XmbMetrics(1920f, 1080f, ThemeLayout.DEFAULT.copy(focusedScale = 1.1f)).itemFocusScale)
    }

    // endregion

    // region Packs

    @Test fun defaultPackKeepsTheMeasuredLayout() {
        val theme = loader.load(ThemePackLoader.DEFAULT_ID)
        val l = theme.layout
        assertTrue(theme.problems.toString(), theme.problems.isEmpty())
        assertEquals(0.22f, l.crosshairX)
        assertEquals(0.25f, l.crosshairY)
        assertEquals(16f / 9f, l.aspectFor("games")!!, 1e-6f)
        assertEquals(1f, l.aspectFor("music")!!, 1e-6f)
        assertEquals(1.3f, l.aspectFor("photo")!!, 1e-6f)
        assertEquals(1.32f, l.focusedScale)
        assertNull(l.unfocusedAlpha)
        assertEquals(FocusDividerStyle.TITLE_UNDERLINE, l.focusDivider)
        assertEquals(DrillDownStyle.BREADCRUMB, l.drillDown)
        assertEquals(TextOverflowMode.ELLIPSIS, l.textOverflow)
        assertEquals(MotionProfile.CRISP, l.motion.profile)
        // The measured layout of the 16:9 reference screenshots.
        val m = XmbMetrics(1920f, 1080f, l)
        assertEquals(maxOf(1920f * 0.22f, Crosshair.neighbourRoom(m.categorySpacing, m.categoryIcon)), m.anchorX, 0.5f)
        assertEquals(270f, m.barY, 0.5f)
    }

    @Test fun aPackLayoutInheritsTheRestFromDefault() {
        pack(
            "ps3",
            """"layout": {"preset": "CLASSIC_PS3", "drillDownStyle": "XMB_FOLDER_COLUMN", "motionProfile": "PSP_SNAPPY", "focusDivider": "FULL_BLEED_LINE"},
               "categoryOverrides": {"GAMES": {"cardAspectRatio": "3:4"}}""",
        )
        val theme = loader.load("ps3")
        assertTrue(theme.problems.toString(), theme.problems.isEmpty())
        val l = theme.layout
        assertEquals(0.30f, l.crosshairX)
        assertEquals(0.35f, l.crosshairY)
        assertEquals(DrillDownStyle.XMB_FOLDER_COLUMN, l.drillDown)
        assertEquals(MotionProfile.SNAPPY, l.motion.profile)
        assertEquals(800f, l.motion.stiffness)
        assertEquals(FocusDividerStyle.FULL_BLEED_LINE, l.focusDivider)
        assertEquals(0.75f, l.aspectFor("games")!!, 1e-6f)
        // From Default.
        assertEquals(1f, l.aspectFor("music")!!, 1e-6f)
        assertEquals(TextOverflowMode.ELLIPSIS, l.textOverflow)
        assertEquals(1.32f, l.focusedScale)
    }

    @Test fun badLayoutValuesAreReportedOnThePack() {
        pack("odd", """"layout": {"motionProfile": "WOBBLY", "cardAspectRatio": "tall", "uiScale": 0.1}""")
        val theme = loader.load("odd")
        assertEquals("odd", theme.id)
        assertEquals(3, theme.problems.size)
        assertEquals(MotionProfile.CRISP, theme.layout.motion.profile)
        assertEquals(0.5f, theme.layout.uiScale)
        assertEquals(16f / 9f, theme.layout.aspectFor("games")!!, 1e-6f)
    }

    // endregion
}
