package com.aritr.rova.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1.6 (ROADMAP_v6 §1.6 / ADR 0003 / risk C7) — pure-math tests for
 * [StorageEstimator].
 *
 * Acceptance per v5 spec: a session sized to roughly fit (capture + final)
 * succeeds on Tier 1; the same session fails preflight on Tier 2/3 if
 * (capture + private merged + public copy) does not fit.
 */
class StorageEstimatorTest {

    private val FHD_BYTES_PER_SEC = 2L * 1024 * 1024
    private val HD_BYTES_PER_SEC = 1L * 1024 * 1024
    private val UHD_BYTES_PER_SEC = 10L * 1024 * 1024

    @Test
    fun `bytesPerSecond table matches ROADMAP C7 conservative bounds`() {
        assertEquals(UHD_BYTES_PER_SEC, StorageEstimator.bytesPerSecondForResolution("4K"))
        assertEquals(UHD_BYTES_PER_SEC, StorageEstimator.bytesPerSecondForResolution("UHD"))
        assertEquals(UHD_BYTES_PER_SEC, StorageEstimator.bytesPerSecondForResolution("2160p"))
        assertEquals(FHD_BYTES_PER_SEC, StorageEstimator.bytesPerSecondForResolution("FHD"))
        assertEquals(FHD_BYTES_PER_SEC, StorageEstimator.bytesPerSecondForResolution("1080p"))
        assertEquals(HD_BYTES_PER_SEC, StorageEstimator.bytesPerSecondForResolution("HD"))
        assertEquals(HD_BYTES_PER_SEC, StorageEstimator.bytesPerSecondForResolution("720p"))
        assertEquals(512L * 1024, StorageEstimator.bytesPerSecondForResolution("SD"))
        // Default fallback = FHD per ROADMAP §C7.
        assertEquals(FHD_BYTES_PER_SEC, StorageEstimator.bytesPerSecondForResolution("unknown"))
    }

    @Test
    fun `tier 1 peak budget is 2x captureBytes`() {
        // 60s × 5 loops × 2 MB/s = 600 MB capture.
        val peak = StorageEstimator.estimatePeakBytes(
            durationSeconds = 60,
            loopCount = 5,
            resolution = "FHD",
            tier = ExportTier.TIER1_API29_PLUS
        )
        val capture = 60L * 5 * FHD_BYTES_PER_SEC
        assertEquals(2L * capture, peak)
    }

    @Test
    fun `tier 2 peak budget is 3x captureBytes`() {
        val peak = StorageEstimator.estimatePeakBytes(
            durationSeconds = 60,
            loopCount = 5,
            resolution = "FHD",
            tier = ExportTier.TIER2_API26_28
        )
        val capture = 60L * 5 * FHD_BYTES_PER_SEC
        assertEquals(3L * capture, peak)
    }

    @Test
    fun `tier 3 peak budget is 3x captureBytes`() {
        val peak = StorageEstimator.estimatePeakBytes(
            durationSeconds = 60,
            loopCount = 5,
            resolution = "FHD",
            tier = ExportTier.TIER3_API24_25
        )
        val capture = 60L * 5 * FHD_BYTES_PER_SEC
        assertEquals(3L * capture, peak)
    }

    /**
     * Acceptance test from ROADMAP_v5 §1.6: same session config, three
     * tiers, on a constrained volume — Tier 1 fits, Tier 2/3 doesn't.
     */
    @Test
    fun `tier 1 fits constrained volume that defeats tier 2 and 3`() {
        // Pick session whose Tier 1 peak (2× capture) fits under the
        // budget but Tier 2/3 peak (3× capture) does not.
        val durationSec = 30L
        val loops = 4
        val resolution = "FHD"
        val capture = durationSec * loops * FHD_BYTES_PER_SEC
        val tier1Peak = 2 * capture
        val tier23Peak = 3 * capture
        // Volume sized between tier1 and tier2/3 — proves the multiplier is doing real work.
        val availableBytes = (tier1Peak + tier23Peak) / 2

        assertTrue(
            "tier 1 peak ($tier1Peak) must fit available volume ($availableBytes)",
            StorageEstimator.estimatePeakBytes(durationSec, loops, resolution, ExportTier.TIER1_API29_PLUS)
                <= availableBytes
        )
        assertTrue(
            "tier 2 peak ($tier23Peak) must NOT fit available volume ($availableBytes)",
            StorageEstimator.estimatePeakBytes(durationSec, loops, resolution, ExportTier.TIER2_API26_28)
                > availableBytes
        )
        assertTrue(
            "tier 3 peak ($tier23Peak) must NOT fit available volume ($availableBytes)",
            StorageEstimator.estimatePeakBytes(durationSec, loops, resolution, ExportTier.TIER3_API24_25)
                > availableBytes
        )
    }

    @Test
    fun `indefinite loop sessions reserve PREFLIGHT_HORIZON loops`() {
        val durationSec = 10L
        val resolution = "HD"
        val tier = ExportTier.TIER1_API29_PLUS
        val expectedCapture =
            durationSec * StorageEstimator.INDEFINITE_LOOP_PREFLIGHT_HORIZON * HD_BYTES_PER_SEC
        val peak = StorageEstimator.estimatePeakBytes(
            durationSeconds = durationSec,
            loopCount = -1,
            resolution = resolution,
            tier = tier
        )
        assertEquals(2L * expectedCapture, peak)
    }

    @Test
    fun `peakBudgetMultiplier extension matches estimator output`() {
        // Defensive: if anyone tweaks the multiplier in one place but not
        // the other, this test fires.
        val durationSec = 5L
        val loops = 2
        val resolution = "FHD"
        val capture = durationSec * loops * FHD_BYTES_PER_SEC

        for (tier in ExportTier.values()) {
            val peak = StorageEstimator.estimatePeakBytes(durationSec, loops, resolution, tier)
            assertEquals(
                "tier $tier",
                capture * tier.peakBudgetMultiplier,
                peak
            )
        }
    }
}
