package dev.xross.launcher.scrape

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** A response whose body fit under the caller's size cap. */
class HttpResponse(val code: Int, val body: ByteArray, val contentType: String?) {
    fun text(): String = body.toString(Charsets.UTF_8)
    val isSuccess: Boolean get() = code in 200..299
}

/** Result of streaming a response straight to disk. */
data class DownloadResult(val code: Int, val bytes: Long, val contentType: String?)

/** Thrown when a body is larger than allowed; the partial download is discarded. */
class TooLargeException(limit: Long) : IOException("Response larger than $limit bytes")

/**
 * The scraper's only door to the network, so providers can be tested against canned responses.
 *
 * Implementations must refuse plain HTTP and must never put a request URL in an exception
 * message: ScreenScraper authenticates through query parameters, so URLs carry credentials.
 */
interface HttpClient {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap(), maxBytes: Long = DEFAULT_MAX): HttpResponse

    suspend fun post(
        url: String,
        body: ByteArray,
        contentType: String,
        headers: Map<String, String> = emptyMap(),
        maxBytes: Long = DEFAULT_MAX,
    ): HttpResponse

    /** Streams to [dest] (written in place; callers pass a temp file). */
    suspend fun download(url: String, dest: File, maxBytes: Long, headers: Map<String, String> = emptyMap()): DownloadResult

    companion object {
        const val DEFAULT_MAX = 4L * 1024 * 1024
    }
}

/**
 * [HttpClient] on [HttpURLConnection]: no extra dependency, HTTP/2-less but fine for a few
 * requests a second. Blocking I/O runs in [runInterruptible] on [Dispatchers.IO] so cancelling a
 * scrape (an emulator came to the front) interrupts a stalled read instead of waiting it out.
 */
class UrlConnectionHttp(private val userAgent: String) : HttpClient {

    override suspend fun get(url: String, headers: Map<String, String>, maxBytes: Long): HttpResponse =
        request("GET", url, null, null, headers, maxBytes)

    override suspend fun post(
        url: String,
        body: ByteArray,
        contentType: String,
        headers: Map<String, String>,
        maxBytes: Long,
    ): HttpResponse = request("POST", url, body, contentType, headers, maxBytes)

    override suspend fun download(url: String, dest: File, maxBytes: Long, headers: Map<String, String>): DownloadResult =
        runInterruptible(Dispatchers.IO) {
            val conn = open("GET", url, headers)
            try {
                val code = conn.responseCode
                if (code !in 200..299) return@runInterruptible DownloadResult(code, 0, conn.contentType)
                val declared = conn.contentLengthLong
                if (declared > maxBytes) throw TooLargeException(maxBytes)
                val written = conn.inputStream.use { input -> dest.outputStream().use { out -> copyCapped(input, out, maxBytes) } }
                DownloadResult(code, written, conn.contentType)
            } finally {
                conn.disconnect()
            }
        }

    private suspend fun request(
        method: String,
        url: String,
        body: ByteArray?,
        contentType: String?,
        headers: Map<String, String>,
        maxBytes: Long,
    ): HttpResponse = runInterruptible(Dispatchers.IO) {
        val conn = open(method, url, headers)
        try {
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", contentType)
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val bytes = stream?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                copyCapped(input, out, maxBytes)
                out.toByteArray()
            } ?: ByteArray(0)
            HttpResponse(code, bytes, conn.contentType)
        } finally {
            conn.disconnect()
        }
    }

    private fun open(method: String, url: String, headers: Map<String, String>): HttpURLConnection {
        val parsed = URL(url)
        if (parsed.protocol != "https") throw IOException("Refusing a non-HTTPS request to ${parsed.host}")
        return (parsed.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            // HttpURLConnection never follows a redirect from HTTPS to HTTP, so this stays HTTPS.
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept-Encoding", "identity")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
    }

    private fun copyCapped(input: InputStream, out: java.io.OutputStream, maxBytes: Long): Long {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > maxBytes) throw TooLargeException(maxBytes)
            out.write(buffer, 0, n)
        }
        return total
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
    }
}
