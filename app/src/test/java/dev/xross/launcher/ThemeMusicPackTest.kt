package dev.xross.launcher

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.xross.launcher.theme.pack.BgmRef
import dev.xross.launcher.theme.pack.LoadedTheme
import dev.xross.launcher.theme.pack.ThemeManifest
import dev.xross.launcher.theme.pack.ThemePackLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * A pack's theme music through the cascade (2b.5c): `bgm.ogg` / `bgm.mp3` at the root,
 * `audio.bgmFile` (alias `bgm`), `none`, and variants, category overrides and rules picking
 * another loop, like the wallpaper.
 */
@RunWith(RobolectricTestRunner::class)
class ThemeMusicPackTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule val tmp = TemporaryFolder()

    private val themes get() = File(tmp.root, "Xross/themes").apply { mkdirs() }
    private val loader by lazy { ThemePackLoader(context.assets) { themes } }

    private fun pack(id: String, manifest: String, files: Map<String, Int> = emptyMap()): LoadedTheme {
        val dir = File(themes, id).apply { mkdirs() }
        File(dir, "theme.json").writeText(manifest.replaceFirst("{", """{"schemaVersion": 2, """))
        for ((name, size) in files) File(dir, name).apply { parentFile!!.mkdirs() }.writeBytes(ByteArray(size))
        return loader.load(id)
    }

    private fun LoadedTheme.track(variant: String = "", category: String? = null): String? =
        bgmTrack(look(variant, category).bgm)?.name

    @Test fun theDefaultPackHasNoMusic() {
        val theme = loader.load(ThemePackLoader.DEFAULT_ID)
        assertFalse(theme.hasBgm)
        assertEquals(BgmRef.NONE, theme.look().bgm)
        assertNull(theme.track())
    }

    @Test fun aBgmFileAtTheRootPlays() {
        val ogg = pack("ogg", """{"name": "Ogg"}""", mapOf("bgm.ogg" to 100))
        assertTrue(ogg.hasBgm)
        assertEquals("bgm.ogg", ogg.track())
        assertEquals("bgm.mp3", pack("mp3", """{"name": "Mp3"}""", mapOf("BGM.MP3" to 100)).track()?.lowercase())
        // Without one, nothing.
        assertNull(pack("quiet", """{"name": "Quiet"}""").track())
    }

    @Test fun audioBgmFileAndItsAliasNameAnotherFile() {
        val named = pack("named", """{"name": "Named", "audio": {"bgmFile": "audio/loop.m4a"}}""", mapOf("audio/loop.m4a" to 100, "bgm.ogg" to 100))
        assertEquals("loop.m4a", named.track())
        assertTrue(named.problems.toString(), named.problems.isEmpty())
        val alias = pack("alias", """{"name": "Alias", "audio": {"bgm": "music.ogg"}}""", mapOf("music.ogg" to 100))
        assertEquals("music.ogg", alias.track())
        assertTrue(alias.problems.toString(), alias.problems.isEmpty())
    }

    @Test fun noneTurnsThePacksLoopOff() {
        val theme = pack("off", """{"name": "Off", "audio": {"bgmFile": "none"}}""", mapOf("bgm.ogg" to 100))
        assertNull(theme.track())
        assertFalse(theme.hasBgm)
    }

    @Test fun aCategoryOverrideGivesGamesItsOwnLoopAndAVariantCanSilenceIt() {
        val theme = pack(
            "layers",
            """{"name": "Layers",
                "variants": [{"id": "quiet", "audio": {"bgmFile": "none"}}],
                "categoryOverrides": {"GAMES": {"audio": {"bgmFile": "games.ogg"}}, "music": {"audio": {"bgm": "off"}}},
                "rules": [{"match": {"category": "VIDEO"}, "apply": {"audio": {"bgmFile": "night.mp3"}}}]}""",
            mapOf("bgm.ogg" to 100, "games.ogg" to 100, "night.mp3" to 100),
        )
        assertTrue(theme.problems.toString(), theme.problems.isEmpty())
        assertEquals("bgm.ogg", theme.track())
        assertEquals("games.ogg", theme.track(category = "games"))
        assertNull(theme.track(category = "music"))
        assertEquals("night.mp3", theme.track(category = "video"))
        assertNull("the variant turns the base off", theme.track("quiet"))
        assertEquals("an override still wins over the variant", "games.ogg", theme.track("quiet", "games"))
    }

    @Test fun missingOversizedAndForeignFilesAreReported() {
        val theme = pack(
            "bad",
            """{"name": "Bad", "audio": {"bgmFile": "gone.ogg"},
                "categoryOverrides": {"GAMES": {"audio": {"bgmFile": "../escape.ogg"}}, "VIDEO": {"audio": {"bgmFile": "art.png"}}},
                "variants": [{"id": "big", "audio": {"bgmFile": "big.ogg"}}]}""",
            mapOf("big.ogg" to (BgmRef.MAX_BYTES + 1).toInt(), "art.png" to 10),
        )
        val problems = theme.problems.joinToString("\n")
        assertTrue(problems, problems.contains("Theme music gone.ogg not found in the pack"))
        assertTrue(problems, problems.contains("Theme music big.ogg is larger than 20 MB"))
        assertTrue(problems, problems.contains("categoryOverrides.GAMES.audio.bgmFile: ../escape.ogg must stay inside the pack"))
        assertTrue(problems, problems.contains("categoryOverrides.VIDEO.audio.bgmFile: art.png isn't an audio file"))
        assertFalse(theme.hasBgm)
        assertNull(theme.track("big"))
    }

    @Test fun patchesMayChangeTheLoopWithoutAProblem() {
        val theme = pack("patch", """{"name": "Patch", "variants": [{"id": "v", "audio": {"bgmFile": "v.ogg", "soundGains": {"cursor": 0.5}}}]}""", mapOf("v.ogg" to 10))
        assertTrue(theme.problems.toString(), theme.problems.isEmpty())
        assertEquals("v.ogg", theme.track("v"))
        assertNull(theme.track())
    }

    @Test fun bgmKeysAreAudioProperties() {
        assertTrue("bgmFile" in ThemeManifest.Audio.KEYS)
        assertTrue("bgm" in ThemeManifest.Audio.KEYS)
        val theme = pack("typo", """{"name": "Typo", "audio": {"bgmFiel": "x.ogg"}}""")
        assertTrue(theme.problems.toString(), theme.problems.any { "audio.bgmFiel isn't an audio property" in it })
    }

    @Test fun anEditedLoopIsANewTrack() {
        val theme = pack("edit", """{"name": "Edit"}""", mapOf("bgm.ogg" to 100))
        val before = theme.bgmTrack(theme.look().bgm)!!
        File(themes, "edit/bgm.ogg").writeBytes(ByteArray(200))
        val after = loader.load("edit").let { it.bgmTrack(it.look().bgm)!! }
        assertTrue(before != after)
    }
}
