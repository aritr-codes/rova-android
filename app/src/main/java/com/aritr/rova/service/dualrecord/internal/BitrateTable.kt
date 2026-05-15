package com.aritr.rova.service.dualrecord.internal

import android.util.Size
import com.aritr.rova.data.QualityPresets
import com.aritr.rova.data.StorageEstimator
import com.aritr.rova.service.dualrecord.VideoCodec

/**
 * Phase 6.1a — output resolution → encoder bitrate (bps).
 *
 * The table is anchored to `StorageEstimator.bytesPerSecondForResolution`
 * (BYTES/sec) × 8 to give bits/sec. Keeps the per-segment storage gate's
 * estimate consistent with the actual encoder rate (no 8× unit drift —
 * cf. the B-fix-3 incident on the per-segment gate).
 *
 * Lookup is orientation-agnostic: a Portrait `Size(1080, 1920)` resolves
 * to the same FHD bucket as a Landscape `Size(1920, 1080)`. The pixel
 * count (w*h) determines the bucket.
 *
 * D-deviation (Phase 6.1a Task 5): the actual lookup is implemented in
 * [forDimensions] taking primitive Ints so pure-JVM tests can exercise
 * it without triggering `android.util.Size`'s stubbed-instance methods
 * (the project runs `testOptions.unitTests.isReturnDefaultValues = true`,
 * which makes `size.width`/`size.height` return 0 on the android.jar
 * stub). [forSize] remains the production entry point and is a
 * one-line delegate.
 */
internal object BitrateTable {

    fun forSize(size: Size, codec: VideoCodec): Long =
        forDimensions(size.width, size.height, codec)

    fun forDimensions(widthPx: Int, heightPx: Int, codec: VideoCodec): Long {
        require(widthPx > 0 && heightPx > 0) {
            "size must be positive, was ${widthPx}x${heightPx}"
        }
        // codec is reserved for future HEVC tuning; H264 uses StorageEstimator buckets as-is.
        @Suppress("UNUSED_VARIABLE")
        val c = codec
        val preset = pixelsToPreset(widthPx.toLong() * heightPx.toLong())
        return StorageEstimator.bytesPerSecondForResolution(preset) * 8L
    }

    private fun pixelsToPreset(pixels: Long): String = when {
        pixels >= 3840L * 2160L -> QualityPresets.UHD
        pixels >= 1920L * 1080L -> QualityPresets.FHD
        pixels >= 1280L * 720L -> QualityPresets.HD
        else -> QualityPresets.SD
    }
}
