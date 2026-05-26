# ADR-0018 — Recovery merge retry, classifier, and preflight

**Status:** Proposed (Milestone 2)

**Date:** 2026-05-26

**Supersedes:** none. Extends ADR-0017.

## Context

Phase 4.3 (PR #48, ADR-0017) shipped service-hosted recovery merge with binary outcomes: `Terminated.COMPLETED` on `ExportResult.Success`, `Terminated.MULTI_SEGMENT_KEPT` (ordinal 4) on any other `ExportResult`. The C2.4 "Can't merge yet" sheet plus `WarningId.CANT_MERGE` (row #14) provide a user-facing manual-retry path from `MULTI_SEGMENT_KEPT`.

Field-quality reliability requires distinguishing transient device-flake failures (encoder jitter, I/O race) from permanent failures (manifest corruption, MediaStore denial), retrying transients with bounded backoff, handling storage exhaustion via user action rather than auto-retry spin, surfacing merge progress in the foreground-service notification, and gating the merge intent on storage headroom so we never start a merge we know will fail mid-flight.

ADR-0017 §Consequences enumerated five out-of-scope reliability gaps; this ADR addresses items 1+2+3+4 (library row enumeration, retry, preflight, notification progress). Item 5 (distinct "Continue without saving" outcome) remains parked per owner.

## Decision

Five concrete changes layered over Phase 4.3's `RovaRecordingService::performMerge` surface:

1. **`MergeFailureClass` sealed classifier** over `ExportResult`:
   - `Terminal` — `Success`. Write `COMPLETED, NONE`.
   - `Transient(cause)` — `MuxFailed | CopyFailed | RenameFailed`. Retry per `MergeRetryPolicy`.
   - `InsufficientStorage(requiredBytes)` — separate flow with notification action + storage-poll.
   - `Permanent(cause)` — `PendingInsertFailed | FinalizeFailed | ManifestWriteFailed | UnknownSession`. Write `MULTI_SEGMENT_KEPT, NONE` immediately, no retry.

2. **`MergeRetryPolicy`** — pure helper. `MAX_ATTEMPTS = 3`. `backoffMillisFor(1..3) = 1_000L | 4_000L | 16_000L`; attempts beyond 3 clamp at 16_000L (defensive — should never trigger). Exponential backoff is suspending `delay(...)`, not blocking sleep.

3. **`InsufficientStorage` tiered flow:**
   - Update notification to "Waiting for free space" with an action button (PendingIntent) that opens device storage settings.
   - Enter a `pollForStorage(requiredBytes, capMillis = 10 * 60_000)` loop polling `StorageManager.getAllocatableBytes` every 30 seconds.
   - On `available >= requiredBytes`: reset attempt counter, re-enter retry loop from `attempt = 1`.
   - On cap timeout: write `MULTI_SEGMENT_KEPT, NONE`, stop FGS.
   - User cancellation via swipe: stop FGS, manifest stays non-terminal, next recovery scan picks it up.

4. **Pre-flight storage gate** in `handleRecoveryMergeStart` BEFORE `startForegroundForRecoveryMerge`:
   - Compute `available = StorageManager.getAllocatableBytes(externalRoot)`.
   - Compute `accumulated = StorageEstimator.accumulatedSessionBytes(...)` (helper already exists).
   - If `available - accumulated < FINALIZE_HEADROOM_BYTES (50 MiB)`: raise existing `CANT_MERGE` signal (Phase 4.3), no FGS start, manifest non-terminal. User retriggers from C2.4 sheet after freeing space.

5. **FGS notification progress wire-up:**
   - `exportRecovered`'s existing `onProgress: (Float) -> Unit` callback fires per finalized segment.
   - Service-side `updateMergeNotification(MergeProgress)` rebuilds the notification with `setProgress(total, current, false)` + `setContentText("Merging X of Y")`.
   - Throttled to 500ms via `SystemClock.elapsedRealtime` guard.
   - Final tick (`current >= total`) bypasses throttle.

6. **Library row enumeration for `MULTI_SEGMENT_KEPT`:**
   - `HistoryArtifactMapper.manifestDrivenArtifacts(manifest): List<HistoryArtifact>` replaces singular `manifestDrivenArtifact(manifest): HistoryArtifact`.
   - For `Terminated.MULTI_SEGMENT_KEPT`: emit N `HistoryArtifact.SegmentClip(sessionId, segmentIndex, ...)` rows where N = `manifest.segments.size`.
   - For all other `Terminated` values: emit a single-element list wrapping the existing `FullSession` artifact.
   - `HistoryViewModel.buildItems` updates from `mapNotNull` to `flatMap`.
   - Delete handler keyed by `(sessionId, segmentIndex)` removes the segment file and updates the manifest; last-segment delete cascades to manifest deletion.

## Consequences

### Accepted

- **`RecoveryMerger.run` becomes a coroutine with embedded retry loop.** Attempt state lives in a local `Int`; `delay(backoffMillisFor(attempt))` is suspending. No new thread, no new dispatcher.
- **`MULTI_SEGMENT_KEPT` semantics shift.** Phase 4.3 wrote this on first failure; Milestone 2 writes it only after retry exhaustion (3 transients in a row), permanent failure, or storage-poll timeout. The data shape is unchanged (manifest carries segments list as before); the user-facing surface gains N library rows where previously there was 1.
- **`ExportResult` sealed-class consumers now include a classifier indirection.** Today `RovaRecordingService::performMerge` switches over `ExportResult` directly; post-Milestone 2, it switches over `classifyMergeFailure(result)`. Reviewer note: this is a one-layer indirection, not an inversion — the sealed class is still authoritative.
- **New test surface:** `RecoveryMergerRetryTest` requires a fake `exportRecovered` returning a programmable `Queue<ExportResult>` per attempt. Pattern is straightforward — the existing `RecoveryMerger` already takes the export pipeline as a dependency.
- **Notification builder grows three variants:** "merging X of Y" (default), "Retrying (attempt N of 3)…" (mid-retry), "Waiting for free space" (InsufficientStorage poll). All share a `mergeNotificationBuilder` helper.
- **`HistoryArtifact` sealed class gains a `SegmentClip` variant.** Existing `FullSession` variant unchanged; the new variant is additive. Delete handlers, player URI resolution, and row rendering must each handle the new variant. Phase 4.3 lesson applied: greppable, single sealed class with bounded consumer set.

### Rejected

- **Fixed retry, no separate storage flow.** Would treat `InsufficientStorage` like a transient flake; users blame the app for retrying invisibly while storage is full.
- **Adaptive jitter + circuit-break.** Resilience gain not measurably needed; jitter makes tests flaky; circuit-break detection logic adds complexity.
- **Warn-and-proceed preflight.** Doubles `InsufficientStorage` code paths; user has wasted retries before getting useful info.
- **Silent block + auto-poll without notification.** Hides why nothing is happening. UX antipattern.
- **Coroutine `Channel` for progress wire-up.** Direct callback simpler; existing `onProgress: (Float) -> Unit` already plumbed.
- **Discrete segment-boundary progress only.** Direct fraction is finer-grained and matches the existing callback signature.
- **Collapsible-group library rendering for `MULTI_SEGMENT_KEPT`.** Premature UI scope for rare edge case. Foldable on top of same data model if field reports show row-explosion.

### Out of scope (future work)

- **G — Distinct "Continue without saving" outcome from `Discard`.** Owner-parked. Today both route through `discardSession`; behavioural difference undefined. Unpark trigger: owner UX spec.
- **Mid-recording (not mid-merge) thermal interrupt.** Owner-parked. Unpark trigger: CRITICAL→OS-kill races in field reports.
- **Two-level thermal hysteresis.** Milestone 3.
- **Collapsible-group library rendering.** Conditional on field-report-driven need.

## Hard invariants preserved

- `WarningId` enum — 20 entries, ordinal pinning unchanged. `CANT_MERGE` row #14 reused; no new warning row added.
- `WarningPrecedence.resolve == allActive.firstOrNull()` invariant from Phase 4.2 — preserved.
- `Terminated` enum — 5 values, `MULTI_SEGMENT_KEPT` at ordinal 4 — unchanged.
- `StopReason` enum — 6 values — unchanged.
- `SessionStore::markTerminated` 3-arg atomic API — unchanged.
- `ExportResult` sealed-class arm list — unchanged (no new arms).
- Forbidden pair B16 (`USER_STOPPED + NONE`) — retry loop never writes this combination.
- ADR-0009 / ADR-0010 / ADR-0011 / ADR-0012 / ADR-0017 outputs — byte-identical.
- `EglRouter` / `AspectFitMath` / `DualVideoRecorder` / muxer / preview surfaces — untouched.
- Single-site recovery-scan trigger — untouched.
- `WarningCenterAggregateTest` / `WarningIdOrderTest` / `RecoveryScannerTest` / `AspectFitMath*Test` / `RecordingFrameLayoutTest` — byte-identical green.

## Implementation reference

See `docs/superpowers/specs/2026-05-26-merge-reliability-bundle-design.md` for the full design + test cases + acceptance criteria. Implementation plan to be drafted via `superpowers:writing-plans` after this ADR + spec are committed.

## Mockup files

None new. Reuses existing `mockups/new_uiux/09-notification-export.html` progress notification pattern and `mockups/new_uiux/03-history-library.html` row pattern. `mockups/` directory is gitignored.
