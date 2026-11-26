package dev.xross.launcher.theme.shader

import dev.xross.launcher.theme.pack.VideoFit
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * What moves a scene this frame (Live backgrounds § 3b). Filled by the renderer once per frame and
 * read by [ScenePoser]; mutable so the GL loop allocates nothing.
 */
class SceneInputs {
    /** Seconds of animation (the wave speed applied; frozen in Still mode). Not wrapped: motions use it in double precision. */
    var time = 0.0

    /** The focus, 0..1 from the screen's top-left corner, y down; (0.5, 0.5) is the centre. */
    var focusX = 0.5f
    var focusY = 0.5f

    /** `uAudioBass`, `uAudioBeat`, `uTransition`, and the daylight (0 at midnight .. 1 at noon). */
    var bass = 0f
    var beat = 0f
    var transition = 0f
    var daylight = 0f

    /** What the `audio` driver follows: the bass, a beat on top. */
    val audioLevel: Float get() = (bass + 0.5f * beat).coerceIn(0f, 1f)
}

/**
 * Where one layer lands this frame: a parallelogram in render pixels (top-left origin, y down),
 * [originX]/[originY] being the corner the image's top-left texel maps to and [axisX]/[axisY] its
 * two edges, plus the window of the texture shown ([u0], [v0], [du], [dv]; v down, so the renderer
 * flips it) and the opacity. Mutable and reused, like [SceneInputs].
 */
class LayerPose {
    var originX = 0f
    var originY = 0f
    var axisXx = 0f
    var axisXy = 0f
    var axisYx = 0f
    var axisYy = 0f
    var u0 = 0f
    var v0 = 0f
    var du = 1f
    var dv = 1f
    var opacity = 1f
}

/**
 * A layer's placement and motion as pure functions of its look, its texture, the screen and the
 * frame's [SceneInputs] (so they are unit tested without GL):
 *  - **Box.** `rect` in screen fractions; else `fit` (`FILL` covers, `FIT` contains, `STRETCH`
 *    fills, `CENTER` is the image's size at 1280 px on the screen's long side, shrunk to fit)
 *    placed by `anchor`. A sprite fits one cell, not the sheet.
 *  - **Parallax.** The layer shifts away from the focus by up to [PARALLAX_SHIFT] of the screen
 *    at `parallax` 1. A covering fit (FILL, STRETCH) is enlarged by that much, so its edges never
 *    show.
 *  - **Motion.** `sway` and `pulse` are sines of their periods; `audio`, `transition` and
 *    `daylight` add their amounts times the input. Scale and rotation are about the box's centre.
 *    `scroll` moves the texture window, in texels of the uploaded texture per second.
 *  - **Sprite.** Cell `floor(time × fps) mod count`, left to right, top to bottom.
 */
object ScenePoser {
    /** Largest parallax shift, as a fraction of the screen's width and height. */
    const val PARALLAX_SHIFT = 0.03f

    /**
     * Poses [layer] (an image or a sprite; its texture [texW] × [texH] texels) on a [screenW] ×
     * [screenH] target into [out]. False when there is nothing to draw (no size, or transparent).
     */
    fun pose(layer: SceneLayer, texW: Int, texH: Int, screenW: Int, screenH: Int, inputs: SceneInputs, out: LayerPose): Boolean {
        if (texW <= 0 || texH <= 0 || screenW <= 0 || screenH <= 0) return false
        val look = layer.look
        val sw = screenW.toFloat()
        val sh = screenH.toFloat()
        val ref = max(sw, sh) / Scene.REFERENCE_W
        val sprite = layer as? SceneLayer.Sprite
        val cellW = texW.toFloat() / (sprite?.columns ?: 1)
        val cellH = texH.toFloat() / (sprite?.rows ?: 1)

        // The box, before motion.
        val shiftX = PARALLAX_SHIFT * look.parallax * sw
        val shiftY = PARALLAX_SHIFT * look.parallax * sh
        var bx: Float
        var by: Float
        var bw: Float
        var bh: Float
        val rect = look.rect
        if (rect != null) {
            bx = rect.x * sw
            by = rect.y * sh
            bw = rect.width * sw
            bh = rect.height * sh
        } else {
            when (look.fit) {
                VideoFit.STRETCH -> {
                    bw = sw + 2 * shiftX
                    bh = sh + 2 * shiftY
                }
                else -> {
                    val cover = max((sw + 2 * shiftX) / cellW, (sh + 2 * shiftY) / cellH)
                    val contain = min(sw / cellW, sh / cellH)
                    val k = when (look.fit) {
                        VideoFit.FILL -> cover
                        VideoFit.FIT -> contain
                        else -> min(ref, contain) // CENTER
                    }
                    bw = cellW * k
                    bh = cellH * k
                }
            }
            bx = look.anchor.x * (sw - bw)
            by = look.anchor.y * (sh - bh)
        }

        // Motion.
        val t = inputs.time
        val m = look.motion
        var angle = 0f
        var scale = 1f
        var opacity = look.opacity
        var dx = 0f
        var dy = 0f
        m.sway?.let { s ->
            val w = wave(t, s.period)
            angle += s.angle * w
            dx += s.offset.x * ref * w
            dy += s.offset.y * ref * w
        }
        m.pulse?.let { p ->
            val w = wave(t, p.period)
            scale += p.scale * w
            opacity += p.opacity * w
        }
        fun drive(d: Drive?, level: Float) {
            if (d == null || level == 0f) return
            scale += d.scale * level
            opacity += d.opacity * level
            angle += d.angle * level
            dx += d.offset.x * ref * level
            dy += d.offset.y * ref * level
        }
        drive(m.audio, inputs.audioLevel)
        drive(m.transition, inputs.transition)
        drive(m.daylight, inputs.daylight)
        dx -= (inputs.focusX - 0.5f) * 2f * shiftX
        dy -= (inputs.focusY - 0.5f) * 2f * shiftY

        opacity = opacity.coerceIn(0f, 1f)
        scale = scale.coerceAtLeast(0f)
        if (opacity <= 1f / 512f || scale == 0f || bw <= 0f || bh <= 0f) return false

        val rad = angle * (PI / 180.0)
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        val w = bw * scale
        val h = bh * scale
        val cx = bx + bw / 2 + dx
        val cy = by + bh / 2 + dy
        out.axisXx = c * w
        out.axisXy = s * w
        out.axisYx = -s * h
        out.axisYy = c * h
        out.originX = cx - (out.axisXx + out.axisYx) / 2
        out.originY = cy - (out.axisXy + out.axisYy) / 2

        // The texture window.
        if (sprite != null) {
            val frame = frame(t, sprite.fps, sprite.count)
            out.du = 1f / sprite.columns
            out.dv = 1f / sprite.rows
            out.u0 = (frame % sprite.columns) * out.du
            out.v0 = (frame / sprite.columns) * out.dv
        } else {
            out.u0 = 0f
            out.v0 = 0f
            out.du = 1f
            out.dv = 1f
        }
        // Moving the content by +x shows texels further left: the window moves the other way.
        out.u0 -= scrolled(t, m.scroll.x, texW)
        out.v0 -= scrolled(t, m.scroll.y, texH)
        out.opacity = opacity
        return true
    }

    /** -1..1 on a sine of [period] seconds, in double precision so it never jumps after days. */
    fun wave(time: Double, period: Float): Float = sin(2.0 * PI * (time / period.toDouble() % 1.0)).toFloat()

    /** The sprite cell at [time]: `floor(time × fps) mod count`. */
    fun frame(time: Double, fps: Float, count: Int): Int {
        if (count <= 1) return 0
        val n = floor(time * fps).toLong() % count
        return (if (n < 0) n + count else n).toInt()
    }

    /** The window's offset after [time] at [speed] texels per second over [size] texels, wrapped to 0..1. */
    fun scrolled(time: Double, speed: Float, size: Int): Float {
        if (speed == 0f || size <= 0) return 0f
        val turns = time * speed / size
        return (turns - floor(turns)).toFloat()
    }
}
