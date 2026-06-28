package com.aritr.rova.service.dualrecord.internal

import com.aritr.rova.data.QualityPresets

/**
 * DualShot per-side output sizing — realizes the deferred 6.1b v1 TODO
 * ("FHD-locked for v1; 6.1c may lookup BitrateTable per resolution").
 *
 * Maps the user's selected quality preset to per-side encoder dimensions,
 * capping 4K → FHD (true per-side 4K = ~1620x2880 x2 streams, unusable on
 * target SoCs — see profiling report). Bitrate is NOT decided here; the
 * caller derives it from these sizes via [BitrateTable.forSize], keeping
 * the encoder rate anchored to the storage gate's per-resolution estimate.
 *
 * All dims are exact 9:16 (portrait) / 16:9 (landscape), preserving the
 * ADR-0009 crop aspect (27/64 of a 4:3 source = exactly 9:16). Output size
 * is free of the crop geometry: the GL fan-out scales the fixed crop into
 * the encoder surface (viewports are aspect-derived, no hard 1080 assumption).
 *
 * D-deviation: [forDimensions] is the primitive-Int entry so pure-JVM tests
 * run under `testOptions.unitTests.isReturnDefaultValues = true` (where
 * `android.util.Size.width/height` return 0 on the android.jar stub).
 * [forPreset] is the production edge and wraps the dims in `android.util.Size`.
 */
internal data class DualSideSizes(
    val portraitW: Int,
    val portraitH: Int,
    val landscapeW: Int,
    val landscapeH: Int,
    val effectivePreset: String, // SD | HD | FHD — never "4K" (capped)
)

internal data class DualSidePlan(
    val portraitSize: android.util.Size,
    val landscapeSize: android.util.Size,
    val effectivePreset: String,
)

internal object DualShotPresetResolver {

    fun forDimensions(rawPreset: String?): DualSideSizes {
        // Unknown/null → FHD (canonicalizeOrDefault default). Then cap 4K → FHD.
        val canonical = QualityPresets.canonicalizeOrDefault(rawPreset)
        val effective = if (canonical == QualityPresets.UHD) QualityPresets.FHD else canonical
        return when (effective) {
            QualityPresets.SD -> DualSideSizes(540, 960, 960, 540, QualityPresets.SD)
            QualityPresets.HD -> DualSideSizes(720, 1280, 1280, 720, QualityPresets.HD)
            else -> DualSideSizes(1080, 1920, 1920, 1080, QualityPresets.FHD)
        }
    }

    fun forPreset(rawPreset: String?): DualSidePlan {
        val d = forDimensions(rawPreset)
        return DualSidePlan(
            portraitSize = android.util.Size(d.portraitW, d.portraitH),
            landscapeSize = android.util.Size(d.landscapeW, d.landscapeH),
            effectivePreset = d.effectivePreset,
        )
    }
}
