package dev.xross.launcher.model

/**
 * Kinds of downloaded art, each stored at most once per game. [folder] is the kind's folder in
 * `Xross/media/` (ES-DE's names where ES-DE has the kind); [fileStem] names it in the old private
 * cache, kept for the one-time migration.
 */
enum class ArtKind(val folder: String, val fileStem: String, val maxBytes: Long, val isVideo: Boolean = false) {
    BOX_3D("3dboxes", "box3d", 8L shl 20),
    BOX_2D("covers", "box2d", 8L shl 20),
    WHEEL("wheels", "wheel", 4L shl 20),
    SCREENSHOT("screenshots", "screenshot", 4L shl 20),
    /** Wide banner for the details screen background. */
    HERO("heroes", "hero", 8L shl 20),
    /** Portrait capsule (600×900). */
    GRID("capsules", "grid", 8L shl 20),
    /** Video snap. */
    VIDEO("snaps", "video", 40L shl 20, isVideo = true),
}
