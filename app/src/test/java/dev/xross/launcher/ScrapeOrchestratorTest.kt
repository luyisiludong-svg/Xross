package dev.xross.launcher

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.xross.launcher.data.GameEntity
import dev.xross.launcher.data.GameHashEntity
import dev.xross.launcher.data.GameMetadataEntity
import dev.xross.launcher.data.XrossDatabase
import dev.xross.launcher.scrape.ArtKind
import dev.xross.launcher.scrape.AssetCache
import dev.xross.launcher.scrape.DownloadResult
import dev.xross.launcher.scrape.ProviderId
import dev.xross.launcher.scrape.ProviderResult
import dev.xross.launcher.scrape.ScrapeOrchestrator
import dev.xross.launcher.scrape.ScrapePrefs
import dev.xross.launcher.scrape.ScrapeProvider
import dev.xross.launcher.scrape.ScrapeRequest
import dev.xross.launcher.scrape.ScrapeStatus
import dev.xross.launcher.scrape.ScrapedGame
import dev.xross.launcher.scrape.ScrapedMedia
import dev.xross.launcher.state.GameDetailsState
import dev.xross.launcher.storage.Fingerprint
import dev.xross.launcher.storage.HashSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ScrapeOrchestratorTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, XrossDatabase::class.java).allowMainThreadQueries().build()

    @get:Rule val tmp = TemporaryFolder()

    @After fun close() = db.close()

    private class Scripted(
        override val id: ProviderId,
        var answer: (ScrapeRequest) -> ProviderResult,
        var configured: Boolean = true,
    ) : ScrapeProvider {
        val seen = ArrayList<ScrapeRequest>()
        override fun isConfigured() = configured
        override suspend fun lookup(request: ScrapeRequest): ProviderResult {
            seen += request
            return answer(request)
        }
    }

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 1, 2, 3)
    private val html = "<html>not an image</html>".toByteArray()

    private fun http() = FakeHttp().apply {
        download = { url, dest ->
            val bytes = if (url.contains("bad")) html else png
            dest.writeBytes(bytes)
            DownloadResult(200, bytes.size.toLong(), "image/png")
        }
    }

    private fun insertGame(hashed: Boolean = true): GameEntity = runBlocking {
        db.games().insertAll(listOf(GameEntity(path = "/r/snes/ct.sfc", platformId = "snes", title = "ct", fileName = "ct.sfc", sizeBytes = 4096, lastModified = 1)))
        val game = db.games().getAll().single()
        if (hashed) db.hashes().upsert(GameHashEntity.from(game, Fingerprint(HashSource.FILE, crc32 = "2d206bf7", sha1 = "aa")))
        game
    }

    private val hasheous = Scripted(ProviderId.HASHEOUS, { ProviderResult.Found(ScrapedGame(ProviderId.HASHEOUS, "42", title = "Chrono Trigger", publisher = "Square")) })
    private val screenScraper = Scripted(ProviderId.SCREENSCRAPER, {
        ProviderResult.Found(
            ScrapedGame(
                ProviderId.SCREENSCRAPER, "3398", title = "Chrono Trigger", synopsis = "Time travel.", developer = "Square",
                rating = 0.9f, media = listOf(ScrapedMedia(ArtKind.BOX_3D, "https://m/box", "png"), ScrapedMedia(ArtKind.WHEEL, "https://m/bad", "png")),
            ),
        )
    })
    private val steamGrid = Scripted(ProviderId.STEAMGRIDDB, {
        ProviderResult.Found(ScrapedGame(ProviderId.STEAMGRIDDB, "9", media = listOf(ScrapedMedia(ArtKind.HERO, "https://cdn/hero", "png"))))
    })

    private fun orchestrator(cache: AssetCache, vararg providers: ScrapeProvider, http: FakeHttp = http()) =
        ScrapeOrchestrator(db, cache, http, providers.toList(), { ScrapePrefs() }, clock = { 5_000L })

    @Test fun scrapesTextAndArtAndAppliesTheTitle() = runBlocking {
        val game = insertGame()
        val cache = AssetCache(tmp.newFolder("assets"))
        val status = orchestrator(cache, hasheous, screenScraper, steamGrid).scrapeOne(game.id)
        assertEquals(ScrapeStatus.OK, status)

        val meta = db.metadata().get(game.id)!!
        assertEquals("Chrono Trigger", meta.scrapedTitle)
        assertEquals("Time travel.", meta.synopsis)
        assertEquals("Square", meta.publisher) // only Hasheous had it
        assertEquals("3398", meta.screenScraperId)
        assertEquals("9", meta.steamGridDbId)
        assertEquals("42", meta.hasheousId)
        assertEquals(5_000L, meta.scrapedAt)
        // A non-image from the server is rejected; the rest is stored and recorded as a problem.
        assertNotNull(cache.file(game.uuid, ArtKind.BOX_3D))
        assertNotNull(cache.file(game.uuid, ArtKind.HERO))
        assertNull(cache.file(game.uuid, ArtKind.WHEEL))
        assertTrue(meta.scrapeDetail!!.contains("wheel"))

        assertEquals("Chrono Trigger", db.games().get(game.id)!!.title)
        // Later services searched with the exact title found by the first one.
        assertEquals("Chrono Trigger", steamGrid.seen.single().title)
        assertEquals("3398", steamGrid.seen.single().screenScraperId)
    }

    @Test fun hashlessGamesWaitForTheirFingerprint() = runBlocking {
        val game = insertGame(hashed = false)
        val status = orchestrator(AssetCache(tmp.newFolder("a")), hasheous).scrapeOne(game.id)
        assertNull(status)
        assertTrue(hasheous.seen.isEmpty())
        assertNull(db.metadata().get(game.id))
    }

    @Test fun transientFailuresAreRetriedAndNotFoundIsFinal() = runBlocking {
        val game = insertGame()
        val cache = AssetCache(tmp.newFolder("a"))
        val notFound = Scripted(ProviderId.HASHEOUS, { ProviderResult.NotFound })
        val down = Scripted(ProviderId.SCREENSCRAPER, { ProviderResult.Failed("timeout") })
        assertEquals(ScrapeStatus.ERROR, orchestrator(cache, notFound, down).scrapeOne(game.id))
        assertEquals("timeout", db.metadata().get(game.id)!!.scrapeDetail)
        // Failed games come back once the retry window has passed.
        assertEquals(1, db.metadata().pendingScrapeCount(retryBefore = 6_000L))
        assertEquals(0, db.metadata().pendingScrapeCount(retryBefore = 4_000L))

        down.answer = { ProviderResult.NotFound }
        assertEquals(ScrapeStatus.NOT_FOUND, orchestrator(cache, notFound, down).scrapeOne(game.id))
        assertEquals(0, db.metadata().pendingScrapeCount(retryBefore = Long.MAX_VALUE))
    }

    @Test fun nothingIsRecordedWhenNoServiceIsSetUp() = runBlocking {
        val game = insertGame()
        val off = Scripted(ProviderId.SCREENSCRAPER, { ProviderResult.Unavailable("no key") })
        assertNull(orchestrator(AssetCache(tmp.newFolder("a")), off).scrapeOne(game.id))
        assertNull(db.metadata().get(game.id))
        off.configured = false
        assertFalse(orchestrator(AssetCache(tmp.newFolder("b")), off).isConfigured)
    }

    @Test fun manualTitleAndEarlierFieldsSurviveAPartialScrape() = runBlocking {
        val game = insertGame()
        db.metadata().upsert(GameMetadataEntity(game.id, titleOverride = "My Title", synopsis = "Kept", genre = "RPG"))
        val partial = Scripted(ProviderId.HASHEOUS, { ProviderResult.Found(ScrapedGame(ProviderId.HASHEOUS, "1", title = "Chrono Trigger")) })
        orchestrator(AssetCache(tmp.newFolder("a")), partial).scrapeOne(game.id)
        val meta = db.metadata().get(game.id)!!
        assertEquals("Kept", meta.synopsis)
        assertEquals("RPG", meta.genre)
        assertEquals("My Title", db.games().get(game.id)!!.title)
    }

    @Test fun arcadeSetsSendTheZipsOwnHashes() = runBlocking {
        val chip = ByteArray(2048) { (it % 13).toByte() }
        val zip = tmp.newFile("1941.zip")
        java.util.zip.ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(java.util.zip.ZipEntry("small.bin")); out.write(ByteArray(16)); out.closeEntry()
            out.putNextEntry(java.util.zip.ZipEntry("41_09.bin")); out.write(chip); out.closeEntry()
        }
        db.games().insertAll(listOf(GameEntity(path = zip.path, platformId = "arcade", title = "1941", fileName = "1941.zip", sizeBytes = zip.length(), lastModified = zip.lastModified())))
        val game = db.games().getAll().single()
        db.hashes().upsert(GameHashEntity.from(game, Fingerprint(HashSource.ARCHIVE_SET, detail = "12 files")))
        val seen = Scripted(ProviderId.SCREENSCRAPER, { ProviderResult.NotFound })
        orchestrator(AssetCache(tmp.newFolder("a")), seen).scrapeOne(game.id)
        val request = seen.seen.single()
        val expected = dev.xross.launcher.storage.FileHash.of(zip, Long.MAX_VALUE)!!
        assertEquals(expected.crc32, request.crc32)
        assertEquals(expected.sha1, request.sha1)
        assertEquals("1941.zip", request.fileName)
        assertEquals(zip.length(), request.sizeBytes)
        // Hasheous gets the largest chip, which is what MAME DATs list.
        assertEquals(java.security.MessageDigest.getInstance("SHA-1").digest(chip).joinToString("") { "%02x".format(it) }, request.dataSha1)
    }

    @Test fun filesSkippedAsTooLargeAreScrapedByName() = runBlocking {
        db.games().insertAll(listOf(GameEntity(path = "/r/3ds/zelda.cci", platformId = "n3ds", title = "Zelda", fileName = "zelda.cci", sizeBytes = 1L shl 31, lastModified = 1)))
        val game = db.games().getAll().single()
        db.hashes().upsert(GameHashEntity.from(game, Fingerprint.skipped(dev.xross.launcher.storage.SkipReason.LARGE)))
        val seen = Scripted(ProviderId.SCREENSCRAPER, { ProviderResult.NotFound })
        assertEquals(ScrapeStatus.NOT_FOUND, orchestrator(AssetCache(tmp.newFolder("a")), seen).scrapeOne(game.id))
        assertFalse(seen.seen.single().hasHash)
    }

    // region Asset cache

    private val uuid = "123e4567-e89b-42d3-a456-426614174000"

    private fun libraryGame(id: Long, uuid: String, file: String = "Chrono Trigger (USA).sfc", platform: String = "snes") =
        GameEntity(id = id, path = "/r/$platform/$file", platformId = platform, title = file, fileName = file, sizeBytes = 1, lastModified = 0, uuid = uuid)

    @Test fun cacheStoresAtomicallyInThePortableLayoutAndRejectsBadNames() = runBlocking {
        val root = tmp.newFolder("media")
        val cache = AssetCache(root)
        cache.setLibrary(listOf(libraryGame(1, uuid)))
        val v0 = cache.version.value
        cache.store(uuid, ArtKind.BOX_2D, "png") { it.writeBytes(png) }
        assertEquals(File(root, "covers/snes/Chrono Trigger (USA).png"), cache.file(uuid, ArtKind.BOX_2D))
        assertTrue(cache.version.value > v0)
        assertTrue("media folder gets a .nomedia", File(root, ".nomedia").isFile)
        // Replacing with another format leaves exactly one file for the kind.
        cache.store(uuid, ArtKind.BOX_2D, "jpg") { it.writeBytes(png) }
        assertEquals(listOf("Chrono Trigger (USA).jpg"), File(root, "covers/snes").list()!!.toList())
        // A failed write leaves nothing behind.
        runCatching { cache.store(uuid, ArtKind.HERO, "png") { error("network died") } }
        assertNull(cache.file(uuid, ArtKind.HERO))
        assertTrue(File(root, "heroes/snes").list().orEmpty().isEmpty())

        assertTrue(runCatching { cache.store("../escape", ArtKind.HERO, "png") { } }.isFailure)
        assertTrue(runCatching { cache.store(uuid, ArtKind.HERO, "sh") { } }.isFailure)
        assertNull(cache.file("../../etc", ArtKind.HERO))
        // Games the index doesn't know can't write anywhere.
        assertTrue(runCatching { cache.store("00000000-0000-4000-8000-000000000000", ArtKind.HERO, "png") { } }.isFailure)
    }

    @Test fun pruneDropsGamesThatAreGoneButKeepsUnknownFiles() = runBlocking {
        val root = tmp.newFolder("media")
        val cache = AssetCache(root)
        val other = "00000000-0000-4000-8000-000000000000"
        cache.setLibrary(listOf(libraryGame(1, uuid), libraryGame(2, other, file = "EarthBound (USA).sfc")))
        cache.store(uuid, ArtKind.BOX_2D, "png") { it.writeBytes(png) }
        cache.store(other, ArtKind.BOX_2D, "png") { it.writeBytes(png) }
        // Media copied in by hand (ES-DE) for a game that isn't in the library.
        val dropped = File(root, "covers/snes/Secret of Mana (USA).png").apply { writeBytes(png) }

        cache.setLibrary(listOf(libraryGame(1, uuid))) // EarthBound was deleted
        assertEquals(png.size.toLong(), cache.prune(setOf(uuid)))
        assertNotNull(cache.file(uuid, ArtKind.BOX_2D))
        assertNull(cache.file(other, ArtKind.BOX_2D))
        assertTrue(dropped.isFile)

        assertEquals(png.size.toLong() * 2, cache.clear())
        assertNull(cache.file(uuid, ArtKind.BOX_2D))
        assertTrue(File(root, ".nomedia").isFile) // clear empties the kind folders, not the media folder
    }

    @Test fun sniffTrustsBytesNotServers() {
        fun file(bytes: ByteArray) = tmp.newFile().apply { writeBytes(bytes) }
        assertEquals("png", AssetCache.sniff(file(png)))
        assertEquals("jpg", AssetCache.sniff(file(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0, 0, 0, 0, 0, 0, 0))))
        assertEquals("webp", AssetCache.sniff(file("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.ISO_8859_1))))
        assertEquals("mp4", AssetCache.sniff(file("\u0000\u0000\u0000\u0018ftypmp42".toByteArray(Charsets.ISO_8859_1))))
        assertNull(AssetCache.sniff(file(html)))
        assertNull(AssetCache.sniff(file(byteArrayOf(1, 2))))
    }

    // endregion

    @Test fun detailsShowWhatIsKnown() {
        val game = GameEntity(1, "/a.sfc", "snes", "Chrono Trigger", "a.sfc", 1, 0, playCount = 3, playTimeMs = 125 * 60_000L)
        val meta = GameMetadataEntity(1, releaseDate = "1995-08-22", developer = "Square", publisher = "Square", genre = "RPG", players = "1", rating = 0.9f, synopsis = " Time travel. ")
        val d = GameDetailsState.build(game, null, meta, artVersion = 3)
        assertEquals("Time travel.", d.synopsis)
        assertEquals(listOf("Released", "Developer", "Genre", "Players"), d.facts.map { it.first }) // publisher = developer: shown once
        assertEquals("Played 2 h 5 min · 3 sessions", d.playtime)
        assertEquals(0, d.scrollBy(-3).scroll)
        assertEquals(GameDetailsState.MAX_SCROLL, d.scrollBy(1_000).scroll)
        assertEquals("Not played yet", GameDetailsState.build(game.copy(playCount = 0, playTimeMs = 0), null, null, 0).playtime)
        assertEquals("1995", GameDetailsState.formatDate("1995"))
        assertTrue(GameDetailsState.build(game, null, meta.copy(scrapeStatus = "OK"), 0).scraped)
        assertFalse(d.scraped)
    }
}
