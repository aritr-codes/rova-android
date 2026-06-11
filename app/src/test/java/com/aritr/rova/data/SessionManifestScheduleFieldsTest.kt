package com.aritr.rova.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** ADR-0027 — daily-window schedule fields (schema 9). */
class SessionManifestScheduleFieldsTest {

    private fun base() = SessionManifest(
        sessionId = "s1",
        startedAt = 1L,
        config = SessionConfig(10, 1, "1080p", 3),
        segments = emptyList(),
        exportTier = ExportTier.TIER1_API29_PLUS
    )

    @Test
    fun scheduleFields_default_off() {
        val m = base()
        assertFalse(m.startedBySchedule)
        assertEquals(0L, m.scheduleWindowStartMillis)
        assertEquals(0L, m.scheduleWindowEndMillis)
        assertFalse(m.scheduleWindowExpired)
    }

    @Test
    fun scheduleFields_roundTrip_through_json() {
        val m = base().copy(
            startedBySchedule = true,
            scheduleWindowStartMillis = 1_700_000_000_000L,
            scheduleWindowEndMillis = 1_700_028_800_000L,
            scheduleWindowExpired = true
        )
        val restored = SessionManifest.fromJson(m.toJson())
        assertEquals(true, restored.startedBySchedule)
        assertEquals(1_700_000_000_000L, restored.scheduleWindowStartMillis)
        assertEquals(1_700_028_800_000L, restored.scheduleWindowEndMillis)
        assertEquals(true, restored.scheduleWindowExpired)
    }

    @Test
    fun scheduleFields_absent_in_old_json_parse_to_defaults() {
        val json = base().toJson()
        json.remove("startedBySchedule")
        json.remove("scheduleWindowStartMillis")
        json.remove("scheduleWindowEndMillis")
        json.remove("scheduleWindowExpired")
        val restored = SessionManifest.fromJson(json)
        assertFalse(restored.startedBySchedule)
        assertEquals(0L, restored.scheduleWindowStartMillis)
        assertEquals(0L, restored.scheduleWindowEndMillis)
        assertFalse(restored.scheduleWindowExpired)
    }
}
