package dev.xross.launcher.theme.pack

import dev.xross.launcher.theme.PACK_THEME
import dev.xross.launcher.theme.SeasonalPalette
import dev.xross.launcher.theme.ThemePalette
import dev.xross.launcher.theme.WaveColors
import dev.xross.launcher.theme.WaveTint
import dev.xross.launcher.theme.XrossTheme
import dev.xross.launcher.theme.shader.ShaderPreset
import dev.xross.launcher.theme.shader.ShaderRef
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.LocalDate
import java.util.Locale

/** What Settings ▸ Theme ▸ Export theme… writes (Phase 2b.8b). */
sealed interface ExportScope {
    /** The pack as it ships: every variant, override and rule, and no user pick. */
    data object WholePack : ExportScope

    /** One colourway ([id]; blank is the base look) flattened into a single-look pack, without the user's picks. */
    data class Variant(val id: String) : ExportScope

    /** The pack, its variant and every user pick over it: loads to what is on screen with no user layer. */
    data object CurrentLook : ExportScope
}

/** The wave values of Settings ▸ Theme ▸ Wave settings, as a current-look export bakes them in. */
data class WaveValues(
    val speed: Float = 1f,
    val lineCount: Int = 5,
    val turbulence: Float = 1f,
    val thickness: Float = 1f,
    /** A [WaveTint] name. */
    val tint: String = WaveTint.THEME.name,
    val colors: WaveColors = WaveColors(),
) {
    /** `WaveValues.of(settings)` lives in `data/`, beside the settings it reads. */
    companion object
}

/** Everything over the pack that a [ExportScope.CurrentLook] export bakes in. */
data class LookPicks(
    /** The selected variant; blank is the base look. */
    val variant: String = "",
    val user: UserLook = UserLook.NONE,
    /** `display.theme`: one of [XrossTheme], or [PACK_THEME]. */
    val colorTheme: String = PACK_THEME,
    val wave: WaveValues = WaveValues(),
    /**
     * The file type (`png`, `jpg`, `webp`) of [UserLook.customWallpaper] when it is set and can be
     * read; null leaves it out (the pack's own wallpaper then shows, as it does on screen).
     */
    val wallpaperType: String? = null,
) {
    /** `LookPicks.of(settings)` lives in `data/`, beside the settings it reads. */
    companion object
}

/** Where an exported file's bytes come from. */
sealed interface ExportSource {
    /** A file of the pack, pack-relative ([LoadedTheme.files]), from its APK assets or its folder. */
    data class Pack(val path: String) : ExportSource

    /** The Xross wallpaper ([UserLook.customWallpaper]): a photo URI or a file path. */
    data object UserWallpaper : ExportSource
}

/** One file of an export: [source] copied to [path] inside the new pack. */
data class ExportFile(val source: ExportSource, val path: String)

/** A pack ready to be written: its single `theme.json`, its files and what the confirm panel says about it. */
data class LookExport(
    val scope: ExportScope,
    val manifest: JsonObject,
    val files: List<ExportFile>,
    /** Things the confirm panel should mention ("Original's November colors"). */
    val notes: List<String> = emptyList(),
) {
    val name: String get() = (manifest["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
}

/**
 * Turns a loaded pack into a new pack (Phase 2b.8b), in one of three [ExportScope]s. Pure: the
 * input is the pack's merged manifest JSON ([LoadedTheme.sourceJson], `imports` already merged) and
 * the files it read ([LoadedTheme.files]); the output is one `theme.json` (`schemaVersion: 2`, no
 * `imports`) and the files to copy. [ThemeExport] writes it.
 *
 * - [ExportScope.WholePack]: `sourceJson` as it is, plus every convention file the import
 *   allowlist knows, so the pack re-imports to the same pack.
 * - [ExportScope.Variant]: the variant's palette, effects, sounds and components written into the
 *   base and `variants` emptied; overrides and rules kept.
 * - [ExportScope.CurrentLook]: flattened like a variant, then every user pick written into the base
 *   **and removed from each override and rule that sets the same key**, because the user's pick
 *   wins in every context and the new pack has to behave the same with no user layer. The colour
 *   theme and the wave tint become the palette, the components the resolved ones.
 *
 * Values copied from the variant and the user layer are written from their checked forms
 * ([ResolvedVariant], [EffectOverrides], [LayoutOverrides]) under the canonical key names, so a
 * bad value in a variant never lands in the base and an alias never sits next to its key.
 */
object LookExporter {
    /** The note every export carries in its description and its confirm panel. */
    const val LICENCE_NOTE = "Copied fonts, sounds, icons and art keep their original licences."

    fun defaultName(pack: LoadedTheme, scope: ExportScope): String = when (scope) {
        ExportScope.WholePack -> pack.info.name
        is ExportScope.Variant -> "${pack.info.name} · ${variantName(pack, scope.id)}"
        ExportScope.CurrentLook -> "${pack.info.name} (my look)"
    }

    /** "Night", or "Base look" for the base. */
    fun variantName(pack: LoadedTheme, id: String): String = pack.variant(id)?.name ?: "Base look"

    /** "whole pack", "Night variant", "base look", "current look": the description's words. */
    fun scopeLabel(pack: LoadedTheme, scope: ExportScope): String = when (scope) {
        ExportScope.WholePack -> "whole pack"
        is ExportScope.Variant -> pack.variant(scope.id)?.let { "${it.name} variant" } ?: "base look"
        ExportScope.CurrentLook -> "current look"
    }

    /**
     * The export of [pack] in [scope] under [name] (blank: [defaultName]), dated [date].
     * [available] lists every file in the pack (pack-relative; the writer lists the folder or the
     * APK assets): a whole-pack export copies its convention files from it. [picks] is the user's
     * side of a current-look export.
     */
    fun export(
        pack: LoadedTheme,
        scope: ExportScope,
        name: String = "",
        date: LocalDate = LocalDate.now(),
        available: Set<String> = emptySet(),
        picks: LookPicks = LookPicks(),
    ): LookExport {
        val m = pack.info.manifest
        val source = JsonObject(pack.sourceJson - "imports")
        val notes = ArrayList<String>()
        val body: JsonObject
        val files: List<ExportFile>
        when (scope) {
            ExportScope.WholePack -> {
                body = source
                files = wholePackFiles(pack, available).map { ExportFile(ExportSource.Pack(it), it) }
            }
            is ExportScope.Variant -> {
                body = Doc(source).apply { flatten(pack, pack.variant(scope.id)) }.root
                files = usedFiles(pack, body).map { ExportFile(ExportSource.Pack(it), it) }
            }
            ExportScope.CurrentLook -> {
                val doc = Doc(source).apply { flatten(pack, pack.variant(picks.variant)) }
                val wallpaper = picks.user.customWallpaper.takeIf { it.isNotBlank() }?.let { picks.wallpaperType }
                    ?.let { type -> wallpaperName(pack.files, type) }
                doc.applyUser(pack, picks, wallpaper, date, notes)
                body = doc.root
                files = usedFiles(pack, body, ignoreWallpapers = wallpaper != null).map { ExportFile(ExportSource.Pack(it), it) } +
                    listOfNotNull(wallpaper?.let { ExportFile(ExportSource.UserWallpaper, it) })
            }
        }
        val label = scopeLabel(pack, scope)
        val stamp = "Exported from ${m.name}${m.version.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()} ($label) by Xross on $date. $LICENCE_NOTE"
        val description = if (scope == ExportScope.WholePack && m.description.isNotBlank()) "${m.description.trim()} · $stamp" else stamp
        val identity = linkedMapOf<String, JsonElement>(
            "schemaVersion" to JsonPrimitive(ThemeManifest.SCHEMA_VERSION),
            "name" to JsonPrimitive(name.trim().ifEmpty { defaultName(pack, scope) }),
            "author" to JsonPrimitive(if (scope == ExportScope.CurrentLook) "" else m.author),
            "version" to JsonPrimitive(if (scope == ExportScope.WholePack) m.version else "1.0.0"),
            "description" to JsonPrimitive(description),
        )
        val manifest = JsonObject(identity + body.filterKeys { it !in identity })
        return LookExport(scope, manifest, files.distinctBy { it.path.lowercase(Locale.ROOT) }, notes)
    }

    // region Files

    /** The loader's files plus every allowlisted convention file in the pack (whole-pack exports). */
    private fun wholePackFiles(pack: LoadedTheme, available: Set<String>): List<String> {
        val convention = available.filter { path ->
            val parts = path.split('/').filter { it.isNotEmpty() }
            parts.isNotEmpty() && parts.none { it.startsWith(".") } &&
                // The JSON (imports included) is merged into the one theme.json; notes (.md, .txt) are kept.
                !parts.last().lowercase(Locale.ROOT).endsWith(".json") &&
                ThemeArchive.disallowed(parts) == null
        }
        return (pack.files + convention.sorted()).distinct()
    }

    /**
     * What a flattened export ([body]) still uses: [LoadedTheme.files] minus each file only a
     * dropped layer named (another variant's wallpaper, a shader the user's pick replaced). Fonts,
     * icons, sounds and the boot video can't be patched, so they always stay.
     */
    private fun usedFiles(pack: LoadedTheme, body: JsonObject, ignoreWallpapers: Boolean = false): List<String> {
        val sourceBaseWallpaper = pack.info.manifest.effects?.wallpaperPath.isNullOrBlank()
        val sourceBaseBgm = pack.info.manifest.audio?.bgmFile.isNullOrBlank()
        val defaults = Defaults(
            wallpaper = pack.wallpaper.path.takeIf { sourceBaseWallpaper },
            bgm = pack.bgm.path.takeIf { sourceBaseBgm },
        )
        val before = refs(pack.info.manifest, defaults)
        val out = ThemeManifest.JSON.decodeFromJsonElement(ThemeManifest.serializer(), body)
        val after = refs(out, defaults).let { r -> if (ignoreWallpapers) r - r.wallpapers else r }
        val dropped = (before.all - after.all).mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
        return pack.files.filter { it.lowercase(Locale.ROOT) !in dropped }
    }

    private data class Defaults(val wallpaper: String?, val bgm: String?)

    private class Refs(val all: Set<String>, val wallpapers: Set<String>) {
        operator fun minus(other: Set<String>) = Refs(all - other, wallpapers - other)
    }

    /** Every patchable file [m] names in its base, variants, overrides and rules (pack-relative). */
    private fun refs(m: ThemeManifest, d: Defaults): Refs {
        val ignored = ArrayList<String>()
        val all = LinkedHashSet<String>()
        val wallpapers = LinkedHashSet<String>()
        fun effects(e: ThemeManifest.Effects?, base: Boolean) {
            ShaderRef.of("x", e?.shaderPreset, e?.shaderPath, ignored)?.takeIf { it.preset == ShaderPreset.CUSTOM }?.let { all += it.path }
            UiFilterRef.of("x", e?.uiFilter, e?.uiFilterPath, ignored)?.takeIf { it.filter == UiFilter.CUSTOM }?.let { all += it.path }
            val w = WallpaperRef.of("x", e?.wallpaperPath, ignored)
            (if (w == null && base) d.wallpaper else w?.path)?.let { all += it; wallpapers += it }
        }
        fun audio(a: ThemeManifest.Audio?, base: Boolean) {
            val b = BgmRef.of("x", a?.bgmFile, ignored)
            (if (b == null && base) d.bgm else b?.path)?.let { all += it }
        }
        fun logo(c: Components?) {
            ComponentsPatch.of("x", c, ignored)?.logo?.takeIf { it.isNotEmpty() }?.let { all += it }
        }
        effects(m.effects, base = true)
        audio(m.audio, base = true)
        logo(m.components)
        for (v in m.variants) {
            effects(v.effects, base = false)
            audio(v.audio, base = false)
            logo(v.components)
        }
        for (o in m.categoryOverrides.values + m.systemOverrides.values) {
            effects(o.effects, base = false)
            audio(o.audio, base = false)
        }
        for (r in m.rules) {
            effects(r.apply.effects, base = false)
            audio(r.apply.audio, base = false)
        }
        return Refs(all, wallpapers)
    }

    /** `wallpaper.<type>` for the user's wallpaper, or a free name when the pack already uses that one. */
    private fun wallpaperName(packFiles: Set<String>, type: String): String {
        val ext = type.lowercase(Locale.ROOT).removePrefix(".").let { if (it == "jpeg") "jpg" else it }
        val taken = packFiles.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
        return listOf("wallpaper.$ext", "wallpapers/xross-wallpaper.$ext").firstOrNull { it !in taken } ?: "wallpapers/xross-wallpaper-2.$ext"
    }

    // endregion

    // region Editing the manifest

    /** Effect keys that travel together (a key, its aliases and its companion path), by canonical key. */
    private val EFFECT_UNITS: Map<String, Set<String>> = mapOf(
        "surfaceStyle" to setOf("surfaceStyle"),
        "adaptiveGamePalette" to setOf("adaptiveGamePalette"),
        "shaderPreset" to setOf("shaderPreset", "shader", "shaderPath"),
        "wallpaperPath" to setOf("wallpaperPath", "wallpaper"),
        "focusFrameStyle" to setOf("focusFrameStyle", "focusFrame"),
        "iconMask" to setOf("iconMask"),
        "iconFilter" to setOf("iconFilter"),
        "foregroundParticles" to setOf("foregroundParticles", "particles"),
        "backgroundFilter" to setOf("backgroundFilter"),
        "uiFilter" to setOf("uiFilter", "postProcess", "uiFilterPath", "postProcessPath"),
        "bootIntro" to setOf("bootIntro", "intro"),
        "launchCurtain" to setOf("launchCurtain", "curtain"),
    )

    private val COLOR_ROLES = listOf("canvas", "surface", "surfaceBorder", "accent", "accentSecondary", "focusGold", "textPrimary", "textSecondary")

    private fun str(s: String) = JsonPrimitive(s)

    private fun hex(argb: Int): String = if ((argb ushr 24) == 0xFF) ThemeManifest.colorText(argb) else "#%08X".format(argb)

    private fun JsonObject.obj(key: String): JsonObject = this[key] as? JsonObject ?: JsonObject(emptyMap())

    private fun JsonObject.set(key: String, value: JsonElement?): JsonObject =
        if (value == null) JsonObject(this - key) else JsonObject(this + (key to value))

    private fun JsonObject.without(keys: Collection<String>): JsonObject = JsonObject(filterKeys { it !in keys })

    /** [this] with [block] replaced by [value]; an empty block is dropped. */
    private fun JsonObject.withBlock(block: String, value: JsonObject): JsonObject = set(block, value.takeIf { it.isNotEmpty() })

    /** The colour roles [p] sets, as JSON. */
    private fun colors(p: ThemeManifest.Palette): Map<String, JsonElement> = buildMap {
        p.canvas?.let { put("canvas", str(it)) }
        p.surface?.let { put("surface", str(it)) }
        p.surfaceBorder?.let { put("surfaceBorder", str(it)) }
        p.accent?.let { put("accent", str(it)) }
        p.accentSecondary?.let { put("accentSecondary", str(it)) }
        p.focusGold?.let { put("focusGold", str(it)) }
        p.textPrimary?.let { put("textPrimary", str(it)) }
        p.textSecondary?.let { put("textSecondary", str(it)) }
    }

    private fun shaderJson(s: ShaderRef): Map<String, JsonElement> =
        if (s.preset == ShaderPreset.CUSTOM) mapOf("shaderPreset" to str(s.preset.name), "shaderPath" to str(s.path)) else mapOf("shaderPreset" to str(s.preset.name))

    private fun uiFilterJson(f: UiFilterRef): Map<String, JsonElement> =
        if (f.filter == UiFilter.CUSTOM) mapOf("uiFilter" to str(f.filter.name), "uiFilterPath" to str(f.path)) else mapOf("uiFilter" to str(f.filter.name))

    /** The resolved components as a whole `components` block: what the new pack draws, every value set. */
    internal fun componentsJson(c: ResolvedComponents): JsonObject = JsonObject(
        linkedMapOf(
            "topLeftCluster" to str(c.topLeft.name),
            "statusBarLeft" to JsonArray(c.left.map { str(it.name) }),
            "statusBarRight" to JsonArray(c.right.map { str(it.name) }),
            "batteryMode" to str(c.battery.name),
            "clockFormat" to str(c.clock),
            "telemetryMode" to str(c.telemetry.name),
            "miniPlayerMode" to str(c.miniPlayer.name),
            "glyphOverride" to str(c.glyphs.name),
            "badgePills" to JsonArray(c.pills.map { str(it.name) }),
            "logo" to str(c.logo),
            "volumeAlways" to JsonPrimitive(c.volumeAlways),
        ),
    )

    /** A manifest being edited: its base blocks, and every override and rule patch. */
    private class Doc(var root: JsonObject) {
        private fun block(name: String) = root.obj(name)
        private fun setBlock(name: String, value: JsonObject) {
            root = root.withBlock(name, value)
        }

        /** In the base `effects`: [unit]'s keys replaced by [values]. */
        fun setEffect(unit: String, values: Map<String, JsonElement>) {
            setBlock("effects", JsonObject(block("effects").without(EFFECT_UNITS.getValue(unit)) + values))
        }

        fun setPalette(values: Map<String, JsonElement>, drop: Collection<String> = values.keys) {
            setBlock("palette", JsonObject(block("palette").without(drop) + values))
        }

        /**
         * [edit] on every `categoryOverrides` / `systemOverrides` entry and every rule's `apply`;
         * a patch left empty is dropped (an override's key, a whole rule).
         */
        fun editPatches(edit: (JsonObject) -> JsonObject) {
            for (overrides in listOf("categoryOverrides", "systemOverrides")) {
                val all = root[overrides] as? JsonObject ?: continue
                val kept = LinkedHashMap<String, JsonElement>()
                for ((key, value) in all) {
                    val o = value as? JsonObject
                    if (o == null) {
                        kept[key] = value
                        continue
                    }
                    edit(o).takeIf { it.isNotEmpty() }?.let { kept[key] = it }
                }
                root = root.set(overrides, JsonObject(kept).takeIf { it.isNotEmpty() })
            }
            val rules = root["rules"] as? JsonArray ?: return
            val kept = ArrayList<JsonElement>()
            for (r in rules) {
                val rule = r as? JsonObject
                val apply = rule?.get("apply") as? JsonObject
                if (rule == null || apply == null) {
                    kept += r
                    continue
                }
                edit(apply).takeIf { it.isNotEmpty() }?.let { kept += JsonObject(rule + ("apply" to it)) }
            }
            root = root.set("rules", JsonArray(kept).takeIf { it.isNotEmpty() })
        }

        /** Removes [keys] from the [block] of every patch. */
        fun stripPatches(block: String, keys: Collection<String>) = editPatches { p ->
            val b = p[block] as? JsonObject ?: return@editPatches p
            p.withBlock(block, b.without(keys))
        }

        /** The variant [v] (null: the base look) written into the base; `variants` dropped. */
        fun flatten(pack: LoadedTheme, v: ResolvedVariant?) {
            root = JsonObject(root - "variants")
            v ?: return
            v.palette?.let { vp ->
                if (pack.roles != null) {
                    setPalette(colors(vp))
                } else {
                    // A pack without colours of its own: the variant's are the palette (cascade), not a patch.
                    val flags = block("palette").filterKeys { it == "circadianCurve" || it == "batteryTint" }
                    root = root.withBlock("palette", JsonObject(flags + colors(vp)))
                }
            }
            v.flags?.let { f ->
                f.circadianCurve?.let { setPalette(mapOf("circadianCurve" to JsonPrimitive(it))) }
                f.batteryTint?.let { setPalette(mapOf("batteryTint" to JsonPrimitive(it))) }
                f.adaptiveGamePalette?.let { setEffect("adaptiveGamePalette", mapOf("adaptiveGamePalette" to JsonPrimitive(it))) }
            }
            v.surfaceId?.let { setEffect("surfaceStyle", mapOf("surfaceStyle" to str(it))) }
            v.shader?.let { setEffect("shaderPreset", shaderJson(it)) }
            v.wallpaper?.let { setEffect("wallpaperPath", mapOf("wallpaperPath" to str(it.path ?: "none"))) }
            v.cosmetics?.let { c ->
                c.focusFrame?.let { setEffect("focusFrameStyle", mapOf("focusFrameStyle" to str(it.name))) }
                c.iconMask?.let { setEffect("iconMask", mapOf("iconMask" to str(it.name))) }
                c.iconFilter?.let { setEffect("iconFilter", mapOf("iconFilter" to str(it.name))) }
                c.particles?.let { setEffect("foregroundParticles", mapOf("foregroundParticles" to str(it.name))) }
                c.backgroundFilter?.let { setEffect("backgroundFilter", mapOf("backgroundFilter" to str(it.name))) }
                c.uiFilter?.let { setEffect("uiFilter", uiFilterJson(it)) }
                c.bootIntro?.let { setEffect("bootIntro", mapOf("bootIntro" to str(it.name))) }
                c.launchCurtain?.let { setEffect("launchCurtain", mapOf("launchCurtain" to str(it.name))) }
            }
            if (v.gains != null || v.bgm != null) {
                var audio = block("audio")
                v.gains?.let { gains ->
                    val own = audio.obj("soundGains").filterKeys { k -> SoundRole.byKey(k) !in gains.keys }
                    audio = audio.set("soundGains", JsonObject(own + gains.entries.associate { (role, g) -> role.key to JsonPrimitive(g) }))
                }
                v.bgm?.let { audio = audio.without(setOf("bgmFile", "bgm")).set("bgmFile", str(it.path ?: "none")) }
                setBlock("audio", audio)
            }
            if (v.components != null) root = root.set("components", componentsJson(pack.components(v.id)))
        }

        /** The user's picks written into the base and taken out of every patch (current look). */
        fun applyUser(pack: LoadedTheme, picks: LookPicks, wallpaper: String?, date: LocalDate, notes: MutableList<String>) {
            val u = picks.user
            // Effects (2b.4f): each set slot wins in every context.
            val fx = u.effects.normalized()
            fun effect(unit: String, values: Map<String, JsonElement>) {
                setEffect(unit, values)
                stripPatches("effects", EFFECT_UNITS.getValue(unit))
            }
            fx.shader?.let { effect("shaderPreset", mapOf("shaderPreset" to str(it.name))) }
            fx.backgroundFilter.takeIf { it.isNotEmpty() }?.let { effect("backgroundFilter", mapOf("backgroundFilter" to str(it))) }
            fx.uiFilter.takeIf { it.isNotEmpty() && it != UiFilter.CUSTOM.name }?.let { effect("uiFilter", mapOf("uiFilter" to str(it))) }
            fx.focusFrameStyle.takeIf { it.isNotEmpty() }?.let { effect("focusFrameStyle", mapOf("focusFrameStyle" to str(it))) }
            fx.foregroundParticles.takeIf { it.isNotEmpty() }?.let { effect("foregroundParticles", mapOf("foregroundParticles" to str(it))) }
            fx.iconMask.takeIf { it.isNotEmpty() }?.let { effect("iconMask", mapOf("iconMask" to str(it))) }
            fx.bootIntro.takeIf { it.isNotEmpty() }?.let { effect("bootIntro", mapOf("bootIntro" to str(it))) }
            fx.launchCurtain.takeIf { it.isNotEmpty() }?.let { effect("launchCurtain", mapOf("launchCurtain" to str(it))) }
            // Surface style: only one the pack offers is drawn (LoadedTheme.surfaceFor).
            u.surfaceStyle.takeIf { it.isNotEmpty() && it in pack.surfaces }?.let { effect("surfaceStyle", mapOf("surfaceStyle" to str(it))) }
            // The colour layers.
            u.adaptiveGamePalette?.let { effect("adaptiveGamePalette", mapOf("adaptiveGamePalette" to JsonPrimitive(it))) }
            u.circadianCurve?.let {
                setPalette(mapOf("circadianCurve" to JsonPrimitive(it)))
                stripPatches("palette", setOf("circadianCurve"))
            }
            u.batteryTint?.let {
                setPalette(mapOf("batteryTint" to JsonPrimitive(it)))
                stripPatches("palette", setOf("batteryTint"))
            }
            // The Xross wallpaper, copied into the pack.
            wallpaper?.let { effect("wallpaperPath", mapOf("wallpaperPath" to str(it))) }
            layout(u.layout.normalized())
            // Components: overrides and rules never patch them (2b.7a), so the resolved block is the whole story.
            root = root.set("components", componentsJson(pack.components(picks.variant, u.components)))
            colorsAndWave(pack, picks, date, notes)
        }

        /** 2b.8a's layout values into the base `layout`, out of every rule's `layout` patch. */
        private fun layout(l: LayoutOverrides) {
            if (l.empty) return
            var base = block("layout")
            val strip = ArrayList<String>()
            l.preset?.let {
                base = base.without(setOf("preset", "crosshairX", "crosshairY")).set("preset", str(it))
                strip += listOf("preset", "crosshairX", "crosshairY")
            }
            l.uiScale?.let { base = base.set("uiScale", JsonPrimitive(it)); strip += "uiScale" }
            l.itemSpacingDp?.let { base = base.set("itemSpacingDp", JsonPrimitive(it)); strip += "itemSpacingDp" }
            l.focusedScale?.let { base = base.set("focusedScale", JsonPrimitive(it)); strip += "focusedScale" }
            setBlock("layout", base)
            stripPatches("layout", strip)
        }

        /**
         * The colours on screen as the palette ([dev.xross.launcher.theme.paletteFor], then
         * [dev.xross.launcher.theme.wavePalette]): a colour theme other than the pack's wins in
         * every context, and so does a wave tint's gradient; the wave values go into `wave`.
         */
        private fun colorsAndWave(pack: LoadedTheme, picks: LookPicks, date: LocalDate, notes: MutableList<String>) {
            val w = picks.wave
            var wave = block("wave")
                .set("speed", JsonPrimitive(w.speed))
                .set("lineCount", JsonPrimitive(w.lineCount))
                .set("turbulence", JsonPrimitive(w.turbulence))
                .set("ribbonThickness", JsonPrimitive(w.thickness))
            val hasPalette = pack.look(picks.variant).palette != null
            val packColors = picks.colorTheme == PACK_THEME && hasPalette
            val tint = WaveTint.byName(w.tint)
            val theme = XrossTheme.byName(picks.colorTheme)
            if (!packColors && theme == XrossTheme.ORIGINAL) notes += "Original's ${SeasonalPalette.describe(date, Locale.ENGLISH)} colors"
            // The palette behind the wave when the colour theme isn't the pack's.
            val themed: ThemePalette? = if (packColors) null else theme.palette(date)
            val tinted: ThemePalette? = when (tint) {
                WaveTint.THEME -> null
                WaveTint.CUSTOM -> w.colors.toPalette()
                else -> tint.palette
            }
            if (tinted != null) notes += "the ${tint.label} wave colors"
            when {
                themed != null -> {
                    // canvas, accent and accentSecondary are the colour theme's (or the tint's) everywhere.
                    val p = tinted?.copy(glow = themed.glow) ?: themed
                    val values = mapOf("canvas" to str(hex(p.top)), "accent" to str(hex(p.glow)), "accentSecondary" to str(hex(p.wave)))
                    if (hasPalette) {
                        setPalette(values)
                        stripPatches("palette", values.keys)
                    } else {
                        // The pack had no colours: none of its patches' colours ever showed.
                        setPalette(values, drop = COLOR_ROLES)
                        stripPatches("palette", COLOR_ROLES)
                    }
                    wave = wave.set("gradient", JsonArray(listOf(str(hex(p.top)), str(hex(p.bottom)))))
                }
                tinted != null -> {
                    // The pack's colours with a tint: the tint's gradient and ribbon win, the pack's accent (glow) stays.
                    val values = mapOf("canvas" to str(hex(tinted.top)), "accentSecondary" to str(hex(tinted.wave)))
                    setPalette(values)
                    stripPatches("palette", values.keys)
                    wave = wave.set("gradient", JsonArray(listOf(str(hex(tinted.top)), str(hex(tinted.bottom)))))
                }
            }
            setBlock("wave", wave)
        }
    }

    // endregion
}
