package com.aritr.rova.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * PR-α (ADR-0029 §Decision 6) — per-segment effectiveTargetRotation persistence.
 * Uses the real org.json on testImplementation (per test policy). Schema 9 -> 10.
 */
class SegmentRecordRotationTest {

    @Test fun `effectiveTargetRotation round-trips when set`() {
        val rec = SegmentRecord(
            filename = "segment_0000.mp4",
            durationMs = 10_000L,
            sizeBytes = 1234L,
            sha1 = "abc",
            effectiveTargetRotation = 1, // Surface.ROTATION_90
        )
        val back = SegmentRecord.fromJson(rec.toJson())
        assertEquals(1, back.effectiveTargetRotation)
    }

    @Test fun `null effectiveTargetRotation is not emitted (legacy byte-shape)`() {
        val rec = SegmentRecord(
            filename = "segment_0000.mp4",
            durationMs = 10_000L,
            sizeBytes = 1234L,
            sha1 = "abc",
        )
        assertFalse("absent field must not appear for single-rotation/legacy records",
            rec.toJson().has("effectiveTargetRotation"))
    }

    @Test fun `legacy schema-9 segment json (no rotation key) reads as null`() {
        val legacy = JSONObject().apply {
            put("filename", "segment_0001.mp4")
            put("durationMs", 9_000L)
            put("sizeBytes", 555L)
            put("sha1", "def")
        }
        assertNull("never fabricate rotation for an old segment record",
            SegmentRecord.fromJson(legacy).effectiveTargetRotation)
    }

    @Test fun `submit parameter default keeps rotation null`() {
        val rec = SegmentRecord(filename = "s.mp4", durationMs = 1L, sizeBytes = 1L, sha1 = "x")
        assertNull(rec.effectiveTargetRotation)
    }
}
