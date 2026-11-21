package dev.xross.launcher

import dev.xross.launcher.audio.BgmQueue
import dev.xross.launcher.config.ConfigCodec
import dev.xross.launcher.data.ThemeDefaults
import dev.xross.launcher.data.ThemeMusic
import dev.xross.launcher.data.XrossSettings
import dev.xross.launcher.theme.pack.BgmRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.random.Random

/** 0.2.2.0 § 5: theme music chosen in Settings, and lists that shuffle. */
@RunWith(RobolectricTestRunner::class)
class ThemeMusicTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun inOrderWraps() {
        val q = BgmQueue(3, shuffle = false)
        assertEquals(listOf(0, 1, 2, 0, 1), List(5) { q.next() })
    }

    @Test fun theShuffleBagPlaysEveryTrackBeforeRepeatingAny() {
        for (seed in 0 until 50) {
            val q = BgmQueue(7, shuffle = true, random = Random(seed))
            val rounds = List(5) { List(7) { q.next() } }
            for (round in rounds) assertEquals(seed.toString(), (0 until 7).toSet(), round.toSet())
            // A new round never starts with the track that just ended.
            for (i in 1 until rounds.size) assertNotEquals(seed.toString(), rounds[i - 1].last(), rounds[i].first())
        }
    }

    @Test fun theQueueKeepsItsPlace() {
        val q = BgmQueue(4, shuffle = false)
        q.next(); q.next()
        assertEquals(1, q.current)
        // Music comes back after a game: the list goes on with the next track.
        assertEquals(2, q.next())
        assertEquals(-1, BgmQueue(0, shuffle = true).next())
        assertEquals(0, BgmQueue(1, shuffle = true).next())
    }

    @Test fun settingsRoundTripAndClampOrder() {
        val s = XrossSettings(themeMusic = "/storage/emulated/0/Music/Loops", themeMusicOrder = ThemeMusic.IN_ORDER)
        val back = ConfigCodec.decodeSettings(XrossSettings(), ConfigCodec.encodeSettings(s))
        assertEquals(s.themeMusic, back.themeMusic)
        assertEquals(ThemeMusic.IN_ORDER, back.themeMusicOrder)
        val odd = ConfigCodec.encodeSettings(s).replace("\"IN_ORDER\"", "\"sideways\"")
        assertEquals(ThemeMusic.SHUFFLE, ConfigCodec.decodeSettings(XrossSettings(), odd).themeMusicOrder)
        // An old file without the keys: the theme pack's music, shuffled lists.
        val old = XrossSettings()
        assertEquals("", old.themeMusic)
        assertEquals(ThemeMusic.SHUFFLE, old.themeMusicOrder)
    }

    @Test fun resetToThemeDefaultsClearsTheChoice() {
        val s = XrossSettings(themeMusic = ThemeMusic.OFF)
        assertFalse(ThemeDefaults.isDefault(s))
        assertEquals("", ThemeDefaults.reset(s).themeMusic)
    }

    @Test fun aFolderIsAListOfItsAudioFiles() {
        val dir = tmp.newFolder("Loops")
        for (name in listOf("b.ogg", "a.mp3", "cover.jpg", ".hidden.ogg", "empty.ogg")) {
            File(dir, name).writeBytes(if (name == "empty.ogg") ByteArray(0) else ByteArray(10))
        }
        val choice = ThemeMusic.resolve(dir.path, ThemeMusic.IN_ORDER) as ThemeMusic.Choice.Own
        val track = choice.track!!
        assertEquals(listOf("a.mp3", "b.ogg"), track.files.map { it.name })
        assertFalse(track.shuffle)
        assertEquals(1, choice.skipped) // the empty file
        assertTrue(ThemeMusic.describe(dir.path, ThemeMusic.SHUFFLE, "Pack", true).contains("shuffled"))
    }

    @Test fun aFolderIsCappedAtFiftyTracks() {
        val dir = tmp.newFolder("Many")
        repeat(60) { File(dir, "t%02d.ogg".format(it)).writeBytes(ByteArray(4)) }
        val track = (ThemeMusic.resolve(dir.path, ThemeMusic.SHUFFLE) as ThemeMusic.Choice.Own).track!!
        assertEquals(BgmRef.MAX_TRACKS, track.files.size)
    }

    @Test fun aFileLoopsAndOffAndBlankMeanWhatTheySay() {
        val f = tmp.newFile("loop.ogg").apply { writeBytes(ByteArray(8)) }
        val one = (ThemeMusic.resolve(f.path, ThemeMusic.SHUFFLE) as ThemeMusic.Choice.Own).track!!
        assertFalse(one.isList)
        assertEquals(ThemeMusic.Choice.Off, ThemeMusic.resolve("NONE", ""))
        assertEquals(ThemeMusic.Choice.Theme, ThemeMusic.resolve(" ", ""))
        assertNull((ThemeMusic.resolve(tmp.newFile("notes.txt").path, "") as ThemeMusic.Choice.Own).track)
    }
}
