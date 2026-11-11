package dev.xross.launcher.launch

import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import dev.xross.launcher.data.GameEntity
import dev.xross.launcher.storage.EmulatorOption
import dev.xross.launcher.storage.Platform
import dev.xross.launcher.storage.PlatformRegistry
import java.io.File

sealed interface LaunchPlan {
    data class Ready(val intent: Intent, val emulatorName: String) : LaunchPlan
    data class Failed(val message: String) : LaunchPlan
}

/** Emulator dispatch layer: picks the emulator for a game and builds its intent. */
class GameLauncher(private val context: Context) {

    fun installedPackage(def: EmulatorDef): String? = def.packages.firstOrNull { isInstalled(it) }

    fun installedEmulatorIds(): Set<String> =
        EmulatorRegistry.all.filter { installedPackage(it) != null }.mapTo(HashSet()) { it.id }

    fun plan(game: GameEntity, choiceKey: String?): LaunchPlan {
        val platform = PlatformRegistry.byId(game.platformId)
            ?: return LaunchPlan.Failed("Unknown platform ${game.platformId}")
        val file = File(game.path)
        if (!file.exists()) return LaunchPlan.Failed("File not found — is the storage mounted?")

        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        }.getOrElse { return LaunchPlan.Failed("Can't share ${file.name}") }
        val knownMime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
        val mime = knownMime ?: "application/octet-stream"

        val option = resolveOption(platform, choiceKey, installedEmulatorIds(), game.emulatorOverride)
        if (option == null) {
            // Only fall back to "open with" for real MIME types: catch-all */* handlers (wallets,
            // file managers) would otherwise claim every ROM as application/octet-stream.
            val generic = knownMime?.let {
                EmulatorRegistry.genericIntent(LaunchRequest(game.path, uri, it, packageName = "", core = null))
            }
            return if (generic?.resolveActivity(context.packageManager) != null) {
                LaunchPlan.Ready(Intent.createChooser(generic, game.title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), "system")
            } else {
                val wanted = platform.emulators.firstOrNull()?.let { EmulatorRegistry.byId(it.emulatorId)?.name }
                LaunchPlan.Failed("No emulator for ${platform.shortName}" + (wanted?.let { " — install $it" } ?: ""))
            }
        }

        val def = EmulatorRegistry.byId(option.emulatorId)
            ?: return LaunchPlan.Failed("Unknown emulator ${option.emulatorId}")
        val pkg = installedPackage(def)
            ?: return LaunchPlan.Failed("${def.name} is not installed")
        val intent = def.buildIntent(LaunchRequest(game.path, uri, mime, pkg, option.core))
        return LaunchPlan.Ready(intent, def.name + (option.core?.let { " · $it" } ?: ""))
    }

    private fun isInstalled(pkg: String) =
        runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess

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
