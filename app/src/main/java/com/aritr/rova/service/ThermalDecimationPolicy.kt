package com.aritr.rova.service

import com.aritr.rova.ui.signals.ThermalStatus

/**
 * ADR-0035 — thermal-adaptive encode decimation. Extends ADR-0016's thermal
 * ladder: at SEVERE (one rung below the CRITICAL autostop) DualShot encodes
 * only every 2nd frame, halving encoder duty so the device climbs to CRITICAL
 * slower — sessions last longer before the hard stop. Preview is never
 * decimated (see EglRouter). Mirrors [SegmentGateThermal]: reads the
 * already-hysteresis'd `thermalStatusSignal.state.value` (ADR-0019), so it
 * needs no hysteresis of its own. Pure — JVM-testable (ADR-0007).
 */
internal object ThermalDecimationPolicy {
    /** 1 = encode every frame; 2 = encode every 2nd (~half fps). */
    fun decimationFactor(status: ThermalStatus): Int =
        if (status.ordinal >= ThermalStatus.SEVERE.ordinal) 2 else 1

    /** True when this frame should be fed to the encoders. factor<=1 => all. */
    fun shouldSubmit(frameCounter: Int, factor: Int): Boolean =
        factor <= 1 || frameCounter % factor == 0
}
