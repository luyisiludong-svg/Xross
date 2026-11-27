package dev.xross.launcher.data

/**
 * Settings ▸ System ▸ Performance: when the screen rests. After [timeoutMs] without input (and
 * nothing playing) the background drops to its standby frame rate and the focus glows hold still;
 * [NEVER] keeps everything moving. The ambient particles rest after at least
 * [particleTimeoutMs].
 */
enum class StandbyMotion(val label: String, val detail: String, val timeoutMs: Long?) {
    AFTER_3S("After 3 seconds", "Saves the most battery", 3_000L),
    AFTER_30S("After 30 seconds", "Room to read a description", 30_000L),
    NEVER("Never", "Always full motion; uses more battery", null),
    ;

    /** The particles' rest: never sooner than [PARTICLE_MIN_MS], and never with [NEVER]. */
    val particleTimeoutMs: Long? get() = timeoutMs?.let { maxOf(it, PARTICLE_MIN_MS) }

    fun next(): StandbyMotion = entries[(ordinal + 1) % entries.size]

    companion object {
        /** The particles' own rest before this setting existed ([dev.xross.launcher.ui.ParticleRest.IDLE_AFTER_MS]). */
        const val PARTICLE_MIN_MS = 10_000L

        /** `settings.json`'s `performance.standbyMotion`, ignoring case; anything else is [AFTER_3S]. */
        fun parse(name: String?): StandbyMotion =
            entries.firstOrNull { it.name.equals(name?.trim(), ignoreCase = true) } ?: AFTER_3S
    }
}

/**
 * Settings ▸ System ▸ Performance: what the frame-budget watchdog does with a background shader
 * whose GPU time is over budget. A shader far over it (every sample over 4× the budget) is retired
 * either way, and the crash guard always applies.
 */
enum class SlowShaders(val label: String, val detail: String) {
    RETIRE("Switch to the wave", "A shader too slow for this GPU is replaced for the session"),
    WARN("Keep and warn", "Only a shader far over budget is replaced; others stay, with a notice"),
    ;

    fun next(): SlowShaders = entries[(ordinal + 1) % entries.size]

    companion object {
        /** `settings.json`'s `performance.slowShaders`, ignoring case; anything else is [RETIRE]. */
        fun parse(name: String?): SlowShaders =
            entries.firstOrNull { it.name.equals(name?.trim(), ignoreCase = true) } ?: RETIRE
    }
}
