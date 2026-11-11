package dev.xross.launcher.storage

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.security.MessageDigest
import java.util.zip.CRC32

/** CRC32, MD5 and SHA-1 of a whole file, lower-case hex. */
data class FileHashes(val crc32: String, val md5: String, val sha1: String)

object FileHash {
    /**
     * Hashes [file] in one streaming pass; null when it is larger than [maxBytes] or unreadable.
     * Checks for cancellation between buffers, so a scrape stopped mid-file ends promptly.
     */
    suspend fun of(file: File, maxBytes: Long): FileHashes? {
        if (!file.isFile || file.length() > maxBytes) return null
        val crc = CRC32()
        val md5 = MessageDigest.getInstance("MD5")
        val sha1 = MessageDigest.getInstance("SHA-1")
        val buffer = ByteArray(256 * 1024)
        return try {
            file.inputStream().use { input ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    crc.update(buffer, 0, n)
                    md5.update(buffer, 0, n)
                    sha1.update(buffer, 0, n)
                }
            }
            FileHashes(Fingerprinter.crcHex(crc.value), md5.digest().hex(), sha1.digest().hex())
        } catch (_: java.io.IOException) {
            null
        }
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}
