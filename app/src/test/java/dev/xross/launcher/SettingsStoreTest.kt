package dev.xross.launcher

import android.content.Context
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import dev.xross.launcher.data.SettingsStore
import dev.xross.launcher.input.ConfirmLayout
import dev.xross.launcher.input.ControllerProfile
import dev.xross.launcher.input.NavAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun hardwareOnlyDeadzoneAndClassicGamesLayoutSurviveARestart() {
        val store = SettingsStore(context)
        assertTrue(store.current.allGamesFolder)
        store.update { it.copy(deadzone = 0f, allGamesFolder = false) }
        val reloaded = SettingsStore(context).current
        assertEquals(0f, reloaded.deadzone)
        assertEquals(false, reloaded.allGamesFolder)
    }

    @Test fun everySettingSurvivesARestart() {
        val store = SettingsStore(context)
        store.update {
            it.copy(
                romFolders = listOf("/storage/emulated/0/ROMs", "/storage/1A2B-3C4D/Games"),
                autoDetectFolders = false,
                knownAutoFolders = listOf("/storage/1A2B-3C4D/ROMs"),
                confirmLayout = ConfirmLayout.EAST,
                deadzone = 0.45f,
                axialDeadzone = 0.2f,
                keyRemap = mapOf(KeyEvent.KEYCODE_BUTTON_X to NavAction.CONFIRM),
                controllerProfiles = mapOf(
                    // Names are free text: separators used by the other encodings must survive.
                    "054c:0ce6" to ControllerProfile(
                        "DualSense = \"Wireless\"\nController",
                        mapOf(KeyEvent.KEYCODE_BUTTON_Y to NavAction.CONFIRM, KeyEvent.KEYCODE_BUTTON_L2 to NavAction.PREV_CATEGORY),
                    ),
                    "045e:0b13" to ControllerProfile("Xbox Wireless Controller", mapOf(KeyEvent.KEYCODE_BUTTON_A to NavAction.BACK)),
                ),
                emulatorChoice = mapOf("psx" to "retroarch:swanstation"),
                waveEnabled = false,
                waveSpeed = 1.5f,
                theme = "ORIGINAL",
                showButtonHints = false,
                soundEffects = false,
                soundVolume = 0.25f,
                bootSound = false,
                gameBootSound = false,
            )
        }
        assertEquals(store.current, SettingsStore(context).current)
    }

    @Test fun defaultsWhenNothingSaved() {
        val s = SettingsStore(context).current
        assertEquals(ConfirmLayout.AUTO, s.confirmLayout)
        assertEquals("LAVENDER", s.theme)
        assertEquals(true, s.soundEffects)
        assertEquals(true, s.showButtonHints)
        assertEquals(0f, s.axialDeadzone)
        assertTrue(s.controllerProfiles.isEmpty())
    }

    @Test fun unreadableProfilesAreDroppedNotFatal() {
        assertTrue(SettingsStore.decodeProfiles("{not json").isEmpty())
        assertTrue(SettingsStore.decodeProfiles(null).isEmpty())
        // Bad controller ids, unknown actions and empty mappings are skipped; good entries survive.
        val raw = """{"bogus": {"name": "x", "remap": {"96": "CONFIRM"}},
            "054c:0ce6": {"name": "Pad", "remap": {"96": "NOT_AN_ACTION", "97": "BACK"}},
            "045e:0b13": {"name": "Empty", "remap": {}}}"""
        val profiles = SettingsStore.decodeProfiles(raw)
        assertEquals(setOf("054c:0ce6"), profiles.keys)
        assertEquals(mapOf(97 to NavAction.BACK), profiles.getValue("054c:0ce6").remap)
    }

    @Test fun profilesRoundTripThroughTheirEncoding() {
        val profiles = mapOf("054c:0ce6" to ControllerProfile("Pad, \"1\"", mapOf(96 to NavAction.CONFIRM)))
        assertEquals(profiles, SettingsStore.decodeProfiles(SettingsStore.encodeProfiles(profiles)))
    }
}
