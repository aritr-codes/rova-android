package com.aritr.rova.ui.warnings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the [WarningId] declaration order (== precedence) and each value's
 * [WarningTier]. The order is a behavioral contract — NEW_UI_BACKEND_REPLAN
 * §"Phase 4" banner-precedence table, owner-signed 2026-05-11 — consumed
 * by [WarningPrecedence.resolve]. A reorder, insertion, deletion, or tier
 * change fails here.
 */
class WarningIdOrderTest {

    @Test fun `declaration order matches the locked precedence table`() {
        assertEquals(
            listOf(
                "CAMERA_PERMISSION_DENIED",  // #1
                "EXACT_ALARM_DENIED",        // #2
                "STORAGE_INSUFFICIENT",      // #3
                "THERMAL_SHUTDOWN",          // #4
                "THERMAL_EMERGENCY",         // #5
                "THERMAL_CRITICAL",          // #6
                "BATTERY_CRITICAL",          // #7
                "CAMERA_IN_USE",             // #8
                "CAMERA_DISABLED",           // #9
                "BATTERY_LOW",               // #10
                "STORAGE_LOW_MID_REC",       // #11 — (R2)
                "STORAGE_FULL_AUTOSTOPPED",  // #12 — NEW (Phase 4 Slice 2)
                "THERMAL_SEVERE",            // #13
                "MICROPHONE_DENIED",         // #14
                "BATTERY_OPTIMIZATION_ON",   // #15
                "POWER_SAVE_MODE",           // #16
                "THERMAL_MODERATE",          // #17
                "NOTIFICATIONS_DENIED"       // #18
            ),
            WarningId.values().map { it.name }
        )
    }

    @Test fun `tiers are as specified`() {
        assertEquals(WarningTier.HARD_BLOCK, WarningId.CAMERA_PERMISSION_DENIED.tier)
        assertEquals(WarningTier.HARD_BLOCK, WarningId.EXACT_ALARM_DENIED.tier)
        assertEquals(WarningTier.HARD_BLOCK, WarningId.STORAGE_INSUFFICIENT.tier)
        assertEquals(WarningTier.CRITICAL, WarningId.THERMAL_SHUTDOWN.tier)
        assertEquals(WarningTier.CRITICAL, WarningId.THERMAL_EMERGENCY.tier)
        assertEquals(WarningTier.CRITICAL, WarningId.THERMAL_CRITICAL.tier)
        assertEquals(WarningTier.CRITICAL, WarningId.BATTERY_CRITICAL.tier)
        assertEquals(WarningTier.CRITICAL, WarningId.CAMERA_IN_USE.tier)
        assertEquals(WarningTier.CRITICAL, WarningId.CAMERA_DISABLED.tier)
        assertEquals(WarningTier.ADVISORY, WarningId.BATTERY_LOW.tier)
        assertEquals(WarningTier.ADVISORY, WarningId.STORAGE_LOW_MID_REC.tier)
        assertEquals(WarningTier.ADVISORY, WarningId.STORAGE_FULL_AUTOSTOPPED.tier)
        assertEquals(WarningTier.ADVISORY, WarningId.THERMAL_SEVERE.tier)
        assertEquals(WarningTier.ADVISORY, WarningId.MICROPHONE_DENIED.tier)
        assertEquals(WarningTier.ADVISORY, WarningId.BATTERY_OPTIMIZATION_ON.tier)
        assertEquals(WarningTier.ADVISORY, WarningId.POWER_SAVE_MODE.tier)
        assertEquals(WarningTier.ADVISORY, WarningId.THERMAL_MODERATE.tier)
        assertEquals(WarningTier.ADVISORY, WarningId.NOTIFICATIONS_DENIED.tier)
    }

    @Test fun `only camera-permission and storage gate Start`() {
        assertEquals(
            listOf("CAMERA_PERMISSION_DENIED", "STORAGE_INSUFFICIENT"),
            WarningId.values().filter { it.gatesStart }.map { it.name }
        )
    }
}
