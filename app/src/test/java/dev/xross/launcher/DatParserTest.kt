package dev.xross.launcher

import dev.xross.launcher.data.DatCandidate
import dev.xross.launcher.data.SetNameHit
import dev.xross.launcher.data.dat.DatFormatException
import dev.xross.launcher.data.dat.DatGame
import dev.xross.launcher.data.dat.DatMatch
import dev.xross.launcher.data.dat.DatMatcher
import dev.xross.launcher.data.dat.DatNames
import dev.xross.launcher.data.dat.DatParser
import dev.xross.launcher.data.dat.MatchKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class DatParserTest {

    private fun parse(text: String): Pair<dev.xross.launcher.data.dat.DatHeader, List<DatGame>> {
        val games = ArrayList<DatGame>()
        val header = DatParser.parse(text.byteInputStream(), "fallback") { games += it }
        return header to games
    }

    // region Logiqx XML

    private val noIntro = """
        <?xml version="1.0"?>
        <!DOCTYPE datafile PUBLIC "-//Logiqx//DTD ROM Management Datafile//EN" "http://www.logiqx.com/Dats/datafile.dtd">
        <datafile>
          <header>
            <name>Nintendo - Super Nintendo Entertainment System</name>
            <description>Nintendo - Super Nintendo Entertainment System</description>
            <version>20261101-000000</version>
            <homepage>No-Intro</homepage>
          </header>
          <game name="Chrono Trigger (USA)" id="0123">
            <description>Chrono Trigger (USA)</description>
            <rom name="Chrono Trigger (USA).sfc" size="4194304" crc="2D206BF7" md5="A2BC447961E52FD2227BAED164F729DC" sha1="DE5822F4F2F7A55ACB8926D4C0EAA63D5D989312" serial="SNS-ACTE-USA"/>
          </game>
          <game name="Broken (Japan)">
            <rom name="Broken (Japan).sfc" size="1024" status="nodump"/>
          </game>
        </datafile>
    """.trimIndent()

    @Test fun readsNoIntroXml() {
        val (header, games) = parse(noIntro)
        assertEquals("Nintendo - Super Nintendo Entertainment System", header.name)
        assertEquals("20261101-000000", header.version)
        assertEquals("No-Intro", header.source)
        // The nodump-only game has nothing to match and is skipped.
        val game = games.single()
        assertEquals("Chrono Trigger (USA)", game.name)
        val rom = game.roms.single()
        assertEquals(4194304L, rom.size)
        assertEquals("2d206bf7", rom.crc32)
        assertEquals("a2bc447961e52fd2227baed164f729dc", rom.md5)
        assertEquals("de5822f4f2f7a55acb8926d4c0eaa63d5d989312", rom.sha1)
        assertEquals("SNS-ACTE-USA", rom.serial)
    }

    @Test fun readsRedumpMultiTrackAndGameSerials() {
        val (_, games) = parse(
            """
            <?xml version="1.0"?>
            <datafile>
              <header><name>Sony - PlayStation</name><homepage>redump.org</homepage></header>
              <game name="Final Fantasy VII (USA) (Disc 1)">
                <category>Games</category>
                <description>Final Fantasy VII (USA) (Disc 1)</description>
                <serial>SCUS-94163</serial>
                <rom name="Final Fantasy VII (USA) (Disc 1).cue" size="99" crc="1a2b3c4d"/>
                <rom name="Final Fantasy VII (USA) (Disc 1).bin" size="747435024" crc="0x1459cbef" sha1="aa1a8d0a1d1c5c06b5b3f58f8a9c5a1e1e1e1e1e"/>
              </game>
            </datafile>
            """.trimIndent(),
        )
        val game = games.single()
        assertEquals("SCUS-94163", game.serial)
        assertEquals(2, game.roms.size)
        assertEquals("1459cbef", game.roms[1].crc32)
    }

    @Test fun readsMameMachines() {
        val (header, games) = parse(
            """<?xml version="1.0"?><mame build="0.264"><machine name="sf2"><description>Street Fighter II: The World Warrior (World 910522)</description><rom name="sf2e_30g.11e" size="131072" crc="fe39ee33"/></machine></mame>""",
        )
        assertEquals("fallback", header.name)
        assertEquals("sf2", games.single().name)
    }

    @Test fun neverLoadsExternalEntities() {
        val secret = File.createTempFile("xross-secret", ".txt").apply { writeText("TOP SECRET"); deleteOnExit() }
        val dat = """
            <?xml version="1.0"?>
            <!DOCTYPE datafile [ <!ENTITY xxe SYSTEM "${secret.toURI()}"> ]>
            <datafile><header><name>x</name></header>
              <game name="a"><description>&xxe;</description><rom name="r" size="1" crc="00000001"/></game>
            </datafile>
        """.trimIndent()
        // Either outcome is safe: the entity resolves to nothing, or the parser refuses the DAT.
        try {
            val (_, games) = parse(dat)
            assertTrue(games.none { it.description.orEmpty().contains("SECRET") })
        } catch (_: DatFormatException) {
        }
    }

    @Test fun rejectsNonDatXml() {
        try {
            parse("<html><body>not a dat</body></html>")
            fail("expected DatFormatException")
        } catch (_: DatFormatException) {
        }
        try {
            parse("   ")
            fail("expected DatFormatException")
        } catch (_: DatFormatException) {
        }
    }

    // endregion

    // region clrmamepro

    @Test fun readsClrMameProText() {
        val (header, games) = parse(
            """
            clrmamepro (
                name "Nintendo - Game Boy"
                description "Nintendo - Game Boy"
                version 20260101
                homepage No-Intro
            )

            game (
                name "Tetris (World) (Rev 1)"
                description "Tetris (World) (Rev 1)"
                rom ( name "Tetris (World) (Rev 1).gb" size 32768 crc 46DF91AD md5 982ED5D2B12A0377EB14BCDC4123744E sha1 74591CC9501AF93873F9A5D3EB12DA12C0723BBC )
            )
            game (
                name "Nothing Here (USA)"
                rom ( name "x.gb" size 1 flags nodump )
            )
            """.trimIndent(),
        )
        assertEquals("Nintendo - Game Boy", header.name)
        assertEquals("No-Intro", header.source)
        val rom = games.single().roms.single()
        assertEquals("Tetris (World) (Rev 1).gb", rom.name)
        assertEquals("46df91ad", rom.crc32)
        assertEquals("74591cc9501af93873f9a5d3eb12da12c0723bbc", rom.sha1)
    }

    @Test fun hashNormalization() {
        assertEquals("0000abcd", DatParser.hex("ABCD", 8))
        assertEquals("0000abcd", DatParser.hex("0xabcd", 8))
        assertNull(DatParser.hex("zzzz", 8))
        assertNull(DatParser.hex("123456789", 8))
        assertNull(DatParser.hex("", 8))
    }

    // endregion

    // region Names

    @Test fun parsesNoIntroNameTags() {
        val t = DatNames.parse("Legend of Zelda, The - A Link to the Past (USA, Europe) (En,Fr,De) (Rev 1) (Beta)")
        assertEquals("The Legend of Zelda - A Link to the Past", t.title)
        assertEquals(listOf("USA", "Europe"), t.regions)
        assertEquals(listOf("En", "Fr", "De"), t.languages)
        assertEquals("Rev 1", t.revision)
        assertEquals(listOf("Beta"), t.flags)

        val ff = DatNames.parse("Final Fantasy VII (USA) (Disc 1)")
        assertEquals("Final Fantasy VII", ff.title)
        assertEquals(listOf("Disc 1"), ff.flags)
        assertEquals("Tetris", DatNames.parse("Tetris").title)
    }

    @Test fun arcadeSetsUseTheDescription() {
        assertEquals("Street Fighter II: The World Warrior", DatNames.bestTitle("sf2", "Street Fighter II: The World Warrior (World 910522)").title)
        assertEquals("Chrono Trigger", DatNames.bestTitle("Chrono Trigger (USA)", "Chrono Trigger (USA)").title)
        // Found on device: one-word arcade titles differ from their set names only by case.
        assertEquals("Amidar", DatNames.bestTitle("amidar", "Amidar").title)
        assertEquals("Berzerk", DatNames.bestTitle("berzerk1", "Berzerk (revision RC28)").title)
        assertEquals("pengo", DatNames.bestTitle("pengo", null).title)
    }

    @Test fun mapsDatSystemsToPlatforms() {
        assertEquals("snes", DatNames.platformFor("Nintendo - Super Nintendo Entertainment System (Parent-Clone)"))
        assertEquals("gba", DatNames.platformFor("Nintendo - Game Boy Advance"))
        assertEquals("gb", DatNames.platformFor("Nintendo - Game Boy"))
        assertEquals("gbc", DatNames.platformFor("Nintendo - Game Boy Color"))
        assertEquals("psx", DatNames.platformFor("Sony - PlayStation"))
        assertEquals("ps2", DatNames.platformFor("Sony - PlayStation 2"))
        assertEquals("psp", DatNames.platformFor("Sony - PlayStation Portable"))
        assertEquals("megadrive", DatNames.platformFor("Sega - Mega Drive - Genesis"))
        assertEquals("arcade", DatNames.platformFor("MAME 0.264"))
        assertNull(DatNames.platformFor("Sony - PlayStation 3"))
        assertNull(DatNames.platformFor("Nintendo - Wii U"))
        assertNull(DatNames.platformFor("Atari - 2600"))
    }

    // endregion

    // region Matching

    private fun hit(game: Long, rom: Long, kind: String, romSize: Long? = 100, hashed: Long? = 100, platform: String? = "snes", at: Long = 1) =
        DatCandidate(game, rom, datId = rom * 10, kind = kind, romSize = romSize, hashedSize = hashed, datPlatformId = platform, datImportedAt = at)

    @Test fun strongerHashesWin() {
        val result = DatMatcher.resolve(listOf(hit(1, 10, "CRC32"), hit(1, 11, "MD5"), hit(1, 12, "SHA1"))) { "snes" }
        assertEquals(listOf(DatMatch(1, 12, MatchKind.SHA1)), result)
    }

    @Test fun crcNeedsAgreeingSizeToCountAsStrong() {
        assertEquals(MatchKind.CRC32_SIZE, DatMatcher.resolve(listOf(hit(1, 10, "CRC32"))) { null }.single().kind)
        assertEquals(MatchKind.CRC32, DatMatcher.resolve(listOf(hit(1, 10, "CRC32", romSize = null))) { null }.single().kind)
        // A CRC collision with the wrong size is not a match at all.
        assertTrue(DatMatcher.resolve(listOf(hit(1, 10, "CRC32", romSize = 999))) { null }.isEmpty())
    }

    @Test fun ownPlatformThenNewestDatBreakTies() {
        val tie = listOf(
            hit(1, 10, "SHA1", platform = "gb", at = 50),
            hit(1, 11, "SHA1", platform = "gbc", at = 10),
            hit(1, 12, "SHA1", platform = "gbc", at = 20),
        )
        assertEquals(12L, DatMatcher.resolve(tie) { "gbc" }.single().romId)
        assertEquals(10L, DatMatcher.resolve(tie) { "nes" }.single().romId)
    }

    @Test fun arcadeSetNamesPreferArcadeDats() {
        val hits = listOf(
            SetNameHit(romId = 5, datId = 1, gameName = "sf2", datPlatformId = null, datImportedAt = 9),
            SetNameHit(romId = 6, datId = 2, gameName = "sf2", datPlatformId = "arcade", datImportedAt = 1),
        )
        assertEquals(listOf(DatMatch(3, 6, MatchKind.SET_NAME)), DatMatcher.resolveSetNames(mapOf(3L to "sf2", 4L to "nope"), hits))
    }

    // endregion
}
