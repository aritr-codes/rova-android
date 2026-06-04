package com.aritr.rova.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionManifestSafFieldsTest {

    private fun base() = SessionManifest(
        sessionId = "s1",
        startedAt = 1L,
        config = SessionConfig(10, 1, "1080p", 3, "Portrait"),
        segments = emptyList(),
        exportTier = ExportTier.TIER1_API29_PLUS
    )

    @Test
    fun safFields_default_null_and_zero() {
        val m = base()
        assertNull(m.safTargetDocUri)
        assertNull(m.portraitSafTargetDocUri)
        assertNull(m.landscapeSafTargetDocUri)
        assertEquals(0, m.safTransientRetryCount)
    }

    @Test
    fun safFields_roundTrip_through_json() {
        val m = base().copy(
            safTargetDocUri = "content://tree/doc/42",
            portraitSafTargetDocUri = "content://tree/doc/p",
            landscapeSafTargetDocUri = "content://tree/doc/l",
            safTransientRetryCount = 2
        )
        val restored = SessionManifest.fromJson(m.toJson())
        assertEquals("content://tree/doc/42", restored.safTargetDocUri)
        assertEquals("content://tree/doc/p", restored.portraitSafTargetDocUri)
        assertEquals("content://tree/doc/l", restored.landscapeSafTargetDocUri)
        assertEquals(2, restored.safTransientRetryCount)
    }

    @Test
    fun safFields_absent_in_old_json_parse_to_defaults() {
        val json = base().toJson()
        json.remove("safTargetDocUri")
        json.remove("safTransientRetryCount")
        val restored = SessionManifest.fromJson(json)
        assertNull(restored.safTargetDocUri)
        assertEquals(0, restored.safTransientRetryCount)
    }
}
