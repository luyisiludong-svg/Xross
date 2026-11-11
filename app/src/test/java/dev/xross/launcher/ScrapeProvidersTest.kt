package dev.xross.launcher

import dev.xross.launcher.scrape.ArtKind
import dev.xross.launcher.scrape.AssetCache
import dev.xross.launcher.scrape.DownloadResult
import dev.xross.launcher.scrape.HttpClient
import dev.xross.launcher.scrape.HttpResponse
import dev.xross.launcher.scrape.ProviderId
import dev.xross.launcher.scrape.ProviderResult
import dev.xross.launcher.scrape.RateLimiter
import dev.xross.launcher.scrape.ScrapePrefs
import dev.xross.launcher.scrape.ScrapeRequest
import dev.xross.launcher.scrape.providers.HasheousProvider
import dev.xross.launcher.scrape.providers.ScreenScraperCredentials
import dev.xross.launcher.scrape.providers.ScreenScraperProvider
import dev.xross.launcher.scrape.providers.SteamGridDbProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Canned HTTP: the first rule whose prefix matches answers; every request is recorded. */
class FakeHttp(private val rules: List<Pair<String, HttpResponse>> = emptyList()) : HttpClient {
    val requests = ArrayList<String>()
    val bodies = ArrayList<String>()
    val headers = ArrayList<Map<String, String>>()
    var download: (String, File) -> DownloadResult = { _, _ -> DownloadResult(404, 0, null) }

    private fun answer(url: String): HttpResponse =
        rules.firstOrNull { url.startsWith(it.first) || url.contains(it.first) }?.second ?: HttpResponse(404, ByteArray(0), null)

    override suspend fun get(url: String, headers: Map<String, String>, maxBytes: Long): HttpResponse {
        requests += url
        this.headers += headers
        return answer(url)
    }

    override suspend fun post(url: String, body: ByteArray, contentType: String, headers: Map<String, String>, maxBytes: Long): HttpResponse {
        requests += url
        bodies += body.toString(Charsets.UTF_8)
        return answer(url)
    }

    override suspend fun download(url: String, dest: File, maxBytes: Long, headers: Map<String, String>): DownloadResult {
        requests += url
        return download(url, dest)
    }

    companion object {
        fun json(code: Int, body: String) = HttpResponse(code, body.toByteArray(), "application/json")
    }
}

/** org.json needs Android's implementation, hence Robolectric. */
@RunWith(RobolectricTestRunner::class)
class ScrapeProvidersTest {
    private val noWait = RateLimiter(0, clock = { 0L }, sleep = {})
    private val request = ScrapeRequest(
        gameId = 1, uuid = "u", title = "Chrono Trigger", fileName = "ct.sfc", platformId = "snes", sizeBytes = 4194304,
        crc32 = "2d206bf7", md5 = "a2bc447961e52fd2227baed164f729dc", sha1 = "de5822f4f2f7a55acb8926d4c0eaa63d5d989312",
        prefs = ScrapePrefs(region = "eu", language = "fr", videos = true),
    )
    private val creds = ScreenScraperCredentials("dev", "devpw", "Xross", user = "me", password = "pw")

    // region ScreenScraper

    private val jeu = """
        {"header": {}, "response": {"jeu": {
            "id": "3398",
            "noms": [{"region": "ss", "text": "Chrono Trigger (SS)"}, {"region": "us", "text": "Chrono Trigger"}, {"region": "eu", "text": "Chrono Trigger (Europe)"}],
            "synopsis": [{"langue": "en", "text": "A time-travel RPG."}, {"langue": "fr", "text": "Un RPG temporel."}],
            "dates": [{"region": "jp", "text": "1995-03-11"}, {"region": "us", "text": "1995-08-22"}],
            "developpeur": {"id": "1", "text": "Square"},
            "editeur": {"id": "1", "text": "Square"},
            "joueurs": {"text": "1"},
            "note": {"text": "18"},
            "genres": [
                {"id": "2", "principale": "0", "noms": [{"langue": "en", "text": "Adventure"}]},
                {"id": "1", "principale": "1", "noms": [{"langue": "en", "text": "Role-playing"}, {"langue": "fr", "text": "Jeu de rôle"}]}
            ],
            "medias": [
                {"type": "box-2D", "region": "us", "url": "https://media.example/box2d-us", "format": "png"},
                {"type": "box-3D", "region": "jp", "url": "https://media.example/box3d-jp", "format": "png"},
                {"type": "box-3D", "region": "eu", "url": "https://media.example/box3d-eu", "format": "png"},
                {"type": "wheel", "region": "wor", "url": "https://media.example/wheel", "format": "png"},
                {"type": "wheel-hd", "region": "wor", "url": "https://media.example/wheel-hd", "format": "png"},
                {"type": "ss", "region": "wor", "url": "https://media.example/ss", "format": "jpg"},
                {"type": "video", "region": "wor", "url": "https://media.example/video", "format": "mp4"},
                {"type": "video-normalized", "region": "wor", "url": "https://media.example/video-n", "format": "mp4"}
            ]
        }}}
    """.trimIndent()

    @Test fun screenScraperSendsHashesAndParsesTheGame() = runBlocking {
        val http = FakeHttp(listOf("jeuInfos.php" to FakeHttp.json(200, jeu)))
        val provider = ScreenScraperProvider(http, { creds }, noWait)
        val result = provider.lookup(request) as ProviderResult.Found
        val url = http.requests.single()
        assertTrue(url.startsWith("https://api.screenscraper.fr/api2/jeuInfos.php?"))
        for (p in listOf("devid=dev", "softname=Xross", "output=json", "ssid=me", "crc=2D206BF7", "systemeid=4", "romtaille=4194304", "romnom=ct.sfc", "romtype=rom")) {
            assertTrue("missing $p in $url", url.contains(p))
        }
        val g = result.game
        assertEquals("3398", g.providerId)
        assertEquals("Chrono Trigger", g.title) // eu name with its "(Europe)" tag cleaned
        assertEquals("Un RPG temporel.", g.synopsis)
        assertEquals("1995-08-22", g.releaseDate) // no eu date: falls back through wor → us
        assertEquals("Square", g.developer)
        assertEquals("Jeu de rôle", g.genre) // the main genre, in French
        assertEquals("1", g.players)
        assertEquals(0.9f, g.rating!!, 0.001f)
        val media = g.media.associate { it.kind to it.url }
        assertEquals("https://media.example/box3d-eu", media[ArtKind.BOX_3D])
        assertEquals("https://media.example/wheel-hd", media[ArtKind.WHEEL])
        assertEquals("https://media.example/video-n", media[ArtKind.VIDEO])
        assertEquals("jpg", g.media.single { it.kind == ArtKind.SCREENSHOT }.ext)
    }

    @Test fun screenScraperUsesTheChosenGameId() = runBlocking {
        val http = FakeHttp(listOf("jeuInfos.php" to FakeHttp.json(200, jeu)))
        ScreenScraperProvider(http, { creds }, noWait).lookup(request.copy(screenScraperId = "3398"))
        val url = http.requests.single()
        assertTrue(url.contains("gameid=3398"))
        assertFalse(url.contains("crc="))
    }

    @Test fun screenScraperStatusCodes() = runBlocking {
        fun provider(code: Int) = ScreenScraperProvider(FakeHttp(listOf("jeuInfos" to FakeHttp.json(code, "Erreur"))), { creds }, noWait)
        assertEquals(ProviderResult.NotFound, provider(404).lookup(request))
        assertTrue(provider(403).lookup(request) is ProviderResult.Unavailable)
        assertTrue(provider(430).lookup(request) is ProviderResult.QuotaExceeded)
        assertTrue(provider(431).lookup(request) is ProviderResult.QuotaExceeded)
        assertTrue(provider(429).lookup(request) is ProviderResult.Failed)
        assertTrue(provider(423).lookup(request) is ProviderResult.Failed)
        // Never leaks the credential-bearing URL into a message.
        val failed = provider(500).lookup(request) as ProviderResult.Failed
        assertFalse(failed.reason.contains("devpw"))
    }

    @Test fun screenScraperWithoutDeveloperCredentialsDoesNothing() = runBlocking {
        val http = FakeHttp()
        val provider = ScreenScraperProvider(http, { ScreenScraperCredentials("", "", "Xross") }, noWait)
        assertFalse(provider.isConfigured())
        assertTrue(provider.lookup(request) is ProviderResult.Unavailable)
        assertTrue(http.requests.isEmpty())
    }

    @Test fun screenScraperReadsKeyedMediaToo() {
        val keyed = JSONObject(
            """{"media_boitiers": {"media_boitier_3d_us": "https://m/3d", "media_boitier_2d_us": "https://m/2d"},
                "media_wheels": {"media_wheel_wor": "https://m/w"}, "media_screenshot": "https://m/ss"}""",
        )
        val media = ScreenScraperProvider.parseMedia(keyed, listOf("us", "wor"), videos = false).associate { it.kind to it.url }
        assertEquals("https://m/3d", media[ArtKind.BOX_3D])
        assertEquals("https://m/2d", media[ArtKind.BOX_2D])
        assertEquals("https://m/w", media[ArtKind.WHEEL])
        assertEquals("https://m/ss", media[ArtKind.SCREENSHOT])
        assertNull(media[ArtKind.VIDEO])
    }

    @Test fun screenScraperSearch() = runBlocking {
        val http = FakeHttp(
            listOf(
                "jeuRecherche.php" to FakeHttp.json(
                    200,
                    """{"response": {"jeux": [
                        {"id": "3398", "noms": [{"region": "us", "text": "Chrono Trigger"}], "systeme": {"text": "Super Nintendo"}, "dates": [{"region": "us", "text": "1995-08-22"}]},
                        {"id": "77", "noms": [{"region": "jp", "text": "Chrono Trigger (Japan)"}]}
                    ]}}""",
                ),
            ),
        )
        val hits = ScreenScraperProvider(http, { creds }, noWait).search("chrono", "snes")
        assertTrue(http.requests.single().contains("recherche=chrono"))
        assertEquals(listOf("3398", "77"), hits.map { it.id })
        assertEquals("Super Nintendo · 1995", hits[0].detail)
    }

    // endregion

    // region SteamGridDB

    @Test fun steamGridDbSearchesThenFetchesArt() = runBlocking {
        val http = FakeHttp(
            listOf(
                "/search/autocomplete/" to FakeHttp.json(200, """{"success": true, "data": [{"id": 5, "name": "Chrono Cross"}, {"id": 9, "name": "Chrono Trigger"}]}"""),
                "/grids/game/9" to FakeHttp.json(200, """{"success": true, "data": [{"id": 1, "url": "https://cdn.sgdb/grid.png", "width": 600, "height": 900}]}"""),
                "/heroes/game/9" to FakeHttp.json(200, """{"success": true, "data": [{"id": 2, "url": "https://cdn.sgdb/hero.jpg"}]}"""),
                "/logos/game/9" to FakeHttp.json(200, """{"success": true, "data": []}"""),
            ),
        )
        val result = SteamGridDbProvider(http, { "key123" }, noWait).lookup(request) as ProviderResult.Found
        assertTrue(http.requests[0].endsWith("/search/autocomplete/Chrono%20Trigger"))
        assertTrue(http.requests.any { it.endsWith("/grids/game/9?dimensions=600x900") })
        assertEquals("Bearer key123", http.headers.first()["Authorization"])
        assertEquals("9", result.game.providerId)
        assertEquals(setOf(ArtKind.GRID, ArtKind.HERO), result.game.media.map { it.kind }.toSet())
        assertNull(result.game.title) // art only
    }

    @Test fun steamGridDbRejectedKeyAndMissingKey() = runBlocking {
        val rejected = SteamGridDbProvider(FakeHttp(listOf("/search" to FakeHttp.json(401, """{"success": false}"""))), { "bad" }, noWait)
        assertTrue(rejected.lookup(request) is ProviderResult.Unavailable)
        val none = SteamGridDbProvider(FakeHttp(), { "" }, noWait)
        assertFalse(none.isConfigured())
        assertTrue(none.lookup(request) is ProviderResult.Unavailable)
    }

    @Test fun steamGridDbPicksTheClosestName() {
        val hits = listOf(Triple("1", "Chrono Cross", null), Triple("2", "Chrono Trigger: DS", null), Triple("3", "CHRONO TRIGGER", null))
        assertEquals("3", SteamGridDbProvider.bestHit("Chrono Trigger", hits)!!.first)
        assertEquals("2", SteamGridDbProvider.bestHit("Chrono Trigger: D", hits)!!.first)
        assertEquals("1", SteamGridDbProvider.bestHit("Zelda", hits)!!.first)
    }

    // endregion

    // region Hasheous

    @Test fun hasheousPostsHashesAndReadsTheMatch() = runBlocking {
        val http = FakeHttp(
            listOf(
                "/Lookup/ByHash" to FakeHttp.json(
                    200,
                    """{"id": 42, "name": "Chrono Trigger (USA)", "platform": {"name": "Super Nintendo Entertainment System"},
                        "publisher": {"name": "Square"}, "signature": {"game": {"name": "Chrono Trigger (USA)", "year": "1995"}}}""",
                ),
            ),
        )
        val result = HasheousProvider(http, { true }, noWait).lookup(request) as ProviderResult.Found
        assertEquals("https://hasheous.org/api/v1/Lookup/ByHash?returnFields=All", http.requests.single())
        val body = JSONObject(http.bodies.single())
        assertEquals("2d206bf7", body.getString("crc"))
        assertEquals("de5822f4f2f7a55acb8926d4c0eaa63d5d989312", body.getString("sha1"))
        assertEquals(ProviderId.HASHEOUS, result.game.provider)
        assertEquals("42", result.game.providerId)
        assertEquals("Chrono Trigger", result.game.title)
        assertEquals("Square", result.game.publisher)
        assertEquals("1995", result.game.releaseDate)
    }

    @Test fun hasheousFallsBackToTheSignatureAndHandlesMisses() = runBlocking {
        val parsed = HasheousProvider.parse(JSONObject("""{"id": 1, "signature": {"game": {"name": "Tetris (World)", "publisher": "Nintendo"}}}"""))!!
        assertEquals("Tetris", parsed.title)
        assertEquals("Nintendo", parsed.publisher)
        assertNull(HasheousProvider.parse(JSONObject("""{"id": 1}""")))

        val miss = HasheousProvider(FakeHttp(listOf("/Lookup" to FakeHttp.json(404, ""))), { true }, noWait)
        assertEquals(ProviderResult.NotFound, miss.lookup(request))
        // Without a fingerprint there is nothing to send.
        val http = FakeHttp()
        assertEquals(ProviderResult.NotFound, HasheousProvider(http, { true }, noWait).lookup(request.copy(crc32 = null, md5 = null, sha1 = null)))
        assertTrue(http.requests.isEmpty())
    }

    // endregion

    // region Plumbing

    @Test fun rateLimiterSpacesRequestsAndBacksOff() = runBlocking {
        var now = 0L
        val slept = ArrayList<Long>()
        val limiter = RateLimiter(1_000, clock = { now }, sleep = { slept += it; now += it })
        limiter.acquire()
        limiter.acquire()
        now += 300
        limiter.acquire()
        assertEquals(listOf(1_000L, 700L), slept)
        limiter.penalize(30_000)
        limiter.acquire()
        assertEquals(30_000L, slept.last())
    }

    @Test fun extensionsComeFromHintTypeOrUrl() {
        assertEquals("jpg", AssetCache.extensionFor("https://x/a", null, "jpeg"))
        assertEquals("png", AssetCache.extensionFor("https://x/a", "image/png; charset=binary"))
        assertEquals("mp4", AssetCache.extensionFor("https://x/clip.MP4?token=1", null))
        assertNull(AssetCache.extensionFor("https://x/page.html", "text/html"))
    }

    // endregion
}
