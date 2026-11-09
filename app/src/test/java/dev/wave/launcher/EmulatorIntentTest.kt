package dev.wave.launcher

import android.content.Intent
import android.net.Uri
import dev.wave.launcher.launch.EmulatorRegistry
import dev.wave.launcher.launch.LaunchRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Verifies each intent recipe matches the launch contract the emulator's activity reads. */
@RunWith(RobolectricTestRunner::class)
class EmulatorIntentTest {
    private val uri = Uri.parse("content://dev.wave.launcher.files/root/storage/emulated/0/ROMs/snes/Game.sfc")

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

    @Test fun genericIntentGrantsReadAccess() {
        val i = EmulatorRegistry.genericIntent(request(""))
        assertEquals(Intent.ACTION_VIEW, i.action)
        assertEquals(uri, i.data)
        assertTrue(i.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }
}
