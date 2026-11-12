package dev.xross.launcher

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.xross.launcher.theme.BatteryLevel
import dev.xross.launcher.theme.pack.ThemeManifest
import dev.xross.launcher.theme.pack.ThemePackLoader
import dev.xross.launcher.theme.shader.FrameBudgetWatchdog
import dev.xross.launcher.theme.shader.FrameBudgetWatchdog.Verdict
import dev.xross.launcher.theme.shader.ProgramState
import dev.xross.launcher.theme.shader.ShaderCanary
import dev.xross.launcher.theme.shader.ShaderFallback
import dev.xross.launcher.theme.shader.ShaderHealth
import dev.xross.launcher.theme.shader.ShaderLibrary
import dev.xross.launcher.theme.shader.ShaderPreset
import dev.xross.launcher.theme.shader.ShaderRef
import dev.xross.launcher.theme.shader.ShaderSources
import dev.xross.launcher.theme.shader.ShaderStatus
import dev.xross.launcher.ui.WaveRenderer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
 * Theme 2.0 2b.4a, the shader host: `effects.shaderPreset` / `effects.shaderPath` decoding through
 * the cascade (base, variants, overrides, context rules), custom `shader.frag` loading and its
 * hot-reload key, the prelude and driver error lines, the frame-budget watchdog's decision, the
 * fallback rule (keep the program on screen until the next one links; WAVE on failure), the
 * crash-loop canary and the toasts.
 */
@RunWith(RobolectricTestRunner::class)
class ShaderHostTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule val tmp = TemporaryFolder()

    private val themes get() = File(tmp.root, "Xross/themes").apply { mkdirs() }
    private val loader by lazy { ThemePackLoader(context.assets) { themes } }

    private fun pack(id: String, body: String, files: Map<String, String> = emptyMap()): File {
        val dir = File(themes, id).apply { mkdirs() }
        File(dir, "theme.json").writeText("""{"schemaVersion": 2, "name": "$id", $body}""")
        for ((name, text) in files) File(dir, name).apply { parentFile?.mkdirs() }.writeText(text)
        return dir
    }

    private val customFrag = "void main() { fragColor = vec4(uAccent.rgb * fract(uTime), 1.0); }\n"

    // region Manifest decoding

    @Test fun presetNamesAreLenient() {
        assertEquals(ShaderPreset.ASCII_MATRIX, ShaderPreset.byName("ASCII_MATRIX"))
        assertEquals(ShaderPreset.ASCII_MATRIX, ShaderPreset.byName("ascii-matrix"))
        assertEquals(ShaderPreset.STARFIELD, ShaderPreset.byName(" Starfield "))
        assertEquals(ShaderPreset.CRT, ShaderPreset.byName("crt"))
        assertNull(ShaderPreset.byName("PLASMA"))
        assertNull(ShaderPreset.byName(""))
        assertNull(ShaderPreset.byName(null))
    }

    @Test fun effectsDecodeShaderPresetAndPath() {
        val m = ThemeManifest.JSON.decodeFromString(
            ThemeManifest.serializer(),
            """{"schemaVersion": 2, "name": "x", "effects": {"shaderPreset": "BOKEH", "shaderPath": null}}""",
        )
        assertEquals("BOKEH", m.effects?.shaderPreset)
        assertNull(m.effects?.shaderPath)
        // `shader` is accepted as a short alias.
        val alias = ThemeManifest.JSON.decodeFromString(ThemeManifest.serializer(), """{"name": "x", "effects": {"shader": "CRT"}}""")
        assertEquals("CRT", alias.effects?.shaderPreset)
    }

    @Test fun shaderRefRules() {
        val problems = ArrayList<String>()
        assertNull(ShaderRef.of("theme", null, null, problems))
        assertEquals(ShaderRef(ShaderPreset.STARFIELD), ShaderRef.of("theme", "starfield", null, problems))
        assertEquals(ShaderRef(ShaderPreset.CUSTOM, "shader.frag"), ShaderRef.of("theme", "CUSTOM", null, problems))
        // A path alone means CUSTOM; backslashes are normalized.
        assertEquals(ShaderRef(ShaderPreset.CUSTOM, "fx/glow.frag"), ShaderRef.of("theme", null, "fx\\glow.frag", problems))
        assertTrue(problems.toString(), problems.isEmpty())

        assertNull(ShaderRef.of("theme", "PLASMA", null, problems))
        assertTrue(problems.last(), problems.last().contains("no shader named PLASMA"))
        assertNull(ShaderRef.of("theme", "CUSTOM", "../other/shader.frag", problems))
        assertNull(ShaderRef.of("theme", "CUSTOM", "/sdcard/shader.frag", problems))
        assertNull(ShaderRef.of("theme", "CUSTOM", "shader.png", problems))
        // A path with a built-in preset is reported and ignored.
        assertEquals(ShaderRef(ShaderPreset.CRT), ShaderRef.of("rules[0].apply", "CRT", "x.frag", problems))
        assertTrue(problems.last().startsWith("rules[0].apply.effects.shaderPath"))
    }

    @Test fun defaultPackDrawsTheWave() {
        val t = loader.load(ThemePackLoader.DEFAULT_ID)
        assertEquals(ShaderRef.WAVE, t.shader)
        assertEquals(ShaderRef.WAVE, t.look().shader)
        assertEquals(listOf(ShaderLibrary.WAVE_KEY), t.shaderLibrary.specs.map { it.key })
    }

    @Test fun cascadePicksTheShaderOfTheLastLayer() {
        pack(
            "fx",
            """
            "palette": {"canvas": "#101010", "accent": "#FFFFFF"},
            "effects": {"shaderPreset": "BOKEH"},
            "variants": [{"id": "bios", "effects": {"shaderPreset": "ASCII_MATRIX"}}],
            "categoryOverrides": {"MUSIC": {"effects": {"shaderPreset": "STARFIELD"}}},
            "systemOverrides": {"dc": {"effects": {"shaderPreset": "CRT"}}},
            "rules": [{"match": {"battery": "<= 15"}, "apply": {"effects": {"shaderPreset": "WAVE"}}}]
            """,
        )
        val t = loader.load("fx")
        assertTrue(t.problems.toString(), t.problems.isEmpty())
        assertEquals(ShaderPreset.BOKEH, t.look().shader.preset)
        assertEquals(ShaderPreset.ASCII_MATRIX, t.look("bios").shader.preset)
        assertEquals(ShaderPreset.STARFIELD, t.look("bios", category = "music").shader.preset)
        assertEquals(ShaderPreset.CRT, t.look(category = "music", system = "dreamcast").shader.preset)
        assertEquals(ShaderPreset.WAVE, t.look(category = "music", battery = BatteryLevel(10, false)).shader.preset)
        assertEquals(ShaderPreset.STARFIELD, t.look(category = "music", battery = BatteryLevel(60, false)).shader.preset)

        // Every preset any layer can show is compiled ahead of time, WAVE first.
        assertEquals(
            listOf("WAVE", "BOKEH", "ASCII_MATRIX", "STARFIELD", "CRT"),
            t.shaderLibrary.specs.map { it.key },
        )
        assertEquals("STARFIELD", t.shaderLibrary.keyOf(t.look(category = "music").shader))
    }

    @Test fun unknownPresetsAreReportedAndIgnored() {
        pack("bad", """"effects": {"shaderPreset": "PLASMA"}, "categoryOverrides": {"GAMES": {"effects": {"shaderPreset": "LAVA"}}}""")
        val t = loader.load("bad")
        assertTrue(t.problems.any { it.startsWith("theme.effects.shaderPreset") })
        assertTrue(t.problems.any { it.startsWith("categoryOverrides.GAMES.effects.shaderPreset") })
        assertEquals(ShaderRef.WAVE, t.look(category = "games").shader)
    }

    // endregion

    // region Custom shaders

    @Test fun customShaderIsReadFromThePack() {
        pack("own", """"effects": {"shaderPreset": "CUSTOM"}""", mapOf("shader.frag" to customFrag))
        val t = loader.load("own")
        assertTrue(t.problems.toString(), t.problems.isEmpty())
        assertEquals(ShaderRef(ShaderPreset.CUSTOM, "shader.frag"), t.look().shader)
        assertEquals(customFrag, t.customShaders["shader.frag"])
        val spec = t.shaderLibrary.spec(t.shaderLibrary.keyOf(t.look().shader))!!
        assertEquals(ShaderPreset.CUSTOM, spec.preset)
        assertTrue(spec.untrusted)
        assertEquals(customFrag, spec.source)
        assertTrue(spec.key, spec.key.startsWith("custom:shader.frag:"))
    }

    @Test fun shaderPathIsReadFromTheFolderAndIgnoresCase() {
        pack(
            "paths",
            """"rules": [{"match": {"category": "VIDEO"}, "apply": {"effects": {"shaderPath": "fx/Glow.frag"}}}]""",
            mapOf("FX/glow.FRAG" to customFrag),
        )
        val t = loader.load("paths")
        assertTrue(t.problems.toString(), t.problems.isEmpty())
        val ref = t.look(category = "video").shader
        assertEquals(ShaderRef(ShaderPreset.CUSTOM, "fx/Glow.frag"), ref)
        assertEquals(customFrag, t.customShaders["fx/Glow.frag"])
        assertNotEquals(ShaderLibrary.WAVE_KEY, t.shaderLibrary.keyOf(ref))
    }

    @Test fun missingCustomShaderIsReportedAndKeyedAsMissing() {
        pack("none", """"effects": {"shaderPreset": "CUSTOM"}""")
        val t = loader.load("none")
        assertTrue(t.problems.toString(), t.problems.any { it.contains("shader.frag not found") })
        val spec = t.shaderLibrary.spec(t.shaderLibrary.keyOf(t.look().shader))!!
        assertNull(spec.source)
        assertTrue(spec.key.endsWith(":missing"))
    }

    @Test fun editedShaderGetsANewKeySoItIsRecompiled() {
        val dir = pack("edit", """"effects": {"shaderPreset": "CUSTOM"}""", mapOf("shader.frag" to customFrag))
        val before = loader.load("edit").let { it.shaderLibrary.keyOf(it.look().shader) }
        assertEquals(before, loader.load("edit").let { it.shaderLibrary.keyOf(it.look().shader) })
        File(dir, "shader.frag").writeText(customFrag.replace("fract", "sin"))
        val after = loader.load("edit").let { it.shaderLibrary.keyOf(it.look().shader) }
        assertNotEquals(before, after)
        assertTrue(after.startsWith("custom:shader.frag:"))
    }

    @Test fun oversizedOrBinaryShadersAreRefused() {
        pack("big", """"effects": {"shaderPreset": "CUSTOM"}""", mapOf("shader.frag" to "//" + "x".repeat(70 * 1024)))
        assertTrue(loader.load("big").problems.any { it.contains("larger than 64 KB") })
        pack("bin", """"effects": {"shaderPreset": "CUSTOM"}""", mapOf("shader.frag" to "void main() {}\u0000"))
        assertTrue(loader.load("bin").problems.any { it.contains("can't be read as text") })
    }

    // endregion

    // region Prelude and driver errors

    @Test fun preludeGoesInFrontUnlessTheFileHasItsOwnVersion() {
        val prelude = "#version 300 es\nprecision highp float;\nuniform float uTime;\nout vec4 fragColor;\n"
        val composed = ShaderSources.compose(prelude, "void main() { fragColor = vec4(1.0); }")
        assertEquals(4, composed.lineOffset)
        assertTrue(composed.text.startsWith(prelude))
        val own = "  #version 300 es\nprecision mediump float;\nout vec4 o;\nvoid main() { o = vec4(1.0); }"
        assertEquals(own, ShaderSources.compose(prelude, own).text)
        assertEquals(0, ShaderSources.compose(prelude, own).lineOffset)
    }

    @Test fun firstErrorLineIsRelativeToTheUsersFile() {
        // Adreno / ANGLE / glslang style.
        assertEquals(
            "line 3: 'foo' : undeclared identifier",
            ShaderSources.firstError("ERROR: 0:19: 'foo' : undeclared identifier\nERROR: 1 compilation errors.  No code generated.", lineOffset = 16),
        )
        // Mali style, with a warning before the error.
        assertEquals(
            "line 7: L0002: Undeclared variable 'bar'",
            ShaderSources.firstError("WARNING: 0:2: P0001: Extension directive\n0:7: L0002: Undeclared variable 'bar'"),
        )
        // An error in the prelude itself (a uniform the file redeclares) keeps its text.
        assertEquals("'uTime' : redefinition", ShaderSources.firstError("ERROR: 0:5: 'uTime' : redefinition", lineOffset = 16))
        assertEquals("the driver gave no reason", ShaderSources.firstError(""))
        assertEquals("Link failed: too many uniforms", ShaderSources.firstError("Link failed: too many uniforms"))
    }

    // endregion

    // region Built-in sources

    private val shaderDir = File("src/main/assets/shaders")

    @Test fun everyPresetShipsASourceAndThePreludeDeclaresThePublicUniforms() {
        for (preset in ShaderPreset.entries) {
            val file = preset.file ?: continue
            assertTrue("$file in assets", File(shaderDir, file).isFile)
            assertFalse("$file leaves #version to the prelude", File(shaderDir, file).readText().trimStart().startsWith("#version"))
        }
        assertTrue(File(shaderDir, WaveRenderer.VERTEX_FILE).isFile)
        val prelude = File(shaderDir, WaveRenderer.PRELUDE_FILE).readText()
        assertTrue(prelude.startsWith("#version 300 es"))
        for (u in listOf(
            "uniform float uTime;", "uniform vec2 uResolution;", "uniform vec4 uColorTop;", "uniform vec4 uColorBottom;",
            "uniform vec4 uAccent;", "uniform float uBatteryLevel;", "uniform float uAudioBass;", "uniform float uAudioBeat;",
        )) assertTrue(u, prelude.contains(u))
    }

    @Test fun waveUsesThePublicUniformNames() {
        val wave = File(shaderDir, "wave.frag").readText()
        for (old in listOf("uRes", "uTop", "uBottom", "uWave")) assertFalse(old, Regex("\\b$old\\b").containsMatchIn(wave))
        for (name in listOf("uResolution", "uColorTop", "uColorBottom", "uAccent", "uTime")) assertTrue(name, wave.contains(name))
    }

    // endregion

    // region Watchdog

    private val budget60 = 16_666_667L
    private val ms = 1_000_000L

    /** Feeds [frames] samples of [sampleNs], one per 16.7 ms, from t = 0. */
    private fun FrameBudgetWatchdog.feed(frames: Int, sampleNs: (Int) -> Long): Verdict {
        var v = verdict
        for (i in 0 until frames) v = record(sampleNs(i), i * budget60)
        return v
    }

    @Test fun watchdogLimitIsSixtyPercentOfTheBudget() {
        val dog = FrameBudgetWatchdog(budget60)
        assertEquals(10_000_000L, dog.limitNs)
    }

    @Test fun cheapShaderPassesAfterTheWindow() {
        val dog = FrameBudgetWatchdog(budget60)
        assertEquals(Verdict.MEASURING, dog.feed(60) { 3 * ms }) // 1 s: still measuring
        assertEquals(Verdict.PASSED, dog.feed(130) { 3 * ms })
        assertEquals(3 * ms, dog.p90Ns)
    }

    @Test fun slowShaderTripsOnP90() {
        val dog = FrameBudgetWatchdog(budget60)
        // 80% of frames are fine, 20% blow the limit: p90 is a slow frame.
        assertEquals(Verdict.TRIPPED, dog.feed(200) { if (it % 5 == 0) 14 * ms else 4 * ms })
        assertEquals(14 * ms, dog.p90Ns)
        assertTrue(dog.describe(), dog.describe().startsWith("p90 14.0 ms > 10.0 ms"))
    }

    @Test fun occasionalSpikesUnderTheP90Pass() {
        val dog = FrameBudgetWatchdog(budget60)
        // 5% slow frames sit above the 90th percentile.
        assertEquals(Verdict.PASSED, dog.feed(200) { if (it % 20 == 0) 30 * ms else 6 * ms })
    }

    @Test fun hopelessShaderTripsAfterThreeSamples() {
        val dog = FrameBudgetWatchdog(budget60)
        // 344 ms a frame (the A55's reading of a 6000-iteration loop): out after 1 skipped + 3.
        assertEquals(Verdict.MEASURING, dog.feed(FrameBudgetWatchdog.SKIP_FIRST + 2) { 344 * ms })
        assertEquals(Verdict.TRIPPED, dog.record(344 * ms, 4 * budget60))
        assertEquals(344 * ms, dog.p90Ns)
    }

    @Test fun verySlowShaderTripsOnTheEarlyMedian() {
        val dog = FrameBudgetWatchdog(budget60)
        // 40 ms is under 4× the budget, so it waits for the median check at 6 samples.
        assertEquals(Verdict.MEASURING, dog.feed(FrameBudgetWatchdog.SKIP_FIRST + FrameBudgetWatchdog.EARLY_SAMPLES - 1) { 40 * ms })
        assertEquals(Verdict.TRIPPED, dog.record(40 * ms, 10 * budget60))
    }

    @Test fun oneFastFrameAmongSlowOnesWaitsForTheMedian() {
        val dog = FrameBudgetWatchdog(budget60)
        dog.record(0, 0) // skipped
        dog.record(80 * ms, 1)
        dog.record(5 * ms, 2)
        assertEquals(Verdict.MEASURING, dog.record(80 * ms, 3))
    }

    @Test fun windowNeedsEnoughSamples() {
        val dog = FrameBudgetWatchdog(budget60)
        // A few sparse samples (glFinish sampling, idle pacing) over 3 s: not enough to decide.
        repeat(FrameBudgetWatchdog.SKIP_FIRST) { dog.record(4 * ms, 0) }
        for (i in 0 until 5) dog.record(4 * ms, i * 600 * ms)
        assertEquals(Verdict.MEASURING, dog.verdict)
        for (i in 0 until FrameBudgetWatchdog.MIN_SAMPLES) dog.record(4 * ms, 3_000 * ms + i * ms)
        assertEquals(Verdict.PASSED, dog.verdict)
    }

    @Test fun firstSamplesAreIgnoredAndVerdictsStick() {
        val dog = FrameBudgetWatchdog(budget60)
        // The first-use frame at 80 ms doesn't count.
        repeat(FrameBudgetWatchdog.SKIP_FIRST) { dog.record(80 * ms, it.toLong()) }
        assertEquals(0, dog.sampleCount)
        assertEquals(Verdict.PASSED, dog.feed(150) { 2 * ms })
        assertEquals(Verdict.PASSED, dog.record(500 * ms, 10_000 * ms))
    }

    @Test fun budgetFollowsTheActiveRate() {
        // 120 Hz paced to 60: the budget is two vsyncs; 90 Hz runs every vsync.
        assertEquals(2, WaveRenderer.activeStride(120f))
        val at120 = FrameBudgetWatchdog(8_333_333L * WaveRenderer.activeStride(120f))
        assertEquals(9_999_999L, at120.limitNs)
        val at90 = FrameBudgetWatchdog(11_111_111L * WaveRenderer.activeStride(90f))
        assertEquals(6_666_666L, at90.limitNs)
    }

    @Test fun percentileIsNearestRank() {
        val v = longArrayOf(5, 1, 4, 2, 3, 10, 9, 8, 7, 6)
        assertEquals(9L, FrameBudgetWatchdog.percentile(v, 10, 90))
        assertEquals(5L, FrameBudgetWatchdog.percentile(v, 10, 50))
        assertEquals(10L, FrameBudgetWatchdog.percentile(v, 10, 100))
        assertEquals(5L, FrameBudgetWatchdog.percentile(v, 1, 90))
    }

    // endregion

    // region Fallback states

    private fun states(vararg pairs: Pair<String, ProgramState>): (String) -> ProgramState? = pairs.toMap()::get

    @Test fun readyProgramIsDrawn() {
        assertEquals("BOKEH", ShaderFallback.choose("BOKEH", "WAVE", states("WAVE" to ProgramState.READY, "BOKEH" to ProgramState.READY)))
    }

    @Test fun programOnScreenStaysUntilTheNextOneLinks() {
        val s = states("WAVE" to ProgramState.READY, "BOKEH" to ProgramState.READY, "CRT" to ProgramState.PENDING)
        assertEquals("BOKEH", ShaderFallback.choose("CRT", "BOKEH", s))
        // Nothing on screen yet (first frame): the wave.
        assertEquals("WAVE", ShaderFallback.choose("CRT", null, s))
        // Not prepared at all yet: same as pending.
        assertEquals("BOKEH", ShaderFallback.choose("STARFIELD", "BOKEH", s))
    }

    @Test fun failedProgramFallsBackToTheWave() {
        val s = states("WAVE" to ProgramState.READY, "BOKEH" to ProgramState.READY, "custom:a" to ProgramState.FAILED)
        assertEquals("WAVE", ShaderFallback.choose("custom:a", "BOKEH", s))
        // A failed program on screen isn't kept while the next one compiles.
        val t = states("WAVE" to ProgramState.READY, "custom:a" to ProgramState.FAILED, "custom:b" to ProgramState.PENDING)
        assertEquals("WAVE", ShaderFallback.choose("custom:b", "custom:a", t))
    }

    @Test fun statusFollowsTheWantedProgram() {
        val spec = ShaderLibrary.of(listOf(ShaderRef(ShaderPreset.CRT)), emptyMap()).spec("CRT")
        assertEquals(ShaderStatus.Ready("CRT", "CRT"), ShaderFallback.status(spec, "CRT", ProgramState.READY, "", null))
        assertEquals(ShaderStatus.Compiling("CRT", "CRT"), ShaderFallback.status(spec, "CRT", ProgramState.PENDING, "", null))
        assertEquals(ShaderStatus.Compiling("CRT", "CRT"), ShaderFallback.status(spec, "CRT", null, "", null))
        val failed = ShaderFallback.status(spec, "CRT", ProgramState.FAILED, "line 3: oops", ShaderStatus.Cause.COMPILE) as ShaderStatus.Failed
        assertEquals("Shader CRT: line 3: oops · showing the wave", failed.message)
        val slow = ShaderStatus.Failed("k", "shader.frag", "p90 14.0 ms > 10.0 ms", ShaderStatus.Cause.TOO_SLOW)
        assertTrue(slow.message, slow.message.contains("too slow"))
    }

    @Test fun libraryKeepsWaveFirstAndDeduplicates() {
        val lib = ShaderLibrary.of(
            listOf(ShaderRef.WAVE, ShaderRef(ShaderPreset.BOKEH), ShaderRef(ShaderPreset.BOKEH), ShaderRef(ShaderPreset.CUSTOM, "a.frag")),
            mapOf("a.frag" to customFrag),
        )
        assertEquals(3, lib.specs.size)
        assertEquals(ShaderLibrary.WAVE_KEY, lib.specs.first().key)
        assertEquals(ShaderLibrary.WAVE_KEY, lib.keyOf(ShaderRef(ShaderPreset.CRT))) // not in the library
        assertEquals("custom:a.frag:" + ShaderLibrary.digest(customFrag), lib.keyOf(ShaderRef(ShaderPreset.CUSTOM, "a.frag")))
    }

    // endregion

    // region Canary

    private val cache get() = File(tmp.root, "Xross/cache")

    @Test fun canaryMarkerLivesWhileArmed() {
        val canary = ShaderCanary { cache }
        val marker = File(cache, ShaderCanary.MARKER)
        assertFalse(marker.exists())
        canary.arm("custom:a")
        assertEquals("custom:a", marker.readText().trim())
        canary.arm("custom:b")
        assertEquals(listOf("custom:a", "custom:b"), marker.readLines())
        canary.disarm("custom:a")
        assertEquals(listOf("custom:b"), marker.readLines())
        canary.disarm("custom:b")
        assertFalse(marker.exists())
        canary.arm("custom:c")
        canary.disarmAll()
        assertFalse(marker.exists())
    }

    @Test fun markerLeftByACrashBlocksTheShaderAtNextStart() {
        ShaderCanary { cache }.arm("custom:shader.frag:abc") // the process dies here
        val next = ShaderCanary { cache }
        assertEquals(listOf("custom:shader.frag:abc"), next.recover())
        assertFalse(File(cache, ShaderCanary.MARKER).exists())
        assertTrue(next.isBlocked("custom:shader.frag:abc"))
        // An edit (new digest) is tried again; the block survives further restarts.
        assertFalse(next.isBlocked("custom:shader.frag:def"))
        val later = ShaderCanary { cache }
        assertEquals(emptyList<String>(), later.recover())
        assertTrue(later.isBlocked("custom:shader.frag:abc"))
    }

    @Test fun cleanExitLeavesNothingBlocked() {
        val canary = ShaderCanary { cache }
        canary.arm("custom:a")
        canary.disarm("custom:a") // drawn its proving frames

        assertEquals(emptyList<String>(), ShaderCanary { cache }.recover())
        assertTrue(ShaderCanary { cache }.blockedKeys().isEmpty())
    }

    @Test fun canaryNeverThrowsWhenItCantWrite() {
        val file = File(tmp.root, "not-a-dir").apply { writeText("x") }
        val canary = ShaderCanary { File(file, "cache") }
        canary.arm("custom:a")
        canary.disarm("custom:a")
        canary.block("custom:b")
        assertEquals(emptyList<String>(), canary.recover())
        assertTrue(canary.isBlocked("custom:b")) // still remembered in memory
    }

    @Test fun healthAnnouncesARecoveredCrashOnceAndEachFailureOnce() = runBlocking {
        ShaderCanary { cache }.arm("custom:x:1")
        val health = ShaderHealth(ShaderCanary { cache })
        assertEquals(listOf("custom:x:1"), health.recovered)
        assertTrue(health.canary.isBlocked("custom:x:1"))
        // The renderer later reports the blocked program: no second toast for it.
        health.report(ShaderStatus.Failed("custom:x:1", "shader.frag", "blocked after a crash", ShaderStatus.Cause.CRASHED))
        val fail = ShaderStatus.Failed("BOKEH", "Bokeh", "line 2: oops", ShaderStatus.Cause.COMPILE)
        health.report(fail)
        assertEquals(fail, health.status.value)
        health.report(ShaderStatus.Ready("WAVE", "Wave"))
        health.report(fail) // shown again later: no repeat toast
        assertEquals(fail, health.status.value)
        val notices = withTimeoutOrNull(1_000) { health.notices.take(2).toList() }
        assertNotNull(notices)
        assertTrue(notices!![0], notices[0].contains("stopped Xross last time"))
        assertEquals(fail.message, notices[1])
        assertNull(withTimeoutOrNull(100) { health.notices.first() })
    }

    // endregion
}
