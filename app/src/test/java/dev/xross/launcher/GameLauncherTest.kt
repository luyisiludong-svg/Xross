package dev.xross.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import dev.xross.launcher.data.GameEntity
import dev.xross.launcher.launch.AndroidIntentGameLauncher
import dev.xross.launcher.launch.GameLauncher
import dev.xross.launcher.launch.LaunchPlan
import dev.xross.launcher.launch.LaunchResult
import dev.xross.launcher.launch.LaunchTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class GameLauncherTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule val tmp = TemporaryFolder()

    private val launcher: GameLauncher = AndroidIntentGameLauncher(context)

    private fun install(pkg: String) {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply { packageName = pkg })
    }

    private fun game(path: String, platform: String = "snes") =
        GameEntity(id = 7, path = path, platformId = platform, title = "Chrono Trigger", fileName = path.substringAfterLast('/'), sizeBytes = 4, lastModified = 0)

    @Test fun missingFileFailsWithAReason() {
        install("com.retroarch.aarch64")
        val plan = launcher.plan(game("/storage/emulated/0/ROMs/snes/missing.sfc"), null)
        assertEquals(LaunchPlan.Failed("File not found — is the storage mounted?"), plan)
    }

    @Test fun unknownPlatformFails() {
        val rom = tmp.newFile("game.bin")
        assertTrue(launcher.plan(game(rom.path, platform = "vectrex9000"), null) is LaunchPlan.Failed)
    }

    @Test fun noEmulatorInstalledNamesOneToInstall() {
        val rom = tmp.newFile("Chrono Trigger.sfc")
        val plan = launcher.plan(game(rom.path), null)
        assertEquals(LaunchPlan.Failed("No emulator for SNES — install RetroArch"), plan)
    }

    @Test fun readyPlanKeepsIntentAndUriGrantInsideTheLauncher() {
        install("com.retroarch.aarch64")
        assertTrue("retroarch" in launcher.installedEmulatorIds())
        val rom = tmp.newFile("Chrono Trigger.sfc")
        val plan = launcher.plan(game(rom.path), "retroarch:snes9x") as LaunchPlan.Ready
        assertEquals("RetroArch · snes9x", plan.emulatorName)

        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        assertEquals(LaunchResult.Started, launcher.start(plan, activity))
        val started: Intent = shadowOf(activity).nextStartedActivity
        assertEquals("com.retroarch.aarch64", started.component?.packageName)
        assertEquals("content", started.data?.scheme)
        assertEquals("dev.xross.launcher.files", started.data?.authority)
        assertTrue(started.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test fun plansFromAnotherLauncherAreRefused() {
        val foreign = LaunchPlan.Ready("Elsewhere", object : LaunchTarget {})
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        assertTrue(launcher.start(foreign, activity) is LaunchResult.Failed)
    }

    @Test fun fakeLaunchersCanStandInForTheAndroidOne() {
        // The UI only depends on the interface: a test double needs no Android launch machinery.
        val fake = object : GameLauncher {
            override fun installedEmulatorIds() = setOf("duckstation")
            override fun plan(game: GameEntity, choiceKey: String?) = LaunchPlan.Ready("Fake", object : LaunchTarget {})
            override fun start(plan: LaunchPlan.Ready, host: Activity) = LaunchResult.Started
        }
        assertEquals("Fake", (fake.plan(game("/x.sfc"), null) as LaunchPlan.Ready).emulatorName)
    }
}
