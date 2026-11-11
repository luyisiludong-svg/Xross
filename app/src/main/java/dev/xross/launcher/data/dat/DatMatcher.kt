package dev.xross.launcher.data.dat

import dev.xross.launcher.data.DatCandidate

/** Which hash produced a candidate (the DAO's `kind` column). */
enum class HashKind { SHA1, MD5, CRC32 }

/** How sure a DAT match is, strongest first. */
enum class MatchKind(val label: String) {
    SHA1("SHA-1"),
    MD5("MD5"),
    /** CRC32 and size both agree. */
    CRC32_SIZE("CRC32 and size"),
    /** CRC32 agrees; the DAT gives no size, or the size couldn't be compared. */
    CRC32("CRC32"),
    /** Arcade set identified by its set name. */
    SET_NAME("Set name"),
    /** Chosen by the user. */
    MANUAL("Chosen manually"),
}

/** The winning DAT ROM for one game. */
data class DatMatch(val gameId: Long, val romId: Long, val kind: MatchKind)

/**
 * Picks one DAT entry per game from the raw hash hits. Pure, so the ranking is unit-tested.
 *
 * Ranking: match strength ([MatchKind] order), then a DAT for the game's own platform, then the
 * most recently imported DAT (newer DAT versions fix names), then the lowest ROM id for stable
 * results. A CRC32 hit whose size contradicts the DAT is dropped: a 32-bit CRC alone collides
 * often enough across a full No-Intro set that a wrong size means a different file.
 */
object DatMatcher {

    fun resolve(candidates: List<DatCandidate>, platformOf: (Long) -> String?): List<DatMatch> =
        candidates.groupBy { it.gameId }.mapNotNull { (gameId, hits) ->
            val platform = platformOf(gameId)
            hits.mapNotNull { hit -> strength(hit)?.let { it to hit } }
                .sortedWith(
                    compareBy<Pair<MatchKind, DatCandidate>> { it.first.ordinal }
                        .thenBy { if (platform != null && it.second.datPlatformId == platform) 0 else 1 }
                        .thenByDescending { it.second.datImportedAt }
                        .thenBy { it.second.romId },
                )
                .firstOrNull()
                ?.let { (kind, hit) -> DatMatch(gameId, hit.romId, kind) }
        }

    internal fun strength(hit: DatCandidate): MatchKind? = when (runCatching { HashKind.valueOf(hit.kind) }.getOrNull()) {
        HashKind.SHA1 -> MatchKind.SHA1
        HashKind.MD5 -> MatchKind.MD5
        HashKind.CRC32 -> when {
            hit.romSize == null || hit.hashedSize == null -> MatchKind.CRC32
            hit.romSize == hit.hashedSize -> MatchKind.CRC32_SIZE
            else -> null
        }
        null -> null
    }

    /** Set-name hits for arcade games: one per game, preferring arcade DATs, then newer ones. */
    fun resolveSetNames(
        games: Map<Long, String>,
        hits: List<dev.xross.launcher.data.SetNameHit>,
    ): List<DatMatch> {
        val byName = hits.groupBy { it.gameName }
        return games.mapNotNull { (gameId, setName) ->
            byName[setName]
                ?.sortedWith(
                    compareBy<dev.xross.launcher.data.SetNameHit> { if (it.datPlatformId == "arcade") 0 else 1 }
                        .thenByDescending { it.datImportedAt }
                        .thenBy { it.romId },
                )
                ?.firstOrNull()
                ?.let { DatMatch(gameId, it.romId, MatchKind.SET_NAME) }
        }
    }
}
