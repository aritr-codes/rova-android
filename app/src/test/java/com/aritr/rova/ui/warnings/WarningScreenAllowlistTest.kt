package com.aritr.rova.ui.warnings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WarningScreenAllowlistTest {

    @Test
    fun `WarningScreen has Record History Settings`() {
        val values = WarningScreen.values()
        assertEquals(3, values.size)
        assertTrue(values.contains(WarningScreen.Record))
        assertTrue(values.contains(WarningScreen.History))
        assertTrue(values.contains(WarningScreen.Settings))
    }

    @Test
    fun `HISTORY_WARNINGS pins exactly the 5 IDs from spec`() {
        assertEquals(
            setOf(
                WarningId.STORAGE_INSUFFICIENT,
                WarningId.STORAGE_FULL_AUTOSTOPPED,
                WarningId.THERMAL_AUTOSTOPPED,
                WarningId.CANT_MERGE,
                WarningId.NOTIFICATIONS_DENIED,
            ),
            HISTORY_WARNINGS,
        )
    }

    @Test
    fun `SETTINGS_WARNINGS pins exactly the 7 IDs from spec`() {
        assertEquals(
            setOf(
                WarningId.CAMERA_PERMISSION_DENIED,
                WarningId.EXACT_ALARM_DENIED,
                WarningId.STORAGE_INSUFFICIENT,
                WarningId.MICROPHONE_DENIED,
                WarningId.BATTERY_OPTIMIZATION_ON,
                WarningId.POWER_SAVE_MODE,
                WarningId.NOTIFICATIONS_DENIED,
            ),
            SETTINGS_WARNINGS,
        )
    }

    @Test
    fun `allowlists overlap on shared IDs`() {
        val intersection = HISTORY_WARNINGS intersect SETTINGS_WARNINGS
        assertTrue(
            "STORAGE_INSUFFICIENT must appear on both per spec §3.2",
            WarningId.STORAGE_INSUFFICIENT in intersection,
        )
        assertTrue(
            "NOTIFICATIONS_DENIED must appear on both per spec §3.2",
            WarningId.NOTIFICATIONS_DENIED in intersection,
        )
    }
}
