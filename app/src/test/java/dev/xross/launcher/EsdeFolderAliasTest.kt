package dev.xross.launcher

import dev.xross.launcher.model.PlatformRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ES-DE's hack and MSU folders scan as their base system. */
class EsdeFolderAliasTest {
    @Test fun hackAndMsuFoldersBelongToTheirBaseSystem() {
        val expected = mapOf(
            "nesh" to "nes", "snesh" to "snes", "snes-msu1" to "snes", "gbh" to "gb", "gbch" to "gbc",
            "gbah" to "gba", "genh" to "megadrive", "msu-md" to "megadrive", "MSU-MD" to "megadrive",
        )
        for ((folder, id) in expected) assertEquals(folder, id, PlatformRegistry.forFolder(folder)?.id)
    }

    @Test fun msuMdCueSheetsAreListed() {
        val md = PlatformRegistry.byId("megadrive")!!
        assertTrue(md.extensions.containsAll(listOf("cue", "chd", "bin", "md")))
    }
}
