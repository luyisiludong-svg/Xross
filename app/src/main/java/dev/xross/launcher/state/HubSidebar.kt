package dev.xross.launcher.state

import androidx.compose.runtime.Immutable
import dev.xross.launcher.data.GameEntity
import dev.xross.launcher.profile.FriendsState
import dev.xross.launcher.profile.RaConsoles
import dev.xross.launcher.profile.RaFriendActivity
import dev.xross.launcher.profile.RaMatcher
import java.io.File

/** One row of the hub's `FOLLOWING` sidebar: a followed user, or the linked user ([self]) in their place. */
@Immutable
data class SidebarRow(
    val user: String,
    /** Hardcore points, which the list is sorted by. */
    val points: Long,
    val avatar: File? = null,
    val avatarVersion: Int = 0,
    /** Their latest game; null for [self] and for friends whose activity wasn't fetched. */
    val activity: RaFriendActivity? = null,
    val self: Boolean = false,
)

/**
 * Pure rules of the `FOLLOWING` sidebar in Games ▸ RetroAchievements (2b.7e): who is listed, in
 * what order, when it collapses, and which library game a friend's latest game is.
 */
object HubSidebar {
    /**
     * The sidebar draws (and takes its width from the dashboard) only when there are followed
     * users to list: unlinked, offline without a cache and following nobody collapse it.
     */
    fun shows(friends: FriendsState): Boolean = friends !is FriendsState.Unlinked && friends.friends.isNotEmpty()

    /**
     * Followed users by points (most first, then by name), with the linked user's own row ([self],
     * null before the profile was fetched) placed among them by its points. Empty when [shows] is false.
     */
    fun rows(friends: FriendsState, self: SidebarRow?): List<SidebarRow> {
        if (!shows(friends)) return emptyList()
        val others = friends.friends.map { SidebarRow(it.user, it.points, it.avatar, it.avatarVersion, it.activity) }
        return (others + listOfNotNull(self?.copy(self = true)))
            .sortedWith(compareByDescending<SidebarRow> { it.points }.thenBy { !it.self }.thenBy { it.user.lowercase() })
    }

    /** Index of the linked user's row, or -1. */
    fun selfIndex(rows: List<SidebarRow>): Int = rows.indexOfFirst { it.self }

    /**
     * The library game behind [activity]'s latest game: the game matched to that RetroAchievements
     * set ([byRaId], from the cached matches), else a game on a supported system with the same
     * title (RetroAchievements' title rules, [RaMatcher.normalize]) that isn't matched to another
     * set; the most recently played one when several fit. Null when the library doesn't have it.
     */
    fun libraryGame(activity: RaFriendActivity?, byRaId: Map<Int, Long>, games: List<GameEntity>): GameEntity? {
        val a = activity?.takeIf { it.gameId > 0 } ?: return null
        byRaId[a.gameId]?.let { id -> games.firstOrNull { it.id == id }?.let { return it } }
        val key = RaMatcher.normalize(a.gameTitle).ifEmpty { return null }
        val matched = byRaId.values.toHashSet()
        return games.filter { RaConsoles.of(it.platformId) != null && it.id !in matched && RaMatcher.normalize(it.title) == key }
            .maxByOrNull { it.lastPlayed ?: 0L }
    }
}
