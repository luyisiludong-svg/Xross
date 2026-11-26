package dev.xross.launcher

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.xross.launcher.theme.pack.EffectOverrides
import dev.xross.launcher.theme.pack.ExportScope
import dev.xross.launcher.theme.pack.LookExporter
import dev.xross.launcher.theme.pack.ThemePackLoader
import dev.xross.launcher.theme.pack.VideoFit
import dev.xross.launcher.theme.shader.ImageWrap
import dev.xross.launcher.theme.shader.LayerAnchor
import dev.xross.launcher.theme.shader.LayerBlend
import dev.xross.launcher.theme.shader.LayerRect
import dev.xross.launcher.theme.shader.Scene
import dev.xross.launcher.theme.shader.SceneBudget
import dev.xross.launcher.theme.shader.SceneLayer
import dev.xross.launcher.theme.shader.SceneResolution
import dev.xross.launcher.theme.shader.ShaderPreset
import dev.xross.launcher.theme.shader.ShaderRef
import dev.xross.launcher.theme.shader.Sway
import dev.xross.launcher.theme.shader.Vec2
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
 * Live backgrounds § 3, slice 3a: `scene.json` parsing (every layer type, the shared fields,
 * motions), per-layer problems, the budget, `effects.scenePath` in the cascade, the loader, the
 * scene's key, the poster, and the exporter.
 */
@RunWith(RobolectricTestRunner::class)
class SceneTest {
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

    private fun parse(text: String): Pair<Scene?, List<String>> {
        val problems = ArrayList<String>()
        return Scene.parse("scene.json", text, problems) to problems
    }

    private val example = """
        {
          "resolution": "sharp",
          "poster": "poster.webp",
          "layers": [
            { "type": "image",  "path": "sky.webp",  "fit": "COVER", "parallax": 0.1, "scroll": [4, 0], "wrap": "REPEAT" },
            { "type": "video",  "path": "clouds.mp4", "blend": "SCREEN", "opacity": 0.6 },
            { "type": "image",  "path": "city.png",  "anchor": "BOTTOM", "parallax": 0.4, "audio": { "scale": 0.02 } },
            { "type": "sprite", "path": "neon.png",  "frames": [4, 2], "fps": 8, "rect": [0.62, 0.40, 0.12, 0.10] },
            { "type": "shader", "path": "rain_glass.frag", "images": ["drops.png"] },
            { "type": "particles", "path": "ember.png", "rate": 30, "life": [3, 6], "velocity": [[-5, 5], [-40, -20]],
              "size": [4, 10], "color": ["#ffb354", "#00ff8024"], "parallax": 0.7 },
            { "type": "image",  "path": "rain_fg.png", "parallax": 1.0, "scroll": [0, 300], "wrap": "REPEAT", "blend": "ADD",
              "sway": { "angle": 2, "period": 5 }, "pulse": 0.03, "mask": "fg_mask.png" }
          ]
        }
    """.trimIndent()

    // region Parsing

    @Test fun thePlansExampleParses() {
        val (scene, problems) = parse(example)
        assertTrue(problems.toString(), problems.isEmpty())
        scene!!
        assertEquals(SceneResolution.SHARP, scene.resolution)
        assertEquals("poster.webp", scene.poster)
        assertEquals(listOf("image", "video", "image", "sprite", "shader", "particles", "image"), scene.layers.map { it.type })

        val sky = scene.layers[0] as SceneLayer.Image
        assertEquals(ImageWrap.REPEAT, sky.wrap)
        assertEquals("COVER is FILL", VideoFit.FILL, sky.look.fit)
        assertEquals(Vec2(4f, 0f), sky.look.motion.scroll)
        assertEquals(0.1f, sky.look.parallax, 0f)

        val clouds = scene.layers[1] as SceneLayer.Video
        assertEquals(LayerBlend.SCREEN, clouds.look.blend)
        assertEquals(0.6f, clouds.look.opacity, 0f)

        assertEquals(LayerAnchor.BOTTOM, scene.layers[2].look.anchor)
        assertEquals(0.02f, scene.layers[2].look.motion.audio!!.scale, 0f)

        val neon = scene.layers[3] as SceneLayer.Sprite
        assertEquals(4 to 2, neon.columns to neon.rows)
        assertEquals("every cell by default", 8, neon.count)
        assertEquals(LayerRect(0.62f, 0.40f, 0.12f, 0.10f), neon.look.rect)

        val glass = scene.layers[4] as SceneLayer.Shader
        assertEquals(listOf("drops.png"), glass.images.map { it?.path })

        val embers = scene.layers[5] as SceneLayer.Particles
        assertEquals(180, embers.count)
        assertEquals(Vec2(-40f, -20f), embers.velocityY)
        assertEquals(0xFFFFB354.toInt() to 0x00FF8024, embers.colors)

        val fg = scene.layers[6]
        assertEquals(Sway(angle = 2f, period = 5f), fg.look.motion.sway)
        assertEquals("a number alone is a scale", 0.03f, fg.look.motion.pulse!!.scale, 0f)
        assertEquals("fg_mask.png", fg.look.mask)

        assertEquals(
            listOf("poster.webp", "sky.webp", "city.png", "neon.png", "drops.png", "ember.png", "rain_fg.png", "fg_mask.png"),
            scene.imagePaths,
        )
        assertEquals(listOf("clouds.mp4"), scene.videoPaths)
        assertEquals(listOf("rain_glass.frag"), scene.shaderPaths)
        assertTrue(scene.hasShaderLayers)
    }

    @Test fun badLayersAreReportedByIndexAndDropped() {
        val (scene, problems) = parse(
            """{"layers": [
                 {"type": "image", "path": "ok.png"},
                 {"type": "hologram", "path": "x.png"},
                 {"type": "image", "path": "../outside.png"},
                 {"type": "sprite", "path": "s.png", "frames": [0, 2]},
                 {"type": "image", "path": "b.png", "opacity": 2, "glow": true, "blend": "OVERLAY", "rect": [0, 0, 0, 1]},
                 {"type": "particles", "life": [5, 1]},
                 {"type": "video", "path": "clip.gif"},
                 {"type": "particles", "rate": 10, "color": "pink"}
               ]}""",
        )
        scene!!
        val text = problems.joinToString("\n")
        assertTrue(text, "scene.json: layers[1].type: hologram" in text)
        assertTrue(text, "scene.json: layers[2].path: ../outside.png must stay inside the pack" in text)
        assertTrue(text, "scene.json: layers[3].frames" in text)
        assertTrue(text, "scene.json: layers[4].opacity" in text)
        assertTrue(text, "scene.json: layers[4].glow isn't a property of a image layer" in text)
        assertTrue(text, "scene.json: layers[4].blend: no blend named OVERLAY" in text)
        assertTrue(text, "scene.json: layers[4].rect" in text)
        assertTrue(text, "scene.json: layers[5].rate" in text)
        assertTrue(text, "scene.json: layers[6].path: clip.gif isn't an MP4 or WebM video" in text)
        assertTrue(text, "scene.json: layers[7].color" in text)
        // Bad properties fall back to their defaults; bad files and types drop the layer.
        assertEquals(listOf("ok.png", "b.png", null), scene.layers.map { (it as? SceneLayer.Image)?.path ?: (it as? SceneLayer.Particles)?.path })
        assertEquals(1f, scene.layers[1].look.opacity, 0f)
        assertEquals(0xFFFFFFFF.toInt() to 0x00FFFFFF, (scene.layers[2] as SceneLayer.Particles).colors)
    }

    @Test fun unusableFilesAreNoScene() {
        assertNull(parse("{ not json").first)
        assertTrue(parse("{ not json").second.single().startsWith("scene.json isn't valid JSON"))
        assertNull(parse("[]").first)
        val (empty, problems) = parse("""{"layers": [], "mood": "calm"}""")
        assertNull(empty)
        assertTrue(problems.toString(), problems.any { "mood isn't a scene property" in it })
        assertTrue(problems.toString(), problems.any { "no usable layer" in it })
    }

    @Test fun thePosterIsTheFirstImageLayerWithoutOne() {
        val scene = parse("""{"layers": [{"type": "particles", "rate": 5}, {"type": "image", "path": "a.png"}, {"type": "image", "path": "b.png"}]}""").first!!
        assertNull(scene.posterPath)
        assertEquals("a.png", scene.poster)
        assertNull(parse("""{"layers": [{"type": "video", "path": "a.mp4"}]}""").first!!.poster)
    }

    // endregion

    // region Budget

    private fun image(rect: LayerRect? = null) = SceneLayer.Image("a.png", look = dev.xross.launcher.theme.shader.LayerLook(rect = rect))

    @Test fun theBudgetDropsWhatDoesntFit() {
        val dropped = ArrayList<Pair<Int, String>>()
        // Eight full screens fit; the ninth doesn't, but a small sprite above it still does.
        val layers = List(9) { image() } + image(LayerRect(0.5f, 0.5f, 0.5f, 0.0001f))
        val kept = SceneBudget.fit(layers) { i, why -> dropped += i to why }
        assertEquals(9, kept.size)
        assertEquals(listOf(8), dropped.map { it.first })
        assertTrue(dropped.single().second, "8 screens" in dropped.single().second)

        val videos = SceneBudget.fit(List(3) { SceneLayer.Video("v.mp4", dev.xross.launcher.theme.shader.LayerLook(rect = LayerRect(0f, 0f, 0.1f, 0.1f))) })
        assertEquals(2, videos.size)
        val shaders = SceneBudget.fit(List(5) { SceneLayer.Shader("s.frag", look = dev.xross.launcher.theme.shader.LayerLook(rect = LayerRect(0f, 0f, 0.1f, 0.1f))) })
        assertEquals(4, shaders.size)
        val many = SceneBudget.fit(List(20) { image(LayerRect(0f, 0f, 0.1f, 0.1f)) })
        assertEquals(SceneBudget.MAX_LAYERS, many.size)
    }

    @Test fun particlesAreCountedAndShadersCostTwice() {
        fun embers(rate: Float) = SceneLayer.Particles(null, rate, Vec2(1f, 10f), Vec2.ZERO, Vec2.ZERO, Vec2(2f, 2f), 0 to 0)
        assertEquals(1000, embers(100f).count)
        val kept = SceneBudget.fit(listOf(embers(100f), embers(150f), embers(50f)))
        assertEquals("1500 doesn't fit beside 1000; 500 still does", listOf(1000, 500), kept.map { (it as SceneLayer.Particles).count })
        assertEquals(2f, SceneBudget.cost(SceneLayer.Shader("s.frag")), 0f)
        assertEquals(0.25f, LayerRect(-0.5f, 0.5f, 1f, 1f).coverage, 1e-6f)

        val (scene, problems) = parse(
            """{"layers": [""" + List(5) { """{"type": "shader", "path": "s$it.frag"}""" }.joinToString() + "]}",
        )
        assertEquals("four shader layers make eight screens", 4, scene!!.layers.size)
        assertTrue(problems.toString(), problems.single().startsWith("scene.json: layers[4] (shader) is left out"))
    }

    // endregion

    // region scenePath

    private fun ref(shader: String? = null, path: String? = null, scene: String? = null): Pair<ShaderRef?, List<String>> {
        val problems = ArrayList<String>()
        return ShaderRef.of("theme", shader, path, problems, null, scene) to problems
    }

    @Test fun scenePathIsAScene() {
        assertEquals(ShaderRef(ShaderPreset.SCENE, "scenes/city.json"), ref(scene = "/scenes\\city.json").first)
        assertEquals("SCENE alone reads scene.json", ShaderRef(ShaderPreset.SCENE, "scene.json"), ref(shader = "scene").first)
        val (both, p) = ref(shader = "BOKEH", path = "x.frag", scene = "s.json")
        assertEquals(ShaderRef(ShaderPreset.SCENE, "s.json"), both)
        assertTrue(p.toString(), p.any { "replaces effects.shaderPreset" in it } && p.any { "shaderPath isn't read with a scene" in it })
        for (bad in listOf("../s.json", "scene.png", "theme.json")) {
            val (none, pb) = ref(scene = bad)
            assertNull(bad, none)
            assertTrue(pb.toString(), pb.any { "scenePath" in it })
        }
        assertNull(EffectOverrides(shaderPreset = "SCENE").shader)
    }

    // endregion

    // region Loader, key, poster, export

    private val city = """{"poster": "art/poster.png", "layers": [
        {"type": "image", "path": "art/sky.png"},
        {"type": "shader", "path": "glass.frag", "images": ["art/drops.png"]},
        {"type": "video", "path": "loop.mp4", "rect": [0, 0, 0.5, 0.5]}
    ]}"""

    private val cityFiles = mapOf(
        "scene.json" to city,
        "art/poster.png" to "p",
        "art/sky.png" to "s",
        "art/drops.png" to "d",
        "glass.frag" to "void main() { fragColor = vec4(1.0); }\n",
        "loop.mp4" to "m",
    )

    @Test fun aSceneCascadesAndLoadsItsFiles() {
        pack(
            "city",
            """"effects": {"scenePath": "scene.json", "wallpaperPath": "art/sky.png"},
               "variants": [{"id": "plain", "name": "Plain", "effects": {"shaderPreset": "WAVE"}}],
               "categoryOverrides": {"games": {"effects": {"scene": "night.json"}}}""",
            cityFiles + ("night.json" to """{"layers": [{"type": "image", "path": "art/night.png"}]}"""),
        )
        val t = loader.load("city")
        assertEquals(listOf("Shader image art/night.png not found in the pack"), t.problems)
        assertEquals(setOf("scene.json", "night.json"), t.scenes.keys)
        val src = t.scenes.getValue("scene.json")
        assertEquals(setOf("art/poster.png", "art/sky.png", "art/drops.png"), src.images.keys)
        assertEquals(setOf("loop.mp4"), src.videos.keys)
        assertEquals(setOf("glass.frag"), src.shaders.keys)
        assertTrue(t.files.toString(), t.files.containsAll(cityFiles.keys))

        assertEquals(ShaderRef(ShaderPreset.SCENE, "scene.json"), t.look().shader)
        assertEquals(ShaderRef.WAVE, t.look("plain").shader)
        assertEquals(ShaderRef(ShaderPreset.SCENE, "night.json"), t.look(category = "games").shader)

        val key = t.shaderLibrary.keyOf(t.look().shader)
        assertEquals(src.key, key)
        assertTrue(key, key.startsWith("scene:scene.json:"))
        val spec = t.shaderLibrary.spec(key)!!
        assertEquals("the poster is uImage0", listOf("art/poster.png"), spec.images.map { it?.image?.path })
        assertNotNull(spec.images.single()!!.source)
        assertFalse("only the built-in poster program runs for now", spec.untrusted)
        val night = t.shaderLibrary.spec(t.shaderLibrary.keyOf(t.look(category = "games").shader))!!
        assertNull("a missing poster binds an empty slot", night.images.single()!!.source)
    }

    @Test fun anUnreadableSceneIsReportedAndKeyedMissing() {
        pack("broken", """"effects": {"scenePath": "scene.json"}""", mapOf("scene.json" to "{\"layers\": 3}"))
        val t = loader.load("broken")
        assertTrue(t.problems.toString(), t.problems.any { "layers: give a list" in it })
        assertTrue(t.scenes.isEmpty())
        val spec = t.shaderLibrary.spec(t.shaderLibrary.keyOf(t.look().shader))!!
        assertEquals("scene:scene.json:missing", spec.key)
        assertNull(spec.scene)
    }

    @Test fun editingAnyFileChangesTheKey() {
        val dir = pack("edit", """"effects": {"scenePath": "scene.json"}""", cityFiles)
        fun key() = loader.load("edit").scenes.getValue("scene.json").key
        val first = key()
        assertEquals("stable", first, key())
        File(dir, "art/drops.png").apply { writeText("a bigger image"); setLastModified(lastModified() + 5_000) }
        val second = key()
        assertNotEquals(first, second)
        File(dir, "glass.frag").writeText("void main() { fragColor = vec4(0.5); }\n")
        val third = key()
        assertNotEquals(second, third)
        File(dir, "scene.json").writeText(city.replace("\"rect\"", "\"opacity\": 0.5, \"rect\""))
        assertNotEquals(third, key())
    }

    @Test fun exportsCarryTheSceneAndItsFiles() {
        pack(
            "share",
            """"effects": {"scenePath": "scene.json"},
               "variants": [{"id": "orbs", "name": "Orbs", "effects": {"shaderPreset": "BOKEH"}},
                            {"id": "night", "name": "Night", "effects": {"scene": "night.json"}}]""",
            cityFiles + ("night.json" to """{"layers": [{"type": "image", "path": "art/night.png"}]}""") + ("art/night.png" to "n"),
        )
        val t = loader.load("share")
        val base = LookExporter.export(t, ExportScope.Variant(""))
        assertEquals(JsonPrimitive("scene.json"), (base.manifest["effects"] as JsonObject)["scenePath"])
        val basePaths = base.files.map { it.path }
        assertTrue(basePaths.toString(), basePaths.containsAll(cityFiles.keys))

        val night = LookExporter.export(t, ExportScope.Variant("night"))
        val fx = night.manifest["effects"] as JsonObject
        assertEquals("a variant's scene is flattened under the canonical keys", JsonPrimitive("SCENE"), fx["shaderPreset"])
        assertEquals(JsonPrimitive("night.json"), fx["scenePath"])
        val nightPaths = night.files.map { it.path }
        assertTrue(nightPaths.toString(), "night.json" in nightPaths && "art/night.png" in nightPaths)
        assertTrue("the base's scene stays home: $nightPaths", cityFiles.keys.none { it in nightPaths })

        val orbs = LookExporter.export(t, ExportScope.Variant("orbs"))
        val paths = orbs.files.map { it.path }
        assertTrue("the scene stays home: $paths", cityFiles.keys.none { it in paths })
    }

    // region Drawing (3b)

    @Test fun layersDecodeOnceEachAndPostersStandInForUndrawnScenes() {
        pack(
            "drawn",
            """"effects": {"scenePath": "scene.json"},
               "variants": [{"id": "dots", "name": "Dots", "effects": {"scene": "dots.json"}}]""",
            mapOf(
                "scene.json" to """{"resolution": "sharp", "layers": [
                    {"type": "image", "path": "sky.png", "mask": "m.png"},
                    {"type": "particles", "path": "spark.png", "rate": 5},
                    {"type": "sprite", "path": "sky.png", "frames": [2, 1]},
                    {"type": "image", "path": "fg.png", "mask": "m.png"}
                ]}""",
                "dots.json" to """{"layers": [{"type": "particles", "rate": 5}]}""",
                "sky.png" to "s", "m.png" to "m", "fg.png" to "f", "spark.png" to "p",
            ),
        )
        val t = loader.load("drawn")
        val src = t.scenes.getValue("scene.json")
        assertTrue(dev.xross.launcher.ui.GlScene.draws(src))
        assertEquals(
            "each image once, bottom layer first; particles' images wait for 3d",
            listOf("sky.png", "m.png", "fg.png"),
            dev.xross.launcher.ui.GlScene.textureImages(src).map { it.image.path },
        )
        assertTrue(dev.xross.launcher.ui.GlScene.textureImages(src).all { it.image.mipmap && it.source != null })
        assertFalse("nothing drawable yet: the poster program shows it", dev.xross.launcher.ui.GlScene.draws(t.scenes.getValue("dots.json")))

        val lib = t.shaderLibrary
        assertEquals(1920, dev.xross.launcher.ui.WaveRenderer.longSide(lib, lib.keyOf(t.look().shader)))
        assertEquals(1280, dev.xross.launcher.ui.WaveRenderer.longSide(lib, lib.keyOf(t.look("dots").shader)))
        assertEquals(1280, dev.xross.launcher.ui.WaveRenderer.longSide(lib, "WAVE"))
        assertEquals("0.82 on the A55", 0.8205f, dev.xross.launcher.ui.WaveRenderer.renderScale(2340, 1080, 1920), 1e-4f)
    }

    // endregion

    @Test fun sceneKeysAreNotUnknownEffects() {
        pack("keys", """"effects": {"scenePath": "s.json"}, "variants": [{"id": "v", "name": "V", "effects": {"scene": "s.json"}}]""", mapOf("s.json" to """{"layers": [{"type": "particles", "rate": 1}]}"""))
        val t = loader.load("keys")
        assertTrue(t.problems.toString(), t.problems.isEmpty())
    }

    // endregion
}
