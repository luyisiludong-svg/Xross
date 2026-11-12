package dev.xross.launcher.ui

import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xross.launcher.data.XrossSettings
import dev.xross.launcher.state.NodeIcon
import dev.xross.launcher.state.XmbUiState
import dev.xross.launcher.theme.AmbientState
import dev.xross.launcher.theme.ArtSwatches
import dev.xross.launcher.theme.BatteryLevel
import dev.xross.launcher.theme.BatteryTint
import dev.xross.launcher.theme.ColorPipeline
import dev.xross.launcher.theme.ColorSwitches
import dev.xross.launcher.theme.GameColors
import dev.xross.launcher.theme.QuickProfile
import dev.xross.launcher.theme.ThemePalette
import dev.xross.launcher.theme.rememberMinuteOfDay
import kotlinx.coroutines.flow.StateFlow

/**
 * The wave background with the color layers above the theme (Appendix A.4, A.7): the quick
 * profile, the circadian curve, the battery tint and the focused game's [art] colors, resolved by
 * [ColorPipeline]. The renderer eases the wave to new colors over ~300 ms on the GL thread; the
 * Compose accents ([ThemeAccent]) morph over the same time, read only while drawing.
 *
 * Its own composable so a new game palette, battery reading or clock minute recomposes this and
 * not the screen around it.
 */
@Composable
fun AdaptiveWave(
    /** The theme color or pack look, with the user's wave tint. */
    themed: ThemePalette,
    profile: QuickProfile,
    switches: ColorSwitches,
    art: StateFlow<GameColors?>,
    battery: StateFlow<BatteryLevel?>,
    settings: XrossSettings,
    enabled: Boolean,
    paused: Boolean,
    idle: Boolean,
    /** The active pack's programs, compiled ahead of time, and the key of the one the look shows. */
    shaders: dev.xross.launcher.theme.shader.ShaderLibrary = dev.xross.launcher.theme.shader.ShaderLibrary.DEFAULT,
    shader: String = dev.xross.launcher.theme.shader.ShaderLibrary.WAVE_KEY,
    /** Open category id, for `uTransition`. */
    category: String? = null,
    health: dev.xross.launcher.theme.shader.ShaderHealth? = null,
) {
    val artColors by art.collectAsStateWithLifecycle()
    val level by battery.collectAsStateWithLifecycle()
    val minute by rememberMinuteOfDay(active = switches.circadian)
    val ambient = AmbientState(minuteOfDay = minute ?: 12 * 60, battery = level.takeIf { switches.batteryTint })
    val shownArt = artColors.takeIf { switches.adaptive }
    val palette = remember(themed, profile, switches, ambient, shownArt) {
        ColorPipeline.resolve(themed, profile, switches, ambient, shownArt)
    }
    val pulse = ColorPipeline.chargePulse(switches, ambient)
    // Debug and profiling builds: one line per color change, for the smoke test (colors aren't
    // in the accessibility tree).
    if (dev.xross.launcher.BuildConfig.PERF_LOG) {
        LaunchedEffect(palette, pulse) {
            android.util.Log.i(
                "XrossColors",
                "top=${hex(palette.top)} glow=${hex(palette.glow)} profile=${profile.name} art=${shownArt != null} " +
                    "circadian=${switches.circadian} batteryLow=${switches.batteryTint && BatteryTint.low(ambient.battery)} charging=$pulse",
            )
        }
    }
    val s = settings
    val batteryLevel = level?.percent?.let { it.coerceIn(0, 100) / 100f } ?: 1f
    val params = remember(palette, s.waveSpeed, s.waveLineCount, s.waveTurbulence, s.waveThickness, pulse, shader, category, batteryLevel) {
        WaveParams(
            speed = s.waveSpeed,
            palette = palette,
            lineCount = s.waveLineCount,
            turbulence = s.waveTurbulence,
            thickness = s.waveThickness,
            chargePulse = pulse,
            shader = shader,
            category = category,
            battery = batteryLevel,
        )
    }
    WaveBackground(
        enabled = enabled,
        params = params,
        paused = paused,
        idle = idle,
        frozen = profile.stillMotion,
        shaders = shaders,
        health = health,
    )

    // Halos stay white unless a color layer is at work, so the plain themes look as before.
    val colored = shownArt != null || profile != QuickProfile.NONE || switches.circadian ||
        (switches.batteryTint && (BatteryTint.low(ambient.battery) || ambient.battery?.charging == true))
    val halo = if (colored) Color(palette.glow) else Color.White
    val still = profile.stillMotion
    LaunchedEffect(halo, still) {
        ThemeAccent.haloTarget = halo
        if (still) ThemeAccent.halo.snapTo(halo) else ThemeAccent.halo.animateTo(halo, tween(ThemeAccent.MORPH_MS))
    }
    val mix = if (shownArt != null) ADAPTIVE_AVATAR_MIX else 0f
    LaunchedEffect(mix, still) {
        if (still) ThemeAccent.adaptiveMix.snapTo(mix) else ThemeAccent.adaptiveMix.animateTo(mix, tween(ThemeAccent.MORPH_MS))
    }
}

private fun hex(argb: Int) = "#%06X".format(argb and 0xFFFFFF)

/** How far the avatar's glow leans toward the game-art accent. */
private const val ADAPTIVE_AVATAR_MIX = 0.6f

/**
 * The art whose colors drive the palette: the open Details screen's game, else the focused row's
 * scraped game art; null when no game is in view. Keyed by uuid and art version
 * ([ArtSwatches.key]), so re-scraped art is read again.
 */
fun adaptiveArtKey(state: XmbUiState): String? {
    val focused = when (val icon = state.focused?.icon) {
        is NodeIcon.Art -> icon.uuid to icon.version
        is NodeIcon.Tile -> if (icon.scraped) icon.uuid to icon.version else null
        else -> null
    }
    state.details?.let { d ->
        val version = focused?.takeIf { it.first == d.uuid }?.second ?: 0L
        return ArtSwatches.key(d.uuid, version)
    }
    return focused?.let { ArtSwatches.key(it.first, it.second) }
}
