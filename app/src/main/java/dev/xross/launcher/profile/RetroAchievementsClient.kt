package dev.xross.launcher.profile

import dev.xross.launcher.scrape.HttpClient
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URLEncoder

/** One recently unlocked achievement. Public data. */
@Serializable
data class RaBadge(
    val title: String,
    val gameTitle: String = "",
    val points: Int = 0,
    /** Absolute HTTPS URL of the badge image. */
    val badgeUrl: String = "",
    /** "2023-12-27 16:04:50", as RetroAchievements reports it (UTC). */
    val date: String = "",
    val hardcore: Boolean = false,
    /** What the achievement asks for; empty in profiles cached before this was kept. */
    val description: String = "",
)

/**
 * The public part of a RetroAchievements profile: what the status bar and the User Card show,
 * and what is cached in the Xross folder. Never holds the Web API key.
 */
@Serializable
data class RaProfile(
    /** The user name as RetroAchievements spells it (it may differ in case from what was typed). */
    val username: String,
    /** Hardcore points. */
    val totalPoints: Long = 0,
    val softcorePoints: Long = 0,
    /** RetroPoints ("true points"): points weighted by how rare each unlock is. */
    val truePoints: Long = 0,
    /** RetroRatio: [truePoints] / [totalPoints]; null without hardcore points. */
    val retroRatio: Double? = null,
    /** Site rank by points; null when unranked (untracked accounts, or the rank call failed). */
    val rank: Int? = null,
    val totalRanked: Int? = null,
    /** Absolute HTTPS URL of the avatar. */
    val avatarUrl: String = "",
    val memberSince: String? = null,
    val motto: String? = null,
    val recentBadges: List<RaBadge> = emptyList(),
    /** When this was fetched (epoch ms). */
    val fetchedAtMs: Long = 0,
)

sealed interface RaResult {
    /**
     * [recentUnlocks] is every unlock of the last [RetroAchievementsClient.RECENT_MINUTES], newest
     * first; [RaProfile.recentBadges] keeps only the first few for the User Card.
     */
    data class Ok(val profile: RaProfile, val recentUnlocks: List<RaBadge> = profile.recentBadges) : RaResult
    /** The Web API key was refused. */
    data object InvalidCredentials : RaResult
    data object UserNotFound : RaResult
    /**
     * Network trouble or an unexpected answer. [reason] is safe to show (no URL, no key). [busy]:
     * RetroAchievements answered 429, so further calls now would be refused too.
     */
    data class Failed(val reason: String, val busy: Boolean = false) : RaResult
}

/** One user the linked account follows (`API_GetUsersIFollow`). Public data. */
@Serializable
data class RaFollowed(
    /** The user name as RetroAchievements spells it today (names can change since 2025). */
    val user: String,
    /** The stable id; empty if RetroAchievements didn't send one. */
    val ulid: String = "",
    /** Hardcore points. */
    val points: Long = 0,
    val softcorePoints: Long = 0,
    /** They follow the linked account too. */
    val followsBack: Boolean = false,
) {
    /** What the friend is known by in the cache: the ULID, else the name in lower case. */
    val key: String get() = ulid.ifEmpty { user.lowercase() }
}

/**
 * What a followed user did last (`API_GetUserSummary` with one recent game). Public data. Dates
 * are "yyyy-MM-dd HH:mm:ss" in UTC, as RetroAchievements sends them; empty when unknown.
 */
@Serializable
data class RaFriendActivity(
    val user: String,
    val ulid: String = "",
    /** Absolute HTTPS URL of the avatar. */
    val avatarUrl: String = "",
    val points: Long = 0,
    /** The RetroAchievements game played last; 0 when they never played one. */
    val gameId: Int = 0,
    val gameTitle: String = "",
    val consoleName: String = "",
    /** Absolute HTTPS URL of the game's icon. */
    val gameIconUrl: String = "",
    /** When they last played [gameId]. */
    val lastPlayed: String = "",
    /** Their last rich presence line ("Exploring Clay's house | ♥ 100 / 100"). */
    val richPresence: String = "",
    /** When the rich presence last changed: the latest sign of life RetroAchievements gives. */
    val lastSeen: String = "",
    /** RetroAchievements' own "Online" status at fetch time. */
    val online: Boolean = false,
    /** When this was fetched (epoch ms). */
    val fetchedAtMs: Long = 0,
) {
    /** The later of [lastPlayed] and [lastSeen] (text order is time order); empty when neither is known. */
    val lastActive: String get() = maxOf(lastPlayed, lastSeen)

    /** What [FriendsRepository] compares to tell new activity: the game and when they were last active. */
    val signature: String get() = "$gameId@$lastActive"
}

sealed interface RaFollowingResult {
    /** [users] in RetroAchievements' order, at most [RetroAchievementsClient.MAX_FOLLOWING]; [total] is how many the account follows. */
    data class Ok(val users: List<RaFollowed>, val total: Int) : RaFollowingResult
    data class Error(val result: RaResult) : RaFollowingResult
}

sealed interface RaFriendResult {
    data class Ok(val activity: RaFriendActivity) : RaFriendResult
    data class Error(val result: RaResult) : RaFriendResult
}

/**
 * Minimal client for the RetroAchievements Web API (https://api-docs.retroachievements.org).
 *
 * Every request authenticates with the Web API key in the `y` query parameter, so request URLs
 * are secrets: they are never logged or put in an error message (the [HttpClient] contract
 * already guarantees that for its own exceptions), and [RaResult.Failed] reasons are fixed text.
 *
 * A profile is one required call ([PROFILE]) plus two optional ones that fill in the rank
 * ([SUMMARY]) and recent unlocks ([RECENT]); if those fail the profile still comes back.
 */
class RetroAchievementsClient(
    private val http: HttpClient,
    private val apiBase: String = API_BASE,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun fetchProfile(user: String, apiKey: String): RaResult {
        val name = user.trim()
        if (name.isEmpty() || apiKey.isBlank()) return RaResult.InvalidCredentials
        val profileJson = when (val r = getJson(PROFILE, name, apiKey.trim())) {
            is Answer.Json -> r.element
            is Answer.Error -> return r.result
        }
        val base = parseProfile(profileJson) ?: return RaResult.UserNotFound
        return coroutineScope {
            // Look the account up by the canonical name from here on.
            val summary = async { getJson(SUMMARY, base.username, apiKey.trim(), "&g=0&a=0") }
            val recent = async { getJson(RECENT, base.username, apiKey.trim(), "&m=$RECENT_MINUTES") }
            val rank = (summary.await() as? Answer.Json)?.element?.let(::parseRank)
            val badges = (recent.await() as? Answer.Json)?.element?.let(::parseRecent).orEmpty()
            RaResult.Ok(
                base.copy(
                    rank = rank?.first,
                    totalRanked = rank?.second,
                    recentBadges = badges.take(MAX_BADGES),
                    fetchedAtMs = clock(),
                ),
                recentUnlocks = badges,
            )
        }
    }

    /**
     * Downloads [url] (an avatar or badge from the RetroAchievements media host) into [dest].
     * Returns false on any failure; [dest] may then hold a partial file the caller discards.
     */
    suspend fun downloadImage(url: String, dest: File): Boolean {
        if (!isRetroAchievementsMedia(url)) return false
        return try {
            val result = http.download(url, dest, MAX_IMAGE_BYTES)
            result.code in 200..299 && result.bytes > 0 &&
                (result.contentType == null || result.contentType.startsWith("image/"))
        } catch (_: IOException) {
            false
        }
    }

    /**
     * The user's progress in one game: every achievement (badge, title, description, points,
     * unlock dates) from `API_GetGameInfoAndUserProgress`.
     */
    suspend fun fetchGameProgress(user: String, apiKey: String, gameId: Int): RaGameResult {
        val name = user.trim()
        if (name.isEmpty() || apiKey.isBlank()) return RaGameResult.Error(RaResult.InvalidCredentials)
        if (gameId <= 0) return RaGameResult.Error(RaResult.Failed("Not a RetroAchievements game"))
        return when (val r = getJson(GAME_PROGRESS, name, apiKey.trim(), "&g=$gameId&a=1")) {
            is Answer.Error -> RaGameResult.Error(r.result)
            is Answer.Json -> parseGameProgress(r.element, clock())
                ?.let { RaGameResult.Ok(it) }
                ?: RaGameResult.Error(RaResult.Failed("RetroAchievements has no game #$gameId"))
        }
    }

    /**
     * Every game of one console, with the ROM hashes RetroAchievements recognizes for it
     * (`API_GetGameList`, `h=1`). Games without an achievement set are included, so a recognized
     * game can say "no achievements yet" instead of "not recognized". Large for the big consoles,
     * so callers cache it ([AchievementsRepository]).
     */
    suspend fun fetchGameList(apiKey: String, consoleId: Int): RaListResult {
        if (apiKey.isBlank()) return RaListResult.Error(RaResult.InvalidCredentials)
        val url = "$apiBase/$GAME_LIST?i=$consoleId&h=1&y=${enc(apiKey.trim())}"
        return when (val r = getUrl(url, MAX_LIST_BYTES)) {
            is Answer.Error -> RaListResult.Error(r.result)
            is Answer.Json -> RaListResult.Ok(parseGameList(r.element))
        }
    }

    /**
     * The users the key's account follows (`API_GetUsersIFollow`; the endpoint has no user
     * parameter: it always answers for the key). Pages until RetroAchievements has no more or
     * [MAX_FOLLOWING] are read; RetroAchievements allows up to 500 per page, so that is one call.
     */
    suspend fun fetchFollowing(apiKey: String): RaFollowingResult {
        if (apiKey.isBlank()) return RaFollowingResult.Error(RaResult.InvalidCredentials)
        val users = ArrayList<RaFollowed>()
        var total = 0
        var page = 0
        while (users.size < MAX_FOLLOWING && page < MAX_FOLLOWING_PAGES) {
            val want = MAX_FOLLOWING - users.size
            val url = "$apiBase/$FOLLOWING?c=$want&o=${users.size}&y=${enc(apiKey.trim())}"
            val element = when (val r = getUrl(url, MAX_JSON_BYTES)) {
                is Answer.Error -> return if (page == 0) RaFollowingResult.Error(r.result) else RaFollowingResult.Ok(users, total)
                is Answer.Json -> r.element
            }
            val (pageTotal, entries, served) = parseFollowing(element) ?: return if (page == 0) {
                RaFollowingResult.Error(RaResult.Failed("RetroAchievements sent an unreadable answer"))
            } else {
                RaFollowingResult.Ok(users, total)
            }
            total = pageTotal
            val known = users.mapTo(HashSet()) { it.key }
            users += entries.filter { known.add(it.key) }
            page++
            // An empty page or the end of the list: nothing more to ask for. (A short page alone isn't
            // the end: the server may serve fewer per page than asked.)
            if (served == 0 || users.size >= total) break
        }
        return RaFollowingResult.Ok(users.take(MAX_FOLLOWING), maxOf(total, users.size))
    }

    /**
     * What [friend] did last: their latest game and when (`API_GetUserSummary`, `g=1&a=1`, the
     * smallest answer that carries it). Asked by ULID when known, so a renamed friend still answers.
     */
    suspend fun fetchFriendActivity(apiKey: String, friend: RaFollowed): RaFriendResult {
        if (apiKey.isBlank()) return RaFriendResult.Error(RaResult.InvalidCredentials)
        val who = friend.ulid.ifEmpty { friend.user }.trim()
        // RetroAchievements answers 422 for anything but letters and digits; don't ask.
        if (who.isEmpty() || !who.all { it.isLetterOrDigit() }) return RaFriendResult.Error(RaResult.UserNotFound)
        return when (val r = getJson(SUMMARY, who, apiKey.trim(), "&g=1&a=1")) {
            is Answer.Error -> RaFriendResult.Error(r.result)
            is Answer.Json -> parseFriendActivity(r.element, clock())
                ?.let { RaFriendResult.Ok(it) }
                ?: RaFriendResult.Error(RaResult.UserNotFound)
        }
    }

    private sealed interface Answer {
        class Json(val element: JsonElement) : Answer
        class Error(val result: RaResult) : Answer
    }

    private suspend fun getJson(endpoint: String, user: String, key: String, extra: String = ""): Answer =
        getUrl("$apiBase/$endpoint?u=${enc(user)}&y=${enc(key)}$extra", MAX_JSON_BYTES)

    private suspend fun getUrl(url: String, maxBytes: Long): Answer {
        val response = try {
            http.get(url, maxBytes = maxBytes)
        } catch (_: IOException) {
            return Answer.Error(RaResult.Failed("Couldn't reach RetroAchievements"))
        }
        return when (response.code) {
            in 200..299 -> try {
                // An HTML error page or bare text parses as a primitive: not an answer either.
                when (val element = JSON.parseToJsonElement(response.text())) {
                    is JsonObject, is JsonArray -> Answer.Json(element)
                    else -> Answer.Error(RaResult.Failed("RetroAchievements sent an unreadable answer"))
                }
            } catch (_: SerializationException) {
                Answer.Error(RaResult.Failed("RetroAchievements sent an unreadable answer"))
            } catch (_: IllegalArgumentException) {
                Answer.Error(RaResult.Failed("RetroAchievements sent an unreadable answer"))
            }
            401, 403 -> Answer.Error(RaResult.InvalidCredentials)
            404, 422 -> Answer.Error(RaResult.UserNotFound)
            429 -> Answer.Error(RaResult.Failed("RetroAchievements is busy — try again in a minute", busy = true))
            else -> Answer.Error(RaResult.Failed("RetroAchievements answered ${response.code}"))
        }
    }

    companion object {
        const val API_BASE = "https://retroachievements.org/API"
        const val MEDIA_BASE = "https://media.retroachievements.org"
        const val PROFILE = "API_GetUserProfile.php"
        const val SUMMARY = "API_GetUserSummary.php"
        const val RECENT = "API_GetUserRecentAchievements.php"
        const val GAME_PROGRESS = "API_GetGameInfoAndUserProgress.php"
        const val GAME_LIST = "API_GetGameList.php"
        const val FOLLOWING = "API_GetUsersIFollow.php"

        /** Followed users read per refresh (2b.7c). */
        const val MAX_FOLLOWING = 200
        private const val MAX_FOLLOWING_PAGES = 4

        /** Recent unlocks look back 30 days. */
        const val RECENT_MINUTES = 30 * 24 * 60
        const val MAX_BADGES = 6
        private const val MAX_JSON_BYTES = 512L * 1024
        /** A console's game list with hashes runs to a few MB for NES or Mega Drive. */
        private const val MAX_LIST_BYTES = 12L * 1024 * 1024
        const val MAX_IMAGE_BYTES = 2L * 1024 * 1024

        private val JSON = Json { ignoreUnknownKeys = true }

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        /**
         * `API_GetUserProfile` → profile without rank or badges; null when the answer names no
         * user (RetroAchievements answers an unknown name with an empty object).
         */
        internal fun parseProfile(element: JsonElement): RaProfile? {
            val o = element as? JsonObject ?: return null
            val user = o.str("User")?.takeIf { it.isNotBlank() } ?: return null
            val points = o.long("TotalPoints") ?: 0
            val truePoints = o.long("TotalTruePoints") ?: 0
            return RaProfile(
                username = user,
                totalPoints = points,
                softcorePoints = o.long("TotalSoftcorePoints") ?: 0,
                truePoints = truePoints,
                retroRatio = if (points > 0) truePoints.toDouble() / points else null,
                avatarUrl = absoluteMedia(o.str("UserPic") ?: "/UserPic/$user.png"),
                memberSince = o.str("MemberSince"),
                motto = o.str("Motto")?.takeIf { it.isNotBlank() },
            )
        }

        /** `API_GetUserSummary` → (rank, total ranked); null when unranked. */
        internal fun parseRank(element: JsonElement): Pair<Int, Int?>? {
            val o = element as? JsonObject ?: return null
            val rank = o.long("Rank")?.toInt()?.takeIf { it > 0 } ?: return null
            return rank to o.long("TotalRanked")?.toInt()
        }

        /** `API_GetUserRecentAchievements` → newest first. Malformed items are skipped. */
        internal fun parseRecent(element: JsonElement): List<RaBadge> {
            val items = element as? JsonArray ?: return emptyList()
            return items.mapNotNull { item ->
                val o = item as? JsonObject ?: return@mapNotNull null
                val title = o.str("Title")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val badge = o.str("BadgeURL") ?: o.str("BadgeName")?.let { "/Badge/$it.png" }
                RaBadge(
                    title = title,
                    gameTitle = o.str("GameTitle").orEmpty(),
                    points = o.long("Points")?.toInt() ?: 0,
                    badgeUrl = badge?.let(::absoluteMedia).orEmpty(),
                    date = o.str("Date").orEmpty(),
                    hardcore = (o.long("HardcoreMode") ?: 0) != 0L,
                    description = o.str("Description").orEmpty(),
                )
            }.sortedByDescending { it.date }
        }

        /**
         * `API_GetGameInfoAndUserProgress` → the game and its achievements in display order;
         * null when the answer names no game. `Achievements` is an object keyed by id, or an
         * empty array for a game without any.
         */
        internal fun parseGameProgress(element: JsonElement, fetchedAtMs: Long = 0): RaGameProgress? {
            val o = element as? JsonObject ?: return null
            val id = o.long("ID")?.toInt()?.takeIf { it > 0 } ?: return null
            val list = when (val a = o["Achievements"]) {
                is JsonObject -> a.values.toList()
                is JsonArray -> a.toList()
                else -> emptyList()
            }
            val achievements = list.mapNotNull { item ->
                val x = item as? JsonObject ?: return@mapNotNull null
                val achId = x.long("ID")?.toInt() ?: return@mapNotNull null
                val badge = x.str("BadgeName")?.takeIf { it.isNotBlank() } ?: "00000"
                RaAchievement(
                    id = achId,
                    title = x.str("Title").orEmpty(),
                    description = x.str("Description").orEmpty(),
                    points = x.long("Points")?.toInt() ?: 0,
                    trueRatio = x.long("TrueRatio")?.toInt() ?: 0,
                    badgeName = badge,
                    displayOrder = x.long("DisplayOrder")?.toInt() ?: 0,
                    type = x.str("type")?.takeIf { it.isNotBlank() },
                    unlockedAt = x.str("DateEarned")?.takeIf { it.isNotBlank() },
                    unlockedHardcoreAt = x.str("DateEarnedHardcore")?.takeIf { it.isNotBlank() },
                    earnedBy = x.long("NumAwarded")?.toInt() ?: 0,
                )
            }.sortedWith(compareBy({ it.displayOrder }, { it.id }))
            return RaGameProgress(
                gameId = id,
                title = o.str("Title").orEmpty(),
                consoleName = o.str("ConsoleName").orEmpty(),
                iconUrl = o.str("ImageIcon")?.let(::absoluteMedia).orEmpty(),
                boxArtUrl = o.str("ImageBoxArt")?.let(::absoluteMedia).orEmpty(),
                players = o.long("NumDistinctPlayers")?.toInt() ?: 0,
                achievements = achievements,
                fetchedAtMs = fetchedAtMs,
            )
        }

        /** `API_GetGameList` → games with their hashes. Malformed rows are skipped. */
        internal fun parseGameList(element: JsonElement): List<RaGameEntry> {
            val items = element as? JsonArray ?: return emptyList()
            return items.mapNotNull { item ->
                val o = item as? JsonObject ?: return@mapNotNull null
                val id = o.long("ID")?.toInt()?.takeIf { it > 0 } ?: return@mapNotNull null
                val title = o.str("Title")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val hashes = (o["Hashes"] as? JsonArray).orEmpty().mapNotNull { h ->
                    (h as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()?.takeIf { it.length == 32 }
                }
                RaGameEntry(id, title, o.long("NumAchievements")?.toInt() ?: 0, hashes)
            }
        }

        /**
         * `API_GetUsersIFollow` → (Total, the entries that parse, how many entries the page had);
         * null when the answer isn't that object. Entries without a user name are skipped.
         */
        internal fun parseFollowing(element: JsonElement): Triple<Int, List<RaFollowed>, Int>? {
            val o = element as? JsonObject ?: return null
            val results = o["Results"] as? JsonArray ?: return null
            val users = results.mapNotNull { item ->
                val x = item as? JsonObject ?: return@mapNotNull null
                val user = x.str("User")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                RaFollowed(
                    user = user,
                    ulid = x.str("ULID")?.trim()?.takeIf { u -> u.all { it.isLetterOrDigit() } }.orEmpty(),
                    points = x.long("Points") ?: 0,
                    softcorePoints = x.long("PointsSoftcore") ?: 0,
                    followsBack = x.bool("IsFollowingMe"),
                )
            }
            val total = o.long("Total")?.toInt()?.coerceAtLeast(0) ?: users.size
            return Triple(total, users, results.size)
        }

        /**
         * `API_GetUserSummary` (with `g=1`) → the friend's latest game and when; null when the
         * answer names no user. The latest game comes from `RecentlyPlayed`, else `LastGame`.
         */
        internal fun parseFriendActivity(element: JsonElement, fetchedAtMs: Long = 0): RaFriendActivity? {
            val o = element as? JsonObject ?: return null
            val user = o.str("User")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val recent = (o["RecentlyPlayed"] as? JsonArray)?.firstOrNull() as? JsonObject
            val last = o["LastGame"] as? JsonObject
            val gameId = recent?.long("GameID")?.toInt() ?: last?.long("ID")?.toInt() ?: o.long("LastGameID")?.toInt() ?: 0
            // LastGame describes LastGameID, which may be newer than the recently-played list.
            val game = recent?.takeIf { it.long("GameID")?.toInt() == gameId } ?: last?.takeIf { it.long("ID")?.toInt() == gameId }
            return RaFriendActivity(
                user = user,
                ulid = o.str("ULID")?.trim()?.takeIf { u -> u.all { it.isLetterOrDigit() } }.orEmpty(),
                avatarUrl = absoluteMedia(o.str("UserPic") ?: "/UserPic/$user.png"),
                points = o.long("TotalPoints") ?: 0,
                gameId = gameId.coerceAtLeast(0),
                gameTitle = game?.str("Title").orEmpty(),
                consoleName = game?.str("ConsoleName").orEmpty(),
                gameIconUrl = game?.str("ImageIcon")?.takeIf { it.isNotBlank() }?.let(::absoluteMedia).orEmpty(),
                lastPlayed = recent?.takeIf { it === game }?.str("LastPlayed")?.takeIf(::isDate).orEmpty(),
                richPresence = o.str("RichPresenceMsg")?.trim().orEmpty().take(MAX_PRESENCE),
                lastSeen = o.str("RichPresenceMsgDate")?.takeIf(::isDate).orEmpty(),
                online = o.str("Status").equals("Online", ignoreCase = true),
                fetchedAtMs = fetchedAtMs,
            )
        }

        private const val MAX_PRESENCE = 200
        private val DATE = Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}""")

        /** Only "yyyy-MM-dd HH:mm:ss" dates are kept: they sort as text. */
        private fun isDate(s: String) = DATE.matches(s)

        /** Badge image of an achievement; [locked] is the greyed version RetroAchievements serves. */
        fun badgeUrl(badgeName: String, locked: Boolean = false): String =
            "$MEDIA_BASE/Badge/${badgeName.filter { it.isLetterOrDigit() }}${if (locked) "_lock" else ""}.png"

        /** "/UserPic/x.png" → "https://media.retroachievements.org/UserPic/x.png"; absolute URLs pass. */
        internal fun absoluteMedia(path: String): String = when {
            path.startsWith("https://") -> path
            path.startsWith("http://") -> "https://" + path.removePrefix("http://")
            path.startsWith("/") -> MEDIA_BASE + path
            else -> "$MEDIA_BASE/$path"
        }

        /** Only images on retroachievements.org hosts are fetched, whatever the cache file says. */
        internal fun isRetroAchievementsMedia(url: String): Boolean {
            val uri = runCatching { URI(url) }.getOrNull() ?: return false
            val host = uri.host?.lowercase() ?: return false
            return uri.scheme == "https" && (host == "retroachievements.org" || host.endsWith(".retroachievements.org"))
        }

        private fun JsonObject.str(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.contentOrNull

        /** true / 1 / "true" / "1". */
        private fun JsonObject.bool(key: String): Boolean {
            val p = this[key] as? JsonPrimitive ?: return false
            if (p is kotlinx.serialization.json.JsonNull) return false
            return p.contentOrNull?.trim()?.lowercase() in setOf("true", "1")
        }

        /** Numbers arrive as numbers or numeric strings depending on the endpoint. */
        private fun JsonObject.long(key: String): Long? {
            val p = this[key] as? JsonPrimitive ?: return null
            if (p is kotlinx.serialization.json.JsonNull) return null
            return p.longOrNull ?: p.doubleOrNull?.toLong() ?: p.contentOrNull?.trim()?.toLongOrNull()
        }
    }
}
