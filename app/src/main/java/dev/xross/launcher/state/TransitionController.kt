package dev.xross.launcher.state

import android.os.Process
import android.os.SystemClock
import android.util.Log
import dev.xross.launcher.BuildConfig
import dev.xross.launcher.audio.AudioDirector
import dev.xross.launcher.data.GameRepository
import dev.xross.launcher.data.PlaySessionTracker
import dev.xross.launcher.data.SettingsStore
import dev.xross.launcher.launch.GameLauncher
import dev.xross.launcher.launch.LaunchPlan
import dev.xross.launcher.theme.QuickProfile
import dev.xross.launcher.theme.pack.LoadedTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The full-screen transitions [TransitionController] owns. */
data class TransitionState(
    /** The cold-start intro on screen (2b.5d): the boot video or the shader ramp, or its crossfade out. */
    val boot: BootState? = null,
    /** Cold start, before the saved theme pack is loaded and the intro decided: the screen stays black. */
    val bootPending: Boolean = false,
    /** Title of the game being started while the start transition plays. */
    val booting: String? = null,
)

/**
 * The cold-start intro and the game start transition, split out of [XmbViewModel] (Architecture
 * Assessment §7.1, step 6). The intro is decided by [BootSequence] once the saved pack is loaded
 * ([startBoot]); a key before that skips it in advance. A game start plays the bloom and the start
 * cue, then hands the [LaunchPlan] to the Activity. While either is on screen the menu takes no
 * input ([takesInput]).
 */
class TransitionController(
    private val scope: CoroutineScope,
    private val settings: SettingsStore,
    private val audio: AudioDirector,
    private val games: GameRepository,
    private val launcher: GameLauncher,
    private val sessions: PlaySessionTracker,
    /** The active theme pack: its intro and boot video. */
    private val pack: () -> LoadedTheme,
    /** The open category, whose look may pick a different intro. */
    private val category: () -> String?,
    coldBoot: Boolean,
    private val host: Host,
    /** Monotonic nanoseconds, the clock [BootState] is stamped with. */
    private val clock: () -> Long = SystemClock::elapsedRealtimeNanos,
) {
    /** What the controller needs from the menu around it. */
    interface Host {
        /** The error sound, then [message]. */
        fun fail(message: String)
        fun emit(effect: UiEffect)
    }

    private val _state = MutableStateFlow(TransitionState(bootPending = coldBoot))
    val state: StateFlow<TransitionState> = _state.asStateFlow()

    private var bootJob: Job? = null

    /** A key or touch came while the intro was still being decided: it is skipped as soon as it is. */
    private var bootSkippedEarly = false

    /** A game is being started: the start transition is on screen. */
    val launching: Boolean get() = _state.value.booting != null

    /**
     * A key while a transition is on screen: true when it was taken here. Any key skips the intro
     * (or, before it is decided, skips it in advance); the start transition ignores input.
     */
    fun takesInput(): Boolean {
        val s = _state.value
        when {
            s.bootPending -> bootSkippedEarly = true
            s.boot?.ending == false -> skipBoot()
            s.booting != null -> Unit
            else -> return false
        }
        return true
    }

    // region Cold-start intro (2b.5d)

    /**
     * Decides the cold-start intro for the active pack and the settings ([BootSequence]) and starts
     * it: the boot video (its own sound, theme music held), or the shader ramp or the fade-in under
     * the `opening` cue. Each ends on its own after its length, at [BootSequence.MAX_MS] at most.
     */
    fun startBoot() {
        val s = settings.current
        val pack = pack()
        val look = pack.look(s.themeVariant, category = category())
        val user = s.effectOverrides.cosmetics.bootIntro
        val plan = BootSequence.resolve(
            BootInputs(
                coldBoot = true,
                intro = user ?: look.cosmetics.bootIntro,
                userChoice = user != null,
                video = pack.bootVideo,
                stillMotion = QuickProfile.byName(s.quickProfile).stillMotion,
                startupSound = s.bootSound,
                cueVolume = s.cueVolume,
                waveEnabled = s.waveEnabled,
            ),
        ) ?: return
        val now = clock()
        if (BuildConfig.PERF_LOG) {
            val sinceStart = SystemClock.uptimeMillis() - Process.getStartUptimeMillis()
            Log.i("XrossBoot", "start mode=${plan.mode} file=${plan.fileName} early=$bootSkippedEarly after=$sinceStart ms")
        }
        if (bootSkippedEarly) {
            // Skipped before it began: no video, no ramp and no slow fade-in, just the crossfade to the menu.
            logBoot(plan, skipped = true, atMs = 0)
            audio.onColdBoot()
            val quick = BootState(BootPlan(BootMode.FADE), now, ending = true, endedAtNanos = now)
            _state.update { it.copy(bootPending = false, boot = quick) }
            bootJob = scope.launch {
                delay(BootSequence.CROSSFADE_MS)
                _state.update { it.copy(boot = null) }
            }
            return
        }
        if (plan.mode == BootMode.FADE) {
            _state.update { it.copy(bootPending = false) }
            logBoot(plan, skipped = false, atMs = 0)
            audio.onColdBoot()
            return
        }
        _state.update { it.copy(bootPending = false, boot = BootState(plan, now)) }
        if (plan.mode == BootMode.VIDEO) audio.onBootVideo(true) else audio.onColdBoot()
        bootJob = scope.launch {
            delay(plan.maxMs)
            endBoot(skipped = false)
        }
    }

    /** A key or a touch during the intro: it crossfades to the menu. */
    fun skipBoot() {
        if (_state.value.bootPending) bootSkippedEarly = true
        else if (_state.value.boot?.ending == false) endBoot(skipped = true)
    }

    /** The boot video reached its end (or couldn't be played). */
    fun onBootVideoEnded() {
        if (_state.value.boot?.ending == false) endBoot(skipped = false)
    }

    /**
     * The launcher is no longer visible: leaving during the intro ends it (and one not decided yet
     * won't start), so the video doesn't play on behind another app.
     */
    fun onStopped() {
        if (_state.value.bootPending) bootSkippedEarly = true
        if (_state.value.boot != null) endBoot(skipped = false, crossfade = false)
    }

    private fun endBoot(skipped: Boolean, crossfade: Boolean = true) {
        val boot = _state.value.boot ?: return
        bootJob?.cancel()
        val now = clock()
        if (!boot.ending) logBoot(boot.plan, skipped, (now - boot.startedAtNanos) / 1_000_000)
        audio.onBootVideo(false)
        if (!crossfade) {
            _state.update { it.copy(boot = null) }
            return
        }
        _state.update { it.copy(boot = boot.copy(ending = true, endedAtNanos = now)) }
        bootJob = scope.launch {
            delay(BootSequence.CROSSFADE_MS)
            _state.update { it.copy(boot = null) }
        }
    }

    private fun logBoot(plan: BootPlan, skipped: Boolean, atMs: Long) {
        if (BuildConfig.PERF_LOG) Log.i("XrossBoot", BootSequence.describe(plan, skipped, atMs))
    }

    // endregion

    // region Game start

    /** Starts [gameId] with its platform's chosen emulator: the start transition, then the hand-off. */
    fun launchGame(gameId: Long) = scope.launch {
        val game = games.get(gameId) ?: return@launch
        when (val plan = launcher.plan(game, settings.current.emulatorChoice[game.platformId])) {
            is LaunchPlan.Ready -> {
                games.markPlayed(game.id)
                // Start transition: bloom + start cue, then hand off to the emulator.
                _state.update { it.copy(booting = game.title) }
                // Music pauses (to resume on return, or not) and the start cue plays.
                audio.onGameLaunch()
                delay(GAMEBOOT_HANDOFF_MS)
                sessions.arm(game.id)
                host.emit(UiEffect.Launch(plan))
                delay(GAMEBOOT_CLEAR_MS)
                _state.update { it.copy(booting = null) }
            }
            is LaunchPlan.Failed -> host.fail(plan.message)
        }
    }

    /** The emulator couldn't be started after all (uninstalled meanwhile, refused the URI). */
    fun onLaunchFailed(message: String) {
        sessions.disarm()
        audio.onLaunchFailed()
        _state.update { it.copy(booting = null) }
        host.fail(message)
    }

    // endregion

    internal companion object {
        const val GAMEBOOT_HANDOFF_MS = 1100L
        /** After the hand-off, while the emulator comes up over Xross. */
        const val GAMEBOOT_CLEAR_MS = 600L
    }
}
