package dev.xross.launcher.theme.shader

import androidx.compose.runtime.Immutable
import java.security.MessageDigest
import java.util.Locale

/**
 * Background programs (Theme 2.0, 2b.4a). A pack picks one with `effects.shaderPreset` in its base,
 * variants, overrides or context rules; [CUSTOM] reads a GLSL ES 3.0 fragment shader from the pack
 * (`shader.frag`, or `effects.shaderPath`). Built-in sources live in `assets/shaders/`.
 */
enum class ShaderPreset(val file: String?, val label: String) {
    /** The built-in glossy wave; always compiled, and what every failure falls back to. */
    WAVE("wave.frag", "Wave"),
    ASCII_MATRIX("ascii_matrix.frag", "ASCII matrix"),
    BOKEH("bokeh.frag", "Bokeh"),
    STARFIELD("starfield.frag", "Starfield"),
    CRT("crt.frag", "CRT"),
    CUSTOM(null, "Custom shader"),
    ;

    companion object {
        /** `ASCII_MATRIX`, `ascii-matrix` or `Ascii Matrix`; null for anything else. */
        fun byName(text: String?): ShaderPreset? {
            val t = text?.trim()?.replace('-', '_')?.replace(' ', '_')?.takeIf { it.isNotEmpty() } ?: return null
            return entries.firstOrNull { it.name.equals(t, ignoreCase = true) }
        }

        /** Names for error messages. */
        val NAMES: String get() = entries.joinToString { it.name }
    }
}

/** A background program a theme layer asks for: a preset, or for [ShaderPreset.CUSTOM] a pack-relative file. */
@Immutable
data class ShaderRef(val preset: ShaderPreset, val path: String = "") {
    companion object {
        val WAVE = ShaderRef(ShaderPreset.WAVE)

        /** What a CUSTOM shader without `shaderPath` reads. */
        const val DEFAULT_FILE = "shader.frag"

        val FILE_TYPES = setOf("frag", "glsl", "fs")

        /**
         * `effects.shaderPreset` / `effects.shaderPath` of one theme layer, or null when it sets neither
         * (or names an unknown preset, reported in [problems] under [where]). A path alone means
         * CUSTOM; CUSTOM alone reads `shader.frag`.
         */
        fun of(where: String, shader: String?, shaderPath: String?, problems: MutableList<String>): ShaderRef? {
            val name = shader?.trim().orEmpty()
            val path = shaderPath?.trim()?.replace('\\', '/').orEmpty()
            if (name.isEmpty() && path.isEmpty()) return null
            val preset = if (name.isEmpty()) ShaderPreset.CUSTOM else ShaderPreset.byName(name) ?: run {
                problems += "$where.effects.shaderPreset: no shader named $name (use ${ShaderPreset.NAMES})"
                return null
            }
            if (preset != ShaderPreset.CUSTOM) {
                if (path.isNotEmpty()) problems += "$where.effects.shaderPath is only read with \"shaderPreset\": \"CUSTOM\""
                return ShaderRef(preset)
            }
            val file = path.ifEmpty { DEFAULT_FILE }
            if (file.substringAfterLast('.', "").lowercase(Locale.ROOT) !in FILE_TYPES || file.split('/').any { it == ".." } || file.startsWith("/")) {
                problems += "$where.effects.shaderPath: use a .frag file inside the pack (not $file)"
                return null
            }
            return ShaderRef(ShaderPreset.CUSTOM, file)
        }
    }
}

/**
 * One program the renderer may compile. [key] names it: the preset for built-ins, the file and a
 * digest of its text for a custom shader, so an edit is a new program (and a shader blocked after
 * a crash is tried again once it changes).
 */
@Immutable
data class ShaderSpec(
    val key: String,
    val preset: ShaderPreset,
    val label: String,
    /** CUSTOM: the file's text, null when it can't be read. Built-ins: null (read from assets). */
    val source: String? = null,
) {
    /** A pack's own code: canary-guarded before it is compiled and drawn. */
    val untrusted: Boolean get() = preset == ShaderPreset.CUSTOM
}

/** Every program a pack can show, compiled ahead of time when it loads; WAVE always first. */
@Immutable
data class ShaderLibrary(val specs: List<ShaderSpec>) {
    fun spec(key: String): ShaderSpec? = specs.firstOrNull { it.key == key }

    /** The key [ref] is compiled under here (a CUSTOM spec's label is its path); WAVE when the library doesn't have it. */
    fun keyOf(ref: ShaderRef): String =
        specs.firstOrNull { it.preset == ref.preset && (ref.preset != ShaderPreset.CUSTOM || it.label == ref.path) }?.key ?: WAVE_KEY

    val keys: Set<String> get() = specs.mapTo(LinkedHashSet()) { it.key }

    companion object {
        const val WAVE_KEY = "WAVE"
        val WAVE_SPEC = ShaderSpec(WAVE_KEY, ShaderPreset.WAVE, ShaderPreset.WAVE.label)
        val DEFAULT = ShaderLibrary(listOf(WAVE_SPEC))

        /** [refs] as specs; CUSTOM text comes from [customSources] (pack-relative path → text). */
        fun of(refs: Collection<ShaderRef>, customSources: Map<String, String>): ShaderLibrary {
            val out = LinkedHashMap<String, ShaderSpec>()
            out[WAVE_KEY] = WAVE_SPEC
            for (ref in refs) {
                val spec = when (ref.preset) {
                    ShaderPreset.CUSTOM -> {
                        val text = customSources[ref.path]
                        ShaderSpec("custom:${ref.path}:${text?.let(::digest) ?: "missing"}", ShaderPreset.CUSTOM, ref.path, text)
                    }
                    else -> ShaderSpec(ref.preset.name, ref.preset, ref.preset.label)
                }
                out.putIfAbsent(spec.key, spec)
            }
            return ShaderLibrary(out.values.toList())
        }

        /** First 12 hex digits of the SHA-1 of [text]. */
        fun digest(text: String): String =
            MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).take(6).joinToString("") { "%02x".format(it) }
    }
}

/** Fragment source as the driver sees it: the prelude in front unless the file brings its own `#version`. */
data class ComposedShader(val text: String, val lineOffset: Int)

object ShaderSources {
    /** [body] behind [prelude], or [body] alone when it starts with `#version` (it then declares its own uniforms). */
    fun compose(prelude: String, body: String): ComposedShader {
        if (body.trimStart().startsWith("#version")) return ComposedShader(body, 0)
        val head = if (prelude.endsWith("\n")) prelude else prelude + "\n"
        return ComposedShader(head + body, head.count { it == '\n' })
    }

    private val LOCATED = Regex("""^\s*(?:ERROR|WARNING)?:?\s*\d+:(\d+):\s*(.*)$""")

    /**
     * The first error of a driver's info log, its line number relative to the file that follows
     * the prelude ([lineOffset] lines): `line 12: 'foo' : undeclared identifier`. Adreno, Mali,
     * ANGLE and glslang all report `0:<line>:`; anything else is passed through, trimmed.
     */
    fun firstError(log: String?, lineOffset: Int = 0): String {
        val lines = log.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return "the driver gave no reason"
        val line = lines.firstOrNull { it.startsWith("ERROR", ignoreCase = true) }
            ?: lines.firstOrNull { !it.startsWith("WARNING", ignoreCase = true) } ?: lines.first()
        val m = LOCATED.matchEntire(line)
        val text = if (m != null) {
            val n = m.groupValues[1].toInt() - lineOffset
            val msg = m.groupValues[2].trim()
            if (n >= 1) "line $n: $msg" else msg
        } else {
            line.removePrefix("ERROR:").trim()
        }
        return text.take(MAX_ERROR)
    }

    private const val MAX_ERROR = 140
}

/** How a program stands on the GL thread. */
enum class ProgramState { PENDING, READY, FAILED }

/**
 * Which program to draw (2b.4a): the one wanted once it is linked; until then the one on screen
 * (so a category switch never shows a gap or a hitch); WAVE when the wanted one failed, or
 * nothing else is ready.
 */
object ShaderFallback {
    fun choose(desired: String, current: String?, state: (String) -> ProgramState?): String = when (state(desired)) {
        ProgramState.READY -> desired
        ProgramState.FAILED -> ShaderLibrary.WAVE_KEY
        ProgramState.PENDING, null ->
            if (current != null && current != desired && state(current) == ProgramState.READY) current else ShaderLibrary.WAVE_KEY
    }

    /** The status to report for [desired]. */
    fun status(spec: ShaderSpec?, desired: String, state: ProgramState?, error: String, cause: ShaderStatus.Cause?): ShaderStatus {
        val label = spec?.label ?: desired
        return when (state) {
            ProgramState.READY -> ShaderStatus.Ready(desired, label)
            ProgramState.FAILED -> ShaderStatus.Failed(desired, label, error, cause ?: ShaderStatus.Cause.COMPILE)
            ProgramState.PENDING, null -> ShaderStatus.Compiling(desired, label)
        }
    }
}

/** What the background shows, for Settings and toasts. */
sealed interface ShaderStatus {
    val key: String
    val label: String

    data class Ready(override val key: String, override val label: String) : ShaderStatus
    data class Compiling(override val key: String, override val label: String) : ShaderStatus
    data class Failed(override val key: String, override val label: String, val error: String, val cause: Cause) : ShaderStatus {
        /** The toast: what failed and why, and that the wave is shown instead. */
        val message: String get() = when (cause) {
            Cause.COMPILE, Cause.LINK -> "Shader $label: $error · showing the wave"
            Cause.MISSING -> "Shader $label: $error · showing the wave"
            Cause.TOO_SLOW -> "Shader $label is too slow here ($error) · showing the wave"
            Cause.CRASHED -> "Shader $label stopped Xross last time · showing the wave"
        }
    }

    enum class Cause { COMPILE, LINK, MISSING, TOO_SLOW, CRASHED }
}
