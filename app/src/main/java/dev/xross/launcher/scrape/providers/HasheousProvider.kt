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
    private val limiter: RateLimiter = RateLimiter(baseIntervalMs = 500),
    private val baseUrl: String = "https://hasheous.org/api/v1",
) : ScrapeProvider {

    override val id = ProviderId.HASHEOUS

    override fun isConfigured(): Boolean = enabled()

    override suspend fun lookup(request: ScrapeRequest): ProviderResult {
        if (!enabled()) return ProviderResult.Unavailable("Hasheous is turned off")
        val body = JSONObject().apply {
            // Hasheous indexes DAT entries, which list ROMs without copier headers.
            (request.dataCrc32 ?: request.crc32)?.let { put("crc", it) }
            (request.dataMd5 ?: request.md5)?.let { put("md5", it) }
            (request.dataSha1 ?: request.sha1)?.let { put("sha1", it) }
        }
        if (body.length() == 0) return ProviderResult.NotFound // nothing to look up without a fingerprint
        // Arcade chips are often shared by a game, its sequels and regional sets: ask for every
        // candidate so the set name can pick the right one.
        val setName = if (request.platformId == "arcade") request.fileName.substringBeforeLast('.').lowercase() else null
        val query = "returnFields=All" + if (setName != null) "&returnAllSources=true" else ""
        limiter.acquire()
        val response = try {
            http.post("$baseUrl/Lookup/ByHash?$query", body.toString().toByteArray(), "application/json")
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
            parse(JSONObject(response.text()), setName)?.let { ProviderResult.Found(it) } ?: ProviderResult.NotFound
        } catch (_: JSONException) {
            ProviderResult.Failed("Hasheous sent an unreadable answer")
        }
    }

    companion object {
        /**
         * [setName]: an arcade set ("aburner2"). Hasheous's MAME and FBNeo entries keep the set
         * name in `description` (title in `name`); when one matches, it wins over the top-level
         * answer, which is simply the first game that contains the chip.
         */
        internal fun parse(root: JSONObject, setName: String? = null): ScrapedGame? {
            setName?.let { set -> forSet(root, set)?.let { return it } }
            val game = root.optJSONObject("signature")?.optJSONObject("game")
            val name = root.optString("name").ifBlank { null } ?: game?.optString("name")?.ifBlank { null } ?: return null
            // With returnAllSources the single `signature` is absent: read the candidates instead,
            // preferring ones titled like the answer ("1943: The Battle of Midway (US, Rev C)").
            val candidates = candidateGames(root).sortedByDescending { it.optString("name").startsWith(name, ignoreCase = true) }
            val publisher = root.optJSONObject("publisher")?.optString("name")?.ifBlank { null }
                ?: game?.optString("publisher")?.ifBlank { null }
                ?: candidates.firstNotNullOfOrNull { it.optString("publisher").ifBlank { null } }
            val year = (listOfNotNull(game) + candidates).firstNotNullOfOrNull { yearOf(it) }
            return ScrapedGame(
                provider = ProviderId.HASHEOUS,
                providerId = root.opt("id")?.toString() ?: "",
                title = DatNames.parse(name).title,
                publisher = publisher,
                releaseDate = year,
            )
        }

        private fun yearOf(game: JSONObject): String? = game.optString("year").takeIf { it.length == 4 && it.all(Char::isDigit) }

        /** Every `signatures.<source>[].game`, in answer order. */
        private fun candidateGames(root: JSONObject): List<JSONObject> {
            val lists = root.optJSONObject("signatures") ?: return emptyList()
            return lists.keys().asSequence().flatMap { source ->
                val items = lists.optJSONArray(source) ?: return@flatMap emptySequence()
                (0 until items.length()).asSequence().mapNotNull { items.optJSONObject(it)?.optJSONObject("game") }
            }.toList()
        }

        private fun forSet(root: JSONObject, set: String): ScrapedGame? {
            val lists = root.optJSONObject("signatures") ?: return null
            for (source in lists.keys()) {
                val items = lists.optJSONArray(source) ?: continue
                for (i in 0 until items.length()) {
                    val game = items.optJSONObject(i)?.optJSONObject("game") ?: continue
                    val ids = listOf(game.optString("description"), game.optString("name")).map { it.trim().lowercase() }
                    if (set !in ids) continue
                    val title = game.optString("name").ifBlank { null }?.takeIf { it.lowercase() != set } ?: continue
                    return ScrapedGame(
                        provider = ProviderId.HASHEOUS,
                        providerId = root.opt("id")?.toString() ?: "",
                        title = DatNames.parse(title).title,
                        publisher = game.optString("publisher").ifBlank { null },
                        releaseDate = yearOf(game),
                    )
                }
            }
            return null
        }
    }
}
