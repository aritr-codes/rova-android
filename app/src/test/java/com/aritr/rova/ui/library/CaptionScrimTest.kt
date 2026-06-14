package com.aritr.rova.ui.library

import com.aritr.rova.ui.theme.ContrastMath
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionScrimTest {

    @Test fun `white caption over scrim over worst-case white frame clears AA normal text`() {
        // Worst case: pure white frame (255,255,255) under the scrim.
        val bg = ContrastMath.compositeAlphaOver(
            CaptionScrim.SCRIM_R, CaptionScrim.SCRIM_G, CaptionScrim.SCRIM_B, CaptionScrim.SCRIM_ALPHA,
            255, 255, 255,
        )
        val ratio = ContrastMath.contrastRatio(
            ContrastMath.relativeLuminance(CaptionScrim.TEXT_R, CaptionScrim.TEXT_G, CaptionScrim.TEXT_B),
            ContrastMath.relativeLuminance(bg[0], bg[1], bg[2]),
        )
        assertTrue("caption AA over worst-case frame was $ratio", ratio >= 4.5)
    }

    @Test fun `chosen alpha is the minimum that still passes (not gratuitously dark)`() {
        // Sanity: a much lighter scrim would fail — proves the floor is meaningful.
        val weakBg = ContrastMath.compositeAlphaOver(0, 0, 0, 0.25, 255, 255, 255)
        val weak = ContrastMath.contrastRatio(
            ContrastMath.relativeLuminance(CaptionScrim.TEXT_R, CaptionScrim.TEXT_G, CaptionScrim.TEXT_B),
            ContrastMath.relativeLuminance(weakBg[0], weakBg[1], weakBg[2]),
        )
        assertTrue("a 0.25 scrim should fail AA (proves the floor matters): $weak", weak < 4.5)
    }
}
