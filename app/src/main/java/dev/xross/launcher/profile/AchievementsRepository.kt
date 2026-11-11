package dev.xross.launcher.profile

import dev.xross.launcher.config.AtomicFiles
import dev.xross.launcher.data.CredentialStore
import dev.xross.launcher.data.GameEntity
import dev.xross.launcher.data.GameHashEntity
import dev.xross.launcher.storage.XrossLayout
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** How a ROM was tied to its RetroAchievements game. */
enum class RaMatchKind { HASH, SET_NAME, TITLE }

/** What the Details screen and the Achievements screen show for one game. */
sealed interface AchievementsState {
    /** No RetroAchievements account linked. */
    data object NotLinked : AchievementsState

    /** RetroAchievements has no achievements for this system. */
    data class Unsupported(val platformId: String) : AchievementsState

    /** No RetroAchievements game matches this ROM. */
    data object NoMatch : AchievementsState

    data class Ready(
        val progress: RaGameProgress,
        val matchedBy: RaMatchKind,
        /** Served from the Xross folder because the network failed. */
        val offline: Boolean = false,
    ) : AchievementsState

    /** [reason] is safe to show: fixed text, never a URL or key. */
    data class Failed(val reason: String) : AchievementsState
}

/**
 * Pure matching of a ROM against a console's RetroAchievements game list, following how
 * rcheevos hashes each system ([RaConsoles.HashMethod]): the full-file MD5, the header-less MD5
 * for NES/SNES/PC Engine, the set name for arcade, and the title for disc systems (whose
 * RetroAchievements hash reads sectors Xross doesn't fingerprint). A hash match always wins over
 * a title match.
 */
object RaMatcher {
    fun candidates(fileName: String, hashes: GameHashEntity?, method: RaConsoles.HashMethod): Set<String> = buildSet {
        when (method) {
            RaConsoles.HashMethod.WHOLE_FILE -> hashes?.md5?.let(::add)
            RaConsoles.HashMethod.HEADERLESS -> {
                hashes?.dataMd5?.let(::add)
                hashes?.md5?.let(::add)
            }
            RaConsoles.HashMethod.ARCADE_SET_NAME -> add(md5(fileName.substringBeforeLast('.')))
            RaConsoles.HashMethod.TITLE_ONLY -> Unit
        }
    }.mapTo(LinkedHashSet()) { it.lowercase(Locale.ROOT) }

    fun match(
        title: String,
        fileName: String,
        hashes: GameHashEntity?,
        console: RaConsoles.Console,
        games: List<RaGameEntry>,
    ): Pair<RaGameEntry, RaMatchKind>? {
        val wanted = candidates(fileName, hashes, console.method)
        if (wanted.isNotEmpty()) {
            val hit = games.firstOrNull { g -> g.hashes.any { it in wanted } }
            val kind = if (console.method == RaConsoles.HashMethod.ARCADE_SET_NAME) RaMatchKind.SET_NAME else RaMatchKind.HASH
            // A known hash that RetroAchievements doesn't list is a dump its achievements don't
            // support (a bad dump, a hack, another revision): no title guess for those.
            return hit?.let { it to kind }
        }
        // Title fallback, only for disc systems and for ROMs not fingerprinted yet: exact after
        // normalizing; hacks, homebrew and subsets ("~Hack~ …", "… [Subset - …]") never match.
        val key = normalize(title)
        if (key.isEmpty()) return null
        return games.firstOrNull { g -> !g.title.startsWith("~") && !g.title.contains("[Subset") && normalize(g.title) == key }
            ?.let { it to RaMatchKind.TITLE }
    }

    /** Lower-case letters and digits; "The Legend of Zelda: A Link to the Past" ≡ "legend of zelda a link to the past, the". */
    fun normalize(title: String): String {
        var t = title.lowercase(Locale.ROOT).replace('&', ' ').replace(" and ", " ")
        // "Title, The" → "The Title" so both orders meet.
        Regex("^(.*), (the|a|an)$").find(t.trim())?.let { m -> t = "${m.groupValues[2]} ${m.groupValues[1]}" }
        t = t.removePrefix("the ")
        return t.filter { it.isLetterOrDigit() }
    }

    fun md5(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

/**
 * Achievements of the games in the library, from the linked account.
 *
 * - The RetroAchievements game behind a ROM is found once per game ([RaMatcher]) against the
 *   console's game list, cached in `Xross/data/ra/console-<id>.json` for a week (a few MB for
 *   the big consoles, so it isn't fetched per game).
 * - The user's progress is cached per game in `Xross/data/ra/game-<id>.json` and shown offline.
 * - Badge images go to `Xross/media/ra/badges/<badge>.png`, under the media folder's `.nomedia`.
 * - Everything written is public data. The Web API key only travels to RetroAchievements and
 *   stays in [CredentialStore].
 */
class AchievementsRepository(
    private val client: RetroAchievementsClient,
    private val credentials: CredentialStore,
    private val layout: () -> XrossLayout,
    private val hashes: suspend (Long) -> GameHashEntity?,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val consoleLock = Mutex()
    private val memory = ConcurrentHashMap<String, AchievementsState.Ready>()
    private val fetchedAt = ConcurrentHashMap<String, Long>()
    private val badgeLocks = ConcurrentHashMap<String, Mutex>()

    /** A badge grid asks for dozens of images at once; a few downloads at a time is plenty. */
    private val badgeDownloads = kotlinx.coroutines.sync.Semaphore(BADGE_DOWNLOADS)

    /** Memory copy for [game], if loaded this session (instant redraws). */
    fun cached(game: GameEntity): AchievementsState.Ready? = memory[game.uuid]

    /**
     * The state for [game]. Uses this session's copy if it is younger than [maxAgeMs], else
     * fetches; a failed fetch falls back to the copy in the Xross folder.
     */
    suspend fun load(game: GameEntity, maxAgeMs: Long = FRESH_MS): AchievementsState = withContext(io) {
        val account = credentials.current
        if (!account.retroAchievementsLinked) return@withContext AchievementsState.NotLinked
        val console = RaConsoles.of(game.platformId) ?: return@withContext AchievementsState.Unsupported(game.platformId)

        memory[game.uuid]?.let { ready ->
            if (clock() - (fetchedAt[game.uuid] ?: 0L) < maxAgeMs) return@withContext ready
        }

        val (raId, kind) = when (val found = resolve(game, console, account.retroAchievementsKey)) {
            is Resolution.Found -> found.id to found.kind
            Resolution.NoMatch -> return@withContext AchievementsState.NoMatch
            is Resolution.Failed -> return@withContext AchievementsState.Failed(found.reason)
        }

        when (val result = client.fetchGameProgress(account.retroAchievementsUser, account.retroAchievementsKey, raId)) {
            is RaGameResult.Ok -> {
                writeJson(gameFile(raId), JSON.encodeToString(RaGameProgress.serializer(), result.progress))
                AchievementsState.Ready(result.progress, kind).also {
                    memory[game.uuid] = it
                    fetchedAt[game.uuid] = clock()
                }
            }
            is RaGameResult.Error -> {
                val offline = readJson(gameFile(raId))?.let {
                    runCatching { JSON.decodeFromString(RaGameProgress.serializer(), it) }.getOrNull()
                }
                if (offline != null) {
                    AchievementsState.Ready(offline, kind, offline = true).also { memory[game.uuid] = it }
                } else {
                    AchievementsState.Failed(failure(result.error))
                }
            }
        }
    }

    /** Forget this session's copy (after playing, unlocks may have changed). */
    fun invalidate(game: GameEntity) {
        fetchedAt.remove(game.uuid)
    }

    fun invalidateAll() = fetchedAt.clear()

    /** Forget everything cached for the account (unlinking). Blocking I/O. */
    fun clear() {
        memory.clear()
        fetchedAt.clear()
        File(layout().data, RA_DIR).listFiles()?.filter { it.name.startsWith("game-") || it.name == MATCHES }?.forEach { it.delete() }
    }

    /**
     * The badge image for [badgeName], downloading it the first time. Null when it can't be
     * had (offline and never cached).
     */
    suspend fun badgeFile(badgeName: String): File? = withContext(io) {
        val clean = badgeName.filter { it.isLetterOrDigit() }.ifEmpty { return@withContext null }
        val dest = File(File(layout().media, BADGES_DIR), "$clean.png")
        if (dest.isFile && dest.length() > 0) return@withContext dest
        badgeLocks.getOrPut(clean) { Mutex() }.withLock {
            if (dest.isFile && dest.length() > 0) return@withLock dest
            val dir = dest.parentFile ?: return@withLock null
            ensureMediaNomedia()
            if (!dir.isDirectory && !dir.mkdirs()) return@withLock null
            val tmp = File(dir, "$clean.part")
            try {
                val url = RetroAchievementsClient.badgeUrl(clean)
                val ok = badgeDownloads.withPermit {
                    client.downloadImage(url, tmp) || run {
                        // One retry: a busy media host or a dropped connection is usually brief.
                        kotlinx.coroutines.delay(BADGE_RETRY_MS)
                        client.downloadImage(url, tmp)
                    }
                }
                if (!ok) {
                    android.util.Log.w(TAG, "Badge $clean: download refused or failed")
                    return@withLock null
                }
                AtomicFiles.copy(tmp, dest)
                dest
            } catch (e: IOException) {
                android.util.Log.w(TAG, "Badge $clean: ${e.javaClass.simpleName}")
                null
            } finally {
                tmp.delete()
            }
        }
    }

    // region Resolving the RetroAchievements game

    private sealed interface Resolution {
        data class Found(val id: Int, val kind: RaMatchKind) : Resolution
        data object NoMatch : Resolution
        data class Failed(val reason: String) : Resolution
    }

    @Serializable
    private data class MatchRecord(
        val raId: Int,
        val kind: RaMatchKind,
        val size: Long,
        val modified: Long,
        /** [LIST_VERSION] of the game list this was matched against; older records are matched again. */
        val listVersion: Int = 1,
    )

    @Serializable
    private data class ConsoleCache(val version: Int = 1, val consoleId: Int, val fetchedAtMs: Long, val games: List<RaGameEntry>)

    private suspend fun resolve(game: GameEntity, console: RaConsoles.Console, key: String): Resolution {
        val matches = readMatches()
        matches[game.uuid]?.let { rec ->
            // A changed file may be another dump: match it again.
            if (rec.size == game.sizeBytes && rec.modified == game.lastModified && rec.listVersion == LIST_VERSION) {
                return if (rec.raId > 0) Resolution.Found(rec.raId, rec.kind) else Resolution.NoMatch
            }
        }
        val games = when (val list = consoleGames(console.id, key)) {
            is RaListResult.Ok -> list.games
            is RaListResult.Error -> return Resolution.Failed(failure(list.error))
        }
        val hit = RaMatcher.match(game.title, game.fileName, hashes(game.id), console, games)
        val record = MatchRecord(hit?.first?.id ?: 0, hit?.second ?: RaMatchKind.HASH, game.sizeBytes, game.lastModified, LIST_VERSION)
        writeMatches(matches + (game.uuid to record))
        return hit?.let { Resolution.Found(it.first.id, it.second) } ?: Resolution.NoMatch
    }

    /** The console's list from the Xross folder while fresh, else from the network (stale copy as a fallback). */
    private suspend fun consoleGames(consoleId: Int, key: String): RaListResult = consoleLock.withLock {
        val file = consoleFile(consoleId)
        val cached = readJson(file)?.let { runCatching { JSON.decodeFromString(ConsoleCache.serializer(), it) }.getOrNull() }
        if (cached != null && cached.version == LIST_VERSION && clock() - cached.fetchedAtMs < CONSOLE_TTL_MS) {
            return@withLock RaListResult.Ok(cached.games)
        }
        when (val fresh = client.fetchGameList(key, consoleId)) {
            is RaListResult.Ok -> {
                writeJson(file, JSON.encodeToString(ConsoleCache.serializer(), ConsoleCache(LIST_VERSION, consoleId, clock(), fresh.games)))
                fresh
            }
            is RaListResult.Error -> cached?.let { RaListResult.Ok(it.games) } ?: fresh
        }
    }

    private fun readMatches(): Map<String, MatchRecord> =
        readJson(File(raDir(), MATCHES))?.let { runCatching { JSON.decodeFromString(MATCHES_SERIALIZER, it) }.getOrNull() }.orEmpty()

    private fun writeMatches(map: Map<String, MatchRecord>) = writeJson(File(raDir(), MATCHES), JSON.encodeToString(MATCHES_SERIALIZER, map))

    // endregion

    private fun raDir() = File(layout().data, RA_DIR)
    private fun consoleFile(id: Int) = File(raDir(), "console-$id.json")
    private fun gameFile(id: Int) = File(raDir(), "game-$id.json")

    private fun ensureMediaNomedia() {
        val media = layout().media
        if (!media.isDirectory) media.mkdirs()
        val marker = File(media, XrossLayout.NOMEDIA)
        if (!marker.exists()) runCatching { marker.createNewFile() }
    }

    private fun readJson(file: File): String? = try {
        AtomicFiles.readText(file)
    } catch (_: IOException) {
        null
    }

    private fun writeJson(file: File, text: String) {
        try {
            AtomicFiles.writeText(file, text)
        } catch (_: IOException) {
            // Only the offline copy is lost.
        }
    }

    private fun failure(error: RaResult): String = when (error) {
        is RaResult.Ok -> ""
        RaResult.InvalidCredentials -> "RetroAchievements refused the Web API key"
        RaResult.UserNotFound -> "RetroAchievements doesn't know this game"
        is RaResult.Failed -> error.reason
    }

    companion object {
        /** Re-fetch progress when this session's copy is older than this. */
        const val FRESH_MS = 10L * 60 * 1000
        const val CONSOLE_TTL_MS = 7L * 24 * 60 * 60 * 1000
        /** 2: lists include games without achievements (version 1 held only games with a set). */
        const val LIST_VERSION = 2
        const val RA_DIR = "ra"
        private const val TAG = "XrossAchievements"
        private const val BADGE_DOWNLOADS = 4
        private const val BADGE_RETRY_MS = 800L
        const val MATCHES = "matches.json"
        /** Relative to the media folder. */
        const val BADGES_DIR = "ra/badges"

        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val MATCHES_SERIALIZER = MapSerializer(String.serializer(), MatchRecord.serializer())
    }
}
