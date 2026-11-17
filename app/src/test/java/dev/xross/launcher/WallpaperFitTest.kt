package dev.xross.launcher

import dev.xross.launcher.config.ConfigCodec
import dev.xross.launcher.data.WallpaperFit
import dev.xross.launcher.data.XrossSettings
import dev.xross.launcher.state.Command
import dev.xross.launcher.state.MediaMenus
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WallpaperFitTest {
    // A 4:3 photo on a 20:9 (2400×1080) screen.
    private fun place(fit: WallpaperFit, w: Float = 4000f, h: Float = 3000f) = fit.place(w, h, 2400f, 1080f)

    @Test fun eachFitPlacesThePicture() {
        // Fill: as wide as the screen, top and bottom cut off.
        assertArrayEquals(floatArrayOf(0f, -360f, 2400f, 1800f), place(WallpaperFit.FILL), 0.01f)
        // Fit: as tall as the screen, bars left and right.
        assertArrayEquals(floatArrayOf(480f, 0f, 1440f, 1080f), place(WallpaperFit.FIT), 0.01f)
        assertArrayEquals(floatArrayOf(0f, 0f, 2400f, 1080f), place(WallpaperFit.STRETCH), 0.01f)
        // Center: a small picture keeps its size; a big one shrinks like Fit.
        assertArrayEquals(floatArrayOf(900f, 290f, 600f, 500f), place(WallpaperFit.CENTER, 600f, 500f), 0.01f)
        assertArrayEquals(place(WallpaperFit.FIT), place(WallpaperFit.CENTER), 0.01f)
    }

    @Test fun onlyFitsWithoutBarsCoverTheScreen() {
        assertTrue(WallpaperFit.FILL.covers(4000f, 3000f, 2400f, 1080f))
        assertTrue(WallpaperFit.STRETCH.covers(4000f, 3000f, 2400f, 1080f))
        assertFalse(WallpaperFit.FIT.covers(4000f, 3000f, 2400f, 1080f))
        // Same shape as the screen: Fit covers it too.
        assertTrue(WallpaperFit.FIT.covers(2400f, 1080f, 2400f, 1080f))
        assertFalse(WallpaperFit.CENTER.covers(600f, 500f, 2400f, 1080f))
    }

    @Test fun fitIsSavedInSettingsJsonAndOfferedInOptions() {
        val s = XrossSettings(wallpaperFit = WallpaperFit.STRETCH)
        assertEquals(WallpaperFit.STRETCH, ConfigCodec.decodeSettings(XrossSettings(), ConfigCodec.encodeSettings(s)).wallpaperFit)
        assertEquals(WallpaperFit.FILL, WallpaperFit.parse("nonsense"))
        val menu = MediaMenus.wallpaperFitMenu(WallpaperFit.FIT)
        assertEquals("Fit", menu.detail)
        assertEquals(WallpaperFit.entries.map { Command.SetWallpaperFit(it) }, menu.submenu.map { it.command })
        assertEquals("Fit", menu.submenu.single { it.selected }.label)
    }
}
