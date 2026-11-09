package dev.wave.launcher

import android.content.Context
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import dev.wave.launcher.data.SettingsStore
import dev.wave.launcher.input.ConfirmLayout
import dev.wave.launcher.input.NavAction
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun everySettingSurvivesARestart() {
        val store = SettingsStore(context)
        store.update {
            it.copy(
                romFolders = listOf("/storage/emulated/0/ROMs", "/storage/1A2B-3C4D/Games"),
                autoDetectFolders = false,
                confirmLayout = ConfirmLayout.EAST,
                deadzone = 0.45f,
                keyRemap = mapOf(KeyEvent.KEYCODE_BUTTON_X to NavAction.CONFIRM),
                emulatorChoice = mapOf("psx" to "retroarch:swanstation"),
                waveEnabled = false,
                waveSpeed = 1.5f,
                theme = "CRIMSON",
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
    }
}
