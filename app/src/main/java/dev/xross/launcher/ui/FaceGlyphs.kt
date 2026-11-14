package dev.xross.launcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp

/**
 * The shape-only button labels (the PlayStation set ✕ ○ △ □ and the minimal set's triangles
 * ▽ ▷ △ ◁), drawn as strokes instead of text: a font draws these characters at different sizes and
 * heights (○ small, △ and ✕ off center), so the hints looked uneven. Every shape fills the same
 * box, centered, with the same stroke.
 */
enum class FaceShape {
    CROSS, CIRCLE, SQUARE,
    /** A triangle pointing up; the others are it turned. */
    TRIANGLE_UP, TRIANGLE_DOWN, TRIANGLE_RIGHT, TRIANGLE_LEFT;

    companion object {
        /** The shape that draws [glyph]; null for letters and words ("A", "Enter"), which stay text. */
        fun of(glyph: String): FaceShape? = when (glyph) {
            "✕", "×", "✖" -> CROSS
            "○", "◯" -> CIRCLE
            "□", "☐" -> SQUARE
            "△" -> TRIANGLE_UP
            "▽" -> TRIANGLE_DOWN
            "▷" -> TRIANGLE_RIGHT
            "◁" -> TRIANGLE_LEFT
            else -> null
        }
    }
}

/**
 * [shape] in a [box] × [box] square, stroked in [color]. Accessibility (and the device tests) still
 * read [glyph] as its text.
 */
@Composable
internal fun FaceShapeGlyph(shape: FaceShape, glyph: String, box: Dp, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(box).semantics { text = AnnotatedString(glyph) }) {
        val stroke = size.minDimension * 0.13f
        translate(0f, stroke * 0.55f) { drawFaceShape(shape, Color(0x66000000), stroke) }
        drawFaceShape(shape, color, stroke)
    }
}

internal fun DrawScope.drawFaceShape(shape: FaceShape, color: Color, stroke: Float) {
    val s = size.minDimension
    val c = Offset(size.width / 2, size.height / 2)
    val line = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
    when (shape) {
        FaceShape.CROSS -> {
            // Arms a little longer than the circle is wide: a cross looks smaller than its box.
            val h = s * 0.40f
            drawLine(color, Offset(c.x - h, c.y - h), Offset(c.x + h, c.y + h), stroke, StrokeCap.Round)
            drawLine(color, Offset(c.x - h, c.y + h), Offset(c.x + h, c.y - h), stroke, StrokeCap.Round)
        }
        FaceShape.CIRCLE -> drawCircle(color, s * 0.5f - stroke / 2, c, style = line)
        FaceShape.SQUARE -> {
            // A square looks bigger than a circle of the same width.
            val side = s * 0.80f - stroke
            drawRect(color, Offset(c.x - side / 2, c.y - side / 2), Size(side, side), style = line)
        }
        else -> {
            val turn = when (shape) {
                FaceShape.TRIANGLE_DOWN -> 180f
                FaceShape.TRIANGLE_RIGHT -> 90f
                FaceShape.TRIANGLE_LEFT -> 270f
                else -> 0f
            }
            // An equilateral triangle around a point a little below its centroid: the optical
            // middle, between the centroid (looks high) and the bounding box's center (looks low).
            val r = (s - stroke) * 0.6f
            val o = Offset(c.x, c.y + r * 0.12f)
            val path = Path().apply {
                for (i in 0 until 3) {
                    val a = Math.toRadians(-90.0 + i * 120.0)
                    val x = o.x + r * Math.cos(a).toFloat()
                    val y = o.y + r * Math.sin(a).toFloat()
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
                close()
            }
            rotate(turn, c) { drawPath(path, color, style = line) }
        }
    }
}
