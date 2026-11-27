package dev.xross.launcher.ui

import android.content.res.AssetManager
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.xross.launcher.theme.pack.MenuImagePlan
import dev.xross.launcher.theme.shader.ImageSize
import dev.xross.launcher.theme.shader.ShaderImageSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * The images a pack names for its menu style (Menu styles slice 1): skins, decals, frames and
 * plates, decoded once per look. A style reads them through [LocalMenuImages]; an image that is
 * missing, unreadable or over the budget ([MenuImagePlan]) is simply absent, and the style draws
 * its flat fallback.
 */
@Immutable
class MenuImages(private val images: Map<String, ImageBitmap>, val problems: List<String>) {
    operator fun get(path: String?): ImageBitmap? = path?.let(images::get)

    val isEmpty: Boolean get() = images.isEmpty()

    companion object {
        val EMPTY = MenuImages(emptyMap(), emptyList())
    }
}

/** The active look's menu images; [MenuImages.EMPTY] until they are decoded. */
val LocalMenuImages = staticCompositionLocalOf { MenuImages.EMPTY }

/** One image to load: the pack path it is named by, where it was found (null: missing), and whether it is a sprite sheet. */
@Immutable
data class MenuImageRequest(val path: String, val source: ShaderImageSource?, val sheet: Boolean = false)

object MenuImageLoader {
    private const val TAG = "XrossMenuImages"

    /** Blocking: [requests] decoded in order within [MenuImagePlan]'s caps and budget. */
    fun load(requests: List<MenuImageRequest>, assets: AssetManager?): MenuImages {
        if (requests.isEmpty()) return MenuImages.EMPTY
        val problems = ArrayList<String>()
        val wanted = requests.distinctBy { it.path }
        val sizes = wanted.map { r ->
            val src = r.source ?: return@map null.also { problems += "${r.path} not found" }
            runCatching { bounds(src, assets) }.getOrNull().also { if (it == null) problems += "${r.path} can't be read as an image" }
        }
        val plan = MenuImagePlan.fit(sizes, wanted.indices.filter { wanted[it].sheet }.toSet())
        val out = LinkedHashMap<String, ImageBitmap>()
        wanted.forEachIndexed { i, r ->
            if (sizes[i] == null) return@forEachIndexed
            val target = plan[i] ?: return@forEachIndexed run {
                problems += "${r.path} is over the ${MenuImagePlan.BUDGET_PIXELS / (1024 * 1024)} MP budget for menu images; left out"
            }
            runCatching { decode(r.source!!, target, assets) }
                .onFailure { Log.w(TAG, "can't decode ${r.path}: ${it.message}") }
                .getOrNull()
                ?.let { out[r.path] = it }
                ?: run { problems += "${r.path} can't be decoded" }
        }
        return MenuImages(out, problems)
    }

    private fun open(src: ShaderImageSource, assets: AssetManager?): InputStream = src.file?.inputStream()
        ?: src.asset?.let { a -> assets?.open(a) }
        ?: error("no source")

    private fun bounds(src: ShaderImageSource, assets: AssetManager?): ImageSize? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(src, assets).use { BitmapFactory.decodeStream(it, null, opts) }
        return if (opts.outWidth > 0 && opts.outHeight > 0) ImageSize(opts.outWidth, opts.outHeight) else null
    }

    private fun decode(src: ShaderImageSource, target: ImageSize, assets: AssetManager?): ImageBitmap {
        val source = src.file?.let { ImageDecoder.createSource(it) }
            ?: src.asset?.let { a -> ImageDecoder.createSource(assets ?: error("no assets"), a) }
            ?: error("no source")
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            if (info.size.width != target.width || info.size.height != target.height) decoder.setTargetSize(target.width, target.height)
        }.asImageBitmap()
    }
}

/** [requests] decoded off the main thread; [MenuImages.EMPTY] meanwhile and for none. */
@Composable
fun rememberMenuImages(requests: List<MenuImageRequest>, assets: AssetManager?): State<MenuImages> =
    produceState(MenuImages.EMPTY, requests) {
        value = if (requests.isEmpty()) MenuImages.EMPTY else withContext(Dispatchers.IO) { MenuImageLoader.load(requests, assets) }
    }

/**
 * Draws [image] as [skin] over this scope's whole size ([NineSlice]), widened by the skin's outset.
 * [density] turns slice pixels and outset dp into screen pixels (the density times `uiScale`).
 * [patches] can be cached per size by the caller (`drawWithCache`); computed here when null.
 */
fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSkin(
    image: ImageBitmap,
    skin: dev.xross.launcher.theme.pack.Skin,
    density: Float,
    tint: androidx.compose.ui.graphics.Color? = null,
    alpha: Float = 1f,
    patches: List<dev.xross.launcher.theme.pack.NineSlice.Patch>? = null,
) {
    val o = skin.outset
    val ox = o.left * density
    val oy = o.top * density
    val w = size.width + ox + o.right * density
    val h = size.height + oy + o.bottom * density
    val pieces = patches ?: dev.xross.launcher.theme.pack.NineSlice.patches(image.width, image.height, skin.slice, w, h, density)
    val filter = tint?.let { androidx.compose.ui.graphics.ColorFilter.tint(it, androidx.compose.ui.graphics.BlendMode.Modulate) }
    for (p in pieces) {
        drawImage(
            image,
            srcOffset = androidx.compose.ui.unit.IntOffset(p.srcX, p.srcY),
            srcSize = androidx.compose.ui.unit.IntSize(p.srcW, p.srcH),
            dstOffset = androidx.compose.ui.unit.IntOffset(kotlin.math.round(p.dstX - ox).toInt(), kotlin.math.round(p.dstY - oy).toInt()),
            // Rounded from both edges, so neighbouring pieces meet without a seam.
            dstSize = androidx.compose.ui.unit.IntSize(
                (kotlin.math.round(p.dstX + p.dstW) - kotlin.math.round(p.dstX)).toInt(),
                (kotlin.math.round(p.dstY + p.dstH) - kotlin.math.round(p.dstY)).toInt(),
            ),
            alpha = alpha,
            colorFilter = filter,
        )
    }
}
