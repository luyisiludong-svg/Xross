package dev.xross.launcher

import dev.xross.launcher.storage.Fingerprinter
import dev.xross.launcher.storage.FingerprintPolicy
import dev.xross.launcher.storage.HashSource
import dev.xross.launcher.storage.PlatformRegistry
import dev.xross.launcher.storage.SkipReason
import kotlinx.coroutines.test.runTest
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FingerprinterTest {
    @get:Rule val tmp = TemporaryFolder()

    // Reference values for the ASCII string "abc".
    private val abc = "abc".toByteArray()
    private val abcCrc = "352441c2"
    private val abcMd5 = "900150983cd24fb0d6963f7d28e17f72"
    private val abcSha1 = "a9993e364706816aba3e25717850c26c9cd0d89d"

    private val snes = PlatformRegistry.byId("snes")!!
    private val fp = Fingerprinter()

    private fun file(name: String, bytes: ByteArray) = tmp.newFile(name).apply { writeBytes(bytes) }

    @Test fun plainFileGetsAllThreeHashes() = runTest {
        val f = fp.fingerprint(file("game.sfc", abc), snes)
        assertEquals(HashSource.FILE, f.source)
        assertEquals(abcCrc, f.crc32)
        assertEquals(abcMd5, f.md5)
        assertEquals(abcSha1, f.sha1)
        assertNull(f.dataCrc32)
    }

    @Test fun inesHeaderIsExcludedFromDataHashes() = runTest {
        val header = byteArrayOf(0x4E, 0x45, 0x53, 0x1A) + ByteArray(12)
        val f = fp.fingerprint(file("game.nes", header + abc), PlatformRegistry.byId("nes"))
        assertEquals(16, f.headerBytes)
        assertEquals(abcCrc, f.dataCrc32)
        assertEquals(abcSha1, f.dataSha1)
    }

    @Test fun snesCopierHeaderDetectedBySize() = runTest {
        val rom = ByteArray(1024) { (it % 251).toByte() }
        val plain = fp.fingerprint(file("plain.smc", rom), snes)
        val headered = fp.fingerprint(file("headered.smc", ByteArray(512) + rom), snes)
        assertEquals(0, plain.headerBytes)
        assertEquals(512, headered.headerBytes)
        assertEquals(plain.crc32, headered.dataCrc32)
        assertEquals(plain.md5, headered.dataMd5)
    }

    @Test fun zipMemberIsIdentifiedAndPreferredByExtension() = runTest {
        val zip = tmp.newFile("game.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("readme.txt")); z.write(ByteArray(5000)); z.closeEntry()
            z.putNextEntry(ZipEntry("Game (USA).sfc")); z.write(abc); z.closeEntry()
        }
        val f = fp.fingerprint(zip, snes)
        assertEquals(HashSource.ARCHIVE_ENTRY, f.source)
        assertEquals("Game (USA).sfc", f.entryName)
        assertEquals(abcCrc, f.crc32)
        assertEquals(abcSha1, f.sha1)
        assertNull(f.detail) // header CRC matched the decompressed content
    }

    @Test fun largeZipMemberKeepsHeaderCrcWithoutDecompressing() = runTest {
        val zip = tmp.newFile("big.zip")
        ZipOutputStream(zip.outputStream()).use { z -> z.putNextEntry(ZipEntry("g.sfc")); z.write(abc); z.closeEntry() }
        val f = Fingerprinter(FingerprintPolicy(maxFullHashBytes = 2)).fingerprint(zip, snes)
        assertEquals(abcCrc, f.crc32)
        assertNull(f.sha1)
    }

    @Test fun zipWithoutAPlatformRomIsSkipped() = runTest {
        val zip = tmp.newFile("manual.zip")
        ZipOutputStream(zip.outputStream()).use { z -> z.putNextEntry(ZipEntry("manual.pdf")); z.write(abc); z.closeEntry() }
        assertEquals(SkipReason.NO_ROM_IN_ARCHIVE, fp.fingerprint(zip, snes).skipReason)
    }

    @Test fun arcadeSetsAreIdentifiedByName() = runTest {
        val zip = tmp.newFile("sf2.zip")
        ZipOutputStream(zip.outputStream()).use { z -> z.putNextEntry(ZipEntry("sf2.01")); z.write(abc); z.closeEntry() }
        assertEquals(HashSource.ARCHIVE_SET, fp.fingerprint(zip, PlatformRegistry.byId("arcade")).source)
    }

    @Test fun sevenZipMemberIsHashed() = runTest {
        val archive = tmp.newFile("game.7z")
        SevenZOutputFile(archive).use { out ->
            val entry = out.createArchiveEntry(tmp.newFile("Game.gba").apply { writeBytes(abc) }, "Game.gba")
            out.putArchiveEntry(entry); out.write(abc); out.closeArchiveEntry()
        }
        val f = fp.fingerprint(archive, PlatformRegistry.byId("gba"))
        assertEquals(HashSource.ARCHIVE_ENTRY, f.source)
        assertEquals("Game.gba", f.entryName)
        assertEquals(abcCrc, f.crc32)
        assertEquals(abcMd5, f.md5)
    }

    @Test fun chdV5ShaComesFromTheHeader() = runTest {
        val header = ByteArray(124)
        "MComprHD".toByteArray().copyInto(header)
        header[15] = 5 // version, big-endian
        val sha = ByteArray(20) { (it + 1).toByte() }
        sha.copyInto(header, 84)
        val f = fp.fingerprint(file("game.chd", header + ByteArray(64)), PlatformRegistry.byId("psx"))
        assertEquals(HashSource.CHD_HEADER, f.source)
        assertEquals("0102030405060708090a0b0c0d0e0f1011121314", f.sha1)
    }

    @Test fun largeImagesSkippedUnlessEnabled() = runTest {
        val iso = file("game.iso", abc)
        val small = FingerprintPolicy(maxFullHashBytes = 2)
        assertEquals(SkipReason.LARGE, Fingerprinter(small).fingerprint(iso, null).skipReason)
        assertEquals(abcSha1, Fingerprinter(small.copy(hashLargeImages = true)).fingerprint(iso, null).sha1)
    }

    @Test fun sheetsCompressedImagesAndCorruptArchivesAreSkipped() = runTest {
        assertEquals(SkipReason.MULTI_FILE, fp.fingerprint(file("g.cue", abc), null).skipReason)
        assertEquals(SkipReason.COMPRESSED_IMAGE, fp.fingerprint(file("g.rvz", abc), null).skipReason)
        assertEquals(SkipReason.ERROR, fp.fingerprint(file("broken.zip", abc), snes).skipReason)
    }

    @Test(expected = java.io.FileNotFoundException::class)
    fun missingFilesAreRetriedNotRecorded() = runTest {
        fp.fingerprint(File(tmp.root, "unmounted.sfc"), snes)
    }
}
