package com.aritr.rova.ui.warnings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class MidRecBannerContentTest {

    // Derived from the surface map — any future TopBanner-mapped WarningId is automatically covered.
    private val ids = WarningId.entries.filter { warningSurfaceFor(it) == WarningSurface.TopBanner }

    @Test fun every_mid_rec_id_returns_nonblank_content() {
        assertEquals("expected 12 TopBanner-mapped ids", 12, ids.size)
        for (id in ids) {
            val c = midRecBannerContent(id)
            assertNotNull("icon for $id", c.icon)
            assertFalse("title for $id", c.title.isBlank())
            assertFalse("sub for $id", c.sub.isBlank())
            assertFalse("cta for $id", c.cta.isBlank())
        }
    }

    @Test fun thermal_shutdown_copy() {
        assertEquals("Device overheating — stopping", midRecBannerContent(WarningId.THERMAL_SHUTDOWN).title)
    }

    @Test fun battery_low_copy() {
        assertEquals("Battery low", midRecBannerContent(WarningId.BATTERY_LOW).title)
    }

    @Test fun camera_in_use_copy() {
        assertEquals("Camera in use", midRecBannerContent(WarningId.CAMERA_IN_USE).title)
    }

    @Test fun storage_low_mid_rec_copy() {
        val c = midRecBannerContent(WarningId.STORAGE_LOW_MID_REC)
        assertEquals("Storage running low", c.title)
        assertEquals("Free space on this device.", c.sub)
    }

    @Test fun thermal_emergency_copy() {
        val c = midRecBannerContent(WarningId.THERMAL_EMERGENCY)
        assertEquals("Device critically hot", c.title)
        assertEquals("Stop now to let it cool.", c.sub)
    }

    @Test fun thermal_critical_copy() {
        val c = midRecBannerContent(WarningId.THERMAL_CRITICAL)
        assertEquals("Device very hot", c.title)
        assertEquals("Recording may auto-stop soon.", c.sub)
    }

    @Test fun thermal_severe_copy() {
        val c = midRecBannerContent(WarningId.THERMAL_SEVERE)
        assertEquals("Device hot", c.title)
        assertEquals("Quality may drop.", c.sub)
    }

    @Test fun thermal_moderate_copy() {
        val c = midRecBannerContent(WarningId.THERMAL_MODERATE)
        assertEquals("Device warming up", c.title)
        assertEquals("Watch the temperature.", c.sub)
    }

    @Test fun battery_critical_copy() {
        val c = midRecBannerContent(WarningId.BATTERY_CRITICAL)
        assertEquals("Battery critical", c.title)
        assertEquals("Recording may stop soon.", c.sub)
    }

    @Test fun camera_disabled_copy() {
        val c = midRecBannerContent(WarningId.CAMERA_DISABLED)
        assertEquals("Camera disabled", c.title)
        assertEquals("Disabled by device policy.", c.sub)
    }

    @Test fun thermal_autostopped_copy() {
        val c = midRecBannerContent(WarningId.THERMAL_AUTOSTOPPED)
        assertEquals("Recording stopped", c.title)
        assertEquals("Device overheated.", c.sub)
        assertEquals("Tips to cool down", c.cta)
        assertEquals(2, c.overflow.size)
        assertEquals(ActionTarget.DISMISS_AUTOSTOP_ECHO, c.overflow[0].target)
        assertEquals(ActionTarget.REVIEW_SESSION, c.overflow[1].target)
    }

    @Test(expected = IllegalStateException::class)
    fun `CANT_MERGE throws — never renders mid-rec`() {
        midRecBannerContent(WarningId.CANT_MERGE)
    }
}
