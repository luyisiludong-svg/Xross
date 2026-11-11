package dev.xross.launcher.storage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat

sealed interface VolumeEvent {
    /** Mount point from the broadcast, when the system provides it. */
    val path: String?

    data class Mounted(override val path: String?) : VolumeEvent
    data class Removed(override val path: String?) : VolumeEvent
}

/**
 * Hot-swap detection: a dynamically registered receiver for the media mount broadcasts (microSD
 * inserted or pulled, USB-OTG drive attached or detached).
 */
class VolumeMonitor(private val onEvent: (VolumeEvent) -> Unit) : BroadcastReceiver() {

    fun register(context: Context) {
        val filter = IntentFilter().apply {
            ACTIONS.forEach(::addAction)
            addDataScheme("file") // these broadcasts carry file://<mount point>
        }
        ContextCompat.registerReceiver(context, this, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun unregister(context: Context) {
        runCatching { context.unregisterReceiver(this) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        eventFor(intent.action, intent.data?.path)?.let(onEvent)
    }

    companion object {
        private val ACTIONS = listOf(
            Intent.ACTION_MEDIA_MOUNTED,
            Intent.ACTION_MEDIA_UNMOUNTED,
            Intent.ACTION_MEDIA_EJECT,
            Intent.ACTION_MEDIA_REMOVED,
            Intent.ACTION_MEDIA_BAD_REMOVAL,
        )

        fun eventFor(action: String?, path: String?): VolumeEvent? = when (action) {
            Intent.ACTION_MEDIA_MOUNTED -> VolumeEvent.Mounted(path)
            Intent.ACTION_MEDIA_UNMOUNTED,
            Intent.ACTION_MEDIA_EJECT,
            Intent.ACTION_MEDIA_REMOVED,
            Intent.ACTION_MEDIA_BAD_REMOVAL -> VolumeEvent.Removed(path)
            else -> null
        }
    }
}
