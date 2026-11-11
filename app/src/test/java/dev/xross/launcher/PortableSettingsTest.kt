package dev.xross.launcher

import android.content.Context
import android.view.KeyEvent
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import dev.xross.launcher.data.CredentialStore
import dev.xross.launcher.data.SettingsStore
import dev.xross.launcher.data.XrossSettings
import dev.xross.launcher.input.ConfirmLayout
import dev.xross.launcher.input.NavAction
import dev.xross.launcher.storage.PlatformRegistry
import dev.xross.launcher.storage.SystemOverride
import dev.xross.launcher.storage.XrossEnvironment
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** SettingsStore persisted as the JSON files in Xross/config/. */
@RunWith(RobolectricTestRunner::class)
class PortableSettingsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule val tmp = TemporaryFolder()

    private var granted = false
    private val shared get() = File(tmp.root, "emulated/0/Xross")
    private val environment by lazy { XrossEnvironment(context, writeAccess = { granted }, primaryRoot = { shared }) }

    private fun store() = SettingsStore(context, environment)
    private val config get() = environment.current.config

    @After fun resetRegistry() = PlatformRegistry.applyOverrides(emptyMap())

    @Test fun firstStartWritesAllThreeFiles() {
        store().flush()
        for (name in listOf("settings.json", "controllers.json", "systems.json")) {
            assertTrue("$name written", File(config, name).isFile)
        }
        assertTrue(File(config, "settings.json").readText().contains("\"theme\": \"LAVENDER\""))
    }

    @Test fun updatesReachTheFilesAndSurviveARestart() {
        val s = store()
        s.update { it.copy(theme = "CRIMSON", keyRemap = mapOf(KeyEvent.KEYCODE_BUTTON_L2 to NavAction.MEDIA), emulatorChoice = mapOf("psx" to "duckstation")) }
        s.flush()
        assertTrue(File(config, "settings.json").readText().contains("CRIMSON"))
        assertTrue(File(config, "controllers.json").readText().contains("\"KEYCODE_BUTTON_L2\": \"MEDIA\""))
        assertTrue(File(config, "systems.json").readText().contains("\"emulator\": \"duckstation\""))
        assertEquals(s.current, store().current)
    }

    @Test fun legacyPreferencesAreMigratedOnceThenCleared() {
        val legacy = context.getSharedPreferences(SettingsStore.LEGACY_PREFS, Context.MODE_PRIVATE)
        legacy.edit {
            putString("theme", "ORIGINAL")
            putString("confirm_layout", "EAST")
            putFloat("deadzone", 0.45f)
            putString("key_remap", "99=CONFIRM")
            putString("emulator_choice", "psx=retroarch:swanstation")
            putBoolean("all_games_folder", false)
            putString("rom_folders", "/storage/emulated/0/ROMs\n/storage/1A2B-3C4D/Games")
        }
        val migrated = store().current
        assertEquals("ORIGINAL", migrated.theme)
        assertEquals(ConfirmLayout.EAST, migrated.confirmLayout)
        assertEquals(0.45f, migrated.deadzone)
        assertEquals(mapOf(KeyEvent.KEYCODE_BUTTON_X to NavAction.CONFIRM), migrated.keyRemap)
        assertEquals(mapOf("psx" to "retroarch:swanstation"), migrated.emulatorChoice)
        assertFalse(migrated.allGamesFolder)
        assertEquals(listOf("/storage/emulated/0/ROMs", "/storage/1A2B-3C4D/Games"), migrated.romFolders)

        assertTrue(File(config, "settings.json").readText().contains("ORIGINAL"))
        assertTrue(legacy.getBoolean(SettingsStore.KEY_MIGRATED, false))
        assertFalse(legacy.contains("theme"))
        // The JSON files are the source of truth from now on.
        assertEquals(migrated, store().current)
    }

    @Test fun unreadableFileIsSetAsideNotLost() {
        store().apply { update { it.copy(theme = "CRIMSON") }; flush() }
        val settingsFile = File(config, "settings.json")
        settingsFile.writeText("""{"display": {"theme": "OCEAN",""") // a hand edit gone wrong
        val s = store()
        assertEquals("LAVENDER", s.current.theme) // that file's part falls back to defaults
        s.flush()
        assertTrue(File(config, "settings.json.bad").readText().contains("OCEAN"))
        assertTrue(settingsFile.readText().contains("LAVENDER")) // rewritten valid
    }

    @Test fun editsMadeOutsideXrossArePickedUp() {
        val s = store()
        s.flush()
        val file = File(config, "settings.json")
        file.writeText(file.readText().replace("\"wave\": true", "\"wave\": false"))
        assertTrue(s.reloadIfChanged())
        assertFalse(s.current.waveEnabled)
        assertFalse(s.reloadIfChanged()) // nothing new
    }

    @Test fun ownPendingWritesAreNotMistakenForOutsideEdits() {
        val s = store()
        s.update { it.copy(soundVolume = 0.5f) }
        assertFalse(s.reloadIfChanged())
        assertEquals(0.5f, s.current.soundVolume)
    }

    @Test fun grantingAccessCarriesSettingsToTheSharedFolder() {
        val s = store()
        s.update { it.copy(theme = "CRIMSON") }
        granted = true
        assertTrue(environment.refresh())
        assertFalse(s.relocate(environment.current)) // nothing there yet: current settings are written
        s.flush()
        assertTrue(File(shared, "config/settings.json").readText().contains("CRIMSON"))
        assertEquals(File(shared, "config"), s.configFolder)
    }

    @Test fun configAlreadyInTheSharedFolderWins() {
        // An Xross folder from another device (or a previous install) is plugged in.
        File(shared, "config").mkdirs()
        File(shared, "config/settings.json").writeText("""{"display": {"theme": "OCEAN", "allGamesFolder": false}}""")
        val s = store()
        s.update { it.copy(theme = "CRIMSON") }
        granted = true
        environment.refresh()
        assertTrue(s.relocate(environment.current))
        assertEquals("OCEAN", s.current.theme)
        assertFalse(s.current.allGamesFolder)
    }

    @Test fun systemOverridesFromSystemsJsonReachThePlatformRegistry() {
        store().flush()
        val file = File(config, "systems.json")
        file.writeText(file.readText().replaceFirst("\"extraFolders\": []", "\"extraFolders\": [\"Famicom Disk\"]"))
        val s = store()
        assertEquals(SystemOverride(extraFolders = setOf("Famicom Disk")), s.current.systemOverrides["nes"])
        assertEquals("nes", PlatformRegistry.forFolder("famicomdisk")?.id)
        // Built-in folder names can't be taken over.
        assertEquals("snes", PlatformRegistry.forFolder("snes")?.id)
    }

    @Test fun credentialsNeverReachTheConfigFiles() {
        CredentialStore(context).update {
            it.copy(screenScraperUser = "ssuser-7731", screenScraperPassword = "hunter2-secret", steamGridDbKey = "sgdb-key-0042")
        }
        store().apply {
            update { it.copy(theme = "CRIMSON", scrapeAutomatically = true) }
            flush()
        }
        val all = config.listFiles().orEmpty().filter { it.isFile }.joinToString("\n") { it.readText() }
        for (secret in listOf("ssuser-7731", "hunter2-secret", "sgdb-key-0042")) assertFalse(secret in all)
        for (key in listOf("password", "apiKey", "steamGridDbKey", "screenScraperUser")) assertFalse(key in all)
    }

    @Test fun defaultsMatchANeverConfiguredInstall() {
        assertEquals(XrossSettings(), store().current)
    }
}
