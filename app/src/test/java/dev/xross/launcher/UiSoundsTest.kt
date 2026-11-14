package dev.xross.launcher

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.xross.launcher.audio.SamplePool
import dev.xross.launcher.audio.UiSound
import dev.xross.launcher.audio.UiSounds
import dev.xross.launcher.theme.pack.LoadedTheme
import dev.xross.launcher.theme.pack.SoundRole
import dev.xross.launcher.theme.pack.SoundSource
import dev.xross.launcher.theme.pack.ThemeLook
import dev.xross.launcher.theme.pack.ThemePackLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.sqrt

/**
 * [UiSounds] over a fake [SamplePool] (Phase 2b.5a): the equal-power pan law keeps the centre at
 * the unpanned level, panning follows the crosshair and the pack's `panAmount`, pitch jitter
 * touches only the cursor and category ticks and stays in 0.98–1.02, gains come from the look,
 * and a zero volume plays nothing.
 */
@RunWith(RobolectricTestRunner::class)
class UiSoundsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private data class Play(val id: Int, val left: Float, val right: Float, val rate: Float)

    private class FakePool : SamplePool {
        val loaded = HashMap<SoundSource, Int>()
        val plays = ArrayList<Play>()
        override fun load(source: SoundSource): Int = loaded.getOrPut(source) { loaded.size + 1 }
        override fun unload(id: Int) {
            loaded.values.remove(id)
        }
        override fun play(id: Int, left: Float, right: Float, rate: Float) {
            plays += Play(id, left, right, rate)
        }
    }

    private val default: LoadedTheme by lazy { ThemePackLoader(context.assets) { context.filesDir }.load(ThemePackLoader.DEFAULT_ID) }

    private fun sounds(theme: LoadedTheme = default, pool: FakePool = FakePool()): Pair<UiSounds, FakePool> =
        UiSounds(context, pool).also { it.useTheme(theme); it.volume = 1f } to pool

    @Test fun panLawKeepsTheCentreLevelAndIsEqualPower() {
        assertEquals(1f, UiSounds.panLeft(0f), 1e-6f)
        assertEquals(1f, UiSounds.panRight(0f), 1e-6f)
        assertEquals(sqrt(2f), UiSounds.panLeft(-1f), 1e-5f)
        assertEquals(0f, UiSounds.panRight(-1f), 1e-6f)
        assertEquals(0f, UiSounds.panLeft(1f), 1e-6f)
        assertEquals(sqrt(2f), UiSounds.panRight(1f), 1e-5f)
        // Constant power anywhere: l² + r² = 2 (the centre's 1² + 1²).
        for (p in listOf(-0.7f, -0.2f, 0.35f, 0.9f)) {
            val l = UiSounds.panLeft(p)
            val r = UiSounds.panRight(p)
            assertEquals(2f, l * l + r * r, 1e-4f)
        }
    }

    @Test fun centredPlayIsTodaysLevelAndHardPansCapAtFullScale() {
        val (sounds, pool) = sounds()
        sounds.volume = 0.5f
        val gain = default.gains.getValue(SoundRole.OK)
        sounds.play(UiSound.OK, 0f)
        val centre = pool.plays.single()
        assertEquals(0.5f * gain, centre.left, 1e-6f)
        assertEquals(0.5f * gain, centre.right, 1e-6f)
        assertEquals(1f, centre.rate, 0f)

        pool.plays.clear()
        sounds.volume = 1f
        sounds.play(UiSound.TROPHY, -1f)
        val hard = pool.plays.single()
        assertEquals(minOf(1f, default.gains.getValue(SoundRole.TROPHY) * sqrt(2f)), hard.left, 1e-6f)
        assertEquals(0f, hard.right, 1e-6f)
    }

    @Test fun panFollowsTheCrosshairTimesPanAmount() {
        val (sounds, _) = sounds()
        // Not reported yet: centred.
        assertEquals(0f, sounds.pan(), 0f)
        sounds.setCrosshair(0.5f)
        assertEquals(0f, sounds.pan(), 1e-6f)
        // The Default layout's crosshair (0.22): left of centre, by panAmount 0.35.
        sounds.setCrosshair(0.22f)
        assertEquals((0.44f - 1f) * 0.35f, sounds.pan(), 1e-6f)
        // A category switch leans 0.15 toward its direction of travel.
        assertEquals((0.44f - 1f) * 0.35f + 0.15f, sounds.pan(travel = 1), 1e-6f)
        assertEquals((0.44f - 1f) * 0.35f - 0.15f, sounds.pan(travel = -3), 1e-6f)
        sounds.setCrosshair(Float.NaN)
        assertEquals((0.44f - 1f) * 0.35f, sounds.pan(), 1e-6f)
    }

    @Test fun panAmountZeroTurnsPanningOff() {
        val (sounds, pool) = sounds(default.copy(panAmount = 0f))
        sounds.setCrosshair(0.1f)
        assertEquals(0f, sounds.pan(travel = 1), 0f)
        sounds.play(UiSound.CATEGORY, sounds.pan(travel = 1))
        assertEquals(pool.plays.single().left, pool.plays.single().right, 0f)
    }

    @Test fun defaultPlayUsesTheCrosshairPan() {
        val (sounds, pool) = sounds()
        sounds.setCrosshair(0.9f)
        sounds.play(UiSound.OK)
        val p = pool.plays.single()
        assertTrue("right of centre: right louder", p.right > p.left)
    }

    @Test fun jitterOnlyOnCursorAndCategoryAndInsideItsRange() {
        val (sounds, pool) = sounds(default.copy(cursorJitter = true))
        repeat(200) {
            sounds.play(UiSound.MOVE, 0f)
            sounds.play(UiSound.CATEGORY, 0f)
        }
        val rates = pool.plays.map { it.rate }
        assertTrue(rates.all { it in 0.98f..1.02f })
        assertTrue("it varies", rates.toSet().size > 50)
        pool.plays.clear()
        for (sound in UiSound.entries - setOf(UiSound.MOVE, UiSound.CATEGORY)) sounds.play(sound, 0f)
        assertTrue(pool.plays.isNotEmpty())
        assertTrue(pool.plays.all { it.rate == 1f })
    }

    @Test fun noJitterWithoutThePacksSwitch() {
        val (sounds, pool) = sounds()
        repeat(20) { sounds.play(UiSound.MOVE, 0f) }
        assertTrue(pool.plays.all { it.rate == 1f })
    }

    @Test fun zeroVolumeOrDisabledPlaysNothing() {
        val (sounds, pool) = sounds()
        sounds.volume = 0f
        for (sound in UiSound.entries) sounds.play(sound)
        sounds.volume = 1f
        sounds.enabled = false
        for (sound in UiSound.entries) sounds.play(sound)
        assertTrue(pool.plays.isEmpty())
    }

    @Test fun aZeroGainPlaysNothing() {
        val (sounds, pool) = sounds(default.copy(gains = default.gains + (SoundRole.OK to 0f)))
        sounds.play(UiSound.OK)
        assertTrue(pool.plays.isEmpty())
    }

    @Test fun theLooksGainsWinUntilAnotherPackLoads() {
        val (sounds, pool) = sounds()
        val look = ThemeLook(palette = null, surfaceId = "", gains = default.gains + (SoundRole.OK to 0.25f))
        sounds.useLook(default, look)
        assertEquals(0.25f, sounds.gainOf(SoundRole.OK), 0f)
        sounds.play(UiSound.OK, 0f)
        assertEquals(0.25f, pool.plays.single().left, 1e-6f)
        // A reload of the pack: back to its own gains until its look arrives.
        val reloaded = default.copy(revision = default.revision + 1)
        sounds.useTheme(reloaded)
        assertEquals(default.gains.getValue(SoundRole.OK), sounds.gainOf(SoundRole.OK), 0f)
        sounds.useLook(reloaded, look)
        assertEquals(0.25f, sounds.gainOf(SoundRole.OK), 0f)
    }

    @Test fun newRolesShareTheirFallbackSampleInTheDefaultPack() {
        val (sounds, pool) = sounds()
        assertEquals(sounds.sourceOf(UiSound.MOVE), sounds.sourceOf(UiSound.CATEGORY))
        assertEquals(sounds.sourceOf(UiSound.ENTER), sounds.sourceOf(UiSound.DRILL_IN))
        assertEquals(sounds.sourceOf(UiSound.BACK), sounds.sourceOf(UiSound.DRILL_OUT))
        // One sample per file: the pack's short effects are cursor, back, ok, system_ng and trophy.
        assertEquals(5, pool.loaded.size)
    }
}
