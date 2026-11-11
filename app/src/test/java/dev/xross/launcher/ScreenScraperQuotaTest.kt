package dev.xross.launcher

import dev.xross.launcher.scrape.ProviderResult
import dev.xross.launcher.scrape.RateLimiter
import dev.xross.launcher.scrape.ScrapePrefs
import dev.xross.launcher.scrape.ScrapeRequest
import dev.xross.launcher.scrape.providers.AccountCheck
import dev.xross.launcher.scrape.providers.HasheousProvider
import dev.xross.launcher.scrape.providers.ScreenScraperCredentials
import dev.xross.launcher.scrape.providers.ScreenScraperProvider
import dev.xross.launcher.scrape.providers.ScreenScraperQuota
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ScreenScraperQuotaTest {
    private val creds = ScreenScraperCredentials("dev", "devpw", "Xross", user = "player1", password = "pw")
    private val request = ScrapeRequest(
        gameId = 1, uuid = "u", title = "Chrono Trigger", fileName = "ct.sfc", platformId = "snes", sizeBytes = 4194816,
        crc32 = "aaaaaaaa", md5 = "a".repeat(32), sha1 = "a".repeat(40),
        dataCrc32 = "bbbbbbbb", dataMd5 = "b".repeat(32), dataSha1 = "b".repeat(40),
        prefs = ScrapePrefs(),
    )

    private fun game(id: String = "3398", name: String = "Chrono Trigger", user: String = userBlock()) =
        """{"response": {"ssuser": $user, "jeu": {"id": "$id", "noms": [{"region": "us", "text": "$name"}]}}}"""

    private fun userBlock(today: Int = 10, perDay: Int = 20000, ko: Int = 2, koPerDay: Int = 2000, perMin: Int = 30) =
        """{"id": "player1", "requeststoday": "$today", "maxrequestsperday": "$perDay", "requestskotoday": "$ko",
            "maxrequestskoperday": "$koPerDay", "maxrequestspermin": "$perMin", "maxthreads": "1"}"""

    @Test fun readsTheQuotaAndPacesToIt() = runBlocking {
        val limiter = RateLimiter(1_200, clock = { 0L }, sleep = {})
        val provider = ScreenScraperProvider(FakeHttp(listOf("jeuInfos" to FakeHttp.json(200, game()))), { creds }, limiter)
        assertTrue(provider.lookup(request) is ProviderResult.Found)
        val q = provider.quota.value!!
        assertEquals(ScreenScraperQuota("player1", 10, 20000, 2, 2000, 30, 1), q)
        // 30 per minute → one every 2 s, plus a 10% margin.
        assertEquals(2_200L, limiter.minIntervalMs)
        assertEquals("10 of 20,000 requests today · 2 of 2,000 not found", q.summary)
    }

    @Test fun neverPacesFasterThanTheBase() = runBlocking {
        val limiter = RateLimiter(1_200, clock = { 0L }, sleep = {})
        val fast = game(user = userBlock(perMin = 600))
        ScreenScraperProvider(FakeHttp(listOf("jeuInfos" to FakeHttp.json(200, fast))), { creds }, limiter).lookup(request)
        assertEquals(1_200L, limiter.minIntervalMs)
        // The doc's other spelling is read too.
        val other = game(user = """{"maxrequestsperdmin": "20"}""")
        ScreenScraperProvider(FakeHttp(listOf("jeuInfos" to FakeHttp.json(200, other))), { creds }, limiter).lookup(request)
        assertEquals(3_300L, limiter.minIntervalMs)
    }

    @Test fun stopsOnItsOwnWhenTheDailyAllowanceIsSpent() = runBlocking {
        val spent = game(user = userBlock(today = 20000))
        val http = FakeHttp(listOf("jeuInfos" to FakeHttp.json(200, spent)))
        val provider = ScreenScraperProvider(http, { creds }, RateLimiter(0, clock = { 0L }, sleep = {}))
        provider.lookup(request)
        assertEquals(1, http.requests.size)
        assertTrue(provider.lookup(request) is ProviderResult.QuotaExceeded)
        assertTrue(provider.search("chrono", "snes").isEmpty())
        assertEquals(1, http.requests.size) // no further calls once spent
    }

    @Test fun notFoundAnswersCountTowardTheNotFoundAllowance() = runBlocking {
        var answer = FakeHttp.json(200, game(user = userBlock(ko = 1999)))
        val http = object : dev.xross.launcher.scrape.HttpClient by FakeHttp() {
            override suspend fun get(url: String, headers: Map<String, String>, maxBytes: Long) = answer
        }
        val provider = ScreenScraperProvider(http, { creds }, RateLimiter(0, clock = { 0L }, sleep = {}))
        provider.lookup(request)
        answer = FakeHttp.json(404, "Erreur : Rom/Iso/Dossier non trouvée !")
        assertEquals(ProviderResult.NotFound, provider.lookup(request))
        assertTrue(provider.quota.value!!.isSpent)
        assertTrue(provider.lookup(request) is ProviderResult.QuotaExceeded)
    }

    @Test fun tellsUserAndDeveloperLoginErrorsApart() = runBlocking {
        fun provider(body: String) = ScreenScraperProvider(FakeHttp(listOf("api2" to FakeHttp.json(403, body))), { creds }, RateLimiter(0, clock = { 0L }, sleep = {}))
        val user = provider("Erreur de login : Vérifier les identifiants utilisateurs !").lookup(request) as ProviderResult.Unavailable
        assertTrue(user.reason.contains("user name"))
        val dev = provider("Erreur de login : Vérifier vos identifiants développeur !").lookup(request) as ProviderResult.Unavailable
        assertTrue(dev.reason.contains("developer"))
        assertEquals(AccountCheck.Failed(user.reason), provider("Erreur de login : Vérifier les identifiants utilisateurs !").checkAccount())
    }

    @Test fun accountCheckReportsTheQuota() = runBlocking {
        val http = FakeHttp(listOf("ssuserInfos" to FakeHttp.json(200, """{"response": {"ssuser": ${userBlock()}}}""")))
        val check = ScreenScraperProvider(http, { creds }, RateLimiter(0, clock = { 0L }, sleep = {})).checkAccount() as AccountCheck.Ok
        assertEquals("player1", check.quota.user)
        val url = http.requests.single()
        assertTrue(url.contains("ssid=player1"))
        assertTrue(url.contains("devid=dev"))
    }

    @Test fun sendsFileHashesToScreenScraperAndHeaderlessOnesToHasheous() = runBlocking {
        val ss = FakeHttp(listOf("jeuInfos" to FakeHttp.json(200, game())))
        ScreenScraperProvider(ss, { creds }, RateLimiter(0, clock = { 0L }, sleep = {})).lookup(request)
        val url = ss.requests.single()
        assertTrue(url.contains("crc=AAAAAAAA"))
        assertTrue(url.contains("sha1=" + "a".repeat(40)))
        assertTrue(url.contains("romtaille=4194816"))

        val hh = FakeHttp(listOf("Lookup" to FakeHttp.json(404, "")))
        HasheousProvider(hh, { true }, RateLimiter(0, clock = { 0L }, sleep = {})).lookup(request)
        assertEquals("b".repeat(40), JSONObject(hh.bodies.single()).getString("sha1"))
    }

    @Test fun filesTooLargeToHashUseAConfidentNameMatch() = runBlocking {
        val unhashed = request.copy(
            title = "The Legend of Zelda Ocarina of Time 3D", fileName = "zelda.cci", platformId = "n3ds",
            crc32 = null, md5 = null, sha1 = null, dataCrc32 = null, dataMd5 = null, dataSha1 = null,
        )
        val http = FakeHttp(
            listOf(
                "jeuRecherche" to FakeHttp.json(
                    200,
                    """{"response": {"jeux": [
                        {"id": "1", "noms": [{"region": "us", "text": "The Legend of Zelda: Majora's Mask 3D"}]},
                        {"id": "2", "noms": [{"region": "us", "text": "The Legend of Zelda: Ocarina of Time 3D (USA)"}]}
                    ]}}""",
                ),
                "jeuInfos" to FakeHttp.json(200, game(id = "2", name = "The Legend of Zelda: Ocarina of Time 3D")),
            ),
        )
        val result = ScreenScraperProvider(http, { creds }, RateLimiter(0, clock = { 0L }, sleep = {})).lookup(unhashed)
        assertTrue(result is ProviderResult.Found)
        assertTrue(http.requests[0].contains("jeuRecherche.php"))
        assertTrue(http.requests[0].contains("systemeid=17"))
        assertTrue(http.requests[1].contains("gameid=2"))
        assertFalse(http.requests[1].contains("crc="))

        // Nothing close enough: no guess, and no lookup by id.
        val vague = FakeHttp(listOf("jeuRecherche" to FakeHttp.json(200, """{"response": {"jeux": [{"id": "1", "noms": [{"region": "us", "text": "Zelda II"}]}]}}""")))
        assertEquals(ProviderResult.NotFound, ScreenScraperProvider(vague, { creds }, RateLimiter(0, clock = { 0L }, sleep = {})).lookup(unhashed))
        assertEquals(1, vague.requests.size)
    }
}
