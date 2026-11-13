package dev.xross.launcher.theme

import androidx.compose.runtime.Immutable
import dev.xross.launcher.theme.pack.EffectOverrides
import dev.xross.launcher.theme.pack.LoadedTheme
import dev.xross.launcher.theme.pack.Surface
import dev.xross.launcher.theme.pack.ThemeLayout
import dev.xross.launcher.theme.pack.ThemeLook

/**
 * Everything the theme cascade reads, already settled: the focused [system] after its rest delay,
 * and [battery] / [minuteOfDay] only when the pack has rules on them (null otherwise), so an
 * equal [LookInputs] means an equal look.
 */
@Immutable
data class LookInputs(
    val pack: LoadedTheme,
    /** Settings ▸ Theme ▸ Theme variant; blank or unknown is the pack's base look. */
    val variant: String = "",
    val category: String? = null,
    val system: String? = null,
    val battery: BatteryLevel? = null,
    val minuteOfDay: Int? = null,
    /** The user's Settings ▸ Theme effect picks (2b.4f). */
    val effects: EffectOverrides = EffectOverrides.NONE,
    /** Settings ▸ Theme ▸ Surface style; blank follows the look. */
    val surfaceStyle: String = "",
    val profile: QuickProfile = QuickProfile.NONE,
    /** The user's colour layers; null follows the pack. */
    val circadian: Boolean? = null,
    val batteryTint: Boolean? = null,
    val adaptive: Boolean? = null,
)

/** What the menu draws with, from [ThemeLookResolver]. The palette layers live in [ColorPipeline]. */
@Immutable
data class ResolvedLook(
    /** The pack's look in this context, with the user's effect picks on top. */
    val look: ThemeLook,
    val layout: ThemeLayout,
    val surface: Surface,
    val switches: ColorSwitches,
    val profile: QuickProfile,
) {
    /** The profile draws one still frame and snaps instead of animating (E-Ink). */
    val stillMotion: Boolean get() = profile.stillMotion
}

/**
 * The theme cascade (Roadmap Appendix A.4), in order:
 *  1. the pack's look: Default → pack → [LookInputs.variant] → category override → system
 *     override → context rules (battery, clock) ([LoadedTheme.look]);
 *  2. the user's Settings ▸ Theme picks: effects, surface style, colour layers;
 *  3. the quick profile, over everything it lists in [QuickProfile.fields] and nothing else;
 *  4. the layout the look and profile give.
 * Pure: no Android or Compose state, so the whole cascade is unit tested.
 */
object ThemeLookResolver {
    fun resolve(i: LookInputs): ResolvedLook {
        val look = i.effects.applyTo(i.pack.look(i.variant, i.category, i.system, i.battery, i.minuteOfDay))
        val p = i.profile
        val surface = p.surface?.let { i.pack.surfaces[it.name] } ?: i.pack.surfaceFor(look, i.surfaceStyle)
        return ResolvedLook(
            look = look,
            layout = p.layout(look.layout ?: i.pack.layout),
            surface = surface,
            switches = ColorSwitches.resolve(look.flags, i.circadian, i.batteryTint, i.adaptive, p),
            profile = p,
        )
    }
}
