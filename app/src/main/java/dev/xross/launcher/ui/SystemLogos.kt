package dev.xross.launcher.ui

import android.content.res.AssetManager
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import dev.xross.launcher.data.SystemLogoStyle
import dev.xross.launcher.theme.pack.BitmapIcon
import dev.xross.launcher.theme.pack.VectorIcon
import java.util.concurrent.ConcurrentHashMap

/**
 * The bundled console logos (`assets/system_logos/<style>/<platform id>.webp`), decoded once
 * and kept: 23 small bitmaps per style.
 */
object SystemLogos {
    const val DIR = "system_logos"

    private val cache = ConcurrentHashMap<String, Any>()
    private val NONE = Any()

    fun path(style: SystemLogoStyle, platformId: String): String? = style.folder?.let { "$DIR/$it/$platformId.webp" }

    fun load(assets: AssetManager, style: SystemLogoStyle, platformId: String): ImageBitmap? {
        val path = path(style, platformId) ?: return null
        val hit = cache.getOrPut(path) {
            runCatching { assets.open(path).use { BitmapFactory.decodeStream(it) } }.getOrNull()?.asImageBitmap() ?: NONE
        }
        return hit as? ImageBitmap
    }
}

/** A system folder's logo and whether it is drawn in its own colors (no XMB gloss). */
class SystemLogo(val painter: Painter, val keepsColors: Boolean)

/**
 * The console logo for [platformId] in [style]: the active pack's (`icons/systems/`, 0.2.1.0) for
 * [SystemLogoStyle.PACK], falling back to the bundled White set, or the chosen bundled set. Null when
 * there is none, so the folder shows its glyph.
 */
@Composable
fun systemLogo(platformId: String, style: SystemLogoStyle): SystemLogo? {
    if (style == SystemLogoStyle.PACK) {
        when (val icon = LocalThemePack.current?.systemIcons?.get(platformId)) {
            is VectorIcon -> return SystemLogo(rememberVectorPainter(icon.image), keepsColors = false)
            is BitmapIcon -> return remember(icon) { SystemLogo(BitmapPainter(icon.image), keepsColors = false) }
            else -> Unit
        }
    }
    val bundled = if (style == SystemLogoStyle.PACK) SystemLogoStyle.PACK_FALLBACK else style
    val assets = LocalContext.current.assets
    return remember(platformId, bundled) {
        SystemLogos.load(assets, bundled, platformId)?.let { SystemLogo(BitmapPainter(it), bundled.keepsColors) }
    }
}
