package dev.xross.launcher.launch

import android.app.Activity
import dev.xross.launcher.data.GameEntity
import dev.xross.launcher.storage.EmulatorOption
import dev.xross.launcher.storage.Platform

/** What [GameLauncher.plan] decided for a game. */
sealed interface LaunchPlan {
    /** Ready to start with [emulatorName]; [target] is only meaningful to the launcher that made it. */
    data class Ready(val emulatorName: String, val target: LaunchTarget) : LaunchPlan
    data class Failed(val message: String) : LaunchPlan
}

/**
 * Launcher-specific description of what to start (for [AndroidIntentGameLauncher], an Intent with
 * its URI grants). Opaque to UI and repository code.
 */
interface LaunchTarget

sealed interface LaunchResult {
    data object Started : LaunchResult
    data class Failed(val message: String) : LaunchResult
}

/**
 * Emulator dispatch: which emulators are installed, which one a game launches with, and starting
 * it. UI and repository code only see [LaunchPlan]s; how a launch reaches the emulator (Intents,
 * `content://` grants, package queries) stays in the implementation.
 */
interface GameLauncher {
    /** [EmulatorDef.id]s of the emulators installed on this device. */
    fun installedEmulatorIds(): Set<String>

    /** Picks the emulator for [game] ([choiceKey]: the platform's chosen [EmulatorOption.key]). */
    fun plan(game: GameEntity, choiceKey: String?): LaunchPlan

    /** Starts a plan this launcher made, from the visible launcher screen. */
    fun start(plan: LaunchPlan.Ready, host: Activity): LaunchResult

    companion object {
        /**
         * Precedence: the game's own override, then the user's choice for the platform, then the
         * first installed option in the platform's preference list. A choice whose emulator isn't
         * installed is skipped. Null means nothing usable.
         */
        fun resolveOption(
            platform: Platform,
            choiceKey: String?,
            installed: Set<String>,
            overrideKey: String? = null,
        ): EmulatorOption? {
            for (key in listOf(overrideKey, choiceKey)) {
                val option = platform.emulators.firstOrNull { it.key == key } ?: continue
                if (option.emulatorId in installed) return option
            }
            return platform.emulators.firstOrNull { it.emulatorId in installed }
        }
    }
}
