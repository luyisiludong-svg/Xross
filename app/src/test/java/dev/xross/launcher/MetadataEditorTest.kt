package dev.xross.launcher

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.xross.launcher.audio.UiSounds
import dev.xross.launcher.data.GameEntity
import dev.xross.launcher.data.GameHashEntity
import dev.xross.launcher.data.GameMetadataEntity
import dev.xross.launcher.data.XrossDatabase
import dev.xross.launcher.input.NavAction
import dev.xross.launcher.model.ArtKind
import dev.xross.launcher.scrape.AssetCache
import dev.xross.launcher.scrape.DownloadResult
import dev.xross.launcher.scrape.GameMediaEditor
import dev.xross.launcher.scrape.MediaOffer
import dev.xross.launcher.scrape.MediaOffers
import dev.xross.launcher.scrape.OfferResult
import dev.xross.launcher.scrape.ProviderId
import dev.xross.launcher.scrape.ProviderResult
import dev.xross.launcher.scrape.RateLimiter
import dev.xross.launcher.scrape.ScrapeOrchestrator
import dev.xross.launcher.scrape.ScrapePrefs
import dev.xross.launcher.scrape.ScrapeProvider
import dev.xross.launcher.scrape.ScrapeRequest
import dev.xross.launcher.scrape.ScrapedGame
import dev.xross.launcher.scrape.ScrapedMedia
import dev.xross.launcher.scrape.providers.ScreenScraperCredentials
import dev.xross.launcher.scrape.providers.ScreenScraperProvider
import dev.xross.launcher.scrape.providers.SteamGridDbProvider
import dev.xross.launcher.state.Command
import dev.xross.launcher.state.EditorAction
import dev.xross.launcher.state.EditorRow
import dev.xross.launcher.state.MediaPickerState
import dev.xross.launcher.state.MediaSlotStatus
import dev.xross.launcher.state.MetadataEditor
import dev.xross.launcher.state.MetadataEditorController
import dev.xross.launcher.state.PickerChoice
import dev.xross.launcher.state.UiEffect
import dev.xross.launcher.storage.Fingerprint
import dev.xross.launcher.storage.HashSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Options ▸ Metadata & Media (0.2.2.0 § 2): offers, pins, the v6 columns, the editor and its picker. */
@RunWith(RobolectricTestRunner::class)
class MetadataEditorTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, XrossDatabase::class.java).allowMainThreadQueries().build()

    @get:Rule val tmp = TemporaryFolder()

    @After fun close() = db.close()

    private val noWait = RateLimiter(0, clock = { 0L }, sleep = {})
    private val request = ScrapeRequest(
        gameId = 1, uuid = "u", title = "Chrono Trigger", fileName = "ct.sfc", platformId = "snes", sizeBytes = 4096,
        crc32 = "2d206bf7", prefs = ScrapePrefs(region = "eu"),
    )

    // region Providers: every offer is kept

    private val jeu = """
        {"response": {"jeu": {"id": "3398", "noms": [{"region": "us", "text": "Chrono Trigger"}], "medias": [
            {"type": "box-2D", "region": "us", "url": "https://media.example/box2d-us?x=1", "format": "png"},
            {"type": "box-2D", "region": "eu", "url": "https://media.example/box2d-eu?x=1", "format": "png"},
            {"type": "box-2D", "region": "jp", "url": "https://media.example/box2d-jp?x=1", "format": "png"},
            {"type": "box-3D", "region": "eu", "url": "https://media.example/box3d-eu?x=1", "format": "png"},
            {"type": "wheel", "region": "wor", "url": "https://media.example/wheel?x=1", "format": "png"},
            {"type": "wheel-hd", "region": "wor", "url": "https://media.example/wheel-hd?x=1", "format": "png"},
            {"type": "video", "region": "wor", "url": "https://media.example/video?x=1", "format": "mp4"}
        ]}}}
    """.trimIndent()

    @Test fun screenScraperKeepsEveryOfferWithLabels() = runBlocking {
        val http = FakeHttp(listOf("jeuInfos.php" to FakeHttp.json(200, jeu)))
        val provider = ScreenScraperProvider(http, { ScreenScraperCredentials("dev", "pw", "Xross") }, noWait)

        val boxes = (provider.mediaOffers(request, ArtKind.BOX_2D) as OfferResult.Offers).offers
        // All three regions, the user's (eu) first.
        assertEquals(listOf("Box · EU", "Box · US", "Box · JP"), boxes.map { it.label })
        assertTrue(boxes.all { it.provider == ProviderId.SCREENSCRAPER && it.thumbnailUrl!!.contains("maxwidth=") })

        // A second slot of the same game comes from the same answer: no new request.
        val logos = (provider.mediaOffers(request, ArtKind.WHEEL) as OfferResult.Offers).offers
        assertEquals(listOf("Logo HD · WOR", "Logo · WOR"), logos.map { it.label })
        val video = (provider.mediaOffers(request, ArtKind.VIDEO) as OfferResult.Offers).offers.single()
        assertNull(video.thumbnailUrl)
        assertEquals(1, http.requests.size)

        // Kinds ScreenScraper doesn't have cost nothing.
        assertEquals(OfferResult.NONE, provider.mediaOffers(request, ArtKind.GRID))
        assertEquals(1, http.requests.size)
    }

    @Test fun screenScraperSaysWhyThereAreNoOffers() = runBlocking {
        val quota = ScreenScraperProvider(FakeHttp(listOf("jeuInfos.php" to FakeHttp.json(430, "{}"))), { ScreenScraperCredentials("dev", "pw", "Xross") }, noWait)
        assertEquals(OfferResult.Unavailable(OfferResult.SCREENSCRAPER_QUOTA), quota.mediaOffers(request, ArtKind.BOX_2D))
        val missing = ScreenScraperProvider(FakeHttp(listOf("jeuInfos.php" to FakeHttp.json(404, ""))), { ScreenScraperCredentials("dev", "pw", "Xross") }, noWait)
        assertEquals(OfferResult.Unavailable(OfferResult.NO_MATCH), missing.mediaOffers(request, ArtKind.BOX_2D))
    }

    @Test fun steamGridDbListsTheWholeKind() = runBlocking {
        val http = FakeHttp(
            listOf(
                "/heroes/game/9" to FakeHttp.json(
                    200,
                    """{"success": true, "data": [
                        {"id": 1, "url": "https://cdn.sgdb/h1.png", "thumb": "https://cdn.sgdb/t1.png", "width": 1920, "height": 620, "style": "alternate"},
                        {"id": 2, "url": "https://cdn.sgdb/h2.png", "thumb": "https://cdn.sgdb/t2.png", "width": 3840, "height": 1240, "style": "blurred"},
                        {"id": 3, "url": "http://insecure/h3.png"}
                    ]}""",
                ),
            ),
        )
        val provider = SteamGridDbProvider(http, { "key" }, noWait)
        val heroes = (provider.mediaOffers(request.copy(steamGridDbId = "9"), ArtKind.HERO) as OfferResult.Offers).offers
        assertEquals(listOf("Alternate · 1920×620", "Blurred · 3840×1240"), heroes.map { it.label })
        assertEquals("https://cdn.sgdb/t1.png", heroes.first().thumbnailUrl)
        assertTrue(http.requests.single().endsWith("/heroes/game/9?types=static"))

        assertEquals(OfferResult.NONE, provider.mediaOffers(request, ArtKind.BOX_3D))
        assertEquals(
            OfferResult.Unavailable(OfferResult.NO_STEAMGRIDDB_KEY),
            SteamGridDbProvider(FakeHttp(), { "" }, noWait).mediaOffers(request, ArtKind.GRID),
        )
    }

    @Test fun steamGridDbLabelsWithoutAStyleOrSize() {
        val offers = SteamGridDbProvider.parseOffers(JSONArray("""[{"url": "https://a/1.png"}]"""), ArtKind.WHEEL)
        assertEquals("Image 1", offers.single().label)
    }

    // endregion

    // region Database v6

    @get:Rule
    val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), XrossDatabase::class.java)

    @Test fun migration5To6AddsTheEditorColumnsAndKeepsRows() {
        migrations.createDatabase("v5.db", 5).use { v5 ->
            v5.execSQL(
                "INSERT INTO games (id, path, platformId, title, fileTitle, uuid, fileName, sizeBytes, lastModified, favorite, lastPlayed, playCount, playTimeMs) " +
                    "VALUES (1, '/r/snes/ct.sfc', 'snes', 'Chrono Trigger', 'ct', '123e4567-e89b-42d3-a456-426614174000', 'ct.sfc', 10, 20, 0, 0, 0, 0)",
            )
            v5.execSQL("INSERT INTO game_metadata (gameId, titleOverride, locked, synopsis, scrapeStatus) VALUES (1, 'Mine', 1, 'Time travel.', 'OK')")
        }
        migrations.runMigrationsAndValidate("v5.db", 6, true, *XrossDatabase.MIGRATIONS).use { v6 ->
            v6.query("SELECT titleOverride, locked, synopsis, scrapeStatus, pinnedArt, synopsisOverride, mediaStatus FROM game_metadata").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("Mine", c.getString(0))
                assertEquals(1, c.getInt(1))
                assertEquals("Time travel.", c.getString(2))
                assertEquals("OK", c.getString(3))
                assertTrue(c.isNull(4))
                assertTrue(c.isNull(5))
                // Not checked yet: the first media check after the upgrade fills it.
                assertTrue(c.isNull(6))
            }
        }
    }

    @Test fun pinsRoundTripAndMaskBits() {
        assertEquals(setOf(ArtKind.HERO, ArtKind.BOX_2D), ArtKind.parsePins("HERO, BOX_2D,NOPE"))
        assertEquals("BOX_2D,HERO", ArtKind.formatPins(setOf(ArtKind.HERO, ArtKind.BOX_2D)))
        assertNull(ArtKind.formatPins(emptySet()))
        assertEquals(setOf(ArtKind.WHEEL), ArtKind.inMask(ArtKind.WHEEL.bit))
    }

    // endregion

    // region Scraping and the media editor

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 1, 2, 3)

    private fun http() = FakeHttp().apply {
        download = { url, dest ->
            val bytes = if (url.contains("bad")) "<html>".toByteArray() + ByteArray(12) else png
            dest.writeBytes(bytes)
            DownloadResult(200, bytes.size.toLong(), "image/png")
        }
    }

    /** Answers a fixed scrape, counts offer requests. */
    private class Fake(override val id: ProviderId, val media: List<ScrapedMedia>, val offers: List<MediaOffer> = emptyList()) : ScrapeProvider {
        var offerCalls = 0
        override fun isConfigured() = true
        override suspend fun lookup(request: ScrapeRequest) = ProviderResult.Found(ScrapedGame(id, "1", title = "Chrono Trigger", media = media))
        override suspend fun mediaOffers(request: ScrapeRequest, kind: ArtKind): OfferResult {
            offerCalls++
            return OfferResult.Offers(offers.filter { it.kind == kind })
        }
    }

    private fun insertGame(path: String = "/r/snes/ct.sfc"): GameEntity = runBlocking {
        db.games().insertAll(listOf(GameEntity(path = path, platformId = "snes", title = "ct", fileName = path.substringAfterLast('/'), sizeBytes = 4096, lastModified = 1)))
        val game = db.games().getAll().first { it.path == path }
        db.hashes().upsert(GameHashEntity.from(game, Fingerprint(HashSource.FILE, crc32 = "2d206bf7", sha1 = "aa")))
        game
    }

    private val heroOffer = MediaOffer(ArtKind.HERO, "https://cdn/hero-picked", "https://cdn/hero-thumb", ProviderId.STEAMGRIDDB, "Alternate")

    private fun setup(provider: Fake = Fake(ProviderId.SCREENSCRAPER, listOf(ScrapedMedia(ArtKind.HERO, "https://m/hero", "png"), ScrapedMedia(ArtKind.BOX_2D, "https://m/box", "png")), listOf(heroOffer))):
        Triple<ScrapeOrchestrator, GameMediaEditor, AssetCache> {
        val cache = AssetCache(tmp.newFolder())
        val http = http()
        val scraper = ScrapeOrchestrator(db, cache, http, listOf(provider), { ScrapePrefs() }, clock = { 7_000L })
        return Triple(scraper, GameMediaEditor(scraper) { tmp.root.resolve("thumbs") }, cache)
    }

    @Test fun aPickedKindSurvivesScrapeAgain() = runBlocking {
        val game = insertGame()
        val (scraper, editor, cache) = setup()
        scraper.scrapeOne(game.id)
        assertEquals(ArtKind.HERO.bit or ArtKind.BOX_2D.bit, db.metadata().get(game.id)!!.mediaStatus)

        assertNull(editor.pick(game.id, heroOffer))
        val picked = cache.file(game.uuid, ArtKind.HERO)!!.lastModified()
        val meta = db.metadata().get(game.id)!!
        assertEquals(setOf(ArtKind.HERO), ArtKind.parsePins(meta.pinnedArt))
        assertEquals(MediaSlotStatus.CHOSEN, MetadataEditor.slotStatus(ArtKind.HERO, meta))
        assertEquals(MediaSlotStatus.SCRAPED, MetadataEditor.slotStatus(ArtKind.BOX_2D, meta))

        // Scrape again keeps the pin and doesn't touch the chosen hero.
        Thread.sleep(20)
        scraper.scrapeOne(game.id)
        assertEquals(setOf(ArtKind.HERO), ArtKind.parsePins(db.metadata().get(game.id)!!.pinnedArt))
        assertEquals(picked, cache.file(game.uuid, ArtKind.HERO)!!.lastModified())
    }

    @Test fun pinnedKindsAreNotDownloadedOnAReplaceScrape() = runBlocking {
        val game = insertGame()
        val http = http()
        val cache = AssetCache(tmp.newFolder())
        val scraper = ScrapeOrchestrator(
            db, cache, http,
            listOf(Fake(ProviderId.SCREENSCRAPER, listOf(ScrapedMedia(ArtKind.HERO, "https://m/hero", "png"), ScrapedMedia(ArtKind.BOX_2D, "https://m/box", "png")))),
            { ScrapePrefs() },
        )
        db.metadata().upsert(GameMetadataEntity(game.id, pinnedArt = "HERO"))
        scraper.scrapeOne(game.id)
        assertEquals(listOf("https://m/box"), http.requests)
        assertNull(cache.file(game.uuid, ArtKind.HERO))
        // Mark stays: a removed slot stays empty.
        assertEquals(MediaSlotStatus.REMOVED, MetadataEditor.slotStatus(ArtKind.HERO, db.metadata().get(game.id)))
    }

    @Test fun removeAndResetMedia() = runBlocking {
        val game = insertGame()
        val (scraper, editor, cache) = setup()
        scraper.scrapeOne(game.id)
        editor.remove(game.id, ArtKind.BOX_2D)
        assertNull(cache.file(game.uuid, ArtKind.BOX_2D))
        var meta = db.metadata().get(game.id)!!
        assertEquals(MediaSlotStatus.REMOVED, MetadataEditor.slotStatus(ArtKind.BOX_2D, meta))
        assertEquals(ArtKind.HERO.bit, meta.mediaStatus)

        editor.resetMedia(game.id)
        meta = db.metadata().get(game.id)!!
        assertNull(meta.pinnedArt)
        assertEquals(MediaSlotStatus.NONE, MetadataEditor.slotStatus(ArtKind.BOX_2D, meta))
        // The tile watches scrapedAt to reload art.
        assertEquals(7_000L, meta.scrapedAt)
    }

    @Test fun aPickThatIsNotAnImageIsRefusedAndNothingIsPinned() = runBlocking {
        val game = insertGame()
        val (_, editor, cache) = setup()
        val problem = editor.pick(game.id, heroOffer.copy(url = "https://cdn/bad"))
        assertNotNull(problem)
        assertNull(cache.file(game.uuid, ArtKind.HERO))
        assertNull(db.metadata().get(game.id)?.pinnedArt)
    }

    @Test fun offersAreCachedForTheSessionAndEmptyOnesSayWhy() = runBlocking {
        val game = insertGame()
        val provider = Fake(ProviderId.STEAMGRIDDB, emptyList(), listOf(heroOffer))
        val (_, editor, _) = setup(provider)
        assertEquals(MediaOffers.Ready(listOf(heroOffer)), editor.offers(game.id, ArtKind.HERO))
        editor.offers(game.id, ArtKind.HERO)
        assertEquals(1, provider.offerCalls)
        assertEquals(MediaOffers.Empty(GameMediaEditor.NOTHING_OFFERED), editor.offers(game.id, ArtKind.WHEEL))
        editor.clearSession()
        editor.offers(game.id, ArtKind.HERO)
        assertEquals(3, provider.offerCalls)
    }

    @Test fun backfillFillsMediaStatusOnce() = runBlocking {
        val game = insertGame()
        val (_, editor, cache) = setup()
        cache.register(game)
        cache.store(game.uuid, ArtKind.WHEEL, "png") { it.writeBytes(png) }
        assertEquals(1, editor.backfillStatus())
        assertEquals(ArtKind.WHEEL.bit, db.metadata().get(game.id)!!.mediaStatus)
        assertEquals(0, editor.backfillStatus())
        assertEquals(MediaSlotStatus.FROM_FOLDER, MetadataEditor.slotStatus(ArtKind.WHEEL, db.metadata().get(game.id)))
    }

    // endregion

    // region Editor and picker state

    private fun game(id: Long) = GameEntity(id = id, path = "/r/$id", platformId = "snes", title = "Game $id", fileName = "$id.sfc", sizeBytes = 1, lastModified = 1)

    @Test fun pagingKeepsTheFocusedRow() {
        val ids = listOf(1L, 2L, 3L)
        val first = MetadataEditor.build(game(1), null, null, 0, ids, videos = false)
        assertFalse(first.rows.any { it is EditorRow.Media && it.kind == ArtKind.VIDEO })
        val onHero = first.moveTo(first.rows.indexOfFirst { it.key == ArtKind.HERO.name })
        // The next game has a Reset metadata row the first lacked: the cursor still lands on Hero.
        val second = MetadataEditor.build(game(2), null, GameMetadataEntity(2, titleOverride = "X"), 0, ids, videos = false, previous = onHero, pageDirection = 1)
        assertEquals(ArtKind.HERO.name, second.focused!!.key)
        assertEquals(1, second.position)
        assertEquals(3, second.count)
        assertTrue(second.rows.any { (it as? EditorRow.Action)?.action == EditorAction.RESET_METADATA })
        assertEquals(3L, MetadataEditor.neighbor(second, +1))
        assertNull(MetadataEditor.neighbor(second.copy(gameId = 3), +1))
        assertNull(MetadataEditor.neighbor(first, -1))
    }

    @Test fun resetMediaShowsOnlyWithPinsAndVideoOnlyWithVideos() {
        val plain = MetadataEditor.build(game(1), null, null, 0, emptyList(), videos = true)
        assertTrue(plain.rows.any { it is EditorRow.Media && it.kind == ArtKind.VIDEO })
        assertFalse(plain.rows.any { (it as? EditorRow.Action)?.action == EditorAction.RESET_MEDIA })
        val pinned = MetadataEditor.build(game(1), null, GameMetadataEntity(1, pinnedArt = "WHEEL"), 0, emptyList(), videos = true)
        assertEquals(EditorAction.RESET_MEDIA, (pinned.rows.last() as EditorRow.Action).action)
        assertEquals(1, pinned.count)
    }

    private fun offers(n: Int) = (0 until n).map { MediaOffer(ArtKind.BOX_2D, "https://o/$it", null, ProviderId.SCREENSCRAPER, "$it") }

    @Test fun pickerGridNavigation() {
        // Six offers in rows of four: 0 1 2 3 / 4 5, then Use a file…, Remove.
        var p = MediaPickerState(ArtKind.BOX_2D, "Box art", hasFile = true).withOffers(MediaOffers.Ready(offers(6)))
        assertEquals(8, p.count)
        p = p.move(NavAction.RIGHT).move(NavAction.RIGHT).move(NavAction.RIGHT)
        assertEquals(3, p.index)
        assertEquals(3, p.move(NavAction.RIGHT).index) // the row's end
        p = p.move(NavAction.DOWN)
        assertEquals(5, p.index) // short last row: its last cell
        p = p.move(NavAction.DOWN)
        assertEquals(PickerChoice.UseFile, p.focused)
        p = p.move(NavAction.DOWN)
        assertEquals(PickerChoice.Remove, p.focused)
        assertEquals(p, p.move(NavAction.DOWN))
        p = p.move(NavAction.UP).move(NavAction.UP)
        assertEquals(4, p.index) // back into the grid, first cell of the last row
        p = p.move(NavAction.UP)
        assertEquals(0, p.index)
        assertEquals(PickerChoice.Offer(offers(6)[0]), p.focused)
    }

    @Test fun anEmptyPickerWaitsOnUseAFile() {
        val p = MediaPickerState(ArtKind.HERO, "Hero background").withOffers(MediaOffers.Empty(OfferResult.NO_STEAMGRIDDB_KEY))
        assertFalse(p.loading)
        assertEquals(OfferResult.NO_STEAMGRIDDB_KEY, p.reason)
        assertEquals(listOf(PickerChoice.UseFile), p.extras)
        assertEquals(PickerChoice.UseFile, p.focused)
    }

    // endregion

    // region Controller

    @Test fun pagingDoesNotAskForOffersAndOpeningAPickerDoes() = runBlocking {
        val a = insertGame("/r/snes/a.sfc")
        val b = insertGame("/r/snes/b.sfc")
        val provider = Fake(ProviderId.STEAMGRIDDB, emptyList(), listOf(heroOffer))
        val (_, media, _) = setup(provider)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val effects = ArrayList<UiEffect>()
        val commands = ArrayList<Command>()
        val followed = ArrayList<Long>()
        var backTo: Long? = null
        val controller = MetadataEditorController(
            scope = scope, db = db, media = media, sounds = UiSounds(context), artVersion = MutableStateFlow(0L),
            videos = { false }, open = { null }, scratch = { tmp.root },
            host = object : MetadataEditorController.Host {
                override fun toast(message: String) = Unit
                override fun fail(message: String) = Unit
                override fun refuse() = Unit
                override fun emit(effect: UiEffect) { effects += effect }
                override fun run(command: Command) { commands += command }
                override fun siblings() = listOf(a.id, b.id)
                override fun follow(gameId: Long) { followed += gameId }
                override fun reopenOptions(gameId: Long) { backTo = gameId }
            },
        )
        try {
            controller.open(a.id)
            await { controller.state.value?.gameId == a.id }
            // Down to the Hero row, then page forward and back.
            val hero = controller.state.value!!.rows.indexOfFirst { it.key == ArtKind.HERO.name }
            repeat(hero) { controller.onAction(NavAction.DOWN) }
            controller.onAction(NavAction.NEXT_CATEGORY)
            await { controller.state.value?.gameId == b.id }
            assertEquals(ArtKind.HERO.name, controller.state.value!!.focused!!.key)
            assertEquals(listOf(b.id), followed)
            controller.onAction(NavAction.NEXT_CATEGORY) // the end of the list: stays
            controller.onAction(NavAction.PREV_CATEGORY)
            await { controller.state.value?.gameId == a.id }
            assertEquals(0, provider.offerCalls)

            // Confirm on a slot opens its picker and asks once.
            controller.onAction(NavAction.CONFIRM)
            await { controller.state.value?.picker?.loading == false }
            assertEquals(1, provider.offerCalls)
            assertEquals(listOf(heroOffer), controller.state.value!!.picker!!.offers)
            // Paging is refused while a picker is open.
            controller.onAction(NavAction.NEXT_CATEGORY)
            assertEquals(a.id, controller.state.value!!.gameId)

            // Pick it: the slot is chosen and the picker closes.
            controller.onAction(NavAction.CONFIRM)
            await { controller.state.value?.picker == null && (controller.state.value?.focused as? EditorRow.Media)?.status == MediaSlotStatus.CHOSEN }

            // Text rows run the menu's commands; Back returns to the game's Options.
            repeat(20) { controller.onAction(NavAction.UP) }
            controller.onAction(NavAction.CONFIRM)
            assertEquals(listOf<Command>(Command.EditTitle(a.id)), commands)
            controller.onAction(NavAction.BACK)
            assertNull(controller.state.value)
            assertEquals(a.id, backTo)
        } finally {
            scope.cancel()
        }
    }

    @Test fun descriptionOverridesTheScrapedOneAndEmptyRestoresIt() = runBlocking {
        val g = insertGame()
        db.metadata().upsert(GameMetadataEntity(g.id, synopsis = "Scraped."))
        val (_, media, _) = setup()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val controller = MetadataEditorController(
            scope, db, media, UiSounds(context), MutableStateFlow(0L), { false }, { null }, { tmp.root },
            object : MetadataEditorController.Host {
                override fun toast(message: String) = Unit
                override fun fail(message: String) = Unit
                override fun refuse() = Unit
                override fun emit(effect: UiEffect) = Unit
                override fun run(command: Command) = Unit
                override fun siblings() = emptyList<Long>()
                override fun follow(gameId: Long) = Unit
                override fun reopenOptions(gameId: Long) = Unit
            },
        )
        try {
            controller.onDescriptionEntered(g.id, "  Mine.  ")
            await { db.metadata().get(g.id)?.shownSynopsis == "Mine." }
            assertEquals("Scraped.", db.metadata().get(g.id)!!.synopsis)
            controller.onDescriptionEntered(g.id, "")
            await { db.metadata().get(g.id)?.shownSynopsis == "Scraped." }
        } finally {
            scope.cancel()
        }
    }

    private suspend fun await(condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("Timed out")
            Thread.sleep(10)
        }
    }
    // endregion
}
