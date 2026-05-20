package com.aritr.rova.service.dualrecord

import android.util.Size

/**
 * Phase 6.1a — capture + encoder + audio + lens + rotation config consumed
 * by `DualVideoRecorder`. Validated in `init`:
 *
 *  - Per-field domain checks (positive sizes, positive bitrates, FPS 15..60,
 *    audio sample rate one of 22_050/44_100/48_000, displayRotation 0..3).
 *  - Cross-field composability: see [ensureEncoderConfigComposable].
 *
 * Layout (orientation-agnostic): the camera input is sized to satisfy the
 * larger of the two output orientations.
 *
 * ## D-deviation (Phase 6.1a Task 6) — Size-stub workaround
 *
 * The data class's *primary constructor* takes the three frame dimensions
 * as primitive `Int` pairs (`cameraInputWidth`/`cameraInputHeight`, etc.)
 * rather than `android.util.Size`. The original-shape `Size`-based call
 * site is preserved via [Companion.invoke]: production callers can still
 * write `DualVideoRecorderConfig(cameraInputSize = Size(...), ...)` — that
 * resolves to the companion's `operator fun invoke`, which extracts the
 * Ints from each `Size` and delegates to the primary constructor.
 *
 * The `Size`-typed properties [cameraInputSize] / [portraitOutputSize] /
 * [landscapeOutputSize] are exposed as derived `get()` accessors so the
 * public API surface (the way callers READ the dimensions) is identical
 * to the plan's documented shape.
 *
 * Rationale: the project sets `testOptions.unitTests.isReturnDefaultValues
 * = true`. Under that flag, `android.util.Size.getWidth()/getHeight()`
 * return 0 from JVM tests (the `android.jar` stub is method-stubbed —
 * constructors store nothing). With a `Size`-based primary constructor,
 * the `init {}` validators would always see `0` for every dimension and
 * trip `require(... > 0)`. The Int-based primary constructor sidesteps
 * the stub entirely and is the seam this file's unit tests target. This
 * mirrors the precedent set in Task 5 (`BitrateTable.forDimensions`,
 * `54fc50f`).
 */
data class DualVideoRecorderConfig(
    val cameraInputWidth: Int,
    val cameraInputHeight: Int,
    val portraitOutputWidth: Int,
    val portraitOutputHeight: Int,
    val landscapeOutputWidth: Int,
    val landscapeOutputHeight: Int,
    val portraitBitrate: Long,
    val landscapeBitrate: Long,
    val videoCodec: VideoCodec,
    val audioBitrate: Int,
    val audioSampleRate: Int,
    val lensFacing: LensFacing,
    /** `Surface.ROTATION_0..ROTATION_270` (integer 0..3). */
    val displayRotation: Int,
    /** Encoder frame rate target; must be 15..60. */
    val fps: Int,
    /**
     * `CameraCharacteristics.SENSOR_ORIENTATION` for the active lens.
     * Must be one of {0, 90, 180, 270}. Threaded into EglRouter for
     * V2 first-principles UV transform; ignored by legacy path.
     * Default 90: SAFE ASSUMPTION for portrait-natural phones (typical
     * rear-camera mount). Caller should pass the actual queried value.
     */
    val sensorOrientation: Int = 90,
    /**
     * Hybrid render-path flag. When true, EglRouter.addTarget uses
     * buildUvTransformV2; when false (default), uses legacy
     * buildCropMatrix. Read from SharedPreferences in DEBUG builds
     * only; forced false in release per BuildConfig.DEBUG short-circuit.
     */
    val useFirstPrinciplesRender: Boolean = false,
    /**
     * Debug snapshot flag. When true, EglRouter.renderFrame writes
     * per-frame DualShotMatrixDebugInfo into a per-side map for
     * sub-project 2 consumption. Default false — zero overhead.
     */
    val enableMatrixSnapshots: Boolean = false,
) {
    /** Derived view of the camera input dimensions as an [android.util.Size]. */
    val cameraInputSize: Size get() = Size(cameraInputWidth, cameraInputHeight)

    /** Derived view of the portrait output dimensions as an [android.util.Size]. */
    val portraitOutputSize: Size get() = Size(portraitOutputWidth, portraitOutputHeight)

    /** Derived view of the landscape output dimensions as an [android.util.Size]. */
    val landscapeOutputSize: Size get() = Size(landscapeOutputWidth, landscapeOutputHeight)

    init {
        require(cameraInputWidth > 0 && cameraInputHeight > 0) {
            "cameraInputSize must be positive, was ${cameraInputWidth}x${cameraInputHeight}"
        }
        require(portraitOutputWidth > 0 && portraitOutputHeight > 0) {
            "portraitOutputSize must be positive, was ${portraitOutputWidth}x${portraitOutputHeight}"
        }
        require(landscapeOutputWidth > 0 && landscapeOutputHeight > 0) {
            "landscapeOutputSize must be positive, was ${landscapeOutputWidth}x${landscapeOutputHeight}"
        }
        require(portraitBitrate > 0L) {
            "portraitBitrate must be positive, was $portraitBitrate"
        }
        require(landscapeBitrate > 0L) {
            "landscapeBitrate must be positive, was $landscapeBitrate"
        }
        require(audioBitrate > 0) {
            "audioBitrate must be positive, was $audioBitrate"
        }
        require(audioSampleRate in SUPPORTED_AUDIO_SAMPLE_RATES) {
            "audioSampleRate must be one of $SUPPORTED_AUDIO_SAMPLE_RATES, was $audioSampleRate"
        }
        require(displayRotation in 0..3) {
            "displayRotation must be in 0..3 (Surface.ROTATION_*), was $displayRotation"
        }
        require(fps in 15..60) {
            "fps must be in 15..60, was $fps"
        }
        require(sensorOrientation in setOf(0, 90, 180, 270)) {
            "sensorOrientation must be 0/90/180/270 " +
                "(CameraCharacteristics.SENSOR_ORIENTATION), was $sensorOrientation"
        }
        ensureEncoderConfigComposable(this)
    }

    companion object {
        val SUPPORTED_AUDIO_SAMPLE_RATES = setOf(22_050, 44_100, 48_000)

        /**
         * `Size`-shaped factory matching the plan's documented public call
         * shape. Delegates to the `Int`-based primary constructor.
         *
         * Production callers can write
         * `DualVideoRecorderConfig(cameraInputSize = Size(1920, 1080), ...)`
         * unchanged; the call resolves here. Pure-JVM unit tests should use
         * the primary constructor directly (Ints) to avoid the
         * `android.util.Size` stub.
         */
        operator fun invoke(
            cameraInputSize: Size,
            portraitOutputSize: Size,
            landscapeOutputSize: Size,
            portraitBitrate: Long,
            landscapeBitrate: Long,
            videoCodec: VideoCodec,
            audioBitrate: Int,
            audioSampleRate: Int,
            lensFacing: LensFacing,
            displayRotation: Int,
            fps: Int,
            sensorOrientation: Int = 90,
            useFirstPrinciplesRender: Boolean = false,
            enableMatrixSnapshots: Boolean = false,
        ): DualVideoRecorderConfig = DualVideoRecorderConfig(
            cameraInputWidth = cameraInputSize.width,
            cameraInputHeight = cameraInputSize.height,
            portraitOutputWidth = portraitOutputSize.width,
            portraitOutputHeight = portraitOutputSize.height,
            landscapeOutputWidth = landscapeOutputSize.width,
            landscapeOutputHeight = landscapeOutputSize.height,
            portraitBitrate = portraitBitrate,
            landscapeBitrate = landscapeBitrate,
            videoCodec = videoCodec,
            audioBitrate = audioBitrate,
            audioSampleRate = audioSampleRate,
            lensFacing = lensFacing,
            displayRotation = displayRotation,
            fps = fps,
            sensorOrientation = sensorOrientation,
            useFirstPrinciplesRender = useFirstPrinciplesRender,
            enableMatrixSnapshots = enableMatrixSnapshots,
        )
    }
}

/**
 * Phase 6.1a — cross-field composability check for [DualVideoRecorderConfig].
 *
 *  - Each output side's long axis ≤ cameraInputSize's long axis.
 *  - Each output side's short axis ≤ cameraInputSize's short axis.
 *  - Bitrate must clear the AVC profile floor for the output resolution
 *    (heuristic: ≥ 1 Mbps for ≥ FHD pixel count, else ≥ 250 kbps).
 *
 * Extracted as a top-level `internal fun` so unit tests can target the
 * composability seam independently — see `EncoderConfigBuilderTest`.
 *
 * Reads the `Int`-typed dimension fields directly (not `Size.width` /
 * `Size.height`) so it works under the project's pure-JVM stub config
 * (`testOptions.unitTests.isReturnDefaultValues = true`); see the
 * D-deviation note on [DualVideoRecorderConfig].
 */
internal fun ensureEncoderConfigComposable(config: DualVideoRecorderConfig) {
    val srcLong = maxOf(config.cameraInputWidth, config.cameraInputHeight)
    val srcShort = minOf(config.cameraInputWidth, config.cameraInputHeight)

    fun checkFits(side: String, outW: Int, outH: Int) {
        val outLong = maxOf(outW, outH)
        val outShort = minOf(outW, outH)
        require(outLong <= srcLong && outShort <= srcShort) {
            "$side output ${outW}x${outH} exceeds cameraInputSize " +
                "${config.cameraInputWidth}x${config.cameraInputHeight}"
        }
    }
    checkFits("portrait", config.portraitOutputWidth, config.portraitOutputHeight)
    checkFits("landscape", config.landscapeOutputWidth, config.landscapeOutputHeight)

    fun checkBitrateFloor(side: String, outW: Int, outH: Int, bps: Long) {
        val pixels = outW.toLong() * outH.toLong()
        val floor: Long = if (pixels >= 1920L * 1080L) 1_000_000L else 250_000L
        require(bps >= floor) {
            "$side bitrate $bps bps is below the AVC profile floor $floor bps for " +
                "${outW}x${outH}"
        }
    }
    checkBitrateFloor(
        "portrait", config.portraitOutputWidth, config.portraitOutputHeight, config.portraitBitrate
    )
    checkBitrateFloor(
        "landscape", config.landscapeOutputWidth, config.landscapeOutputHeight, config.landscapeBitrate
    )
}
