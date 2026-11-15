package dev.xross.launcher.state

import androidx.compose.runtime.Immutable
import dev.xross.launcher.theme.pack.BadgePill
import java.text.NumberFormat

/** One pill beside the focused game's title (2b.7b `badgePills`). */
@Immutable
data class TitlePill(val kind: BadgePill, val text: String) {
    /** Figures (year, playtime, achievements) are set in the pack's `mono` font. */
    val mono: Boolean get() = kind == BadgePill.YEAR || kind == BadgePill.PLAYTIME || kind == BadgePill.RA_PROGRESS
}

/**
 * The pills of `components.badgePills` (2b.7b): built with the game's row ([of]) in the pack's
 * order, each skipped when its data is missing, and `RA_PROGRESS` filled from the focused game's
 * RetroAchievements numbers when drawn ([shown]). Games only; apps and media rows have none.
 */
object BadgePills {
    /** `DEVELOPER` is cut at this many characters, the last an ellipsis. */
    const val DEVELOPER_MAX = 18

    private val YEAR = Regex("""(?<!\d)(19[5-9]\d|20\d\d)(?!\d)""")

    /** The year of a full or partial date ("1999-03-11", "1999-03", "03/1999", "1999"); null without one. */
    fun year(releaseDate: String?): String? = releaseDate?.let { YEAR.find(it)?.value }

    /** [name] trimmed and cut at [DEVELOPER_MAX] characters; null when blank. */
    fun developer(name: String?): String? {
        val t = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if (t.length <= DEVELOPER_MAX) t else t.take(DEVELOPER_MAX - 1).trimEnd() + "…"
    }

    /** "12 min", "14h 25m"; null before the first minute is tracked. */
    fun playtime(ms: Long): String? {
        if (ms <= 0) return null
        val minutes = (ms / 60_000).coerceAtLeast(1)
        return if (minutes < 60) "$minutes min" else "${minutes / 60}h ${minutes % 60}m"
    }

    /**
     * The pills of [order] for one game, in that order, skipping those without data: [system]
     * (the platform's short tag), [releaseDate]'s year, [developer], [playTimeMs]. `RA_PROGRESS`
     * is kept as a place-holder ([shown] fills or drops it).
     */
    fun of(order: List<BadgePill>, system: String?, releaseDate: String?, developer: String?, playTimeMs: Long): List<TitlePill> =
        order.mapNotNull { kind ->
            when (kind) {
                BadgePill.SYSTEM -> system?.trim()?.takeIf { it.isNotEmpty() }
                BadgePill.YEAR -> year(releaseDate)
                BadgePill.DEVELOPER -> developer(developer)
                BadgePill.PLAYTIME -> playtime(playTimeMs)
                BadgePill.RA_PROGRESS -> ""
            }?.let { TitlePill(kind, it) }
        }

    /** [pills] as drawn on the focused game: `RA_PROGRESS` as "🏆 18/42" with [telemetry], dropped without. */
    fun shown(pills: List<TitlePill>, telemetry: GameTelemetry?): List<TitlePill> =
        pills.mapNotNull { p ->
            if (p.kind != BadgePill.RA_PROGRESS) p
            else telemetry?.let { t ->
                val n = NumberFormat.getIntegerInstance()
                p.copy(text = "🏆 ${n.format(t.unlocked)}/${n.format(t.total)}")
            }
        }
}
