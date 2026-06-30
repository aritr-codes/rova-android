package com.aritr.rova.service.singlerecord

/**
 * Immutable construction config for [SingleVideoRecorder]. Cheap to build.
 *
 * @param resolutionStr canonical QualityPresets label driving the QualitySelector.
 * @param buildTimeTargetRotation the Identity-normalised display rotation
 *   (`computeTargetRotation(displayRotation)`) applied to the VideoCapture use
 *   case at build (ADR-0029 §3 boundary). Per-segment rotation is re-applied at
 *   [SingleVideoRecorder.start].
 */
data class SingleVideoRecorderConfig(
    val resolutionStr: String,
    val buildTimeTargetRotation: Int,
)
