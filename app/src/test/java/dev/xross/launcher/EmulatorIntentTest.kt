package dev.xross.launcher

import android.content.Intent
import android.net.Uri
import dev.xross.launcher.launch.EmulatorRegistry
import dev.xross.launcher.launch.LaunchRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Verifies each intent recipe matches the launch contract the emulator's activity reads. */
@RunWith(RobolectricTestRunner::class)
class EmulatorIntentTest {
    private val uri = Uri.parse("content://dev.xross.launcher.files/root/storage/emulated/0/ROMs/snes/Game.sfc")

    private fun request(pkg: String, core: String? = null) = LaunchRequest(
        romPath = "/storage/emulated/0/ROMs/snes/Game.sfc",
        romUri = uri,
        mimeType = "application/octet-stream",
        packageName = pkg,
        core = core,
    )

    private fun build(id: String, pkg: String, core: String? = null): Intent =
        EmulatorRegistry.byId(id)!!.buildIntent(request(pkg, core))

    @Test fun retroArchPassesRomCoreAndConfig() {
        val i = build("retroarch", "com.retroarch.aarch64", "snes9x")
        assertEquals("com.retroarch.aarch64", i.component?.packageName)
        assertEquals("com.retroarch.browser.retroactivity.RetroActivityFuture", i.component?.className)
        assertEquals("/storage/emulated/0/ROMs/snes/Game.sfc", i.getStringExtra("ROM"))
        assertEquals("/data/data/com.retroarch.aarch64/cores/snes9x_libretro_android.so", i.getStringExtra("LIBRETRO"))
        assertEquals("/storage/emulated/0/Android/data/com.retroarch.aarch64/files/retroarch.cfg", i.getStringExtra("CONFIGFILE"))
        assertTrue(i.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertEquals(uri, i.data)
        assertTrue(i.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test fun standaloneEmulatorsGetPathExtraAndGrantedUri() {
        val cases = listOf(
            Triple("duckstation", "com.github.stenzek.duckstation", "bootPath"),
            Triple("nethersx2", "xyz.aethersx2.android", "bootPath"),
            Triple("dolphin", "org.dolphinemu.dolphinemu", "AutoStartFile"),
        )
        for ((id, pkg, extra) in cases) {
            val i = build(id, pkg)
            assertEquals(id, pkg, i.component?.packageName)
            assertEquals(id, "/storage/emulated/0/ROMs/snes/Game.sfc", i.getStringExtra(extra))
            assertEquals(id, uri, i.data)
            assertTrue(id, i.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        }
    }

    @Test fun ppssppOpensTheRomAsViewData() {
        val i = build("ppsspp", "org.ppsspp.ppsspp")
        assertEquals(Intent.ACTION_VIEW, i.action)
        assertEquals("org.ppsspp.ppsspp.PpssppActivity", i.component?.className)
        assertEquals(uri, i.data)
        assertTrue(i.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    // Recipes below follow ES-DE's published Android rules (es_find_rules.xml / es_systems.xml).

    private fun assertGrantedData(id: String, i: Intent) {
        assertEquals(id, uri, i.data)
        assertTrue(id, i.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(id, i.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test fun azaharOpensTheRomUriInItsEmulationActivity() {
        for (pkg in listOf("org.azahar_emu.azahar", "io.github.lime3ds.android")) {
            val i = build("azahar", pkg)
            assertEquals(pkg, i.component?.packageName)
            assertEquals("org.citra.citra_emu.activities.EmulationActivity", i.component?.className)
            assertTrue(i.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK != 0)
            assertTrue(i.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
            assertGrantedData("azahar", i)
        }
    }

    @Test fun edenUsesTheYuzuFamilyNfcAction() {
        val i = build("eden", "dev.eden.eden_emulator")
        assertEquals("android.nfc.action.TECH_DISCOVERED", i.action)
        assertEquals("org.yuzu.yuzu_emu.activities.EmulationActivity", i.component?.className)
        assertGrantedData("eden", i)
    }

    @Test fun flycastAndM64PlusFzViewTheRom() {
        val flycast = build("flycast", "com.flycast.emulator")
        assertEquals(Intent.ACTION_VIEW, flycast.action)
        assertEquals("com.flycast.emulator.MainActivity", flycast.component?.className)
        assertGrantedData("flycast", flycast)

        val m64 = build("m64plusfz", "org.mupen64plusae.v3.fzurita.pro")
        assertEquals(Intent.ACTION_VIEW, m64.action)
        assertEquals("org.mupen64plusae.v3.fzurita.pro", m64.component?.packageName)
        assertEquals("paulscode.android.mupen64plusae.SplashActivity", m64.component?.className)
        assertGrantedData("m64plusfz", m64)
    }

    @Test fun melonDsGetsItsLaunchActionAndUriExtra() {
        val i = build("melonds", "me.magnum.melonds")
        assertEquals("me.magnum.melonds.LAUNCH_ROM", i.action)
        assertEquals("me.magnum.melonds.ui.emulator.EmulatorActivity", i.component?.className)
        assertEquals(uri.toString(), i.getStringExtra("uri"))
        assertGrantedData("melonds", i)
    }

    @Test fun scummVmReceivesTheGameIdStoredInTheLauncherFile() {
        val file = java.io.File.createTempFile("Monkey Island 2", ".scummvm").apply { writeText("monkey2\n") }
        val req = LaunchRequest(file.path, uri, "application/octet-stream", "org.scummvm.scummvm", null)
        val i = EmulatorRegistry.byId("scummvm")!!.buildIntent(req)
        assertEquals(Intent.ACTION_MAIN, i.action)
        assertEquals("org.scummvm.scummvm.SplashActivity", i.component?.className)
        assertEquals(Uri.parse("monkey2"), i.data)

        // An empty launcher file falls back to its name.
        val empty = java.io.File.createTempFile("tentacle", ".scummvm").apply { writeText("") }
        val fallback = EmulatorRegistry.byId("scummvm")!!.buildIntent(req.copy(romPath = empty.path))
        assertTrue(fallback.data.toString().startsWith("tentacle"))
    }

    @Test fun newPlatformsAreRecognisedAndPreferStandaloneEmulators() {
        val registry = dev.xross.launcher.storage.PlatformRegistry
        assertEquals("n3ds", registry.forFolder("3DS")?.id)
        assertEquals("n3ds", registry.forFolder("n3ds")?.id)
        assertEquals("switch", registry.forFolder("Switch")?.id)
        assertEquals("scummvm", registry.forFolder("scummvm")?.id)
        assertTrue("3ds" in registry.byId("n3ds")!!.extensions)
        assertTrue("xci" in registry.byId("switch")!!.extensions)
        assertTrue("scummvm" in registry.byId("scummvm")!!.extensions)

        fun first(id: String) = registry.byId(id)!!.emulators.first().key
        assertEquals("azahar", first("n3ds"))
        assertEquals("eden", first("switch"))
        assertEquals("flycast", first("dreamcast"))
        assertEquals("m64plusfz", first("n64"))
        assertEquals("melonds", first("nds"))
        assertEquals("scummvm", first("scummvm"))
        // RetroArch stays available as an alternative where a core exists.
        assertTrue(registry.byId("n64")!!.emulators.any { it.key == "retroarch:mupen64plus_next_gles3" })
        assertTrue(registry.byId("dreamcast")!!.emulators.any { it.key == "retroarch:flycast" })

        // Every platform option points at a registered emulator.
        for (platform in registry.all) for (option in platform.emulators) {
            assertTrue("${platform.id}:${option.key}", EmulatorRegistry.byId(option.emulatorId) != null)
        }
    }

    @Test fun genericIntentGrantsReadAccess() {
        val i = EmulatorRegistry.genericIntent(request(""))
        assertEquals(Intent.ACTION_VIEW, i.action)
        assertEquals(uri, i.data)
        assertTrue(i.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }
}
