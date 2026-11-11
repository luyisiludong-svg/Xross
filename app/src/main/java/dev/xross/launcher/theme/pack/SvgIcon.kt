package dev.xross.launcher.theme.pack

import android.util.Xml
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import org.xmlpull.v1.XmlPullParser
import java.io.Reader
import java.io.StringReader

/**
 * A deliberately small SVG reader for theme pack icons: flat, single-color glyphs like the ones
 * Xross ships. It understands `viewBox`, `<g>` with `translate`/`scale`/axis-aligned `matrix`
 * transforms, `<path>`, `<rect>`, `<circle>`, `<ellipse>`, `<polygon>`/`<polyline>`, `fill`,
 * `fill-opacity`, `opacity`, `fill-rule`, `stroke`/`stroke-width` and simple `style="fill:…"`.
 * Gradients, masks, text, filters and embedded images are ignored.
 *
 * Icons are drawn as silhouettes (the XMB paints its own white-to-silver gloss over them), so
 * colors only matter as "drawn or not": like `tools/icons/svg_icons.py`, black fills are taken
 * for a backdrop and dropped.
 */
object SvgIcon {
    /** Null when the text isn't an SVG this reader can draw anything from. */
    fun parse(text: String, name: String = "icon"): ImageVector? = parse(StringReader(text), name)

    fun parse(reader: Reader, name: String = "icon"): ImageVector? = try {
        read(reader, name)
    } catch (_: Exception) {
        // XmlPullParserException, NumberFormatException, IllegalArgumentException from PathParser…
        null
    }

    private class Group(val tx: Float, val ty: Float, val sx: Float, val sy: Float, val skip: Boolean)

    private fun read(reader: Reader, name: String): ImageVector? {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(reader)

        var builder: ImageVector.Builder? = null
        val groups = ArrayDeque<Group>()
        var skipDepth = 0
        var shapes = 0

        while (true) {
            when (parser.next()) {
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.START_TAG -> {
                    val tag = parser.name.substringAfter(':')
                    if (skipDepth > 0) {
                        skipDepth++
                        continue
                    }
                    if (tag in SKIPPED) {
                        skipDepth = 1
                        continue
                    }
                    val attrs = attributes(parser)
                    if (builder == null) {
                        if (tag != "svg") return null
                        val box = viewBox(attrs) ?: return null
                        builder = ImageVector.Builder(
                            name = name,
                            defaultWidth = 48.dp,
                            defaultHeight = 48.dp,
                            viewportWidth = box[2],
                            viewportHeight = box[3],
                        )
                        if (box[0] != 0f || box[1] != 0f) {
                            builder.addGroup(translationX = -box[0], translationY = -box[1])
                            groups.addLast(Group(-box[0], -box[1], 1f, 1f, skip = false))
                        } else {
                            groups.addLast(Group(0f, 0f, 1f, 1f, skip = true))
                        }
                        continue
                    }
                    when (tag) {
                        "g" -> {
                            val t = transform(attrs["transform"])
                            if (t == null) {
                                // An unsupported transform (rotation, skew): leave the group out.
                                skipDepth = 1
                            } else if (t.isIdentity) {
                                groups.addLast(Group(0f, 0f, 1f, 1f, skip = true))
                            } else {
                                builder.addGroup(translationX = t.tx, translationY = t.ty, scaleX = t.sx, scaleY = t.sy)
                                groups.addLast(Group(t.tx, t.ty, t.sx, t.sy, skip = false))
                            }
                        }
                        else -> {
                            val shape = shapeNodes(tag, attrs) ?: continue
                            if (addShape(builder, shape, attrs)) shapes++
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val tag = parser.name.substringAfter(':')
                    if (skipDepth > 0) {
                        skipDepth--
                        continue
                    }
                    if (tag == "g" || tag == "svg") {
                        val g = groups.removeLastOrNull() ?: continue
                        if (!g.skip) builder?.clearGroup()
                    }
                }
            }
        }
        return builder?.takeIf { shapes > 0 }?.build()
    }

    private val SKIPPED = setOf("defs", "clipPath", "mask", "linearGradient", "radialGradient", "pattern", "filter", "text", "image", "style", "title", "desc", "metadata", "symbol")

    private fun attributes(parser: XmlPullParser): Map<String, String> {
        val map = HashMap<String, String>()
        for (i in 0 until parser.attributeCount) map[parser.getAttributeName(i).substringAfter(':')] = parser.getAttributeValue(i)
        // Inline style wins over attributes, as in CSS.
        map["style"]?.split(';')?.forEach { decl ->
            val k = decl.substringBefore(':').trim()
            val v = decl.substringAfter(':', "").trim()
            if (k.isNotEmpty() && v.isNotEmpty()) map[k] = v
        }
        return map
    }

    /** [minX, minY, width, height] from `viewBox`, else from `width`/`height`. */
    private fun viewBox(attrs: Map<String, String>): FloatArray? {
        attrs["viewBox"]?.let { vb ->
            val n = numbers(vb)
            if (n.size == 4 && n[2] > 0f && n[3] > 0f) return floatArrayOf(n[0], n[1], n[2], n[3])
        }
        val w = attrs["width"]?.let(::length) ?: return null
        val h = attrs["height"]?.let(::length) ?: return null
        return if (w > 0f && h > 0f) floatArrayOf(0f, 0f, w, h) else null
    }

    private fun length(text: String): Float? = text.trim().removeSuffix("px").removeSuffix("pt").toFloatOrNull()

    private fun numbers(text: String): List<Float> =
        Regex("[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?").findAll(text).map { it.value.toFloat() }.toList()

    internal data class Transform(val tx: Float = 0f, val ty: Float = 0f, val sx: Float = 1f, val sy: Float = 1f) {
        val isIdentity: Boolean get() = tx == 0f && ty == 0f && sx == 1f && sy == 1f
    }

    /** Translate/scale/axis-aligned matrix transforms; null for rotation or skew. */
    internal fun transform(text: String?): Transform? {
        if (text.isNullOrBlank()) return Transform()
        var t = Transform()
        for (m in Regex("(\\w+)\\s*\\(([^)]*)\\)").findAll(text)) {
            val n = numbers(m.groupValues[2])
            t = when (m.groupValues[1]) {
                // Applied right to left: the inner transform runs first, then the outer one.
                "translate" -> t.copy(tx = t.tx + t.sx * (n.getOrElse(0) { 0f }), ty = t.ty + t.sy * (n.getOrElse(1) { 0f }))
                "scale" -> {
                    val sx = n.getOrElse(0) { 1f }
                    val sy = n.getOrElse(1) { sx }
                    t.copy(sx = t.sx * sx, sy = t.sy * sy)
                }
                "matrix" -> {
                    if (n.size != 6 || n[1] != 0f || n[2] != 0f) return null
                    t.copy(tx = t.tx + t.sx * n[4], ty = t.ty + t.sy * n[5], sx = t.sx * n[0], sy = t.sy * n[3])
                }
                else -> return null
            }
        }
        return t
    }

    /** A shape's path and its own transform (translate/scale only; others drop the shape). */
    private class Shape(val nodes: List<PathNode>, val transform: Transform)

    private fun shapeNodes(tag: String, a: Map<String, String>): Shape? {
        fun f(key: String) = a[key]?.let(::length) ?: 0f
        val d = when (tag) {
            "path" -> a["d"]?.takeIf { it.isNotBlank() } ?: return null
            "rect" -> {
                val w = f("width")
                val h = f("height")
                if (w <= 0f || h <= 0f) return null
                val x = f("x")
                val y = f("y")
                "M$x ${y}H${x + w}V${y + h}H${x}Z"
            }
            "circle" -> ellipse(f("cx"), f("cy"), f("r"), f("r")) ?: return null
            "ellipse" -> ellipse(f("cx"), f("cy"), f("rx"), f("ry")) ?: return null
            "polygon", "polyline" -> {
                val n = numbers(a["points"].orEmpty())
                if (n.size < 4) return null
                buildString {
                    append("M${n[0]} ${n[1]}")
                    for (i in 2 until n.size - 1 step 2) append("L${n[i]} ${n[i + 1]}")
                    if (tag == "polygon") append("Z")
                }
            }
            else -> return null
        }
        val nodes = PathParser().parsePathString(d).toNodes()
        if (nodes.isEmpty()) return null
        val t = transform(a["transform"]) ?: return null
        return Shape(nodes, t)
    }

    private fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float): String? {
        if (rx <= 0f || ry <= 0f) return null
        return "M${cx - rx} ${cy}A$rx $ry 0 1 0 ${cx + rx} ${cy}A$rx $ry 0 1 0 ${cx - rx} ${cy}Z"
    }

    /** Adds the shape with its paint; false when it draws nothing. */
    private fun addShape(builder: ImageVector.Builder, shape: Shape, a: Map<String, String>): Boolean {
        val opacity = (a["opacity"]?.toFloatOrNull() ?: 1f).coerceIn(0f, 1f)
        val fill = paint(a["fill"] ?: "white")
        val stroke = paint(a["stroke"] ?: "none")
        val strokeWidth = a["stroke-width"]?.let(::length) ?: 1f
        if (fill == null && (stroke == null || strokeWidth <= 0f)) return false
        val t = shape.transform.takeUnless { it.isIdentity }
        if (t != null) builder.addGroup(translationX = t.tx, translationY = t.ty, scaleX = t.sx, scaleY = t.sy)
        builder.addPath(
            pathData = shape.nodes,
            pathFillType = if (a["fill-rule"] == "evenodd") PathFillType.EvenOdd else PathFillType.NonZero,
            fill = fill?.let { SolidColor(it) },
            fillAlpha = opacity * (a["fill-opacity"]?.toFloatOrNull() ?: 1f).coerceIn(0f, 1f),
            stroke = stroke?.let { SolidColor(it) },
            strokeAlpha = opacity * (a["stroke-opacity"]?.toFloatOrNull() ?: 1f).coerceIn(0f, 1f),
            strokeLineWidth = strokeWidth,
        )
        if (t != null) builder.clearGroup()
        return true
    }

    /** Null for "none", transparent and black (a backdrop); white for anything else drawn. */
    private fun paint(value: String): Color? {
        val v = value.trim().lowercase()
        if (v == "none" || v == "transparent") return null
        if (v == "black" || v == "#000" || v == "#000000" || v == "#ff000000") return null
        if (v.startsWith("url(")) return Color.White // a gradient: draw it as a solid shape
        return Color.White
    }
}
