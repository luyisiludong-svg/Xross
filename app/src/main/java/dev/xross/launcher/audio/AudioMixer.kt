package dev.xross.launcher.audio

/** What the mix depends on: the user's volumes and what is playing right now. */
data class MixInputs(
    /** Settings ▸ Sound ▸ Effects volume. */
    val effectsVolume: Float = 0.75f,
    /** A video snap is playing (from the moment it starts loading until it stops). */
    val snapActive: Boolean = false,
)

/** The volume, 0–1, each of Xross's players is set to. */
data class Mix(
    /** UI effects (`UiSounds.volume`). */
    val effects: Float,
    /** The `opening` and `gameboot` cues. */
    val cue: Float,
    /** A video snap's target level, reached after its fade-in. */
    val snap: Float,
    /** Xross's music player, on top of the system media volume. */
    val music: Float,
)

/** What music does when a game starts. */
enum class MusicOnLaunch {
    /** Pause, and resume when the user is back in Xross. */
    PAUSE_AND_RESUME,
    /** Pause and stay paused. */
    PAUSE,
}

/**
 * The audio rules in one pure place (Phase 2b.5-prep): which volume each player gets, and what
 * music does around snaps and games. [AudioDirector] applies them. Phases 2b.5b (separate volumes,
 * music paused under snaps) and 2b.5c (theme BGM) extend this table.
 */
object AudioMixer {
    /** Music under a video snap: lowered, not paused. */
    const val SNAP_DUCK = 0.2f

    fun resolve(i: MixInputs): Mix {
        val fx = i.effectsVolume.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        return Mix(
            effects = fx,
            // Cues and snaps follow the one Effects volume until 2b.5b gives them their own.
            cue = fx,
            snap = fx,
            music = if (i.snapActive) SNAP_DUCK else 1f,
        )
    }

    fun musicOnLaunch(resumeMusicAfterGames: Boolean): MusicOnLaunch =
        if (resumeMusicAfterGames) MusicOnLaunch.PAUSE_AND_RESUME else MusicOnLaunch.PAUSE
}
