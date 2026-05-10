package com.aritr.rova.ui.screens.player

import com.aritr.rova.data.ExportState
import com.aritr.rova.data.ExportTier
import com.aritr.rova.data.SegmentRecord
import com.aritr.rova.data.SessionConfig
import com.aritr.rova.data.SessionManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2.5 — pins the manifest → [PlayerUiState] dispatch contract.
 * JVM-only: the resolver is a pure function over manifest fields, no
 * `ContentResolver` / ExoPlayer / Robolectric required.
 *
 * The matrix mirrors [com.aritr.rova.ui.screens.HistoryArtifactMapperTest]
 * (the read-side analogue) so both surfaces — Library list and Player
 * — agree on what a "playable" manifest looks like:
 *  - `exportState != FINALIZED` is the gate; non-finalized rows
 *    surface as Unavailable rather than Loading-forever.
 *  - Tier 1 routes through `pendingUri` directly (no `_DATA` round-
 *    trip; the player consumes URIs, not Files).
 *  - Tier 2 / Tier 3 route through `publicTargetPath` rendered as a
 *    `file://` URI.
 */
class PlayerUriResolverTest {

    private fun manifest(
        sessionId: String = "abc",
        exportState: ExportState = ExportState.FINALIZED,
        tier: ExportTier = ExportTier.TIER1_API29_PLUS,
        pendingUri: String? = null,
        publicTargetPath: String? = null,
        segments: List<SegmentRecord> = emptyList(),
        durationSeconds: Int = 30,
        startedAt: Long = 1_715_000_000_000L
    ) = SessionManifest(
        sessionId = sessionId,
        startedAt = startedAt,
        config = SessionConfig(
            durationSeconds = durationSeconds,
            intervalMinutes = 0,
            resolution = "FHD",
            loopCount = segments.size.coerceAtLeast(1)
        ),
        segments = segments,
        exportTier = tier,
        exportState = exportState,
        pendingUri = pendingUri,
        publicTargetPath = publicTargetPath
    )

    private fun seg(durationMs: Long) =
        SegmentRecord(filename = "segment_$durationMs.mp4", durationMs = durationMs, sizeBytes = 1L, sha1 = "0")

    // ─── Unavailable branches ─────────────────────────────────────

    @Test
    fun `null manifest returns Unavailable`() {
        val state = PlayerUriResolver.resolve(null)
        assertTrue("expected Unavailable, got $state", state is PlayerUiState.Unavailable)
    }

    @Test
    fun `non-finalized manifest returns Unavailable`() {
        val states = listOf(
            ExportState.NOT_STARTED,
            ExportState.MUXING,
            ExportState.COPYING,
            ExportState.FAILED
        ).map { state ->
            PlayerUriResolver.resolve(
                manifest(
                    exportState = state,
                    tier = ExportTier.TIER1_API29_PLUS,
                    pendingUri = "content://media/1"
                )
            )
        }
        states.forEachIndexed { i, s ->
            assertTrue("index $i expected Unavailable, got $s", s is PlayerUiState.Unavailable)
        }
    }

    @Test
    fun `Tier 1 with null pendingUri returns Unavailable`() {
        val state = PlayerUriResolver.resolve(
            manifest(tier = ExportTier.TIER1_API29_PLUS, pendingUri = null)
        )
        assertTrue(state is PlayerUiState.Unavailable)
    }

    @Test
    fun `Tier 2 with null publicTargetPath returns Unavailable`() {
        val state = PlayerUriResolver.resolve(
            manifest(tier = ExportTier.TIER2_API26_28, publicTargetPath = null)
        )
        assertTrue(state is PlayerUiState.Unavailable)
    }

    @Test
    fun `Tier 3 with null publicTargetPath returns Unavailable`() {
        val state = PlayerUriResolver.resolve(
            manifest(tier = ExportTier.TIER3_API24_25, publicTargetPath = null)
        )
        assertTrue(state is PlayerUiState.Unavailable)
    }

    // ─── Ready branches ───────────────────────────────────────────

    @Test
    fun `Tier 1 ready uses pendingUri verbatim as content URI`() {
        val state = PlayerUriResolver.resolve(
            manifest(
                sessionId = "t1",
                tier = ExportTier.TIER1_API29_PLUS,
                pendingUri = "content://media/external/video/media/42",
                segments = listOf(seg(10_000), seg(10_000), seg(10_000))
            )
        )
        val ready = state as PlayerUiState.Ready
        assertEquals("content://media/external/video/media/42", ready.mediaUri)
        assertEquals("t1", ready.sessionId)
        assertEquals(listOf(10_000L, 10_000L, 10_000L), ready.segmentDurationsMs)
        assertEquals(3, ready.totalClips)
        assertEquals(30_000L, ready.perClipDurationMs)
        // Audit F#4 — manifest-authoritative total surfaced on Ready.
        assertEquals(30_000L, ready.totalDurationFromSegmentsMs)
    }

    @Test
    fun `Ready totalDurationFromSegmentsMs equals segment sum on uneven clips`() {
        // Phase 1.5 carry-over case: last clip truncated mid-recording
        // produces a short final segment. The authoritative total
        // tracks reality, not perClipDurationMs * totalClips.
        val state = PlayerUriResolver.resolve(
            manifest(
                tier = ExportTier.TIER1_API29_PLUS,
                pendingUri = "content://media/uneven",
                segments = listOf(seg(30_000), seg(30_000), seg(7_500)),
                durationSeconds = 30
            )
        )
        val ready = state as PlayerUiState.Ready
        assertEquals(67_500L, ready.totalDurationFromSegmentsMs)
        // perClipDurationMs * totalClips would have over-reported 90s.
        assertEquals(30_000L, ready.perClipDurationMs)
        assertEquals(3, ready.totalClips)
    }

    @Test
    fun `Tier 2 ready renders publicTargetPath as file URI`() {
        val state = PlayerUriResolver.resolve(
            manifest(
                tier = ExportTier.TIER2_API26_28,
                publicTargetPath = "/storage/Movies/Rova/Rova_t2.mp4",
                segments = listOf(seg(5_000))
            )
        )
        val ready = state as PlayerUiState.Ready
        assertEquals("file:///storage/Movies/Rova/Rova_t2.mp4", ready.mediaUri)
        assertEquals(1, ready.totalClips)
        assertEquals(5_000L, ready.totalDurationFromSegmentsMs)
    }

    @Test
    fun `Tier 3 ready renders publicTargetPath as file URI`() {
        val state = PlayerUriResolver.resolve(
            manifest(
                tier = ExportTier.TIER3_API24_25,
                publicTargetPath = "/storage/Movies/Rova/Rova_t3.mp4",
                segments = listOf(seg(1_000))
            )
        )
        val ready = state as PlayerUiState.Ready
        assertEquals("file:///storage/Movies/Rova/Rova_t3.mp4", ready.mediaUri)
    }

    @Test
    fun `Tier 2 file URI escapes spaces`() {
        val state = PlayerUriResolver.resolve(
            manifest(
                tier = ExportTier.TIER2_API26_28,
                publicTargetPath = "/storage/Movies/Rova/My Recording.mp4",
                segments = listOf(seg(1_000))
            )
        )
        val ready = state as PlayerUiState.Ready
        assertEquals("file:///storage/Movies/Rova/My%20Recording.mp4", ready.mediaUri)
    }

    @Test
    fun `Tier 2 file URI normalizes Windows backslashes`() {
        // Defensive: a manifest written by a test fixture on Windows
        // could carry backslash separators. The on-device service
        // never produces such a path, but the resolver should not
        // emit an invalid `file:` URI either way.
        val state = PlayerUriResolver.resolve(
            manifest(
                tier = ExportTier.TIER2_API26_28,
                publicTargetPath = "\\storage\\Movies\\Rova\\Rova_w.mp4",
                segments = listOf(seg(1_000))
            )
        )
        val ready = state as PlayerUiState.Ready
        assertEquals("file:///storage/Movies/Rova/Rova_w.mp4", ready.mediaUri)
    }

    @Test
    fun `empty segments is rejected as Unavailable`() {
        // Audit F#11 — a finalized manifest with zero segments is
        // either corrupted or pre-finalize race. Surfacing it as
        // Ready leads to a Ready→Unavailable flicker once ExoPlayer
        // fires onPlayerError on the missing/0-byte file. Refuse up
        // front so the user sees a single Unavailable with a clear
        // reason.
        val state = PlayerUriResolver.resolve(
            manifest(
                tier = ExportTier.TIER1_API29_PLUS,
                pendingUri = "content://media/empty",
                segments = emptyList()
            )
        )
        assertTrue("expected Unavailable, got $state", state is PlayerUiState.Unavailable)
        assertEquals("Recording incomplete", (state as PlayerUiState.Unavailable).reason)
    }

    @Test
    fun `single segment ready surfaces sum equal to that segment`() {
        val state = PlayerUriResolver.resolve(
            manifest(
                tier = ExportTier.TIER2_API26_28,
                publicTargetPath = "/storage/Movies/Rova/Rova_only.mp4",
                segments = listOf(seg(45_000))
            )
        )
        val ready = state as PlayerUiState.Ready
        assertEquals(1, ready.totalClips)
        assertEquals(45_000L, ready.totalDurationFromSegmentsMs)
        assertEquals(listOf(45_000L), ready.segmentDurationsMs)
    }
}
