package com.aritr.rova.ui.screens.player

import com.aritr.rova.data.ExportState
import com.aritr.rova.data.ExportTier
import com.aritr.rova.data.SegmentRecord
import com.aritr.rova.data.SessionConfig
import com.aritr.rova.data.SessionManifest
import com.aritr.rova.service.dualrecord.VideoSide
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
        startedAt: Long = 1_715_000_000_000L,
        mode: String = "Portrait",
        portraitPendingUri: String? = null,
        portraitPublicTargetPath: String? = null,
        landscapePendingUri: String? = null,
        landscapePublicTargetPath: String? = null
    ) = SessionManifest(
        sessionId = sessionId,
        startedAt = startedAt,
        config = SessionConfig(
            durationSeconds = durationSeconds,
            intervalMinutes = 0,
            resolution = "FHD",
            loopCount = segments.size.coerceAtLeast(1),
            mode = mode
        ),
        segments = segments,
        exportTier = tier,
        exportState = exportState,
        pendingUri = pendingUri,
        publicTargetPath = publicTargetPath,
        portraitPendingUri = portraitPendingUri,
        portraitPublicTargetPath = portraitPublicTargetPath,
        landscapePendingUri = landscapePendingUri,
        landscapePublicTargetPath = landscapePublicTargetPath
    )

    private fun seg(durationMs: Long, side: VideoSide? = null) =
        SegmentRecord(
            filename = "segment_${durationMs}_${side?.name ?: "single"}.mp4",
            durationMs = durationMs,
            sizeBytes = 1L,
            sha1 = "0",
            side = side
        )

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

    // ─── Phase 6.1b smoke-fix #3 — P+L per-side dispatch ─────────────
    // PlayerScreen receives a `side: VideoSide?` argument threaded from
    // the History card click. For P+L sessions the shared `pendingUri` /
    // `publicTargetPath` are null (Tier1Exporter / Tier2Exporter write to
    // the per-side variants — see ADR-0008 + Phase 6.1b T11/T13). Without
    // this branch the resolver collapses to Unavailable on every P+L card
    // click ("Recording file not found"). The 4th read-side consumer the
    // T18-T20 final reviewer missed alongside HistoryArtifactMapper.
    @Test
    fun `P+L Tier 1 with side PORTRAIT returns Ready with portraitPendingUri`() {
        val state = PlayerUriResolver.resolve(
            manifest(
                sessionId = "pl1",
                tier = ExportTier.TIER1_API29_PLUS,
                mode = "PortraitLandscape",
                portraitPendingUri = "content://media/external/video/media/100",
                landscapePendingUri = "content://media/external/video/media/200",
                segments = listOf(
                    seg(10_000, VideoSide.PORTRAIT),
                    seg(10_000, VideoSide.LANDSCAPE),
                )
            ),
            side = VideoSide.PORTRAIT
        )
        val ready = state as PlayerUiState.Ready
        assertEquals("content://media/external/video/media/100", ready.mediaUri)
        assertEquals("pl1", ready.sessionId)
    }

    @Test
    fun `P+L Tier 1 with side LANDSCAPE returns Ready with landscapePendingUri`() {
        val state = PlayerUriResolver.resolve(
            manifest(
                sessionId = "pl1",
                tier = ExportTier.TIER1_API29_PLUS,
                mode = "PortraitLandscape",
                portraitPendingUri = "content://media/external/video/media/100",
                landscapePendingUri = "content://media/external/video/media/200",
                segments = listOf(
                    seg(10_000, VideoSide.PORTRAIT),
                    seg(10_000, VideoSide.LANDSCAPE),
                )
            ),
            side = VideoSide.LANDSCAPE
        )
        val ready = state as PlayerUiState.Ready
        assertEquals("content://media/external/video/media/200", ready.mediaUri)
    }

    @Test
    fun `P+L Tier 2 with side PORTRAIT returns Ready with file URI of portraitPublicTargetPath`() {
        val state = PlayerUriResolver.resolve(
            manifest(
                tier = ExportTier.TIER2_API26_28,
                mode = "PortraitLandscape",
                portraitPublicTargetPath = "/storage/Movies/Rova/Rova_portrait.mp4",
                landscapePublicTargetPath = "/storage/Movies/Rova/Rova_landscape.mp4",
                segments = listOf(
                    seg(5_000, VideoSide.PORTRAIT),
                    seg(5_000, VideoSide.LANDSCAPE),
                )
            ),
            side = VideoSide.PORTRAIT
        )
        val ready = state as PlayerUiState.Ready
        assertEquals("file:///storage/Movies/Rova/Rova_portrait.mp4", ready.mediaUri)
    }

    @Test
    fun `P+L with null side returns Unavailable (defensive routing invariant)`() {
        // The History card click ALWAYS passes a non-null side for P+L
        // rows (HistoryViewModel populates VideoItem.side from
        // PerSideArtifact.side). A null side reaching the resolver on a
        // P+L manifest is a routing bug — surface Unavailable rather
        // than silently picking one side or falling through to the
        // (null) shared pointers.
        val state = PlayerUriResolver.resolve(
            manifest(
                tier = ExportTier.TIER1_API29_PLUS,
                mode = "PortraitLandscape",
                portraitPendingUri = "content://media/external/video/media/100",
                landscapePendingUri = "content://media/external/video/media/200",
                segments = listOf(seg(10_000, VideoSide.PORTRAIT))
            ),
            side = null
        )
        assertTrue("expected Unavailable, got $state", state is PlayerUiState.Unavailable)
    }

    @Test
    fun `single-mode ignores stale per-side fields and side argument`() {
        // Regression: a manifest from a pre-T16 build (or a bug-mixed
        // future state) that carries both shared `pendingUri` and stray
        // per-side fields must keep returning the shared pointer for
        // single-mode. The `side` arg is only consulted when
        // `config.mode == "PortraitLandscape"`.
        val state = PlayerUriResolver.resolve(
            manifest(
                tier = ExportTier.TIER1_API29_PLUS,
                mode = "Portrait",
                pendingUri = "content://media/single/canonical",
                portraitPendingUri = "content://media/stale/portrait",
                landscapePendingUri = "content://media/stale/landscape",
                segments = listOf(seg(10_000))
            ),
            side = VideoSide.LANDSCAPE
        )
        val ready = state as PlayerUiState.Ready
        assertEquals("content://media/single/canonical", ready.mediaUri)
    }

    // ─── Phase 6.1b smoke-fix #4 — P+L per-side segment filter ─────────
    // RovaRecordingService.handleDualVideoEvent.Finalize appends ONE
    // SegmentRecord per side per loop (line 2027). For a 2-loop P+L
    // recording the manifest carries 4 SegmentRecords (2 PORTRAIT + 2
    // LANDSCAPE). The player only plays one side's file — the timeline
    // must filter to that side's segments or it shows the doubled count.

    @Test
    fun `P+L PORTRAIT filters interleaved manifest segments down to PORTRAIT only`() {
        // 2-loop P+L recording: 4 segments tagged P/L/P/L, durations
        // intentionally asymmetric so we can tell which side leaked
        // through if the filter regressed.
        val state = PlayerUriResolver.resolve(
            manifest(
                tier = ExportTier.TIER1_API29_PLUS,
                mode = "PortraitLandscape",
                portraitPendingUri = "content://media/p",
                landscapePendingUri = "content://media/l",
                segments = listOf(
                    seg(10_000, VideoSide.PORTRAIT),
                    seg(9_500, VideoSide.LANDSCAPE),
                    seg(10_200, VideoSide.PORTRAIT),
                    seg(9_700, VideoSide.LANDSCAPE),
                )
            ),
            side = VideoSide.PORTRAIT
        )
        val ready = state as PlayerUiState.Ready
        assertEquals(2, ready.totalClips)
        assertEquals(listOf(10_000L, 10_200L), ready.segmentDurationsMs)
        assertEquals(20_200L, ready.totalDurationFromSegmentsMs)
    }

    @Test
    fun `P+L LANDSCAPE filters interleaved manifest segments down to LANDSCAPE only`() {
        val state = PlayerUriResolver.resolve(
            manifest(
                tier = ExportTier.TIER1_API29_PLUS,
                mode = "PortraitLandscape",
                portraitPendingUri = "content://media/p",
                landscapePendingUri = "content://media/l",
                segments = listOf(
                    seg(10_000, VideoSide.PORTRAIT),
                    seg(9_500, VideoSide.LANDSCAPE),
                    seg(10_200, VideoSide.PORTRAIT),
                    seg(9_700, VideoSide.LANDSCAPE),
                )
            ),
            side = VideoSide.LANDSCAPE
        )
        val ready = state as PlayerUiState.Ready
        assertEquals(2, ready.totalClips)
        assertEquals(listOf(9_500L, 9_700L), ready.segmentDurationsMs)
        assertEquals(19_200L, ready.totalDurationFromSegmentsMs)
    }

    @Test
    fun `P+L PORTRAIT with no PORTRAIT segments surfaces Recording incomplete`() {
        // Degenerate manifest: P+L session where every PORTRAIT segment
        // failed mid-mux (DualMuxer tolerant per-side failure path).
        // The shared pointer fix-up still has a portrait artifact URI
        // populated (recovery wrote a placeholder), but the segments
        // list has only LANDSCAPE entries. The filter collapses to
        // empty → existing `segmentDurations.isEmpty()` branch fires.
        val state = PlayerUriResolver.resolve(
            manifest(
                tier = ExportTier.TIER1_API29_PLUS,
                mode = "PortraitLandscape",
                portraitPendingUri = "content://media/p_recovered",
                landscapePendingUri = "content://media/l",
                segments = listOf(
                    seg(9_500, VideoSide.LANDSCAPE),
                    seg(9_700, VideoSide.LANDSCAPE),
                )
            ),
            side = VideoSide.PORTRAIT
        )
        assertTrue("expected Unavailable, got $state", state is PlayerUiState.Unavailable)
        assertEquals("Recording incomplete", (state as PlayerUiState.Unavailable).reason)
    }

    @Test
    fun `single-mode segments retain unfiltered durations regardless of side argument`() {
        // Regression: the new P+L filter MUST NOT apply to single-mode
        // sessions. Even if a single-mode session somehow carries a
        // mix of side-tagged + untagged segments (a bug-mixed state),
        // the resolver returns all of them unfiltered because the
        // `isPlusL && side != null` guard requires P+L mode.
        val state = PlayerUriResolver.resolve(
            manifest(
                tier = ExportTier.TIER1_API29_PLUS,
                mode = "Portrait",
                pendingUri = "content://media/single",
                segments = listOf(seg(10_000), seg(10_000), seg(10_000))
            ),
            side = VideoSide.PORTRAIT
        )
        val ready = state as PlayerUiState.Ready
        assertEquals(3, ready.totalClips)
        assertEquals(30_000L, ready.totalDurationFromSegmentsMs)
    }
}
