package com.aritr.rova.service.singlerecord

import com.aritr.rova.data.QualityPresets

/**
 * Pure, JVM-testable representation of the single-mode quality choice.
 * D-deviation (house pattern): the CameraX `androidx.camera.video.Quality`
 * type is constructed only at the recorder edge ([SingleVideoRecorder]); this
 * enum carries the decision so the resolution→quality map is unit-testable
 * under `isReturnDefaultValues = true` without touching the camera AAR.
 *
 * Verbatim lift of the `when (resolutionStr)` map formerly inline in
 * `RovaRecordingService.setupSingleCamera` (the strict-match-then-FHD-fallback
 * contract: non-canonical labels fall through to FHD; we deliberately do NOT
 * route through QualityPresets.canonicalize).
 */
enum class SingleQuality { UHD, FHD, HD, SD }

object SingleQualitySelector {
    fun forResolution(resolutionStr: String): SingleQuality = when (resolutionStr) {
        QualityPresets.UHD -> SingleQuality.UHD
        QualityPresets.FHD -> SingleQuality.FHD
        QualityPresets.HD -> SingleQuality.HD
        QualityPresets.SD -> SingleQuality.SD
        else -> SingleQuality.FHD
    }
}
