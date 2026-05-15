package com.aritr.rova.service.dualrecord

import org.junit.Test

/**
 * Phase 6.1a — cross-field composability (encoder-config builder)
 * validation for [DualVideoRecorderConfig], targeting the top-level
 * [ensureEncoderConfigComposable] seam.
 *
 * D-deviation (Task 6): the test factory uses the Int-based primary
 * constructor. See [DualVideoRecorderConfigTest]'s KDoc for the
 * rationale (Size-stub under `isReturnDefaultValues = true`).
 */
class EncoderConfigBuilderTest {

    private fun cfg(
        cameraInputWidth: Int = 1920,
        cameraInputHeight: Int = 1080,
        portraitOutputWidth: Int = 1080,
        portraitOutputHeight: Int = 1920,
        landscapeOutputWidth: Int = 1920,
        landscapeOutputHeight: Int = 1080,
        portraitBitrate: Long = 16_000_000L,
    ) = DualVideoRecorderConfig(
        cameraInputWidth = cameraInputWidth,
        cameraInputHeight = cameraInputHeight,
        portraitOutputWidth = portraitOutputWidth,
        portraitOutputHeight = portraitOutputHeight,
        landscapeOutputWidth = landscapeOutputWidth,
        landscapeOutputHeight = landscapeOutputHeight,
        portraitBitrate = portraitBitrate,
        landscapeBitrate = 16_000_000L,
        videoCodec = VideoCodec.H264,
        audioBitrate = 128_000,
        audioSampleRate = 48_000,
        lensFacing = LensFacing.BACK,
        displayRotation = 0,
        fps = 30,
    )

    @Test
    fun `composable when both outputs fit camera input`() {
        cfg()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `landscape output exceeds camera input width — throws`() {
        cfg(
            landscapeOutputWidth = 3840,
            landscapeOutputHeight = 2160,
            cameraInputWidth = 1920,
            cameraInputHeight = 1080,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `portrait output exceeds camera input — throws`() {
        cfg(
            portraitOutputWidth = 2160,
            portraitOutputHeight = 3840,
            cameraInputWidth = 1920,
            cameraInputHeight = 1080,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unreasonably low FHD bitrate — throws`() {
        cfg(portraitBitrate = 500_000L)
    }
}
