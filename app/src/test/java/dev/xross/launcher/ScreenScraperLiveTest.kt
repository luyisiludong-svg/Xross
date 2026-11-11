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

    @Test fun scrapesSampleRoms() = runBlocking {
        assumeTrue("pass -PliveScrape=true to run", System.getProperty("xross.liveScrape") == "true")
        val env = env()
        assumeTrue("SCREENSCRAPER_DEV_ID missing from .env", !env["SCREENSCRAPER_DEV_ID"].isNullOrBlank())
        val provider = ScreenScraperProvider(UrlConnectionHttp("Xross/live-test"), {
            ScreenScraperCredentials(
                devId = env.getValue("SCREENSCRAPER_DEV_ID"),
                devPassword = env["SCREENSCRAPER_DEV_PASSWORD"].orEmpty(),
                softName = "Xross",
                user = env["SCREENSCRAPER_USER"].orEmpty(),
                password = env["SCREENSCRAPER_PASSWORD"].orEmpty(),
            )
        })

        val account = provider.checkAccount()
        println("Account: $account")
        if (account is AccountCheck.Failed) return@runBlocking

        val samples = listOf("ARCADE" to "arcade", "3DS" to "n3ds").flatMap { (dir, platform) ->
            File(root, "localTestMedia/Roms/$dir").listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }.take(3).map { it to platform }
        }
        for ((file, platform) in samples) {
            val hashes = FileHash.of(file, maxBytes = 256L shl 20) // 3DS images are too big: name search
            val request = ScrapeRequest(
                gameId = 0, uuid = "live", title = RomScanner.cleanTitle(file.nameWithoutExtension), fileName = file.name,
                platformId = platform, sizeBytes = file.length(), crc32 = hashes?.crc32, md5 = hashes?.md5, sha1 = hashes?.sha1,
                prefs = ScrapePrefs(region = "us", language = "en"),
            )
            val result = provider.lookup(request)
            val summary = when (result) {
                is ProviderResult.Found -> with(result.game) {
                    "FOUND #$providerId \"$title\" · $releaseDate · $developer · ${media.joinToString { it.kind.name }}"
                }
                else -> result.toString()
            }
            println("${file.name}: $summary")
            if (result is ProviderResult.QuotaExceeded || result is ProviderResult.Unavailable) break
        }
        println("Quota after run: ${provider.quota.value?.summary}")
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
}
