package dev.xross.launcher

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.xross.launcher.audio.AudioDirector
import dev.xross.launcher.audio.AudioMixer
import dev.xross.launcher.audio.BgmDecision
import dev.xross.launcher.audio.BgmInputs
import dev.xross.launcher.audio.BgmOutput
import dev.xross.launcher.audio.BgmPolicy
import dev.xross.launcher.audio.BgmReason
import dev.xross.launcher.audio.BgmState
import dev.xross.launcher.audio.MusicUnderSnaps
import dev.xross.launcher.audio.PlaybackCensus
import dev.xross.launcher.audio.SnapState
import dev.xross.launcher.audio.UiSounds
import dev.xross.launcher.data.XrossSettings
import dev.xross.launcher.media.ThemeBgm
import dev.xross.launcher.theme.pack.BgmTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.time.Duration

/**
 * Theme music (2b.5c): [BgmPolicy]'s table, the other-app census, and how [AudioDirector] drives
 * the player through it (never over the user's music, paused or ducked under snaps, stopped for
 * games and restarted from 0 after them).
 */
@RunWith(RobolectricTestRunner::class)
class BgmPolicyTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    // region BgmPolicy

    private val playing = BgmInputs(bgmVolume = 0.6f)

    @Test fun everythingAllowedPlaysAtTheThemeMusicVolume() {
        assertEquals(BgmDecision(BgmState.PLAYING, 0.6f, BgmReason.ALLOWED), BgmPolicy.decide(playing))
    }

    @Test fun eachConditionAloneBlocksPlayback() {
        val table = listOf(
            playing.copy(hasTrack = false) to (BgmState.STOPPED to BgmReason.NO_TRACK),
            playing.copy(bgmVolume = 0f) to (BgmState.STOPPED to BgmReason.MUTED),
            playing.copy(bgmVolume = Float.NaN) to (BgmState.STOPPED to BgmReason.MUTED),
            playing.copy(gameRunning = true) to (BgmState.STOPPED to BgmReason.GAME),
            playing.copy(musicLoaded = true) to (BgmState.STOPPED to BgmReason.MUSIC),
            playing.copy(started = false) to (BgmState.PAUSED to BgmReason.BACKGROUND),
            playing.copy(fullScreenVideo = true) to (BgmState.PAUSED to BgmReason.VIDEO),
            playing.copy(bootVideo = true) to (BgmState.PAUSED to BgmReason.BOOT_VIDEO),
            playing.copy(otherAppPlaying = true) to (BgmState.PAUSED to BgmReason.OTHER_APP),
            playing.copy(snap = SnapState.AUDIBLE) to (BgmState.PAUSED to BgmReason.SNAP),
        )
        for ((inputs, expected) in table) {
            val d = BgmPolicy.decide(inputs)
            assertEquals(inputs.toString(), expected, d.state to d.reason)
            assertEquals(0f, d.volume, 0f)
        }
    }

    @Test fun stopsBeatPausesSoAGameOrASongAlwaysRestartsFromZero() {
        val d = BgmPolicy.decide(playing.copy(started = false, gameRunning = true, musicLoaded = true, snap = SnapState.AUDIBLE))
        assertEquals(BgmReason.GAME, d.reason)
        assertEquals(BgmReason.MUSIC, BgmPolicy.decide(playing.copy(musicLoaded = true, otherAppPlaying = true)).reason)
    }

    @Test fun aSnapPausesUnderPauseAndDucksUnderDuck() {
        assertEquals(BgmState.PAUSED, BgmPolicy.decide(playing.copy(snap = SnapState.AUDIBLE, musicUnderSnaps = MusicUnderSnaps.PAUSE)).state)
        val ducked = BgmPolicy.decide(playing.copy(snap = SnapState.AUDIBLE, musicUnderSnaps = MusicUnderSnaps.DUCK))
        assertEquals(BgmDecision(BgmState.PLAYING, 0.6f * AudioMixer.SNAP_DUCK, BgmReason.SNAP_DUCK), ducked)
        assertEquals(0.12f, ducked.volume, 1e-6f)
        // A snap still loading, or one at 0%, isn't heard and changes nothing.
        assertEquals(BgmReason.ALLOWED, BgmPolicy.decide(playing.copy(snap = SnapState.LOADING)).reason)
        assertEquals(BgmReason.ALLOWED, BgmPolicy.decide(playing.copy(snap = SnapState.AUDIBLE, snapVolume = 0f)).reason)
    }

    @Test fun fadesByTransition() {
        val play = BgmPolicy.decide(playing)
        val stop = BgmPolicy.decide(playing.copy(musicLoaded = true))
        val snap = BgmPolicy.decide(playing.copy(snap = SnapState.AUDIBLE))
        val away = BgmPolicy.decide(playing.copy(started = false))
        assertEquals("a fresh start fades in", BgmPolicy.FADE_IN_MS, BgmPolicy.fadeMs(null, play))
        assertEquals(BgmPolicy.FADE_IN_MS, BgmPolicy.fadeMs(stop, play))
        assertEquals("music starting: 300 ms out", 300L, BgmPolicy.fadeMs(play, stop))
        assertEquals("a snap: like a song", AudioMixer.SNAP_FADE_MS, BgmPolicy.fadeMs(play, snap))
        assertEquals(AudioMixer.SNAP_FADE_MS, BgmPolicy.fadeMs(snap, play))
        assertEquals("leaving the screen cuts", 0L, BgmPolicy.fadeMs(play, away))
        assertEquals(600L, BgmPolicy.FADE_IN_MS)
    }

    // endregion
    // region PlaybackCensus

    @Test fun censusCountsMediaAndGameMusicButNotClicks() {
        assertEquals(android.media.AudioAttributes.USAGE_MEDIA, PlaybackCensus.USAGE_MEDIA)
        assertEquals(android.media.AudioAttributes.USAGE_GAME, PlaybackCensus.USAGE_GAME)
        assertEquals(android.media.AudioAttributes.CONTENT_TYPE_MUSIC, PlaybackCensus.CONTENT_TYPE_MUSIC)
        assertEquals(android.media.AudioAttributes.CONTENT_TYPE_MOVIE, PlaybackCensus.CONTENT_TYPE_MOVIE)
        val a = android.media.AudioAttributes::class.java
        fun c(name: String) = a.getField(name).getInt(null)
        assertEquals(PlaybackCensus.Kind.MEDIA, PlaybackCensus.kindOf(c("USAGE_MEDIA"), c("CONTENT_TYPE_SPEECH")))
        assertEquals(PlaybackCensus.Kind.GAME_MUSIC, PlaybackCensus.kindOf(c("USAGE_GAME"), c("CONTENT_TYPE_MUSIC")))
        // Xross's own UI effects and cues (USAGE_GAME, SONIFICATION), notifications, calls.
        assertNull(PlaybackCensus.kindOf(c("USAGE_GAME"), c("CONTENT_TYPE_SONIFICATION")))
        assertNull(PlaybackCensus.kindOf(c("USAGE_NOTIFICATION"), c("CONTENT_TYPE_SONIFICATION")))
        assertNull(PlaybackCensus.kindOf(c("USAGE_VOICE_COMMUNICATION"), c("CONTENT_TYPE_SPEECH")))
    }

    @Test fun othersAreWhatXrossDoesntRunItself() {
        val media = PlaybackCensus.Kind.MEDIA
        val game = PlaybackCensus.Kind.GAME_MUSIC
        assertFalse(PlaybackCensus.othersPlaying(emptyMap(), emptyMap()))
        assertFalse("only Xross's song", PlaybackCensus.othersPlaying(mapOf(media to 1), mapOf(media to 1)))
        assertTrue("a podcast in another app", PlaybackCensus.othersPlaying(mapOf(media to 1), mapOf(media to 0)))
        assertTrue("a podcast next to Xross's snap", PlaybackCensus.othersPlaying(mapOf(media to 2), mapOf(media to 1)))
        assertFalse("theme music itself", PlaybackCensus.othersPlaying(mapOf(game to 2), mapOf(game to 2)))
        assertTrue(PlaybackCensus.othersPlaying(mapOf(game to 1), emptyMap()))
    }

    // endregion
    // region AudioDirector

    /** [BgmOutput] recording what the director asks for; "playing" once asked to play. */
    class FakeBgm : BgmOutput {
        data class Call(val track: String?, val state: BgmState, val volume: Float, val fadeMs: Long)

        val calls = ArrayList<Call>()
        var state = BgmState.STOPPED
        var created = false
        override var onActiveChanged: (() -> Unit)? = null
        override val activePlayers: Int get() = if (state == BgmState.PLAYING) 1 else 0

        override fun apply(track: BgmTrack?, state: BgmState, volume: Float, fadeMs: Long) {
            calls += Call(track?.name, state, volume, fadeMs)
            if (track != null && state == BgmState.PLAYING) created = true
            val was = activePlayers
            this.state = if (track == null) BgmState.STOPPED else state
            if (activePlayers != was) onActiveChanged?.invoke()
        }

        override fun release() {
            state = BgmState.STOPPED
        }

        val last: Call get() = calls.last()
    }

    private val music = AudioDirectorTest.FakeMusic()
    private val bgm = FakeBgm()
    private var settings = XrossSettings(bgmVolume = 0.6f)
    private val director by lazy { AudioDirector(music, UiSounds(context), bgm) { settings } }
    private val loop = BgmTrack(File("/packs/calm/bgm.ogg"), key = "bgm")
    private val games = BgmTrack(File("/packs/calm/games.ogg"), key = "games")

    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /** In front, with [track] and [s]: theme music plays. */
    private fun inFront(track: BgmTrack? = loop, s: XrossSettings = settings) {
        settings = s
        director.onSettingsChanged(s)
        director.onBgmTrack(track)
        director.onStarted(true)
    }

    @Test fun playsWithThePackFadingInAtItsVolume() {
        inFront()
        assertEquals(FakeBgm.Call("bgm.ogg", BgmState.PLAYING, 0.6f, BgmPolicy.FADE_IN_MS), bgm.last)
        assertEquals(BgmReason.ALLOWED, director.bgmDecision.reason)
    }

    @Test fun waitsForTheActivityToStart() {
        director.onSettingsChanged(settings)
        director.onBgmTrack(loop)
        assertTrue(bgm.calls.none { it.state == BgmState.PLAYING })
        director.onStarted(true)
        assertEquals(BgmState.PLAYING, bgm.state)
        director.onStarted(false)
        assertEquals(FakeBgm.Call("bgm.ogg", BgmState.PAUSED, 0f, 0L), bgm.last)
        director.onStarted(true)
        assertEquals("resumes where it was", BgmState.PLAYING, bgm.state)
    }

    @Test fun noTrackNeverCreatesAPlayer() {
        inFront(track = null)
        director.onSnapState(SnapState.AUDIBLE)
        director.onSnapState(SnapState.NONE)
        director.onReturnFromGame()
        assertFalse(bgm.created)
        assertEquals(BgmReason.NO_TRACK, director.bgmDecision.reason)
    }

    @Test fun neverPlaysOverTheUsersMusicAndRestartsWhenTheQueueIsCleared() {
        inFront()
        music.userPlays()
        director.onMusicChanged()
        assertEquals(FakeBgm.Call("bgm.ogg", BgmState.STOPPED, 0f, BgmPolicy.FADE_OUT_MS), bgm.last)
        // A paused song still blocks it: the user is listening to music in Xross.
        music.userPauses()
        director.onMusicChanged()
        assertEquals(BgmState.STOPPED, bgm.state)
        assertEquals(BgmReason.MUSIC, director.bgmDecision.reason)
        // Queue cleared: back from the start, fading in.
        music.loaded = false
        director.onMusicChanged()
        assertEquals(FakeBgm.Call("bgm.ogg", BgmState.PLAYING, 0.6f, BgmPolicy.FADE_IN_MS), bgm.last)
    }

    @Test fun aSnapPausesItUnderPauseAndDucksItUnderDuck() {
        inFront()
        director.onSnapState(SnapState.LOADING)
        assertEquals("loading doesn't touch it", BgmState.PLAYING, bgm.state)
        director.onSnapState(SnapState.AUDIBLE)
        assertEquals(FakeBgm.Call("bgm.ogg", BgmState.PAUSED, 0f, AudioMixer.SNAP_FADE_MS), bgm.last)
        director.onSnapState(SnapState.NONE)
        assertEquals(FakeBgm.Call("bgm.ogg", BgmState.PLAYING, 0.6f, AudioMixer.SNAP_FADE_MS), bgm.last)

        settings = settings.copy(musicUnderSnaps = MusicUnderSnaps.DUCK)
        director.onSettingsChanged(settings)
        director.onSnapState(SnapState.LOADING)
        director.onSnapState(SnapState.AUDIBLE)
        assertEquals(BgmState.PLAYING, bgm.last.state)
        assertEquals(0.12f, bgm.last.volume, 1e-6f)
        director.onSnapState(SnapState.NONE)
        assertEquals(0.6f, bgm.last.volume, 0f)
    }

    @Test fun aLaunchStopsItAndTheReturnRestartsItFromZero() {
        inFront(s = settings.copy(gameBootSound = false))
        director.onGameLaunch()
        assertEquals(FakeBgm.Call("bgm.ogg", BgmState.STOPPED, 0f, BgmPolicy.FADE_OUT_MS), bgm.last)
        assertEquals(BgmReason.GAME, director.bgmDecision.reason)
        // The emulator takes the screen, then the user comes back: onStart before onResume.
        director.onStarted(false)
        director.onStarted(true)
        assertEquals("still the game's until resumed", BgmState.STOPPED, bgm.state)
        director.onReturnFromGame()
        // STOPPED → PLAYING: the player prepares again from position 0.
        assertEquals(FakeBgm.Call("bgm.ogg", BgmState.PLAYING, 0.6f, BgmPolicy.FADE_IN_MS), bgm.last)
    }

    @Test fun aFailedLaunchBringsItBack() {
        inFront(s = settings.copy(gameBootSound = false))
        director.onGameLaunch()
        director.onLaunchFailed()
        assertEquals(BgmState.PLAYING, bgm.state)
    }

    @Test fun theVideoPlayerAndTheBootVideoPauseIt() {
        inFront()
        director.onFullScreenVideo(true)
        assertEquals(BgmReason.VIDEO, director.bgmDecision.reason)
        assertEquals(BgmState.PAUSED, bgm.state)
        director.onFullScreenVideo(false)
        assertEquals(BgmState.PLAYING, bgm.state)
        director.onBootVideo(true)
        assertEquals(BgmState.PAUSED, bgm.state)
        director.onBootVideo(false)
        assertEquals(BgmState.PLAYING, bgm.state)
    }

    @Test fun volumeChangesApplyLiveAndZeroStopsIt() {
        inFront()
        settings = settings.copy(bgmVolume = 0.3f)
        director.onSettingsChanged(settings)
        assertEquals(FakeBgm.Call("bgm.ogg", BgmState.PLAYING, 0.3f, AudioMixer.SNAP_FADE_MS), bgm.last)
        settings = settings.copy(bgmVolume = 0f)
        director.onSettingsChanged(settings)
        assertEquals(BgmState.STOPPED, bgm.state)
        assertEquals(BgmReason.MUTED, director.bgmDecision.reason)
    }

    @Test fun aNewTrackFromTheLookGoesToThePlayer() {
        inFront()
        director.onBgmTrack(games)
        assertEquals("games.ogg", bgm.last.track)
        assertEquals(BgmState.PLAYING, bgm.last.state)
        director.onBgmTrack(null)
        assertNull(bgm.last.track)
        assertEquals(BgmReason.NO_TRACK, director.bgmDecision.reason)
    }

    @Test fun anotherAppPlayingKeepsItSilentOnceItHolds() {
        val media = PlaybackCensus.Kind.MEDIA
        inFront()
        // Xross's own song shows in the census as a media player: not another app.
        // (A blip shorter than the settle time, like Xross's own pause showing late, changes nothing.)
        director.onPlaybackChanged(mapOf(media to 1), snapshot = false)
        advance(200)
        director.onPlaybackChanged(emptyMap(), snapshot = false)
        advance(600)
        assertEquals(BgmState.PLAYING, bgm.state)
        // Another app starts a podcast and keeps playing.
        director.onPlaybackChanged(mapOf(media to 1), snapshot = false)
        advance(600)
        assertEquals(BgmReason.OTHER_APP, director.bgmDecision.reason)
        assertEquals(BgmState.PAUSED, bgm.state)
        // It stops: theme music comes back at once.
        director.onPlaybackChanged(emptyMap(), snapshot = false)
        assertEquals(BgmState.PLAYING, bgm.state)
    }

    @Test fun xrossOwnPlayersDontCountAsOtherApps() {
        val media = PlaybackCensus.Kind.MEDIA
        val game = PlaybackCensus.Kind.GAME_MUSIC
        inFront()
        // Theme music itself, and a loading snap next to it.
        director.onPlaybackChanged(mapOf(game to 1), snapshot = false)
        director.onSnapState(SnapState.LOADING)
        director.onPlaybackChanged(mapOf(game to 1, media to 1), snapshot = false)
        advance(600)
        assertEquals(BgmReason.ALLOWED, director.bgmDecision.reason)
    }

    // endregion
    // region ThemeBgm

    @Test fun themeBgmCreatesNoPlayerWithoutATrack() {
        val player = ThemeBgm(context)
        player.apply(null, BgmState.PLAYING, 0.6f, 0L)
        assertFalse(player.hasPlayer)
        assertEquals(0, player.activePlayers)
        // A stop or pause for a pack it never played creates nothing either.
        player.apply(loop, BgmState.STOPPED, 0f, 0L)
        player.apply(loop, BgmState.PAUSED, 0f, 0L)
        assertFalse(player.hasPlayer)
        player.release()
    }

    // endregion
}
