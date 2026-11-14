package dev.xross.launcher

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.xross.launcher.data.GameEntity
import dev.xross.launcher.input.NavAction
import dev.xross.launcher.state.Categories
import dev.xross.launcher.state.MenuTree
import dev.xross.launcher.state.RaController
import dev.xross.launcher.state.XmbUiState
import dev.xross.launcher.state.XmbViewModel
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * RetroAchievements out of [XmbViewModel] (2b.7-prep): the view model's avatar HUD and dashboard
 * tests, unchanged, still pass through its facade; the rest drive [RaController] on its own.
 */
@RunWith(RobolectricTestRunner::class)
class RaControllerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun XmbViewModel.await(what: String, timeoutMs: Long = 5_000, condition: (XmbUiState) -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            idle()
            if (condition(state.value)) return
            if (System.currentTimeMillis() > deadline) fail("Timed out waiting for $what: ${state.value}")
            Thread.sleep(10)
        }
    }

    private fun XmbViewModel.press(action: NavAction, times: Int = 1) = repeat(times) {
        onAction(action)
        idle()
    }

    private fun snes(title: String) = GameEntity(
        path = "/storage/emulated/0/ROMs/snes/$title.sfc", platformId = "snes", title = title, fileName = "$title.sfc", sizeBytes = 4096, lastModified = 0,
    )

    // region Through the view model (moved from ProfileCardTest and Phase2bFeaturesTest)

    @Test fun upFromTheTopOfACategoryExpandsTheAvatarHudInPlace() {
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

        // Back collapses the HUD too, and no full-screen card replaces the menu on the way.
        press(NavAction.UP)
        assertTrue(vm.state.value.avatarFocused)
        assertNull(vm.state.value.profileCard)
        press(NavAction.BACK)
        assertFalse(vm.state.value.avatarFocused)
        assertNull(vm.state.value.profileCard)

        // Touch: a tap on the avatar expands the HUD, a tap outside it collapses it again.
        vm.onAvatarTapped()
        idle()
        assertTrue(vm.state.value.avatarFocused)
        assertNull(vm.state.value.profileCard)
        vm.closeAvatarHud()
        idle()
        assertFalse(vm.state.value.avatarFocused)

        // Account management stays one press away: Options on the HUD opens the full User Card.
        press(NavAction.UP)
        press(NavAction.OPTIONS)
        assertNotNull(vm.state.value.profileCard)
        assertFalse(vm.state.value.avatarFocused)
        assertFalse(vm.state.value.profile.linked)
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

    @Test fun dashboardOpensFromGamesWithAnOfflinePlaceholderState() {
        val container = AppContainer(context)
        runBlocking { container.database.games().insertAll(listOf(snes("Alpha"))) }
        val vm = XmbViewModel(context, container, storageAccess = { false }).also { idle() }
        vm.await("the RetroAchievements entry") { s -> s.items.any { it.id == MenuTree.RA_HUB } }
        val index = vm.state.value.items.indexOfFirst { it.id == MenuTree.RA_HUB }
        vm.press(NavAction.DOWN, times = index - vm.state.value.focusedIndex)
        vm.press(NavAction.CONFIRM)
        assertNotNull(vm.state.value.raHub)
        // No account is linked: the screen opens anyway and reports an empty, network-free summary.
        assertFalse(vm.state.value.profile.linked)
        vm.await("the library summary") { it.raHub?.summary != null }
        assertEquals(1, vm.state.value.raHub!!.summary!!.supportedGames)
        assertEquals(0, vm.state.value.raHub!!.summary!!.trackedGames)
        vm.press(NavAction.DOWN)
        assertEquals(1, vm.state.value.raHub!!.scroll)
        vm.press(NavAction.BACK)
        assertNull(vm.state.value.raHub)
    }

    // endregion

    // region The controller on its own

    private class FakeHost : RaController.Host {
        val toasts = mutableListOf<String>()
        val prompts = mutableListOf<Pair<String, Boolean>>()
        val moves = mutableListOf<Int>()
        var dismissed = 0
        override fun toast(message: String) { toasts += message }
        override fun fail(message: String) { toasts += message }
        override fun promptLink(user: String, linked: Boolean) { prompts += user to linked }
        override fun moveCategory(delta: Int) { moves += delta }
        override fun dismissMenus() { dismissed++ }
    }

    private val scope = MainScope()

    @After fun tearDown() = scope.cancel()

    private fun controller(host: FakeHost, container: AppContainer = AppContainer(context)) = RaController(
        scope = scope,
        profiles = container.profiles,
        achievements = container.achievements,
        credentials = container.credentials,
        games = container.games,
        sounds = container.sounds,
        artVersion = { 7L },
        host = host,
    )

    @Test fun avatarHudTakesInputOnlyWhileExpandedAndHandsSidewaysMovesBack() {
        val host = FakeHost()
        val ra = controller(host)
        assertFalse("Collapsed: the menu keeps the input", ra.onAvatarAction(NavAction.DOWN))
        assertFalse(ra.onScreenAction(NavAction.CONFIRM))

        ra.focusAvatar()
        assertTrue(ra.state.value.avatarFocused)
        assertTrue(ra.onAvatarAction(NavAction.RIGHT))
        assertFalse(ra.state.value.avatarFocused)
        assertEquals(listOf(1), host.moves)

        ra.focusAvatar()
        ra.onAvatarAction(NavAction.PREV_CATEGORY)
        assertEquals(listOf(1, -1), host.moves)

        // Unlinked: Confirm collapses the HUD and asks for an account.
        ra.focusAvatar()
        ra.onAvatarAction(NavAction.CONFIRM)
        assertFalse(ra.state.value.avatarFocused)
        assertEquals(1, host.prompts.size)
        assertFalse(host.prompts.single().second)
    }

    @Test fun screensDismissTheMenusUnderThemAndNowPlayingClosesThemAll() {
        val host = FakeHost()
        val ra = controller(host)
        ra.openProfileCard()
        assertNotNull(ra.state.value.profileCard)
        assertEquals(1, host.dismissed)
        assertTrue("The open card takes the input", ra.onScreenAction(NavAction.RIGHT))

        ra.openRaHub()
        idle()
        assertEquals(2, host.dismissed)
        assertEquals(7L, ra.state.value.raHub!!.artVersion)

        ra.focusAvatar()
        ra.dismissAll()
        val s = ra.state.value
        assertNull(s.profileCard)
        assertNull(s.raHub)
        assertNull(s.achievements)
        assertFalse(s.avatarFocused)
    }

    @Test fun linkDialogNeedsAKeyForANewUserAndAnEmptyNameOnUnlinkedDoesNothing() {
        val host = FakeHost()
        val ra = controller(host)
        ra.onRetroAchievementsEntered("  ", "")
        assertTrue(host.toasts.isEmpty())
        ra.onRetroAchievementsEntered("SomeoneNew", "")
        assertEquals(listOf("Enter your Web API key (retroachievements.org ▸ Settings ▸ Keys)"), host.toasts)
    }

    @Test fun telemetryClearsWhenTheFocusMovesAndNeverLoadsWhileUnlinked() {
        val host = FakeHost()
        val container = AppContainer(context)
        runBlocking { container.database.games().insertAll(listOf(snes("Alpha"))) }
        val ra = controller(host, container)
        val focused = MutableStateFlow<Long?>(null)
        ra.watch(focused)
        idle()
        val id = runBlocking { container.database.games().getAll().first().id }
        focused.value = id
        idle()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1)) // past the peek and load delays
        assertNull("No account: no telemetry and no request", ra.state.value.telemetry)
        focused.value = null
        idle()
        assertNull(ra.state.value.telemetry)
    }

    @Test fun failureMessagesForEachRefusal() {
        assertEquals("RetroAchievements refused that Web API key", RaController.failure(dev.xross.launcher.profile.RaResult.InvalidCredentials))
        assertEquals("No RetroAchievements user by that name", RaController.failure(dev.xross.launcher.profile.RaResult.UserNotFound))
        assertEquals("offline", RaController.failure(dev.xross.launcher.profile.RaResult.Failed("offline")))
    }

    // endregion
}
