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
import dev.xross.launcher.input.NavAction
import dev.xross.launcher.storage.XrossEnvironment
import dev.xross.launcher.theme.PACK_THEME
import dev.xross.launcher.theme.QuickProfile
import dev.xross.launcher.theme.XrossTheme
import dev.xross.launcher.theme.pack.ArchiveResult
import dev.xross.launcher.theme.pack.ArchiveWrite
import dev.xross.launcher.theme.pack.BadgePill
import dev.xross.launcher.theme.pack.ComponentRow
import dev.xross.launcher.theme.pack.EffectSlot
import dev.xross.launcher.theme.pack.InstallResult
import dev.xross.launcher.theme.pack.ExportPlan
import dev.xross.launcher.theme.pack.ExportReader
import dev.xross.launcher.theme.pack.ExportResult
import dev.xross.launcher.theme.pack.ExportScope
import dev.xross.launcher.theme.pack.ExportSource
import dev.xross.launcher.theme.pack.LoadedTheme
import dev.xross.launcher.theme.pack.LookExport
import dev.xross.launcher.theme.pack.LookExporter
import dev.xross.launcher.theme.pack.LookPicks
import dev.xross.launcher.theme.pack.PackLoad
import dev.xross.launcher.theme.pack.ThemeExport
import dev.xross.launcher.theme.pack.UserLook
import dev.xross.launcher.theme.pack.LayoutOverrides
import dev.xross.launcher.theme.pack.ResolvedComponents
import dev.xross.launcher.theme.pack.StagedTheme
import dev.xross.launcher.theme.pack.StatusSide
import dev.xross.launcher.theme.pack.StatusSlot
import dev.xross.launcher.theme.pack.TelemetryMode
import dev.xross.launcher.theme.pack.ThemeArchive
import dev.xross.launcher.data.ThemeDefaults
import dev.xross.launcher.theme.pack.ThemePackLoader
import dev.xross.launcher.theme.pack.ThemeRuntime
import dev.xross.launcher.theme.pack.TopLeftClusterMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import dev.xross.launcher.data.of
import dev.xross.launcher.data.writeTo
import dev.xross.launcher.platform.AgslSupport
import dev.xross.launcher.theme.WaveTint
import dev.xross.launcher.theme.BatteryLevel
import dev.xross.launcher.theme.ResolvedLook
import dev.xross.launcher.theme.ThemeLookResolver
import dev.xross.launcher.theme.minuteOfDay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalTime

/**
 * Settings ▸ Theme, split out of [XmbViewModel] (Architecture Assessment #6, Phase 2b.8-prep):
 * theme pack, variant, colour theme, quick profile, colour switches, surface style, effects,
 * components (status slots, badge pills), the Layout panel (2b.8a), the Xross wallpaper, the `.xtheme` import with its
 * pending Replace / Keep both, Reset to theme defaults and Export theme… (2b.8b). The view model
 * owns one of these and hands it every theme [Command] ([onCommand]).
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
        /** Closes the Options menu (a panel opens in its place). */
        fun dismissOptions()
        /** Opens the gamepad keyboard for a name (2b.8b: an exported theme's), in place of the Options menu. */
        fun openNameEntry(entry: NameEntry)
        /** The menu's busy icon, while a theme is exported. */
        fun busy(on: Boolean)
    }

    private val _layoutEditor = MutableStateFlow<LayoutEditorState?>(null)

    /** Settings ▸ Theme ▸ Layout while it is open (2b.8a). */
    val layoutEditor: StateFlow<LayoutEditorState?> = _layoutEditor.asStateFlow()

    /**
     * The look the menu draws with (Architecture Assessment §7.4): the whole cascade
     * ([ThemeLookResolver]) over the active pack, the settings, the menu's [focus] and the
     * [battery], settled by [LookFlow] (the focused system after its rest delay, battery and clock
     * only when the pack's rules read them). The Activity collects this one value. Call it once:
     * each call starts its own collection in [scope].
     */
    fun resolvedLook(
        focus: StateFlow<LookFocus>,
        battery: StateFlow<BatteryLevel?>,
        clock: () -> LocalTime = LocalTime::now,
    ): StateFlow<ResolvedLook> {
        val first = LookFlow.of(themes.active.value, settings.current, focus.value, battery.value, minuteOfDay(clock()))
        return LookFlow.inputs(themes.active, settings.settings, focus, battery, clock)
            .map(ThemeLookResolver::resolve)
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, ThemeLookResolver.resolve(first))
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
                settings.update { it.copy(customWallpaper = uri.toString(), wallpaperFolder = "", wallpaperAlbum = "") }
                host.toast("Xross wallpaper set")
            }
            Command.ResetXrossWallpaper -> {
                val old = settings.current.customWallpaper
                settings.update { it.copy(customWallpaper = "", wallpaperFolder = "", wallpaperAlbum = "") }
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
            Command.OpenLayoutSettings -> openLayoutSettings()
            Command.ExportTheme -> openExport(themes.active.value.id)
            is Command.ExportThemePack -> openExport(command.id)
            is Command.ChooseExportScope -> chooseExportScope(command.packId, command.scope, command.share)
            Command.ConfirmThemeExport -> confirmExport()
            Command.SwitchToExportedTheme -> switchToExport()
            is Command.ShareThemePack -> openExport(command.id, share = true)
            Command.ShareExportedTheme -> shareExported()
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

    // region Layout mixer (2b.8a)

    private fun openLayoutSettings() {
        host.dismissOptions()
        _layoutEditor.value = LayoutEditorState()
    }

    /** Input while the Layout panel is open; false when it isn't. */
    fun onLayoutAction(action: NavAction): Boolean {
        val editor = _layoutEditor.value ?: return false
        applyLayoutOutcome(editor, LayoutEditor.onAction(editor, action, settings.current.layoutOverrides, themes.active.value.layout, layoutContext()))
        return true
    }

    /** The Layout panel's rows for the look on screen: its menu style and the styles the pack's variants offer. */
    fun layoutContext(): LayoutPanelContext {
        val pack = themes.active.value
        val variant = pack.variant(settings.current.themeVariant)
        val active = variant?.menuStyle ?: pack.layout.menuStyle
        return LayoutPanelContext.of(active, pack.layout.menuStyle, pack.variants.map { it.id to it.menuStyle })
    }

    private fun applyLayoutOutcome(before: LayoutEditorState, outcome: LayoutEditorOutcome) {
        when {
            outcome.close -> return closeLayoutSettings()
            outcome.blocked -> host.refuse()
            outcome.variant != null -> setThemeVariant(outcome.variant)
            outcome.overrides != null -> {
                saveLayout(outcome.overrides)
                sounds.play(UiSound.MOVE)
            }
            outcome.state != before -> sounds.play(UiSound.MOVE)
        }
        if (outcome.state != before) _layoutEditor.value = outcome.state
    }

    /** Each step is saved at once: the cascade re-resolves and the menu behind the panel moves. */
    private fun saveLayout(overrides: LayoutOverrides) {
        settings.update { it.copy(layoutOverrides = overrides.normalized()) }
    }

    /** A touch on the Layout panel. */
    fun onEvent(event: XmbEvent.Layout) {
        when (event) {
            is XmbEvent.Layout.RowTap -> onLayoutRowTapped(event.index)
            is XmbEvent.Layout.Slider -> onLayoutSliderChanged(event.index, event.fraction)
            XmbEvent.Layout.Close -> closeLayoutSettings()
        }
    }

    fun closeLayoutSettings() {
        if (_layoutEditor.value != null) {
            sounds.play(UiSound.BACK)
            _layoutEditor.value = null
        }
    }

    /** Something else took the screen (Now Playing): close without a sound. */
    fun dismissLayoutSettings() {
        _layoutEditor.value = null
    }

    /** Touch: tap a row to focus it; tapping the focused row acts like Confirm. */
    fun onLayoutRowTapped(index: Int) {
        val editor = _layoutEditor.value ?: return
        if (index == editor.index) {
            onLayoutAction(NavAction.CONFIRM)
        } else if (index in layoutContext().rows.indices) {
            sounds.play(UiSound.MOVE)
            _layoutEditor.value = editor.copy(index = index)
        }
    }

    /** Touch: a slider dragged or tapped at [fraction] of its track. */
    fun onLayoutSliderChanged(index: Int, fraction: Float) {
        val editor = _layoutEditor.value ?: return
        val row = layoutContext().rows.getOrNull(index) ?: return
        if (editor.index != index) _layoutEditor.value = editor.copy(index = index)
        val user = settings.current.layoutOverrides
        val next = LayoutEditor.setFraction(row, user, fraction)
        if (next != user) saveLayout(next)
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
        settings.update { it.copy(customWallpaper = uri.toString(), wallpaperFolder = "", wallpaperAlbum = "") }
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
                !AgslSupport.supported() -> "UI filters need Android 13 or later"
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
            ComponentRow.MINI_PLAYER, ComponentRow.GLYPHS, ComponentRow.TILE_GLOW -> null
        }
        note?.let(host::toast)
        if (row == ComponentRow.TOP_LEFT || row == ComponentRow.TELEMETRY) host.friendsWanted(c)
    }

    // endregion

    // region Export (2b.8b)

    /** A built export waiting for the confirm panel's Export (or Share, into an `.xtheme`, 2b.8c). */
    private data class ExportDraft(
        val pack: LoadedTheme,
        val export: LookExport,
        val wallpaper: String,
        val plan: ExportPlan? = null,
        val share: Boolean = false,
    ) {
        /** It carries a picture the user picked from their photos. */
        val photo: Boolean get() = export.files.any { it.source == ExportSource.UserWallpaper }
    }

    /** What was last written, for Switch to and Share as .xtheme…. */
    private data class Exported(val id: String, val name: String, val scope: ExportScope, val photo: Boolean = false)

    /** The pack whose scope list is open (a non-active one is loaded for it). */
    private var exportPack: LoadedTheme? = null
    private var exportDraft: ExportDraft? = null
    private var exported: Exported? = null

    private val themeExport by lazy { ThemeExport(themesDir = { environment.current.themes }) }

    /** Opens the scope list for pack [id] (to share as an `.xtheme` when [share]): the active one at once, another after it loads. */
    private fun openExport(id: String, share: Boolean = false) {
        exportDraft = null
        val active = themes.active.value
        if (id == active.id) return showExportScopes(active, share)
        scope.launch {
            val pack = withContext(Dispatchers.IO) { (packs.loadStrict(id) as? PackLoad.Ok)?.theme }
                ?: return@launch host.fail("Couldn't load $id to ${if (share) "share" else "export"} it")
            showExportScopes(pack, share)
        }
    }

    private fun showExportScopes(pack: LoadedTheme, share: Boolean) {
        exportPack = pack
        sounds.play(UiSound.OPTION)
        host.showOptions(OptionsMenuState.open("${if (share) "Share" else "Export"} ${pack.info.name}", exportOptions(pack, share)))
    }

    /**
     * The scope list for [pack]: Whole pack, one row per variant when it has them (the base look
     * included), and Current look for the active pack only.
     */
    fun exportOptions(pack: LoadedTheme, share: Boolean = false): List<MenuOption> = buildList {
        val name = pack.info.name
        val n = pack.variants.size
        add(
            MenuOption(
                "Whole pack",
                detail = if (n == 0) "$name, as it ships" else "$name, $n variant" + (if (n == 1) "" else "s"),
                command = Command.ChooseExportScope(pack.id, ExportScope.WholePack, share),
            ),
        )
        if (n > 0) {
            for (id in listOf("") + pack.variants.map { it.id }) {
                add(
                    MenuOption(
                        "${LookExporter.variantName(pack, id)} only",
                        detail = "One look, without your picks",
                        command = Command.ChooseExportScope(pack.id, ExportScope.Variant(id), share),
                    ),
                )
            }
        }
        if (pack.id == themes.active.value.id) {
            val picks = UserLook.of(settings.current).changes()
            add(
                MenuOption(
                    "Current look",
                    detail = if (picks.isEmpty()) "What is on screen" else "With your picks: " + picks.joinToString(", "),
                    command = Command.ChooseExportScope(pack.id, ExportScope.CurrentLook, share),
                ),
            )
        }
    }

    private fun exportPackFor(id: String): LoadedTheme? =
        exportPack?.takeIf { it.id == id } ?: themes.active.value.takeIf { it.id == id }

    private fun chooseExportScope(packId: String, exportScope: ExportScope, share: Boolean) {
        val pack = exportPackFor(packId) ?: return host.toast("Pick the pack to export again")
        if (exportScope == ExportScope.CurrentLook && pack.id != themes.active.value.id) return host.refuse()
        val name = LookExporter.defaultName(pack, exportScope).take(MAX_EXPORT_NAME)
        host.openNameEntry(
            NameEntry(
                if (share) "Name the shared theme" else "Name the exported theme",
                NamePurpose.ExportTheme(packId, exportScope, share),
                SearchState(query = name),
                maxLength = MAX_EXPORT_NAME,
            ),
        )
    }

    /** The name entry's Done: builds the export (off the main thread) and shows what goes in. */
    fun onExportNamed(purpose: NamePurpose.ExportTheme, text: String) {
        val name = text.trim().replace(Regex("\\s+"), " ").take(MAX_EXPORT_NAME)
        if (name.isEmpty()) return host.toast("Type a name first")
        val pack = exportPackFor(purpose.packId) ?: return host.toast("Pick the pack to export again")
        val s = settings.current
        scope.launch {
            host.busy(true)
            val draft = try {
                withContext(Dispatchers.IO) { buildExport(pack, purpose.scope, name, s).copy(share = purpose.share) }
            } finally {
                host.busy(false)
            }
            exportDraft = draft
            sounds.play(UiSound.OPTION)
            host.showOptions(OptionsMenuState.open("${if (draft.share) "Share" else "Export"} as $name", confirmOptions(draft, s)))
        }
    }

    private fun buildExport(pack: LoadedTheme, exportScope: ExportScope, name: String, s: XrossSettings): ExportDraft {
        val wallpaper = if (exportScope == ExportScope.CurrentLook) s.customWallpaper.trim() else ""
        val picks = LookPicks.of(s, wallpaperType = wallpaper.takeIf { it.isNotEmpty() }?.let(::wallpaperType))
        val available = if (exportScope == ExportScope.WholePack) packs.packFiles(pack.info) else emptySet()
        val export = LookExporter.export(pack, exportScope, name, java.time.LocalDate.now(), available, picks)
        val draft = ExportDraft(pack, export, wallpaper)
        return draft.copy(plan = themeExport.plan(export, reader(draft)))
    }

    /** The confirm panel: what goes in, its size and the licence note, then Export. */
    private fun confirmOptions(draft: ExportDraft, s: XrossSettings): List<MenuOption> {
        val e = draft.export
        val plan = draft.plan ?: ExportPlan(e.files.size, 0, emptyList())
        val size = "${plan.files} file" + (if (plan.files == 1) "" else "s") + ", " + ThemeExport.megabytes(plan.bytes)
        val pack = draft.pack.info.name
        val what = when (val sc = e.scope) {
            ExportScope.WholePack -> {
                val n = draft.pack.variants.size
                "$pack, " + (if (n == 0) "" else "$n variant" + (if (n == 1) ", " else "s, ")) + size
            }
            is ExportScope.Variant -> "$pack · ${LookExporter.variantName(draft.pack, sc.id)}, $size"
            ExportScope.CurrentLook -> {
                val picks = UserLook.of(s).changes().map { phrase ->
                    if (phrase == "wallpaper") (if (e.files.any { it.source == ExportSource.UserWallpaper }) "your wallpaper" else "your wallpaper left out (it can't be read)") else phrase
                } + e.notes
                buildString {
                    append(pack)
                    if (picks.isNotEmpty()) append(" + ").append(picks.joinToString(", "))
                    append(", ").append(size)
                    if (QuickProfile.byName(s.quickProfile) != QuickProfile.NONE) append("; quick profile not included")
                }
            }
        }
        if (draft.share) {
            // The file leaves the device: say first that a picture from the user's photos is in it.
            val detail = if (draft.photo) "Includes your wallpaper photo · $what" else what
            return listOf(
                MenuOption("Share", detail = "$detail · pick where to save ${archiveName(e.name)}", command = Command.ConfirmThemeExport),
                MenuOption("Licences", detail = LookExporter.LICENCE_NOTE),
            )
        }
        return listOf(
            MenuOption("Export", detail = what, command = Command.ConfirmThemeExport),
            MenuOption("Licences", detail = LookExporter.LICENCE_NOTE),
        )
    }

    private fun confirmExport() {
        val draft = exportDraft ?: return host.toast("Start the export again from Settings ▸ Theme")
        exportDraft = null
        host.dismissOptions()
        if (draft.share) return askForArchive(PendingShare.Export(draft))
        scope.launch {
            host.busy(true)
            val result = try {
                withContext(Dispatchers.IO) { themeExport.write(draft.export, reader(draft)) }
            } finally {
                host.busy(false)
            }
            when (result) {
                is ExportResult.Refused -> host.fail("Theme not exported: ${result.reason}")
                is ExportResult.Saved -> {
                    themes.rediscover()
                    val name = draft.export.name
                    exported = Exported(result.id, name, draft.export.scope, draft.photo)
                    sounds.play(UiSound.OK)
                    host.toast("Saved as $name in Xross/themes")
                    host.showOptions(
                        OptionsMenuState.open(
                            "$name saved",
                            listOf(
                                MenuOption("Switch to $name", detail = switchDetail(draft.export.scope), command = Command.SwitchToExportedTheme),
                                MenuOption("Saved in", detail = "Xross/themes/${result.id}"),
                                MenuOption(
                                    "Share as .xtheme…",
                                    detail = if (draft.photo) "Includes your wallpaper photo" else "Save it as one theme file to send or keep",
                                    command = Command.ShareExportedTheme,
                                ),
                            ),
                        ),
                    )
                }
            }
        }
    }

    private fun switchDetail(exportScope: ExportScope) = when (exportScope) {
        ExportScope.WholePack -> "Keeps your variant and picks"
        is ExportScope.Variant -> "Keeps your picks"
        ExportScope.CurrentLook -> "Your picks are in it, so they reset"
    }

    /**
     * Switch to the pack just written, so the screen doesn't change: a whole pack keeps the
     * variant and the user layer; a variant keeps the user layer on the new pack's base look; a
     * current look starts on its base with the pack's colours and no user layer (it's all in the pack).
     */
    private fun switchToExport() {
        val done = exported ?: return host.toast("Nothing exported yet")
        host.dismissOptions()
        scope.launch {
            val loaded = themes.select(done.id)
            if (loaded.id != done.id) {
                sounds.useTheme(loaded)
                return@launch host.fail("Couldn't load ${done.name}; using Default")
            }
            val before = settings.current
            settings.update { s ->
                when (done.scope) {
                    ExportScope.WholePack -> s.copy(themePack = loaded.id)
                    is ExportScope.Variant -> s.copy(themePack = loaded.id, themeVariant = "")
                    ExportScope.CurrentLook -> UserLook.NONE.writeTo(s).copy(
                        themePack = loaded.id,
                        themeVariant = "",
                        theme = if (loaded.look().palette != null) PACK_THEME else s.theme,
                        // The tint's colors are the pack's palette now.
                        waveTint = WaveTint.THEME.name,
                    )
                }
            }
            if (done.scope == ExportScope.CurrentLook) releaseWallpaperGrant(before.customWallpaper)
            sounds.useTheme(loaded)
            sounds.play(UiSound.OK)
            host.toast("Theme pack: ${loaded.info.name}")
        }
    }

    /** Reads the pack's files from its assets or folder, and the Xross wallpaper through its URI or path. */
    private fun reader(draft: ExportDraft): ExportReader = object : ExportReader {
        override fun size(source: ExportSource): Long? = when (source) {
            is ExportSource.Pack -> packs.packFileSize(draft.pack.info, source.path)
            ExportSource.UserWallpaper -> wallpaperSize(draft.wallpaper)
        }

        override fun open(source: ExportSource): java.io.InputStream = when (source) {
            is ExportSource.Pack -> packs.openPackFile(draft.pack.info, source.path)
            ExportSource.UserWallpaper -> openWallpaper(draft.wallpaper)
        }
    }

    private fun wallpaperFile(location: String): File =
        File(location).let { if (it.isAbsolute) it else File(environment.current.root, location) }

    private fun isUri(location: String) = location.startsWith("content://") || location.startsWith("file://")

    private fun openWallpaper(location: String): java.io.InputStream =
        if (isUri(location)) context.contentResolver.openInputStream(location.toUri()) ?: throw java.io.IOException("can't open your wallpaper")
        else wallpaperFile(location).inputStream()

    private fun wallpaperSize(location: String): Long? = if (isUri(location)) {
        runCatching {
            context.contentResolver.query(location.toUri(), arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
            }
        }.getOrNull()
    } else {
        wallpaperFile(location).takeIf { it.isFile }?.length()
    }

    /** `png`, `jpg` or `webp` from the picture's first bytes (its name or MIME type can lie); null if it can't be read or isn't one. */
    private fun wallpaperType(location: String): String? {
        val head = runCatching { openWallpaper(location).use { input -> ByteArray(12).also { b -> var n = 0; while (n < b.size) { val r = input.read(b, n, b.size - n); if (r < 0) break; n += r } } } }.getOrNull()
            ?: return null
        return when {
            head[0] == 0x89.toByte() && head[1] == 'P'.code.toByte() && head[2] == 'N'.code.toByte() && head[3] == 'G'.code.toByte() -> "png"
            head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() -> "jpg"
            String(head, 0, 4, Charsets.US_ASCII) == "RIFF" && String(head, 8, 4, Charsets.US_ASCII) == "WEBP" -> "webp"
            else -> null
        }
    }

    // endregion

    // region Share as .xtheme (2b.8c)

    /** What the next created document gets: an export built for the file, or a pack folder already written. */
    private sealed interface PendingShare {
        val name: String

        data class Export(val draft: ExportDraft) : PendingShare {
            override val name get() = draft.export.name
        }

        data class Folder(val dir: File, override val name: String) : PendingShare
    }

    private var pendingShare: PendingShare? = null

    /** `<id>.xtheme` for a theme named [name]. */
    private fun archiveName(name: String) = ThemeArchive.idFor(name, "theme", null) + ".xtheme"

    /** Share as .xtheme… after an export: zips the folder just written. */
    private fun shareExported() {
        val done = exported ?: return host.toast("Nothing exported yet")
        val dir = File(environment.current.themes, done.id)
        if (!dir.isDirectory) return host.fail("${done.name} is no longer in Xross/themes")
        host.dismissOptions()
        askForArchive(PendingShare.Folder(dir, done.name), fileName = "${done.id}.xtheme")
    }

    /** Opens the create-document picker; [onThemeArchiveCreated] writes the file. */
    private fun askForArchive(share: PendingShare, fileName: String = archiveName(share.name)) {
        pendingShare = share
        host.emit(UiEffect.CreateThemeArchive(fileName))
    }

    /** The document picked for a shared theme (null: the picker was cancelled): the archive is written into it. */
    fun onThemeArchiveCreated(uri: Uri?) {
        val share = pendingShare ?: return
        pendingShare = null
        uri ?: return
        scope.launch {
            host.busy(true)
            val result = try {
                withContext(Dispatchers.IO) { writeArchive(share, uri) }
            } finally {
                host.busy(false)
            }
            when (result) {
                is ArchiveWrite.Refused -> host.fail("Theme not shared: ${result.reason}")
                is ArchiveWrite.Written -> {
                    sounds.play(UiSound.OK)
                    val file = displayName(uri) ?: archiveName(share.name)
                    val left = result.leftOut.takeIf { it.isNotEmpty() }
                        ?.let { " · left out ${it.size} file" + (if (it.size == 1) "" else "s") + " a theme can't use (${it.first()})" }.orEmpty()
                    host.toast("Saved ${share.name} as $file$left")
                }
            }
        }
    }

    private fun writeArchive(share: PendingShare, uri: Uri): ArchiveWrite {
        val resolver = context.contentResolver
        val result = try {
            val out = resolver.openOutputStream(uri, "wt") ?: return ArchiveWrite.Refused("Couldn't open the file to write")
            out.use { stream ->
                when (share) {
                    is PendingShare.Export -> ThemeArchive.write(share.draft.export, reader(share.draft), stream)
                    is PendingShare.Folder -> ThemeArchive.writeFolder(share.dir, stream)
                }
            }
        } catch (e: java.io.IOException) {
            ArchiveWrite.Refused("Couldn't write the file (${e.message ?: "write error"})")
        } catch (e: SecurityException) {
            ArchiveWrite.Refused("No permission to write the file")
        }
        // Don't leave a broken theme file where the user put it.
        if (result is ArchiveWrite.Refused) runCatching { android.provider.DocumentsContract.deleteDocument(resolver, uri) }
        return result
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

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

    companion object {
        /** The longest name an exported theme takes (folder ids keep 48 characters). */
        const val MAX_EXPORT_NAME = 40
    }
}
