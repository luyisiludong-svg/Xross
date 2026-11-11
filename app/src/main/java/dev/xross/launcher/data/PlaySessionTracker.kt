package dev.xross.launcher.data

import android.content.Context
import androidx.core.content.edit

/** Persists the open play session so it survives the launcher process being killed mid-game. */
interface SessionStore {
    fun load(): PlaySessionTracker.Session?
    fun save(session: PlaySessionTracker.Session?)
}

class PrefsSessionStore(context: Context) : SessionStore {
    private val prefs = context.getSharedPreferences("xross_session", Context.MODE_PRIVATE)

    override fun load(): PlaySessionTracker.Session? {
        val id = prefs.getLong(KEY_GAME, -1)
        return if (id < 0) null else PlaySessionTracker.Session(id, prefs.getLong(KEY_START, 0))
    }

    override fun save(session: PlaySessionTracker.Session?) = prefs.edit {
        if (session == null) {
            remove(KEY_GAME).remove(KEY_START)
        } else {
            putLong(KEY_GAME, session.gameId).putLong(KEY_START, session.startedAt)
        }
    }

    private companion object {
        const val KEY_GAME = "game_id"
        const val KEY_START = "started_at"
    }
}

/**
 * Measures how long a game was played: from the moment the launcher goes to the background after
 * a launch until it comes back to the front.
 *
 * A launch only *arms* the tracker. The session starts when the launcher is actually paused
 * within [ARM_WINDOW_MS]; if the emulator never opened, a later unrelated pause is not counted.
 *
 * Limits: this is wall-clock time away from the launcher, so time spent in another app reached
 * from the emulator (or with the device asleep) counts too; a session is capped at
 * [MAX_SESSION_MS].
 */
class PlaySessionTracker(
    private val store: SessionStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Session(val gameId: Long, val startedAt: Long)
    data class Finished(val gameId: Long, val durationMs: Long)

    private var armed: Session? = null

    /** A launch intent is about to be sent for [gameId]. */
    fun arm(gameId: Long) {
        armed = Session(gameId, clock())
    }

    /** The launcher was paused; start the session if a launch was just requested. */
    fun onBackground() {
        val pending = armed ?: return
        armed = null
        val now = clock()
        if (now - pending.startedAt in 0..ARM_WINDOW_MS) store.save(Session(pending.gameId, now))
    }

    /** The launcher is back in front; returns the finished session, if one was open. */
    fun onForeground(): Finished? {
        armed = null
        val session = store.load() ?: return null
        store.save(null)
        val duration = clock() - session.startedAt
        if (duration <= 0) return null // clock changed
        return Finished(session.gameId, duration.coerceAtMost(MAX_SESSION_MS))
    }

    companion object {
        const val ARM_WINDOW_MS = 15_000L
        const val MAX_SESSION_MS = 24 * 3_600_000L
    }
}
