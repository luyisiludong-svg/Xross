package dev.xross.launcher

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.xross.launcher.data.CredentialStore
import dev.xross.launcher.data.GameEntity
import dev.xross.launcher.profile.AchievementsRepository
import dev.xross.launcher.profile.AchievementsState
import dev.xross.launcher.profile.ProfileRepository
import dev.xross.launcher.profile.RaResult
import dev.xross.launcher.profile.RetroAchievementsClient
import dev.xross.launcher.scrape.UrlConnectionHttp
import dev.xross.launcher.storage.XrossEnvironment
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.net.URLEncoder

/**
 * Real calls to RetroAchievements with the account in `.env`. Opt-in and skipped by default:
 *
 *     ./gradlew :app:testDebugUnitTest --tests '*RetroAchievementsLiveTest*' -PliveScrape=true
 *
 *  - `RETROACHIEVEMENT_USER` + `RETROACHIEVEMENT_USER_PASSWORD`: checks the account signs in
 *    (the login emulators use, sent as a POST body; the session token is never printed or kept).
 *  - `RETROACHIEVEMENTS_WEB_API_KEY` (retroachievements.org ▸ Settings ▸ Keys): links the account
 *    the way the app does, fetches the profile and avatar into a scratch Xross folder, resolves an
 *    arcade sample from `localTestMedia/Roms/ARCADE` to its RetroAchievements game, loads its
 *    achievements and badges, and checks the key is in no file the app wrote.
 */
@RunWith(RobolectricTestRunner::class)
class RetroAchievementsLiveTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val root = File(System.getProperty("user.dir")).let { if (File(it, "app").isDirectory) it else it.parentFile }

    @get:Rule val tmp = TemporaryFolder()

    private fun env(): Map<String, String> = File(root, ".env").takeIf { it.isFile }?.readLines().orEmpty()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && it.indexOf('=') > 0 }
        .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim().removeSurrounding("\"") }

    private fun live() = assumeTrue("pass -PliveScrape=true to run", System.getProperty("xross.liveScrape") == "true")

    @Test fun accountSignsIn() = runBlocking {
        live()
        val env = env()
        val user = env["RETROACHIEVEMENT_USER"] ?: env["RETROACHIEVEMENTS_USER"]
        val password = env["RETROACHIEVEMENT_USER_PASSWORD"] ?: env["RETROACHIEVEMENTS_PASSWORD"]
        assumeTrue("RETROACHIEVEMENT_USER / _PASSWORD missing from .env", !user.isNullOrBlank() && !password.isNullOrBlank())
        val body = "r=login2&u=${URLEncoder.encode(user, "UTF-8")}&p=${URLEncoder.encode(password, "UTF-8")}"
        val response = UrlConnectionHttp("Xross/live-test").post(
            "https://retroachievements.org/dorequest.php",
            body.toByteArray(),
            "application/x-www-form-urlencoded",
        )
        val json = runCatching { Json.parseToJsonElement(response.text()) as JsonObject }.getOrNull()
        val ok = (json?.get("Success") as? JsonPrimitive)?.booleanOrNull == true
        val name = (json?.get("User") as? JsonPrimitive)?.contentOrNull
        val score = (json?.get("Score") as? JsonPrimitive)?.contentOrNull
        val error = (json?.get("Error") as? JsonPrimitive)?.contentOrNull
        println("RetroAchievements login: HTTP ${response.code}, success=$ok, user=$name, hardcore points=$score" + (error?.let { ", error=$it" } ?: ""))
        assertTrue("RetroAchievements refused the .env account: ${error ?: "HTTP " + response.code}", ok)
        assertTrue(name.equals(user, ignoreCase = true))
    }

    @Test fun webApiKeyLinksTheProfileAndLoadsAGamesAchievements() = runBlocking {
        live()
        val env = env()
        val user = env["RETROACHIEVEMENT_USER"] ?: env["RETROACHIEVEMENTS_USER"]
        val key = env["RETROACHIEVEMENTS_WEB_API_KEY"] ?: env["RETROACHIEVEMENT_WEB_API_KEY"]
        assumeTrue("RETROACHIEVEMENTS_WEB_API_KEY missing from .env", !user.isNullOrBlank() && !key.isNullOrBlank())
        val environment = XrossEnvironment(context, writeAccess = { true }, primaryRoot = { File(tmp.root, "Xross") })
        val credentials = CredentialStore(context)
        val http = UrlConnectionHttp("Xross/live-test")
        val profiles = ProfileRepository(RetroAchievementsClient(http), credentials, { environment.current })

        val linked = profiles.link(user!!, key!!)
        println("Link: ${if (linked is RaResult.Ok) "ok as ${linked.profile.username}, ${linked.profile.totalPoints} points, rank ${linked.profile.rank}" else linked}")
        assertTrue(linked is RaResult.Ok)
        assertTrue("avatar cached", profiles.current.avatar?.isFile == true)

        val achievements = AchievementsRepository(RetroAchievementsClient(http), credentials, { environment.current }, hashes = { null })
        val sample = File(root, "localTestMedia/Roms/ARCADE").listFiles().orEmpty().filter { it.isFile }.minByOrNull { it.name }
        if (sample != null) {
            val game = GameEntity(path = sample.path, platformId = "arcade", title = sample.nameWithoutExtension, fileName = sample.name, sizeBytes = sample.length(), lastModified = sample.lastModified())
            when (val state = achievements.load(game)) {
                is AchievementsState.Ready -> {
                    val p = state.progress
                    println("${sample.name} → #${p.gameId} ${p.title}: ${p.unlocked}/${p.total} unlocked, ${p.earnedPoints}/${p.totalPoints} points (${state.matchedBy})")
                    p.achievements.firstOrNull()?.let { a ->
                        val badge = achievements.badgeFile(a.badgeName)
                        println("  first badge ${a.title}: ${badge?.relativeTo(environment.current.root)} ${badge?.length()} bytes")
                        assertTrue(badge?.isFile == true)
                    }
                }
                else -> println("${sample.name}: $state")
            }
        }
        environment.current.root.walkTopDown().filter { it.isFile }.forEach { f ->
            assertFalse("key leaked into ${f.name}", f.readBytes().toString(Charsets.ISO_8859_1).contains(key))
        }
        assertEquals(key, credentials.current.retroAchievementsKey)
    }
}
