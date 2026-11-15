package dev.xross.launcher

import dev.xross.launcher.profile.AchievementsState
import dev.xross.launcher.profile.Friend
import dev.xross.launcher.profile.FriendsState
import dev.xross.launcher.profile.RaAchievement
import dev.xross.launcher.profile.RaFriendActivity
import dev.xross.launcher.profile.RaGameProgress
import dev.xross.launcher.profile.RaMatchKind
import dev.xross.launcher.state.AccountsSetup
import dev.xross.launcher.state.GameTelemetry
import dev.xross.launcher.state.MenuSnapshot
import dev.xross.launcher.state.MenuTree
import dev.xross.launcher.state.TelemetryCard
import dev.xross.launcher.theme.pack.ComponentOverrides
import dev.xross.launcher.theme.pack.ComponentRow
import dev.xross.launcher.theme.pack.ResolvedComponents
import dev.xross.launcher.theme.pack.TelemetryMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Phase 2b.7d: `telemetryMode` — the rich card's content from progress, its friend strip, and
 * how it collapses (unlinked, offline, no progress, no friends); the Achievement badge row.
 */
class TelemetryCardTest {
    private fun achievement(id: Int, unlockedAt: String? = null, points: Int = 5, order: Int = id) =
        RaAchievement(id, "Ach $id", points = points, badgeName = "b$id", displayOrder = order, unlockedAt = unlockedAt)

    private fun progress(vararg a: RaAchievement, gameId: Int = 228) =
        RaGameProgress(gameId, "Game", consoleName = "Super Nintendo", achievements = a.toList())

    private fun ready(p: RaGameProgress, offline: Boolean = false) = AchievementsState.Ready(p, RaMatchKind.HASH, offline = offline)

    private fun telemetry(p: RaGameProgress, offline: Boolean = false, peeked: Boolean = false) =
        GameTelemetry.of(7L, ready(p, offline), platform = "SNES", peeked = peeked, uuid = "u-7")!!

    private fun friend(name: String, game: Int, at: String) =
        Friend(name, name.lowercase(), activity = RaFriendActivity(name, gameId = game, lastPlayed = at))

    // region Card content

    @Test fun fewerThanSixUnlocksFillTheRowWithLockedOnesInGameOrder() {
        val p = progress(
            achievement(1, "2026-01-01 10:00:00"),
            achievement(2),
            achievement(3, "2026-03-01 10:00:00"),
            achievement(4, order = 9),
            achievement(5, order = 1),
            achievement(6),
            achievement(7),
            achievement(8),
        )
        val t = telemetry(p)
        assertEquals("newest unlock first, then locked by display order", listOf("b3", "b1", "b5", "b2", "b6", "b7"), t.slots.map { it.badgeName })
        assertEquals(listOf(true, true, false, false, false, false), t.slots.map { it.unlocked })
        assertEquals(TelemetryCard.SLOTS, t.slots.size)
        assertEquals("SNES", t.platform)
        assertEquals(228, t.raGameId)
        assertEquals("u-7", t.uuid)
        assertEquals("2/8 · 10/40 pts", TelemetryCard.progressText(t, Locale.US))
        assertEquals(0.25f, TelemetryCard.completion(t), 0.0001f)
    }

    @Test fun manyUnlocksShowTheSixNewest() {
        val all = (1..10).map { achievement(it, "2026-01-%02d 10:00:00".format(it)) }
        val t = telemetry(progress(*all.toTypedArray()))
        assertEquals(listOf("b10", "b9", "b8", "b7", "b6", "b5"), t.slots.map { it.badgeName })
        assertTrue(t.slots.all { it.unlocked })
    }

    @Test fun masteredSmallSetAndZeroPoints() {
        val t = telemetry(progress(achievement(1, "2026-01-01 10:00:00", points = 0), achievement(2, "2026-01-02 10:00:00", points = 0)))
        assertTrue(t.mastered)
        assertEquals("a set of two has two cells", 2, t.slots.size)
        assertEquals("no points: the count alone", "2/2", TelemetryCard.progressText(t, Locale.US))
        assertEquals(1f, TelemetryCard.completion(t), 0f)
        val big = telemetry(progress(*(1..1200).map { achievement(it, points = 10) }.toTypedArray()))
        assertEquals("0/1,200 · 0/12,000 pts", TelemetryCard.progressText(big, Locale.US))
        assertFalse(big.mastered)
    }

    @Test fun platformFallsBackToTheConsoleName() {
        val t = GameTelemetry.of(7L, ready(progress(achievement(1))), platform = "")!!
        assertEquals("Super Nintendo", t.platform)
    }

    // endregion

    // region Friend strip

    @Test fun stripMatchesByRetroAchievementsGameIdNewestFirstThenPlusN() {
        val t = telemetry(progress(achievement(1)))
        val friends = FriendsState.Ready(
            listOf(
                friend("Old", 228, "2026-10-01 10:00:00"),
                friend("Other", 999, "2026-11-14 10:00:00"),
                friend("New", 228, "2026-11-13 10:00:00"),
                friend("Mid", 228, "2026-11-01 10:00:00"),
                friend("Older", 228, "2026-09-01 10:00:00"),
                friend("Oldest", 228, "2026-08-01 10:00:00"),
                Friend("NoActivity", "noactivity"),
            ),
        )
        val strip = TelemetryCard.friendStrip(t, friends)!!
        assertEquals(listOf("New", "Mid", "Old"), strip.friends.map { it.user })
        assertEquals(2, strip.more)
        assertEquals("+2", strip.moreText)

        val three = TelemetryCard.friendStrip(t, FriendsState.Ready(friends.friends.take(3)))!!
        assertEquals(listOf("New", "Old"), three.friends.map { it.user })
        assertEquals(0, three.more)
        assertEquals("", three.moreText)
        assertNotNull("a refresh keeps the strip it had", TelemetryCard.friendStrip(t, FriendsState.Loading(friends.friends)))
    }

    @Test fun noFriendOnThisGameOrUnknownGameIdMeansNoStrip() {
        val t = telemetry(progress(achievement(1)))
        assertNull(TelemetryCard.friendStrip(t, FriendsState.Ready(listOf(friend("Other", 999, "2026-11-14 10:00:00")))))
        assertNull("following nobody", TelemetryCard.friendStrip(t, FriendsState.Ready()))
        assertNull(TelemetryCard.friendStrip(t.copy(raGameId = 0), FriendsState.Ready(listOf(friend("Zero", 0, "2026-11-14 10:00:00")))))
    }

    // endregion

    // region Collapse (A.13)

    @Test fun unlinkedOrNoProgressDrawsNothing() {
        assertNull(GameTelemetry.of(7L, AchievementsState.NotLinked))
        assertNull(GameTelemetry.of(7L, AchievementsState.NoMatch))
        assertNull(GameTelemetry.of(7L, AchievementsState.Unsupported("ps2")))
        assertNull(GameTelemetry.of(7L, AchievementsState.Failed("offline")))
        assertNull(GameTelemetry.of(7L, null))
        assertNull("a set without achievements", GameTelemetry.of(7L, ready(progress())))
        for (mode in TelemetryMode.entries) assertFalse(mode.name, TelemetryCard.shows(mode, null))
        val t = telemetry(progress(achievement(1)))
        assertFalse(TelemetryCard.shows(TelemetryMode.OFF, t))
        assertTrue(TelemetryCard.shows(TelemetryMode.COMPACT_PILL, t))
        assertTrue(TelemetryCard.shows(TelemetryMode.RICH_CARD, t))
        val someone = listOf(friend("A", 228, "2026-11-14 10:00:00"))
        assertNull("unlinked: no strip", TelemetryCard.friendStrip(t, FriendsState.Unlinked))
        assertNotNull(TelemetryCard.friendStrip(t, FriendsState.Ready(someone)))
    }

    @Test fun offlineDrawsTheCardFromCachedProgressWithoutTheStrip() {
        val p = progress(achievement(1, "2026-01-01 10:00:00"), achievement(2))
        val someone = listOf(friend("A", 228, "2026-11-14 10:00:00"))
        // The friend refresh failed: the saved list doesn't make a strip.
        assertNull(TelemetryCard.friendStrip(telemetry(p), FriendsState.Offline(someone)))
        // This game's fetch failed: the card comes from the saved copy, without the strip.
        val offline = telemetry(p, offline = true)
        assertTrue(offline.offline)
        assertEquals("1/2 · 5/10 pts", TelemetryCard.progressText(offline, Locale.US))
        assertNull(TelemetryCard.friendStrip(offline, FriendsState.Ready(someone)))
        // The instant copy read before the fetch isn't "offline" yet: no strip blinking in and out.
        val peeked = telemetry(p, offline = true, peeked = true)
        assertFalse(peeked.offline)
        assertNotNull(TelemetryCard.friendStrip(peeked, FriendsState.Ready(someone)))
    }

    // endregion

    // region Settings ▸ Theme ▸ Components ▸ Achievement badge

    @Test fun achievementBadgeRowCyclesAndExplainsItself() {
        val row = ComponentRow.TELEMETRY
        assertEquals("Achievement badge", row.title)
        assertEquals(listOf("OFF", "COMPACT_PILL", "RICH_CARD"), row.choices.map { it.first })
        assertEquals("OFF", row.next(null))
        assertEquals("RICH_CARD", row.next("COMPACT_PILL"))
        assertNull(row.next("RICH_CARD"))
        val o = ComponentOverrides.NONE.with(row, "card")
        assertEquals("RICH_CARD", o[row])
        assertEquals(TelemetryMode.RICH_CARD, o.patch.telemetry)
        assertEquals("Theme pack (Compact)", row.packLabel(ResolvedComponents.DEFAULT))
        assertNull("an unknown value follows the pack", ComponentOverrides.NONE.with(row, "hologram")[row])

        fun rows(linked: Boolean, value: String?) = MenuTree(
            MenuSnapshot(
                settings = dev.xross.launcher.data.XrossSettings(componentOverrides = ComponentOverrides(telemetryMode = value)),
                accounts = AccountsSetup(retroAchievementsLinked = linked),
            ),
        ).list(MenuTree.DISPLAY_COMPONENTS)
        fun subtitle(linked: Boolean, value: String?) = rows(linked, value).single { it.id == "disp:comp:telemetry" }.subtitle
        assertEquals("Theme pack (Compact) · The focused game's unlocks and points, above the hints", subtitle(true, null))
        assertEquals("Rich card · Newest unlocks and friends who played it, above the hints", subtitle(true, "RICH_CARD"))
        assertEquals("Rich card · Needs a RetroAchievements account (Settings ▸ Accounts)", subtitle(false, "RICH_CARD"))
        assertEquals("Off · No RetroAchievements badge on games", subtitle(false, "OFF"))
        assertEquals("the last row, so earlier rows keep their places", "disp:comp:telemetry", rows(true, null).last().id)
    }

    // endregion
}
