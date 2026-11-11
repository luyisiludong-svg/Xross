package dev.xross.launcher.input

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Receives normalized input from [GamepadHandler]. */
interface InputSink {
    fun onAction(action: NavAction)

    /**
     * Called with every fresh key press before mapping. Return true to consume it (remap capture).
     * [controller] is the identified controller model it came from, or null (keyboards without
     * IDs, virtual and injected input).
     */
    fun onRawKeyDown(keyCode: Int, controller: ConnectedController?): Boolean = false

    fun onControllerUsed(family: ControllerFamily) {}
}

data class InputConfig(
    val confirm: ConfirmLayout = ConfirmLayout.AUTO,
    val deadzone: Float = 0.35f,
    /** Global mapping, used for any device without its own entry in [profiles]. */
    val remap: Map<Int, NavAction> = emptyMap(),
    val axialDeadzone: Float = 0f,
    /** [ControllerKey.id] → per-controller mapping. */
    val profiles: Map<String, ControllerProfile> = emptyMap(),
) {
    fun remapFor(controller: ControllerKey?): Map<Int, NavAction> = remapFor(controller, profiles, remap)
}

/**
 * Gamepad abstraction layer: turns Android key and joystick events into [NavAction]s.
 *
 * Joystick motion is always consumed so that ViewRootImpl doesn't also synthesize D-pad key
 * events from the same stick/hat movement (which would double-step the cursor).
 */
class GamepadHandler(
    private val scope: CoroutineScope,
    private val sink: InputSink,
    private val config: () -> InputConfig,
) {
    private val stick = StickTracker()
    private val hat = StickTracker(deadzone = 0.5f, snapbackWindowMs = 0)
    private var repeatJob: Job? = null
    private var lastFamily: ControllerFamily? = null

    fun onKeyEvent(event: KeyEvent): Boolean {
        val cfg = config()
        val device = event.device
        val family = familyOf(device)
        val confirm = cfg.confirm.resolve(family)
        val key = device?.let { ControllerKey.of(it.vendorId, it.productId) }
        val action = KeyMapper.map(event.keyCode, confirm, cfg.remapFor(key))
        val isFreshPress = event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0

        if (isFreshPress) {
            reportFamily(family)
            val controller = key?.let { ConnectedController(it, device.name.orEmpty()) }
            if (sink.onRawKeyDown(event.keyCode, controller)) return true
        }
        if (action == null) return false

        if (event.action == KeyEvent.ACTION_DOWN) {
            // Let the OS key-repeat drive held D-pad keys; never auto-repeat confirm/back.
            if (event.repeatCount == 0 || action.isDirectional) sink.onAction(action)
        }
        // Consume UP as well so Android doesn't generate a fallback KEYCODE_BACK.
        return true
    }

    fun onMotionEvent(event: MotionEvent): Boolean {
        val isJoystick = event.isFromSource(InputDevice.SOURCE_JOYSTICK) ||
            event.isFromSource(InputDevice.SOURCE_GAMEPAD)
        if (!isJoystick || event.action != MotionEvent.ACTION_MOVE) return false

        reportFamily(familyOf(event.device))
        val cfg = config()
        stick.deadzone = cfg.deadzone
        stick.axialDeadzone = cfg.axialDeadzone
        val now = event.eventTime

        val stickPress = stick.update(
            centered(event, MotionEvent.AXIS_X),
            centered(event, MotionEvent.AXIS_Y),
            now,
        )
        val hatPress = hat.update(
            event.getAxisValue(MotionEvent.AXIS_HAT_X),
            event.getAxisValue(MotionEvent.AXIS_HAT_Y),
            now,
        )

        val press = hatPress ?: stickPress
        val held = hat.current ?: stick.current
        when {
            press != null -> startRepeat(press)
            held == null -> stopRepeat()
        }
        return true
    }

    fun release() {
        stopRepeat()
        stick.reset()
        hat.reset()
    }

    private fun startRepeat(action: NavAction) {
        repeatJob?.cancel()
        sink.onAction(action)
        repeatJob = scope.launch {
            delay(INITIAL_REPEAT_DELAY_MS)
            while (isActive && (hat.current ?: stick.current) == action) {
                sink.onAction(action)
                delay(REPEAT_INTERVAL_MS)
            }
        }
    }

    private fun stopRepeat() {
        repeatJob?.cancel()
        repeatJob = null
    }

    private fun reportFamily(family: ControllerFamily) {
        if (family != lastFamily) {
            lastFamily = family
            sink.onControllerUsed(family)
        }
    }

    private fun centered(event: MotionEvent, axis: Int): Float {
        val range = event.device?.getMotionRange(axis, event.source) ?: return 0f
        val value = event.getAxisValue(axis)
        // Hardware flat zone; our own axial and radial deadzones are applied on top.
        return if (kotlin.math.abs(value) > range.flat) value else 0f
    }

    companion object {
        private const val INITIAL_REPEAT_DELAY_MS = 380L
        private const val REPEAT_INTERVAL_MS = 90L

        fun familyOf(device: InputDevice?): ControllerFamily {
            if (device == null) return ControllerFamily.KEYBOARD
            val sources = device.sources
            val isGamepad = sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
                sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
            return ControllerFamily.fromVendor(device.vendorId, isGamepad)
        }
    }
}
