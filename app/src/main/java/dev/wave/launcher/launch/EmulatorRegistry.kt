package dev.wave.launcher.launch

import android.content.ComponentName
import android.content.Intent
import android.net.Uri

/** Everything an intent recipe needs to start a game. */
data class LaunchRequest(
    /** Absolute filesystem path of the ROM. */
    val romPath: String,
    /** content:// URI for the same file, served by our FileProvider. */
    val romUri: Uri,
    val mimeType: String,
    /** The installed package chosen from [EmulatorDef.packages]. */
    val packageName: String,
    val core: String?,
)

data class EmulatorDef(
    val id: String,
    val name: String,
    /** Package IDs in preference order (e.g. RetroArch 64-bit before the universal build). */
    val packages: List<String>,
    val buildIntent: (LaunchRequest) -> Intent,
)

/**
 * Intent schema matrix. Each recipe matches what the emulator's own activity reads; these are
 * the same contracts used by other Android frontends. Add new emulators here only after testing
 * on a real install, because a wrong extra name fails silently (the emulator opens to its menu).
 */
object EmulatorRegistry {
    const val GENERIC = "generic"

    private const val CLEAR = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK

    private fun explicit(pkg: String, activity: String) =
        Intent(Intent.ACTION_MAIN).setComponent(ComponentName(pkg, activity)).addFlags(CLEAR)

    /** Attaches the content URI with a read grant (permission bridge) alongside the path extra. */
    private fun Intent.withGrantedData(req: LaunchRequest): Intent =
        setDataAndType(req.romUri, req.mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    val all: List<EmulatorDef> = listOf(
        EmulatorDef(
            id = "retroarch",
            name = "RetroArch",
            packages = listOf("com.retroarch.aarch64", "com.retroarch", "com.retroarch.ra32"),
        ) { req ->
            val pkg = req.packageName
            explicit(pkg, "com.retroarch.browser.retroactivity.RetroActivityFuture")
                .putExtra("ROM", req.romPath)
                .putExtra("LIBRETRO", "/data/data/$pkg/cores/${req.core}_libretro_android.so")
                .putExtra("CONFIGFILE", "/storage/emulated/0/Android/data/$pkg/files/retroarch.cfg")
        },
        EmulatorDef(
            id = "duckstation",
            name = "DuckStation",
            packages = listOf("com.github.stenzek.duckstation"),
        ) { req ->
            explicit(req.packageName, "com.github.stenzek.duckstation.EmulationActivity")
                .putExtra("bootPath", req.romPath)
                .withGrantedData(req)
        },
        EmulatorDef(
            id = "nethersx2",
            name = "NetherSX2",
            packages = listOf("xyz.aethersx2.android"),
        ) { req ->
            explicit(req.packageName, "xyz.aethersx2.android.EmulationActivity")
                .putExtra("bootPath", req.romPath)
                .withGrantedData(req)
        },
        EmulatorDef(
            id = "dolphin",
            name = "Dolphin",
            packages = listOf("org.dolphinemu.dolphinemu"),
        ) { req ->
            explicit(req.packageName, "org.dolphinemu.dolphinemu.ui.main.MainActivity")
                .putExtra("AutoStartFile", req.romPath)
                .withGrantedData(req)
        },
        EmulatorDef(
            id = "ppsspp",
            name = "PPSSPP",
            packages = listOf("org.ppsspp.ppssppgold", "org.ppsspp.ppsspp"),
        ) { req ->
            Intent(Intent.ACTION_VIEW)
                .setComponent(ComponentName(req.packageName, "org.ppsspp.ppsspp.PpssppActivity"))
                .addFlags(CLEAR)
                .withGrantedData(req)
        },
    )

    private val byId = all.associateBy { it.id }

    fun byId(id: String): EmulatorDef? = byId[id]

    /** Fallback: let any installed app that handles the file type open it. */
    fun genericIntent(req: LaunchRequest): Intent =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(req.romUri, req.mimeType)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
}
