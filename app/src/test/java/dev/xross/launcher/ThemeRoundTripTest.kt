package dev.xross.launcher

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.xross.launcher.theme.PACK_THEME
import dev.xross.launcher.theme.WaveTint
import dev.xross.launcher.theme.XrossTheme
import dev.xross.launcher.theme.pack.ArchiveResult
import dev.xross.launcher.theme.pack.ArchiveWrite
import dev.xross.launcher.theme.pack.ComponentOverrides
import dev.xross.launcher.theme.pack.EffectOverrides
import dev.xross.launcher.theme.pack.ExportReader
import dev.xross.launcher.theme.pack.ExportScope
import dev.xross.launcher.theme.pack.ExportSource
import dev.xross.launcher.theme.pack.InstallResult
import dev.xross.launcher.theme.pack.LayoutOverrides
import dev.xross.launcher.theme.pack.LoadedTheme
import dev.xross.launcher.theme.pack.LookExport
import dev.xross.launcher.theme.pack.LookExporter
import dev.xross.launcher.theme.pack.LookPicks
import dev.xross.launcher.theme.pack.PackLoad
import dev.xross.launcher.theme.pack.ThemeArchive
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
import java.io.InputStream
import java.time.LocalDate
import java.util.zip.ZipFile
import dev.xross.launcher.data.of

/**
 * The Appendix A.13 gate for 2b.8 (Phase 2b.8c): for the built-in Default pack and the stress
 * pack, each export scope goes export → [ThemeArchive.write] → [ThemeArchive.stage] → install →
 * load, and the re-imported pack resolves to the same look as the original in every context of
 * [SameLook.CONTEXTS]; no archive holds a credential (A.1).
 */
@RunWith(RobolectricTestRunner::class)
class ThemeRoundTripTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule val tmp = TemporaryFolder()

    private val themes get() = File(tmp.root, "Xross/themes").apply { mkdirs() }
    private val loader by lazy { ThemePackLoader(context.assets) { themes } }
    private val archives by lazy { ThemeArchive({ themes }, loader::inspect) }
    private val date = LocalDate.of(2026, 11, 15)
    private var n = 0

    private fun reader(pack: LoadedTheme, wallpaper: File? = null) = object : ExportReader {
        override fun size(source: ExportSource): Long? = when (source) {
            is ExportSource.Pack -> loader.packFileSize(pack.info, source.path)
            ExportSource.UserWallpaper -> wallpaper?.length()
        }

        override fun open(source: ExportSource): InputStream = when (source) {
            is ExportSource.Pack -> loader.openPackFile(pack.info, source.path)
            ExportSource.UserWallpaper -> wallpaper!!.inputStream()
        }
    }

    private fun default() = loader.load(ThemePackLoader.DEFAULT_ID)

    private fun stress(): LoadedTheme {
        StressPack.write(themes)
        return loader.load(StressPack.ID)
    }

    /** [export] zipped into a new `.xtheme` (outside Xross/themes, as a shared file would be). */
    private fun zip(pack: LoadedTheme, export: LookExport, wallpaper: File? = null): File {
        val file = File(tmp.root, "shared/${++n}.xtheme").apply { parentFile!!.mkdirs() }
        val r = file.outputStream().use { ThemeArchive.write(export, reader(pack, wallpaper), it) }
        if (r !is ArchiveWrite.Written) fail("${export.scope}: not written: $r")
        assertEquals("${export.scope}: nothing left out", emptyList<String>(), (r as ArchiveWrite.Written).leftOut)
        return file
    }

    /** Imports [file] as Import theme (.xtheme)… does (a copy when the id is taken) and loads it. */
    private fun reimport(file: File, label: String): LoadedTheme {
        val r = archives.stage(file, file.name)
        val staged = (r as? ArchiveResult.Staged)?.theme ?: throw AssertionError("$label: rejected: $r")
        val target = if (staged.exists) archives.asCopy(staged) else staged
        val id = (archives.install(target, replace = false) as? InstallResult.Installed)?.id ?: error("$label: not installed")
        val loaded = loader.loadStrict(id)
        if (loaded !is PackLoad.Ok) fail("$label: doesn't load: $loaded")
        val t = (loaded as PackLoad.Ok).theme
        assertEquals("$label: loads without problems", emptyList<String>(), t.problems)
        return t
    }

    private fun roundTrip(pack: LoadedTheme, scope: ExportScope, picks: LookPicks = LookPicks(), wallpaper: File? = null, label: String = "$scope"): LoadedTheme {
        val available = if (scope == ExportScope.WholePack) loader.packFiles(pack.info) else emptySet()
        val export = LookExporter.export(pack, scope, "", date, available, picks)
        return reimport(zip(pack, export, wallpaper), "${pack.info.name} $label")
    }

    /** A user layer that sets every [UserLook] field (the wallpaper when [photo] is given). */
    private fun everything(photo: File?) = UserLook(
        effects = EffectOverrides(
            shaderPreset = "STARFIELD", backgroundFilter = "SCANLINES", uiFilter = "BLOOM", focusFrameStyle = "GOLD_BEZEL",
            foregroundParticles = "SNOW", iconMask = "CIRCLE", bootIntro = "SHADER", launchCurtain = "IRIS",
        ),
        components = ComponentOverrides(batteryMode = "PERCENTAGE", statusBarRight = listOf("CLOCK", "AVATAR"), clockFormat = "Hm"),
        layout = LayoutOverrides(preset = "MODERN_WIDESCREEN", uiScale = 0.9f, itemSpacingDp = 6f, focusedScale = 1.05f),
        surfaceStyle = "Y2K_GLOSSY_BEZEL",
        customWallpaper = photo?.absolutePath.orEmpty(),
        adaptiveGamePalette = false,
        circadianCurve = true,
        batteryTint = true,
    )

    private fun photo(): File = File(tmp.root, "DCIM/photo.png").apply {
        parentFile!!.mkdirs()
        writeBytes(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()) + ByteArray(300) { it.toByte() })
    }

    /** Every field of [UserLook] is set by [everything], so the current-look gate covers all of them. */
    @Test fun theUserLayerUsedBelowSetsEveryField() {
        val all = everything(photo())
        val fields = UserLook::class.java.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
        assertTrue(fields.size >= 8)
        for (f in fields) {
            f.isAccessible = true
            assertTrue("everything() leaves ${f.name} as the pack's", f.get(all) != f.get(UserLook.NONE))
        }
    }

    // region The built-in Default pack

    @Test fun defaultWholePackRoundTrips() {
        val pack = default()
        val back = roundTrip(pack, ExportScope.WholePack)
        assertEquals(pack.variants.map { it.id to it.name }, back.variants.map { it.id to it.name })
        for (v in listOf("") + pack.variants.map { it.id }) {
            SameLook.assertSame(pack, back, variant = v, exportedVariant = v, date = date, label = "whole pack, variant '$v'")
        }
        // Its sounds and icons came out of the APK assets, convention files (power.svg) included.
        assertEquals(pack.sounds.keys, back.sounds.keys)
        assertEquals(pack.icons.keys, back.icons.keys)
        assertTrue(File(themes, "${back.id}/icons/power.svg").isFile)
    }

    @Test fun defaultBaseLookRoundTrips() {
        val pack = default()
        SameLook.assertSame(pack, roundTrip(pack, ExportScope.Variant("")), date = date, label = "base look")
    }

    @Test fun defaultCurrentLookRoundTrips() {
        val pack = default()
        val photo = photo()
        val user = everything(photo)
        for ((theme, tint) in listOf(PACK_THEME to WaveTint.THEME, XrossTheme.EMERALD.name to WaveTint.OCEAN, XrossTheme.ORIGINAL.name to WaveTint.THEME)) {
            val wave = WaveValues(speed = 0.7f, lineCount = 3, turbulence = 1.4f, thickness = 0.8f, tint = tint.name)
            val label = "current look ($theme, $tint)"
            val back = roundTrip(pack, ExportScope.CurrentLook, LookPicks("", user, theme, wave, wallpaperType = "png"), photo, label)
            SameLook.assertSame(pack, back, user = user, colorTheme = theme, wave = wave, date = date, label = label)
            for (w in SameLook.CONTEXTS) assertEquals("$label: $w: the photo", "wallpaper.png", back.look("", w.category, w.system, w.battery, w.minute).wallpaper.path)
            assertTrue(back.wallpaperFile(back.look().wallpaper)!!.isFile)
        }
    }

    // endregion

    // region The stress pack (variants, overrides, rules, every file kind)

    @Test fun stressWholePackRoundTripsForEachVariant() {
        val pack = stress()
        val back = roundTrip(pack, ExportScope.WholePack)
        assertEquals(pack.variants.map { it.id to it.name }, back.variants.map { it.id to it.name })
        for (v in listOf("") + pack.variants.map { it.id }) {
            SameLook.assertSame(pack, back, variant = v, exportedVariant = v, date = date, label = "whole pack, variant '$v'")
        }
        // The files only a root logo and art/ name came through the import (2b.8c's allowlist).
        for (f in listOf("logo.svg", "logo-night.svg", "art/night.webp", "art/late.jpg")) assertTrue(f, File(themes, "${back.id}/$f").isFile)
    }

    @Test fun stressVariantsRoundTrip() {
        val pack = stress()
        for (v in listOf("") + pack.variants.map { it.id }) {
            val back = roundTrip(pack, ExportScope.Variant(v), label = "variant '$v'")
            assertTrue(back.variants.isEmpty())
            SameLook.assertSame(pack, back, variant = v, date = date, label = "variant '$v'")
        }
    }

    @Test fun stressCurrentLookRoundTrips() {
        val pack = stress()
        val photo = photo()
        for ((variant, theme, tint) in listOf(
            Triple("night", PACK_THEME, WaveTint.THEME),
            Triple("", XrossTheme.CRIMSON.name, WaveTint.THEME),
            Triple("day", PACK_THEME, WaveTint.AURORA),
            Triple("", XrossTheme.ORIGINAL.name, WaveTint.SUNSET),
        )) {
            val user = everything(photo)
            val wave = WaveValues(speed = 1.3f, lineCount = 9, turbulence = 0.4f, thickness = 1.5f, tint = tint.name)
            val label = "current look ($variant, $theme, $tint)"
            val back = roundTrip(pack, ExportScope.CurrentLook, LookPicks(variant, user, theme, wave, wallpaperType = "png"), photo, label)
            SameLook.assertSame(pack, back, variant, user, theme, wave, date = date, label = label)
        }
    }

    /** A pack folder shared as it is (Share as .xtheme… after an export, or any folder pack): `imports` and all. */
    @Test fun aFolderSharedAsItIsRoundTrips() {
        val pack = stress()
        val file = File(tmp.root, "shared/stress.xtheme").apply { parentFile!!.mkdirs() }
        val r = file.outputStream().use { ThemeArchive.writeFolder(File(themes, StressPack.ID), it) } as ArchiveWrite.Written
        assertEquals(emptyList<String>(), r.leftOut)
        val back = reimport(file, "stress folder")
        assertEquals("stress-2", back.id)
        for (v in listOf("") + pack.variants.map { it.id }) {
            SameLook.assertSame(pack, back, variant = v, exportedVariant = v, date = date, label = "folder, variant '$v'")
        }
        assertEquals(pack.files, back.files)
    }

    // endregion

    // region Credentials (A.1)

    @Test fun noCredentialInAnyArchive() {
        val container = AppContainer(context)
        val raKey = "RA-KEY-7f3a9c1e5b"
        val raUser = "SecretUser42"
        val ssPassword = "ss-pass-0xBADC0DE"
        container.credentials.update { it.copy(retroAchievementsUser = raUser, retroAchievementsKey = raKey, screenScraperUser = "ssu", screenScraperPassword = ssPassword) }
        container.settings.update {
            it.copy(effectOverrides = EffectOverrides(shaderPreset = "BOKEH"), layoutOverrides = LayoutOverrides(uiScale = 1.2f), theme = XrossTheme.ROSE.name)
        }
        val s = container.settings.current
        val stress = stress()
        val d = default()
        val zips = buildList {
            for (pack in listOf(stress, d)) {
                add(zip(pack, LookExporter.export(pack, ExportScope.WholePack, "", date, loader.packFiles(pack.info))))
                add(zip(pack, LookExporter.export(pack, ExportScope.Variant(pack.variants.firstOrNull()?.id.orEmpty()), "", date)))
                add(zip(pack, LookExporter.export(pack, ExportScope.CurrentLook, "", date, picks = LookPicks.of(s))))
            }
            add(File(tmp.root, "shared/folder.xtheme").also { f -> f.outputStream().use { ThemeArchive.writeFolder(File(themes, StressPack.ID), it) } })
        }
        for (zip in zips) {
            // Inflated, entry by entry: a deflated secret wouldn't show in the file's own bytes.
            ZipFile(zip).use { z ->
                for (e in z.entries()) {
                    val text = e.name + z.getInputStream(e).use { it.readBytes() }.toString(Charsets.ISO_8859_1)
                    for (secret in listOf(raKey, raUser, ssPassword)) assertFalse("${zip.name}/${e.name} holds a credential", text.contains(secret))
                }
            }
        }
    }

    // endregion
}
