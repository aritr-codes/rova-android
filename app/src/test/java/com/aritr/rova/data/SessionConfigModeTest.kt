package com.aritr.rova.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 6 — verifies the [SessionConfig.mode] field default, serialization,
 * and round-trip behavior. See spec §2 + §4.
 */
class SessionConfigModeTest {

    @Test
    fun defaultMode_isPortrait() {
        val config = SessionConfig(
            durationSeconds = 10,
            intervalMinutes = 1,
            resolution = "HD",
            loopCount = 10
        )
        assertEquals("Portrait", config.mode)
    }

    @Test
    fun toJson_includesMode() {
        val config = SessionConfig(
            durationSeconds = 10,
            intervalMinutes = 1,
            resolution = "HD",
            loopCount = 10,
            mode = "Landscape"
        )
        assertEquals("Landscape", config.toJson().getString("mode"))
    }

    @Test
    fun fromJson_roundTripsMode() {
        for (mode in listOf("Portrait", "Landscape")) {
            val json = JSONObject().apply {
                put("durationSeconds", 10)
                put("intervalMinutes", 1)
                put("resolution", "HD")
                put("loopCount", 10)
                put("mode", mode)
            }
            assertEquals(mode, SessionConfig.fromJson(json).mode)
        }
    }
}
