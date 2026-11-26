package dev.xross.launcher.theme.shader

import androidx.compose.runtime.Immutable
import dev.xross.launcher.theme.pack.ThemeManifest
import dev.xross.launcher.theme.pack.VideoFit
import dev.xross.launcher.theme.pack.VideoRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import java.io.File
import java.util.Locale
import kotlin.math.ceil

/*
 * Layered scenes (Live backgrounds § 3, slice 3a): the `scene.json` an `effects.scenePath` names,
 * as a model. A scene is a stack of layers drawn bottom to top; the whole scene is one entry in
 * the [ShaderLibrary] (preset [ShaderPreset.SCENE]), so variants, rules, the fallback and the
 * watchdog treat it like any other background program.
 *
 * Coordinates are the pack author's: fractions of the screen from its top-left corner, y down
 * (`rect`, `anchor`); speeds and sizes are in render pixels at 1280 on the long side, y down
 * (`velocity`, `size`); `scroll` is in texels of the layer's own image per second.
 *
 * Image, sprite and video layers are drawn as posed quads (slices 3b and 3c, [ScenePoser]); shader
 * layers are full-screen § 1 programs over the layers below them (`uBelow`, 3c). Particle layers
 * are skipped until 3d. A scene with no drawn layer shows its [Scene.poster].
 */

/** `scene.resolution`: how sharp the surface is while the scene shows. */
enum class SceneResolution(val longSide: Int) {
    /** Today's 1280 cap; fine for soft art and math. */
    SOFT(1280),

    /** 1920 on the long side (0.82 on a 2340 × 1080 phone), about 2.25× the pixels of SOFT. */
    SHARP(1920),
}

/** How a layer is composited onto the ones below it. */
enum class LayerBlend { NORMAL, ADD, SCREEN, MULTIPLY }

/** Where a `fit` layer sits when it doesn't cover the screen: a point of the screen and of the layer, (0, 0) top-left. */
enum class LayerAnchor(val x: Float, val y: Float) {
    CENTER(0.5f, 0.5f),
    TOP(0.5f, 0f),
    BOTTOM(0.5f, 1f),
    LEFT(0f, 0.5f),
    RIGHT(1f, 0.5f),
    TOP_LEFT(0f, 0f),
    TOP_RIGHT(1f, 0f),
    BOTTOM_LEFT(0f, 1f),
    BOTTOM_RIGHT(1f, 1f),
}

/** A pair of numbers: an offset, a speed, or for ranges the low and high ends. */
@Immutable
data class Vec2(val x: Float, val y: Float) {
    companion object {
        val ZERO = Vec2(0f, 0f)
    }
}

/** `[x, y, width, height]`: fractions of the screen from its top-left corner. */
@Immutable
data class LayerRect(val x: Float, val y: Float, val width: Float, val height: Float) {
    /** The part of the screen it covers, 0..1. */
    val coverage: Float get() {
        val w = (minOf(1f, x + width) - maxOf(0f, x)).coerceAtLeast(0f)
        val h = (minOf(1f, y + height) - maxOf(0f, y)).coerceAtLeast(0f)
        return w * h
    }
}

/** `sway`: a rotation and / or offset on a sine of [period] seconds. A number alone is an angle in degrees. */
@Immutable
data class Sway(val angle: Float = 0f, val offset: Vec2 = Vec2.ZERO, val period: Float = 6f)

/** `pulse`: the scale and / or opacity breathing on a sine of [period] seconds. A number alone is a scale amount. */
@Immutable
data class Pulse(val scale: Float = 0f, val opacity: Float = 0f, val period: Float = 4f)

/**
 * How much an input moves a layer when it is at 1.0: `audio` (the bass, a beat adds to it),
 * `transition` (a category switch) or `daylight` (0 at night .. 1 at noon). Each amount is added
 * to the layer's resting value.
 */
@Immutable
data class Drive(val scale: Float = 0f, val opacity: Float = 0f, val offset: Vec2 = Vec2.ZERO, val angle: Float = 0f)

/** A layer's movement: a steady [scroll] plus [sway] and [pulse], and what the inputs add. */
@Immutable
data class LayerMotion(
    val scroll: Vec2 = Vec2.ZERO,
    val sway: Sway? = null,
    val pulse: Pulse? = null,
    val audio: Drive? = null,
    val transition: Drive? = null,
    val daylight: Drive? = null,
) {
    val still: Boolean get() = this == NONE

    companion object {
        val NONE = LayerMotion()
    }
}

/** What every layer has: how it is placed, blended and moved. */
@Immutable
data class LayerLook(
    val opacity: Float = 1f,
    val blend: LayerBlend = LayerBlend.NORMAL,
    /** Where it is drawn; null: placed by [fit] and [anchor]. */
    val rect: LayerRect? = null,
    val fit: VideoFit = VideoFit.FILL,
    val anchor: LayerAnchor = LayerAnchor.CENTER,
    /** 0 stays still, 1 moves the most with the focus (and tilt). */
    val parallax: Float = 0f,
    /** An image whose alpha (straight, not premultiplied) masks the layer; pack-relative. */
    val mask: String? = null,
    val motion: LayerMotion = LayerMotion.NONE,
) {
    /** The part of the screen it may cover (a fit that doesn't cover is counted as covering, an upper bound). */
    val coverage: Float get() = rect?.coverage ?: 1f
}

/** One layer of a [Scene]. */
@Immutable
sealed interface SceneLayer {
    val look: LayerLook

    /** The type as written in `scene.json`. */
    val type: String

    /** The images it reads (its own, its mask): what the scene's key and the exporter fold in. */
    val imagePaths: List<String>

    /** A still image (`png`, `jpg`, `webp`). */
    @Immutable
    data class Image(val path: String, val wrap: ImageWrap = ImageWrap.CLAMP, override val look: LayerLook = LayerLook()) : SceneLayer {
        override val type get() = "image"
        override val imagePaths get() = listOfNotNull(path, look.mask)
    }

    /** A looping, muted video (§ 2's decoder and copy pass). Folder packs only. */
    @Immutable
    data class Video(val path: String, override val look: LayerLook = LayerLook()) : SceneLayer {
        override val type get() = "video"
        override val imagePaths get() = listOfNotNull(look.mask)
    }

    /** A sprite sheet of [columns] × [rows] cells, [count] of them played at [fps], left to right, top to bottom. */
    @Immutable
    data class Sprite(
        val path: String,
        val columns: Int,
        val rows: Int,
        val fps: Float = 8f,
        val count: Int = columns * rows,
        override val look: LayerLook = LayerLook(),
    ) : SceneLayer {
        override val type get() = "sprite"
        override val imagePaths get() = listOfNotNull(path, look.mask)
    }

    /** A § 1 program over the layers below it (`uBelow`), with up to four images of its own. Pack code: canary-guarded. */
    @Immutable
    data class Shader(val path: String, val images: List<ShaderImage?> = emptyList(), override val look: LayerLook = LayerLook()) : SceneLayer {
        override val type get() = "shader"
        override val imagePaths get() = images.mapNotNull { it?.path } + listOfNotNull(look.mask)
    }

    /**
     * GPU particles: born at [rate] per second, each living [life] seconds (a range), moving at
     * [velocity] (x and y ranges) and [size] across (a range), its colour going from [colors]'
     * first to its last. [path] is the particle's image; null draws a soft dot.
     */
    @Immutable
    data class Particles(
        val path: String?,
        val rate: Float,
        val life: Vec2,
        val velocityX: Vec2,
        val velocityY: Vec2,
        val size: Vec2,
        val colors: Pair<Int, Int>,
        override val look: LayerLook = LayerLook(),
    ) : SceneLayer {
        override val type get() = "particles"
        override val imagePaths get() = listOfNotNull(path, look.mask)

        /** How many are alive at most: the instance count of its one draw. */
        val count: Int get() = ceil(rate * life.y).toInt().coerceAtLeast(1)

        /** The part of the screen they cover at most, each a [size]-wide square (at the reference 1280 × 720). */
        val coverage: Float get() {
            val side = (size.x + size.y) / 2f
            return (count * side * side / (Scene.REFERENCE_W * Scene.REFERENCE_H)).coerceAtMost(1f)
        }
    }
}

/** A parsed `scene.json`: its [layers] bottom to top, after the budget. */
@Immutable
data class Scene(
    val resolution: SceneResolution = SceneResolution.SOFT,
    /** The image shown before the renderer is ready and when the scene is retired; else the first image layer. */
    val posterPath: String? = null,
    val layers: List<SceneLayer> = emptyList(),
) {
    /** What stands in for the scene: [posterPath], else the first image layer's image. */
    val poster: String? get() = posterPath ?: layers.firstNotNullOfOrNull { (it as? SceneLayer.Image)?.path }

    /** Every image file it reads (poster, layers, masks, shader layers' images), in order. */
    val imagePaths: List<String> get() = (listOfNotNull(posterPath) + layers.flatMap { it.imagePaths }).distinct()

    val videoPaths: List<String> get() = layers.mapNotNull { (it as? SceneLayer.Video)?.path }.distinct()

    /** The video file its video layers show (one per scene: [SceneBudget.MAX_VIDEOS]); null for none. */
    val video: String? get() = videoPaths.firstOrNull()

    val shaderPaths: List<String> get() = layers.mapNotNull { (it as? SceneLayer.Shader)?.path }.distinct()

    /** Pack code runs in it: the scene is canary-guarded as one unit. */
    val hasShaderLayers: Boolean get() = layers.any { it is SceneLayer.Shader }

    /** Screens of pixels it draws per frame: [SceneBudget.overdraw]. */
    val overdraw: Float get() = SceneBudget.overdraw(layers)

    companion object {
        /** The file `"shaderPreset": "SCENE"` reads without `scenePath`. */
        const val DEFAULT_FILE = "scene.json"

        /** Reference screen for [SceneLayer.Particles] sizes and speeds. */
        const val REFERENCE_W = 1280f
        const val REFERENCE_H = 720f

        private val JSON = Json { isLenient = true; allowTrailingComma = true; allowComments = true }

        /**
         * [text] (the file at [path]) as a scene. Every problem is reported in [problems] as
         * `<path>: layers[3].frames: …`; an unusable layer is dropped and the rest kept. Null when
         * the file isn't a JSON object, or keeps no layer.
         */
        fun parse(path: String, text: String, problems: MutableList<String>): Scene? {
            val root = runCatching { JSON.parseToJsonElement(text) }.getOrElse {
                problems += "$path isn't valid JSON (${it.message?.lineSequence()?.firstOrNull()?.take(80)})"
                return null
            } as? JsonObject ?: run {
                problems += "$path: a scene is a JSON object with \"layers\""
                return null
            }
            val p = Problems(path, problems)
            for (key in root.keys - SCENE_KEYS) p += "$key isn't a scene property (${SCENE_KEYS.joinToString()})"
            val resolution = p.enumOf<SceneResolution>("resolution", root["resolution"]) ?: SceneResolution.SOFT
            val poster = root["poster"]?.let { p.image("poster", it) }
            val raw = when (val l = root["layers"]) {
                is JsonArray -> l.toList()
                null, is JsonNull -> emptyList()
                else -> {
                    p += "layers: give a list of layers"
                    emptyList()
                }
            }
            // Each usable layer with its index in the file, for the budget's messages.
            val parsed = raw.mapIndexedNotNull { i, item -> layer(p.at("layers[$i]"), item)?.let { i to it } }
            if (parsed.isEmpty()) {
                p += "the scene has no usable layer"
                return null
            }
            val layers = parsed.map { it.second }
            val kept = SceneBudget.fit(layers) { i, why -> p += "layers[${parsed[i].first}] (${layers[i].type}) is left out: $why" }
            return Scene(resolution, poster, kept)
        }

        private fun layer(p: Problems, item: JsonElement): SceneLayer? {
            val obj = item as? JsonObject ?: run {
                p += "a layer is an object with a \"type\""
                return null
            }
            val type = (obj["type"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase(Locale.ROOT)
            val own = when (type) {
                "image" -> setOf("path", "wrap")
                "video" -> setOf("path")
                "sprite" -> setOf("path", "frames", "fps", "count")
                "shader" -> setOf("path", "images")
                "particles" -> setOf("path", "rate", "life", "velocity", "size", "color")
                else -> {
                    p += "type: ${type ?: "missing"} (use image, video, sprite, shader or particles)"
                    return null
                }
            }
            for (key in obj.keys - own - COMMON_KEYS - "type") p += "$key isn't a property of a $type layer"
            // A shader layer draws its own pixels over its rect (else the screen): it isn't posed or blended.
            if (type == "shader") {
                for (key in obj.keys.filter { it in COMMON_KEYS && it !in SHADER_LOOK_KEYS }) {
                    p += "$key is ignored on a shader layer (it draws over its rect, else the whole screen, at its opacity)"
                }
            }
            val look = look(p, obj)
            return when (type) {
                "image" -> p.image("path", obj["path"])?.let { SceneLayer.Image(it, p.enumOf<ImageWrap>("wrap", obj["wrap"]) ?: ImageWrap.CLAMP, look) }
                "video" -> p.video("path", obj["path"])?.let { SceneLayer.Video(it, look) }
                "sprite" -> sprite(p, obj, look)
                "shader" -> p.shader("path", obj["path"])?.let { path ->
                    val images = ShaderImage.listAt(p.where("images"), obj["images"], p.sink)
                    SceneLayer.Shader(path, images.dropLastWhile { it == null }, look)
                }
                else -> particles(p, obj, look)
            }
        }

        private fun sprite(p: Problems, obj: JsonObject, look: LayerLook): SceneLayer.Sprite? {
            val path = p.image("path", obj["path"]) ?: return null
            val frames = p.pair("frames", obj["frames"])
            val cols = frames?.x?.toInt() ?: 0
            val rows = frames?.y?.toInt() ?: 0
            if (frames == null || cols < 1 || rows < 1 || cols > 64 || rows > 64 || frames.x != cols.toFloat() || frames.y != rows.toFloat()) {
                p += "frames: give [columns, rows], whole numbers from 1 to 64"
                return null
            }
            val fps = p.number("fps", obj["fps"], 0.1f, 60f) ?: 8f
            val count = obj["count"]?.let { c ->
                (c as? JsonPrimitive)?.intOrNull?.takeIf { it in 1..cols * rows } ?: run {
                    p += "count: a whole number from 1 to ${cols * rows}"
                    null
                }
            } ?: (cols * rows)
            return SceneLayer.Sprite(path, cols, rows, fps, count, look)
        }

        private fun particles(p: Problems, obj: JsonObject, look: LayerLook): SceneLayer.Particles? {
            val path = obj["path"]?.let { p.image("path", it) }
            val rate = p.number("rate", obj["rate"], 0.01f, MAX_PARTICLES.toFloat()) ?: run {
                if (obj["rate"] == null) p += "rate: give particles born per second"
                return null
            }
            val life = p.range("life", obj["life"], 0.05f, 60f) ?: Vec2(2f, 4f)
            val velocity = obj["velocity"]
            val (vx, vy) = when {
                velocity == null -> Vec2.ZERO to Vec2(-40f, -20f)
                velocity is JsonArray && velocity.size == 2 && velocity.all { it is JsonArray } ->
                    (p.range("velocity[0]", velocity[0], -5000f, 5000f) ?: Vec2.ZERO) to (p.range("velocity[1]", velocity[1], -5000f, 5000f) ?: Vec2.ZERO)
                else -> {
                    p += "velocity: give [[xMin, xMax], [yMin, yMax]] in pixels per second"
                    Vec2.ZERO to Vec2.ZERO
                }
            }
            val size = p.range("size", obj["size"], 0.5f, 512f) ?: Vec2(4f, 8f)
            val colors = obj["color"]?.let { c -> p.colors("color", c) } ?: (WHITE to TRANSPARENT_WHITE)
            return SceneLayer.Particles(path, rate, life, vx, vy, size, colors, look)
        }

        private fun look(p: Problems, obj: JsonObject): LayerLook {
            val rect = obj["rect"]?.let { r ->
                val n = (r as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.floatOrNull }
                if (n == null || n.size != 4 || n[2] <= 0f || n[3] <= 0f || n.any { it < -4f || it > 4f }) {
                    p += "rect: give [x, y, width, height] as fractions of the screen (width and height above 0)"
                    null
                } else {
                    LayerRect(n[0], n[1], n[2], n[3])
                }
            }
            val fitText = (obj["fit"] as? JsonPrimitive)?.contentOrNull
            val fit = fitText?.let { f ->
                VideoFit.byName(f) ?: null.also { p += "fit: no fit named $f (use COVER, FIT, STRETCH or CENTER)" }
            }
            if (rect != null && fit != null) p += "fit is ignored with a rect"
            return LayerLook(
                opacity = p.number("opacity", obj["opacity"], 0f, 1f) ?: 1f,
                blend = p.enumOf<LayerBlend>("blend", obj["blend"]) ?: LayerBlend.NORMAL,
                rect = rect,
                fit = fit ?: VideoFit.FILL,
                anchor = p.enumOf<LayerAnchor>("anchor", obj["anchor"]) ?: LayerAnchor.CENTER,
                parallax = p.number("parallax", obj["parallax"], 0f, 1f) ?: 0f,
                mask = obj["mask"]?.let { p.image("mask", it) },
                motion = LayerMotion(
                    scroll = obj["scroll"]?.let { e ->
                        p.pair("scroll", e) ?: run {
                            p += "scroll: give [x, y] in texels per second"
                            null
                        }
                    } ?: Vec2.ZERO,
                    sway = obj["sway"]?.let { sway(p.at("sway"), it) },
                    pulse = obj["pulse"]?.let { pulse(p.at("pulse"), it) },
                    audio = obj["audio"]?.let { drive(p.at("audio"), it) },
                    transition = obj["transition"]?.let { drive(p.at("transition"), it) },
                    daylight = obj["daylight"]?.let { drive(p.at("daylight"), it) },
                ),
            )
        }

        private fun sway(p: Problems, e: JsonElement): Sway? {
            (e as? JsonPrimitive)?.floatOrNull?.let { return Sway(angle = it.coerceIn(-180f, 180f)) }
            val o = e as? JsonObject ?: return null.also { p += "give an angle in degrees, or { angle, offset, period }" }
            for (key in o.keys - setOf("angle", "offset", "period")) p += "$key isn't a sway property (angle, offset, period)"
            return Sway(
                angle = p.number("angle", o["angle"], -180f, 180f) ?: 0f,
                offset = o["offset"]?.let { p.pair("offset", it) } ?: Vec2.ZERO,
                period = p.number("period", o["period"], 0.1f, 600f) ?: 6f,
            )
        }

        private fun pulse(p: Problems, e: JsonElement): Pulse? {
            (e as? JsonPrimitive)?.floatOrNull?.let { return Pulse(scale = it.coerceIn(-1f, 1f)) }
            val o = e as? JsonObject ?: return null.also { p += "give a scale amount, or { scale, opacity, period }" }
            for (key in o.keys - setOf("scale", "opacity", "period")) p += "$key isn't a pulse property (scale, opacity, period)"
            return Pulse(
                scale = p.number("scale", o["scale"], -1f, 1f) ?: 0f,
                opacity = p.number("opacity", o["opacity"], -1f, 1f) ?: 0f,
                period = p.number("period", o["period"], 0.1f, 600f) ?: 4f,
            )
        }

        private fun drive(p: Problems, e: JsonElement): Drive? {
            val o = e as? JsonObject ?: return null.also { p += "give { scale, opacity, offset, angle }: what the input adds at 1.0" }
            for (key in o.keys - setOf("scale", "opacity", "offset", "angle")) p += "$key isn't a driver property (scale, opacity, offset, angle)"
            return Drive(
                scale = p.number("scale", o["scale"], -1f, 1f) ?: 0f,
                opacity = p.number("opacity", o["opacity"], -1f, 1f) ?: 0f,
                offset = o["offset"]?.let { p.pair("offset", it) } ?: Vec2.ZERO,
                angle = p.number("angle", o["angle"], -180f, 180f) ?: 0f,
            )
        }

        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val TRANSPARENT_WHITE = 0x00FFFFFF

        private val SCENE_KEYS = setOf("resolution", "poster", "layers")
        /** What a shader layer's look uses; the rest of [COMMON_KEYS] is reported as ignored. */
        private val SHADER_LOOK_KEYS = setOf("opacity", "rect")
        private val COMMON_KEYS = setOf(
            "opacity", "blend", "rect", "fit", "anchor", "parallax", "mask",
            "scroll", "sway", "pulse", "audio", "transition", "daylight",
        )

        /**
         * Pixels across a scene's layer textures (16 MP: 64 MB as RGBA8, ~85 MB with the mipmaps
         * every layer texture gets). Images past it are left out, top layers first.
         */
        const val TEXTURE_BUDGET_PIXELS = 16L * 1024 * 1024

        /** Most particles across a scene's particle layers. */
        const val MAX_PARTICLES = 2000
    }
}

/**
 * What a scene may cost (pure, so it is unit tested): at most [MAX_LAYERS] layers,
 * [MAX_VIDEOS] video file (any number of video layers may show it), [MAX_SHADERS] shader layers, [Scene.MAX_PARTICLES] particles and
 * [MAX_OVERDRAW] screens of pixels. Layers are taken bottom to top while they fit; one that
 * doesn't is dropped (and a cheaper one above it may still fit).
 */
object SceneBudget {
    const val MAX_LAYERS = 16
    /**
     * Video files per scene. One: § 2's rule of one hardware decoder in use at a time (the plan's
     * draft had 2 layers). Several layers may show the same file, each with its own blend and pose.
     */
    const val MAX_VIDEOS = 1
    const val MAX_SHADERS = 4
    /** Screens of pixels per frame (the plan's 6 left its own sample scene, at 6.02, over; the A55 timings settle it). */
    const val MAX_OVERDRAW = 8f

    /** Screens of pixels [layer] draws: its coverage, a shader layer twice (it reads `uBelow` and writes the next target). */
    fun cost(layer: SceneLayer): Float = when (layer) {
        is SceneLayer.Shader -> 2f * layer.look.coverage
        is SceneLayer.Particles -> layer.coverage
        else -> layer.look.coverage
    }

    fun overdraw(layers: List<SceneLayer>): Float = layers.sumOf { cost(it).toDouble() }.toFloat()

    /** [layers] that fit, in order; [dropped] hears each one left out (its index in [layers]) and why. */
    fun fit(layers: List<SceneLayer>, dropped: (Int, String) -> Unit = { _, _ -> }): List<SceneLayer> {
        val videos = HashSet<String>()
        var shaders = 0
        var particles = 0
        var screens = 0f
        val out = ArrayList<SceneLayer>()
        layers.forEachIndexed { i, layer ->
            val cost = cost(layer)
            val why = when {
                out.size >= MAX_LAYERS -> "a scene draws at most $MAX_LAYERS layers"
                layer is SceneLayer.Video && layer.path !in videos && videos.size >= MAX_VIDEOS ->
                    "a scene plays one video file (${videos.first()}); more layers may show that one"
                layer is SceneLayer.Shader && shaders >= MAX_SHADERS -> "a scene runs at most $MAX_SHADERS shader layers"
                layer is SceneLayer.Particles && particles + layer.count > Scene.MAX_PARTICLES ->
                    "a scene keeps at most ${Scene.MAX_PARTICLES} particles (this layer needs ${layer.count}, $particles are taken)"
                screens + cost > MAX_OVERDRAW + 1e-4f ->
                    "a scene draws at most ${MAX_OVERDRAW.toInt()} screens of pixels (%.1f are taken, this layer adds %.1f)".format(Locale.ROOT, screens, cost)
                else -> null
            }
            if (why != null) {
                dropped(i, why)
            } else {
                out += layer
                screens += cost
                when (layer) {
                    is SceneLayer.Video -> videos += layer.path
                    is SceneLayer.Shader -> shaders++
                    is SceneLayer.Particles -> particles += layer.count
                    else -> Unit
                }
            }
        }
        return out
    }
}

/**
 * A scene as the loader found it: the model, its text, and where each file it names is. [key]
 * covers the text and every file's size and modification time, so editing any of them is a new
 * program (and a scene blocked after a crash is tried again).
 */
@Immutable
data class SceneSource(
    val path: String,
    val text: String,
    val scene: Scene,
    /** Each image the scene reads, by pack-relative path (missing ones absent). */
    val images: Map<String, ShaderImageSource> = emptyMap(),
    /** Each video layer's file (folder packs only; missing and oversized ones absent). */
    val videos: Map<String, File> = emptyMap(),
    /** Each shader layer's text (unreadable ones absent). */
    val shaders: Map<String, String> = emptyMap(),
) {
    val key: String get() = keyOf(path, text, scene, images, videos, shaders)

    /** The poster as § 1's slot 0, so the poster program draws it: null when the scene has none, or its file is missing. */
    val posterImage: BoundImage? get() = scene.poster?.let { BoundImage(ShaderImage(it, mipmap = true), images[it]) }

    /** Every file it names, pack-relative: what an export carries with it. */
    val files: List<String> get() = (listOf(path) + scene.imagePaths + scene.videoPaths + scene.shaderPaths).distinct()

    companion object {
        /** `scene:<path>:<digest>` over the text and each file's stamp ("missing" where it wasn't found). */
        fun keyOf(
            path: String,
            text: String,
            scene: Scene,
            images: Map<String, ShaderImageSource>,
            videos: Map<String, File>,
            shaders: Map<String, String>,
        ): String {
            val stamps = buildList {
                for (p in scene.imagePaths) add("i|$p|${images[p]?.stamp ?: "missing"}")
                for (p in scene.videoPaths) add("v|$p|${videos[p]?.let { "${it.length()}:${it.lastModified()}" } ?: "missing"}")
                for (p in scene.shaderPaths) add("s|$p|${shaders[p]?.let(ShaderLibrary::digest) ?: "missing"}")
            }
            return "scene:$path:${ShaderLibrary.digest(text + "\n\u0000files\n" + stamps.joinToString("\n"))}"
        }

        /** A scene file's `scenePath` must be a `.json` inside the pack. */
        fun validPath(path: String): Boolean =
            path.substringAfterLast('.', "").lowercase(Locale.ROOT) == "json" && !path.startsWith("/") &&
                path.split('/').none { it == ".." } && path.lowercase(Locale.ROOT) != "theme.json"
    }
}

/** Problems of one scene file, each prefixed with where it is: `scene.json: layers[3].frames: …`. */
private class Problems(private val file: String, val sink: MutableList<String>, private val prefix: String = "") {
    fun at(part: String) = Problems(file, sink, if (prefix.isEmpty()) part else "$prefix.$part")

    /** The `where` [ShaderImage.listAt] reports under. */
    fun where(key: String) = "$file: ${if (prefix.isEmpty()) key else "$prefix.$key"}"

    operator fun plusAssign(text: String) {
        sink += if (prefix.isEmpty()) "$file: $text" else "$file: $prefix.$text"
    }

    fun string(key: String, e: JsonElement?): String? =
        (e as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: null.also {
            if (e != null) this += "$key: give a path"
        }

    private fun file(key: String, e: JsonElement?, types: Collection<String>, what: String): String? {
        val raw = string(key, e) ?: return null.also { if (e == null) this += "$key: give a path" }
        val path = raw.replace('\\', '/').trimStart('/')
        if (path.split('/').any { it == ".." }) return null.also { this += "$key: $raw must stay inside the pack" }
        if (path.substringAfterLast('.', "").lowercase(Locale.ROOT) !in types) return null.also { this += "$key: $raw isn't $what" }
        return path
    }

    fun image(key: String, e: JsonElement?) = file(key, e, ShaderImage.TYPES, "a PNG, JPEG or WebP image")

    fun video(key: String, e: JsonElement?) = file(key, e, VideoRef.TYPES, "an MP4 or WebM video")

    fun shader(key: String, e: JsonElement?) = file(key, e, ShaderRef.FILE_TYPES, "a .frag shader")

    fun number(key: String, e: JsonElement?, min: Float, max: Float): Float? {
        e ?: return null
        val n = (e as? JsonPrimitive)?.takeUnless { it.isString || it.booleanOrNull != null }?.floatOrNull
        if (n == null || n.isNaN() || n < min || n > max) {
            this += "$key: give a number from ${fmt(min)} to ${fmt(max)}"
            return null
        }
        return n
    }

    /** `[a, b]` as numbers; null (unreported) when it isn't one. */
    fun pair(key: String, e: JsonElement?): Vec2? {
        val n = (e as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.floatOrNull }
        return if (n != null && n.size == 2 && e.size == 2 && n.none { it.isNaN() || it.isInfinite() }) Vec2(n[0], n[1]) else null
    }

    /** `[low, high]` within [min]..[max], or one number for both ends. */
    fun range(key: String, e: JsonElement?, min: Float, max: Float): Vec2? {
        e ?: return null
        val r = (e as? JsonPrimitive)?.takeUnless { it.isString }?.floatOrNull?.let { Vec2(it, it) } ?: pair(key, e)
        if (r == null || r.x > r.y || r.x < min || r.y > max) {
            this += "$key: give [low, high] (or one number) from ${fmt(min)} to ${fmt(max)}"
            return null
        }
        return r
    }

    /** `"#rrggbb"` or `["#start", "#end"]` (`#aarrggbb` for alpha, as everywhere in a theme). */
    fun colors(key: String, e: JsonElement): Pair<Int, Int>? {
        val texts = when (e) {
            is JsonPrimitive -> listOfNotNull(e.contentOrNull)
            is JsonArray -> e.map { (it as? JsonPrimitive)?.contentOrNull.orEmpty() }
            else -> emptyList()
        }
        val parsed = texts.map { ThemeManifest.parseColor(it) }
        if (texts.size !in 1..2 || parsed.any { it == null }) {
            this += "$key: give a colour, or [start, end] (#rrggbb, or #aarrggbb with alpha)"
            return null
        }
        return parsed.first()!! to parsed.last()!!
    }

    inline fun <reified E : Enum<E>> enumOf(key: String, e: JsonElement?): E? {
        val text = (e as? JsonPrimitive)?.contentOrNull?.trim() ?: return null
        return enumValues<E>().firstOrNull { it.name.equals(text.replace('-', '_'), ignoreCase = true) } ?: null.also {
            this += "$key: no $key named $text (use ${enumValues<E>().joinToString { it.name }})"
        }
    }

    private fun fmt(f: Float) = if (f == f.toInt().toFloat()) f.toInt().toString() else f.toString()
}
