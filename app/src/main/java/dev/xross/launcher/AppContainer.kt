package dev.xross.launcher

import android.content.Context
import dev.xross.launcher.audio.UiSounds
import dev.xross.launcher.data.AppRepository
import dev.xross.launcher.data.FingerprintQueue
import dev.xross.launcher.data.GameRepository
import dev.xross.launcher.data.PlaySessionTracker
import dev.xross.launcher.data.PrefsSessionStore
import dev.xross.launcher.data.SettingsStore
import dev.xross.launcher.data.XrossDatabase
import dev.xross.launcher.launch.GameLauncher
import dev.xross.launcher.media.MediaRepository
import dev.xross.launcher.media.MusicPlayer
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
    val fingerprints: FingerprintQueue by lazy {
        FingerprintQueue(database.games(), database.hashes()) {
            FingerprintPolicy(hashLargeImages = settings.current.hashLargeImages)
        }
    }
}
