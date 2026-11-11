package dev.xross.launcher

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.xross.launcher.storage.XrossEnvironment
import dev.xross.launcher.storage.XrossLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class XrossEnvironmentTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule val tmp = TemporaryFolder()

    private fun primary() = File(tmp.root, "emulated/0/Xross")

    private fun env(granted: () -> Boolean = { true }) =
        XrossEnvironment(context, writeAccess = { granted() }, primaryRoot = ::primary)

    @Test fun fallsBackToAppStorageWithoutWriteAccess() {
        val e = env(granted = { false })
        assertFalse(e.current.portable)
        assertEquals(File(context.filesDir, "Xross"), e.current.root)
        assertBootstrapped(e.current.root)
    }

    @Test fun usesThePrimaryXrossFolderOnceWritable() {
        val e = env()
        assertTrue(e.current.portable)
        assertEquals(primary(), e.current.root)
        assertBootstrapped(primary())
        assertEquals(File(primary(), "config/settings.json"), e.current.settingsFile)
        assertEquals(File(primary(), "config/controllers.json"), e.current.controllersFile)
        assertEquals(File(primary(), "config/systems.json"), e.current.systemsFile)
        assertEquals(File(primary(), "data/xross.db"), e.current.databaseBackup)
    }

    @Test fun nomediaMarkersGuardMediaAndThemesOnly() {
        val root = File(tmp.root, "Xross")
        assertTrue(XrossEnvironment.bootstrap(root))
        assertTrue(File(root, "media/.nomedia").isFile)
        assertTrue(File(root, "themes/.nomedia").isFile)
        for (dir in listOf("config", "data", "dats", "roms")) assertFalse(File(root, "$dir/.nomedia").exists())
        // Bootstrapping again is harmless and keeps the markers.
        assertTrue(XrossEnvironment.bootstrap(root))
        assertTrue(File(root, "media/.nomedia").isFile)
    }

    @Test fun nomediaIsInPlaceBeforeTheMediaFolderIsUsable() {
        // A media/ folder made earlier without a marker gets one on the next bootstrap.
        val root = File(tmp.root, "Xross")
        File(root, "media/covers/snes").mkdirs()
        assertTrue(XrossEnvironment.bootstrap(root))
        assertTrue(File(root, "media/.nomedia").isFile)
    }

    @Test fun unwritableRootFailsBootstrap() {
        val blocked = File(tmp.root, "blocked")
        blocked.writeText("a file where the folder should be")
        assertFalse(XrossEnvironment.bootstrap(blocked))
    }

    @Test fun grantingAccessMovesTheRootOnRefresh() {
        var granted = false
        val e = env(granted = { granted })
        assertFalse(e.current.portable)
        assertFalse(e.refresh())
        granted = true
        assertTrue(e.refresh())
        assertEquals(XrossLayout(primary(), portable = true), e.current)
        assertEquals(e.current, e.layout.value)
    }

    @Test fun chosenVolumeIsUsedAndRemembered() {
        val sd = File(tmp.root, "1A2B-3C4D").apply { mkdirs() }
        val e = env()
        assertTrue(e.chooseVolume(sd))
        assertEquals(File(sd, "Xross"), e.current.root)
        assertBootstrapped(File(sd, "Xross"))
        // Another instance (next app start) picks the same volume.
        assertEquals(File(sd, "Xross"), env().current.root)
        assertEquals(sd, env().chosenVolume)
        assertTrue(e.chooseVolume(null))
        assertEquals(primary(), e.current.root)
        assertNull(e.chosenVolume)
    }

    @Test fun unwritableChosenVolumeFallsBackToPrimary() {
        val card = File(tmp.root, "card").apply { writeText("not a folder") }
        val e = env()
        e.chooseVolume(card)
        assertEquals(primary(), e.current.root)
        assertTrue(e.current.portable)
    }

    private fun assertBootstrapped(root: File) {
        for (dir in listOf("config", "data", "dats", "media", "roms", "themes")) {
            assertTrue("$dir/ exists", File(root, dir).isDirectory)
        }
        assertTrue(File(root, "media/.nomedia").isFile)
        assertTrue(File(root, "themes/.nomedia").isFile)
    }
}
