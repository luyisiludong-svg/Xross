package dev.wave.launcher

import dev.wave.launcher.storage.PlatformRegistry
import dev.wave.launcher.storage.RomScanner
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RomScannerTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun cleansTitles() {
        assertEquals("Chrono Trigger", RomScanner.cleanTitle("Chrono Trigger (USA) [!]"))
        assertEquals("Final Fantasy VII", RomScanner.cleanTitle("Final_Fantasy_VII (Disc 1)"))
        assertEquals("(Untitled)", RomScanner.cleanTitle("(Untitled)"))
    }

    @Test fun folderAliasesAreNormalized() {
        assertEquals("snes", PlatformRegistry.forFolder("Super Nintendo")?.id)
        assertEquals("ps2", PlatformRegistry.forFolder("PlayStation 2")?.id)
        assertEquals("megadrive", PlatformRegistry.forFolder("Mega-Drive")?.id)
        assertEquals(null, PlatformRegistry.forFolder("Downloads"))
    }

    @Test fun scansPlatformFoldersAndFiltersExtensions() {
        val root = tmp.newFolder("ROMs")
        root.resolve("snes").mkdirs()
        root.resolve("snes/Super Metroid (USA).sfc").writeText("x")
        root.resolve("snes/readme.txt").writeText("x")
        root.resolve("snes/media").mkdirs()
        root.resolve("snes/media/Super Metroid.sfc").writeText("x") // asset folder: ignored
        root.resolve("gba/RPG").mkdirs()
        root.resolve("gba/RPG/Golden Sun.gba").writeText("x")
        root.resolve("Downloads").mkdirs()
        root.resolve("Downloads/random.zip").writeText("x")

        val roms = RomScanner().scan(listOf(root)).associateBy { it.title }
        assertEquals(setOf("Super Metroid", "Golden Sun"), roms.keys)
        assertEquals("snes", roms.getValue("Super Metroid").platformId)
        assertEquals("gba", roms.getValue("Golden Sun").platformId)
    }

    @Test fun rootCanBeAPlatformFolder() {
        val psx = tmp.newFolder("psx")
        psx.resolve("Game.chd").writeText("x")
        assertEquals(listOf("psx"), RomScanner().scan(listOf(psx)).map { it.platformId })
    }

    @Test fun cueAndM3uHideTheirTracks() {
        val psx = tmp.newFolder("ROMs", "psx")
        psx.resolve("FF7 (Disc 1).cue").writeText("FILE \"FF7 (Disc 1).bin\" BINARY\n  TRACK 01 MODE2/2352\n")
        psx.resolve("FF7 (Disc 1).bin").writeText("x")
        psx.resolve("FF7 (Disc 2).cue").writeText("FILE \"FF7 (Disc 2).bin\" BINARY\n")
        psx.resolve("FF7 (Disc 2).bin").writeText("x")
        psx.resolve("FF7.m3u").writeText("#EXTM3U\nFF7 (Disc 1).cue\nFF7 (Disc 2).cue\n")
        psx.resolve("Loose.bin").writeText("x")

        val names = RomScanner().scan(listOf(psx.parentFile!!)).map { it.fileName }.toSet()
        assertEquals(setOf("FF7.m3u", "Loose.bin"), names)
    }

    @Test fun parsesGdiTrackNames() {
        val refs = RomScanner.parseReferences(
            "gdi",
            listOf("3", "1 0 4 2352 track01.bin 0", "2 756 0 2352 \"track02.raw\" 0", "3 45000 4 2352 track03.bin 0"),
        )
        assertEquals(setOf("track01.bin", "track02.raw", "track03.bin"), refs)
    }
}
