package dev.xross.launcher.state

import dev.xross.launcher.audio.UiSound
import dev.xross.launcher.audio.UiSounds
import dev.xross.launcher.data.SettingsStore
import dev.xross.launcher.input.NavAction
import dev.xross.launcher.theme.AvatarStyleLimits
import dev.xross.launcher.theme.pack.ThemeManifest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The settings panels drawn over the menu that [EditorController] owns. */
data class EditorsState(
    /** Settings ▸ Theme ▸ Wave settings while it is open. */
    val wave: WaveEditorState? = null,
    /** Settings ▸ Theme ▸ Profile customization (avatar ring and glow) while it is open. */
    val avatar: AvatarEditorState? = null,
)

/**
 * The Wave settings and Profile customization panels, split out of [XmbViewModel] (Architecture
 * Assessment §7.1, step 6). Both are a pure editor ([WaveEditor], [AvatarEditor]) over the
 * settings; this class turns their outcomes into settings writes, sounds and panel state. The
 * Layout panel stays with [ThemeController], which owns the pack's layout defaults.
 *
 * The view model opens a panel from its commands, routes input here while one is open and merges
 * [state] into [XmbUiState].
 */
class EditorController(
    private val settings: SettingsStore,
    private val sounds: UiSounds,
    /** The active pack's wave defaults, which the Wave panel's Reset returns to. */
    private val waveDefaults: () -> ThemeManifest.WaveDefaults?,
    private val host: Host,
) {
    /** What the controller needs from the menu around it. */
    interface Host {
        fun toast(message: String)
        /** The error sound, then [message]. */
        fun fail(message: String)
        /** The error sound alone: a move the panel doesn't allow. */
        fun refuse()
        /** Asks for text; the answer comes back through [onAvatarColorEntered]. */
        fun promptText(prompt: TextPrompt)
    }

    private val _state = MutableStateFlow(EditorsState())
    val state: StateFlow<EditorsState> = _state.asStateFlow()

    // region Wave settings

    fun openWave() = _state.update { it.copy(wave = WaveEditorState()) }

    /** Input while the Wave panel is open; false when it isn't. */
    fun onWaveAction(action: NavAction): Boolean {
        val editor = _state.value.wave ?: return false
        applyWave(editor, WaveEditor.onAction(editor, action, settings.current, waveDefaults()))
        return true
    }

    private fun applyWave(before: WaveEditorState, outcome: WaveEditorOutcome) {
        when {
            outcome.close -> return closeWave()
            outcome.blocked -> host.refuse()
            outcome.settings != null -> {
                settings.update { outcome.settings }
                sounds.play(UiSound.MOVE)
            }
            outcome.state != before -> sounds.play(UiSound.MOVE)
        }
        if (outcome.state != before) _state.update { it.copy(wave = outcome.state) }
    }

    fun closeWave() {
        if (_state.value.wave != null) {
            sounds.play(UiSound.BACK)
            _state.update { it.copy(wave = null) }
        }
    }

    /** Touch: tap a row to focus it; tapping the focused row acts like Confirm. */
    fun onWaveRowTapped(index: Int) {
        val editor = _state.value.wave ?: return
        if (index == editor.index) {
            onWaveAction(NavAction.CONFIRM)
        } else if (index in WaveEditor.rows.indices) {
            sounds.play(UiSound.MOVE)
            _state.update { it.copy(wave = editor.copy(index = index)) }
        }
    }

    /** Touch: a slider dragged or tapped at [fraction] of its track. */
    fun onWaveSliderChanged(index: Int, fraction: Float) {
        val editor = _state.value.wave ?: return
        val row = WaveEditor.rows.getOrNull(index) ?: return
        if (editor.index != index) _state.update { it.copy(wave = editor.copy(index = index)) }
        settings.update { WaveEditor.setFraction(row, it, fraction) }
    }

    // endregion

    // region Profile customization

    fun openAvatar() = _state.update { it.copy(avatar = AvatarEditorState()) }

    /** Input while the Profile customization panel is open; false when it isn't. */
    fun onAvatarAction(action: NavAction): Boolean {
        val editor = _state.value.avatar ?: return false
        applyAvatar(editor, AvatarEditor.onAction(editor, action, settings.current.avatarStyle))
        return true
    }

    private fun applyAvatar(before: AvatarEditorState, outcome: AvatarEditorOutcome) {
        when {
            outcome.close -> return closeAvatar()
            outcome.promptColor -> {
                sounds.play(UiSound.ENTER)
                host.promptText(TextPrompt.AvatarBorderColor(settings.current.avatarStyle.borderColorHex))
            }
            outcome.blocked -> host.refuse()
            outcome.style != null -> {
                settings.update { AvatarEditor.apply(it, outcome.style) }
                sounds.play(UiSound.MOVE)
            }
            outcome.state != before -> sounds.play(UiSound.MOVE)
        }
        if (outcome.state != before) _state.update { it.copy(avatar = outcome.state) }
    }

    fun closeAvatar() {
        if (_state.value.avatar != null) {
            sounds.play(UiSound.BACK)
            _state.update { it.copy(avatar = null) }
        }
    }

    /** Touch: tap a row to focus it; tapping the focused row acts like Confirm. */
    fun onAvatarRowTapped(index: Int) {
        val editor = _state.value.avatar ?: return
        if (index == editor.index) {
            onAvatarAction(NavAction.CONFIRM)
        } else if (index in AvatarEditor.rows.indices) {
            sounds.play(UiSound.MOVE)
            _state.update { it.copy(avatar = editor.copy(index = index)) }
        }
    }

    /** Touch: a slider dragged or tapped at [fraction] of its track. */
    fun onAvatarSliderChanged(index: Int, fraction: Float) {
        val editor = _state.value.avatar ?: return
        val row = AvatarEditor.rows.getOrNull(index) ?: return
        if (editor.index != index) _state.update { it.copy(avatar = editor.copy(index = index)) }
        settings.update { AvatarEditor.apply(it, AvatarEditor.setFraction(row, it.avatarStyle, fraction)) }
    }

    /** The custom ring colour typed in the prompt: blank keeps the current one, anything else must be a hex colour. */
    fun onAvatarColorEntered(text: String) {
        val value = text.trim()
        if (value.isEmpty()) return
        val color = AvatarStyleLimits.normalizeColor(value)
            ?: return host.fail("That isn't a hex color — use #RRGGBB, like #FFB5C5")
        settings.update { AvatarEditor.apply(it, it.avatarStyle.copy(borderColorHex = color)) }
        host.toast("Avatar ring color: $color")
    }

    // endregion

    /** Now Playing opened over everything: both panels close without a sound. */
    fun dismissAll() = _state.update { EditorsState() }
}
