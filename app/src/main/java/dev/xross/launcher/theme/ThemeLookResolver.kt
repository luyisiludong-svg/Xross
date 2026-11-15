package dev.xross.launcher.theme

import androidx.compose.runtime.Immutable
import dev.xross.launcher.theme.pack.LoadedTheme
import dev.xross.launcher.theme.pack.ResolvedComponents
import dev.xross.launcher.theme.pack.Surface
import dev.xross.launcher.theme.pack.ThemeLayout
import dev.xross.launcher.theme.pack.ThemeLook
import dev.xross.launcher.theme.pack.UserLook

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
    /** Everything the user set over the pack (effects, surface, colour layers, components): [UserLook.of]. */
    val user: UserLook = UserLook.NONE,
    val profile: QuickProfile = QuickProfile.NONE,
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
    /** The widgets drawn (2b.7a): Default pack, pack, variant, then the user's; never per category. */
    val components: ResolvedComponents = ResolvedComponents.DEFAULT,
) {
    /** The profile draws one still frame and snaps instead of animating (E-Ink). */
    val stillMotion: Boolean get() = profile.stillMotion
}

/**
 * The theme cascade (Roadmap Appendix A.4), in order:
 *  1. the pack's look: Default → pack → [LookInputs.variant] → category override → system
 *     override → context rules (battery, clock) ([LoadedTheme.look]);
 *  2. the user's Settings ▸ Theme picks ([LookInputs.user]): effects, surface style, colour layers;
 *  3. the quick profile, over everything it lists in [QuickProfile.fields] and nothing else;
 *  4. the layout the look and profile give;
 *  5. the components ([LoadedTheme.components]): Default pack, pack, variant, the user's
 *     `display.components`. Category and system overrides, rules and quick profiles don't touch it.
 * Pure: no Android or Compose state, so the whole cascade is unit tested.
 */
object ThemeLookResolver {
    fun resolve(i: LookInputs): ResolvedLook {
        val u = i.user
        val look = u.effects.applyTo(i.pack.look(i.variant, i.category, i.system, i.battery, i.minuteOfDay))
        val p = i.profile
        val surface = p.surface?.let { i.pack.surfaces[it.name] } ?: i.pack.surfaceFor(look, u.surfaceStyle)
        return ResolvedLook(
            look = look,
            layout = p.layout(look.layout ?: i.pack.layout),
            surface = surface,
            switches = ColorSwitches.resolve(look.flags, u.circadianCurve, u.batteryTint, u.adaptiveGamePalette, p),
            profile = p,
            components = i.pack.components(i.variant, u.components),
        )
    }
}
