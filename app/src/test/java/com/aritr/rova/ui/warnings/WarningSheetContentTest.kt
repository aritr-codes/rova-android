package com.aritr.rova.ui.warnings

import com.aritr.rova.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WarningSheetContentTest {

    @Test fun everyWarningHasSheetContent() {
        for (id in WarningId.entries) {
            val c = warningSheetContent(id)
            assertNotEquals("title @StringRes set for $id", 0, c.title)
            when (warningSurfaceFor(id)) {
                WarningSurface.HardBlockSheet, WarningSurface.SoftSheet, WarningSurface.AdvisorySheet ->
                    assertTrue("secondary action present for sheet-rendered $id", c.secondary != null)
                WarningSurface.TopBanner -> { /* secondary may be null */ }
            }
        }
    }

    @Test fun cameraDeniedRoutesToAppSettings() {
        val c = warningSheetContent(WarningId.CAMERA_PERMISSION_DENIED)
        assertEquals(R.string.warning_camera_perm_title, c.title)
        assertEquals(ActionTarget.APP_DETAILS_SETTINGS, c.primary.target)
    }

    @Test fun exactAlarmRoutesToExactAlarmSettings() {
        assertEquals(ActionTarget.EXACT_ALARM_SETTINGS, warningSheetContent(WarningId.EXACT_ALARM_DENIED).primary.target)
    }

    @Test fun micDeniedSecondaryIsContinueWithoutAudio() {
        val c = warningSheetContent(WarningId.MICROPHONE_DENIED)
        assertEquals(R.string.warning_mic_secondary, c.secondary!!.label)
    }

    @Test fun storage_low_mid_rec_arm_returns_nonblank_defensive_content() {
        // Defensive — STORAGE_LOW_MID_REC is TopBanner-only; this exists to keep
        // warningSheetContent total over WarningId. The test checks that the arm
        // returns a non-blank, callable sheet content (never rendered as a sheet).
        val c = warningSheetContent(WarningId.STORAGE_LOW_MID_REC)
        assertNotEquals("title @StringRes set", 0, c.title)
        assertEquals("glyph wired from spec", WarningIconSpec.glyphFor(WarningId.STORAGE_LOW_MID_REC), c.glyph)
        assertEquals(R.string.warning_action_ok, c.primary.label)
        assertNull("secondary should be null on TopBanner-only arm", c.secondary)
    }

    @Test
    fun `CANT_MERGE sheet renders 3 CTAs with link-styled tertiary`() {
        val content = warningSheetContent(WarningId.CANT_MERGE)
        assertEquals(R.string.warning_cant_merge_title, content.title)
        assertEquals(R.string.warning_cant_merge_body, content.body)
        assertEquals(R.string.warning_cant_merge_primary, content.primary.label)
        assertEquals(ActionTarget.STORAGE_SETTINGS, content.primary.target)
        assertEquals(WarningActionStyle.Primary, content.primary.style)
        assertNotNull(content.secondary)
        assertEquals(R.string.warning_cant_merge_secondary, content.secondary!!.label)
        assertEquals(ActionTarget.KEEP_SEGMENTS_ONLY, content.secondary!!.target)
        assertEquals(WarningActionStyle.Secondary, content.secondary!!.style)
        assertNotNull(content.tertiary)
        assertEquals(R.string.warning_cant_merge_tertiary, content.tertiary!!.label)
        assertEquals(ActionTarget.DISCARD_RECOVERY_SESSION, content.tertiary!!.target)
        assertEquals(WarningActionStyle.Link, content.tertiary!!.style)
    }

    @Test
    fun `CANT_MERGE surface is AdvisorySheet`() {
        assertEquals(WarningSurface.AdvisorySheet, warningSurfaceFor(WarningId.CANT_MERGE))
    }
}
