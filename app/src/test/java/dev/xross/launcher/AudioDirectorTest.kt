package dev.xross.launcher

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.xross.launcher.audio.AudioDirector
import dev.xross.launcher.audio.AudioMixer
import dev.xross.launcher.audio.Mix
import dev.xross.launcher.audio.MixInputs
import dev.xross.launcher.audio.MusicOnLaunch
import dev.xross.launcher.audio.MusicOutput
import dev.xross.launcher.audio.MusicUnderSnaps
import dev.xross.launcher.audio.SnapState
import dev.xross.launcher.audio.UiSounds
import dev.xross.launcher.data.XrossSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * The audio rules ([AudioMixer]) and how [AudioDirector] applies them (2b.5-prep, 2b.5b): separate
 * volumes, music paused (or lowered) under a video snap with sound, games.
 */
@RunWith(RobolectricTestRunner::class)
class AudioDirectorTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val music = FakeMusic()
    private val sounds by lazy { UiSounds(context) }
    private var settings = XrossSettings()
    private val director by lazy { AudioDirector(music, sounds) { settings } }

    /** [MusicOutput] the way MusicPlayer behaves, without ExoPlayer. */
    class FakeMusic : MusicOutput {
        override var volume = 1f
        override var playing = false
        override var pausedForSnap = false
        var pausedForGame = false
        override var loaded = false

        fun userPlays() { loaded = true; playing = true; pausedForSnap = false; pausedForGame = false }
        fun userPauses() { playing = false; pausedForSnap = false; pausedForGame = false }
        override fun pause() { playing = false }
        override fun pauseForGame() { if (playing) { playing = false; pausedForGame = true } }
        override fun resumeAfterGame() { if (pausedForGame) { pausedForGame = false; playing = true } }
        override fun pauseForSnap() { if (playing) { playing = false; pausedForSnap = true } }
        override fun resumeAfterSnap(): Boolean {
            if (!pausedForSnap) return false
            pausedForSnap = false
            if (playing || !loaded) return false
            playing = true
            return true
        }
        override fun handSnapPauseToGame(resume: Boolean) {
            if (!pausedForSnap) return
            pausedForSnap = false
            pausedForGame = resume
        }
    }

    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun start(s: XrossSettings = settings) {
        settings = s
        director.onSettingsChanged(settings)
    }

    // region AudioMixer

    @Test fun eachVolumeDrivesItsOwnPlayer() {
        assertEquals(Mix(effects = 0.75f, cue = 0.75f, snap = 0.75f, music = 1f), AudioMixer.resolve(MixInputs()))
        val m = AudioMixer.resolve(MixInputs(effectsVolume = 0.3f, cueVolume = 0.4f, snapVolume = 0.5f, musicVolume = 0.6f))
        assertEquals(Mix(effects = 0.3f, cue = 0.4f, snap = 0.5f, music = 0.6f), m)
        // Out of range and NaN volumes from a hand-edited settings.json.
        assertEquals(1f, AudioMixer.resolve(MixInputs(effectsVolume = 4f)).effects, 0f)
        assertEquals(0f, AudioMixer.resolve(MixInputs(cueVolume = -1f)).cue, 0f)
        assertEquals(0f, AudioMixer.resolve(MixInputs(snapVolume = Float.NaN)).snap, 0f)
        assertEquals(0f, AudioMixer.resolve(MixInputs(musicVolume = Float.NaN)).music, 0f)
    }

    @Test fun duckLowersMusicToAFifthOfItsVolumeOnlyWhileTheSnapIsHeard() {
        fun music(snap: SnapState, musicVolume: Float = 0.5f, snapVolume: Float = 0.75f) = AudioMixer.resolve(
            MixInputs(musicVolume = musicVolume, snapVolume = snapVolume, musicUnderSnaps = MusicUnderSnaps.DUCK, snap = snap),
        )
        assertEquals(0.5f, music(SnapState.NONE).music, 0f)
        assertEquals(0.5f, music(SnapState.LOADING).music, 0f)
        assertEquals(0.1f, music(SnapState.AUDIBLE).music, 1e-6f)
        assertFalse(music(SnapState.AUDIBLE).holdMusic)
        // A snap at 0% is silent: nothing to duck under.
        assertEquals(0.5f, music(SnapState.AUDIBLE, snapVolume = 0f).music, 0f)
        assertEquals(0.2f, AudioMixer.SNAP_DUCK, 0f)
    }

    @Test fun pauseHoldsMusicOnlyWhileTheSnapIsHeard() {
        fun mix(snap: SnapState, snapVolume: Float = 0.75f) =
            AudioMixer.resolve(MixInputs(musicVolume = 0.5f, snapVolume = snapVolume, musicUnderSnaps = MusicUnderSnaps.PAUSE, snap = snap))
        assertFalse(mix(SnapState.NONE).holdMusic)
        assertFalse(mix(SnapState.LOADING).holdMusic)
        assertTrue(mix(SnapState.AUDIBLE).holdMusic)
        assertFalse(mix(SnapState.AUDIBLE, snapVolume = 0f).holdMusic)
        // The song keeps its level for when it resumes.
        assertEquals(0.5f, mix(SnapState.AUDIBLE).music, 0f)
    }

    @Test fun gamesPauseMusicAndResumeItBySetting() {
        assertEquals(MusicOnLaunch.PAUSE_AND_RESUME, AudioMixer.musicOnLaunch(resumeMusicAfterGames = true))
        assertEquals(MusicOnLaunch.PAUSE, AudioMixer.musicOnLaunch(resumeMusicAfterGames = false))
    }

    // endregion
    // region AudioDirector

    @Test fun theDirectorAppliesEffectsAndSnapLevels() {
        var pushed = -1f
        director.snapVolumeListener = { pushed = it }
        start(XrossSettings(soundEffects = false, soundVolume = 0.4f, snapVolume = 0.3f))
        assertFalse(sounds.enabled)
        assertEquals(0.4f, sounds.volume, 0f)
        assertEquals(0.3f, director.snapVolume, 0f)
        assertEquals(0.3f, pushed, 0f)
        // A change reaches a playing snap live.
        start(settings.copy(snapVolume = 0.8f))
        assertEquals(0.8f, pushed, 0f)
    }

    @Test fun musicVolumeAppliesLive() {
        music.userPlays()
        start(XrossSettings(musicVolume = 0.7f))
        assertEquals(0.7f, music.volume, 0f)
        start(settings.copy(musicVolume = 0.3f))
        assertEquals(0.3f, music.volume, 0f)
    }

    @Test fun duckGivesAFifthOfTheMusicVolumeAndRestoresIt() {
        music.userPlays()
        start(XrossSettings(musicVolume = 0.5f, musicUnderSnaps = MusicUnderSnaps.DUCK))
        director.onSnapState(SnapState.LOADING)
        assertEquals(0.5f, music.volume, 0f)
        director.onSnapState(SnapState.AUDIBLE)
        assertEquals(0.1f, music.volume, 1e-6f)
        assertTrue(music.playing)
        // A settings change while the snap plays keeps it ducked, at the new level.
        start(settings.copy(musicVolume = 1f))
        assertEquals(0.2f, music.volume, 1e-6f)
        director.onSnapState(SnapState.NONE)
        assertEquals(1f, music.volume, 0f)
    }

    @Test fun pauseFadesOutAndPausesWhenTheSnapIsHeardThenResumes() {
        music.userPlays()
        start(XrossSettings(musicVolume = 0.8f))
        director.onSnapState(SnapState.LOADING)
        assertTrue("loading doesn't touch the music", music.playing)
        assertEquals(0.8f, music.volume, 0f)
        director.onSnapState(SnapState.AUDIBLE)
        advance(100)
        assertTrue("still fading out", music.playing && music.volume in 0.01f..0.79f)
        advance(150)
        assertFalse(music.playing)
        assertTrue(music.pausedForSnap)
        assertEquals("back at its level, paused", 0.8f, music.volume, 0f)
        director.onSnapState(SnapState.NONE)
        assertTrue(music.playing)
        assertEquals("fades back in from silence", 0f, music.volume, 0.05f)
        advance(250)
        assertEquals(0.8f, music.volume, 1e-6f)
        assertFalse(music.pausedForSnap)
    }

    @Test fun pauseNeverResumesASongTheUserPaused() {
        music.userPlays()
        music.userPauses()
        start()
        director.onSnapState(SnapState.LOADING)
        director.onSnapState(SnapState.AUDIBLE)
        advance(300)
        director.onSnapState(SnapState.NONE)
        advance(300)
        assertFalse(music.playing)
        // Paused by the snap, then paused-and-played by the user: the user's choice stands.
        music.userPlays()
        director.onSnapState(SnapState.LOADING)
        director.onSnapState(SnapState.AUDIBLE)
        advance(300)
        assertTrue(music.pausedForSnap)
        music.userPlays()
        music.userPauses()
        director.onSnapState(SnapState.NONE)
        advance(300)
        assertFalse(music.playing)
    }

    @Test fun pauseIgnoresASnapThatNeverStartedItsSound() {
        music.userPlays()
        start()
        repeat(5) {
            director.onSnapState(SnapState.LOADING)
            advance(50)
            director.onSnapState(SnapState.NONE)
        }
        advance(300)
        assertTrue(music.playing)
        assertEquals(1f, music.volume, 0f)
    }

    @Test fun pauseIgnoresASilentSnap() {
        music.userPlays()
        start(XrossSettings(snapVolume = 0f))
        director.onSnapState(SnapState.LOADING)
        director.onSnapState(SnapState.AUDIBLE)
        advance(300)
        assertTrue(music.playing)
        assertEquals(1f, music.volume, 0f)
    }

    @Test fun aSnapEndingMidFadeBringsTheSongBackUp() {
        music.userPlays()
        start()
        director.onSnapState(SnapState.LOADING)
        director.onSnapState(SnapState.AUDIBLE)
        advance(100)
        director.onSnapState(SnapState.NONE)
        advance(300)
        assertTrue(music.playing)
        assertFalse(music.pausedForSnap)
        assertEquals(1f, music.volume, 1e-6f)
    }

    @Test fun aGameLaunchDuringAPausedBySnapSongFollowsResumeMusicAfterGames() {
        for (resume in listOf(true, false)) {
            music.userPlays()
            start(XrossSettings(resumeMusicAfterGames = resume, gameBootSound = false))
            director.onSnapState(SnapState.LOADING)
            director.onSnapState(SnapState.AUDIBLE)
            advance(300)
            assertTrue(music.pausedForSnap)
            director.onGameLaunch()
            // The launch stops the snap: that no longer resumes the song.
            director.onSnapState(SnapState.NONE)
            advance(300)
            assertFalse(music.playing)
            director.onReturnFromGame()
            assertEquals("resumeMusicAfterGames=$resume", resume, music.playing)
            assertEquals(1f, music.volume, 0f)
            music.userPauses()
        }
    }

    @Test fun aGameLaunchMidFadeStillPausesForTheGame() {
        music.userPlays()
        start(XrossSettings(gameBootSound = false))
        director.onSnapState(SnapState.LOADING)
        director.onSnapState(SnapState.AUDIBLE)
        advance(100)
        director.onGameLaunch()
        director.onSnapState(SnapState.NONE)
        advance(300)
        assertFalse(music.playing)
        assertEquals(1f, music.volume, 0f)
        director.onReturnFromGame()
        assertTrue(music.playing)
    }

    @Test fun switchingToDuckWhileASnapHoldsTheSongResumesItLowered() {
        music.userPlays()
        start(XrossSettings(musicVolume = 0.5f))
        director.onSnapState(SnapState.LOADING)
        director.onSnapState(SnapState.AUDIBLE)
        advance(300)
        assertFalse(music.playing)
        start(settings.copy(musicUnderSnaps = MusicUnderSnaps.DUCK))
        advance(300)
        assertTrue(music.playing)
        assertEquals(0.1f, music.volume, 1e-6f)
    }

    // endregion
}
