package dev.xross.launcher.data.dat

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.PushbackReader
import java.io.Reader
import java.io.StringReader
import java.util.Locale
import javax.xml.parsers.SAXParserFactory

/** DAT header: who made it and for which system. */
data class DatHeader(
    val name: String,
    val description: String? = null,
    val version: String? = null,
    /** Homepage or author, e.g. "No-Intro", "redump.org". */
    val source: String? = null,
)

/** One ROM/track entry. Hashes are lower-case hex; any of them may be missing. */
data class DatRom(
    val name: String,
    val size: Long?,
    val crc32: String?,
    val md5: String?,
    val sha1: String?,
    val serial: String? = null,
)

/** One game (or MAME machine) and its ROMs. */
data class DatGame(
    val name: String,
    val description: String?,
    val serial: String?,
    val roms: List<DatRom>,
)

class DatFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Streaming reader for the two DAT formats ROM managers use:
 *
 *  - **Logiqx XML** (No-Intro, Redump, MAME `-listxml`-style `machine` entries).
 *  - **clrmamepro text** (`clrmamepro ( … ) game ( name "…" rom ( name … crc … ) )`).
 *
 * The format is detected from the first non-blank character. Games are handed to [onGame] one
 * at a time, so a 50 MB Redump DAT never sits in memory.
 *
 * XML hardening: DOCTYPEs are allowed (every Logiqx DAT declares one) but no DTD or external
 * entity is ever loaded, so parsing a DAT can't touch the network or the file system.
 */
object DatParser {

    /** Parses [input]; returns the header (synthesized from [fallbackName] if the DAT has none). */
    fun parse(input: InputStream, fallbackName: String, onGame: (DatGame) -> Unit): DatHeader {
        val buffered = BufferedInputStream(input)
        buffered.mark(4096)
        val sniff = ByteArray(4096)
        val read = buffered.read(sniff).coerceAtLeast(0)
        buffered.reset()
        val first = String(sniff, 0, read, Charsets.UTF_8).trimStart('﻿', ' ', '\t', '\r', '\n').firstOrNull()
        return when (first) {
            '<' -> parseXml(buffered, fallbackName, onGame)
            null -> throw DatFormatException("Empty file")
            else -> parseClrMamePro(buffered.reader(Charsets.UTF_8), fallbackName, onGame)
        }
    }

    // region Logiqx XML

    private fun parseXml(input: InputStream, fallbackName: String, onGame: (DatGame) -> Unit): DatHeader {
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = false
            isValidating = false
        }
        // Not every platform parser supports every feature (Android's Expat-based one rejects some);
        // the entity resolver below is the guarantee, these are defense in depth.
        for ((feature, value) in XML_FEATURES) runCatching { factory.setFeature(feature, value) }
        val handler = LogiqxHandler(onGame)
        try {
            val reader = factory.newSAXParser().xmlReader
            reader.entityResolver = org.xml.sax.EntityResolver { _, _ -> InputSource(StringReader("")) }
            reader.contentHandler = handler
            reader.errorHandler = handler
            reader.parse(InputSource(input))
        } catch (e: SAXException) {
            throw DatFormatException("Not a valid DAT: ${e.message}", e)
        }
        if (!handler.sawDatafile) throw DatFormatException("Not a Logiqx DAT (no <datafile> element)")
        return handler.header(fallbackName)
    }

    private class LogiqxHandler(private val onGame: (DatGame) -> Unit) : DefaultHandler() {
        var sawDatafile = false
        private val text = StringBuilder()
        private var inHeader = false
        private val headerFields = HashMap<String, String>()

        private var gameName: String? = null
        private var gameDescription: String? = null
        private var gameSerial: String? = null
        private val roms = ArrayList<DatRom>()

        fun header(fallback: String) = DatHeader(
            name = headerFields["name"]?.takeIf { it.isNotBlank() } ?: fallback,
            description = headerFields["description"],
            version = headerFields["version"],
            source = headerFields["homepage"] ?: headerFields["author"] ?: headerFields["url"],
        )

        override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
            text.setLength(0)
            when (qName) {
                "datafile", "mame" -> sawDatafile = true
                "header" -> inHeader = true
                "game", "machine", "software" -> {
                    gameName = attributes.getValue("name")
                    gameDescription = null
                    gameSerial = null
                    roms.clear()
                }
                "rom", "disk" -> if (gameName != null) {
                    // Nodump entries have no hashes to match against.
                    if (attributes.getValue("status") == "nodump") return
                    roms += DatRom(
                        name = attributes.getValue("name") ?: "",
                        size = attributes.getValue("size")?.trim()?.toLongOrNull(),
                        crc32 = hex(attributes.getValue("crc"), 8),
                        md5 = hex(attributes.getValue("md5"), 32),
                        sha1 = hex(attributes.getValue("sha1"), 40),
                        serial = attributes.getValue("serial")?.trim()?.ifEmpty { null },
                    )
                }
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            text.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            val value = text.toString().trim()
            when {
                qName == "header" -> inHeader = false
                inHeader && value.isNotEmpty() -> headerFields.putIfAbsent(qName, value)
                qName == "description" && gameName != null -> gameDescription = value.ifEmpty { null }
                qName == "serial" && gameName != null -> gameSerial = value.ifEmpty { null }
                qName == "game" || qName == "machine" || qName == "software" -> {
                    val name = gameName
                    if (name != null && roms.isNotEmpty()) {
                        onGame(DatGame(name, gameDescription, gameSerial, roms.toList()))
                    }
                    gameName = null
                    roms.clear()
                }
            }
            text.setLength(0)
        }

        override fun warning(e: org.xml.sax.SAXParseException) = Unit
    }

    // endregion

    // region clrmamepro

    private fun parseClrMamePro(reader: Reader, fallbackName: String, onGame: (DatGame) -> Unit): DatHeader {
        val tokens = Tokenizer(reader)
        var header: DatHeader? = null
        var sawBlock = false
        while (true) {
            val keyword = tokens.next() ?: break
            if (tokens.next() != "(") throw DatFormatException("Expected '(' after '$keyword'")
            sawBlock = true
            val block = readBlock(tokens)
            when (keyword.lowercase(Locale.ROOT)) {
                "clrmamepro", "emulator" -> header = DatHeader(
                    name = block.value("name") ?: fallbackName,
                    description = block.value("description"),
                    version = block.value("version"),
                    source = block.value("homepage") ?: block.value("author") ?: block.value("url"),
                )
                "game", "machine", "resource" -> {
                    val name = block.value("name") ?: continue
                    val roms = block.children("rom").mapNotNull { rom ->
                        if (rom.value("flags") == "nodump") return@mapNotNull null
                        DatRom(
                            name = rom.value("name") ?: "",
                            size = rom.value("size")?.toLongOrNull(),
                            crc32 = hex(rom.value("crc"), 8),
                            md5 = hex(rom.value("md5"), 32),
                            sha1 = hex(rom.value("sha1"), 40),
                            serial = rom.value("serial"),
                        )
                    }
                    if (roms.isNotEmpty()) onGame(DatGame(name, block.value("description"), block.value("serial"), roms))
                }
            }
        }
        if (!sawBlock) throw DatFormatException("Not a clrmamepro DAT")
        return header ?: DatHeader(fallbackName)
    }

    /** A parenthesized block: key/value pairs and nested blocks, in order. */
    private class Block(val entries: List<Pair<String, Any>>) {
        fun value(key: String): String? =
            entries.firstOrNull { it.first.equals(key, ignoreCase = true) && it.second is String }?.second as String?

        fun children(key: String): List<Block> =
            entries.filter { it.first.equals(key, ignoreCase = true) && it.second is Block }.map { it.second as Block }
    }

    private fun readBlock(tokens: Tokenizer): Block {
        val entries = ArrayList<Pair<String, Any>>()
        while (true) {
            val key = tokens.next() ?: throw DatFormatException("Unclosed block")
            if (key == ")") return Block(entries)
            val value = tokens.next() ?: throw DatFormatException("Missing value for '$key'")
            entries += if (value == "(") key to readBlock(tokens) else key to value
        }
    }

    /** Splits on whitespace; "(" and ")" are tokens; double-quoted strings may hold spaces. */
    private class Tokenizer(source: Reader) {
        private val reader = PushbackReader(source.buffered(), 1)

        fun next(): String? {
            var c = reader.read()
            while (c != -1 && c.toChar().isWhitespace()) c = reader.read()
            if (c == -1) return null
            val ch = c.toChar()
            if (ch == '(' || ch == ')') return ch.toString()
            val out = StringBuilder()
            if (ch == '"') {
                while (true) {
                    val q = reader.read()
                    if (q == -1 || q.toChar() == '"') break
                    out.append(q.toChar())
                }
                return out.toString()
            }
            out.append(ch)
            while (true) {
                val n = reader.read()
                if (n == -1) break
                val nc = n.toChar()
                if (nc.isWhitespace()) break
                if (nc == '(' || nc == ')') {
                    reader.unread(n)
                    break
                }
                out.append(nc)
            }
            return out.toString()
        }
    }

    // endregion

    /** Normalizes a hash: lower case, no "0x", left-padded CRCs; null when it isn't [length] hex digits. */
    internal fun hex(raw: String?, length: Int): String? {
        val v = raw?.trim()?.lowercase(Locale.ROOT)?.removePrefix("0x") ?: return null
        if (v.isEmpty() || v.length > length || v.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
        return v.padStart(length, '0')
    }

    private val XML_FEATURES = listOf(
        "http://xml.org/sax/features/external-general-entities" to false,
        "http://xml.org/sax/features/external-parameter-entities" to false,
        "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
        "http://xml.org/sax/features/validation" to false,
    )
}
