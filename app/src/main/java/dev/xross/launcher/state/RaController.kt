package dev.xross.launcher.state

import dev.xross.launcher.audio.UiSound
import dev.xross.launcher.audio.UiSounds
import dev.xross.launcher.data.CredentialStore
import dev.xross.launcher.data.GameEntity
import dev.xross.launcher.data.GameRepository
import dev.xross.launcher.input.NavAction
import dev.xross.launcher.profile.AchievementsRepository
import dev.xross.launcher.profile.AchievementsState
import dev.xross.launcher.profile.FriendsRepository
import dev.xross.launcher.profile.FriendsState
import dev.xross.launcher.profile.ProfileRepository
import dev.xross.launcher.profile.RaConsoles
import dev.xross.launcher.profile.RaResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.xross.launcher.model.PlatformRegistry
import dev.xross.launcher.profile.ProfileState
import dev.xross.launcher.theme.pack.ResolvedComponents
import dev.xross.launcher.theme.pack.TelemetryMode
import dev.xross.launcher.theme.pack.TopLeftClusterMode

/** What the RetroAchievements screens and the status bar avatar show (the profile itself is [ProfileRepository.state]). */
data class RaState(
    /** The User Card while it is open. */
    val profileCard: ProfileCardState? = null,
    /** The status bar avatar has the focus (Up from the top of a category). */
    val avatarFocused: Boolean = false,
    /** A game's Achievements screen while it is open. */
    val achievements: AchievementsViewState? = null,
    /** The unlock toast on screen (it never takes input). */
    val unlockToast: UnlockToastState? = null,
    /** Games ▸ RetroAchievements while it is open. */
    val raHub: RaHubState? = null,
    /** RetroAchievements numbers of the focused game, for the badge in the bottom corner. */
    val telemetry: GameTelemetry? = null,
    /** Followed users (2b.7c), for the social stack. */
    val friends: FriendsState = FriendsState.Unlinked,
    /** The social stack's panel while it is open (it "has the focus", like the avatar's HUD). */
    val socialPanel: SocialPanelState? = null,
)

/**
 * RetroAchievements and the linked profile, split out of [XmbViewModel] (Architecture
 * Assessment #6): linking and unlinking, profile refresh and staleness, the avatar mini-HUD and
 * User Card, the focused game's telemetry, the Achievements screen, Games ▸ RetroAchievements and
 * unlock toasts. The view model owns one of these, routes input to it and merges [state] into
 * [XmbUiState].
 */
class RaController(
    private val scope: CoroutineScope,
    private val profiles: ProfileRepository,
    private val achievements: AchievementsRepository,
    private val friends: FriendsRepository,
    private val credentials: CredentialStore,
    private val games: GameRepository,
    private val sounds: UiSounds,
    /** The art cache's version, stamped on screens that show badges and box art. */
    private val artVersion: () -> Long,
    private val host: Host,
) {
    /** What the controller needs from the menu around it. */
    interface Host {
        fun toast(message: String)
        fun fail(message: String)
        /** Opens the link dialog (user name + Web API key); answered by [onRetroAchievementsEntered]. */
        fun promptLink(user: String, linked: Boolean)
        /** Left/Right from the avatar HUD move to the neighbouring category. */
        fun moveCategory(delta: Int)
        /** A RetroAchievements screen opened over the menu: close the Options menu and Details under it. */
        fun dismissMenus()
        /** The status bar as drawn now: whether the social stack and the avatar are in it (2b.7a, c). */
        fun components(): ResolvedComponents
        /** Moves the menu's cursor onto [gameId] in its system's list (the hub's sidebar, 2b.7e). */
        fun revealGame(gameId: Long)
    }

    private val _state = MutableStateFlow(RaState())
    val state: StateFlow<RaState> = _state.asStateFlow()

    private var profileJob: Job? = null
    private var raHubJob: Job? = null
    private var achievementsJob: Job? = null
    private var friendsJob: Job? = null

    /** Badges per row the Achievements grid last measured (the display doesn't change between games). */
    private var gridColumns = AchievementsViewState.COLUMNS

    /** The offline profile cache has been read (see [loadProfile]). */
    private var profileLoaded = false

    /** Starts the background work: unlock toasts and the telemetry of the game under [focusedGameId]. */
    fun watch(focusedGameId: Flow<Long?>) {
        // Followed users: whatever FriendsRepository knows; it only fetches when asked to refresh.
        scope.launch { friends.state.collect { f -> _state.update { it.copy(friends = f) } } }
        // Unlock toasts: new unlocks found by any profile refresh, one at a time.
        scope.launch {
            profiles.unlocks.collect { badges ->
                achievements.invalidateAll()
                unlockQueue.offer(badges)
                showNextUnlock()
                _state.value.achievements?.let { refreshAchievements(it.gameId, quiet = true) }
            }
        }
        // RetroAchievements badge for the focused game: what is cached at once, then a fresh copy
        // once the cursor has rested on it (so scrolling a list never queues a request per row).
        scope.launch {
            focusedGameId.distinctUntilChanged().collectLatest { id ->
                if (_state.value.telemetry != null) _state.update { it.copy(telemetry = null) }
                if (id == null || !profiles.current.linked) return@collectLatest
                delay(TELEMETRY_PEEK_MS)
                val game = games.get(id) ?: return@collectLatest
                if (RaConsoles.of(game.platformId) == null) return@collectLatest
                val platform = PlatformRegistry.byId(game.platformId)?.shortName
                GameTelemetry.of(id, achievements.peek(game), platform, peeked = true, uuid = game.uuid)?.let { known -> _state.update { it.copy(telemetry = known) } }
                delay(TELEMETRY_LOAD_MS)
                when (val fresh = achievements.load(game)) {
                    is AchievementsState.Ready,
                    AchievementsState.NoMatch,
                    -> _state.update { it.copy(telemetry = GameTelemetry.of(id, fresh, platform, uuid = game.uuid)) }
                    // Offline or refused: keep what the Xross folder had, now known to be offline.
                    else -> _state.update { s -> s.copy(telemetry = s.telemetry?.takeIf { it.gameId == id }?.copy(offline = true)) }
                }
            }
        }
    }

    /** Avatar and profile from the Xross folder first (instant, offline), then the network if stale. */
    fun loadProfile() {
        scope.launch {
            profiles.load()
            friends.load()
            profileLoaded = true
            if (profiles.isStale()) refreshProfile(quiet = true) else onFriendsWanted(onlyWithoutCache = true)
        }
    }

    /**
     * The social stack or the rich telemetry card was turned on (Settings ▸ Theme ▸ Components ▸
     * Top-left, Achievement badge), or the app started with one on: without this, a stack with no
     * cache would collapse and never be opened to fetch, and the card's strip would stay empty.
     * Fetches when stale, or at startup ([onlyWithoutCache]) only when nothing was ever fetched.
     * [components]: the bar as just saved, when the host's copy may not have caught up yet.
     */
    fun onFriendsWanted(onlyWithoutCache: Boolean = false, components: ResolvedComponents? = null) {
        if (!profiles.current.linked || !friendsWanted(components ?: host.components())) return
        if (if (onlyWithoutCache) !friends.hasCache() else friends.isStale()) refreshFriends()
    }

    /** The Xross folder moved: read the profile from its new place. */
    suspend fun reloadProfile() {
        profiles.load()
        friends.load()
    }

    /** Back in front: refresh a stale profile (once the offline copy has been read). */
    fun onForeground() {
        if (profileLoaded && profiles.isStale()) refreshProfile(quiet = true)
    }

    /** Back from [game]: fetch unlocks earned in the emulator now, so their toasts appear on return. */
    fun onReturnFromGame(game: GameEntity) {
        achievements.invalidate(game)
        if (profiles.current.linked) {
            profileJob?.cancel()
            refreshProfile(quiet = true)
        }
    }

    // region Input

    /** Input while an Achievements screen, the User Card or the hub is open; false when none is. */
    fun onScreenAction(action: NavAction): Boolean {
        val s = _state.value
        s.achievements?.let { achievementsAction(it, action); return true }
        s.profileCard?.let { profileCardAction(it, action); return true }
        s.raHub?.let { raHubAction(it, action); return true }
        return false
    }

    /** Input while the avatar HUD or the social panel is open; false when neither is. */
    fun onAvatarAction(action: NavAction): Boolean {
        _state.value.socialPanel?.let { socialPanelAction(it, action); return true }
        if (!_state.value.avatarFocused) return false
        avatarAction(action)
        return true
    }

    /** Now Playing opened over everything: close every RetroAchievements screen and the HUD. */
    fun dismissAll() {
        achievementsJob?.cancel()
        if (_state.value.socialPanel != null) markFriendsSeen()
        _state.update { it.copy(profileCard = null, avatarFocused = false, achievements = null, raHub = null, socialPanel = null) }
    }

    // endregion

    // region Profile (status bar avatar, User Card, RetroAchievements)

    /** Expands the avatar into its mini-HUD (the avatar "has the focus" while the HUD is open). */
    fun focusAvatar() {
        sounds.play(UiSound.MOVE)
        // From the social panel (Right, or a tap on the avatar while it is open): the HUD takes over.
        _state.update { it.copy(avatarFocused = true, socialPanel = null) }
        if (profiles.current.linked && profiles.isStale()) refreshProfile(quiet = true)
    }

    /** Collapses the mini-HUD back into the avatar (Back, Down, or a tap outside it). */
    fun closeAvatarHud() {
        if (_state.value.avatarFocused) {
            sounds.play(UiSound.MOVE)
            _state.update { it.copy(avatarFocused = false) }
        }
    }

    private fun avatarAction(action: NavAction) {
        when (action) {
            // The HUD stays in place: Confirm refreshes it, or starts linking when there is no account.
            NavAction.CONFIRM, NavAction.START -> {
                sounds.play(UiSound.ENTER)
                if (profiles.current.linked) {
                    refreshProfile(quiet = false)
                } else {
                    _state.update { it.copy(avatarFocused = false) }
                    promptLink()
                }
            }
            NavAction.DOWN, NavAction.BACK -> closeAvatarHud()
            // Left crosses the bar to the social stack when it shows (2b.7c).
            NavAction.LEFT -> if (socialStackShown()) {
                _state.update { it.copy(avatarFocused = false) }
                openSocialPanel()
            } else {
                _state.update { it.copy(avatarFocused = false) }
                host.moveCategory(-1)
            }
            NavAction.RIGHT, NavAction.PREV_CATEGORY, NavAction.NEXT_CATEGORY -> {
                _state.update { it.copy(avatarFocused = false) }
                host.moveCategory(if (action == NavAction.PREV_CATEGORY) -1 else +1)
            }
            // Account management (unlink, link another) lives on the full User Card.
            NavAction.OPTIONS -> openProfileCard()
            NavAction.UP, NavAction.MEDIA -> Unit
        }
    }

    /** Touch: tap the avatar to expand its mini-HUD, tap it again to collapse. */
    fun onAvatarTapped() {
        if (_state.value.avatarFocused) closeAvatarHud() else focusAvatar()
    }

    fun openProfileCard() {
        sounds.play(UiSound.OPTION)
        host.dismissMenus()
        _state.update { it.copy(profileCard = ProfileCardState(), avatarFocused = false) }
    }

    fun closeProfileCard() {
        if (_state.value.profileCard != null) {
            sounds.play(UiSound.BACK)
            _state.update { it.copy(profileCard = null) }
        }
    }

    private fun profileCardAction(card: ProfileCardState, action: NavAction) =
        applyProfileCard(card, ProfileCard.onAction(card, action, profiles.current.linked))

    /** Touch: tap a User Card button. */
    fun onProfileCardButtonTapped(index: Int) {
        val card = _state.value.profileCard ?: return
        applyProfileCard(card, ProfileCard.tap(card, index, profiles.current.linked))
    }

    private fun applyProfileCard(before: ProfileCardState, outcome: ProfileCardOutcome) {
        if (outcome.state != before) {
            sounds.play(UiSound.MOVE)
            _state.update { it.copy(profileCard = outcome.state) }
        }
        when (val effect = outcome.effect) {
            ProfileCardEffect.None -> Unit
            ProfileCardEffect.Close -> closeProfileCard()
            is ProfileCardEffect.Press -> {
                sounds.play(UiSound.ENTER)
                when (effect.button) {
                    ProfileCardButton.REFRESH -> refreshProfile(quiet = false)
                    ProfileCardButton.LINK -> {
                        _state.update { it.copy(profileCard = null) }
                        promptLink()
                    }
                    ProfileCardButton.UNLINK -> unlink()
                    ProfileCardButton.CLOSE -> closeProfileCard()
                }
            }
        }
    }

    fun promptLink() {
        val account = credentials.current
        host.promptLink(account.retroAchievementsUser, account.retroAchievementsLinked)
    }

    /** Settings ▸ Accounts ▸ Refresh: refreshes a linked profile, or starts linking. */
    fun refreshOrLink() = if (profiles.current.linked) refreshProfile(quiet = false) else promptLink()

    /**
     * Answer from the link dialog. An empty [key] keeps the saved key when the user name is
     * unchanged; an empty [user] unlinks.
     */
    fun onRetroAchievementsEntered(user: String, key: String) {
        val name = user.trim()
        val saved = credentials.current
        if (name.isEmpty()) {
            if (saved.retroAchievementsLinked) unlink()
            return
        }
        val apiKey = key.trim().ifEmpty {
            if (name.equals(saved.retroAchievementsUser, ignoreCase = true)) saved.retroAchievementsKey else ""
        }
        if (apiKey.isEmpty()) return host.toast("Enter your Web API key (retroachievements.org ▸ Settings ▸ Keys)")
        profileJob?.cancel()
        profileJob = scope.launch {
            host.toast("Checking RetroAchievements account…")
            when (val result = profiles.link(name, apiKey)) {
                is RaResult.Ok -> {
                    host.toast("Linked RetroAchievements as ${result.profile.username}")
                    // Another account's friends aren't this one's.
                    friends.load()
                    if (friendsWanted()) refreshFriends()
                }
                else -> host.fail(failure(result))
            }
        }
    }

    /**
     * Refreshes the profile; once it worked, also the followed users when something shows them
     * ([friendsWanted]) or [withFriends] (the hub's Primary action).
     */
    private fun refreshProfile(quiet: Boolean, withFriends: Boolean = false) {
        if (!profiles.current.linked) return
        if (profileJob?.isActive == true) {
            if (withFriends) refreshFriends()
            return
        }
        profileJob = scope.launch {
            val result = profiles.refresh()
            if (!quiet) host.toast(if (result is RaResult.Ok) "Profile updated" else failure(result))
            if (result is RaResult.Ok && (withFriends || friendsWanted())) refreshFriends()
        }
    }

    // region Social stack (2b.7c)

    /**
     * Something draws friend data: the social stack, the rich telemetry card's strip, or the hub's
     * `FOLLOWING` sidebar while Games ▸ RetroAchievements is open (2b.7e).
     */
    private fun friendsWanted(components: ResolvedComponents = host.components()): Boolean =
        components.topLeft == TopLeftClusterMode.SOCIAL_STACK ||
            components.telemetry == TelemetryMode.RICH_CARD ||
            _state.value.raHub != null

    /** The stack is in the bar now (see [SocialStack.shows]). */
    fun socialStackShown(): Boolean = SocialStack.shows(host.components(), _state.value.friends)

    private fun refreshFriends() {
        if (!profiles.current.linked || friendsJob?.isActive == true) return
        friendsJob = scope.launch {
            friends.refresh()
            // Found while the panel is open: seen, not news for the badge later.
            if (_state.value.socialPanel != null) friends.markOpened()
        }
    }

    /** Opens the stack's panel (Up from the top without an avatar, Left from the avatar HUD, or a tap). */
    fun openSocialPanel() {
        if (!socialStackShown()) return
        sounds.play(UiSound.MOVE)
        val newKeys = _state.value.friends.friends.filter { it.isNew }.mapTo(HashSet()) { it.key }
        _state.update { it.copy(socialPanel = SocialPanelState(newKeys = newKeys), avatarFocused = false) }
        markFriendsSeen()
        if (friends.isStale()) refreshFriends()
    }

    fun closeSocialPanel() {
        if (_state.value.socialPanel != null) {
            sounds.play(UiSound.MOVE)
            _state.update { it.copy(socialPanel = null) }
        }
    }

    /** Touch: tap the stack to open its panel, tap it again to close. */
    fun onSocialStackTapped() {
        if (_state.value.socialPanel != null) closeSocialPanel() else openSocialPanel()
    }

    private fun markFriendsSeen() {
        scope.launch { friends.markOpened() }
    }

    private fun socialPanelAction(panel: SocialPanelState, action: NavAction) {
        val count = _state.value.friends.friends.size
        val outcome = SocialStack.onAction(panel, action, count, host.components().avatar)
        when (val effect = outcome.effect) {
            SocialPanelEffect.Close -> return closeSocialPanel()
            SocialPanelEffect.ToAvatar -> {
                _state.update { it.copy(socialPanel = null) }
                return focusAvatar()
            }
            is SocialPanelEffect.MoveCategory -> {
                _state.update { it.copy(socialPanel = null) }
                return host.moveCategory(effect.delta)
            }
            SocialPanelEffect.None -> Unit
        }
        if (outcome.state != panel) {
            sounds.play(UiSound.MOVE)
            _state.update { it.copy(socialPanel = outcome.state) }
        }
    }

    // endregion

    private fun unlink() {
        profileJob?.cancel()
        friendsJob?.cancel()
        profileJob = scope.launch {
            profiles.unlink()
            friends.clear()
            withContext(Dispatchers.IO) { achievements.clear() }
            _state.update { it.copy(profileCard = it.profileCard?.let { ProfileCardState() }, socialPanel = null) }
            host.toast("RetroAchievements account unlinked")
        }
    }

    // endregion

    // region Unlock toasts

    private val unlockQueue = UnlockQueue()
    private var unlockJob: Job? = null

    /** Shows the next queued unlock unless one is on screen; each stays [UNLOCK_TOAST_MS]. */
    private fun showNextUnlock() {
        if (unlockJob?.isActive == true) return
        val next = unlockQueue.next() ?: return
        unlockJob = scope.launch {
            sounds.play(UiSound.TROPHY)
            _state.update { it.copy(unlockToast = next) }
            delay(UNLOCK_TOAST_MS)
            _state.update { it.copy(unlockToast = null) }
            delay(UNLOCK_TOAST_GAP_MS)
            unlockJob = null
            showNextUnlock()
        }
    }

    // endregion

    // region RetroAchievements dashboard (Games ▸ RetroAchievements)

    /**
     * Opens the dashboard. A stale profile is fetched (and the friends with it, the sidebar wants
     * them); a fresh profile with stale friends fetches only the friends, as opening a stale
     * social stack does. Never while it stays open: only its Primary action fetches again.
     */
    fun openRaHub() {
        host.dismissMenus()
        _state.update { it.copy(raHub = RaHubState(artVersion = artVersion())) }
        loadRaSummary()
        if (!profiles.current.linked) return
        if (profiles.isStale()) refreshProfile(quiet = true) else if (friends.isStale()) refreshFriends()
    }

    /** Library-wide and per-system progress from what is cached in the Xross folder; never the network. */
    private fun loadRaSummary() {
        raHubJob?.cancel()
        raHubJob = scope.launch {
            val summary = achievements.hubSummary(games.games.first())
            _state.update { s -> s.copy(raHub = s.raHub?.let { RaHub.withSummary(it, summary) }) }
        }
    }

    /** The sidebar's rows as drawn now ([HubSidebar.rows]); empty while it is collapsed. */
    fun hubSidebarRows(): List<SidebarRow> = HubSidebar.rows(_state.value.friends, selfRow(profiles.current))

    private fun raHubAction(hub: RaHubState, action: NavAction) {
        val rows = hubSidebarRows()
        val outcome = RaHub.onAction(hub, action, rows.size)
        when (val effect = outcome.effect) {
            is RaHubEffect.OpenFriend -> return openFriendGame(rows.getOrNull(effect.row))
            RaHubEffect.Close -> return closeRaHub()
            RaHubEffect.Primary -> {
                sounds.play(UiSound.ENTER)
                if (profiles.current.linked) {
                    refreshProfile(quiet = false, withFriends = true)
                    loadRaSummary()
                } else {
                    _state.update { it.copy(raHub = null) }
                    promptLink()
                }
                return
            }
            RaHubEffect.None -> Unit
        }
        if (outcome.state != hub) {
            sounds.play(UiSound.MOVE)
            _state.update { it.copy(raHub = outcome.state) }
        } else if (action in HUB_EDGES) {
            // Clamped: L1/R1 at All or at the last system.
            sounds.play(UiSound.ERROR)
        }
    }

    /**
     * Confirm on a sidebar row: the friend's latest game in the library ([HubSidebar.libraryGame]),
     * with the hub closed and the menu's cursor on it; a toast when the library doesn't have it.
     */
    private fun openFriendGame(row: SidebarRow?) {
        if (row == null || row.self) return sounds.play(UiSound.ERROR)
        val a = row.activity
        if (a == null || a.gameId <= 0) {
            sounds.play(UiSound.ERROR)
            return host.toast(if (a == null) "${row.user}'s latest game isn't known yet" else "${row.user} hasn't played a game yet")
        }
        val byRaId = _state.value.raHub?.summary?.libraryGames.orEmpty()
        scope.launch {
            val game = HubSidebar.libraryGame(a, byRaId, games.games.first())
            if (game == null) {
                sounds.play(UiSound.ERROR)
                host.toast("${a.gameTitle.ifEmpty { "That game" }} isn't in your library")
                return@launch
            }
            sounds.play(UiSound.ENTER)
            raHubJob?.cancel()
            _state.update { it.copy(raHub = null) }
            host.revealGame(game.id)
        }
    }

    /** Touch: a filter pill. */
    fun onRaHubPillTapped(index: Int) {
        val hub = _state.value.raHub ?: return
        if (index !in hub.pills.indices || index == hub.filter) return
        sounds.play(UiSound.MOVE)
        _state.update { it.copy(raHub = hub.copy(filter = index, scroll = 0)) }
    }

    /** Touch: a sidebar row focuses it; a tap on the focused row opens the friend's game. */
    fun onRaHubRowTapped(index: Int) {
        val hub = _state.value.raHub ?: return
        val rows = hubSidebarRows()
        if (index !in rows.indices) return
        if (hub.focus == HubFocus.SIDEBAR && hub.row == index) return openFriendGame(rows[index])
        sounds.play(UiSound.MOVE)
        _state.update { it.copy(raHub = hub.copy(focus = HubFocus.SIDEBAR, row = index)) }
    }

    fun closeRaHub() {
        raHubJob?.cancel()
        if (_state.value.raHub != null) {
            sounds.play(UiSound.BACK)
            _state.update { it.copy(raHub = null) }
        }
    }

    // endregion

    // region Achievements screen

    fun showAchievements(gameId: Long) {
        achievementsJob?.cancel()
        achievementsJob = scope.launch {
            val game = games.get(gameId) ?: return@launch
            val platform = PlatformRegistry.byId(game.platformId)
            val view = AchievementsViewState(
                gameId = game.id,
                uuid = game.uuid,
                title = game.title,
                platform = platform?.name,
                result = achievements.cached(game),
                artVersion = artVersion(),
                columns = gridColumns,
            ).settled()
            sounds.play(UiSound.ENTER)
            host.dismissMenus()
            _state.update { it.copy(achievements = view) }
            val result = achievements.load(game)
            _state.update { s -> s.copy(achievements = s.achievements?.takeIf { it.gameId == gameId }?.withResult(result)) }
        }
    }

    private fun refreshAchievements(gameId: Long, quiet: Boolean) {
        if (achievementsJob?.isActive == true) return
        achievementsJob = scope.launch {
            val game = games.get(gameId) ?: return@launch
            if (!quiet) host.toast("Refreshing achievements…")
            val result = achievements.load(game, maxAgeMs = 0)
            _state.update { s -> s.copy(achievements = s.achievements?.takeIf { it.gameId == gameId }?.withResult(result)) }
            if (!quiet && result is AchievementsState.Failed) host.fail(result.reason)
        }
    }

    private fun achievementsAction(view: AchievementsViewState, action: NavAction) {
        val outcome = AchievementsGrid.onAction(view, action)
        when (outcome.effect) {
            AchievementsEffect.Close -> return closeAchievements()
            AchievementsEffect.Refresh -> {
                sounds.play(UiSound.OPTION)
                return refreshAchievements(view.gameId, quiet = false)
            }
            AchievementsEffect.None -> Unit
        }
        if (outcome.state != view) {
            sounds.play(UiSound.MOVE)
            _state.update { it.copy(achievements = outcome.state) }
        } else if (action == NavAction.CONFIRM || action == NavAction.START) {
            // Nothing to run on a badge; its details are already shown.
            Unit
        } else if (view.achievements.isNotEmpty() && action in GRID_MOVES) {
            sounds.play(UiSound.ERROR)
        }
    }

    fun closeAchievements() {
        achievementsJob?.cancel()
        if (_state.value.achievements != null) {
            sounds.play(UiSound.BACK)
            _state.update { it.copy(achievements = null) }
        }
    }

    /** The grid measured its columns (2b.7e: from the width). */
    fun onAchievementsColumns(columns: Int) {
        gridColumns = columns
        val view = _state.value.achievements ?: return
        val next = view.withColumns(columns)
        if (next != view) _state.update { s -> s.copy(achievements = s.achievements?.takeIf { it.gameId == view.gameId }?.withColumns(columns)) }
    }

    /** Touch: tap a badge to show its details. */
    fun onAchievementTapped(index: Int) {
        val view = _state.value.achievements ?: return
        val next = AchievementsGrid.tap(view, index)
        if (next != view) {
            sounds.play(UiSound.MOVE)
            _state.update { it.copy(achievements = next) }
        }
    }

    // endregion

    companion object {
        internal const val UNLOCK_TOAST_MS = 4_500L
        private const val UNLOCK_TOAST_GAP_MS = 350L
        private const val TELEMETRY_PEEK_MS = 250L
        private const val TELEMETRY_LOAD_MS = 450L
        private val GRID_MOVES = setOf(NavAction.LEFT, NavAction.RIGHT, NavAction.UP, NavAction.DOWN)
        private val HUB_EDGES = setOf(NavAction.PREV_CATEGORY, NavAction.NEXT_CATEGORY)

        /** The linked user's own sidebar row, once the profile was fetched. */
        fun selfRow(p: ProfileState): SidebarRow? = p.profile?.let {
            SidebarRow(it.username, it.totalPoints, p.avatar, p.avatarVersion, self = true)
        }

        fun failure(result: RaResult): String = when (result) {
            is RaResult.Ok -> ""
            RaResult.InvalidCredentials -> "RetroAchievements refused that Web API key"
            RaResult.UserNotFound -> "No RetroAchievements user by that name"
            is RaResult.Failed -> result.reason
        }
    }
}
