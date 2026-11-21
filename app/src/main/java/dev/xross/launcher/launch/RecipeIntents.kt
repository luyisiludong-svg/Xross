package dev.xross.launcher.launch

import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import java.io.File

/** Turns an [EsdeRecipe] into the Intent for one game. */
object RecipeIntents {

    /** "org.app.Activity", or ".Activity" relative to [pkg]. */
    fun className(pkg: String, activity: String): String = if (activity.startsWith(".")) pkg + activity else activity

    fun build(recipe: EsdeRecipe, req: LaunchRequest, activity: String): Intent {
        val values = RomValues(req.romPath, req.romUri.toString(), req.packageName)
        val dir = File(req.romPath).parentFile
        val launch = recipe.resolve(values) { name ->
            runCatching { File(if (name.startsWith("/")) name else File(dir, name).path).takeIf { it.isFile }?.readText() }.getOrNull()
        }
        return intent(launch, req, ComponentName(req.packageName, className(req.packageName, activity)))
    }

    fun intent(launch: ResolvedLaunch, req: LaunchRequest, component: ComponentName): Intent {
        val intent = Intent(launch.action ?: Intent.ACTION_MAIN).setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        for (c in launch.categories) intent.addCategory(c)
        for (flag in launch.flags) {
            intent.addFlags(
                when (flag) {
                    IntentFlag.CLEAR_TASK -> Intent.FLAG_ACTIVITY_CLEAR_TASK
                    IntentFlag.CLEAR_TOP -> Intent.FLAG_ACTIVITY_CLEAR_TOP
                    IntentFlag.NO_HISTORY -> Intent.FLAG_ACTIVITY_NO_HISTORY
                },
            )
        }
        when {
            launch.data == null -> Unit
            launch.dataIsProvider -> intent.setDataAndType(req.romUri, req.mimeType)
            else -> intent.data = Uri.parse(launch.data)
        }
        for (e in launch.extras) {
            when (e.type) {
                ExtraType.STRING -> intent.putExtra(e.key, e.value)
                ExtraType.BOOL -> intent.putExtra(e.key, e.value.equals("true", ignoreCase = true))
                ExtraType.INT -> intent.putExtra(e.key, e.value.toIntOrNull() ?: 0)
                ExtraType.ARRAY -> intent.putExtra(e.key, e.value.split(',').map { it.trim() }.toTypedArray())
            }
        }
        if (launch.grantsRead) {
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // A URI only in an extra is granted through the clip data.
            if (!launch.dataIsProvider) intent.clipData = ClipData.newRawUri("rom", req.romUri)
        }
        return intent
    }
}
