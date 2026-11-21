package dev.xross.launcher.state

import androidx.compose.runtime.Immutable
import dev.xross.launcher.data.GameEntity
import dev.xross.launcher.data.GameMetadataEntity
import dev.xross.launcher.input.NavAction
import dev.xross.launcher.model.ArtKind
import dev.xross.launcher.model.Platform
import dev.xross.launcher.scrape.MediaOffer
import dev.xross.launcher.scrape.MediaOffers

/** What a text or match row of the Metadata & Media editor does. */
enum class EditorAction { EDIT_TITLE, EDIT_DESCRIPTION, FIND_MATCH, SCRAPE_AGAIN, RESET_METADATA, RESET_MEDIA }

/** Where a media slot's file came from. */
enum class MediaSlotStatus(val label: String) {
    /** Picked by hand; scraping leaves it alone. */
    CHOSEN("Chosen"),
    SCRAPED("Scraped"),
    /** A file found in the media folders that no scrape put there (ES-DE media, say). */
    FROM_FOLDER("From your folder"),
    /** Removed by hand; scraping leaves the slot empty. */
    REMOVED("Removed"),
    NONE("None"),
}

/** One row of the Metadata & Media editor. [key] survives paging, so the cursor stays on the same slot. */
@Immutable
sealed interface EditorRow {
    val key: String
    val label: String
    val detail: String?

    data class Action(val action: EditorAction, override val label: String, override val detail: String?) : EditorRow {
        override val key: String get() = action.name
    }

    data class Media(val kind: ArtKind, override val label: String, val status: MediaSlotStatus) : EditorRow {
        override val key: String get() = kind.name
        override val detail: String get() = status.label
        val hasFile: Boolean get() = status == MediaSlotStatus.CHOSEN || status == MediaSlotStatus.SCRAPED || status == MediaSlotStatus.FROM_FOLDER
    }
}

/**
 * The Metadata & Media editor for one game (Options ▸ Metadata & Media): text and match rows, one
 * row per media slot, and the open [picker]. [ids] is the list the editor was opened from; L1/R1
 * page through it and the cursor stays on the same row.
 */
@Immutable
data class MetadataEditorState(
    val gameId: Long,
    val uuid: String,
    val title: String,
    val platform: String?,
    /** "Verified · scraped", "Set manually"… the same words as the game's Options menu. */
    val matchStatus: String,
    val rows: List<EditorRow>,
    val index: Int = 0,
    val ids: List<Long> = emptyList(),
    /** Changes when art is stored, so previews reload. */
    val artVersion: Long = 0,
    /** How this page was reached: -1 from the next game, +1 from the previous one, 0 when opened. */
    val pageDirection: Int = 0,
    val picker: MediaPickerState? = null,
) {
    val focused: EditorRow? get() = rows.getOrNull(index)
    val position: Int get() = DetailsPager.position(ids, gameId).first
    val count: Int get() = DetailsPager.position(ids, gameId).second

    fun move(delta: Int): MetadataEditorState =
        if (rows.isEmpty()) this else copy(index = (index + delta).coerceIn(0, rows.lastIndex))

    fun moveTo(i: Int): MetadataEditorState = move(i - index)
}

/** Builds and pages [MetadataEditorState]. Pure. */
object MetadataEditor {

    /** The media rows, top to bottom, with the words the user sees. */
    val SLOTS: List<Pair<ArtKind, String>> = listOf(
        ArtKind.BOX_2D to "Box art",
        ArtKind.BOX_3D to "3D box",
        ArtKind.GRID to "Capsule",
        ArtKind.HERO to "Hero background",
        ArtKind.WHEEL to "Logo",
        ArtKind.SCREENSHOT to "Screenshot",
        ArtKind.VIDEO to "Video snap",
    )

    fun slotLabel(kind: ArtKind): String = SLOTS.first { it.first == kind }.second

    /** How a game was identified, as its Options menu and the editor's header put it. */
    fun matchStatus(meta: GameMetadataEntity?): String = when {
        meta?.locked == true -> "Set manually"
        meta?.matchKind != null && meta.scrapeStatus == "OK" -> "Verified · scraped"
        meta?.matchKind != null -> "Verified by DAT"
        meta?.scrapeStatus == "OK" -> "Scraped"
        meta?.scrapeStatus == "NOT_FOUND" -> "Not found online"
        else -> "Not scraped yet"
    }

    fun slotStatus(kind: ArtKind, meta: GameMetadataEntity?): MediaSlotStatus {
        val present = (meta?.mediaStatus ?: 0) and kind.bit != 0
        val pinned = kind in ArtKind.parsePins(meta?.pinnedArt)
        return when {
            pinned && present -> MediaSlotStatus.CHOSEN
            pinned -> MediaSlotStatus.REMOVED
            present && meta?.scrapeStatus == "OK" -> MediaSlotStatus.SCRAPED
            present -> MediaSlotStatus.FROM_FOLDER
            else -> MediaSlotStatus.NONE
        }
    }

    /**
     * The editor on [game]. [previous] is the state it replaces (the same game refreshed, or the
     * neighbour it was paged from): the cursor stays on the row with the same key, and the open
     * picker survives a refresh of the same game.
     */
    fun build(
        game: GameEntity,
        platform: Platform?,
        meta: GameMetadataEntity?,
        artVersion: Long,
        ids: List<Long>,
        /** Settings ▸ Scrape videos: the Video snap row shows only then. */
        videos: Boolean,
        previous: MetadataEditorState? = null,
        pageDirection: Int = 0,
    ): MetadataEditorState {
        val pins = ArtKind.parsePins(meta?.pinnedArt)
        val rows = buildList<EditorRow> {
            add(EditorRow.Action(EditorAction.EDIT_TITLE, "Edit title…", meta?.titleOverride ?: "Use your own name for this game"))
            add(
                EditorRow.Action(
                    EditorAction.EDIT_DESCRIPTION, "Edit description…",
                    when {
                        meta?.synopsisOverride != null -> "Your own description"
                        meta?.synopsis != null -> "Scraped"
                        else -> "None yet"
                    },
                ),
            )
            add(EditorRow.Action(EditorAction.FIND_MATCH, "Find match…", "Search by name when the automatic match is wrong"))
            add(EditorRow.Action(EditorAction.SCRAPE_AGAIN, "Scrape again", "Refresh text and art; chosen media stays"))
            if (meta?.locked == true || meta?.titleOverride != null || meta?.synopsisOverride != null) {
                add(EditorRow.Action(EditorAction.RESET_METADATA, "Reset metadata", "Forget your title, description and match"))
            }
            for ((kind, label) in SLOTS) {
                if (kind.isVideo && !videos) continue
                add(EditorRow.Media(kind, label, slotStatus(kind, meta)))
            }
            if (pins.isNotEmpty()) {
                add(EditorRow.Action(EditorAction.RESET_MEDIA, "Reset media", "Let scraping choose every slot again"))
            }
        }
        val key = previous?.focused?.key
        val index = rows.indexOfFirst { it.key == key }.takeIf { it >= 0 }
            ?: (previous?.index ?: 0).coerceIn(0, rows.lastIndex)
        return MetadataEditorState(
            gameId = game.id,
            uuid = game.uuid,
            title = game.title,
            platform = platform?.name,
            matchStatus = matchStatus(meta),
            rows = rows,
            index = index,
            ids = ids,
            artVersion = artVersion,
            pageDirection = if (previous?.gameId == game.id) previous.pageDirection else pageDirection,
            picker = previous?.picker?.takeIf { previous.gameId == game.id }
                ?.let { p -> p.copy(hasFile = rows.any { it is EditorRow.Media && it.kind == p.kind && it.hasFile }) },
        )
    }

    /** The game [delta] steps along the list the editor was opened from; null at either end. */
    fun neighbor(state: MetadataEditorState, delta: Int): Long? = DetailsPager.neighbor(state.ids, state.gameId, delta)
}

/** What Confirm does on the focused cell of the media picker. */
sealed interface PickerChoice {
    data class Offer(val offer: MediaOffer) : PickerChoice
    data object UseFile : PickerChoice
    data object Remove : PickerChoice
}

/**
 * The media picker for one slot: a grid of the services' offers, [COLUMNS] to a row, then the
 * rows "Use a file…" and (when the slot has a file) "Remove". [index] runs over the offers first,
 * then those rows.
 */
@Immutable
data class MediaPickerState(
    val kind: ArtKind,
    val title: String,
    val loading: Boolean = true,
    val offers: List<MediaOffer> = emptyList(),
    /** Why the grid is empty ("No SteamGridDB key…"); null while loading or when there are offers. */
    val reason: String? = null,
    val index: Int = 0,
    /** The slot has a file, so it can be removed. */
    val hasFile: Boolean = false,
    /** A pick is downloading ("Downloading…"); input waits. */
    val busy: String? = null,
) {
    val extras: List<PickerChoice> get() = if (hasFile) listOf(PickerChoice.UseFile, PickerChoice.Remove) else listOf(PickerChoice.UseFile)
    val count: Int get() = offers.size + extras.size
    val focused: PickerChoice? get() = offers.getOrNull(index)?.let(PickerChoice::Offer) ?: extras.getOrNull(index - offers.size)

    fun withOffers(result: MediaOffers): MediaPickerState = when (result) {
        is MediaOffers.Ready -> copy(loading = false, offers = result.offers, reason = null, index = 0)
        // Nothing to browse: the cursor waits on "Use a file…".
        is MediaOffers.Empty -> copy(loading = false, offers = emptyList(), reason = result.reason, index = 0)
    }

    fun moveTo(i: Int): MediaPickerState = if (i in 0 until count) copy(index = i) else this

    /** The cursor after a D-pad [action]; the same state when the move isn't possible. */
    fun move(action: NavAction): MediaPickerState {
        val n = offers.size
        val inGrid = index < n
        val next = when (action) {
            NavAction.LEFT -> if (inGrid && index % COLUMNS > 0) index - 1 else index
            NavAction.RIGHT -> if (inGrid && index % COLUMNS < COLUMNS - 1 && index + 1 < n) index + 1 else index
            NavAction.UP -> when {
                inGrid -> if (index >= COLUMNS) index - COLUMNS else index
                index == n && n > 0 -> ((n - 1) / COLUMNS) * COLUMNS // the last grid row, its first cell
                else -> index - 1
            }
            NavAction.DOWN -> when {
                inGrid && index + COLUMNS < n -> index + COLUMNS
                // A short last row below: land on its last cell; from the last row, on "Use a file…".
                inGrid && index / COLUMNS < (n - 1) / COLUMNS -> n - 1
                inGrid -> n
                else -> index + 1
            }
            else -> index
        }
        return copy(index = next.coerceIn(0, (count - 1).coerceAtLeast(0)))
    }

    companion object {
        /** Thumbnails per row. */
        const val COLUMNS = 4
    }
}
