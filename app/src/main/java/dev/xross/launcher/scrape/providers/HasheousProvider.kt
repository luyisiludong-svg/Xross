package dev.xross.launcher.scrape.providers

import dev.xross.launcher.data.dat.DatNames
import dev.xross.launcher.scrape.HttpClient
import dev.xross.launcher.scrape.ProviderId
import dev.xross.launcher.scrape.ProviderResult
import dev.xross.launcher.scrape.RateLimiter
import dev.xross.launcher.scrape.ScrapeProvider
import dev.xross.launcher.scrape.ScrapeRequest
import dev.xross.launcher.scrape.ScrapedGame
import kotlinx.coroutines.CancellationException
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/**
 * Hasheous (`POST https://hasheous.org/api/v1/Lookup/ByHash`, body `{"crc", "md5", "sha1"}`,
 * no key required): an exact hash → game identification built from No-Intro, Redump, TOSEC and
 * MAME DATs. Used first, so a game's exact name is known before name-based services are asked.
 *
 * Response fields are read defensively: the top-level `name` / `platform` / `publisher`, with
 * the matched DAT entry under `signature.game` (`name`, `year`, `publisher`) as fallback.
 */
class HasheousProvider(
    private val http: HttpClient,
    private val enabled: () -> Boolean,
    private val limiter: RateLimiter = RateLimiter(minIntervalMs = 500),
    private val baseUrl: String = "https://hasheous.org/api/v1",
) : ScrapeProvider {

    override val id = ProviderId.HASHEOUS

    override fun isConfigured(): Boolean = enabled()

    override suspend fun lookup(request: ScrapeRequest): ProviderResult {
        if (!enabled()) return ProviderResult.Unavailable("Hasheous is turned off")
        val body = JSONObject().apply {
            request.crc32?.let { put("crc", it) }
            request.md5?.let { put("md5", it) }
            request.sha1?.let { put("sha1", it) }
        }
        if (body.length() == 0) return ProviderResult.NotFound // nothing to look up without a fingerprint
        limiter.acquire()
        val response = try {
            http.post("$baseUrl/Lookup/ByHash?returnFields=All", body.toString().toByteArray(), "application/json")
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            return ProviderResult.Failed("Hasheous unreachable (${e.javaClass.simpleName})")
        }
        when (response.code) {
            in 200..299 -> Unit
            404, 400 -> return ProviderResult.NotFound
            429 -> {
                limiter.penalize(30_000)
                return ProviderResult.Failed("Hasheous is rate limiting requests")
            }
            else -> return ProviderResult.Failed("Hasheous answered HTTP ${response.code}")
        }
        return try {
            parse(JSONObject(response.text()))?.let { ProviderResult.Found(it) } ?: ProviderResult.NotFound
        } catch (_: JSONException) {
            ProviderResult.Failed("Hasheous sent an unreadable answer")
        }
    }

    companion object {
        internal fun parse(root: JSONObject): ScrapedGame? {
            val game = root.optJSONObject("signature")?.optJSONObject("game")
            val name = root.optString("name").ifBlank { null } ?: game?.optString("name")?.ifBlank { null } ?: return null
            val publisher = root.optJSONObject("publisher")?.optString("name")?.ifBlank { null }
                ?: game?.optString("publisher")?.ifBlank { null }
            val year = game?.optString("year")?.takeIf { it.length == 4 && it.all(Char::isDigit) }
            return ScrapedGame(
                provider = ProviderId.HASHEOUS,
                providerId = root.opt("id")?.toString() ?: "",
                title = DatNames.parse(name).title,
                publisher = publisher,
                releaseDate = year,
            )
        }
    }
}
