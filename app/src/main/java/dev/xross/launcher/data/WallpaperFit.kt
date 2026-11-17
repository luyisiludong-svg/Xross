package dev.xross.launcher.data

/**
 * How the Xross wallpaper (a picture the user chose) is placed on the screen. Theme packs'
 * own wallpapers always [FILL]: they are made for it.
 */
enum class WallpaperFit(val label: String, val detail: String) {
    FILL("Fill", "Covers the screen; edges may be cut off"),
    FIT("Fit", "The whole picture; the background shows around it"),
    STRETCH("Stretch", "Covers the screen; the picture is distorted to fit"),
    CENTER("Center", "Actual size, centered; shrinks if it's bigger than the screen"),
    ;

    /**
     * Where an [imageW]×[imageH] image lands, centered, in a [boxW]×[boxH] box:
     * (left, top, width, height), in box pixels.
     */
    fun place(imageW: Float, imageH: Float, boxW: Float, boxH: Float): FloatArray {
        if (imageW <= 0f || imageH <= 0f || this == STRETCH) return floatArrayOf(0f, 0f, boxW, boxH)
        val cover = maxOf(boxW / imageW, boxH / imageH)
        val contain = minOf(boxW / imageW, boxH / imageH)
        val scale = when (this) {
            FILL -> cover
            FIT -> contain
            CENTER -> minOf(1f, contain)
            STRETCH -> 1f
        }
        val w = imageW * scale
        val h = imageH * scale
        return floatArrayOf((boxW - w) / 2, (boxH - h) / 2, w, h)
    }

    /** The placed image covers the whole box (nothing behind it shows). Half a pixel of slack. */
    fun covers(imageW: Float, imageH: Float, boxW: Float, boxH: Float): Boolean {
        val r = place(imageW, imageH, boxW, boxH)
        return r[0] <= 0.5f && r[1] <= 0.5f && r[0] + r[2] >= boxW - 0.5f && r[1] + r[3] >= boxH - 0.5f
    }

    companion object {
        fun parse(raw: String?) = entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: FILL
    }
}
