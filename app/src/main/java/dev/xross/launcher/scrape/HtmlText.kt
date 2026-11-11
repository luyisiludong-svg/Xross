package dev.xross.launcher.scrape

/**
 * Decodes HTML character references in text a metadata service sends as plain text (ScreenScraper
 * descriptions arrive with `&quot;`, `&amp;`, `&#039;`…). Named references cover the ones these
 * services use; numeric ones (`&#233;`, `&#xE9;`) cover the rest. Unknown or malformed references
 * are left as they are, so text that merely contains "&" is unchanged.
 */
object HtmlText {
    private val REFERENCE = Regex("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z]{2,8});")

    private val NAMED = mapOf(
        "quot" to "\"", "amp" to "&", "apos" to "'", "lt" to "<", "gt" to ">", "nbsp" to " ",
        "laquo" to "«", "raquo" to "»", "hellip" to "…", "mdash" to "—", "ndash" to "–",
        "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”", "copy" to "©", "reg" to "®",
        "trade" to "™", "eacute" to "é", "egrave" to "è", "agrave" to "à", "ccedil" to "ç",
    )

    fun decode(text: String): String {
        if ('&' !in text) return text
        // Two passes undo double encoding ("&amp;quot;"), which some API answers carry.
        var out = text
        repeat(2) {
            val next = REFERENCE.replace(out) { m ->
                val ref = m.groupValues[1]
                when {
                    ref.startsWith("#x") || ref.startsWith("#X") -> codePoint(ref.substring(2).toIntOrNull(16)) ?: m.value
                    ref.startsWith("#") -> codePoint(ref.substring(1).toIntOrNull()) ?: m.value
                    else -> NAMED[ref] ?: m.value
                }
            }
            if (next == out) return out
            out = next
        }
        return out
    }

    private fun codePoint(cp: Int?): String? =
        cp?.takeIf { it in 1..0x10FFFF && it !in 0xD800..0xDFFF }?.let { String(Character.toChars(it)) }
}
