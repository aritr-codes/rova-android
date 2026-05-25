# ADR-0017 — Phase 4.3: Recovery merge architecture (service-hosted, ExportPipeline-routed, signal-driven outcomes)

**Status:** Accepted
**Date:** 2026-05-25
**Supersedes:** none
**Related ADRs:** ADR-0003 (Tier1 FD mode), ADR-0006 (atomic terminal-write), ADR-0007 (record warning sheets), ADR-0013 (warning re-skin v3 chrome canon), ADR-0015 (storage-full autostopped echo), ADR-0016 (thermal autostop)
**Spec:** `docs/superpowers/specs/2026-05-25-phase-4-3-recovery-merge-design.md`

## Context

Recovery sessions (`Terminated ∈ {USER_STOPPED, KILLED_BY_SYSTEM, KILLED_FORCE_STOP}` AND `ExportState != FINALIZED`) accumulate unmerged segments in their session directory. Beta shipped a Discard-only path; users with valid segments had no way to recover them into a watchable artifact. Phase 4.3 adds a Merge action plus a Keep-as-raw terminal so users with insufficient storage can preserve their footage as N separate files.

Three coupled architectural questions:

1. **Where does the recovery merge run?** Options: `WorkManager` (durability), a new `RecoveryMergeController` class with its own scope (separation), or `RovaRecordingService` via a new action (lifecycle reuse).
2. **How does the merge call the mux primitives?** `VideoMerger` is single-entry-locked behind `ExportPipeline` (Gradle check `checkExportPipelineSingleEntry`).
3. **How does pre-flight failure surface to the user?** Must compose with the existing WarningCenter precedence + the v3 sheet chrome.

## Decision

### 1. Host = `RovaRecordingService.startRecoveryMerge(context, sessionId)` via `ACTION_RECOVER_MERGE` intent

Reuses the FGS lifecycle, the notification channel, the battery-optimization wiring, the wakelock policy, and the live-recording mutex. The gate refuses a recovery merge while a recording is active OR while another recovery merge is in-flight, emitting `RecoveryMergeOutcome.ServiceBusy`.

The start-decision is extracted into a pure helper (`recoveryMergeStartGate`), JVM-testable per the `SegmentGateThermal` precedent (ADR-0016). Decision rules:
- blank sessionId → Reject(UnknownSession)
- isRecordingActive (recording OR another merge in-flight) → Reject(ServiceBusy)
- else → Accept(sessionId)

The service FGS is declared with `foregroundServiceType="camera|microphone|dataSync"` (manifest); recovery merge uses `FOREGROUND_SERVICE_TYPE_DATA_SYNC` on API 34+ — it doesn't use the camera/mic.

### 2. Merge call = new `ExportPipeline.exportRecovered(...)` entry alongside the existing `export()`

Runs an eager storage pre-flight (`sum(segment.length()) * 1.05` vs `sessionDir.usableSpace`); on insufficient storage, returns `ExportResult.InsufficientStorage(requiredBytes, availableBytes)` WITHOUT opening a `MediaMuxer`. Otherwise delegates verbatim to `export()` (same tier dispatch, same `VideoMerger` call). Single-entry invariant preserved — recovery still goes through `service/export/`; the Gradle check matches the literal `ExportPipeline.export(` and is unaffected by `exportRecovered(`.

A pure test seam `exportRecoveredForTest` mirrors the pre-flight logic with injected lambdas so the branch can be verified without `Context`/`SessionStore`/MediaMuxer dependencies — matches the established pure-helper test policy.

### 3. Outcome surface = `RecoveryMergeOutcomeSignal` (lazy singleton on `RovaApp`)

States: `Idle | InProgress(sessionId, progress) | Outcome(sessionId, outcome)`. Outcome variants: `Succeeded | InsufficientStorage(req, avail) | MuxFailed(cause) | ServiceBusy | UnknownSession`.

Producer: `RecoveryMerger` (running inside the FGS).

Consumers:
- `WarningCenterViewModel` lifts `Outcome.InsufficientStorage` into `WarningId.CANT_MERGE` (ADVISORY, AdvisorySheet); exposes `pendingCantMergeSessionId`.
- `RecoveryViewModel` lifts `InProgress` into the matching card's `mergeInProgress` (drives the ProgressStrip fill) and `Outcome.MuxFailed` into the card's `mergeFailedReason` (drives the "Retry merge" label flip).
- `acknowledge(sessionId)` is session-scoped (no-op for mismatched ids) — late acks for stale outcomes cannot wipe a fresher one.

### 4. New terminal `Terminated.MULTI_SEGMENT_KEPT`

Represents the user choice "keep recovered segments as N separate files instead of one merged file" (`Keep as raw clips` CTA on the card + `Save segments only` CTA on the C2.4 sheet). Recovery mapper hides cards in this state; Library row enumeration (one row per segment) is a future slice.

`MULTI_SEGMENT_KEPT` is the 5th value on `Terminated`, ordinal 4 (last). Pinned by `TerminatedMultiSegmentKeptTest`.

### 5. C2.4 sheet chrome — reuse v3, no new render path

The sheet is `WarningSheetV3` with a NEW `tertiary: WarningAction?` slot. `WarningActionStyle` enum (Primary/Secondary/Link) controls visual treatment; the CANT_MERGE tertiary uses `Link` (destructive red underlined text). All defaults preserve back-compat for the 19 pre-existing sheets.

CTAs:
- Primary: `STORAGE_SETTINGS` (system Intent — free up space and retry)
- Secondary: `KEEP_SEGMENTS_ONLY` (VM-only — writes `MULTI_SEGMENT_KEPT` terminal)
- Tertiary: `DISCARD_RECOVERY_SESSION` (VM-only — calls `SessionStore.discardSession`)

`WarningCenter::onSecondary` was narrowed to dispatch the secondary target ONLY for the two new recovery-specific targets — pre-existing "Not now" secondaries (which point to `APP_DETAILS_SETTINGS` as a placeholder) remain dismiss-only.

## Rejected alternatives

- **`WorkManager`** — durable, but: (a) the FGS notification + wakelock policy + battery-opt prompt are already on the service, (b) `WorkManager`'s constraint system adds latency the user can see, (c) duplicating "are we already recording?" mutex into WorkManager is more code than the start-with-action path.
- **`RecoveryMergeController` class with its own coroutine scope** — works, but the service already owns the long-running compute lifecycle on this device. Adding a second one duplicates the notification / wakelock / battery-opt wiring without gain.
- **Caller-scope coroutine (off `RecoveryViewModel`)** — fails the FGS requirement: a long mux call from a backgrounded `ViewModel` scope can be process-killed mid-merge with no notification. Service-hosted gets the FGS for free.
- **`ExportState` flag instead of a `Terminated` value** — `ExportState` is owned by the export-pipeline contract (NOT_STARTED / MUXING / COPYING / FINALIZED / FAILED). "User explicitly chose to keep raw" is a session terminal, not an export step. Belongs on `Terminated`.
- **New `RecoveryCardKind.MERGE_FAILED`** — rejected. Merge-failed is a transient UI state on top of the existing kind (USER_STOPPED / KILLED_BY_SYSTEM / KILLED_FORCE_STOP); a nullable `mergeFailedReason: String?` field on `RecoveryCardState` captures it without enum bloat.

## Consequences

- `RovaRecordingService` gains a second responsibility (recovery merge). Mitigation: the start-decision is a pure helper; the service-side wiring is a thin shell. Recovery-merge-in-flight tracked separately from `isPeriodicActive` to prevent concurrent merges.
- `ExportPipeline` gains a second entry. The Gradle single-entry check matches `ExportPipeline.export(` literally and is unaffected by `exportRecovered(`.
- `Terminated` enum grows to 5 values; every exhaustive `when` adds one arm.
- `WarningId` grows to 20 entries; `WarningIdOrderTest` pins the new ordinal.
- `WarningAction` and `WarningSheetContent` gain default-valued fields (`style`, `tertiary`) so existing call sites compile unchanged.
- `AndroidManifest.xml`'s service `foregroundServiceType` extends with `dataSync` to cover the recovery merge path on API 34+.
- Out-of-scope (parked):
  - Library row enumeration for `MULTI_SEGMENT_KEPT` (one row per segment).
  - Retry backoff / auto-retry on transient failures.
  - Mid-recording "Can't merge" prevention.
  - Merge progress in the FGS notification.
  - A dedicated "Continue without saving" outcome distinct from `Discard` (today both go through the same `discardSession` path).
