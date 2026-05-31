package com.aritr.rova.service

/**
 * Pure decision rule for releasing the idle camera preview on an
 * app-background event.
 *
 * Mirrors the in-service guard in [RovaRecordingService.stopCameraPreview]:
 * a live recording is FGS-owned and must never be torn down by a background
 * event; only the idle preview is released. Extracted as a pure function so
 * the rule is unit-testable under the project's JVM-only test policy
 * (isReturnDefaultValues = true), where Android framework calls are stubbed.
 *
 * ADR-0021 — Camera warm across in-app navigation.
 */
fun shouldReleaseCameraOnBackground(isRecording: Boolean): Boolean = !isRecording
