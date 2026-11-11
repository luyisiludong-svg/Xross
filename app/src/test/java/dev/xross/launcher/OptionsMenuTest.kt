package dev.xross.launcher

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.xross.launcher.data.GameEntity
import dev.xross.launcher.data.XrossDatabase
import dev.xross.launcher.data.XrossSettings
import dev.xross.launcher.launch.GameLauncher
import dev.xross.launcher.state.Command
import dev.xross.launcher.state.MenuOption
import dev.xross.launcher.state.MenuSnapshot
import dev.xross.launcher.state.MenuTree
import dev.xross.launcher.state.OptionsMenuState
import dev.xross.launcher.state.OptionsMenuState.Selection
import dev.xross.launcher.storage.PlatformRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OptionsMenuTest {

    // region Menu state

    private val sub = listOf(MenuOption("Default", command = Command.Rescan), MenuOption("Other", command = Command.ToggleWave))
    private val options = listOf(
        MenuOption("Play", command = Command.LaunchGame(1)),
        MenuOption("Emulator", detail = "RetroArch", submenu = sub),
        MenuOption("Played", detail = "2 h"),
    )

    @Test fun cursorMovesAndClamps() {
        var m = OptionsMenuState.open("Chrono Trigger", options)
        assertEquals("Play", m.focused?.label)
        m = m.move(-1)
        assertEquals(0, m.current.index)
        m = m.move(+1).move(+1).move(+1)
        assertEquals("Played", m.focused?.label)
    }

    @Test fun selectingRunsACommandOpensASubmenuOrDoesNothing() {
        val m = OptionsMenuState.open("Chrono Trigger", options)
        assertEquals(Selection.Run(Command.LaunchGame(1)), m.select())

        val pushed = m.move(+1).select()
        assertTrue(pushed is Selection.Open)
        val inner = (pushed as Selection.Open).menu
        assertEquals("Emulator", inner.current.title)
        assertEquals(listOf("Default", "Other"), inner.current.options.map { it.label })
        assertEquals(Selection.Run(Command.ToggleWave), inner.move(+1).select())

        // Information rows have nothing to run.
        assertEquals(Selection.None, m.move(+2).select())
    }

    @Test fun backLeavesTheSubmenuThenClosesAndKeepsTheParentCursor() {
        val inner = (OptionsMenuState.open("Game", options).move(+1).select() as Selection.Open).menu
        val parent = inner.back()!!
        assertEquals("Emulator", parent.focused?.label)
        assertNull(parent.back())
    }

    @Test fun submenuOpensOnTheSelectedEntry() {
        val marked = listOf(MenuOption("A"), MenuOption("B", selected = true))
        val menu = OptionsMenuState.open("x", listOf(MenuOption("Pick", submenu = marked)))
        assertEquals("B", (menu.select() as Selection.Open).menu.focused?.label)
    }

    // endregion

    // region Menus on items

    private fun gameMenu(game: GameEntity, installed: Set<String>, settings: XrossSettings = XrossSettings()) =
        MenuTree(MenuSnapshot(games = listOf(game), installedEmulators = installed, settings = settings))
            .list("platform:${game.platformId}").single().menu

    @Test fun gameMenuOffersPlayFavoriteEmulatorAndHistory() {
        val game = GameEntity(7, "/r/psx/a.chd", "psx", "Vagrant Story", "a.chd", 10, 0, playCount = 3, playTimeMs = 135 * 60_000L)
        val menu = gameMenu(game, installed = setOf("duckstation", "retroarch"))
        assertEquals(listOf("Play", "Add to favorites", "Emulator", "Played"), menu.map { it.label })
        assertEquals(Command.LaunchGame(7), menu[0].command)
        assertEquals(Command.ToggleFavorite(7), menu[1].command)
        assertEquals("DuckStation", menu[2].detail)
        assertEquals("2 h 15 min · 3 times", menu[3].detail)

        val choices = menu[2].submenu
        assertEquals(listOf("Platform default", "DuckStation", "RetroArch · swanstation", "RetroArch · pcsx_rearmed"), choices.map { it.label })
        assertEquals(Command.SetGameEmulator(7, null), choices[0].command)
        assertEquals(Command.SetGameEmulator(7, "retroarch:swanstation"), choices[2].command)
        assertTrue(choices[0].selected)
    }

    @Test fun overrideIsShownAndMarkedAndMissingEmulatorsAreFlagged() {
        val game = GameEntity(7, "/r/psx/a.chd", "psx", "Vagrant Story", "a.chd", 10, 0, favorite = true, emulatorOverride = "retroarch:swanstation")
        val menu = gameMenu(game, installed = setOf("retroarch"))
        assertEquals("Remove from favorites", menu[1].label)
        assertEquals("RetroArch · swanstation · this game only", menu[2].detail)
        val choices = menu[2].submenu
        assertEquals(listOf(false, false, true, false), choices.map { it.selected })
        assertEquals("Not installed", choices[1].detail) // DuckStation
        assertEquals("RetroArch · swanstation", choices[0].detail) // what the platform default resolves to
        // No sessions yet: no history row.
        assertEquals(listOf("Play", "Remove from favorites", "Emulator"), menu.map { it.label })
    }

    // endregion

    // region Launch resolution

    @Test fun gameOverrideBeatsPlatformChoiceWhichBeatsTheDefault() {
        val psx = PlatformRegistry.byId("psx")!!
        val installed = setOf("duckstation", "retroarch")
        assertEquals("retroarch:pcsx_rearmed", GameLauncher.resolveOption(psx, "retroarch:swanstation", installed, overrideKey = "retroarch:pcsx_rearmed")?.key)
        assertEquals("retroarch:swanstation", GameLauncher.resolveOption(psx, "retroarch:swanstation", installed, overrideKey = null)?.key)
        assertEquals("duckstation", GameLauncher.resolveOption(psx, null, installed, overrideKey = null)?.key)
        // The overridden emulator was uninstalled: fall back to the platform choice.
        assertEquals("retroarch:swanstation", GameLauncher.resolveOption(psx, "retroarch:swanstation", setOf("retroarch"), overrideKey = "duckstation")?.key)
    }

    // endregion

    // region Persistence

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun overrideIsStoredAndCleared() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, XrossDatabase::class.java).allowMainThreadQueries().build()
        val dao = db.games()
        dao.insertAll(listOf(GameEntity(1, "/a.chd", "psx", "A", "a.chd", 1, 0)))
        dao.setEmulatorOverride(1, "retroarch:swanstation")
        assertEquals("retroarch:swanstation", dao.get(1)?.emulatorOverride)
        dao.setEmulatorOverride(1, null)
        assertNull(dao.get(1)?.emulatorOverride)
        db.close()
    }

    @get:Rule
    val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), XrossDatabase::class.java)

    @Test fun migration2To4KeepsDataAndAddsNullOverride() {
        migrations.createDatabase("override.db", 2).use { db ->
            db.execSQL(
                "INSERT INTO games (id, path, platformId, title, fileName, sizeBytes, lastModified, favorite, lastPlayed, playCount) " +
                    "VALUES (5, '/r/psx/a.chd', 'psx', 'Vagrant Story', 'a.chd', 10, 20, 1, 1234, 3)",
            )
        }
        migrations.runMigrationsAndValidate("override.db", 4, true, *XrossDatabase.MIGRATIONS).use { db ->
            db.query("SELECT title, favorite, playTimeMs, emulatorOverride FROM games WHERE id = 5").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("Vagrant Story", c.getString(0))
                assertEquals(1, c.getInt(1))
                assertEquals(0L, c.getLong(2))
                assertTrue(c.isNull(3))
            }
        }
    }

    // endregion
}
