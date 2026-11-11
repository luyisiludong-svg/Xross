package dev.xross.launcher

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.xross.launcher.data.GameEntity
import dev.xross.launcher.data.PlaySessionTracker
import dev.xross.launcher.data.PrefsSessionStore
import dev.xross.launcher.data.XrossDatabase
import dev.xross.launcher.state.MenuSnapshot
import dev.xross.launcher.state.MenuTree
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlaytimeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var now = 1_000_000L
    private fun tracker() = PlaySessionTracker(PrefsSessionStore(context)) { now }

    // region Session tracking

    @Test fun sessionRunsFromLauncherPauseToReturn() {
        val t = tracker()
        t.arm(gameId = 7)
        now += 1_200 // start transition, then the emulator takes over
        t.onBackground()
        now += 25 * 60_000
        assertEquals(PlaySessionTracker.Finished(7, 25 * 60_000L), t.onForeground())
        // Counted once.
        assertNull(t.onForeground())
    }

    @Test fun pauseLongAfterAFailedLaunchIsNotCounted() {
        val t = tracker()
        t.arm(7) // launch failed: the launcher never went to the background
        now += 5 * 60_000
        t.onBackground() // user opened Android settings much later
        now += 60_000
        assertNull(t.onForeground())
    }

    @Test fun pauseWithoutALaunchIsNotCounted() {
        val t = tracker()
        t.onBackground()
        now += 60_000
        assertNull(t.onForeground())
    }

    @Test fun sessionSurvivesTheLauncherProcessBeingKilled() {
        tracker().apply { arm(3); onBackground() }
        now += 40 * 60_000
        // New process: a fresh tracker over the same persisted store.
        assertEquals(PlaySessionTracker.Finished(3, 40 * 60_000L), tracker().onForeground())
    }

    @Test fun clockChangesAndRunawaySessionsAreBounded() {
        tracker().apply { arm(3); onBackground() }
        now -= 60_000 // clock moved backwards
        assertNull(tracker().onForeground())

        tracker().apply { arm(3); onBackground() }
        now += 72 * 3_600_000L // never came back for three days
        assertEquals(PlaySessionTracker.MAX_SESSION_MS, tracker().onForeground()?.durationMs)
    }

    // endregion

    // region Persistence

    @Test fun playtimeAccumulatesPerGame() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, XrossDatabase::class.java).allowMainThreadQueries().build()
        val dao = db.games()
        dao.insertAll(listOf(GameEntity(1, "/a.sfc", "snes", "A", "a.sfc", 1, 0), GameEntity(2, "/b.sfc", "snes", "B", "b.sfc", 1, 0)))
        dao.addPlayTime(1, 90_000)
        dao.addPlayTime(1, 30_000)
        assertEquals(120_000L, dao.get(1)?.playTimeMs)
        assertEquals(0L, dao.get(2)?.playTimeMs)
        db.close()
    }

    @get:Rule
    val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), XrossDatabase::class.java)

    @Test fun migration2To3KeepsGamesAndAddsZeroPlaytime() {
        migrations.createDatabase("migrate.db", 2).use { db ->
            db.execSQL(
                "INSERT INTO games (id, path, platformId, title, fileName, sizeBytes, lastModified, favorite, lastPlayed, playCount) " +
                    "VALUES (5, '/r/snes/a.sfc', 'snes', 'Chrono Trigger', 'a.sfc', 10, 20, 1, 1234, 3)",
            )
            db.execSQL(
                "INSERT INTO game_hashes (gameId, fileSize, fileModified, source, crc32, headerBytes, hashedAt) " +
                    "VALUES (5, 10, 20, 'FILE', '352441c2', 0, 99)",
            )
        }
        // Validates the migrated schema against the exported v3 schema.
        migrations.runMigrationsAndValidate("migrate.db", 3, true, XrossDatabase.MIGRATION_2_3).use { db ->
            db.query("SELECT title, favorite, playCount, playTimeMs FROM games WHERE id = 5").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("Chrono Trigger", c.getString(0))
                assertEquals(1, c.getInt(1))
                assertEquals(3, c.getInt(2))
                assertEquals(0L, c.getLong(3))
            }
            db.query("SELECT crc32 FROM game_hashes WHERE gameId = 5").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("352441c2", c.getString(0))
            }
            // The FTS index still finds the game after migration.
            db.query("SELECT COUNT(*) FROM games_fts WHERE games_fts MATCH 'chrono*'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(1, c.getInt(0))
            }
        }
    }

    // endregion

    // region UI

    @Test fun playtimeIsFormattedForHumans() {
        assertNull(MenuTree.formatPlaytime(0))
        assertNull(MenuTree.formatPlaytime(59_000))
        assertEquals("1 min", MenuTree.formatPlaytime(60_000))
        assertEquals("45 min", MenuTree.formatPlaytime(45 * 60_000L))
        assertEquals("2 h 15 min", MenuTree.formatPlaytime(135 * 60_000L))
        assertEquals("120 h", MenuTree.formatPlaytime(120 * 3_600_000L))
    }

    @Test fun gameRowsShowAccumulatedPlaytime() {
        val games = listOf(
            GameEntity(1, "/r/snes/a.sfc", "snes", "Played", "a.sfc", 2048, 0, lastPlayed = 5, playTimeMs = 135 * 60_000L),
            GameEntity(2, "/r/snes/b.sfc", "snes", "Never", "b.sfc", 2048, 0),
        )
        val rows = MenuTree(MenuSnapshot(games = games)).list("platform:snes")
        assertEquals(listOf("Played", "Never"), rows.map { it.title })
        assertTrue(rows[0].subtitle!!.contains("Played 2 h 15 min"))
        assertTrue("Played" !in rows[1].subtitle!!)
    }

    // endregion
}
