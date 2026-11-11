package dev.xross.launcher.launch

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import dev.xross.launcher.data.GameEntity
import dev.xross.launcher.storage.PlatformRegistry
import java.io.File

/** An emulator launch as an Intent; the ROM is shared through a `content://` URI with a read grant. */
internal data class IntentTarget(val intent: Intent) : LaunchTarget

/**
 * [GameLauncher] for Android: finds emulators by package, builds their launch Intents
 * ([EmulatorRegistry] recipes), and shares the ROM through this app's FileProvider with a
 * one-shot read grant.
 */
class AndroidIntentGameLauncher(private val context: Context) : GameLauncher {

    fun installedPackage(def: EmulatorDef): String? = def.packages.firstOrNull { isInstalled(it) }

    override fun installedEmulatorIds(): Set<String> =
        EmulatorRegistry.all.filter { installedPackage(it) != null }.mapTo(HashSet()) { it.id }

    override fun start(plan: LaunchPlan.Ready, host: Activity): LaunchResult {
        val target = plan.target as? IntentTarget ?: return LaunchResult.Failed("Can't start this launch")
        return try {
            host.startActivity(target.intent)
            LaunchResult.Started
        } catch (_: ActivityNotFoundException) {
            LaunchResult.Failed("${plan.emulatorName} couldn't be started")
        } catch (_: SecurityException) {
            LaunchResult.Failed("${plan.emulatorName} refused the launch")
        }
    }

    override fun plan(game: GameEntity, choiceKey: String?): LaunchPlan {
        val platform = PlatformRegistry.byId(game.platformId)
            ?: return LaunchPlan.Failed("Unknown platform ${game.platformId}")
        val file = File(game.path)
        if (!file.exists()) return LaunchPlan.Failed("File not found — is the storage mounted?")

        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        }.getOrElse { return LaunchPlan.Failed("Can't share ${file.name}") }
        val knownMime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
        val mime = knownMime ?: "application/octet-stream"

        val option = GameLauncher.resolveOption(platform, choiceKey, installedEmulatorIds(), game.emulatorOverride)
        if (option == null) {
            // Only fall back to "open with" for real MIME types: catch-all */* handlers (wallets,
            // file managers) would otherwise claim every ROM as application/octet-stream.
            val generic = knownMime?.let {
                EmulatorRegistry.genericIntent(LaunchRequest(game.path, uri, it, packageName = "", core = null))
            }
            return if (generic?.resolveActivity(context.packageManager) != null) {
                LaunchPlan.Ready("system", IntentTarget(Intent.createChooser(generic, game.title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)))
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
        return LaunchPlan.Ready(def.name + (option.core?.let { " · $it" } ?: ""), IntentTarget(intent))
    }

    private fun isInstalled(pkg: String) =
        runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess
}
