package dev.xross.launcher

import android.content.Context
import dev.xross.launcher.audio.UiSounds
import dev.xross.launcher.data.AppRepository
import dev.xross.launcher.data.CredentialStore
import dev.xross.launcher.data.DatRepository
import dev.xross.launcher.scrape.ArtLoader
import dev.xross.launcher.scrape.AssetCache
import dev.xross.launcher.scrape.HttpClient
import dev.xross.launcher.scrape.Network
import dev.xross.launcher.scrape.ScrapeOrchestrator
import dev.xross.launcher.scrape.ScrapePrefs
import dev.xross.launcher.scrape.UrlConnectionHttp
import dev.xross.launcher.scrape.providers.HasheousProvider
import dev.xross.launcher.scrape.providers.ScreenScraperCredentials
import dev.xross.launcher.scrape.providers.ScreenScraperProvider
import dev.xross.launcher.scrape.providers.SteamGridDbProvider
import dev.xross.launcher.data.FingerprintQueue
import dev.xross.launcher.data.GameRepository
import dev.xross.launcher.data.PlaySessionTracker
import dev.xross.launcher.data.PrefsSessionStore
import dev.xross.launcher.data.SettingsStore
import dev.xross.launcher.data.XrossDatabase
import dev.xross.launcher.launch.GameLauncher
import dev.xross.launcher.media.MediaRepository
import dev.xross.launcher.media.MusicPlayer
import dev.xross.launcher.media.SnapPlayer
import dev.xross.launcher.storage.FingerprintPolicy
import dev.xross.launcher.storage.StorageVolumes

/** Manual dependency graph; swap for Hilt once the module split happens. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val database: XrossDatabase by lazy { XrossDatabase.create(appContext) }
    val games: GameRepository by lazy { GameRepository(database.games()) }
    val apps: AppRepository by lazy { AppRepository(appContext) }
    val settings: SettingsStore by lazy { SettingsStore(appContext) }
    val volumes: StorageVolumes by lazy { StorageVolumes(appContext) }
    val launcher: GameLauncher by lazy { GameLauncher(appContext) }
    val sounds: UiSounds by lazy { UiSounds(appContext) }
    val sessions: PlaySessionTracker by lazy { PlaySessionTracker(PrefsSessionStore(appContext)) }
    val media: MediaRepository by lazy { MediaRepository(appContext) }
    val music: MusicPlayer by lazy { MusicPlayer(appContext) }
    val dats: DatRepository by lazy { DatRepository(database) }
    val credentials: CredentialStore by lazy { CredentialStore(appContext) }

    /** Downloaded art, outside backups (re-scrapeable, large). */
    val assets: AssetCache by lazy { AssetCache(java.io.File(appContext.noBackupFilesDir, "assets")) }
    val art: ArtLoader by lazy { ArtLoader(assets) }
    val snaps: SnapPlayer by lazy { SnapPlayer(appContext, music) }
    private val http: HttpClient by lazy { UrlConnectionHttp("Xross/${BuildConfig.VERSION_NAME} (Android)") }

    val scraper: ScrapeOrchestrator by lazy {
        ScrapeOrchestrator(
            db = database,
            cache = assets,
            http = http,
            providers = listOf(
                HasheousProvider(http, enabled = { settings.current.hasheousEnabled }),
                ScreenScraperProvider(http, credentials = {
                    ScreenScraperCredentials(
                        devId = BuildConfig.SCREENSCRAPER_DEV_ID,
                        devPassword = BuildConfig.SCREENSCRAPER_DEV_PASSWORD,
                        softName = "Xross",
                        user = credentials.current.screenScraperUser,
                        password = credentials.current.screenScraperPassword,
                    )
                }),
                SteamGridDbProvider(http, apiKey = { credentials.current.steamGridDbKey }),
            ),
            prefs = {
                settings.current.let { ScrapePrefs(region = it.scrapeRegion, language = it.scrapeLanguage, videos = it.scrapeVideos) }
            },
            networkAllowed = { !settings.current.scrapeWifiOnly || Network.isUnmetered(appContext) },
        )
    }
    val fingerprints: FingerprintQueue by lazy {
        FingerprintQueue(
            games = database.games(),
            hashes = database.hashes(),
            policy = { FingerprintPolicy(hashLargeImages = settings.current.hashLargeImages) },
            // New fingerprints may now match an imported DAT.
            onFinished = { dats.matchAll() },
        )
    }
}
