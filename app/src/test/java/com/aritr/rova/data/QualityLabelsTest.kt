package com.aritr.rova.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM-only tests pinning the contract the History label and the
 * picker share. Boundaries are exercised both from the upper tier
 * (just-on the bucket) and the lower tier (one pixel below) so a
 * future refactor can't silently shift a tier by ±1px.
 *
 * Portrait orientations are checked explicitly because the helper
 * promises that EITHER axis can satisfy a tier — clamping to the
 * larger dimension only would silently downgrade portrait recordings.
 */
class QualityLabelsTest {

    @Test
    fun `4K landscape - 3840x2160 maps to 4K`() {
        assertEquals("4K", QualityLabels.forDimensions(3840, 2160))
    }

    @Test
    fun `4K portrait - 2160x3840 maps to 4K`() {
        assertEquals("4K", QualityLabels.forDimensions(2160, 3840))
    }

    @Test
    fun `4K above-tier - 7680x4320 still maps to 4K`() {
        // 8K input has no dedicated bucket; saturate at the highest tier.
        assertEquals("4K", QualityLabels.forDimensions(7680, 4320))
    }

    @Test
    fun `FHD landscape - 1920x1080 maps to FHD`() {
        assertEquals("FHD", QualityLabels.forDimensions(1920, 1080))
    }

    @Test
    fun `FHD portrait - 1080x1920 maps to FHD`() {
        assertEquals("FHD", QualityLabels.forDimensions(1080, 1920))
    }

    @Test
    fun `FHD just below 4K - 3839x2159 maps to FHD`() {
        // One pixel under both 4K thresholds — must NOT round up.
        assertEquals("FHD", QualityLabels.forDimensions(3839, 2159))
    }

    @Test
    fun `HD landscape - 1280x720 maps to HD`() {
        assertEquals("HD", QualityLabels.forDimensions(1280, 720))
    }

    @Test
    fun `HD portrait - 720x1280 maps to HD`() {
        assertEquals("HD", QualityLabels.forDimensions(720, 1280))
    }

    @Test
    fun `HD just below FHD - 1919x1079 maps to HD`() {
        assertEquals("HD", QualityLabels.forDimensions(1919, 1079))
    }

    @Test
    fun `SD landscape - 640x480 maps to SD`() {
        assertEquals("SD", QualityLabels.forDimensions(640, 480))
    }

    @Test
    fun `SD just below HD - 1279x719 maps to SD`() {
        assertEquals("SD", QualityLabels.forDimensions(1279, 719))
    }

    @Test
    fun `SD very small - 320x240 maps to SD`() {
        // Spec says "else SD" — sub-480p still buckets as SD rather
        // than a separate "tiny" tier. Picker has no SD-below option.
        assertEquals("SD", QualityLabels.forDimensions(320, 240))
    }

    @Test
    fun `degenerate ribbon - 100x1080 falls back to SD`() {
        // A 100×1080 frame meets the FHD long-side threshold but
        // not the short-side floor, so it is NOT FHD — the bucket
        // requires both axes to clear the tier (orientation-tolerant
        // AND-pair, not either-axis OR). Real recordings are never
        // this aspect ratio; pinning the case prevents a future
        // refactor from drifting back to the OR-rule.
        assertEquals("SD", QualityLabels.forDimensions(100, 1080))
    }

    @Test
    fun `degenerate ribbon - 1280x100 falls back to SD`() {
        // Same orientation-AND rule, landscape ribbon. 1280 clears
        // the HD long-side threshold but the 100px short side
        // disqualifies the entry from any tier above SD.
        assertEquals("SD", QualityLabels.forDimensions(1280, 100))
    }

    @Test
    fun `zero width returns null`() {
        assertNull(QualityLabels.forDimensions(0, 1080))
    }

    @Test
    fun `zero height returns null`() {
        assertNull(QualityLabels.forDimensions(1920, 0))
    }

    @Test
    fun `negative width returns null`() {
        // Defensive: extractMetadata can yield odd values on broken files.
        assertNull(QualityLabels.forDimensions(-1, 1080))
    }

    @Test
    fun `negative height returns null`() {
        assertNull(QualityLabels.forDimensions(1920, -1))
    }

    @Test
    fun `both zero returns null`() {
        assertNull(QualityLabels.forDimensions(0, 0))
    }
}
