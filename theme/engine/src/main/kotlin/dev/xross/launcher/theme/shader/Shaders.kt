package dev.xross.launcher.theme.shader

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * Background programs (Theme 2.0, 2b.4a). A pack picks one with `effects.shaderPreset` in its base,
 * variants, overrides or context rules; [CUSTOM] reads a GLSL ES 3.0 fragment shader from the pack
 * (`shader.frag`, or `effects.shaderPath`). Built-in sources live in `assets/shaders/`.
 */
enum class ShaderPreset(val file: String?, val label: String) {
    /** The built-in glossy wave; always compiled, and what every failure falls back to. */
    WAVE("wave.frag", "Wave"),
    ASCII_MATRIX("ascii_matrix.frag", "ASCII matrix"),
    BOKEH("bokeh.frag", "Bokeh"),
    STARFIELD("starfield.frag", "Starfield"),
    CRT("crt.frag", "CRT"),
    /** Gargantua-style black hole, ray traced through Schwarzschild space and drawn in ASCII. */
    BLACK_HOLE("black_hole.frag", "Black hole"),
    /** A battle-worn mecha kneeling over its sword, drawn in ASCII; the core ignites after launch. */
    MECHA("mecha.frag", "Mecha"),
    /**
     * The look's background video (`effects.videoPath`, Live backgrounds § 2) placed by
     * `effects.videoFit`, over the theme gradient. What a look with a video and the wave shows.
     */
    VIDEO("video.frag", "Video"),
    /**
     * A layered scene (`effects.scenePath`, Live backgrounds § 3): the [Scene] a `scene.json`
     * describes, one program as far as the library, the fallback and the watchdog go. Its image
     * and sprite layers are drawn by the app's `GlScene` (slice 3b); [file] draws the scene's poster
     * ([SceneSource.posterImage], bound as `uImage0`) when it has no layer of those kinds.
     */
    SCENE("scene_poster.frag", "Scene"),
    CUSTOM(null, "Custom shader"),
    ;

    companion object {
        /** `ASCII_MATRIX`, `ascii-matrix` or `Ascii Matrix`; null for anything else. */
        fun byName(text: String?): ShaderPreset? {
            val t = text?.trim()?.replace('-', '_')?.replace(' ', '_')?.takeIf { it.isNotEmpty() } ?: return null
            return entries.firstOrNull { it.name.equals(t, ignoreCase = true) }
        }

        /** Names for error messages. */
        val NAMES: String get() = entries.joinToString { it.name }
    }
}

/** How a shader image is sampled past its edges (`effects.shaderImages[].wrap`). */
enum class ImageWrap { CLAMP, REPEAT, MIRROR }

/** How a shader image is filtered (`effects.shaderImages[].filter`). */
enum class ImageFilter { LINEAR, NEAREST }

/**
 * One image a CUSTOM shader samples (Live backgrounds § 1): `uImage0..3` in the order of
 * `effects.shaderImages`. A plain string means CLAMP, LINEAR, no mipmaps.
 */
@Immutable
data class ShaderImage(
    val path: String,
    val wrap: ImageWrap = ImageWrap.CLAMP,
    val filter: ImageFilter = ImageFilter.LINEAR,
    val mipmap: Boolean = false,
) {
    companion object {
        /** Slots: `uImage0` .. `uImage3`, texture units [FIRST_UNIT] onward. */
        const val MAX = 4

        /** Texture unit of `uImage0` (0 is `uPrev`, 1 is `uField`). */
        const val FIRST_UNIT = 2

        /** Image types a shader image may be. */
        val TYPES = setOf("png", "jpg", "jpeg", "webp")

        /** Files larger than this aren't read (a 2048² PNG is ~6 MB; the decode is capped anyway). */
        const val MAX_BYTES = 24L * 1024 * 1024

        /** Longest side uploaded, also capped at the GPU's `GL_MAX_TEXTURE_SIZE`. */
        const val MAX_SIDE = 2048

        /** Pixels across one program's images (8 MP: 32 MB as RGBA8, ~43 MB with mipmaps). */
        const val BUDGET_PIXELS = 8L * 1024 * 1024

        /**
         * `effects.shaderImages` as written at [where]: strings and `{ path, wrap, filter, mipmap }`
         * objects. A bad entry is reported in [problems] and dropped, but keeps its slot as a missing
         * image, so the ones after it still land in the `uImageN` the shader expects.
         */
        fun listOf(where: String, element: JsonElement?, problems: MutableList<String>): List<ShaderImage?> {
            return listAt("$where.effects.shaderImages", element, problems)
        }

        /** [listOf] reported under [at] as written (a scene's shader layer: `scene.json: layers[4].images`). */
        fun listAt(at: String, element: JsonElement?, problems: MutableList<String>): List<ShaderImage?> {
            if (element == null || element is JsonNull) return emptyList()
            val items = when (element) {
                is JsonArray -> element.toList()
                is JsonPrimitive, is JsonObject -> kotlin.collections.listOf(element)
            }
            if (items.size > MAX) problems += "$at: at most $MAX images (uImage0..uImage${MAX - 1}); the rest are ignored"
            return items.take(MAX).mapIndexed { i, item -> one("$at[$i]", item, problems) }
        }

        private fun one(at: String, item: JsonElement, problems: MutableList<String>): ShaderImage? {
            val obj = item as? JsonObject
            val rawPath = when {
                obj != null -> (obj["path"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                item is JsonPrimitive && item.isString -> item.content
                else -> null
            }?.trim()?.replace('\\', '/')
            if (rawPath.isNullOrEmpty()) {
                problems += "$at: give an image path, or { \"path\": ... }"
                return null
            }
            val path = rawPath.trimStart('/')
            if (path.split('/').any { it == ".." }) {
                problems += "$at: $rawPath must stay inside the pack"
                return null
            }
            if (path.substringAfterLast('.', "").lowercase(Locale.ROOT) !in TYPES) {
                problems += "$at: $rawPath isn't a PNG, JPEG or WebP image"
                return null
            }
            if (obj == null) return ShaderImage(path)
            for (key in obj.keys - KEYS) problems += "$at.$key isn't a shader image property (path, wrap, filter, mipmap)"
            val wrap = enumOf<ImageWrap>(at, "wrap", obj, problems) ?: ImageWrap.CLAMP
            val filter = enumOf<ImageFilter>(at, "filter", obj, problems) ?: ImageFilter.LINEAR
            val mipmap = obj["mipmap"]?.let { m ->
                (m as? JsonPrimitive)?.booleanOrNull ?: run {
                    problems += "$at.mipmap: use true or false"
                    null
                }
            } ?: false
            return ShaderImage(path, wrap, filter, mipmap)
        }

        private inline fun <reified E : Enum<E>> enumOf(at: String, key: String, obj: JsonObject, problems: MutableList<String>): E? {
            val text = (obj[key] as? JsonPrimitive)?.contentOrNull?.trim() ?: return null
            return enumValues<E>().firstOrNull { it.name.equals(text, ignoreCase = true) } ?: run {
                problems += "$at.$key: no $key named $text (use ${enumValues<E>().joinToString { it.name }})"
                null
            }
        }

        private val KEYS = setOf("path", "wrap", "filter", "mipmap")
    }
}

/**
 * Where a shader image's bytes are, as the loader found them: a file of a folder pack, or an APK
 * asset of a bundled one. [bytes] and [modified] go into the program's key, so replacing the file
 * (a hot reload) is a new program.
 */
@Immutable
data class ShaderImageSource(val file: File? = null, val asset: String? = null, val bytes: Long = 0, val modified: Long = 0) {
    /** What the key folds in. */
    internal val stamp: String get() = "$bytes:$modified"
}

/** A pixel size, for [ShaderImagePlan]. */
@Immutable
data class ImageSize(val width: Int, val height: Int) {
    val pixels: Long get() = width.toLong() * height
}

/**
 * How shader images are sized for upload (pure, so it is unit tested): each one's long side is
 * capped, then the slots are taken in order while they fit [ShaderImage.BUDGET_PIXELS] together.
 */
object ShaderImagePlan {
    /** [size] with its long side at most [maxSide], the aspect kept (each side at least 1). */
    fun scaled(size: ImageSize, maxSide: Int): ImageSize {
        val long = maxOf(size.width, size.height)
        if (long <= maxSide || long <= 0) return size
        val k = maxSide.toDouble() / long
        return ImageSize(maxOf(1, Math.round(size.width * k).toInt()), maxOf(1, Math.round(size.height * k).toInt()))
    }

    /**
     * The upload size of each slot of [sizes] (null: missing or unreadable), or null where the slot
     * is dropped because the slots before it used up [budget]. A slot that doesn't fit is skipped,
     * and a smaller one after it may still fit.
     */
    fun fit(sizes: List<ImageSize?>, maxSide: Int, budget: Long = ShaderImage.BUDGET_PIXELS): List<ImageSize?> {
        var used = 0L
        return sizes.map { s ->
            val target = s?.let { scaled(it, maxSide) } ?: return@map null
            if (used + target.pixels > budget) null else target.also { used += it.pixels }
        }
    }
}

/** A slot of a program: what the pack asked for and where it was found (null source: missing, bound as empty). */
@Immutable
data class BoundImage(val image: ShaderImage, val source: ShaderImageSource?)

/** A background program a theme layer asks for: a preset, or for [ShaderPreset.CUSTOM] a pack-relative file. */
@Immutable
data class ShaderRef(
    val preset: ShaderPreset,
    val path: String = "",
    /** CUSTOM's `effects.shaderImages`, by slot; null keeps a slot whose entry was unusable. */
    val images: List<ShaderImage?> = emptyList(),
) {
    companion object {
        val WAVE = ShaderRef(ShaderPreset.WAVE)

        /** What a CUSTOM shader without `shaderPath` reads. */
        const val DEFAULT_FILE = "shader.frag"

        val FILE_TYPES = setOf("frag", "glsl", "fs")

        /**
         * `effects.shaderPreset` / `effects.shaderPath` / `effects.shaderImages` /
         * `effects.scenePath` of one theme layer, or null when it sets no shader key (or names an
         * unknown preset, reported in [problems] under [where]). A path alone means CUSTOM; CUSTOM
         * alone reads `shader.frag`. Images are only read with CUSTOM. A `scenePath` (or `SCENE`
         * alone, reading `scene.json`) is a [ShaderPreset.SCENE] and replaces the other keys.
         */
        fun of(
            where: String,
            shader: String?,
            shaderPath: String?,
            problems: MutableList<String>,
            images: JsonElement? = null,
            scenePath: String? = null,
        ): ShaderRef? {
            val name = shader?.trim().orEmpty()
            val path = shaderPath?.trim()?.replace('\\', '/').orEmpty()
            val hasImages = images != null && images !is JsonNull
            val scene = scenePath?.trim()?.replace('\\', '/')?.trimStart('/').orEmpty()
            if (scene.isNotEmpty() || ShaderPreset.byName(name) == ShaderPreset.SCENE) {
                if (name.isNotEmpty() && ShaderPreset.byName(name) != ShaderPreset.SCENE) {
                    problems += "$where.effects.scenePath replaces effects.shaderPreset ($name): a layer shows one or the other"
                }
                if (path.isNotEmpty()) problems += "$where.effects.shaderPath isn't read with a scene; give the scene a shader layer"
                if (hasImages) problems += "$where.effects.shaderImages isn't read with a scene; give its shader layer \"images\""
                val file = scene.ifEmpty { Scene.DEFAULT_FILE }
                if (!SceneSource.validPath(file)) {
                    problems += "$where.effects.scenePath: use a .json scene file inside the pack (not $file)"
                    return null
                }
                return ShaderRef(ShaderPreset.SCENE, file)
            }
            if (name.isEmpty() && path.isEmpty()) {
                if (hasImages) problems += "$where.effects.shaderImages is only read together with a CUSTOM shader (shaderPreset or shaderPath)"
                return null
            }
            val preset = if (name.isEmpty()) ShaderPreset.CUSTOM else ShaderPreset.byName(name) ?: run {
                problems += "$where.effects.shaderPreset: no shader named $name (use ${ShaderPreset.NAMES})"
                return null
            }
            if (preset != ShaderPreset.CUSTOM) {
                if (path.isNotEmpty()) problems += "$where.effects.shaderPath is only read with \"shaderPreset\": \"CUSTOM\""
                if (hasImages) problems += "$where.effects.shaderImages is only read with \"shaderPreset\": \"CUSTOM\""
                return ShaderRef(preset)
            }
            val file = path.ifEmpty { DEFAULT_FILE }
            if (file.substringAfterLast('.', "").lowercase(Locale.ROOT) !in FILE_TYPES || file.split('/').any { it == ".." } || file.startsWith("/")) {
                problems += "$where.effects.shaderPath: use a .frag file inside the pack (not $file)"
                return null
            }
            val list = ShaderImage.listOf(where, images, problems)
            // Trailing unusable entries hold no later slot in place: drop them.
            return ShaderRef(ShaderPreset.CUSTOM, file, list.dropLastWhile { it == null })
        }
    }
}

/**
 * One program the renderer may compile. [key] names it: the preset for built-ins, the file and a
 * digest of its text for a custom shader, so an edit is a new program (and a shader blocked after
 * a crash is tried again once it changes).
 */
@Immutable
data class ShaderSpec(
    val key: String,
    val preset: ShaderPreset,
    val label: String,
    /** CUSTOM: the file's text, null when it can't be read. Built-ins: null (read from assets). */
    val source: String? = null,
    /** CUSTOM: `uImage0..3` by slot (null: the slot is empty); SCENE: its poster; built-ins have none. */
    val images: List<BoundImage?> = emptyList(),
    /** SCENE: the scene as loaded; null when its file couldn't be read or parsed. */
    val scene: SceneSource? = null,
) {
    /** The images as the pack asked for them, to match a [ShaderRef] against. */
    val requested: List<ShaderImage?> get() = images.map { it?.image }

    /**
     * A pack's own code: canary-guarded before it is compiled and drawn. A SCENE is when it has
     * shader layers (Live backgrounds § 3c): they compile and draw with it, guarded as one unit.
     */
    val untrusted: Boolean get() = preset == ShaderPreset.CUSTOM || preset == ShaderPreset.SCENE && scene?.scene?.hasShaderLayers == true
}

/** Every program a pack can show, compiled ahead of time when it loads; WAVE always first. */
@Immutable
data class ShaderLibrary(val specs: List<ShaderSpec>) {
    fun spec(key: String): ShaderSpec? = specs.firstOrNull { it.key == key }

    /**
     * The key [ref] is compiled under here (a CUSTOM spec's label is its path, and its images must
     * match); WAVE when the library doesn't have it.
     */
    fun keyOf(ref: ShaderRef): String =
        specs.firstOrNull {
            it.preset == ref.preset && when (ref.preset) {
                ShaderPreset.CUSTOM -> it.label == ref.path && it.requested == ref.images
                ShaderPreset.SCENE -> it.label == ref.path
                else -> true
            }
        }?.key ?: WAVE_KEY

    val keys: Set<String> get() = specs.mapTo(LinkedHashSet()) { it.key }

    companion object {
        const val WAVE_KEY = "WAVE"
        val WAVE_SPEC = ShaderSpec(WAVE_KEY, ShaderPreset.WAVE, ShaderPreset.WAVE.label)
        val DEFAULT = ShaderLibrary(listOf(WAVE_SPEC))

        /**
         * [refs] as specs; CUSTOM text comes from [customSources] (pack-relative path → text), its
         * images from [imageSources] (pack-relative path → where the loader found it), and a
         * SCENE from [scenes] (its `scene.json` path → the scene as loaded).
         */
        fun of(
            refs: Collection<ShaderRef>,
            customSources: Map<String, String>,
            imageSources: Map<String, ShaderImageSource> = emptyMap(),
            scenes: Map<String, SceneSource> = emptyMap(),
        ): ShaderLibrary {
            val out = LinkedHashMap<String, ShaderSpec>()
            out[WAVE_KEY] = WAVE_SPEC
            for (ref in refs) {
                val spec = when (ref.preset) {
                    ShaderPreset.CUSTOM -> {
                        val text = customSources[ref.path]
                        val images = ref.images.map { img -> img?.let { BoundImage(it, imageSources[it.path]) } }
                        ShaderSpec(customKey(ref.path, text, images), ShaderPreset.CUSTOM, ref.path, text, images)
                    }
                    ShaderPreset.SCENE -> {
                        val scene = scenes[ref.path]
                        ShaderSpec(
                            key = scene?.key ?: "scene:${ref.path}:missing",
                            preset = ShaderPreset.SCENE,
                            label = ref.path,
                            images = listOfNotNull(scene?.posterImage),
                            scene = scene,
                        )
                    }
                    else -> ShaderSpec(ref.preset.name, ref.preset, ref.preset.label)
                }
                out.putIfAbsent(spec.key, spec)
            }
            return ShaderLibrary(out.values.toList())
        }

        /**
         * `custom:<path>:<digest>`: the digest covers the text and each image's settings, size and
         * modification time, so editing either is a new program (and a blocked one is retried). A
         * shader without images keeps the key it had before images existed.
         */
        fun customKey(path: String, text: String?, images: List<BoundImage?>): String {
            if (text == null) return "custom:$path:missing"
            if (images.isEmpty()) return "custom:$path:${digest(text)}"
            val inputs = images.joinToString("\n") { b ->
                val i = b?.image ?: return@joinToString "-"
                "${i.path}|${i.wrap}|${i.filter}|${i.mipmap}|${b.source?.stamp ?: "missing"}"
            }
            return "custom:$path:${digest(text + "\n\u0000images\n" + inputs)}"
        }

        /** First 12 hex digits of the SHA-1 of [text]. */
        fun digest(text: String): String =
            MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).take(6).joinToString("") { "%02x".format(it) }
    }
}

/** Fragment source as the driver sees it: the prelude in front unless the file brings its own `#version`. */
data class ComposedShader(val text: String, val lineOffset: Int)

object ShaderSources {
    /** [body] behind [prelude], or [body] alone when it starts with `#version` (it then declares its own uniforms). */
    fun compose(prelude: String, body: String): ComposedShader {
        if (body.trimStart().startsWith("#version")) return ComposedShader(body, 0)
        val head = if (prelude.endsWith("\n")) prelude else prelude + "\n"
        return ComposedShader(head + body, head.count { it == '\n' })
    }

    private val LOCATED = Regex("""^\s*(?:ERROR|WARNING)?:?\s*\d+:(\d+):\s*(.*)$""")

    /**
     * The first error of a driver's info log, its line number relative to the file that follows
     * the prelude ([lineOffset] lines): `line 12: 'foo' : undeclared identifier`. Adreno, Mali,
     * ANGLE and glslang all report `0:<line>:`; anything else is passed through, trimmed.
     */
    fun firstError(log: String?, lineOffset: Int = 0): String {
        val lines = log.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return "the driver gave no reason"
        val line = lines.firstOrNull { it.startsWith("ERROR", ignoreCase = true) }
            ?: lines.firstOrNull { !it.startsWith("WARNING", ignoreCase = true) } ?: lines.first()
        val m = LOCATED.matchEntire(line)
        val text = if (m != null) {
            val n = m.groupValues[1].toInt() - lineOffset
            val msg = m.groupValues[2].trim()
            if (n >= 1) "line $n: $msg" else msg
        } else {
            line.removePrefix("ERROR:").trim()
        }
        return text.take(MAX_ERROR)
    }

    private const val MAX_ERROR = 140
}

/** How a program stands on the GL thread. */
enum class ProgramState { PENDING, READY, FAILED }

/**
 * Which program to draw (2b.4a): the one wanted once it is linked; until then the one on screen
 * (so a category switch never shows a gap or a hitch); WAVE when the wanted one failed, or
 * nothing else is ready.
 */
object ShaderFallback {
    fun choose(desired: String, current: String?, state: (String) -> ProgramState?): String = when (state(desired)) {
        ProgramState.READY -> desired
        ProgramState.FAILED -> ShaderLibrary.WAVE_KEY
        ProgramState.PENDING, null ->
            if (current != null && current != desired && state(current) == ProgramState.READY) current else ShaderLibrary.WAVE_KEY
    }

    /** The status to report for [desired]. */
    fun status(spec: ShaderSpec?, desired: String, state: ProgramState?, error: String, cause: ShaderStatus.Cause?): ShaderStatus {
        val label = spec?.label ?: desired
        return when (state) {
            ProgramState.READY -> ShaderStatus.Ready(desired, label)
            ProgramState.FAILED -> ShaderStatus.Failed(desired, label, error, cause ?: ShaderStatus.Cause.COMPILE)
            ProgramState.PENDING, null -> ShaderStatus.Compiling(desired, label)
        }
    }
}

/** What the background shows, for Settings and toasts. */
sealed interface ShaderStatus {
    val key: String
    val label: String

    data class Ready(override val key: String, override val label: String) : ShaderStatus
    data class Compiling(override val key: String, override val label: String) : ShaderStatus
    data class Failed(override val key: String, override val label: String, val error: String, val cause: Cause) : ShaderStatus {
        /** The toast: what failed and why, and that the wave is shown instead. */
        val message: String get() = when (cause) {
            Cause.COMPILE, Cause.LINK -> "Shader $label: $error · showing the wave"
            Cause.MISSING -> "Shader $label: $error · showing the wave"
            Cause.TOO_SLOW -> "Shader $label is too slow here ($error) · showing the wave"
            Cause.CRASHED -> "Shader $label stopped Xross last time · showing the wave"
        }
    }

    enum class Cause { COMPILE, LINK, MISSING, TOO_SLOW, CRASHED }
}
