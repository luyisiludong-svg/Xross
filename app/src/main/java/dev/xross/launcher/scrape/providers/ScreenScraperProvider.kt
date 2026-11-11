package dev.xross.launcher.scrape.providers

import dev.xross.launcher.data.dat.DatNames
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/** Developer credentials (per application) and the user's own account (optional, raises quotas). */
data class ScreenScraperCredentials(
    val devId: String,
    val devPassword: String,
    val softName: String,
    val user: String = "",
    val password: String = "",
) {
    val hasDeveloper: Boolean get() = devId.isNotBlank() && devPassword.isNotBlank()
}

/** The user's ScreenScraper limits, as reported in every API answer (`ssuser`). */
data class ScreenScraperQuota(
    val user: String? = null,
    val requestsToday: Int = 0,
    val maxPerDay: Int = 0,
    /** Lookups today that found nothing. */
    val failedToday: Int = 0,
    val maxFailedPerDay: Int = 0,
    val maxPerMinute: Int = 0,
    val maxThreads: Int = 1,
) {
    /** Using the API now would break the daily limits the service asks clients to enforce. */
    val isSpent: Boolean
        get() = (maxPerDay > 0 && requestsToday >= maxPerDay) || (maxFailedPerDay > 0 && failedToday >= maxFailedPerDay)

    val summary: String
        get() = buildString {
            append("${fmt(requestsToday)} of ${fmt(maxPerDay)} requests today")
            if (maxFailedPerDay > 0) append(" · ${fmt(failedToday)} of ${fmt(maxFailedPerDay)} not found")
        }

    private fun fmt(n: Int) = java.text.NumberFormat.getIntegerInstance().format(n)
}

/** Result of [ScreenScraperProvider.checkAccount]. */
sealed interface AccountCheck {
    data class Ok(val quota: ScreenScraperQuota) : AccountCheck
    data class Failed(val reason: String) : AccountCheck
}

/**
 * ScreenScraper API v2 (`https://api.screenscraper.fr/api2/`), per the API page (translated in
 * `docs/SCREENSCRAPER_API.md`):
 *
 * - Every call carries the application's developer credentials (`devid`, `devpassword`,
 *   `softname`); the user's own account (`ssid` = user name, `sspassword`) is optional and
 *   raises the quotas.
 * - `jeuInfos.php` identifies a file by hash (CRC32/MD5/SHA-1 of the file as stored) plus size,
 *   name and system. Hashes are mandatory there, so files too large to hash fall back to
 *   `jeuRecherche.php` (name search) and then `jeuInfos.php?gameid=` for a confident match.
 * - Quota management is mandatory for clients: every answer's `ssuser` block (requests per
 *   minute and per day, requests and not-found lookups used today) paces and stops this client
 *   before the service has to refuse it.
 * - Status codes: 404 not found, 403 bad developer or user credentials, 429 too many threads,
 *   430 daily quota, 431 too many not-found lookups today, 401/423 API closed, 426 client
 *   blacklisted.
 *
 * The JSON reader accepts both shapes seen for localized values (`[{"region", "text"}]` arrays
 * and `{"nom_us": …}` keyed objects).
 */
class ScreenScraperProvider(
    private val http: HttpClient,
    private val credentials: () -> ScreenScraperCredentials,
    private val limiter: RateLimiter = RateLimiter(baseIntervalMs = 1_200),
    private val baseUrl: String = "https://api.screenscraper.fr/api2",
) : ScrapeProvider {

    override val id = ProviderId.SCREENSCRAPER

    private val _quota = MutableStateFlow<ScreenScraperQuota?>(null)
    /** Last limits the service reported; null until the first answer. */
    val quota: StateFlow<ScreenScraperQuota?> = _quota.asStateFlow()

    override fun isConfigured(): Boolean = credentials().hasDeveloper

    override suspend fun lookup(request: ScrapeRequest): ProviderResult {
        val creds = credentials()
        if (!creds.hasDeveloper) return ProviderResult.Unavailable("This build has no ScreenScraper developer credentials")
        if (_quota.value?.isSpent == true) return ProviderResult.QuotaExceeded("ScreenScraper's daily quota is used up")

        val gameId = request.screenScraperId ?: if (request.hasHash) {
            null
        } else {
            // No hash (file too large to fingerprint): only a confident name match may be used.
            when (val found = searchForGameId(creds, request)) {
                is ProviderResult -> return found
                is String -> found
                else -> return ProviderResult.NotFound
            }
        }
        val params = LinkedHashMap<String, String>()
        if (gameId != null) {
            params["gameid"] = gameId
        } else {
            request.crc32?.let { params["crc"] = it.uppercase() }
            request.md5?.let { params["md5"] = it }
            request.sha1?.let { params["sha1"] = it }
            request.serial?.let { params["serialnum"] = it }
            params["romtype"] = "rom"
            params["romnom"] = request.fileName
            params["romtaille"] = request.sizeBytes.toString()
            SYSTEM_IDS[request.platformId]?.let { params["systemeid"] = it.toString() }
        }
        val response = try {
            call("jeuInfos.php", creds, params)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            return ProviderResult.Failed("ScreenScraper unreachable (${e.javaClass.simpleName})")
        }
        statusResult(response)?.let { return it }
        return try {
            val root = JSONObject(response.text())
            val jeu = root.optJSONObject("response")?.optJSONObject("jeu") ?: root.optJSONObject("jeu")
                ?: return ProviderResult.NotFound
            if (jeu.optString("notgame") == "true") return ProviderResult.NotFound
            ProviderResult.Found(parseGame(jeu, request.prefs.region, request.prefs.language, request.prefs.videos))
        } catch (_: JSONException) {
            ProviderResult.Failed("ScreenScraper sent an unreadable answer")
        }
    }

    /** A game id whose name matches the request's title exactly (after normalizing), or a result. */
    private suspend fun searchForGameId(creds: ScreenScraperCredentials, request: ScrapeRequest): Any? {
        val hits = try {
            searchRaw(creds, request.title, request.platformId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            return ProviderResult.Failed("ScreenScraper unreachable (${e.javaClass.simpleName})")
        } ?: return ProviderResult.Failed("ScreenScraper search failed")
        val want = normalize(request.title)
        return hits.firstOrNull { (_, names) -> names.any { normalize(DatNames.parse(it).title) == want } }?.first
    }

    override suspend fun search(query: String, platformId: String?): List<SearchHit> {
        val creds = credentials()
        if (!creds.hasDeveloper || query.isBlank() || _quota.value?.isSpent == true) return emptyList()
        val games = searchJson(creds, query, platformId) ?: return emptyList()
        return (0 until games.length()).mapNotNull { i ->
            val g = games.optJSONObject(i) ?: return@mapNotNull null
            val id = g.optString("id").ifEmpty { return@mapNotNull null }
            val title = localized(g.opt("noms"), listOf("wor", "us", "eu", "ss", "jp"), "region") ?: return@mapNotNull null
            val system = g.optJSONObject("systeme")?.let { textOf(it.opt("text")) ?: it.optString("nom").ifEmpty { null } }
            val year = localized(g.opt("dates"), listOf("wor", "us", "eu", "jp"), "region")?.take(4)
            SearchHit(id = id, provider = ProviderId.SCREENSCRAPER, title = title, detail = listOfNotNull(system, year).joinToString(" · ").ifEmpty { null })
        }
    }

    /** (id, all localized names) per search result; null when the service refused. */
    private suspend fun searchRaw(creds: ScreenScraperCredentials, query: String, platformId: String?): List<Pair<String, List<String>>>? {
        val games = searchJson(creds, query, platformId) ?: return null
        return (0 until games.length()).mapNotNull { i ->
            val g = games.optJSONObject(i) ?: return@mapNotNull null
            val id = g.optString("id").ifEmpty { return@mapNotNull null }
            id to allLocalized(g.opt("noms"))
        }
    }

    private suspend fun searchJson(creds: ScreenScraperCredentials, query: String, platformId: String?): JSONArray? {
        val params = linkedMapOf("recherche" to query)
        SYSTEM_IDS[platformId]?.let { params["systemeid"] = it.toString() }
        val response = call("jeuRecherche.php", creds, params)
        if (!response.isSuccess) return null
        val root = runCatching { JSONObject(response.text()) }.getOrNull() ?: return null
        return root.optJSONObject("response")?.optJSONArray("jeux") ?: root.optJSONArray("jeux") ?: JSONArray()
    }

    /** Signs in with the current credentials and reports the account's limits (Settings ▸ Check account). */
    suspend fun checkAccount(): AccountCheck {
        val creds = credentials()
        if (!creds.hasDeveloper) return AccountCheck.Failed("This build has no ScreenScraper developer credentials")
        val response = try {
            call("ssuserInfos.php", creds, emptyMap())
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            return AccountCheck.Failed("ScreenScraper unreachable (${e.javaClass.simpleName})")
        }
        if (response.code == 403) return AccountCheck.Failed(loginError(response.text()))
        if (!response.isSuccess) return AccountCheck.Failed("ScreenScraper answered HTTP ${response.code}")
        return _quota.value?.let { AccountCheck.Ok(it) } ?: AccountCheck.Failed("ScreenScraper sent no account details")
    }

    private suspend fun call(endpoint: String, creds: ScreenScraperCredentials, params: Map<String, String>): HttpResponse {
        limiter.acquire()
        val query = buildList {
            add("devid" to creds.devId)
            add("devpassword" to creds.devPassword)
            add("softname" to creds.softName)
            add("output" to "json")
            if (creds.user.isNotBlank()) {
                add("ssid" to creds.user)
                add("sspassword" to creds.password)
            }
            params.forEach { (k, v) -> add(k to v) }
        }.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val response = http.get("$baseUrl/$endpoint?$query")
        when {
            response.code == 429 -> limiter.penalize(30_000)
            response.isSuccess -> runCatching { JSONObject(response.text()) }.getOrNull()?.let(::readQuota)
            // A miss isn't JSON, but it still counts against the not-found allowance.
            response.code == 404 -> _quota.value?.let { q ->
                _quota.value = q.copy(requestsToday = q.requestsToday + 1, failedToday = q.failedToday + 1)
            }
        }
        return response
    }

    /** Adopts the limits in an answer's `ssuser` block and paces requests to them. */
    private fun readQuota(root: JSONObject) {
        val user = root.optJSONObject("response")?.optJSONObject("ssuser") ?: root.optJSONObject("ssuser") ?: return
        fun int(vararg keys: String) = keys.firstNotNullOfOrNull { k -> user.opt(k)?.toString()?.toIntOrNull() } ?: 0
        val quota = ScreenScraperQuota(
            user = user.optString("id").ifBlank { null },
            requestsToday = int("requeststoday"),
            maxPerDay = int("maxrequestsperday"),
            failedToday = int("requestskotoday"),
            maxFailedPerDay = int("maxrequestskoperday"),
            // The API page spells it both ways.
            maxPerMinute = int("maxrequestspermin", "maxrequestsperdmin"),
            maxThreads = int("maxthreads").coerceAtLeast(1),
        )
        _quota.value = quota
        // 10% under the per-minute allowance; one request at a time regardless of threads.
        if (quota.maxPerMinute > 0) limiter.paceTo((60_000L * 11) / (10L * quota.maxPerMinute))
    }

    private suspend fun statusResult(response: HttpResponse): ProviderResult? = when (response.code) {
        in 200..299 -> null
        404 -> ProviderResult.NotFound
        400 -> ProviderResult.NotFound // the API rejects this file's request; asking again won't help
        403 -> ProviderResult.Unavailable(loginError(response.text()))
        426 -> ProviderResult.Unavailable("ScreenScraper no longer accepts this version of Xross")
        429 -> ProviderResult.Failed("ScreenScraper is busy (too many threads)")
        430 -> ProviderResult.QuotaExceeded("ScreenScraper's daily quota is used up")
        431 -> ProviderResult.QuotaExceeded("Too many unrecognized files today; ScreenScraper asks to try again tomorrow")
        401, 423 -> {
            limiter.penalize(5 * 60_000)
            ProviderResult.Failed("ScreenScraper's API is closed for now")
        }
        else -> ProviderResult.Failed("ScreenScraper answered HTTP ${response.code}")
    }

    companion object {
        /** 403 bodies say which credentials failed ("…identifiants utilisateurs" or "…développeur"). */
        internal fun loginError(body: String): String = when {
            body.contains("utilisateur", ignoreCase = true) ->
                "ScreenScraper rejected your user name or password (use your ScreenScraper user name, not your email)"
            else -> "ScreenScraper rejected this build's developer credentials"
        }

        internal fun normalize(s: String) = s.lowercase(java.util.Locale.ROOT).filter { it.isLetterOrDigit() }

        /** Every text in a localized value, any shape. */
        internal fun allLocalized(raw: Any?): List<String> = when (raw) {
            is String -> listOf(raw)
            is JSONArray -> (0 until raw.length()).mapNotNull { textOf(raw.optJSONObject(it)?.opt("text")) }
            is JSONObject -> raw.keys().asSequence().mapNotNull { textOf(raw.opt(it)) }.toList()
            else -> emptyList()
        }

        /** `systemeid` per Xross platform, as listed in the API's platform media paths. */
        val SYSTEM_IDS: Map<String, Int> = mapOf(
            "megadrive" to 1, "mastersystem" to 2, "nes" to 3, "snes" to 4, "gb" to 9, "gbc" to 10,
            "gba" to 12, "gc" to 13, "n64" to 14, "nds" to 15, "wii" to 16, "n3ds" to 17,
            "gamegear" to 21, "saturn" to 22, "dreamcast" to 23, "pcengine" to 31, "psx" to 57,
            "ps2" to 58, "psp" to 61, "arcade" to 75, "scummvm" to 123, "switch" to 225,
        )

        /** Region fallbacks after the user's choice; "ss" is ScreenScraper's own reference name. */
        private val REGION_FALLBACK = listOf("wor", "us", "eu", "ss", "jp")

        internal fun parseGame(jeu: JSONObject, region: String, language: String, videos: Boolean): ScrapedGame {
            val regions = (listOf(region) + REGION_FALLBACK).distinct()
            val languages = (listOf(language, "en")).distinct()
            val rawTitle = localized(jeu.opt("noms"), regions, "region")
            val genre = jeu.optJSONArray("genres")?.let { genres ->
                val ordered = (0 until genres.length()).mapNotNull { genres.optJSONObject(it) }
                    .sortedByDescending { it.optString("principale") == "1" }
                ordered.firstNotNullOfOrNull { localized(it.opt("noms"), languages, "langue") }
            }
            val note = textOf(jeu.opt("note"))?.toFloatOrNull()
            return ScrapedGame(
                provider = ProviderId.SCREENSCRAPER,
                providerId = jeu.optString("id"),
                title = rawTitle?.let { DatNames.parse(it).title },
                synopsis = localized(jeu.opt("synopsis"), languages, "langue")?.trim(),
                releaseDate = localized(jeu.opt("dates"), regions, "region"),
                developer = textOf(jeu.opt("developpeur")),
                publisher = textOf(jeu.opt("editeur")),
                genre = genre,
                players = textOf(jeu.opt("joueurs")),
                rating = note?.let { (it / 20f).coerceIn(0f, 1f) },
                media = parseMedia(jeu.opt("medias"), regions, videos),
            )
        }

        /** Media type → art kind, best first within a kind. */
        private val MEDIA_TYPES: List<Pair<String, ArtKind>> = listOf(
            "box-3D" to ArtKind.BOX_3D,
            "box-2D" to ArtKind.BOX_2D,
            "wheel-hd" to ArtKind.WHEEL,
            "wheel" to ArtKind.WHEEL,
            "ss" to ArtKind.SCREENSHOT,
            "fanart" to ArtKind.HERO,
            "video-normalized" to ArtKind.VIDEO,
            "video" to ArtKind.VIDEO,
        )

        internal fun parseMedia(raw: Any?, regions: List<String>, videos: Boolean): List<ScrapedMedia> {
            data class Item(val type: String, val region: String?, val url: String, val format: String?)
            val items = ArrayList<Item>()
            when (raw) {
                is JSONArray -> for (i in 0 until raw.length()) {
                    val m = raw.optJSONObject(i) ?: continue
                    val url = m.optString("url").ifEmpty { continue }
                    items += Item(m.optString("type"), m.optString("region").ifEmpty { null }, url, m.optString("format").ifEmpty { null })
                }
                // Keyed layout: "media_boitiers": {"media_boitier_3d": url…}, "media_wheels": {"media_wheel_us": url}.
                is JSONObject -> flattenKeyed(raw) { key, url ->
                    val type = when {
                        key.contains("3d") -> "box-3D"
                        key.contains("boitier") && key.contains("2d") -> "box-2D"
                        key.startsWith("media_wheel_") -> "wheel" // not the carbon/steel variants
                        key == "media_screenshot" -> "ss"
                        key == "media_fanart" -> "fanart"
                        key == "media_video" -> "video"
                        else -> null
                    } ?: return@flattenKeyed
                    val region = key.substringAfterLast('_').takeIf { it.length in 2..3 && it != "3d" && it != "2d" }
                    items += Item(type, region, url, null)
                }
            }
            return MEDIA_TYPES.map { it.second }.distinct().mapNotNull { kind ->
                if (kind.isVideo && !videos) return@mapNotNull null
                val types = MEDIA_TYPES.filter { it.second == kind }.map { it.first }
                items.filter { it.type in types }
                    .sortedWith(
                        compareBy<Item> { types.indexOf(it.type) }
                            .thenBy { regions.indexOf(it.region).let { r -> if (r < 0) regions.size else r } },
                    )
                    .firstOrNull()
                    ?.let { ScrapedMedia(kind, it.url, AssetCache.extensionFor(it.url, null, it.format)) }
            }
        }

        private fun flattenKeyed(obj: JSONObject, out: (String, String) -> Unit) {
            for (key in obj.keys()) {
                when (val v = obj.opt(key)) {
                    is String -> if (v.startsWith("https://")) out(key, v)
                    is JSONObject -> flattenKeyed(v, out)
                }
            }
        }

        /**
         * A localized value: `[{"region"|"langue": "us", "text": "…"}]` arrays, `{"nom_us": "…"}`
         * / `{"synopsis_en": "…"}` objects, or a plain string. First match in [preferred] order,
         * else the first value present.
         */
        internal fun localized(raw: Any?, preferred: List<String>, keyField: String): String? = when (raw) {
            is String -> raw.ifBlank { null }?.let(dev.xross.launcher.scrape.HtmlText::decode)
            is JSONArray -> {
                val entries = (0 until raw.length()).mapNotNull { raw.optJSONObject(it) }
                    .mapNotNull { e -> textOf(e.opt("text"))?.let { e.optString(keyField) to it } }
                preferred.firstNotNullOfOrNull { p -> entries.firstOrNull { it.first == p }?.second } ?: entries.firstOrNull()?.second
            }
            is JSONObject -> {
                val entries = raw.keys().asSequence().mapNotNull { k -> textOf(raw.opt(k))?.let { k.substringAfterLast('_') to it } }.toList()
                preferred.firstNotNullOfOrNull { p -> entries.firstOrNull { it.first == p }?.second } ?: entries.firstOrNull()?.second
            }
            else -> null
        }

        /** `{"text": "…"}` or a plain string. */
        internal fun textOf(raw: Any?): String? = when (raw) {
            is String -> raw.trim().ifEmpty { null }?.let(dev.xross.launcher.scrape.HtmlText::decode)
            is JSONObject -> raw.optString("text").trim().ifEmpty { null }?.let(dev.xross.launcher.scrape.HtmlText::decode)
            is Number -> raw.toString()
            else -> null
        }
    }
}
