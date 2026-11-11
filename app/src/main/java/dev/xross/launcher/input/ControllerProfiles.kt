package dev.xross.launcher.input

import androidx.compose.runtime.Immutable
import java.util.Locale

/**
 * A controller model, identified by its USB/Bluetooth vendor and product IDs. Two pads of the same
 * model share a key (and so a saved mapping), which is what users expect: a remap made for one
 * DualSense applies to every DualSense.
 */
@Immutable
data class ControllerKey(val vendorId: Int, val productId: Int) {
    /** Stable storage id, e.g. "054c:0ce6". */
    val id: String get() = String.format(Locale.ROOT, "%04x:%04x", vendorId, productId)

    /** "054C:0CE6", for settings rows. */
    val display: String get() = id.uppercase(Locale.ROOT)

    companion object {
        /**
         * Null for devices without real IDs (virtual keyboards, adb-injected input, some built-in
         * handheld pads); those keep using the global mapping.
         */
        fun of(vendorId: Int, productId: Int): ControllerKey? =
            if (vendorId == 0 && productId == 0) null else ControllerKey(vendorId, productId)

        fun parse(id: String): ControllerKey? {
            val parts = id.split(':')
            if (parts.size != 2) return null
            val vendor = parts[0].toIntOrNull(16) ?: return null
            val product = parts[1].toIntOrNull(16) ?: return null
            return of(vendor, product)
        }
    }
}

/** The controller a key press came from: its model key and the name Android reports. */
@Immutable
data class ConnectedController(val key: ControllerKey, val name: String)

/** A saved button mapping for one controller model. */
@Immutable
data class ControllerProfile(
    /** Device name when the mapping was saved; shown in Settings ▸ Controls. */
    val name: String,
    val remap: Map<Int, NavAction>,
)

/**
 * Picks the key mapping for a press: the controller's own saved profile when it has one, the
 * global mapping otherwise (keyboards, unidentified devices, and pads never remapped on their own).
 */
fun remapFor(
    controller: ControllerKey?,
    profiles: Map<String, ControllerProfile>,
    global: Map<Int, NavAction>,
): Map<Int, NavAction> = controller?.let { profiles[it.id]?.remap } ?: global
