package dev.xross.launcher

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.xross.launcher.audio.AudioDirector
import dev.xross.launcher.audio.AudioMixer
import dev.xross.launcher.audio.Mix
import dev.xross.launcher.audio.MixInputs
import dev.xross.launcher.audio.MusicOnLaunch
import dev.xross.launcher.audio.UiSounds
import dev.xross.launcher.data.XrossSettings
import dev.xross.launcher.media.MusicPlayer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Phase 2b.5-prep: the audio rules moved out of the ViewModel, the Activity and the snap player
 * into [AudioMixer] / [AudioDirector], with today's behaviour pinned so 2b.5b and 2b.5c change it
 * on purpose only.
 */
@RunWith(RobolectricTestRunner::class)
class AudioDirectorTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val music by lazy { MusicPlayer(context) }
    private val sounds by lazy { UiSounds(context) }
    private var settings = XrossSettings()
    private val director by lazy { AudioDirector(music, sounds) { settings } }

    @After fun release() = music.player.release()

    @Test fun oneEffectsVolumeDrivesEffectsCuesAndSnaps() {
        assertEquals(Mix(effects = 0.75f, cue = 0.75f, snap = 0.75f, music = 1f), AudioMixer.resolve(MixInputs()))
        assertEquals(0.3f, AudioMixer.resolve(MixInputs(effectsVolume = 0.3f)).snap, 0f)
        // Out of range and NaN volumes from a hand-edited settings.json.
        assertEquals(1f, AudioMixer.resolve(MixInputs(effectsVolume = 4f)).effects, 0f)
        assertEquals(0f, AudioMixer.resolve(MixInputs(effectsVolume = -1f)).cue, 0f)
        assertEquals(0f, AudioMixer.resolve(MixInputs(effectsVolume = Float.NaN)).effects, 0f)
    }

    @Test fun musicDucksUnderASnapAndPausesForGames() {
        assertEquals(1f, AudioMixer.resolve(MixInputs(snapActive = false)).music, 0f)
        assertEquals(AudioMixer.SNAP_DUCK, AudioMixer.resolve(MixInputs(snapActive = true)).music, 0f)
        assertEquals(0.2f, AudioMixer.SNAP_DUCK, 0f)
        assertEquals(MusicOnLaunch.PAUSE_AND_RESUME, AudioMixer.musicOnLaunch(resumeMusicAfterGames = true))
        assertEquals(MusicOnLaunch.PAUSE, AudioMixer.musicOnLaunch(resumeMusicAfterGames = false))
    }

    @Test fun theDirectorDucksMusicForTheLengthOfASnap() {
        director.onSettingsChanged(settings)
        assertEquals(1f, music.player.volume, 0f)
        director.onSnapStarted()
        assertEquals(0.2f, music.player.volume, 0f)
        // A settings change while the snap plays keeps it ducked.
        settings = settings.copy(soundVolume = 0.5f)
        director.onSettingsChanged(settings)
        assertEquals(0.2f, music.player.volume, 0f)
        director.onSnapStopped()
        assertEquals(1f, music.player.volume, 0f)
    }

    @Test fun theDirectorAppliesTheEffectsSettings() {
        settings = XrossSettings(soundEffects = false, soundVolume = 0.4f)
        director.onSettingsChanged(settings)
        assertFalse(sounds.enabled)
        assertEquals(0.4f, sounds.volume, 0f)
        assertEquals(0.4f, director.snapVolume, 0f)
    }
}
