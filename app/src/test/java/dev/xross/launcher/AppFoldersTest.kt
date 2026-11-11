package dev.xross.launcher

import dev.xross.launcher.data.AppEntry
import dev.xross.launcher.data.XrossSettings
import dev.xross.launcher.input.NavAction
import dev.xross.launcher.state.AppFolders
import dev.xross.launcher.state.Categories
import dev.xross.launcher.state.Command
import dev.xross.launcher.state.KeyKind
import dev.xross.launcher.state.MenuSnapshot
import dev.xross.launcher.state.MenuTree
import dev.xross.launcher.state.NameEntry
import dev.xross.launcher.state.NameEntryEffect
import dev.xross.launcher.state.NamePurpose
import dev.xross.launcher.state.NodeAction
import dev.xross.launcher.state.SearchKeyboard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppFoldersTest {
    private val retroArch = AppEntry("com.retroarch.aarch64", "com.retroarch.browser.mainmenu.MainMenuActivity", "RetroArch", isGame = false)
    private val duckStation = AppEntry("com.github.stenzek.duckstation", "com.github.stenzek.duckstation.MainActivity", "DuckStation", isGame = false)
    private val netflix = AppEntry("com.netflix.mediaclient", "com.netflix.Main", "Netflix", isGame = false)
    private val files = AppEntry("com.android.documentsui", "com.android.documentsui.Launcher", "Files", isGame = false, isSystem = true)
    private val notes = AppEntry("org.example.notes", "org.example.notes.Main", "Notes", isGame = false)
    private val runner = AppEntry("com.example.runner", "com.example.runner.Main", "Runner", isGame = true)
    private val all = listOf(duckStation, files, netflix, notes, retroArch, runner)

    private fun tree(s: XrossSettings = XrossSettings()) = MenuTree(MenuSnapshot(apps = all, settings = s, storageGranted = true))
    private fun MenuTree.apps() = list(Categories.rootKey(Categories.APPS))

    // region Hiding

    @Test fun hiddenAppsLeaveAppsAndAndroidGames() {
        val s = XrossSettings(hiddenApps = setOf(netflix.packageName, runner.packageName))
        val titles = tree(s).apps().map { it.title }
        assertFalse("Netflix" in titles)
        assertTrue("Notes" in titles)
        assertTrue(tree(s).list(MenuTree.ANDROID_GAMES).isEmpty())
        assertTrue(tree(s).list(Categories.rootKey(Categories.GAMES)).none { it.title == "Android Games" })
    }

    @Test fun hideSystemAppsHidesPreinstalledOnes() {
        assertTrue(tree().apps().any { it.title == "Files" })
        assertTrue(tree(XrossSettings(hideSystemApps = true)).apps().none { it.title == "Files" })
    }

    @Test fun everyAppOffersHideAndAndroidGamesHaveNoFolders() {
        val notesRow = tree().apps().single { it.title == "Notes" }
        assertEquals(listOf("Open", "Move to folder", "Hide"), notesRow.menu.map { it.label })
        assertEquals(Command.HideApp(notes.packageName), notesRow.menu.last().command)
        val game = tree().list(MenuTree.ANDROID_GAMES).single()
        assertEquals(listOf("Open", "Hide"), game.menu.map { it.label })
    }

    @Test fun hiddenAppsScreenListsAndRestores() {
        val s = XrossSettings(hiddenApps = setOf(netflix.packageName, "com.gone.app"), hideSystemApps = true)
        val rows = tree(s).list(MenuTree.HIDDEN_APPS)
        assertEquals(listOf("Show all hidden apps", "com.gone.app", "Netflix", "System apps"), rows.map { it.title })
        assertEquals(NodeAction.Run(Command.UnhideAllApps), rows[0].action)
        assertEquals(NodeAction.Run(Command.UnhideApp(netflix.packageName)), rows.single { it.title == "Netflix" }.action)
        assertTrue(rows.single { it.title == "com.gone.app" }.subtitle!!.startsWith("Not installed"))
        assertEquals("No hidden apps", tree().list(MenuTree.HIDDEN_APPS).single().title)
    }

    @Test fun settingsAppsSection() {
        val rows = tree(XrossSettings(hiddenApps = setOf(netflix.packageName))).list(MenuTree.APPS_SETTINGS)
        assertEquals("Hidden apps", rows[0].title)
        assertEquals("1 app", rows[0].subtitle)
        assertEquals(NodeAction.Run(Command.ToggleHideSystemApps), rows.single { it.title == "Hide system apps" }.action)
    }

    // endregion

    // region Folders

    @Test fun foldersComeFirstInAppsWithPresetsBeforeCustomNames() {
        val s = XrossSettings(
            appFolders = mapOf(
                retroArch.packageName to "Emulators", duckStation.packageName to "Emulators",
                netflix.packageName to "Streaming", notes.packageName to "Writing",
            ),
        )
        val rows = tree(s).apps()
        assertEquals(listOf("Emulators", "Streaming", "Writing", "Files"), rows.map { it.title })
        assertEquals("2 apps", rows[0].subtitle)
        assertEquals(NodeAction.OpenList(MenuTree.APP_FOLDER_PREFIX + "Emulators"), rows[0].action)
        assertEquals(listOf("DuckStation", "RetroArch"), tree(s).list(MenuTree.APP_FOLDER_PREFIX + "Emulators").map { it.title })
    }

    @Test fun foldersOnlyShowWhileTheyHoldAVisibleApp() {
        val s = XrossSettings(appFolders = mapOf(netflix.packageName to "Streaming"), hiddenApps = setOf(netflix.packageName))
        assertTrue(tree(s).apps().none { it.title == "Streaming" })
    }

    @Test fun emulatorsAreSuggestedForTheEmulatorsFolder() {
        val rows = tree().apps()
        val group = rows.first()
        assertEquals("Group emulators", group.title)
        assertEquals("Move 2 emulators into an Emulators folder", group.subtitle)
        assertEquals(NodeAction.Run(Command.GroupEmulators), group.action)

        val move = rows.single { it.title == "RetroArch" }.menu.single { it.label == "Move to folder" }
        assertEquals("Suggested: Emulators", move.detail)
        assertEquals("Emulators", move.submenu.first().label)
        assertEquals("Suggested", move.submenu.first().detail)
        assertEquals(Command.MoveAppToFolder(retroArch.packageName, "Emulators"), move.submenu.first().command)
        assertEquals(listOf("Emulators", "Streaming", "Tools", "New folder…"), move.submenu.map { it.label })

        // Once grouped the suggestion row goes away.
        val grouped = XrossSettings(appFolders = AppFolders.ungroupedEmulators(all, XrossSettings()).associate { it.packageName to "Emulators" })
        assertTrue(tree(grouped).apps().none { it.title == "Group emulators" })
    }

    @Test fun moveMenuMarksTheCurrentFolderAndOffersCustomOnes() {
        val s = XrossSettings(appFolders = mapOf(notes.packageName to "Writing", netflix.packageName to "Streaming"))
        val move = tree(s).list(MenuTree.APP_FOLDER_PREFIX + "Writing").single().menu.single { it.label == "Move to folder" }
        assertEquals("Writing", move.detail)
        assertEquals(listOf("Emulators", "Streaming", "Tools", "Writing", "New folder…", "Remove from folder"), move.submenu.map { it.label })
        assertTrue(move.submenu.single { it.label == "Writing" }.selected)
        assertEquals(Command.MoveAppToFolder(notes.packageName, null), move.submenu.last().command)
        assertEquals(Command.NewAppFolder(notes.packageName), move.submenu.single { it.label == "New folder…" }.command)
    }

    @Test fun folderNamesAreTidied() {
        assertEquals("My Tools", AppFolders.normalizeName("  MY   TOOLS "))
        assertEquals("Emulators", AppFolders.normalizeName("EMULATORS"))
        assertEquals("NES Hacks", AppFolders.normalizeName("NES HACKS"))
        assertEquals("TV & Movies", AppFolders.normalizeName("TV & MOVIES"))
        assertEquals("The Best Apps", AppFolders.normalizeName("THE BEST APPS"))
        assertEquals("iPlayer", AppFolders.normalizeName("iPlayer")) // mixed case is kept as typed
        assertNull(AppFolders.normalizeName("   "))
        assertEquals(AppFolders.MAX_NAME, AppFolders.normalizeName("A".repeat(60))!!.length)
    }

    // endregion

    // region Name keyboard

    private fun entry() = NameEntry("New folder for Notes", NamePurpose.NewAppFolder(notes.packageName))

    private fun NameEntry.typeWord(word: String) = word.fold(this) { e, c -> e.type(c) }

    @Test fun typingAndDoneSubmitTheName() {
        val typed = entry().typeWord("MY TOOLS")
        assertEquals("MY TOOLS", typed.text)
        assertEquals(NameEntryEffect.Submit("MY TOOLS"), typed.onAction(NavAction.START).effect)
        assertEquals(NameEntryEffect.Submit("MY TOOLS"), typed.onAction(NavAction.NEXT_CATEGORY).effect)
        // Confirm on the Done key submits too.
        val doneRow = SearchKeyboard.rows.lastIndex
        val done = SearchKeyboard.rows[doneRow].single { it.kind == KeyKind.DONE }
        assertEquals(NameEntryEffect.Submit("MY TOOLS"), typed.tap(doneRow, done.start).effect)
    }

    @Test fun confirmTypesTheFocusedKeyAndBackCancels() {
        val start = entry()
        val afterConfirm = start.onAction(NavAction.CONFIRM).state
        assertEquals(start.keys.focusedKey.char.toString(), afterConfirm.text)
        assertEquals(NameEntryEffect.Close, start.onAction(NavAction.BACK).effect)
        assertEquals(NameEntryEffect.None, start.onAction(NavAction.MEDIA).effect)
    }

    @Test fun blankNamesDontSubmitAndLongOnesStop() {
        assertEquals(NameEntryEffect.None, entry().onAction(NavAction.START).effect)
        assertEquals(AppFolders.MAX_NAME, entry().typeWord("X".repeat(40)).text.length)
        assertEquals("A", entry().typeWord("AB").onAction(NavAction.PREV_CATEGORY).state.text) // L1 deletes
    }

    // endregion
}
