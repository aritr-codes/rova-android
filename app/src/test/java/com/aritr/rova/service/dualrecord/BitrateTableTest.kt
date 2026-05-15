package com.aritr.rova.service.dualrecord

import com.aritr.rova.data.QualityPresets
import com.aritr.rova.data.StorageEstimator
import com.aritr.rova.service.dualrecord.internal.BitrateTable
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 6.1a — output resolution → bitrate (bps). MUST stay aligned with
 * StorageEstimator.bytesPerSecondForResolution so peak-bytes math is
 * consistent across the codebase.
 *
 * D-deviation (Task 5): tests target [BitrateTable.forDimensions]
 * directly. The `Size` overload would require either Robolectric or a
 * mocking framework — the project's pure-JVM test harness has
 * `unitTests.isReturnDefaultValues = true`, under which `android.util.Size`
 * is a stub whose `width`/`height` accessors return 0. The two entry
 * points share their implementation; `forSize` is a one-line delegate
 * that is exercised in instrumentation paths.
 */
class BitrateTableTest {

    @Test
    fun `FHD landscape returns FHD bitrate from StorageEstimator`() {
        val bps = BitrateTable.forDimensions(1920, 1080, VideoCodec.H264)
        val expected = StorageEstimator.bytesPerSecondForResolution(QualityPresets.FHD) * 8L
        assertEquals(expected, bps)
    }

    @Test
    fun `FHD portrait normalizes to the same FHD bucket as landscape`() {
        // Portrait 1080x1920 has the same pixel count as landscape 1920x1080;
        // the lookup is orientation-agnostic — both go to the FHD bucket.
        val bpsPortrait = BitrateTable.forDimensions(1080, 1920, VideoCodec.H264)
        val bpsLandscape = BitrateTable.forDimensions(1920, 1080, VideoCodec.H264)
        assertEquals(bpsLandscape, bpsPortrait)
    }

    @Test
    fun `HD returns HD bucket`() {
        val bps = BitrateTable.forDimensions(1280, 720, VideoCodec.H264)
        val expected = StorageEstimator.bytesPerSecondForResolution(QualityPresets.HD) * 8L
        assertEquals(expected, bps)
    }

    @Test
    fun `UHD returns UHD bucket`() {
        val bps = BitrateTable.forDimensions(3840, 2160, VideoCodec.H264)
        val expected = StorageEstimator.bytesPerSecondForResolution(QualityPresets.UHD) * 8L
        assertEquals(expected, bps)
    }

    @Test
    fun `SD returns SD bucket`() {
        val bps = BitrateTable.forDimensions(854, 480, VideoCodec.H264)
        val expected = StorageEstimator.bytesPerSecondForResolution(QualityPresets.SD) * 8L
        assertEquals(expected, bps)
    }

    @Test
    fun `non-positive dimensions throw`() {
        try {
            BitrateTable.forDimensions(0, 1080, VideoCodec.H264)
            throw AssertionError("expected IllegalArgumentException for width == 0")
        } catch (_: IllegalArgumentException) { /* expected */ }

        try {
            BitrateTable.forDimensions(1920, -1, VideoCodec.H264)
            throw AssertionError("expected IllegalArgumentException for height < 0")
        } catch (_: IllegalArgumentException) { /* expected */ }
    }
}
