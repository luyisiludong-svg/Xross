package dev.xross.launcher

import android.content.Context
import android.os.Looper
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.test.core.app.ApplicationProvider
import dev.xross.launcher.audio.UiCue
import dev.xross.launcher.config.ConfigCodec
import dev.xross.launcher.data.XrossSettings
import dev.xross.launcher.input.NavAction
import dev.xross.launcher.state.Command
import dev.xross.launcher.state.MenuSnapshot
import dev.xross.launcher.state.MenuTree
import dev.xross.launcher.state.NodeAction
import dev.xross.launcher.state.ThemeSetup
import dev.xross.launcher.state.XmbViewModel
import dev.xross.launcher.theme.pack.ArchiveResult
import dev.xross.launcher.theme.pack.BootIntro
import dev.xross.launcher.theme.pack.BootVideoRef
import dev.xross.launcher.theme.pack.Cosmetics
import dev.xross.launcher.theme.pack.EffectOverrides
import dev.xross.launcher.theme.pack.EffectSlot
import dev.xross.launcher.theme.pack.FolderCheck
import dev.xross.launcher.theme.pack.LaunchCurtain
import dev.xross.launcher.theme.pack.LoadedTheme
import dev.xross.launcher.theme.pack.ThemeArchive
import dev.xross.launcher.theme.pack.ThemeDefaults
import dev.xross.launcher.theme.pack.ThemeLook
import dev.xross.launcher.theme.pack.ThemeManifest
import dev.xross.launcher.theme.pack.ThemePackLoader
import dev.xross.launcher.ui.BootInputs
import dev.xross.launcher.ui.BootMode
import dev.xross.launcher.ui.BootPlan
import dev.xross.launcher.ui.BootSequence
import dev.xross.launcher.ui.LaunchCurtainTiming
import dev.xross.launcher.ui.WaveIntro
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
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Phase 2b.5d: the cold-start intro (boot video, shader ramp or fade-in) and its rules, the boot
 * video in packs and archives, the launch curtains, the Settings ▸ Theme ▸ Intro and transitions
 * rows, and the ViewModel's skip handler.
 */
@RunWith(RobolectricTestRunner::class)
class BootSequenceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule val tmp = TemporaryFolder()

    private val themes get() = File(tmp.root, "Xross/themes").apply { mkdirs() }
    private val loader by lazy { ThemePackLoader(context.assets) { themes } }
    private val video = File("boot.mp4")

    private fun pack(id: String, body: String, files: Map<String, Long> = emptyMap(), dir: File = themes): LoadedTheme {
        val d = File(dir, id).apply { mkdirs() }
        File(d, "theme.json").writeText("""{"schemaVersion": 2, "name": "$id"${if (body.isEmpty()) "" else ", $body"}}""")
        for ((name, size) in files) {
            RandomAccessFile(File(d, name).apply { parentFile?.mkdirs() }, "rw").use { it.setLength(size) }
        }
        return ThemePackLoader(context.assets) { dir }.load(id)
    }

    // region Mode resolution

    @Test fun aWarmStartPlaysNothing() {
        assertNull(BootSequence.resolve(BootInputs(coldBoot = false, video = video)))
        assertNull(BootSequence.resolve(BootInputs(coldBoot = false, intro = BootIntro.SHADER)))
    }

    @Test fun theVideoBeatsThePacksShaderIntro() {
        assertEquals(BootMode.VIDEO, BootSequence.resolve(BootInputs(intro = BootIntro.SHADER, video = video))!!.mode)
        assertEquals(BootPlan(BootMode.VIDEO, video), BootSequence.resolve(BootInputs(video = video)))
        assertEquals(BootMode.SHADER, BootSequence.resolve(BootInputs(intro = BootIntro.SHADER))!!.mode)
        // Without either, the fade-in.
        assertEquals(BootPlan(BootMode.FADE), BootSequence.resolve(BootInputs()))
    }

    @Test fun theUsersChoiceWins() {
        // Settings ▸ Theme ▸ Boot intro ▸ Shader plays the shader even over a pack's video.
        assertEquals(BootMode.SHADER, BootSequence.resolve(BootInputs(intro = BootIntro.SHADER, userChoice = true, video = video))!!.mode)
        assertEquals(BootMode.FADE, BootSequence.resolve(BootInputs(intro = BootIntro.OFF, userChoice = true, video = video))!!.mode)
        // Video without a video: the fade-in.
        assertEquals(BootMode.FADE, BootSequence.resolve(BootInputs(intro = BootIntro.VIDEO, userChoice = true))!!.mode)
        // A pack turning its own intro off.
        assertEquals(BootMode.FADE, BootSequence.resolve(BootInputs(intro = BootIntro.OFF, video = video))!!.mode)
    }

    @Test fun eInkAndASilentStartTurnItOff() {
        val wanted = BootInputs(intro = BootIntro.SHADER, userChoice = true, video = video)
        assertEquals(BootMode.FADE, BootSequence.resolve(wanted.copy(stillMotion = true))!!.mode)
        assertEquals(BootMode.FADE, BootSequence.resolve(wanted.copy(startupSound = false))!!.mode)
        assertEquals(BootMode.FADE, BootSequence.resolve(wanted.copy(cueVolume = 0f))!!.mode)
        assertEquals(BootMode.FADE, BootSequence.resolve(wanted.copy(cueVolume = Float.NaN))!!.mode)
        assertEquals(BootMode.FADE, BootSequence.resolve(BootInputs(video = video, startupSound = false))!!.mode)
        // No wave, nothing for the shader to ramp.
        assertEquals(BootMode.FADE, BootSequence.resolve(wanted.copy(waveEnabled = false))!!.mode)
        assertEquals(BootMode.VIDEO, BootSequence.resolve(BootInputs(video = video, waveEnabled = false))!!.mode)
    }

    @Test fun introsAreCappedAndLogged() {
        assertEquals(15_000L, BootSequence.resolve(BootInputs(video = video))!!.maxMs)
        assertEquals(BootSequence.SHADER_MS, BootSequence.resolve(BootInputs(intro = BootIntro.SHADER))!!.maxMs)
        assertEquals(
            "mode=VIDEO file=boot.mp4 skipped=true at=1840 ms",
            BootSequence.describe(BootPlan(BootMode.VIDEO, video), skipped = true, atMs = 1840),
        )
    }

    @Test fun theShaderRampDrivesUTransitionToOne() {
        val intro = WaveIntro(startedAtNanos = 1_000_000_000L, durationMs = 2_500)
        assertEquals(0f, intro.level(1_000_000_000L), 0f)
        assertEquals(0.5f, intro.level(1_000_000_000L + 1_250_000_000L), 1e-4f)
        assertEquals(1f, intro.level(1_000_000_000L + 2_500_000_000L), 0f)
        assertFalse(intro.over(1_000_000_000L + 2_000_000_000L))
        assertTrue(intro.over(1_000_000_000L + 2_500_000_000L))
        // Skipped: over from the moment it ended, holding the level it had.
        val skipped = intro.copy(endedAtNanos = 1_000_000_000L + 800_000_000L)
        assertTrue(skipped.over(1_000_000_000L + 800_000_000L))
        assertTrue(skipped.level(1_000_000_000L + 800_000_000L) in 0.1f..0.4f)
    }

    // endregion

    // region Packs and archives

    @Test fun aBootVideoAtTheRootIsFound() {
        val theme = pack("boot", "", mapOf("boot.mp4" to 1000))
        assertEquals("boot.mp4", theme.bootVideo?.name)
        assertTrue(theme.problems.toString(), theme.problems.isEmpty())
        assertNull(pack("plain", "").bootVideo)
        assertNull("the built-in pack has none", loader.load(ThemePackLoader.DEFAULT_ID).bootVideo)
    }

    @Test fun audioBootVideoNamesAnotherFileOrNone() {
        val named = pack("named", """"audio": {"bootVideo": "video/intro.webm"}""", mapOf("video/intro.webm" to 1000, "boot.mp4" to 1000))
        assertEquals("intro.webm", named.bootVideo?.name)
        val alias = pack("alias", """"audio": {"boot": "intro.mp4"}""", mapOf("intro.mp4" to 1000))
        assertEquals("intro.mp4", alias.bootVideo?.name)
        assertTrue(alias.problems.toString(), alias.problems.isEmpty())
        assertNull(pack("off", """"audio": {"bootVideo": "none"}""", mapOf("boot.mp4" to 1000)).bootVideo)
    }

    @Test fun badBootVideosAreReported() {
        val big = pack("big", "", mapOf("boot.mp4" to BootVideoRef.MAX_BYTES + 1))
        assertNull(big.bootVideo)
        assertTrue(big.problems.toString(), big.problems.any { "larger than 30 MB" in it })
        val missing = pack("missing", """"audio": {"bootVideo": "intro.mp4"}""")
        assertTrue(missing.problems.toString(), missing.problems.any { "intro.mp4 not found" in it })
        val outside = pack("outside", """"audio": {"bootVideo": "../x.mp4"}""")
        assertTrue(outside.problems.toString(), outside.problems.any { "inside the pack" in it })
        val notVideo = pack("song", """"audio": {"bootVideo": "song.ogg"}""", mapOf("song.ogg" to 10))
        assertNull(notVideo.bootVideo)
        assertTrue(notVideo.problems.toString(), notVideo.problems.any { "isn't a video" in it })
        val variant = pack("variant", """"variants": [{"id": "v", "audio": {"bootVideo": "boot.mp4"}}]""", mapOf("boot.mp4" to 10))
        assertTrue(variant.problems.toString(), variant.problems.any { it.startsWith("variants.v.audio.bootVideo") })
    }

    private fun zip(name: String, vararg entries: Pair<String, ByteArray>): File {
        val f = File(tmp.root, name)
        ZipOutputStream(f.outputStream()).use { z -> for ((n, b) in entries) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return f
    }

    private fun archive() = ThemeArchive({ themes }, { dir ->
        val m = ThemeManifest.JSON.decodeFromString(ThemeManifest.serializer(), File(dir, "theme.json").readText())
        FolderCheck.Ok(m)
    })

    @Test fun archivesAcceptABootVideoAtTheRootUpToItsCap() {
        val manifest = """{"schemaVersion": 2, "name": "Boot"}""".toByteArray()
        val ok = archive().stage(zip("boot.xtheme", "theme.json" to manifest, "boot.mp4" to ByteArray(1000)), "boot.xtheme")
        assertTrue(ok.toString(), ok is ArchiveResult.Staged)
        val big = archive().stage(zip("big.xtheme", "theme.json" to manifest, "boot.mp4" to ByteArray((31 * ThemeArchive.MB).toInt())), "big.xtheme")
        val reason = (big as ArchiveResult.Rejected).reason
        assertTrue(reason, "boot.mp4 is larger than 30 MB (boot video)" in reason)
    }

    // endregion

    // region Curtain and intro names

    private fun effects(json: String): ThemeManifest.Effects =
        ThemeManifest.JSON.decodeFromString(ThemeManifest.serializer(), """{"schemaVersion": 2, "name": "x", "effects": $json}""").effects!!

    @Test fun curtainAndIntroNamesDecodeWithAliases() {
        val problems = ArrayList<String>()
        fun curtain(text: String) = Cosmetics.of("theme", effects("""{"launchCurtain": "$text"}"""), problems)?.launchCurtain
        assertEquals(LaunchCurtain.FADE_BLACK, curtain("fade-black"))
        assertEquals(LaunchCurtain.FADE_BLACK, curtain("fade"))
        assertEquals(LaunchCurtain.IRIS, curtain("Iris"))
        assertEquals(LaunchCurtain.FLASH, curtain("white flash"))
        assertEquals(LaunchCurtain.BLOOM, curtain("BLOOM"))
        assertEquals(LaunchCurtain.IRIS, Cosmetics.of("theme", effects("""{"curtain": "circle"}"""), problems)?.launchCurtain)
        assertEquals(BootIntro.SHADER, Cosmetics.of("theme", effects("""{"bootIntro": "shader"}"""), problems)?.bootIntro)
        assertEquals(BootIntro.OFF, Cosmetics.of("theme", effects("""{"intro": "fade"}"""), problems)?.bootIntro)
        assertTrue(problems.toString(), problems.isEmpty())
        assertNull(curtain("wipe"))
        assertEquals(listOf("theme.effects.launchCurtain: wipe isn't one of BLOOM, FADE_BLACK, IRIS, FLASH"), problems)
        assertEquals(LaunchCurtain.BLOOM, Cosmetics.NONE.curtain)
    }

    @Test fun theCurtainCascadesAndTheUserWins() {
        val theme = pack(
            "curtains",
            """"effects": {"launchCurtain": "FADE_BLACK"},
               "systemOverrides": {"snes": {"effects": {"launchCurtain": "IRIS"}}},
               "rules": [{"match": {"category": "VIDEO"}, "apply": {"effects": {"curtain": "FLASH"}}}]""",
        )
        assertTrue(theme.problems.toString(), theme.problems.isEmpty())
        assertEquals(LaunchCurtain.FADE_BLACK, theme.look().cosmetics.curtain)
        assertEquals(LaunchCurtain.IRIS, theme.look(category = "games", system = "snes").cosmetics.curtain)
        assertEquals(LaunchCurtain.FLASH, theme.look(category = "video").cosmetics.curtain)
        val user = EffectOverrides(launchCurtain = "bloom").normalized()
        assertEquals(LaunchCurtain.BLOOM, user.applyTo(theme.look(category = "games", system = "snes")).cosmetics.curtain)
    }

    @Test fun curtainShapesCloseInTime() {
        val size = Size(1000f, 500f)
        val focus = Offset(220f, 250f)
        // The iris starts past the farthest corner (nothing covered) and closes to a point.
        assertEquals(Offset(1000f, 0f).minus(focus).getDistance(), LaunchCurtainTiming.irisRadius(0f, focus, size), 1e-3f)
        assertEquals(0f, LaunchCurtainTiming.irisRadius(1f, focus, size), 0f)
        // The flash peaks white early, then black takes over.
        assertEquals(1f to 0.16666667f, LaunchCurtainTiming.flash(0.15f).let { it.first to it.second })
        assertEquals(0f to 1f, LaunchCurtainTiming.flash(1f))
        assertEquals(0f, LaunchCurtainTiming.titleAlpha(LaunchCurtain.FLASH, 0.3f, still = false), 0f)
        assertEquals(1f, LaunchCurtainTiming.titleAlpha(LaunchCurtain.IRIS, 0.2f, still = true), 0f)
        assertTrue("the curtain closes before the 1100 ms hand-off", LaunchCurtainTiming.CLOSE_MS < 1100)
    }

    // endregion

    // region Settings

    @Test fun introAndTransitionsRowsCycleAndReset() {
        val look = ThemeLook(null, "FLAT_GLASS")
        val setup = ThemeSetup(activeName = "Boot", looks = mapOf("" to look), packBootVideo = true)
        val s = XrossSettings(effectOverrides = EffectOverrides(launchCurtain = "IRIS"))
        val tree = MenuTree(MenuSnapshot(settings = s, themes = setup))
        assertEquals(listOf("disp:fx:boot_intro", "disp:fx:launch_curtain"), tree.list(MenuTree.DISPLAY_INTRO).map { it.id })
        val top = tree.list(MenuTree.DISPLAY).associateBy { it.id }
        assertEquals("Theme pack (Video) · Iris", top[MenuTree.DISPLAY_INTRO]!!.subtitle)
        val rows = tree.list(MenuTree.DISPLAY_INTRO).associateBy { it.id }
        val intro = rows.getValue("disp:fx:boot_intro")
        assertEquals(listOf("Theme pack (Video)", "Video", "Shader", "Off"), intro.menu.map { it.label })
        assertEquals(NodeAction.Run(Command.CycleEffect(EffectSlot.BOOT_INTRO)), intro.action)
        assertEquals(listOf("Theme pack (Bloom)", "Bloom", "Fade to black", "Iris", "Flash"), rows.getValue("disp:fx:launch_curtain").menu.map { it.label })
        assertEquals("Iris", rows.getValue("disp:fx:launch_curtain").menu.single { it.selected }.label)
        // Without a video the pack's intro is the fade-in; a pack's shader shows as Shader.
        assertEquals("Theme pack (Off)", EffectSlot.BOOT_INTRO.packLabel(look))
        assertEquals("Theme pack (Shader)", EffectSlot.BOOT_INTRO.packLabel(look.copy(cosmetics = Cosmetics(bootIntro = BootIntro.SHADER))))
        assertEquals("Theme pack (Video)", EffectSlot.BOOT_INTRO.packLabel(look.copy(cosmetics = Cosmetics(bootIntro = BootIntro.SHADER)), bootVideo = true))
        assertEquals("VIDEO", EffectSlot.BOOT_INTRO.next(""))
        assertEquals("", EffectSlot.LAUNCH_CURTAIN.next("FLASH"))
        assertEquals("FADE_BLACK", EffectSlot.LAUNCH_CURTAIN.normalize("fade"))
        // Muted, E-Ink: the row says why it won't play.
        val muted = MenuTree(MenuSnapshot(settings = XrossSettings(bootSound = false), themes = setup)).list(MenuTree.DISPLAY_INTRO)
        assertTrue(muted.first().subtitle!!.endsWith("Off while the startup sound is off"))
        val eInk = MenuTree(MenuSnapshot(settings = XrossSettings(quickProfile = "MONOCHROME_EINK"), themes = setup)).list(MenuTree.DISPLAY_INTRO)
        assertTrue(eInk.first().subtitle!!.endsWith("Off in Monochrome E-Ink"))
        assertTrue(eInk.last().subtitle!!.endsWith("Plain black in Monochrome E-Ink"))
        // Reset to theme defaults clears both.
        val set = XrossSettings(effectOverrides = EffectOverrides(bootIntro = "SHADER", launchCurtain = "FLASH"))
        assertEquals("Clears 2 effects", MenuTree(MenuSnapshot(settings = set, themes = setup)).list(MenuTree.DISPLAY).single { it.id == "disp:reset" }.subtitle)
        assertEquals(EffectOverrides.NONE, ThemeDefaults.reset(set).effectOverrides)
    }

    @Test fun introAndCurtainRoundTripThroughSettingsJson() {
        val s = XrossSettings(effectOverrides = EffectOverrides(bootIntro = "OFF", launchCurtain = "FADE_BLACK"))
        val back = ConfigCodec.decodeSettings(XrossSettings(), ConfigCodec.encodeSettings(s)).effectOverrides
        assertEquals(s.effectOverrides, back)
        assertEquals(BootIntro.OFF, back.cosmetics.bootIntro)
        assertEquals(LaunchCurtain.FADE_BLACK, back.cosmetics.launchCurtain)
        val edited = """{"display": {"effects": {"bootIntro": "movie", "launchCurtain": "wipe"}}}"""
        assertEquals(EffectOverrides(bootIntro = "VIDEO"), ConfigCodec.decodeSettings(XrossSettings(), edited).effectOverrides)
    }

    // endregion

    // region ViewModel

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** A cold-start ViewModel over a pack in the container's themes folder, settled once the intro is decided. */
    private fun coldStart(container: AppContainer, cues: MutableList<UiCue>): XmbViewModel {
        container.sounds.cueRequested = { cues += it }
        val vm = XmbViewModel(context, container, coldBoot = true, storageAccess = { false })
        val until = System.currentTimeMillis() + 5_000
        while (vm.state.value.bootPending && System.currentTimeMillis() < until) {
            idle()
            Thread.sleep(10)
        }
        idle()
        assertFalse("the intro is decided", vm.state.value.bootPending)
        return vm
    }

    @Test fun aKeySkipsTheBootVideoAndTheNextOneReachesTheMenu() {
        val container = AppContainer(context)
        pack("bootpack", "", mapOf("boot.mp4" to 1000), dir = container.environment.current.themes)
        container.settings.update { it.copy(themePack = "bootpack") }
        val cues = ArrayList<UiCue>()
        val vm = coldStart(container, cues)
        val boot = vm.state.value.boot
        assertNotNull(boot)
        assertEquals(BootMode.VIDEO, boot!!.plan.mode)
        assertEquals("the video replaces the opening cue", emptyList<UiCue>(), cues)
        val category = vm.state.value.categoryIndex
        vm.onAction(NavAction.RIGHT)
        assertTrue("the first key skips", vm.state.value.boot!!.ending)
        assertEquals(category, vm.state.value.categoryIndex)
        vm.onAction(NavAction.RIGHT)
        assertEquals("the next key reaches the menu", category + 1, vm.state.value.categoryIndex)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(BootSequence.CROSSFADE_MS))
        assertNull(vm.state.value.boot)
    }

    @Test fun aKeyBeforeTheIntroIsDecidedSkipsItInAdvance() {
        val container = AppContainer(context)
        pack("early", "", mapOf("boot.mp4" to 1000), dir = container.environment.current.themes)
        container.settings.update { it.copy(themePack = "early") }
        val cues = ArrayList<UiCue>()
        container.sounds.cueRequested = { cues += it }
        val vm = XmbViewModel(context, container, coldBoot = true, storageAccess = { false })
        assertTrue(vm.state.value.bootPending)
        val category = vm.state.value.categoryIndex
        vm.onAction(NavAction.RIGHT)
        val until = System.currentTimeMillis() + 5_000
        while (vm.state.value.bootPending && System.currentTimeMillis() < until) {
            idle()
            Thread.sleep(10)
        }
        idle()
        val boot = vm.state.value.boot!!
        assertTrue("straight to the crossfade", boot.ending)
        assertEquals("no video", BootMode.FADE, boot.plan.mode)
        assertEquals(listOf(UiCue.OPENING), cues)
        assertEquals("the key only skipped", category, vm.state.value.categoryIndex)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(BootSequence.CROSSFADE_MS))
        assertNull(vm.state.value.boot)
    }

    @Test fun leavingBeforeTheIntroIsDecidedNeverStartsTheVideo() {
        val container = AppContainer(context)
        pack("away", "", mapOf("boot.mp4" to 1000), dir = container.environment.current.themes)
        container.settings.update { it.copy(themePack = "away") }
        val vm = XmbViewModel(context, container, coldBoot = true, storageAccess = { false })
        vm.onStopped()
        val until = System.currentTimeMillis() + 5_000
        while (vm.state.value.bootPending && System.currentTimeMillis() < until) {
            idle()
            Thread.sleep(10)
        }
        idle()
        assertEquals(BootMode.FADE, vm.state.value.boot?.plan?.mode)
        assertTrue(vm.state.value.boot!!.ending)
    }

    @Test fun theDefaultPackFadesInUnderTheOpeningCue() {
        val container = AppContainer(context)
        val cues = ArrayList<UiCue>()
        val vm = coldStart(container, cues)
        assertNull(vm.state.value.boot)
        assertEquals(listOf(UiCue.OPENING), cues)
        val category = vm.state.value.categoryIndex
        vm.onAction(NavAction.RIGHT)
        assertEquals(category + 1, vm.state.value.categoryIndex)
    }

    @Test fun aShaderIntroEndsByItselfAndAWarmStartShowsNothing() {
        val container = AppContainer(context)
        container.settings.update { it.copy(effectOverrides = EffectOverrides(bootIntro = "SHADER")) }
        val cues = ArrayList<UiCue>()
        val vm = coldStart(container, cues)
        assertEquals(BootMode.SHADER, vm.state.value.boot?.plan?.mode)
        assertEquals(listOf(UiCue.OPENING), cues)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(BootSequence.SHADER_MS))
        assertTrue(vm.state.value.boot!!.ending)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(BootSequence.CROSSFADE_MS))
        assertNull(vm.state.value.boot)

        val warm = XmbViewModel(context, container, coldBoot = false, storageAccess = { false }).also { idle() }
        assertFalse(warm.state.value.bootPending)
        assertNull(warm.state.value.boot)
    }

    // endregion
}
