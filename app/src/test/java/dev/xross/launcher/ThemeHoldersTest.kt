package dev.xross.launcher

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import dev.xross.launcher.theme.pack.Surface
import dev.xross.launcher.theme.pack.SurfaceShape
import dev.xross.launcher.ui.LocalThemeAccent
import dev.xross.launcher.ui.LocalThemeSurfaces
import dev.xross.launcher.ui.ThemeSurfaces
import dev.xross.launcher.ui.shapeFor
import dev.xross.launcher.ui.xrossSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The draw-phase theme holders (Assessment §7.5, step 8): provided per composition root through
 * `CompositionLocal`s instead of living in global objects, and still read while drawing.
 */
@RunWith(RobolectricTestRunner::class)
class ThemeHoldersTest {
    @get:Rule val compose = createComposeRule()

    /** No hidden shared default: a composition that reads a holder must be given one. */
    @Test fun aHolderThatIsNotProvidedFailsLoudly() {
        val failure = runCatching { compose.setContent { LocalThemeAccent.current } }.exceptionOrNull()
        assertTrue("$failure", failure is IllegalStateException && "ThemeAccent" in failure.message.orEmpty())
    }

    /**
     * Switching the provided holder's surface reaches the surface's layer (its shape is read again)
     * without recomposing the component that draws it.
     */
    @Test fun aSurfaceSwitchUpdatesTheSurfaceWithoutRecomposing() {
        val surfaces = ThemeSurfaces()
        var compositions = 0
        val seen = mutableListOf<String>()
        compose.setContent {
            CompositionLocalProvider(LocalThemeSurfaces provides surfaces) {
                SideEffect { compositions++ }
                Box(Modifier.size(40.dp).xrossSurface(shape = { s -> seen += s.id; s.shapeFor(RoundedCornerShape(4.dp)) }, strength = { 1f }))
            }
        }
        compose.waitForIdle()
        val before = compositions
        assertTrue(seen.toString(), Surface.FALLBACK.id in seen)

        seen.clear()
        surfaces.active = Surface.FALLBACK.copy(id = "SQUARE", shape = SurfaceShape.Rectangle)
        compose.waitForIdle()

        assertTrue(seen.toString(), "SQUARE" in seen)
        assertEquals("recompositions", before, compositions)
    }

    /** Two roots, two holders: one's surface never shows in the other. */
    @Test fun twoRootsDoNotShareASurface() {
        val a = ThemeSurfaces()
        val b = ThemeSurfaces()
        val seenA = mutableListOf<String>()
        val seenB = mutableListOf<String>()
        compose.setContent {
            CompositionLocalProvider(LocalThemeSurfaces provides a) {
                Box(Modifier.size(40.dp).xrossSurface(shape = { s -> seenA += s.id; s.shapeFor(RoundedCornerShape(4.dp)) }, strength = { 1f }))
            }
            CompositionLocalProvider(LocalThemeSurfaces provides b) {
                Box(Modifier.size(40.dp).xrossSurface(shape = { s -> seenB += s.id; s.shapeFor(RoundedCornerShape(4.dp)) }, strength = { 1f }))
            }
        }
        compose.waitForIdle()
        seenA.clear()
        seenB.clear()

        a.active = Surface.FALLBACK.copy(id = "ONLY_A", shape = SurfaceShape.Rectangle)
        compose.waitForIdle()

        assertTrue(seenA.toString(), "ONLY_A" in seenA)
        assertTrue(seenB.toString(), "ONLY_A" !in seenB)
    }
}
