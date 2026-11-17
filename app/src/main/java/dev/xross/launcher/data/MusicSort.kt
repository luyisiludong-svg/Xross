package dev.xross.launcher.data

/** How Music ▸ All songs is ordered (its Options ▸ Sort songs). */
enum class SongSort(val label: String) {
    TITLE("Title (A–Z)"),
    TITLE_DESC("Title (Z–A)"),
    ARTIST("Artist"),
    ALBUM("Album"),
    RECENT("Recently added"),
    ;

    companion object {
        fun parse(raw: String?) = entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: TITLE
    }
}

/** How Music ▸ Albums is ordered. */
enum class AlbumSort(val label: String) {
    TITLE("Title (A–Z)"),
    TITLE_DESC("Title (Z–A)"),
    ARTIST("Artist"),
    RECENT("Recently added"),
    ;

    companion object {
        fun parse(raw: String?) = entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: TITLE
    }
}
