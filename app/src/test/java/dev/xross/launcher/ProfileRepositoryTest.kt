package dev.xross.launcher

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.xross.launcher.RetroAchievementsClientTest.Companion.KEY
import dev.xross.launcher.RetroAchievementsClientTest.Companion.PROFILE_JSON
import dev.xross.launcher.RetroAchievementsClientTest.Companion.RECENT_JSON
import dev.xross.launcher.RetroAchievementsClientTest.Companion.SUMMARY_JSON
import dev.xross.launcher.data.CredentialStore
import dev.xross.launcher.data.SettingsStore
import dev.xross.launcher.profile.ProfileRepository
import dev.xross.launcher.profile.RaResult
import dev.xross.launcher.profile.RetroAchievementsClient
import dev.xross.launcher.scrape.DownloadResult
import dev.xross.launcher.scrape.HttpClient
import dev.xross.launcher.storage.PlatformRegistry
import dev.xross.launcher.storage.XrossEnvironment
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Linking, the offline profile cache and the avatar on disk, and where the Web API key may
 * (only [CredentialStore]) and may not (settings.json, anything in the Xross folder) end up.
 */
@RunWith(RobolectricTestRunner::class)
class ProfileRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule val tmp = TemporaryFolder()

    private val environment by lazy {
        XrossEnvironment(context, writeAccess = { true }, primaryRoot = { File(tmp.root, "emulated/0/Xross") })
    }
    private val layout get() = environment.current
    private val credentials by lazy { CredentialStore(context) }

    @After fun resetRegistry() = PlatformRegistry.applyOverrides(emptyMap())

    private val avatarBytes = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3)

    private fun onlineHttp() = FakeHttp(
        listOf(
            RetroAchievementsClient.PROFILE to FakeHttp.json(200, PROFILE_JSON),
            RetroAchievementsClient.SUMMARY to FakeHttp.json(200, SUMMARY_JSON),
            RetroAchievementsClient.RECENT to FakeHttp.json(200, RECENT_JSON),
        ),
    ).apply {
        download = { _, dest -> dest.writeBytes(avatarBytes); DownloadResult(200, avatarBytes.size.toLong(), "image/png") }
    }

    private fun repo(http: HttpClient, now: Long = 1_000L) =
        ProfileRepository(RetroAchievementsClient(http, clock = { now }), credentials, { layout }, clock = { now })

    @Test fun linkingKeepsTheKeyInCredentialStoreOnly() = runBlocking {
        val settings = SettingsStore(context, environment)
        val result = repo(onlineHttp()).link("maxmilyin", KEY)
        assertTrue(result is RaResult.Ok)
        // Something else changes settings afterwards, so settings.json is rewritten with the account linked.
        settings.update { it.copy(theme = "CRIMSON") }
        settings.flush()

        assertEquals("MaxMilyin", credentials.current.retroAchievementsUser)
        assertEquals(KEY, credentials.current.retroAchievementsKey)
        assertFalse("toString hides secrets", credentials.current.toString().contains(KEY))

        val settingsJson = layout.settingsFile.readText()
        assertTrue(settingsJson.contains("CRIMSON"))
        assertFalse("key in settings.json", settingsJson.contains(KEY))
        // Nothing anywhere in the portable Xross folder carries the key...
        val leaks = layout.root.walkTopDown().filter { it.isFile && it.readBytes().toString(Charsets.ISO_8859_1).contains(KEY) }.toList()
        assertEquals(emptyList<File>(), leaks)
        // ...while the public profile cache is there.
        val cache = layout.profileCache.readText()
        assertTrue(cache.contains("\"username\": \"MaxMilyin\""))
        assertTrue(cache.contains("media/avatars/MaxMilyin.png"))
        assertTrue(cache.contains("399597"))
        // Settings preferences of earlier versions never see it either.
        val legacy = context.getSharedPreferences(SettingsStore.LEGACY_PREFS, Context.MODE_PRIVATE).all.values
        assertFalse(legacy.any { it.toString().contains(KEY) })
    }

    @Test fun avatarIsCachedToDiskUnderMediaAvatars() = runBlocking {
        val repository = repo(onlineHttp())
        repository.link("MaxMilyin", KEY)
        val file = File(layout.media, "avatars/MaxMilyin.png")
        assertTrue(file.isFile)
        assertArrayEquals(avatarBytes, file.readBytes())
        assertEquals(file, repository.current.avatar)
        assertFalse("temp file cleaned up", File(file.parentFile, "MaxMilyin.png.part").exists())
        assertEquals(4616, repository.current.profile?.rank)
    }

    @Test fun offlineStartShowsTheCachedProfileAndAvatar() = runBlocking {
        repo(onlineHttp()).link("MaxMilyin", KEY)

        // Next start, no network: everything comes from the Xross folder without a request.
        val offline = OfflineHttp()
        val restarted = repo(offline)
        restarted.load()
        val state = restarted.current
        assertTrue(state.linked)
        assertEquals("MaxMilyin", state.profile?.username)
        assertEquals(399597L, state.profile?.totalPoints)
        assertEquals(File(layout.avatars, "MaxMilyin.png"), state.avatar)
        assertEquals(0, offline.calls)

        // A refresh that can't reach the network fails softly and keeps the cache.
        val refresh = restarted.refresh()
        assertTrue(refresh is RaResult.Failed)
        assertEquals(399597L, restarted.current.profile?.totalPoints)
        assertTrue(File(layout.avatars, "MaxMilyin.png").isFile)
        assertFalse(restarted.current.refreshing)
    }

    @Test fun failedAvatarDownloadFallsBackToThePlaceholderButKeepsAnOldAvatar() = runBlocking {
        // First link: the image host is down, so there is no avatar (the UI draws the silhouette).
        val noImages = onlineHttp().apply { download = { _, _ -> DownloadResult(503, 0, null) } }
        val repository = repo(noImages)
        assertTrue(repository.link("MaxMilyin", KEY) is RaResult.Ok)
        assertNull(repository.current.avatar)
        assertFalse(layout.profileCache.readText().contains("media/avatars"))

        // With an avatar cached, a later failed download leaves it in place.
        repo(onlineHttp()).link("MaxMilyin", KEY)
        val again = repo(noImages)
        again.load()
        assertTrue(again.refresh() is RaResult.Ok)
        assertNotNull(again.current.avatar)
        assertArrayEquals(avatarBytes, File(layout.avatars, "MaxMilyin.png").readBytes())
    }

    @Test fun refusedKeyLeavesTheLinkedAccountAlone() = runBlocking {
        repo(onlineHttp()).link("MaxMilyin", KEY)
        val refused = FakeHttp(listOf(RetroAchievementsClient.PROFILE to FakeHttp.json(401, "{}")))
        assertEquals(RaResult.InvalidCredentials, repo(refused).link("Someone", "wrong-key"))
        assertEquals("MaxMilyin", credentials.current.retroAchievementsUser)
        assertEquals(KEY, credentials.current.retroAchievementsKey)
    }

    @Test fun unlinkForgetsKeyCacheAndAvatar() = runBlocking {
        val repository = repo(onlineHttp())
        repository.link("MaxMilyin", KEY)
        repository.unlink()
        assertFalse(credentials.current.retroAchievementsLinked)
        assertEquals("", credentials.current.retroAchievementsKey)
        assertFalse(layout.profileCache.exists())
        assertFalse(File(layout.avatars, "MaxMilyin.png").exists())
        assertFalse(repository.current.linked)
        assertNull(repository.current.avatar)
    }

    @Test fun staleness() = runBlocking {
        val repository = repo(onlineHttp(), now = 1_000L)
        assertFalse("nothing linked, nothing to refresh", repository.isStale())
        repository.link("MaxMilyin", KEY)
        assertFalse(repository.isStale())
        val later = repo(OfflineHttp(), now = 1_000L + ProfileRepository.STALE_MS + 1)
        later.load()
        assertTrue(later.isStale())
    }

    @Test fun avatarFileNamesCannotEscapeTheFolder() {
        assertEquals("MaxMilyin", ProfileRepository.safeName("MaxMilyin"))
        assertEquals("_.._x", ProfileRepository.safeName("/../x"))
        assertEquals("_", ProfileRepository.safeName(".."))
    }
}
