package dev.xross.launcher.data

/** What a playing song does while a video snap's sound plays (Settings ▸ Sound, 2b.5b). */
enum class MusicUnderSnaps(val label: String) {
    /** Fade out and pause; fade back in when the snap ends, if Xross paused it. */
    PAUSE("Pause"),
    /** Lower it to [dev.xross.launcher.audio.AudioMixer.SNAP_DUCK] of its level. */
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
