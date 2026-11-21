package dev.xross.launcher.state

import androidx.compose.runtime.Immutable
import dev.xross.launcher.data.GameEntity
import dev.xross.launcher.data.GameMetadataEntity
import dev.xross.launcher.model.ArtKind
import dev.xross.launcher.model.Platform

/** One system on the browser's console strip; [id] null is All. */
@Immutable
data class BrowserSystem(val id: String?, val name: String, val shortName: String, val count: Int)

/** One game row: which art kinds have a file ([have], a `mediaStatus` mask) and which are pinned. */
@Immutable
data class BrowserRow(
    val gameId: Long,
    val title: String,
    val platformShort: String,
    val have: Int,
    val pinned: Set<ArtKind>,
) {
    fun has(kind: ArtKind) = have and kind.bit != 0
}

/** Where input goes: the filter row above the list, or the list. */
enum class BrowserFocus { FILTERS, LIST }

/**
 * Settings ▸ Metadata ▸ Fix media… (0.2.2.0 § 3): a console strip (All, then every scanned
 * system), a filter row, and the games of the chosen system with a badge per art kind. Pure:
 * built from the library and its metadata, so scrolling never touches the file system. The
 * platform, filter, cursor and scroll survive a round trip through the Metadata & Media editor.
 */
@Immutable
data class LibraryBrowserState(
    val systems: List<BrowserSystem>,
    val systemIndex: Int = 0,
    val filter: MediaFilter = MediaFilter.ALL,
    val rows: List<BrowserRow> = emptyList(),
    val index: Int = 0,
    val focus: BrowserFocus = BrowserFocus.LIST,
    /** The first row on screen when the editor opened, so Back lands on the same scroll. */
    val firstVisible: Int = 0,
) {
    val system: BrowserSystem? get() = systems.getOrNull(systemIndex)
    val focused: BrowserRow? get() = rows.getOrNull(index)
    val ids: List<Long> get() = rows.map { it.gameId }

    companion object {
        /** The art kinds a row shows a badge for, in order, with their short labels. */
        val BADGES: List<Pair<ArtKind, String>> = listOf(
            ArtKind.BOX_2D to "BOX", ArtKind.BOX_3D to "3D", ArtKind.GRID to "CAP",
            ArtKind.HERO to "HERO", ArtKind.WHEEL to "LOGO", ArtKind.SCREENSHOT to "SHOT",
        )
    }
}

object LibraryBrowser {

    /** All, then every system with games, in registry order. */
    fun systems(games: List<GameEntity>, platforms: List<Platform>): List<BrowserSystem> {
        val counts = games.groupingBy { it.platformId }.eachCount()
        return listOf(BrowserSystem(null, "All", "ALL", games.size)) +
            platforms.filter { (counts[it.id] ?: 0) > 0 }.map { BrowserSystem(it.id, it.name, it.shortName, counts.getValue(it.id)) }
    }

    fun rows(
        games: List<GameEntity>,
        metadata: Map<Long, GameMetadataEntity>,
        platformId: String?,
        filter: MediaFilter,
        shortName: (String) -> String,
    ): List<BrowserRow> = games.asSequence()
        .filter { platformId == null || it.platformId == platformId }
        .filter { filter.keeps(metadata[it.id]) }
        .sortedWith(compareBy({ it.title.lowercase() }, { it.id }))
        .map { g ->
            val m = metadata[g.id]
            BrowserRow(g.id, g.title, shortName(g.platformId), m?.mediaStatus ?: 0, ArtKind.parsePins(m?.pinnedArt))
        }
        .toList()

    /**
     * The browser on fresh data. [previous] keeps its system (by id), filter, and the cursor on
     * the same game when it is still listed (else the same position).
     */
    fun build(
        games: List<GameEntity>,
        metadata: Map<Long, GameMetadataEntity>,
        platforms: List<Platform>,
        previous: LibraryBrowserState?,
        startOn: String? = null,
    ): LibraryBrowserState {
        val systems = systems(games, platforms)
        val wanted = if (previous != null) previous.system?.id else startOn
        val sys = systems.indexOfFirst { it.id == wanted }.coerceAtLeast(0)
        val filter = previous?.filter ?: MediaFilter.ALL
        val short = platforms.associate { it.id to it.shortName }
        val rows = rows(games, metadata, systems[sys].id, filter) { short[it] ?: it.uppercase() }
        val keep = previous?.focused?.gameId?.let { id -> rows.indexOfFirst { it.gameId == id } }?.takeIf { it >= 0 }
        val index = (keep ?: previous?.index ?: 0).coerceIn(0, (rows.size - 1).coerceAtLeast(0))
        return LibraryBrowserState(
            systems = systems,
            systemIndex = sys,
            filter = filter,
            rows = rows,
            index = index,
            focus = previous?.focus ?: BrowserFocus.LIST,
            firstVisible = previous?.firstVisible ?: 0,
        )
    }

    /** Moves along the console strip, wrapping; the list starts at its top. */
    fun moveSystem(s: LibraryBrowserState, delta: Int, rebuild: (LibraryBrowserState) -> LibraryBrowserState): LibraryBrowserState {
        if (s.systems.size < 2) return s
        val next = (s.systemIndex + delta).mod(s.systems.size)
        return rebuild(s.copy(systemIndex = next, rows = emptyList(), index = 0, firstVisible = 0))
    }

    fun moveFilter(s: LibraryBrowserState, delta: Int, rebuild: (LibraryBrowserState) -> LibraryBrowserState): LibraryBrowserState {
        val all = MediaFilter.BROWSER
        val i = (all.indexOf(s.filter).coerceAtLeast(0) + delta).coerceIn(0, all.lastIndex)
        if (all[i] == s.filter) return s
        return rebuild(s.copy(filter = all[i], rows = emptyList(), index = 0, firstVisible = 0))
    }

    /** Up/Down: in the list; Up from its top goes to the filter row, Down from there comes back. */
    fun moveCursor(s: LibraryBrowserState, delta: Int): LibraryBrowserState = when {
        s.focus == BrowserFocus.FILTERS -> if (delta > 0 && s.rows.isNotEmpty()) s.copy(focus = BrowserFocus.LIST, index = 0) else s
        delta < 0 && s.index == 0 -> s.copy(focus = BrowserFocus.FILTERS)
        else -> s.copy(index = (s.index + delta).coerceIn(0, (s.rows.size - 1).coerceAtLeast(0)))
    }

    /** "12 games · 5 missing box art", for the header. */
    fun summary(s: LibraryBrowserState): String {
        val n = s.rows.size
        val games = if (n == 1) "1 game" else "$n games"
        return if (s.filter == MediaFilter.ALL) games else "$games · ${s.filter.label.lowercase()}"
    }
}
