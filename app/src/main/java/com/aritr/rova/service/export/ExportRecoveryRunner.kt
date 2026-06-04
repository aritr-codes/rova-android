package com.aritr.rova.service.export

import com.aritr.rova.data.ExportState
import com.aritr.rova.data.MarkTerminatedResult
import com.aritr.rova.data.SessionManifest
import com.aritr.rova.data.SessionStore
import com.aritr.rova.data.StopReason
import com.aritr.rova.data.Terminated
import com.aritr.rova.utils.RovaLog

/**
 * Phase 1.7 commit-6 (ADR 0006 §"Ownership table" — cross-phase ordering).
 * Single entry point for cold-launch export recovery: snapshots
 * referenced pending URIs, dispatches per-session recovery to the
 * tier-correct exporter, runs a late-terminal reconciliation pass for
 * sessions whose `markTerminated(COMPLETED)` write was lost, then runs
 * the Tier 1 orphan sweep against the snapshot. Designed to run BEFORE
 * the Phase 1.5 classifier
 * ([com.aritr.rova.service.recovery.RecoveryScanner]) so the classifier
 * sees the post-recovery manifest state when it builds discard
 * eligibility flags.
 *
 * **Snapshot-before-mutation invariant** (commit-6 NO-GO patch round 1
 * — Blocker 1). The set of `pendingUri` values that the orphan sweep
 * must NOT delete is captured BEFORE per-session recovery runs.
 * Per-session recovery can call `setExportFailed`, which clears
 * `pendingUri`; if the sweep used a post-recovery snapshot, an
 * in-flight pending row whose manifest just transitioned to FAILED
 * would be orphaned to the sweep and deleted, violating the sweep's
 * "no delete of referenced rows" invariant on every recovery-failure
 * path.
 *
 * The snapshot deliberately does NOT filter by `exportState` —
 * "referenced" means *any* manifest references the URI, not "only
 * non-FAILED manifests". A legacy or partially-corrupt manifest at
 * `exportState = FAILED` whose `pendingUri` field is still populated
 * still protects that row from the sweep. The conservative reading is
 * load-bearing: filtering by state could re-introduce the same race the
 * snapshot was designed to prevent.
 *
 * **Snapshot-incomplete short-circuit** (commit-6 NO-GO patch round 2
 * — Blocker 2). [SessionStore.loadManifest] returns `null` on JSON
 * parse failure (the catch is internal to the store). If a corrupt
 * manifest contained the only `pendingUri` reference to a valid pending
 * row, the snapshot would silently omit it and the sweep would treat
 * the row as orphan and delete user data. The runner detects every
 * load failure during snapshot — `null` return OR throw — and routes
 * the sweep to [OrphanSweepResult.QueryFailed] without invoking the
 * underlying listing seam. `QueryFailed` propagates through
 * [ExportCleanupPredicate.runCleanupPass]'s short-circuit, so cleanup
 * is also blocked for the run. Next cold launch retries.
 *
 * **Per-session dispatch.** Manifests in `MUXING` / `COPYING` are
 * always recovered (the export pipeline was interrupted mid-flight).
 * Manifests in `FINALIZED` are recovered iff `privateTempPath != null`
 * — the deferred-scan reconciliation path on Tier 2/3 (per
 * [PreQExportCore]) needs to re-fire the scan and clean up the private
 * temp file. `FINALIZED` with `privateTempPath = null` is fully done
 * by the exporter; the late-terminal pass below picks up any lost
 * `markTerminated(COMPLETED)` write. `FAILED` and `NOT_STARTED` never
 * enter recovery — `FAILED` is a terminal state that requires explicit
 * user retry per ADR 0006 §"Ownership table"; `NOT_STARTED` never
 * started exporting.
 *
 * **Late-terminal reconciliation** (commit-6 NO-GO patch round 2 —
 * Blocker 1). ADR 0006 §"Terminal-Write Ordering" (B7): `COMPLETED`
 * is written after merge succeeds. The exporter's `recover()` does NOT
 * write the terminal value; per ADR 0006 the runner/integration layer
 * owns that decision. After per-session dispatch, the runner re-reads
 * every manifest and writes `markTerminated(COMPLETED, NONE)` for any
 * session in `exportState = FINALIZED && terminated == null` whose
 * tier artifact is intact (Tier 1: `validatePending(pendingUri)`;
 * Tier 2/3: `publicTargetPath` exists with non-zero length).
 *
 * The pass covers TWO failure modes uniformly:
 *  - A session resumed by [recoverSession] this launch — Tier 1
 *    `RecoveryResult.Resumed(Success)` writes `setExportFinalized` but
 *    the runner is the only writer that can issue `markTerminated`.
 *  - A session that was already in `FINALIZED` from a prior launch —
 *    the prior launch's `markTerminated` write was lost in a crash;
 *    [com.aritr.rova.service.recovery.RecoveryScanner] would otherwise
 *    classify it as `SKIPPED_EXPORT_PENDING` and the row would strand
 *    forever (row 13c in ADR 0005's matrix).
 *
 * If the artifact validation fails, the manifest stays at
 * `terminated = null` and the next cold launch retries. The seam
 * [validateTierArtifact] is given the manifest (not just the URI/path)
 * so the seam can dispatch by `manifest.exportTier`.
 *
 * **Tier dispatch travels with the manifest.** Per ADR 0003 §"Recovery
 * routing", recovery uses `manifest.exportTier` (frozen at session
 * start), not `Build.VERSION.SDK_INT` of the running build. The
 * [recoverSession] and [validateTierArtifact] seams consume the frozen
 * tier so a downgrade scenario (e.g., schema-2 manifest with no
 * `exportTier` defaulting to the running build's tier — see
 * [SessionManifest.fromJson]) still routes to a code path the running
 * build can execute.
 *
 * **Orphan sweep.** Tier 1 only — `MediaStore.IS_PENDING` is API 29+.
 * On pre-Q boots, [orphanSweep] is `null` and the runner returns a
 * synthetic empty [OrphanSweepResult.Swept]. The sweep consumes the
 * snapshot built in step 1 — never a recomputed set.
 */
class ExportRecoveryRunner(
    private val sessionStore: SessionStore,
    private val recoverSession: suspend (SessionManifest) -> RecoveryResult,
    private val validateTierArtifact: suspend (SessionManifest) -> Boolean,
    private val orphanSweep: (suspend (referencedPendingUris: Set<String>) -> OrphanSweepResult)?,
    // B5 / ADR-0025 — vault re-merge seam. Wired in RovaApp to
    // `ExportPipeline.exportRecovered(..., vaultIntent = true)` so a
    // [VaultRecoveryAction.MERGE_TO_VAULT] session re-merges to the
    // app-private vault and never publishes. Threading vault intent
    // THROUGH `exportRecovered` (not calling `export(` here) keeps the
    // `checkExportPipelineSingleEntry` invariant intact. Optional with a
    // `null` default so existing callers/tests that never exercise the
    // vault path compile unchanged — a `null` seam means the vault
    // re-merge is UNWIRED, which is treated as "skip" (never publish via
    // [recoverSession]), per codex review.
    private val recoverVaultSession: (suspend (SessionManifest) -> RecoveryResult)? = null
) {

    /**
     * Run the export-recovery pass. Returns the per-session results, the
     * snapshotted referenced-URI set (for log/diagnostic correlation),
     * the late-terminal reconciliation outcomes, and the sweep outcome.
     * Idempotent across cold launches: a second run on the same on-disk
     * state produces no new mutations because every step is driven by
     * manifest pointers + on-disk artifacts (both invariant under
     * repeated reads).
     */
    suspend fun run(): ExportRecoveryReport {
        // Step 1 — snapshot referenced pending URIs BEFORE any mutation.
        // No exportState filter; FAILED manifests still protect their rows.
        // Detect every parse failure (null return OR throw) so the sweep
        // can short-circuit if any reference might be missing.
        val sessionIds = sessionStore.listSessionIds()
        val manifests = mutableListOf<SessionManifest>()
        var snapshotIncomplete = false
        for (sid in sessionIds) {
            val m: SessionManifest? = try {
                sessionStore.loadManifest(sid)
            } catch (t: Throwable) {
                RovaLog.w("$TAG: loadManifest threw for $sid during snapshot; marking snapshot incomplete", t)
                snapshotIncomplete = true
                null
            }
            if (m == null) {
                // SessionStore.loadManifest returns null on parse failure
                // (the store catches Exception internally), AND on missing
                // / empty file. Either way the snapshot may be missing a
                // pendingUri that protects a real row — the sweep cannot
                // safely run.
                RovaLog.w("$TAG: loadManifest returned null for $sid; snapshot incomplete")
                snapshotIncomplete = true
                continue
            }
            manifests += m
        }
        val referencedPendingUris: Set<String> = manifests
            .mapNotNull { it.pendingUri }
            .toSet()
        RovaLog.d(
            "$TAG: snapshot complete (manifests=${manifests.size}, " +
                "referencedPendingUris=${referencedPendingUris.size}, " +
                "incomplete=$snapshotIncomplete)"
        )

        // Step 2 — per-session recovery dispatch.
        //
        // B5 / ADR-0025 — vault-aware routing keys off vault membership,
        // never exportTier. Per session we first classify the vault
        // dimension via [vaultRecoveryAction]:
        //  - MERGE_TO_VAULT → re-merge to the app-private vault via the
        //    [recoverVaultSession] seam (vaultIntent=true through
        //    exportRecovered). It must NEVER fall through to
        //    [recoverSession] (tier-recovery PUBLISHES). If the seam is
        //    unwired (null), skip — a vault session must not be published.
        //  - RESUME_VAULTING / RESUME_UNVAULTING → an in-flight move was
        //    interrupted; the move executors land in a later phase. Skip
        //    (never publish a half-moved session).
        //  - NONE → existing tier-recovery dispatch via [recoverSession].
        val perSession = LinkedHashMap<String, RecoveryResult>()
        for (m in manifests) {
            if (!needsExportRecovery(m)) continue
            val vaultAction = vaultRecoveryAction(
                vaultIntentAtStart = m.vaultIntentAtStart,
                state = m.vaultState,
                finalized = m.exportState == ExportState.FINALIZED
            )
            when (vaultAction) {
                VaultRecoveryAction.RESUME_VAULTING, VaultRecoveryAction.RESUME_UNVAULTING -> {
                    // TODO(Phase 6 / VaultMover): resume interrupted move (finishVaulting / finishUnvaulting). Until then, skip to avoid mis-publishing.
                    RovaLog.d(
                        "$TAG: ${m.sessionId} vaultState=${m.vaultState} (in-flight move) → " +
                            "deferring move-resume to VaultMover; skipping recovery this launch"
                    )
                    continue
                }
                VaultRecoveryAction.MERGE_TO_VAULT -> {
                    val vaultSeam = recoverVaultSession
                    if (vaultSeam == null) {
                        // Unwired vault seam. Skipping is the safe outcome:
                        // routing through `recoverSession` would PUBLISH a
                        // vault-intent recording (the exact bug to avoid).
                        RovaLog.w(
                            "$TAG: ${m.sessionId} needs MERGE_TO_VAULT but recoverVaultSession " +
                                "is unwired; skipping to avoid publishing a vault-intent session"
                        )
                        continue
                    }
                    val result = try {
                        vaultSeam(m)
                    } catch (t: Throwable) {
                        RovaLog.w("$TAG: recoverVaultSession threw for ${m.sessionId}", t)
                        RecoveryResult.RetryableFailure("recoverVaultSession", t)
                    }
                    perSession[m.sessionId] = result
                }
                VaultRecoveryAction.NONE -> {
                    val result = try {
                        recoverSession(m)
                    } catch (t: Throwable) {
                        // A throw from the tier dispatcher itself (not RecoveryResult-
                        // wrapped) is treated as RetryableFailure so cleanup gating
                        // suppresses physical deletion of the dir. The next cold
                        // launch will retry.
                        RovaLog.w("$TAG: recoverSession threw for ${m.sessionId}", t)
                        RecoveryResult.RetryableFailure("recoverSession", t)
                    }
                    perSession[m.sessionId] = result
                }
            }
        }
        RovaLog.d("$TAG: per-session recovery dispatched for ${perSession.size} session(s)")

        // Step 3 — late-terminal reconciliation. Re-read every session
        // because per-session recovery may have just transitioned a
        // manifest into FINALIZED. For sessions in
        // `terminated == null && exportState == FINALIZED`, validate the
        // artifact and write `markTerminated(COMPLETED, NONE)` if intact.
        // Failures leave terminated == null (next launch retries); the
        // pass never deletes anything.
        val lateTerminals = LinkedHashMap<String, LateTerminalAction>()
        for (sid in sessionIds) {
            val m = try {
                sessionStore.loadManifest(sid)
            } catch (t: Throwable) {
                RovaLog.w("$TAG: late-terminal: loadManifest threw for $sid", t)
                null
            }
            if (m == null) continue
            if (m.terminated != null) continue
            if (m.exportState != ExportState.FINALIZED) continue

            val artifactOk = try {
                validateTierArtifact(m)
            } catch (t: Throwable) {
                RovaLog.w("$TAG: late-terminal: validateTierArtifact threw for $sid", t)
                false
            }
            if (!artifactOk) {
                RovaLog.d(
                    "$TAG: late-terminal: $sid artifact missing/invalid; " +
                        "leaving terminated=null for next-launch retry"
                )
                lateTerminals[sid] = LateTerminalAction.SkippedArtifactInvalid
                continue
            }
            // Per ADR 0006 §"Migration table": COMPLETED writers pass
            // StopReason.NONE. Per `checkAtomicTerminalWriteForbiddenPair`
            // lint the (USER_STOPPED, NONE) pair is forbidden — but the
            // (COMPLETED, NONE) pair is the canonical terminal write and
            // is not flagged.
            // completed-write-opt-out: late-terminal reconciliation for
            // FINALIZED && terminated == null (ADR 0005 row 13c) — runner
            // owns this write per ADR 0006 §"Ownership table"; the live
            // merge-success path in performMerge is the other COMPLETED
            // writer.
            val r = sessionStore.markTerminated(sid, Terminated.COMPLETED, StopReason.NONE)
            lateTerminals[sid] = when (r) {
                is MarkTerminatedResult.Wrote -> {
                    RovaLog.d("$TAG: late-terminal: wrote COMPLETED for $sid (lost markTerminated recovery)")
                    LateTerminalAction.WroteCompleted
                }
                is MarkTerminatedResult.AlreadyTerminal -> {
                    // Race: another writer (live merge-success path) snuck
                    // in between our check and the write. AlreadyTerminal
                    // is acceptable — the existing terminal stays and we
                    // record the no-op.
                    RovaLog.d(
                        "$TAG: late-terminal: $sid already terminal " +
                            "(${r.existingTerminated}/${r.existingStopReason}); no-op"
                    )
                    LateTerminalAction.AlreadyTerminal
                }
                is MarkTerminatedResult.Failed -> {
                    RovaLog.w(
                        "$TAG: late-terminal: markTerminated failed for $sid " +
                            "(attempts=${r.attempts}); will retry next cold launch",
                        r.cause
                    )
                    LateTerminalAction.WriteFailed
                }
            }
        }
        RovaLog.d("$TAG: late-terminal reconciliation complete (${lateTerminals.size} session(s) inspected)")

        // Step 4 — Tier 1 orphan sweep against the SNAPSHOT (never a
        // post-recovery recomputation). On snapshot-incomplete, skip the
        // listing seam entirely and return QueryFailed — pending-row
        // reference safety is unknown when any manifest failed to load.
        // On pre-Q boots (no [orphanSweep]), return synthetic empty Swept.
        val sweep: OrphanSweepResult = when {
            snapshotIncomplete -> {
                val cause = IllegalStateException(
                    "snapshot incomplete: one or more manifests failed to load; " +
                        "skipping sweep to avoid deleting potentially-referenced rows"
                )
                RovaLog.w("$TAG: snapshot incomplete → QueryFailed (skipping sweep)")
                OrphanSweepResult.QueryFailed(cause)
            }
            orphanSweep == null -> OrphanSweepResult.Swept(
                deleted = 0,
                retainedReferenced = 0,
                retainedOtherPackage = 0,
                deleteFailures = 0
            )
            else -> try {
                orphanSweep.invoke(referencedPendingUris)
            } catch (t: Throwable) {
                RovaLog.w("$TAG: orphanSweep threw", t)
                OrphanSweepResult.QueryFailed(t)
            }
        }

        return ExportRecoveryReport(
            referencedPendingUris = referencedPendingUris,
            perSession = perSession,
            lateTerminals = lateTerminals,
            sweep = sweep
        )
    }

    private fun needsExportRecovery(m: SessionManifest): Boolean = when (m.exportState) {
        ExportState.MUXING, ExportState.COPYING -> true
        // FINALIZED with privateTempPath != null is the Tier 2/3
        // deferred-scan path — re-fire scanFile + delete the private temp.
        // FINALIZED with privateTempPath == null is fully done by the
        // exporter; the late-terminal pass writes `markTerminated` if
        // needed. Dispatching here would no-op or risk false abandons.
        ExportState.FINALIZED -> m.privateTempPath != null
        ExportState.FAILED, ExportState.NOT_STARTED -> false
    }

    private companion object {
        const val TAG = "ExportRecoveryRunner"
    }
}

/**
 * Phase 1.7 commit-6 — output of [ExportRecoveryRunner.run]. Consumed
 * by [ExportCleanupPredicate.shouldDelete] to gate physical session-dir
 * cleanup, and by callers (`RovaApp`) for log/diagnostic emission.
 *
 * - [referencedPendingUris] — snapshot taken BEFORE per-session
 *   recovery ran. Used to verify the orphan sweep ran against the
 *   correct set, never a post-recovery recomputation.
 * - [perSession] — `sessionId -> RecoveryResult` for every session that
 *   entered tier-recovery. Sessions skipped because they were
 *   `FAILED` / `NOT_STARTED` / `FINALIZED` with cleared private temp do
 *   NOT appear in the map.
 * - [lateTerminals] — `sessionId -> LateTerminalAction` for every
 *   session inspected by the late-terminal reconciliation pass
 *   (post-recovery, `terminated == null && exportState == FINALIZED`).
 *   Sessions that did not match the gate do NOT appear.
 * - [sweep] — Tier 1 orphan sweep result, or a synthetic empty
 *   [OrphanSweepResult.Swept] on pre-Q boots, or
 *   [OrphanSweepResult.QueryFailed] when the snapshot was incomplete.
 *   `QueryFailed` blocks all cleanup this run.
 */
data class ExportRecoveryReport(
    val referencedPendingUris: Set<String>,
    val perSession: Map<String, RecoveryResult>,
    val lateTerminals: Map<String, LateTerminalAction>,
    val sweep: OrphanSweepResult
)

/**
 * Phase 6.1b T14 — combine two per-side [RecoveryResult]s into a
 * single conservative outcome. Used by [RovaApp]'s `recoverSession`
 * lambda when dispatching P+L (PortraitLandscape) recovery: the lambda
 * calls the tier-correct exporter's `recover(m, side = PORTRAIT)` and
 * `recover(m, side = LANDSCAPE)`, then folds the two results through
 * this helper before returning a single [RecoveryResult] to
 * [ExportRecoveryRunner].
 *
 * Precedence (most-conservative wins) — see the test cases in
 * `ExportRecoveryRunnerTest` for the canonical truth table:
 *
 * 1. **[RecoveryResult.RetryableFailure]** — "transient, try again next
 *    cold launch". Strongest signal because it asks the runner to leave
 *    the on-disk row untouched and skip cleanup gating; any side
 *    asking for a retry wins.
 * 2. **[RecoveryResult.ManifestWriteFailed]** — "manifest mutation
 *    failed". The runner leaves the session in place; next cold launch
 *    retries. Beats success/abandoned but not retryable.
 * 3. **[RecoveryResult.UnknownSession]** — "manifest disappeared
 *    mid-recovery". Treated as a failure mode for the combined session
 *    so cleanup gating sees a failure signal and skips physical
 *    deletion; beats success/abandoned.
 * 4. **[RecoveryResult.Resumed]** — at least one side recovered.
 *    Returned when no failure mode is present and at least one input
 *    is Resumed. If both are Resumed, the first input wins (the wrapped
 *    [ExportResult] semantics are identical when both succeed).
 * 5. **[RecoveryResult.Abandoned]** — both sides had nothing usable
 *    and neither side's per-side cleanup hit a manifest write error.
 *
 * The combinator never inspects the wrapped [ExportResult] payloads —
 * per-side details remain in the manifest (per-side fields populated by
 * T11 + T12) and are recoverable from disk by the next cold launch if
 * needed. Single-mode recovery never calls this combinator; it stays
 * byte-identical to the pre-T14 path.
 */
internal fun combineRecoveryResults(
    portrait: RecoveryResult,
    landscape: RecoveryResult
): RecoveryResult {
    // 1 — RetryableFailure wins over everything (most conservative:
    //     ask the runner to leave the row and try again next launch).
    if (portrait is RecoveryResult.RetryableFailure) return portrait
    if (landscape is RecoveryResult.RetryableFailure) return landscape
    // 2 — ManifestWriteFailed: leave session in place; next launch retry.
    if (portrait is RecoveryResult.ManifestWriteFailed) return portrait
    if (landscape is RecoveryResult.ManifestWriteFailed) return landscape
    // 3 — UnknownSession: surface as a non-success outcome.
    if (portrait is RecoveryResult.UnknownSession) return portrait
    if (landscape is RecoveryResult.UnknownSession) return landscape
    // 4 — Resumed: at least one side succeeded. Portrait first if both.
    if (portrait is RecoveryResult.Resumed) return portrait
    if (landscape is RecoveryResult.Resumed) return landscape
    // 5 — Both Abandoned (the only remaining case).
    return RecoveryResult.Abandoned
}

/**
 * Phase 1.7 commit-6 — outcome of the late-terminal reconciliation
 * pass for a single session. Distinct from [RecoveryResult] because
 * the late-terminal pass is a runner-level write, not an exporter
 * dispatch — recovery's outcome dimensions (Resumed / Abandoned /
 * RetryableFailure / ManifestWriteFailed) do not apply here.
 *
 * - [WroteCompleted] — `markTerminated(COMPLETED, NONE)` succeeded.
 * - [AlreadyTerminal] — the manifest was already terminal at the
 *   moment of write (race with another terminal writer; acceptable).
 * - [SkippedArtifactInvalid] — the tier artifact validator returned
 *   `false` or threw; the manifest stays `terminated = null` and the
 *   next cold launch retries.
 * - [WriteFailed] — `markTerminated`'s 3-attempt retry budget was
 *   exhausted; the manifest may be in an intermediate state. Next
 *   cold launch retries.
 */
enum class LateTerminalAction {
    WroteCompleted,
    AlreadyTerminal,
    SkippedArtifactInvalid,
    WriteFailed
}
