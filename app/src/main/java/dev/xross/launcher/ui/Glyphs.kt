package dev.xross.launcher.ui

import androidx.annotation.DrawableRes
import dev.xross.launcher.R
import dev.xross.launcher.state.GlyphKey

/** Generated from assets/icons by tools/icons/svg_icons.py (see tools/icons/icon_map.txt). */
@DrawableRes
fun GlyphKey.drawable(): Int = when (this) {
    GlyphKey.SETTINGS -> R.drawable.xmb_settings
    GlyphKey.GAMES -> R.drawable.xmb_games
    GlyphKey.APPS -> R.drawable.xmb_apps
    GlyphKey.PHOTO -> R.drawable.xmb_photo
    GlyphKey.MUSIC -> R.drawable.xmb_music
    GlyphKey.VIDEO -> R.drawable.xmb_video
    GlyphKey.ALBUM -> R.drawable.xmb_game_disc
    GlyphKey.ARTIST -> R.drawable.xmb_users
    GlyphKey.SHUFFLE -> R.drawable.xmb_refresh
    GlyphKey.SEARCH -> R.drawable.xmb_search
    GlyphKey.PLATFORM -> R.drawable.xmb_platform
    GlyphKey.GAME_CART -> R.drawable.xmb_game_cart
    GlyphKey.GAME_DISC -> R.drawable.xmb_game_disc
    GlyphKey.FAVORITES -> R.drawable.xmb_favorites
    GlyphKey.RECENT -> R.drawable.xmb_recent
    GlyphKey.HANDHELD -> R.drawable.xmb_handheld
    GlyphKey.FOLDER -> R.drawable.xmb_folder
    GlyphKey.STORAGE -> R.drawable.xmb_storage
    GlyphKey.CONTROLS -> R.drawable.xmb_controls
    GlyphKey.EMULATORS -> R.drawable.xmb_emulators
    GlyphKey.DISPLAY -> R.drawable.xmb_display
    GlyphKey.SOUND -> R.drawable.xmb_sound
    GlyphKey.SYSTEM -> R.drawable.xmb_system
    GlyphKey.ADD -> R.drawable.xmb_add
    GlyphKey.REFRESH -> R.drawable.xmb_refresh
    GlyphKey.HOME -> R.drawable.xmb_home
    GlyphKey.INFO -> R.drawable.xmb_info
}
