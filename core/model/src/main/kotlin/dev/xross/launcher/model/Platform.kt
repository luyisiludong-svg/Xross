package dev.xross.launcher.model

/**
 * One emulator choice for a platform. [core] is the libretro core basename when the emulator is
 * RetroArch (e.g. "snes9x" → snes9x_libretro_android.so).
 */
data class EmulatorOption(val emulatorId: String, val core: String? = null) {
    val key: String get() = if (core == null) emulatorId else "$emulatorId:$core"
}

data class Platform(
    val id: String,
    val name: String,
    val shortName: String,
    /** Folder names that identify this platform, compared after [normalizeFolderName]. */
    val folderAliases: Set<String>,
    val extensions: Set<String>,
    /** Ordered by preference; the first installed one is the default. */
    val emulators: List<EmulatorOption>,
    val isDiscBased: Boolean = false,
    /**
     * A system the user defined in `systems.json` (0.2.2.0 § 1). It scans, launches and scrapes
     * by name like a built-in one, but gets no RetroAchievements or DAT matching.
     */
    val custom: Boolean = false,
    /** A custom system's console logo: an image file path, or null. */
    val logo: String? = null,
)

fun normalizeFolderName(name: String): String =
    name.lowercase().filter { it.isLetterOrDigit() }

/**
 * The user's additions to a built-in system, from `Xross/config/systems.json`: more folder names
 * that identify it and more file extensions to list. Built-in values always stay.
 */
data class SystemOverride(
    val extraFolders: Set<String> = emptySet(),
    val extraExtensions: Set<String> = emptySet(),
) {
    val isEmpty: Boolean get() = extraFolders.isEmpty() && extraExtensions.isEmpty()

    companion object {
        fun normalizeExtension(ext: String): String = ext.trim().trimStart('.').lowercase()
    }
}

object PlatformRegistry {
    private const val RA = "retroarch"
    private val archives = setOf("zip", "7z")

    private fun ra(vararg cores: String) = cores.map { EmulatorOption(RA, it) }

    /**
     * Systems as Xross ships them, before any [SystemOverride]. ES-DE's hack and MSU folders
     * (`nesh`, `snesh`, `snes-msu1`, `gbh`, `gbch`, `gbah`, `genh`, `msu-md`) are folder names of
     * their base system, so a ROMs tree laid out for ES-DE scans as it is.
     */
    val builtIn: List<Platform> = listOf(
        Platform(
            "nes", "Nintendo Entertainment System", "NES",
            setOf("nes", "famicom", "fc", "nintendoentertainmentsystem", "nesh"),
            setOf("nes", "fds", "unf", "unif") + archives,
            ra("fceumm", "nestopia", "mesen"),
        ),
        Platform(
            "snes", "Super Nintendo", "SNES",
            setOf("snes", "sfc", "superfamicom", "supernintendo", "supernes", "snesh", "snesmsu1"),
            setOf("sfc", "smc", "fig", "swc", "bs") + archives,
            ra("snes9x", "bsnes"),
        ),
        Platform(
            "n64", "Nintendo 64", "N64",
            setOf("n64", "nintendo64"),
            setOf("n64", "z64", "v64") + archives,
            listOf(EmulatorOption("m64plusfz")) + ra("mupen64plus_next_gles3", "parallel_n64"),
        ),
        Platform(
            "gb", "Game Boy", "GB",
            setOf("gb", "gameboy", "gbh"),
            setOf("gb") + archives,
            ra("gambatte", "mgba", "sameboy"),
        ),
        Platform(
            "gbc", "Game Boy Color", "GBC",
            setOf("gbc", "gameboycolor", "gbch"),
            setOf("gbc") + archives,
            ra("gambatte", "mgba", "sameboy"),
        ),
        Platform(
            "gba", "Game Boy Advance", "GBA",
            setOf("gba", "gameboyadvance", "gbah"),
            setOf("gba") + archives,
            ra("mgba", "gpsp", "vba_next"),
        ),
        Platform(
            "nds", "Nintendo DS", "NDS",
            setOf("nds", "ds", "nintendods"),
            setOf("nds", "dsi") + archives,
            listOf(EmulatorOption("melonds")) + ra("melondsds", "desmume"),
        ),
        Platform(
            "megadrive", "Sega Genesis / Mega Drive", "MD",
            // ES-DE's hack and MSU-MD folders (genh, msu-md) list here too; MSU-MD games are
            // cue sheets (or CHDs) that Genesis Plus GX boots.
            setOf("genesis", "megadrive", "md", "segagenesis", "segamegadrive", "genh", "msumd"),
            setOf("md", "gen", "smd", "bin", "cue", "chd") + archives,
            ra("genesis_plus_gx", "picodrive"),
        ),
        Platform(
            "mastersystem", "Sega Master System", "SMS",
            setOf("mastersystem", "sms", "segamastersystem"),
            setOf("sms") + archives,
            ra("genesis_plus_gx", "picodrive"),
        ),
        Platform(
            "gamegear", "Sega Game Gear", "GG",
            setOf("gamegear", "gg", "segagamegear"),
            setOf("gg") + archives,
            ra("genesis_plus_gx"),
        ),
        Platform(
            "pcengine", "PC Engine / TurboGrafx-16", "PCE",
            // ES-DE keeps CD games in their own folders; they are the same system here.
            setOf("pcengine", "pce", "tg16", "turbografx16", "turbografx", "pcenginecd", "tgcd", "turbografxcd", "supercd"),
            setOf("pce", "cue", "chd") + archives,
            ra("mednafen_pce_fast", "mednafen_pce"),
            isDiscBased = true,
        ),
        Platform(
            "psx", "PlayStation", "PS1",
            setOf("psx", "ps1", "playstation", "playstation1", "sonyplaystation"),
            setOf("cue", "chd", "pbp", "m3u", "iso", "bin", "ecm"),
            listOf(EmulatorOption("duckstation")) + ra("swanstation", "pcsx_rearmed"),
            isDiscBased = true,
        ),
        Platform(
            "ps2", "PlayStation 2", "PS2",
            setOf("ps2", "playstation2", "sonyplaystation2"),
            setOf("iso", "chd", "cso", "gz", "bin", "cue"),
            listOf(EmulatorOption("nethersx2"), EmulatorOption("armsx2")),
            isDiscBased = true,
        ),
        Platform(
            // Disc images, and `.ps3` files naming a game installed inside ARMSX3 (its serial).
            // Folder-format dumps (PS3_GAME/…) aren't scanned.
            "ps3", "PlayStation 3", "PS3",
            setOf("ps3", "playstation3", "sonyplaystation3"),
            setOf("chd", "iso", "ps3"),
            listOf(EmulatorOption("armsx3")),
            isDiscBased = true,
        ),
        Platform(
            "psp", "PlayStation Portable", "PSP",
            setOf("psp", "playstationportable"),
            setOf("iso", "cso", "chd", "pbp", "elf"),
            listOf(EmulatorOption("ppsspp")) + ra("ppsspp"),
        ),
        Platform(
            "gc", "Nintendo GameCube", "GC",
            setOf("gc", "gamecube", "ngc", "nintendogamecube"),
            setOf("iso", "gcm", "rvz", "gcz", "ciso"),
            listOf(EmulatorOption("dolphin")),
        ),
        Platform(
            "wii", "Nintendo Wii", "Wii",
            setOf("wii", "nintendowii"),
            setOf("iso", "wbfs", "rvz", "gcz", "wad"),
            listOf(EmulatorOption("dolphin")),
        ),
        Platform(
            "dreamcast", "Sega Dreamcast", "DC",
            setOf("dreamcast", "dc", "segadreamcast"),
            setOf("cdi", "gdi", "chd", "m3u"),
            listOf(EmulatorOption("flycast")) + ra("flycast"),
            isDiscBased = true,
        ),
        Platform(
            "saturn", "Sega Saturn", "SAT",
            setOf("saturn", "segasaturn"),
            setOf("cue", "chd", "m3u", "ccd"),
            ra("mednafen_saturn", "yabasanshiro"),
            isDiscBased = true,
        ),
        Platform(
            "n3ds", "Nintendo 3DS", "3DS",
            setOf("n3ds", "3ds", "nintendo3ds"),
            setOf("3ds", "3dsx", "app", "axf", "cci", "cxi", "elf", "z3dsx", "zcci", "zcxi"),
            listOf(EmulatorOption("azahar")) + ra("citra"),
        ),
        Platform(
            "switch", "Nintendo Switch", "NSW",
            setOf("switch", "nintendoswitch", "nsw"),
            setOf("nca", "nro", "nso", "nsp", "xci"),
            listOf(EmulatorOption("eden")),
        ),
        Platform(
            "scummvm", "ScummVM", "SVM",
            setOf("scummvm"),
            // Launcher files that contain the game's ScummVM target id.
            setOf("scummvm", "svm"),
            listOf(EmulatorOption("scummvm")) + ra("scummvm"),
        ),
        Platform(
            "arcade", "Arcade", "ARC",
            setOf("arcade", "fbneo", "mame", "fba", "neogeo"),
            archives,
            ra("fbneo", "mame2003_plus"),
        ),
    )

    private class Index(val all: List<Platform>, builtInAliases: Map<String, Set<String>>) {
        val byId = all.associateBy { it.id }

        /** Built-in folder names win over user-added ones, so an override can't steal "snes". */
        val byAlias: Map<String, Platform> = buildMap {
            for (platform in all) for (alias in builtInAliases[platform.id].orEmpty()) putIfAbsent(alias, platform)
            for (platform in all) for (alias in platform.folderAliases) putIfAbsent(alias, platform)
        }
    }

    private val builtInAliases = builtIn.associate { it.id to it.folderAliases }

    @Volatile private var index = Index(builtIn, builtInAliases)

    /** Every system, with the user's [SystemOverride]s applied. */
    val all: List<Platform> get() = index.all

    fun byId(id: String): Platform? = index.byId[id]

    fun forFolder(folderName: String): Platform? = index.byAlias[normalizeFolderName(folderName)]

    /** Applies the user's additions (unknown system ids are ignored). Takes effect for the next scan. */
    fun applyOverrides(overrides: Map<String, SystemOverride>) {
        index = Index(withOverrides(builtIn, overrides), builtInAliases)
    }

    /**
     * Installs the full list: built-ins (with overrides and any extra emulator options) followed by
     * the user's custom systems. A custom system whose id is a built-in one is dropped, and a
     * built-in folder name always stays with its built-in system.
     */
    fun install(platforms: List<Platform>) {
        val builtInIds = builtIn.mapTo(HashSet()) { it.id }
        val seen = HashSet<String>()
        val kept = platforms.filter { p -> (!p.custom || p.id !in builtInIds) && seen.add(p.id) }
        index = Index(kept, builtInAliases)
    }

    fun withOverrides(platforms: List<Platform>, overrides: Map<String, SystemOverride>): List<Platform> =
        platforms.map { platform ->
            val o = overrides[platform.id] ?: return@map platform
            platform.copy(
                folderAliases = platform.folderAliases + o.extraFolders.map(::normalizeFolderName).filter { it.isNotEmpty() },
                extensions = platform.extensions + o.extraExtensions.map(SystemOverride::normalizeExtension).filter { it.isNotEmpty() },
            )
        }
}
