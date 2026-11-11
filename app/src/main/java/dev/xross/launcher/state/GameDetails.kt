package dev.xross.launcher.state

import androidx.compose.runtime.Immutable
import dev.xross.launcher.data.GameEntity
import dev.xross.launcher.data.GameMetadataEntity
import dev.xross.launcher.storage.Platform
import java.util.Locale

/**
 * The Game Details screen: art, description and release facts for one game. Built from rows
 * already in the database; art is read from the asset cache by [uuid] when drawn.
 */
@Immutable
data class GameDetailsState(
    val gameId: Long,
    val uuid: String,
    val title: String,
    val platform: String?,
    val synopsis: String?,
    /** "Released 1995 · Square · Square · RPG · 1 player", any parts known. */
    val facts: List<Pair<String, String>>,
    /** 0–1, shown as five stars. */
    val rating: Float?,
    val playtime: String,
    /** Changes when new art is stored, so the screen reloads it. */
    val artVersion: Long,
    /** A metadata service has identified this game (so a missing description is the service's). */
    val scraped: Boolean = false,
    /** Synopsis scroll step, moved by up/down. */
    val scroll: Int = 0,
    /** RetroAchievements progress; null while loading or for systems without achievements. */
    val achievements: dev.xross.launcher.profile.AchievementsState? = null,
    /** The system has RetroAchievements (the summary row is shown). */
    val achievementsSupported: Boolean = false,
) {
    fun scrollBy(delta: Int): GameDetailsState = copy(scroll = (scroll + delta).coerceIn(0, MAX_SCROLL))

    companion object {
        const val MAX_SCROLL = 40

        fun build(game: GameEntity, platform: Platform?, meta: GameMetadataEntity?, artVersion: Long): GameDetailsState {
            val facts = buildList {
                meta?.releaseDate?.let { add("Released" to formatDate(it)) }
                meta?.developer?.let { add("Developer" to it) }
                meta?.publisher?.takeIf { it != meta.developer }?.let { add("Publisher" to it) }
                meta?.genre?.let { add("Genre" to it) }
                meta?.players?.let { add("Players" to it) }
                meta?.region?.let { add("Region" to it) }
            }
            val played = MenuTree.formatPlaytime(game.playTimeMs)
            return GameDetailsState(
                gameId = game.id,
                uuid = game.uuid,
                title = game.title,
                platform = platform?.name,
                // Descriptions scraped before entity decoding still hold "&quot;" and friends.
                synopsis = meta?.synopsis?.trim()?.ifEmpty { null }?.let(dev.xross.launcher.scrape.HtmlText::decode),
                facts = facts,
                rating = meta?.rating,
                playtime = when {
                    played != null -> "Played $played" + if (game.playCount > 1) " · ${game.playCount} sessions" else ""
                    game.playCount > 0 -> "Played under a minute"
                    else -> "Not played yet"
                },
                artVersion = artVersion,
                scraped = meta?.scrapeStatus == "OK",
                achievementsSupported = dev.xross.launcher.profile.RaConsoles.of(game.platformId) != null,
            )
        }

        /** "1995-03-11" → "11 Mar 1995"-style localized medium date; years and odd values pass through. */
        internal fun formatDate(raw: String, locale: Locale = Locale.getDefault()): String {
            val date = runCatching { java.time.LocalDate.parse(raw.take(10)) }.getOrNull() ?: return raw
            return date.format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM).withLocale(locale))
        }
    }
}

/** What a text prompt is for; the answer comes back to [XmbViewModel.onTextEntered]. */
sealed interface TextPrompt {
    val title: String
    val initial: String
    val secret: Boolean get() = false
    val hint: String? get() = null

    data class ScreenScraperUser(override val initial: String) : TextPrompt {
        override val title = "ScreenScraper user name"
        override val hint = "Leave empty to sign out"
    }

    data class ScreenScraperPassword(val user: String) : TextPrompt {
        override val title = "ScreenScraper password"
        override val initial = ""
        override val secret = true
    }

    data object SteamGridDbKey : TextPrompt {
        override val title = "SteamGridDB API key"
        override val initial = ""
        override val secret = true
        override val hint = "From steamgriddb.com ▸ Preferences ▸ API. Empty removes it."
    }

    data class GameTitle(val gameId: Long, override val initial: String) : TextPrompt {
        override val title = "Title"
        override val hint = "Empty restores the automatic title"
    }

    data class MatchQuery(val gameId: Long, val platformId: String, override val initial: String) : TextPrompt {
        override val title = "Find a match"
        override val hint = "Game name"
    }
}
