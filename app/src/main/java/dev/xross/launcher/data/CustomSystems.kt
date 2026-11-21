package dev.xross.launcher.data

import dev.xross.launcher.config.CustomSystem
import dev.xross.launcher.model.EmulatorOption
import dev.xross.launcher.model.Platform
import dev.xross.launcher.model.PlatformRegistry
import dev.xross.launcher.model.SystemOverride
import dev.xross.launcher.model.normalizeFolderName

/**
 * The merged system list (0.2.2.0 § 1): built-ins with the user's overrides, each also offering
 * the custom emulators (emulators.json) that name it, then the custom systems from systems.json.
 * A built-in id always wins a clash.
 */
object CustomSystems {

    fun platforms(s: XrossSettings): List<Platform> {
        val builtInIds = PlatformRegistry.builtIn.mapTo(HashSet()) { it.id }
        val byId = s.customEmulators.filterKeys { it.isNotBlank() }
        fun extraOptions(systemId: String): List<EmulatorOption> =
            byId.filter { (_, e) -> e.systems.any { it.trim() == systemId } }.keys.map { EmulatorOption(it) }

        val builtIns = PlatformRegistry.withOverrides(PlatformRegistry.builtIn, s.systemOverrides).map { p ->
            val extra = extraOptions(p.id).filter { o -> p.emulators.none { it.key == o.key } }
            if (extra.isEmpty()) p else p.copy(emulators = p.emulators + extra)
        }
        val custom = s.customSystems.filterKeys { it !in builtInIds }.mapNotNull { (id, c) -> platform(id, c, extraOptions(id)) }
        return builtIns + custom
    }

    /** One custom system as a [Platform]; null when it lists no file types. */
    fun platform(id: String, c: CustomSystem, extra: List<EmulatorOption> = emptyList()): Platform? {
        val extensions = c.extensions.map(SystemOverride::normalizeExtension).filter { it.isNotEmpty() }.toSet()
        if (extensions.isEmpty()) return null
        val folders = (c.folders.ifEmpty { listOf(id) }).map(::normalizeFolderName).filter { it.isNotEmpty() }.toSet()
        val named = c.emulators.map(::option)
        return Platform(
            id = id,
            name = c.name.ifBlank { id },
            shortName = c.shortName.ifBlank { id.uppercase().take(4) },
            folderAliases = folders.ifEmpty { setOf(normalizeFolderName(id)) },
            extensions = extensions,
            emulators = (named + extra).distinctBy { it.key },
            custom = true,
            logo = c.logo.ifBlank { null },
        )
    }

    /** "retroarch:mgba" → RetroArch with that core; anything else is an emulator id. */
    fun option(key: String): EmulatorOption {
        val i = key.indexOf(':')
        return if (i > 0) EmulatorOption(key.substring(0, i), key.substring(i + 1)) else EmulatorOption(key)
    }

    /** Problems with the custom systems, for Settings ▸ Emulators. */
    fun problems(s: XrossSettings): List<Pair<String, String>> = buildList {
        val builtInIds = PlatformRegistry.builtIn.mapTo(HashSet()) { it.id }
        for ((id, c) in s.customSystems) {
            if (id in builtInIds) add(id to "Uses the id of a built-in system; add folders with extraFolders instead")
            else if (c.extensions.isEmpty()) add(id to "Lists no file extensions, so nothing would show")
            for (alias in c.folders.map(::normalizeFolderName)) {
                val owner = PlatformRegistry.builtIn.firstOrNull { alias in it.folderAliases }
                if (owner != null) add(id to "Folder \"$alias\" already belongs to ${owner.shortName}")
            }
        }
    }
}
