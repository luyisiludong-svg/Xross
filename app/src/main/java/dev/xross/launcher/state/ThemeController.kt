package dev.xross.launcher.state

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import dev.xross.launcher.audio.UiSound
import dev.xross.launcher.audio.UiSounds
import dev.xross.launcher.data.CredentialStore
import dev.xross.launcher.data.SettingsStore
import dev.xross.launcher.data.XrossSettings
import dev.xross.launcher.storage.XrossEnvironment
import dev.xross.launcher.theme.PACK_THEME
import dev.xross.launcher.theme.QuickProfile
import dev.xross.launcher.theme.XrossTheme
import dev.xross.launcher.theme.pack.ArchiveResult
import dev.xross.launcher.theme.pack.BadgePill
import dev.xross.launcher.theme.pack.ComponentRow
import dev.xross.launcher.theme.pack.EffectSlot
import dev.xross.launcher.theme.pack.InstallResult
import dev.xross.launcher.theme.pack.ResolvedComponents
import dev.xross.launcher.theme.pack.StagedTheme
import dev.xross.launcher.theme.pack.StatusSide
import dev.xross.launcher.theme.pack.StatusSlot
import dev.xross.launcher.theme.pack.TelemetryMode
import dev.xross.launcher.theme.pack.ThemeArchive
import dev.xross.launcher.theme.pack.ThemeDefaults
import dev.xross.launcher.theme.pack.ThemePackLoader
import dev.xross.launcher.theme.pack.ThemeRuntime
import dev.xross.launcher.theme.pack.TopLeftClusterMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Settings ▸ Theme, split out of [XmbViewModel] (Architecture Assessment #6, Phase 2b.8-prep):
 * theme pack, variant, colour theme, quick profile, colour switches, surface style, effects,
 * components (status slots, badge pills), the Xross wallpaper, the `.xtheme` import with its
 * pending Replace / Keep both, and Reset to theme defaults. The view model owns one of these and
 * hands it every [Command] first ([onCommand]); 2b.8's layout mixer and export add theirs here.
 */
class ThemeController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val themes: ThemeRuntime,
    private val packs: ThemePackLoader,
    private val settings: SettingsStore,
    private val environment: XrossEnvironment,
    private val credentials: CredentialStore,
    private val sounds: UiSounds,
    private val host: Host,
) {
    /** What the controller needs from the menu around it. */
    interface Host {
        fun toast(message: String)
        /** The error sound, then [message]. */
        fun fail(message: String)
        /** The error sound alone: the action isn't allowed here. */
        fun refuse()
        fun emit(effect: UiEffect)
        /** Opens (or reopens) [menu] as the Options menu. */
        fun showOptions(menu: OptionsMenuState)
        /** The status bar as drawn now (2b.7a). */
        fun components(): ResolvedComponents
        /** A Components row that needs followed users changed: fetch them for [components]. */
        fun friendsWanted(components: ResolvedComponents)
        /** A Photo item's URI, for Set as Xross wallpaper. */
        fun photoUri(photoId: Long): Uri?
    }

    /** Runs [command] when it is a theme command; false hands it back to the view model. */
    fun onCommand(command: Command): Boolean {
        when (command) {
            Command.CycleTheme -> settings.update {
                // The theme pack's own colors join the cycle when the active pack has them.
                val names = XrossTheme.entries.map { t -> t.name } +
                    listOfNotNull(PACK_THEME.takeIf { _ -> themes.active.value.look(it.themeVariant).palette != null })
                val i = names.indexOf(it.theme).coerceAtLeast(-1)
                it.copy(theme = names[(i + 1) % names.size])
            }
            is Command.SetTheme -> {
                val known = command.name == PACK_THEME || XrossTheme.entries.any { it.name == command.name }
                if (known) settings.update { it.copy(theme = command.name) }
            }
            Command.CycleThemePack -> {
                val all = themes.packs.value
                if (all.size <= 1) {
                    scope.launch {
                        val found = themes.rediscover()
                        if (found.size <= 1) host.fail("No theme packs in ${environment.current.themes.absolutePath}")
                        else applyThemePack(found[(found.indexOfFirst { it.id == settings.current.themePack } + 1) % found.size].id)
                    }
                } else {
                    val i = all.indexOfFirst { it.id == settings.current.themePack }
                    applyThemePack(all[(i + 1) % all.size].id)
                }
            }
            is Command.SetThemePack -> applyThemePack(command.id)
            Command.ReloadThemePacks -> scope.launch {
                val found = themes.rediscover()
                themes.reload()
                val skipped = themes.rejected.value
                host.toast(
                    buildString {
                        append(if (found.size == 1) "No theme packs in Xross/themes" else "${found.size} theme packs")
                        if (skipped.isNotEmpty()) append(" · skipped ${skipped.size}: ${skipped.first().id} (${skipped.first().reason})")
                    },
                )
            }
            Command.CycleThemeVariant -> {
                val ids = listOf("") + themes.active.value.variants.map { it.id }
                val i = ids.indexOf(themes.active.value.variant(settings.current.themeVariant)?.id.orEmpty())
                setThemeVariant(ids[(i + 1) % ids.size])
            }
            is Command.SetThemeVariant -> setThemeVariant(command.id)
            Command.ImportThemeArchive -> host.emit(UiEffect.PickThemeArchive)
            Command.ReplaceImportedTheme -> resolvePendingImport(keepBoth = false)
            Command.KeepBothImportedThemes -> resolvePendingImport(keepBoth = true)
            Command.CycleQuickProfile -> {
                val all = QuickProfile.entries
                setQuickProfile(all[(QuickProfile.byName(settings.current.quickProfile).ordinal + 1) % all.size])
            }
            is Command.SetQuickProfile -> setQuickProfile(QuickProfile.byName(command.name))
            is Command.ToggleColorSwitch -> {
                val pack = themes.active.value.flags
                settings.update {
                    when (command.which) {
                        ColorSwitch.ADAPTIVE -> it.copy(adaptiveGamePalette = !(it.adaptiveGamePalette ?: pack.adaptiveGamePalette ?: true))
                        ColorSwitch.CIRCADIAN -> it.copy(circadianCurve = !(it.circadianCurve ?: pack.circadianCurve ?: false))
                        ColorSwitch.BATTERY -> it.copy(batteryTint = !(it.batteryTint ?: pack.batteryTint ?: false))
                    }
                }
                colorSwitchBlockedNote(command.which)?.let(host::toast)
            }
            is Command.SetColorSwitch -> {
                settings.update {
                    when (command.which) {
                        ColorSwitch.ADAPTIVE -> it.copy(adaptiveGamePalette = command.on)
                        ColorSwitch.CIRCADIAN -> it.copy(circadianCurve = command.on)
                        ColorSwitch.BATTERY -> it.copy(batteryTint = command.on)
                    }
                }
                colorSwitchBlockedNote(command.which)?.let(host::toast)
            }
            Command.CycleSurfaceStyle -> settings.update {
                // "" (the pack's own) first, then every surface the active pack offers.
                val ids = listOf("") + themes.active.value.surfaces.keys
                val i = ids.indexOf(it.surfaceStyle).coerceAtLeast(0)
                it.copy(surfaceStyle = ids[(i + 1) % ids.size])
            }
            is Command.SetXrossWallpaper -> host.photoUri(command.photoId)?.let { uri ->
                settings.update { it.copy(customWallpaper = uri.toString()) }
                host.toast("Xross wallpaper set")
            }
            Command.ResetXrossWallpaper -> {
                val old = settings.current.customWallpaper
                settings.update { it.copy(customWallpaper = "") }
                releaseWallpaperGrant(old)
                host.toast(if (themes.active.value.hasWallpaper) "Using the theme's wallpaper" else "Xross wallpaper removed")
            }
            Command.PickXrossWallpaper -> host.emit(UiEffect.PickWallpaperImage)
            is Command.SetStatusSlots -> settings.update { it.copy(componentOverrides = it.componentOverrides.withSlots(command.side, command.slots)) }
            is Command.EditStatusSlot -> editStatusSlot(command)
            is Command.CycleComponent -> setComponent(command.row, command.row.next(settings.current.componentOverrides[command.row]))
            is Command.SetComponent -> setComponent(command.row, command.value)
            is Command.SetBadgePills -> editBadgePills(command.pills, null)
            is Command.ToggleBadgePill -> editBadgePills(ComponentMenus.togglePill(host.components().pills, command.pill), command.pill)
            is Command.CycleEffect -> setEffect(command.slot, command.slot.next(settings.current.effectOverrides[command.slot]))
            is Command.SetEffect -> setEffect(command.slot, command.value)
            Command.ResetThemeDefaults -> resetThemeDefaults()
            else -> return false
        }
        return true
    }

    // region Pack, variant, colours

    private fun setQuickProfile(profile: QuickProfile) {
        settings.update { it.copy(quickProfile = profile.name) }
        host.toast("Quick profile: ${profile.label}")
    }

    /** Why a color switch has no effect right now (the quick profile turns it off), if it doesn't. */
    private fun colorSwitchBlockedNote(which: ColorSwitch): String? {
        val profile = QuickProfile.byName(settings.current.quickProfile)
        val blocked = if (which == ColorSwitch.ADAPTIVE) !profile.allowsArt else !profile.allowsAmbient
        return if (blocked) "Saved; ${profile.label} keeps it off" else null
    }

    private fun applyThemePack(id: String) {
        scope.launch {
            val loaded = themes.select(id)
            settings.update { s ->
                // Another pack starts on its base look; the same pack keeps its variant.
                var next = s.copy(themePack = loaded.id, themeVariant = if (loaded.id == s.themePack) s.themeVariant else "")
                next = withPackColors(next, loaded.look(next.themeVariant).palette != null)
                // Its wave defaults, keeping the user's color tint.
                loaded.wave?.let { w -> next = WaveEditor.reset(next, w).copy(waveTint = s.waveTint, waveEnabled = s.waveEnabled) }
                next
            }
            sounds.useTheme(loaded)
            if (loaded.id != id) {
                host.fail("Couldn't load that theme pack; using Default")
            } else {
                sounds.play(UiSound.OK)
                host.toast("Theme pack: ${loaded.info.name}" + (loaded.problems.firstOrNull()?.let { " · $it" } ?: ""))
            }
        }
    }

    /** A pack look with its own colors switches to them; one without falls back to a fixed theme. */
    private fun withPackColors(s: XrossSettings, hasPalette: Boolean) = when {
        hasPalette -> s.copy(theme = PACK_THEME)
        s.theme == PACK_THEME -> s.copy(theme = XrossTheme.LAVENDER.name)
        else -> s
    }

    private fun setThemeVariant(id: String) {
        val pack = themes.active.value
        val variant = pack.variant(id)
        settings.update { withPackColors(it.copy(themeVariant = variant?.id.orEmpty()), pack.look(variant?.id.orEmpty()).palette != null) }
        sounds.play(UiSound.OK)
        host.toast("Theme variant: ${variant?.name ?: "Standard"}")
    }

    /** Settings ▸ Theme ▸ Reset to theme defaults: the user layer ([dev.xross.launcher.theme.pack.UserLook]) back to the pack's. */
    private fun resetThemeDefaults() {
        val before = settings.current
        val after = ThemeDefaults.reset(before)
        if (after == before) {
            host.toast("Already the theme's own look")
        } else {
            settings.update { ThemeDefaults.reset(it) }
            releaseWallpaperGrant(before.customWallpaper)
            host.toast("Back to ${themes.active.value.info.name}'s own look")
        }
    }

    // endregion

    // region Wallpaper

    /**
     * Settings ▸ Theme ▸ Xross wallpaper ▸ Pick a picture: keeps read access to [uri] across
     * restarts (a document the picker granted), saves it as the Xross wallpaper and lets go of the
     * last picked one.
     */
    fun onWallpaperPicked(uri: Uri?) {
        uri ?: return
        val resolver = context.contentResolver
        val kept = runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }.isSuccess
        val old = settings.current.customWallpaper
        settings.update { it.copy(customWallpaper = uri.toString()) }
        if (old != uri.toString()) releaseWallpaperGrant(old)
        host.toast(if (kept) "Xross wallpaper set" else "Xross wallpaper set (until Xross restarts: the picker gave no lasting access)")
    }

    /** Lets go of a picked wallpaper's lasting read grant once nothing uses it (Photo's MediaStore URIs hold none). */
    private fun releaseWallpaperGrant(location: String) {
        if (!location.startsWith("content://")) return
        val resolver = context.contentResolver
        val uri = location.toUri()
        if (resolver.persistedUriPermissions.none { it.uri == uri }) return
        runCatching { resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    // endregion

    // region Effects and components

    /** Settings ▸ Theme (2b.4f): [slot] set to [value] over the pack, with a note where it can't show. */
    private fun setEffect(slot: EffectSlot, value: String) {
        settings.update { it.copy(effectOverrides = it.effectOverrides.with(slot, value)) }
        val v = settings.current.effectOverrides[slot]
        val note = when {
            slot == EffectSlot.UI_FILTER && v.isNotEmpty() && v != "NONE" &&
                !dev.xross.launcher.ui.UiFilterSupport.supported() -> "UI filters need Android 13 or later"
            slot == EffectSlot.SHADER && !settings.current.waveEnabled -> "Turn Wave background on to see it"
            slot == EffectSlot.BOOT_INTRO -> "Shown on the next cold start"
            else -> null
        }
        note?.let(host::toast)
    }

    /**
     * Settings ▸ Theme ▸ Components (2b.7a): one move of a status-bar slot. The sides it changes
     * are written as the user's (the other keeps following the pack), and the slot list reopens
     * on the slot so it can be moved again.
     */
    private fun editStatusSlot(command: Command.EditStatusSlot) {
        val pack = themes.active.value
        val before = settings.current
        val shown = pack.components(before.themeVariant, before.componentOverrides)
        val (left, right) = ComponentMenus.edit(shown, command.side, command.slot, command.edit) ?: return host.refuse()
        settings.update { st ->
            var o = st.componentOverrides
            if (left != shown.left) o = o.withSlots(StatusSide.LEFT, left)
            if (right != shown.right) o = o.withSlots(StatusSide.RIGHT, right)
            st.copy(componentOverrides = o)
        }
        val after = settings.current
        val now = pack.components(after.themeVariant, after.componentOverrides)
        val userSet = (if (command.side == StatusSide.LEFT) after.componentOverrides.statusBarLeft else after.componentOverrides.statusBarRight) != null
        val options = ComponentMenus.options(command.side, now, pack.components(after.themeVariant), userSet, now.logo in pack.logos)
        val title = if (command.side == StatusSide.LEFT) "Status bar left" else "Status bar right"
        host.showOptions(ComponentMenus.reopen(title, options, command.slot, command.edit))
        if (command.edit == SlotEdit.REMOVE && command.slot == StatusSlot.AVATAR) {
            host.toast("Avatar removed from the status bar · the User Card stays in Settings ▸ Accounts")
        }
    }

    /**
     * Settings ▸ Theme ▸ Components ▸ Title badges (2b.7b): the user's badges set to [pills] (null:
     * the pack's), and the list reopened on the badge just turned on or off ([toggled]).
     */
    private fun editBadgePills(pills: List<BadgePill>?, toggled: BadgePill?) {
        settings.update { it.copy(componentOverrides = it.componentOverrides.withPills(pills)) }
        val after = settings.current
        val pack = themes.active.value
        val now = pack.components(after.themeVariant, after.componentOverrides)
        val options = ComponentMenus.pillOptions(now.pills, pack.components(after.themeVariant).pills, after.componentOverrides.badgePills != null)
        host.showOptions(ComponentMenus.reopenPills("Title badges", options, toggled, pills?.isEmpty() == true))
    }

    private fun setComponent(row: ComponentRow, value: String?) {
        settings.update { it.copy(componentOverrides = it.componentOverrides.with(row, value)) }
        // Settled from what was just saved: the view model's components flow catches up a moment later.
        val c = settings.current.let { st -> themes.active.value.components(st.themeVariant, st.componentOverrides) }
        val note = when (row) {
            ComponentRow.BATTERY -> "Battery isn't in the status bar".takeIf { !c.shows(StatusSlot.BATTERY) }
            ComponentRow.CLOCK -> "Clock isn't in the status bar".takeIf { !c.shows(StatusSlot.CLOCK) }
            ComponentRow.TOP_LEFT ->
                "Link a RetroAchievements account to see who you follow".takeIf {
                    c.topLeft == TopLeftClusterMode.SOCIAL_STACK && !credentials.current.retroAchievementsLinked
                }
            ComponentRow.TELEMETRY ->
                "Link a RetroAchievements account to see your progress".takeIf {
                    c.telemetry != TelemetryMode.OFF && !credentials.current.retroAchievementsLinked
                }
            ComponentRow.MINI_PLAYER, ComponentRow.GLYPHS -> null
        }
        note?.let(host::toast)
        if (row == ComponentRow.TOP_LEFT || row == ComponentRow.TELEMETRY) host.friendsWanted(c)
    }

    // endregion

    // region .xtheme import

    private val themeArchive by lazy {
        ThemeArchive(
            themesDir = { environment.current.themes },
            check = { dir -> packs.inspect(dir) },
        )
    }

    /** A checked import waiting for "Replace" / "Keep both" because its id is installed. */
    private var pendingImport: StagedTheme? = null

    /** The picked `.xtheme`: copied to the cache, checked, unpacked, and installed or held for a prompt. */
    fun onThemeArchivePicked(uri: Uri?) {
        uri ?: return
        scope.launch {
            pendingImport?.let { old -> withContext(Dispatchers.IO) { themeArchive.discard(old) } }
            pendingImport = null
            host.toast("Checking theme file…")
            when (val result = withContext(Dispatchers.IO) { stageThemeArchive(uri) }) {
                is ArchiveResult.Rejected -> host.fail("Theme not imported: ${result.reason}")
                is ArchiveResult.Staged -> {
                    val staged = result.theme
                    if (!staged.exists) return@launch installTheme(staged, replace = false)
                    pendingImport = staged
                    sounds.play(UiSound.OPTION)
                    val options = listOf(
                        MenuOption("Replace ${staged.id}", detail = "Overwrites the installed pack's files", command = Command.ReplaceImportedTheme),
                        MenuOption("Keep both", detail = "Installs it as ${themeArchive.asCopy(staged).id}", command = Command.KeepBothImportedThemes),
                    )
                    host.showOptions(OptionsMenuState.open("${staged.name} is already installed", options))
                }
            }
        }
    }

    private fun stageThemeArchive(uri: Uri): ArchiveResult {
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "theme.xtheme"
        val copy = File(context.cacheDir, "import.xtheme")
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: return ArchiveResult.Rejected("Couldn't open $name")
            // The compressed file can't be meaningfully bigger than the 50 MB it may unpack to.
            val cap = ThemeArchive.MAX_BYTES + ThemeArchive.MB
            var total = 0L
            input.use { inp ->
                copy.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > cap) return ArchiveResult.Rejected("$name is larger than 50 MB")
                        out.write(buf, 0, n)
                    }
                }
            }
            return themeArchive.stage(copy, name)
        } catch (e: java.io.IOException) {
            return ArchiveResult.Rejected("Couldn't read $name")
        } catch (e: SecurityException) {
            return ArchiveResult.Rejected("No permission to read $name")
        } finally {
            copy.delete()
        }
    }

    private fun resolvePendingImport(keepBoth: Boolean) {
        val staged = pendingImport ?: return host.toast("Pick the theme file again")
        pendingImport = null
        scope.launch { installTheme(if (keepBoth) themeArchive.asCopy(staged) else staged, replace = !keepBoth) }
    }

    /** Installs [staged], rescans the packs and switches to it. */
    private suspend fun installTheme(staged: StagedTheme, replace: Boolean) {
        when (val r = withContext(Dispatchers.IO) { themeArchive.install(staged, replace) }) {
            is InstallResult.Failed -> {
                withContext(Dispatchers.IO) { themeArchive.discard(staged) }
                host.fail("Theme not imported: ${r.reason}")
            }
            is InstallResult.Installed -> {
                themes.rediscover()
                // Replacing the pack in use: read its new files even though the id is the same.
                if (themes.active.value.id == r.id) themes.reload()
                applyThemePack(r.id)
            }
        }
    }

    // endregion
}
