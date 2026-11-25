package dev.xross.launcher

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.xross.launcher.media.BackgroundVideoPolicy
import dev.xross.launcher.media.BackgroundVideoPolicy.Action
import dev.xross.launcher.theme.pack.EffectOverrides
import dev.xross.launcher.theme.pack.ExportScope
import dev.xross.launcher.theme.pack.LookExporter
import dev.xross.launcher.theme.pack.ThemePackLoader
import dev.xross.launcher.theme.pack.VideoFit
import dev.xross.launcher.theme.pack.VideoRef
import dev.xross.launcher.theme.shader.ShaderPreset
import dev.xross.launcher.theme.shader.ShaderRef
import dev.xross.launcher.ui.VideoPolicy
import dev.xross.launcher.ui.VideoTexture
import dev.xross.launcher.ui.WaveRenderer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertArrayEquals
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
 * Live backgrounds § 2: `effects.videoPath` / `videoFit` decoding, the cascade (VIDEO under the
 * wave, CUSTOM and presets kept, `none`, fit-only layers), the loader and exporter, VIDEO's
 * placement, the copy size, the player policy and the format probe, and the prelude.
 */
@RunWith(RobolectricTestRunner::class)
class BackgroundVideoTest {
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

    private fun ref(path: String?, fit: String? = null): Pair<VideoRef?, List<String>> {
        val problems = ArrayList<String>()
        return VideoRef.of("theme", path, fit, problems) to problems
    }

    // region Decoding

    @Test fun pathsFitsAndNone() {
        assertEquals(VideoRef("loops/rain.mp4"), ref("/loops\\rain.mp4").first)
        assertEquals(VideoRef("a.webm", VideoFit.FIT), ref("a.webm", "fit").first)
        assertEquals("COVER is FILL", VideoRef("a.mp4", VideoFit.FILL), ref("a.mp4", "COVER").first)
        assertEquals(VideoRef.NONE, ref("none").first)
        assertEquals(VideoRef.NONE, ref("OFF", "FIT").first)
        assertNull(ref(null).first)
        assertNull(ref("  ").first)
        assertEquals(VideoFit.FILL, VideoRef("a.mp4").shownFit)
    }

    @Test fun badPathsAndFitsAreReported() {
        val (out, p1) = ref("../x.mp4")
        assertNull(out)
        assertTrue(p1.toString(), p1.any { "videoPath" in it && "inside the pack" in it })
        val (gif, p2) = ref("loop.gif")
        assertNull(gif)
        assertTrue(p2.toString(), p2.any { "isn't an MP4 or WebM" in it })
        val (badFit, p3) = ref("a.mp4", "ZOOM")
        assertEquals(VideoRef("a.mp4"), badFit)
        assertTrue(p3.toString(), p3.any { "videoFit" in it && "ZOOM" in it })
    }

    @Test fun aFitAloneChangesTheFitAndKeepsTheVideo() {
        val base = VideoRef("a.mp4")
        val fitOnly = ref(null, "CENTER").first!!
        assertEquals(VideoRef("a.mp4", VideoFit.CENTER), base.over(fitOnly))
        assertEquals("a fit alone over no video is still no video", null, VideoRef.NONE.over(fitOnly).path)
        assertEquals(VideoRef("b.mp4", VideoFit.CENTER), base.over(fitOnly).over(VideoRef("b.mp4")))
        assertEquals(VideoRef(null, VideoFit.CENTER), base.over(fitOnly).over(VideoRef.NONE))
    }

    @Test fun videoKeysAreNotUnknownEffects() {
        pack("keys", """"effects": {"videoPath": "none", "video": "none", "videoFit": "FIT"}""")
        val t = loader.load("keys")
        assertFalse(t.problems.toString(), t.problems.any { "isn't" in it && "video" in it.lowercase() })
    }

    @Test fun theUserCantPickVideo() {
        assertNull(EffectOverrides(shaderPreset = "VIDEO").shader)
        assertEquals(ShaderPreset.BOKEH, EffectOverrides(shaderPreset = "BOKEH").shader)
    }

    // endregion

    // region Loader and cascade

    @Test fun aVideoWithTheWaveShowsVideo() {
        pack(
            "rainy",
            """"effects": {"videoPath": "loops/rain.mp4", "videoFit": "FIT"},
               "variants": [
                 {"id": "dry", "name": "Dry", "effects": {"videoPath": "none"}},
                 {"id": "orbs", "name": "Orbs", "effects": {"shaderPreset": "BOKEH"}},
                 {"id": "glass", "name": "Glass", "effects": {"shaderPath": "glass.frag"}},
                 {"id": "wide", "name": "Wide", "effects": {"videoFit": "STRETCH"}}
               ],
               "categoryOverrides": {"games": {"effects": {"videoPath": "loops/neon.webm"}}}""",
            mapOf("loops/rain.mp4" to "mp4", "loops/neon.webm" to "webm", "glass.frag" to "void main() { fragColor = texture(uVideo, gl_FragCoord.xy / uResolution); }\n"),
        )
        val t = loader.load("rainy")
        assertTrue(t.problems.toString(), t.problems.isEmpty())
        assertEquals(setOf("loops/rain.mp4", "loops/neon.webm"), t.videos.keys)
        assertTrue(t.files.toString(), "loops/rain.mp4" in t.files && "loops/neon.webm" in t.files)
        assertTrue("VIDEO is compiled ahead of time", t.shaderLibrary.specs.any { it.preset == ShaderPreset.VIDEO })

        val base = t.look()
        assertEquals(ShaderRef(ShaderPreset.VIDEO), base.shader)
        assertEquals(VideoRef("loops/rain.mp4", VideoFit.FIT), base.video)
        assertEquals(VideoFit.FIT, t.videoSource(base.video)!!.fit)
        assertEquals("rain.mp4", t.videoSource(base.video)!!.file.name)

        assertEquals(ShaderRef.WAVE, t.look("dry").shader)
        assertNull(t.videoSource(t.look("dry").video))
        assertEquals("a preset keeps its place", ShaderPreset.BOKEH, t.look("orbs").shader.preset)
        assertEquals("a CUSTOM shader reads uVideo", ShaderPreset.CUSTOM, t.look("glass").shader.preset)
        assertEquals("loops/rain.mp4", t.look("glass").video.path)
        assertEquals(VideoRef("loops/rain.mp4", VideoFit.STRETCH), t.look("wide").video)
        assertEquals(VideoRef("loops/neon.webm", VideoFit.FIT), t.look(category = "games").video)
    }

    @Test fun missingAndBundledVideosAreReported() {
        pack("gone", """"effects": {"videoPath": "loop.mp4"}""")
        val t = loader.load("gone")
        assertTrue(t.problems.toString(), t.problems.any { "Video loop.mp4 not found" in it })
        assertTrue(t.videos.isEmpty())
        assertFalse("no video file: VIDEO isn't compiled", t.shaderLibrary.specs.any { it.preset == ShaderPreset.VIDEO })
        assertNull(t.videoSource(t.look().video))
    }

    @Test fun replacingTheFileChangesTheSourceKey() {
        val dir = pack("swap", """"effects": {"videoPath": "a.mp4"}""", mapOf("a.mp4" to "one"))
        fun key() = loader.load("swap").let { it.videoSource(it.look().video)!!.key }
        val before = key()
        File(dir, "a.mp4").apply { writeText("a longer file"); setLastModified(lastModified() + 5_000) }
        assertTrue(before != key())
    }

    @Test fun exportsCarryTheVideo() {
        pack(
            "share",
            """"effects": {"videoPath": "a.mp4"},
               "variants": [{"id": "b", "name": "B", "effects": {"videoPath": "b.mp4", "videoFit": "FIT"}},
                            {"id": "c", "name": "C", "effects": {"videoFit": "CENTER"}}]""",
            mapOf("a.mp4" to "a", "b.mp4" to "b"),
        )
        val t = loader.load("share")
        val whole = LookExporter.export(t, ExportScope.WholePack, available = loader.packFiles(t.info))
        assertTrue(whole.files.map { it.path }.containsAll(listOf("a.mp4", "b.mp4")))

        val onlyB = LookExporter.export(t, ExportScope.Variant("b"))
        val fx = onlyB.manifest["effects"] as JsonObject
        assertEquals(JsonPrimitive("b.mp4"), fx["videoPath"])
        assertEquals(JsonPrimitive("FIT"), fx["videoFit"])
        assertEquals("the other variant's video stays home", listOf("b.mp4"), onlyB.files.map { it.path }.filter { it.endsWith(".mp4") })

        val onlyC = LookExporter.export(t, ExportScope.Variant("c"))
        val fc = onlyC.manifest["effects"] as JsonObject
        assertEquals("a fit-only variant keeps the base's video", JsonPrimitive("a.mp4"), fc["videoPath"])
        assertEquals(JsonPrimitive("CENTER"), fc["videoFit"])
    }

    // endregion

    // region Placement and copy

    @Test fun fitsPlaceTheFrame() {
        // A 16:9 video on a 9:16 target.
        assertArrayEquals(floatArrayOf(0f, 0f, 90f, 160f), VideoFit.STRETCH.place(160f, 90f, 90f, 160f), 0.01f)
        val fill = VideoFit.FILL.place(160f, 90f, 90f, 160f)
        assertEquals(160f, fill[3], 0.01f)
        assertTrue("FILL overflows sideways", fill[0] < 0f && fill[2] > 90f)
        val fit = VideoFit.FIT.place(160f, 90f, 90f, 160f)
        assertArrayEquals(floatArrayOf(0f, 54.6875f, 90f, 50.625f), fit, 0.01f)
        assertArrayEquals("CENTER never enlarges", floatArrayOf(40f, 70f, 20f, 20f), VideoFit.CENTER.place(20f, 20f, 100f, 160f), 0.01f)
        assertArrayEquals("no size yet: the whole target", floatArrayOf(0f, 0f, 90f, 160f), VideoFit.FIT.place(0f, 0f, 90f, 160f), 0.01f)
    }

    @Test fun theCopyIsCappedAt1280() {
        assertEquals(1280 to 720, VideoPolicy.copySize(1920, 1080))
        assertEquals(720 to 1280, VideoPolicy.copySize(2160, 3840))
        assertEquals(640 to 360, VideoPolicy.copySize(640, 360))
        assertEquals("unknown: 720p until the player says", 1280 to 720, VideoPolicy.copySize(0, 0))
        assertEquals(1280 to 1, VideoPolicy.copySize(4000, 1))
    }

    @Test fun theVideoUnitFollowsTheImages() {
        assertEquals(6, VideoTexture.UNIT)
    }

    // endregion

    // region Player policy and probe

    @Test fun policyPlaysOnlyWhenNothingStopsIt() {
        assertEquals(Action.PLAY, BackgroundVideoPolicy.action(hasVideo = true, started = true))
        assertEquals(Action.RELEASE, BackgroundVideoPolicy.action(hasVideo = false, started = true))
        assertEquals("a game in front frees the decoder", Action.RELEASE, BackgroundVideoPolicy.action(hasVideo = true, started = false))
        assertEquals(Action.RELEASE, BackgroundVideoPolicy.action(hasVideo = true, started = true, failed = true))
        assertEquals("still mode: a poster", Action.HOLD, BackgroundVideoPolicy.action(hasVideo = true, started = true, still = true))
        assertEquals(Action.HOLD, BackgroundVideoPolicy.action(hasVideo = true, started = true, covered = true))
        assertEquals(Action.HOLD, BackgroundVideoPolicy.action(hasVideo = true, started = true, standby = true))
        assertEquals("one decoder at a time", Action.HOLD, BackgroundVideoPolicy.action(hasVideo = true, started = true, otherVideo = true))
        assertEquals("stopped beats still", Action.RELEASE, BackgroundVideoPolicy.action(hasVideo = true, started = false, still = true))
    }

    @Test fun probeVerdicts() {
        assertEquals(BackgroundVideoPolicy.Probe.Ok, BackgroundVideoPolicy.probe("video/avc", 1280, 720, decoder = true))
        assertTrue(BackgroundVideoPolicy.probe(null, 0, 0, decoder = false) is BackgroundVideoPolicy.Probe.Unplayable)
        val none = BackgroundVideoPolicy.probe("video/av01", 3840, 2160, decoder = false)
        assertTrue(none.toString(), none is BackgroundVideoPolicy.Probe.Unplayable && "video/av01" in none.reason)
        val big = BackgroundVideoPolicy.probe("video/hevc", 2560, 1440, decoder = true)
        assertTrue(big.toString(), big is BackgroundVideoPolicy.Probe.Notice && "1920" in big.text)
    }

    // endregion

    @Test fun preludeAndPresetDeclareTheVideo() {
        val prelude = File("src/main/assets/shaders", WaveRenderer.PRELUDE_FILE).readText()
        for (u in listOf("uniform sampler2D uVideo;", "uniform vec4 uVideoSize;", "uniform vec4 uVideoRect;")) {
            assertTrue(u, prelude.contains(u))
        }
        val frag = File("src/main/assets/shaders", ShaderPreset.VIDEO.file!!).readText()
        assertTrue(frag.contains("uVideo") && frag.contains("uVideoRect"))
    }
}
