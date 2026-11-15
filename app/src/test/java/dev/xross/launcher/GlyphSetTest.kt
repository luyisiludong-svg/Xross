package dev.xross.launcher

import dev.xross.launcher.input.ConfirmLayout
import dev.xross.launcher.input.ControllerFamily
import dev.xross.launcher.input.resolve
import dev.xross.launcher.model.GlyphSet
import dev.xross.launcher.ui.FaceShape
import org.junit.Assert.assertEquals
import org.junit.Test
import dev.xross.launcher.model.Face
import dev.xross.launcher.state.XmbUiState

/** 2b.7-prep: the face-button labels split from controller detection, with today's strings. */
class GlyphSetTest {
    /** The strings `ControllerFamily` carried before the split, as south, east, north, west. */
    private val before = mapOf(
        ControllerFamily.PLAYSTATION to listOf("✕", "○", "△", "□"),
        ControllerFamily.XBOX to listOf("A", "B", "Y", "X"),
        ControllerFamily.NINTENDO to listOf("B", "A", "X", "Y"),
        ControllerFamily.GENERIC to listOf("A", "B", "Y", "X"),
        ControllerFamily.KEYBOARD to listOf("Enter", "Esc", "Tab", "Space"),
    )

    @Test fun eachFamilysGlyphsEqualTodaysStrings() {
        assertEquals(ControllerFamily.entries.toSet(), before.keys)
        for ((family, faces) in before) {
            val g = family.glyphs
            assertEquals(family.name, faces, listOf(g.south, g.east, g.north, g.west))
        }
    }

    @Test fun detectionAndConfirmLayoutAreUnchanged() {
        assertEquals(ControllerFamily.PLAYSTATION, ControllerFamily.fromVendor(0x054C, isGamepad = true))
        assertEquals(ControllerFamily.XBOX, ControllerFamily.fromVendor(0x045E, isGamepad = true))
        assertEquals(ControllerFamily.NINTENDO, ControllerFamily.fromVendor(0x057E, isGamepad = true))
        assertEquals(ControllerFamily.GENERIC, ControllerFamily.fromVendor(0x1234, isGamepad = true))
        assertEquals(ControllerFamily.KEYBOARD, ControllerFamily.fromVendor(0x054C, isGamepad = false))
        assertEquals(ConfirmLayout.EAST, ConfirmLayout.AUTO.resolve(ControllerFamily.NINTENDO))
        assertEquals(ConfirmLayout.SOUTH, ConfirmLayout.AUTO.resolve(ControllerFamily.PLAYSTATION))
        assertEquals(ConfirmLayout.SOUTH, ConfirmLayout.SOUTH.resolve(ControllerFamily.NINTENDO))
    }

    @Test fun theUiStateCarriesTheDetectedFamilysGlyphsByDefault() {
        assertEquals(GlyphSet.KEYBOARD, XmbUiState().glyphs)
    }

    @Test fun shapeLabelsAreDrawnAndLettersStayText() {

        // Every label of the PlayStation and minimal sets is a drawn shape, each its own.
        for (set in listOf(GlyphSet.PLAYSTATION, GlyphSet.MINIMAL)) {
            val shapes = Face.entries.map { FaceShape.of(set[it]) }
            assertEquals("$set", 4, shapes.filterNotNull().distinct().size)
        }
        assertEquals(FaceShape.CROSS, FaceShape.of("✕"))
        assertEquals(FaceShape.CIRCLE, FaceShape.of("○"))
        assertEquals(FaceShape.TRIANGLE_UP, FaceShape.of("△"))
        assertEquals(FaceShape.SQUARE, FaceShape.of("□"))
        assertEquals(FaceShape.TRIANGLE_DOWN, FaceShape.of("▽"))
        // Letters and words stay text.
        for (set in listOf(GlyphSet.XBOX, GlyphSet.NINTENDO, GlyphSet.KEYBOARD)) {
            assertEquals("$set", emptyList<Any>(), Face.entries.mapNotNull { FaceShape.of(set[it]) })
        }
        assertEquals(null, FaceShape.of("L1"))
    }
}
