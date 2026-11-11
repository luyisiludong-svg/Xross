package dev.xross.launcher.scrape

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
    val crc32: String? = null,
    val md5: String? = null,
    val sha1: String? = null,
    val serial: String? = null,
    /** Provider ids chosen earlier (manual match) or found by a previous scrape. */
    val screenScraperId: String? = null,
    val steamGridDbId: String? = null,
    val prefs: ScrapePrefs = ScrapePrefs(),
)

/** One downloadable media item a provider offered. */
data class ScrapedMedia(val kind: ArtKind, val url: String, val ext: String?)

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
}
