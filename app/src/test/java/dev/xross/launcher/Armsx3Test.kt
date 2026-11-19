package dev.xross.launcher

import android.content.Intent
import android.net.Uri
import dev.xross.launcher.data.dat.DatNames
import dev.xross.launcher.launch.Armsx3
import dev.xross.launcher.launch.EmulatorRegistry
import dev.xross.launcher.launch.LaunchRequest
import dev.xross.launcher.model.PlatformRegistry
import dev.xross.launcher.profile.RaConsoles
import dev.xross.launcher.scrape.providers.ScreenScraperProvider
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

/** 0.2.1.0: the PS3 platform and ARMSX3's two launch forms (ES-DE's Android rules). */
@RunWith(RobolectricTestRunner::class)
class Armsx3Test {
    @get:Rule val tmp = TemporaryFolder()

    private val uri = Uri.parse("content://dev.xross.launcher.files/root/x")

    private fun build(file: File, pkg: String = "com.armsx3"): Intent =
        EmulatorRegistry.byId("armsx3")!!.buildIntent(LaunchRequest(file.path, uri, "application/octet-stream", pkg, null))

    @Test fun aDiscImageGoesAsAPathWithNoAction() {
        val iso = tmp.newFile("Demon's Souls.iso")
        for (pkg in listOf("com.armsx3", "com.armsx3.play")) {
            val i = build(iso, pkg)
            assertNull(i.action)
            assertEquals(pkg, i.component?.packageName)
            assertEquals("com.armsx2.Main", i.component?.className)
            assertEquals(iso.path, i.getStringExtra("path"))
            assertTrue(i.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
            // A plain path: no URI, no grant.
            assertNull(i.data)
        }
    }

    @Test fun aPs3FileOpensTheInstalledGameByItsSerial() {
        val ps3 = tmp.newFile("Journey.ps3").apply { writeText("﻿\n  NPUA80068  \nsecond line\n") }
        val i = build(ps3, "com.armsx3.play")
        assertEquals(Intent.ACTION_VIEW, i.action)
        assertEquals("/storage/emulated/0/Android/data/com.armsx3.play/files/config/dev_hdd0/game/NPUA80068", i.getStringExtra("path"))
        assertNull(EmulatorRegistry.byId("armsx3")!!.check(ps3))
    }

    @Test fun aPs3FileWithoutASerialIsRefusedBeforeTheLaunch() {
        val def = EmulatorRegistry.byId("armsx3")!!
        val empty = tmp.newFile("Empty.ps3")
        assertEquals("Empty.ps3 doesn't hold a PS3 serial (like BLUS30443)", def.check(empty))
        val junk = tmp.newFile("Junk.ps3").apply { writeText("hello world") }
        assertTrue(def.check(junk)!!.startsWith("Junk.ps3"))
        // Disc images aren't read.
        assertNull(def.check(tmp.newFile("Game.chd")))
        assertTrue(def.needsStorageAccess)
        assertFalse(EmulatorRegistry.byId("armsx2")!!.needsStorageAccess)
    }

    @Test fun serialIsTheFirstNonBlankLine() {
        assertEquals("BLUS30443", Armsx3.serial("\n\r\nBLUS30443\r\n"))
        assertNull(Armsx3.serial("  \n"))
        assertTrue(Armsx3.looksLikeSerial("BLES01807"))
        assertFalse(Armsx3.looksLikeSerial("BLES 01807"))
    }

    @Test fun ps3PlatformScansItsFoldersAndFileTypes() {
        val ps3 = PlatformRegistry.byId("ps3")!!
        assertEquals("PlayStation 3", ps3.name)
        assertEquals("PS3", ps3.shortName)
        for (folder in listOf("ps3", "PS3", "PlayStation 3", "sony-playstation3")) assertEquals(folder, "ps3", PlatformRegistry.forFolder(folder)?.id)
        assertEquals(setOf("chd", "iso", "ps3"), ps3.extensions)
        assertTrue(ps3.isDiscBased)
        assertEquals(listOf("armsx3"), ps3.emulators.map { it.key })
        // Right after PS2 in the Games list.
        val ids = PlatformRegistry.all.map { it.id }
        assertEquals(ids.indexOf("ps2") + 1, ids.indexOf("ps3"))
    }

    @Test fun scrapersKnowPs3AndRetroAchievementsDoesNot() {
        assertEquals(59, ScreenScraperProvider.SYSTEM_IDS["ps3"])
        assertNull(RaConsoles.of("ps3"))
        assertEquals("ps3", DatNames.platformFor("Sony - PlayStation 3"))
    }
}
