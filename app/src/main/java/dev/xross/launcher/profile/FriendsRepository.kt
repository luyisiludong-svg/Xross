package dev.xross.launcher.profile

import androidx.compose.runtime.Immutable
import dev.xross.launcher.config.AtomicFiles
import dev.xross.launcher.data.CredentialStore
import dev.xross.launcher.storage.XrossLayout
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** One followed user as the social stack and its panel show them. Public data. */
@Immutable
data class Friend(
    val user: String,
    /** [RaFollowed.key]. */
    val key: String,
    val points: Long = 0,
    /** Cached avatar on disk, or null: the UI draws the silhouette placeholder. */
    val avatar: File? = null,
    /** Changes when [avatar]'s content changes (its modification time), so the UI decodes it again. */
    val avatarVersion: Int = 0,
    /** What they did last; null until it was fetched once. */
    val activity: RaFriendActivity? = null,
    /** Their latest game or last activity changed since the social stack was last opened. */
    val isNew: Boolean = false,
)

/**
 * The followed users (2b.7c). Every state but [Unlinked] carries what is known, newest activity
 * first ([friends] may be the cached list), and [newActivity]: how many of them have something
 * new since the social stack was last opened.
 */
sealed interface FriendsState {
    val friends: List<Friend>
    val newActivity: Int

    /** No RetroAchievements account linked. */
    data object Unlinked : FriendsState {
        override val friends: List<Friend> get() = emptyList()
        override val newActivity: Int get() = 0
    }

    /** No network, or the last refresh failed: [friends] is the cached list (empty without a cache). */
    data class Offline(override val friends: List<Friend> = emptyList(), override val newActivity: Int = 0) : FriendsState

    /** A refresh is running; [friends] is what was known before it. */
    data class Loading(override val friends: List<Friend> = emptyList(), override val newActivity: Int = 0) : FriendsState

    data class Ready(override val friends: List<Friend> = emptyList(), override val newActivity: Int = 0) : FriendsState

    /** What the badge says: "" (nothing new), "1"–"9", then "9+". */
    val badge: String get() = badgeText(newActivity)

    companion object {
        fun badgeText(n: Int): String = when {
            n <= 0 -> ""
            n > 9 -> "9+"
            else -> n.toString()
        }
    }
}

/** What one [FriendsRepository.refresh] did, for the `XrossRa` log line and tests. */
data class FriendsRefresh(
    val friends: Int,
    /** Activity answers received. */
    val activity: Int,
    /** Requests sent to the Web API (avatars, on the media host, not counted). */
    val calls: Int,
    val failed: Boolean,
    /** Skipped: another refresh was running, or no account is linked. */
    val skipped: Boolean = false,
)

/**
 * `Xross/data/ra/friends.json`. Public data only: who the account follows, what they played,
 * and what the social stack last showed. No key, no session token.
 */
@Serializable
internal data class FriendsCacheFile(
    val version: Int = 1,
    /** The linked account this belongs to; another account's file is ignored. */
    val owner: String,
    val followingFetchedAtMs: Long = 0,
    val following: List<RaFollowed> = emptyList(),
    /** How many the account follows (may exceed [following], which is capped). */
    val total: Int = 0,
    /** By [RaFollowed.key]. */
    val activity: Map<String, RaFriendActivity> = emptyMap(),
    /** [RaFriendActivity.signature] per friend when the stack was last opened; null before the first refresh. */
    val seen: Map<String, String>? = null,
    val stackOpenedAtMs: Long = 0,
)

/**
 * Followed users and what they played last (2b.7c), for the social stack and later the rich
 * telemetry card and the hub's sidebar.
 *
 * **Fetched on [refresh] only**, which the RetroAchievements hub's Primary action, the profile's
 * own refresh, and opening a stale social stack call; never hover, focus, composition or a timer.
 * One refresh asks for the following list when it is older than [FOLLOWING_STALE_MS], then the
 * activity of at most [MAX_ACTIVITY] friends whose copy is older than [ACTIVITY_FRESH_MS], one
 * call at a time at least [CALL_SPACING_MS] apart; a 429 or a network failure ends it (the rest
 * keep their cached copy). Avatars go to `Xross/media/avatars/friends/` like the user's own.
 */
class FriendsRepository(
    private val client: RetroAchievementsClient,
    private val credentials: CredentialStore,
    private val layout: () -> XrossLayout,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Waits between calls; replaced in tests. */
    private val pause: suspend (Long) -> Unit = { delay(it) },
    /** Debug and profiling builds: one `XrossRa` line per refresh (never the key or a URL). */
    private val log: (String) -> Unit = { if (dev.xross.launcher.BuildConfig.PERF_LOG) runCatching { android.util.Log.i(TAG, it) } },
) {
    private val refreshLock = Mutex()
    private val cacheLock = Mutex()
    @Volatile private var cache: FriendsCacheFile? = null

    private val _state = MutableStateFlow<FriendsState>(if (credentials.current.retroAchievementsLinked) FriendsState.Offline() else FriendsState.Unlinked)
    val state: StateFlow<FriendsState> = _state.asStateFlow()
    val current: FriendsState get() = _state.value

    /** Reads the cache for the linked account. No network. Call at startup and after the Xross folder moves. */
    suspend fun load() = withContext(io) {
        val account = credentials.current
        if (!account.retroAchievementsLinked) {
            cache = null
            _state.value = FriendsState.Unlinked
            return@withContext
        }
        cacheLock.withLock {
            cache = readCache()?.takeIf { it.owner.equals(account.retroAchievementsUser, ignoreCase = true) }
        }
        // Not fetched in this session yet: what the cache holds is ready to show.
        _state.value = cache?.let { FriendsState.Ready(friendsOf(it), newCount(it)) } ?: FriendsState.Offline()
    }

    /** The following list was fetched at least once for this account (possibly long ago). */
    fun hasCache(): Boolean = (cache?.followingFetchedAtMs ?: 0L) > 0L

    /**
     * True when the following list is older than [FOLLOWING_STALE_MS] (or was never fetched), or no
     * activity was fetched in the last [ACTIVITY_FRESH_MS]. No network.
     */
    fun isStale(): Boolean {
        if (!credentials.current.retroAchievementsLinked) return false
        val c = cache ?: return true
        val now = clock()
        if (c.followingFetchedAtMs == 0L || now - c.followingFetchedAtMs > FOLLOWING_STALE_MS) return true
        if (c.following.isEmpty()) return false
        val newest = c.activity.values.maxOfOrNull { it.fetchedAtMs } ?: return true
        return now - newest > ACTIVITY_FRESH_MS
    }

    /** See the class comment. Returns at once (skipped) while another refresh runs. */
    suspend fun refresh(): FriendsRefresh {
        if (!refreshLock.tryLock()) return FriendsRefresh(current.friends.size, 0, 0, failed = false, skipped = true)
        try {
            return withContext(io) { doRefresh() }
        } finally {
            refreshLock.unlock()
        }
    }

    private suspend fun doRefresh(): FriendsRefresh {
        val account = credentials.current
        if (!account.retroAchievementsLinked) {
            _state.value = FriendsState.Unlinked
            return FriendsRefresh(0, 0, 0, failed = false, skipped = true)
        }
        val key = account.retroAchievementsKey
        val start = cache?.takeIf { it.owner.equals(account.retroAchievementsUser, ignoreCase = true) }
            ?: FriendsCacheFile(owner = account.retroAchievementsUser)
        _state.value = FriendsState.Loading(friendsOf(start), newCount(start))

        var calls = 0
        var lastCallAt = Long.MIN_VALUE
        suspend fun spaced() {
            if (lastCallAt != Long.MIN_VALUE) {
                val wait = lastCallAt + CALL_SPACING_MS - clock()
                if (wait > 0) pause(wait)
            }
            lastCallAt = clock()
            calls++
        }

        var failed = false
        var following = start.following
        var total = start.total
        var followingAt = start.followingFetchedAtMs
        if (followingAt == 0L || clock() - followingAt > FOLLOWING_STALE_MS) {
            spaced()
            when (val r = retryOnce({ (it as? RaFollowingResult.Error)?.result.isBusy() }, { spaced() }) { client.fetchFollowing(key) }) {
                is RaFollowingResult.Ok -> {
                    following = r.users
                    total = r.total
                    followingAt = clock()
                }
                is RaFollowingResult.Error -> failed = true
            }
        }

        val activity = start.activity.filterKeys { k -> following.any { it.key == k } }.toMutableMap()
        var received = 0
        if (!failed) {
            for (friend in pick(following, activity, clock())) {
                spaced()
                when (val r = retryOnce({ (it as? RaFriendResult.Error)?.result.isBusy() }, { spaced() }) { client.fetchFriendActivity(key, friend) }) {
                    is RaFriendResult.Ok -> {
                        activity[friend.key] = r.activity
                        received++
                    }
                    is RaFriendResult.Error -> when (r.result) {
                        // Gone, or a name RetroAchievements no longer knows: forget what we had.
                        RaResult.UserNotFound -> activity.remove(friend.key)
                        // Offline, refused or busy: asking the others now would fail the same way.
                        else -> failed = true
                    }
                }
                if (failed) break
            }
        }

        downloadAvatars(following, activity)

        val now = clock()
        val updated = cacheLock.withLock {
            // Keep what opening the stack recorded meanwhile.
            val latest = cache?.takeIf { it.owner.equals(start.owner, ignoreCase = true) } ?: start
            val fresh = latest.copy(
                followingFetchedAtMs = followingAt,
                following = following,
                total = total,
                activity = activity,
            )
            // The first refresh of an account: what friends already did isn't news.
            val settled = if (fresh.seen == null && followingAt != 0L) {
                fresh.copy(seen = signatures(fresh), stackOpenedAtMs = now)
            } else {
                fresh
            }
            cache = settled
            writeCache(settled)
            settled
        }
        val friends = friendsOf(updated)
        val news = newCount(updated)
        _state.value = if (failed) FriendsState.Offline(friends, news) else FriendsState.Ready(friends, news)
        log("friends=${following.size} activity=$received calls=$calls" + if (failed) " failed" else "")
        return FriendsRefresh(following.size, received, calls, failed)
    }

    /** [call], and once more after [BUSY_BACKOFF_MS] if RetroAchievements answered 429 ([busy]). */
    private suspend fun <T> retryOnce(busy: (T) -> Boolean, spaced: suspend () -> Unit, call: suspend () -> T): T {
        val first = call()
        if (!busy(first)) return first
        pause(BUSY_BACKOFF_MS)
        spaced()
        return call()
    }

    private fun RaResult?.isBusy() = this is RaResult.Failed && busy

    /** The social stack was opened: what it shows now is no longer new. No network. */
    suspend fun markOpened() = withContext(io) {
        val updated = cacheLock.withLock {
            val c = cache ?: return@withContext
            c.copy(seen = signatures(c), stackOpenedAtMs = clock()).also {
                cache = it
                writeCache(it)
            }
        }
        _state.value = when (val s = _state.value) {
            FriendsState.Unlinked -> s
            is FriendsState.Offline -> FriendsState.Offline(friendsOf(updated), 0)
            is FriendsState.Loading -> FriendsState.Loading(friendsOf(updated), 0)
            is FriendsState.Ready -> FriendsState.Ready(friendsOf(updated), 0)
        }
    }

    /** Forgets every friend (unlinking): the cache file and their avatars. Blocking I/O on [io]. */
    suspend fun clear() = withContext(io) {
        cacheLock.withLock {
            cache = null
            file().delete()
            friendAvatars().listFiles()?.forEach { it.delete() }
            friendAvatars().delete()
        }
        _state.value = if (credentials.current.retroAchievementsLinked) FriendsState.Offline() else FriendsState.Unlinked
    }

    /** `Xross/media/avatars/friends/<user>.png`. */
    fun avatarFile(user: String): File = File(friendAvatars(), ProfileRepository.safeName(user) + ".png")

    private fun friendAvatars() = File(layout().avatars, FRIEND_AVATARS)

    private fun file() = File(File(layout().data, AchievementsRepository.RA_DIR), FILE)

    /** Avatars of the friends the stack and panel can show, when missing or older than [AVATAR_TTL_MS]. */
    private suspend fun downloadAvatars(following: List<RaFollowed>, activity: Map<String, RaFriendActivity>) {
        val shown = ordered(following, activity).take(MAX_ACTIVITY)
        for (f in shown) {
            val dest = avatarFile(f.user)
            if (dest.isFile && dest.length() > 0 && clock() - dest.lastModified() < AVATAR_TTL_MS) continue
            val url = activity[f.key]?.avatarUrl?.takeIf { it.isNotBlank() }
                ?: RetroAchievementsClient.absoluteMedia("/UserPic/${f.user}.png")
            val dir = dest.parentFile ?: continue
            if (!dir.isDirectory && !dir.mkdirs()) continue
            val tmp = File(dir, dest.name + ".part")
            try {
                if (client.downloadImage(url, tmp)) AtomicFiles.copy(tmp, dest)
            } catch (_: IOException) {
                // The old avatar (or the placeholder) stays.
            } finally {
                tmp.delete()
            }
        }
    }

    private fun friendsOf(c: FriendsCacheFile): List<Friend> {
        val since = if (c.seen == null) null else utc(c.stackOpenedAtMs)
        return ordered(c.following, c.activity).map { f ->
            val a = c.activity[f.key]
            val avatar = avatarFile(f.user).takeIf { it.isFile && it.length() > 0 }
            Friend(
                user = a?.user?.takeIf { it.isNotBlank() } ?: f.user,
                key = f.key,
                points = a?.points?.takeIf { it > 0 } ?: f.points,
                avatar = avatar,
                avatarVersion = avatar?.lastModified()?.let { (it xor (it ushr 32)).toInt() } ?: 0,
                activity = a,
                isNew = since != null && a != null && isNew(f.key, a, c.seen.orEmpty(), since),
            )
        }
    }

    private fun newCount(c: FriendsCacheFile): Int = friendsOf(c).count { it.isNew }

    private fun readCache(): FriendsCacheFile? {
        val text = try {
            AtomicFiles.readText(file())
        } catch (_: IOException) {
            null
        } ?: return null
        return try {
            JSON.decodeFromString(FriendsCacheFile.serializer(), text).takeIf { it.version == 1 }
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun writeCache(c: FriendsCacheFile) {
        try {
            AtomicFiles.writeText(file(), JSON.encodeToString(FriendsCacheFile.serializer(), c))
        } catch (_: IOException) {
            // Shown this session; just not offline next time.
        }
    }

    companion object {
        private const val TAG = "XrossRa"
        const val FILE = "friends.json"
        const val FRIEND_AVATARS = "friends"

        /** The following list is fetched again after this (the profile's own staleness). */
        const val FOLLOWING_STALE_MS = ProfileRepository.STALE_MS

        /** A friend's activity is fetched again after this. */
        const val ACTIVITY_FRESH_MS = 30L * 60 * 1000

        /** Friends whose activity one refresh may ask for. */
        const val MAX_ACTIVITY = 20

        /** Of [MAX_ACTIVITY], the slots kept for the most recently active; the rest rotate through the others. */
        const val RECENT_SLOTS = 15

        /**
         * Between the starts of two Web API calls of one refresh. RetroAchievements lets about 7
         * calls through back to back, then answers 429 (no Retry-After) until its bucket refills;
         * calls ~330 ms apart passed 30 in a row (docs/RETROACHIEVEMENTS_API.md).
         */
        const val CALL_SPACING_MS = 350L

        /** After a 429: wait this long, try that call once more, and end the refresh if it is refused again. */
        const val BUSY_BACKOFF_MS = 2_000L

        const val AVATAR_TTL_MS = 24L * 60 * 60 * 1000

        private val JSON = Json { ignoreUnknownKeys = true; prettyPrint = true }

        /**
         * [following] with known activity first, most recently active first, then the rest in
         * RetroAchievements' order.
         */
        internal fun ordered(following: List<RaFollowed>, activity: Map<String, RaFriendActivity>): List<RaFollowed> {
            val (known, unknown) = following.partition { !activity[it.key]?.lastActive.isNullOrEmpty() }
            return known.sortedByDescending { activity[it.key]?.lastActive } + unknown
        }

        /**
         * Who one refresh asks about: the [RECENT_SLOTS] most recently active, then the others
         * least recently fetched first (never fetched before anyone), [MAX_ACTIVITY] in all, minus
         * those fetched in the last [ACTIVITY_FRESH_MS].
         */
        internal fun pick(following: List<RaFollowed>, activity: Map<String, RaFriendActivity>, now: Long): List<RaFollowed> {
            val recent = ordered(following, activity).filter { !activity[it.key]?.lastActive.isNullOrEmpty() }.take(RECENT_SLOTS)
            val chosen = recent.mapTo(HashSet()) { it.key }
            val rotation = following.filter { it.key !in chosen }.sortedBy { activity[it.key]?.fetchedAtMs ?: 0L }
            return (recent + rotation).take(MAX_ACTIVITY)
                .filter { f -> activity[f.key]?.let { now - it.fetchedAtMs >= ACTIVITY_FRESH_MS } ?: true }
        }

        internal fun signatures(c: FriendsCacheFile): Map<String, String> =
            c.activity.filterKeys { k -> c.following.any { it.key == k } }.mapValues { it.value.signature }

        /**
         * New since the stack was opened: the game or the last activity differs from what it showed,
         * or, for a friend it never showed, they were active after it was opened ([since], UTC text).
         */
        internal fun isNew(key: String, a: RaFriendActivity, seen: Map<String, String>, since: String): Boolean {
            if (a.lastActive.isEmpty()) return false
            val before = seen[key] ?: return a.lastActive > since
            return before != a.signature
        }

        /** Epoch ms → "yyyy-MM-dd HH:mm:ss" in UTC, RetroAchievements' format. */
        internal fun utc(ms: Long): String =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))
    }
}
