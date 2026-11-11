package dev.xross.launcher

import android.view.KeyEvent
import dev.xross.launcher.input.ConfirmLayout
import dev.xross.launcher.input.ControllerFamily
import dev.xross.launcher.input.KeyMapper
import dev.xross.launcher.input.NavAction
import dev.xross.launcher.input.resolve
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyMapperTest {
    @Test fun southLayoutConfirmsWithA() {
        assertEquals(NavAction.CONFIRM, KeyMapper.map(KeyEvent.KEYCODE_BUTTON_A, ConfirmLayout.SOUTH))
        assertEquals(NavAction.BACK, KeyMapper.map(KeyEvent.KEYCODE_BUTTON_B, ConfirmLayout.SOUTH))
    }

    @Test fun eastLayoutSwapsConfirmAndBack() {
        assertEquals(NavAction.CONFIRM, KeyMapper.map(KeyEvent.KEYCODE_BUTTON_B, ConfirmLayout.EAST))
        assertEquals(NavAction.BACK, KeyMapper.map(KeyEvent.KEYCODE_BUTTON_A, ConfirmLayout.EAST))
    }

    @Test fun remapWinsOverDefaults() {
        val remap = mapOf(KeyEvent.KEYCODE_BUTTON_A to NavAction.OPTIONS)
        assertEquals(NavAction.OPTIONS, KeyMapper.map(KeyEvent.KEYCODE_BUTTON_A, ConfirmLayout.SOUTH, remap))
    }

    @Test fun autoLayoutFollowsControllerFamily() {
        assertEquals(ConfirmLayout.EAST, ConfirmLayout.AUTO.resolve(ControllerFamily.NINTENDO))
        assertEquals(ConfirmLayout.SOUTH, ConfirmLayout.AUTO.resolve(ControllerFamily.PLAYSTATION))
        assertEquals(ConfirmLayout.SOUTH, ConfirmLayout.SOUTH.resolve(ControllerFamily.NINTENDO))
    }

    @Test fun vendorDetection() {
        assertEquals(ControllerFamily.PLAYSTATION, ControllerFamily.fromVendor(0x054C, true))
        assertEquals(ControllerFamily.NINTENDO, ControllerFamily.fromVendor(0x057E, true))
        assertEquals(ControllerFamily.GENERIC, ControllerFamily.fromVendor(0x1234, true))
        assertEquals(ControllerFamily.KEYBOARD, ControllerFamily.fromVendor(0x054C, false))
    }

    @Test fun unknownKeyIsUnmapped() {
        assertEquals(null, KeyMapper.map(KeyEvent.KEYCODE_VOLUME_UP, ConfirmLayout.SOUTH))
    }
}
