package com.aritr.rova.ui.library

import com.aritr.rova.service.dualrecord.VideoSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OrientationResolverTest {

    // --- side is authoritative (DualShot per-side rows); wins even against contrary thumb dims ---

    @Test fun side_portrait_wins() {
        assertEquals(LibraryOrientation.PORTRAIT, OrientationResolver.resolve(VideoSide.PORTRAIT, 1920, 1080))
    }

    @Test fun side_landscape_wins() {
        assertEquals(LibraryOrientation.LANDSCAPE, OrientationResolver.resolve(VideoSide.LANDSCAPE, 1080, 1920))
    }

    // --- single-mode rows derive from the (rotation-corrected) thumbnail dimensions ---

    @Test fun thumb_tallerIsPortrait() {
        assertEquals(LibraryOrientation.PORTRAIT, OrientationResolver.resolve(null, 1080, 1920))
    }

    @Test fun thumb_widerIsLandscape() {
        assertEquals(LibraryOrientation.LANDSCAPE, OrientationResolver.resolve(null, 1920, 1080))
    }

    // --- no verdict ---

    @Test fun square_isNull() {
        assertNull(OrientationResolver.resolve(null, 1080, 1080))
    }

    @Test fun noThumbnailYet_isNull() {
        assertNull(OrientationResolver.resolve(null, 0, 0))
    }

    @Test fun negativeOrZeroDim_isNull() {
        assertNull(OrientationResolver.resolve(null, -1, 100))
        assertNull(OrientationResolver.resolve(null, 100, 0))
    }
}
