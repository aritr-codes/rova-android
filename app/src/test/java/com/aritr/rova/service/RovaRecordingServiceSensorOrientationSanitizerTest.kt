package com.aritr.rova.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase: render-architecture audit. Pure-JVM tests for the
 * SENSOR_ORIENTATION sanitizer used by RovaRecordingService.
 * resolveDualCameraIntrinsics.
 *
 * The sanitizer lives as a package-internal top-level fun (NOT a
 * private method of RovaRecordingService) specifically so this test
 * can call it without instantiating the service. See spec §4.1.
 */
class RovaRecordingServiceSensorOrientationSanitizerTest {

    @Test
    fun `sanitizeSensorOrientation accepts all 4 legal values unchanged`() {
        for (legal in listOf(0, 90, 180, 270)) {
            assertEquals(legal, sanitizeSensorOrientation(legal))
        }
    }

    @Test
    fun `sanitizeSensorOrientation falls back to 90 for positive non-legal values`() {
        assertEquals(SENSOR_ORIENTATION_FALLBACK, sanitizeSensorOrientation(45))
        assertEquals(SENSOR_ORIENTATION_FALLBACK, sanitizeSensorOrientation(360))
        assertEquals(SENSOR_ORIENTATION_FALLBACK, sanitizeSensorOrientation(1))
    }

    @Test
    fun `sanitizeSensorOrientation falls back to 90 for negative values`() {
        assertEquals(SENSOR_ORIENTATION_FALLBACK, sanitizeSensorOrientation(-1))
        assertEquals(SENSOR_ORIENTATION_FALLBACK, sanitizeSensorOrientation(-90))
        assertEquals(SENSOR_ORIENTATION_FALLBACK, sanitizeSensorOrientation(-360))
    }
}
