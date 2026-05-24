package com.aritr.rova.service

import com.aritr.rova.ui.signals.ThermalStatus

/**
 * Phase 4 Slice 3 (ADR-0016) — pure helper for Layer 4 of
 * [RovaRecordingService.checkSegmentGates]. Returns true when the gate
 * should terminate with [com.aritr.rova.data.StopReason.THERMAL].
 *
 * Threshold is "CRITICAL or above" — at CRITICAL the OS begins aggressive
 * throttling and encoder output starts degrading. Stopping one segment
 * earlier preserves footage integrity. At SEVERE the active-HUD banner
 * already nudges the user to manual-stop; the gate only fires when the
 * user did not react and the device kept climbing.
 *
 * The Layer-4 call site reads `app.thermalStatusSignal.state.value` (push-
 * listener fed) and passes it here. Extraction mirrors the existing
 * `RovaRecordingService.accumulatedSessionBytes` → `StorageEstimator`
 * pattern so the gate stays pure-JVM testable (ADR-0007).
 */
internal object SegmentGateThermal {
    fun shouldTerminate(status: ThermalStatus): Boolean =
        status.ordinal >= ThermalStatus.CRITICAL.ordinal
}
