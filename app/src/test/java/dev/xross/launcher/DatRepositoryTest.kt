package dev.xross.launcher

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.xross.launcher.data.DatRepository
import dev.xross.launcher.data.GameEntity
import dev.xross.launcher.data.GameHashEntity
import dev.xross.launcher.data.GameMetadataEntity
import dev.xross.launcher.data.XrossDatabase
import dev.xross.launcher.data.dat.MatchKind
import dev.xross.launcher.storage.Fingerprint
import dev.xross.launcher.storage.HashSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class DatRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, XrossDatabase::class.java).allowMainThreadQueries().build()
    private val repo = DatRepository(db) { 1_000L }

    @get:Rule val tmp = TemporaryFolder()

    @After fun close() = db.close()

    private val ctSha1 = "de5822f4f2f7a55acb8926d4c0eaa63d5d989312"

    private fun snesDat(sha1: String = ctSha1, name: String = "Chrono Trigger (USA)") = """
        <?xml version="1.0"?>
        <datafile>
          <header><name>Nintendo - Super Nintendo Entertainment System</name><version>1</version><homepage>No-Intro</homepage></header>
          <game name="$name"><description>$name</description>
            <rom name="$name.sfc" size="4194304" crc="2d206bf7" sha1="$sha1" serial="SNS-ACTE-USA"/>
          </game>
        </datafile>
    """.trimIndent()

    /** A badly named file whose content is Chrono Trigger. */
    private fun insertGame(): GameEntity = runBlocking {
        val game = GameEntity(path = "/r/snes/ct.sfc", platformId = "snes", title = "ct", fileName = "ct.sfc", sizeBytes = 4194304, lastModified = 7)
        db.games().insertAll(listOf(game))
        val stored = db.games().getAll().single()
        db.hashes().upsert(GameHashEntity.from(stored, Fingerprint(HashSource.FILE, crc32 = "2d206bf7", sha1 = ctSha1)))
        stored
    }

    @Test fun importVerifiesTheGameAndFixesItsTitle() = runBlocking {
        val game = insertGame()
        val dir = tmp.newFolder("dats")
        File(dir, "snes.dat").writeText(snesDat())

        val result = repo.importFolder(dir)
        assertEquals(1, result.imported)
        assertTrue(result.failures.isEmpty())

        val meta = db.metadata().get(game.id)!!
        assertEquals(MatchKind.SHA1.name, meta.matchKind)
        assertEquals("Chrono Trigger", meta.datTitle)
        assertEquals("USA", meta.region)
        assertEquals("SNS-ACTE-USA", meta.serial)
        val updated = db.games().get(game.id)!!
        assertEquals("Chrono Trigger", updated.title)
        assertEquals("ct", updated.fileTitle)
        // Search follows the new title through the FTS triggers.
        assertEquals(listOf(game.id), db.games().search("chrono*").map { it.id })

        val dat = db.dats().files().single()
        assertEquals("snes", dat.platformId)
        assertEquals(1, dat.gameCount)
        assertEquals(1, repo.summaries.first().single().matched)
    }

    @Test fun unchangedDatsAreSkippedAndRemovedOnesForgotten() = runBlocking {
        val game = insertGame()
        val dir = tmp.newFolder("dats")
        val file = File(dir, "snes.dat").apply { writeText(snesDat()) }
        repo.importFolder(dir)
        assertEquals(1, repo.importFolder(dir).unchanged)

        file.delete()
        val result = repo.importFolder(dir)
        assertEquals(1, result.removed)
        assertTrue(db.dats().files().isEmpty())
        // The match is gone and the scanner's title is back.
        assertNull(db.metadata().get(game.id)!!.matchKind)
        assertEquals("ct", db.games().get(game.id)!!.title)
    }

    @Test fun reimportReplacesTheOldVersion() = runBlocking {
        val game = insertGame()
        val dir = tmp.newFolder("dats")
        val file = File(dir, "snes.dat").apply { writeText(snesDat()) }
        repo.importFolder(dir)
        file.writeText(snesDat(name = "Chrono Trigger (USA) (Rev 1)"))
        file.setLastModified(file.lastModified() + 5_000)
        assertEquals(1, repo.importFolder(dir).imported)
        assertEquals(1, db.dats().files().size)
        assertEquals("Rev 1", db.metadata().get(game.id)!!.revision)
    }

    @Test fun readsDatsInsideZipsAndReportsBrokenOnes() = runBlocking {
        val game = insertGame()
        val dir = tmp.newFolder("dats")
        ZipOutputStream(File(dir, "pack.zip").outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("No-Intro/snes.dat"))
            zip.write(snesDat().toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("readme.txt"))
            zip.write("ignored".toByteArray())
            zip.closeEntry()
        }
        File(dir, "broken.xml").writeText("<html>nope</html>")

        val result = repo.importFolder(dir)
        assertEquals(1, result.imported)
        assertEquals(1, result.failures.size)
        assertTrue(result.failures.single().startsWith("broken.xml"))
        assertTrue(db.dats().files().single().path.endsWith("pack.zip!/No-Intro/snes.dat"))
        assertEquals("Chrono Trigger", db.games().get(game.id)!!.title)
    }

    @Test fun manualLockIsRespected() = runBlocking {
        val game = insertGame()
        db.metadata().upsert(GameMetadataEntity(game.id, titleOverride = "My Hack", locked = true))
        val dir = tmp.newFolder("dats")
        File(dir, "snes.dat").writeText(snesDat())
        repo.importFolder(dir)
        val meta = db.metadata().get(game.id)!!
        assertNull(meta.matchKind)
        db.games().applyTitles()
        assertEquals("My Hack", db.games().get(game.id)!!.title)
    }

    @Test fun arcadeSetsMatchByName() = runBlocking {
        db.games().insertAll(listOf(GameEntity(path = "/r/arcade/SF2.zip", platformId = "arcade", title = "SF2", fileName = "SF2.zip", sizeBytes = 1, lastModified = 1)))
        val dir = tmp.newFolder("dats")
        File(dir, "mame.xml").writeText(
            """<?xml version="1.0"?><datafile><header><name>MAME 0.264</name></header>
               <machine name="sf2"><description>Street Fighter II: The World Warrior (World 910522)</description><rom name="a" size="1" crc="00000001"/></machine></datafile>""",
        )
        repo.importFolder(dir)
        val game = db.games().getAll().single()
        assertEquals("Street Fighter II: The World Warrior", game.title)
        assertEquals(MatchKind.SET_NAME.name, db.metadata().get(game.id)!!.matchKind)
    }

    @Test fun newGamesGetDistinctUuidsAndKeepThemAcrossRescans() = runBlocking {
        db.games().insertAll(
            listOf(
                GameEntity(path = "/a.sfc", platformId = "snes", title = "A", fileName = "a.sfc", sizeBytes = 1, lastModified = 1),
                GameEntity(path = "/b.sfc", platformId = "snes", title = "B", fileName = "b.sfc", sizeBytes = 1, lastModified = 1),
            ),
        )
        val (a, b) = db.games().getAll().sortedBy { it.path }
        assertNotEquals(a.uuid, b.uuid)
        db.games().updateFile(a.id, "snes", "A2", 2, 2)
        assertEquals(a.uuid, db.games().get(a.id)!!.uuid)
    }

    // region Migration

    @get:Rule
    val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), XrossDatabase::class.java)

    @Test fun migration4To5KeepsDataAndBackfillsTitlesAndUuids() {
        migrations.createDatabase("v4.db", 4).use { db ->
            for (id in 1..3) {
                db.execSQL(
                    "INSERT INTO games (id, path, platformId, title, fileName, sizeBytes, lastModified, favorite, lastPlayed, playCount, playTimeMs, emulatorOverride) " +
                        "VALUES ($id, '/r/snes/$id.sfc', 'snes', 'Game $id', '$id.sfc', 10, 20, 1, 1234, 3, 600000, 'retroarch:snes9x')",
                )
            }
            db.execSQL("INSERT INTO game_hashes (gameId, fileSize, fileModified, source, crc32, headerBytes, hashedAt) VALUES (1, 10, 20, 'FILE', '2d206bf7', 0, 99)")
        }
        migrations.runMigrationsAndValidate("v4.db", 5, true, *XrossDatabase.MIGRATIONS).use { db ->
            val uuids = ArrayList<String>()
            db.query("SELECT title, fileTitle, uuid, favorite, playTimeMs, emulatorOverride FROM games ORDER BY id").use { c ->
                while (c.moveToNext()) {
                    assertEquals(c.getString(0), c.getString(1))
                    assertEquals(1, c.getInt(3))
                    assertEquals(600000L, c.getLong(4))
                    assertEquals("retroarch:snes9x", c.getString(5))
                    uuids += c.getString(2)
                }
            }
            assertEquals(3, uuids.toSet().size)
            val uuidShape = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
            uuids.forEach { assertTrue(it, uuidShape.matches(it)) }
            db.query("SELECT crc32 FROM game_hashes WHERE gameId = 1").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("2d206bf7", c.getString(0))
            }
            db.query("SELECT COUNT(*) FROM games_fts WHERE games_fts MATCH 'game*'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(3, c.getInt(0))
            }
        }
    }

    // endregion
}
