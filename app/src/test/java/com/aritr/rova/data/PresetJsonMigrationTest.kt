package com.aritr.rova.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetJsonMigrationTest {

    @Test fun v3RoundTrip_secondsAndIdPreserved() {
        val p = RovaPreset(name = "Half", duration = 10, intervalSeconds = 30, loopCount = 5,
            resolution = "HD", id = "custom.abc", isBuiltIn = false)
        val back = PresetJson.decode(PresetJson.encode(listOf(p)))
        assertEquals(30, back[0].intervalSeconds)
        assertEquals("custom.abc", back[0].id)
    }

    @Test fun legacyV2Envelope_minutesScaledAndIdKept() {
        val v2 = JSONObject().apply {
            put("presetSchemaVersion", 2)
            put("presets", JSONArray().put(JSONObject().apply {
                put("id", "custom.deadbeef"); put("name", "Two"); put("duration", 10)
                put("interval", 2); put("loopCount", 5); put("resolution", "HD")
            }))
        }.toString()
        val back = PresetJson.decode(v2)
        assertEquals(120, back[0].intervalSeconds)
        assertEquals("custom.deadbeef", back[0].id)
    }

    @Test fun legacyArray_noVersion_treatedAsMinutes() {
        val arr = "[{\"id\":\"custom.x\",\"name\":\"Min\",\"duration\":10,\"interval\":1,\"loopCount\":5,\"resolution\":\"HD\"}]"
        assertEquals(60, PresetJson.decode(arr)[0].intervalSeconds)
    }

    @Test fun blankId_derivesStableCustomId() {
        val arr = "[{\"name\":\"NoId\",\"duration\":10,\"interval\":1,\"loopCount\":5,\"resolution\":\"HD\"}]"
        assertTrue(PresetJson.decode(arr)[0].id.startsWith("custom."))
    }
}
