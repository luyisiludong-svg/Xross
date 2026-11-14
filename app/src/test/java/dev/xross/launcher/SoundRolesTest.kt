package dev.xross.launcher

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.xross.launcher.theme.pack.LoadedTheme
import dev.xross.launcher.theme.pack.SoundRole
import dev.xross.launcher.theme.pack.SoundSource
import dev.xross.launcher.theme.pack.ThemePackLoader
import dev.xross.launcher.theme.pack.cascadeGains
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Phase 2b.5a sound roles (Appendix A.9): the three new roles and their fallback chain (pack
 * file → fallback role's file in the pack → Default pack), camelCase aliases in `audio.sounds`
 * and `audio.soundGains`, unknown keys reported, and gains through variants, overrides and rules.
 */
@RunWith(RobolectricTestRunner::class)
class SoundRolesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule val tmp = TemporaryFolder()

    private val themes get() = File(tmp.root, "Xross/themes").apply { mkdirs() }
    private val loader by lazy { ThemePackLoader(context.assets) { themes } }
    private val wav = "RIFF0000WAVEfmt ".toByteArray()

    private fun pack(id: String, manifest: String, sounds: List<String> = emptyList()): LoadedTheme {
        val dir = File(themes, id).apply { mkdirs() }
        File(dir, "theme.json").writeText(manifest.replaceFirst("{", """{"schemaVersion": 2, """))
        for (name in sounds) File(dir, "sounds/$name").apply { parentFile!!.mkdirs() }.writeBytes(wav)
        return loader.load(id)
    }

    private fun LoadedTheme.file(role: SoundRole): String = when (val s = sounds[role]) {
        is SoundSource.Disk -> "pack:${s.file.name}"
        is SoundSource.Asset -> "default:${s.path.substringAfterLast('/')}"
        null -> "none"
    }

    // region Roles and fallbacks

    @Test fun newRolesHaveFallbacksAndKeys() {
        assertEquals(SoundRole.CURSOR, SoundRole.CATEGORY_SWITCH.fallback)
        assertEquals(SoundRole.CONFIRM, SoundRole.DRILL_IN.fallback)
        assertEquals(SoundRole.BACK, SoundRole.DRILL_OUT.fallback)
        assertEquals("category_switch.wav", SoundRole.CATEGORY_SWITCH.file)
        for (role in listOf(SoundRole.CURSOR, SoundRole.CONFIRM, SoundRole.BACK, SoundRole.OPENING)) assertNull(role.fallback)
        // Fallbacks resolve before the roles that borrow them.
        val order = SoundRole.resolveOrder
        for (role in SoundRole.entries) role.fallback?.let { assertTrue(order.indexOf(it) < order.indexOf(role)) }
        assertEquals(SoundRole.entries.toSet(), order.toSet())
    }

    @Test fun defaultPackGivesNewRolesItsFallbackFiles() {
        val theme = loader.load(ThemePackLoader.DEFAULT_ID)
        assertTrue(theme.problems.toString(), theme.problems.isEmpty())
        // No category_switch.wav or drill_*.wav in the Default pack: its cursor, confirm and back files.
        assertEquals(theme.sounds[SoundRole.CURSOR], theme.sounds[SoundRole.CATEGORY_SWITCH])
        assertEquals(theme.sounds[SoundRole.CONFIRM], theme.sounds[SoundRole.DRILL_IN])
        assertEquals(theme.sounds[SoundRole.BACK], theme.sounds[SoundRole.DRILL_OUT])
        // Its own category_switch gain; the drill roles follow confirm and back.
        assertEquals(0.6f, theme.gains[SoundRole.CATEGORY_SWITCH]!!, 1e-6f)
        assertEquals(theme.gains[SoundRole.CONFIRM], theme.gains[SoundRole.DRILL_IN])
        assertEquals(theme.gains[SoundRole.BACK], theme.gains[SoundRole.DRILL_OUT])
        assertEquals(mapOf(SoundRole.DRILL_IN to SoundRole.CONFIRM, SoundRole.DRILL_OUT to SoundRole.BACK), theme.soundLinks)
        assertEquals(0.35f, theme.panAmount, 0f)
        assertFalse(theme.cursorJitter)
    }

    @Test fun aMissingRoleUsesItsFallbackInThePackBeforeTheDefaultPack() {
        // Only cursor.wav and drill_in.wav: category switches tick with the pack's cursor, drilling in
        // plays its own file, drilling out (no back.wav here) comes from the Default pack.
        val theme = pack("ticks", """{"name": "Ticks"}""", listOf("cursor.wav", "drill_in.wav"))
        assertEquals("pack:cursor.wav", theme.file(SoundRole.CATEGORY_SWITCH))
        assertEquals("pack:drill_in.wav", theme.file(SoundRole.DRILL_IN))
        assertEquals("default:back.wav", theme.file(SoundRole.DRILL_OUT))
        assertEquals("default:back.wav", theme.file(SoundRole.BACK))
        // A borrowed file keeps the fallback's level and follows it ([soundLinks]).
        assertEquals(1f, theme.gains[SoundRole.CATEGORY_SWITCH]!!, 0f)
        assertEquals(SoundRole.CURSOR, theme.soundLinks[SoundRole.CATEGORY_SWITCH])
        assertNull(theme.soundLinks[SoundRole.DRILL_IN])
        // From the Default pack, with its gain and its link to back.
        assertEquals(0.6f, theme.gains[SoundRole.DRILL_OUT]!!, 1e-6f)
        assertEquals(SoundRole.BACK, theme.soundLinks[SoundRole.DRILL_OUT])
    }

    @Test fun aFallbackFileTakesTheFallbacksGainUnlessTheRoleHasOne() {
        val theme = pack(
            "levels",
            """{"name": "Levels", "audio": {"soundGains": {"cursor": 0.4, "confirm": 0.9, "drill_in": 0.3}}}""",
            listOf("cursor.wav", "confirm.wav"),
        )
        assertEquals(0.4f, theme.gains[SoundRole.CATEGORY_SWITCH]!!, 1e-6f)
        assertEquals(0.3f, theme.gains[SoundRole.DRILL_IN]!!, 1e-6f)
        assertNull("its own gain: no link", theme.soundLinks[SoundRole.DRILL_IN])
    }

    @Test fun aRoleDeclaredInAudioSoundsUsesThatFile() {
        val theme = pack(
            "declared",
            """{"name": "Declared", "audio": {"sounds": {"categorySwitch": "swish.wav", "drillOut": "pop.wav"}}}""",
            listOf("swish.wav", "pop.wav"),
        )
        assertEquals("pack:swish.wav", theme.file(SoundRole.CATEGORY_SWITCH))
        assertEquals("pack:pop.wav", theme.file(SoundRole.DRILL_OUT))
        assertTrue(theme.problems.toString(), theme.problems.isEmpty())
    }

    // endregion

    // region Aliases and unknown keys

    @Test fun camelCaseAliasesResolve() {
        val expected = mapOf(
            "categorySwitch" to SoundRole.CATEGORY_SWITCH,
            "drillIn" to SoundRole.DRILL_IN,
            "drillOut" to SoundRole.DRILL_OUT,
            "cancel" to SoundRole.BACK,
            "optionOpen" to SoundRole.OPTION,
            "systemNg" to SoundRole.SYSTEM_NG,
            "achievementUnlock" to SoundRole.TROPHY,
            "gameLaunch" to SoundRole.GAMEBOOT,
            // Canonical keys in any spelling.
            "category_switch" to SoundRole.CATEGORY_SWITCH,
            "SYSTEM_NG" to SoundRole.SYSTEM_NG,
            " Drill-In " to SoundRole.DRILL_IN,
        )
        for ((key, role) in expected) assertEquals(key, role, SoundRole.byKey(key))
        assertNull(SoundRole.byKey("whoosh"))
        assertNull(SoundRole.byKey(""))
    }

    @Test fun aliasesWorkInSoundsAndGains() {
        val theme = pack(
            "aliases",
            """{"name": "Aliases", "audio": {
                "sounds": {"systemNg": "buzz.wav", "cancel": "nope.wav"},
                "soundGains": {"systemNg": 0.25, "achievementUnlock": 0.5, "drillIn": 0.2}
            }}""",
            listOf("buzz.wav", "nope.wav"),
        )
        assertEquals("pack:buzz.wav", theme.file(SoundRole.SYSTEM_NG))
        assertEquals("pack:nope.wav", theme.file(SoundRole.BACK))
        // drill_out borrows the pack's back (cancel) file.
        assertEquals("pack:nope.wav", theme.file(SoundRole.DRILL_OUT))
        assertEquals(0.25f, theme.gains[SoundRole.SYSTEM_NG]!!, 1e-6f)
        assertEquals(0.5f, theme.gains[SoundRole.TROPHY]!!, 1e-6f)
        assertEquals(0.2f, theme.gains[SoundRole.DRILL_IN]!!, 1e-6f)
        assertTrue(theme.problems.toString(), theme.problems.isEmpty())
    }

    @Test fun unknownKeysAreReportedWithThePacksProblems() {
        val theme = pack(
            "typos",
            """{"name": "Typos", "audio": {
                "sounds": {"whoosh": "w.wav"}, "soundGains": {"cursr": 0.5}, "soundGain": {"cursor": 1}, "panAmount": 0.5
            }}""",
        )
        val problems = theme.problems.joinToString("\n")
        assertTrue(problems, problems.contains("audio.sounds.whoosh isn't a sound role"))
        assertTrue(problems, problems.contains("audio.soundGains.cursr isn't a sound role"))
        assertTrue(problems, problems.contains("theme.audio.soundGain isn't an audio property"))
        assertFalse(problems, problems.contains("panAmount"))
        assertEquals(0.5f, theme.panAmount, 0f)
    }

    @Test fun gainsAreClampedAndPanAmountIsChecked() {
        val theme = pack(
            "loud",
            """{"name": "Loud", "audio": {"soundGains": {"cursor": 3, "back": -1}, "panAmount": 7, "cursorJitter": true}}""",
            listOf("cursor.wav", "back.wav"),
        )
        assertEquals(1f, theme.gains[SoundRole.CURSOR]!!, 0f)
        assertEquals(0f, theme.gains[SoundRole.BACK]!!, 0f)
        assertEquals(1f, theme.panAmount, 0f)
        assertTrue(theme.cursorJitter)
    }

    // endregion

    // region Gains through the cascade

    @Test fun gainsFollowVariantOverrideAndRuleLastLayerWins() {
        val theme = pack(
            "layers",
            """{"name": "Layers",
                "audio": {"soundGains": {"cursor": 0.8, "ok": 0.5}},
                "variants": [{"id": "quiet", "audio": {"soundGains": {"cursor": 0.4, "trophy": 0.6}}}],
                "categoryOverrides": {"MUSIC": {"audio": {"soundGains": {"cursor": 0.3}}}},
                "systemOverrides": {"snes": {"audio": {"soundGains": {"ok": 0.9}}}},
                "rules": [
                    {"match": {"category": "MUSIC"}, "apply": {"audio": {"soundGains": {"cursor": 0.2}}}},
                    {"match": {"time": "21:00..06:00"}, "apply": {"audio": {"soundGains": {"systemNg": 0.1}}}}
                ]
            }""",
            listOf("cursor.wav", "ok.wav"),
        )
        assertTrue(theme.problems.toString(), theme.problems.isEmpty())
        fun gain(role: SoundRole, look: dev.xross.launcher.theme.pack.ThemeLook) = look.gains.getValue(role)

        val base = theme.look()
        assertEquals(0.8f, gain(SoundRole.CURSOR, base), 1e-6f)
        assertEquals(0.5f, gain(SoundRole.OK, base), 1e-6f)

        val quiet = theme.look("quiet")
        assertEquals(0.4f, gain(SoundRole.CURSOR, quiet), 1e-6f)
        assertEquals(0.6f, gain(SoundRole.TROPHY, quiet), 1e-6f)

        // Variant → category override → rule: the rule is last and wins.
        val music = theme.look("quiet", category = "music")
        assertEquals(0.2f, gain(SoundRole.CURSOR, music), 1e-6f)
        assertEquals(0.6f, gain(SoundRole.TROPHY, music), 1e-6f)

        val snes = theme.look(category = "games", system = "snes")
        assertEquals(0.9f, gain(SoundRole.OK, snes), 1e-6f)
        assertEquals(0.8f, gain(SoundRole.CURSOR, snes), 1e-6f)

        val night = theme.look(minuteOfDay = 23 * 60)
        assertEquals(0.1f, gain(SoundRole.SYSTEM_NG, night), 1e-6f)
        assertEquals(theme.gains[SoundRole.SYSTEM_NG]!!, gain(SoundRole.SYSTEM_NG, theme.look(minuteOfDay = 12 * 60)), 1e-6f)
    }

    @Test fun aBorrowedSampleFollowsItsFallbacksGainThroughTheCascade() {
        val theme = pack(
            "linked",
            """{"name": "Linked", "variants": [{"id": "soft", "audio": {"soundGains": {"cursor": 0.25}}}],
                "rules": [{"match": {"category": "VIDEO"}, "apply": {"audio": {"soundGains": {"categorySwitch": 0.7}}}}]}""",
            listOf("cursor.wav"),
        )
        // category_switch plays the pack's cursor.wav, so it gets quieter with it...
        assertEquals(0.25f, theme.look("soft").gains.getValue(SoundRole.CATEGORY_SWITCH), 1e-6f)
        // ...unless a layer names it.
        assertEquals(0.7f, theme.look("soft", category = "video").gains.getValue(SoundRole.CATEGORY_SWITCH), 1e-6f)
        assertEquals(0.25f, theme.look("soft", category = "video").gains.getValue(SoundRole.CURSOR), 1e-6f)
    }

    @Test fun patchesOnlyChangeGains() {
        val theme = pack(
            "strict",
            """{"name": "Strict", "variants": [{"id": "v", "audio": {"sounds": {"cursor": "x.wav"}, "panAmount": 0, "soundGains": {"nope": 1}}}]}""",
        )
        val problems = theme.problems.joinToString("\n")
        assertTrue(problems, problems.contains("variants.v.audio.sounds: only soundGains and bgmFile can change here"))
        assertTrue(problems, problems.contains("variants.v.audio.panAmount: only soundGains and bgmFile can change here"))
        assertTrue(problems, problems.contains("variants.v.audio.soundGains.nope isn't a sound role"))
        // The variant still exists; its look keeps the pack's gains.
        assertEquals(theme.gains, theme.look("v").gains)
    }

    @Test fun aRuleWithOnlyGainsIsKept() {
        val theme = pack("gainrule", """{"name": "GainRule", "rules": [{"match": {"battery": "<= 20"}, "apply": {"audio": {"soundGains": {"cursor": 0.1}}}}]}""")
        assertTrue(theme.problems.toString(), theme.problems.isEmpty())
        assertEquals(1, theme.rules.size)
        assertEquals(0.1f, theme.look(battery = dev.xross.launcher.theme.BatteryLevel(10, charging = false)).gains.getValue(SoundRole.CURSOR), 1e-6f)
    }

    @Test fun cascadeGainsWithoutPatchesIsTheBase() {
        val base = mapOf(SoundRole.CURSOR to 0.5f, SoundRole.CATEGORY_SWITCH to 0.5f)
        val links = mapOf(SoundRole.CATEGORY_SWITCH to SoundRole.CURSOR)
        assertTrue(base === cascadeGains(base, links, emptyList()))
        val out = cascadeGains(base, links, listOf(mapOf(SoundRole.CURSOR to 0.2f), mapOf(SoundRole.CATEGORY_SWITCH to 0.9f)))
        assertEquals(0.2f, out.getValue(SoundRole.CURSOR), 0f)
        assertEquals(0.9f, out.getValue(SoundRole.CATEGORY_SWITCH), 0f)
    }

    // endregion
}
