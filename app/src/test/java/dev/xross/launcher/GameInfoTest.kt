package dev.xross.launcher

import dev.xross.launcher.data.GameEntity
import dev.xross.launcher.data.GameHashEntity
import dev.xross.launcher.data.XrossSettings
import dev.xross.launcher.state.GameInfo
import dev.xross.launcher.state.GameInfoState
import dev.xross.launcher.state.InfoRow
import dev.xross.launcher.state.MenuSnapshot
import dev.xross.launcher.state.MenuTree
import dev.xross.launcher.storage.Fingerprint
import dev.xross.launcher.storage.HashSource
import dev.xross.launcher.storage.PlatformRegistry
import dev.xross.launcher.storage.SkipReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.util.Locale

class GameInfoTest {
    private val game = GameEntity(
        id = 4,
        path = "/storage/emulated/0/ROMs/snes/Chrono Trigger (USA).sfc",
        platformId = "snes",
        title = "Chrono Trigger",
        fileName = "Chrono Trigger (USA).sfc",
        sizeBytes = 4_194_816,
        lastModified = 1_700_000_000_000,
        favorite = true,
        lastPlayed = 1_760_000_000_000,
        playCount = 3,
        playTimeMs = 135 * 60_000L,
    )

    private fun build(
        g: GameEntity = game,
        hash: GameHashEntity? = null,
        emulator: String? = "RetroArch · snes9x",
        offline: Boolean = false,
        exists: Boolean? = true,
    ): GameInfoState = GameInfo.build(
        g, hash, PlatformRegistry.byId(g.platformId), emulator, offline, exists,
        zone = ZoneOffset.UTC, locale = Locale.US,
    )

    private fun GameInfoState.row(label: String): String? = rows.firstOrNull { it.label == label }?.value

    @Test fun showsFilePlatformSizeAndPlayHistory() {
        val info = build()
        assertEquals("Chrono Trigger", info.title)
        assertEquals("Super Nintendo", info.row("Platform"))
        assertEquals("Chrono Trigger (USA).sfc", info.row("File"))
        assertEquals("/storage/emulated/0/ROMs/snes", info.row("Folder"))
        assertEquals("Available", info.row("Status"))
        assertEquals("4.0 MB (4,194,816 bytes)", info.row("Size"))
        assertEquals("Yes", info.row("Favorite"))
        assertEquals("2 h 15 min", info.row("Playtime"))
        assertEquals("3 times", info.row("Times played"))
        assertTrue(info.row("Last played")!!.contains("2025"))
        assertEquals("RetroArch · snes9x", info.row("Emulator"))
        assertTrue(info.row("Fingerprint")!!.startsWith("Waiting"))
    }

    @Test fun neverPlayedAndUnplugged() {
        val info = build(
            g = game.copy(lastPlayed = null, playCount = 0, playTimeMs = 0),
            emulator = null,
            offline = true,
            exists = null,
        )
        assertEquals("Not played yet", info.row("Playtime"))
        assertEquals("Never", info.row("Times played"))
        assertEquals("Never", info.row("Last played"))
        assertEquals("Drive not connected", info.row("Status"))
        assertNull(info.row("Emulator"))
        assertEquals("Under a minute", build(g = game.copy(playCount = 1, playTimeMs = 20_000)).row("Playtime"))
        assertTrue(build(exists = false).row("Status")!!.startsWith("File missing"))
    }

    @Test fun listsHashesInUpperCaseMonospaceAndHeaderlessVariants() {
        val fp = Fingerprint(
            source = HashSource.FILE,
            crc32 = "2d206bf7",
            md5 = "a2bc447961e52fd2227baed164f729dc",
            sha1 = "de5822f4f2f7a55acb8926d4c0eaa63d5d989312",
            dataCrc32 = "2d206bf8",
            dataMd5 = "b2bc447961e52fd2227baed164f729dc",
            dataSha1 = "ee5822f4f2f7a55acb8926d4c0eaa63d5d989312",
            headerBytes = 512,
        )
        val info = build(hash = GameHashEntity.from(game, fp, now = 1_762_000_000_000))
        assertEquals("File contents", info.row("Identified by"))
        assertEquals("2D206BF7", info.row("CRC32"))
        assertEquals("DE5822F4F2F7A55ACB8926D4C0EAA63D5D989312", info.row("SHA-1"))
        assertEquals("512 bytes, excluded below", info.row("Header"))
        assertEquals("2D206BF8", info.row("CRC32 (no header)"))
        assertTrue(info.rows.single { it.label == "MD5" }.monospace)
        assertFalse(info.rows.single { it.label == "File" }.monospace)
        assertTrue(info.row("Fingerprinted")!!.contains("2025"))
        assertNull(info.row("Fingerprint"))
        // Row labels double as list keys in the UI, so they must be unique.
        assertEquals(info.rows.size, info.rows.map(InfoRow::label).toSet().size)
    }

    @Test fun archiveMembersAndSkipsExplainThemselves() {
        val member = Fingerprint(HashSource.ARCHIVE_ENTRY, crc32 = "aa", entryName = "ct.sfc", contentSize = 4_194_304)
        val archived = build(hash = GameHashEntity.from(game, member))
        assertEquals("Archive member: ct.sfc", archived.row("Identified by"))
        assertEquals("4.0 MB (4,194,304 bytes)", archived.row("Uncompressed size"))

        val large = build(hash = GameHashEntity.from(game, Fingerprint.skipped(SkipReason.LARGE)))
        assertTrue(large.row("Identified by")!!.contains("Fingerprint large disc images"))
        assertNull(large.row("CRC32"))
    }

    @Test fun staleFingerprintIsNotShownAsCurrent() {
        val old = GameHashEntity.from(game.copy(sizeBytes = 1), Fingerprint(HashSource.FILE, crc32 = "ff"))
        val info = build(hash = old)
        assertTrue(info.row("Fingerprint")!!.contains("changed"))
        assertNull(info.row("CRC32"))
    }

    @Test fun highlightMovesWithinTheRows() {
        val info = build()
        assertEquals(0, info.move(-1).index)
        assertEquals(info.rows.lastIndex, info.move(+100).index)
        assertEquals(3, info.moveTo(3).index)
    }

    @Test fun emulatorSummaryNamesOverridesAndMissingEmulators() {
        val tree = MenuTree(MenuSnapshot(games = listOf(game), installedEmulators = setOf("retroarch")))
        assertEquals("RetroArch · snes9x", tree.emulatorSummary(game))
        assertEquals(
            "RetroArch · snes9x · this game only",
            tree.emulatorSummary(game.copy(emulatorOverride = "retroarch:snes9x")),
        )
        val nothingInstalled = MenuTree(MenuSnapshot(games = listOf(game), settings = XrossSettings()))
        assertTrue(nothingInstalled.emulatorSummary(game)!!.endsWith("(not installed)"))
    }
}
