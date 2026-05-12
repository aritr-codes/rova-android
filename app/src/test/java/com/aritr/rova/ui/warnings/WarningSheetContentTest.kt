package com.aritr.rova.ui.warnings

import org.junit.Assert.assertEquals
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
}
