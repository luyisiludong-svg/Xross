package dev.xross.launcher

import dev.xross.launcher.scrape.ProviderResult
import dev.xross.launcher.scrape.ScrapePrefs
import dev.xross.launcher.scrape.ScrapeRequest
import dev.xross.launcher.scrape.UrlConnectionHttp
import dev.xross.launcher.scrape.providers.AccountCheck
import dev.xross.launcher.scrape.providers.ScreenScraperCredentials
import dev.xross.launcher.scrape.providers.ScreenScraperProvider
import dev.xross.launcher.storage.FileHash
import dev.xross.launcher.storage.RomScanner
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Real calls to the metadata services, against the ROMs in the git-ignored `localTestMedia/Roms/` folder.
 * Opt-in and skipped by default:
 *
 *     ./gradlew :app:testDebugUnitTest --tests '*ScreenScraperLiveTest*' -PliveScrape=true
 *
 * Each test runs only when its credentials are in `.env`: SCREENSCRAPER_DEV_ID (+ password, and
 * optionally a user account) for ScreenScraper; STEAMGRIDDB_API_KEY (or SteamGridDB_API) for
 * SteamGridDB. Prints what was found. Uses a handful of files to stay well inside quotas.
 */
@RunWith(RobolectricTestRunner::class)
class ScreenScraperLiveTest {
    private val root = File(System.getProperty("user.dir")).let { if (File(it, "app").isDirectory) it else it.parentFile }

    private fun env(): Map<String, String> = File(root, ".env").takeIf { it.isFile }?.readLines().orEmpty()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && it.indexOf('=') > 0 }
        .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim().removeSurrounding("\"") }

    /**
     * ScreenScraper end to end with the app's own pieces: identification by hash (or name for
     * files too large to hash), the quota block read from every answer and the pacing derived from
     * it, then descriptions, box art and video snaps downloaded and cached through [AssetCache]
     * into a scratch `Xross/media/` (ES-DE layout, `.nomedia` first). Without a user account in
     * `.env` the developer credentials alone are used (lower quotas) and the account check is
     * skipped, because `ssuserInfos.php` needs a user.
     */
    @Test fun scrapesSampleRoms() = runBlocking {
        assumeTrue("pass -PliveScrape=true to run", System.getProperty("xross.liveScrape") == "true")
        val env = env()
        assumeTrue("SCREENSCRAPER_DEV_ID missing from .env", !env["SCREENSCRAPER_DEV_ID"].isNullOrBlank())
        val http = UrlConnectionHttp("Xross/live-test")
        val limiter = dev.xross.launcher.scrape.RateLimiter(baseIntervalMs = 1_200)
        val provider = ScreenScraperProvider(http, {
            ScreenScraperCredentials(
                devId = env.getValue("SCREENSCRAPER_DEV_ID"),
                devPassword = env["SCREENSCRAPER_DEV_PASSWORD"].orEmpty(),
                softName = "Xross",
                user = env["SCREENSCRAPER_USER"].orEmpty(),
                password = env["SCREENSCRAPER_PASSWORD"].orEmpty(),
            )
        }, limiter)

        if (!env["SCREENSCRAPER_USER"].isNullOrBlank()) {
            val account = provider.checkAccount()
            println("Account: $account")
            if (account is AccountCheck.Failed) return@runBlocking
        } else {
            println("Account: none in .env; developer credentials only")
        }

        val samples = listOf("ARCADE" to "arcade", "3DS" to "n3ds").flatMap { (dir, platform) ->
            File(root, "localTestMedia/Roms/$dir").listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }.take(3).map { it to platform }
        }
        assumeTrue("no ROMs in localTestMedia/Roms/", samples.isNotEmpty())
        val xross = java.nio.file.Files.createTempDirectory("xross-live-ss").toFile()
        val mediaDir = File(xross, "media")
        val cache = dev.xross.launcher.scrape.AssetCache({ mediaDir })
        var found = 0
        var described = 0
        var cachedImages = 0
        var cachedVideos = 0
        val started = System.nanoTime()
        try {
            for ((file, platform) in samples) {
                val hashes = FileHash.of(file, maxBytes = 256L shl 20) // 3DS images are too big: name search
                val game = dev.xross.launcher.data.GameEntity(
                    path = file.absolutePath, platformId = platform, title = RomScanner.cleanTitle(file.nameWithoutExtension),
                    fileName = file.name, sizeBytes = file.length(), lastModified = file.lastModified(),
                )
                cache.register(game)
                val request = ScrapeRequest(
                    gameId = 0, uuid = game.uuid, title = game.title, fileName = file.name,
                    platformId = platform, sizeBytes = file.length(), crc32 = hashes?.crc32, md5 = hashes?.md5, sha1 = hashes?.sha1,
                    prefs = ScrapePrefs(region = "us", language = "en", videos = true),
                )
                val result = provider.lookup(request)
                val summary = when (result) {
                    is ProviderResult.Found -> with(result.game) {
                        found++
                        if (!synopsis.isNullOrBlank()) described++
                        // One image (box art first) and the video snap, through the same checks the app uses.
                        val picks = listOfNotNull(
                            media.firstOrNull { !it.kind.isVideo && it.kind in BOX_KINDS } ?: media.firstOrNull { !it.kind.isVideo },
                            media.firstOrNull { it.kind.isVideo },
                        )
                        val stored = picks.mapNotNull { m ->
                            val tmp = cache.tempFile()
                            try {
                                val dl = http.download(m.url, tmp, m.kind.maxBytes)
                                val ext = dev.xross.launcher.scrape.AssetCache.sniff(tmp)
                                if (dl.code !in 200..299 || ext == null || (ext == "mp4") != m.kind.isVideo) {
                                    "${m.kind} ${dl.code} rejected"
                                } else {
                                    val target = cache.adopt(game.uuid, m.kind, ext, tmp)
                                    if (m.kind.isVideo) cachedVideos++ else cachedImages++
                                    "${m.kind} → ${target.relativeTo(xross)} (${target.length() / 1024} KB)"
                                }
                            } catch (e: java.io.IOException) {
                                "${m.kind} failed (${e.javaClass.simpleName})"
                            } finally {
                                tmp.delete()
                            }
                        }
                        "FOUND #$providerId \"$title\" · $releaseDate · $developer · " +
                            "${synopsis?.length ?: 0} chars of description · offered ${media.joinToString { it.kind.name }}\n      " +
                            stored.joinToString("\n      ")
                    }
                    else -> result.toString()
                }
                println("${file.name}: $summary")
                println("   quota: ${provider.quota.value?.summary} · pacing ${limiter.minIntervalMs} ms")
                if (result is ProviderResult.QuotaExceeded || result is ProviderResult.Unavailable) break
            }
            val seconds = (System.nanoTime() - started) / 1e9
            println("Quota after run: ${provider.quota.value}")
            println("Found $found of ${samples.size}; $described with descriptions; cached $cachedImages images and $cachedVideos videos in %.1f s".format(seconds))
            org.junit.Assert.assertTrue(".nomedia must exist before media is written", File(mediaDir, ".nomedia").isFile || cachedImages + cachedVideos == 0)
            org.junit.Assert.assertNotNull("ScreenScraper reported no quota block", provider.quota.value)
            org.junit.Assert.assertTrue("ScreenScraper identified none of the samples", found > 0)
        } finally {
            xross.deleteRecursively()
        }
    }

    /**
     * The app's arcade path end to end: Hasheous names each set from its largest ROM chip, then
     * SteamGridDB finds art for that title; one image per game is downloaded and checked by its
     * bytes. 3DS games use their file names. Fails if nothing is found at all.
     */
    @Test fun steamGridDbFindsArtForSampleRoms() = runBlocking {
        assumeTrue("pass -PliveScrape=true to run", System.getProperty("xross.liveScrape") == "true")
        val env = env()
        val key = env["STEAMGRIDDB_API_KEY"] ?: env["SteamGridDB_API"]
        assumeTrue("STEAMGRIDDB_API_KEY missing from .env", !key.isNullOrBlank())
        val http = UrlConnectionHttp("Xross/live-test")
        val hasheous = dev.xross.launcher.scrape.providers.HasheousProvider(http, { true })
        val sgdb = dev.xross.launcher.scrape.providers.SteamGridDbProvider(http, { key!! })

        val samples = listOf("ARCADE" to "arcade", "3DS" to "n3ds").flatMap { (dir, platform) ->
            File(root, "localTestMedia/Roms/$dir").listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }.take(4).map { it to platform }
        }
        assumeTrue("no ROMs in localTestMedia/Roms/", samples.isNotEmpty())
        val out = java.nio.file.Files.createTempDirectory("xross-live").toFile()
        var withArt = 0
        for ((file, platform) in samples) {
            var title = RomScanner.cleanTitle(file.nameWithoutExtension)
            if (platform == "arcade") {
                val chip = FileHash.ofLargestZipMember(file, 256L shl 20)
                val named = hasheous.lookup(
                    ScrapeRequest(
                        gameId = 0, uuid = "live", title = title, fileName = file.name, platformId = platform, sizeBytes = file.length(),
                        dataCrc32 = chip?.crc32, dataMd5 = chip?.md5, dataSha1 = chip?.sha1,
                    ),
                )
                (named as? ProviderResult.Found)?.game?.title?.let { title = it }
            }
            val result = sgdb.lookup(
                ScrapeRequest(gameId = 0, uuid = "live", title = title, fileName = file.name, platformId = platform, sizeBytes = file.length()),
            )
            val line = when (result) {
                is ProviderResult.Found -> {
                    val first = result.game.media.first()
                    val dest = File(out, "${file.nameWithoutExtension}.part")
                    val dl = http.download(first.url, dest, first.kind.maxBytes)
                    val type = dev.xross.launcher.scrape.AssetCache.sniff(dest)
                    if (dl.code == 200 && type != null) withArt++
                    "#${result.game.providerId} ${result.game.media.joinToString { it.kind.name }} · ${first.kind} ${dl.code} ${dl.bytes / 1024} KB $type"
                }
                else -> result.toString()
            }
            println("${file.name} → \"$title\": $line")
            if (result is ProviderResult.Unavailable) break
        }
        out.deleteRecursively()
        println("$withArt of ${samples.size} games got art")
        org.junit.Assert.assertTrue("SteamGridDB found no art for any sample", withArt > 0)
    }

    private companion object {
        val BOX_KINDS = setOf(dev.xross.launcher.scrape.ArtKind.BOX_2D, dev.xross.launcher.scrape.ArtKind.BOX_3D)
    }
}
