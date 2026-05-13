package com.aritr.rova.ui.warnings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class MidRecBannerContentTest {

    private val ids = listOf(
        WarningId.THERMAL_SHUTDOWN,
        WarningId.THERMAL_EMERGENCY,
        WarningId.THERMAL_CRITICAL,
        WarningId.THERMAL_SEVERE,
        WarningId.THERMAL_MODERATE,
        WarningId.BATTERY_CRITICAL,
        WarningId.BATTERY_LOW,
        WarningId.CAMERA_IN_USE,
        WarningId.CAMERA_DISABLED,
        WarningId.STORAGE_LOW_MID_REC,
    )

    @Test fun every_mid_rec_id_returns_nonblank_content() {
        for (id in ids) {
            val c = midRecBannerContent(id)
            assertNotNull("icon for $id", c.icon)
            assertFalse("title for $id", c.title.isBlank())
            assertFalse("sub for $id", c.sub.isBlank())
            assertEquals("cta for $id", "Stop", c.cta)
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
}
