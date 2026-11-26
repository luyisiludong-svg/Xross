package dev.xross.launcher.ui

import android.opengl.GLES30
import android.util.Log
import dev.xross.launcher.theme.shader.BoundImage
import dev.xross.launcher.theme.shader.ImageWrap
import dev.xross.launcher.theme.shader.LayerBlend
import dev.xross.launcher.theme.shader.LayerPose
import dev.xross.launcher.theme.shader.LayerRect
import dev.xross.launcher.theme.shader.ProgramState
import dev.xross.launcher.theme.shader.SceneInputs
import dev.xross.launcher.theme.shader.SceneLayer
import dev.xross.launcher.theme.shader.ScenePoser
import dev.xross.launcher.theme.shader.SceneSource
import dev.xross.launcher.theme.shader.ShaderImage
import dev.xross.launcher.theme.shader.ShaderPreset
import dev.xross.launcher.theme.shader.ShaderSpec
import kotlin.math.roundToInt

/**
 * The textured-quad program every scene shares (Live backgrounds § 3b): `scene_layer.vert` /
 * `scene_layer.frag`, compiled once per context next to the filter pass. Image and sprite layers
 * are one draw each through it (a 4-vertex strip, no buffers); `uMode` 1 draws the theme gradient
 * under them. If it doesn't link, scenes are drawn as their poster.
 */
internal class SceneQuadProgram {
    var handle = 0
        private set
    val ready: Boolean get() = handle != 0

    var uOrigin = -1; var uAxisX = -1; var uAxisY = -1; var uUv = -1
    var uTex = -1; var uMask = -1; var uHasMask = -1; var uOpacity = -1; var uMode = -1
    var uColorTop = -1; var uColorBottom = -1

    /**
     * One sampler per wrap mode (trilinear: every layer texture has mipmaps), plus a last, clamped
     * bilinear one for the video copy, which has none (a mipmap filter would sample it black).
     */
    private val samplers = IntArray(ImageWrap.entries.size + 1)

    /** Compiles [vertexSource] + [fragmentSource] (null: missing from the APK); the last context's handle is gone already. */
    fun compile(vertexSource: String?, fragmentSource: String?) {
        handle = 0
        samplers.fill(0)
        if (vertexSource == null || fragmentSource == null) {
            Log.e(TAG, "scene layer program missing from the app")
            return
        }
        val vs = shader(GLES30.GL_VERTEX_SHADER, vertexSource) ?: return
        val fs = shader(GLES30.GL_FRAGMENT_SHADER, fragmentSource) ?: run {
            GLES30.glDeleteShader(vs)
            return
        }
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vs)
        GLES30.glAttachShader(program, fs)
        GLES30.glLinkProgram(program)
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        val status = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "scene layer program: ${GLES30.glGetProgramInfoLog(program)}")
            GLES30.glDeleteProgram(program)
            return
        }
        handle = program
        fun loc(name: String) = GLES30.glGetUniformLocation(program, name)
        uOrigin = loc("uOrigin"); uAxisX = loc("uAxisX"); uAxisY = loc("uAxisY"); uUv = loc("uUv")
        uTex = loc("uTex"); uMask = loc("uMask"); uHasMask = loc("uHasMask"); uOpacity = loc("uOpacity"); uMode = loc("uMode")
        uColorTop = loc("uColorTop"); uColorBottom = loc("uColorBottom")
        GLES30.glGenSamplers(samplers.size, samplers, 0)
        for (wrap in ImageWrap.entries) {
            val s = samplers[wrap.ordinal]
            val mode = when (wrap) {
                ImageWrap.CLAMP -> GLES30.GL_CLAMP_TO_EDGE
                ImageWrap.REPEAT -> GLES30.GL_REPEAT
                ImageWrap.MIRROR -> GLES30.GL_MIRRORED_REPEAT
            }
            GLES30.glSamplerParameteri(s, GLES30.GL_TEXTURE_WRAP_S, mode)
            GLES30.glSamplerParameteri(s, GLES30.GL_TEXTURE_WRAP_T, mode)
            GLES30.glSamplerParameteri(s, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
            GLES30.glSamplerParameteri(s, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        }
        val v = samplers[ImageWrap.entries.size]
        GLES30.glSamplerParameteri(v, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glSamplerParameteri(v, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glSamplerParameteri(v, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glSamplerParameteri(v, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
    }

    fun sampler(wrap: ImageWrap): Int = samplers[wrap.ordinal]

    /** For textures without mipmaps (the video copy). */
    val linearSampler: Int get() = samplers[ImageWrap.entries.size]

    private fun shader(type: Int, source: String): Int? {
        val s = GLES30.glCreateShader(type)
        if (s == 0) return null
        GLES30.glShaderSource(s, source)
        GLES30.glCompileShader(s)
        val status = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "scene layer shader: ${GLES30.glGetShaderInfoLog(s)}")
            GLES30.glDeleteShader(s)
            return null
        }
        return s
    }

    fun release() {
        if (handle != 0) GLES30.glDeleteProgram(handle)
        if (samplers[0] != 0) GLES30.glDeleteSamplers(samplers.size, samplers, 0)
        handle = 0
        samplers.fill(0)
    }

    private companion object {
        const val TAG = "XrossScene"
    }
}

/**
 * A scene's layers on the GPU (Live backgrounds § 3b, 3c), owned by its [GlProgram]:
 *  - **Image and sprite layers:** one RGBA8 texture with mipmaps per image they read
 *    ([textureImages]), uploaded once from pixels [ShaderImageLoader] decoded premultiplied on IO.
 *  - **Video layers:** the § 2 copy ([VideoTexture]) of the scene's one video file, drawn like an
 *    image (it is opaque, so premultiplied as it is). Before the first frame they are skipped.
 *  - **Shader layers:** one [GlProgram] each (the prelude + the pack's text, compiled with the
 *    scene and canary-guarded with it as one unit), with its own `images` ([shaderImages], straight
 *    alpha like § 1). While the scene has one, it is drawn into a [SceneTargets] ping-pong pair:
 *    each shader layer reads the layers so far as `uBelow` and writes the other target, and the
 *    last target is blitted to the screen.
 *
 * [draw] lays the theme gradient, then each layer bottom to top with its blend, opacity, mask and
 * pose ([ScenePoser]). Particle layers come with 3d; until then they are skipped.
 */
internal class GlScene(val source: SceneSource) {
    private val layers: List<SceneLayer> = source.scene.layers.filter(::drawn)

    /** The images to decode, in layer order (so the budget drops the top layers' first). */
    val textureImages: List<BoundImage> = textureImages(source)

    private val paths = textureImages.map { it.image.path }
    private val tex = IntArray(paths.size)
    private val texW = IntArray(paths.size)
    private val texH = IntArray(paths.size)
    private val layerTex = IntArray(layers.size) { paths.indexOf(layers[it].ownImage) }
    private val maskTex = IntArray(layers.size) { i -> layers[i].look.mask?.let(paths::indexOf) ?: -1 }

    /** Each shader layer's program (null for other layers, and where the layer's text wasn't found). */
    private val programs: Array<GlProgram?> = Array(layers.size) { i ->
        (layers[i] as? SceneLayer.Shader)?.let { layer -> layerSpec(source, layer, i)?.let(::GlProgram) }
    }

    /** It has a shader layer to draw, so it renders through [SceneTargets]. */
    val pingPong: Boolean = programs.any { it != null }

    /** Every shader layer's images, layer after layer (each [GlProgram.spec]'s slots), decoded together within § 1's budget. */
    val shaderImages: List<BoundImage?> = programs.filterNotNull().flatMap { it.spec.images }

    private val pose = LayerPose()

    /** Its textures are on the GPU (some may be missing; their layers are skipped). */
    var uploaded = false
        private set

    /** The shader layers' images are on the GPU (true when there are none). */
    var shaderImagesUploaded = shaderImages.isEmpty()
        private set

    /** Everything it draws is on the GPU: until then the scene is PENDING ([GlProgram.drawState]). */
    val ready: Boolean get() = uploaded && shaderImagesUploaded

    /** Starts compiling the shader layers with the scene's own program (the prelude, the fullscreen [vertex]). */
    fun beginLayers(vertex: Int, prelude: String) {
        for (p in programs) p?.begin(vertex, prelude, p.spec.source!!)
    }

    /** Every shader layer finished compiling and linking (see [GlProgram.complete]). */
    fun layersComplete(parallel: Boolean): Boolean = programs.all { it?.complete(parallel) != false }

    /**
     * Reads the shader layers' results. A layer that failed is skipped when drawing, and its
     * problem returned (`layers[3] glass.frag: …`); the rest of the scene still draws.
     */
    fun settleLayers(): List<String> {
        val problems = ArrayList<String>()
        for (p in programs) {
            p ?: continue
            val log = p.settle()
            if (p.state == ProgramState.FAILED) {
                Log.e("XrossScene", "${p.spec.label} ${p.cause?.name?.lowercase()} failed: ${p.error}")
                if (log != null) Log.w("XrossScene", log.trim())
                problems += "${p.spec.label}: ${p.error}"
            }
        }
        return problems
    }

    /** Uploads [slots] (parallel to [textureImages]; null: missing or left out). False when the driver refused one. */
    fun upload(slots: List<DecodedImage?>): Boolean {
        var ok = true
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        for ((i, img) in slots.withIndex()) {
            if (i >= tex.size || img == null) continue
            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
            GLES30.glGetError()
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, img.width, img.height, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, img.pixels)
            if (GLES30.glGetError() != GLES30.GL_NO_ERROR) {
                Log.e("XrossScene", "scene image ${img.image.path} refused at ${img.width}x${img.height}")
                GLES30.glDeleteTextures(1, ids, 0)
                ok = false
                continue
            }
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
            tex[i] = ids[0]
            texW[i] = img.width
            texH[i] = img.height
        }
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        uploaded = true
        return ok
    }

    /** Uploads the shader layers' images ([slots] parallel to [shaderImages]) into each layer's [ImageTextures]. */
    fun uploadShaderImages(slots: List<DecodedImage?>): Boolean {
        var ok = true
        var at = 0
        for (p in programs) {
            p ?: continue
            val n = p.spec.images.size
            val mine = (at until at + n).map { slots.getOrNull(it) }
            at += n
            if (n == 0 || p.state == ProgramState.FAILED) continue
            val textures = ImageTextures()
            ok = textures.upload(mine) && ok
            p.textures = textures
        }
        shaderImagesUploaded = true
        return ok
    }

    /**
     * One frame on a [width] × [height] target: the gradient, then the layers. [video] holds the
     * scene's video frame (null: none yet). With shader layers and [targets] allocated, it draws
     * through them and blits the result to framebuffer 0; [drawShader] draws a shader layer's
     * program (every prelude uniform bound) with the given texture as `uBelow`. Without targets
     * (the driver refused them) the shader layers are skipped.
     */
    fun draw(
        quad: SceneQuadProgram,
        width: Int,
        height: Int,
        inputs: SceneInputs,
        top: FloatArray,
        bottom: FloatArray,
        video: VideoTexture?,
        targets: SceneTargets?,
        drawShader: (GlProgram, Int) -> Unit,
    ) {
        val through = pingPong && targets != null && targets.ensure(width, height)
        if (through) targets!!.bindTarget()
        GLES30.glUseProgram(quad.handle)
        GLES30.glUniform1i(quad.uTex, 0)
        GLES30.glUniform1i(quad.uMask, 1)
        GLES30.glUniform4f(quad.uColorTop, top[0], top[1], top[2], 1f)
        GLES30.glUniform4f(quad.uColorBottom, bottom[0], bottom[1], bottom[2], 1f)
        // The gradient covers the screen: no blending, so it also replaces last frame.
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glUniform1i(quad.uMode, 1)
        quadUniforms(quad, -1f, 1f, 2f, 0f, 0f, -2f)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        GLES30.glUniform1i(quad.uMode, 0)
        GLES30.glEnable(GLES30.GL_BLEND)
        val w = width.toFloat()
        val h = height.toFloat()
        for ((i, layer) in layers.withIndex()) {
            if (layer is SceneLayer.Shader) {
                val program = programs[i]
                if (!through || program == null || program.state != ProgramState.READY) continue
                unbindQuad()
                shaderLayer(layer, program, targets!!, width, height, drawShader)
                GLES30.glUseProgram(quad.handle)
                GLES30.glEnable(GLES30.GL_BLEND)
                continue
            }
            val t: Int
            val tw: Int
            val th: Int
            val sampler: Int
            if (layer is SceneLayer.Video) {
                t = video?.texture ?: 0
                tw = video?.size?.get(0)?.toInt() ?: 0
                th = video?.size?.get(1)?.toInt() ?: 0
                sampler = quad.linearSampler
            } else {
                val k = layerTex[i]
                t = if (k >= 0) tex[k] else 0
                tw = if (k >= 0) texW[k] else 0
                th = if (k >= 0) texH[k] else 0
                sampler = quad.sampler(layer.wrap)
            }
            if (t == 0) continue
            if (!ScenePoser.pose(layer, tw, th, width, height, inputs, pose)) continue
            blend(layer.look.blend)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t)
            GLES30.glBindSampler(0, sampler)
            val mask = maskTex[i].takeIf { it >= 0 }?.let { tex[it] } ?: 0
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mask)
            GLES30.glBindSampler(1, quad.sampler(ImageWrap.CLAMP))
            GLES30.glUniform1f(quad.uHasMask, if (mask != 0) 1f else 0f)
            GLES30.glUniform1f(quad.uOpacity, pose.opacity)
            GLES30.glUniform4f(quad.uUv, pose.u0, pose.v0, pose.du, pose.dv)
            // Render pixels (y down) to clip space (y up).
            quadUniforms(
                quad,
                pose.originX / w * 2f - 1f, 1f - pose.originY / h * 2f,
                pose.axisXx / w * 2f, -pose.axisXy / h * 2f,
                pose.axisYx / w * 2f, -pose.axisYy / h * 2f,
            )
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        }
        GLES30.glDisable(GLES30.GL_BLEND)
        unbindQuad()
        if (through) targets!!.present()
    }

    /**
     * A shader layer: it writes the other target while reading this one as `uBelow`. Opaque and
     * full-screen, it simply replaces the picture. With a `rect` or an `opacity` below 1, the
     * picture is first copied across, then the layer is drawn inside the rect (a scissor) and mixed
     * in at its opacity (a constant-alpha blend). Destination alpha is kept.
     */
    private fun shaderLayer(layer: SceneLayer.Shader, program: GlProgram, targets: SceneTargets, width: Int, height: Int, drawShader: (GlProgram, Int) -> Unit) {
        val opacity = layer.look.opacity
        if (opacity <= 1f / 512f) return
        val rect = layer.look.rect
        val partial = rect != null || opacity < 1f
        if (partial) targets.copyAcross()
        val below = targets.flip()
        if (rect != null) {
            val (x, y, rw, rh) = scissorOf(rect, width, height)
            if (rw <= 0 || rh <= 0) return
            GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
            GLES30.glScissor(x, y, rw, rh)
        }
        if (opacity < 1f) {
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendColor(0f, 0f, 0f, opacity)
            GLES30.glBlendFuncSeparate(GLES30.GL_CONSTANT_ALPHA, GLES30.GL_ONE_MINUS_CONSTANT_ALPHA, GLES30.GL_ZERO, GLES30.GL_ONE)
        } else {
            GLES30.glDisable(GLES30.GL_BLEND)
        }
        drawShader(program, below)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        // Off unit 7 before the next layer writes the target it holds.
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + BELOW_UNIT)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    private fun unbindQuad() {
        GLES30.glBindSampler(0, 0)
        GLES30.glBindSampler(1, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    private fun quadUniforms(quad: SceneQuadProgram, ox: Float, oy: Float, xx: Float, xy: Float, yx: Float, yy: Float) {
        GLES30.glUniform2f(quad.uOrigin, ox, oy)
        GLES30.glUniform2f(quad.uAxisX, xx, xy)
        GLES30.glUniform2f(quad.uAxisY, yx, yy)
        if (quad.uUv >= 0) GLES30.glUniform4f(quad.uUv, pose.u0, pose.v0, pose.du, pose.dv)
    }

    fun release() {
        for (i in tex.indices) if (tex[i] != 0) GLES30.glDeleteTextures(1, tex, i)
        tex.fill(0)
        for (p in programs) p?.release()
        uploaded = false
        shaderImagesUploaded = shaderImages.isEmpty()
    }

    companion object {
        /** Texture unit of a shader layer's `uBelow` (0 `uPrev`, 1 `uField`, 2–5 `uImage0..3`, 6 `uVideo`). */
        const val BELOW_UNIT = 7

        /** The layer types drawn so far (3c); particles are skipped until 3d. */
        fun drawn(layer: SceneLayer): Boolean = layer !is SceneLayer.Particles

        /** A scene with at least one layer this renderer draws; else it shows its poster. */
        fun draws(source: SceneSource?): Boolean = source != null && source.scene.layers.any(::drawn)

        /** Each distinct image the drawn quad layers and their masks read, bottom layer first (shader layers' own images aren't here). */
        fun textureImages(source: SceneSource): List<BoundImage> {
            val out = LinkedHashMap<String, BoundImage>()
            for (layer in source.scene.layers.filter(::drawn)) {
                if (layer is SceneLayer.Shader) continue
                for (path in listOfNotNull(layer.ownImage, layer.look.mask)) {
                    out.getOrPut(path) { BoundImage(ShaderImage(path, mipmap = true), source.images[path]) }
                }
            }
            return out.values.toList()
        }

        /**
         * The program a shader layer runs: CUSTOM-like, keyed under the scene's key with the layer's
         * index among the drawn layers, labelled `scene.json › layers[i] glass.frag`. Null when the
         * layer's text wasn't found (the loader reported it).
         */
        fun layerSpec(source: SceneSource, layer: SceneLayer.Shader, index: Int): ShaderSpec? {
            val text = source.shaders[layer.path] ?: return null
            return ShaderSpec(
                key = "${source.key}#$index",
                preset = ShaderPreset.CUSTOM,
                label = "${source.path} › layers[$index] ${layer.path}",
                source = text,
                images = layer.images.map { img -> img?.let { BoundImage(it, source.images[it.path]) } },
            )
        }

        /** A `rect` (screen fractions, y down) as a GL scissor box on a [width] × [height] target (y up), clamped to it. */
        fun scissorOf(rect: LayerRect, width: Int, height: Int): IntArray {
            val x0 = (rect.x * width).roundToInt().coerceIn(0, width)
            val x1 = ((rect.x + rect.width) * width).roundToInt().coerceIn(0, width)
            val top = (rect.y * height).roundToInt().coerceIn(0, height)
            val bottom = ((rect.y + rect.height) * height).roundToInt().coerceIn(0, height)
            return intArrayOf(x0, height - bottom, x1 - x0, bottom - top)
        }

        /**
         * Premultiplied blending: NORMAL is "over", ADD adds, SCREEN is `s + d·(1 − s)`, MULTIPLY
         * is `s·d + d·(1 − sa)` (transparent parts leave what is below). Destination alpha is kept.
         */
        fun blend(mode: LayerBlend) {
            when (mode) {
                LayerBlend.NORMAL -> GLES30.glBlendFuncSeparate(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA, GLES30.GL_ZERO, GLES30.GL_ONE)
                LayerBlend.ADD -> GLES30.glBlendFuncSeparate(GLES30.GL_ONE, GLES30.GL_ONE, GLES30.GL_ZERO, GLES30.GL_ONE)
                LayerBlend.SCREEN -> GLES30.glBlendFuncSeparate(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_COLOR, GLES30.GL_ZERO, GLES30.GL_ONE)
                LayerBlend.MULTIPLY -> GLES30.glBlendFuncSeparate(GLES30.GL_DST_COLOR, GLES30.GL_ONE_MINUS_SRC_ALPHA, GLES30.GL_ZERO, GLES30.GL_ONE)
            }
        }
    }
}

/**
 * The ping-pong pair a scene with shader layers draws into (Live backgrounds § 3c): two RGBA8
 * targets at the surface size, the pattern [FeedbackTargets] uses. Layers draw into the current
 * one; a shader layer [flip]s, reading the old one as `uBelow` while writing the other; [present]
 * blits the current one to the screen. Owned by the renderer, allocated only while such a scene
 * shows (~2 × 4 bytes per render pixel).
 */
internal class SceneTargets {
    private val tex = IntArray(2)
    private val fbo = IntArray(2)
    private var cur = 0
    private var width = 0
    private var height = 0

    val allocated: Boolean get() = fbo[0] != 0

    /** Allocates at [w]×[h] (kept while the size holds); false when the driver refused. */
    fun ensure(w: Int, h: Int): Boolean {
        if (allocated && w == width && h == height) return true
        release()
        GLES30.glGenTextures(2, tex, 0)
        GLES30.glGenFramebuffers(2, fbo, 0)
        for (i in 0..1) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[i])
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[i])
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex[i], 0)
            if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE) {
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
                Log.e("XrossScene", "scene targets incomplete at ${w}x$h; shader layers are skipped")
                release()
                return false
            }
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        width = w
        height = h
        cur = 0
        return true
    }

    /** Draws go to the current target. */
    fun bindTarget() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[cur])
    }

    /** Copies the current picture into the other target (a shader layer that only covers part, or mixes in). */
    fun copyAcross() {
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, fbo[cur])
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, fbo[1 - cur])
        GLES30.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[cur])
    }

    /** Makes the other target current and bound; returns the texture of the one just left (`uBelow`). */
    fun flip(): Int {
        val below = tex[cur]
        cur = 1 - cur
        bindTarget()
        return below
    }

    /** Blits the current picture to the screen, leaving framebuffer 0 bound. */
    fun present() {
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, fbo[cur])
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0)
        GLES30.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    fun release() {
        if (fbo[0] != 0) GLES30.glDeleteFramebuffers(2, fbo, 0)
        if (tex[0] != 0) GLES30.glDeleteTextures(2, tex, 0)
        forget()
    }

    /** The context died with its handles: drop them without GL calls. */
    fun forget() {
        fbo.fill(0)
        tex.fill(0)
        width = 0
        height = 0
        cur = 0
    }
}

/** The image an image or sprite layer draws. */
private val SceneLayer.ownImage: String?
    get() = when (this) {
        is SceneLayer.Image -> path
        is SceneLayer.Sprite -> path
        else -> null
    }

/** How an image layer's texture wraps; sprites clamp (their cells sit side by side). */
private val SceneLayer.wrap: ImageWrap
    get() = (this as? SceneLayer.Image)?.wrap ?: ImageWrap.CLAMP
