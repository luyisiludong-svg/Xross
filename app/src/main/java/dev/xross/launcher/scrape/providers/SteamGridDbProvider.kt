package dev.xross.launcher.scrape.providers

import dev.xross.launcher.scrape.ArtKind
import dev.xross.launcher.scrape.AssetCache
import dev.xross.launcher.scrape.HttpClient
import dev.xross.launcher.scrape.HttpResponse
import dev.xross.launcher.scrape.ProviderId
import dev.xross.launcher.scrape.ProviderResult
import dev.xross.launcher.scrape.RateLimiter
import dev.xross.launcher.scrape.ScrapeProvider
import dev.xross.launcher.scrape.ScrapeRequest
import dev.xross.launcher.scrape.ScrapedGame
import dev.xross.launcher.scrape.ScrapedMedia
import dev.xross.launcher.scrape.SearchHit
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale

/**
 * SteamGridDB API v2 (contract from the official client): `Authorization: Bearer <key>`,
 * `GET /search/autocomplete/{term}`, then `GET /{grids|heroes|logos}/game/{id}`. Responses are
 * `{"success": true, "data": …}`. Supplies art only (hero banners, 600×900 capsules, logos);
 * titles and descriptions come from the other services.
 */
class SteamGridDbProvider(
    private val http: HttpClient,
    private val apiKey: () -> String,
    private val limiter: RateLimiter = RateLimiter(minIntervalMs = 250),
    private val baseUrl: String = "https://www.steamgriddb.com/api/v2",
) : ScrapeProvider {

    override val id = ProviderId.STEAMGRIDDB

    override fun isConfigured(): Boolean = apiKey().isNotBlank()

    override suspend fun lookup(request: ScrapeRequest): ProviderResult {
        if (!isConfigured()) return ProviderResult.Unavailable("No SteamGridDB API key")
        return try {
            val gameId = request.steamGridDbId ?: run {
                val hits = searchRaw(request.title)
                    ?: return ProviderResult.Unavailable("SteamGridDB rejected the API key")
                bestHit(request.title, hits)?.first ?: return ProviderResult.NotFound
            }
            val media = buildList {
                image("grids", gameId, "dimensions=600x900")?.let { add(ScrapedMedia(ArtKind.GRID, it, AssetCache.extensionFor(it, null))) }
                image("heroes", gameId, null)?.let { add(ScrapedMedia(ArtKind.HERO, it, AssetCache.extensionFor(it, null))) }
                image("logos", gameId, null)?.let { add(ScrapedMedia(ArtKind.WHEEL, it, AssetCache.extensionFor(it, null))) }
            }
            if (media.isEmpty()) ProviderResult.NotFound
            else ProviderResult.Found(ScrapedGame(ProviderId.STEAMGRIDDB, gameId, media = media))
        } catch (e: CancellationException) {
            throw e
        } catch (e: RateLimited) {
            ProviderResult.Failed("SteamGridDB is rate limiting requests")
        } catch (e: IOException) {
            ProviderResult.Failed("SteamGridDB unreachable (${e.javaClass.simpleName})")
        } catch (_: JSONException) {
            ProviderResult.Failed("SteamGridDB sent an unreadable answer")
        }
    }

    override suspend fun search(query: String, platformId: String?): List<SearchHit> {
        if (!isConfigured() || query.isBlank()) return emptyList()
        val hits = runCatching { searchRaw(query) }.getOrNull().orEmpty()
        return hits.map { (id, name, year) -> SearchHit(ProviderId.STEAMGRIDDB, id, name, year) }
    }

    private class RateLimited : IOException()

    /** (id, name, year) triples; null when the key was rejected. */
    private suspend fun searchRaw(term: String): List<Triple<String, String, String?>>? {
        val response = get("/search/autocomplete/" + URLEncoder.encode(term, "UTF-8").replace("+", "%20"))
        if (response.code == 401 || response.code == 403) return null
        val data = dataArray(response) ?: return emptyList()
        return (0 until data.length()).mapNotNull { i ->
            val g = data.optJSONObject(i) ?: return@mapNotNull null
            val id = g.opt("id")?.toString() ?: return@mapNotNull null
            val year = g.optLong("release_date", 0L).takeIf { it > 0 }?.let {
                java.time.Instant.ofEpochSecond(it).atZone(java.time.ZoneOffset.UTC).year.toString()
            }
            Triple(id, g.optString("name"), year)
        }
    }

    private suspend fun image(kind: String, gameId: String, query: String?): String? {
        val response = get("/$kind/game/$gameId" + (query?.let { "?$it" } ?: ""))
        val data = dataArray(response) ?: return null
        return (0 until data.length()).asSequence()
            .mapNotNull { data.optJSONObject(it)?.optString("url")?.takeIf { url -> url.startsWith("https://") } }
            .firstOrNull()
    }

    private suspend fun get(path: String): HttpResponse {
        limiter.acquire()
        val response = http.get(baseUrl + path, headers = mapOf("Authorization" to "Bearer ${apiKey()}"))
        if (response.code == 429) {
            limiter.penalize(10_000)
            throw RateLimited()
        }
        return response
    }

    private fun dataArray(response: HttpResponse): JSONArray? {
        if (!response.isSuccess) return null
        val root = JSONObject(response.text())
        if (!root.optBoolean("success", false)) return null
        return root.optJSONArray("data")
    }

    companion object {
        /**
         * Picks the search result whose normalized name equals the title, else the first result
         * whose name starts with it, else the first result (the API ranks by relevance).
         */
        internal fun bestHit(title: String, hits: List<Triple<String, String, String?>>): Triple<String, String, String?>? {
            val want = normalize(title)
            return hits.firstOrNull { normalize(it.second) == want }
                ?: hits.firstOrNull { normalize(it.second).startsWith(want) }
                ?: hits.firstOrNull()
        }

        internal fun normalize(s: String) = s.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
    }
}
