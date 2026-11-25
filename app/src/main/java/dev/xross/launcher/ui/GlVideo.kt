package dev.xross.launcher.ui

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.util.Log
import android.view.Surface
import dev.xross.launcher.theme.pack.VideoFit
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The background video on the GL side (Live backgrounds § 2). The player decodes into a
 * [SurfaceTexture] on an external (OES) texture; each time a new frame is waiting, [update] latches
 * it and copies it into a regular RGBA8 texture at the video's size (capped at [VideoPolicy.MAX_COPY_SIDE]
 * on the long side) with a tiny `samplerExternalOES` program. Programs sample that copy as
 * `uVideo` on texture unit [UNIT], like any `sampler2D`: no external-image extension in the
 * prelude, filtering and `texelFetch` work, and the copy costs one small pass per decoded frame
 * (~30 a second), not per render frame. The copy keeps the last frame on screen while the player
 * is paused or released.
 *
 * GL thread only, except [onFrameAvailable] (the main looper) and [abandon].
 */
internal class VideoTexture(
    /** A new decoded frame is waiting (main thread): a still screen asks for the frame that shows it. */
    private val onFrame: () -> Unit,
) : SurfaceTexture.OnFrameAvailableListener {
    private var oes = 0
    private var surfaceTexture: SurfaceTexture? = null

    /** What the player renders into; null until [create]. */
    var surface: Surface? = null
        private set

    private val pending = AtomicBoolean(false)
    private val matrix = FloatArray(16)

    private var copy = 0
    private var aPos = -1
    private var uTex = -1
    private var uMatrix = -1
    private val triangle: FloatBuffer = ByteBuffer.allocateDirect(6 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 3f, -1f, -1f, 3f))
        position(0)
    }

    private val tex = IntArray(1)
    private val fbo = IntArray(1)
    private var width = 0
    private var height = 0

    /** w, h, 1/w, 1/h of the copied frame (`uVideoSize`); zeros before the first one. */
    val size = FloatArray(4)

    /** Where [VideoFit] places the frame (`uVideoRect`: left, bottom, width, height in render pixels). */
    val rect = FloatArray(4)
    private var rectFor = LongArray(3) { -1 }

    val created: Boolean get() = surfaceTexture != null

    /** A frame has been copied: [size] and the texture hold it. */
    val hasFrame: Boolean get() = tex[0] != 0 && size[0] > 0f

    override fun onFrameAvailable(st: SurfaceTexture) {
        pending.set(true)
        onFrame()
    }

    /** The external texture, its [SurfaceTexture] and the copy program; false when the driver refused. */
    fun create(): Boolean {
        if (created) return true
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        oes = ids[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oes)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        copy = compileCopy()
        if (oes == 0 || copy == 0) {
            Log.e(TAG, "background video: the driver refused the external texture or the copy program")
            release()
            return false
        }
        val st = SurfaceTexture(oes)
        // Callbacks on the main looper (the GL thread has none): they only set a flag.
        st.setOnFrameAvailableListener(this, android.os.Handler(android.os.Looper.getMainLooper()))
        surfaceTexture = st
        surface = Surface(st)
        pending.set(false)
        return true
    }

    /**
     * Latches a waiting frame and copies it at [videoW]×[videoH] (the player's display size; 0
     * while unknown). True when a new frame was copied. Leaves framebuffer 0 bound and the
     * viewport at [screenW]×[screenH].
     */
    fun update(videoW: Int, videoH: Int, screenW: Int, screenH: Int): Boolean {
        val st = surfaceTexture ?: return false
        if (!pending.getAndSet(false)) return false
        try {
            st.updateTexImage()
        } catch (e: RuntimeException) { // abandoned or the context changed under it
            Log.w(TAG, "background video: updateTexImage failed: ${e.message}")
            return false
        }
        st.getTransformMatrix(matrix)
        val (w, h) = VideoPolicy.copySize(videoW, videoH)
        if (!ensureTarget(w, h)) return false
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
        GLES30.glViewport(0, 0, w, h)
        GLES30.glUseProgram(copy)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oes)
        GLES30.glUniform1i(uTex, 0)
        GLES30.glUniformMatrix4fv(uMatrix, 1, false, matrix, 0)
        GLES30.glEnableVertexAttribArray(aPos)
        GLES30.glVertexAttribPointer(aPos, 2, GLES30.GL_FLOAT, false, 0, triangle)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glDisableVertexAttribArray(aPos)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, screenW, screenH)
        size[0] = w.toFloat()
        size[1] = h.toFloat()
        size[2] = 1f / w
        size[3] = 1f / h
        return true
    }

    /** [rect] for [fit] on a [screenW]×[screenH] target, recomputed only when one of them changed. */
    fun place(fit: VideoFit, screenW: Int, screenH: Int, videoW: Int, videoH: Int) {
        val k0 = fit.ordinal.toLong() shl 48 or (screenW.toLong() shl 24) or screenH.toLong()
        val k1 = videoW.toLong() shl 32 or (videoH.toLong() and 0xFFFFFFFFL)
        if (rectFor[0] == k0 && rectFor[1] == k1) return
        rectFor[0] = k0
        rectFor[1] = k1
        val r = fit.place(videoW.toFloat(), videoH.toFloat(), screenW.toFloat(), screenH.toFloat())
        r.copyInto(rect)
    }

    /** Puts the copied frame on unit [UNIT] (0 before the first one: it samples black), then unit 0 is active again. */
    fun bind() {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + UNIT)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (hasFrame) tex[0] else 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    private fun ensureTarget(w: Int, h: Int): Boolean {
        if (tex[0] != 0 && w == width && h == height) return true
        releaseTarget()
        GLES30.glGenTextures(1, tex, 0)
        GLES30.glGenFramebuffers(1, fbo, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0])
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex[0], 0)
        val complete = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        if (!complete) {
            Log.e(TAG, "background video: copy framebuffer incomplete at ${w}x$h")
            releaseTarget()
            return false
        }
        width = w
        height = h
        return true
    }

    private fun releaseTarget() {
        if (fbo[0] != 0) GLES30.glDeleteFramebuffers(1, fbo, 0)
        if (tex[0] != 0) GLES30.glDeleteTextures(1, tex, 0)
        fbo[0] = 0
        tex[0] = 0
        width = 0
        height = 0
        size.fill(0f)
    }

    private fun compileCopy(): Int {
        fun shader(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type)
            if (s == 0) return 0
            GLES30.glShaderSource(s, src)
            GLES30.glCompileShader(s)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) {
                Log.e(TAG, "background video copy: ${GLES30.glGetShaderInfoLog(s).trim()}")
                GLES30.glDeleteShader(s)
                return 0
            }
            return s
        }
        val vs = shader(GLES30.GL_VERTEX_SHADER, COPY_VERTEX)
        val fs = shader(GLES30.GL_FRAGMENT_SHADER, COPY_FRAGMENT)
        if (vs == 0 || fs == 0) {
            if (vs != 0) GLES30.glDeleteShader(vs)
            if (fs != 0) GLES30.glDeleteShader(fs)
            return 0
        }
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vs)
        GLES30.glAttachShader(program, fs)
        GLES30.glLinkProgram(program)
        GLES30.glDetachShader(program, vs)
        GLES30.glDetachShader(program, fs)
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            Log.e(TAG, "background video copy: ${GLES30.glGetProgramInfoLog(program).trim()}")
            GLES30.glDeleteProgram(program)
            return 0
        }
        aPos = GLES30.glGetAttribLocation(program, "aPos")
        uTex = GLES30.glGetUniformLocation(program, "uTex")
        uMatrix = GLES30.glGetUniformLocation(program, "uTexMatrix")
        return program
    }

    /** Frees everything (the context must be current). The player's surface is gone with it. */
    fun release() {
        releaseTarget()
        if (copy != 0) GLES30.glDeleteProgram(copy)
        if (oes != 0) GLES30.glDeleteTextures(1, intArrayOf(oes), 0)
        copy = 0
        oes = 0
        abandon()
    }

    /** The context died with its handles (or the view is gone): drop them without GL calls. */
    fun forget() {
        fbo[0] = 0
        tex[0] = 0
        width = 0
        height = 0
        size.fill(0f)
        copy = 0
        oes = 0
        abandon()
    }

    /** Releases the [SurfaceTexture] and its [Surface] (no GL calls; any thread once the GL thread is parked). */
    fun abandon() {
        surface?.release()
        surface = null
        surfaceTexture?.setOnFrameAvailableListener(null)
        surfaceTexture?.release()
        surfaceTexture = null
        pending.set(false)
        rectFor.fill(-1)
    }

    companion object {
        /** Texture unit of `uVideo` (0 `uPrev`, 1 `uField`, 2–5 `uImage0..3`). */
        const val UNIT = 6

        private const val TAG = "XrossVideo"

        // ESSL 1.0 with GL_OES_EGL_image_external: every Android GPU has it, unlike the ESSL 3 variant.
        private const val COPY_VERTEX = """
attribute vec2 aPos;
uniform mat4 uTexMatrix;
varying vec2 vUv;
void main() {
    vUv = (uTexMatrix * vec4(aPos * 0.5 + 0.5, 0.0, 1.0)).xy;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""
        private const val COPY_FRAGMENT = """#extension GL_OES_EGL_image_external : require
precision mediump float;
uniform samplerExternalOES uTex;
varying vec2 vUv;
void main() {
    gl_FragColor = vec4(texture2D(uTex, vUv).rgb, 1.0);
}
"""
    }
}

/** The background video's sizing rules (pure, unit tested). */
internal object VideoPolicy {
    /** Longest side of the copy that programs sample. */
    const val MAX_COPY_SIDE = 1280

    /** Size used before the player reports one (it does before the first frame). */
    private const val UNKNOWN_W = 1280
    private const val UNKNOWN_H = 720

    /** The copy's size for a [w]×[h] video: the long side capped at [MAX_COPY_SIDE], each side at least 1. */
    fun copySize(w: Int, h: Int): Pair<Int, Int> {
        if (w <= 0 || h <= 0) return UNKNOWN_W to UNKNOWN_H
        val long = maxOf(w, h)
        if (long <= MAX_COPY_SIDE) return w to h
        val k = MAX_COPY_SIDE.toDouble() / long
        return maxOf(1, Math.round(w * k).toInt()) to maxOf(1, Math.round(h * k).toInt())
    }
}
