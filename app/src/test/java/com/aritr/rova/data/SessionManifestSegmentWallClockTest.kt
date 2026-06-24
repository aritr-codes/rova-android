package com.aritr.rova.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SessionManifestSegmentWallClockTest {

    @Test fun `round-trips startedAtWallClock when set`() {
        val rec = SegmentRecord("segment_0001.mp4", 30000L, 1234L, "abc", startedAtWallClock = 1_700_000_000_000L)
        val back = SegmentRecord.fromJson(rec.toJson())
        assertEquals(1_700_000_000_000L, back.startedAtWallClock)
    }

    @Test fun `legacy record without key reads null`() {
        val legacy = JSONObject()
            .put("filename", "segment_0001.mp4").put("durationMs", 30000L)
            .put("sizeBytes", 1234L).put("sha1", "abc")
        val rec = SegmentRecord.fromJson(legacy)
        assertNull(rec.startedAtWallClock)
    }

    @Test fun `byte-shape unchanged when null — no key emitted`() {
        val rec = SegmentRecord("segment_0001.mp4", 30000L, 1234L, "abc")
        assertFalse(rec.toJson().has("startedAtWallClock"))
    }

    @Test fun `schema version is 13`() {
        // 12 -> 13: ADR-0033 interval unit minutes -> seconds (intervalSeconds).
        assertEquals(13, SessionManifest.SCHEMA_VERSION)
    }
}
