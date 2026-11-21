package dev.xross.launcher

import dev.xross.launcher.data.XrossSettings
import dev.xross.launcher.theme.pack.ComponentOverrides
import dev.xross.launcher.theme.pack.EffectOverrides
import dev.xross.launcher.theme.pack.LayoutOverrides
import dev.xross.launcher.data.ThemeDefaults
import dev.xross.launcher.theme.pack.UserLook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.xross.launcher.data.of
import dev.xross.launcher.data.writeTo

/** Phase 2b.8-prep: every user-over-pack value in one type, read by the cascade, Reset and its summary. */
class UserLookTest {

    /**
     * One row per [UserLook] field: a look that sets only that field, and the phrase Reset's
     * summary shows for it. [everyFieldHasARow] fails when a field is added without a row.
     */
    private val rows: List<Pair<UserLook, String>> = listOf(
        UserLook(effects = EffectOverrides(shaderPreset = "BOKEH")) to "1 effect",
        UserLook(components = ComponentOverrides(clockFormat = "H24")) to "1 component",
        UserLook(layout = LayoutOverrides(uiScale = 1.2f)) to "1 layout value",
        UserLook(surfaceStyle = "OLED_TRUE_BLACK") to "surface",
        UserLook(customWallpaper = "content://media/external/images/1") to "wallpaper",
        UserLook(adaptiveGamePalette = false) to "1 color layer",
        UserLook(circadianCurve = true) to "1 color layer",
        UserLook(batteryTint = true) to "1 color layer",
        UserLook(themeMusic = "none") to "theme music",
        UserLook(wallpaperFolder = "/storage/emulated/0/Pictures/Walls") to "wallpaper",
    )

    @Test
    fun everyFieldHasARow() {
        // Each row changes a different field: together they differ from NONE in every component.
        val fieldCount = UserLook::class.java.declaredFields.count { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
        assertEquals("a UserLook field without a row in this test", fieldCount, rows.size)
        val changed = rows.map { (look, _) -> look.differingFields() }
        changed.forEach { assertEquals(1, it.size) }
        assertEquals(fieldCount, changed.flatten().toSet().size)
    }

    @Test
    fun eachFieldCountsInChangesAndReset() {
        val clean = XrossSettings()
        for ((look, phrase) in rows) {
            val s = look.writeTo(clean)
            assertFalse("$look is default", ThemeDefaults.isDefault(s))
            assertEquals(listOf(phrase), UserLook.of(s).changes())
            val reset = ThemeDefaults.reset(s)
            assertTrue(ThemeDefaults.isDefault(reset))
            assertEquals(UserLook.NONE, UserLook.of(reset))
        }
    }

    @Test
    fun noneIsAFreshSettings() {
        assertEquals(UserLook.NONE, UserLook.of(XrossSettings()))
        assertTrue(UserLook.NONE.isNone)
        assertTrue(UserLook.NONE.changes().isEmpty())
        assertEquals(XrossSettings(), UserLook.NONE.writeTo(XrossSettings()))
    }

    @Test
    fun roundTripsThroughSettings() {
        val all = UserLook(
            effects = EffectOverrides(shaderPreset = "CRT", focusFrameStyle = "GOLD_BEZEL", iconMask = "NONE"),
            components = ComponentOverrides(clockFormat = "H24", batteryMode = "PERCENT"),
            layout = LayoutOverrides(preset = "PSP_CENTER", itemSpacingDp = 6f),
            surfaceStyle = "EINK_HIGH_CONTRAST",
            customWallpaper = "/sdcard/Pictures/bg.png",
            adaptiveGamePalette = false,
            circadianCurve = true,
            batteryTint = false,
        )
        val s = all.writeTo(XrossSettings(themePack = "neon", themeVariant = "night", quickProfile = "OLED_NIGHT", theme = "PACK"))
        assertEquals(all, UserLook.of(s))
        assertEquals(listOf("2 layout values", "3 effects", "2 components", "surface", "3 color layers", "wallpaper"), all.changes())
    }

    /** 2b.8a: the Reset row's summary leads with the layout values ("Clears 2 layout values, 1 effect"). */
    @Test
    fun layoutCountsInChangesAndReset() {
        val s = UserLook(layout = LayoutOverrides(preset = "MODERN_WIDESCREEN", focusedScale = 1.1f), effects = EffectOverrides(iconMask = "CIRCLE"))
            .writeTo(XrossSettings(themePack = "neon"))
        assertEquals(listOf("2 layout values", "1 effect"), UserLook.of(s).changes())
        val r = ThemeDefaults.reset(s)
        assertEquals(LayoutOverrides.NONE, r.layoutOverrides)
        assertEquals("neon", r.themePack)
    }

    @Test
    fun resetKeepsWhatIsNotTheUserLayer() {
        val s = UserLook(surfaceStyle = "OLED_TRUE_BLACK", circadianCurve = true).writeTo(
            XrossSettings(themePack = "neon", themeVariant = "night", quickProfile = "OLED_NIGHT", theme = "PACK", waveSpeed = 1.7f, waveLineCount = 3),
        )
        val r = ThemeDefaults.reset(s)
        assertEquals(s.copy(surfaceStyle = "", circadianCurve = null), r)
    }

    /** The names of the fields where this look differs from [UserLook.NONE]. */
    private fun UserLook.differingFields(): List<String> =
        UserLook::class.java.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }.filter { f ->
            f.isAccessible = true
            f.get(this) != f.get(UserLook.NONE)
        }.map { it.name }
}
