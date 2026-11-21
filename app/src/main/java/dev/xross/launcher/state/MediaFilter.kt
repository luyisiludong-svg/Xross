package dev.xross.launcher.state

import dev.xross.launcher.data.GameMetadataEntity
import dev.xross.launcher.model.ArtKind

/**
 * Which games a media filter keeps (0.2.2.0 § 3 and § 4), read from `game_metadata.mediaStatus`
 * only, so filtering a system never touches the file system. A game without metadata, or whose
 * status isn't known yet (NULL until the first library load fills it), counts as having nothing.
 */
enum class MediaFilter(val label: String, val kinds: Set<ArtKind>) {
    ALL("All", emptySet()),
    /** Settings' "Missing art" (§ 4): box art, hero or logo. */
    MISSING_ART("Missing art", setOf(ArtKind.BOX_2D, ArtKind.HERO, ArtKind.WHEEL)),
    MISSING_ANY("Missing anything", setOf(ArtKind.BOX_2D, ArtKind.BOX_3D, ArtKind.GRID, ArtKind.HERO, ArtKind.WHEEL, ArtKind.SCREENSHOT)),
    MISSING_BOX("Missing box art", setOf(ArtKind.BOX_2D)),
    MISSING_HERO("Missing hero", setOf(ArtKind.HERO)),
    MISSING_LOGO("Missing logo", setOf(ArtKind.WHEEL)),
    ;

    /** True when [meta] lacks any of [kinds] (always true for [ALL]). A slot removed on purpose still counts as missing. */
    fun keeps(meta: GameMetadataEntity?): Boolean {
        if (this == ALL) return true
        val have = meta?.mediaStatus ?: 0
        return kinds.any { have and it.bit == 0 }
    }

    companion object {
        /** The filters the library browser's filter row offers, in order. */
        val BROWSER = listOf(ALL, MISSING_ANY, MISSING_BOX, MISSING_HERO, MISSING_LOGO)
    }
}
