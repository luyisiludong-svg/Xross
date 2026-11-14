package dev.xross.launcher

import dev.xross.launcher.audio.MusicUnderSnaps
import dev.xross.launcher.audio.VolumeChannel
import dev.xross.launcher.config.ConfigCodec
import dev.xross.launcher.data.XrossSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Phase 2b.5b: the separate volumes in settings.json, their migration and their steps. */
@RunWith(RobolectricTestRunner::class)
class VolumeSettingsTest {
    private fun decode(sound: String) = ConfigCodec.decodeSettings(XrossSettings(), """{"sound": {$sound}}""")

    @Test fun aFileFromBefore2b5bKeepsItsMix() {
        val s = decode(""""effects": true, "effectsVolume": 0.4, "startupSound": true, "gameStartSound": true""")
        assertEquals(0.4f, s.soundVolume)
        // Cues and snaps followed the one Effects volume until now.
        assertEquals(0.4f, s.cueVolume)
        assertEquals(0.4f, s.snapVolume)
        assertEquals(1f, s.musicVolume)
        assertEquals(0.6f, s.bgmVolume)
        assertEquals(MusicUnderSnaps.PAUSE, s.musicUnderSnaps)
    }

    @Test fun anEmptyFileTakesTheDefaults() {
        val s = ConfigCodec.decodeSettings(XrossSettings(), "{}")
        assertEquals(XrossSettings(), s)
        assertEquals(0.75f, s.cueVolume)
        assertEquals(0.75f, s.snapVolume)
    }

    @Test fun volumesRoundTripUnderTheirOwnKeys() {
        val s = XrossSettings(soundVolume = 0.2f, cueVolume = 0.3f, bgmVolume = 0.4f, musicVolume = 0.5f, snapVolume = 0.6f, musicUnderSnaps = MusicUnderSnaps.DUCK)
        val text = ConfigCodec.encodeSettings(s)
        val sound = Json.parseToJsonElement(text).jsonObject["sound"]!!.jsonObject
        assertEquals("0.3", sound["cueVolume"]!!.jsonPrimitive.content)
        assertEquals("0.4", sound["bgmVolume"]!!.jsonPrimitive.content)
        assertEquals("0.5", sound["musicVolume"]!!.jsonPrimitive.content)
        assertEquals("0.6", sound["snapVolume"]!!.jsonPrimitive.content)
        assertEquals("DUCK", sound["musicUnderSnaps"]!!.jsonPrimitive.content)
        assertEquals(s, ConfigCodec.decodeSettings(XrossSettings(), text))
    }

    @Test fun outOfRangeVolumesClamp() {
        val s = decode(""""effectsVolume": 0.5, "cueVolume": 3, "bgmVolume": -1, "musicVolume": 1.5, "snapVolume": -0.2""")
        assertEquals(1f, s.cueVolume)
        assertEquals(0f, s.bgmVolume)
        assertEquals(1f, s.musicVolume)
        assertEquals(0f, s.snapVolume)
    }

    /** NaN isn't JSON: the file is reported as broken, like any other syntax error, and the settings in use stay. */
    @Test(expected = kotlinx.serialization.SerializationException::class)
    fun notANumberIsABrokenFile() {
        decode(""""effectsVolume": 0.5, "cueVolume": NaN""")
    }

    @Test fun musicUnderSnapsDefaultsToPauseAndReadsItsNamesInAnyCase() {
        assertEquals(MusicUnderSnaps.PAUSE, decode(""""musicUnderSnaps": "PAUSE"""").musicUnderSnaps)
        assertEquals(MusicUnderSnaps.DUCK, decode(""""musicUnderSnaps": "duck"""").musicUnderSnaps)
        assertEquals(MusicUnderSnaps.DUCK, decode(""""musicUnderSnaps": "Lower"""").musicUnderSnaps)
        assertEquals(MusicUnderSnaps.PAUSE, decode(""""musicUnderSnaps": "stop"""").musicUnderSnaps)
        assertEquals(MusicUnderSnaps.PAUSE, decode(""""musicUnderSnaps": """"").musicUnderSnaps)
        assertEquals(MusicUnderSnaps.PAUSE, decode(""""musicUnderSnaps": null""").musicUnderSnaps)
    }

    @Test fun legacyPreferencesSplitTheOneVolume() {
        val prefs = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSharedPreferences("volume-test", android.content.Context.MODE_PRIVATE)
        prefs.edit().putFloat(dev.xross.launcher.data.SettingsStore.KEY_SFX_VOLUME, 0.25f).commit()
        val s = dev.xross.launcher.data.SettingsStore.loadLegacy(prefs)
        assertEquals(0.25f, s.cueVolume)
        assertEquals(0.25f, s.snapVolume)
        assertEquals(1f, s.musicVolume)
    }

    @Test fun volumeRowsStepTenPercentAndWrap() {
        assertEquals(0.1f, VolumeChannel.next(0f), 1e-6f)
        assertEquals(0.8f, VolumeChannel.next(0.75f), 1e-6f) // the old 25% steps join the 10% ones
        assertEquals(1f, VolumeChannel.next(0.9f), 1e-6f)
        assertEquals(0f, VolumeChannel.next(1f), 0f)
        var v = 0f
        repeat(11) { v = VolumeChannel.next(v) }
        assertEquals(0f, v, 0f)
        assertEquals("70%", VolumeChannel.percent(0.7f))
    }

    @Test fun eachChannelReadsAndWritesItsOwnSetting() {
        var s = XrossSettings()
        for ((i, c) in VolumeChannel.entries.withIndex()) s = c.set(s, i / 10f)
        assertEquals(XrossSettings(soundVolume = 0f, cueVolume = 0.1f, bgmVolume = 0.2f, musicVolume = 0.3f, snapVolume = 0.4f), s)
        for ((i, c) in VolumeChannel.entries.withIndex()) assertEquals(i / 10f, c.of(s))
        assertEquals(1f, VolumeChannel.MUSIC.set(s, 5f).musicVolume)
        assertEquals(0f, VolumeChannel.MUSIC.set(s, Float.NaN).musicVolume)
    }

    @Test fun resetToThemeDefaultsKeepsTheVolumes() {
        val s = XrossSettings(soundVolume = 0.2f, cueVolume = 0.3f, bgmVolume = 0.4f, musicVolume = 0.5f, snapVolume = 0.6f, musicUnderSnaps = MusicUnderSnaps.DUCK, surfaceStyle = "OLED_TRUE_BLACK")
        val r = dev.xross.launcher.theme.pack.ThemeDefaults.reset(s)
        assertEquals("", r.surfaceStyle)
        for (c in VolumeChannel.entries) assertEquals(c.of(s), c.of(r))
        assertEquals(MusicUnderSnaps.DUCK, r.musicUnderSnaps)
    }
}
