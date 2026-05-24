package com.aritr.rova.ui.warnings

import org.junit.Assert.assertEquals
import org.junit.Test

class WarningSurfaceTest {

    @Test fun everyWarningHasASurface() {
        // Exhaustive — one assertion per WarningId; fails to compile if a new id is added without a mapping.
        for (id in WarningId.entries) {
            val s = warningSurfaceFor(id)
            assertEquals("surface for $id", expectedSurface(id), s)
        }
    }

    private fun expectedSurface(id: WarningId): WarningSurface = when (id) {
        WarningId.CAMERA_PERMISSION_DENIED, WarningId.EXACT_ALARM_DENIED, WarningId.STORAGE_INSUFFICIENT -> WarningSurface.HardBlockSheet
        WarningId.MICROPHONE_DENIED -> WarningSurface.SoftSheet
        WarningId.NOTIFICATIONS_DENIED, WarningId.BATTERY_OPTIMIZATION_ON, WarningId.POWER_SAVE_MODE -> WarningSurface.AdvisorySheet
        WarningId.THERMAL_SHUTDOWN, WarningId.THERMAL_EMERGENCY, WarningId.THERMAL_CRITICAL, WarningId.THERMAL_SEVERE, WarningId.THERMAL_MODERATE,
        WarningId.BATTERY_CRITICAL, WarningId.BATTERY_LOW, WarningId.CAMERA_IN_USE, WarningId.CAMERA_DISABLED,
        WarningId.STORAGE_LOW_MID_REC,
        WarningId.STORAGE_FULL_AUTOSTOPPED -> WarningSurface.TopBanner     // ← NEW arm (Phase 4 Slice 2)
    }

    @Test fun storage_low_mid_rec_resolves_to_top_banner() {
        assertEquals(WarningSurface.TopBanner, warningSurfaceFor(WarningId.STORAGE_LOW_MID_REC))
    }
}
