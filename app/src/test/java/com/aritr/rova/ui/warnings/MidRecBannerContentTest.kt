package com.aritr.rova.ui.warnings

import com.aritr.rova.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MidRecBannerContentTest {

    // Derived from the surface map — any future TopBanner-mapped WarningId is automatically covered.
    private val ids = WarningId.entries.filter { warningSurfaceFor(it) == WarningSurface.TopBanner }

    @Test fun every_mid_rec_id_returns_nonblank_content() {
        assertEquals("expected 12 TopBanner-mapped ids", 12, ids.size)
        for (id in ids) {
            val c = midRecBannerContent(id)
            assertEquals("glyph for $id", WarningIconSpec.glyphFor(id), c.glyph)
            assertNotEquals("title @StringRes for $id", 0, c.title)
            assertNotEquals("sub @StringRes for $id", 0, c.sub)
            assertNotEquals("cta @StringRes for $id", 0, c.cta)
        }
    }

    @Test fun thermal_shutdown_copy() {
        assertEquals(R.string.warning_banner_thermal_shutdown_title, midRecBannerContent(WarningId.THERMAL_SHUTDOWN).title)
    }

    @Test fun battery_low_copy() {
        assertEquals(R.string.warning_banner_battery_low_title, midRecBannerContent(WarningId.BATTERY_LOW).title)
    }

    @Test fun camera_in_use_copy() {
        assertEquals(R.string.warning_banner_camera_in_use_title, midRecBannerContent(WarningId.CAMERA_IN_USE).title)
    }

    @Test fun storage_low_mid_rec_copy() {
        val c = midRecBannerContent(WarningId.STORAGE_LOW_MID_REC)
        assertEquals(R.string.warning_banner_storage_low_title, c.title)
        assertEquals(R.string.warning_banner_storage_low_sub, c.sub)
    }

    @Test fun thermal_emergency_copy() {
        val c = midRecBannerContent(WarningId.THERMAL_EMERGENCY)
        assertEquals(R.string.warning_banner_thermal_emergency_title, c.title)
        assertEquals(R.string.warning_banner_thermal_emergency_sub, c.sub)
    }

    @Test fun thermal_critical_copy() {
        val c = midRecBannerContent(WarningId.THERMAL_CRITICAL)
        assertEquals(R.string.warning_banner_thermal_critical_title, c.title)
        assertEquals(R.string.warning_banner_thermal_critical_sub, c.sub)
    }

    @Test fun thermal_severe_copy() {
        val c = midRecBannerContent(WarningId.THERMAL_SEVERE)
        assertEquals(R.string.warning_banner_thermal_severe_title, c.title)
        assertEquals(R.string.warning_banner_thermal_severe_sub, c.sub)
    }

    @Test fun thermal_moderate_copy() {
        val c = midRecBannerContent(WarningId.THERMAL_MODERATE)
        assertEquals(R.string.warning_banner_thermal_moderate_title, c.title)
        assertEquals(R.string.warning_banner_thermal_moderate_sub, c.sub)
    }

    @Test fun battery_critical_copy() {
        val c = midRecBannerContent(WarningId.BATTERY_CRITICAL)
        assertEquals(R.string.warning_banner_battery_critical_title, c.title)
        assertEquals(R.string.warning_banner_battery_critical_sub, c.sub)
    }

    @Test fun camera_disabled_copy() {
        val c = midRecBannerContent(WarningId.CAMERA_DISABLED)
        assertEquals(R.string.warning_banner_camera_disabled_title, c.title)
        assertEquals(R.string.warning_banner_camera_disabled_sub, c.sub)
    }

    @Test fun thermal_autostopped_copy() {
        val c = midRecBannerContent(WarningId.THERMAL_AUTOSTOPPED)
        assertEquals(R.string.warning_banner_thermal_autostopped_title, c.title)
        assertEquals(R.string.warning_banner_thermal_autostopped_sub, c.sub)
        assertEquals(R.string.warning_banner_thermal_autostopped_cta, c.cta)
        assertEquals(2, c.overflow.size)
        assertEquals(ActionTarget.DISMISS_AUTOSTOP_ECHO, c.overflow[0].target)
        assertEquals(ActionTarget.REVIEW_SESSION, c.overflow[1].target)
    }

    @Test(expected = IllegalStateException::class)
    fun `CANT_MERGE throws — never renders mid-rec`() {
        midRecBannerContent(WarningId.CANT_MERGE)
    }
}
