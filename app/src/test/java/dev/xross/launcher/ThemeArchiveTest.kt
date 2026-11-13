package dev.xross.launcher

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.xross.launcher.theme.pack.ArchiveResult
import dev.xross.launcher.theme.pack.InstallResult
import dev.xross.launcher.theme.pack.StagedTheme
import dev.xross.launcher.theme.pack.ThemeArchive
import dev.xross.launcher.theme.pack.ThemePackLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * `.xtheme` import (Roadmap §4.3, Appendix A.4 and A.13): zip slip, folders and file types,
 * programs, the 50 MB cap, a valid v2 manifest, and installing without silently replacing a pack.
 */
@RunWith(RobolectricTestRunner::class)
class ThemeArchiveTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule val tmp = TemporaryFolder()

    private val xross get() = File(tmp.root, "Xross")
    private val themes get() = File(xross, "themes").apply { mkdirs() }
    private val loader by lazy { ThemePackLoader(context.assets) { themes } }
    private fun archive(maxBytes: Long = ThemeArchive.MAX_BYTES) = ThemeArchive({ themes }, loader::inspect, maxBytes)

    private val manifest = """{"schemaVersion": 2, "name": "Y2K Cyber Dream", "palette": {"canvas": "#1B1829"}}"""

    /** A zip at [name] with [entries] (path → bytes), written as given (no sanitizing). */
    private fun zip(name: String, vararg entries: Pair<String, ByteArray>): File {
        val f = File(tmp.root, name)
        ZipOutputStream(f.outputStream()).use { z ->
            for ((path, data) in entries) {
                z.putNextEntry(ZipEntry(path))
                z.write(data)
                z.closeEntry()
            }
        }
        return f
    }

    private fun text(s: String) = s.toByteArray()

    private fun rejected(r: ArchiveResult): String = (r as? ArchiveResult.Rejected)?.reason ?: run { fail("expected a rejection, got $r"); "" }
    private fun staged(r: ArchiveResult): StagedTheme = (r as? ArchiveResult.Staged)?.theme ?: run { fail("expected a staged pack, got $r"); throw AssertionError() }

    /** Nothing but [expected] in Xross/themes, and nothing at all next to it. */
    private fun assertOnly(vararg expected: String) {
        assertEquals(expected.toSet(), themes.list().orEmpty().toSet())
        assertEquals(setOf("themes"), xross.list().orEmpty().toSet())
    }

    // region Zip slip and unsafe names

    @Test fun pathTraversalIsRejectedBeforeAnythingIsWritten() {
        for (evil in listOf("../evil.json", "fonts/../../evil.ttf", "icons/..\\..\\evil.svg", "..\\theme.json", "sounds/a..b.wav")) {
            val reason = rejected(archive().stage(zip("slip.xtheme", "theme.json" to text(manifest), evil to text("x")), "slip.xtheme"))
            assertTrue("$evil: $reason", "path with .." in reason)
            assertOnly()
        }
        assertFalse(File(tmp.root, "evil.json").exists())
        assertFalse(File(xross, "evil.json").exists())
    }

    @Test fun absolutePathsAreRejected() {
        for (evil in listOf("/etc/theme.json", "\\Windows\\theme.json", "C:/theme.json", "c:\\x\\theme.json")) {
            val reason = rejected(archive().stage(zip("abs.xtheme", evil to text(manifest)), "abs.xtheme"))
            assertTrue("$evil: $reason", "absolute path" in reason)
        }
        assertOnly()
    }

    // endregion

    // region Folders, file types and programs

    @Test fun programsAreRejected() {
        for (exe in listOf("payload.apk", "classes.dex", "icons/libhack.so", "sounds/run.sh", "fonts/x.jar", "install.bat")) {
            val reason = rejected(archive().stage(zip("exe.xtheme", "theme.json" to text(manifest), exe to text("MZ")), "exe.xtheme"))
            assertTrue("$exe: $reason", "is a program" in reason)
        }
        assertOnly()
    }

    @Test fun onlyKnownFoldersAndTypesAreAccepted() {
        for ((path, why) in listOf(
            "scripts/setup.json" to "unexpected folder scripts/",
            "cover.png" to "only .json, wallpaper.png, shader.frag and postprocess.agsl at the top level",
            "wallpapers/night.gif" to "wallpapers/ holds jpeg, jpg, png, webp",
            "fonts/readme.pdf" to "fonts/ holds otf, ttf",
            "icons/a/b/c/deep.svg" to "nested too deep",
        )) {
            val reason = rejected(archive().stage(zip("odd.xtheme", "theme.json" to text(manifest), path to text("x")), "odd.xtheme"))
            assertTrue("$path: $reason", why in reason)
        }
        assertOnly()
    }

    @Test fun aWellFormedPackIsStagedHidden() {
        val r = archive().stage(
            zip(
                "y2k.xtheme",
                "theme.json" to text("""{"schemaVersion": 2, "name": "Y2K Cyber Dream", "imports": ["surfaces.json"]}"""),
                "surfaces.json" to text("""{"surfaces": {}}"""),
                "README.md" to text("hi"),
                "fonts/Display.ttf" to text("font"),
                "sounds/cursor.wav" to text("wav"),
                "audio/ambient_loop.ogg" to text("ogg"),
                "icons/settings.svg" to text("<svg/>"),
                "icons/platforms/dc.png" to text("png"),
                "shaders/shader.frag" to text("void main(){}"),
                "shader.frag" to text("void main(){}"),
                "wallpaper.png" to text("png"),
                "wallpapers/night.webp" to text("webp"),
                "__MACOSX/._theme.json" to text("junk"),
                "icons/.DS_Store" to text("junk"),
            ),
            "y2k.xtheme",
        )
        val s = staged(r)
        assertEquals("y2k-cyber-dream", s.id)
        assertEquals("Y2K Cyber Dream", s.name)
        assertFalse(s.exists)
        assertTrue(s.dir.name.startsWith(ThemeArchive.STAGING_PREFIX))
        // Hidden: discovery doesn't offer a half-finished import.
        assertEquals(listOf(ThemePackLoader.DEFAULT_ID), loader.discover().map { it.id })
        assertTrue(File(s.dir, "icons/platforms/dc.png").isFile)
        // 2b.4c: the pack-root wallpaper and the default CUSTOM shader file come along.
        assertTrue(File(s.dir, "wallpaper.png").isFile && File(s.dir, "shader.frag").isFile && File(s.dir, "wallpapers/night.webp").isFile)
        assertFalse(File(s.dir, "__MACOSX").exists())
        assertFalse(File(s.dir, "icons/.DS_Store").exists())
    }

    @Test fun aZippedFolderIsUnwrapped() {
        val s = staged(
            archive().stage(
                zip("Sakura-main.zip", "Sakura/theme.json" to text("""{"schemaVersion": 2, "name": "?!"}"""), "Sakura/sounds/ok.wav" to text("wav")),
                "Sakura-main.zip",
            ),
        )
        // A name with no letters falls back to the folder's.
        assertEquals("sakura", s.id)
        assertTrue(File(s.dir, "theme.json").isFile)
        assertTrue(File(s.dir, "sounds/ok.wav").isFile)
    }

    // endregion

    // region Size cap

    @Test fun archivesOver50MbUnpackedAreRejected() {
        // 51 MB of zeros deflates to ~50 KB: the declared size gives it away before inflating.
        val big = zip("bomb.xtheme", "theme.json" to text(manifest), "sounds/big.wav" to ByteArray((51 * ThemeArchive.MB).toInt()))
        assertTrue(big.length() < ThemeArchive.MB)
        assertTrue(rejected(archive().stage(big, "bomb.xtheme")).contains("larger than 50 MB"))
        assertOnly()
        // Just under the cap is fine.
        val ok = zip("ok.xtheme", "theme.json" to text(manifest), "sounds/big.wav" to ByteArray((49 * ThemeArchive.MB).toInt()))
        staged(archive().stage(ok, "ok.xtheme"))
    }

    @Test fun manyFilesAddUpToTheCap() {
        val parts = (1..6).map { "sounds/s$it.wav" to ByteArray(300) }.toTypedArray()
        val reason = rejected(archive(maxBytes = 1_000).stage(zip("many.xtheme", "theme.json" to text(manifest), *parts), "many.xtheme"))
        assertTrue(reason, "larger than" in reason)
        assertOnly()
    }

    // endregion

    // region The manifest

    @Test fun anArchiveMustHoldAV2Pack() {
        assertTrue("no theme.json" in rejected(archive().stage(zip("a.xtheme", "sounds/ok.wav" to text("x")), "a.xtheme")))
        assertTrue("schemaVersion" in rejected(archive().stage(zip("v1.xtheme", "theme.json" to text("""{"name": "Old"}""")), "v1.xtheme")))
        assertTrue("line" in rejected(archive().stage(zip("broken.xtheme", "theme.json" to text("""{"schemaVersion": 2,,}""")), "broken.xtheme")))
        assertTrue("isn't a zip" in rejected(archive().stage(File(tmp.root, "plain.xtheme").apply { writeText("hello") }, "plain.xtheme")))
        assertTrue("empty" in rejected(archive().stage(zip("empty.xtheme"), "empty.xtheme")))
        assertTrue(
            "twice" in rejected(archive().stage(zip("dup.xtheme", "theme.json" to text(manifest), "Theme.JSON" to text(manifest)), "dup.xtheme")),
        )
        // Every rejection cleans up its staging folder.
        assertOnly()
    }

    @Test fun idsComeFromTheNameThenTheFile() {
        assertEquals("y2k-cyber-dream", ThemeArchive.idFor("Y2K Cyber Dream", "whatever.xtheme", null))
        assertEquals("my-pack-1", ThemeArchive.idFor("", "My Pack (1).xtheme", null))
        assertEquals("sakura", ThemeArchive.idFor("", "x.zip", "Sakura"))
        assertEquals("default-pack", ThemeArchive.idFor("Default", "x.xtheme", null))
        assertEquals("theme", ThemeArchive.idFor("☆", "☆.xtheme", null))
        assertTrue(ThemeArchive.idFor("a".repeat(200), "", null).length <= 48)
    }

    // endregion

    // region Installing

    @Test fun installMovesThePackIntoPlaceAndItIsDiscovered() {
        val a = archive()
        val s = staged(a.stage(zip("y2k.xtheme", "theme.json" to text(manifest), "sounds/cursor.wav" to text("wav")), "y2k.xtheme"))
        assertEquals(InstallResult.Installed("y2k-cyber-dream"), a.install(s, replace = false))
        assertOnly("y2k-cyber-dream")
        assertEquals(listOf("default", "y2k-cyber-dream"), loader.discover().map { it.id })
        assertEquals(0xFF1B1829.toInt(), loader.load("y2k-cyber-dream").palette!!.top)
    }

    @Test fun anInstalledPackIsReplacedOnlyWhenAsked() {
        val a = archive()
        val installed = File(themes, "y2k-cyber-dream").apply { mkdirs() }
        File(installed, "theme.json").writeText("""{"schemaVersion": 2, "name": "Old"}""")
        File(installed, "mine.txt").writeText("keep?")

        val s = staged(a.stage(zip("y2k.xtheme", "theme.json" to text(manifest)), "y2k.xtheme"))
        assertTrue(s.exists)
        // Not without asking.
        assertTrue(a.install(s, replace = false) is InstallResult.Failed)
        assertEquals("keep?", File(installed, "mine.txt").readText())

        // Keep both: a free id next to it.
        val copy = a.asCopy(s)
        assertEquals("y2k-cyber-dream-2", copy.id)
        assertFalse(copy.exists)

        // Replace: the old files go, the new ones are in place, no leftovers.
        assertEquals(InstallResult.Installed("y2k-cyber-dream"), a.install(s, replace = true))
        assertFalse(File(installed, "mine.txt").exists())
        assertEquals("Y2K Cyber Dream", loader.load("y2k-cyber-dream").info.name)
        assertOnly("y2k-cyber-dream")
    }

    @Test fun aCancelledImportLeavesNothingBehind() {
        val a = archive()
        val s = staged(a.stage(zip("y2k.xtheme", "theme.json" to text(manifest)), "y2k.xtheme"))
        a.discard(s)
        assertOnly()
        // A staging folder an interrupted import left is swept on the next one.
        File(themes, "${ThemeArchive.STAGING_PREFIX}stale").mkdirs()
        staged(a.stage(zip("y2k.xtheme", "theme.json" to text(manifest)), "y2k.xtheme"))
        assertEquals(1, themes.list()!!.count { it.startsWith(ThemeArchive.STAGING_PREFIX) })
    }

    // endregion
}
