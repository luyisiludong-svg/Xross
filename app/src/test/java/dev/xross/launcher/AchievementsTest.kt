package dev.xross.launcher

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.xross.launcher.RetroAchievementsClientTest.Companion.KEY
import dev.xross.launcher.data.CredentialStore
import dev.xross.launcher.data.GameEntity
import dev.xross.launcher.data.GameHashEntity
import dev.xross.launcher.input.NavAction
import dev.xross.launcher.profile.AchievementsRepository
import dev.xross.launcher.profile.AchievementsState
import dev.xross.launcher.profile.RaAchievement
import dev.xross.launcher.profile.RaConsoles
import dev.xross.launcher.profile.RaGameEntry
import dev.xross.launcher.profile.RaGameProgress
import dev.xross.launcher.profile.RaGameResult
import dev.xross.launcher.profile.RaListResult
import dev.xross.launcher.profile.RaMatchKind
import dev.xross.launcher.profile.RaMatcher
import dev.xross.launcher.profile.RetroAchievementsClient
import dev.xross.launcher.scrape.DownloadResult
import dev.xross.launcher.scrape.HttpResponse
import dev.xross.launcher.state.AchievementsEffect
import dev.xross.launcher.state.AchievementsGrid
import dev.xross.launcher.state.AchievementsViewState
import dev.xross.launcher.storage.XrossEnvironment
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
import java.io.File

/**
 * Game achievement lists: Web API parsing, ROM → RetroAchievements game matching per system, the
 * repository's caches in the Xross folder (and that the Web API key never lands there), badge
 * downloads, and the badge grid's navigation.
 */
@RunWith(RobolectricTestRunner::class)
class AchievementsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule val tmp = TemporaryFolder()

    private val environment by lazy {
        XrossEnvironment(context, writeAccess = { true }, primaryRoot = { File(tmp.root, "emulated/0/Xross") })
    }
    private val layout get() = environment.current
    private val credentials by lazy { CredentialStore(context) }

    @Before fun link() = credentials.update { it.copy(retroAchievementsUser = "MaxMilyin", retroAchievementsKey = KEY) }

    // region Web API parsing

    @Test fun parsesGameProgressInDisplayOrderWithUnlockState() {
        val p = RetroAchievementsClient.parseGameProgress(Json.parseToJsonElement(PROGRESS_JSON), fetchedAtMs = 7)!!
        assertEquals(1, p.gameId)
        assertEquals("Sonic the Hedgehog", p.title)
        assertEquals("Genesis/Mega Drive", p.consoleName)
        assertEquals("https://media.retroachievements.org/Images/085573.png", p.iconUrl)
        assertEquals(listOf(9, 10, 11), p.achievements.map { it.id }) // DisplayOrder 1, 2, 3
        assertEquals(3, p.total)
        assertEquals(2, p.unlocked)
        assertEquals(1, p.unlockedHardcore)
        assertEquals(5 + 10 + 25, p.totalPoints)
        assertEquals(15, p.earnedPoints)
        assertEquals(2f / 3f, p.completion, 1e-6f)
        val first = p.achievements.first()
        assertTrue(first.hardcore)
        assertEquals("2023-01-02 10:00:00", first.unlockDate) // hardcore date wins
        assertEquals("progression", first.type)
        assertEquals("250336", first.badgeName)
        assertFalse(p.achievements.last().unlocked)
        // Unlocked first (newest first), then locked.
        assertEquals(listOf(10, 9, 11), p.ordered().map { it.id })
        assertEquals(7L, p.fetchedAtMs)
    }

    @Test fun aGameWithoutAchievementsSendsAnEmptyArray() {
        val p = RetroAchievementsClient.parseGameProgress(Json.parseToJsonElement("""{"ID": 5, "Title": "X", "Achievements": []}"""))!!
        assertEquals(0, p.total)
        assertEquals(0f, p.completion)
        assertNull("no game id → nothing", RetroAchievementsClient.parseGameProgress(Json.parseToJsonElement("{}")))
    }

    @Test fun parsesConsoleGameListsWithHashes() {
        val list = RetroAchievementsClient.parseGameList(Json.parseToJsonElement(LIST_JSON))
        assertEquals(listOf(1, 2), list.map { it.id })
        assertEquals(listOf("0d2a4d2ba3b8e8e6c4d7f1a2b3c4d5e6"), list.first().hashes) // lower-cased, bad ones dropped
        assertEquals(36, list.first().achievements)
    }

    @Test fun gameRequestsCarryTheKeyOnlyToRetroAchievements() = runBlocking {
        val http = FakeHttp(
            listOf(
                RetroAchievementsClient.GAME_PROGRESS to FakeHttp.json(200, PROGRESS_JSON),
                RetroAchievementsClient.GAME_LIST to FakeHttp.json(200, LIST_JSON),
            ),
        )
        val client = RetroAchievementsClient(http)
        assertTrue(client.fetchGameProgress("Max Milyin", KEY, 1) is RaGameResult.Ok)
        assertTrue(client.fetchGameList(KEY, 1) is RaListResult.Ok)
        val (progress, list) = http.requests
        assertTrue(progress, progress.startsWith("https://retroachievements.org/API/API_GetGameInfoAndUserProgress.php?u=Max+Milyin&y=$KEY&g=1&a=1"))
        assertTrue(list, list.startsWith("https://retroachievements.org/API/API_GetGameList.php?i=1&h=1&y=$KEY"))
        // Errors carry fixed text, never the URL.
        val failing = RetroAchievementsClient(FakeHttp(listOf(RetroAchievementsClient.GAME_PROGRESS to HttpResponse(500, ByteArray(0), null))))
        val error = failing.fetchGameProgress("u", KEY, 1) as RaGameResult.Error
        assertFalse(error.toString().contains(KEY))
        assertEquals("https://media.retroachievements.org/Badge/250336.png", RetroAchievementsClient.badgeUrl("250336"))
        assertEquals("https://media.retroachievements.org/Badge/250336_lock.png", RetroAchievementsClient.badgeUrl("../250336", locked = true))
    }

    // endregion

    // region Matching

    private fun hashes(md5: String? = null, dataMd5: String? = null) = GameHashEntity(
        gameId = 1, fileSize = 1, fileModified = 1, source = "FILE", crc32 = null, md5 = md5, sha1 = null,
        dataCrc32 = null, dataMd5 = dataMd5, dataSha1 = null, headerBytes = 0, entryName = null, contentSize = null,
        skipReason = null, detail = null, hashedAt = 0,
    )

    @Test fun matchesCartridgesByTheirFullMd5() {
        val games = listOf(RaGameEntry(1, "Sonic the Hedgehog", 36, listOf(MD5_A)), RaGameEntry(2, "Sonic 2", 30, listOf(MD5_B)))
        val hit = RaMatcher.match("Whatever", "sonic.md", hashes(md5 = MD5_B.uppercase()), RaConsoles.of("megadrive")!!, games)
        assertEquals(2 to RaMatchKind.HASH, hit!!.first.id to hit.second)
    }

    @Test fun nesAndSnesUseTheHeaderlessHashFirst() {
        val games = listOf(RaGameEntry(7, "Super Mario Bros.", 20, listOf(MD5_A)))
        val nes = RaConsoles.of("nes")!!
        assertEquals(RaConsoles.HashMethod.HEADERLESS, nes.method)
        assertEquals(7, RaMatcher.match("x", "smb.nes", hashes(md5 = MD5_B, dataMd5 = MD5_A), nes, games)!!.first.id)
        assertEquals(listOf(MD5_A, MD5_B), RaMatcher.candidates("smb.nes", hashes(md5 = MD5_B, dataMd5 = MD5_A), nes.method).toList())
    }

    @Test fun arcadeSetsAreHashedByTheirName() {
        val name = RaMatcher.md5("1941") // rcheevos hashes the set name, not the zip
        val games = listOf(RaGameEntry(27, "1941: Counter Attack", 20, listOf(name)))
        val hit = RaMatcher.match("1941 Counter Attack", "1941.zip", null, RaConsoles.of("arcade")!!, games)!!
        assertEquals(27, hit.first.id)
        assertEquals(RaMatchKind.SET_NAME, hit.second)
    }

    @Test fun discSystemsFallBackToAnExactTitleAndSkipHacksAndSubsets() {
        val psx = RaConsoles.of("psx")!!
        val games = listOf(
            RaGameEntry(1, "~Hack~ Final Fantasy VII", 10, emptyList()),
            RaGameEntry(2, "Final Fantasy VII [Subset - Bonus]", 10, emptyList()),
            RaGameEntry(3, "Final Fantasy VII", 80, emptyList()),
            RaGameEntry(4, "Legend of Dragoon, The", 50, emptyList()),
        )
        assertEquals(3 to RaMatchKind.TITLE, RaMatcher.match("Final Fantasy VII", "ff7.chd", null, psx, games)!!.let { it.first.id to it.second })
        assertEquals(4, RaMatcher.match("The Legend of Dragoon", "lod.chd", null, psx, games)!!.first.id)
        assertNull(RaMatcher.match("Final Fantasy VIII", "ff8.chd", null, psx, games))
        // A cartridge not fingerprinted yet may match by title; one whose hash isn't listed may not.
        val md = RaConsoles.of("megadrive")!!
        val sonic = listOf(RaGameEntry(1, "Sonic the Hedgehog", 36, listOf(MD5_A)))
        assertEquals(RaMatchKind.TITLE, RaMatcher.match("Sonic the Hedgehog", "s.md", null, md, sonic)!!.second)
        assertNull(RaMatcher.match("Sonic the Hedgehog", "s.md", hashes(md5 = MD5_B), md, sonic))
        // A hash match beats a title match on cartridge systems.
        val gba = RaConsoles.of("gba")!!
        val both = listOf(RaGameEntry(10, "Metroid Fusion", 1, emptyList()), RaGameEntry(11, "Other", 1, listOf(MD5_A)))
        assertEquals(11, RaMatcher.match("Metroid Fusion", "mf.gba", hashes(md5 = MD5_A), gba, both)!!.first.id)
    }

    @Test fun systemsWithoutAchievementsHaveNoConsole() {
        for (id in listOf("n3ds", "wii", "switch", "scummvm")) assertNull(id, RaConsoles.of(id))
        assertEquals(27, RaConsoles.of("arcade")!!.id)
        assertEquals(12, RaConsoles.of("psx")!!.id)
    }

    // endregion

    // region Repository

    private fun game(platform: String = "megadrive", file: String = "Sonic.md", size: Long = 524_288, id: Long = 1) = GameEntity(
        id = id, path = "/roms/$platform/$file", platformId = platform, title = "Sonic the Hedgehog", fileName = file,
        sizeBytes = size, lastModified = 1, uuid = java.util.UUID.nameUUIDFromBytes("$platform$file".toByteArray()).toString(),
    )

    private fun onlineHttp() = FakeHttp(
        listOf(
            RetroAchievementsClient.GAME_LIST to FakeHttp.json(200, LIST_JSON),
            RetroAchievementsClient.GAME_PROGRESS to FakeHttp.json(200, PROGRESS_JSON),
        ),
    ).apply {
        download = { _, dest -> dest.writeBytes(PNG); DownloadResult(200, PNG.size.toLong(), "image/png") }
    }

    private fun repo(http: FakeHttp, now: Long = 1_000, md5: String? = "0D2A4D2BA3B8E8E6C4D7F1A2B3C4D5E6") = AchievementsRepository(
        client = RetroAchievementsClient(http, clock = { now }),
        credentials = credentials,
        layout = { layout },
        hashes = { hashes(md5 = md5) },
        clock = { now },
    )

    @Test fun loadsProgressByHashAndCachesItInTheXrossFolderWithoutTheKey() = runBlocking {
        val http = onlineHttp()
        val state = repo(http).load(game())
        val ready = state as AchievementsState.Ready
        assertEquals(RaMatchKind.HASH, ready.matchedBy)
        assertEquals(3, ready.progress.total)
        assertFalse(ready.offline)

        val ra = File(layout.data, "ra")
        assertTrue(File(ra, "console-1.json").isFile)
        assertTrue(File(ra, "game-1.json").isFile)
        assertTrue(File(ra, "matches.json").isFile)
        // The key is never written anywhere in the Xross folder.
        layout.root.walkTopDown().filter { it.isFile }.forEach { f ->
            assertFalse("key leaked into ${f.relativeTo(layout.root)}", f.readBytes().toString(Charsets.ISO_8859_1).contains(KEY))
        }
    }

    @Test fun reusesTheConsoleListAndTheMatchAndServesTheOfflineCopy() = runBlocking {
        val http = onlineHttp()
        val repo = repo(http)
        repo.load(game())
        val lists = http.requests.count { it.contains(RetroAchievementsClient.GAME_LIST) }
        // Another game of the same console: the list comes from disk.
        repo.load(game(file = "Sonic2.md", id = 2), maxAgeMs = 0)
        assertEquals(lists, http.requests.count { it.contains(RetroAchievementsClient.GAME_LIST) })

        // Offline later: the cached progress is shown, flagged as offline.
        val offline = repo(FakeHttp(emptyList()), now = 2_000).load(game(), maxAgeMs = 0) as AchievementsState.Ready
        assertTrue(offline.offline)
        assertEquals(3, offline.progress.total)
    }

    @Test fun aGameWithoutASetIsRecognizedAndOldListsAreFetchedAgain() = runBlocking {
        // A list cached by an earlier version (only games with achievements, version 1).
        val ra = File(layout.data, "ra").apply { mkdirs() }
        File(ra, "console-1.json").writeText("""{"version": 1, "consoleId": 1, "fetchedAtMs": 999, "games": []}""")
        val http = FakeHttp(
            listOf(
                RetroAchievementsClient.GAME_LIST to FakeHttp.json(200, LIST_JSON),
                RetroAchievementsClient.GAME_PROGRESS to FakeHttp.json(200, """{"ID": 1, "Title": "Sonic the Hedgehog", "Achievements": []}"""),
            ),
        )
        val ready = repo(http).load(game()) as AchievementsState.Ready
        assertTrue("the old list was refetched", http.requests.any { it.contains(RetroAchievementsClient.GAME_LIST) })
        assertEquals(0, ready.progress.total) // recognized, no set yet: "no achievements yet", not "not recognized"
    }

    @Test fun reportsNotLinkedUnsupportedAndNoMatch() = runBlocking {
        assertTrue(repo(onlineHttp()).load(game(platform = "n3ds", file = "mh4u.3ds")) is AchievementsState.Unsupported)
        assertEquals(AchievementsState.NoMatch, repo(onlineHttp(), md5 = "ffffffffffffffffffffffffffffffff").load(game(file = "Hack.md")))
        credentials.update { it.copy(retroAchievementsKey = "") }
        assertEquals(AchievementsState.NotLinked, repo(onlineHttp()).load(game()))
    }

    @Test fun aChangedFileIsMatchedAgain() = runBlocking {
        val http = onlineHttp()
        repo(http).load(game())
        val before = http.requests.size
        // Same file, same size: the stored match is used (only progress is fetched).
        repo(http).load(game(), maxAgeMs = 0)
        assertEquals(before + 1, http.requests.size)
        // A different dump (size changed) with an unknown hash no longer matches.
        assertEquals(AchievementsState.NoMatch, repo(http, md5 = "ffffffffffffffffffffffffffffffff").load(game(size = 1_048_576), maxAgeMs = 0))
    }

    @Test fun badgesDownloadOnceIntoTheMediaFolderUnderNomedia() = runBlocking {
        val http = onlineHttp()
        val repo = repo(http)
        val file = repo.badgeFile("250336")!!
        assertEquals(File(layout.media, "ra/badges/250336.png"), file)
        assertTrue(File(layout.media, ".nomedia").isFile)
        val downloads = http.requests.size
        assertEquals(file, repo.badgeFile("250336"))
        assertEquals(downloads, http.requests.size)
        assertEquals(File(layout.media, "ra/badges/x.png"), repo.badgeFile("../x"))
        // Unlinking clears the per-game caches (public data, but tied to the account).
        repo.load(game())
        repo.clear()
        assertFalse(File(layout.data, "ra/game-1.json").exists())
    }

    // endregion

    // region Badge grid

    private fun view(n: Int, index: Int = 0): AchievementsViewState {
        val list = (1..n).map { RaAchievement(id = it, title = "A$it", displayOrder = it) }
        return AchievementsViewState(1, "u", "Game", "MD", AchievementsState.Ready(RaGameProgress(1, "Game", achievements = list), RaMatchKind.HASH), index)
    }

    @Test fun gridMovesByCellAndRowAndStopsAtEdges() {
        val cols = AchievementsViewState.COLUMNS
        var v = view(20)
        v = AchievementsGrid.onAction(v, NavAction.RIGHT).state
        assertEquals(1, v.index)
        v = AchievementsGrid.onAction(v, NavAction.DOWN).state
        assertEquals(1 + cols, v.index)
        assertEquals(1 + cols * 2, AchievementsGrid.onAction(view(30, 1 + cols), NavAction.DOWN).state.index) // row 3 exists
        assertEquals(19, AchievementsGrid.onAction(view(20, 18), NavAction.RIGHT).state.index)
        assertEquals(19, AchievementsGrid.onAction(view(20, 19), NavAction.RIGHT).state.index) // last stays
        assertEquals(3, AchievementsGrid.onAction(view(20, 3), NavAction.UP).state.index) // top row stays
        assertEquals(24, AchievementsGrid.onAction(view(25, 17), NavAction.DOWN).state.index) // short last row: clamp
        assertEquals(AchievementsEffect.Close, AchievementsGrid.onAction(v, NavAction.BACK).effect)
        assertEquals(AchievementsEffect.Refresh, AchievementsGrid.onAction(v, NavAction.OPTIONS).effect)
        assertEquals(5, AchievementsGrid.tap(v, 5).index)
        assertEquals(v, AchievementsGrid.tap(v, 99))
    }

    // endregion

    companion object {
        const val MD5_A = "0d2a4d2ba3b8e8e6c4d7f1a2b3c4d5e6"
        const val MD5_B = "11111111111111111111111111111111"
        private val PNG = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2)

        val PROGRESS_JSON = """
            {"ID": 1, "Title": "Sonic the Hedgehog", "ConsoleID": 1, "ConsoleName": "Genesis/Mega Drive",
             "ImageIcon": "/Images/085573.png", "ImageBoxArt": "/Images/051872.png", "NumDistinctPlayers": "27080",
             "Achievements": {
               "11": {"ID": 11, "Title": "Chaos", "Description": "Get all emeralds", "Points": 25, "TrueRatio": 90,
                      "BadgeName": "250338", "DisplayOrder": 3, "NumAwarded": 900, "type": null},
               "9": {"ID": 9, "Title": "That Was Easy", "Description": "Beat Green Hill Act 1", "Points": "5",
                     "BadgeName": "250336", "DisplayOrder": 1, "NumAwarded": 25000, "type": "progression",
                     "DateEarned": "2023-01-02 10:00:00", "DateEarnedHardcore": "2023-01-02 10:00:00"},
               "10": {"ID": 10, "Title": "Speed", "Description": "Fast run", "Points": 10, "BadgeName": "250337",
                      "DisplayOrder": 2, "DateEarned": "2023-02-01 09:00:00"}
             },
             "NumAwardedToUser": 2, "UserCompletion": "66.67%"}
        """.trimIndent()

        val LIST_JSON = """
            [{"Title": "Sonic the Hedgehog", "ID": 1, "ConsoleID": 1, "NumAchievements": 36,
              "Hashes": ["0D2A4D2BA3B8E8E6C4D7F1A2B3C4D5E6", "not-a-hash"]},
             {"Title": "Sonic the Hedgehog 2", "ID": "2", "NumAchievements": 30, "Hashes": []},
             {"ID": 0, "Title": "broken"}, {"Title": ""}]
        """.trimIndent()
    }
}
