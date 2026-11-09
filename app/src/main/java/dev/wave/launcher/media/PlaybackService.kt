package dev.wave.launcher.media

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dev.wave.launcher.MainActivity
import dev.wave.launcher.WaveApplication

/**
 * Wraps the app's [MusicPlayer] in a MediaSession so music keeps playing (with a notification and
 * lock-screen / headset controls) while a game or another app is in front.
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = (application as WaveApplication).container.music.player
        val openLauncher = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player).setSessionActivity(openLauncher).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Swiped away from recents while paused: stop the service; keep playing if music is on. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        // The player belongs to the app-wide MusicPlayer; only the session is released here.
        session?.release()
        session = null
        super.onDestroy()
    }
}
