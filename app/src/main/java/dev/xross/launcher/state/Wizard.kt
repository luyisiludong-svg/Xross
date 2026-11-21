package dev.xross.launcher.state

import androidx.compose.runtime.Immutable

/** What a wizard row does when confirmed. */
enum class WizardRowKind {
    /** Picks this value and usually moves on. */
    CHOICE,

    /** Opens the text prompt for a value. */
    TEXT,

    /** Runs something (Pick a folder…, Try it, Save). */
    ACTION,

    /** Shows something; Confirm does nothing. */
    INFO,
}

@Immutable
data class WizardRow(
    val key: String,
    val label: String,
    val detail: String? = null,
    val kind: WizardRowKind = WizardRowKind.CHOICE,
    /** A ticked choice (the current one, or one of several in a multi-choice step). */
    val selected: Boolean = false,
)

/** One page of a wizard as the screen draws it. */
@Immutable
data class WizardView(
    val title: String,
    val stepName: String,
    val step: Int,
    val steps: Int,
    val hint: String?,
    val rows: List<WizardRow>,
    val index: Int,
) {
    val focused: WizardRow? get() = rows.getOrNull(index)
}

/** A text field of a wizard, asked for with [TextPrompt.WizardField]. */
enum class WizardField(val title: String, val hint: String?) {
    SYSTEM_NAME("System name", "As it shows on the folder, like \"Xbox\""),
    SYSTEM_SHORT_NAME("Short name", "A few letters, like \"XBOX\""),
    SYSTEM_EXTENSIONS("File types", "Separated by commas, like iso, xiso"),
    EMULATOR_NAME("Emulator name", null),
    EXTRA_NAME("Extra name", "The name the emulator reads the path from, like \"bootPath\""),
    COMMAND("Launch command", "ES-DE syntax, like %ACTION%=android.intent.action.VIEW %DATA%=%ROMPROVIDER%"),
}

/** What a wizard asks its controller to do. */
sealed interface WizardEffect {
    data class Prompt(val field: WizardField, val initial: String) : WizardEffect
    data object PickFolder : WizardEffect
    data object PickLogo : WizardEffect
    /** The emulator wizard's step 2 needs the activities of the picked app. */
    data class LoadActivities(val packageName: String) : WizardEffect
    data object TryIt : WizardEffect
    data object Save : WizardEffect
    data object Delete : WizardEffect
    data object Cancel : WizardEffect
    data object Refuse : WizardEffect
}

/** Cursor helpers shared by the wizards. */
internal fun clampIndex(index: Int, rows: List<WizardRow>): Int = index.coerceIn(0, (rows.size - 1).coerceAtLeast(0))

/** A readable, unique id for a new entry: "Xbox Classic" → "xboxclassic", then "xboxclassic2". */
fun uniqueId(base: String, taken: Set<String>): String {
    val root = base.lowercase().filter { it.isLetterOrDigit() }.ifEmpty { "custom" }
    if (root !in taken) return root
    var n = 2
    while ("$root$n" in taken) n++
    return "$root$n"
}
