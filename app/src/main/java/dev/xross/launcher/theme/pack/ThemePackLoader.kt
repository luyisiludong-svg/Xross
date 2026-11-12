package dev.xross.launcher.theme.pack

import android.content.res.AssetManager
import android.graphics.BitmapFactory
import android.graphics.Typeface
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import dev.xross.launcher.state.GlyphKey
import dev.xross.launcher.theme.ThemePalette
import dev.xross.launcher.theme.shader.ShaderLibrary
import dev.xross.launcher.theme.shader.ShaderPreset
import dev.xross.launcher.theme.shader.ShaderRef
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.io.File
import java.io.IOException
import java.util.Locale

/** Where a pack's files come from. */
sealed interface PackSource {
    /** Shipped inside the APK under `assets/<dir>`. */
    data class Builtin(val dir: String) : PackSource

    /** A folder in `Xross/themes/`. */
    data class Folder(val dir: File) : PackSource
}

/** A discovered pack: its id (folder name), manifest and location. */
@Immutable
data class ThemePackInfo(val id: String, val manifest: ThemeManifest, val source: PackSource) {
    val builtIn: Boolean get() = source is PackSource.Builtin
    val name: String get() = manifest.name
}

/** A sound file of a pack. */
sealed interface SoundSource {
    data class Asset(val path: String) : SoundSource
    data class Disk(val file: File) : SoundSource
}

/** A pack icon: vector (SVG) or bitmap (PNG/WebP). */
sealed interface ThemeIcon {
    data class Vector(val image: ImageVector) : ThemeIcon
    data class Bitmap(val image: ImageBitmap) : ThemeIcon
}

/**
 * A pack resolved into things the UI and the audio engine use. Every role, icon and default is
 * filled: what the pack lacks comes from the Default pack, so a pack may ship only a font, or only
 * a cursor sound.
 */
@Immutable
data class LoadedTheme(
    val info: ThemePackInfo,
    /** The pack's own colors, offered as the "Theme pack" color option; null when it has none. */
    val palette: ThemePalette?,
    val wave: ThemeManifest.WaveDefaults?,
    val icons: Map<GlyphKey, ThemeIcon>,
    val fonts: ThemeFonts,
    val sounds: Map<SoundRole, SoundSource>,
    val gains: Map<SoundRole, Float>,
    /** Every surface available: the Default pack's presets, then this pack's own. */
    val surfaces: Map<String, Surface> = emptyMap(),
    /** The pack's `effects.surfaceStyle` (or the inherited one). */
    val surfaceId: String = SurfacePreset.FLAT_GLASS.name,
    /** The `layout` block over the Default pack's (crosshair, tiles, spacing, motion, …). */
    val layout: ThemeLayout = ThemeLayout.DEFAULT,
    /** The pack's palette roles when it has a valid palette ([palette] is drawn from them). */
    val roles: ThemeManifest.Palette? = null,
    /** The pack's `variants`, checked. */
    val variants: List<ResolvedVariant> = emptyList(),
    /** `categoryOverrides` by category id ("games"). */
    val categoryPatches: Map<String, ThemePatch> = emptyMap(),
    /** `systemOverrides` by Xross platform id ("dreamcast"). */
    val systemPatches: Map<String, ThemePatch> = emptyMap(),
    /** `rules`, checked, in order (imported files first). */
    val rules: List<ResolvedRule> = emptyList(),
    /** The pack's own `palette.circadianCurve`, `palette.batteryTint` and `effects.adaptiveGamePalette`. */
    val flags: ColorFlags = ColorFlags.NONE,
    /** The pack's own `effects.shaderPreset` (2b.4a); variants, overrides and rules may pick others. */
    val shader: ShaderRef = ShaderRef.WAVE,
    /** Text of each CUSTOM shader file the pack names, by its pack-relative path (unreadable ones absent). */
    val customShaders: Map<String, String> = emptyMap(),
    /** The pack's own wallpaper (2b.4c): `effects.wallpaperPath`, else its `wallpaper.png`, else none. */
    val wallpaper: WallpaperRef = WallpaperRef.NONE,
    /** Each wallpaper file the pack names, by its pack-relative path (missing ones absent). */
    val wallpapers: Map<String, File> = emptyMap(),
    /** What the pack declared but couldn't be used (bad font, unreadable icon), for Settings. */
    val problems: List<String> = emptyList(),
    /** Bumped on every load, so a hot reload of the same files still counts as a change. */
    val revision: Long = 0,
) {
    val id: String get() = info.id

    /** [id] if it names a surface, else the pack's own, else [Surface.FALLBACK]. */
    fun surface(id: String = ""): Surface = surfaces[id] ?: surfaces[surfaceId] ?: Surface.FALLBACK

    /** The variant [id] names, or null for the pack's base look (blank or unknown ids). */
    fun variant(id: String?): ResolvedVariant? = id?.takeIf { it.isNotBlank() }?.let { v -> variants.firstOrNull { it.id == v } }

    private data class LookKey(val variant: String, val category: String, val system: String, val rules: List<Int>, val layoutRules: List<Int>, val layoutContext: String)

    private val looks = java.util.concurrent.ConcurrentHashMap<LookKey, ThemeLook>()

    /** Every background program the base, a variant, an override or a rule can show. */
    val shadersUsed: Set<ShaderRef>
        get() = buildSet {
            add(shader)
            variants.forEach { v -> v.shader?.let(::add) }
            (categoryPatches.values + systemPatches.values).forEach { p -> p.shader?.let(::add) }
            rules.forEach { r -> r.patch?.shader?.let(::add) }
        }

    /** [shadersUsed] as programs, compiled ahead of time when this pack becomes active. */
    val shaderLibrary: ShaderLibrary by lazy { ShaderLibrary.of(shadersUsed, customShaders) }

    /** True when the base, a variant, an override or a rule can show a wallpaper. */
    val hasWallpaper: Boolean get() = wallpapers.isNotEmpty()

    /** The file of [ref] (the look's wallpaper); null for none or a file that wasn't found. */
    fun wallpaperFile(ref: WallpaperRef): File? = ref.path?.let(wallpapers::get)

    /** Some rule depends on the battery. */
    val usesBattery: Boolean get() = rules.any { it.battery != null }

    /** Some rule depends on the time of day. */
    val usesClock: Boolean get() = rules.any { it.time != null }

    /**
     * The pack's look with variant [variantId] (blank or unknown: the base), while [category] is
     * open, a game of [system] is focused, the battery is at [battery] and the local time is
     * [minuteOfDay] ([cascade], then the context rules that hold). Cached by the rules that hold,
     * so calling it on every focus change or battery tick costs a scan of the rules and a lookup.
     */
    fun look(
        variantId: String = "",
        category: String? = null,
        system: String? = null,
        battery: dev.xross.launcher.theme.BatteryLevel? = null,
        minuteOfDay: Int? = null,
    ): ThemeLook {
        val v = variant(variantId)
        val cat = category?.takeIf { it in categoryPatches }.orEmpty()
        val sys = system?.takeIf { it in systemPatches }.orEmpty()
        val ctx = RuleContext(category, system, battery, minuteOfDay)
        val held = if (rules.isEmpty()) emptyList() else rules.filter { it.matches(ctx) }
        val layoutHeld = if (rules.isEmpty()) emptyList() else rules.filter { it.changesLayout && it.matchesExceptSystem(ctx) }.map { it.index }
        // Category-wide tile shapes and focused-system layout patches depend on those two as well.
        val layoutContext = if (layoutHeld.isEmpty()) "" else "${category.orEmpty()}/${system.orEmpty()}"
        return looks.getOrPut(LookKey(v?.id.orEmpty(), cat, sys, held.map { it.index }, layoutHeld, layoutContext)) {
            cascade(roles, surfaceId, wave, v, categoryPatches[cat], systemPatches[sys], held, flags, shader, wallpaper)
                .copy(layout = if (layoutHeld.isEmpty()) null else applyRuleLayout(layout, rules, ctx))
        }
    }

    /**
     * The surface drawn in [look]: the user's [userSurface] (Settings ▸ Theme ▸ Surface style)
     * when it names one, else the look's.
     */
    fun surfaceFor(look: ThemeLook, userSurface: String): Surface =
        surfaces[userSurface] ?: surfaces[look.surfaceId] ?: surface()
}

/** Typefaces per role (Roadmap Appendix A.8); a role the pack lacks falls back to [body]. */
@Immutable
data class ThemeFonts(val display: Typeface? = null, val body: Typeface? = null, val mono: Typeface? = null)

/** A folder in `Xross/themes/` that isn't offered as a pack, and why. */
@Immutable
data class RejectedPack(val id: String, val reason: String)

/** [discover] plus the folders it skipped. */
data class PackScan(val packs: List<ThemePackInfo>, val rejected: List<RejectedPack>)

/** A strict load for hot reload: the theme, or why it can't be used. */
sealed interface PackLoad {
    data class Ok(val theme: LoadedTheme) : PackLoad
    data class Failed(val reason: String) : PackLoad
}

/**
 * Finds and loads theme packs: the built-in Default pack (`assets/themes/default`, the bundled
 * icons and sounds) and every folder in `Xross/themes/` with a readable `theme.json`:
 *
 * ```
 * Xross/themes/<theme_id>/
 *   theme.json   schemaVersion 2, name, palette, wave, typography, audio, effects (see ThemeManifest)
 *   *.json       files listed in "imports" (surfaces.json, ...), merged under theme.json
 *   fonts/       .ttf/.otf, named in "typography" (or the first one, for every role)
 *   icons/       <icon id>.svg / .png / .webp, e.g. settings.svg, games.png
 *   sounds/      cursor.wav, confirm.wav, back.wav, option.wav, ok.wav, system_ng.wav,
 *                trophy.wav, 01. opening.wav, gameboot.wav
 *   shader.frag  GLSL ES 3.0 fragment shader for "effects": { "shaderPreset": "CUSTOM" } (or effects.shaderPath)
 *   wallpaper.png  static wallpaper behind the menu (or .jpg / .webp, or effects.wallpaperPath)
 * ```
 *
 * File lookups ignore case (FAT-formatted SD cards and hand-made packs disagree on it) and never
 * leave the pack's folder. Blocking I/O: call off the main thread, except for the small built-in
 * pack at startup.
 */
class ThemePackLoader(
    private val assets: AssetManager,
    private val themesDir: () -> File,
) {
    /** Default first, then folder packs by name. Unreadable and v1 folders are skipped. */
    fun discover(): List<ThemePackInfo> = scan().packs

    /** [discover], plus each skipped folder with a `theme.json` and the reason it was skipped. */
    fun scan(): PackScan {
        val packs = ArrayList<ThemePackInfo>()
        val rejected = ArrayList<RejectedPack>()
        builtIn()?.let(packs::add)
        val dir = themesDir()
        val folders = dir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }.orEmpty()
        for (folder in folders.sortedBy { it.name.lowercase(Locale.ROOT) }) {
            val id = folder.name
            if (!ID.matches(id) || id.equals(DEFAULT_ID, ignoreCase = true)) continue
            if (find(folder, MANIFEST) == null) continue
            when (val r = readFolderManifest(folder)) {
                is Read.Ok -> packs += ThemePackInfo(id, r.manifest, PackSource.Folder(folder))
                is Read.Bad -> rejected += RejectedPack(id, r.reason)
            }
        }
        return PackScan(
            packs.sortedWith(compareBy<ThemePackInfo>({ !it.builtIn }, { it.name.lowercase(Locale.ROOT) })),
            rejected,
        )
    }

    /** The Default pack's manifest; null only if the APK is broken. */
    fun builtIn(): ThemePackInfo? {
        val r = readManifest(MANIFEST) { name -> runCatching { assets.open("$DEFAULT_DIR/$name").bufferedReader().use { it.readText() } }.getOrNull() }
        return (r as? Read.Ok)?.let { ThemePackInfo(DEFAULT_ID, it.manifest, PackSource.Builtin(DEFAULT_DIR)) }
    }

    /** Loads [id], or the Default pack when [id] is unknown or unreadable. */
    fun load(id: String): LoadedTheme = (loadStrict(id) as? PackLoad.Ok)?.theme ?: base()

    /** Loads [id] without falling back, so a hot reload can keep the last good theme. */
    fun loadStrict(id: String): PackLoad {
        if (id.equals(DEFAULT_ID, ignoreCase = true) || id.isBlank()) return PackLoad.Ok(base())
        val folder = themesDir().listFiles()?.firstOrNull { it.isDirectory && it.name == id }
            ?: return PackLoad.Failed("$id is no longer in Xross/themes")
        return when (val r = readFolderManifest(folder)) {
            is Read.Ok -> PackLoad.Ok(resolve(ThemePackInfo(id, r.manifest, PackSource.Folder(folder)), base()))
            is Read.Bad -> PackLoad.Failed(r.reason)
        }
    }

    /** Reads [folder] as a pack (its manifest and imports), as discovery would. */
    fun inspect(folder: File): FolderCheck = when (val r = readFolderManifest(folder)) {
        is Read.Ok -> FolderCheck.Ok(r.manifest)
        is Read.Bad -> FolderCheck.Bad(r.reason)
    }

    /**
     * Changes when any file of folder pack [id] is added, removed or rewritten (names, sizes and
     * modification times, two folders deep); 0 for the built-in pack.
     */
    fun fingerprint(id: String): Long {
        val folder = themesDir().listFiles()?.firstOrNull { it.isDirectory && it.name == id } ?: return 0
        var h = 17L
        fun walk(dir: File, depth: Int) {
            for (f in dir.listFiles().orEmpty().sortedBy { it.name }) {
                h = h * 31 + f.name.hashCode()
                if (f.isDirectory) {
                    if (depth < 2) walk(f, depth + 1)
                } else {
                    h = h * 31 + f.length()
                    h = h * 31 + f.lastModified()
                }
            }
        }
        walk(folder, 0)
        return h
    }

    private fun base(): LoadedTheme {
        val default = builtIn() ?: error("Built-in theme pack missing from the APK")
        return resolve(default, null)
    }

    private fun resolve(pack: ThemePackInfo, base: LoadedTheme?): LoadedTheme {
        val m = pack.manifest
        val problems = ArrayList<String>()

        val sounds = LinkedHashMap<SoundRole, SoundSource>()
        val gains = LinkedHashMap<SoundRole, Float>()
        val declaredSounds = m.audio?.sounds.orEmpty()
        val declaredGains = m.audio?.soundGains.orEmpty()
        for (key in declaredSounds.keys + declaredGains.keys) if (SoundRole.byKey(key) == null) problems += "Unknown sound role $key"
        for (role in SoundRole.entries) {
            val fileName = declaredSounds.entries.firstOrNull { SoundRole.byKey(it.key) == role }?.value ?: role.file
            val own = soundFile(pack, fileName)
            val source = own ?: base?.sounds?.get(role)
            if (source != null) sounds[role] = source
            val gain = declaredGains.entries.firstOrNull { SoundRole.byKey(it.key) == role }?.value
            gains[role] = when {
                gain != null && gain.isFinite() -> gain.coerceIn(0f, 1f)
                own == null -> base?.gains?.get(role) ?: 1f
                else -> 1f
            }
        }

        val icons = LinkedHashMap<GlyphKey, ThemeIcon>()
        for (key in GlyphKey.entries) {
            val declared = m.icons.entries.firstOrNull { it.key.equals(key.iconId, ignoreCase = true) }?.value
            val icon = icon(pack, declared, key.iconId)
            if (icon == null && declared != null) problems += "Icon ${key.iconId}: can't read $declared"
            (icon ?: base?.icons?.get(key))?.let { icons[key] = it }
        }

        val fonts = fonts(pack, m.typography, base?.fonts, problems)
        val palette = m.palette?.takeIf { it.hasColors }?.let { p ->
            p.toPalette(m.wave).also { if (it == null) problems += "Palette needs a canvas color, and colors must be #RRGGBB" }
        }

        val surfaces = LinkedHashMap(base?.surfaces.orEmpty())
        for ((sid, spec) in m.surfaces) {
            if (!SURFACE_ID.matches(sid)) {
                problems += "Surface name $sid: use letters, digits, - and _"
                continue
            }
            surfaces[sid] = resolveSurface(sid, spec, problems)
        }
        val wanted = m.effects?.surfaceStyle?.trim().orEmpty()
        if (wanted.isNotEmpty() && wanted !in surfaces) problems += "effects.surfaceStyle: no surface named $wanted"
        val surfaceId = wanted.takeIf { it in surfaces } ?: base?.surfaceId ?: SurfacePreset.FLAT_GLASS.name
        val systems = resolveSystemKeys(m.systemOverrides, problems)
        val (layout, rules) = foldRuleAspects(
            resolveLayout(m.layout, m.categoryOverrides, base?.layout ?: ThemeLayout.DEFAULT, problems, systems),
            resolveRules(m.rules, surfaces.keys, base?.layout ?: ThemeLayout.DEFAULT, problems),
        )

        val shader = ShaderRef.of("theme", m.effects?.shaderPreset, m.effects?.shaderPath, problems) ?: base?.shader ?: ShaderRef.WAVE
        val variants = resolveVariants(m.variants, palette != null, surfaces.keys, problems)
        val categoryPatches = resolveCategoryPatches(m.categoryOverrides, surfaces.keys, problems)
        val systemPatches = resolveSystemPatches(systems, surfaces.keys, problems)
        val customPaths = (listOf(shader) + variants.mapNotNull { it.shader } + (categoryPatches.values + systemPatches.values).mapNotNull { it.shader } +
            rules.mapNotNull { it.patch?.shader })
            .filter { it.preset == ShaderPreset.CUSTOM }.map { it.path }.distinct()
        val customShaders = LinkedHashMap<String, String>()
        for (path in customPaths) shaderText(pack, path, problems)?.let { customShaders[path] = it }

        val folder = (pack.source as? PackSource.Folder)?.dir
        val wallpaper = WallpaperRef.of("theme", m.effects?.wallpaperPath, problems)
            ?: folder?.let(WallpaperRef::defaultIn)?.let(::WallpaperRef)
            ?: WallpaperRef.NONE
        val wallpaperRefs = listOf(wallpaper) + variants.mapNotNull { it.wallpaper } +
            (categoryPatches.values + systemPatches.values).mapNotNull { it.wallpaper } + rules.mapNotNull { it.patch?.wallpaper }
        val wallpapers = wallpaperFiles(folder, wallpaperRefs, problems)

        return LoadedTheme(
            info = pack,
            palette = palette,
            wave = m.wave,
            icons = icons,
            fonts = fonts,
            sounds = sounds,
            gains = gains,
            surfaces = surfaces,
            surfaceId = surfaceId,
            layout = layout,
            roles = if (palette != null) m.palette else null,
            variants = variants,
            categoryPatches = categoryPatches,
            systemPatches = systemPatches,
            rules = rules,
            flags = ColorFlags.of(m.palette, m.effects),
            shader = shader,
            customShaders = customShaders,
            wallpaper = wallpaper,
            wallpapers = wallpapers,
            problems = problems,
            revision = revisions.incrementAndGet(),
        )
    }

    // region Files inside a pack

    /**
     * A CUSTOM shader's text, read at load so a hot reload (ThemeWatcher) picks up an edit. Only
     * folder packs carry one; a missing, oversized or binary file is reported and left out (the
     * renderer then shows the wave and says why).
     */
    private fun shaderText(pack: ThemePackInfo, path: String, problems: MutableList<String>): String? {
        val dir = (pack.source as? PackSource.Folder)?.dir ?: run {
            problems += "Shader $path: only folder packs can bring a CUSTOM shader"
            return null
        }
        val file = inside(dir, path)?.takeIf { it.isFile } ?: run {
            problems += "Shader $path not found in the pack"
            return null
        }
        if (file.length() > MAX_SHADER_BYTES) {
            problems += "Shader $path is larger than ${MAX_SHADER_BYTES / 1024} KB"
            return null
        }
        val text = runCatching { file.readText() }.getOrNull()
        if (text == null || text.any { it == '\u0000' }) {
            problems += "Shader $path can't be read as text"
            return null
        }
        return text
    }

    /** A bare file name is looked up in `sounds/`; a path ("audio/tick.wav") from the pack root. */
    private fun soundFile(pack: ThemePackInfo, name: String): SoundSource? {
        val path = if ('/' in name || '\\' in name) name else "$SOUNDS/$name"
        return when (val s = pack.source) {
            is PackSource.Builtin -> "${s.dir}/$path".takeIf { assetExists(it) }?.let { SoundSource.Asset(it) }
            is PackSource.Folder -> inside(s.dir, path)?.takeIf { it.isFile }?.let { SoundSource.Disk(it) }
        }
    }

    private fun icon(pack: ThemePackInfo, declared: String?, id: String): ThemeIcon? {
        val candidates = if (declared != null) listOf(declared) else ICON_TYPES.map { "$id.$it" }
        for (name in candidates) {
            val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            if (ext !in ICON_TYPES) continue
            val icon = when (val s = pack.source) {
                is PackSource.Builtin -> "${s.dir}/$ICONS/$name".takeIf { assetExists(it) }?.let { path ->
                    runCatching { assets.open(path).use { stream -> readIcon(ext, id) { stream.readBytes() } } }.getOrNull()
                }
                is PackSource.Folder -> inside(s.dir, "$ICONS/$name")?.let { file -> readIcon(ext, id) { file.readBytes() } }
            }
            if (icon != null) return icon
        }
        return null
    }

    private fun readIcon(ext: String, id: String, bytes: () -> ByteArray): ThemeIcon? = try {
        val data = bytes()
        if (data.size > MAX_ICON_BYTES) {
            null
        } else if (ext == "svg") {
            SvgIcon.parse(data.toString(Charsets.UTF_8), id)?.let { ThemeIcon.Vector(it) }
        } else {
            decodeBitmap(data)?.let { ThemeIcon.Bitmap(it) }
        }
    } catch (_: IOException) {
        null
    }

    private fun decodeBitmap(data: ByteArray): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= ICON_PX) sample *= 2
        return BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    }

    /**
     * The pack's font roles. `body` is the declared file or the first font in `fonts/`; `display`
     * and `mono` fall back to it. A pack with no fonts at all inherits [base]'s.
     */
    private fun fonts(pack: ThemePackInfo, t: ThemeManifest.Typography?, base: ThemeFonts?, problems: MutableList<String>): ThemeFonts {
        val body = font(pack, t?.body.orEmpty(), problems, firstIfBlank = true)
        val display = t?.display?.takeIf { it.isNotBlank() }?.let { font(pack, it, problems, firstIfBlank = false) }
        val mono = t?.mono?.takeIf { it.isNotBlank() }?.let { font(pack, it, problems, firstIfBlank = false) }
        if (body == null && display == null && mono == null) return base ?: ThemeFonts()
        return ThemeFonts(display = display ?: body, body = body ?: display ?: mono, mono = mono ?: body)
    }

    private fun font(pack: ThemePackInfo, declared: String, problems: MutableList<String>, firstIfBlank: Boolean): Typeface? = when (val s = pack.source) {
        is PackSource.Builtin -> {
            val path = if (declared.isNotBlank()) "${s.dir}/$declared" else if (firstIfBlank) firstFontAsset(s.dir) else null
            path?.let { p -> runCatching { Typeface.createFromAsset(assets, p) }.getOrNull() }
        }
        is PackSource.Folder -> {
            val file = when {
                declared.isNotBlank() -> inside(s.dir, declared)
                firstIfBlank -> find(s.dir, FONTS)?.listFiles()?.filter { it.isFile && it.extension.lowercase(Locale.ROOT) in FONT_TYPES }?.minByOrNull { it.name }
                else -> null
            }
            when {
                file == null -> {
                    if (declared.isNotBlank()) problems += "Font $declared not found"
                    null
                }
                file.length() > MAX_FONT_BYTES -> {
                    problems += "Font ${file.name} is larger than 16 MB"
                    null
                }
                else -> runCatching { Typeface.Builder(file).build() }.getOrNull()
                    .also { if (it == null) problems += "Font ${file.name} can't be read" }
            }
        }
    }

    private fun firstFontAsset(dir: String): String? =
        runCatching { assets.list("$dir/$FONTS") }.getOrNull()
            ?.filter { it.substringAfterLast('.').lowercase(Locale.ROOT) in FONT_TYPES }
            ?.minOrNull()
            ?.let { "$dir/$FONTS/$it" }

    private fun assetExists(path: String): Boolean = try {
        assets.openFd(path).close()
        true
    } catch (_: IOException) {
        // Compressed assets can't be opened as a descriptor; fall back to a stream check.
        runCatching { assets.open(path).close() }.isSuccess
    }

    // endregion

    private sealed interface Read {
        data class Ok(val manifest: ThemeManifest) : Read
        data class Bad(val reason: String) : Read
    }

    private fun readFolderManifest(folder: File): Read = readManifest(find(folder, MANIFEST)?.name ?: MANIFEST) { name ->
        inside(folder, name)?.takeIf { it.isFile && it.length() <= MAX_JSON_BYTES }?.let { f -> runCatching { f.readText() }.getOrNull() }
    }

    /**
     * Reads [manifestName] and its `imports` through [read] (null: missing or unreadable), merges
     * them (imports first, the manifest on top) and decodes the result. Only `schemaVersion: 2` is
     * accepted. Imports are JSON files inside the pack; they can't import further files.
     */
    private fun readManifest(manifestName: String, read: (String) -> String?): Read {
        val text = read(manifestName) ?: return Read.Bad("$manifestName can't be read")
        val root = when (val p = parseObject(manifestName, text)) {
            is Parsed.Ok -> p.obj
            is Parsed.Bad -> return Read.Bad(p.reason)
        }
        var merged = JsonObject(emptyMap())
        // `rules` add up across files (imports first) instead of the last file's replacing the rest.
        val rules = ArrayList<kotlinx.serialization.json.JsonElement>()
        val imports = (root["imports"] as? JsonArray).orEmpty()
        if (imports.size > MAX_IMPORTS) return Read.Bad("$manifestName: at most $MAX_IMPORTS imports")
        for (entry in imports) {
            val name = (entry as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (!name.lowercase(Locale.ROOT).endsWith(".json") || name.contains("..")) return Read.Bad("$manifestName: imports must be .json files in the pack ($name)")
            val importText = read(name) ?: return Read.Bad("$manifestName imports $name, which can't be read")
            val obj = when (val p = parseObject(name, importText)) {
                is Parsed.Ok -> p.obj
                is Parsed.Bad -> return Read.Bad(p.reason)
            }
            (obj["rules"] as? JsonArray)?.let(rules::addAll)
            merged = ThemeManifest.merge(merged, JsonObject(obj - "imports" - "rules"))
        }
        (root["rules"] as? JsonArray)?.let(rules::addAll)
        merged = ThemeManifest.merge(merged, JsonObject(root - "rules"))
        if (rules.isNotEmpty()) merged = JsonObject(merged + ("rules" to JsonArray(rules)))
        val version = (merged["schemaVersion"] as? JsonPrimitive)?.intOrNull
        if (version != ThemeManifest.SCHEMA_VERSION) {
            return Read.Bad(if (version == null) "theme.json has no \"schemaVersion\": 2 (v1 packs aren't supported)" else "schemaVersion $version isn't supported (use 2)")
        }
        return try {
            val m = ThemeManifest.JSON.decodeFromJsonElement(ThemeManifest.serializer(), merged)
            if (m.name.isBlank()) Read.Bad("theme.json needs a \"name\"") else Read.Ok(m)
        } catch (e: SerializationException) {
            Read.Bad("$manifestName: ${e.message?.lineSequence()?.firstOrNull() ?: "invalid"}")
        } catch (e: IllegalArgumentException) {
            Read.Bad("$manifestName: ${e.message?.lineSequence()?.firstOrNull() ?: "invalid"}")
        }
    }

    private sealed interface Parsed {
        data class Ok(val obj: JsonObject) : Parsed
        data class Bad(val reason: String) : Parsed
    }

    /** Parses [text] as a JSON object; a failure names the file and, when known, the line. */
    private fun parseObject(name: String, text: String): Parsed = try {
        (Json.parseToJsonElement(text) as? JsonObject)?.let { Parsed.Ok(it) } ?: Parsed.Bad("$name isn't a JSON object")
    } catch (e: SerializationException) {
        val msg = e.message.orEmpty()
        val line = Regex("offset (\\d+)").find(msg)?.groupValues?.get(1)?.toIntOrNull()
            ?.let { off -> text.take(off).count { it == '\n' } + 1 }
        Parsed.Bad(name + (line?.let { " line $it" } ?: "") + ": " + msg.lineSequence().first().substringBefore(" at path").take(120))
    }

    companion object {
        const val DEFAULT_ID = "default"
        const val DEFAULT_DIR = "themes/default"
        const val MANIFEST = "theme.json"
        const val SOUNDS = "sounds"
        const val ICONS = "icons"
        const val FONTS = "fonts"
        val ICON_TYPES = listOf("svg", "png", "webp")
        val FONT_TYPES = setOf("ttf", "otf")
        /** Pack icons are decoded no larger than this (they're drawn at most ~120 px). */
        const val ICON_PX = 256
        private const val MAX_ICON_BYTES = 2L * 1024 * 1024
        private const val MAX_FONT_BYTES = 16L * 1024 * 1024
        private val ID = Regex("[A-Za-z0-9_. -]{1,64}")
        private val SURFACE_ID = Regex("[A-Za-z0-9_-]{1,48}")
        private const val MAX_JSON_BYTES = 256L * 1024
        private const val MAX_SHADER_BYTES = 64L * 1024
        private const val MAX_IMPORTS = 8
        private val revisions = java.util.concurrent.atomic.AtomicLong()

        /**
         * [relative] inside [root], matching each path segment without regard to case; null when
         * it doesn't exist or would leave [root] ("..", absolute paths, symlinks out).
         */
        fun inside(root: File, relative: String): File? {
            val parts = relative.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
            if (parts.isEmpty() || parts.any { it == ".." }) return null
            var current = root
            for (part in parts) current = find(current, part) ?: return null
            val canonicalRoot = runCatching { root.canonicalPath }.getOrNull() ?: return null
            val canonical = runCatching { current.canonicalPath }.getOrNull() ?: return null
            if (!canonical.startsWith(canonicalRoot + File.separator)) return null
            return current.takeIf { it.isFile || it.isDirectory }
        }

        /** The child of [dir] named [name], exact match first, then ignoring case. */
        fun find(dir: File, name: String): File? {
            val exact = File(dir, name)
            if (exact.exists()) return exact
            return dir.listFiles()?.firstOrNull { it.name.equals(name, ignoreCase = true) }
        }
    }
}
