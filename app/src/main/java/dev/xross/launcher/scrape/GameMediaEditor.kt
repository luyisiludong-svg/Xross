package dev.xross.launcher.scrape

import dev.xross.launcher.data.GameEntity
import dev.xross.launcher.model.ArtKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** What a media picker shows for one slot of one game. */
sealed interface MediaOffers {
    data class Ready(val offers: List<MediaOffer>) : MediaOffers

    /** Nothing to pick from, and why ("No SteamGridDB key…"). */
    data class Empty(val reason: String) : MediaOffers
}

/**
 * The hand-editing side of a game's media, behind the Metadata & Media editor: what the services
 * offer for a slot, and choosing, replacing or removing the file in it.
 *
 * - **Offers cost quota**, so they are fetched only when a picker asks ([offers]) and kept in
 *   memory per game and kind until [clearSession]. Nothing here runs when the editor opens or
 *   pages to another game.
 * - **A chosen kind is pinned** (`game_metadata.pinnedArt`): scraping never replaces it, not even
 *   Scrape again. Removing a file pins the empty slot for the same reason. [resetMedia] unpins.
 * - Every change also refreshes `game_metadata.mediaStatus`, which lists read instead of the
 *   media folders, and `scrapedAt`, which game tiles watch to reload their art.
 * - Changes take [ScrapeOrchestrator.withGameLock], so a scrape in flight can't overwrite a pick.
 */
class GameMediaEditor(
    private val scraper: ScrapeOrchestrator,
    /** Where picker thumbnails are kept while the editor is open (app cache; emptied by [clearSession]). */
    private val thumbnailDir: () -> File,
) {
    private val games = scraper.db.games()
    private val metadata = scraper.db.metadata()
    private val cache = scraper.cache

    private val session = ConcurrentHashMap<Pair<Long, ArtKind>, List<MediaOffer>>()

    /** ScreenScraper allows most accounts one connection at a time; image CDNs take a few. */
    private val thumbnailLanes = ConcurrentHashMap<ProviderId, Semaphore>()

    /** Every offer for [gameId]'s [kind] across the configured services, or why there is none. */
    suspend fun offers(gameId: Long, kind: ArtKind): MediaOffers {
        session[gameId to kind]?.let { return MediaOffers.Ready(it) }
        val game = games.get(gameId) ?: return MediaOffers.Empty("This game is no longer in the library")
        val request = scraper.requestFor(game)
        val offers = ArrayList<MediaOffer>()
        val reasons = LinkedHashSet<String>()
        for (provider in scraper.providers) {
            val result = try {
                provider.mediaOffers(request, kind)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                OfferResult.Unavailable("${provider.id.label}: ${e.javaClass.simpleName}")
            }
            when (result) {
                is OfferResult.Offers -> offers += result.offers
                is OfferResult.Unavailable -> reasons += result.reason
            }
        }
        if (offers.isEmpty()) return MediaOffers.Empty(reasons.joinToString("\n").ifEmpty { NOTHING_OFFERED })
        // Only an answer with something in it is kept: a failure is asked again next time.
        val unique = offers.distinctBy { it.url }
        session[gameId to kind] = unique
        return MediaOffers.Ready(unique)
    }

    /** Downloads [offer] into its slot and pins the kind. Null when done, else what went wrong. */
    suspend fun pick(gameId: Long, offer: MediaOffer): String? = replace(gameId, offer.kind) { tmp ->
        val result = scraper.http.download(offer.url, tmp, offer.kind.maxBytes)
        if (result.code !in 200..299) throw IOException("the server answered HTTP ${result.code}")
    }

    /** Moves [source] (a copy of a file the user picked; consumed) into the slot and pins the kind. */
    suspend fun useFile(gameId: Long, kind: ArtKind, source: File): String? = try {
        replace(gameId, kind) { tmp -> withContext(Dispatchers.IO) { source.copyTo(tmp, overwrite = true) } }
    } finally {
        source.delete()
    }

    private suspend fun replace(gameId: Long, kind: ArtKind, fill: suspend (File) -> Unit): String? = scraper.withGameLock {
        val game = games.get(gameId) ?: return@withGameLock "This game is no longer in the library"
        cache.register(game)
        val tmp = cache.tempFile()
        try {
            fill(tmp)
            val ext = withContext(Dispatchers.IO) { AssetCache.sniff(tmp) }
                ?: throw IOException(if (kind.isVideo) "that isn't an MP4 video" else "that isn't a PNG, JPEG or WebP image")
            if ((ext == "mp4") != kind.isVideo) throw IOException(if (kind.isVideo) "that isn't an MP4 video" else "that is a video, not an image")
            cache.adopt(game.uuid, kind, ext, tmp)
            record(game) { it + kind }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (_: TooLargeException) {
            "That file is over the ${kind.maxBytes shr 20} MB limit for this slot"
        } catch (e: IOException) {
            "Couldn't use that file: ${e.message ?: e.javaClass.simpleName}"
        } catch (e: IllegalStateException) {
            "Couldn't use that file: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            tmp.delete()
        }
    }

    /** Deletes the file in [kind]'s slot and pins the empty slot, so scraping doesn't bring it back. */
    suspend fun remove(gameId: Long, kind: ArtKind) = scraper.withGameLock {
        val game = games.get(gameId) ?: return@withGameLock
        cache.register(game)
        cache.removeKind(game.uuid, kind)
        record(game) { it + kind }
    }

    /** Forgets every pin of [gameId]. The files stay until the next scrape replaces them. */
    suspend fun resetMedia(gameId: Long) = scraper.withGameLock {
        val game = games.get(gameId) ?: return@withGameLock
        record(game) { emptySet() }
    }

    /**
     * Re-reads which kinds have a file (media copied into the folders by hand shows up here) and
     * stores it when it changed. Returns the mask.
     */
    suspend fun refreshStatus(gameId: Long): Int {
        val game = games.get(gameId) ?: return 0
        cache.register(game)
        val status = withContext(Dispatchers.IO) { cache.mediaStatus(game.uuid) }
        metadata.edit(gameId) { it.copy(mediaStatus = status) }
        return status
    }

    /**
     * Fills `mediaStatus` for every game that has none yet: once for the whole library after the
     * v6 upgrade, then only for games added since. Returns how many rows were written.
     */
    suspend fun backfillStatus(): Int = withContext(Dispatchers.IO) {
        val pending = metadata.mediaStatusPending()
        for (game in pending) {
            cache.register(game)
            val status = cache.mediaStatus(game.uuid)
            metadata.edit(game.id) { it.copy(mediaStatus = status) }
        }
        pending.size
    }

    private suspend fun record(game: GameEntity, pins: (Set<ArtKind>) -> Set<ArtKind>) {
        val status = withContext(Dispatchers.IO) { cache.mediaStatus(game.uuid) }
        metadata.edit(game.id) {
            it.copy(pinnedArt = ArtKind.formatPins(pins(ArtKind.parsePins(it.pinnedArt))), mediaStatus = status, scrapedAt = scraper.clock())
        }
    }

    /**
     * The small preview of [offer] as a local file, downloaded once per session; null for videos,
     * while offline, or when the service sent something that isn't an image.
     */
    suspend fun thumbnail(offer: MediaOffer): File? {
        if (offer.kind.isVideo) return null
        val url = offer.thumbnailUrl ?: offer.url
        val file = File(thumbnailDir(), sha1(url))
        if (withContext(Dispatchers.IO) { file.isFile }) return file
        val lane = thumbnailLanes.getOrPut(offer.provider) { Semaphore(if (offer.provider == ProviderId.SCREENSCRAPER) 1 else 4) }
        return lane.withPermit {
            withContext(Dispatchers.IO) {
                if (file.isFile) return@withContext file
                file.parentFile?.mkdirs()
                val part = File(file.parentFile, file.name + ".part")
                try {
                    val result = scraper.http.download(url, part, THUMBNAIL_MAX_BYTES)
                    val image = result.code in 200..299 && AssetCache.sniff(part).let { it != null && it != "mp4" }
                    if (image && part.renameTo(file)) file else null
                } catch (e: CancellationException) {
                    throw e
                } catch (_: IOException) {
                    null
                } finally {
                    part.delete()
                }
            }
        }
    }

    /** The editor closed: forget the offers and delete the thumbnails. */
    suspend fun clearSession() {
        session.clear()
        withContext(Dispatchers.IO) { thumbnailDir().deleteRecursively() }
    }

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        const val NOTHING_OFFERED = "No service has media for this slot"

        /** A preview is small; a service that ignores the size hint still fits under the image cap. */
        private const val THUMBNAIL_MAX_BYTES = 8L shl 20
    }
}
