package dev.xross.launcher

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.xross.launcher.theme.pack.ThemePackLoader
import dev.xross.launcher.theme.shader.BoundImage
import dev.xross.launcher.theme.shader.ImageFilter
import dev.xross.launcher.theme.shader.ImageSize
import dev.xross.launcher.theme.shader.ImageWrap
import dev.xross.launcher.theme.shader.ShaderImage
import dev.xross.launcher.theme.shader.ShaderImagePlan
import dev.xross.launcher.theme.shader.ShaderImageSource
import dev.xross.launcher.theme.shader.ShaderLibrary
import dev.xross.launcher.theme.shader.ShaderPreset
import dev.xross.launcher.theme.shader.ShaderRef
import dev.xross.launcher.ui.WaveRenderer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Live backgrounds § 1: `effects.shaderImages` decoding (strings and objects, bad paths, wrap and
 * filter names, images with a preset), the cascade, the program key following each image, the
 * upload sizes and the 8 MP budget, `uFocus` easing, and the prelude's new uniforms.
 */
@RunWith(RobolectricTestRunner::class)
class ShaderImagesTest {
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

    private val frag = "void main() { fragColor = texture(uImage0, gl_FragCoord.xy / uResolution); }\n"

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun ref(images: String, preset: String? = null, path: String? = "scene.frag"): Pair<ShaderRef?, List<String>> {
        val problems = ArrayList<String>()
        return ShaderRef.of("theme", preset, path, problems, json(images)) to problems
    }

    // region Decoding

    @Test fun stringsAndObjectsDecode() {
        val (r, problems) = ref(
            """["art/painting.webp", {"path": "art/depth.png", "wrap": "clamp", "filter": "LINEAR", "mipmap": false},
               {"path": "art/noise.png", "wrap": "REPEAT"}, {"path": "px.png", "filter": "nearest", "mipmap": true, "wrap": "mirror"}]""",
        )
        assertTrue(problems.toString(), problems.isEmpty())
        assertEquals(
            listOf(
                ShaderImage("art/painting.webp"),
                ShaderImage("art/depth.png"),
                ShaderImage("art/noise.png", wrap = ImageWrap.REPEAT),
                ShaderImage("px.png", ImageWrap.MIRROR, ImageFilter.NEAREST, mipmap = true),
            ),
            r!!.images,
        )
        assertEquals(ShaderPreset.CUSTOM, r.preset)
    }

    @Test fun aSingleStringIsOneImage() {
        val (r, problems) = ref("\"a.jpg\"")
        assertTrue(problems.isEmpty())
        assertEquals(listOf(ShaderImage("a.jpg")), r!!.images)
    }

    @Test fun badEntriesAreReportedAndKeepTheirSlot() {
        val (r, problems) = ref(
            """["../out.png", "notes.txt", {"path": "ok.png", "wrap": "TILE", "filter": "CUBIC", "mipmap": "yes", "gamma": 2}, "last.png"]""",
        )
        assertEquals(listOf(null, null, ShaderImage("ok.png"), ShaderImage("last.png")), r!!.images)
        assertTrue(problems.toString(), problems.any { "shaderImages[0]" in it && "inside the pack" in it })
        assertTrue(problems.toString(), problems.any { "shaderImages[1]" in it && "PNG, JPEG or WebP" in it })
        assertTrue(problems.toString(), problems.any { "shaderImages[2].wrap" in it && "TILE" in it })
        assertTrue(problems.toString(), problems.any { "shaderImages[2].filter" in it && "CUBIC" in it })
        assertTrue(problems.toString(), problems.any { "shaderImages[2].mipmap" in it })
        assertTrue(problems.toString(), problems.any { "shaderImages[2].gamma" in it })
    }

    @Test fun trailingBadEntriesAndExtraSlotsAreDropped() {
        val (r, problems) = ref("""["a.png", "b.png", "c.png", "d.png", "e.png"]""")
        assertEquals(4, r!!.images.size)
        assertTrue(problems.toString(), problems.any { "at most 4" in it })
        val (r2, _) = ref("""["a.png", "bad.gif"]""")
        assertEquals(listOf(ShaderImage("a.png")), r2!!.images)
    }

    @Test fun imagesWithAPresetOrNoShaderAreAProblem() {
        val (preset, p1) = ref("""["a.png"]""", preset = "BOKEH", path = null)
        assertEquals(ShaderRef(ShaderPreset.BOKEH), preset)
        assertTrue(p1.toString(), p1.any { "shaderImages is only read with" in it })
        val (none, p2) = ref("""["a.png"]""", preset = null, path = null)
        assertNull(none)
        assertTrue(p2.toString(), p2.any { "only read together with a CUSTOM shader" in it })
    }

    // endregion

    // region Loading and keys

    @Test fun loaderFindsImagesAndTheCascadeCarriesThem() {
        pack(
            "painted",
            """"effects": {"shaderPath": "scene.frag", "shaderImages": ["art/painting.png", "art/missing.png"]},
               "variants": [{"id": "night", "name": "Night", "effects": {"shaderPath": "scene.frag", "shaderImages": ["art/night.png"]}}]""",
            mapOf("scene.frag" to frag, "art/painting.png" to "png", "art/night.png" to "png"),
        )
        val t = loader.load("painted")
        assertTrue(t.problems.toString(), t.problems.any { "art/missing.png not found" in it })
        assertEquals(setOf("art/painting.png", "art/night.png"), t.shaderImages.keys)
        assertTrue(t.files.toString(), "art/painting.png" in t.files && "art/night.png" in t.files)

        val base = t.shaderLibrary.spec(t.shaderLibrary.keyOf(t.look().shader))!!
        val night = t.shaderLibrary.spec(t.shaderLibrary.keyOf(t.look("night").shader))!!
        assertNotEquals("same shader, other images: another program", base.key, night.key)
        assertEquals(2, base.images.size)
        assertEquals("art/painting.png", base.images[0]!!.source!!.file!!.name.let { "art/$it" })
        assertNull("a missing image is an empty slot", base.images[1]!!.source)
        assertEquals(listOf(ShaderImage("art/night.png")), night.requested)
    }

    @Test fun replacingAnImageChangesTheKey() {
        val dir = pack(
            "swap",
            """"effects": {"shaderPath": "scene.frag", "shaderImages": ["a.png"]}""",
            mapOf("scene.frag" to frag, "a.png" to "one"),
        )
        fun key() = loader.load("swap").let { it.shaderLibrary.keyOf(it.look().shader) }
        val before = key()
        assertTrue(before, before.startsWith("custom:scene.frag:"))
        assertEquals(before, key())
        File(dir, "a.png").apply { writeText("a longer image"); setLastModified(lastModified() + 5_000) }
        assertNotEquals(before, key())
    }

    @Test fun keyFoldsEveryInputButKeepsTheOldKeyWithoutImages() {
        assertEquals("custom:a.frag:" + ShaderLibrary.digest(frag), ShaderLibrary.customKey("a.frag", frag, emptyList()))
        val src = ShaderImageSource(bytes = 10, modified = 1)
        val k = ShaderLibrary.customKey("a.frag", frag, listOf(BoundImage(ShaderImage("a.png"), src)))
        assertNotEquals(k, ShaderLibrary.customKey("a.frag", frag, listOf(BoundImage(ShaderImage("a.png", wrap = ImageWrap.REPEAT), src))))
        assertNotEquals(k, ShaderLibrary.customKey("a.frag", frag, listOf(BoundImage(ShaderImage("a.png"), src.copy(modified = 2)))))
        assertNotEquals(k, ShaderLibrary.customKey("a.frag", frag, listOf(BoundImage(ShaderImage("a.png"), null))))
        assertEquals("custom:a.frag:missing", ShaderLibrary.customKey("a.frag", null, listOf(BoundImage(ShaderImage("a.png"), src))))
    }

    // endregion

    // region Sizes and budget

    @Test fun longSideIsCappedKeepingTheAspect() {
        assertEquals(ImageSize(2048, 1024), ShaderImagePlan.scaled(ImageSize(4096, 2048), 2048))
        assertEquals(ImageSize(1000, 3), ShaderImagePlan.scaled(ImageSize(1000, 3), 2048))
        assertEquals(ImageSize(1, 2048), ShaderImagePlan.scaled(ImageSize(3, 8000), 2048))
    }

    @Test fun slotsPastTheBudgetAreDroppedInOrder() {
        val big = ImageSize(2048, 1536) // 3 MP: two use 6 of the 8
        val plan = ShaderImagePlan.fit(listOf(big, null, big, ImageSize(4096, 4096), ImageSize(64, 64)), 2048)
        assertEquals("the 4 MP one doesn't fit, the small one after it does", listOf(big, null, big, null, ImageSize(64, 64)), plan)
        val full = ImageSize(2048, 2048)
        assertEquals("two full-size images are exactly the budget", listOf(full, full, null), ShaderImagePlan.fit(listOf(full, full, ImageSize(1, 1)), 2048))
        val small = ShaderImagePlan.fit(listOf(ImageSize(512, 512)), 2048, budget = 100)
        assertEquals(listOf<ImageSize?>(null), small)
    }

    // endregion

    // region Focus and prelude

    @Test fun focusEasesAndSettlesExactly() {
        val shown = floatArrayOf(0.5f, 0.5f)
        WaveRenderer.easeFocus(shown, 0.2f, 0.8f, WaveRenderer.focusStep(1f / 60f))
        assertTrue(shown[0] < 0.5f && shown[0] > 0.2f)
        assertTrue(shown[1] > 0.5f && shown[1] < 0.8f)
        repeat(200) { WaveRenderer.easeFocus(shown, 0.2f, 0.8f, WaveRenderer.focusStep(1f / 60f)) }
        assertEquals(0.2f, shown[0])
        assertEquals(0.8f, shown[1])
        WaveRenderer.easeFocus(shown, 0.9f, 0.1f, 1f)
        assertEquals(0.9f, shown[0])
        assertEquals(0f, WaveRenderer.focusStep(0f))
    }

    @Test fun preludeDeclaresTheImageAndFocusUniforms() {
        val prelude = File("src/main/assets/shaders", WaveRenderer.PRELUDE_FILE).readText()
        for (u in listOf(
            "uniform vec2 uFocus;",
            "uniform sampler2D uImage0, uImage1, uImage2, uImage3;",
            "uniform vec4 uImageSize0, uImageSize1, uImageSize2, uImageSize3;",
        )) assertTrue(u, prelude.contains(u))
    }

    // endregion
}
