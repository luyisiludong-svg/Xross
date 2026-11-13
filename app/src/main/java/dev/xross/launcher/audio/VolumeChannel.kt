package dev.xross.launcher.audio

import dev.xross.launcher.data.XrossSettings

/** One of the volumes in Settings ▸ Sound (2b.5b), each 0–1 in `settings.json`'s `sound` block. */
enum class VolumeChannel(val title: String) {
    /** UI effects: `sound.effectsVolume`. */
    EFFECTS("Effects volume"),
    /** The `opening` and `gameboot` cues: `sound.cueVolume`. */
    CUES("Startup and game cues"),
    /** The theme pack's ambient music (2b.5c): `sound.bgmVolume`. */
    BGM("Theme music"),
    /** Xross's music player, on top of the system media volume: `sound.musicVolume`. */
    MUSIC("Music"),
    /** Video snaps: `sound.snapVolume`. */
    SNAPS("Video snaps"),
    ;

    fun of(s: XrossSettings): Float = when (this) {
        EFFECTS -> s.soundVolume
        CUES -> s.cueVolume
        BGM -> s.bgmVolume
        MUSIC -> s.musicVolume
        SNAPS -> s.snapVolume
    }

    fun set(s: XrossSettings, v: Float): XrossSettings {
        val c = v.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        return when (this) {
            EFFECTS -> s.copy(soundVolume = c)
            CUES -> s.copy(cueVolume = c)
            BGM -> s.copy(bgmVolume = c)
            MUSIC -> s.copy(musicVolume = c)
            SNAPS -> s.copy(snapVolume = c)
        }
    }

    companion object {
        /** Confirm on a volume row steps through these, 0–100% in 10% steps, then wraps to 0. */
        val STEPS: List<Float> = (0..10).map { it / 10f }

        /** The step after [v]; a hand-edited value between steps goes to the next one up. */
        fun next(v: Float): Float = STEPS.firstOrNull { it > v + 0.001f } ?: STEPS.first()

        /** "70%". */
        fun percent(v: Float): String = "${kotlin.math.round(v * 100).toInt()}%"
    }
}
