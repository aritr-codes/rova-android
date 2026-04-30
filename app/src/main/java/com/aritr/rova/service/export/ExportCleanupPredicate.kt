package com.aritr.rova.service.export

import com.aritr.rova.data.SessionManifest
import com.aritr.rova.data.SessionStore
import com.aritr.rova.service.recovery.DiscardEligibility
import com.aritr.rova.service.recovery.SessionClassification
import com.aritr.rova.utils.RovaLog

/**
 * Phase 1.7 commit-6 (ADR 0006 §"Ownership table" — cleanup gate).
 * Pure predicate that decides whether a session directory may be
 * physically deleted by the post-recovery cleanup pass. Lives under
 * `service/export/` rather than `service/recovery/` because:
 *  - The Phase 1.5 lint `checkRecoveryNoDeletion` forbids `.delete(`,
 *    `.deleteRecursively(`, and `.discardSession(` in `RovaApp.kt`
 *    and `service/recovery/`. Cleanup ownership is a Phase 1.7
 *    responsibility per ADR 0006; the deletion call site lives here
 *    so RovaApp can orchestrate without holding a deletion API
 *    reference itself.
 *  - The four predicate gates straddle Phase 1.5 (eligibility) and
 *    Phase 1.7 (export-clean, sweep-clean) signals — keeping the gate
 *    in a single file makes the load-bearing combination auditable.
 *
 * **Four gates, ALL must hold for delete to proceed** (commit-6 NO-GO
 * patch — Blocker 2):
 *
 *  1. **Phase 1.5 eligibility = `AUTO_DISCARD_ELIGIBLE`** — the
 *     classifier saw no surviving segments / orphans / anomalies and
 *     a terminal value is set.
 *  2. **`manifest.privateTempPath == null`** — Tier 1 never sets it;
 *     Tier 2/3 happy path clears it after `setMediaScanCompleted`. A
 *     non-null value means a private temp file may still be on disk
 *     (deferred-scan retry path), which a session-dir delete would
 *     destroy.
 *  3. **Per-session export recovery is terminal-clean.** Specifically
 *     NOT [RecoveryResult.RetryableFailure] (post-validate seam
 *     failure that recovery wants the next cold launch to retry —
 *     deleting the manifest now would forfeit the retry signal) and
 *     NOT [RecoveryResult.ManifestWriteFailed] (manifest write
 *     exhausted retries; the on-disk manifest may be in an
 *     intermediate state). [RecoveryResult.Resumed],
 *     [RecoveryResult.Abandoned], [RecoveryResult.UnknownSession],
 *     and "session was skipped (no recovery needed)" all pass this
 *     gate.
 *  4. **Tier 1 orphan sweep returned `Swept`, not `QueryFailed`.**
 *     [OrphanSweepResult.QueryFailed] means the listing seam threw —
 *     pending-row reference safety is unknown, and a manifest we'd
 *     delete might still be the only reference protecting an
 *     unsweepable pending row. The conservative implementation
 *     (per the commit-6 NO-GO patch's amendment) skips ALL physical
 *     cleanup when the sweep is `QueryFailed` — not just Tier-1-
 *     scoped cleanup, because we cannot prove the sweep failure was
 *     scoped.
 *
 * The lint `checkExportCleanupPredicate` enforces the source-text
 * presence of all four gate tokens (`AUTO_DISCARD_ELIGIBLE`,
 * `privateTempPath`, `RetryableFailure` + `ManifestWriteFailed`,
 * `QueryFailed`). It cannot enforce semantic correctness, but the
 * tokens being present documents that the predicate covers each
 * load-bearing dimension.
 */
internal object ExportCleanupPredicate {

    /**
     * Pure decision: does the cleanup pass have permission to delete
     * `videos/<sessionId>/`?
     *
     * @param classification Phase 1.5 output for this session.
     * @param manifest the post-recovery manifest snapshot. (The
     *   pre-recovery manifest is the wrong input — recovery may have
     *   cleared `privateTempPath` via `setExportFailed`.)
     * @param recoveryResult the per-session [RecoveryResult] from
     *   [ExportRecoveryRunner.run] — `null` if the session did not
     *   enter tier-recovery (FAILED / NOT_STARTED / FINALIZED with
     *   cleared private temp). `null` passes gate 3.
     * @param sweepResult the Tier 1 orphan sweep result. `Swept`
     *   passes gate 4; `QueryFailed` blocks.
     */
    fun shouldDelete(
        classification: SessionClassification,
        manifest: SessionManifest,
        recoveryResult: RecoveryResult?,
        sweepResult: OrphanSweepResult
    ): Boolean {
        // Gate 1 — Phase 1.5 eligibility.
        if (classification.eligibility != DiscardEligibility.AUTO_DISCARD_ELIGIBLE) {
            return false
        }
        // Gate 2 — no privateTempPath retained (Tier 2/3 deferred-scan
        // retry path needs the file to remain).
        if (manifest.privateTempPath != null) {
            return false
        }
        // Gate 3 — per-session export recovery terminal-clean. RetryableFailure
        // and ManifestWriteFailed must both block; everything else (Resumed,
        // Abandoned, UnknownSession, no-recovery-dispatched=null) passes.
        if (recoveryResult is RecoveryResult.RetryableFailure) return false
        if (recoveryResult is RecoveryResult.ManifestWriteFailed) return false
        // Gate 4 — sweep didn't fail. QueryFailed blocks ALL physical cleanup
        // this run (per commit-6 NO-GO patch — pending-row reference safety
        // is unknown when the listing seam throws).
        if (sweepResult is OrphanSweepResult.QueryFailed) return false
        return true
    }

    /**
     * Run the cleanup pass over every classified session. Iterates in
     * map order, applies [shouldDelete], and discards the dir via
     * [SessionStore.discardSession] for sessions that pass. Returns the
     * list of deleted session ids for log/diagnostic emission.
     *
     * Any classifications whose manifest fails to load are skipped
     * (the session may have been deleted out-of-band; nothing to do).
     * The cleanup pass NEVER modifies a manifest — `discardSession` is
     * a recursive directory delete, not a manifest mutation.
     */
    fun runCleanupPass(
        sessionStore: SessionStore,
        classifications: Map<String, SessionClassification>,
        report: ExportRecoveryReport
    ): List<String> {
        // Sweep gate is global — bail out before per-session work to
        // avoid logging spurious per-session "skipped" messages.
        if (report.sweep is OrphanSweepResult.QueryFailed) {
            RovaLog.d(
                "ExportCleanupPredicate: sweep was QueryFailed; " +
                    "skipping all physical cleanup this run"
            )
            return emptyList()
        }
        val deleted = mutableListOf<String>()
        for ((sessionId, classification) in classifications) {
            val manifest = sessionStore.loadManifest(sessionId) ?: continue
            val recoveryResult = report.perSession[sessionId]
            if (shouldDelete(classification, manifest, recoveryResult, report.sweep)) {
                sessionStore.discardSession(sessionId)
                deleted += sessionId
                RovaLog.d("ExportCleanupPredicate: discarded $sessionId (post-recovery cleanup)")
            }
        }
        return deleted
    }
}
