package dev.xross.launcher

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.xross.launcher.config.ConfigCodec
import dev.xross.launcher.config.CustomEmulator
import dev.xross.launcher.config.CustomSystem
import dev.xross.launcher.data.CustomSystems
import dev.xross.launcher.data.GameEntity
import dev.xross.launcher.data.XrossSettings
import dev.xross.launcher.launch.AndroidIntentGameLauncher
import dev.xross.launcher.launch.EmulatorRegistry
import dev.xross.launcher.launch.EsdeCommand
import dev.xross.launcher.launch.ExtraType
import dev.xross.launcher.launch.IntentFlag
import dev.xross.launcher.launch.LaunchPlan
import dev.xross.launcher.launch.LaunchRequest
import dev.xross.launcher.launch.RecipeIntents
import dev.xross.launcher.launch.RomValues
import dev.xross.launcher.model.PlatformRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** 0.2.2.0 § 1: emulators.json recipes in ES-DE syntax, custom systems, and the merged registries. */
@RunWith(RobolectricTestRunner::class)
class CustomEmulatorsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule val tmp = TemporaryFolder()

    @After fun reset() {
        EmulatorRegistry.install(emptyMap(), emptySet())
        PlatformRegistry.install(PlatformRegistry.builtIn)
    }

    private val citron = CustomEmulator(
        name = "Citron",
        packages = listOf("org.citron.citron_emu", "org.citron.citron_emu.ea"),
        activity = "org.yuzu.yuzu_emu.activities.EmulationActivity",
        systems = listOf("switch"),
        command = "%ACTION%=android.nfc.action.TECH_DISCOVERED %DATA%=%ROMPROVIDER%",
    )

    // region EsdeCommand

    @Test fun parsesThePlansCitronLine() {
        val p = EsdeCommand.parse(citron.command)
        assertTrue(p.problems.toString(), p.ok)
        assertEquals("android.nfc.action.TECH_DISCOVERED", p.recipe.action)
        assertEquals("%ROMPROVIDER%", p.recipe.data)
    }

    @Test fun parsesEveryTag() {
        val p = EsdeCommand.parse(
            "%ACTION%=android.intent.action.VIEW %CATEGORY%=android.intent.category.DEFAULT %DATA%=%ROM% " +
                "%EXTRA_bootPath%=%ROM% %EXTRABOOL_fullscreen%=true %EXTRAINTEGER_slot%=2 %EXTRAARRAY_args%=-a,-b " +
                "%ACTIVITY_CLEAR_TASK% %ACTIVITY_CLEAR_TOP% %ACTIVITY_NO_HISTORY% %INJECT%=%BASENAME%.json",
        )
        assertTrue(p.problems.toString(), p.ok)
        val r = p.recipe
        assertEquals(listOf("android.intent.category.DEFAULT"), r.categories)
        assertEquals(
            listOf(ExtraType.STRING, ExtraType.BOOL, ExtraType.INT, ExtraType.ARRAY),
            r.extras.map { it.type },
        )
        assertEquals(listOf("bootPath", "fullscreen", "slot", "args"), r.extras.map { it.key })
        assertEquals(setOf(IntentFlag.CLEAR_TASK, IntentFlag.CLEAR_TOP, IntentFlag.NO_HISTORY), r.flags)
        assertEquals(listOf("%BASENAME%.json"), r.injects)
    }

    @Test fun anEsdeRetroArchLineParses() {
        // es_systems.xml style, minus %EMULATOR_RETROARCH%.
        val p = EsdeCommand.parse(
            "%EXTRA_CONFIGFILE%=/storage/emulated/0/Android/data/%ANDROIDPACKAGE%/files/retroarch.cfg " +
                "%EXTRA_LIBRETRO%=/data/data/%ANDROIDPACKAGE%/cores/mgba_libretro_android.so %EXTRA_ROM%=%ROM%",
        )
        assertTrue(p.problems.toString(), p.ok)
        val launch = p.recipe.resolve(RomValues("/sdcard/ROMs/gba/Game.gba", "content://x/y", "com.retroarch.aarch64"))
        assertEquals("/storage/emulated/0/Android/data/com.retroarch.aarch64/files/retroarch.cfg", launch.extras[0].value)
        assertEquals("/sdcard/ROMs/gba/Game.gba", launch.extras[2].value)
        assertFalse(launch.grantsRead)
    }

    @Test fun valuesHoldSpacesAndPlaceholders() {
        val p = EsdeCommand.parse("%EXTRA_title%=%BASENAME% Deluxe %EXTRA_dir%=%ROMPATH% %EXTRA_root%=%EXTERNALDATA%")
        val launch = p.recipe.resolve(RomValues("/sdcard/ROMs/x/My Game.iso", "content://u", "pkg"))
        assertEquals(listOf("My Game Deluxe", "/sdcard/ROMs/x", "/storage/emulated/0"), launch.extras.map { it.value })
    }

    @Test fun badInputIsListedNotThrown() {
        val p = EsdeCommand.parse("%EMULATOR_CITRON% %FOO%=1 %EXTRABOOL_x%=maybe %EXTRAINTEGER_n%=two %DATA%=%ROMZ% %WAT%")
        assertEquals(5 + 1, p.problems.size) // emulator prefix, %FOO%, bool, int, unknown value, %WAT%
        assertTrue(p.problems.any { "EMULATOR_" in it })
        assertTrue(p.problems.any { "%ROMZ%" in it })
    }

    @Test fun injectedTagsAreMerged() {
        val p = EsdeCommand.parse("%ACTION%=android.intent.action.VIEW %INJECT%=%BASENAME%.json").recipe
        val launch = p.resolve(RomValues("/r/g.iso", "content://u", "pkg")) { name ->
            if (name == "g.json") "%EXTRA_serial%=SLUS-1234" else null
        }
        assertEquals("SLUS-1234", launch.extras.single().value)
        // A missing file is skipped quietly, as in ES-DE.
        assertTrue(p.resolve(RomValues("/r/h.iso", "u", "pkg")) { null }.extras.isEmpty())
    }

    // endregion

    // region Intents

    private fun request(pkg: String) = LaunchRequest(
        romPath = "/storage/emulated/0/ROMs/switch/Game.nsp",
        romUri = Uri.parse("content://dev.xross.launcher.files/root/storage/emulated/0/ROMs/switch/Game.nsp"),
        mimeType = "application/octet-stream",
        packageName = pkg,
        core = null,
    )

    @Test fun citronRecipeBuildsTheYuzuIntent() {
        val (def, problems) = EmulatorRegistry.customDef("citron", citron)
        assertTrue(problems.toString(), problems.isEmpty())
        val i = def!!.buildIntent(request("org.citron.citron_emu"))
        assertEquals("android.nfc.action.TECH_DISCOVERED", i.action)
        assertEquals(ComponentName("org.citron.citron_emu", "org.yuzu.yuzu_emu.activities.EmulationActivity"), i.component)
        assertEquals("content", i.data?.scheme)
        assertEquals("application/octet-stream", i.type)
        assertTrue(i.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(i.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertFalse(def.needsStorageAccess)
    }

    @Test fun typedExtrasFlagsAndRelativeActivities() {
        val e = CustomEmulator(
            packages = listOf("com.example.emu"), activity = ".Play", systems = listOf("gba"),
            command = "%EXTRA_path%=%ROM% %EXTRABOOL_fast%=TRUE %EXTRAINTEGER_n%=3 %EXTRAARRAY_a%=x, y %ACTIVITY_CLEAR_TASK% %EXTRA_uri%=%ROMSAF%",
        )
        val i = EmulatorRegistry.customDef("e", e).first!!.buildIntent(request("com.example.emu"))
        assertEquals("com.example.emu.Play", i.component?.className)
        assertEquals(Intent.ACTION_MAIN, i.action)
        assertEquals("/storage/emulated/0/ROMs/switch/Game.nsp", i.getStringExtra("path"))
        assertTrue(i.getBooleanExtra("fast", false))
        assertEquals(3, i.getIntExtra("n", 0))
        assertArrayEquals(arrayOf("x", "y"), i.getStringArrayExtra("a"))
        assertTrue(i.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK != 0)
        // The URI only in an extra: granted through clip data.
        assertTrue(i.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(request("x").romUri, i.clipData?.getItemAt(0)?.uri)
    }

    @Test fun aPlainPathRecipeNeedsStorageAccess() {
        val e = citron.copy(command = "%EXTRA_bootPath%=%ROM%")
        assertTrue(EmulatorRegistry.customDef("p", e).first!!.needsStorageAccess)
    }

    // endregion

    // region Registry

    @Test fun badEntriesAreSkippedAndTheRestLoad() {
        EmulatorRegistry.install(
            mapOf(
                "citron" to citron,
                "typo" to citron.copy(command = "%ACTON%=x"),
                "nopkg" to citron.copy(packages = emptyList()),
                "dolphin" to citron, // a built-in id wins
                "nowhere" to citron.copy(systems = listOf("vectrex9000")),
            ),
            PlatformRegistry.builtIn.mapTo(HashSet()) { it.id },
        )
        assertTrue(EmulatorRegistry.byId("citron")!!.custom)
        assertNull(EmulatorRegistry.byId("typo"))
        assertNull(EmulatorRegistry.byId("nopkg"))
        assertFalse(EmulatorRegistry.byId("dolphin")!!.custom)
        // Unknown systems warn but don't stop the entry.
        assertNotNull(EmulatorRegistry.byId("nowhere"))
        assertEquals(setOf("typo", "nopkg", "dolphin", "nowhere"), EmulatorRegistry.problems.keys)
        assertTrue(EmulatorRegistry.problems.getValue("typo").single().contains("%ACTON%"))
    }

    private fun installApp(pkg: String, activity: String? = null) {
        val info = PackageInfo().apply {
            packageName = pkg
            applicationInfo = ApplicationInfo().apply { packageName = pkg }
            if (activity != null) {
                activities = arrayOf(ActivityInfo().apply { name = activity; packageName = pkg; exported = true; applicationInfo = this@apply.applicationInfo })
            }
        }
        shadowOf(context.packageManager).installPackage(info)
        if (activity != null) shadowOf(context.packageManager).addOrUpdateActivity(ActivityInfo().apply { name = activity; packageName = pkg; exported = true })
    }

    @Test fun aSpoofedPackageWithoutTheActivityIsNotInstalled() {
        val spoof = citron.copy(packages = listOf("com.antutu.ABenchMark"), activity = "org.yuzu.yuzu_emu.activities.EmulationActivity")
        EmulatorRegistry.install(mapOf("spoof" to spoof), setOf("switch"))
        installApp("com.antutu.ABenchMark") // the real benchmark app, no emulator activity
        val launcher = AndroidIntentGameLauncher(context)
        assertFalse("spoof" in launcher.installedEmulatorIds())
        installApp("com.antutu.ABenchMark", "org.yuzu.yuzu_emu.activities.EmulationActivity")
        assertTrue("spoof" in launcher.installedEmulatorIds())
    }

    @Test fun aCustomEmulatorJoinsItsSystemsAndLaunches() {
        val settings = XrossSettings(customEmulators = mapOf("citron" to citron))
        EmulatorRegistry.install(settings)
        PlatformRegistry.install(CustomSystems.platforms(settings))
        val switch = PlatformRegistry.byId("switch")!!
        assertEquals(listOf("eden", "citron"), switch.emulators.map { it.key })

        installApp("org.citron.citron_emu", "org.yuzu.yuzu_emu.activities.EmulationActivity")
        val rom = tmp.newFile("Game.nsp")
        val game = GameEntity(id = 1, path = rom.path, platformId = "switch", title = "Game", fileName = rom.name, sizeBytes = 1, lastModified = 0)
        val launcher = AndroidIntentGameLauncher(context)
        // Eden isn't installed, so the first installed option, Citron, runs.
        val plan = launcher.plan(game, null) as LaunchPlan.Ready
        assertEquals("Citron", plan.emulatorName)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        launcher.start(plan, activity)
        assertEquals("org.citron.citron_emu", shadowOf(activity).nextStartedActivity.component?.packageName)
    }

    @Test fun customSystemsScanByFolderAndAreFlagged() {
        val xbox = CustomSystem("Microsoft Xbox", "XBOX", listOf("xbox", "Xbox Games"), listOf(".ISO", "xiso"), listOf("hakux"))
        val settings = XrossSettings(customSystems = mapOf("xbox" to xbox, "snes" to xbox.copy(name = "Not SNES")))
        PlatformRegistry.install(CustomSystems.platforms(settings))
        val p = PlatformRegistry.forFolder("Xbox Games")!!
        assertEquals("xbox", p.id)
        assertTrue(p.custom)
        assertEquals(setOf("iso", "xiso"), p.extensions)
        assertEquals(listOf("hakux"), p.emulators.map { it.key })
        // A built-in id always wins.
        assertEquals("Super Nintendo", PlatformRegistry.byId("snes")!!.name)
        assertTrue(CustomSystems.problems(settings).any { it.first == "snes" })
    }

    @Test fun aCustomSystemCantStealABuiltInFolder() {
        val settings = XrossSettings(customSystems = mapOf("mysnes" to CustomSystem("Mine", "MINE", listOf("snes"), listOf("sfc"), emptyList())))
        PlatformRegistry.install(CustomSystems.platforms(settings))
        assertEquals("snes", PlatformRegistry.forFolder("snes")!!.id)
        assertTrue(CustomSystems.problems(settings).single().second.contains("SNES"))
    }

    @Test fun pcEngineCdFoldersAreTheBaseSystem() {
        for (folder in listOf("pcenginecd", "tg-cd", "turbografxcd", "supercd")) {
            assertEquals(folder, "pcengine", PlatformRegistry.forFolder(folder)?.id)
        }
    }

    // endregion

    // region emulators.json and systems.json

    private val handWritten = """
        // My emulators
        {
          "version": 1,
          "emulators": {
            /* Switch */
            "citron": {
              "name": "Citron",
              "packages": ["org.citron.citron_emu"],
              "activity": "org.yuzu.yuzu_emu.activities.EmulationActivity",
              "systems": ["switch"],
              "command": "%ACTION%=android.nfc.action.TECH_DISCOVERED %DATA%=%ROMPROVIDER%", // works
            },
          },
        }
    """.trimIndent() + "\n"

    private fun decode(text: String) = ConfigCodec.decodeEmulators(XrossSettings(), text)

    @Test fun handWrittenFilesWithCommentsReadAndStayAsTheyAre() {
        val s = decode(handWritten)
        assertEquals("Citron", s.customEmulators.getValue("citron").name)
        assertEquals(handWritten, ConfigCodec.encodeEmulators(s, handWritten))
    }

    @Test fun deviceEntriesAreSplicedInWithoutTouchingHandWrittenOnes() {
        val s = decode(handWritten)
        val device = CustomEmulator("My Emu", listOf("com.example.emu"), ".Main", listOf("gba"), "%DATA%=%ROMPROVIDER%", ConfigCodec.SOURCE_DEVICE)
        val added = ConfigCodec.encodeEmulators(s.copy(customEmulators = s.customEmulators + ("myemu" to device)), handWritten)
        assertTrue(added.startsWith(handWritten.substringBefore("// works")))
        assertTrue("/* Switch */" in added && "// works" in added && "// My emulators" in added)
        val back = decode(added)
        assertEquals(setOf("citron", "myemu"), back.customEmulators.keys)
        assertEquals(device, back.customEmulators.getValue("myemu"))

        // Edit, then delete the device entry: the hand-written text comes back unchanged.
        val edited = ConfigCodec.encodeEmulators(back.copy(customEmulators = back.customEmulators + ("myemu" to device.copy(name = "Renamed"))), added)
        assertEquals("Renamed", decode(edited).customEmulators.getValue("myemu").name)
        assertTrue("// works" in edited)
        val removed = ConfigCodec.encodeEmulators(back.copy(customEmulators = back.customEmulators - "myemu"), edited)
        assertEquals(s.customEmulators, decode(removed).customEmulators)
        assertTrue("// works" in removed && "/* Switch */" in removed)
    }

    @Test fun aNewFileIsWrittenWhole() {
        val s = XrossSettings(customEmulators = mapOf("citron" to citron))
        val text = ConfigCodec.encodeEmulators(s, previous = null)
        assertEquals(s.customEmulators, decode(text).customEmulators)
        // The first device entry in an empty section.
        val empty = "{\n  \"version\": 1,\n  \"emulators\": {}\n}\n"
        val d = citron.copy(source = ConfigCodec.SOURCE_DEVICE)
        val out = ConfigCodec.encodeEmulators(XrossSettings(customEmulators = mapOf("d" to d)), empty)
        assertEquals(d, decode(out).customEmulators.getValue("d"))
    }

    @Test fun customSystemsRoundTripThroughSystemsJson() {
        val xbox = CustomSystem("Microsoft Xbox", "XBOX", listOf("xbox"), listOf("iso", "xiso"), listOf("hakux"), source = ConfigCodec.SOURCE_DEVICE)
        val s = XrossSettings(
            customSystems = mapOf("xbox" to xbox),
            customEmulators = mapOf("citron" to citron),
            emulatorChoice = mapOf("xbox" to "hakux", "switch" to "citron"),
        )
        val text = ConfigCodec.encodeSystems(s)
        val back = ConfigCodec.decodeSystems(XrossSettings(customEmulators = s.customEmulators), text)
        assertEquals(s.customSystems, back.customSystems)
        assertEquals(s.emulatorChoice, back.emulatorChoice)
        // Without the custom emulator, the built-in system's choice of it is dropped.
        assertNull(ConfigCodec.decodeSystems(XrossSettings(), text).emulatorChoice["switch"])
    }

    @Test fun builtInSystemsDontCarryTheCustomKeys() {
        val text = ConfigCodec.encodeSystems(XrossSettings())
        val snes = text.substringAfter("\"snes\"").substringBefore("\"builtIn\"")
        for (key in listOf("name", "shortName", "folders", "extensions", "emulators", "logo", "source")) {
            assertFalse(key, "\"$key\"" in snes)
        }
    }

    @Test fun theSamplesAreValidJsonWithComments() {
        val emulators = context.assets.open("config/emulators.example.json").bufferedReader().readText()
        val s = decode(emulators)
        assertTrue("citron" in s.customEmulators)
        for ((id, e) in s.customEmulators) assertTrue(id, EsdeCommand.parse(e.command).ok)
        val systems = context.assets.open("config/systems.example.json").bufferedReader().readText()
        assertTrue("xbox" in ConfigCodec.decodeSystems(XrossSettings(), systems).customSystems)
    }

    // endregion
}
