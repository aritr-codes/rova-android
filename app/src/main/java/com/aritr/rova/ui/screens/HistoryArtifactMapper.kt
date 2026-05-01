package com.aritr.rova.ui.screens

import com.aritr.rova.data.ExportState
import com.aritr.rova.data.ExportTier
import com.aritr.rova.data.SessionManifest
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
     * gate — a session can be `FINALIZED` with `terminated == null` for
     * one cold-launch tick before [com.aritr.rova.service.export.ExportRecoveryRunner]'s
     * late-terminal pass writes `markTerminated(COMPLETED, NONE)`. The
     * artifact is on disk; the user expects to see it. The terminal
     * record catches up out-of-band.
     */
    fun finalizedManifests(manifests: List<SessionManifest>): List<SessionManifest> =
        manifests.filter { it.exportState == ExportState.FINALIZED }

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
    }
}
