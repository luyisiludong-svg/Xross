package dev.wave.launcher.launch

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import java.io.File

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

    /** ACTION_VIEW on an explicit activity with the granted ROM URI as data. */
    private fun view(req: LaunchRequest, activity: String) =
        Intent(Intent.ACTION_VIEW)
            .setComponent(ComponentName(req.packageName, activity))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .withGrantedData(req)

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
                // RetroArch reads the ROM extra; the granted URI covers installs without storage access.
                .withGrantedData(req)
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

        // The recipes below follow ES-DE's published Android launch rules (es_find_rules.xml and
        // es_systems.xml). ES-DE hands these emulators a storage-access-framework URI; Wave hands
        // them its own FileProvider URI with a read grant. They are covered by the stub harness
        // but not yet confirmed against real installs.
        EmulatorDef(
            id = "azahar",
            name = "Azahar",
            packages = listOf("org.azahar_emu.azahar", "io.github.lime3ds.android"),
        ) { req ->
            view(req, "org.citra.citra_emu.activities.EmulationActivity")
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        },
        EmulatorDef(
            id = "eden",
            name = "Eden",
            packages = listOf("dev.eden.eden_emulator", "dev.legacy.eden_emulator"),
        ) { req ->
            // Yuzu-family builds start a game from this NFC action carrying the ROM as data.
            Intent("android.nfc.action.TECH_DISCOVERED")
                .setComponent(ComponentName(req.packageName, "org.yuzu.yuzu_emu.activities.EmulationActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .withGrantedData(req)
        },
        EmulatorDef(
            id = "flycast",
            name = "Flycast",
            packages = listOf("com.flycast.emulator"),
        ) { req -> view(req, "com.flycast.emulator.MainActivity") },
        EmulatorDef(
            id = "m64plusfz",
            name = "M64Plus FZ",
            packages = listOf(
                "org.mupen64plusae.v3.fzurita.pro",
                "org.mupen64plusae.v3.fzurita",
                "org.mupen64plusae.v3.fzurita.amazon",
            ),
        ) { req -> view(req, "paulscode.android.mupen64plusae.SplashActivity") },
        EmulatorDef(
            id = "melonds",
            name = "melonDS",
            packages = listOf("me.magnum.melonds", "me.magnum.melondualds"),
        ) { req ->
            Intent("me.magnum.melonds.LAUNCH_ROM")
                .setComponent(ComponentName(req.packageName, "me.magnum.melonds.ui.emulator.EmulatorActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("uri", req.romUri.toString())
                .withGrantedData(req)
        },
        EmulatorDef(
            id = "scummvm",
            name = "ScummVM",
            packages = listOf("org.scummvm.scummvm"),
        ) { req ->
            // A .scummvm launcher file holds the game's ScummVM target id, which is passed as data.
            val file = File(req.romPath)
            val target = runCatching { file.readLines().firstOrNull { it.isNotBlank() }?.trim() }.getOrNull()
                ?: file.nameWithoutExtension
            Intent(Intent.ACTION_MAIN)
                .setComponent(ComponentName(req.packageName, "org.scummvm.scummvm.SplashActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .setData(Uri.parse(target))
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
