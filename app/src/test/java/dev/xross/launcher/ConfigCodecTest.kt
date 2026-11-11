package dev.xross.launcher

import android.view.KeyEvent
import dev.xross.launcher.config.ConfigCodec
import dev.xross.launcher.data.XrossSettings
import dev.xross.launcher.input.ConfirmLayout
import dev.xross.launcher.input.ControllerProfile
import dev.xross.launcher.input.NavAction
import dev.xross.launcher.storage.PlatformRegistry
import dev.xross.launcher.storage.SystemOverride
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** settings.json, controllers.json and systems.json encoding (Robolectric: key names come from KeyEvent). */
@RunWith(RobolectricTestRunner::class)
class ConfigCodecTest {
    private val everything = XrossSettings(
        romFolders = listOf("/storage/emulated/0/ROMs", "/storage/1A2B-3C4D/Games"),
        autoDetectFolders = false,
        knownAutoFolders = listOf("/storage/1A2B-3C4D/ROMs"),
        confirmLayout = ConfirmLayout.EAST,
        deadzone = 0f,
        axialDeadzone = 0.2f,
        keyRemap = mapOf(KeyEvent.KEYCODE_BUTTON_X to NavAction.CONFIRM, KeyEvent.KEYCODE_BUTTON_SELECT to NavAction.MEDIA),
        controllerProfiles = mapOf(
            "054c:0ce6" to ControllerProfile("DualSense \"Wireless\"\nController", mapOf(KeyEvent.KEYCODE_BUTTON_Y to NavAction.CONFIRM)),
        ),
        emulatorChoice = mapOf("psx" to "retroarch:swanstation"),
        waveEnabled = false,
        waveSpeed = 1.5f,
        theme = "ORIGINAL",
        showButtonHints = false,
        soundEffects = false,
        soundVolume = 0.25f,
        bootSound = false,
        gameBootSound = false,
        hashLargeImages = true,
        datFolder = "/storage/emulated/0/DATs",
        scrapeAutomatically = true,
        scrapeWifiOnly = false,
        scrapeVideos = true,
        scrapeRegion = "jp",
        scrapeLanguage = "ja",
        hasheousEnabled = false,
        slideshowSeconds = 10,
        resumeMusicAfterGames = false,
        videoLoop = true,
        allGamesFolder = false,
        systemOverrides = mapOf("snes" to SystemOverride(extraFolders = setOf("SFC Hacks"), extraExtensions = setOf("bps"))),
        hiddenApps = setOf("com.example.ads"),
        hideSystemApps = true,
        appFolders = mapOf("com.retroarch.aarch64" to "Emulators", "com.netflix.mediaclient" to "Streaming"),
        baseRomsFolder = "/storage/1A2B-3C4D/ROMs",
        photoFolders = listOf("/storage/emulated/0/Pictures/Shots"),
        videoFolders = listOf("/storage/1A2B-3C4D/Movies", "/storage/emulated/0/Xross/media/snaps"),
    )

    private fun roundTrip(s: XrossSettings): XrossSettings {
        var out = XrossSettings()
        out = ConfigCodec.decodeSettings(out, ConfigCodec.encodeSettings(s))
        out = ConfigCodec.decodeControllers(out, ConfigCodec.encodeControllers(s))
        out = ConfigCodec.decodeSystems(out, ConfigCodec.encodeSystems(s))
        return out
    }

    @Test fun everySettingRoundTripsThroughTheThreeFiles() {
        assertEquals(everything, roundTrip(everything))
        assertEquals(XrossSettings(), roundTrip(XrossSettings()))
    }

    @Test fun settingsFileIsGroupedLikeTheSettingsMenu() {
        val root = Json.parseToJsonElement(ConfigCodec.encodeSettings(everything)).jsonObject
        assertEquals(
            setOf("version", "library", "controls", "display", "sound", "media", "metadata", "apps", "photo", "video"),
            root.keys,
        )
        val apps = root.getValue("apps").jsonObject
        assertEquals("[\"com.example.ads\"]", apps.getValue("hidden").toString())
        assertEquals("Emulators", apps.getValue("folders").jsonObject.getValue("com.retroarch.aarch64").jsonPrimitive.content)
        assertEquals("[\"/storage/emulated/0/Pictures/Shots\"]", root.getValue("photo").jsonObject.getValue("customFolders").toString())
        assertEquals("EAST", root.getValue("controls").jsonObject.getValue("confirmButton").jsonPrimitive.content)
        assertEquals("0.0", root.getValue("controls").jsonObject.getValue("stickDeadzone").jsonPrimitive.content)
    }

    @Test fun mappingsLiveOnlyInControllersJson() {
        val settings = ConfigCodec.encodeSettings(everything)
        assertTrue("KEYCODE" !in settings)
        val controllers = Json.parseToJsonElement(ConfigCodec.encodeControllers(everything)).jsonObject
        val buttons = controllers.getValue("default").jsonObject.getValue("buttons").jsonObject
        assertEquals("CONFIRM", buttons.getValue("KEYCODE_BUTTON_X").jsonPrimitive.content)
        assertEquals("MEDIA", buttons.getValue("KEYCODE_BUTTON_SELECT").jsonPrimitive.content)
        val pad = controllers.getValue("controllers").jsonObject.getValue("054c:0ce6").jsonObject
        assertEquals("DualSense \"Wireless\"\nController", pad.getValue("name").jsonPrimitive.content)
    }

    @Test fun handEditedButtonsAcceptNamesNumbersAndShortNames() {
        val text = """{"default": {"buttons": {"KEYCODE_BUTTON_A": "back", "97": "CONFIRM", "BUTTON_L2": "MEDIA",
            "KEYCODE_NOT_A_KEY": "CONFIRM", "KEYCODE_BUTTON_B": "FLY"}},
            "controllers": {"not-an-id": {"name": "x", "buttons": {"96": "BACK"}},
                            "045e:0b13": {"name": "Xbox", "buttons": {}}}}"""
        val s = ConfigCodec.decodeControllers(XrossSettings(), text)
        // 97 is KEYCODE_BUTTON_B; the unknown key and the unknown action ("FLY") are dropped.
        assertEquals(
            mapOf(
                KeyEvent.KEYCODE_BUTTON_A to NavAction.BACK,
                KeyEvent.KEYCODE_BUTTON_B to NavAction.CONFIRM,
                KeyEvent.KEYCODE_BUTTON_L2 to NavAction.MEDIA,
            ),
            s.keyRemap,
        )
        assertTrue(s.controllerProfiles.isEmpty()) // bad id dropped, empty mapping dropped
    }

    @Test fun outOfRangeAndUnknownValuesFallBackSafely() {
        val text = """{"controls": {"confirmButton": "diagonal", "stickDeadzone": 7.5, "axialDeadzone": -1},
            "display": {"theme": "", "waveSpeed": 99}, "sound": {"effectsVolume": 3},
            "media": {"slideshowSeconds": 0}, "metadata": {"region": ""}, "futureSection": {"x": 1}}"""
        val s = ConfigCodec.decodeSettings(XrossSettings(), text)
        assertEquals(ConfirmLayout.AUTO, s.confirmLayout)
        assertEquals(0.95f, s.deadzone)
        assertEquals(0f, s.axialDeadzone)
        assertEquals("LAVENDER", s.theme)
        assertEquals(4f, s.waveSpeed)
        assertEquals(1f, s.soundVolume)
        assertEquals(1, s.slideshowSeconds)
        assertEquals("us", s.scrapeRegion)
    }

    @Test fun missingKeysKeepTheirDefaults() {
        val s = ConfigCodec.decodeSettings(XrossSettings(), """{"display": {"wave": false}}""")
        assertEquals(XrossSettings(waveEnabled = false), s)
    }

    @Test(expected = SerializationException::class)
    fun brokenJsonIsReported() {
        ConfigCodec.decodeSettings(XrossSettings(), """{"display": {"wave": fals""")
    }

    @Test fun systemsFileListsEveryBuiltInSystemForReference() {
        val root = Json.parseToJsonElement(ConfigCodec.encodeSystems(everything)).jsonObject
        val systems = root.getValue("systems").jsonObject
        assertEquals(PlatformRegistry.builtIn.map { it.id }, systems.keys.toList())
        val psx = systems.getValue("psx").jsonObject
        assertEquals("retroarch:swanstation", psx.getValue("emulator").jsonPrimitive.content)
        val builtIn = psx.getValue("builtIn") as JsonObject
        assertEquals("PlayStation", builtIn.getValue("name").jsonPrimitive.content)
    }

    @Test fun systemsFileKeepsOnlyValidChoicesAndNormalizesAdditions() {
        val text = """{"systems": {
            "snes": {"emulator": "retroarch:bsnes", "extraFolders": [" Satellaview "], "extraExtensions": [".BPS", ""],
                     "builtIn": {"name": "Edited", "shortName": "X", "folders": [], "extensions": [], "emulators": []}},
            "psx": {"emulator": "not-an-emulator"},
            "commodore64": {"emulator": "vice"}}}"""
        val s = ConfigCodec.decodeSystems(XrossSettings(), text)
        assertEquals(mapOf("snes" to "retroarch:bsnes"), s.emulatorChoice)
        assertEquals(mapOf("snes" to SystemOverride(setOf("Satellaview"), setOf("bps"))), s.systemOverrides)
        // Edits to builtIn are reference only.
        assertEquals("Super Nintendo", PlatformRegistry.builtIn.first { it.id == "snes" }.name)
    }

    @Test fun overridesExtendBuiltInSystems() {
        val snes = PlatformRegistry.builtIn.first { it.id == "snes" }
        val extended = PlatformRegistry.withOverrides(
            listOf(snes),
            mapOf("snes" to SystemOverride(extraFolders = setOf("SFC Hacks"), extraExtensions = setOf(".BPS"))),
        ).single()
        assertTrue("sfchacks" in extended.folderAliases)
        assertTrue("bps" in extended.extensions)
        assertTrue(snes.extensions.all { it in extended.extensions })
        assertNull(PlatformRegistry.forFolder("sfchacks")) // the shared registry is untouched by withOverrides
    }
}
