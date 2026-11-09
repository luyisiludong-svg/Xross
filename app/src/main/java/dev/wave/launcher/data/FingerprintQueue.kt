package dev.wave.launcher.data

import android.os.Process
import android.util.Log
import dev.wave.launcher.BuildConfig
import dev.wave.launcher.storage.Fingerprinter
import dev.wave.launcher.storage.FingerprintPolicy
import dev.wave.launcher.storage.PlatformRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.Executors

/**
 * Background fingerprinting. Runs on one low-priority thread and only while the launcher is in
 * front ([start] on resume, [stop] on pause), so it never competes with a running emulator for
 * CPU or storage bandwidth. Progress is persisted per game, so stopping loses at most one file.
 */
class FingerprintQueue(
    private val games: GameDao,
    private val hashes: GameHashDao,
    private val policy: () -> FingerprintPolicy,
) {
    private val dispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            runnable.run()
        }, "wave-fingerprint")
    }.asCoroutineDispatcher()

    private var job: Job? = null

    /** Title of the file being fingerprinted, or null when idle. */
    private val _current = MutableStateFlow<String?>(null)
    val current: StateFlow<String?> = _current.asStateFlow()

    val stats = hashes.observeStats()

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch(dispatcher) {
            val fingerprinter = Fingerprinter(policy())
            val includeLarge = policy().hashLargeImages
            val started = System.nanoTime()
            var done = 0
            var afterId = 0L
            try {
                while (isActive) {
                    val batch = hashes.pending(afterId, includeLarge, BATCH)
                    if (batch.isEmpty()) break
                    for (game in batch) {
                        afterId = game.id
                        _current.value = game.title
                        val fp = try {
                            fingerprinter.fingerprint(File(game.path), PlatformRegistry.byId(game.platformId))
                        } catch (_: FileNotFoundException) {
                            continue // volume not mounted right now; retried next session
                        }
                        // The game may have been rescanned or removed meanwhile; only store if it still matches.
                        val latest = games.get(game.id) ?: continue
                        if (latest.sizeBytes == game.sizeBytes && latest.lastModified == game.lastModified) {
                            try {
                                hashes.upsert(GameHashEntity.from(game, fp))
                                done++
                            } catch (_: android.database.sqlite.SQLiteConstraintException) {
                                // Deleted by a rescan between the check and the write.
                            }
                        }
                    }
                }
            } finally {
                _current.value = null
                if (BuildConfig.PERF_LOG && done > 0) {
                    Log.i("WaveScan", "fingerprinted $done files in ${(System.nanoTime() - started) / 1_000_000} ms")
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Restart from the beginning, e.g. after a rescan or a policy change. */
    fun restart(scope: CoroutineScope) {
        stop()
        start(scope)
    }

    private companion object {
        const val BATCH = 64
    }
}
