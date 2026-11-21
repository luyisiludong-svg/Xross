package dev.xross.launcher.config

/**
 * Edits one object inside a hand-written JSON file as text, so the rest of the file (comments,
 * layout, key order, entries Xross didn't make) stays byte for byte as the user wrote it. Knows
 * strings, `//` and block comments and trailing commas: what [ConfigCodec.json] reads.
 */
internal object JsonSplice {

    private data class Member(val key: String, val start: Int, val valueStart: Int, val end: Int)

    private class Obj(val open: Int, val close: Int, val members: List<Member>)

    /**
     * In the top-level member [section] (an object), removes the members named in [drop] and
     * appends the ones [add] returns (each a full `"key": value` text, given the indent to use).
     * Null when the text isn't shaped that way; the caller then writes the whole file.
     */
    fun replaceMembers(text: String, section: String, drop: Set<String>, add: (indent: String) -> List<String>): String? {
        val (afterDrop, _) = runCatching { removeMembers(text, section, drop) }.getOrNull() ?: return null
        val obj = runCatching { sectionObject(afterDrop, section) }.getOrNull() ?: return null
        val indent = lineIndent(afterDrop, obj.open) + "  "
        val added = add(indent)
        if (added.isEmpty()) return afterDrop
        val joined = added.joinToString(",\n")
        return if (obj.members.isEmpty()) {
            afterDrop.substring(0, obj.open + 1) + "\n" + joined + "\n" + lineIndent(afterDrop, obj.open) +
                afterDrop.substring(obj.close)
        } else {
            val at = obj.members.last().end
            afterDrop.substring(0, at) + ",\n" + joined + afterDrop.substring(at)
        }
    }

    private fun removeMembers(text: String, section: String, drop: Set<String>): Pair<String, Int> {
        val obj = sectionObject(text, section) ?: error("no section")
        val m = obj.members
        if (drop.none { d -> m.any { it.key == d } }) return text to 0
        val dropped = m.map { it.key in drop }
        val firstKept = dropped.indexOfFirst { !it }
        val ranges = ArrayList<IntRange>()
        if (firstKept == -1) {
            var end = m.last().end
            val after = skip(text, end)
            if (after < text.length && text[after] == ',') end = after + 1
            ranges += m.first().start until end
        } else {
            if (firstKept > 0) ranges += m.first().start until m[firstKept].start
            for (i in firstKept + 1 until m.size) if (dropped[i]) ranges += m[i - 1].end until m[i].end
        }
        val out = StringBuilder(text)
        for (r in ranges.sortedByDescending { it.first }) out.delete(r.first, r.last + 1)
        return out.toString() to ranges.size
    }

    private fun sectionObject(text: String, section: String): Obj? {
        val root = skip(text, 0)
        if (root >= text.length || text[root] != '{') return null
        val top = parseObject(text, root)
        val sec = top.members.firstOrNull { it.key == section } ?: return null
        if (text[sec.valueStart] != '{') return null
        return parseObject(text, sec.valueStart)
    }

    private fun lineIndent(text: String, at: Int): String {
        val lineStart = text.lastIndexOf('\n', at - 1) + 1
        return text.substring(lineStart).takeWhile { it == ' ' || it == '\t' }
    }

    /** Parses the object whose `{` is at [open]. */
    private fun parseObject(text: String, open: Int): Obj {
        val members = ArrayList<Member>()
        var i = skip(text, open + 1)
        while (true) {
            require(i < text.length) { "unterminated object" }
            if (text[i] == '}') return Obj(open, i, members)
            require(text[i] == '"') { "expected a key at $i" }
            val keyEnd = endOfString(text, i)
            val key = unescape(text.substring(i + 1, keyEnd - 1))
            var j = skip(text, keyEnd)
            require(text[j] == ':') { "expected : at $j" }
            j = skip(text, j + 1)
            val valueEnd = endOfValue(text, j)
            members += Member(key, i, j, valueEnd)
            i = skip(text, valueEnd)
            if (i < text.length && text[i] == ',') i = skip(text, i + 1)
        }
    }

    private fun endOfValue(text: String, at: Int): Int = when (text[at]) {
        '"' -> endOfString(text, at)
        '{', '[' -> endOfNested(text, at)
        else -> {
            var i = at
            while (i < text.length && text[i] !in ",}] \t\r\n/") i++
            i
        }
    }

    private fun endOfNested(text: String, at: Int): Int {
        var depth = 0
        var i = at
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' -> { i = endOfString(text, i); continue }
                c == '/' && i + 1 < text.length && (text[i + 1] == '/' || text[i + 1] == '*') -> { i = skip(text, i); continue }
                c == '{' || c == '[' -> depth++
                c == '}' || c == ']' -> { depth--; if (depth == 0) return i + 1 }
            }
            i++
        }
        error("unterminated value")
    }

    /** Index just past the closing quote of the string starting at [at]. */
    private fun endOfString(text: String, at: Int): Int {
        var i = at + 1
        while (i < text.length) {
            when (text[i]) {
                '\\' -> i += 2
                '"' -> return i + 1
                else -> i++
            }
        }
        error("unterminated string")
    }

    /** Skips whitespace and comments from [at]. */
    private fun skip(text: String, at: Int): Int {
        var i = at
        while (i < text.length) {
            val c = text[i]
            when {
                c.isWhitespace() -> i++
                c == '/' && i + 1 < text.length && text[i + 1] == '/' -> {
                    while (i < text.length && text[i] != '\n') i++
                }
                c == '/' && i + 1 < text.length && text[i + 1] == '*' -> {
                    val end = text.indexOf("*/", i + 2)
                    i = if (end < 0) text.length else end + 2
                }
                else -> return i
            }
        }
        return i
    }

    private fun unescape(raw: String): String =
        if ('\\' !in raw) raw else runCatching { ConfigCodec.json.decodeFromString<String>("\"$raw\"") }.getOrDefault(raw)
}
