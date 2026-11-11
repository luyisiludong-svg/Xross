package dev.xross.launcher.profile

import dev.xross.launcher.config.AtomicFiles
import dev.xross.launcher.data.CredentialStore
import dev.xross.launcher.storage.XrossLayout
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/** What the status bar avatar and the User Card show. */
data class ProfileState(
    /** A user name and Web API key are saved. */
    val linked: Boolean = false,
    val username: String = "",
    /** Last fetched profile (possibly from the offline cache); null before the first fetch. */
    val profile: RaProfile? = null,
    /** Cached avatar on disk, or null: the UI then draws the silhouette placeholder. */
    val avatar: File? = null,
    /** Bumped whenever [avatar]'s content changes, so the UI decodes it again. */
    val avatarVersion: Int = 0,
    val refreshing: Boolean = false,
)

/**
 * `Xross/data/retroachievements.json`. Public data only: the user name, where the avatar is,
 * and the profile. The Web API key stays in [CredentialStore].
 */
@Serializable
internal data class ProfileCacheFile(
    val version: Int = 1,
    val username: String,
    /** Relative to the Xross folder: "media/avatars/<user>.png". */
    val avatarFile: String? = null,
    val profile: RaProfile,
    /** Date of the newest unlock already announced; newer ones become unlock toasts. */
    val lastUnlockSeen: String? = null,
)

/**
 * The linked RetroAchievements account: links and unlinks it, refreshes the profile, and keeps an
 * offline copy (profile JSON plus avatar image) in the Xross folder so the status bar shows the
 * avatar instantly at startup and without a network.
 */
class ProfileRepository(
    private val client: RetroAchievementsClient,
    private val credentials: CredentialStore,
    private val layout: () -> XrossLayout,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<ProfileState> = _state.asStateFlow()
    val current: ProfileState get() = _state.value

    private val _unlocks = MutableSharedFlow<List<RaBadge>>(extraBufferCapacity = 8)

    /**
     * Achievements unlocked since the last refresh (oldest first), for the unlock toasts. Nothing
     * is announced for the first fetch of an account: only what is new after it.
     */
    val unlocks: SharedFlow<List<RaBadge>> = _unlocks.asSharedFlow()

    /** See [ProfileCacheFile.lastUnlockSeen]; null until the account's first fetch. */
    @Volatile private var lastUnlockSeen: String? = null

    private fun initialState() = credentials.current.let {
        ProfileState(linked = it.retroAchievementsLinked, username = it.retroAchievementsUser)
    }

    /** Reads the offline cache for the linked account. Call once at startup, and after the Xross folder moves. */
    suspend fun load() = withContext(io) {
        val account = credentials.current
        if (!account.retroAchievementsLinked) {
            _state.value = ProfileState()
            return@withContext
        }
        val cached = readCache()?.takeIf { it.username.equals(account.retroAchievementsUser, ignoreCase = true) }
        lastUnlockSeen = cached?.let { it.lastUnlockSeen ?: it.profile.recentBadges.firstOrNull()?.date ?: "" }
        val avatar = avatarFile(account.retroAchievementsUser).takeIf { it.isFile && it.length() > 0 }
        _state.update {
            it.copy(
                linked = true,
                username = account.retroAchievementsUser,
                profile = cached?.profile,
                avatar = avatar,
                avatarVersion = it.avatarVersion + 1,
            )
        }
    }

    /** True when the profile was never fetched or is older than [maxAgeMs]. */
    fun isStale(maxAgeMs: Long = STALE_MS): Boolean {
        val s = _state.value
        if (!s.linked) return false
        val fetched = s.profile?.fetchedAtMs ?: return true
        return clock() - fetched > maxAgeMs
    }

    /**
     * Checks [user] and [apiKey] against RetroAchievements and, only if they work, saves them and
     * caches the profile. A failed attempt leaves any previously linked account untouched.
     */
    suspend fun link(user: String, apiKey: String): RaResult = lock.withLock {
        val result = client.fetchProfile(user.trim(), apiKey.trim())
        if (result is RaResult.Ok) {
            val previous = credentials.current.retroAchievementsUser
            credentials.update {
                it.copy(retroAchievementsUser = result.profile.username, retroAchievementsKey = apiKey.trim())
            }
            if (previous.isNotBlank() && !previous.equals(result.profile.username, ignoreCase = true)) {
                withContext(io) { avatarFile(previous).delete() }
                lastUnlockSeen = null
            }
            store(result.profile, result.recentUnlocks)
        }
        result
    }

    /** Fetches the linked profile again. Without a network the cached copy stays as it was. */
    suspend fun refresh(): RaResult = lock.withLock {
        val account = credentials.current
        if (!account.retroAchievementsLinked) return@withLock RaResult.Failed("No RetroAchievements account linked")
        _state.update { it.copy(refreshing = true) }
        try {
            val result = client.fetchProfile(account.retroAchievementsUser, account.retroAchievementsKey)
            if (result is RaResult.Ok) {
                if (result.profile.username != account.retroAchievementsUser) {
                    credentials.update { it.copy(retroAchievementsUser = result.profile.username) }
                }
                store(result.profile, result.recentUnlocks)
            }
            result
        } finally {
            _state.update { it.copy(refreshing = false) }
        }
    }

    /** Forgets the account: the key, the cached profile and the avatar. */
    suspend fun unlink() = lock.withLock {
        val user = credentials.current.retroAchievementsUser
        credentials.update { it.copy(retroAchievementsUser = "", retroAchievementsKey = "") }
        withContext(io) {
            layout().profileCache.delete()
            if (user.isNotBlank()) avatarFile(user).delete()
        }
        lastUnlockSeen = null
        _state.update { ProfileState(avatarVersion = it.avatarVersion + 1) }
    }

    /** `Xross/media/avatars/<user>.png`. */
    fun avatarFile(username: String): File = File(layout().avatars, safeName(username) + ".png")

    private suspend fun store(profile: RaProfile, recent: List<RaBadge>) = withContext(io) {
        val avatar = avatarFile(profile.username)
        val downloaded = downloadAvatar(profile.avatarUrl, avatar)
        val hasAvatar = avatar.isFile && avatar.length() > 0
        val seen = lastUnlockSeen
        val fresh = newUnlocks(seen, recent)
        val newest = (recent.map { it.date } + listOfNotNull(seen)).filter { it.isNotEmpty() }.maxOrNull() ?: ""
        lastUnlockSeen = newest
        writeCache(
            ProfileCacheFile(
                username = profile.username,
                avatarFile = if (hasAvatar) "${XrossLayout.MEDIA}/${avatar.parentFile?.name}/${avatar.name}" else null,
                profile = profile,
                lastUnlockSeen = newest,
            ),
        )
        if (fresh.isNotEmpty()) _unlocks.tryEmit(fresh)
        _state.update {
            it.copy(
                linked = true,
                username = profile.username,
                profile = profile,
                avatar = avatar.takeIf { hasAvatar },
                avatarVersion = if (downloaded || it.avatar != avatar.takeIf { hasAvatar }) it.avatarVersion + 1 else it.avatarVersion,
            )
        }
    }

    /** Replaces [dest] only with a complete download; an old avatar survives a failed one. */
    private suspend fun downloadAvatar(url: String, dest: File): Boolean {
        if (url.isBlank()) return false
        val dir = dest.parentFile ?: return false
        if (!dir.isDirectory && !dir.mkdirs()) return false
        val tmp = File(dir, dest.name + ".part")
        return try {
            if (!client.downloadImage(url, tmp)) return false
            AtomicFiles.copy(tmp, dest)
            true
        } catch (_: IOException) {
            false
        } finally {
            tmp.delete()
        }
    }

    private fun readCache(): ProfileCacheFile? {
        val text = try {
            AtomicFiles.readText(layout().profileCache)
        } catch (_: IOException) {
            null
        } ?: return null
        return try {
            JSON.decodeFromString(ProfileCacheFile.serializer(), text)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun writeCache(cache: ProfileCacheFile) {
        try {
            AtomicFiles.writeText(layout().profileCache, JSON.encodeToString(ProfileCacheFile.serializer(), cache))
        } catch (_: IOException) {
            // The profile still shows this session; it just won't be there offline next time.
        }
    }

    companion object {
        /** Refresh in the background when the cached profile is older than this. */
        const val STALE_MS = 6L * 60 * 60 * 1000

        private val JSON = Json { ignoreUnknownKeys = true; prettyPrint = true }

        /** RetroAchievements names are letters and digits; anything else can't escape the folder. */
        /**
         * Unlocks newer than [seen], oldest first. A null [seen] is an account's first fetch:
         * its history isn't news. Dates are "yyyy-MM-dd HH:mm:ss", so text order is time order.
         */
        internal fun newUnlocks(seen: String?, recent: List<RaBadge>): List<RaBadge> {
            if (seen == null) return emptyList()
            return recent.filter { it.date.isNotEmpty() && it.date > seen }.sortedBy { it.date }
        }

        internal fun safeName(username: String): String =
            username.trim().replace(Regex("[^A-Za-z0-9_.-]"), "_").trimStart('.').ifEmpty { "_" }
    }
}
