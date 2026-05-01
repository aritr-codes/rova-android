package com.aritr.rova.service.export

import com.aritr.rova.data.ExportState
import com.aritr.rova.data.ExportTier
import com.aritr.rova.data.SessionManifest

/**
 * Phase 2 Slice 2.3b — pure dispatcher for the user-initiated
 * "Saved locally — Copy to gallery" affordance on Tier 2 / Tier 3
 * exports.
 *
 * The actual no-remux retry logic already lives in
 * [PreQExportCore.recover] (Phase 1.7 commit-3, ADR 0003 §"Recovery
 * routing"): Case A re-fires `MediaScanWaiter.scanAndWait` against an
 * existing public artifact, Case C re-runs publish-and-finalize from
 * a surviving `privateTempPath` via `.part` rename. This runner adds
 * three things on top of that substrate:
 *
 *  1. **Eligibility gating** ([eligibility]) — Tier 1 manifests, the
 *     `FAILED` state with cleared pointers, and the already-scanned
 *     happy-path are all rejected before any tier dispatch runs. The
 *     gate is a pure data-only function so the UI layer (a future
 *     wire-up slice) can decide whether to surface the affordance
 *     without entering a coroutine.
 *
 *  2. **Tier dispatch** ([retry]) — Tier 2 manifests route to
 *     [Tier2Exporter.recover]; Tier 3 manifests route to
 *     [Tier3Exporter.recover]. The exporters are injected as suspend
 *     lambdas so this runner stays unit-testable on the JVM without
 *     touching `Context` / `MediaScannerConnection`.
 *
 *  3. **Result wrapping** ([RetryOutcome]) — distinguishes
 *     "ineligible, did not run" from "ran, here is the
 *     [RecoveryResult]" so the UI layer can react without re-checking
 *     the eligibility predicate.
 *
 * Tier 1 is intentionally rejected: Tier 1 publishes via MediaStore
 * `IS_PENDING` and uses no `MediaScannerConnection.scanFile` call to
 * re-fire. A Tier 1 retry surface, if needed, is a separate slice
 * (2.3a) with a different state shape (`pendingUri` instead of
 * `privateTempPath` + `publicTargetPath`).
 *
 * `FAILED` is intentionally rejected: [PreQExportCore.cleanupOnFailure]
 * deletes the private temp + .part files before
 * [com.aritr.rova.data.SessionStore.setExportFailed] clears every
 * pointer field, so there is no on-disk state to retry from without
 * re-muxing the segments. Re-mux is explicitly out of scope for this
 * affordance.
 *
 * The runner does NOT enforce single-entry against a concurrent
 * [ExportRecoveryRunner.run] — that is the wire-up slice's job (UI
 * gates the button behind a "cold-launch recovery completed" flag).
 * Manifest writes are serialized per-session by `SessionStore`, so
 * substrate-level safety is preserved even under concurrent calls.
 */
internal class PreQExportRetryRunner(
    private val recoverTier2: suspend (SessionManifest) -> RecoveryResult,
    private val recoverTier3: suspend (SessionManifest) -> RecoveryResult
) {

    /**
     * Pure eligibility gate. Returns [RetryEligibility.Eligible] iff
     * the manifest names a Tier 2 / Tier 3 deferred-scan state with
     * both pointers retained and `mediaScanCompleted == false`.
     *
     * Note: `terminated == null` is NOT a substrate-level
     * disqualifier. The wire-up slice may choose to hide the
     * affordance until [com.aritr.rova.data.SessionManifest.terminated]
     * is set (typically by the next cold launch's late-terminal
     * pass), but recover() itself is safe to invoke either way.
     */
    fun eligibility(manifest: SessionManifest): RetryEligibility = when {
        manifest.exportTier == ExportTier.TIER1_API29_PLUS ->
            RetryEligibility.WrongTier
        manifest.exportState != ExportState.FINALIZED ->
            RetryEligibility.WrongState(manifest.exportState)
        manifest.privateTempPath == null ->
            RetryEligibility.NoPrivateTemp
        manifest.publicTargetPath == null ->
            RetryEligibility.NoPublicTarget
        manifest.mediaScanCompleted ->
            RetryEligibility.AlreadyScanned
        else ->
            RetryEligibility.Eligible
    }

    /**
     * Run the retry. Ineligible manifests return
     * [RetryOutcome.Ineligible] without invoking either exporter
     * lambda; eligible Tier 2 / Tier 3 manifests dispatch to the
     * matching `recover` and wrap the result in [RetryOutcome.Ran].
     */
    suspend fun retry(manifest: SessionManifest): RetryOutcome {
        val gate = eligibility(manifest)
        if (gate != RetryEligibility.Eligible) {
            return RetryOutcome.Ineligible(gate)
        }
        val result = when (manifest.exportTier) {
            ExportTier.TIER2_API26_28 -> recoverTier2(manifest)
            ExportTier.TIER3_API24_25 -> recoverTier3(manifest)
            ExportTier.TIER1_API29_PLUS ->
                error("PreQExportRetryRunner.retry: Tier 1 reached dispatch — eligibility gate is broken")
        }
        return RetryOutcome.Ran(result)
    }
}

/**
 * Why a given manifest is or is not eligible for the user-initiated
 * Copy-to-gallery affordance. Pure data — the wire-up slice maps
 * each variant to a UI state.
 */
sealed class RetryEligibility {
    object Eligible : RetryEligibility()
    object WrongTier : RetryEligibility()
    data class WrongState(val state: ExportState) : RetryEligibility()
    object NoPrivateTemp : RetryEligibility()
    object NoPublicTarget : RetryEligibility()
    object AlreadyScanned : RetryEligibility()
}

/**
 * Output of [PreQExportRetryRunner.retry]. [Ineligible] carries the
 * gate reason for caller diagnostics; [Ran] wraps the exporter's
 * [RecoveryResult] verbatim — the wire-up slice maps Resumed /
 * Abandoned / UnknownSession / ManifestWriteFailed / RetryableFailure
 * to UI state.
 */
sealed class RetryOutcome {
    data class Ineligible(val reason: RetryEligibility) : RetryOutcome()
    data class Ran(val result: RecoveryResult) : RetryOutcome()
}
