package dev.xross.launcher.data.dat

import java.util.Locale

/** What a No-Intro / Redump style name says beyond the title. */
data class NameTags(
    val title: String,
    /** "USA", "Europe", "Japan"…, in the order the name lists them. */
    val regions: List<String> = emptyList(),
    /** Language codes as written: "En", "Fr", "Zh-Hant"… */
    val languages: List<String> = emptyList(),
    /** "Rev 1", "v1.1"… */
    val revision: String? = null,
    /** Other tags: "Beta", "Proto", "Unl", "Disc 1"… */
    val flags: List<String> = emptyList(),
)

/**
 * Helpers for DAT naming conventions (No-Intro, Redump; MAME descriptions follow the same
 * "Title (extra)" shape).
 */
object DatNames {

    /** "Legend of Zelda, The - A Link to the Past (USA) (Rev 1)" → title, regions, revision. */
    fun parse(name: String): NameTags {
        val titleEnd = name.indexOf(" (").let { if (it < 0) name.indexOf(" [") else it }
        val rawTitle = (if (titleEnd < 0) name else name.substring(0, titleEnd)).trim()
        val regions = ArrayList<String>()
        val languages = ArrayList<String>()
        val flags = ArrayList<String>()
        var revision: String? = null
        for (match in TAG.findAll(name)) {
            val tag = match.groupValues[1].trim()
            val parts = tag.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            when {
                parts.isNotEmpty() && parts.all { it in REGIONS } -> parts.forEach { if (it !in regions) regions += it }
                parts.isNotEmpty() && parts.all { LANGUAGE.matches(it) } -> parts.forEach { if (it !in languages) languages += it }
                REVISION.matches(tag) && revision == null -> revision = tag
                else -> flags += tag
            }
        }
        return NameTags(displayTitle(rawTitle.ifEmpty { name.trim() }), regions, languages, revision, flags)
    }

    /**
     * Moves a trailing article back to the front, the way people read titles:
     * "Legend of Zelda, The - A Link to the Past" → "The Legend of Zelda - A Link to the Past".
     */
    fun displayTitle(title: String): String {
        val dash = title.indexOf(" - ")
        val head = if (dash < 0) title else title.substring(0, dash)
        val tail = if (dash < 0) "" else title.substring(dash)
        val m = TRAILING_ARTICLE.matchEntire(head) ?: return title
        return "${m.groupValues[2]} ${m.groupValues[1]}$tail"
    }

    /**
     * Title for a DAT game. MAME-style DATs name games by set ("sf2") and put the real title in
     * the description; No-Intro and Redump use the same text for both.
     */
    fun bestTitle(gameName: String, description: String?): NameTags {
        val useDescription = description != null && description.isNotBlank() &&
            (gameName.none { it == ' ' } && description.contains(' '))
        return parse(if (useDescription) description!! else gameName)
    }

    /**
     * Xross platform for a DAT's header name ("Nintendo - Super Nintendo Entertainment System",
     * "Sony - PlayStation", "MAME 0.264"). Null for systems Xross doesn't know.
     */
    fun platformFor(datName: String): String? {
        val n = datName.lowercase(Locale.ROOT)
            .replace(Regex("\\(.*?\\)"), " ") // "(Parent-Clone)", "(Headered)", dates
            .replace(Regex("\\s+"), " ")
            .trim()
        if (n.startsWith("mame") || n.contains("arcade") || n.startsWith("fbneo") || n.startsWith("final burn")) return "arcade"
        if (UNSUPPORTED.any { n.contains(it) }) return null
        return SYSTEMS.firstOrNull { (needle, _) -> n.contains(needle) }?.second
    }

    /** Systems whose names contain a supported one ("PlayStation 3" contains "PlayStation"). */
    private val UNSUPPORTED = listOf("playstation 3", "playstation 4", "playstation 5", "playstation vita", "wii u")

    // Longest, most specific names first: "game boy advance" must win over "game boy".
    private val SYSTEMS = listOf(
        "super nintendo entertainment system" to "snes",
        "super famicom" to "snes",
        "nintendo entertainment system" to "nes",
        "family computer disk system" to "nes",
        "famicom" to "nes",
        "nintendo 64" to "n64",
        "game boy advance" to "gba",
        "game boy color" to "gbc",
        "game boy" to "gb",
        "nintendo dsi" to "nds",
        "nintendo ds" to "nds",
        "new nintendo 3ds" to "n3ds",
        "nintendo 3ds" to "n3ds",
        "gamecube" to "gc",
        "nintendo - wii" to "wii",
        "nintendo switch" to "switch",
        "mega drive - genesis" to "megadrive",
        "mega drive" to "megadrive",
        "genesis" to "megadrive",
        "master system - mark iii" to "mastersystem",
        "master system" to "mastersystem",
        "game gear" to "gamegear",
        "dreamcast" to "dreamcast",
        "saturn" to "saturn",
        "pc engine" to "pcengine",
        "turbografx" to "pcengine",
        "playstation portable" to "psp",
        "playstation 2" to "ps2",
        "sony - playstation" to "psx",
        "scummvm" to "scummvm",
    )

    private val TAG = Regex("""\(([^()]*)\)""")
    private val LANGUAGE = Regex("""[A-Z][a-z](-[A-Z][a-z]+)?""")
    private val REVISION = Regex("""(Rev [0-9A-Z.]+|v[0-9][0-9A-Za-z.]*)""")
    private val TRAILING_ARTICLE = Regex("""(.+), (The|A|An|Le|La|Les|Der|Die|Das|El|Los|Las|Il)""")

    private val REGIONS = setOf(
        "World", "USA", "Europe", "Japan", "Asia", "Australia", "Brazil", "Canada", "China",
        "France", "Germany", "Hong Kong", "Italy", "Korea", "Netherlands", "Spain", "Sweden",
        "Taiwan", "UK", "United Kingdom", "Russia", "Scandinavia", "Denmark", "Finland", "Norway",
        "Poland", "Portugal", "Greece", "Mexico", "Argentina", "Latin America", "India",
        "Ireland", "Israel", "New Zealand", "South Africa", "Switzerland", "Austria", "Belgium",
        "Croatia", "Turkey", "Unknown",
    )
}
