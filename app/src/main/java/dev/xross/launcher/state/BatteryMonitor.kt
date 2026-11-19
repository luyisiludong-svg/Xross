package dev.xross.launcher.state

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import dev.xross.launcher.theme.BatteryLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Battery level and charging state from `ACTION_BATTERY_CHANGED` (sticky, so [start] reads the
 * current state at once), for the battery tint and `battery` context rules. Updates arrive when
 * the level or plug state changes, never per frame. A device without a battery (a TV, a box)
 * reads as null, so the status bar's battery slot and the battery rules stay out of the way.
 */
class BatteryMonitor(context: Context) {
    private val appContext = context.applicationContext
    private val _level = MutableStateFlow<BatteryLevel?>(null)

    /** Null until the first reading, and on devices without a battery. */
    val level: StateFlow<BatteryLevel?> = _level.asStateFlow()

    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            _level.value = parse(intent)
        }
    }

    fun start() {
        if (registered) return
        registered = true
        val sticky = runCatching {
            ContextCompat.registerReceiver(appContext, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        }.getOrNull()
        _level.value = parse(sticky)
    }

    fun stop() {
        if (!registered) return
        registered = false
        runCatching { appContext.unregisterReceiver(receiver) }
    }

    /** For tests: report a reading as if the system had broadcast it. */
    internal fun report(level: BatteryLevel?) {
        _level.value = level
    }

    companion object {
        /** A battery broadcast as a [BatteryLevel]; null when it carries no level or the device has no battery. */
        fun parse(intent: Intent?): BatteryLevel? {
            intent ?: return null
            if (!hasBattery(intent)) return null
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level < 0 || scale <= 0) return null
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            return BatteryLevel((level * 100 / scale).coerceIn(0, 100), charging)
        }

        /**
         * False when the broadcast says there's no battery (`EXTRA_PRESENT`, as TVs and boxes
         * send it), or when it describes the empty stand-in some of them report while claiming
         * one: 0%, no voltage, unknown health. A real battery at 0% still has a voltage.
         */
        fun hasBattery(intent: Intent): Boolean {
            if (!intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true)) return false
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
            val health = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)
            return !(level == 0 && voltage <= 0 && health == BatteryManager.BATTERY_HEALTH_UNKNOWN)
        }
    }
}
