package dev.xross.launcher.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import dev.xross.launcher.media.ThumbKind
import dev.xross.launcher.model.ArtKind
import dev.xross.launcher.state.NodeIcon
import dev.xross.launcher.state.XmbCategory
import dev.xross.launcher.state.XmbEvent
import dev.xross.launcher.state.XmbNode
import dev.xross.launcher.state.XmbUiState
import dev.xross.launcher.theme.pack.Motion
import dev.xross.launcher.theme.pack.NineSlice
import dev.xross.launcher.theme.pack.Skin
import dev.xross.launcher.theme.pack.SlashArt
import dev.xross.launcher.theme.pack.SlashCategories
import dev.xross.launcher.theme.pack.SlashDecal
import dev.xross.launcher.theme.pack.SlashFocusedContent
import dev.xross.launcher.theme.pack.SlashGeometry
import dev.xross.launcher.theme.pack.SlashHighlight
import dev.xross.launcher.theme.pack.SlashLayout
import dev.xross.launcher.theme.pack.SlashTextAlign
import dev.xross.launcher.theme.pack.SlashTitleCase
import dev.xross.launcher.theme.pack.SlashTransition
import dev.xross.launcher.theme.pack.TextOverflowMode
import dev.xross.launcher.theme.pack.ThemeManifest
import dev.xross.launcher.theme.pack.nav
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.tan
import kotlinx.coroutines.launch

// The SLASH menu style (Menu styles slices 2–4): a bold, slanted list of art-skinned rows, a
// focused panel that can show the game's logo, categories on an icon bar with a decal, framed
// hero art, and motion that cascades rows in and wipes or shatters the list it replaces.

/**
 * How colors a pack names for the menu resolve: `accent` (the look's glow color), a palette role
 * name (`surface`, `accentSecondary`, `textPrimary`…) or a hex color. Provided by `XmbScreen` for
 * the menu, the status bar and the hints.
 */
@Immutable
class MenuPaint(val accent: Color, private val roles: ThemeManifest.Palette?) {
    fun color(name: String?): Color? {
        val n = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (n.startsWith("#")) return ThemeManifest.parseColor(n)?.let(::Color)
        val role = when (n.lowercase().replace("_", "").replace("-", "")) {
            "accent" -> return roles?.accent?.let(ThemeManifest::parseColor)?.let(::Color) ?: accent
            "canvas" -> roles?.canvas
            "surface" -> roles?.surface
            "surfaceborder" -> roles?.surfaceBorder
            "accentsecondary" -> roles?.accentSecondary
            "focusgold" -> roles?.focusGold
            "textprimary" -> roles?.textPrimary
            "textsecondary" -> roles?.textSecondary
            else -> null
        }
        return role?.let(ThemeManifest::parseColor)?.let(::Color)
    }

    companion object {
        val DEFAULT = MenuPaint(Color.White, null)
    }
}

val LocalMenuPaint = staticCompositionLocalOf { MenuPaint.DEFAULT }

/**
 * The SLASH style's sizes on one screen, from [m] (its short side and `uiScale`) and the pack's
 * [l]. Fractions of the screen stay fractions; everything else follows [XmbMetrics.s].
 */
internal class SlashMetrics(val m: XmbMetrics, val l: SlashLayout, logo: Boolean) {
    val width = m.width
    val height = m.height
    val s = m.s
    val rowH = s * 0.082f
    val gap = s * (0.012f + m.layout.itemSpacingDp / 1000f)
    val pitch = rowH + gap
    val focusedH = maxOf(rowH * l.focusedScale, if (logo) height * l.focusedHeight else 0f)
    val pad = s * 0.035f
    val box = SlashGeometry.rowBox(l, width, pad, s * 0.25f)
    val titleSize = s * 0.05f
    val iconSize = s * 0.085f
    val iconPitch = s * 0.15f
    val categoryY = height * l.categoryY
    val categoryLeft = width * l.listX + iconSize / 2
    val headerH = rowH * 1.25f
    val listTop = maxOf(height * l.listY, categoryY + iconSize * 0.9f + s * 0.06f)
    val listBottom = height - s * 0.14f
    /** Rows of room under [listTop] with the focused panel open, at least three. */
    val visible = (((listBottom - listTop) - (focusedH - rowH)) / pitch).toInt().coerceAtLeast(3)
    val skew = tan(Math.toRadians(l.skewDeg.toDouble())).toFloat()

    /** Row [i]'s top edge with the list's first row [top] on screen and the focus at [f] (both animated), and the list [header] (0..1) open above. */
    fun rowTop(i: Int, top: Float, f: Float, header: Float): Float =
        listTop + header * headerH + (i - top) * pitch + (focusedH - rowH) * (i - f).coerceIn(0f, 1f)

    /** Row [i]'s height with the focus at [f]. */
    fun rowHeight(i: Int, f: Float): Float = rowH + (focusedH - rowH) * (1f - abs(i - f)).coerceIn(0f, 1f)
}

/** The SLASH style's anchors: the focused row's centre, and the status bar's left cluster stopping above the category bar. */
internal fun MenuAnchors.Companion.slash(sm: SlashMetrics, focusedIndex: Int, top: Int, depth: Int): MenuAnchors {
    val y = sm.rowTop(focusedIndex, top.toFloat(), focusedIndex.toFloat(), if (depth > 0) 1f else 0f) + sm.focusedH / 2
    return MenuAnchors(
        focus = Offset((sm.box.left.coerceAtLeast(0f) + sm.box.left + sm.box.width) / 2, y),
        statusClearance = (sm.categoryY - sm.iconSize * 0.9f - sm.s * 0.02f).coerceAtLeast(0f),
    )
}

/**
 * One transition between lists: what the menu draws while a category switch, a drill-down or a
 * back step replaces the list. The list draws into one of two [GraphicsLayer]s; on a change the
 * last one is frozen (it keeps the old list's picture) and drawn on top, wiped, shattered or
 * slid away, while the new list cascades in beneath it. One progress value drives every row.
 */
@Stable
internal class SlashMotion(val layers: Array<GraphicsLayer>) {
    enum class Kind { NONE, CATEGORY, DRILL_IN, DRILL_OUT }

    var key: String? = null
    var current = 0
    var kind = Kind.NONE
    /** +1 when the new category is to the right of the old one, −1 to the left. */
    var direction = 1
    var hasFrozen = false
    var totalMs = 0
    /** Milliseconds since the transition began; [totalMs] at rest. */
    var elapsed by mutableFloatStateOf(Float.MAX_VALUE)

    val frozen: GraphicsLayer get() = layers[1 - current]
    val live: GraphicsLayer get() = layers[current]
    val running: Boolean get() = elapsed < totalMs

    fun begin(key: String, kind: Kind, direction: Int, totalMs: Int, still: Boolean) {
        val first = this.key == null
        this.key = key
        this.kind = if (first) Kind.NONE else kind
        this.direction = direction
        current = 1 - current
        hasFrozen = !first && !still
        this.totalMs = totalMs
        elapsed = if (first || still) Float.MAX_VALUE else 0f
    }
}

/** How long the overlay part of a transition takes, by kind. */
private fun overlayMs(kind: SlashMotion.Kind, t: SlashTransition): Int = when (kind) {
    SlashMotion.Kind.NONE -> 0
    SlashMotion.Kind.DRILL_IN, SlashMotion.Kind.DRILL_OUT -> 260
    SlashMotion.Kind.CATEGORY -> when (t) {
        SlashTransition.WIPE -> 300
        SlashTransition.SHARDS -> 360
        SlashTransition.FADE -> 200
    }
}

/** A row's slide-in time within the cascade. */
private const val RowInMs = 180

private fun ease(t: Float): Float {
    val c = t.coerceIn(0f, 1f)
    return 1f - (1f - c) * (1f - c) * (1f - c)
}

/**
 * The SLASH menu style. It reads the same [XmbUiState] and sends the same [XmbEvent]s as the XMB:
 * left and right switch category, up and down move the focus, confirm and back drill in and out.
 * [onAnchors] reports where the focused row sits; [onTransition] the transition's progress (0..1,
 * 1 at rest) for scenes' `transition` driver.
 */
@Composable
internal fun SlashMenu(
    state: XmbUiState,
    m: XmbMetrics,
    motion: Motion,
    still: Boolean,
    idle: Boolean,
    catPos: () -> Float,
    loadArt: suspend (String, List<ArtKind>, Int) -> ImageBitmap?,
    onEvent: (XmbEvent) -> Unit,
    onAnchors: (MenuAnchors) -> Unit,
    onTransition: (Float) -> Unit,
) {
    val l = m.layout.slash
    val instant = motion.instant || still
    val logo = l.focusedContent != SlashFocusedContent.TITLE
    val sm = remember(m, l) { SlashMetrics(m, l, logo) }
    val density = LocalDensity.current
    val images = LocalMenuImages.current
    val paint = LocalMenuPaint.current
    val depth = state.depth

    // Scrolling: the list holds still while the focus moves inside it (SlashGeometry.nextTop).
    var topIndex by remember { mutableIntStateOf(0) }
    var lastListKey by remember { mutableStateOf(state.listKey) }
    if (lastListKey != state.listKey) {
        lastListKey = state.listKey
        topIndex = SlashGeometry.nextTop(0, state.focusedIndex, state.items.size, sm.visible)
    } else {
        topIndex = SlashGeometry.nextTop(topIndex, state.focusedIndex, state.items.size, sm.visible)
    }
    val fPos = remember { androidx.compose.animation.core.Animatable(state.focusedIndex.toFloat()) }
    val topPos = remember { androidx.compose.animation.core.Animatable(topIndex.toFloat()) }
    val pop = remember { androidx.compose.animation.core.Animatable(0f) }
    val header by animateFloatAsState(if (depth > 0) 1f else 0f, if (instant) androidx.compose.animation.core.snap() else motion.nav(), label = "slashHeader")
    // A new list snaps the focus and the scroll; moving inside a list glides.
    val springKey = remember { arrayOf(state.listKey, state.listKey) }
    LaunchedEffect(state.listKey, state.focusedIndex, instant) {
        if (instant || springKey[0] != state.listKey) {
            springKey[0] = state.listKey
            fPos.snapTo(state.focusedIndex.toFloat())
        } else {
            fPos.animateTo(state.focusedIndex.toFloat(), motion.nav())
        }
    }
    LaunchedEffect(state.listKey, topIndex, instant) {
        if (instant || springKey[1] != state.listKey) {
            springKey[1] = state.listKey
            topPos.snapTo(topIndex.toFloat())
        } else {
            topPos.animateTo(topIndex.toFloat(), motion.nav())
        }
    }
    // The focused row lands with a small overshoot (slash.pop) on top of the nav spring.
    LaunchedEffect(state.focusedIndex, state.listKey) {
        if (instant || l.pop <= 0f) return@LaunchedEffect
        pop.snapTo(0f)
        // Released from rest with a push, the under-damped spring swings out to about 1 and settles.
        val stiffness = motion.stiffness.coerceAtMost(900f)
        pop.animateTo(0f, spring(dampingRatio = 0.35f, stiffness = stiffness), initialVelocity = kotlin.math.sqrt(stiffness) * 1.6f)
    }

    // Transitions (slice 3): one progress value, swapped in before the new list's first frame.
    val layerA = rememberGraphicsLayer()
    val layerB = rememberGraphicsLayer()
    val tr = remember { SlashMotion(arrayOf(layerA, layerB)) }
    var lastDepth by remember { mutableIntStateOf(depth) }
    var lastCategory by remember { mutableIntStateOf(state.categoryIndex) }
    val cascadeMs = SlashGeometry.cascadeMs(minOf(state.items.size, sm.visible + 1), l.staggerMs, RowInMs, instant)
    SideEffect {
        if (tr.key != state.listKey) {
            val kind = when {
                depth > lastDepth -> SlashMotion.Kind.DRILL_IN
                depth < lastDepth -> SlashMotion.Kind.DRILL_OUT
                else -> SlashMotion.Kind.CATEGORY
            }
            val dir = if (state.categoryIndex >= lastCategory) 1 else -1
            val total = maxOf(cascadeMs, overlayMs(kind, l.transition))
            tr.begin(state.listKey, kind, dir, total, instant)
            lastDepth = depth
            lastCategory = state.categoryIndex
        }
    }
    val signalTransition by rememberUpdatedState(onTransition)
    LaunchedEffect(tr.key) {
        if (!tr.running) {
            signalTransition(1f)
            return@LaunchedEffect
        }
        val total = tr.totalMs.toFloat()
        animate(0f, total, animationSpec = tween(tr.totalMs, easing = LinearEasing)) { v, _ ->
            tr.elapsed = v
            signalTransition((v / total).coerceIn(0f, 1f))
        }
        tr.elapsed = Float.MAX_VALUE
        signalTransition(1f)
    }

    // Anchors: the focused row's centre once the list settles.
    val anchorsCb by rememberUpdatedState(onAnchors)
    LaunchedEffect(sm, state.focusedIndex, topIndex, depth) {
        anchorsCb(MenuAnchors.slash(sm, state.focusedIndex, topIndex, depth))
    }

    val front = paint.color(l.highlightColors.getOrNull(0)) ?: paint.accent
    val back = paint.color(l.highlightColors.getOrNull(1)) ?: Color.Black
    val rowText = paint.color(l.rowTextColor) ?: Color.White
    val focusedText = paint.color(l.focusedTextColor) ?: when {
        l.focusedSkin != null -> Color.White
        l.highlight == SlashHighlight.NONE -> front
        front.luminance() > 0.55f -> Color.Black
        else -> Color.White
    }

    // Categories (slice 4), above the list and outside its transition.
    SlashCategoryBar(state.categories, state.categoryIndex, sm, catPos, still || idle, depth > 0, front, onEvent)

    // The framed art of the focused item (slice 4), behind the list.
    if (l.art != SlashArt.NONE) SlashArtPanel(state.focused, sm, instant, loadArt)

    // Drill-down: the opened folder's title as a slanted header above the list.
    val folderTitle = state.breadcrumb.lastOrNull()
    var shownHeader by remember { mutableStateOf(folderTitle) }
    if (folderTitle != null) shownHeader = folderTitle
    shownHeader?.takeIf { header > 0f }?.let { title ->
        SlashHeader(title, sm, { header }, front, focusedText)
    }

    val tap by rememberUpdatedState<(Int) -> Unit> { onEvent(XmbEvent.Menu.ItemTap(it)) }
    val longPress by rememberUpdatedState<(Int) -> Unit> { onEvent(XmbEvent.Menu.ItemLongPress(it)) }
    val highlightPath = remember { Path() }
    val cornerCache = remember(sm) { HashMap<Int, FloatArray>() }
    val skinPatches = remember(sm) { HashMap<Long, List<NineSlice.Patch>>() }
    val dpx = density.density * m.layout.uiScale
    val focusedSkinImage = images[l.focusedSkin?.path]

    // The list and its highlight, drawn through the transition's layers.
    Box(
        Modifier
            .fillMaxSize()
            .drawWithContent { drawTransition(tr, sm, l, front, back, this) }
            .drawBehind {
                // The highlight or the focused panel, following the focus with the nav spring.
                val f = fPos.value
                val fi = state.focusedIndex
                if (state.items.isEmpty()) return@drawBehind
                val y = sm.rowTop(fi, topPos.value, f, header) + (sm.rowHeight(fi, f) - sm.focusedH) / 2
                val rowIn = rowProgress(tr, fi - floor(topPos.value).toInt(), sm, l, instant)
                val slide = (1f - rowIn) * -sm.s * 0.4f
                val w = sm.box.width * 1.04f * (1f + pop.value * l.pop)
                val h = sm.focusedH
                val x = sm.box.left + slide
                val tilt = SlashGeometry.rowTilt(l, fi) * 0.5f
                withTransform({
                    translate(x, y)
                    rotate(tilt, Offset(w / 2, h / 2))
                    transform(skewAbout(sm.skew, h / 2))
                }) {
                    val alpha = rowIn
                    val skin = l.focusedSkin
                    if (skin != null && focusedSkinImage != null) {
                        val key = (w.toLong() shl 32) or h.toLong()
                        val patches = skinPatches.getOrPut(key) { skinPieces(focusedSkinImage, skin, w, h, dpx) }
                        drawSkinAt(focusedSkinImage, skin, Size(w, h), dpx, paint.color(skin.tint), alpha, patches)
                    } else when (if (skin != null) SlashHighlight.SLAB else l.highlight) {
                        SlashHighlight.NONE -> Unit
                        SlashHighlight.SLAB -> {
                            drawRect(back, topLeft = Offset(sm.s * 0.012f, sm.s * 0.012f), size = Size(w, h), alpha = alpha)
                            drawRect(front, size = Size(w, h), alpha = alpha)
                        }
                        SlashHighlight.SHARD -> {
                            val lo = floor(f).toInt()
                            val hi = ceil(f).toInt()
                            val a = cornerCache.getOrPut(lo) { SlashGeometry.highlightCorners(l.seed, lo, w, h, sm.s * 0.01f, l.jitter) }
                            val b = cornerCache.getOrPut(hi) { SlashGeometry.highlightCorners(l.seed, hi, w, h, sm.s * 0.01f, l.jitter) }
                            val t = if (instant) 1f else f - lo
                            // The back shard: smaller, offset down and right.
                            quad(highlightPath, a, b, t, sm.s * 0.02f, sm.s * 0.016f, 0.92f, w, h)
                            drawPath(highlightPath, back, alpha = alpha)
                            quad(highlightPath, a, b, t, 0f, 0f, 1f, w, h)
                            drawPath(highlightPath, front, alpha = alpha)
                        }
                    }
                }
            },
    ) {
        val first = (floor(topPos.value).toInt() - 1).coerceAtLeast(0)
        val last = (ceil(topPos.value).toInt() + sm.visible + 1).coerceAtMost(state.items.lastIndex)
        val dim = m.layout.unfocusedAlpha ?: 0.82f
        val titleSp = with(density) { sm.titleSize.toSp() }
        val rowWdp = with(density) { sm.box.width.toDp() }
        val rowHdp = with(density) { sm.focusedH.toDp() }
        for (i in first..last) {
            val node = state.items[i]
            key(state.listKey, node.id) {
                val focused = i == state.focusedIndex
                val focus = remember(i) { { (1f - abs(i - fPos.value)).coerceIn(0f, 1f) } }
                val interaction = remember { MutableInteractionSource() }
                val skin = l.rowSkin(i)
                val rowImage = images[skin?.path]
                val tilt = SlashGeometry.rowTilt(l, i)
                val nudge = SlashGeometry.rowNudge(l, i) * sm.rowH
                val patches = remember(sm, skin, rowImage) { HashMap<Long, List<NineSlice.Patch>>() }
                Box(
                    Modifier
                        .offset {
                            val f = fPos.value
                            val y = sm.rowTop(i, topPos.value, f, header) + sm.rowHeight(i, f) / 2 - sm.focusedH / 2
                            val slide = (1f - rowProgress(tr, i - floor(topPos.value).toInt(), sm, l, instant)) * -sm.s * 0.4f
                            IntOffset((sm.box.left + nudge + slide).roundToInt(), y.roundToInt())
                        }
                        .size(rowWdp, rowHdp)
                        .graphicsLayer {
                            rotationZ = tilt * (1f - focus() * 0.5f)
                            val p = if (focused) 1f + pop.value * l.pop else 1f
                            scaleX = p
                            scaleY = p
                        }
                        .fadeAlpha {
                            val inP = rowProgress(tr, i - floor(topPos.value).toInt(), sm, l, instant)
                            // Rows scrolled past the list's top fade instead of clipping.
                            val rowY = sm.rowTop(i, topPos.value, fPos.value, header)
                            val edge = ((rowY - (sm.listTop - sm.pitch)) / sm.pitch).coerceIn(0f, 1f)
                            val bottom = ((sm.listBottom + sm.pitch - rowY) / sm.pitch).coerceIn(0f, 1f)
                            inP * edge * bottom * (dim + (1f - dim) * focus())
                        }
                        .drawWithContent {
                            val f = focus()
                            val h = sm.rowHeight(i, fPos.value)
                            val top = (size.height - h) / 2
                            withTransform({ transform(skewAbout(sm.skew, size.height / 2)) }) {
                                // The row's own skin fades as the focused panel takes its place.
                                if (skin != null && rowImage != null && f < 1f) {
                                    val k = (size.width.toLong() shl 32) or h.toLong()
                                    val pieces = patches.getOrPut(k) { skinPieces(rowImage, skin, size.width, h, dpx) }
                                    translate(0f, top) {
                                        drawSkinAt(rowImage, skin, Size(size.width, h), dpx, paint.color(skin.tint), 1f - f, pieces)
                                    }
                                }
                                this@drawWithContent.drawContent()
                            }
                        }
                        .combinedClickable(
                            interactionSource = interaction,
                            indication = null,
                            onLongClick = { longPress(i) },
                            onClick = { tap(i) },
                        ),
                ) {
                    SlashRowContent(node, focused, focus, sm, titleSp, focusedText, rowText, loadArt)
                }
            }
        }
    }
}

/**
 * Row [slot]'s cascade progress (0..1, eased) in the running transition: rows start
 * `staggerMs` apart from the top of the visible list. 1 at rest and when [instant].
 */
private fun rowProgress(tr: SlashMotion, slot: Int, sm: SlashMetrics, l: SlashLayout, instant: Boolean): Float {
    if (instant || !tr.running) return 1f
    val delay = SlashGeometry.staggerDelay(slot.coerceAtLeast(0), sm.visible + 1, l.staggerMs, RowInMs, false)
    return ease((tr.elapsed - delay) / RowInMs)
}

/** A skew along x by [k] (tan of the angle) about the horizontal line at [pivotY]: x' = x + k (y − pivotY). */
internal fun skewAbout(k: Float, pivotY: Float): Matrix {
    val mx = SkewMatrix
    mx.reset()
    mx.values[Matrix.SkewX] = k
    mx.values[Matrix.TranslateX] = -k * pivotY
    return mx
}

/** One reusable matrix (drawing happens on the main thread): no allocation per frame. */
private val SkewMatrix = Matrix()

/**
 * The shard quad into [path]: the corners [a] and [b] mixed by [t] (the highlight morphing as the
 * focus moves), shrunk by [scale] about the box centre and moved by ([dx], [dy]).
 */
private fun quad(path: Path, a: FloatArray, b: FloatArray, t: Float, dx: Float, dy: Float, scale: Float, w: Float, h: Float) {
    path.rewind()
    for (c in 0 until 4) {
        val x = a[c * 2] + (b[c * 2] - a[c * 2]) * t
        val y = a[c * 2 + 1] + (b[c * 2 + 1] - a[c * 2 + 1]) * t
        val px = w / 2 + (x - w / 2) * scale + dx
        val py = h / 2 + (y - h / 2) * scale + dy
        if (c == 0) path.moveTo(px, py) else path.lineTo(px, py)
    }
    path.close()
}

/** Nine-slice pieces of [image] as [skin] over a [w] × [h] box, widened by the skin's outset. */
private fun skinPieces(image: ImageBitmap, skin: Skin, w: Float, h: Float, dpx: Float): List<NineSlice.Patch> {
    val o = skin.outset
    return NineSlice.patches(image.width, image.height, skin.slice, w + (o.left + o.right) * dpx, h + (o.top + o.bottom) * dpx, dpx)
}

/** [drawSkin] into a box of [box] at the current origin (not the scope's own size). */
internal fun DrawScope.drawSkinAt(image: ImageBitmap, skin: Skin, box: Size, dpx: Float, tint: Color?, alpha: Float, patches: List<NineSlice.Patch>) {
    if (alpha <= 0f) return
    val ox = skin.outset.left * dpx
    val oy = skin.outset.top * dpx
    val filter = tint?.let { ColorFilter.tint(it, BlendMode.Modulate) }
    for (p in patches) {
        val x0 = (p.dstX - ox).roundToInt()
        val y0 = (p.dstY - oy).roundToInt()
        drawImage(
            image,
            srcOffset = IntOffset(p.srcX, p.srcY),
            srcSize = IntSize(p.srcW, p.srcH),
            dstOffset = IntOffset(x0, y0),
            dstSize = IntSize(((p.dstX + p.dstW - ox).roundToInt() - x0), ((p.dstY + p.dstH - oy).roundToInt() - y0)),
            alpha = alpha,
            colorFilter = filter,
        )
    }
}

/**
 * The list as the transition [tr] draws it: the live list recorded into its layer and drawn,
 * then (while a transition runs) the frozen old list over it, wiped, shattered, faded or slid.
 */
private fun DrawScope.drawTransition(tr: SlashMotion, sm: SlashMetrics, l: SlashLayout, front: Color, back: Color, content: ContentDrawScope) {
    val live = tr.live
    live.record { content.drawContent() }
    if (!tr.running || !tr.hasFrozen || tr.kind == SlashMotion.Kind.NONE) {
        drawLayer(live)
        return
    }
    val t = ease(tr.elapsed / overlayMs(tr.kind, l.transition).coerceAtLeast(1))
    val old = tr.frozen
    when (tr.kind) {
        SlashMotion.Kind.DRILL_IN, SlashMotion.Kind.DRILL_OUT -> {
            drawLayer(live)
            if (t < 1f) {
                // The parent slides out left, skewed and dimmed; backing out, the child slides right.
                val dx = (if (tr.kind == SlashMotion.Kind.DRILL_IN) -1f else 1f) * t * sm.s * 0.6f
                withTransform({ translate(dx, 0f); transform(skewAbout(sm.skew * (1f + t), size.height / 2)) }) {
                    old.alpha = (1f - t) * 0.6f
                    drawLayer(old)
                    old.alpha = 1f
                }
            }
        }
        else -> when (l.transition) {
            SlashTransition.FADE -> {
                drawLayer(live)
                if (t < 1f) {
                    old.alpha = 1f - t
                    drawLayer(old)
                    old.alpha = 1f
                }
            }
            SlashTransition.WIPE -> {
                // A skewed band sweeps across: the new list behind it, the old one still ahead of it.
                val band = sm.s * 0.22f
                val slant = size.height * (if (sm.skew == 0f) -0.2f else sm.skew.coerceIn(-0.5f, 0.5f))
                val travel = size.width + band + abs(slant) * 2
                val lead = -band - abs(slant) + travel * t
                val behind = WipePaths[0]
                val ahead = WipePaths[1]
                val edge = WipePaths[2]
                ahead(behind, lead, slant, tr.direction)
                ahead(ahead, lead + band, slant, tr.direction)
                ahead(edge, lead + band * 0.86f, slant, tr.direction)
                clipPath(ahead, androidx.compose.ui.graphics.ClipOp.Difference) { drawLayer(live) }
                clipPath(ahead) { drawLayer(old) }
                val a = 1f - t * t
                clipPath(behind) { clipPath(ahead, androidx.compose.ui.graphics.ClipOp.Difference) { drawRect(front, alpha = a) } }
                clipPath(edge) { clipPath(ahead, androidx.compose.ui.graphics.ClipOp.Difference) { drawRect(back, alpha = a) } }
            }
            SlashTransition.SHARDS -> {
                drawLayer(live)
                if (t < 1f) {
                    val shards = ShardCache.get(l.seed, size)
                    val path = ShardPath
                    shards.forEachIndexed { i, q ->
                        val cx = (q[0] + q[2] + q[4] + q[6]) / 4
                        val dir = if (cx < size.width / 2) -1f else 1f
                        val dx = dir * t * size.width * (0.35f + 0.1f * i)
                        val dy = (SlashGeometry.hash(l.seed, i, 30) - 0.3f) * t * size.height * 0.5f
                        val rot = dir * t * (8f + 6f * SlashGeometry.hash(l.seed, i, 31))
                        path.rewind()
                        path.moveTo(q[0], q[1]); path.lineTo(q[2], q[3]); path.lineTo(q[4], q[5]); path.lineTo(q[6], q[7]); path.close()
                        withTransform({ translate(dx, dy); rotate(rot, Offset(cx, size.height / 2)) }) {
                            clipPath(path) {
                                old.alpha = 1f - t
                                drawLayer(old)
                                old.alpha = 1f
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Paths the transitions reuse (drawing happens on the main thread). */
private val WipePaths = Array(3) { Path() }
private val ShardPath = Path()

/**
 * Into [path]: the part of the screen still ahead of a sweeping edge at [x] (slanted by [slant]
 * over the height), for a sweep to the right ([direction] > 0) or, mirrored, to the left.
 */
private fun DrawScope.ahead(path: Path, x: Float, slant: Float, direction: Int) {
    val w = size.width
    val h = size.height
    path.rewind()
    if (direction > 0) {
        path.moveTo(x, 0f); path.lineTo(x - slant, h); path.lineTo(w * 2 + abs(slant), h); path.lineTo(w * 2 + abs(slant), 0f)
    } else {
        val rx = w - x
        path.moveTo(rx, 0f); path.lineTo(rx + slant, h); path.lineTo(-w - abs(slant), h); path.lineTo(-w - abs(slant), 0f)
    }
    path.close()
}

/** The shard polygons for the last screen size, kept until the size or seed changes. */
private object ShardCache {
    private var key = 0L
    private var seed = 0
    private var shards: List<FloatArray> = emptyList()

    fun get(seed: Int, size: Size): List<FloatArray> {
        val k = (size.width.toLong() shl 32) or size.height.toLong()
        if (k != key || seed != this.seed || shards.isEmpty()) {
            key = k
            this.seed = seed
            shards = SlashGeometry.shards(seed, size.width, size.height, 4)
        }
        return shards
    }
}

/**
 * A row's text (and, focused, the game's logo): bold display font, outlined, aligned inside the
 * row's padding. The logo replaces or leads the title per `focusedContent`; items without one
 * (folders, utilities, settings) always show their title.
 */
@Composable
private fun SlashRowContent(
    node: XmbNode,
    focused: Boolean,
    focus: () -> Float,
    sm: SlashMetrics,
    titleSp: TextUnit,
    focusedText: Color,
    rowText: Color,
    loadArt: suspend (String, List<ArtKind>, Int) -> ImageBitmap?,
) {
    val l = sm.l
    val density = LocalDensity.current
    val title = if (l.titleCase == SlashTitleCase.UPPER) node.title.uppercase() else node.title
    val uuid = when (val i = node.icon) {
        is NodeIcon.Art -> i.uuid
        is NodeIcon.Tile -> i.uuid.takeIf { i.scraped }
        else -> null
    }
    val wantsLogo = focused && l.focusedContent != SlashFocusedContent.TITLE && uuid != null
    val logo = rememberHeldLogo(if (wantsLogo) uuid else null, (sm.focusedH * 2).toInt(), loadArt)
    val align = when (l.textAlign) {
        SlashTextAlign.START -> Alignment.CenterStart
        SlashTextAlign.CENTER -> Alignment.Center
        SlashTextAlign.END -> Alignment.CenterEnd
    }
    val startPad = with(density) { sm.box.textStart.toDp() }
    val endPad = with(density) { sm.box.textEnd.toDp() }
    Box(Modifier.fillMaxSize().padding(start = startPad, end = endPad), contentAlignment = align) {
        val showLogo = logo != null && wantsLogo
        val logoOnly = showLogo && l.focusedContent == SlashFocusedContent.LOGO
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(with(density) { (sm.s * 0.03f).toDp() })) {
            if (showLogo) {
                val h = with(density) { (sm.focusedH * 0.78f).toDp() }
                val w = h * (logo!!.width.toFloat() / logo.height.coerceAtLeast(1)).coerceIn(0.6f, 3.2f)
                Image(logo, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(w, h).fadeAlpha(focus))
            }
            if (!logoOnly) {
                val size = titleSp * (if (focused) 1f + (l.focusedScale - 1f) * 0.6f * focus() else 1f).let { if (showLogo) minOf(it, 1.2f) else it }
                SlashText(title, size, if (focused) focusedText else rowText, l.outline, focused, sm.m.layout.textOverflow)
            }
        }
    }
}

/**
 * [uuid]'s logo (`WHEEL` art), held across focus moves: the last logo stays for a short while
 * when the focus moves on, so it never flashes out and in once per scroll step, and the title
 * shows if the new one doesn't arrive in time.
 */
@Composable
private fun rememberHeldLogo(uuid: String?, px: Int, loadArt: suspend (String, List<ArtKind>, Int) -> ImageBitmap?): ImageBitmap? {
    val held by produceState<ImageBitmap?>(null, uuid) {
        if (uuid == null) {
            value = null
            return@produceState
        }
        val clear = launch {
            kotlinx.coroutines.delay(260)
            value = null
        }
        val logo = loadArt(uuid, listOf(ArtKind.WHEEL), px)
        clear.cancel()
        value = logo
    }
    return held
}

/** Bold, slanted-friendly text with an outline [outlineDp] wide behind it (0: a drop shadow only). */
@Composable
private fun SlashText(text: String, size: TextUnit, color: Color, outlineDp: Float, marquee: Boolean, overflow: TextOverflowMode) {
    val family = LocalThemeTypography.current.displayOrBody
    val base = TextStyle(fontSize = size, fontWeight = FontWeight.Black, fontFamily = family)
    val outlineColor = if (color.luminance() > 0.5f) Color.Black else Color.White.copy(alpha = 0.9f)
    val move = if (marquee && overflow == TextOverflowMode.MARQUEE) Modifier.basicMarquee(iterations = 3) else Modifier
    val cut = if (overflow == TextOverflowMode.MARQUEE && marquee) TextOverflow.Clip else TextOverflow.Ellipsis
    Box {
        if (outlineDp > 0f) {
            Text(
                text,
                style = base.copy(color = outlineColor, drawStyle = Stroke(width = with(LocalDensity.current) { outlineDp.dp.toPx() } * 2f, join = StrokeJoin.Round)),
                maxLines = 1, softWrap = false, overflow = cut, modifier = move,
            )
        }
        Text(
            text,
            style = base.copy(color = color, shadow = if (outlineDp > 0f) null else androidx.compose.ui.graphics.Shadow(Color(0x99000000), Offset(0f, 3f), 6f)),
            maxLines = 1, softWrap = false, overflow = cut, modifier = move,
        )
    }
}

/** The opened folder's title, a large slanted header above the list while drilled in. */
@Composable
private fun SlashHeader(title: String, sm: SlashMetrics, shown: () -> Float, front: Color, text: Color) {
    val density = LocalDensity.current
    val images = LocalMenuImages.current
    val paint = LocalMenuPaint.current
    val skin = sm.l.focusedSkin
    val image = images[skin?.path]
    val dpx = density.density * sm.m.layout.uiScale
    val pieces = remember(sm, image) { HashMap<Long, List<NineSlice.Patch>>() }
    val w = sm.box.width * 0.8f
    val h = sm.headerH
    Box(
        Modifier
            .offset { IntOffset((sm.box.left + sm.s * 0.02f - (1f - shown()) * sm.s * 0.3f).roundToInt(), (sm.listTop - sm.s * 0.01f).roundToInt()) }
            .size(with(density) { w.toDp() }, with(density) { h.toDp() })
            .graphicsLayer { rotationZ = -2f }
            .fadeAlpha(shown)
            .drawWithContent {
                withTransform({ transform(skewAbout(sm.skew * 1.3f, size.height / 2)) }) {
                    if (skin != null && image != null) {
                        val k = (size.width.toLong() shl 32) or size.height.toLong()
                        drawSkinAt(image, skin, size, dpx, paint.color(skin.tint), 1f, pieces.getOrPut(k) { skinPieces(image, skin, size.width, size.height, dpx) })
                    } else {
                        drawRect(front)
                    }
                    this@drawWithContent.drawContent()
                }
            }
            .padding(start = with(density) { sm.pad.toDp() }),
        contentAlignment = Alignment.CenterStart,
    ) {
        SlashText(if (sm.l.titleCase == SlashTitleCase.UPPER) title.uppercase() else title, with(density) { (sm.titleSize * 1.15f).toSp() }, text, sm.l.outline, false, TextOverflowMode.ELLIPSIS)
    }
}

/**
 * The categories (slice 4): the icon bar with the focused one on its decal and its name under it
 * ([SlashCategories.ICONS]), or a slanted strip of names or glyphs. Left and right still switch.
 */
@Composable
private fun SlashCategoryBar(
    categories: List<XmbCategory>,
    index: Int,
    sm: SlashMetrics,
    catPos: () -> Float,
    still: Boolean,
    drilled: Boolean,
    front: Color,
    onEvent: (XmbEvent) -> Unit,
) {
    val l = sm.l
    val density = LocalDensity.current
    val dim by animateFloatAsState(if (drilled) 0.55f else 1f, label = "slashCategoriesDim")
    when (l.categories) {
        SlashCategories.ICONS -> {
            // The bar scrolls only when it doesn't fit, keeping the focused icon a cell from the end.
            val fits = ((sm.width * 0.9f - sm.categoryLeft) / sm.iconPitch).toInt().coerceAtLeast(2)
            val shift = { (catPos() - (fits - 2)).coerceIn(0f, (categories.size - fits).coerceAtLeast(0).toFloat()) }
            l.categoryDecal?.let { SlashDecalView(it, sm, { sm.categoryLeft + (catPos() - shift()) * sm.iconPitch }, still, index) }
            val iconDp = with(density) { sm.iconSize.toDp() }
            categories.forEachIndexed { i, c ->
                val focus = { (1f - abs(i - catPos())).coerceIn(0f, 1f) }
                Box(
                    Modifier
                        .offset {
                            val x = sm.categoryLeft + (i - shift()) * sm.iconPitch - sm.iconSize / 2
                            IntOffset(x.roundToInt(), (sm.categoryY - sm.iconSize / 2).roundToInt())
                        }
                        .size(iconDp)
                        .graphicsLayer {
                            val s = 1f + 0.35f * focus()
                            scaleX = s
                            scaleY = s
                            rotationZ = l.skewDeg * 0.25f * (1f - focus())
                        }
                        .fadeAlpha { (0.55f + 0.45f * focus()) * dim }
                        .clickable(remember { MutableInteractionSource() }, indication = null) { onEvent(XmbEvent.Menu.CategoryTap(i)) },
                ) {
                    Image(glyphPainter(c.icon), contentDescription = c.title, colorFilter = ColorFilter.tint(Color.White), modifier = Modifier.fillMaxSize())
                }
            }
            categories.getOrNull(index)?.let { c ->
                Box(
                    Modifier
                        .offset {
                            val x = sm.categoryLeft + (catPos() - shift()) * sm.iconPitch - sm.iconSize
                            IntOffset(x.roundToInt(), (sm.categoryY + sm.iconSize * 0.78f).roundToInt())
                        }
                        .graphicsLayer { rotationZ = l.skewDeg * 0.35f }
                        .fadeAlpha { dim },
                ) {
                    SlashText(
                        if (l.titleCase == SlashTitleCase.UPPER) c.title.uppercase() else c.title,
                        with(density) { (sm.s * 0.036f).toSp() }, Color.White, l.outline.coerceAtMost(2f), false, TextOverflowMode.ELLIPSIS,
                    )
                }
            }
        }
        SlashCategories.LABELS, SlashCategories.GLYPHS -> {
            val labels = l.categories == SlashCategories.LABELS
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(with(density) { (sm.s * 0.04f).toDp() }),
                modifier = Modifier
                    .offset { IntOffset((sm.width * l.listX).roundToInt(), (sm.categoryY - sm.iconSize / 2).roundToInt()) }
                    .graphicsLayer { rotationZ = l.skewDeg * 0.2f }
                    .fadeAlpha { dim }
                    .drawWithContent { withTransform({ transform(skewAbout(sm.skew, size.height / 2)) }) { this@drawWithContent.drawContent() } },
            ) {
                categories.forEachIndexed { i, c ->
                    val focus = { (1f - abs(i - catPos())).coerceIn(0f, 1f) }
                    val mod = Modifier
                        .graphicsLayer {
                            val s = 1f + 0.25f * focus()
                            scaleX = s
                            scaleY = s
                        }
                        .fadeAlpha { 0.45f + 0.55f * focus() }
                        .clickable(remember { MutableInteractionSource() }, indication = null) { onEvent(XmbEvent.Menu.CategoryTap(i)) }
                    if (labels) {
                        Box(mod) {
                            SlashText(
                                if (l.titleCase == SlashTitleCase.UPPER) c.title.uppercase() else c.title,
                                with(density) { (sm.s * 0.042f).toSp() }, if (i == index) front else Color.White, l.outline.coerceAtMost(2f), false, TextOverflowMode.ELLIPSIS,
                            )
                        }
                    } else {
                        Image(
                            glyphPainter(c.icon), contentDescription = c.title,
                            colorFilter = ColorFilter.tint(if (i == index) front else Color.White),
                            modifier = mod.size(with(density) { (sm.iconSize * 0.8f).toDp() }),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The focused category's decal: [decal]'s skin [SlashDecal.size] times the icon, centred at
 * [centerX] (it follows the nav spring), playing its sprite frames unless [still] or resting.
 */
@Composable
private fun SlashDecalView(decal: SlashDecal, sm: SlashMetrics, centerX: () -> Float, still: Boolean, categoryIndex: Int) {
    val image = LocalMenuImages.current[decal.skin.path] ?: return
    val paint = LocalMenuPaint.current
    val tint = paint.color(decal.skin.tint)
    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(decal, still, categoryIndex) {
        frame = 0
        if (!decal.animated || still) return@LaunchedEffect
        val start = withFrameMillis { it }
        while (true) {
            withFrameMillis { now -> frame = decal.frameAt(now - start, false) }
            kotlinx.coroutines.delay((1000f / decal.fps).toLong().coerceAtLeast(16))
        }
    }
    val d = sm.iconSize * decal.size
    val dpx = LocalDensity.current.density * sm.m.layout.uiScale
    Box(
        Modifier
            .offset { IntOffset((centerX() - d / 2).roundToInt(), (sm.categoryY - d / 2).roundToInt()) }
            .size(with(LocalDensity.current) { d.toDp() })
            .drawBehind {
                val r = decal.frameRect(frame, image.width, image.height)
                val o = decal.skin.outset
                val ox = o.left * dpx
                val oy = o.top * dpx
                drawImage(
                    image,
                    srcOffset = IntOffset(r[0], r[1]),
                    srcSize = IntSize(r[2], r[3]),
                    dstOffset = IntOffset((-ox).roundToInt(), (-oy).roundToInt()),
                    dstSize = IntSize((size.width + ox + o.right * dpx).roundToInt(), (size.height + oy + o.bottom * dpx).roundToInt()),
                    colorFilter = tint?.let { ColorFilter.tint(it, BlendMode.Modulate) },
                )
            },
    )
}

/**
 * The focused item's art in a tilted, framed box (slice 4, `slash.art`): the game's `HERO` art
 * (else its logo) or its box art, crossfading as the focus moves; the frame skin drawn over the
 * edge with its outset. Items without art leave the box empty.
 */
@Composable
private fun SlashArtPanel(node: XmbNode?, sm: SlashMetrics, instant: Boolean, loadArt: suspend (String, List<ArtKind>, Int) -> ImageBitmap?) {
    val l = sm.l
    val r = l.artRect
    val x = sm.width * r[0]
    val y = sm.height * r[1]
    val w = sm.width * r[2]
    val h = sm.height * r[3]
    val target = when (val i = node?.icon) {
        is NodeIcon.Art -> i.uuid to (if (l.art == SlashArt.HERO) listOf(ArtKind.HERO, ArtKind.WHEEL) else i.kinds)
        is NodeIcon.Tile -> i.uuid.takeIf { i.scraped }?.let { it to (if (l.art == SlashArt.HERO) listOf(ArtKind.HERO, ArtKind.WHEEL) else listOf(ArtKind.BOX_2D, ArtKind.GRID)) }
        else -> null
    }
    val px = maxOf(w, h).toInt().coerceAtMost(1600)
    val art by produceState<ImageBitmap?>(null, target?.first) {
        value = target?.let { (uuid, kinds) -> loadArt(uuid, kinds, px) }
    }
    val shown by animateFloatAsState(if (art != null) 1f else 0f, if (instant) androidx.compose.animation.core.snap() else tween(240), label = "slashArt")
    var last by remember { mutableStateOf<ImageBitmap?>(null) }
    art?.let { last = it }
    val image = last
    val frameSkin = l.artFrame
    val frame = LocalMenuImages.current[frameSkin?.path]
    val paint = LocalMenuPaint.current
    val dpx = LocalDensity.current.density * sm.m.layout.uiScale
    val pieces = remember(sm, frame) { HashMap<Long, List<NineSlice.Patch>>() }
    // No art for this item: the frame goes with it rather than standing empty.
    if (image == null) return
    Box(
        Modifier
            .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
            .size(with(LocalDensity.current) { w.toDp() }, with(LocalDensity.current) { h.toDp() })
            .graphicsLayer { rotationZ = l.artTilt }
            .drawBehind {
                if (image != null && shown > 0f) {
                    // Cropped to fill the box.
                    val k = maxOf(size.width / image.width, size.height / image.height)
                    val sw = (size.width / k).roundToInt().coerceAtMost(image.width)
                    val sh = (size.height / k).roundToInt().coerceAtMost(image.height)
                    drawImage(
                        image,
                        srcOffset = IntOffset((image.width - sw) / 2, (image.height - sh) / 2),
                        srcSize = IntSize(sw, sh),
                        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                        alpha = shown,
                    )
                }
                if (frameSkin != null && frame != null && shown > 0f) {
                    val key = (size.width.toLong() shl 32) or size.height.toLong()
                    drawSkinAt(frame, frameSkin, size, dpx, paint.color(frameSkin.tint), shown, pieces.getOrPut(key) { skinPieces(frame, frameSkin, size.width, size.height, dpx) })
                }
            },
    )
}
