package dev.xross.launcher

import dev.xross.launcher.state.Categories
import dev.xross.launcher.theme.BatteryLevel
import dev.xross.launcher.theme.LookInputs
import dev.xross.launcher.theme.PACK_THEME
import dev.xross.launcher.theme.ThemeLookResolver
import dev.xross.launcher.theme.WaveTint
import dev.xross.launcher.theme.paletteFor
import dev.xross.launcher.theme.pack.LoadedTheme
import dev.xross.launcher.theme.pack.UserLook
import dev.xross.launcher.theme.pack.WaveValues
import dev.xross.launcher.theme.wavePalette
import org.junit.Assert.assertEquals
import java.time.LocalDate

/**
 * "Resolves to the same look" for 2b.8b's export tests and 2b.8c's round-trip gate (Appendix
 * A.13): [ThemeLookResolver.resolve] and the palette the wave draws ([paletteFor], then
 * [wavePalette]), field by field, in a matrix of contexts.
 */
object SameLook {
    /** One context a look is resolved in. */
    data class Where(val category: String?, val system: String?, val battery: BatteryLevel?, val minute: Int?) {
        override fun toString() = "category=$category system=$system battery=$battery minute=$minute"
    }

    /**
     * Each category (and none); no system and one with an override (`dreamcast`, the stress
     * pack's); battery 15%, 50% and charging; 08:00, 20:00 and 22:00 (inside the stress pack's
     * 21:00..06:00 rule).
     */
    val CONTEXTS: List<Where> = buildList {
        for (cat in Categories.all.map { it.id } + listOf<String?>(null)) {
            for (system in listOf(null, "dreamcast")) {
                for (battery in listOf(BatteryLevel(15, false), BatteryLevel(50, false), BatteryLevel(50, true))) {
                    for (minute in listOf(8 * 60, 20 * 60, 22 * 60)) add(Where(cat, system, battery, minute))
                }
            }
        }
    }

    /**
     * [original] with [variant], [user], [colorTheme] and [wave] resolves like [exported] on
     * [exportedVariant] with no user layer and its own colours, in every one of [contexts]. A
     * mismatch names [label] (the scope), the context and the field.
     */
    fun assertSame(
        original: LoadedTheme,
        exported: LoadedTheme,
        variant: String = "",
        user: UserLook = UserLook.NONE,
        colorTheme: String = PACK_THEME,
        wave: WaveValues = WaveValues(),
        exportedVariant: String = "",
        date: LocalDate = LocalDate.of(2026, 11, 15),
        label: String = "",
        contexts: List<Where> = CONTEXTS,
    ) {
        for (w in contexts) {
            val a = ThemeLookResolver.resolve(LookInputs(original, variant, w.category, w.system, w.battery, w.minute, user))
            val b = ThemeLookResolver.resolve(LookInputs(exported, exportedVariant, w.category, w.system, w.battery, w.minute, UserLook.NONE))
            val where = (if (label.isEmpty()) "" else "$label: ") + w
            fun same(field: String, x: Any?, y: Any?) = assertEquals("$where: $field", x, y)
            same("layout", a.layout, b.layout)
            same("surface", a.surface, b.surface)
            same("switches", a.switches, b.switches)
            same("components", a.components, b.components)
            same("shader", a.look.shader, b.look.shader)
            same("cosmetics", a.look.cosmetics, b.look.cosmetics)
            same("focusGold", a.look.focusGold, b.look.focusGold)
            same("bgm", a.look.bgm, b.look.bgm)
            same("gains", a.look.gains.ifEmpty { original.gains }, b.look.gains.ifEmpty { exported.gains })
            if (user.customWallpaper.isBlank()) same("wallpaper", a.look.wallpaper, b.look.wallpaper)
            val pa = wavePalette(paletteFor(colorTheme, a.look.palette, date), WaveTint.byName(wave.tint), wave.colors)
            val pb = wavePalette(paletteFor(PACK_THEME, b.look.palette, date), WaveTint.THEME, wave.colors)
            same("palette", pa, pb)
        }
    }
}
