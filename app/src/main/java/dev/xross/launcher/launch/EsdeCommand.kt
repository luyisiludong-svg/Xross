package dev.xross.launcher.launch

/**
 * ES-DE's Android launch `command` syntax, minus the `%EMULATOR_X%` prefix (that part is a custom
 * emulator's `packages` and `activity`). Pure: [parse] turns the text into a [EsdeRecipe], and
 * [EsdeRecipe.resolve] fills in one game's values; turning that into an Intent is
 * [RecipeIntents]' job.
 *
 * | Tag | Meaning |
 * | --- | --- |
 * | `%ACTION%=x`, `%CATEGORY%=x` | Intent action, category |
 * | `%DATA%=v` | Intent data |
 * | `%EXTRA_k%=v`, `%EXTRABOOL_k%=v`, `%EXTRAINTEGER_k%=v`, `%EXTRAARRAY_k%=a,b` | Typed extras |
 * | `%ACTIVITY_CLEAR_TASK%`, `%ACTIVITY_CLEAR_TOP%`, `%ACTIVITY_NO_HISTORY%` | Intent flags |
 * | `%INJECT%=file` | More tags read from that file beside the ROM (ES-DE's injection) |
 *
 * Values may use `%ROM%` (absolute path), `%ROMPROVIDER%` / `%ROMSAF%` (Xross's FileProvider URI
 * with a read grant; Xross has no SAF tree, so both map here), `%BASENAME%`, `%FILENAME%`,
 * `%ROMPATH%` (the ROM's folder), `%EXTERNALDATA%` and `%ANDROIDPACKAGE%`. Anything else is a
 * problem for that entry, never for the whole file.
 */
object EsdeCommand {

    /** Placeholders allowed inside values. */
    val VALUE_TAGS = listOf(
        "%ROM%", "%ROMPROVIDER%", "%ROMSAF%", "%BASENAME%", "%FILENAME%", "%ROMPATH%", "%EXTERNALDATA%", "%ANDROIDPACKAGE%",
    )

    const val EXTERNAL_DATA = "/storage/emulated/0"

    private val FLAGS = mapOf(
        "%ACTIVITY_CLEAR_TASK%" to IntentFlag.CLEAR_TASK,
        "%ACTIVITY_CLEAR_TOP%" to IntentFlag.CLEAR_TOP,
        "%ACTIVITY_NO_HISTORY%" to IntentFlag.NO_HISTORY,
    )

    /** Result of [parse]: a recipe, and every problem found (an empty list means it is usable). */
    data class Parsed(val recipe: EsdeRecipe, val problems: List<String>) {
        val ok: Boolean get() = problems.isEmpty()
    }

    /**
     * Splits [command] into tags. A token that doesn't start with `%` continues the previous
     * value, so a value may hold spaces (`%EXTRA_title%=Some Game`).
     */
    fun tokens(command: String): List<String> {
        val out = ArrayList<String>()
        for (word in command.trim().split(Regex("\\s+"))) {
            if (word.isEmpty()) continue
            if (word.startsWith("%") || out.isEmpty()) out += word else out[out.lastIndex] = out.last() + " " + word
        }
        return out
    }

    fun parse(command: String): Parsed {
        var action: String? = null
        val categories = ArrayList<String>()
        var data: String? = null
        val extras = ArrayList<EsdeExtra>()
        val flags = LinkedHashSet<IntentFlag>()
        val injects = ArrayList<String>()
        val problems = ArrayList<String>()

        for (token in tokens(command)) {
            FLAGS[token]?.let { flags += it; continue }
            val eq = token.indexOf("%=")
            if (eq < 0) {
                problems += if (token.startsWith("%EMULATOR_")) {
                    "$token: leave it out; packages and activity say which app runs"
                } else {
                    "Unknown tag $token"
                }
                continue
            }
            val tag = token.substring(0, eq + 1)
            val value = token.substring(eq + 2)
            checkValue(value)?.let { problems += "$tag: $it" }
            when {
                tag == "%ACTION%" -> action = value
                tag == "%CATEGORY%" -> categories += value
                tag == "%DATA%" -> data = value
                tag == "%INJECT%" -> injects += value
                tag.startsWith("%EXTRABOOL_") -> {
                    val key = tag.removeSurrounding("%EXTRABOOL_", "%")
                    if (value.lowercase() != "true" && value.lowercase() != "false") problems += "$tag: expects true or false, not \"$value\""
                    extras += EsdeExtra(key, ExtraType.BOOL, value)
                }
                tag.startsWith("%EXTRAINTEGER_") -> {
                    val key = tag.removeSurrounding("%EXTRAINTEGER_", "%")
                    if (value.toIntOrNull() == null) problems += "$tag: expects a whole number, not \"$value\""
                    extras += EsdeExtra(key, ExtraType.INT, value)
                }
                tag.startsWith("%EXTRAARRAY_") -> extras += EsdeExtra(tag.removeSurrounding("%EXTRAARRAY_", "%"), ExtraType.ARRAY, value)
                tag.startsWith("%EXTRA_") -> extras += EsdeExtra(tag.removeSurrounding("%EXTRA_", "%"), ExtraType.STRING, value)
                else -> problems += "Unknown tag $tag"
            }
        }
        if (extras.any { it.key.isBlank() }) problems += "An extra has no name"
        return Parsed(EsdeRecipe(action, categories, data, extras, flags, injects), problems)
    }

    /** Why a value can't be used (an unknown `%TAG%` inside it), or null. */
    private fun checkValue(value: String): String? {
        val unknown = Regex("%[A-Z_]+%").findAll(value).map { it.value }.filter { it !in VALUE_TAGS }.toList()
        return if (unknown.isEmpty()) null else "unknown value ${unknown.joinToString()}"
    }
}

enum class ExtraType { STRING, BOOL, INT, ARRAY }

enum class IntentFlag { CLEAR_TASK, CLEAR_TOP, NO_HISTORY }

data class EsdeExtra(val key: String, val type: ExtraType, val value: String)

/** One game's values for the placeholders. [providerUri] is the `content://` URI as text. */
data class RomValues(
    val path: String,
    val providerUri: String,
    val packageName: String,
) {
    private val file = java.io.File(path)

    fun fill(value: String): String = value
        .replace("%ROMPROVIDER%", providerUri)
        .replace("%ROMSAF%", providerUri)
        .replace("%ROMPATH%", file.parent.orEmpty())
        .replace("%ROM%", path)
        .replace("%BASENAME%", file.nameWithoutExtension)
        .replace("%FILENAME%", file.name)
        .replace("%EXTERNALDATA%", EsdeCommand.EXTERNAL_DATA)
        .replace("%ANDROIDPACKAGE%", packageName)
}

/** A parsed `command`. [injects] are file names (with placeholders) whose contents add more tags. */
data class EsdeRecipe(
    val action: String? = null,
    val categories: List<String> = emptyList(),
    val data: String? = null,
    val extras: List<EsdeExtra> = emptyList(),
    val flags: Set<IntentFlag> = emptySet(),
    val injects: List<String> = emptyList(),
) {
    /**
     * Fills in one game's values. [readInject] returns the text of an `%INJECT%` file (null when it
     * isn't there, which ES-DE also skips quietly); its tags are merged in, last one wins.
     */
    fun resolve(values: RomValues, readInject: (String) -> String? = { null }): ResolvedLaunch {
        var recipe = this
        for (name in injects) {
            val text = readInject(values.fill(name)) ?: continue
            val more = EsdeCommand.parse(text).recipe
            recipe = recipe.copy(
                action = more.action ?: recipe.action,
                categories = recipe.categories + more.categories,
                data = more.data ?: recipe.data,
                extras = recipe.extras + more.extras,
                flags = recipe.flags + more.flags,
            )
        }
        val usesProvider = listOfNotNull(recipe.data).plus(recipe.extras.map { it.value })
            .any { it.contains("%ROMPROVIDER%") || it.contains("%ROMSAF%") }
        return ResolvedLaunch(
            action = recipe.action,
            categories = recipe.categories,
            data = recipe.data?.let(values::fill),
            dataIsProvider = recipe.data == "%ROMPROVIDER%" || recipe.data == "%ROMSAF%",
            extras = recipe.extras.map { it.copy(value = values.fill(it.value)) },
            flags = recipe.flags,
            grantsRead = usesProvider,
        )
    }
}

/** A recipe with one game's values in it, ready for [RecipeIntents]. */
data class ResolvedLaunch(
    val action: String?,
    val categories: List<String>,
    val data: String?,
    /** The data is exactly the FileProvider URI, so it goes with the file's MIME type. */
    val dataIsProvider: Boolean,
    val extras: List<EsdeExtra>,
    val flags: Set<IntentFlag>,
    /** The FileProvider URI appears somewhere, so the intent carries a read grant for it. */
    val grantsRead: Boolean,
)
