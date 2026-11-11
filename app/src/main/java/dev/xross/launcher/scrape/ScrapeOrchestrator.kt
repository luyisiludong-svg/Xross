package dev.xross.launcher.scrape

import android.util.Log
import dev.xross.launcher.BuildConfig
import dev.xross.launcher.data.GameEntity
import dev.xross.launcher.data.GameHashEntity
import dev.xross.launcher.data.GameMetadataEntity
import dev.xross.launcher.data.XrossDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/** Live state of a scrape pass, for Settings and the busy indicator. */
data class ScrapeProgress(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    /** Title being scraped now. */
    val current: String? = null,
    /** Last noteworthy problem ("ScreenScraper's daily quota is used up"). */
    val message: String? = null,
)

/**
 * Throttled background scraping.
 *
 * For each game still to scrape: Hasheous identifies it by hash, ScreenScraper fills the text
 * and most art (by hash, or by id after a manual match), SteamGridDB adds hero banners, capsules
 * and logos (by the best title known). Each service has its own [RateLimiter]; a service that
 * reports its daily quota spent is skipped for the rest of the pass.
 *
 * Like fingerprinting, it only runs while Xross is in front ([start] on resume, [stop] on pause)
 * and on one I/O lane, so it never competes with a running emulator. Results land in
 * `game_metadata`; art lands in [AssetCache] keyed by the game's UUID.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScrapeOrchestrator(
    private val db: XrossDatabase,
    private val cache: AssetCache,
    private val http: HttpClient,
    /** In query order: identification first, art last. */
    private val providers: List<ScrapeProvider>,
    private val prefs: () -> ScrapePrefs,
    /** False on a metered network when the user asked for Wi-Fi only. */
    private val networkAllowed: () -> Boolean = { true },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lane = Dispatchers.IO.limitedParallelism(1)
    private val games = db.games()
    private val hashes = db.hashes()
    private val metadata = db.metadata()
    private val gameLock = Mutex()

    private val _progress = MutableStateFlow(ScrapeProgress())
    val progress: StateFlow<ScrapeProgress> = _progress.asStateFlow()

    private var job: Job? = null

    val isConfigured: Boolean get() = providers.any { it.isConfigured() }

    /** Scrapes every game that still needs it. No-op while a pass runs. */
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch(lane) { runPass() }
    }

    fun stop() {
        job?.cancel()
        job = null
        _progress.update { it.copy(running = false, current = null) }
    }

    /** Re-scrapes one game now (manual rescrape or after a manual match), replacing its art. */
    suspend fun scrapeOne(gameId: Long): ScrapeStatus? = withContext(lane) {
        val game = games.get(gameId) ?: return@withContext null
        scrapeGame(game, exhausted = HashSet(), replaceArt = true).also { games.applyTitles() }
    }

    /** Name search across services, for the manual match picker. */
    suspend fun search(query: String, platformId: String?): List<SearchHit> = withContext(Dispatchers.IO) {
        providers.filter { it.isConfigured() }.flatMap { provider ->
            try {
                provider.search(query, platformId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    private suspend fun runPass() {
        val retryBefore = clock() - RETRY_AFTER_MS
        val total = metadata.pendingScrapeCount(retryBefore)
        _progress.value = ScrapeProgress(running = true, total = total)
        val exhausted = HashSet<ProviderId>()
        var done = 0
        var afterId = 0L
        try {
            loop@ while (currentCoroutineIsActive()) {
                val batch = metadata.pendingScrape(afterId, retryBefore, BATCH)
                if (batch.isEmpty()) break
                for (game in batch) {
                    afterId = game.id
                    if (!networkAllowed()) {
                        _progress.update { it.copy(message = "Waiting for Wi-Fi") }
                        break@loop
                    }
                    if (providers.none { it.isConfigured() && it.id !in exhausted }) {
                        _progress.update { it.copy(message = it.message ?: "No metadata service is set up") }
                        break@loop
                    }
                    _progress.update { it.copy(current = game.title) }
                    scrapeGame(game, exhausted, replaceArt = false)
                    done++
                    _progress.update { it.copy(done = done) }
                    if (done % TITLE_FLUSH == 0) games.applyTitles()
                }
            }
            if (afterId > 0 || done > 0) cache.prune(metadata.allUuids().toHashSet())
        } finally {
            if (done > 0) withContext(kotlinx.coroutines.NonCancellable) { games.applyTitles() }
            _progress.update { it.copy(running = false, current = null) }
        }
    }

    private suspend fun currentCoroutineIsActive() = kotlin.coroutines.coroutineContext.isActive

    /**
     * One game, all services. Returns the stored status, or null when nothing could be tried
     * (no fingerprint yet: scraped on a later pass, once hashing has caught up).
     */
    internal suspend fun scrapeGame(game: GameEntity, exhausted: MutableSet<ProviderId>, replaceArt: Boolean): ScrapeStatus? =
        gameLock.withLock {
            val hash = hashes.get(game.id)?.takeIf { it.fileSize == game.sizeBytes && it.fileModified == game.lastModified }
            val meta = metadata.get(game.id)
            // Scrape once fingerprinting has had its turn: by hash, by a chosen id, or (arcade sets,
            // files too large to hash) by whole-file hashes and names.
            val identifiable = hash.hasContentHash() || meta?.screenScraperId != null || game.platformId == "arcade" ||
                hash?.source == dev.xross.launcher.storage.HashSource.SKIPPED.name
            if (!identifiable) return@withLock null

            var request = buildRequest(game, hash, meta)
            val found = ArrayList<ScrapedGame>()
            val problems = ArrayList<String>()
            var answered = false
            var transient = false
            for (provider in providers) {
                if (!provider.isConfigured() || provider.id in exhausted) continue
                val result = try {
                    provider.lookup(request)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ProviderResult.Failed("${provider.id.label}: ${e.javaClass.simpleName}")
                }
                when (result) {
                    is ProviderResult.Found -> {
                        answered = true
                        found += result.game
                        // Later services search by name: give them the exact one.
                        val better = meta?.titleOverride ?: meta?.datTitle ?: result.game.title
                        if (better != null) request = request.copy(title = better)
                        if (result.game.provider == ProviderId.SCREENSCRAPER) request = request.copy(screenScraperId = result.game.providerId)
                    }
                    ProviderResult.NotFound -> answered = true
                    is ProviderResult.Unavailable -> problems += result.reason
                    is ProviderResult.Failed -> {
                        transient = true
                        problems += result.reason
                    }
                    is ProviderResult.QuotaExceeded -> {
                        transient = true
                        exhausted += provider.id
                        problems += result.reason
                        _progress.update { it.copy(message = result.reason) }
                    }
                }
            }

            val status = when {
                found.isNotEmpty() -> ScrapeStatus.OK
                // A service that couldn't answer might know the game: try again later.
                transient -> ScrapeStatus.ERROR
                answered -> ScrapeStatus.NOT_FOUND
                // Every service is unconfigured or rejected its credentials: record nothing.
                else -> return@withLock null
            }
            val artProblems = downloadArt(game.uuid, found, replaceArt)
            metadata.edit(game.id) { merge(it, found, status, (problems + artProblems).firstOrNull(), clock()) }
            if (BuildConfig.PERF_LOG) Log.i("XrossScrape", "${game.id}: $status (${found.joinToString { it.provider.label }})")
            status
        }

    private suspend fun buildRequest(game: GameEntity, hash: GameHashEntity?, meta: GameMetadataEntity?): ScrapeRequest {
        // Arcade sets are fingerprinted by name only; services that need a hash get the zip's own.
        val whole = if (game.platformId == "arcade" && hash.hasContentHash().not()) {
            dev.xross.launcher.storage.FileHash.of(java.io.File(game.path), ARCADE_HASH_LIMIT)
        } else {
            null
        }
        return ScrapeRequest(
            gameId = game.id,
            uuid = game.uuid,
            title = meta?.titleOverride ?: meta?.datTitle ?: game.title,
            fileName = game.fileName,
            platformId = game.platformId,
            sizeBytes = hash?.contentSize ?: game.sizeBytes,
            // As stored (ScreenScraper wants the local file's hashes)…
            crc32 = whole?.crc32 ?: hash?.crc32,
            md5 = whole?.md5 ?: hash?.md5,
            sha1 = whole?.sha1 ?: hash?.sha1,
            // …and header-less, which DAT-based services index.
            dataCrc32 = hash?.dataCrc32,
            dataMd5 = hash?.dataMd5,
            dataSha1 = hash?.dataSha1,
            serial = meta?.serial,
            screenScraperId = meta?.screenScraperId,
            steamGridDbId = meta?.steamGridDbId,
            prefs = prefs(),
        )
    }

    /** First offered URL per art kind, ScreenScraper before SteamGridDB; returns problems. */
    private suspend fun downloadArt(uuid: String, found: List<ScrapedGame>, replace: Boolean): List<String> {
        val problems = ArrayList<String>()
        val offers = found.flatMap { it.media }.groupBy { it.kind }
        for ((kind, candidates) in offers) {
            if (!replace && cache.has(uuid, kind)) continue
            for (media in candidates) {
                val tmp = cache.tempFile()
                try {
                    val result = http.download(media.url, tmp, kind.maxBytes)
                    if (result.code !in 200..299) throw IOException("HTTP ${result.code}")
                    val ext = AssetCache.sniff(tmp) ?: throw IOException("not an image or video")
                    if ((ext == "mp4") != kind.isVideo) throw IOException("unexpected .$ext")
                    cache.adopt(uuid, kind, ext, tmp)
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    problems += "${kind.name.lowercase()} download failed (${e.message ?: e.javaClass.simpleName})"
                } finally {
                    tmp.delete()
                }
            }
        }
        return problems
    }

    private fun GameHashEntity?.hasContentHash() =
        this != null && (crc32 != null || md5 != null || sha1 != null || dataSha1 != null)

    companion object {
        private const val BATCH = 32
        private const val TITLE_FLUSH = 10
        /** Whole-zip hashing for arcade sets stays cheap below this (sets average a few MB). */
        private const val ARCADE_HASH_LIMIT = 256L shl 20
        /** A failed game is tried again after a day (quota resets, servers come back). */
        const val RETRY_AFTER_MS = 24L * 60 * 60 * 1000

        /**
         * Folds provider answers into the stored row. Field priority: ScreenScraper, then
         * Hasheous (title, publisher, year only). Earlier values survive when no service has a
         * better one, so a later partial scrape never erases data.
         */
        internal fun merge(
            row: GameMetadataEntity,
            found: List<ScrapedGame>,
            status: ScrapeStatus,
            detail: String?,
            now: Long,
        ): GameMetadataEntity {
            val ss = found.firstOrNull { it.provider == ProviderId.SCREENSCRAPER }
            val hh = found.firstOrNull { it.provider == ProviderId.HASHEOUS }
            val sg = found.firstOrNull { it.provider == ProviderId.STEAMGRIDDB }
            return row.copy(
                scrapedTitle = ss?.title ?: hh?.title ?: row.scrapedTitle,
                synopsis = ss?.synopsis ?: row.synopsis,
                releaseDate = ss?.releaseDate ?: hh?.releaseDate ?: row.releaseDate,
                developer = ss?.developer ?: row.developer,
                publisher = ss?.publisher ?: hh?.publisher ?: row.publisher,
                genre = ss?.genre ?: row.genre,
                players = ss?.players ?: row.players,
                rating = ss?.rating ?: row.rating,
                screenScraperId = ss?.providerId?.ifEmpty { null } ?: row.screenScraperId,
                steamGridDbId = sg?.providerId?.ifEmpty { null } ?: row.steamGridDbId,
                hasheousId = hh?.providerId?.ifEmpty { null } ?: row.hasheousId,
                scrapeStatus = status.name,
                scrapeDetail = detail,
                scrapedAt = now,
            )
        }
    }
}
