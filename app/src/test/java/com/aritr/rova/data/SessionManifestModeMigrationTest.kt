package com.aritr.rova.data

import com.aritr.rova.service.dualrecord.VideoSide
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ADR-0029 PR-γ §6 — verifies that [SessionManifest.fromJson] correctly handles
 * the legacy "mode" field across schema versions. See spec §2, §4, §6 D7.
 *
 * Schema<=10 manifests carry a "mode" key in config; schema 11+ use
 * "captureTopology"/"orientationPolicy". Legacy "mode" values must derive
 * the correct [SessionConfig.captureTopology] via [ModeMigration], and the
 * raw label must be preserved in [SessionConfig.legacyMode] for recovery
 * rewrites.
 *
 * Manifests with no "mode" key (v3) derive "Single" topology (null → default).
 * Unknown/garbage mode values also derive "Single" (safe coercion).
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
    fun v3_manifest_with_no_mode_key_derivesSingle_noLegacy() {
        val json = buildManifestJson(schemaVersion = 3, configJson = v3ConfigJson())
        val manifest = SessionManifest.fromJson(json)
        assertEquals("Single", manifest.config.captureTopology)
        assertNull(manifest.config.legacyMode)
    }

    @Test
    fun v4_manifest_with_Portrait_mode_derivesSingle_preservesLegacy() {
        val configJson = v3ConfigJson().apply { put("mode", "Portrait") }
        val manifest = SessionManifest.fromJson(buildManifestJson(4, configJson))
        assertEquals("Single", manifest.config.captureTopology)
        assertEquals("Portrait", manifest.config.legacyMode)
    }

    @Test
    fun v4_manifest_with_Landscape_mode_derivesSingle_preservesLegacy() {
        val configJson = v3ConfigJson().apply { put("mode", "Landscape") }
        val manifest = SessionManifest.fromJson(buildManifestJson(4, configJson))
        assertEquals("Single", manifest.config.captureTopology)
        assertEquals("Landscape", manifest.config.legacyMode)
    }

    @Test
    fun v4_manifest_with_PortraitLandscape_derivesDualShot_preservesLegacy() {
        val configJson = v3ConfigJson().apply { put("mode", "PortraitLandscape") }
        val manifest = SessionManifest.fromJson(buildManifestJson(4, configJson))
        assertEquals("DualShot", manifest.config.captureTopology)
        assertEquals("PortraitLandscape", manifest.config.legacyMode)
    }

    @Test
    fun v4_manifest_with_unknown_mode_derivesSingle_noLegacy() {
        val configJson = v3ConfigJson().apply { put("mode", "Diagonal") }
        val manifest = SessionManifest.fromJson(buildManifestJson(4, configJson))
        assertEquals("Single", manifest.config.captureTopology)
        assertNull(manifest.config.legacyMode)
    }

    @Test
    fun v4_manifest_with_null_or_empty_mode_derivesSingle_noLegacy() {
        // JSONObject.NULL case
        val nullConfigJson = v3ConfigJson().apply { put("mode", JSONObject.NULL) }
        val nullManifest = SessionManifest.fromJson(buildManifestJson(4, nullConfigJson))
        assertEquals("Single", nullManifest.config.captureTopology)
        assertNull(nullManifest.config.legacyMode)

        // Empty string case
        val emptyConfigJson = v3ConfigJson().apply { put("mode", "") }
        val emptyManifest = SessionManifest.fromJson(buildManifestJson(4, emptyConfigJson))
        assertEquals("Single", emptyManifest.config.captureTopology)
        assertNull(emptyManifest.config.legacyMode)
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
    fun `SCHEMA_VERSION is 13`() {
        // 12 -> 13: ADR-0033 intervalSeconds.
        // Bumped 11 -> 12 for ADR-0032 per-segment startedAtWallClock.
        // Previously: 10 -> 11 ADR-0029 PR-γ captureTopology/orientationPolicy axes;
        // 9 -> 10 ADR-0029 PR-α per-segment effectiveTargetRotation;
        // 8 -> 9 ADR-0027 daily-window schedule fields;
        // 7 -> 8 commit-before-finalize move-out pointers;
        // 6 -> 7 vault fields; 5 -> 6 ADR-0024 SAF target fields.
        assertEquals(13, SessionManifest.SCHEMA_VERSION)
    }
}
