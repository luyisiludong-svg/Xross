package dev.xross.launcher

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.xross.launcher.data.FingerprintQueue
import dev.xross.launcher.data.GameEntity
import dev.xross.launcher.data.GameHashEntity
import dev.xross.launcher.data.XrossDatabase
import dev.xross.launcher.storage.Fingerprint
import dev.xross.launcher.storage.FingerprintPolicy
import dev.xross.launcher.storage.HashSource
import dev.xross.launcher.storage.SkipReason
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FingerprintQueueTest {
    @get:Rule val tmp = TemporaryFolder()

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(), XrossDatabase::class.java,
    ).allowMainThreadQueries().build()
    private val games = db.games()
    private val hashes = db.hashes()

    @After fun close() = db.close()

    private fun game(id: Long, path: String, size: Long = 3) =
        GameEntity(id, path, "snes", "G$id", path.substringAfterLast('/'), size, 100)

    @Test fun pendingTracksStalenessAndLargeImagePolicy() = runBlocking {
        games.insertAll(listOf(game(1, "/a.sfc"), game(2, "/b.sfc"), game(3, "/c.iso")))
        assertEquals(listOf(1L, 2L, 3L), hashes.pending(0, false, 10).map { it.id })

        hashes.upsert(GameHashEntity.from(game(1, "/a.sfc"), Fingerprint(HashSource.FILE, crc32 = "00000001")))
        hashes.upsert(GameHashEntity.from(game(3, "/c.iso"), Fingerprint.skipped(SkipReason.LARGE)))
        assertEquals(listOf(2L), hashes.pending(0, false, 10).map { it.id })
        assertEquals(listOf(2L, 3L), hashes.pending(0, true, 10).map { it.id })
        assertEquals(listOf(3L), hashes.pending(2, true, 10).map { it.id }) // keyset paging

        // The file changed on disk → its fingerprint is stale.
        games.updateFile(1, "snes", "G1", size = 4, modified = 100)
        assertTrue(1L in hashes.pending(0, false, 10).map { it.id })
    }

    @Test fun statsAndCascadeDelete() = runBlocking {
        games.insertAll(listOf(game(1, "/a.sfc"), game(2, "/b.zip"), game(3, "/c.cue"), game(4, "/d.sfc")))
        hashes.upsert(GameHashEntity.from(game(1, "/a.sfc"), Fingerprint(HashSource.FILE, crc32 = "0000000a")))
        hashes.upsert(GameHashEntity.from(game(2, "/b.zip"), Fingerprint(HashSource.ARCHIVE_SET)))
        hashes.upsert(GameHashEntity.from(game(3, "/c.cue"), Fingerprint.skipped(SkipReason.MULTI_FILE)))
        val st = hashes.observeStats().first()
        assertEquals(listOf(4, 1, 1, 1, 1), listOf(st.total, st.hashed, st.byName, st.skipped, st.pending))
        assertEquals(1, hashes.findByCrc("0000000a").size)

        games.deleteIds(listOf(1))
        assertNull(hashes.get(1))
    }

    @Test fun queueFingerprintsRealFilesAndSkipsMissingOnes() = runBlocking {
        val rom = tmp.newFile("Game.sfc").apply { writeText("abc") }
        games.insertAll(listOf(game(1, rom.path, size = 3), game(2, "/storage/UNMOUNTED/x.sfc")))
        val queue = FingerprintQueue(games, hashes) { FingerprintPolicy() }
        queue.start(this)
        // start() launches on the queue's own thread; wait for it to drain.
        while (queue.current.value != null || hashes.get(1) == null) kotlinx.coroutines.delay(20)
        queue.stop()
        assertEquals("352441c2", hashes.get(1)?.crc32)
        assertNull(hashes.get(2)) // missing file: not recorded, retried next session
    }
}
