package dev.wave.launcher

import android.content.Context
import dev.wave.launcher.audio.UiSounds
import dev.wave.launcher.data.AppRepository
import dev.wave.launcher.data.FingerprintQueue
import dev.wave.launcher.data.GameRepository
import dev.wave.launcher.data.PlaySessionTracker
import dev.wave.launcher.data.PrefsSessionStore
import dev.wave.launcher.data.SettingsStore
import dev.wave.launcher.data.WaveDatabase
import dev.wave.launcher.launch.GameLauncher
import dev.wave.launcher.media.MediaRepository
import dev.wave.launcher.media.MusicPlayer
import dev.wave.launcher.storage.FingerprintPolicy
import dev.wave.launcher.storage.StorageVolumes

/** Manual dependency graph; swap for Hilt once the module split happens. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val database: WaveDatabase by lazy { WaveDatabase.create(appContext) }
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
