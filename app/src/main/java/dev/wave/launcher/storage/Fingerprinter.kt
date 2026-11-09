package dev.wave.launcher.storage

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipFile

/** How a fingerprint was obtained. */
enum class HashSource {
    /** Whole file streamed. */
    FILE,

    /** One ROM inside a .zip/.7z: CRC32 from the archive header, MD5/SHA-1 from decompressing it. */
    ARCHIVE_ENTRY,

    /** Arcade set: identified by set name, not by hash. */
    ARCHIVE_SET,

    /** SHA-1 recorded in a CHD v4/v5 header. */
    CHD_HEADER,

    /** Not hashed; see [SkipReason]. */
    SKIPPED,
}

enum class SkipReason {
    /** Larger than the full-hash limit and disc hashing is off. Re-evaluated when it's turned on. */
    LARGE,

    /** Compressed disc container (rvz, cso, …): its bytes don't match any DAT. */
    COMPRESSED_IMAGE,

    /** Cue/gdi/m3u sheet: tracks are hashed individually (not implemented yet). */
    MULTI_FILE,

    /** Archive with no file this platform can run. */
    NO_ROM_IN_ARCHIVE,

    /** Unreadable or corrupt. */
    ERROR,
}

data class Fingerprint(
    val source: HashSource,
    /** Lower-case hex. Null when the source can't provide it. */
    val crc32: String? = null,
    val md5: String? = null,
    val sha1: String? = null,
    /** Hashes of the ROM data with a copier/emulator header removed (No-Intro style). */
    val dataCrc32: String? = null,
    val dataMd5: String? = null,
    val dataSha1: String? = null,
    val headerBytes: Int = 0,
    /** Archive member that was fingerprinted. */
    val entryName: String? = null,
    /** Size of the hashed content (uncompressed member size for archives). */
    val contentSize: Long? = null,
    val skipReason: SkipReason? = null,
    val detail: String? = null,
) {
    companion object {
        fun skipped(reason: SkipReason, detail: String? = null) =
            Fingerprint(HashSource.SKIPPED, skipReason = reason, detail = detail)
    }
}

data class FingerprintPolicy(
    /** Plain files and archive members up to this size are fully hashed. */
    val maxFullHashBytes: Long = 256L * 1024 * 1024,
    /** Also fully hash larger disc images (ISO/BIN). Slow on SD cards; off by default. */
    val hashLargeImages: Boolean = false,
)

/**
 * Identifies ROM files by content for DAT matching and scrapers (ScreenScraper, Hasheous).
 *
 * Cheap paths first: CRC32 straight from ZIP/7z headers, SHA-1 straight from CHD headers. Only
 * cartridge-sized content is streamed, in one pass computing CRC32, MD5 and SHA-1 together.
 */
class Fingerprinter(private val policy: FingerprintPolicy = FingerprintPolicy()) {

    suspend fun fingerprint(file: File, platform: Platform?): Fingerprint {
        val ext = file.extension.lowercase()
        if (!file.isFile) throw java.io.FileNotFoundException(file.path)
        return try {
            when {
                ext == "zip" -> zip(file, platform)
                ext == "7z" -> sevenZip(file, platform)
                ext == "chd" -> chd(file)
                ext in COMPRESSED_IMAGES -> Fingerprint.skipped(SkipReason.COMPRESSED_IMAGE)
                ext in SHEETS -> Fingerprint.skipped(SkipReason.MULTI_FILE)
                file.length() > policy.maxFullHashBytes && !policy.hashLargeImages ->
                    Fingerprint.skipped(SkipReason.LARGE)
                else -> file.inputStream().buffered(BUFFER).use { stream(it, headerSize(ext, file), HashSource.FILE) }
                    .copy(contentSize = file.length())
            }
        } catch (e: java.io.FileNotFoundException) {
            throw e // missing (unmounted) files are retried later, not recorded
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Fingerprint.skipped(SkipReason.ERROR, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // region Archives

    private suspend fun zip(file: File, platform: Platform?): Fingerprint = ZipFile(file).use { zip ->
        val entries = zip.entries().asSequence().filterNot { it.isDirectory }
            .map { Member(it.name, it.size, it.crc) to it }.toList()
        if (platform?.id == ARCADE) return Fingerprint(HashSource.ARCHIVE_SET, entryName = null, detail = "${entries.size} files")
        val (member, entry) = pick(entries.map { it.first }, platform)
            ?.let { m -> entries.first { it.first === m } }
            ?: return Fingerprint.skipped(SkipReason.NO_ROM_IN_ARCHIVE)
        archiveMember(member) { zip.getInputStream(entry) }
    }

    private suspend fun sevenZip(file: File, platform: Platform?): Fingerprint =
        SevenZFile.builder().setFile(file).setMaxMemoryLimitKb(SEVEN_Z_MEMORY_KB).get().use { archive ->
            val entries = archive.entries.filterNot { it.isDirectory }
                .map { Member(it.name, it.size, if (it.hasCrc) it.crcValue else -1L) to it }
            if (platform?.id == ARCADE) return Fingerprint(HashSource.ARCHIVE_SET, detail = "${entries.size} files")
            val (member, entry) = pick(entries.map { it.first }, platform)
                ?.let { m -> entries.first { it.first === m } }
                ?: return Fingerprint.skipped(SkipReason.NO_ROM_IN_ARCHIVE)
            archiveMember(member) { archive.getInputStream(entry) }
        }

    private data class Member(val name: String, val size: Long, val crc: Long)

    /** Prefer a member with one of the platform's ROM extensions; otherwise the largest. */
    private fun pick(members: List<Member>, platform: Platform?): Member? {
        val romExts = platform?.extensions.orEmpty() - ARCHIVES
        val candidates = members.filter { it.name.substringAfterLast('.', "").lowercase() in romExts }
        if (platform != null && romExts.isNotEmpty() && candidates.isEmpty()) return null
        return (candidates.ifEmpty { members }).maxByOrNull { it.size }
    }

    private suspend fun archiveMember(member: Member, open: () -> InputStream): Fingerprint {
        val headerCrc = member.crc.takeIf { it >= 0 }?.let(::crcHex)
        val ext = member.name.substringAfterLast('.', "").lowercase()
        if (member.size > policy.maxFullHashBytes || member.size < 0) {
            return Fingerprint(HashSource.ARCHIVE_ENTRY, crc32 = headerCrc, entryName = member.name, contentSize = member.size)
        }
        val hashed = try {
            open().buffered(BUFFER).use { stream(it, headerSizeFromName(ext, member.size), HashSource.ARCHIVE_ENTRY) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // e.g. a 7z whose LZMA dictionary exceeds the memory limit: keep the header CRC.
            return Fingerprint(
                HashSource.ARCHIVE_ENTRY, crc32 = headerCrc, entryName = member.name, contentSize = member.size,
                detail = "not decompressed: ${e.javaClass.simpleName}",
            )
        }
        // A mismatch means a damaged archive; keep the computed value, flag it.
        val detail = if (headerCrc != null && headerCrc != hashed.crc32) "CRC mismatch: header $headerCrc" else null
        return hashed.copy(entryName = member.name, contentSize = member.size, detail = detail)
    }

    // endregion

    // region CHD

    /** CHD v5: raw SHA-1 at 64, data+metadata SHA-1 at 84. v4: SHA-1 at 48. */
    private fun chd(file: File): Fingerprint = RandomAccessFile(file, "r").use { raf ->
        val header = ByteArray(124)
        val read = raf.read(header)
        if (read < 16 || String(header, 0, 8, Charsets.US_ASCII) != "MComprHD") {
            return Fingerprint.skipped(SkipReason.ERROR, "not a CHD file")
        }
        val version = ((header[12].toInt() and 0xFF) shl 24) or ((header[13].toInt() and 0xFF) shl 16) or
            ((header[14].toInt() and 0xFF) shl 8) or (header[15].toInt() and 0xFF)
        val sha1Offset = when (version) {
            5 -> 84
            4 -> 48
            else -> return Fingerprint.skipped(SkipReason.ERROR, "CHD v$version unsupported")
        }
        if (read < sha1Offset + 20) return Fingerprint.skipped(SkipReason.ERROR, "truncated CHD header")
        Fingerprint(
            HashSource.CHD_HEADER,
            sha1 = header.copyOfRange(sha1Offset, sha1Offset + 20).toHex(),
            contentSize = file.length(),
            detail = "CHD v$version",
        )
    }

    // endregion

    // region Streaming

    /**
     * One pass over [input]: full-content CRC32/MD5/SHA-1, plus the same three over the data
     * after [header] bytes when a header is present.
     */
    private suspend fun stream(input: InputStream, header: Int, source: HashSource): Fingerprint {
        val crc = CRC32()
        val md5 = MessageDigest.getInstance("MD5")
        val sha1 = MessageDigest.getInstance("SHA-1")
        val dCrc = if (header > 0) CRC32() else null
        val dMd5 = if (header > 0) MessageDigest.getInstance("MD5") else null
        val dSha1 = if (header > 0) MessageDigest.getInstance("SHA-1") else null

        val buffer = ByteArray(BUFFER)
        var offset = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            crc.update(buffer, 0, n)
            md5.update(buffer, 0, n)
            sha1.update(buffer, 0, n)
            if (header > 0) {
                val skip = (header - offset).coerceIn(0, n.toLong()).toInt()
                if (n > skip) {
                    dCrc!!.update(buffer, skip, n - skip)
                    dMd5!!.update(buffer, skip, n - skip)
                    dSha1!!.update(buffer, skip, n - skip)
                }
            }
            offset += n
            currentCoroutineContext().ensureActive()
        }
        return Fingerprint(
            source = source,
            crc32 = crcHex(crc.value),
            md5 = md5.digest().toHex(),
            sha1 = sha1.digest().toHex(),
            dataCrc32 = dCrc?.let { crcHex(it.value) },
            dataMd5 = dMd5?.digest()?.toHex(),
            dataSha1 = dSha1?.digest()?.toHex(),
            headerBytes = header,
        )
    }

    /** Copier/emulator headers that No-Intro excludes from its hashes. */
    private fun headerSize(ext: String, file: File): Int = when (ext) {
        "nes" -> if (file.startsWith(INES)) 16 else 0
        "fds" -> if (file.startsWith(FWNES)) 16 else 0
        else -> headerSizeFromName(ext, file.length())
    }

    private fun headerSizeFromName(ext: String, size: Long): Int = when (ext) {
        // SNES copier headers make the size 512 bytes over a 1 KiB multiple.
        "smc", "sfc", "fig", "swc" -> if (size % 1024 == 512L) 512 else 0
        // Inside archives we can't peek cheaply; iNES ROMs are 16 bytes over a 8 KiB multiple.
        "nes" -> if (size % 8192 == 16L) 16 else 0
        "fds" -> if (size % 65500 == 16L) 16 else 0
        else -> 0
    }

    private fun File.startsWith(magic: ByteArray): Boolean = runCatching {
        inputStream().use { s -> val b = ByteArray(magic.size); s.read(b) == magic.size && b.contentEquals(magic) }
    }.getOrDefault(false)

    // endregion

    companion object {
        private const val BUFFER = 1 shl 20
        /** Caps LZMA dictionary memory for 7z members (ultra-compressed sets can ask for 1 GiB+). */
        private const val SEVEN_Z_MEMORY_KB = 96 * 1024
        private const val ARCADE = "arcade"
        private val ARCHIVES = setOf("zip", "7z")
        private val COMPRESSED_IMAGES = setOf("rvz", "gcz", "wbfs", "cso", "ciso", "pbp", "ecm", "wia")
        private val SHEETS = setOf("cue", "gdi", "m3u", "ccd")
        private val INES = byteArrayOf(0x4E, 0x45, 0x53, 0x1A) // "NES\x1A"
        private val FWNES = byteArrayOf(0x46, 0x44, 0x53, 0x1A) // "FDS\x1A"

        fun crcHex(value: Long) = "%08x".format(value and 0xFFFFFFFFL)

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
