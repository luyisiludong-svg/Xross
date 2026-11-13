package dev.xross.launcher.audio

/** What a playing song does while a video snap's sound plays (Settings ▸ Sound, 2b.5b). */
enum class MusicUnderSnaps(val label: String) {
    /** Fade out and pause; fade back in when the snap ends, if Xross paused it. */
    PAUSE("Pause"),
    /** Lower it to [AudioMixer.SNAP_DUCK] of its level. */
    DUCK("Lower"),
    ;

    companion object {
        /** `settings.json`'s `sound.musicUnderSnaps`, ignoring case; `LOWER` reads as [DUCK], anything else as [PAUSE]. */
        fun parse(name: String?): MusicUnderSnaps {
            val key = name?.trim().orEmpty()
            return entries.firstOrNull { it.name.equals(key, ignoreCase = true) || it.label.equals(key, ignoreCase = true) } ?: PAUSE
        }
    }
}

/** Where the video snap is. */
enum class SnapState {
    /** None playing. */
    NONE,
    /** Loading: nothing on screen or audible yet. A snap scrolled past stops here. */
    LOADING,
    /** Its first frame is up and its sound is ramping in or playing. */
    AUDIBLE,
}

/** What the mix depends on: the user's volumes and what is playing right now. */
data class MixInputs(
    /** Settings ▸ Sound ▸ Effects volume. */
    val effectsVolume: Float = 0.75f,
    /** Startup and game cues. */
    val cueVolume: Float = 0.75f,
    /** Video snaps. */
    val snapVolume: Float = 0.75f,
    /** Xross's music player. */
    val musicVolume: Float = 1f,
    val musicUnderSnaps: MusicUnderSnaps = MusicUnderSnaps.PAUSE,
    val snap: SnapState = SnapState.NONE,
)

/** The volume, 0–1, each of Xross's players is set to, and whether music must wait for a snap. */
data class Mix(
    /** UI effects (`UiSounds.volume`). */
    val effects: Float,
    /** The `opening` and `gameboot` cues. */
    val cue: Float,
    /** A video snap's target level, reached after its fade-in. */
    val snap: Float,
    /** Xross's music player, on top of the system media volume. */
    val music: Float,
    /** A snap with sound is playing under [MusicUnderSnaps.PAUSE]: a playing song pauses for it. */
    val holdMusic: Boolean = false,
)

/** What music does when a game starts. */
enum class MusicOnLaunch {
    /** Pause, and resume when the user is back in Xross. */
    PAUSE_AND_RESUME,
    /** Pause and stay paused. */
    PAUSE,
}

/**
 * The audio rules in one pure place: which volume each player gets, and what music does around
 * snaps and games. [AudioDirector] applies them. Phase 2b.5c adds theme music to this table.
 */
object AudioMixer {
    /** Music under a video snap with [MusicUnderSnaps.DUCK]: this share of the Music volume. */
    const val SNAP_DUCK = 0.2f

    /** Music fades out before pausing for a snap, and back in after it ([MusicUnderSnaps.PAUSE]). */
    const val SNAP_FADE_MS = 200L

    fun resolve(i: MixInputs): Mix {
        val snap = volume(i.snapVolume)
        val music = volume(i.musicVolume)
        // Only a snap that is actually heard touches the music: one still loading (scrolled
        // past) or one at 0% leaves it alone.
        val snapHeard = i.snap == SnapState.AUDIBLE && snap > 0f
        return Mix(
            effects = volume(i.effectsVolume),
            cue = volume(i.cueVolume),
            snap = snap,
            music = if (snapHeard && i.musicUnderSnaps == MusicUnderSnaps.DUCK) music * SNAP_DUCK else music,
            holdMusic = snapHeard && i.musicUnderSnaps == MusicUnderSnaps.PAUSE,
        )
    }

    fun musicOnLaunch(resumeMusicAfterGames: Boolean): MusicOnLaunch =
        if (resumeMusicAfterGames) MusicOnLaunch.PAUSE_AND_RESUME else MusicOnLaunch.PAUSE

    /** Out of range and NaN (a hand-edited settings.json) clamp to 0–1, NaN to 0. */
    private fun volume(v: Float) = v.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
}
