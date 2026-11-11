package dev.xross.launcher

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.xross.launcher.input.NavAction
import dev.xross.launcher.state.Categories
import dev.xross.launcher.state.Command
import dev.xross.launcher.state.MenuSnapshot
import dev.xross.launcher.state.MenuTree
import dev.xross.launcher.state.AccountsSetup
import dev.xross.launcher.state.NodeAction
import dev.xross.launcher.state.ProfileCard
import dev.xross.launcher.state.ProfileCardButton
import dev.xross.launcher.state.ProfileCardEffect
import dev.xross.launcher.state.ProfileCardState
import dev.xross.launcher.state.XmbViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** The User Card's buttons, status bar avatar focus, and Settings ▸ Accounts. */
@RunWith(RobolectricTestRunner::class)
class ProfileCardTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

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

    @Test fun upFromTheTopOfACategoryFocusesTheAvatarAndOpensTheUserCard() {
        val vm = XmbViewModel(context, AppContainer(context), storageAccess = { false }).also { idle() }
        fun press(a: NavAction) {
            vm.onAction(a)
            idle()
        }
        // Settings root: the cursor starts on the first row.
        while (vm.state.value.let { it.categories[it.categoryIndex].id } != Categories.SETTINGS) press(NavAction.LEFT)
        assertEquals(0, vm.state.value.focusedIndex)
        assertFalse(vm.state.value.avatarFocused)

        press(NavAction.UP)
        assertTrue(vm.state.value.avatarFocused)
        press(NavAction.DOWN)
        assertFalse("Down returns to the list", vm.state.value.avatarFocused)
        assertEquals(0, vm.state.value.focusedIndex)

        press(NavAction.UP)
        press(NavAction.CONFIRM)
        assertNotNull(vm.state.value.profileCard)
        assertFalse(vm.state.value.avatarFocused)
        assertFalse(vm.state.value.profile.linked)
        press(NavAction.BACK)
        assertNull(vm.state.value.profileCard)

        // Touch: tapping the avatar opens it too, and its Close button shuts it.
        vm.onAvatarTapped()
        idle()
        assertNotNull(vm.state.value.profileCard)
        vm.onProfileCardButtonTapped(1) // Close (unlinked: Link account…, Close)
        idle()
        assertNull(vm.state.value.profileCard)
    }

    @Test fun upInsideAListStillMovesTheCursor() {
        val vm = XmbViewModel(context, AppContainer(context), storageAccess = { false }).also { idle() }
        while (vm.state.value.let { it.categories[it.categoryIndex].id } != Categories.SETTINGS) {
            vm.onAction(NavAction.LEFT)
            idle()
        }
        vm.onAction(NavAction.DOWN)
        idle()
        assertEquals(1, vm.state.value.focusedIndex)
        vm.onAction(NavAction.UP)
        idle()
        assertEquals(0, vm.state.value.focusedIndex)
        assertFalse(vm.state.value.avatarFocused)
    }
}
