package com.aritr.rova.service.dualrecord

import org.junit.Test

/**
 * Phase 6.1a — per-field domain validation for [DualVideoRecorderConfig].
 *
 * D-deviation (Task 6): the test factory constructs the config via the
 * Int-based primary constructor rather than the plan's `Size`-shaped
 * call. The project runs `testOptions.unitTests.isReturnDefaultValues
 * = true`, under which `android.util.Size`'s `getWidth()`/`getHeight()`
 * return `0` — so calling the `Size`-based factory would always make
 * `init {}` see 0×0 dimensions and trip the positive-size guard before
 * the test even runs. Test intent and assertions are identical to the
 * plan's spec. See [DualVideoRecorderConfig]'s KDoc for the precedent
 * (Task 5, `54fc50f`).
 */
class DualVideoRecorderConfigTest {

    private fun validConfig(
        cameraInputWidth: Int = 1920,
        cameraInputHeight: Int = 1080,
        portraitOutputWidth: Int = 1080,
        portraitOutputHeight: Int = 1920,
        landscapeOutputWidth: Int = 1920,
        landscapeOutputHeight: Int = 1080,
        portraitBitrate: Long = 16_000_000L,
        landscapeBitrate: Long = 16_000_000L,
        audioBitrate: Int = 128_000,
        audioSampleRate: Int = 48_000,
        fps: Int = 30,
        displayRotation: Int = 0,
    ) = DualVideoRecorderConfig(
        cameraInputWidth = cameraInputWidth,
        cameraInputHeight = cameraInputHeight,
        portraitOutputWidth = portraitOutputWidth,
        portraitOutputHeight = portraitOutputHeight,
        landscapeOutputWidth = landscapeOutputWidth,
        landscapeOutputHeight = landscapeOutputHeight,
        portraitBitrate = portraitBitrate,
        landscapeBitrate = landscapeBitrate,
        videoCodec = VideoCodec.H264,
        audioBitrate = audioBitrate,
        audioSampleRate = audioSampleRate,
        lensFacing = LensFacing.BACK,
        displayRotation = displayRotation,
        fps = fps,
    )

    @Test
    fun `valid config is accepted`() {
        validConfig()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `non-positive portrait bitrate throws`() {
        validConfig(portraitBitrate = 0L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `non-positive landscape bitrate throws`() {
        validConfig(landscapeBitrate = -1L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `non-positive audio bitrate throws`() {
        validConfig(audioBitrate = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unsupported audio sample rate throws`() {
        validConfig(audioSampleRate = 12_345)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `fps outside 15-60 throws`() {
        validConfig(fps = 5)
    }
}
