package dev.xross.launcher

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.xross.launcher.data.GameEntity
import dev.xross.launcher.data.GameMetadataEntity
import dev.xross.launcher.data.XrossSettings
import dev.xross.launcher.input.ConfirmLayout
import dev.xross.launcher.input.ControllerFamily
import dev.xross.launcher.input.resolve
import dev.xross.launcher.media.NowPlaying
import dev.xross.launcher.media.Song
import dev.xross.launcher.model.Face
import dev.xross.launcher.model.GlyphSet
import dev.xross.launcher.model.GlyphStyle
import dev.xross.launcher.state.BadgePills
import dev.xross.launcher.state.Categories
import dev.xross.launcher.state.Command
import dev.xross.launcher.state.ComponentMenus
import dev.xross.launcher.state.GameTelemetry
import dev.xross.launcher.state.MenuSnapshot
import dev.xross.launcher.state.MenuTree
import dev.xross.launcher.state.MiniPlayerLine
import dev.xross.launcher.state.MiniPlayerSpot
import dev.xross.launcher.state.MiniPlayerVisibility
import dev.xross.launcher.state.NodeAction
import dev.xross.launcher.state.ThemeSetup
import dev.xross.launcher.state.TitlePill
import dev.xross.launcher.state.XmbViewModel
import dev.xross.launcher.state.glyphsFor
import dev.xross.launcher.theme.pack.BadgePill
import dev.xross.launcher.theme.pack.BadgePill.DEVELOPER
import dev.xross.launcher.theme.pack.BadgePill.PLAYTIME
import dev.xross.launcher.theme.pack.BadgePill.RA_PROGRESS
import dev.xross.launcher.theme.pack.BadgePill.SYSTEM
import dev.xross.launcher.theme.pack.BadgePill.YEAR
import dev.xross.launcher.theme.pack.ComponentOverrides
import dev.xross.launcher.theme.pack.ComponentRow
import dev.xross.launcher.theme.pack.ComponentsPatch
import dev.xross.launcher.theme.pack.GlyphOverrideMode
import dev.xross.launcher.theme.pack.MiniPlayerMode
import dev.xross.launcher.theme.pack.ThemeDefaults
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Theme 2.0, 2b.7b: `glyphOverride` (labels only, never the confirm side), `miniPlayerMode`
 * (card, status-bar ticker, off) and `badgePills` beside the focused title, with their rows in
 * Settings ▸ Theme ▸ Components.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ComponentsTwoBTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    // region Glyphs

    @Test fun everyOverrideOverEveryFamily() {
        for (family in ControllerFamily.entries) {
            assertEquals("AUTO keeps $family's own", family.glyphs, GlyphOverrideMode.AUTO.glyphsFor(family))
            assertEquals(GlyphSet.PLAYSTATION, GlyphOverrideMode.PLAYSTATION.glyphsFor(family))
            assertEquals(GlyphSet.XBOX_COLORED, GlyphOverrideMode.XBOX.glyphsFor(family))
            assertEquals(GlyphSet.NINTENDO, GlyphOverrideMode.NINTENDO.glyphsFor(family))
            assertEquals(GlyphSet.MINIMAL, GlyphOverrideMode.MONOCHROME_MINIMAL.glyphsFor(family))
        }
        // The sets as the spec has them, south, east, north, west.
        fun faces(g: GlyphSet) = Face.entries.map { g[it] }
        assertEquals(listOf("✕", "○", "△", "□"), faces(GlyphOverrideMode.PLAYSTATION.glyphsFor(ControllerFamily.XBOX)))
        assertEquals(listOf("A", "B", "Y", "X"), faces(GlyphOverrideMode.XBOX.glyphsFor(ControllerFamily.PLAYSTATION)))
        assertEquals(GlyphStyle.XBOX_COLORS, GlyphSet.XBOX_COLORED.style)
        // Nintendo positions: south reads B.
        assertEquals("B", GlyphOverrideMode.NINTENDO.glyphsFor(ControllerFamily.KEYBOARD).south)
        // Minimal: outlined shapes, no letters, one per face.
        val minimal = faces(GlyphSet.MINIMAL)
        assertEquals(4, minimal.toSet().size)
        assertTrue(minimal.none { s -> s.any(Char::isLetterOrDigit) })
        assertEquals(GlyphStyle.OUTLINE, GlyphSet.MINIMAL.style)
        // Only a face label takes the set's style; other hint text ("L1") stays plain.
        assertEquals(Face.WEST, GlyphSet.XBOX_COLORED.faceOf("X"))
        assertNull(GlyphSet.XBOX_COLORED.faceOf("L1"))
        // Every family's own set is still plain.
        assertTrue(ControllerFamily.entries.all { it.glyphs.style == GlyphStyle.PLAIN })
    }

    @Test fun overrideChangesTheLabelsNeverTheConfirmSide() {
        val container = AppContainer(context)
        val vm = XmbViewModel(context, container, storageAccess = { false })
        fun idle() = shadowOf(Looper.getMainLooper()).idle()
        idle()
        assertEquals(GlyphSet.KEYBOARD, vm.state.value.glyphs)
        val confirm = vm.state.value.confirm
        for (mode in GlyphOverrideMode.entries) {
            container.settings.update { it.copy(componentOverrides = it.componentOverrides.with(ComponentRow.GLYPHS, mode.name)) }
            idle()
            assertEquals(mode.name, mode.glyphsFor(ControllerFamily.KEYBOARD), vm.state.value.glyphs)
            assertEquals("$mode keeps the confirm side", confirm, vm.state.value.confirm)
        }
        // Nintendo labels on a pad that confirms on the south: south is still Confirm, labelled B.
        assertEquals(ConfirmLayout.SOUTH, ConfirmLayout.AUTO.resolve(ControllerFamily.XBOX))
        assertEquals(ConfirmLayout.EAST, ConfirmLayout.AUTO.resolve(ControllerFamily.NINTENDO))
        container.settings.update { ThemeDefaults.reset(it) }
        idle()
        assertEquals(GlyphSet.KEYBOARD, vm.state.value.glyphs)
    }

    // endregion

    // region Badge pills

    @Test fun yearFromFullAndPartialDates() {
        assertEquals("1999", BadgePills.year("1999-03-11"))
        assertEquals("1999", BadgePills.year("1999-03"))
        assertEquals("1999", BadgePills.year("1999"))
        assertEquals("2001", BadgePills.year("03/2001"))
        assertEquals("1998", BadgePills.year("Nov 21, 1998"))
        assertNull(BadgePills.year(null))
        assertNull(BadgePills.year(""))
        assertNull(BadgePills.year("19xx"))
        assertNull("not a year inside a longer number", BadgePills.year("123456"))
    }

    @Test fun missingDataSkipsAPillAndOrderIsKept() {
        val all = listOf(DEVELOPER, YEAR, SYSTEM, PLAYTIME, RA_PROGRESS)
        val full = BadgePills.of(all, "DC", "1999-09-09", "Sonic Team", 135 * 60_000L)
        assertEquals(all, full.map { it.kind })
        assertEquals(listOf("Sonic Team", "1999", "DC", "2h 15m", ""), full.map { it.text })
        // An unscraped game that was never played: only the system (and the RA place-holder).
        val bare = BadgePills.of(all, "DC", null, " ", 0)
        assertEquals(listOf(SYSTEM, RA_PROGRESS), bare.map { it.kind })
        assertEquals("12 min", BadgePills.playtime(12 * 60_000L))
        assertEquals("1 min", BadgePills.playtime(5_000))
        // DEVELOPER is cut at 18 characters.
        assertEquals("Treasure Company…", BadgePills.developer("Treasure Company Limited Japan"))
        assertEquals("Intelligent Syste…", BadgePills.developer("Intelligent Systems Co."))
        assertEquals(18, BadgePills.developer("Intelligent Systems Co.")!!.length)
        assertEquals("exactly 18 stays whole", "Nintendo EAD Tokyo", BadgePills.developer("Nintendo EAD Tokyo"))
        assertEquals("Treasure", BadgePills.developer("  Treasure "))
        // Figures use the mono font.
        assertEquals(listOf(false, true, false, true, true), full.map { it.mono })
    }

    @Test fun raProgressOnlyWithTelemetry() {
        val pills = BadgePills.of(listOf(SYSTEM, RA_PROGRESS, YEAR), "GC", "2002", null, 0)
        assertEquals(listOf(SYSTEM, YEAR), BadgePills.shown(pills, telemetry = null).map { it.kind })
        val shown = BadgePills.shown(pills, GameTelemetry(gameId = 1, unlocked = 18, total = 42, earnedPoints = 120, totalPoints = 400))
        assertEquals(listOf("GC", "🏆 18/42", "2002"), shown.map { it.text })
    }

    private fun game(playTimeMs: Long = 0) = GameEntity(1, "/r/dc/sonic.chd", "dreamcast", "Sonic Adventure", "sonic.chd", 2048, 0, playTimeMs = playTimeMs)

    private fun tree(pills: List<BadgePill>, playTimeMs: Long = 0, metadata: GameMetadataEntity? = GameMetadataEntity(1, releaseDate = "1998-12-23", developer = "Sonic Team")) =
        MenuTree(
            MenuSnapshot(
                games = listOf(game(playTimeMs)),
                metadata = metadata?.let { mapOf(1L to it) } ?: emptyMap(),
                themes = ThemeSetup(components = mapOf("" to ComponentsPatch(pills = pills))),
            ),
        )

    @Test fun gameRowsCarryTheirPillsAndListedPartsLeaveTheSubtitle() {
        // The Default pack: no pills, today's subtitle.
        val plain = tree(emptyList(), playTimeMs = 90 * 60_000L).gameNodes(listOf(game(90 * 60_000L))).single()
        assertEquals(emptyList<TitlePill>(), plain.pills)
        assertEquals("DC · CHD · Playtime: 1h 30m", plain.subtitle)

        val node = tree(listOf(SYSTEM, YEAR, DEVELOPER, PLAYTIME), playTimeMs = 90 * 60_000L).gameNodes(listOf(game(90 * 60_000L))).single()
        assertEquals(listOf("DC", "1998", "Sonic Team", "1h 30m"), node.pills.map { it.text })
        assertEquals("SYSTEM and PLAYTIME move out of the subtitle", "CHD", node.subtitle)

        // Unscraped: no year or developer pill.
        val unscraped = tree(listOf(YEAR, DEVELOPER, SYSTEM), metadata = null).gameNodes(listOf(game())).single()
        assertEquals(listOf(SYSTEM), unscraped.pills.map { it.kind })
        assertEquals("CHD · Never played", unscraped.subtitle)
    }

    @Test fun neverOnAppsMediaOrSettings() {
        val t = tree(BadgePill.entries)
        for (key in listOf(Categories.rootKey(Categories.SETTINGS), Categories.rootKey(Categories.MUSIC), Categories.rootKey(Categories.APPS), MenuTree.DISPLAY_COMPONENTS)) {
            assertTrue(key, t.list(key).all { it.pills.isEmpty() })
        }
    }

    // endregion

    // region Mini player

    private fun song() = Song(1, Uri.parse("content://media/external/audio/media/1"), "Smoke Song", "Smoke Artist", "Album", 9, 1, 180_000)

    @Test fun eachModeDrawsOneFormOrNone() {
        for (visible in listOf(true, false)) {
            assertEquals(visible, MiniPlayerLine.shows(MiniPlayerMode.FLOATING_CARD, MiniPlayerSpot.CARD, visible))
            assertFalse(MiniPlayerLine.shows(MiniPlayerMode.FLOATING_CARD, MiniPlayerSpot.TICKER, visible))
            assertEquals(visible, MiniPlayerLine.shows(MiniPlayerMode.STATUS_TICKER, MiniPlayerSpot.TICKER, visible))
            assertFalse(MiniPlayerLine.shows(MiniPlayerMode.STATUS_TICKER, MiniPlayerSpot.CARD, visible))
            for (spot in MiniPlayerSpot.entries) assertFalse("OFF draws no $spot", MiniPlayerLine.shows(MiniPlayerMode.OFF, spot, visible))
        }
        assertEquals("Smoke Song — Smoke Artist", MiniPlayerLine.tickerText(NowPlaying(song = song(), isPlaying = true)))
        assertEquals("Smoke Song", MiniPlayerLine.tickerText(NowPlaying(song = song().copy(artist = ""))))
        assertNull(MiniPlayerLine.tickerText(NowPlaying()))
    }

    @Test fun theTickerFollowsTheCardsVisibilityRules() = runTest {
        val v = MiniPlayerVisibility(backgroundScope)
        fun ticker() = MiniPlayerLine.shows(MiniPlayerMode.STATUS_TICKER, MiniPlayerSpot.TICKER, v.visible.value)
        v.update(NowPlaying(song = song(), isPlaying = true), covered = false)
        assertTrue(ticker())
        v.update(NowPlaying(song = song(), isPlaying = true), covered = true)
        assertFalse("covered like the card", ticker())
        v.update(NowPlaying(song = song(), isPlaying = false), covered = false)
        assertTrue(ticker())
        advanceTimeBy(MiniPlayerLine.PAUSED_HIDE_MS)
        runCurrent()
        assertFalse("a long pause hides it", ticker())
    }

    // endregion

    // region Settings

    @Test fun miniPlayerAndGlyphRows() {
        val o = ComponentOverrides.NONE.with(ComponentRow.MINI_PLAYER, "ticker").with(ComponentRow.GLYPHS, "ps")
        assertEquals("STATUS_TICKER", o[ComponentRow.MINI_PLAYER])
        assertEquals("PLAYSTATION", o[ComponentRow.GLYPHS])
        assertEquals(MiniPlayerMode.STATUS_TICKER, o.patch.miniPlayer)
        assertEquals(GlyphOverrideMode.PLAYSTATION, o.patch.glyphs)
        assertNull(o.with(ComponentRow.GLYPHS, "nonsense")[ComponentRow.GLYPHS])
        assertEquals(listOf(null, "FLOATING_CARD", "STATUS_TICKER", "OFF"), (listOf<String?>(null) + ComponentRow.MINI_PLAYER.choices.map { it.first }))
        assertEquals("FLOATING_CARD", ComponentRow.MINI_PLAYER.next(null))
        assertNull(ComponentRow.MINI_PLAYER.next("OFF"))
        assertEquals(GlyphOverrideMode.entries.map { it.name }, ComponentRow.GLYPHS.choices.map { it.first })

        val rows = MenuTree(MenuSnapshot(settings = XrossSettings(componentOverrides = o))).list(MenuTree.DISPLAY_COMPONENTS)
        val mini = rows.single { it.id == "disp:comp:mini_player" }
        assertEquals("Mini player", mini.title)
        assertEquals("Status bar ticker · One line before the avatar", mini.subtitle)
        assertEquals(NodeAction.Run(Command.CycleComponent(ComponentRow.MINI_PLAYER)), mini.action)
        assertEquals(listOf("Theme pack (Floating card)", "Floating card", "Status bar ticker", "Off"), mini.menu.map { it.label })
        assertTrue(mini.menu[2].selected)
        val glyphs = rows.single { it.id == "disp:comp:glyphs" }
        assertEquals("Button glyphs", glyphs.title)
        assertTrue(glyphs.subtitle!!.startsWith("Shapes (✕ ○ △ □) · Labels only"))
        assertEquals("Theme pack (Controller's own)", glyphs.menu[0].label)
    }

    @Test fun titleBadgesToggleInOrder() {
        assertEquals(listOf(YEAR, SYSTEM), ComponentMenus.togglePill(listOf(YEAR), SYSTEM))
        assertEquals(listOf(SYSTEM), ComponentMenus.togglePill(listOf(YEAR, SYSTEM), YEAR))
        val options = ComponentMenus.pillOptions(shown = listOf(YEAR, SYSTEM), pack = emptyList(), userSet = true)
        assertEquals(listOf("Theme pack", "None", "System", "Release year", "Developer", "Playtime", "Achievements"), options.map { it.label })
        assertEquals("None", options[0].detail)
        assertFalse(options[0].selected)
        assertEquals(listOf(false, false, true, true, false, false, false), options.map { it.selected })
        assertEquals("On · 2 of 2", options[2].detail)
        assertEquals("On · 1 of 2", options[3].detail)
        assertEquals(Command.ToggleBadgePill(DEVELOPER), options[4].command)
        assertEquals(Command.SetBadgePills(emptyList()), options[1].command)
        assertEquals(listOf("SYSTEM", "YEAR"), ComponentOverrides.NONE.withPills(listOf(SYSTEM, YEAR, SYSTEM)).badgePills)
        assertEquals(1, ComponentOverrides.NONE.withPills(emptyList()).count)
    }

    // endregion
}
