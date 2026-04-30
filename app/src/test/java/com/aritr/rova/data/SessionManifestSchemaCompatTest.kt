package com.aritr.rova.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Phase 1.7 commit-0 — schema-2 read-tolerance regression suite for
 * [SessionManifest.fromJson].
 *
 * Why this is load-bearing: Phase 1.3 builds wrote manifests at schema
 * version 2 with no `exportTier` field. The pre-fix `fromJson` called
 * `json.getString("exportTier")`, which throws `JSONException` on those
 * manifests. A cold launch on a Phase 1.7 build that encounters a single
 * legacy session would crash before either Phase 1.5 (segment recovery)
 * or Phase 1.7 (export recovery) could classify it. Per ADR 0003 §"FD
 * Mode Amendment" / §"Pending Visibility (API 29 vs 30+)" — the
 * amendment block also documents the `currentExportTier()` fallback
 * rationale: a downgraded build reading a higher-SDK manifest must
 * route recovery on a code path the running build can execute, not on
 * a frozen literal that may reference an unreachable tier.
 *
 * Per-API SDK coverage (sdk = 24 / 26 / 29 / 30 / 33) is deferred to a
 * future Robolectric pass; the JVM unit tests here cover the contract
 * at the test process's effective `Build.VERSION.SDK_INT` (which the
 * `unitTests.isReturnDefaultValues = true` config returns as 0 — i.e.
 * the `currentExportTier()` else branch). The fallback wiring is
 * verified; the per-API matrix is icing.
 */
class SessionManifestSchemaCompatTest {

    /**
     * The primary defect: a hand-built schema-2 manifest with no
     * `exportTier` field MUST load cleanly. Pre-fix this throws
     * `JSONException`. Post-fix the field defaults to
     * [currentExportTier]'s return value so recovery has a usable tier.
     */
    @Test
    fun `schema-2 manifest without exportTier loads with currentExportTier fallback`() {
        val schema2Json = JSONObject().apply {
            put("schemaVersion", 2)
            put("sessionId", "legacy-session-001")
            put("startedAt", 1_700_000_000_000L)
            put("config", JSONObject().apply {
                put("durationSeconds", 30)
                put("intervalMinutes", 5)
                put("resolution", "FHD")
                put("loopCount", 4)
            })
            put("segments", JSONArray())
            // No exportTier, no exportState, no Phase 1.7 export pointers.
            put("stopRequested", false)
        }

        val manifest = SessionManifest.fromJson(schema2Json)

        assertEquals(currentExportTier(), manifest.exportTier)
        assertEquals(ExportState.NOT_STARTED, manifest.exportState)
        assertNull(manifest.privateTempPath)
        assertNull(manifest.pendingUri)
        assertNull(manifest.publicTargetPath)
        assertFalse(manifest.mediaScanCompleted)
    }

    /**
     * Belt-and-braces against future enum renames or a corrupted manifest
     * carrying a stale tier value. `runCatching { ExportTier.valueOf(...) }`
     * collapses both the missing-field and the bad-value cases into one
     * fallback path. This mirrors the existing pattern for `audioMode`,
     * `stopReason`, and `terminated`.
     */
    @Test
    fun `schema-2 manifest with malformed exportTier value falls back to currentExportTier`() {
        val schema2Json = JSONObject().apply {
            put("schemaVersion", 2)
            put("sessionId", "legacy-session-002")
            put("startedAt", 1_700_000_000_000L)
            put("config", JSONObject().apply {
                put("durationSeconds", 30)
                put("intervalMinutes", 5)
                put("resolution", "FHD")
                put("loopCount", 4)
            })
            put("segments", JSONArray())
            put("exportTier", "TIER0_NONEXISTENT")
            put("stopRequested", false)
        }

        val manifest = SessionManifest.fromJson(schema2Json)

        assertEquals(currentExportTier(), manifest.exportTier)
    }

    /**
     * Regression guard for schema-3 round-trip: every Phase 1.7 export
     * field must survive `toJson` → `fromJson`. If a future refactor
     * accidentally drops one of these from the serializer, this fires.
     */
    @Test
    fun `schema-3 manifest round-trips export fields unchanged`() {
        val original = SessionManifest(
            sessionId = "current-session-001",
            startedAt = 1_700_000_000_000L,
            config = SessionConfig(
                durationSeconds = 30,
                intervalMinutes = 5,
                resolution = "FHD",
                loopCount = 4
            ),
            segments = emptyList(),
            exportTier = ExportTier.TIER1_API29_PLUS,
            privateTempPath = "/data/user/0/com.aritr.rova/files/videos/sess/export/sess.mp4",
            pendingUri = "content://media/external/video/media/12345",
            publicTargetPath = "/storage/emulated/0/Movies/Rova/clip.mp4",
            mediaScanCompleted = true,
            exportState = ExportState.FINALIZED
        )

        val roundTripped = SessionManifest.fromJson(original.toJson())

        assertEquals(original.exportTier, roundTripped.exportTier)
        assertEquals(original.privateTempPath, roundTripped.privateTempPath)
        assertEquals(original.pendingUri, roundTripped.pendingUri)
        assertEquals(original.publicTargetPath, roundTripped.publicTargetPath)
        assertEquals(original.mediaScanCompleted, roundTripped.mediaScanCompleted)
        assertEquals(original.exportState, roundTripped.exportState)
    }

    /**
     * Defensive: a partial schema-2 manifest carrying SOME but not ALL
     * Phase 1.7 fields (e.g. an upgrade that landed exportState before
     * exportTier — hypothetical, but the read path must not assume
     * field-set atomicity). The fallback wiring covers each field
     * independently.
     */
    @Test
    fun `partial schema migration leaves missing fields at safe defaults`() {
        val partialJson = JSONObject().apply {
            put("schemaVersion", 2)
            put("sessionId", "partial-session-003")
            put("startedAt", 1_700_000_000_000L)
            put("config", JSONObject().apply {
                put("durationSeconds", 30)
                put("intervalMinutes", 5)
                put("resolution", "FHD")
                put("loopCount", 4)
            })
            put("segments", JSONArray())
            // exportTier present, exportState present, pointers absent.
            put("exportTier", ExportTier.TIER2_API26_28.name)
            put("exportState", ExportState.MUXING.name)
            put("stopRequested", false)
        }

        val manifest = SessionManifest.fromJson(partialJson)

        assertEquals(ExportTier.TIER2_API26_28, manifest.exportTier)
        assertEquals(ExportState.MUXING, manifest.exportState)
        assertNull(manifest.privateTempPath)
        assertNull(manifest.pendingUri)
        assertNull(manifest.publicTargetPath)
        assertFalse(manifest.mediaScanCompleted)
        assertNotNull(manifest.exportTier)
    }
}
