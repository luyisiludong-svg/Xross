package dev.xross.launcher

import dev.xross.launcher.theme.pack.StressPack
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.xross.launcher.data.CredentialStore
import dev.xross.launcher.theme.PACK_THEME
import dev.xross.launcher.theme.WaveTint
import dev.xross.launcher.theme.XrossTheme
import dev.xross.launcher.theme.pack.ComponentOverrides
import dev.xross.launcher.theme.pack.EffectOverrides
import dev.xross.launcher.theme.pack.ExportReader
import dev.xross.launcher.theme.pack.ExportResult
import dev.xross.launcher.theme.pack.ExportScope
import dev.xross.launcher.theme.pack.ExportSource
import dev.xross.launcher.theme.pack.LayoutOverrides
import dev.xross.launcher.theme.pack.LoadedTheme
import dev.xross.launcher.theme.pack.LookExport
import dev.xross.launcher.theme.pack.LookExporter
import dev.xross.launcher.theme.pack.LookPicks
import dev.xross.launcher.theme.pack.PackLoad
import dev.xross.launcher.theme.pack.ThemeExport
import dev.xross.launcher.theme.pack.ThemePackLoader
import dev.xross.launcher.theme.pack.UserLook
import dev.xross.launcher.theme.pack.WaveValues
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
import java.io.IOException
import java.io.InputStream
import java.time.LocalDate
import dev.xross.launcher.data.of

/**
 * 2b.8b: the export writer (staging, rename, failures, ids, the cap), that each scope loads back
 * as a pack resolving to the same look, and that no credential reaches an exported file (A.1).
 */
@RunWith(RobolectricTestRunner::class)
class ThemeExportTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule val tmp = TemporaryFolder()

    private val themes get() = File(tmp.root, "Xross/themes").apply { mkdirs() }
    private val loader by lazy { ThemePackLoader(context.assets) { themes } }
    private val writer by lazy { ThemeExport({ themes }) }
    private val date = LocalDate.of(2026, 11, 15)

    /** Reads pack files through the loader and the wallpaper from [wallpaper]. */
    private fun reader(pack: LoadedTheme, wallpaper: File? = null, failOn: String? = null) = object : ExportReader {
        override fun size(source: ExportSource): Long? = when (source) {
            is ExportSource.Pack -> loader.packFileSize(pack.info, source.path)
            ExportSource.UserWallpaper -> wallpaper?.length()
        }

        override fun open(source: ExportSource): InputStream {
            if (source is ExportSource.Pack && source.path == failOn) throw IOException("card removed")
            return when (source) {
                is ExportSource.Pack -> loader.openPackFile(pack.info, source.path)
                ExportSource.UserWallpaper -> wallpaper!!.inputStream()
            }
        }
    }

    private fun stress(): LoadedTheme {
        StressPack.write(themes)
        return loader.load(StressPack.ID)
    }

    private fun write(pack: LoadedTheme, e: LookExport, wallpaper: File? = null): LoadedTheme {
        val r = writer.write(e, reader(pack, wallpaper))
        if (r !is ExportResult.Saved) fail("not saved: $r")
        r as ExportResult.Saved
        val loaded = loader.loadStrict(r.id)
        if (loaded !is PackLoad.Ok) fail("doesn't load: $loaded")
        val t = (loaded as PackLoad.Ok).theme
        assertEquals("loads without problems", emptyList<String>(), t.problems)
        return t
    }

    private fun stagingLeft() = themes.listFiles()!!.filter { it.name.startsWith(ThemeExport.STAGING_PREFIX) }

    // region Writer

    @Test fun writesIntoAStagingFolderThenRenamesIt() {
        val pack = stress()
        val e = LookExporter.export(pack, ExportScope.WholePack, "Stress copy", date, loader.packFiles(pack.info))
        val r = writer.write(e, reader(pack)) as ExportResult.Saved
        assertEquals("stress-copy", r.id)
        assertEquals(File(themes, "stress-copy"), r.dir)
        assertEquals(e.files.size, r.files)
        assertTrue(stagingLeft().isEmpty())
        for (f in e.files) assertTrue(f.path, File(r.dir, f.path).isFile)
        assertTrue(File(r.dir, "theme.json").readText().contains("\"name\": \"Stress copy\""))
        assertFalse(File(r.dir, "surfaces.json").exists())
        // Discovered like any pack.
        assertTrue(loader.discover().any { it.id == "stress-copy" && it.name == "Stress copy" })
    }

    @Test fun aFailureMidCopyLeavesNoFolder() {
        val pack = stress()
        val e = LookExporter.export(pack, ExportScope.WholePack, "Broken", date, loader.packFiles(pack.info))
        val before = themes.list()!!.toSet()
        val r = writer.write(e, reader(pack, failOn = e.files[3].path))
        assertTrue(r is ExportResult.Refused)
        assertEquals("Couldn't copy ${e.files[3].path} (card removed)", (r as ExportResult.Refused).reason)
        assertEquals(before, themes.list()!!.toSet())
    }

    @Test fun anInstalledIdIsNeverOverwritten() {
        val pack = stress()
        val e = LookExporter.export(pack, ExportScope.WholePack, "", date, loader.packFiles(pack.info))
        assertEquals("stress-2", (writer.write(e, reader(pack)) as ExportResult.Saved).id)
        assertEquals("stress-3", (writer.write(e, reader(pack)) as ExportResult.Saved).id)
        // The built-in pack's name never takes the reserved id.
        val d = loader.load(ThemePackLoader.DEFAULT_ID)
        val de = LookExporter.export(d, ExportScope.WholePack, "", date, loader.packFiles(d.info))
        assertEquals("xmb-pack", (writer.write(de, reader(d)) as ExportResult.Saved).id)
        assertTrue(File(themes, StressPack.ID).isDirectory)
    }

    @Test fun overTheCapIsRefusedWithTheLargestFilesNamed() {
        val pack = stress()
        val e = LookExporter.export(pack, ExportScope.WholePack, "Big", date, loader.packFiles(pack.info))
        val small = ThemeExport({ themes }, maxBytes = 2_000)
        val r = small.write(e, reader(pack)) as ExportResult.Refused
        assertTrue(r.reason, r.reason.startsWith("The pack would be "))
        assertTrue(r.reason, r.reason.contains("(largest: boot.mp4 1 KB"))
        assertTrue(stagingLeft().isEmpty())
        assertFalse(File(themes, "big").exists())
        // An unknown size is caught while copying.
        val blind = object : ExportReader by reader(pack) {
            override fun size(source: ExportSource): Long? = null
        }
        assertTrue(small.write(e, blind) is ExportResult.Refused)
        assertTrue(stagingLeft().isEmpty())
    }

    // endregion

    // region Each scope loads to the same look

    private fun assertSameLook(
        original: LoadedTheme,
        exported: LoadedTheme,
        variant: String = "",
        user: UserLook = UserLook.NONE,
        colorTheme: String = PACK_THEME,
        wave: WaveValues = WaveValues(),
        exportedVariant: String = "",
    ) = SameLook.assertSame(original, exported, variant, user, colorTheme, wave, exportedVariant, date)

    @Test fun eachScopeOfTheStressPackLoadsToTheSameLook() {
        val pack = stress()
        // Whole pack: every variant and the base, as they were.
        val whole = write(pack, LookExporter.export(pack, ExportScope.WholePack, "Whole", date, loader.packFiles(pack.info)))
        assertEquals(pack.variants.map { it.id to it.name }, whole.variants.map { it.id to it.name })
        for (v in listOf("", "night", "day")) assertSameLook(pack, whole, variant = v, exportedVariant = v)
        // One variant: its base is that variant.
        for (v in listOf("", "night", "day")) {
            val one = write(pack, LookExporter.export(pack, ExportScope.Variant(v), "Only $v", date))
            assertTrue(one.variants.isEmpty())
            assertSameLook(pack, one, variant = v)
        }
    }

    @Test fun theCurrentLookOfTheStressPackLoadsToWhatIsOnScreen() {
        val pack = stress()
        val user = UserLook(
            effects = EffectOverrides(
                shaderPreset = "STARFIELD", backgroundFilter = "SCANLINES", uiFilter = "BLOOM", focusFrameStyle = "GOLD_BEZEL",
                foregroundParticles = "SNOW", iconMask = "CIRCLE", bootIntro = "SHADER", launchCurtain = "IRIS",
            ),
            components = ComponentOverrides(batteryMode = "PERCENTAGE", statusBarRight = listOf("CLOCK", "AVATAR"), tileGlow = false),
            layout = LayoutOverrides(preset = "MODERN_WIDESCREEN", uiScale = 0.9f, itemSpacingDp = 6f, focusedScale = 1.05f),
            surfaceStyle = "Y2K_GLOSSY_BEZEL",
            adaptiveGamePalette = false,
            circadianCurve = true,
            batteryTint = true,
        )
        for ((variant, theme, tint) in listOf(
            Triple("night", PACK_THEME, WaveTint.THEME),
            Triple("", XrossTheme.CRIMSON.name, WaveTint.THEME),
            Triple("day", PACK_THEME, WaveTint.AURORA),
            Triple("", XrossTheme.ORIGINAL.name, WaveTint.SUNSET),
        )) {
            val wave = WaveValues(speed = 1.3f, lineCount = 9, turbulence = 0.4f, thickness = 1.5f, tint = tint.name)
            val e = LookExporter.export(pack, ExportScope.CurrentLook, "Mine $variant $theme $tint", date, picks = LookPicks(variant, user, theme, wave))
            val mine = write(pack, e)
            assertSameLook(pack, mine, variant, user, theme, wave)
            assertEquals(1.3f, mine.wave!!.speed)
            assertEquals(9, mine.wave!!.lineCount)
        }
        // Nothing over the pack at all: the same as its base.
        val plain = write(pack, LookExporter.export(pack, ExportScope.CurrentLook, "Plain", date))
        assertSameLook(pack, plain)
    }

    @Test fun theBuiltInPackExportsToTheSameLook() {
        val pack = loader.load(ThemePackLoader.DEFAULT_ID)
        val whole = write(pack, LookExporter.export(pack, ExportScope.WholePack, "", date, loader.packFiles(pack.info)))
        assertSameLook(pack, whole, colorTheme = XrossTheme.LAVENDER.name)
        // Its sounds and icons came from the APK assets.
        assertEquals(pack.sounds.keys, whole.sounds.keys)
        assertEquals(pack.icons.keys, whole.icons.keys)
        val user = UserLook(effects = EffectOverrides(foregroundParticles = "DUST_MOTES"), layout = LayoutOverrides(preset = "PSP_CENTER", uiScale = 1.3f), components = ComponentOverrides(clockFormat = "Hm"))
        val wave = WaveValues(tint = WaveTint.OCEAN.name)
        val mine = write(pack, LookExporter.export(pack, ExportScope.CurrentLook, "", date, picks = LookPicks("", user, XrossTheme.EMERALD.name, wave)))
        assertSameLook(pack, mine, user = user, colorTheme = XrossTheme.EMERALD.name, wave = wave)
    }

    @Test fun theUsersWallpaperIsCopiedAndShowsWithoutItsGrant() {
        val pack = stress()
        val photo = File(tmp.root, "DCIM/photo.png").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()) + ByteArray(200)) }
        val user = UserLook(customWallpaper = photo.absolutePath)
        val e = LookExporter.export(pack, ExportScope.CurrentLook, "With photo", date, picks = LookPicks(user = user, wallpaperType = "png"))
        val mine = write(pack, e, wallpaper = photo)
        photo.delete()
        // Every context shows the copied picture.
        for (w in SameLook.CONTEXTS) {
            val look = mine.look("", w.category, w.system, w.battery, w.minute)
            assertEquals("wallpaper.png", look.wallpaper.path)
            assertTrue(mine.wallpaperFile(look.wallpaper)!!.isFile)
        }
        assertSameLook(pack, mine, user = user)
    }

    // endregion

    // region Credentials (A.1)

    @Test fun noCredentialInAnyExportedFile() {
        val container = AppContainer(context)
        val store: CredentialStore = container.credentials
        val raKey = "RA-KEY-7f3a9c1e5b"
        val raUser = "SecretUser42"
        val ssPassword = "ss-pass-0xBADC0DE"
        store.update { it.copy(retroAchievementsUser = raUser, retroAchievementsKey = raKey, screenScraperUser = "ssu", screenScraperPassword = ssPassword) }
        container.settings.update {
            it.copy(effectOverrides = EffectOverrides(shaderPreset = "BOKEH"), layoutOverrides = LayoutOverrides(uiScale = 1.2f), theme = XrossTheme.ROSE.name)
        }
        val s = container.settings.current
        val pack = stress()
        val exports = listOf(
            LookExporter.export(pack, ExportScope.WholePack, "", date, loader.packFiles(pack.info)),
            LookExporter.export(pack, ExportScope.Variant("night"), "", date),
            LookExporter.export(pack, ExportScope.CurrentLook, "", date, picks = LookPicks.of(s)),
        )
        val d = loader.load(ThemePackLoader.DEFAULT_ID)
        val all = exports.map { pack to it } + listOf(
            d to LookExporter.export(d, ExportScope.WholePack, "", date, loader.packFiles(d.info)),
            d to LookExporter.export(d, ExportScope.CurrentLook, "", date, picks = LookPicks.of(s)),
        )
        val dirs = all.map { (p, e) -> (writer.write(e, reader(p)) as ExportResult.Saved).dir }
        for (dir in dirs) {
            for (f in dir.walkTopDown().filter { it.isFile }) {
                val text = f.readBytes().toString(Charsets.ISO_8859_1)
                for (secret in listOf(raKey, raUser, ssPassword)) assertFalse("${f.path} holds a credential", text.contains(secret))
            }
            // The current look's author is empty, never the account's name.
            assertFalse(File(dir, "theme.json").readText().contains(raUser))
        }
    }

    // endregion
}
