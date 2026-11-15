package dev.xross.launcher

import dev.xross.launcher.model.CategoryIds
import dev.xross.launcher.state.Categories
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The package layering of every module's main sources (docs/design/ARCHITECTURE_ASSESSMENT.md
 * §4–§6). Gradle already keeps `:core:model` and `:theme:engine` from seeing `:app`; this keeps
 * the packages still inside `:app` in the same order, so each later module split is a move, not
 * an untangling.
 *
 * Every reference to another `dev.xross.launcher` package counts: imports and fully qualified
 * names in code alike. Comments (KDoc links included) do not.
 */
class ArchitectureTest {
    /**
     * What each top-level package may use, besides itself. A package not listed here fails the
     * test, so a new one has to be placed in the graph on purpose.
     */
    private val allowed: Map<String, Set<String>> = run {
        val model = setOf("model")
        val base = model + "platform"
        val persistence = base + setOf("data", "config", "theme", "input", "storage")
        val features = setOf("audio", "launch", "scrape", "profile", "media")
        val belowState = persistence + features
        mapOf(
            // :core:model: depends on nothing.
            "model" to emptySet(),
            // :theme:engine, and its Android adapters in :app's theme/ until :theme:android exists: model only.
            "theme" to model,
            // Android SDK checks and other thin platform helpers.
            "platform" to model,
            "input" to model,
            "storage" to model,
            // One persistence layer (:core:data): settings may depend on the pack schema's
            // override types, never the other way round. `data → storage` (paths, the ROM scanner
            // and fingerprints) is split when :core:data is extracted.
            "data" to persistence,
            "config" to persistence,
            // Feature packages: persistence and below, never `state` or `ui`.
            "audio" to persistence,
            "launch" to persistence,
            "scrape" to persistence,
            "profile" to persistence + "scrape",
            "media" to persistence + "audio",
            // The state layer sees everything but the UI; the UI everything but the app shell.
            "state" to belowState,
            "ui" to belowState + "state",
            ROOT to belowState + setOf("state", "ui"),
        )
    }

    /** Which packages each module's main sources hold. */
    private val modulePackages = mapOf(
        ":core:model" to setOf("model"),
        ":theme:engine" to setOf("theme"),
    )

    /** The one Android import every engine file may have: `@Immutable` (annotation only). */
    private val engineAndroidAllowed = setOf("androidx.compose.runtime.Immutable")

    @Test fun packagesFollowTheLayering() {
        val violations = sources().flatMap { src ->
            val from = src.group
            val may = allowed[from] ?: return@flatMap listOf("${src.path}: package '$from' is not in the layering")
            src.references().filter { it.group != from && it.group !in may }
                .map { "${src.path}:${it.line}: $from → ${it.group} (${it.name})" }
        }
        assertTrue("Package edges outside the layering:\n" + violations.joinToString("\n"), violations.isEmpty())
    }

    @Test fun everyListedPackageExists() {
        val present = sources().mapTo(HashSet()) { it.group }
        assertEquals(emptySet<String>(), allowed.keys - present)
        assertEquals(emptySet<String>(), allowed.values.flatten().toSet() - present)
    }

    @Test fun modulesHoldOnlyTheirPackages() {
        val violations = sources().filter { it.module in modulePackages && it.group !in modulePackages.getValue(it.module) }
            .map { "${it.module} ${it.path}: package '${it.group}' belongs elsewhere" }
        assertTrue(violations.joinToString("\n"), violations.isEmpty())
        // What the modules hold has moved out of :app for good.
        val leftBehind = sources().filter { it.module == ":app" && it.group == "model" }.map { it.path }
        assertTrue("Still in :app: $leftBehind", leftBehind.isEmpty())
    }

    @Test fun theEngineStaysOffAndroid() {
        val violations = sources().filter { it.module == ":theme:engine" }.flatMap { src ->
            src.code.lineSequence().withIndex()
                .mapNotNull { (i, l) -> ANDROID.find(l)?.let { i + 1 to it.value } }
                .filter { (_, name) -> name !in engineAndroidAllowed }
                .map { (line, name) -> "${src.path}:$line: $name" }
                .toList()
        }
        assertTrue("Android or Compose UI in the theme engine:\n" + violations.joinToString("\n"), violations.isEmpty())
    }

    @Test fun categoryIdsMirrorTheMenuCategories() {
        assertEquals(CategoryIds.all, Categories.all.map { it.id })
    }

    private data class Reference(val group: String, val name: String, val line: Int)

    private class Source(val module: String, val path: String, val group: String, val code: String) {
        fun references(): List<Reference> = code.lineSequence().withIndex().flatMap { (i, l) ->
            REF.findAll(l).map { Reference(it.groupValues[1], it.value, i + 1) }
        }.toList()
    }

    /** The repository root: the test runs in `app/` under Gradle, at the root from an IDE. */
    private val repo: File by lazy {
        listOf(File(".."), File(".")).first { File(it, "settings.gradle.kts").isFile && File(it, "app").isDirectory }
    }

    /** Each module's main source root. */
    private val roots: Map<String, File> by lazy {
        mapOf(
            ":app" to "app/src/main/java",
            ":core:model" to "core/model/src/main/kotlin",
            ":theme:engine" to "theme/engine/src/main/kotlin",
        ).mapValues { (_, dir) -> File(repo, "$dir/dev/xross/launcher") }
            .onEach { (module, dir) -> check(dir.isDirectory) { "No sources for $module at $dir" } }
    }

    private val cached: List<Source> by lazy {
        roots.flatMap { (module, root) ->
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.map { f ->
                val path = f.relativeTo(root).invariantSeparatorsPath
                val group = if ('/' in path) path.substringBefore('/') else ROOT
                Source(module, path, group, stripComments(f.readText()))
            }.toList()
        }
    }

    private fun sources(): List<Source> = cached

    private companion object {
        const val ROOT = "(root)"
        val REF = Regex("""\bdev\.xross\.launcher\.([a-z]+)(?:\.[A-Za-z_][A-Za-z0-9_]*)+""")
        val ANDROID = Regex("""\b(?:android|androidx)\.[a-z0-9_.]*[A-Za-z_]+""")

        /** [text] with block and line comments blanked out, line breaks kept so line numbers hold. */
        fun stripComments(text: String): String {
            val out = StringBuilder(text.length)
            var i = 0
            var inString = false
            while (i < text.length) {
                val c = text[i]
                when {
                    inString -> {
                        out.append(c)
                        if (c == '\\' && i + 1 < text.length) { out.append(text[i + 1]); i++ } else if (c == '"') inString = false
                    }
                    text.startsWith("\"\"\"", i) -> {
                        val end = text.indexOf("\"\"\"", i + 3).let { if (it < 0) text.length else it + 3 }
                        out.append(text, i, end)
                        i = end
                        continue
                    }
                    c == '\'' -> {
                        // A char literal ('"', '\'', '/'): copied as it is.
                        val end = (if (text.getOrNull(i + 1) == '\\') text.indexOf('\'', i + 3) else text.indexOf('\'', i + 2))
                            .let { if (it < 0) i + 1 else it + 1 }
                        out.append(text, i, end)
                        i = end
                        continue
                    }
                    c == '"' -> { inString = true; out.append(c) }
                    text.startsWith("/*", i) -> {
                        val end = text.indexOf("*/", i + 2).let { if (it < 0) text.length else it + 2 }
                        text.substring(i, end).forEach { out.append(if (it == '\n') '\n' else ' ') }
                        i = end
                        continue
                    }
                    text.startsWith("//", i) -> {
                        val end = text.indexOf('\n', i).let { if (it < 0) text.length else it }
                        i = end
                        continue
                    }
                    else -> out.append(c)
                }
                i++
            }
            return out.toString()
        }
    }
}
