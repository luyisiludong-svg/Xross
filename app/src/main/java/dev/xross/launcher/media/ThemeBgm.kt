package dev.xross.launcher.media

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dev.xross.launcher.audio.BgmOutput
import dev.xross.launcher.audio.BgmPolicy
import dev.xross.launcher.audio.BgmQueue
import dev.xross.launcher.audio.BgmState
import dev.xross.launcher.theme.pack.BgmTrack

/**
 * The theme pack's ambient music loop (2b.5c): an ExoPlayer on `REPEAT_MODE_ONE` (gapless on a
 * single item), separate from Xross's music player.
 *
 * - `USAGE_GAME` / `CONTENT_TYPE_MUSIC` and **no audio focus**: it never pauses another app's
 *   podcast, and it starts no media session or notification. [dev.xross.launcher.audio.BgmPolicy]
 *   keeps it silent while other media plays instead, and the platform ducks it under a call or a
 *   notification sound.
 * - Created on the first [apply] that plays, released when the look has no loop or on [release].
 * - A track change while playing crossfades over [BgmPolicy.FADE_IN_MS] on a second, short-lived
 *   player; every other move is a linear fade of the player's own volume.
 * - A list (several files: a pack's `audio.bgmFiles`, or a folder picked in Settings, 0.2.2.0 § 5)
 *   plays one file at a time in [BgmQueue] order and crossfades over [BgmQueue.CROSSFADE_MS]
 *   into the next. The queue outlives a stop, so after a game the list goes on with the next track.
 * - Main thread only, like ExoPlayer itself.
 */
class ThemeBgm(private val context: Context) : BgmOutput {
    private val handler = Handler(Looper.getMainLooper())

    /** The loop playing (or paused, or stopped and ready to restart from 0). */
    private var deck: Deck? = null

    /** Loops fading out after a track change or a pack without music, released when silent. */
    private val outgoing = ArrayList<Deck>()

    override var onActiveChanged: (() -> Unit)? = null

    /** Each list's place, by [BgmTrack.key], kept while Xross runs. */
    private val queues = HashMap<String, BgmQueue>()

    /** The level the deck plays at, for the next track's crossfade. */
    private var level = 0f

    private fun queueOf(track: BgmTrack) = queues.getOrPut(track.key) { BgmQueue(track.files.size, track.shuffle) }

    /** A deck on [track]'s next file (the only file for a loop). */
    private fun newDeck(track: BgmTrack): Deck = Deck(track, if (track.isList) queueOf(track).next() else 0)

    override val activePlayers: Int
        get() = (deck?.let { if (it.player.isPlaying) 1 else 0 } ?: 0) + outgoing.count { it.player.isPlaying }

    override fun apply(track: BgmTrack?, state: BgmState, volume: Float, fadeMs: Long) {
        if (state == BgmState.PLAYING) level = volume
        val current = deck
        if (track == null) {
            current?.let { retire(it, BgmPolicy.FADE_OUT_MS) }
            deck = null
            return
        }
        if (current != null && current.track != track) {
            deck = null
            if (state == BgmState.PLAYING && current.player.playWhenReady) {
                // Crossfade: the old loop fades out while the new one fades in.
                retire(current, BgmPolicy.FADE_IN_MS)
                newDeck(track).also { deck = it }.play(volume, BgmPolicy.FADE_IN_MS)
                return
            }
            current.release()
        }
        // A list coming back from a stop goes on with its next track, not the one it stopped in.
        deck?.let { d -> if (state == BgmState.PLAYING && track.isList && d.isStopped && d.hasPlayed) { d.release(); deck = null } }
        when (state) {
            BgmState.PLAYING -> (deck ?: newDeck(track).also { deck = it }).play(volume, fadeMs)
            BgmState.PAUSED -> deck?.pause(fadeMs)
            BgmState.STOPPED -> deck?.stop(fadeMs)
        }
    }

    /** A player exists (playing, paused, stopped, or fading out): for tests. */
    internal val hasPlayer: Boolean get() = deck != null || outgoing.isNotEmpty()

    override fun release() {
        deck?.release()
        deck = null
        outgoing.toList().forEach { it.release() }
        outgoing.clear()
    }

    private fun retire(d: Deck, fadeMs: Long) {
        outgoing += d
        d.fadeTo(0f, fadeMs) {
            outgoing -= d
            d.release()
        }
    }

    /** The list's next track crossfades in over the current one, which fades out. */
    private fun advance(from: Deck) {
        if (deck !== from || from.isStopped) return
        val track = from.track
        retire(from, BgmQueue.CROSSFADE_MS)
        val next = newDeck(track).also { deck = it }
        android.util.Log.i("XrossBgm", "next track ${next.index + 1}/${track.files.size}: ${track.files[next.index].name}")
        next.play(level, BgmQueue.CROSSFADE_MS)
    }

    private inner class Deck(val track: BgmTrack, val index: Int) {
        private val file = track.files.getOrElse(index) { track.file }

        val player: ExoPlayer = ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_GAME).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ false,
            )
            .build()
            .apply {
                repeatMode = if (track.isList) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ONE
                volume = 0f
                setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)))
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying) hasPlayed = true
                        onActiveChanged?.invoke()
                        if (track.isList) watchEnd()
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        // A track shorter than the crossfade, or one that ended under a pause.
                        if (playbackState == Player.STATE_ENDED && track.isList) advance(this@Deck)
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        android.util.Log.w("XrossBgm", "Can't play ${file.name}: ${error.errorCodeName}")
                        // A list skips a file it can't play (once per file, so a broken list goes quiet).
                        if (track.isList && failures.add(file.path) && failures.size < track.files.size) advance(this@Deck)
                    }
                })
            }

        /** Stopped (or never started): the next [play] prepares again from position 0. */
        private var stopped = true
        private var fade: Runnable? = null
        private var released = false
        val isStopped: Boolean get() = stopped
        /** It has been heard, so a list resuming after a stop moves on. */
        var hasPlayed = false

        private var endWatch: Runnable? = null

        /** While a list track plays, starts the crossfade [BgmQueue.CROSSFADE_MS] before its end. */
        fun watchEnd() {
            endWatch?.let(handler::removeCallbacks)
            if (released || !player.isPlaying) return
            val check = object : Runnable {
                override fun run() {
                    if (endWatch !== this || released || deck !== this@Deck) return
                    val duration = player.duration
                    if (duration != C.TIME_UNSET && duration > 0 && duration - player.currentPosition <= BgmQueue.CROSSFADE_MS) {
                        endWatch = null
                        return advance(this@Deck)
                    }
                    if (player.isPlaying) handler.postDelayed(this, END_POLL_MS)
                }
            }
            endWatch = check
            handler.postDelayed(check, END_POLL_MS)
        }

        fun play(volume: Float, fadeMs: Long) {
            if (stopped) {
                player.seekTo(0)
                player.prepare()
                stopped = false
            }
            player.playWhenReady = true
            fadeTo(volume, fadeMs)
        }

        fun pause(fadeMs: Long) {
            if (stopped || !player.playWhenReady) return cancelFade()
            fadeTo(0f, fadeMs) { player.pause() }
        }

        fun stop(fadeMs: Long) {
            if (stopped) return cancelFade()
            fadeTo(0f, fadeMs) {
                player.stop()
                stopped = true
            }
        }

        /** Moves the volume linearly from where it is to [target] over [ms] (at once for 0), then [then]. */
        fun fadeTo(target: Float, ms: Long, then: () -> Unit = {}) {
            cancelFade()
            if (released) return
            val from = player.volume
            val to = target.coerceIn(0f, 1f)
            if (ms <= 0L || from == to) {
                player.volume = to
                then()
                return
            }
            val start = SystemClock.uptimeMillis()
            val step = object : Runnable {
                override fun run() {
                    if (fade !== this || released) return
                    val t = ((SystemClock.uptimeMillis() - start).toFloat() / ms).coerceIn(0f, 1f)
                    player.volume = from + (to - from) * t
                    if (t < 1f) {
                        handler.postDelayed(this, FADE_STEP_MS)
                    } else {
                        fade = null
                        then()
                    }
                }
            }
            fade = step
            handler.post(step)
        }

        private fun cancelFade() {
            fade?.let(handler::removeCallbacks)
            fade = null
        }

        fun release() {
            cancelFade()
            endWatch?.let(handler::removeCallbacks)
            endWatch = null
            if (released) return
            released = true
            player.release()
            onActiveChanged?.invoke()
        }
    }

    /** Files of a list that failed to play, so a list of broken files doesn't spin. */
    private val failures = HashSet<String>()

    private companion object {
        const val FADE_STEP_MS = 16L
        const val END_POLL_MS = 250L
    }
}
