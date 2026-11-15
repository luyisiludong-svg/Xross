package dev.xross.launcher

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.xross.launcher.RetroAchievementsClientTest.Companion.KEY
import dev.xross.launcher.data.CredentialStore
import dev.xross.launcher.input.NavAction
import dev.xross.launcher.profile.Friend
import dev.xross.launcher.profile.FriendsRepository
import dev.xross.launcher.profile.FriendsState
import dev.xross.launcher.profile.RaFollowed
import dev.xross.launcher.profile.RaFollowingResult
import dev.xross.launcher.profile.RaFriendActivity
import dev.xross.launcher.profile.RaFriendResult
import dev.xross.launcher.profile.RaResult
import dev.xross.launcher.profile.RetroAchievementsClient
import dev.xross.launcher.scrape.DownloadResult
import dev.xross.launcher.scrape.HttpClient
import dev.xross.launcher.scrape.HttpResponse
import dev.xross.launcher.state.SocialPanelEffect
import dev.xross.launcher.state.SocialPanelState
import dev.xross.launcher.state.SocialStack
import dev.xross.launcher.storage.XrossEnvironment
import dev.xross.launcher.theme.pack.ResolvedComponents
import dev.xross.launcher.theme.pack.TopLeftClusterMode
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.io.IOException
import dev.xross.launcher.data.XrossSettings
import dev.xross.launcher.profile.AchievementsRepository
import dev.xross.launcher.profile.ProfileRepository
import dev.xross.launcher.state.AccountsSetup
import dev.xross.launcher.state.MenuSnapshot
import dev.xross.launcher.state.MenuTree
import dev.xross.launcher.state.RaController
import dev.xross.launcher.theme.pack.ComponentOverrides
import dev.xross.launcher.theme.pack.ComponentRow
import dev.xross.launcher.theme.pack.TelemetryMode

/**
 * Phase 2b.7c: the two friend calls and their parsers, [FriendsRepository] (cache, the 20-friend
 * cap, call spacing, fetched on refresh only, new activity), the social stack's collapse rules
 * and its panel.
 */
@RunWith(RobolectricTestRunner::class)
class FriendsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule val tmp = TemporaryFolder()

    private val environment by lazy {
        XrossEnvironment(context, writeAccess = { true }, primaryRoot = { File(tmp.root, "emulated/0/Xross") })
    }
    private val layout get() = environment.current
    private val credentials by lazy { CredentialStore(context) }

    @Before fun link() = credentials.update { it.copy(retroAchievementsUser = "Me", retroAchievementsKey = KEY) }

    // region Fakes

    /** RetroAchievements with [friends] followed; counts every call and records the times they started. */
    private class FakeRa(
        var friends: List<String>,
        val clock: FakeClock,
        var offline: Boolean = false,
    ) : HttpClient {
        val urls = ArrayList<String>()
        val startedAt = ArrayList<Long>()
        val downloads = ArrayList<String>()
        var busyLeft = 0
        /** Per friend: the game they're on and when. */
        val playing = HashMap<String, Pair<Int, String>>()

        val apiCalls get() = urls.size
        val followingCalls get() = urls.count { RetroAchievementsClient.FOLLOWING in it }
        val activityCalls get() = urls.count { RetroAchievementsClient.SUMMARY in it }

        override suspend fun get(url: String, headers: Map<String, String>, maxBytes: Long): HttpResponse {
            urls += url
            startedAt += clock.now
            if (offline) throw IOException("Network unreachable")
            if (busyLeft > 0) {
                busyLeft--
                return HttpResponse(429, "<html>429</html>".toByteArray(), "text/html")
            }
            return when {
                RetroAchievementsClient.FOLLOWING in url -> {
                    val c = Regex("[?&]c=(\\d+)").find(url)!!.groupValues[1].toInt()
                    val o = Regex("[?&]o=(\\d+)").find(url)!!.groupValues[1].toInt()
                    val page = friends.drop(o).take(c)
                    json(following(page, friends.size))
                }
                RetroAchievementsClient.SUMMARY in url -> {
                    val who = Regex("[?&]u=([^&]+)").find(url)!!.groupValues[1]
                    val name = friends.firstOrNull { ulid(it) == who || it == who } ?: return HttpResponse(422, "{}".toByteArray(), "application/json")
                    val (game, at) = playing[name] ?: (100 to "2026-11-01 10:00:00")
                    json(summary(name, game, at))
                }
                else -> HttpResponse(404, ByteArray(0), null)
            }
        }

        override suspend fun post(url: String, body: ByteArray, contentType: String, headers: Map<String, String>, maxBytes: Long) =
            HttpResponse(404, ByteArray(0), null)

        override suspend fun download(url: String, dest: File, maxBytes: Long, headers: Map<String, String>): DownloadResult {
            downloads += url
            if (offline) throw IOException("Network unreachable")
            dest.writeBytes(byteArrayOf(1, 2, 3))
            return DownloadResult(200, 3, "image/png")
        }

        private fun json(text: String) = HttpResponse(200, text.toByteArray(), "application/json")
    }

    private class FakeClock(var now: Long = 1_800_000_000_000L)

    private fun repo(ra: FakeRa, clock: FakeClock, logs: MutableList<String> = ArrayList()) = FriendsRepository(
        client = RetroAchievementsClient(ra, clock = { clock.now }),
        credentials = credentials,
        layout = { layout },
        clock = { clock.now },
        pause = { clock.now += it },
        log = { logs += it },
    )

    private fun names(n: Int) = (1..n).map { "Friend$it" }

    // endregion

    // region Parsers

    @Test fun followingParserReadsEntriesAndSkipsMalformedOnes() {
        val element = Json.parseToJsonElement(
            """{"Count":4,"Total":120,"Results":[
                {"User":"zuliman92","ULID":"00003EMFWR7XB8SDPEHB3K56ZQ","Points":1882,"PointsSoftcore":258,"IsFollowingMe":true},
                {"ULID":"NONAME"}, 7, {"User":"  "},
                {"User":"Second","Points":"12","IsFollowingMe":false}]}""",
        )
        val (total, users, served) = RetroAchievementsClient.parseFollowing(element)!!
        assertEquals(120, total)
        assertEquals(5, served)
        assertEquals(listOf("zuliman92", "Second"), users.map { it.user })
        assertEquals(RaFollowed("zuliman92", "00003EMFWR7XB8SDPEHB3K56ZQ", 1882, 258, followsBack = true), users[0])
        assertEquals(12L, users[1].points)
        assertEquals("ULID, else the name in lower case", "second", users[1].key)
        // The account follows nobody (what the live API answered for the test account).
        assertEquals(Triple(0, emptyList<RaFollowed>(), 0), RetroAchievementsClient.parseFollowing(Json.parseToJsonElement("""{"Count":0,"Total":0,"Results":[]}""")))
        assertNull(RetroAchievementsClient.parseFollowing(Json.parseToJsonElement("[]")))
        assertNull(RetroAchievementsClient.parseFollowing(Json.parseToJsonElement("""{"message":"The c must not be greater than 500."}""")))
    }

    @Test fun activityParserTakesTheRecentGameThenLastGame() {
        val a = RetroAchievementsClient.parseFriendActivity(Json.parseToJsonElement(summary("MaxMilyin", 3750, "2026-11-14 16:22:04")), fetchedAtMs = 5)!!
        assertEquals("MaxMilyin", a.user)
        assertEquals(ulid("MaxMilyin"), a.ulid)
        assertEquals(3750, a.gameId)
        assertEquals("Game 3750", a.gameTitle)
        assertEquals("Dreamcast", a.consoleName)
        assertEquals("https://media.retroachievements.org/Images/062885.png", a.gameIconUrl)
        assertEquals("https://media.retroachievements.org/UserPic/MaxMilyin.png", a.avatarUrl)
        assertEquals("2026-11-14 16:22:04", a.lastPlayed)
        assertEquals("2026-11-14 16:22:04", a.lastActive)
        assertEquals(5L, a.fetchedAtMs)
        assertTrue(a.online)

        // No recently played list: LastGame describes LastGameID; garbage dates are dropped.
        val b = RetroAchievementsClient.parseFriendActivity(
            Json.parseToJsonElement(
                """{"User":"B","LastGameID":12,"RichPresenceMsgDate":"yesterday","Status":"Offline",
                    "LastGame":{"ID":12,"Title":"Twelve","ConsoleName":"NES","ImageIcon":"/Images/1.png"},"RecentlyPlayed":[]}""",
            ),
        )!!
        assertEquals(12, b.gameId)
        assertEquals("Twelve", b.gameTitle)
        assertEquals("", b.lastPlayed)
        assertEquals("", b.lastSeen)
        assertFalse(b.online)

        // Never played anything.
        val c = RetroAchievementsClient.parseFriendActivity(Json.parseToJsonElement("""{"User":"C","LastGameID":null}"""))!!
        assertEquals(0, c.gameId)
        assertEquals("", c.lastActive)
        assertNull("an unknown user answers an empty object", RetroAchievementsClient.parseFriendActivity(Json.parseToJsonElement("{}")))
        assertNull(RetroAchievementsClient.parseFriendActivity(Json.parseToJsonElement("[]")))
    }

    // endregion

    // region Client

    @Test fun followingIsOneCallUpTo200AndCapped() = runBlocking {
        val clock = FakeClock()
        val ra = FakeRa(names(250), clock)
        val r = RetroAchievementsClient(ra).fetchFollowing(KEY) as RaFollowingResult.Ok
        assertEquals(RetroAchievementsClient.MAX_FOLLOWING, r.users.size)
        assertEquals(250, r.total)
        assertEquals("RetroAchievements serves up to 500 per page: one call", 1, ra.apiCalls)
        assertTrue(ra.urls.single().contains("c=200&o=0"))
        assertFalse("the endpoint answers for the key; no user parameter", ra.urls.single().contains("u="))

        val few = FakeRa(names(3), clock)
        assertEquals(3, (RetroAchievementsClient(few).fetchFollowing(KEY) as RaFollowingResult.Ok).users.size)
        assertEquals(1, few.apiCalls)
    }

    @Test fun followingPagesWhenAPageComesBackShort() = runBlocking {
        // A server that serves at most 100 per page whatever c says.
        val pages = ArrayList<String>()
        val http = object : HttpClient {
            override suspend fun get(url: String, headers: Map<String, String>, maxBytes: Long): HttpResponse {
                pages += url
                val o = Regex("[?&]o=(\\d+)").find(url)!!.groupValues[1].toInt()
                return HttpResponse(200, following(names(150).drop(o).take(100), 150).toByteArray(), "application/json")
            }
            override suspend fun post(url: String, body: ByteArray, contentType: String, headers: Map<String, String>, maxBytes: Long) = throw IOException()
            override suspend fun download(url: String, dest: File, maxBytes: Long, headers: Map<String, String>) = throw IOException()
        }
        val r = RetroAchievementsClient(http).fetchFollowing(KEY) as RaFollowingResult.Ok
        assertEquals(150, r.users.size)
        assertEquals(listOf("c=200&o=0", "c=100&o=100"), pages.map { Regex("c=\\d+&o=\\d+").find(it)!!.value })
    }

    @Test fun activityAsksByUlidAndReportsBusy() = runBlocking {
        val clock = FakeClock()
        val ra = FakeRa(listOf("Alice"), clock)
        val client = RetroAchievementsClient(ra)
        val r = client.fetchFriendActivity(KEY, RaFollowed("Alice", ulid("Alice")))
        assertTrue(r is RaFriendResult.Ok)
        assertTrue(ra.urls.single().contains("u=${ulid("Alice")}&"))
        assertTrue(ra.urls.single().contains("g=1&a=1"))
        ra.busyLeft = 1
        val busy = client.fetchFriendActivity(KEY, RaFollowed("Alice")) as RaFriendResult.Error
        assertEquals(true, (busy.result as RaResult.Failed).busy)
        // A name RetroAchievements would refuse (422) isn't sent.
        val before = ra.apiCalls
        assertEquals(RaFriendResult.Error(RaResult.UserNotFound), client.fetchFriendActivity(KEY, RaFollowed("bad name/..")))
        assertEquals(before, ra.apiCalls)
    }

    // endregion

    // region FriendsRepository

    @Test fun refreshFetchesFollowingThenActivityAndCachesBoth() = runBlocking {
        val clock = FakeClock()
        val ra = FakeRa(names(3), clock)
        val logs = ArrayList<String>()
        val repo = repo(ra, clock, logs)
        val r = repo.refresh()
        assertEquals(3, r.friends)
        assertEquals(3, r.activity)
        assertEquals(4, r.calls)
        assertEquals(4, ra.apiCalls)
        assertEquals(listOf("friends=3 activity=3 calls=4"), logs)
        val ready = repo.current as FriendsState.Ready
        assertEquals(3, ready.friends.size)
        assertEquals("the first refresh isn't news", 0, ready.newActivity)
        assertTrue(File(layout.data, "ra/friends.json").isFile)
        assertTrue("avatars like the user's own", File(layout.avatars, "friends/Friend1.png").isFile)

        // Within 30 minutes: everything is fresh, nothing is asked.
        clock.now += 10 * 60_000
        assertFalse(repo.isStale())
        assertEquals(0, repo.refresh().calls)
        // After 30 minutes the activity, after 6 hours the list too.
        clock.now += 25 * 60_000
        assertTrue(repo.isStale())
        assertEquals(3, repo.refresh().calls)
        clock.now += FriendsRepository.FOLLOWING_STALE_MS
        assertEquals(4, repo.refresh().calls)
    }

    @Test fun atMost20FriendsGetActivityCallsSpacedApart() = runBlocking {
        val clock = FakeClock()
        val ra = FakeRa(names(40), clock)
        val repo = repo(ra, clock)
        repo.refresh()
        assertEquals(1, ra.followingCalls)
        assertEquals(FriendsRepository.MAX_ACTIVITY, ra.activityCalls)
        val gaps = ra.startedAt.zipWithNext { a, b -> b - a }
        assertTrue("calls ≥ ${FriendsRepository.CALL_SPACING_MS} ms apart: $gaps", gaps.all { it >= FriendsRepository.CALL_SPACING_MS })

        // The next refresh keeps the 15 most recently active and rotates the rest through the others.
        ra.playing["Friend40"] = 9 to "2026-11-14 12:00:00"
        clock.now += FriendsRepository.ACTIVITY_FRESH_MS
        ra.urls.clear()
        repo.refresh()
        val asked = ra.urls.map { url -> Regex("u=([^&]+)").find(url)!!.groupValues[1] }
        assertEquals(FriendsRepository.MAX_ACTIVITY, asked.size)
        assertTrue("never-fetched friends come before the rest", asked.contains(ulid("Friend21")) && asked.contains(ulid("Friend25")))
        // Five a refresh: 26–30, 31–35, then 36–40.
        repeat(3) {
            clock.now += FriendsRepository.ACTIVITY_FRESH_MS
            repo.refresh()
        }
        assertEquals("Friend40's game, found by the rotation, puts them first", "Friend40", repo.current.friends.first().user)
    }

    @Test fun nothingIsFetchedByLoadingReadingOrOpening() = runBlocking {
        val clock = FakeClock()
        val ra = FakeRa(names(5), clock)
        val first = repo(ra, clock)
        first.refresh()
        val calls = ra.apiCalls
        val downloads = ra.downloads.size

        // A new session: the cache, state reads, staleness checks and opening the stack are all offline.
        clock.now += FriendsRepository.FOLLOWING_STALE_MS * 2
        val repo = repo(ra, clock)
        repo.load()
        repeat(50) {
            repo.current.friends
            repo.isStale()
            repo.state.value.newActivity
        }
        repo.markOpened()
        assertEquals(5, repo.current.friends.size)
        assertEquals(calls, ra.apiCalls)
        assertEquals(downloads, ra.downloads.size)
    }

    @Test fun newActivityCountsChangesSinceTheStackWasOpenedAndCapsAt9Plus() = runBlocking {
        val clock = FakeClock()
        val ra = FakeRa(names(12), clock)
        val repo = repo(ra, clock)
        repo.refresh()
        assertEquals(0, repo.current.newActivity)

        // Two friends start a new game.
        ra.playing["Friend3"] = 200 to "2027-01-01 10:00:00"
        ra.playing["Friend7"] = 300 to "2027-01-01 11:00:00"
        clock.now += FriendsRepository.ACTIVITY_FRESH_MS
        repo.refresh()
        assertEquals(2, repo.current.newActivity)
        assertEquals("2", repo.current.badge)
        assertEquals(listOf("Friend7", "Friend3"), repo.current.friends.take(2).map { it.user })
        assertTrue(repo.current.friends[0].isNew)

        // Opening clears it; refreshing with nothing new keeps it clear.
        repo.markOpened()
        assertEquals(0, repo.current.newActivity)
        assertEquals("", repo.current.badge)
        clock.now += FriendsRepository.ACTIVITY_FRESH_MS
        repo.refresh()
        assertEquals(0, repo.current.newActivity)

        // Everyone moves on: the badge stops at 9+.
        for ((i, n) in names(12).withIndex()) ra.playing[n] = (400 + i) to "2027-02-01 10:${"%02d".format(i)}:00"
        clock.now += FriendsRepository.ACTIVITY_FRESH_MS
        repo.refresh()
        assertEquals(12, repo.current.newActivity)
        assertEquals("9+", repo.current.badge)

        // Kept across sessions (friends.json).
        val again = repo(ra, clock)
        again.load()
        assertEquals(12, again.current.newActivity)
        again.markOpened()
        val reread = repo(ra, clock).also { it.load() }
        assertEquals(0, reread.current.newActivity)
    }

    @Test fun badgeText() {
        assertEquals(listOf("", "1", "9", "9+", "9+"), listOf(0, 1, 9, 10, 250).map(FriendsState::badgeText))
    }

    @Test fun offlineKeepsTheCacheAndStopsAtTheFirstFailure() = runBlocking {
        val clock = FakeClock()
        val ra = FakeRa(names(10), clock)
        val repo = repo(ra, clock)
        repo.refresh()
        ra.offline = true
        ra.urls.clear()
        clock.now += FriendsRepository.FOLLOWING_STALE_MS + 1
        val r = repo.refresh()
        assertTrue(r.failed)
        assertEquals("no point asking 20 times without a network", 1, ra.apiCalls)
        val offline = repo.current as FriendsState.Offline
        assertEquals("the cached list", 10, offline.friends.size)

        // Without a cache: offline and empty.
        File(layout.data, "ra/friends.json").delete()
        val fresh = repo(ra, clock)
        fresh.load()
        fresh.refresh()
        assertEquals(FriendsState.Offline(), fresh.current)
    }

    @Test fun busyWaitsOnceThenEndsTheRefresh() = runBlocking {
        val clock = FakeClock()
        val ra = FakeRa(names(5), clock)
        val repo = repo(ra, clock)
        ra.busyLeft = 1
        val r = repo.refresh()
        assertFalse("one 429, then the retry worked", r.failed)
        assertEquals(5, r.activity)
        assertTrue(ra.startedAt[1] - ra.startedAt[0] >= FriendsRepository.BUSY_BACKOFF_MS)

        ra.busyLeft = 2
        clock.now += FriendsRepository.ACTIVITY_FRESH_MS
        ra.urls.clear()
        val busy = repo.refresh()
        assertTrue(busy.failed)
        assertEquals("the first friend, its retry, and nobody after", 2, ra.apiCalls)
        assertEquals(5, repo.current.friends.size)
    }

    @Test fun followingNobodyIsReadyAndEmpty() = runBlocking {
        val clock = FakeClock()
        val ra = FakeRa(emptyList(), clock)
        val repo = repo(ra, clock)
        repo.refresh()
        assertEquals(FriendsState.Ready(), repo.current)
        assertEquals("only the following list was asked for", 1, ra.apiCalls)
        assertFalse("not stale for 6 hours", repo.isStale())
    }

    @Test fun anotherAccountsCacheIsIgnoredAndUnlinkClearsEverything() = runBlocking {
        val clock = FakeClock()
        val ra = FakeRa(names(2), clock)
        val repo = repo(ra, clock)
        repo.refresh()
        credentials.update { it.copy(retroAchievementsUser = "Someone") }
        repo.load()
        assertEquals(FriendsState.Offline(), repo.current)
        assertTrue(repo.isStale())

        credentials.update { it.copy(retroAchievementsUser = "", retroAchievementsKey = "") }
        repo.clear()
        assertEquals(FriendsState.Unlinked, repo.current)
        assertFalse(File(layout.data, "ra/friends.json").exists())
        assertFalse(File(layout.avatars, "friends").exists())
        val r = repo.refresh()
        assertTrue(r.skipped)
        assertEquals(0, r.calls)
    }

    @Test fun noCredentialInFriendsJson() = runBlocking {
        val clock = FakeClock()
        val ra = FakeRa(names(4), clock)
        val repo = repo(ra, clock)
        repo.refresh()
        repo.markOpened()
        val file = File(layout.data, "ra/friends.json")
        assertTrue(file.isFile)
        assertFalse("key in friends.json", file.readText().contains(KEY))
        val leaks = layout.root.walkTopDown().filter { it.isFile && it.readBytes().toString(Charsets.ISO_8859_1).contains(KEY) }.toList()
        assertEquals(emptyList<File>(), leaks)
        assertTrue("the key only travels in requests", ra.urls.all { it.contains("y=$KEY") })
    }

    // endregion

    // region Social stack

    private val stack = ResolvedComponents(topLeft = TopLeftClusterMode.SOCIAL_STACK)
    private val someone = listOf(Friend("A", "a"))

    @Test fun stackCollapsesWhenUnlinkedOfflineWithoutCacheOrFollowingNobody() {
        assertFalse(SocialStack.shows(stack, FriendsState.Unlinked))
        assertFalse("offline without cache", SocialStack.shows(stack, FriendsState.Offline()))
        assertTrue("offline with cache: the cached list", SocialStack.shows(stack, FriendsState.Offline(someone)))
        assertFalse("following nobody", SocialStack.shows(stack, FriendsState.Ready()))
        assertTrue(SocialStack.shows(stack, FriendsState.Ready(someone)))
        assertTrue("refreshing shows what was known", SocialStack.shows(stack, FriendsState.Loading(someone)))
        assertFalse("EMPTY (the default) draws nothing", SocialStack.shows(ResolvedComponents.DEFAULT, FriendsState.Ready(someone)))
    }

    @Test fun topLeftRowCyclesThemePackSocialStackEmpty() {
        val row = ComponentRow.TOP_LEFT
        assertEquals(listOf("SOCIAL_STACK", "EMPTY"), row.choices.map { it.first })
        assertEquals("SOCIAL_STACK", row.next(null))
        assertEquals("EMPTY", row.next("SOCIAL_STACK"))
        assertNull(row.next("EMPTY"))
        val o = ComponentOverrides.NONE.with(row, "social")
        assertEquals("SOCIAL_STACK", o[row])
        assertEquals(TopLeftClusterMode.SOCIAL_STACK, o.patch.topLeft)
        assertEquals("Theme pack (Empty)", row.packLabel(ResolvedComponents.DEFAULT))

        fun subtitle(linked: Boolean, value: String?) = MenuTree(
            MenuSnapshot(
                settings = XrossSettings(componentOverrides = ComponentOverrides(topLeftCluster = value)),
                accounts = AccountsSetup(retroAchievementsLinked = linked),
            ),
        ).list(MenuTree.DISPLAY_COMPONENTS).single { it.id == "disp:comp:top_left" }.subtitle
        assertEquals("Theme pack (Empty) · Friends you follow on RetroAchievements, after the left slots", subtitle(true, null))
        assertEquals("Social stack · Needs a RetroAchievements account (Settings ▸ Accounts)", subtitle(false, "SOCIAL_STACK"))
        assertEquals("Social stack · Friends you follow, newest activity first", subtitle(true, "SOCIAL_STACK"))
    }

    @Test fun panelInput() {
        val s = SocialPanelState()
        assertEquals(1, SocialStack.onAction(s, NavAction.DOWN, 3, avatar = true).state.index)
        assertEquals("clamped at the end", 2, SocialStack.onAction(s.copy(index = 2), NavAction.DOWN, 3, avatar = true).state.index)
        assertEquals("clamped at the top", 0, SocialStack.onAction(s, NavAction.UP, 3, avatar = true).state.index)
        assertEquals(SocialPanelEffect.Close, SocialStack.onAction(s, NavAction.BACK, 3, avatar = true).effect)
        assertEquals(SocialPanelEffect.ToAvatar, SocialStack.onAction(s, NavAction.RIGHT, 3, avatar = true).effect)
        assertEquals(SocialPanelEffect.None, SocialStack.onAction(s, NavAction.RIGHT, 3, avatar = false).effect)
        assertEquals(SocialPanelEffect.MoveCategory(+1), SocialStack.onAction(s, NavAction.NEXT_CATEGORY, 3, avatar = true).effect)
    }

    @Test fun howLongAgo() {
        val now = SocialStack.parseUtc("2026-11-14 16:00:00")!!
        assertEquals("now", SocialStack.ago("2026-11-14 15:59:30", now))
        assertEquals("12 min", SocialStack.ago("2026-11-14 15:48:00", now))
        assertEquals("5 h", SocialStack.ago("2026-11-14 11:00:00", now))
        assertEquals("3 d", SocialStack.ago("2026-11-11 15:00:00", now))
        assertEquals("3 wk", SocialStack.ago("2026-10-24 16:00:00", now))
        assertEquals("2 y", SocialStack.ago("2024-11-01 16:00:00", now))
        assertEquals("", SocialStack.ago("", now))
        assertEquals("", SocialStack.ago("someday", now))
    }

    // endregion

    // region Through the controller

    private class Host(var components: ResolvedComponents) : RaController.Host {
        val moves = ArrayList<Int>()
        override fun toast(message: String) = Unit
        override fun fail(message: String) = Unit
        override fun promptLink(user: String, linked: Boolean) = Unit
        override fun moveCategory(delta: Int) { moves += delta }
        override fun dismissMenus() = Unit
        override fun components() = components
        override fun revealGame(gameId: Long) = Unit
    }

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            idle()
            if (System.currentTimeMillis() > deadline) throw AssertionError("Timed out waiting for $what")
            Thread.sleep(10)
        }
        idle()
    }

    @Test fun focusNeverFetchesFriendsWhileOpeningAStaleStackDoes() = runBlocking {
        val clock = FakeClock()
        val ra = FakeRa(names(3), clock)
        val friends = repo(ra, clock)
        val client = RetroAchievementsClient(ra, clock = { clock.now })
        val container = AppContainer(context)
        val scope = kotlinx.coroutines.MainScope()
        val host = Host(ResolvedComponents(topLeft = TopLeftClusterMode.SOCIAL_STACK))
        val controller = RaController(
            scope = scope,
            profiles = ProfileRepository(client, credentials, { layout }, clock = { clock.now }),
            achievements = AchievementsRepository(client, credentials, { layout }, hashes = { null }),
            friends = friends,
            credentials = credentials,
            games = container.games,
            sounds = container.sounds,
            artVersion = { 0L },
            host = host,
        )
        try {
            controller.watch(kotlinx.coroutines.flow.flowOf(1L, 2L, 3L))
            friends.refresh()
            await("friends in the controller") { controller.state.value.friends.friends.size == 3 }
            val known = ra.followingCalls + ra.activityCalls

            // Focusing the avatar refreshes a stale profile (its endpoints fail here), never friends;
            // so do the focused games' telemetry and reading the state.
            controller.focusAvatar()
            repeat(20) { controller.state.value.friends; idle() }
            Thread.sleep(100)
            idle()
            assertEquals(known, ra.followingCalls + ra.activityCalls)

            // Left from the HUD crosses to the stack and opens its panel; fresh data: no fetch.
            assertTrue(controller.onAvatarAction(NavAction.LEFT))
            assertNotNull(controller.state.value.socialPanel)
            assertFalse(controller.state.value.avatarFocused)
            assertEquals(emptyList<Int>(), host.moves)
            assertEquals(known, ra.followingCalls + ra.activityCalls)
            assertTrue(controller.onAvatarAction(NavAction.DOWN))
            assertEquals(1, controller.state.value.socialPanel?.index)
            // Right goes back to the avatar.
            assertTrue(controller.onAvatarAction(NavAction.RIGHT))
            assertNull(controller.state.value.socialPanel)
            assertTrue(controller.state.value.avatarFocused)
            controller.closeAvatarHud()

            // Half an hour later, opening the stack (a tap) refreshes it.
            clock.now += FriendsRepository.ACTIVITY_FRESH_MS + 1
            controller.onSocialStackTapped()
            await("the stale stack's refresh") { ra.activityCalls > 3 }
            assertNotNull(controller.state.value.socialPanel)
            // A tap on the avatar while the panel is open hands the input to the HUD.
            controller.onAvatarTapped()
            assertNull(controller.state.value.socialPanel)
            assertTrue(controller.state.value.avatarFocused)
            controller.closeAvatarHud()
            controller.onSocialStackTapped()
            controller.onSocialStackTapped()
            assertNull(controller.state.value.socialPanel)

            // Without a stack in the components, it can't open.
            host.components = ResolvedComponents.DEFAULT
            controller.openSocialPanel()
            assertNull(controller.state.value.socialPanel)
            assertFalse(controller.socialStackShown())
        } finally {
            scope.cancel()
        }
    }

    @Test fun richCardWantsFriendsButFocusingGamesNeverFetchesThem() = runBlocking {
        val clock = FakeClock()
        val ra = FakeRa(names(3), clock)
        val friends = repo(ra, clock)
        val client = RetroAchievementsClient(ra, clock = { clock.now })
        val container = AppContainer(context)
        val scope = kotlinx.coroutines.MainScope()
        val host = Host(ResolvedComponents(telemetry = TelemetryMode.RICH_CARD))
        val controller = RaController(
            scope = scope,
            profiles = ProfileRepository(client, credentials, { layout }, clock = { clock.now }),
            achievements = AchievementsRepository(client, credentials, { layout }, hashes = { null }),
            friends = friends,
            credentials = credentials,
            games = container.games,
            sounds = container.sounds,
            artVersion = { 0L },
            host = host,
        )
        fun friendCalls() = ra.followingCalls + ra.activityCalls
        val focus = kotlinx.coroutines.flow.MutableStateFlow<Long?>(null)
        try {
            controller.watch(focus)
            // Nothing saved yet: the card being on (at startup or turned on) fetches once, for its strip.
            controller.onFriendsWanted(onlyWithoutCache = true)
            await("friends for the rich card") { controller.state.value.friends.friends.size == 3 }
            val known = friendCalls()
            assertEquals(4, known)

            // Focus moving across games reads telemetry and the friends as they stand: no friend call.
            for (id in listOf(1L, 2L, 3L, null, 4L)) {
                focus.value = id
                repeat(5) { controller.state.value.friends; idle() }
            }
            Thread.sleep(200)
            idle()
            controller.onFriendsWanted(onlyWithoutCache = true)
            idle()
            assertEquals("a saved list isn't fetched again at startup", known, friendCalls())

            // The compact pill wants no friend data, even when it's stale.
            host.components = ResolvedComponents.DEFAULT
            clock.now += FriendsRepository.FOLLOWING_STALE_MS * 2
            controller.onFriendsWanted()
            Thread.sleep(100)
            idle()
            assertEquals(known, friendCalls())
            // Back on the rich card, a stale list is fetched when asked (the profile's refresh, turning it on).
            host.components = ResolvedComponents(telemetry = TelemetryMode.RICH_CARD)
            controller.onFriendsWanted()
            await("the stale list's refresh") { friendCalls() > known }
        } finally {
            scope.cancel()
        }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    // endregion

    companion object {
        /** A ULID-looking id for [name] (letters and digits only, 26 characters). */
        fun ulid(name: String) = ("01" + name.uppercase().filter { it.isLetterOrDigit() } + "Z" + "0".repeat(26)).take(26)

        fun following(page: List<String>, total: Int) = buildString {
            append("""{"Count":${page.size},"Total":$total,"Results":[""")
            append(page.joinToString(",") { """{"User":"$it","ULID":"${ulid(it)}","Points":10,"PointsSoftcore":0,"IsFollowingMe":false}""" })
            append("]}")
        }

        fun summary(name: String, game: Int, at: String) = """
            {"User":"$name","ULID":"${ulid(name)}","UserPic":"/UserPic/$name.png","TotalPoints":42,"LastGameID":$game,
             "RichPresenceMsg":"Exploring","RichPresenceMsgDate":"$at","Status":"Online",
             "RecentlyPlayed":[{"GameID":$game,"ConsoleID":40,"ConsoleName":"Dreamcast","Title":"Game $game","ImageIcon":"/Images/062885.png","LastPlayed":"$at"}],
             "LastGame":{"ID":$game,"Title":"Game $game","ConsoleName":"Dreamcast"}}
        """.trimIndent()
    }
}
