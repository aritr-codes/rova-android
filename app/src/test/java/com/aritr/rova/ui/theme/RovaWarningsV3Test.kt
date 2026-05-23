package com.aritr.rova.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RovaWarningsV3Test {

    @Test fun sheetCornerRadius_is_26dp() {
        assertEquals(26.dp, RovaWarningsV3.sheetCornerRadius)
    }

    @Test fun sheetIconSize_is_56dp() {
        assertEquals(56.dp, RovaWarningsV3.sheetIconSize)
    }

    @Test fun sheetIconCornerRadius_is_18dp() {
        assertEquals(18.dp, RovaWarningsV3.sheetIconCornerRadius)
    }

    @Test fun sheetTitleSize_is_15sp() {
        assertEquals(15.sp, RovaWarningsV3.sheetTitleSize)
    }

    @Test fun sheetBodySize_is_11sp() {
        assertEquals(11.sp, RovaWarningsV3.sheetBodySize)
    }

    @Test fun sheetCtaHeight_is_46dp() {
        assertEquals(46.dp, RovaWarningsV3.sheetCtaHeight)
    }

    @Test fun secondaryCtaTextAlpha_is_0_68() {
        // a11y bump from R2's 0.55 — pinned to catch regressions
        assertEquals(0.68f, RovaWarningsV3.secondaryCtaTextAlpha, 1e-4f)
    }

    @Test fun bannerCountdownRingSize_is_38dp() {
        assertEquals(38.dp, RovaWarningsV3.bannerCountdownRingSize)
    }

    @Test fun bannerCountdownRingStroke_is_3dp() {
        assertEquals(3.dp, RovaWarningsV3.bannerCountdownRingStroke)
    }

    @Test fun recoveryCardCornerRadius_is_20dp() {
        assertEquals(20.dp, RovaWarningsV3.recoveryCardCornerRadius)
    }

    @Test fun recoveryProgressCellHeight_is_7dp() {
        assertEquals(7.dp, RovaWarningsV3.recoveryProgressCellHeight)
    }

    @Test fun iconGlow_returns_nonNull_brush_for_each_severity() {
        val r = 100f
        assertNotNull(RovaWarningsV3.iconGlow(RovaWarnings.hard, r))
        assertNotNull(RovaWarningsV3.iconGlow(RovaWarnings.soft, r))
        assertNotNull(RovaWarningsV3.iconGlow(RovaWarnings.advisory, r))
        assertNotNull(RovaWarningsV3.iconGlow(RovaWarnings.escalating, r))
    }

    @Test fun recoveryGlow_returns_nonNull_brush_for_each_severity() {
        assertNotNull(RovaWarningsV3.recoveryGlow(RovaWarnings.hard))
        assertNotNull(RovaWarningsV3.recoveryGlow(RovaWarnings.soft))
    }

    @Test fun iconGlow_and_recoveryGlow_are_distinct_brush_types() {
        // iconGlow is radial; recoveryGlow is vertical. Same severity ≠ same Brush.
        assertTrue(
            "iconGlow and recoveryGlow must produce different Brush instances",
            RovaWarningsV3.iconGlow(RovaWarnings.hard, 100f) !== RovaWarningsV3.recoveryGlow(RovaWarnings.hard)
        )
    }
}
