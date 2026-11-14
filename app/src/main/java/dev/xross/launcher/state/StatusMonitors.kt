package dev.xross.launcher.state

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The default network for the status bar's `WIFI` slot (2b.7a), from `ConnectivityManager`'s
 * default-network callback: Wi-Fi, cellular, ethernet or offline. Wi-Fi strength comes in 4
 * levels from `NetworkCapabilities.signalStrength` on API 29+, and on API 28 from
 * `WifiManager.getConnectionInfo().rssi` through `WifiManager.calculateSignalLevel`; either is
 * re-read on `RSSI_CHANGED_ACTION` (needs `ACCESS_WIFI_STATE`, a normal permission). The SSID is
 * never read, so no location permission is involved.
 *
 * Nothing runs until [status] is collected, and nothing is polled: updates are the system's.
 */
class NetworkMonitor(context: Context) {
    private val appContext = context.applicationContext
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
    private val wifi = appContext.getSystemService(WifiManager::class.java)

    fun status(): Flow<NetworkStatus> = callbackFlow {
        val cm = connectivity ?: run {
            trySend(NetworkStatus.OFFLINE)
            awaitClose { }
            return@callbackFlow
        }
        var caps: NetworkCapabilities? = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull()
        var rssi: Int? = null

        fun publish() {
            trySend(statusOf(caps, rssi))
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                caps = capabilities
                publish()
            }

            override fun onLost(network: Network) {
                caps = null
                rssi = null
                publish()
            }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                rssi = intent.getIntExtra(WifiManager.EXTRA_NEW_RSSI, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
                publish()
            }
        }
        publish()
        runCatching { cm.registerDefaultNetworkCallback(callback) }
        val registered = runCatching {
            ContextCompat.registerReceiver(appContext, receiver, IntentFilter(WifiManager.RSSI_CHANGED_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        }.isSuccess
        awaitClose {
            runCatching { cm.unregisterNetworkCallback(callback) }
            if (registered) runCatching { appContext.unregisterReceiver(receiver) }
        }
    }.conflate().distinctUntilChanged()

    /** [caps] as a status; [rssi] from the last `RSSI_CHANGED_ACTION`, if any, beats an older reading. */
    private fun statusOf(caps: NetworkCapabilities?, rssi: Int?): NetworkStatus {
        caps ?: return NetworkStatus.OFFLINE
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return NetworkStatus.OFFLINE
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkStatus(NetworkKind.WIFI, wifiLevel(caps, rssi))
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkStatus(NetworkKind.ETHERNET)
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkStatus(NetworkKind.CELLULAR)
            else -> NetworkStatus(NetworkKind.WIFI, StatusLevels.LEVELS - 1)
        }
    }

    @Suppress("DEPRECATION")
    private fun wifiLevel(caps: NetworkCapabilities, rssi: Int?): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val strength = rssi ?: caps.signalStrength.takeIf { it != NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED }
                ?: runCatching { wifi?.connectionInfo?.rssi }.getOrNull()
            return strength?.let(StatusLevels::wifiLevel) ?: (StatusLevels.LEVELS - 1)
        }
        // Android 9: no signal strength in the capabilities; the connection's RSSI instead.
        val r = rssi ?: runCatching { wifi?.connectionInfo?.rssi }.getOrNull() ?: return StatusLevels.LEVELS - 1
        return WifiManager.calculateSignalLevel(r, StatusLevels.LEVELS)
    }
}

/** `STREAM_MUSIC` as Android reports it: index [volume] of [max], and the mute switch. */
data class MediaVolume(val volume: Int, val max: Int, val muted: Boolean = false) {
    /** 0 (muted) to 3 ([StatusLevels.volumeLevel]). */
    val level: Int get() = StatusLevels.volumeLevel(volume, max, muted)
}

/**
 * The system media volume (`AudioManager.STREAM_MUSIC`) for the status bar's `VOLUME` slot
 * (2b.7a), in [StatusLevels.LEVELS] levels with 0 for muted. Observed through a `ContentObserver`
 * on `Settings.System`, where Android persists stream volumes, and the volume-changed broadcast,
 * which arrives without the persisting delay; no polling.
 */
class MediaVolumeMonitor(context: Context) {
    private val appContext = context.applicationContext
    private val audio = appContext.getSystemService(AudioManager::class.java)

    fun read(): MediaVolume {
        val am = audio ?: return MediaVolume(0, 0, false)
        val max = runCatching { am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(0)
        val v = runCatching { am.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(0)
        val muted = runCatching { am.isStreamMute(AudioManager.STREAM_MUSIC) }.getOrDefault(false)
        return MediaVolume(v, max, muted)
    }

    /** The stream's index on every change (a step that keeps the same level still counts as a change). */
    fun volumes(): Flow<MediaVolume> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(read())
            }
        }
        // Android writes the volume to Settings.System up to a second after the key; its (hidden but
        // long-standing) VOLUME_CHANGED_ACTION broadcast comes at once, so the slot shows promptly.
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                trySend(read())
            }
        }
        trySend(read())
        val registered = runCatching {
            appContext.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
        }.isSuccess
        val listening = runCatching {
            ContextCompat.registerReceiver(appContext, receiver, IntentFilter(VOLUME_CHANGED_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        }.isSuccess
        awaitClose {
            if (registered) runCatching { appContext.contentResolver.unregisterContentObserver(observer) }
            if (listening) runCatching { appContext.unregisterReceiver(receiver) }
        }
    }.conflate().distinctUntilChanged()

    private companion object {
        /** `AudioManager.VOLUME_CHANGED_ACTION` (hidden): sent on every stream volume change. */
        const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
    }
}
