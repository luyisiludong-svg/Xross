package dev.xross.launcher

import android.app.Activity
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.xross.launcher.data.GameEntity
import dev.xross.launcher.launch.GameLauncher
import dev.xross.launcher.launch.LaunchPlan
import dev.xross.launcher.launch.LaunchResult
import dev.xross.launcher.launch.LaunchTarget
import dev.xross.launcher.state.BootMode
import dev.xross.launcher.state.BootSequence
import dev.xross.launcher.state.TransitionController
import dev.xross.launcher.state.UiEffect
import dev.xross.launcher.state.XmbViewModel
import dev.xross.launcher.theme.pack.EffectOverrides
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * The cold-start intro and the game start out of [XmbViewModel] (step 6). `BootSequenceTest`
 * still cold-starts the view model with real packs; these drive [TransitionController] on its own,
 * with a fake launcher and the main looper's clock.
 */
@RunWith(RobolectricTestRunner::class)
class TransitionControllerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val container = AppContainer(context)
    private val scope = MainScope()

    @After fun tearDown() = scope.cancel()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("Timed out waiting for $what")
            idle()
            Thread.sleep(10)
        }
    }

    private class FakeHost : TransitionController.Host {
        val failures = mutableListOf<String>()
        val effects = mutableListOf<UiEffect>()
        override fun fail(message: String) { failures += message }
        override fun emit(effect: UiEffect) { effects += effect }
    }

    private object Target : LaunchTarget

    private class FakeLauncher(var plan: LaunchPlan) : GameLauncher {
        override fun installedEmulatorIds() = emptySet<String>()
        override fun plan(game: GameEntity, choiceKey: String?) = plan
        override fun start(plan: LaunchPlan.Ready, host: Activity): LaunchResult = error("not in tests")
    }

    private val host = FakeHost()
    private val launcher = FakeLauncher(LaunchPlan.Ready("RetroArch", Target))

    private fun transitions(coldBoot: Boolean) = TransitionController(
        scope = scope,
        settings = container.settings,
        audio = container.audio,
        games = container.games,
        launcher = launcher,
        sessions = container.sessions,
        pack = { container.themes.active.value },
        category = { null },
        coldBoot = coldBoot,
        host = host,
        clock = { 1_000_000_000L },
    )

    private fun shaderIntro() = container.settings.update { it.copy(effectOverrides = EffectOverrides(bootIntro = "SHADER")) }

    @Test fun aWarmStartHasNoTransitionAndTakesNoInput() {
        val t = transitions(coldBoot = false)
        assertFalse(t.state.value.bootPending)
        assertFalse(t.takesInput())
        t.startBoot() // not a cold start: nothing to decide
        // The Default pack's intro fades in under the cue, with nothing on screen.
        assertNull(t.state.value.boot)
    }

    @Test fun aKeyBeforeTheIntroIsDecidedLeavesOnlyTheCrossfade() {
        shaderIntro()
        val t = transitions(coldBoot = true)
        assertTrue(t.state.value.bootPending)
        assertTrue("the key is taken", t.takesInput())

        t.startBoot()
        val boot = t.state.value.boot!!
        assertFalse(t.state.value.bootPending)
        assertEquals(BootMode.FADE, boot.plan.mode)
        assertTrue("straight to the crossfade", boot.ending)
        assertFalse("the crossfade takes no input", t.takesInput())
        advance(BootSequence.CROSSFADE_MS)
        assertNull(t.state.value.boot)
    }

    @Test fun aShaderIntroIsSkippedByAKeyAndEndsByItselfOtherwise() {
        shaderIntro()
        val skipped = transitions(coldBoot = true).apply { startBoot() }
        assertEquals(BootMode.SHADER, skipped.state.value.boot!!.plan.mode)
        assertTrue(skipped.takesInput())
        assertTrue(skipped.state.value.boot!!.ending)
        assertFalse("the next key reaches the menu", skipped.takesInput())

        val left = transitions(coldBoot = true).apply { startBoot() }
        advance(BootSequence.SHADER_MS)
        assertTrue(left.state.value.boot!!.ending)
        advance(BootSequence.CROSSFADE_MS)
        assertNull(left.state.value.boot)
    }

    @Test fun leavingDuringTheIntroEndsItWithoutACrossfade() {
        shaderIntro()
        val t = transitions(coldBoot = true).apply { startBoot() }
        t.onStopped()
        assertNull(t.state.value.boot)
    }

    @Test fun aGameStartPlaysTheTransitionThenHandsThePlanOver() {
        runBlocking {
            container.database.games().insertAll(
                listOf(GameEntity(path = "/sdcard/ROMs/snes/Alpha.sfc", platformId = "snes", title = "Alpha", fileName = "Alpha.sfc", sizeBytes = 1, lastModified = 0)),
            )
        }
        val id = runBlocking { container.database.games().getAll() }.single().id
        val t = transitions(coldBoot = false)
        t.launchGame(id)
        await("the start transition") { t.state.value.booting != null }
        assertEquals("Alpha", t.state.value.booting)
        assertTrue("the start transition takes the keys", t.takesInput())
        assertTrue(t.launching)
        assertEquals(emptyList<UiEffect>(), host.effects)

        advance(TransitionController.GAMEBOOT_HANDOFF_MS)
        assertEquals(listOf<UiEffect>(UiEffect.Launch(launcher.plan as LaunchPlan.Ready)), host.effects)
        advance(TransitionController.GAMEBOOT_CLEAR_MS)
        assertNull(t.state.value.booting)

        // The emulator refused it after all: the transition clears and the reason is shown.
        t.onLaunchFailed("RetroArch couldn't be started")
        assertEquals(listOf("RetroArch couldn't be started"), host.failures)

        launcher.plan = LaunchPlan.Failed("No emulator for SNES is installed")
        t.launchGame(id)
        await("the refusal") { host.failures.size == 2 }
        assertNull(t.state.value.booting)
    }
}
