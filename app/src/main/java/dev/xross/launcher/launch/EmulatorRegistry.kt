package dev.xross.launcher.launch

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import java.io.File

/** The recipe hands over a path (not the granted URI), so the emulator needs its own storage access. */
internal fun EsdeRecipe.usesPlainPath(): Boolean {
    val values = listOfNotNull(data) + extras.map { it.value }
    val provider = values.any { it.contains("%ROMPROVIDER%") || it.contains("%ROMSAF%") }
    return !provider && values.any { it.contains("%ROM%") || it.contains("%ROMPATH%") }
}

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
    /**
     * The emulator can't boot a `.cue` it is handed as a single-file URI, so a `.cue` is swapped
     * for its first track ([CueSheet.firstTrack]) before the launch.
     */
    val resolveCue: Boolean = false,
    /**
     * The recipe hands over a plain file path, which the emulator reads with its own storage
     * access (the URI grant doesn't cover it); a failed launch says to grant it.
     */
    val needsStorageAccess: Boolean = false,
    /** Why [buildIntent] can't start this file ("doesn't hold a serial"); null when it can. */
    val check: (File) -> String? = { null },
    /**
     * For a custom recipe: the activity that must exist in the package for it to count as
     * installed, so a spoofed package name (an emulator posing as a benchmark app) without it
     * isn't taken for the emulator.
     */
    val activity: String? = null,
    /** From emulators.json, not shipped with Xross. */
    val custom: Boolean = false,
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

    /** The recipes Xross ships. */
    val builtIn: List<EmulatorDef> = listOf(
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
            // From ARMSX2's own source (github.com/ARMSX2/ARMSX2, MainActivityRuntime.extractLaunchUri):
            // Main reads the intent data first, then EXTRA_STREAM, clip data, and a "path" string
            // extra. It needs a cue sheet's tracks beside it, which a single-file URI can't reach.
            id = "armsx2",
            name = "ARMSX2",
            packages = listOf("com.armsx2", "com.armsx2.nightly"),
            resolveCue = true,
        ) { req ->
            Intent(Intent.ACTION_VIEW)
                .setComponent(ComponentName(req.packageName, "com.armsx2.Main"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("path", req.romPath)
                .withGrantedData(req)
        },
        EmulatorDef(
            // ES-DE's Android rules (es_find_rules.xml / es_systems.xml, ps3): ARMSX3 is built from
            // ARMSX2's code and keeps its com.armsx2.Main activity. A disc image goes as a "path"
            // extra with no action; a .ps3 file holds the serial of a game installed inside
            // ARMSX3, launched by its folder under dev_hdd0. Both are plain paths, so ARMSX3 needs
            // its own storage access (Xross's read grant doesn't cover a path).
            id = "armsx3",
            name = "ARMSX3",
            packages = listOf("com.armsx3", "com.armsx3.play"),
            needsStorageAccess = true,
            check = Armsx3::check,
        ) { req -> Armsx3.intent(req) },
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
        // es_systems.xml). ES-DE hands these emulators a storage-access-framework URI; Xross hands
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

    private class Index(val all: List<EmulatorDef>, val problems: Map<String, List<String>>) {
        val byId = all.associateBy { it.id }
    }

    @Volatile private var index = Index(builtIn, emptyMap())

    /** Built-in recipes, then the usable custom ones from emulators.json. */
    val all: List<EmulatorDef> get() = index.all

    /** Custom entry id → what is wrong with it. An entry with problems is not loaded. */
    val problems: Map<String, List<String>> get() = index.problems

    fun byId(id: String): EmulatorDef? = index.byId[id]

    /** Loads [s]'s custom emulators against the systems it has (built-in and custom). */
    fun install(s: dev.xross.launcher.data.XrossSettings) =
        install(s.customEmulators, dev.xross.launcher.model.PlatformRegistry.builtIn.mapTo(HashSet()) { it.id } + s.customSystems.keys)

    /**
     * Loads emulators.json's entries. [systems] are the system ids that exist (built-in and
     * custom); a recipe naming none of them is still loaded but flagged. A built-in id wins a clash.
     */
    fun install(custom: Map<String, dev.xross.launcher.config.CustomEmulator>, systems: Set<String>) {
        val builtInIds = builtIn.mapTo(HashSet()) { it.id }
        val defs = ArrayList<EmulatorDef>()
        val problems = LinkedHashMap<String, List<String>>()
        for ((id, entry) in custom) {
            val (def, issues) = customDef(id, entry, builtInIds, systems)
            if (issues.isNotEmpty()) problems[id] = issues
            if (def != null) defs += def
        }
        index = Index(builtIn + defs, problems)
    }

    /** A recipe from one emulators.json entry, or null with the reasons it can't load. */
    fun customDef(
        id: String,
        e: dev.xross.launcher.config.CustomEmulator,
        builtInIds: Set<String> = builtIn.mapTo(HashSet()) { it.id },
        systems: Set<String>? = null,
    ): Pair<EmulatorDef?, List<String>> {
        val fatal = ArrayList<String>()
        val warn = ArrayList<String>()
        if (id.isBlank()) fatal += "The entry has no id"
        if (id in builtInIds) fatal += "\"$id\" is a built-in emulator; pick another id"
        val packages = e.packages.map { it.trim() }.filter { it.isNotEmpty() }
        if (packages.isEmpty()) fatal += "No packages"
        val activity = e.activity.trim()
        if (activity.isEmpty()) fatal += "No activity"
        val parsed = EsdeCommand.parse(e.command)
        fatal += parsed.problems
        if (systems != null) {
            val unknown = e.systems.map { it.trim() }.filter { it.isNotEmpty() && it !in systems }
            if (unknown.isNotEmpty()) warn += "Unknown system ${unknown.joinToString()}"
            if (e.systems.none { it.trim() in systems }) warn += "Runs no system Xross has"
        }
        if (fatal.isNotEmpty()) return null to (fatal + warn)
        val def = EmulatorDef(
            id = id,
            name = e.name.trim().ifEmpty { id },
            packages = packages,
            activity = activity,
            custom = true,
            needsStorageAccess = parsed.recipe.usesPlainPath(),
        ) { req -> RecipeIntents.build(parsed.recipe, req, activity) }
        return def to warn
    }

    /** Fallback: let any installed app that handles the file type open it. */
    fun genericIntent(req: LaunchRequest): Intent =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(req.romUri, req.mimeType)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
}
