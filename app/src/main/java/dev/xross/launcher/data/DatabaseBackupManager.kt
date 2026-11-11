package dev.xross.launcher.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.core.content.edit
import androidx.room.InvalidationTracker
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.xross.launcher.config.AtomicFiles
import dev.xross.launcher.storage.XrossLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** A library database snapshot found in `Xross/data/`. */
data class BackupInfo(
    val file: File,
    val games: Int,
    val schemaVersion: Int,
    val modifiedAt: Long,
    val sizeBytes: Long,
)

sealed interface BackupResult {
    data class Done(val info: BackupInfo) : BackupResult

    /** Nothing to do: [reason] says why (no changes, no shared storage yet). */
    data class Skipped(val reason: String) : BackupResult
    data class Failed(val reason: String) : BackupResult
}

/** What Settings ▸ System shows about backups. */
data class BackupStatus(
    /** Time of the last successful backup made by this install, if any. */
    val lastBackupAt: Long? = null,
    /** Result of the last attempt, for the settings row. */
    val message: String? = null,
    val running: Boolean = false,
)

/**
 * Keeps a consistent copy of the live library database in `Xross/data/xross.db`.
 *
 * The live database never leaves app-private storage: SQLite in WAL mode on a FUSE/FAT volume is
 * unsafe, and other apps could touch it there. Instead a snapshot is copied out when the launcher
 * goes to the background (if anything changed) or when asked from Settings.
 *
 * Snapshot procedure (works on Android 9; `VACUUM INTO` needs SQLite 3.27 / API 30):
 *  1. `PRAGMA wal_checkpoint(TRUNCATE)` folds the write-ahead log into the main file.
 *  2. `BEGIN IMMEDIATE` blocks writers while the file is copied, so the copy can't tear; if the
 *     log isn't empty by then (a write slipped in between), the steps repeat.
 *  3. The main file is stream-copied to `xross.db.tmp`, its header switched from WAL to rollback
 *     journal mode (so the copy is one self-contained file, readable without `-wal`/`-shm`
 *     companions), verified, and renamed over `xross.db`.
 *
 * Restoring copies a snapshot to `<databases>/xross.db.restore`; it replaces the live database the
 * next time the process starts, before Room opens it ([applyStagedRestore]).
 */
class DatabaseBackupManager(
    context: Context,
    private val database: () -> XrossDatabase,
    private val layout: () -> XrossLayout,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutex = Mutex()

    /** The database changed since the last backup (true at start: an earlier run may not have backed up). */
    @Volatile private var dirty = true

    /** True while there are library changes the backup doesn't have yet. */
    val hasUnsavedChanges: Boolean get() = dirty
    private var watching = false

    private val _status = MutableStateFlow(BackupStatus(lastBackupAt = prefs.getLong(KEY_LAST, 0L).takeIf { it > 0 }))
    val status: StateFlow<BackupStatus> = _status.asStateFlow()

    private val liveFile: File get() = appContext.getDatabasePath(XrossDatabase.NAME)

    /** Marks the backup stale whenever a library table changes. */
    fun watch() {
        if (watching) return
        watching = true
        database().invalidationTracker.addObserver(object : InvalidationTracker.Observer(TRACKED_TABLES) {
            override fun onInvalidated(tables: Set<String>) {
                dirty = true
            }
        })
    }

    /** Backs up when something changed since the last backup, or no backup exists yet. */
    suspend fun backupIfChanged(): BackupResult {
        val target = layout().databaseBackup
        if (!dirty && target.isFile) return BackupResult.Skipped("No changes since the last backup")
        return backupNow()
    }

    suspend fun backupNow(): BackupResult = mutex.withLock {
        val at = layout()
        if (!at.portable) {
            return@withLock BackupResult.Skipped("Needs storage access").also { report(it) }
        }
        _status.value = _status.value.copy(running = true)
        val result = withContext(Dispatchers.IO) {
            try {
                dirty = false
                snapshot(database().openHelper.writableDatabase, at.databaseBackup)
                val info = inspect(at.databaseBackup) ?: throw IOException("The copy couldn't be read back")
                prefs.edit { putLong(KEY_LAST, info.modifiedAt) }
                BackupResult.Done(info)
            } catch (e: Exception) {
                dirty = true
                BackupResult.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
        report(result)
        result
    }

    /**
     * A snapshot worth offering on a clean install: one exists in the Xross folder, holds games,
     * can be opened by this version, while the live library is empty.
     */
    suspend fun findRestorable(): BackupInfo? = withContext(Dispatchers.IO) {
        val at = layout()
        if (!at.portable) return@withContext null
        val info = inspect(at.databaseBackup) ?: return@withContext null
        if (info.games == 0 || info.schemaVersion > XrossDatabase.VERSION) return@withContext null
        val liveGames = runCatching { database().games().count() }.getOrDefault(0)
        info.takeIf { liveGames == 0 }
    }

    /** True the first time a given snapshot is offered, so the restore hint isn't repeated every start. */
    fun announceOnce(info: BackupInfo): Boolean {
        val key = "${info.file.path}@${info.modifiedAt}"
        if (prefs.getString(KEY_ANNOUNCED, null) == key) return false
        prefs.edit { putString(KEY_ANNOUNCED, key) }
        return true
    }

    /** The snapshot in the current Xross folder, if one exists and is readable. */
    suspend fun currentBackup(): BackupInfo? = withContext(Dispatchers.IO) {
        layout().takeIf { it.portable }?.let { inspect(it.databaseBackup) }
    }

    /**
     * Stages [info] to replace the live database on the next start. The caller restarts the
     * process right after (the live database stays open and untouched until then).
     */
    suspend fun stageRestore(info: BackupInfo): BackupResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val checked = inspect(info.file) ?: throw IOException("The backup can't be read")
                if (checked.schemaVersion > XrossDatabase.VERSION) throw IOException("The backup is from a newer Xross")
                val staged = stagedFile(appContext)
                AtomicFiles.copy(info.file, staged)
                if (inspect(staged) == null) {
                    staged.delete()
                    throw IOException("The copy couldn't be read back")
                }
                BackupResult.Done(checked)
            } catch (e: Exception) {
                BackupResult.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun report(result: BackupResult) {
        _status.value = when (result) {
            is BackupResult.Done -> BackupStatus(lastBackupAt = result.info.modifiedAt, message = null)
            is BackupResult.Skipped -> _status.value.copy(message = result.reason, running = false)
            is BackupResult.Failed -> _status.value.copy(message = "Backup failed: ${result.reason}", running = false)
        }
    }

    private suspend fun snapshot(db: SupportSQLiteDatabase, target: File) {
        val wal = File(liveFile.path + "-wal")
        repeat(MAX_ATTEMPTS) { attempt ->
            checkpoint(db)
            db.beginTransactionNonExclusive() // BEGIN IMMEDIATE: writers wait until the copy is done
            try {
                if (!wal.exists() || wal.length() == 0L) {
                    AtomicFiles.copy(liveFile, target, beforeCommit = { tmp ->
                        useRollbackJournal(tmp)
                        if (inspect(tmp) == null) throw IOException("The copy is not a valid database")
                    })
                    target.setLastModified(clock())
                    return
                }
            } finally {
                db.endTransaction() // nothing was written; ends without committing
            }
            delay(RETRY_DELAY_MS * (attempt + 1))
        }
        throw IOException("The library was busy; try again")
    }

    /** Folds the WAL into the main file; retries while readers hold it. */
    private suspend fun checkpoint(db: SupportSQLiteDatabase) {
        repeat(MAX_ATTEMPTS) { attempt ->
            val busy = db.query(SimpleSQLiteQuery("PRAGMA wal_checkpoint(TRUNCATE)")).use { c ->
                if (c.moveToFirst()) c.getInt(0) else 0
            }
            if (busy == 0) return
            delay(RETRY_DELAY_MS * (attempt + 1))
        }
    }

    companion object {
        private const val PREFS = "xross_backup"
        private const val KEY_LAST = "last_backup_at"
        private const val KEY_ANNOUNCED = "restore_announced"
        private const val MAX_ATTEMPTS = 5
        private const val RETRY_DELAY_MS = 40L
        const val RESTORE_SUFFIX = ".restore"

        val TRACKED_TABLES = arrayOf("games", "game_hashes", "dat_files", "dat_roms", "game_metadata")

        private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

        fun stagedFile(context: Context): File =
            File(context.getDatabasePath(XrossDatabase.NAME).path + RESTORE_SUFFIX)

        /**
         * Replaces the live database with a staged restore, if there is one. Must run before Room
         * opens the database. Returns true when a restore was applied.
         */
        fun applyStagedRestore(context: Context): Boolean {
            val staged = stagedFile(context)
            if (!staged.isFile) return false
            val live = context.getDatabasePath(XrossDatabase.NAME)
            if (inspect(staged) == null) {
                staged.delete()
                return false
            }
            for (suffix in listOf("-wal", "-shm", "-journal")) File(live.path + suffix).delete()
            AtomicFiles.copy(staged, live)
            staged.delete()
            return true
        }

        /** Header bytes 18–19 = 1: legacy rollback journal instead of WAL (2). */
        internal fun useRollbackJournal(file: File) {
            RandomAccessFile(file, "rw").use { raf ->
                if (raf.length() < 100) throw IOException("Not a database")
                raf.seek(18)
                raf.write(byteArrayOf(1, 1))
                raf.fd.sync()
            }
        }

        /** Reads a snapshot's schema version and game count, read-only; null if it isn't a usable database. */
        fun inspect(file: File): BackupInfo? {
            if (!file.isFile || file.length() < 100) return null
            val header = ByteArray(SQLITE_MAGIC.size)
            val headerRead = runCatching { file.inputStream().use { it.read(header) } }.getOrDefault(-1)
            if (headerRead != header.size || !header.contentEquals(SQLITE_MAGIC)) return null
            return try {
                SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
                    val version = db.version
                    val games = db.rawQuery("SELECT COUNT(*) FROM games", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
                    BackupInfo(file, games, version, file.lastModified(), file.length())
                }
            } catch (_: SQLiteException) {
                null
            }
        }
    }
}
