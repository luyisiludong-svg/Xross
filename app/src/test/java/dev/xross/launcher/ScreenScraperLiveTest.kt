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
 * Real ScreenScraper calls against the ROMs in the git-ignored `Roms/` folder. Opt-in and
 * skipped by default:
 *
 *     ./gradlew :app:testDebugUnitTest --tests '*ScreenScraperLiveTest*' -PliveScrape=true
 *
 * Needs SCREENSCRAPER_DEV_ID / SCREENSCRAPER_DEV_PASSWORD (and optionally SCREENSCRAPER_USER /
 * SCREENSCRAPER_PASSWORD) in `.env`. Prints what was found; asserts only that the service
 * answered, since the catalogue changes. Uses a handful of files to stay well inside quotas.
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
            File(root, "Roms/$dir").listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }.take(3).map { it to platform }
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
}
