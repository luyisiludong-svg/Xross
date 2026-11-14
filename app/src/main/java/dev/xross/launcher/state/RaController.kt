package dev.xross.launcher.state

import dev.xross.launcher.audio.UiSound
import dev.xross.launcher.audio.UiSounds
import dev.xross.launcher.data.CredentialStore
import dev.xross.launcher.data.GameEntity
import dev.xross.launcher.data.GameRepository
import dev.xross.launcher.input.NavAction
import dev.xross.launcher.profile.AchievementsRepository
import dev.xross.launcher.profile.AchievementsState
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
    }

    private val _state = MutableStateFlow(RaState())
    val state: StateFlow<RaState> = _state.asStateFlow()

    private var profileJob: Job? = null
    private var raHubJob: Job? = null
    private var achievementsJob: Job? = null

    /** The offline profile cache has been read (see [loadProfile]). */
    private var profileLoaded = false

    /** Starts the background work: unlock toasts and the telemetry of the game under [focusedGameId]. */
    fun watch(focusedGameId: Flow<Long?>) {
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
                GameTelemetry.of(id, achievements.peek(game))?.let { known -> _state.update { it.copy(telemetry = known) } }
                delay(TELEMETRY_LOAD_MS)
                when (val fresh = achievements.load(game)) {
                    is AchievementsState.Ready,
                    AchievementsState.NoMatch,
                    -> _state.update { it.copy(telemetry = GameTelemetry.of(id, fresh)) }
                    else -> Unit // offline or refused: keep what the Xross folder had
                }
            }
        }
    }

    /** Avatar and profile from the Xross folder first (instant, offline), then the network if stale. */
    fun loadProfile() {
        scope.launch {
            profiles.load()
            profileLoaded = true
            if (profiles.isStale()) refreshProfile(quiet = true)
        }
    }

    /** The Xross folder moved: read the profile from its new place. */
    suspend fun reloadProfile() = profiles.load()

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

    /** Input while the avatar HUD is expanded; false when it isn't. */
    fun onAvatarAction(action: NavAction): Boolean {
        if (!_state.value.avatarFocused) return false
        avatarAction(action)
        return true
    }

    /** Now Playing opened over everything: close every RetroAchievements screen and the HUD. */
    fun dismissAll() {
        achievementsJob?.cancel()
        _state.update { it.copy(profileCard = null, avatarFocused = false, achievements = null, raHub = null) }
    }

    // endregion

    // region Profile (status bar avatar, User Card, RetroAchievements)

    /** Expands the avatar into its mini-HUD (the avatar "has the focus" while the HUD is open). */
    fun focusAvatar() {
        sounds.play(UiSound.MOVE)
        _state.update { it.copy(avatarFocused = true) }
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
            NavAction.LEFT, NavAction.RIGHT, NavAction.PREV_CATEGORY, NavAction.NEXT_CATEGORY -> {
                _state.update { it.copy(avatarFocused = false) }
                host.moveCategory(if (action == NavAction.LEFT || action == NavAction.PREV_CATEGORY) -1 else +1)
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
                is RaResult.Ok -> host.toast("Linked RetroAchievements as ${result.profile.username}")
                else -> host.fail(failure(result))
            }
        }
    }

    private fun refreshProfile(quiet: Boolean) {
        if (!profiles.current.linked) return
        if (profileJob?.isActive == true) return
        profileJob = scope.launch {
            val result = profiles.refresh()
            if (!quiet) host.toast(if (result is RaResult.Ok) "Profile updated" else failure(result))
        }
    }

    private fun unlink() {
        profileJob?.cancel()
        profileJob = scope.launch {
            profiles.unlink()
            withContext(Dispatchers.IO) { achievements.clear() }
            _state.update { it.copy(profileCard = it.profileCard?.let { ProfileCardState() }) }
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

    fun openRaHub() {
        host.dismissMenus()
        _state.update { it.copy(raHub = RaHubState(artVersion = artVersion())) }
        loadRaSummary()
        if (profiles.current.linked && profiles.isStale()) refreshProfile(quiet = true)
    }

    /** Library-wide progress from what is cached in the Xross folder; never the network. */
    private fun loadRaSummary() {
        raHubJob?.cancel()
        raHubJob = scope.launch {
            val summary = achievements.librarySummary(games.games.first())
            _state.update { s -> s.copy(raHub = s.raHub?.copy(summary = summary)) }
        }
    }

    private fun raHubAction(hub: RaHubState, action: NavAction) {
        val outcome = RaHub.onAction(hub, action)
        when (outcome.effect) {
            RaHubEffect.Close -> return closeRaHub()
            RaHubEffect.Primary -> {
                sounds.play(UiSound.ENTER)
                if (profiles.current.linked) {
                    refreshProfile(quiet = false)
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
        }
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
            val platform = dev.xross.launcher.storage.PlatformRegistry.byId(game.platformId)
            val view = AchievementsViewState(
                gameId = game.id,
                uuid = game.uuid,
                title = game.title,
                platform = platform?.name,
                result = achievements.cached(game),
                artVersion = artVersion(),
            )
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

        fun failure(result: RaResult): String = when (result) {
            is RaResult.Ok -> ""
            RaResult.InvalidCredentials -> "RetroAchievements refused that Web API key"
            RaResult.UserNotFound -> "No RetroAchievements user by that name"
            is RaResult.Failed -> result.reason
        }
    }
}
