package dev.xross.launcher.state

import androidx.compose.runtime.Immutable
import dev.xross.launcher.theme.pack.BootIntro
import java.io.File

/** What a cold start actually shows (2b.5d), after [BootSequence.resolve]. */
enum class BootMode {
    /** The pack's boot video, full-screen over the menu, with its own sound instead of the `opening` cue. */
    VIDEO,

    /** The background shader ramped up from black (`uTransition` 0 → 1) under the `opening` cue. */
    SHADER,

    /** The menu's fade-in under the `opening` cue, as before 2b.5d. */
    FADE,
}

/** Everything the cold-start intro depends on. */
data class BootInputs(
    /** The first screen after the process started (a warm start shows nothing). */
    val coldBoot: Boolean = true,
    /** The look's `effects.bootIntro` with the user's Settings ▸ Theme ▸ Boot intro over it (null: unset). */
    val intro: BootIntro? = null,
    /** [intro] is the user's choice, not the pack's: a chosen shader intro plays even when the pack has a video. */
    val userChoice: Boolean = false,
    /** The pack's boot video, if it has one ([dev.xross.launcher.theme.pack.LoadedTheme.bootVideo]). */
    val video: File? = null,
    /** A still-motion quick profile (Monochrome E-Ink) is on. */
    val stillMotion: Boolean = false,
    /** Settings ▸ Sound ▸ Startup sound. */
    val startupSound: Boolean = true,
    /** Settings ▸ Sound ▸ Startup and game cues, 0–1. */
    val cueVolume: Float = 0.75f,
    /** Settings ▸ Theme ▸ Background ▸ Wave background (the shader intro has nothing to ramp without it). */
    val waveEnabled: Boolean = true,
)

/** The intro to play: [mode], the [video] for [BootMode.VIDEO], and how long it may last. */
@Immutable
data class BootPlan(val mode: BootMode, val video: File? = null, val maxMs: Long = BootSequence.MAX_MS) {
    val fileName: String get() = video?.name ?: "none"
}

/**
 * The cold-start intro (2b.5d), pure. On a cold start only:
 *  - the pack's boot video if it has one (`boot.mp4` or `audio.bootVideo`), unless the look or
 *    the user turns the intro off or the user picks the shader intro (a pack's own shader intro
 *    loses to its video);
 *  - else `effects.bootIntro: SHADER`: the pack's shader ramped up from black over [SHADER_MS];
 *  - else the menu's fade-in ([BootMode.FADE]).
 *
 * The fade-in is all there is under a still-motion profile (E-Ink), when the startup sound is off
 * or the cue volume is 0 (a silent start stays plain), and for the shader without the wave. Any
 * key, touch, or [MAX_MS] ends an intro with a [CROSSFADE_MS] crossfade; the menu is composed
 * underneath all along.
 */
object BootSequence {
    /** An intro (and a boot video) is cut here. */
    const val MAX_MS = 15_000L

    /** The shader intro's length. */
    const val SHADER_MS = 2_500L

    /** The crossfade from an intro (ended or skipped) to the menu. */
    const val CROSSFADE_MS = 300L

    /** The shader intro: the black over the shader lifts over this, from its start. */
    const val SHADER_BLACK_MS = 1_500L

    /** The shader intro: the menu fades in from here to the end. */
    const val SHADER_MENU_AT_MS = 1_200L

    /** The fade-in: the black of a cold start lifts over this, then the menu fades in over [FADE_MENU_MS]. */
    const val FADE_BLACK_MS = 350L
    const val FADE_MENU_MS = 1_400L

    /** The intro a start with [i] plays; null on a warm start (nothing at all: the menu is just there). */
    fun resolve(i: BootInputs): BootPlan? {
        if (!i.coldBoot) return null
        val fade = BootPlan(BootMode.FADE)
        val muted = !i.startupSound || !i.cueVolume.isFinite() || i.cueVolume <= 0f
        if (i.stillMotion || muted) return fade
        val video = i.video
        val shader = if (i.waveEnabled) BootPlan(BootMode.SHADER, maxMs = SHADER_MS) else fade
        return when (i.intro) {
            BootIntro.OFF -> fade
            BootIntro.SHADER -> if (video != null && !i.userChoice) BootPlan(BootMode.VIDEO, video) else shader
            BootIntro.VIDEO, null -> if (video != null) BootPlan(BootMode.VIDEO, video) else fade
        }
    }

    /** The `XrossBoot` log line for [plan] ending [atMs] after it started. */
    fun describe(plan: BootPlan, skipped: Boolean, atMs: Long): String =
        "mode=${plan.mode} file=${plan.fileName} skipped=$skipped at=$atMs ms"
}

/**
 * The intro on screen (in [dev.xross.launcher.state.XmbUiState]): [plan], when it started
 * (`SystemClock.elapsedRealtimeNanos`, the wave renderer's clock), and whether it is crossfading
 * to the menu ([ending], since [endedAtNanos]).
 */
@Immutable
data class BootState(
    val plan: BootPlan,
    val startedAtNanos: Long,
    val ending: Boolean = false,
    val endedAtNanos: Long = 0L,
)
