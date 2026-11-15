package dev.xross.launcher.model

import java.util.Locale

/**
 * The rule for app folder names (Apps ▸ Move to folder), shared by the folder logic in `state/`
 * and the `settings.json` reader in `config/`: the preset names and how a typed name is cleaned.
 */
object FolderNames {
    const val EMULATORS = "Emulators"
    val PRESETS = listOf(EMULATORS, "Streaming", "Tools")
    const val MAX_NAME = 24

    /**
     * A folder name as typed: trimmed, inner whitespace collapsed, at most [MAX_NAME] characters.
     * The gamepad keyboard types capitals, so all-caps words become title case ("MY TOOLS" →
     * "My Tools") except short ones that read as acronyms ("TV", "NES"), and a name equal to a
     * preset in another case becomes that preset. Null when nothing is left.
     */
    fun normalize(raw: String): String? {
        val collapsed = raw.trim().replace(Regex("\\s+"), " ").take(MAX_NAME).trim()
        if (collapsed.isEmpty()) return null
        PRESETS.firstOrNull { it.equals(collapsed, ignoreCase = true) }?.let { return it }
        return collapsed.split(' ').joinToString(" ") { word ->
            val allCaps = word == word.uppercase(Locale.ROOT) && word.any { it.isLetter() }
            // Short all-caps words are usually acronyms (TV, NES, PS2) and stay as typed.
            val titled = allCaps && (word.length >= 4 || word.uppercase(Locale.ROOT) in SHORT_WORDS)
            if (titled) word.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) } else word
        }
    }

    private val SHORT_WORDS = setOf("A", "AN", "AND", "FOR", "IN", "MY", "OF", "ON", "OR", "THE", "TO", "BY", "ALL", "NEW", "OLD", "APP", "FUN")
}
