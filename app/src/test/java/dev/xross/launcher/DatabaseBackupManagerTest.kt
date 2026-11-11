package dev.xross.launcher

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.xross.launcher.data.BackupResult
import dev.xross.launcher.data.DatabaseBackupManager
import dev.xross.launcher.data.GameEntity
import dev.xross.launcher.data.XrossDatabase
import dev.xross.launcher.storage.XrossEnvironment
import dev.xross.launcher.storage.XrossLayout
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.io.RandomAccessFile

@RunWith(RobolectricTestRunner::class)
class DatabaseBackupManagerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: XrossDatabase
    private lateinit var layout: XrossLayout
    private lateinit var backups: DatabaseBackupManager

    @Before fun setUp() {
        context.deleteDatabase(XrossDatabase.NAME)
        DatabaseBackupManager.stagedFile(context).delete()
        db = XrossDatabase.create(context)
        layout = XrossLayout(File(tmp.root, "Xross"), portable = true).also { XrossEnvironment.bootstrap(it.root) }
        backups = DatabaseBackupManager(context, database = { db }, layout = { layout })
    }

    @After fun tearDown() {
        db.close()
        context.deleteDatabase(XrossDatabase.NAME)
    }

    private fun game(i: Int) = GameEntity(
        path = "/storage/emulated/0/ROMs/snes/game$i.sfc", platformId = "snes", title = "Game $i",
        fileName = "game$i.sfc", sizeBytes = 1024L * i, lastModified = 0,
    )

    /** Room reports table changes asynchronously; lets pending notifications land. */
    private fun settle() {
        db.invalidationTracker.refreshVersionsSync()
        repeat(20) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    private fun awaitUnsavedChanges() {
        val deadline = System.currentTimeMillis() + 3_000
        while (!backups.hasUnsavedChanges) {
            db.invalidationTracker.refreshVersionsSync()
            shadowOf(Looper.getMainLooper()).idle()
            if (System.currentTimeMillis() > deadline) fail("the change was never reported")
            Thread.sleep(10)
        }
    }

    private fun insert(vararg ids: Int) = runBlocking { db.games().insertAll(ids.map(::game)) }

    @Test fun backupIsASelfContainedSnapshotInTheDataFolder() = runBlocking {
        insert(1, 2, 3)
        val result = backups.backupNow()
        assertTrue(result.toString(), result is BackupResult.Done)
        val file = File(layout.data, "xross.db")
        assertEquals(file, (result as BackupResult.Done).info.file)
        assertEquals(3, result.info.games)
        assertEquals(XrossDatabase.VERSION, result.info.schemaVersion)
        // Rollback-journal header: readable alone, and no WAL/SHM companions or temp file in Xross/data.
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(18)
            assertEquals(1, raf.read())
            assertEquals(1, raf.read())
        }
        assertEquals(listOf("xross.db"), layout.data.list()!!.toList())
        assertNotNull(backups.status.value.lastBackupAt)
    }

    @Test fun writesStillInTheLogAreIncluded() = runBlocking {
        insert(1)
        backups.backupNow()
        insert(2, 3, 4) // lands in the WAL first
        backups.backupNow()
        assertEquals(4, DatabaseBackupManager.inspect(layout.databaseBackup)!!.games)
    }

    @Test fun backgroundBackupRunsOnlyAfterChanges() = runBlocking {
        backups.watch()
        insert(1)
        settle()
        assertTrue(backups.backupIfChanged() is BackupResult.Done)
        assertFalse(backups.hasUnsavedChanges)
        assertTrue(backups.backupIfChanged() is BackupResult.Skipped)
        insert(2)
        awaitUnsavedChanges()
        assertTrue(backups.backupIfChanged() is BackupResult.Done)
        assertEquals(2, DatabaseBackupManager.inspect(layout.databaseBackup)!!.games)
    }

    @Test fun noBackupWithoutSharedStorage() = runBlocking {
        layout = layout.copy(portable = false)
        insert(1)
        val result = backups.backupNow()
        assertTrue(result is BackupResult.Skipped)
        assertFalse(layout.databaseBackup.exists())
        assertEquals("Needs storage access", backups.status.value.message)
    }

    @Test fun cleanInstallOffersTheBackupInTheXrossFolder() = runBlocking {
        insert(1, 2)
        backups.backupNow()
        // Same folder, fresh install: the live library is empty.
        db.close()
        context.deleteDatabase(XrossDatabase.NAME)
        db = XrossDatabase.create(context)
        val offer = backups.findRestorable()
        assertNotNull(offer)
        assertEquals(2, offer!!.games)
    }

    @Test fun eachBackupIsAnnouncedOnlyOnce() = runBlocking {
        insert(1)
        val first = (backups.backupNow() as BackupResult.Done).info
        assertTrue(backups.announceOnce(first))
        assertFalse(backups.announceOnce(first))
        // A newer snapshot is worth mentioning again.
        assertTrue(backups.announceOnce(first.copy(modifiedAt = first.modifiedAt + 60_000)))
    }

    @Test fun nothingIsOfferedWhenTheLibraryAlreadyHasGames() = runBlocking {
        insert(1, 2)
        backups.backupNow()
        assertNull(backups.findRestorable())
        assertNotNull(backups.currentBackup())
    }

    @Test fun stagedRestoreReplacesTheLiveDatabaseOnTheNextStart() = runBlocking {
        insert(1, 2, 3)
        backups.backupNow()
        // The library changes after the backup…
        db.close()
        context.deleteDatabase(XrossDatabase.NAME)
        db = XrossDatabase.create(context)
        insert(9)
        // …and the user restores the backup.
        val info = backups.currentBackup()!!
        assertTrue(backups.stageRestore(info) is BackupResult.Done)
        assertTrue(DatabaseBackupManager.stagedFile(context).isFile)
        assertEquals(1, db.games().count()) // the live database is untouched until the restart

        db.close()
        db = XrossDatabase.create(context) // next process start
        assertEquals(listOf("Game 1", "Game 2", "Game 3"), db.games().getAll().map { it.title }.sorted())
        assertFalse(DatabaseBackupManager.stagedFile(context).exists())
        // The restored database is writable and back in WAL mode.
        insert(4)
        assertEquals(4, db.games().count())
    }

    @Test fun invalidFilesAreNeverOfferedOrRestored() = runBlocking {
        layout.databaseBackup.writeText("not a database at all, just text that is long enough to pass the size check ".repeat(3))
        assertNull(DatabaseBackupManager.inspect(layout.databaseBackup))
        assertNull(backups.findRestorable())
        assertNull(backups.currentBackup())
        // A corrupt staged file is discarded rather than replacing the library.
        insert(1)
        DatabaseBackupManager.stagedFile(context).writeText("garbage ".repeat(40))
        db.close()
        db = XrossDatabase.create(context)
        assertEquals(1, db.games().count())
        assertFalse(DatabaseBackupManager.stagedFile(context).exists())
    }
}
