package com.aritr.rova.data

import com.aritr.rova.service.dualrecord.VideoSide
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Phase 6 — verifies that [SessionManifest.fromJson] correctly handles the
 * [SessionConfig.mode] field across schema versions. See spec §2, §4, §6 D7.
 *
 * v3 manifests (no `mode` key in `config`) must default to `"Portrait"`.
 * v4 manifests must round-trip valid values and coerce unknown / null / empty
 * values to `"Portrait"`.
 */
class SessionManifestModeMigrationTest {

    /** Builds a minimal valid manifest JSON with the given config sub-object. */
    private fun buildManifestJson(schemaVersion: Int, configJson: JSONObject): JSONObject =
        JSONObject().apply {
            put("schemaVersion", schemaVersion)
            put("sessionId", "test-session-123")
            put("startedAt", 1_700_000_000_000L)
            put("config", configJson)
            put("segments", JSONArray())
            put("stopRequested", false)
        }

    /** Builds a minimal v3-style config JSON (omits the `mode` key). */
    private fun v3ConfigJson(): JSONObject = JSONObject().apply {
        put("durationSeconds", 10)
        put("intervalMinutes", 1)
        put("resolution", "HD")
        put("loopCount", 10)
        // intentionally NO "mode" key — this is the v3 shape
    }

    @Test
    fun v3_manifest_with_no_mode_key_defaults_to_Portrait() {
        val json = buildManifestJson(schemaVersion = 3, configJson = v3ConfigJson())
        val manifest = SessionManifest.fromJson(json)
        assertEquals("Portrait", manifest.config.mode)
    }

    @Test
    fun v4_manifest_with_Portrait_mode_round_trips() {
        val configJson = v3ConfigJson().apply { put("mode", "Portrait") }
        val manifest = SessionManifest.fromJson(buildManifestJson(4, configJson))
        assertEquals("Portrait", manifest.config.mode)
    }

    @Test
    fun v4_manifest_with_Landscape_mode_round_trips() {
        val configJson = v3ConfigJson().apply { put("mode", "Landscape") }
        val manifest = SessionManifest.fromJson(buildManifestJson(4, configJson))
        assertEquals("Landscape", manifest.config.mode)
    }

    @Test
    fun v4_manifest_with_unknown_mode_coerces_to_Portrait() {
        val configJson = v3ConfigJson().apply { put("mode", "Diagonal") }
        val manifest = SessionManifest.fromJson(buildManifestJson(4, configJson))
        assertEquals("Portrait", manifest.config.mode)
    }

    @Test
    fun v4_manifest_with_null_or_empty_mode_coerces_to_Portrait() {
        // JSONObject.NULL case
        val nullConfigJson = v3ConfigJson().apply { put("mode", JSONObject.NULL) }
        val nullManifest = SessionManifest.fromJson(buildManifestJson(4, nullConfigJson))
        assertEquals("Portrait", nullManifest.config.mode)

        // Empty string case
        val emptyConfigJson = v3ConfigJson().apply { put("mode", "") }
        val emptyManifest = SessionManifest.fromJson(buildManifestJson(4, emptyConfigJson))
        assertEquals("Portrait", emptyManifest.config.mode)
    }

    @Test
    fun `SegmentRecord round-trips with side PORTRAIT`() {
        val rec = SegmentRecord(
            filename = "segment_0001_P.mp4",
            durationMs = 10000L,
            sizeBytes = 1024L,
            sha1 = "abc",
            side = VideoSide.PORTRAIT
        )
        val json = rec.toJson()
        val back = SegmentRecord.fromJson(json)
        assertEquals(VideoSide.PORTRAIT, back.side)
        assertEquals("segment_0001_P.mp4", back.filename)
    }

    @Test
    fun `SegmentRecord with side null does not emit side field in JSON`() {
        val rec = SegmentRecord(
            filename = "segment_0001.mp4",
            durationMs = 10000L,
            sizeBytes = 1024L,
            sha1 = "abc",
            side = null
        )
        val json = rec.toJson()
        assertFalse(json.has("side"))
    }

    @Test
    fun `SegmentRecord fromJson with missing side reads as null (legacy)`() {
        val json = JSONObject().apply {
            put("filename", "segment_0001.mp4")
            put("durationMs", 10000L)
            put("sizeBytes", 1024L)
            put("sha1", "abc")
        }
        val rec = SegmentRecord.fromJson(json)
        assertNull(rec.side)
    }

    @Test
    fun `SCHEMA_VERSION is 5`() {
        assertEquals(5, SessionManifest.SCHEMA_VERSION)
    }
}
