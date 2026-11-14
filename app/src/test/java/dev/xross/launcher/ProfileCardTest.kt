package dev.xross.launcher

import dev.xross.launcher.input.NavAction
import dev.xross.launcher.state.Command
import dev.xross.launcher.state.MenuSnapshot
import dev.xross.launcher.state.MenuTree
import dev.xross.launcher.state.AccountsSetup
import dev.xross.launcher.state.NodeAction
import dev.xross.launcher.state.ProfileCard
import dev.xross.launcher.state.ProfileCardButton
import dev.xross.launcher.state.ProfileCardEffect
import dev.xross.launcher.state.ProfileCardState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The User Card's buttons and Settings ▸ Accounts (avatar focus is in [RaControllerTest]). */
@RunWith(RobolectricTestRunner::class)
class ProfileCardTest {
    @Test fun buttonsDependOnWhetherAnAccountIsLinked() {
        assertEquals(listOf(ProfileCardButton.REFRESH, ProfileCardButton.UNLINK, ProfileCardButton.CLOSE), ProfileCard.buttons(linked = true))
        assertEquals(listOf(ProfileCardButton.LINK, ProfileCardButton.CLOSE), ProfileCard.buttons(linked = false))
    }

    @Test fun movingPressingAndClosing() {
        var s = ProfileCardState()
        s = ProfileCard.onAction(s, NavAction.LEFT, linked = true).state
        assertEquals("clamped at the first button", 0, s.index)
        s = ProfileCard.onAction(s, NavAction.RIGHT, linked = true).state
        s = ProfileCard.onAction(s, NavAction.RIGHT, linked = true).state
        s = ProfileCard.onAction(s, NavAction.RIGHT, linked = true).state
        assertEquals(2, s.index)
        assertEquals(ProfileCardEffect.Close, ProfileCard.onAction(s, NavAction.CONFIRM, linked = true).effect)
        assertEquals(ProfileCardEffect.Close, ProfileCard.onAction(ProfileCardState(), NavAction.BACK, linked = true).effect)
        assertEquals(
            ProfileCardEffect.Press(ProfileCardButton.REFRESH),
            ProfileCard.onAction(ProfileCardState(), NavAction.CONFIRM, linked = true).effect,
        )
        assertEquals(
            ProfileCardEffect.Press(ProfileCardButton.LINK),
            ProfileCard.onAction(ProfileCardState(), NavAction.CONFIRM, linked = false).effect,
        )
    }

    @Test fun unlinkNeedsASecondPress() {
        val onUnlink = ProfileCardState(index = 1)
        val first = ProfileCard.onAction(onUnlink, NavAction.CONFIRM, linked = true)
        assertEquals(ProfileCardEffect.None, first.effect)
        assertTrue(first.state.confirmUnlink)
        assertEquals("Press again to unlink", first.state.label(ProfileCardButton.UNLINK))
        val second = ProfileCard.onAction(first.state, NavAction.CONFIRM, linked = true)
        assertEquals(ProfileCardEffect.Press(ProfileCardButton.UNLINK), second.effect)
        // Moving away disarms it.
        val moved = ProfileCard.onAction(first.state, NavAction.LEFT, linked = true).state
        assertFalse(moved.confirmUnlink)
    }

    @Test fun accountsSettingsShowTheLinkState() {
        val unlinked = MenuTree(MenuSnapshot()).list(MenuTree.ACCOUNTS)
        assertEquals(listOf("acct:ra"), unlinked.map { it.id })
        assertEquals(NodeAction.Run(Command.LinkRetroAchievements), unlinked.single().action)

        val linked = MenuTree(
            MenuSnapshot(accounts = AccountsSetup("MaxMilyin", true, retroAchievementsPoints = 399597, retroAchievementsRank = 4616)),
        ).list(MenuTree.ACCOUNTS)
        assertEquals(listOf("acct:ra", "acct:card", "acct:refresh"), linked.map { it.id })
        val subtitle = linked.first().subtitle!!
        assertTrue(subtitle, subtitle.startsWith("Linked as MaxMilyin"))
        assertTrue(subtitle, subtitle.contains("4,616") || subtitle.contains("4616"))
    }
}
