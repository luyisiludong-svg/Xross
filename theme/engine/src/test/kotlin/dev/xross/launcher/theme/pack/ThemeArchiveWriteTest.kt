package dev.xross.launcher.theme.pack

import dev.xross.launcher.theme.pack.ArchiveEntry
import dev.xross.launcher.theme.pack.ArchiveResult
import dev.xross.launcher.theme.pack.ArchiveWrite
import dev.xross.launcher.theme.pack.ExportReader
import dev.xross.launcher.theme.pack.ExportScope
import dev.xross.launcher.theme.pack.ExportSource
import dev.xross.launcher.theme.pack.LoadedTheme
import dev.xross.launcher.theme.pack.LookExporter
import dev.xross.launcher.theme.pack.StagedTheme
import dev.xross.launcher.theme.pack.ThemeArchive
import dev.xross.launcher.theme.pack.ThemePackLoader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.time.LocalDate
import java.util.zip.ZipFile

/**
 * 2b.8c's `.xtheme` writer: a folder and the built-in Default pack's assets zip into archives
 * [ThemeArchive.stage] accepts, with the same files; dot-files, litter and files an import would
 * refuse are left out; the size and entry caps; the same pack zips to the same bytes.
 */
class ThemeArchiveWriteTest {
    @get:Rule val tmp = TemporaryFolder()

    private val themes get() = File(tmp.root, "Xross/themes").apply { mkdirs() }
    private val loader by lazy { TestAssets.loader { themes } }
    private val archives by lazy { ThemeArchive({ themes }, loader::inspect) }
    private val date = LocalDate.of(2026, 11, 15)

    private fun reader(pack: LoadedTheme) = object : ExportReader {
        override fun size(source: ExportSource): Long? = (source as? ExportSource.Pack)?.let { loader.packFileSize(pack.info, it.path) }
        override fun open(source: ExportSource): InputStream = loader.openPackFile(pack.info, (source as ExportSource.Pack).path)
    }

    private fun out(name: String) = File(tmp.root, "shared/$name").apply { parentFile!!.mkdirs() }

    private fun written(r: ArchiveWrite): ArchiveWrite.Written = r as? ArchiveWrite.Written ?: throw AssertionError("not written: $r")

    private fun staged(file: File): StagedTheme {
        val r = archives.stage(file, file.name)
        return (r as? ArchiveResult.Staged)?.theme ?: throw AssertionError("rejected: $r")
    }

    private fun names(file: File): List<String> = ZipFile(file).use { z -> z.entries().toList().map { it.name } }

    /** Every file under [dir] as `/` paths. */
    private fun tree(dir: File): Set<String> = dir.walkTopDown().filter { it.isFile }.mapTo(sortedSetOf()) { it.relativeTo(dir).invariantSeparatorsPath }

    @Test fun aFolderZipsToTheSameFilesAndStages() {
        val dir = StressPack.write(themes)
        val file = out("stress.xtheme")
        val r = written(file.outputStream().use { ThemeArchive.writeFolder(dir, it) })
        assertEquals(emptyList<String>(), r.leftOut)
        val names = names(file)
        assertEquals("theme.json first, at the archive root", "theme.json", names.first())
        assertFalse(names.any { it.startsWith(".") || "/." in it })
        val s = staged(file)
        // Everything but the dot-files, byte for byte, imports included.
        val expected = tree(dir).filterNot { it.startsWith(".") }.toSet()
        assertEquals(expected, tree(s.dir))
        assertEquals(r.files, expected.size)
        for (p in expected) assertArrayEquals(p, File(dir, p).readBytes(), File(s.dir, p).readBytes())
        assertTrue(File(s.dir, "surfaces.json").isFile && File(s.dir, "rules.json").isFile)
    }

    @Test fun theBuiltInPackStreamsFromItsAssets() {
        val pack = loader.load(ThemePackLoader.DEFAULT_ID)
        val export = LookExporter.export(pack, ExportScope.WholePack, "", date, loader.packFiles(pack.info))
        val file = out("default.xtheme")
        val r = written(file.outputStream().use { ThemeArchive.write(export, reader(pack), it) })
        assertEquals(export.files.size + 1, r.files)
        // No folder was written to make it.
        assertEquals(emptyList<String>(), themes.list()!!.toList())
        val s = staged(file)
        assertEquals("xmb-pack", s.id)
        assertEquals(setOf("theme.json") + export.files.map { it.path }, tree(s.dir))
        for (f in export.files) {
            val bytes = TestAssets.platform.openAsset("${ThemePackLoader.DEFAULT_DIR}/${f.path}").use { it.readBytes() }
            assertArrayEquals(f.path, bytes, File(s.dir, f.path).readBytes())
        }
        assertTrue("sounds/ and icons/ by convention", File(s.dir, "sounds/gameboot.wav").isFile && File(s.dir, "icons/power.svg").isFile)
    }

    @Test fun dotFilesLitterAndUnusableFilesAreLeftOut() {
        val dir = StressPack.write(themes)
        File(dir, ".DS_Store").writeText("junk")
        File(dir, "icons/._games.svg").writeText("junk")
        File(dir, "art/preview.gif").writeText("gif")
        File(dir, "LICENSE").writeText("MIT")
        File(dir, "tools/build.sh").apply { parentFile!!.mkdirs() }.writeText("rm -rf /")
        File(dir, "icons/a/b/c/deep.svg").apply { parentFile!!.mkdirs() }.writeText("<svg/>")
        val file = out("stress.xtheme")
        val r = written(file.outputStream().use { ThemeArchive.writeFolder(dir, it) })
        assertEquals(listOf("LICENSE", "art/preview.gif", "icons/a/b/c/deep.svg", "tools/build.sh"), r.leftOut.sorted())
        val names = names(file)
        for (gone in listOf(".DS_Store", "icons/._games.svg", ".git/config", ".nomedia", "LICENSE", "art/preview.gif", "tools/build.sh")) {
            assertFalse(gone, gone in names)
        }
        staged(file)
    }

    @Test fun theCapsMatchTheReader() {
        val dir = StressPack.write(themes)
        // Over the size cap: refused, the largest files named.
        val small = written(ByteArrayOutputStream().use { ThemeArchive.writeFolder(dir, it) }).bytes
        val refused = ByteArrayOutputStream().use { ThemeArchive.writeFolder(dir, it, maxBytes = small - 1) } as ArchiveWrite.Refused
        assertTrue(refused.reason, refused.reason.startsWith("The pack would be ") && "(largest: theme.json 2 KB, boot.mp4 1 KB" in refused.reason)
        // A size the header didn't know is caught while streaming.
        val blind = ByteArrayOutputStream().use { stream ->
            ThemeArchive.write(listOf(ArchiveEntry("theme.json", null) { "{}".byteInputStream() }, ArchiveEntry("sounds/big.wav", null) { ByteArray(5_000).inputStream() }), stream, maxBytes = 1_000)
        }
        assertTrue(blind is ArchiveWrite.Refused)
        // More entries than an import takes.
        val many = listOf(ArchiveEntry("theme.json", 2) { "{}".byteInputStream() }) +
            (0..ThemeArchive.MAX_ENTRIES).map { i -> ArchiveEntry("sounds/s$i.wav", 1) { byteArrayOf(1).inputStream() } }
        val r = ByteArrayOutputStream().use { ThemeArchive.write(many, it) } as ArchiveWrite.Refused
        assertTrue(r.reason, "more than ${ThemeArchive.MAX_ENTRIES} files" in r.reason)
        // A root music loop over 20 MB would be refused by the import (and isn't played): left out.
        val loud = written(
            ByteArrayOutputStream().use {
                ThemeArchive.write(listOf(ArchiveEntry("theme.json", 2) { "{}".byteInputStream() }, ArchiveEntry("bgm.ogg", 21L shl 20) { ByteArray(0).inputStream() }), it)
            },
        )
        assertEquals(listOf("bgm.ogg"), loud.leftOut)
        // No theme.json first, or a name that leaves the pack: refused.
        assertTrue(ByteArrayOutputStream().use { ThemeArchive.write(listOf(ArchiveEntry("sounds/a.wav", 1) { byteArrayOf(1).inputStream() }), it) } is ArchiveWrite.Refused)
        assertTrue(
            ByteArrayOutputStream().use {
                ThemeArchive.write(listOf(ArchiveEntry("theme.json", 2) { "{}".byteInputStream() }, ArchiveEntry("../evil.svg", 1) { byteArrayOf(1).inputStream() }), it)
            } is ArchiveWrite.Refused,
        )
    }

    @Test fun aReadFailureRefuses() {
        val r = ByteArrayOutputStream().use {
            ThemeArchive.write(listOf(ArchiveEntry("theme.json", 2) { "{}".byteInputStream() }, ArchiveEntry("sounds/a.wav", 1) { throw IOException("card removed") }), it)
        }
        assertEquals("Couldn't write sounds/a.wav (card removed)", (r as ArchiveWrite.Refused).reason)
    }

    @Test fun theSamePackZipsToTheSameBytes() {
        val dir = StressPack.write(themes)
        val a = ByteArrayOutputStream().also { ThemeArchive.writeFolder(dir, it) }.toByteArray()
        Thread.sleep(5)
        val b = ByteArrayOutputStream().also { ThemeArchive.writeFolder(dir, it) }.toByteArray()
        assertArrayEquals(a, b)
    }
}
