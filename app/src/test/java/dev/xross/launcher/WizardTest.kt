package dev.xross.launcher

import dev.xross.launcher.config.ConfigCodec
import dev.xross.launcher.launch.EsdeCommand
import dev.xross.launcher.state.ActivityChoice
import dev.xross.launcher.state.AppChoice
import dev.xross.launcher.state.EmulatorPick
import dev.xross.launcher.state.EmulatorWizard
import dev.xross.launcher.state.FolderChoice
import dev.xross.launcher.state.Handover
import dev.xross.launcher.state.SystemChoice
import dev.xross.launcher.state.SystemWizard
import dev.xross.launcher.state.WizardEffect
import dev.xross.launcher.state.WizardField
import dev.xross.launcher.state.uniqueId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.2.2.0 § 1b: the Add emulator and Add system steps, as pure state. */
class WizardTest {
    private val apps = EmulatorWizard.sortApps(
        listOf(AppChoice("com.zz.notes", "Notes"), AppChoice("org.citron.citron_emu", "Citron"), AppChoice("org.ppsspp.ppsspp", "PPSSPP", known = true)),
    )
    private val systems = listOf(SystemChoice("switch", "Nintendo Switch"), SystemChoice("xbox", "Xbox"))
    private val activities = listOf(
        ActivityChoice("org.yuzu.yuzu_emu.ui.main.MainActivity", launcher = true, acceptsView = false),
        ActivityChoice("org.yuzu.yuzu_emu.activities.EmulationActivity", launcher = false, acceptsView = true),
    )

    private fun EmulatorWizard.focus(key: String) = copy(index = rows().indexOfFirst { it.key == key }.also { require(it >= 0) { key } })

    private fun EmulatorWizard.pick(key: String): Pair<EmulatorWizard, WizardEffect?> = focus(key).confirm()

    @Test fun knownEmulatorsComeFirst() {
        assertEquals(listOf("PPSSPP", "Citron", "Notes"), apps.map { it.label })
    }

    @Test fun eachStepToASavedEntry() {
        var w = EmulatorWizard(apps, systems)
        val (afterApp, load) = w.pick("app:org.citron.citron_emu")
        assertEquals(WizardEffect.LoadActivities("org.citron.citron_emu"), load)
        w = afterApp.withActivities(activities)
        assertEquals(EmulatorWizard.Step.ACTIVITY, w.step)
        // The launcher activity is preselected and focused.
        assertEquals("org.yuzu.yuzu_emu.ui.main.MainActivity", w.activity)
        assertEquals(0, w.view().index)
        w = w.pick("act:org.yuzu.yuzu_emu.activities.EmulationActivity").first
        assertEquals(EmulatorWizard.Step.HANDOVER, w.step)
        w = w.pick("ho:URI").first
        assertEquals(EmulatorWizard.Step.SYSTEMS, w.step)
        assertEquals(WizardEffect.Refuse, w.pick("next").second) // no system yet
        w = w.pick("sys:switch").first
        w = w.pick("next").first
        assertEquals(EmulatorWizard.Step.REVIEW, w.step)
        assertEquals(WizardEffect.Save, w.pick("save").second)
        assertEquals(WizardEffect.TryIt, w.pick("try").second)
        val e = w.entry()
        assertEquals("Citron", e.name)
        assertEquals(listOf("org.citron.citron_emu"), e.packages)
        assertEquals("org.yuzu.yuzu_emu.activities.EmulationActivity", e.activity)
        assertEquals(listOf("switch"), e.systems)
        assertEquals(ConfigCodec.SOURCE_DEVICE, e.source)
        assertTrue(EsdeCommand.parse(e.command).ok)
    }

    @Test fun backWalksTheStepsAndCancelsFromTheFirst() {
        var w = EmulatorWizard(apps, systems).pick("app:org.citron.citron_emu").first.withActivities(activities)
        w = w.pick("act:org.yuzu.yuzu_emu.ui.main.MainActivity").first
        assertEquals(EmulatorWizard.Step.HANDOVER, w.step)
        w = w.back()!!
        assertEquals(EmulatorWizard.Step.ACTIVITY, w.step)
        // Back keeps the choice and lands on it.
        assertEquals(0, w.view().index)
        w = w.back()!!
        assertEquals(EmulatorWizard.Step.APP, w.step)
        assertEquals("app:org.citron.citron_emu", w.view().focused?.key)
        assertNull(w.back())
    }

    @Test fun presetsWriteCommandsThatParseBack() {
        val base = EmulatorWizard(apps, systems)
        assertEquals("%ACTION%=android.intent.action.VIEW %DATA%=%ROMPROVIDER%", base.copy(handover = Handover.URI).command)
        assertEquals("%ACTION%=android.intent.action.VIEW %DATA%=%ROM%", base.copy(handover = Handover.PATH_DATA).command)
        val extra = base.copy(handover = Handover.PATH_EXTRA).withText(WizardField.EXTRA_NAME, " bootPath ")
        assertEquals("%EXTRA_bootPath%=%ROM%", extra.command)
        for (h in Handover.entries) {
            val w = base.copy(handover = h, extraName = "bootPath", customCommand = "%DATA%=%ROMSAF%")
            assertTrue(h.name, EsdeCommand.parse(w.command).ok)
        }
    }

    @Test fun anExtraNameOrCommandIsAskedFor() {
        val w = EmulatorWizard(apps, systems, step = EmulatorWizard.Step.HANDOVER)
        val (asked, effect) = w.pick("ho:PATH_EXTRA")
        assertEquals(WizardEffect.Prompt(WizardField.EXTRA_NAME, ""), effect)
        assertEquals(EmulatorWizard.Step.HANDOVER, asked.step)
        assertEquals(EmulatorWizard.Step.SYSTEMS, asked.withText(WizardField.EXTRA_NAME, "path").step)
        // A blank answer stays on the step.
        assertEquals(EmulatorWizard.Step.HANDOVER, asked.withText(WizardField.EXTRA_NAME, " ").step)
        val (typed, prompt) = w.pick("ho:CUSTOM")
        assertTrue((prompt as WizardEffect.Prompt).initial.isNotBlank())
        val bad = typed.withText(WizardField.COMMAND, "%NOPE%=1").copy(step = EmulatorWizard.Step.REVIEW, packageName = "p", activity = "a", chosen = setOf("xbox"))
        assertTrue(bad.problems().any { "%NOPE%" in it })
        assertEquals(WizardEffect.Refuse, bad.pick("save").second)
    }

    @Test fun editingADeviceEntryOpensOnReviewWithItsValues() {
        val e = EmulatorWizard(apps, systems, packageName = "org.citron.citron_emu", name = "Citron", activity = "x.A", handover = Handover.PATH_EXTRA, extraName = "rom", chosen = setOf("xbox")).entry()
        val w = EmulatorWizard.edit("citron", e, apps, systems, activities)
        assertEquals(EmulatorWizard.Step.REVIEW, w.step)
        assertEquals(Handover.PATH_EXTRA, w.handover)
        assertEquals("rom", w.extraName)
        assertEquals(e, w.entry())
        assertEquals(WizardEffect.Delete, w.pick("delete").second)
    }

    // region System wizard

    private val folders = listOf(
        FolderChoice("/sdcard/ROMs/xbox", "xbox", underRoot = true, extensions = mapOf("iso" to 12, "jpg" to 30, "txt" to 2, "xiso" to 1)),
    )
    private val picks = listOf(EmulatorPick("dolphin", "Dolphin", "Installed"), EmulatorPick("", "Notes", newApp = AppChoice("com.zz.notes", "Notes")))

    private fun SystemWizard.pick(key: String): Pair<SystemWizard, WizardEffect?> =
        copy(index = rows().indexOfFirst { it.key == key }.also { require(it >= 0) { key } }).confirm()

    @Test fun aFolderPrefillsNameAndGameFileTypes() {
        val w = SystemWizard(folders, picks).pick("dir:/sdcard/ROMs/xbox").first
        assertEquals(SystemWizard.Step.DETAILS, w.step)
        assertEquals("Xbox", w.name)
        assertEquals(listOf("iso", "xiso"), w.extensionList)
        assertEquals("XBOX", w.entry().shortName)
    }

    @Test fun systemStepsToASavedEntry() {
        var w = SystemWizard(folders, picks).pick("dir:/sdcard/ROMs/xbox").first
        assertEquals(WizardEffect.Prompt(WizardField.SYSTEM_NAME, "Xbox"), w.pick("name").second)
        w = w.withText(WizardField.SYSTEM_NAME, "Microsoft Xbox").withText(WizardField.SYSTEM_EXTENSIONS, ".ISO; xiso, iso")
        assertEquals(listOf("iso", "xiso"), w.extensionList)
        assertEquals(WizardEffect.PickLogo, w.pick("logo").second)
        w = w.withLogo("/x/logos/xbox.png").pick("next").first
        assertEquals(SystemWizard.Step.EMULATOR, w.step)
        w = w.pick("emu:com.zz.notes").first
        assertEquals(SystemWizard.Step.REVIEW, w.step)
        assertEquals(WizardEffect.Save, w.pick("save").second)
        val c = w.entry(emulatorKey = "notes")
        assertEquals(listOf("xbox"), c.folders)
        assertEquals(listOf("notes"), c.emulators)
        assertEquals("/x/logos/xbox.png", c.logo)
        assertEquals(ConfigCodec.SOURCE_DEVICE, c.source)
    }

    @Test fun detailsNeedANameAndTypes() {
        val w = SystemWizard(folders, picks).pick("dir:/sdcard/ROMs/xbox").first.withText(WizardField.SYSTEM_EXTENSIONS, " , ")
        assertEquals(WizardEffect.Refuse, w.pick("next").second)
        assertTrue(w.problems().isNotEmpty())
    }

    @Test fun systemBackCancelsFromTheFolderStep() {
        val w = SystemWizard(folders, picks).pick("dir:/sdcard/ROMs/xbox").first
        val back = w.back()!!
        assertEquals(SystemWizard.Step.FOLDER, back.step)
        assertEquals("dir:/sdcard/ROMs/xbox", back.view().focused?.key)
        assertNull(back.back())
        assertEquals(WizardEffect.PickFolder, back.pick("pick").second)
    }

    @Test fun guessesSkipArtAndText() {
        assertEquals(listOf("chd", "cue"), SystemWizard.guessExtensions(mapOf("png" to 99, "cue" to 3, "chd" to 3, "nfo" to 9)))
    }

    @Test fun idsAreReadableAndUnique() {
        assertEquals("xbox", uniqueId("Xbox", emptySet()))
        assertEquals("xbox2", uniqueId("XBOX", setOf("xbox")))
        assertEquals("custom", uniqueId("!!", emptySet()))
    }

    // endregion
}
