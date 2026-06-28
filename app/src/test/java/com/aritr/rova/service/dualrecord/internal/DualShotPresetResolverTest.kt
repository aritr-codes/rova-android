package com.aritr.rova.service.dualrecord.internal

import com.aritr.rova.data.QualityPresets
import com.aritr.rova.service.dualrecord.VideoCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DualShotPresetResolverTest {

    @Test fun sd_resolvesToQhdPerSide() {
        val s = DualShotPresetResolver.forDimensions(QualityPresets.SD)
        assertEquals(540, s.portraitW); assertEquals(960, s.portraitH)
        assertEquals(960, s.landscapeW); assertEquals(540, s.landscapeH)
        assertEquals(QualityPresets.SD, s.effectivePreset)
    }

    @Test fun hd_resolvesTo720pPerSide() {
        val s = DualShotPresetResolver.forDimensions(QualityPresets.HD)
        assertEquals(720, s.portraitW); assertEquals(1280, s.portraitH)
        assertEquals(1280, s.landscapeW); assertEquals(720, s.landscapeH)
        assertEquals(QualityPresets.HD, s.effectivePreset)
    }

    @Test fun fhd_resolvesTo1080pPerSide() {
        val s = DualShotPresetResolver.forDimensions(QualityPresets.FHD)
        assertEquals(1080, s.portraitW); assertEquals(1920, s.portraitH)
        assertEquals(1920, s.landscapeW); assertEquals(1080, s.landscapeH)
        assertEquals(QualityPresets.FHD, s.effectivePreset)
    }

    @Test fun fourK_cappedToFhd() {
        val s = DualShotPresetResolver.forDimensions(QualityPresets.UHD) // "4K"
        assertEquals(1080, s.portraitW); assertEquals(1920, s.portraitH)
        assertEquals(QualityPresets.FHD, s.effectivePreset) // capped, never "4K"
    }

    @Test fun unknownAndNull_fallbackToFhd() {
        for (raw in listOf(null, "", "garbage", "8K")) {
            val s = DualShotPresetResolver.forDimensions(raw)
            assertEquals(1080, s.portraitW); assertEquals(1920, s.portraitH)
            assertEquals(QualityPresets.FHD, s.effectivePreset)
        }
    }

    @Test fun legacyAlias_canonicalizes() {
        val s = DualShotPresetResolver.forDimensions("480p")
        assertEquals(540, s.portraitW)
        assertEquals(QualityPresets.SD, s.effectivePreset)
    }

    @Test fun everyPreset_portraitIsExact9by16_landscapeIsExact16by9() {
        for (raw in listOf(QualityPresets.SD, QualityPresets.HD, QualityPresets.FHD, QualityPresets.UHD)) {
            val s = DualShotPresetResolver.forDimensions(raw)
            // 9:16 portrait, 16:9 landscape — cross-multiply, no float
            assertEquals("portrait 9:16 for $raw", s.portraitW * 16, s.portraitH * 9)
            assertEquals("landscape 16:9 for $raw", s.landscapeW * 9, s.landscapeH * 16)
        }
    }

    @Test fun everyPreset_dimsAreEven_h264HardRequirement() {
        // ONLY %2 is asserted. NOT %8/%16: 540 is %4-only and 1080 (the
        // proven-working current FHD value) is not %16 either (1080/16=67.5).
        // Both rely on the encoder's SPS crop-rectangle padding, as 1080 does today.
        for (raw in listOf(QualityPresets.SD, QualityPresets.HD, QualityPresets.FHD, QualityPresets.UHD)) {
            val s = DualShotPresetResolver.forDimensions(raw)
            for (d in listOf(s.portraitW, s.portraitH, s.landscapeW, s.landscapeH)) {
                assertTrue("$d must be even ($raw)", d % 2 == 0)
            }
        }
    }

    private fun rate(w: Int, h: Int) =
        BitrateTable.forDimensions(w, h, VideoCodec.H264)

    @Test fun resolverDims_landInExpectedBitrateBuckets() {
        // Expected = StorageEstimator byte/s (MiB-based, 1024*1024) * 8.
        // SD 512*1024*8, HD 1024*1024*8, FHD 2*1024*1024*8 — NOT round 4/8/16 Mbps
        // (those treat MB as 10^6; the table uses MiB).
        val sdRate = 512L * 1024 * 8        // 4_194_304
        val hdRate = 1024L * 1024 * 8       // 8_388_608
        val fhdRate = 2L * 1024 * 1024 * 8  // 16_777_216

        val sd = DualShotPresetResolver.forDimensions(QualityPresets.SD)
        assertEquals(sdRate, rate(sd.portraitW, sd.portraitH))   // SD bucket
        assertEquals(sdRate, rate(sd.landscapeW, sd.landscapeH))

        val hd = DualShotPresetResolver.forDimensions(QualityPresets.HD)
        assertEquals(hdRate, rate(hd.portraitW, hd.portraitH))   // HD bucket
        assertEquals(hdRate, rate(hd.landscapeW, hd.landscapeH))

        val fhd = DualShotPresetResolver.forDimensions(QualityPresets.FHD)
        assertEquals(fhdRate, rate(fhd.portraitW, fhd.portraitH)) // FHD bucket
        assertEquals(fhdRate, rate(fhd.landscapeW, fhd.landscapeH))

        val capped = DualShotPresetResolver.forDimensions(QualityPresets.UHD)
        assertEquals(fhdRate, rate(capped.portraitW, capped.portraitH)) // capped → FHD rate

        // SD < HD < FHD ordering — the user's lever actually lightens the encode.
        assertTrue(sdRate < hdRate); assertTrue(hdRate < fhdRate)
    }
}
