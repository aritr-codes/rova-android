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

    @Test
    fun `CANT_MERGE sheet renders 3 CTAs with link-styled tertiary`() {
        val content = warningSheetContent(WarningId.CANT_MERGE)
        assertEquals("Can't merge yet", content.title)
        assertEquals("Free space and retry, or keep the raw segments for later.", content.body)
        assertEquals("Free space & retry", content.primary.label)
        assertEquals(ActionTarget.STORAGE_SETTINGS, content.primary.target)
        assertNotNull(content.secondary)
        assertEquals("Save segments only", content.secondary!!.label)
        assertEquals(ActionTarget.KEEP_SEGMENTS_ONLY, content.secondary!!.target)
        assertNotNull(content.tertiary)
        assertEquals("Discard session", content.tertiary!!.label)
        assertEquals(ActionTarget.DISCARD_RECOVERY_SESSION, content.tertiary!!.target)
        assertEquals(WarningActionStyle.Link, content.tertiary!!.style)
    }

    @Test
    fun `CANT_MERGE surface is AdvisorySheet`() {
        assertEquals(WarningSurface.AdvisorySheet, warningSurfaceFor(WarningId.CANT_MERGE))
    }
}
