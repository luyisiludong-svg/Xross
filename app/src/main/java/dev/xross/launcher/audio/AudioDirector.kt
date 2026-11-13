package dev.xross.launcher.audio

import dev.xross.launcher.data.XrossSettings
import dev.xross.launcher.media.MusicPlayer

/**
 * Applies [AudioMixer]'s rules to Xross's players: UI effects and cues ([UiSounds]), the music
 * player, and video snaps (which report themselves through [onSnapStarted] / [onSnapStopped]).
 * Every volume and every pause/resume decision goes through here, so the ViewModel, the
 * Activity and the snap player no longer set each other's levels. Main thread only.
 */
class AudioDirector(
    private val music: MusicPlayer,
    private val sounds: UiSounds,
    private val settings: () -> XrossSettings,
) {
    private var snapActive = false

    private fun mix(s: XrossSettings = settings()) = AudioMixer.resolve(MixInputs(effectsVolume = s.soundVolume, snapActive = snapActive))

    /** The level a snap starting now ramps up to. */
    val snapVolume: Float get() = mix().snap

    /** Settings changed (or were just loaded): effects on/off and their level. */
    fun onSettingsChanged(s: XrossSettings) {
        sounds.enabled = s.soundEffects
        sounds.volume = mix(s).effects
        applyMusic()
    }

    fun onSnapStarted() {
        snapActive = true
        applyMusic()
    }

    fun onSnapStopped() {
        snapActive = false
        applyMusic()
    }

    /** The startup cue, on a cold boot only, once the theme pack's sounds are loaded. */
    fun onColdBoot() {
        val s = settings()
        if (s.bootSound) sounds.playCue(UiCue.OPENING, mix(s).cue)
    }

    /** A game is starting: music pauses (to resume later, or not) and the start cue plays. */
    fun onGameLaunch() {
        val s = settings()
        when (AudioMixer.musicOnLaunch(s.resumeMusicAfterGames)) {
            MusicOnLaunch.PAUSE_AND_RESUME -> music.pauseForGame()
            MusicOnLaunch.PAUSE -> music.player.pause()
        }
        if (s.gameBootSound) sounds.playCue(UiCue.GAMEBOOT, mix(s).cue) else sounds.play(UiSound.OK)
    }

    /** Back in Xross: music a game launch paused picks up again. */
    fun onReturnFromGame() = music.resumeAfterGame()

    private fun applyMusic() {
        val m = mix()
        music.setVolume(m.music)
        // Debug and profiling builds: one line per change, for the device checks (a player's
        // own volume isn't visible in dumpsys audio).
        if (dev.xross.launcher.BuildConfig.PERF_LOG && m.music != lastMusic) {
            android.util.Log.i("XrossMix", "music=%.2f snap=%s snapVolume=%.2f".format(java.util.Locale.ROOT, m.music, snapActive, m.snap))
        }
        lastMusic = m.music
    }

    private var lastMusic = -1f
}
