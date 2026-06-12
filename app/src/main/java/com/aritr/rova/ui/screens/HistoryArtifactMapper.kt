package com.aritr.rova.ui.screens

import com.aritr.rova.data.ExportState
import com.aritr.rova.data.ExportTier
import com.aritr.rova.data.SessionManifest
import com.aritr.rova.data.Terminated
import com.aritr.rova.service.dualrecord.VideoSide
import java.io.File

/**
 * Phase 1.7 commit-7 NO-GO patch round 2 — manifest-driven artifact
 * resolution for [HistoryViewModel]. Pure functions extracted so JVM
 * unit tests can pin the tier-dispatch contract without Robolectric or
 * a real `ContentResolver`.
 *
 * Pre-Phase-1.7 [HistoryViewModel] scanned `videos/<sessionId>/Rova_*.mp4`
 * directly off disk. Phase 1.7 changed the live-export path so a
 * successful merge no longer leaves a file under the per-session dir:
 *  - **Tier 1** (API 29+): the artifact is a pending `MediaStore` row
 *    referenced by `manifest.pendingUri`. The on-disk file lives under
 *    `Movies/Rova/<displayName>.mp4` but its path is owned by
 *    `MediaStore`; consumers query the `_DATA` column to translate the
 *    URI to a `java.io.File`.
 *  - **Tier 2 / Tier 3** (pre-Q): the artifact is at
 *    `manifest.publicTargetPath` (`Movies/Rova/<displayName>.mp4`).
 *
 * The History screen showed nothing post-merge because the per-session
 * dir was empty (segments deleted, no merged file persisted there).
 * Fix: list manifests instead, dispatch on `manifest.exportTier` to
 * locate the artifact, drop entries whose artifact is unresolvable.
 *
 * `mergedOutputPath` is intentionally NOT reintroduced — Phase 1.7
 * commits 0–7 retired the merge-then-publish split, and resurrecting a
 * persisted "merged output path" would reopen the same authority gap
 * that ADR 0003 closed.
 */
internal object HistoryArtifactMapper {

    /**
     * Filters the loaded manifests down to the ones whose export
     * actually finalized. `terminated` is intentionally NOT used as a
     * gate for the single-mode path — a session can be `FINALIZED` with
     * `terminated == null` for one cold-launch tick before
     * [com.aritr.rova.service.export.ExportRecoveryRunner]'s late-terminal
     * pass writes `markTerminated(COMPLETED, NONE)`. The artifact is on
     * disk; the user expects to see it. The terminal record catches up
     * out-of-band.
     *
     * Phase 6.1b T20 final-review remediation — P+L escape hatch.
     * `performMergeDual`'s shared `setExportFinalized` write may throw
     * AFTER both per-side pipelines have finalized successfully (per-side
     * pointers populated, terminal `COMPLETED` written). The shared
     * `exportState` stays at `MUXING` in that race. Pre-T20 the History
     * screen filtered the manifest out and the user saw nothing despite
     * intact files. The hatch admits a P+L manifest when:
     *   1. `config.mode == "PortraitLandscape"`,
     *   2. `terminated == COMPLETED` (the per-side pipelines settled),
     *   3. at least one per-side public target pointer is populated.
     *
     * Single-mode contract is byte-identical: the hatch is gated on
     * `mode == "PortraitLandscape"` so a single-mode session that is
     * stuck-at-MUXING (a bug state that should not be reachable in
     * production) is NOT silently surfaced.
     */
    fun finalizedManifests(manifests: List<SessionManifest>): List<SessionManifest> =
        manifests.filter { m ->
            val isFinalized = m.exportState == ExportState.FINALIZED
            val isDualWithArtifacts = m.config.captureTopology == "DualShot" &&
                m.terminated == Terminated.COMPLETED &&
                (m.portraitPublicTargetPath != null || m.landscapePublicTargetPath != null)
            // Milestone 2 — MULTI_SEGMENT_KEPT sessions never reach
            // ExportState.FINALIZED (their merge failed and segments were
            // retained on disk by the user's "Keep as raw clips" choice).
            // Admit them here so the per-segment fanout in
            // [resolveArtifactsPerSegment] can emit one library row per
            // kept segment. The segments themselves live under the
            // per-session directory and are surfaced via the
            // HistoryViewModel pipeline's MULTI_SEGMENT_KEPT branch.
            val isMultiSegmentKept = m.terminated == Terminated.MULTI_SEGMENT_KEPT &&
                m.segments.isNotEmpty()
            isFinalized || isDualWithArtifacts || isMultiSegmentKept
        }

    /**
     * Tier dispatch for the on-disk artifact path.
     *
     * @param resolveTier1Uri callback the caller wires to a
     *   `ContentResolver` query for `MediaStore.Video.Media.DATA`.
     *   Returning `null` means "this URI no longer resolves" — the
     *   caller drops the entry.
     */
    fun resolveArtifactFile(
        manifest: SessionManifest,
        resolveTier1Uri: (uriString: String) -> File?
    ): File? = when (manifest.exportTier) {
        ExportTier.TIER1_API29_PLUS ->
            manifest.pendingUri?.let(resolveTier1Uri)
        ExportTier.TIER2_API26_28, ExportTier.TIER3_API24_25 ->
            manifest.publicTargetPath?.let { File(it) }
        // ADR-0024 — a SAF artifact is a `content://` document with no
        // java.io.File on disk (the private temp is cleared on finalize).
        // Like Tier 1 (MediaStore URI), it has no plain file path; callers
        // tolerate null and fall back to the content URI from resolveShareUri.
        ExportTier.SAF_DESTINATION -> null
    }

    /**
     * Tier dispatch for the share URI surfaced via Android's share sheet.
     * Tier 1 always carries a canonical `MediaStore` content URI in
     * `manifest.pendingUri` — that is the safe form for sharing because
     * the file lives under `Movies/Rova/...` (not the FileProvider root)
     * and `FileProvider.getUriForFile` would throw
     * `IllegalArgumentException`.
     *
     * Tier 2/3 manifests do not persist a content URI — only a public
     * file path (`Movies/Rova/<displayName>.mp4`). The caller resolves
     * a content URI by querying `MediaStore` for the row whose `_DATA`
     * column matches that path; that lookup needs a `ContentResolver`,
     * which keeps it out of this pure transformer.
     */
    fun resolveShareUri(manifest: SessionManifest): String? = when (manifest.exportTier) {
        ExportTier.TIER1_API29_PLUS -> manifest.pendingUri
        ExportTier.TIER2_API26_28, ExportTier.TIER3_API24_25 -> null
        // ADR-0024 — the SAF doc URI is itself a shareable `content://` Uri
        // (same shape as Tier 1's MediaStore URI); surface it directly.
        ExportTier.SAF_DESTINATION -> manifest.safTargetDocUri
    }

    /**
     * Phase 6.1b T16 — per-side artifact fanout for P+L sessions.
     *
     * Single-mode (`config.mode == "Portrait"` or `"Landscape"`) returns
     * an empty list — the caller routes those through the existing
     * single-card [resolveArtifactFile] / [resolveShareUri] pair. Only
     * `mode == "PortraitLandscape"` is eligible for fanout; legacy /
     * pre-Phase-6.1b manifests therefore never accidentally double-emit
     * even if a stale per-side field is non-null.
     *
     * For an eligible P+L manifest, emits 0/1/2 entries depending on
     * which sides successfully finalized — per the Phase 6.1b T13 D12
     * independent-atomicity contract, portrait and landscape pipelines
     * can settle independently, and the History screen surfaces whatever
     * is playable:
     * - Both sides finalized → 2 entries (PORTRAIT + LANDSCAPE).
     * - One side finalized → 1 entry (the surviving side).
     * - Both sides null → 0 entries (manifest stays terminal, no
     *   playable artifact — same shape as a single-mode FAILED export
     *   from [resolveArtifactFile]).
     *
     * Tier dispatch per side mirrors the single-mode contract:
     * - **Tier 1**: `portraitPendingUri` / `landscapePendingUri` is the
     *   per-side MediaStore content URI (Phase 6.1b T11+T12); the
     *   caller's [resolveTier1Uri] callback translates it to a File via
     *   `_DATA`. Returning `null` from the callback drops that side.
     *   The per-side `*publicTargetPath` is ALSO populated with the
     *   same URI string (see Tier1Exporter Phase 6.1b T12 contract); we
     *   read `pendingUri` to keep the share-URI semantics consistent
     *   with the single-mode mapper.
     * - **Tier 2/3**: `portraitPublicTargetPath` / `landscapePublicTargetPath`
     *   is the per-side on-disk file path, wrapped as [File].
     *
     * Each emitted entry's `shareUri` mirrors [resolveShareUri]: Tier 1
     * surfaces the content URI directly; Tier 2/3 returns `null`
     * (caller looks up `_DATA` against MediaStore — same as single-mode).
     */
    fun resolveArtifactsPerSide(
        manifest: SessionManifest,
        resolveTier1Uri: (uriString: String) -> File?
    ): List<PerSideArtifact> {
        if (manifest.config.captureTopology != "DualShot") return emptyList()
        val out = mutableListOf<PerSideArtifact>()
        VideoSide.entries.forEach { side ->
            val (sidePending, sidePublic) = when (side) {
                VideoSide.PORTRAIT ->
                    manifest.portraitPendingUri to manifest.portraitPublicTargetPath
                VideoSide.LANDSCAPE ->
                    manifest.landscapePendingUri to manifest.landscapePublicTargetPath
            }
            val (file, shareUri) = when (manifest.exportTier) {
                ExportTier.TIER1_API29_PLUS -> {
                    val uri = sidePending ?: return@forEach
                    val resolved = resolveTier1Uri(uri) ?: return@forEach
                    resolved to uri
                }
                ExportTier.TIER2_API26_28, ExportTier.TIER3_API24_25 -> {
                    val path = sidePublic ?: return@forEach
                    File(path) to null
                }
                // ADR-0024 — a SAF per-side artifact is a `content://`
                // document with no java.io.File on disk. PerSideArtifact
                // requires a non-null File (the History pipeline dedups,
                // sorts, and deletes by file path), so — mirroring the
                // Tier 1 "no resolvable File → return@forEach" structure —
                // P+L SAF rows are skipped here. SAF History surfacing is
                // a later UI slice (content-URI-only rows). The artifact
                // itself is intact (portrait/landscapeSafTargetDocUri).
                ExportTier.SAF_DESTINATION -> return@forEach
            }
            out += PerSideArtifact(side = side, file = file, shareUri = shareUri)
        }
        return out
    }

    /**
     * Phase 6.1b T16 — emitted row from [resolveArtifactsPerSide]. One
     * per side that successfully finalized for a P+L session. [file] is
     * the resolved artifact path (Tier 1: `_DATA`-resolved from the
     * per-side `pendingUri`; Tier 2/3: per-side `publicTargetPath`).
     * [shareUri] mirrors [resolveShareUri]: non-null only for Tier 1.
     */
    data class PerSideArtifact(
        val side: VideoSide,
        val file: File,
        val shareUri: String?
    )

    /**
     * Milestone 2 — per-segment artifact fanout for `MULTI_SEGMENT_KEPT` sessions.
     *
     * Phase 4.3 introduced `Terminated.MULTI_SEGMENT_KEPT` (ordinal 4) for
     * recovery-merge sessions whose merge failed and whose individual segments
     * are kept on disk. Milestone 2 surfaces those segments as flat 1-per-segment
     * library rows (each independently playable + deletable).
     *
     * For sessions terminated as `MULTI_SEGMENT_KEPT`: emits N entries where
     * N = `manifest.segments.size`. Each entry carries the bare segment
     * filename plus already-persisted segment metadata; the caller wraps the
     * filename with the session directory to obtain the playable path.
     *
     * For all other terminal states: emits an empty list. The caller falls
     * back to [resolveArtifactFile] (single-mode) or [resolveArtifactsPerSide]
     * (P+L) — same composition pattern as the existing fanout.
     *
     * Spec: `docs/superpowers/specs/2026-05-26-merge-reliability-bundle-design.md` §5 #4 + §6.4.
     */
    fun resolveArtifactsPerSegment(manifest: SessionManifest): List<PerSegmentArtifact> {
        if (manifest.terminated != Terminated.MULTI_SEGMENT_KEPT) return emptyList()
        return manifest.segments.mapIndexed { index, segment ->
            PerSegmentArtifact(
                sessionId = manifest.sessionId,
                segmentIndex = index,
                filename = segment.filename,
                durationMs = segment.durationMs,
                sizeBytes = segment.sizeBytes,
            )
        }
    }

    /**
     * Milestone 2 — emitted row from [resolveArtifactsPerSegment]. One per
     * kept segment in a `MULTI_SEGMENT_KEPT` session. Carries enough data
     * for the caller to construct a [VideoItem] + perform delete.
     *
     * [filename] is the bare segment filename (`segment_0.mp4`, etc.);
     * the caller wraps it with the session directory via
     * `File(sessionDir, filename)` to get the playable path.
     */
    data class PerSegmentArtifact(
        val sessionId: String,
        val segmentIndex: Int,
        val filename: String,
        val durationMs: Long,
        val sizeBytes: Long,
    )
}
