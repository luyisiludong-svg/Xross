package dev.xross.launcher

import dev.xross.launcher.config.ConfigCodec
import dev.xross.launcher.data.ThemeDefaults
import dev.xross.launcher.data.XrossSettings
import dev.xross.launcher.state.RotationInterval
import dev.xross.launcher.state.WallpaperRotator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** 0.2.2.0 § 5b: a folder of wallpapers that take turns, without waking a resting screen. */
@RunWith(RobolectricTestRunner::class)
class WallpaperRotationTest {
    @get:Rule val tmp = TemporaryFolder()

    private val pictures = listOf("/w/a.png", "/w/b.jpg", "/w/c.webp")
    private val gone = HashSet<String>()
    private val rotator = WallpaperRotator { it !in gone }
    private val minute = 60_000L

    @Test fun itChangesOnlyOnInputOnceTheIntervalIsUp() {
        rotator.configure(pictures, RotationInterval.MIN_5, now = 0)
        assertEquals("/w/a.png", rotator.current.value)
        rotator.onInput(4 * minute)
        assertEquals("/w/a.png", rotator.current.value)
        // Ten minutes of rest change nothing by themselves: there is no timer. The next input does.
        rotator.onInput(10 * minute)
        assertEquals("/w/b.jpg", rotator.current.value)
        rotator.onInput(11 * minute)
        assertEquals("/w/b.jpg", rotator.current.value)
    }

    @Test fun eachTimeXrossComesToTheFront() {
        rotator.configure(pictures, RotationInterval.FRONT, now = 0)
        rotator.onInput(100 * minute)
        assertEquals("/w/a.png", rotator.current.value)
        rotator.onFront(100 * minute)
        assertEquals("/w/b.jpg", rotator.current.value)
        rotator.onFront(100 * minute)
        rotator.onFront(100 * minute)
        assertEquals("/w/a.png", rotator.current.value) // wrapped, in name order
    }

    @Test fun offShowsTheFirstPictureAndStays() {
        rotator.configure(pictures, RotationInterval.OFF, now = 0)
        rotator.onFront(1000 * minute)
        rotator.onInput(1000 * minute)
        assertEquals("/w/a.png", rotator.current.value)
    }

    @Test fun aDeletedPictureIsSkipped() {
        rotator.configure(pictures, RotationInterval.MIN_1, now = 0)
        gone += "/w/b.jpg"
        rotator.onInput(2 * minute)
        assertEquals("/w/c.webp", rotator.current.value)
        gone += pictures
        rotator.onInput(4 * minute)
        assertEquals("/w/c.webp", rotator.current.value) // nothing left to move to: it stays
    }

    @Test fun aNewListingKeepsThePlaceAndAnEmptyOneClears() {
        rotator.configure(pictures, RotationInterval.FRONT, now = 0)
        rotator.onFront(0)
        rotator.configure(listOf("/w/0.png") + pictures, RotationInterval.FRONT, now = 0)
        assertEquals("/w/b.jpg", rotator.current.value)
        rotator.configure(emptyList(), RotationInterval.FRONT, now = 0)
        assertNull(rotator.current.value)
    }

    @Test fun theFolderIsListedByNameWithPicturesOnly() {
        val dir = tmp.newFolder("walls")
        for (n in listOf("b.JPG", "a.png", "notes.txt", ".hidden.png", "c.webp")) File(dir, n).writeBytes(ByteArray(1))
        assertEquals(listOf("a.png", "b.JPG", "c.webp"), WallpaperRotator.list(dir.path).map { File(it).name })
    }

    @Test fun settingsRoundTripAndReset() {
        val s = XrossSettings(wallpaperFolder = "/storage/emulated/0/Pictures/Walls", wallpaperInterval = "MIN_15")
        val back = ConfigCodec.decodeSettings(XrossSettings(), ConfigCodec.encodeSettings(s))
        assertEquals(s.wallpaperFolder, back.wallpaperFolder)
        assertEquals("MIN_15", back.wallpaperInterval)
        val bad = ConfigCodec.encodeSettings(s).replace("\"MIN_15\"", "\"hourly\"")
        assertEquals("OFF", ConfigCodec.decodeSettings(XrossSettings(), bad).wallpaperInterval)
        assertEquals("", ThemeDefaults.reset(s).wallpaperFolder)
    }
}
