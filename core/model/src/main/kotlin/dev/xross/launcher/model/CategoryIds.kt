package dev.xross.launcher.model

/**
 * The ids of the top-level XMB categories, in menu order. The theme engine reads them to check a
 * pack's rules and variants; `state.Categories` adds the labels and icons on top.
 */
object CategoryIds {
    const val SETTINGS = "settings"
    const val PHOTO = "photo"
    const val MUSIC = "music"
    const val VIDEO = "video"
    const val GAMES = "games"
    const val APPS = "apps"

    val all: List<String> = listOf(SETTINGS, PHOTO, MUSIC, VIDEO, GAMES, APPS)
}
