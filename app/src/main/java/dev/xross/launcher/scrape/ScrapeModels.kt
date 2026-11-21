package dev.xross.launcher.scrape

import dev.xross.launcher.model.ArtKind

/** Metadata services Xross can query. */
enum class ProviderId(val label: String) {
    HASHEOUS("Hasheous"),
    SCREENSCRAPER("ScreenScraper"),
    STEAMGRIDDB("SteamGridDB"),
}

/** Outcome of scraping one game, stored in `game_metadata.scrapeStatus`. */
enum class ScrapeStatus {
    /** At least one service identified the game. */
    OK,

    /** Every service answered and none knew the game. */
    NOT_FOUND,

    /** No service could answer (network, server, quota); retried on a later pass. */
    ERROR,
}

/** User preferences that shape lookups. */
data class ScrapePrefs(
    /** ScreenScraper region code: "us", "eu", "jp", "wor"… */
    val region: String = "us",
    /** Language code: "en", "fr", "de", "es", "it", "ja", "pt"… */
    val language: String = "en",
    val videos: Boolean = false,
)

/** Everything a provider may use to identify one game. Hashes are lower-case hex. */
data class ScrapeRequest(
    val gameId: Long,
    val uuid: String,
    /** Best title known so far (DAT, override, or file name). */
    val title: String,
    val fileName: String,
    val platformId: String,
    val sizeBytes: Long,
    /** Hashes of the file as stored (or of the archive member that holds the ROM). */
    val crc32: String? = null,
    val md5: String? = null,
    val sha1: String? = null,
    /**
     * Hashes as DAT-based services index them: the ROM with a copier header removed (No-Intro
     * style), or an arcade set's largest ROM chip. Null when the same as the file hashes.
     */
    val dataCrc32: String? = null,
    val dataMd5: String? = null,
    val dataSha1: String? = null,
    val serial: String? = null,
    /** Provider ids chosen earlier (manual match) or found by a previous scrape. */
    val screenScraperId: String? = null,
    val steamGridDbId: String? = null,
    val prefs: ScrapePrefs = ScrapePrefs(),
) {
    val hasHash: Boolean get() = crc32 != null || md5 != null || sha1 != null
}

/** One downloadable media item a provider offered. */
data class ScrapedMedia(val kind: ArtKind, val url: String, val ext: String?)

/**
 * One piece of media a provider has for a game, for the media picker. Unlike [ScrapedMedia]
 * (the one item a scrape takes per kind) every offer is kept.
 */
data class MediaOffer(
    val kind: ArtKind,
    /** The full-size file; downloaded only when picked. */
    val url: String,
    /** A small preview, when the provider has one. */
    val thumbnailUrl: String?,
    val provider: ProviderId,
    /** "3D box · EU", "Alternate · 600×900". */
    val label: String,
    val region: String? = null,
    val width: Int? = null,
    val height: Int? = null,
)

/** A provider's answer to [ScrapeProvider.mediaOffers]. */
sealed interface OfferResult {
    data class Offers(val offers: List<MediaOffer>) : OfferResult

    /** Nothing to offer, and why, in words for the picker ("No SteamGridDB key…"). */
    data class Unavailable(val reason: String) : OfferResult

    companion object {
        val NONE = Offers(emptyList())
        const val NO_MATCH = "No match yet. Use Find match…"
        const val SCREENSCRAPER_QUOTA = "ScreenScraper quota used up for today"
        const val NO_STEAMGRIDDB_KEY = "No SteamGridDB key. Add one in Settings ▸ Metadata to get heroes, logos and capsules"
    }
}

/** What one provider knows about a game. Null fields are unknown, not empty. */
data class ScrapedGame(
    val provider: ProviderId,
    val providerId: String,
    val title: String? = null,
    val synopsis: String? = null,
    /** ISO-8601 date or year, as given ("1995-03-11", "1995"). */
    val releaseDate: String? = null,
    val developer: String? = null,
    val publisher: String? = null,
    val genre: String? = null,
    val players: String? = null,
    /** 0–1. */
    val rating: Float? = null,
    val media: List<ScrapedMedia> = emptyList(),
)

sealed interface ProviderResult {
    data class Found(val game: ScrapedGame) : ProviderResult
    data object NotFound : ProviderResult

    /** Not set up (missing key or credentials) or the credentials were rejected. */
    data class Unavailable(val reason: String) : ProviderResult

    /** A transient failure; the game is retried on a later pass. */
    data class Failed(val reason: String) : ProviderResult

    /** The daily quota is used up: skip this provider for the rest of the run. */
    data class QuotaExceeded(val reason: String) : ProviderResult
}

/** A candidate shown in the manual match picker. */
data class SearchHit(
    val provider: ProviderId,
    val id: String,
    val title: String,
    val detail: String? = null,
)

interface ScrapeProvider {
    val id: ProviderId

    /** False when the provider can't run at all (no key); the orchestrator skips it. */
    fun isConfigured(): Boolean

    suspend fun lookup(request: ScrapeRequest): ProviderResult

    /** Name search for the manual match picker; empty when unsupported. */
    suspend fun search(query: String, platformId: String?): List<SearchHit> = emptyList()

    /**
     * Everything the provider has for [kind], for the media picker. Costs quota, so it is called
     * only when a picker opens. A provider without that kind answers [OfferResult.NONE] without
     * touching the network.
     */
    suspend fun mediaOffers(request: ScrapeRequest, kind: ArtKind): OfferResult = OfferResult.NONE
}
