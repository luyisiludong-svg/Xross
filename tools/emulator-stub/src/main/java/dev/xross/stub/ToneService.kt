package dev.xross.stub

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.IBinder
import android.util.Log
import kotlin.math.PI
import kotlin.math.sin

/**
 * "Another app playing media" for Xross's theme-music checks (2b.5c): a quiet 330 Hz tone on a
 * looping AudioTrack with USAGE_MEDIA, in a foreground service so it keeps playing behind Xross.
 *
 *   adb shell am start-foreground-service -n com.retroarch.aarch64/dev.xross.stub.ToneService
 *   adb shell am stopservice -n com.retroarch.aarch64/dev.xross.stub.ToneService
 */
class ToneService : Service() {
    private var track: AudioTrack? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Stub tone", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, CHANNEL).setContentTitle("Stub tone").setSmallIcon(android.R.drawable.ic_media_play).build()
        if (android.os.Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) else startForeground(1, n)
        if (track == null) track = tone().also { it.play() }
        Log.i(TAG, "tone playing")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        track?.run { stop(); release() }
        track = null
        Log.i(TAG, "tone stopped")
        super.onDestroy()
    }

    /** One second of a 330 Hz sine, looped forever (a whole number of cycles, so no click). */
    private fun tone(): AudioTrack {
        val rate = 44100
        val samples = ShortArray(rate) { i -> (0.1 * Short.MAX_VALUE * sin(2 * PI * 330 * i / rate)).toInt().toShort() }
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(samples.size * 2)
            .build()
            .apply {
                write(samples, 0, samples.size)
                setLoopPoints(0, samples.size, -1)
            }
    }

    private companion object {
        const val TAG = "XrossStub"
        const val CHANNEL = "tone"
    }
}
