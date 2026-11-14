package dev.xross.launcher.model

/**
 * Icon roles; each maps to an `xmb_*` vector drawable generated from assets/icons. Read by the
 * menu tree, the theme pack's icon overrides and the UI alike, so it sits below all three.
 */
enum class GlyphKey {
    SETTINGS, GAMES, APPS, PHOTO, MUSIC, VIDEO, ALBUM, ARTIST, SHUFFLE, SEARCH,
    PLATFORM, GAME_CART, GAME_DISC, FAVORITES, RECENT, HANDHELD, FOLDER, STORAGE,
    CONTROLS, EMULATORS, DISPLAY, SOUND, SYSTEM,
    ADD, REFRESH, HOME, INFO, DOWNLOAD, NETWORK, USERS, TROPHY,
}
