package com.aritr.rova.ui.warnings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WarningSheetContentTest {

    @Test fun everyWarningHasSheetContent() {
        for (id in WarningId.entries) {
            val c = warningSheetContent(id)
            assertTrue("title non-blank for $id", c.title.isNotBlank())
            when (warningSurfaceFor(id)) {
                WarningSurface.HardBlockSheet, WarningSurface.SoftSheet, WarningSurface.AdvisorySheet ->
                    assertTrue("secondary action present for sheet-rendered $id", c.secondary != null)
                WarningSurface.TopBanner -> { /* secondary may be null */ }
            }
        }
    }

    @Test fun cameraDeniedRoutesToAppSettings() {
        val c = warningSheetContent(WarningId.CAMERA_PERMISSION_DENIED)
        assertEquals("Camera access required", c.title)
        assertEquals(ActionTarget.APP_DETAILS_SETTINGS, c.primary.target)
    }

    @Test fun exactAlarmRoutesToExactAlarmSettings() {
        assertEquals(ActionTarget.EXACT_ALARM_SETTINGS, warningSheetContent(WarningId.EXACT_ALARM_DENIED).primary.target)
    }

    @Test fun micDeniedSecondaryIsContinueWithoutAudio() {
        val c = warningSheetContent(WarningId.MICROPHONE_DENIED)
        assertTrue(c.secondary!!.label.contains("audio", ignoreCase = true) || c.secondary!!.label.contains("without", ignoreCase = true))
    }

    @Test fun storage_low_mid_rec_arm_returns_nonblank_defensive_content() {
        // Defensive — STORAGE_LOW_MID_REC is TopBanner-only; this exists to keep
        // warningSheetContent total over WarningId. The test checks that the arm
        // returns a non-blank, callable sheet content (never rendered as a sheet).
        val c = warningSheetContent(WarningId.STORAGE_LOW_MID_REC)
        assertFalse("title", c.title.isBlank())
        assertNotNull("icon", c.icon)
        assertEquals("OK", c.primary.label)
        assertNull("secondary should be null on TopBanner-only arm", c.secondary)
    }
}
