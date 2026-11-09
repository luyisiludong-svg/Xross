package dev.wave.launcher.storage

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
)

fun normalizeFolderName(name: String): String =
    name.lowercase().filter { it.isLetterOrDigit() }

object PlatformRegistry {
    private const val RA = "retroarch"
    private val archives = setOf("zip", "7z")

    private fun ra(vararg cores: String) = cores.map { EmulatorOption(RA, it) }

    val all: List<Platform> = listOf(
        Platform(
            "nes", "Nintendo Entertainment System", "NES",
            setOf("nes", "famicom", "fc", "nintendoentertainmentsystem"),
            setOf("nes", "fds", "unf", "unif") + archives,
            ra("fceumm", "nestopia", "mesen"),
        ),
        Platform(
            "snes", "Super Nintendo", "SNES",
            setOf("snes", "sfc", "superfamicom", "supernintendo", "supernes"),
            setOf("sfc", "smc", "fig", "swc", "bs") + archives,
            ra("snes9x", "bsnes"),
        ),
        Platform(
            "n64", "Nintendo 64", "N64",
            setOf("n64", "nintendo64"),
            setOf("n64", "z64", "v64") + archives,
            ra("mupen64plus_next_gles3", "parallel_n64"),
        ),
        Platform(
            "gb", "Game Boy", "GB",
            setOf("gb", "gameboy"),
            setOf("gb") + archives,
            ra("gambatte", "mgba", "sameboy"),
        ),
        Platform(
            "gbc", "Game Boy Color", "GBC",
            setOf("gbc", "gameboycolor"),
            setOf("gbc") + archives,
            ra("gambatte", "mgba", "sameboy"),
        ),
        Platform(
            "gba", "Game Boy Advance", "GBA",
            setOf("gba", "gameboyadvance"),
            setOf("gba") + archives,
            ra("mgba", "gpsp", "vba_next"),
        ),
        Platform(
            "nds", "Nintendo DS", "NDS",
            setOf("nds", "ds", "nintendods"),
            setOf("nds", "dsi") + archives,
            ra("melondsds", "desmume"),
        ),
        Platform(
            "megadrive", "Sega Genesis / Mega Drive", "MD",
            setOf("genesis", "megadrive", "md", "segagenesis", "segamegadrive"),
            setOf("md", "gen", "smd", "bin") + archives,
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
            setOf("pcengine", "pce", "tg16", "turbografx16", "turbografx"),
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
            listOf(EmulatorOption("nethersx2")),
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
            ra("flycast"),
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
            "arcade", "Arcade", "ARC",
            setOf("arcade", "fbneo", "mame", "fba", "neogeo"),
            archives,
            ra("fbneo", "mame2003_plus"),
        ),
    )

    private val byId = all.associateBy { it.id }
    private val byAlias: Map<String, Platform> = buildMap {
        for (platform in all) for (alias in platform.folderAliases) putIfAbsent(alias, platform)
    }

    fun byId(id: String): Platform? = byId[id]

    fun forFolder(folderName: String): Platform? = byAlias[normalizeFolderName(folderName)]
}
