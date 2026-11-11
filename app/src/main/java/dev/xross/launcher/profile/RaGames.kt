package dev.xross.launcher.profile

import kotlinx.serialization.Serializable

/** One achievement of a game, with the linked user's unlock state. Public data. */
@Serializable
data class RaAchievement(
    val id: Int,
    val title: String,
    val description: String = "",
    val points: Int = 0,
    /** RetroPoints: [points] weighted by rarity. */
    val trueRatio: Int = 0,
    /** Badge image id; see [RetroAchievementsClient.badgeUrl]. */
    val badgeName: String = "00000",
    val displayOrder: Int = 0,
    /** "progression", "win_condition", "missable" or null. */
    val type: String? = null,
    /** "2024-03-01 18:02:11" (UTC) when unlocked in either mode, else null. */
    val unlockedAt: String? = null,
    /** Set when unlocked in hardcore mode. */
    val unlockedHardcoreAt: String? = null,
    /** How many players have it. */
    val earnedBy: Int = 0,
) {
    val unlocked: Boolean get() = unlockedAt != null || unlockedHardcoreAt != null
    val hardcore: Boolean get() = unlockedHardcoreAt != null

    /** The date to show: the hardcore unlock wins. */
    val unlockDate: String? get() = unlockedHardcoreAt ?: unlockedAt
}

/** A game's achievement set and the user's progress in it. */
@Serializable
data class RaGameProgress(
    val gameId: Int,
    val title: String,
    val consoleName: String = "",
    val iconUrl: String = "",
    val boxArtUrl: String = "",
    val players: Int = 0,
    val achievements: List<RaAchievement> = emptyList(),
    val fetchedAtMs: Long = 0,
) {
    val total: Int get() = achievements.size
    val unlocked: Int get() = achievements.count { it.unlocked }
    val unlockedHardcore: Int get() = achievements.count { it.hardcore }
    val totalPoints: Int get() = achievements.sumOf { it.points }
    val earnedPoints: Int get() = achievements.filter { it.unlocked }.sumOf { it.points }

    /** 0–1 share of achievements unlocked; 0 for a game without any. */
    val completion: Float get() = if (total == 0) 0f else unlocked.toFloat() / total

    /** Unlocked first (newest first), then locked in the game's display order. */
    fun ordered(): List<RaAchievement> {
        val (open, locked) = achievements.partition { it.unlocked }
        return open.sortedByDescending { it.unlockDate } + locked
    }
}

/** A row of a console's game list: the RetroAchievements game and the ROM hashes it accepts. */
@Serializable
data class RaGameEntry(
    val id: Int,
    val title: String,
    val achievements: Int = 0,
    /** Lower-case MD5s. */
    val hashes: List<String> = emptyList(),
)

sealed interface RaGameResult {
    data class Ok(val progress: RaGameProgress) : RaGameResult
    data class Error(val error: RaResult) : RaGameResult
}

sealed interface RaListResult {
    data class Ok(val games: List<RaGameEntry>) : RaListResult
    data class Error(val error: RaResult) : RaListResult
}

/**
 * Xross platform id → RetroAchievements console id, and how RetroAchievements hashes that
 * console's ROMs (from rcheevos `rc_hash`). Platforms missing here have no achievements
 * (Wii, 3DS, Switch, ScummVM).
 */
object RaConsoles {
    enum class HashMethod {
        /** MD5 of the whole ROM (or of the ROM inside a .zip/.7z). */
        WHOLE_FILE,

        /** MD5 without a copier/iNES header: Xross's header-less fingerprint, else the whole file. */
        HEADERLESS,

        /** MD5 of the set name (the .zip file name without its extension). */
        ARCADE_SET_NAME,

        /** Disc and NDS hashes read parts of the image; Xross matches these games by title. */
        TITLE_ONLY,
    }

    data class Console(val id: Int, val method: HashMethod)

    val byPlatform: Map<String, Console> = mapOf(
        "megadrive" to Console(1, HashMethod.WHOLE_FILE),
        "n64" to Console(2, HashMethod.WHOLE_FILE),
        "snes" to Console(3, HashMethod.HEADERLESS),
        "gb" to Console(4, HashMethod.WHOLE_FILE),
        "gba" to Console(5, HashMethod.WHOLE_FILE),
        "gbc" to Console(6, HashMethod.WHOLE_FILE),
        "nes" to Console(7, HashMethod.HEADERLESS),
        "pcengine" to Console(8, HashMethod.HEADERLESS),
        "mastersystem" to Console(11, HashMethod.WHOLE_FILE),
        "psx" to Console(12, HashMethod.TITLE_ONLY),
        "gamegear" to Console(15, HashMethod.WHOLE_FILE),
        "gc" to Console(16, HashMethod.TITLE_ONLY),
        "nds" to Console(18, HashMethod.TITLE_ONLY),
        "ps2" to Console(21, HashMethod.TITLE_ONLY),
        "arcade" to Console(27, HashMethod.ARCADE_SET_NAME),
        "saturn" to Console(39, HashMethod.TITLE_ONLY),
        "dreamcast" to Console(40, HashMethod.TITLE_ONLY),
        "psp" to Console(41, HashMethod.TITLE_ONLY),
    )

    fun of(platformId: String): Console? = byPlatform[platformId]
}
