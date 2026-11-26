package dev.xross.launcher.ui

import android.opengl.GLES30
import android.util.Log
import dev.xross.launcher.theme.shader.BoundImage
import dev.xross.launcher.theme.shader.ImageWrap
import dev.xross.launcher.theme.shader.LayerBlend
import dev.xross.launcher.theme.shader.LayerPose
import dev.xross.launcher.theme.shader.SceneInputs
import dev.xross.launcher.theme.shader.SceneLayer
import dev.xross.launcher.theme.shader.ScenePoser
import dev.xross.launcher.theme.shader.SceneSource
import dev.xross.launcher.theme.shader.ShaderImage

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

    /** One sampler per wrap mode (trilinear: every layer texture has mipmaps), and a clamped one for masks. */
    private val samplers = IntArray(ImageWrap.entries.size)

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
    }

    fun sampler(wrap: ImageWrap): Int = samplers[wrap.ordinal]

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
 * A scene's layers on the GPU (Live backgrounds § 3b), owned by its [GlProgram]: one RGBA8 texture
 * with mipmaps per image the drawn layers read ([textureImages]), uploaded once from pixels
 * [ShaderImageLoader] decoded premultiplied on IO. [draw] lays the theme gradient, then each image
 * and sprite layer bottom to top with its blend, opacity, mask and pose ([ScenePoser]). Video,
 * shader and particle layers come with slices 3c and 3d; until then they are skipped.
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

    private val pose = LayerPose()

    /** Its textures are on the GPU (some may be missing; their layers are skipped). */
    var uploaded = false
        private set

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

    /** One frame on the current target ([width] × [height]): the gradient, then the layers. */
    fun draw(quad: SceneQuadProgram, width: Int, height: Int, inputs: SceneInputs, top: FloatArray, bottom: FloatArray) {
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
            val t = layerTex[i].takeIf { it >= 0 }?.let { tex[it] } ?: 0
            if (t == 0) continue
            if (!ScenePoser.pose(layer, texW[layerTex[i]], texH[layerTex[i]], width, height, inputs, pose)) continue
            blend(layer.look.blend)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t)
            GLES30.glBindSampler(0, quad.sampler(layer.wrap))
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
        GLES30.glBindSampler(0, 0)
        GLES30.glBindSampler(1, 0)
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
        uploaded = false
    }

    companion object {
        /** The layer types drawn so far (3b); the others are skipped until their slice lands. */
        fun drawn(layer: SceneLayer): Boolean = layer is SceneLayer.Image || layer is SceneLayer.Sprite

        /** A scene with at least one layer this renderer draws; else it shows its poster. */
        fun draws(source: SceneSource?): Boolean = source != null && source.scene.layers.any(::drawn)

        /** Each distinct image the drawn layers and their masks read, bottom layer first. */
        fun textureImages(source: SceneSource): List<BoundImage> {
            val out = LinkedHashMap<String, BoundImage>()
            for (layer in source.scene.layers.filter(::drawn)) {
                for (path in listOfNotNull(layer.ownImage, layer.look.mask)) {
                    out.getOrPut(path) { BoundImage(ShaderImage(path, mipmap = true), source.images[path]) }
                }
            }
            return out.values.toList()
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
