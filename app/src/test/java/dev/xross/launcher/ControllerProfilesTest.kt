package dev.xross.launcher

import android.view.KeyEvent
import dev.xross.launcher.input.ConfirmLayout
import dev.xross.launcher.input.ConnectedController
import dev.xross.launcher.input.ControllerKey
import dev.xross.launcher.input.ControllerProfile
import dev.xross.launcher.input.InputConfig
import dev.xross.launcher.input.KeyMapper
import dev.xross.launcher.input.NavAction
import dev.xross.launcher.state.RemapSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerProfilesTest {
    private val dualSense = ConnectedController(ControllerKey(0x054C, 0x0CE6), "DualSense Wireless Controller")
    private val xbox = ConnectedController(ControllerKey(0x045E, 0x0B13), "Xbox Wireless Controller")

    @Test fun keysAreStableHexIds() {
        assertEquals("054c:0ce6", dualSense.key.id)
        assertEquals("054C:0CE6", dualSense.key.display)
        assertEquals(dualSense.key, ControllerKey.parse("054c:0ce6"))
        assertEquals(dualSense.key, ControllerKey.parse("054C:0CE6"))
    }

    @Test fun devicesWithoutIdsHaveNoKey() {
        assertNull(ControllerKey.of(0, 0))
        assertNull(ControllerKey.parse("0000:0000"))
        assertNull(ControllerKey.parse("nonsense"))
        assertNull(ControllerKey.parse("054c"))
        assertEquals(ControllerKey(0x1234, 0), ControllerKey.of(0x1234, 0))
    }

    @Test fun aControllersOwnProfileWinsOverTheGlobalMapping() {
        val global = mapOf(KeyEvent.KEYCODE_BUTTON_X to NavAction.CONFIRM)
        val own = mapOf(KeyEvent.KEYCODE_BUTTON_Y to NavAction.CONFIRM)
        val config = InputConfig(
            remap = global,
            profiles = mapOf(dualSense.key.id to ControllerProfile(dualSense.name, own)),
        )
        assertEquals(own, config.remapFor(dualSense.key))
        // Another model and unidentified devices fall back to the global mapping.
        assertEquals(global, config.remapFor(xbox.key))
        assertEquals(global, config.remapFor(null))

        // End to end through the key mapper: Y confirms only on the DualSense.
        val onDualSense = KeyMapper.map(KeyEvent.KEYCODE_BUTTON_Y, ConfirmLayout.SOUTH, config.remapFor(dualSense.key))
        val onXbox = KeyMapper.map(KeyEvent.KEYCODE_BUTTON_Y, ConfirmLayout.SOUTH, config.remapFor(xbox.key))
        assertEquals(NavAction.CONFIRM, onDualSense)
        assertEquals(NavAction.OPTIONS, onXbox)
    }

    @Test fun remapSessionLocksOntoTheFirstController() {
        var s = RemapSession()
        assertFalse(s.deviceChosen)
        s = s.capture(KeyEvent.KEYCODE_DPAD_UP, dualSense)
        assertEquals(dualSense, s.controller)
        assertTrue(s.deviceChosen)
        // A second pad pressing a button is ignored entirely.
        val ignored = s.capture(KeyEvent.KEYCODE_BUTTON_A, xbox)
        assertEquals(s, ignored)
        // So is a keyboard without IDs.
        assertEquals(s, s.capture(KeyEvent.KEYCODE_ENTER, null))
        s = s.capture(KeyEvent.KEYCODE_DPAD_DOWN, dualSense)
        assertEquals(2, s.step)
        assertEquals(mapOf(KeyEvent.KEYCODE_DPAD_UP to NavAction.UP, KeyEvent.KEYCODE_DPAD_DOWN to NavAction.DOWN), s.captured)
    }

    @Test fun sessionStartedFromADeviceWithoutIdsBuildsTheGlobalMapping() {
        var s = RemapSession().capture(KeyEvent.KEYCODE_DPAD_UP, null)
        assertTrue(s.deviceChosen)
        assertNull(s.controller)
        // An identified pad joining later can't take over the session.
        assertEquals(s, s.capture(KeyEvent.KEYCODE_DPAD_DOWN, dualSense))
        RemapSession.STEPS.drop(1).forEachIndexed { i, _ -> s = s.capture(200 + i, null) }
        assertTrue(s.isDone)
        assertNull(s.controller)
        assertEquals(RemapSession.STEPS.toSet(), s.captured.values.toSet())
    }
}
