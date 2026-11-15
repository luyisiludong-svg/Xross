package dev.xross.launcher

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.xross.launcher.input.NavAction
import dev.xross.launcher.state.AvatarRow
import dev.xross.launcher.state.EditorController
import dev.xross.launcher.state.EditorsState
import dev.xross.launcher.state.TextPrompt
import dev.xross.launcher.state.WaveRow
import dev.xross.launcher.state.XmbViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Wave settings and Profile customization panels out of [XmbViewModel] (step 6). The view
 * model tests in `XmbViewModelTest` and `Phase2bFeaturesTest` still open them through the menu;
 * these drive [EditorController] on its own.
 */
@RunWith(RobolectricTestRunner::class)
class EditorControllerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private class FakeHost : EditorController.Host {
        val toasts = mutableListOf<String>()
        val failures = mutableListOf<String>()
        var refusals = 0
        val prompts = mutableListOf<TextPrompt>()
        override fun toast(message: String) { toasts += message }
        override fun fail(message: String) { failures += message }
        override fun refuse() { refusals++ }
        override fun promptText(prompt: TextPrompt) { prompts += prompt }
    }

    private val container = AppContainer(context)
    private val host = FakeHost()
    private var defaultsRead = 0
    private val editors = EditorController(
        settings = container.settings,
        sounds = container.sounds,
        waveDefaults = { defaultsRead++; null },
        host = host,
    )

    @Test fun closedPanelsTakeNoInput() {
        assertFalse(editors.onWaveAction(NavAction.DOWN))
        assertFalse(editors.onAvatarAction(NavAction.DOWN))
        editors.onWaveSliderChanged(WaveRow.SPEED.ordinal, 1f)
        editors.onAvatarRowTapped(0)
        assertEquals(EditorsState(), editors.state.value)
    }

    @Test fun waveSettingsMoveRowsRefuseAtTheEdgeAndClose() {
        editors.openWave()
        val start = editors.state.value.wave!!.index
        assertTrue(editors.onWaveAction(NavAction.UP))
        assertEquals(start - 1, editors.state.value.wave!!.index)
        editors.onWaveAction(NavAction.UP) // the first row: nowhere to go
        assertEquals(1, host.refusals)

        assertTrue(editors.onWaveAction(NavAction.BACK))
        assertNull(editors.state.value.wave)
        assertFalse(editors.onWaveAction(NavAction.BACK))
    }

    @Test fun waveSliderFocusesItsRowAndSaves() {
        editors.openWave()
        editors.onWaveSliderChanged(WaveRow.TURBULENCE.ordinal, 1f)
        assertEquals(WaveRow.TURBULENCE.ordinal, editors.state.value.wave!!.index)
        val max = container.settings.current.waveTurbulence
        editors.onWaveSliderChanged(WaveRow.TURBULENCE.ordinal, 0f)
        assertNotEquals(max, container.settings.current.waveTurbulence)
    }

    @Test fun waveResetReadsTheActivePacksDefaults() {
        editors.openWave()
        editors.onWaveRowTapped(WaveRow.RESET.ordinal) // focus
        assertEquals(0, defaultsRead)
        editors.onWaveRowTapped(WaveRow.RESET.ordinal) // tapping the focused row confirms
        assertEquals(1, defaultsRead)
    }

    @Test fun customColorRowPromptsAndTheAnswerIsValidated() {
        editors.openAvatar()
        editors.onAvatarRowTapped(AvatarRow.CUSTOM_COLOR.ordinal)
        editors.onAvatarRowTapped(AvatarRow.CUSTOM_COLOR.ordinal)
        val before = container.settings.current.avatarStyle.borderColorHex
        assertEquals(listOf<TextPrompt>(TextPrompt.AvatarBorderColor(before)), host.prompts)

        editors.onAvatarColorEntered("   ")
        editors.onAvatarColorEntered("not-a-color")
        assertEquals(before, container.settings.current.avatarStyle.borderColorHex)
        assertEquals(1, host.failures.size)

        editors.onAvatarColorEntered(" a1b2c3 ")
        assertEquals("#A1B2C3", container.settings.current.avatarStyle.borderColorHex)
        assertEquals(listOf("Avatar ring color: #A1B2C3"), host.toasts)
    }

    @Test fun nowPlayingDismissesBothPanels() {
        editors.openWave()
        editors.openAvatar()
        editors.dismissAll()
        assertEquals(EditorsState(), editors.state.value)
    }
}
