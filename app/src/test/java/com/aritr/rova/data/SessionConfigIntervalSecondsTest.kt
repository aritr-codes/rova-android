package com.aritr.rova.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionConfigIntervalSecondsTest {

    private fun cfg(intervalSeconds: Int) =
        SessionConfig(durationSeconds = 10, intervalSeconds = intervalSeconds, resolution = "HD", loopCount = 10)

    @Test fun roundTrip_writesIntervalSeconds() {
        val json = cfg(30).toJson()
        assertEquals(30, json.getInt("intervalSeconds"))
        assertEquals(30, SessionConfig.fromJson(json).intervalSeconds)
    }

    @Test fun legacyManifest_readsMinutesTimes60() {
        val json = JSONObject().apply {
            put("durationSeconds", 10)
            put("intervalMinutes", 5)   // schema<=12: only the legacy key
            put("resolution", "HD")
            put("loopCount", 10)
        }
        assertEquals(300, SessionConfig.fromJson(json).intervalSeconds)
    }

    @Test fun newKeyWins_whenBothPresent() {
        val json = JSONObject().apply {
            put("durationSeconds", 10)
            put("intervalMinutes", 5)
            put("intervalSeconds", 30)
            put("resolution", "HD")
            put("loopCount", 10)
        }
        assertEquals(30, SessionConfig.fromJson(json).intervalSeconds)
    }

    @Test fun schemaVersion_isThirteen() {
        assertEquals(13, SessionManifest.SCHEMA_VERSION)
    }
}
