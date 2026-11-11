package dev.xross.launcher

import dev.xross.launcher.profile.RaResult
import dev.xross.launcher.profile.RetroAchievementsClient
import dev.xross.launcher.scrape.DownloadResult
import dev.xross.launcher.scrape.HttpClient
import dev.xross.launcher.scrape.HttpResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/** Payload parsing and error handling of the RetroAchievements Web API client. Pure JVM. */
class RetroAchievementsClientTest {

    @Test fun parsesAFullProfileWithRankAndRecentUnlocks() = runBlocking {
        val http = FakeHttp(
            listOf(
                RetroAchievementsClient.PROFILE to FakeHttp.json(200, PROFILE_JSON),
                RetroAchievementsClient.SUMMARY to FakeHttp.json(200, SUMMARY_JSON),
                RetroAchievementsClient.RECENT to FakeHttp.json(200, RECENT_JSON),
            ),
        )
        val result = RetroAchievementsClient(http, clock = { 1234L }).fetchProfile("maxmilyin", KEY)
        val profile = (result as RaResult.Ok).profile

        assertEquals("MaxMilyin", profile.username) // canonical spelling from the API
        assertEquals(399597L, profile.totalPoints)
        assertEquals(1599212L, profile.truePoints)
        assertEquals(1599212.0 / 399597.0, profile.retroRatio!!, 1e-9)
        assertEquals(4616, profile.rank)
        assertEquals(45654, profile.totalRanked)
        assertEquals("https://media.retroachievements.org/UserPic/MaxMilyin.png", profile.avatarUrl)
        assertEquals("2016-01-02 00:43:04", profile.memberSince)
        assertNull("blank motto is dropped", profile.motto)
        assertEquals(1234L, profile.fetchedAtMs)

        assertEquals(listOf("Beginner II", "Beginner I"), profile.recentBadges.map { it.title }) // newest first
        val badge = profile.recentBadges.last()
        assertEquals("Pokemon Pinball mini", badge.gameTitle)
        assertEquals(5, badge.points)
        assertTrue(badge.hardcore)
        assertEquals("https://media.retroachievements.org/Badge/108302.png", badge.badgeUrl)
    }

    @Test fun keyTravelsOnlyAsTheYParameterToRetroAchievements() = runBlocking {
        val http = FakeHttp(listOf(RetroAchievementsClient.PROFILE to FakeHttp.json(200, PROFILE_JSON)))
        RetroAchievementsClient(http).fetchProfile("Max Milyin", KEY)
        assertTrue(http.requests.isNotEmpty())
        for (url in http.requests) {
            assertTrue(url, url.startsWith("https://retroachievements.org/API/API_"))
            assertTrue(url, url.contains("&y=$KEY"))
        }
        // The user name is URL-encoded.
        assertTrue(http.requests.first().contains("u=Max+Milyin"))
    }

    @Test fun profileStillArrivesWhenRankAndUnlocksFail() = runBlocking {
        val http = FakeHttp(listOf(RetroAchievementsClient.PROFILE to FakeHttp.json(200, PROFILE_JSON))) // others 404
        val profile = (RetroAchievementsClient(http).fetchProfile("MaxMilyin", KEY) as RaResult.Ok).profile
        assertNull(profile.rank)
        assertTrue(profile.recentBadges.isEmpty())
        assertEquals(399597L, profile.totalPoints)
    }

    @Test fun numbersSentAsStringsAndNullsAreTolerated() {
        val json = Json.parseToJsonElement(
            """{"User":"Kid","UserPic":null,"TotalPoints":"120","TotalTruePoints":null,"Motto":"Hi","Rank":null}""",
        )
        val p = RetroAchievementsClient.parseProfile(json)!!
        assertEquals(120L, p.totalPoints)
        assertEquals(0L, p.truePoints)
        assertEquals(0.0, p.retroRatio!!, 0.0)
        assertEquals("https://media.retroachievements.org/UserPic/Kid.png", p.avatarUrl) // derived when missing
        assertEquals("Hi", p.motto)
        assertNull(RetroAchievementsClient.parseRank(json))
        assertNull("no hardcore points, no ratio", RetroAchievementsClient.parseProfile(Json.parseToJsonElement("""{"User":"New"}"""))!!.retroRatio)
    }

    @Test fun malformedRecentItemsAreSkipped() {
        val json = Json.parseToJsonElement("""[{"Title":""},{"Points":5},"x",{"Title":"Ok","BadgeName":"77"}]""")
        val badges = RetroAchievementsClient.parseRecent(json)
        assertEquals(listOf("Ok"), badges.map { it.title })
        assertEquals("https://media.retroachievements.org/Badge/77.png", badges.single().badgeUrl)
    }

    @Test fun errorsMapToResultsWithoutLeakingTheKey() = runBlocking {
        suspend fun answer(code: Int, body: String) =
            RetroAchievementsClient(FakeHttp(listOf(RetroAchievementsClient.PROFILE to FakeHttp.json(code, body)))).fetchProfile("u", KEY)

        assertEquals(RaResult.InvalidCredentials, answer(401, """{"message":"Unauthenticated."}"""))
        assertEquals(RaResult.InvalidCredentials, answer(403, ""))
        assertEquals(RaResult.UserNotFound, answer(200, "{}"))
        assertEquals(RaResult.UserNotFound, answer(200, "[]"))
        assertEquals(RaResult.UserNotFound, answer(404, ""))
        assertTrue(answer(200, "<html>oops") is RaResult.Failed)
        assertTrue(answer(429, "") is RaResult.Failed)
        assertTrue(answer(500, "") is RaResult.Failed)

        val offline = RetroAchievementsClient(OfflineHttp()).fetchProfile("u", KEY)
        assertTrue(offline is RaResult.Failed)
        assertFalse((offline as RaResult.Failed).reason.contains(KEY))

        // Nothing to send without a name or key.
        assertEquals(RaResult.InvalidCredentials, RetroAchievementsClient(OfflineHttp()).fetchProfile(" ", KEY))
        assertEquals(RaResult.InvalidCredentials, RetroAchievementsClient(OfflineHttp()).fetchProfile("u", ""))
    }

    @Test fun imagesAreOnlyFetchedFromRetroAchievementsOverHttps() = runBlocking {
        val http = FakeHttp().apply {
            download = { _, dest -> dest.writeBytes(byteArrayOf(1, 2, 3)); DownloadResult(200, 3, "image/png") }
        }
        val client = RetroAchievementsClient(http)
        val dest = File.createTempFile("avatar", ".png").apply { deleteOnExit() }
        assertTrue(client.downloadImage("https://media.retroachievements.org/UserPic/A.png", dest))
        assertFalse(client.downloadImage("https://evil.example/UserPic/A.png", dest))
        assertFalse(client.downloadImage("http://media.retroachievements.org/UserPic/A.png", dest))
        assertFalse(client.downloadImage("https://retroachievements.org.evil.example/a.png", dest))
        assertEquals(1, http.requests.size)

        assertEquals("https://media.retroachievements.org/UserPic/A.png", RetroAchievementsClient.absoluteMedia("/UserPic/A.png"))
        assertEquals("https://x.retroachievements.org/a.png", RetroAchievementsClient.absoluteMedia("http://x.retroachievements.org/a.png"))
    }

    companion object {
        const val KEY = "SeCrEtWebApiKey123"

        val PROFILE_JSON = """
            {"User":"MaxMilyin","ULID":"00003EMFWR7XB8SDPEHB3K56ZQ","UserPic":"/UserPic/MaxMilyin.png",
             "MemberSince":"2016-01-02 00:43:04","RichPresenceMsg":"Playing","LastGameID":19504,"ContribCount":0,
             "ContribYield":0,"TotalPoints":399597,"TotalSoftcorePoints":0,"TotalTruePoints":1599212,"Permissions":1,
             "Untracked":0,"ID":16446,"UserWallActive":1,"Motto":""}
        """.trimIndent()

        val SUMMARY_JSON = """{"User":"MaxMilyin","TotalPoints":399597,"Rank":4616,"TotalRanked":45654,"Status":"Offline"}"""

        val RECENT_JSON = """
            [{"Date":"2023-12-27 16:04:50","HardcoreMode":1,"AchievementID":98012,"Title":"Beginner I","BadgeName":"108302",
              "Points":5,"TrueRatio":25,"GameTitle":"Pokemon Pinball mini","GameID":14715,"ConsoleName":"Pokemon Mini",
              "BadgeURL":"/Badge/108302.png","GameURL":"/game/14715"},
             {"Date":"2023-12-28 10:00:00","HardcoreMode":0,"AchievementID":98013,"Title":"Beginner II","BadgeName":"108303",
              "Points":10,"GameTitle":"Pokemon Pinball mini","BadgeURL":"/Badge/108303.png"}]
        """.trimIndent()
    }
}

/** Every request fails as if there were no network. */
class OfflineHttp : HttpClient {
    var calls = 0
    override suspend fun get(url: String, headers: Map<String, String>, maxBytes: Long): HttpResponse {
        calls++
        throw IOException("Network unreachable")
    }

    override suspend fun post(url: String, body: ByteArray, contentType: String, headers: Map<String, String>, maxBytes: Long): HttpResponse {
        calls++
        throw IOException("Network unreachable")
    }

    override suspend fun download(url: String, dest: File, maxBytes: Long, headers: Map<String, String>): DownloadResult {
        calls++
        throw IOException("Network unreachable")
    }
}
