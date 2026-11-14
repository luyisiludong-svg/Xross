package dev.xross.launcher

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.xross.launcher.config.ConfigCodec
import dev.xross.launcher.data.GameEntity
import dev.xross.launcher.data.XrossSettings
import dev.xross.launcher.input.NavAction
import dev.xross.launcher.profile.AchievementsState
import dev.xross.launcher.profile.RaAchievement
import dev.xross.launcher.profile.RaGameProgress
import dev.xross.launcher.profile.RaLibrarySummary
import dev.xross.launcher.profile.RaMatchKind
import dev.xross.launcher.state.AvatarEditor
import dev.xross.launcher.state.AvatarEditorState
import dev.xross.launcher.state.AvatarRow
import dev.xross.launcher.state.Categories
import dev.xross.launcher.state.Command
import dev.xross.launcher.state.DetailsPager
import dev.xross.launcher.state.GameTelemetry
import dev.xross.launcher.state.MenuSnapshot
import dev.xross.launcher.state.MenuTree
import dev.xross.launcher.state.NodeAction
import dev.xross.launcher.state.RaHub
import dev.xross.launcher.state.RaHubEffect
import dev.xross.launcher.state.RaHubState
import dev.xross.launcher.state.XmbUiState
import dev.xross.launcher.state.XmbViewModel
import dev.xross.launcher.theme.AvatarColorPreset
import dev.xross.launcher.theme.AvatarStyleConfig
import dev.xross.launcher.theme.AvatarStyleLimits
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Phase 2b.1: avatar style settings, playtime subtitles, Details paging, the avatar mini-HUD's
 * state, game RetroAchievements telemetry and the RetroAchievements dashboard.
 */
@RunWith(RobolectricTestRunner::class)
class Phase2bFeaturesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun XmbViewModel.await(what: String, timeoutMs: Long = 5_000, condition: (XmbUiState) -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            idle()
            if (condition(state.value)) return
            if (System.currentTimeMillis() > deadline) fail("Timed out waiting for $what: ${state.value}")
            Thread.sleep(10)
        }
    }

    private fun XmbViewModel.press(action: NavAction, times: Int = 1) = repeat(times) {
        onAction(action)
        idle()
    }

    // region AvatarStyleConfig in settings.json

    @Test fun avatarStyleRoundTripsThroughSettingsJson() {
        val style = AvatarStyleConfig(borderColorHex = "#00E5FF", borderWidthDp = 4.5f, glowEnabled = false, glowSpeed = 1.7f, glowIntensity = 0.3f)
        val text = ConfigCodec.encodeSettings(XrossSettings(avatarStyle = style))
        assertEquals(style, ConfigCodec.decodeSettings(XrossSettings(), text).avatarStyle)

        // It is written as the `profile.avatarStyle` block.
        val block = Json.parseToJsonElement(text).jsonObject.getValue("profile").jsonObject.getValue("avatarStyle").jsonObject
        assertEquals("#00E5FF", block.getValue("borderColorHex").jsonPrimitive.content)
        assertEquals(4.5f, block.getValue("borderWidthDp").jsonPrimitive.float, 0.0001f)
        assertFalse(block.getValue("glowEnabled").jsonPrimitive.boolean)
        assertEquals(1.7f, block.getValue("glowSpeed").jsonPrimitive.float, 0.0001f)
        assertEquals(0.3f, block.getValue("glowIntensity").jsonPrimitive.float, 0.0001f)
    }

    @Test fun avatarStyleDefaultsWhenTheBlockIsMissing() {
        // A settings.json written before the block existed.
        val old = """{"version": 1, "display": {"theme": "CRIMSON"}}"""
        val decoded = ConfigCodec.decodeSettings(XrossSettings(), old)
        assertEquals(AvatarStyleConfig(), decoded.avatarStyle)
        assertEquals("#FFB5C5", decoded.avatarStyle.borderColorHex)
        assertEquals(2f, decoded.avatarStyle.borderWidthDp, 0f)
        assertTrue(decoded.avatarStyle.glowEnabled)
    }

    @Test fun handEditedAvatarStyleIsClampedAndABadColorFallsBackOnItsOwn() {
        val edited = """{"profile": {"avatarStyle": {"borderColorHex": "pink", "borderWidthDp": 40, "glowSpeed": 0.01, "glowIntensity": 7, "glowEnabled": false}}}"""
        val style = ConfigCodec.decodeSettings(XrossSettings(), edited).avatarStyle
        assertEquals("#FFB5C5", style.borderColorHex)
        assertEquals(6f, style.borderWidthDp, 0f)
        assertEquals(0.5f, style.glowSpeed, 0f)
        assertEquals(1f, style.glowIntensity, 0f)
        assertFalse(style.glowEnabled)
        // Short and unprefixed hex colors are accepted and normalized.
        assertEquals("#FFBB55", AvatarStyleLimits.normalizeColor("fb5"))
        assertEquals("#00E5FF", AvatarStyleLimits.normalizeColor(" 00e5ff "))
        assertNull(AvatarStyleLimits.normalizeColor("#12345"))
    }

    @Test fun credentialsNeverReachTheAvatarBlock() {
        val text = ConfigCodec.encodeSettings(XrossSettings())
        val profile = Json.parseToJsonElement(text).jsonObject.getValue("profile").jsonObject
        assertEquals(setOf("avatarStyle"), profile.keys)
    }

    // endregion

    // region Profile customization panel

    @Test fun avatarEditorStepsSlidersWithinTheirRanges() {
        var style = AvatarStyleConfig()
        style = AvatarEditor.adjust(AvatarRow.WIDTH, style, +1)!!
        assertEquals(2.5f, style.borderWidthDp, 0.0001f)
        repeat(20) { style = AvatarEditor.adjust(AvatarRow.WIDTH, style, +1) ?: style }
        assertEquals(6f, style.borderWidthDp, 0f)
        assertNull("At the end of the slider", AvatarEditor.adjust(AvatarRow.WIDTH, style, +1))

        repeat(30) { style = AvatarEditor.adjust(AvatarRow.SPEED, style, -1) ?: style }
        assertEquals(0.5f, style.glowSpeed, 0f)
        repeat(30) { style = AvatarEditor.adjust(AvatarRow.INTENSITY, style, -1) ?: style }
        assertEquals(0.1f, style.glowIntensity, 0.0001f)
        assertEquals(0f, AvatarEditor.fraction(AvatarRow.INTENSITY, style)!!, 0.0001f)

        // Touch: a drag snaps to the row's step.
        assertEquals(2f, AvatarEditor.setFraction(AvatarRow.SPEED, style, 1f).glowSpeed, 0f)
        assertEquals(3.5f, AvatarEditor.setFraction(AvatarRow.WIDTH, style, 0.5f).borderWidthDp, 0.0001f)
    }

    @Test fun avatarEditorCyclesColorPresetsTogglesGlowAndResets() {
        val start = AvatarStyleConfig()
        assertEquals("Pastel pink", AvatarEditor.value(AvatarRow.COLOR, start))
        val cyan = AvatarEditor.adjust(AvatarRow.COLOR, start, +1)!!
        assertEquals(AvatarColorPreset.NEON_CYAN.hex, cyan.borderColorHex)
        assertEquals(AvatarColorPreset.WHITE.hex, AvatarEditor.adjust(AvatarRow.COLOR, start, -1)!!.borderColorHex)
        // A custom color is shown as such, and Right moves on to the first preset.
        val custom = start.copy(borderColorHex = "#123456")
        assertEquals("Custom", AvatarEditor.value(AvatarRow.COLOR, custom))
        assertEquals(AvatarColorPreset.PASTEL_PINK.hex, AvatarEditor.adjust(AvatarRow.COLOR, custom, +1)!!.borderColorHex)

        val off = AvatarEditor.activate(AvatarRow.GLOW, start)!!
        assertFalse(off.glowEnabled)
        assertNull("Already off", AvatarEditor.adjust(AvatarRow.GLOW, off, -1))
        assertEquals(start, AvatarEditor.activate(AvatarRow.RESET, off.copy(borderWidthDp = 5f)))
        assertNull("Nothing to reset", AvatarEditor.activate(AvatarRow.RESET, start))
    }

    @Test fun avatarEditorInputMapsToOutcomes() {
        val style = AvatarStyleConfig()
        val top = AvatarEditorState(0)
        assertTrue(AvatarEditor.onAction(top, NavAction.UP, style).blocked)
        assertEquals(1, AvatarEditor.onAction(top, NavAction.DOWN, style).state.index)
        assertTrue(AvatarEditor.onAction(top, NavAction.BACK, style).close)
        val customRow = AvatarEditorState(AvatarRow.CUSTOM_COLOR.ordinal)
        assertTrue(AvatarEditor.onAction(customRow, NavAction.CONFIRM, style).promptColor)
        val width = AvatarEditorState(AvatarRow.WIDTH.ordinal)
        assertEquals(1.5f, AvatarEditor.onAction(width, NavAction.LEFT, style).style!!.borderWidthDp, 0.0001f)
    }

    @Test fun profileCustomizationOpensFromThemeSettingsAndSavesAtOnce() {
        val row = MenuTree(MenuSnapshot()).themeRows().single { it.id == "disp:avatar" }
        assertEquals("Profile customization", row.title)
        assertEquals("Pastel pink ring · 2 dp · glow on", row.subtitle)
        assertEquals(NodeAction.Run(Command.OpenAvatarStyle), row.action)

        val container = AppContainer(context)
        val vm = XmbViewModel(context, container, storageAccess = { false }).also { idle() }
        while (vm.state.value.let { it.categories[it.categoryIndex].id } != Categories.SETTINGS) vm.press(NavAction.LEFT)
        vm.await("the Settings root") { s -> s.items.isNotEmpty() }
        val theme = vm.state.value.items.indexOfFirst { (it.action as? NodeAction.OpenList)?.key == MenuTree.DISPLAY }
        vm.press(NavAction.DOWN, times = theme)
        vm.press(NavAction.CONFIRM)
        vm.await("the Theme list") { s -> s.items.any { it.id == MenuTree.DISPLAY_MENU } }
        vm.press(NavAction.DOWN, times = vm.state.value.items.indexOfFirst { it.id == MenuTree.DISPLAY_MENU })
        vm.press(NavAction.CONFIRM)
        vm.await("Theme ▸ Menu and profile") { s -> s.items.any { it.id == "disp:avatar" } }
        val avatar = vm.state.value.items.indexOfFirst { it.id == "disp:avatar" }
        vm.press(NavAction.DOWN, times = avatar)
        vm.press(NavAction.CONFIRM)
        assertNotNull(vm.state.value.avatarEditor)

        vm.press(NavAction.RIGHT) // Border color: next preset
        assertEquals(AvatarColorPreset.NEON_CYAN.hex, container.settings.current.avatarStyle.borderColorHex)
        assertEquals(AvatarColorPreset.NEON_CYAN.hex, vm.state.value.settings.avatarStyle.borderColorHex)
        vm.onAvatarSliderChanged(AvatarRow.WIDTH.ordinal, 1f)
        idle()
        assertEquals(6f, container.settings.current.avatarStyle.borderWidthDp, 0f)
        assertEquals(AvatarRow.WIDTH.ordinal, vm.state.value.avatarEditor!!.index)

        // A custom hex typed in the prompt is validated before it is saved.
        vm.onTextEntered(dev.xross.launcher.state.TextPrompt.AvatarBorderColor("#00E5FF"), "not-a-color")
        idle()
        assertEquals(AvatarColorPreset.NEON_CYAN.hex, container.settings.current.avatarStyle.borderColorHex)
        vm.onTextEntered(dev.xross.launcher.state.TextPrompt.AvatarBorderColor("#00E5FF"), "a1b2c3")
        idle()
        assertEquals("#A1B2C3", container.settings.current.avatarStyle.borderColorHex)

        vm.press(NavAction.BACK)
        assertNull(vm.state.value.avatarEditor)
    }

    // endregion

    // region Playtime subtitle

    @Test fun playtimeSubtitleBoundaries() {
        assertEquals("Never played", MenuTree.playtimeSubtitle(0))
        assertEquals("Playtime: 1 min", MenuTree.playtimeSubtitle(45_000)) // 45 s
        assertEquals("Playtime: 1 min", MenuTree.playtimeSubtitle(60_000))
        assertEquals("Playtime: 2 mins", MenuTree.playtimeSubtitle(2 * 60_000L + 59_000))
        assertEquals("Playtime: 59 mins", MenuTree.playtimeSubtitle(59 * 60_000L + 59_999))
        assertEquals("Playtime: 1h 0m", MenuTree.playtimeSubtitle(60 * 60_000L))
        assertEquals("Playtime: 1h 1m", MenuTree.playtimeSubtitle(61 * 60_000L)) // 61 m
        assertEquals("Playtime: 14h 25m", MenuTree.playtimeSubtitle((14 * 60 + 25) * 60_000L))
        assertEquals("Playtime: 120h 0m", MenuTree.playtimeSubtitle(120 * 3_600_000L)) // 120 h
        assertEquals("Never played", MenuTree.playtimeSubtitle(-5))
    }

    @Test fun gameRowsShowPlaytimeInsteadOfTheFileSize() {
        val games = listOf(
            GameEntity(1, "/r/snes/a.sfc", "snes", "A", "a.sfc", 1_500_000_000, 0, playTimeMs = (14 * 60 + 25) * 60_000L),
            GameEntity(2, "/r/snes/b.sfc", "snes", "B", "b.sfc", 2048, 0),
        )
        val rows = MenuTree(MenuSnapshot(games = games)).list("platform:snes")
        assertEquals("SFC · Playtime: 14h 25m", rows[0].subtitle)
        assertEquals("SFC · Never played", rows[1].subtitle)
        assertTrue(rows.none { "GB" in it.subtitle!! || "KB" in it.subtitle!! })
    }

    // endregion

    // region Details paging

    @Test fun detailsPagerStopsAtBothEnds() {
        val ids = listOf(10L, 20L, 30L)
        // First item
        assertNull(DetailsPager.neighbor(ids, 10, -1))
        assertEquals(20L, DetailsPager.neighbor(ids, 10, +1))
        assertEquals(0 to 3, DetailsPager.position(ids, 10))
        // Middle
        assertEquals(10L, DetailsPager.neighbor(ids, 20, -1))
        assertEquals(30L, DetailsPager.neighbor(ids, 20, +1))
        // Last item
        assertEquals(20L, DetailsPager.neighbor(ids, 30, -1))
        assertNull(DetailsPager.neighbor(ids, 30, +1))
        assertEquals(2 to 3, DetailsPager.position(ids, 30))
    }

    @Test fun detailsPagerOnSingleItemAndForeignLists() {
        assertNull(DetailsPager.neighbor(listOf(7L), 7, -1))
        assertNull(DetailsPager.neighbor(listOf(7L), 7, +1))
        assertEquals(0 to 1, DetailsPager.position(listOf(7L), 7))
        // A game that isn't in the list (opened from elsewhere) is a list of its own.
        assertNull(DetailsPager.neighbor(listOf(1L, 2L), 9, +1))
        assertEquals(0 to 1, DetailsPager.position(listOf(1L, 2L), 9))
        assertNull(DetailsPager.neighbor(emptyList(), 1, +1))
    }

    private fun snes(title: String) = GameEntity(
        path = "/storage/emulated/0/ROMs/snes/$title.sfc", platformId = "snes", title = title, fileName = "$title.sfc", sizeBytes = 4096, lastModified = 0,
    )

    private fun openSnesList(vararg titles: String): XmbViewModel {
        val container = AppContainer(context)
        runBlocking { container.database.games().insertAll(titles.map(::snes)) }
        val vm = XmbViewModel(context, container, storageAccess = { false }).also { idle() }
        vm.await("the All Games folder") { s -> s.focused?.title == "All Games" }
        vm.press(NavAction.CONFIRM)
        vm.press(NavAction.CONFIRM)
        assertEquals(titles.first(), vm.state.value.focused?.title)
        return vm
    }

    @Test fun shouldersAndDpadPageThroughGamesOnTheDetailsScreen() {
        val vm = openSnesList("Alpha", "Bravo", "Charlie")
        vm.press(NavAction.RIGHT) // → on a game opens Details
        vm.await("Details for Alpha") { it.details?.title == "Alpha" }
        assertEquals(0, vm.state.value.details!!.position)
        assertEquals(3, vm.state.value.details!!.count)
        assertFalse(vm.state.value.details!!.hasPrevious)

        // First item: nothing before it, and the screen stays open.
        vm.press(NavAction.PREV_CATEGORY)
        vm.press(NavAction.LEFT)
        assertEquals("Alpha", vm.state.value.details?.title)

        vm.press(NavAction.NEXT_CATEGORY) // R1
        vm.await("Details for Bravo") { it.details?.title == "Bravo" }
        assertEquals(1, vm.state.value.details!!.position)
        assertEquals(+1, vm.state.value.details!!.pageDirection)
        vm.press(NavAction.RIGHT) // D-pad right
        vm.await("Details for Charlie") { it.details?.title == "Charlie" }
        assertFalse(vm.state.value.details!!.hasNext)

        // Last item: R1 and → change nothing.
        vm.press(NavAction.NEXT_CATEGORY)
        vm.press(NavAction.RIGHT)
        assertEquals("Charlie", vm.state.value.details?.title)

        vm.press(NavAction.PREV_CATEGORY) // L1
        vm.await("Details for Bravo again") { it.details?.title == "Bravo" }
        assertEquals(-1, vm.state.value.details!!.pageDirection)

        // Back returns to the ribbon with the cursor on the game that was showing, one level deep
        // as before: paging never pops the menu.
        vm.press(NavAction.BACK)
        assertNull(vm.state.value.details)
        assertEquals("Bravo", vm.state.value.focused?.title)
        assertEquals(listOf("All Games", "Super Nintendo"), vm.state.value.breadcrumb)
    }

    @Test fun pagingASingleGameListDoesNothing() {
        val vm = openSnesList("Solo")
        vm.press(NavAction.RIGHT)
        vm.await("Details for Solo") { it.details?.title == "Solo" }
        assertEquals(1, vm.state.value.details!!.count)
        for (action in listOf(NavAction.LEFT, NavAction.RIGHT, NavAction.PREV_CATEGORY, NavAction.NEXT_CATEGORY)) vm.press(action)
        assertEquals("Solo", vm.state.value.details?.title)
        assertEquals(listOf("All Games", "Super Nintendo"), vm.state.value.breadcrumb)
    }

    // endregion

    // region RetroAchievements telemetry and dashboard

    private fun progress(id: Int, unlocked: Int, total: Int) = RaGameProgress(
        gameId = id,
        title = "Game $id",
        achievements = (1..total).map {
            RaAchievement(id = id * 100 + it, title = "A$it", points = 10, unlockedAt = if (it <= unlocked) "2026-01-0$it 10:00:00" else null)
        },
    )

    @Test fun telemetryOnlyForRecognizedGamesWithAchievements() {
        val t = GameTelemetry.of(5, AchievementsState.Ready(progress(1, 3, 7), RaMatchKind.HASH))!!
        assertEquals(3, t.unlocked)
        assertEquals(7, t.total)
        assertEquals(30, t.earnedPoints)
        assertEquals(70, t.totalPoints)
        assertFalse(t.mastered)
        assertTrue(GameTelemetry.of(5, AchievementsState.Ready(progress(1, 7, 7), RaMatchKind.HASH))!!.mastered)

        assertNull(GameTelemetry.of(5, null))
        assertNull(GameTelemetry.of(5, AchievementsState.NoMatch)) // unhashed
        assertNull(GameTelemetry.of(5, AchievementsState.NotLinked))
        assertNull(GameTelemetry.of(5, AchievementsState.Unsupported("switch")))
        assertNull(GameTelemetry.of(5, AchievementsState.Failed("offline")))
        assertNull("A set without achievements", GameTelemetry.of(5, AchievementsState.Ready(progress(1, 0, 0), RaMatchKind.HASH)))
    }

    @Test fun telemetryFollowsTheFocusedRow() {
        val game = XmbUiState(
            items = listOf(dev.xross.launcher.state.XmbNode("game:5", "G", action = NodeAction.LaunchGame(5))),
            telemetry = GameTelemetry(5, 1, 2, 10, 20),
        )
        assertNotNull(game.focusedTelemetry)
        assertNull("Numbers of the previous row are never shown on the next", game.copy(telemetry = GameTelemetry(6, 1, 2, 10, 20)).focusedTelemetry)
    }

    @Test fun librarySummaryCountsEachSetOnceAndFindsMasteredGames() {
        val a = GameEntity(1, "/r/snes/a.sfc", "snes", "Zelda", "a.sfc", 1, 0)
        val aDupe = GameEntity(2, "/r/snes/a2.sfc", "snes", "Zelda (Rev 1)", "a2.sfc", 1, 0)
        val b = GameEntity(3, "/r/gba/b.gba", "gba", "Advance Wars", "b.gba", 1, 0)
        val c = GameEntity(4, "/r/gba/c.gba", "gba", "No Set", "c.gba", 1, 0)
        val summary = RaLibrarySummary.of(
            supportedGames = 6,
            known = listOf(a to progress(1, 4, 4), aDupe to progress(1, 4, 4), b to progress(2, 1, 6), c to progress(3, 0, 0)),
        )
        assertEquals(6, summary.supportedGames)
        assertEquals(2, summary.trackedGames)
        assertEquals(10, summary.achievements)
        assertEquals(5, summary.unlocked)
        assertEquals(100, summary.points)
        assertEquals(50, summary.earnedPoints)
        assertEquals(0.5f, summary.completion, 0.0001f)
        assertEquals(listOf("Zelda"), summary.mastered.map { it.title })
        assertEquals(0f, RaLibrarySummary().completion, 0f)
    }

    @Test fun retroAchievementsEntrySitsWithTheCollectionsInGames() {
        val games = listOf(GameEntity(1, "/r/snes/a.sfc", "snes", "A", "a.sfc", 10, 0, favorite = true, lastPlayed = 5))
        val root = MenuTree(MenuSnapshot(games = games, storageGranted = true)).list(Categories.rootKey(Categories.GAMES))
        assertEquals(listOf("Search", "Recently Played", "Favorites", "All Games", "RetroAchievements"), root.map { it.title })
        val entry = root.last()
        assertEquals(MenuTree.RA_HUB, entry.id)
        assertEquals(NodeAction.Run(Command.OpenRaHub), entry.action)
        assertEquals("Link an account to track achievements", entry.subtitle)
        // An empty, unlinked library still shows only the pointer to Settings.
        assertTrue(MenuTree(MenuSnapshot()).list(Categories.rootKey(Categories.GAMES)).none { it.id == MenuTree.RA_HUB })
    }

    @Test fun dashboardScrollsAndCloses() {
        var hub = RaHubState()
        assertEquals(hub, RaHub.onAction(hub, NavAction.UP).state)
        hub = RaHub.onAction(hub, NavAction.DOWN).state
        assertEquals(1, hub.scroll)
        hub = RaHub.onAction(hub, NavAction.NEXT_CATEGORY).state
        assertEquals(5, hub.scroll)
        repeat(100) { hub = RaHub.onAction(hub, NavAction.DOWN).state }
        assertEquals(RaHubState.MAX_SCROLL, hub.scroll)
        assertEquals(RaHubEffect.Close, RaHub.onAction(hub, NavAction.BACK).effect)
        assertEquals(RaHubEffect.Primary, RaHub.onAction(hub, NavAction.CONFIRM).effect)
    }

    // endregion
}
