package dev.xross.launcher

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.xross.launcher.RetroAchievementsClientTest.Companion.KEY
import dev.xross.launcher.RetroAchievementsClientTest.Companion.PROFILE_JSON
import dev.xross.launcher.RetroAchievementsClientTest.Companion.SUMMARY_JSON
import dev.xross.launcher.data.CredentialStore
import dev.xross.launcher.profile.ProfileRepository
import dev.xross.launcher.profile.RaBadge
import dev.xross.launcher.profile.RetroAchievementsClient
import dev.xross.launcher.scrape.DownloadResult
import dev.xross.launcher.scrape.HttpClient
import dev.xross.launcher.scrape.HttpResponse
import dev.xross.launcher.state.UnlockQueue
import dev.xross.launcher.state.XmbViewModel
import dev.xross.launcher.storage.XrossEnvironment
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Unlock toasts: which unlocks are news (only those after the last refresh, never an account's
 * history), that the watermark survives restarts in the profile cache, and the one-at-a-time queue.
 */
@RunWith(RobolectricTestRunner::class)
class UnlockToastTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule val tmp = TemporaryFolder()

    private val environment by lazy {
        XrossEnvironment(context, writeAccess = { true }, primaryRoot = { File(tmp.root, "emulated/0/Xross") })
    }
    private val credentials by lazy { CredentialStore(context) }

    private fun badge(title: String, date: String) = RaBadge(title = title, gameTitle = "Sonic", points = 5, date = date)

    @Test fun onlyUnlocksNewerThanTheWatermarkAreNews() {
        val recent = listOf(badge("C", "2024-03-03 10:00:00"), badge("B", "2024-03-02 10:00:00"), badge("A", "2024-03-01 10:00:00"))
        assertEquals(emptyList<RaBadge>(), ProfileRepository.newUnlocks(null, recent)) // first fetch: history isn't news
        assertEquals(listOf("B", "C"), ProfileRepository.newUnlocks("2024-03-01 10:00:00", recent).map { it.title }) // oldest first
        assertEquals(listOf("A", "B", "C"), ProfileRepository.newUnlocks("", recent).map { it.title }) // linked with no unlocks before
        assertEquals(emptyList<RaBadge>(), ProfileRepository.newUnlocks("2024-03-03 10:00:00", recent))
    }

    /** Serves a profile whose recent unlocks are whatever [recent] holds at request time. */
    private class SwitchingHttp(var recent: String) : HttpClient {
        override suspend fun get(url: String, headers: Map<String, String>, maxBytes: Long): HttpResponse = when {
            url.contains(RetroAchievementsClient.PROFILE) -> FakeHttp.json(200, PROFILE_JSON)
            url.contains(RetroAchievementsClient.SUMMARY) -> FakeHttp.json(200, SUMMARY_JSON)
            url.contains(RetroAchievementsClient.RECENT) -> FakeHttp.json(200, recent)
            else -> HttpResponse(404, ByteArray(0), null)
        }

        override suspend fun post(url: String, body: ByteArray, contentType: String, headers: Map<String, String>, maxBytes: Long) =
            HttpResponse(404, ByteArray(0), null)

        override suspend fun download(url: String, dest: File, maxBytes: Long, headers: Map<String, String>) =
            DownloadResult(404, 0, null)
    }

    private fun recentJson(vararg items: Pair<String, String>) = items.joinToString(",", "[", "]") { (title, date) ->
        """{"Date": "$date", "HardcoreMode": 1, "AchievementID": 1, "Title": "$title", "Description": "", "BadgeName": "1",
           "Points": 5, "GameTitle": "Sonic", "GameID": 1, "BadgeURL": "/Badge/1.png"}"""
    }

    @Test fun refreshAnnouncesNewUnlocksOnceAndRemembersAcrossRestarts() = runBlocking {
        val http = SwitchingHttp(recentJson("Old" to "2024-01-01 00:00:00"))
        val repo = ProfileRepository(RetroAchievementsClient(http), credentials, { environment.current })
        repo.link("MaxMilyin", KEY)

        // Nothing new: no toast.
        val quiet = async { withTimeoutOrNull(300) { repo.unlocks.first() } }
        repo.refresh()
        assertNull(quiet.await())

        // Two unlocks while playing: both announced, oldest first.
        http.recent = recentJson("Second" to "2024-01-03 00:00:00", "First" to "2024-01-02 00:00:00", "Old" to "2024-01-01 00:00:00")
        val news = async { withTimeoutOrNull(2_000) { repo.unlocks.first() } }
        repo.refresh()
        assertEquals(listOf("First", "Second"), news.await()!!.map { it.title })

        // The watermark is in the profile cache, so a restart doesn't announce them again.
        val cache = environment.current.profileCache.readText()
        assertTrue(cache.contains("\"lastUnlockSeen\": \"2024-01-03 00:00:00\""))
        assertFalse(cache.contains(KEY))
        val restarted = ProfileRepository(RetroAchievementsClient(http), credentials, { environment.current })
        restarted.load()
        val again = async { withTimeoutOrNull(300) { restarted.unlocks.first() } }
        restarted.refresh()
        assertNull(again.await())
    }

    @Test fun queueShowsOneAtATimeWithoutDuplicates() {
        val q = UnlockQueue(limit = 3)
        q.offer(listOf(badge("A", "1"), badge("B", "2")))
        q.offer(listOf(badge("A", "1"), badge("C", "3"), badge("D", "4")))
        val first = q.next()!!
        assertEquals("A", first.badge.title)
        assertEquals(2, first.more)
        assertEquals(listOf("B", "C"), listOfNotNull(q.next(), q.next()).map { it.badge.title }) // D dropped: over the limit
        assertNull(q.next())
        val serials = listOf(UnlockQueue().apply { offer(listOf(badge("x", "1"), badge("y", "2"))) }.let { listOf(it.next()!!.serial, it.next()!!.serial) })
        assertEquals(listOf(listOf(1L, 2L)), serials)
        assertTrue(XmbViewModel.UNLOCK_TOAST_MS in 3_000..6_000)
    }
}
