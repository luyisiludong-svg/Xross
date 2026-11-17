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
import dev.xross.launcher.scrape.BuiltInCredentials
import dev.xross.launcher.scrape.providers.ScreenScraperCredentials
import dev.xross.launcher.scrape.providers.ScreenScraperProvider
import dev.xross.launcher.scrape.providers.SteamGridDbProvider
import dev.xross.launcher.data.FingerprintQueue
import dev.xross.launcher.data.GameRepository
import dev.xross.launcher.data.PlaySessionTracker
import dev.xross.launcher.data.PrefsSessionStore
import dev.xross.launcher.data.SettingsStore
import dev.xross.launcher.data.XrossDatabase
import dev.xross.launcher.data.DatabaseBackupManager
import dev.xross.launcher.launch.AndroidIntentGameLauncher
import dev.xross.launcher.launch.GameLauncher
import dev.xross.launcher.storage.XrossEnvironment
import dev.xross.launcher.media.MediaRepository
import dev.xross.launcher.media.MusicPlayer
import dev.xross.launcher.media.SnapPlayer
import dev.xross.launcher.profile.ProfileRepository
import dev.xross.launcher.profile.RetroAchievementsClient
import dev.xross.launcher.storage.FingerprintPolicy
import dev.xross.launcher.storage.StorageVolumes
import dev.xross.launcher.audio.AudioDirector
import dev.xross.launcher.audio.PlaybackWatcher
import dev.xross.launcher.media.ThemeBgm
import dev.xross.launcher.profile.AchievementsRepository
import dev.xross.launcher.profile.FriendsRepository
import dev.xross.launcher.theme.ArtSwatches
import dev.xross.launcher.theme.GamePaletteExtractor
import dev.xross.launcher.theme.pack.ThemePackLoader
import dev.xross.launcher.theme.pack.ThemeRuntime
import dev.xross.launcher.theme.shader.ShaderCanary
import dev.xross.launcher.theme.shader.ShaderHealth
import dev.xross.launcher.ui.WallpaperLoader

/** Manual dependency graph; swap for Hilt once the module split happens. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val database: XrossDatabase by lazy { XrossDatabase.create(appContext) }
    val games: GameRepository by lazy { GameRepository(database.games()) }
    val apps: AppRepository by lazy { AppRepository(appContext) }
    /** The portable Xross folder (config, database backup, DATs, media, themes). */
    val environment: XrossEnvironment by lazy { XrossEnvironment(appContext) }
    val settings: SettingsStore by lazy { SettingsStore(appContext, environment) }
    val backups: DatabaseBackupManager by lazy {
        DatabaseBackupManager(appContext, database = { database }, layout = { environment.current })
    }
    val volumes: StorageVolumes by lazy { StorageVolumes(appContext) }
    val launcher: GameLauncher by lazy { AndroidIntentGameLauncher(appContext) }
    /** Theme packs: the built-in Default pack and folders in `Xross/themes/`. */
    val themePacks: ThemePackLoader by lazy {
        ThemePackLoader(appContext.assets) { environment.current.themes }
    }
    /**
     * Background shader status, toasts and the crash-loop canary (`Xross/cache/`). Its creation
     * runs the canary's recovery, so it is built in [XrossApplication.onCreate], before any frame.
     */
    val shaderHealth: ShaderHealth by lazy {
        ShaderHealth(
            ShaderCanary { java.io.File(environment.current.root, CACHE_DIR) },
        )
    }
    val themes: ThemeRuntime by lazy { ThemeRuntime(themePacks) }
    val sounds: UiSounds by lazy { UiSounds(appContext).also { it.useTheme(themes.active.value) } }
    val sessions: PlaySessionTracker by lazy { PlaySessionTracker(PrefsSessionStore(appContext)) }
    val media: MediaRepository by lazy { MediaRepository(appContext) }
    val music: MusicPlayer by lazy { MusicPlayer(appContext) }
    val dats: DatRepository by lazy { DatRepository(database) }
    val credentials: CredentialStore by lazy {
        CredentialStore(appContext).also { store ->
            // Debug builds only (release builds carry empty strings): the tester's accounts from .env,
            // used only where nothing has been entered in Settings.
            if (store.current.screenScraperUser.isBlank() && BuildConfig.DEBUG_SCREENSCRAPER_USER.isNotBlank()) {
                store.update {
                    it.copy(
                        screenScraperUser = BuildConfig.DEBUG_SCREENSCRAPER_USER,
                        screenScraperPassword = BuildConfig.DEBUG_SCREENSCRAPER_PASSWORD,
                    )
                }
            }
            if (store.current.steamGridDbKey.isBlank() && BuildConfig.DEBUG_STEAMGRIDDB_KEY.isNotBlank()) {
                store.update { it.copy(steamGridDbKey = BuildConfig.DEBUG_STEAMGRIDDB_KEY) }
            }
            // The profile is fetched (and the key checked) by ProfileRepository on the first start.
            // Never under Robolectric: unit tests also see the debug BuildConfig and must not link
            // (or call) the tester's real account.
            if (!store.current.retroAchievementsLinked && android.os.Build.FINGERPRINT != "robolectric" &&
                BuildConfig.DEBUG_RETROACHIEVEMENTS_USER.isNotBlank() && BuildConfig.DEBUG_RETROACHIEVEMENTS_KEY.isNotBlank()
            ) {
                store.update {
                    it.copy(
                        retroAchievementsUser = BuildConfig.DEBUG_RETROACHIEVEMENTS_USER,
                        retroAchievementsKey = BuildConfig.DEBUG_RETROACHIEVEMENTS_KEY,
                    )
                }
            }
        }
    }

    /**
     * Downloaded art in `Xross/media/<kind>/<system>/<rom stem>.<ext>`, following the Xross folder.
     * The private cache of earlier versions is moved there once (see [AssetCache.migrateLegacy]).
     */
    val assets: AssetCache by lazy {
        AssetCache(root = { environment.current.media }, legacyRoot = java.io.File(appContext.noBackupFilesDir, "assets"))
    }
    val art: ArtLoader by lazy { ArtLoader(assets) }

    /** The wallpaper behind the menu (2b.4c): Set as Xross wallpaper, or the theme pack's. */
    val wallpapers: WallpaperLoader by lazy {
        WallpaperLoader(appContext.contentResolver) { environment.current.root }
    }

    /**
     * Colors of the focused game's hero or wheel art (Theme 2.0 contextual palette), debounced and
     * cached per game; extraction runs on [kotlinx.coroutines.Dispatchers.Default].
     */
    val gamePalette: GamePaletteExtractor<android.graphics.Bitmap> by lazy {
        GamePaletteExtractor(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate),
            load = { key ->
                val uuid = ArtSwatches.uuidOf(key)
                ArtSwatches.KINDS.firstNotNullOfOrNull { assets.file(uuid, it) }
                    ?.let(ArtSwatches::decode)
            },
            analyze = { bitmap ->
                val start = android.os.SystemClock.elapsedRealtimeNanos()
                ArtSwatches.analyze(bitmap).also {
                    // Debug and profiling builds: what was extracted, how long it took, and where.
                    if (BuildConfig.PERF_LOG) {
                        android.util.Log.i(
                            "XrossPalette",
                            "extract ${bitmap.width}x${bitmap.height} in %.1f ms on %s".format(
                                (android.os.SystemClock.elapsedRealtimeNanos() - start) / 1e6, Thread.currentThread().name,
                            ),
                        )
                    }
                    bitmap.recycle()
                }
            },
        )
    }
    /** Every volume and pause decision across effects, cues, music and snaps. */
    val audio: AudioDirector by lazy {
        AudioDirector(
            music, sounds,
            bgm = ThemeBgm(appContext),
            playback = PlaybackWatcher(appContext),
        ) { settings.current }
    }
    val snaps: SnapPlayer by lazy {
        SnapPlayer(appContext, volume = { audio.snapVolume }, onState = audio::onSnapState).also { player ->
            audio.snapVolumeListener = player::setVolume
        }
    }
    private val http: HttpClient by lazy { UrlConnectionHttp("Xross/${BuildConfig.VERSION_NAME} (Android)") }

    val screenScraper: ScreenScraperProvider by lazy {
        ScreenScraperProvider(http, credentials = {
            ScreenScraperCredentials(
                devId = BuiltInCredentials.screenScraperDevId,
                devPassword = BuiltInCredentials.screenScraperDevPassword,
                softName = "Xross",
                user = credentials.current.screenScraperUser,
                password = credentials.current.screenScraperPassword,
            )
        })
    }

    val scraper: ScrapeOrchestrator by lazy {
        ScrapeOrchestrator(
            db = database,
            cache = assets,
            http = http,
            providers = listOf(
                HasheousProvider(http, enabled = { settings.current.hasheousEnabled }),
                screenScraper,
                SteamGridDbProvider(http, apiKey = { credentials.current.steamGridDbKey }),
            ),
            prefs = {
                settings.current.let { ScrapePrefs(region = it.scrapeRegion, language = it.scrapeLanguage, videos = it.scrapeVideos) }
            },
            networkAllowed = { !settings.current.scrapeWifiOnly || Network.isUnmetered(appContext) },
        )
    }
    /** The linked RetroAchievements account (status bar avatar, User Card, Settings ▸ Accounts). */
    val profiles: ProfileRepository by lazy {
        ProfileRepository(
            client = RetroAchievementsClient(http),
            credentials = credentials,
            layout = { environment.current },
        )
    }

    /** Followed RetroAchievements users and what they played last (2b.7c). */
    val friends: FriendsRepository by lazy {
        FriendsRepository(
            client = RetroAchievementsClient(http),
            credentials = credentials,
            layout = { environment.current },
        )
    }

    /** RetroAchievements game lists and progress for the Details and Achievements screens. */
    val achievements: AchievementsRepository by lazy {
        AchievementsRepository(
            client = RetroAchievementsClient(http),
            credentials = credentials,
            layout = { environment.current },
            hashes = { id -> database.hashes().get(id) },
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

    private companion object {
        /** Under the Xross root: the shader canary and the list of shaders blocked after a crash. */
        const val CACHE_DIR = "cache"
    }
}
