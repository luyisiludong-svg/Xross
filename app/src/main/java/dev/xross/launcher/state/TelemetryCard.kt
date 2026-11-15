package dev.xross.launcher.state

import androidx.compose.runtime.Immutable
import dev.xross.launcher.profile.Friend
import dev.xross.launcher.profile.FriendsState
import dev.xross.launcher.profile.RaGameProgress
import dev.xross.launcher.theme.pack.TelemetryMode
import java.text.NumberFormat
import java.util.Locale

/** One cell of the rich card's badge row: an achievement's badge, greyed while [unlocked] is false. */
@Immutable
data class TelemetrySlot(val badgeName: String, val title: String, val unlocked: Boolean)

/**
 * The rich card's `RECENTLY PLAYED` strip: up to [TelemetryCard.STRIP_AVATARS] followed users whose
 * latest game is the focused one, most recently active first, then "+[more]".
 */
@Immutable
data class FriendStrip(val friends: List<Friend>, val more: Int) {
    val moreText: String get() = if (more > 0) "+$more" else ""
}

/**
 * Pure rules of the focused game's telemetry (2b.7d `telemetryMode`): what the rich card shows
 * and when its friend strip is there. Nothing here fetches: the card reads the telemetry the
 * focus already loads (as the compact pill always did) and [FriendsState] as it stands.
 */
object TelemetryCard {
    /** Badge cells in the rich card. */
    const val SLOTS = 6

    /** Friend avatars in the strip before "+N". */
    const val STRIP_AVATARS = 3

    /**
     * The badge row for [progress]: the [SLOTS] newest unlocks, newest first; when fewer are
     * unlocked, the next locked ones in the game's order fill the row (drawn greyed). A set with
     * fewer than [SLOTS] achievements has that many cells.
     */
    fun slots(progress: RaGameProgress): List<TelemetrySlot> {
        val (open, locked) = progress.achievements.partition { it.unlocked }
        val newest = open.sortedByDescending { it.unlockDate }.take(SLOTS)
        val rest = locked.sortedBy { it.displayOrder }.take(SLOTS - newest.size)
        return newest.map { TelemetrySlot(it.badgeName, it.title, unlocked = true) } +
            rest.map { TelemetrySlot(it.badgeName, it.title, unlocked = false) }
    }

    /** "18/42 · 120/400 pts"; a set worth no points leaves the points out ("3/5"). */
    fun progressText(t: GameTelemetry, locale: Locale = Locale.getDefault()): String {
        val n = NumberFormat.getIntegerInstance(locale)
        val count = "${n.format(t.unlocked)}/${n.format(t.total)}"
        return if (t.totalPoints <= 0) count else "$count · ${n.format(t.earnedPoints)}/${n.format(t.totalPoints)} pts"
    }

    /** 0–1 share unlocked. */
    fun completion(t: GameTelemetry): Float = if (t.total <= 0) 0f else (t.unlocked.toFloat() / t.total).coerceIn(0f, 1f)

    /**
     * Who of the followed users last played [t]'s game, matched by RetroAchievements game id; null
     * (no strip) when nobody did, when the game's id is unknown, when friends aren't known
     * (unlinked) or offline (the last friend refresh failed, or this game's own fetch did).
     * [FriendsState.Loading] keeps the list it had, so a refresh doesn't blink the strip.
     */
    fun friendStrip(t: GameTelemetry, friends: FriendsState): FriendStrip? {
        if (t.raGameId <= 0 || t.offline) return null
        if (friends !is FriendsState.Ready && friends !is FriendsState.Loading) return null
        val playing = friends.friends
            .filter { it.activity?.gameId == t.raGameId }
            .sortedByDescending { it.activity?.lastActive.orEmpty() }
        if (playing.isEmpty()) return null
        return FriendStrip(playing.take(STRIP_AVATARS), (playing.size - STRIP_AVATARS).coerceAtLeast(0))
    }

    /** The card or pill drawn for [mode], or nothing ([TelemetryMode.OFF], or no [telemetry]). */
    fun shows(mode: TelemetryMode, telemetry: GameTelemetry?): Boolean = mode != TelemetryMode.OFF && telemetry != null
}
