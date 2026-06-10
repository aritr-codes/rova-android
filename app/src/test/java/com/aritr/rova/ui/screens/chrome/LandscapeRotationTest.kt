package com.aritr.rova.ui.screens.chrome

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Test

class LandscapeRotationTest {

    @Test fun rotation90_and_270_are_opposite_senses() {
        assertEquals(DeviceLandscape.A, landscapeSense(Surface.ROTATION_90))
        assertEquals(DeviceLandscape.B, landscapeSense(Surface.ROTATION_270))
    }

    @Test fun portrait_rotations_have_no_sense() {
        assertEquals(null, landscapeSense(Surface.ROTATION_0))
        assertEquals(null, landscapeSense(Surface.ROTATION_180))
    }

    @Test fun senseA_matches_native_portraitLeft_to_bottom() {
        assertEquals(NavEdge.Trailing, clusterEdge(DeviceLandscape.A))
        // [Library, FAB, Settings] L->R  =>  top->bottom = [Settings, FAB, Library]
        assertEquals(
            listOf("Settings", "FAB", "Library"),
            railOrder(listOf("Library", "FAB", "Settings"), DeviceLandscape.A),
        )
    }

    @Test fun senseB_is_the_mirror() {
        assertEquals(NavEdge.Leading, clusterEdge(DeviceLandscape.B))
        assertEquals(
            listOf("Library", "FAB", "Settings"),
            railOrder(listOf("Library", "FAB", "Settings"), DeviceLandscape.B),
        )
    }

    @Test fun senseA_is_the_reverse_of_senseB() {
        val src = listOf("a", "b", "c", "d", "e")
        // The two senses are mirror images: A is B reversed.
        assertEquals(railOrder(src, DeviceLandscape.B).reversed(), railOrder(src, DeviceLandscape.A))
        // Double-reverse is identity (adjacency preserved).
        assertEquals(src, railOrder(railOrder(src, DeviceLandscape.A), DeviceLandscape.A))
    }
}
