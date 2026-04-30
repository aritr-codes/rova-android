# ADR 0005 — Recovery Scan

- **Status:** Accepted (amended by ADR 0006 §"Cross-Phase Ordering Invariant")
- **Date:** 2026-04-29 (amended 2026-04-30)
- **Phase:** 1.5 (cold-launch recovery)
- **Supersedes:** —
- **Superseded by:** —
- **Amended by:** ADR 0006 — adds the [TerminalAction.SKIPPED_EXPORT_PENDING] branch when `manifest.exportState in {MUXING, COPYING, FINALIZED} && terminated == null`, deferring those sessions to Phase 1.7 export-recovery. `ExportState.FAILED` and `ExportState.NOT_STARTED` continue to flow through the original ADR 0005 classification matrix — they are Phase 1.5 inputs, not Phase 1.7 inputs. The 3-arg `markTerminated(sessionId, terminated, stopReason)` API replaces the 2-arg form per ADR 0006 §"Atomic terminal-write API"; Phase 1.5 call sites pass `StopReason.USER` (`stopRequested=true` branch) or `StopReason.NONE` (`KILLED_FORCE_STOP` and `KILLED_BY_SYSTEM` branches) per the ADR 0006 migration table.
- **Related:** ROADMAP_v6.md §1.5 (Recovery Scanning), ROADMAP_v4.md §1.5 (carry-over scope), ADR 0001 (receiver lifetimes that motivate Guard B), ADR 0003 (tier-specific export recovery — the merged-output owner), ADR 0006 (Recording Lifecycle Robustness — owns the cross-phase invariant), Phase 1.3 stop-path implementation

---

## Context

Phase 1.3 closed the stop path: `RovaStopReceiver` and `RovaTickReceiver` perform terminal writes via `SessionStore.markTerminated` (first-writer-wins), and `RovaController.requestStop()` persists `stopRequested=true` before any cleanup. Cold launches now encounter at least four observable manifest shapes that the running build must classify without losing user-recorded segments and without racing receivers that may still be completing terminal writes.

Two structural facts dominate the design:

1. **Receivers own the most accurate terminal classification.** When `RovaStopReceiver` fires for a dead service, it writes `USER_STOPPED` because the user's intent is unambiguous. When `RovaTickReceiver` fires after the process was killed, it writes `KILLED_BY_SYSTEM`. A scan that ran first would fall back to `KILLED_FORCE_STOP`, losing both signals. `markTerminated` is first-writer-wins, so receiver order matters.
2. **`Application.onCreate` runs on receiver-cold-start.** A pending `RovaTickReceiver` or `RovaStopReceiver` alarm/broadcast cold-starts the process *with the receiver as the entry point*. Any unconditional scan invocation from `RovaApp.onCreate` therefore races receiver-owned terminal writes — even when the user never opened the UI.

Phase 1.3 also leaves a recognized non-blocker shape: `(stopRequested=true, terminated=null, segments=[])` produced when the user taps STOP during camera prep. Phase 1.5 owns the resolution.

## Decision

### Scope Boundary — Phase 1.5 Owns Segments and Terminal State, Not Merged Output

Phase 1.5 classifies the **manifest + on-disk segments** of dead sessions. The merged output file is **out of scope**:

- `SessionManifest` has no `mergedOutputPath` field and no segment-count helper. Recovery never reasons about "did merge complete?" beyond reading `terminated`. A `terminated == COMPLETED` value is the canonical signal that merge succeeded (written by the service in `performMerge` only on success). Any non-segment file in the session dir is treated as evidence (anomaly), never as authoritative output.
- Tier-specific export recovery (`recoverTier1Exports`, `recoverPreQExports`) is owned by **ADR 0003** and runs in Phase 1.7. The Phase 1.5 scan must not touch `pendingUri`, `privateTempPath`, or `publicTargetPath` and must not delete merged-output files.

### State / Evidence Vocabulary

Recovery distinguishes durable from derived state.

**Terminated state** (durable, persisted): the `Terminated` enum value in `SessionManifest`. Written via `SessionStore.markTerminated` (first-writer-wins). Values:

| Value | Owner | Meaning |
|---|---|---|
| `USER_STOPPED` | `RovaStopReceiver`, service `performMerge` finally on stop, Phase 1.5 fallback when `stopRequested=true` | User asked to stop. |
| `COMPLETED` | service `performMerge` finally on success | Recording finished and merge succeeded. |
| `KILLED_BY_SYSTEM` | `RovaTickReceiver` (live tick, registry-dead) | Process died between ticks. |
| `KILLED_FORCE_STOP` | Phase 1.5 scan only | No surviving manifest signal — force-stop or hard crash with no live alarm to land receiver-owned classification. |

**Anomaly set** (derived, ephemeral): computed each scan from on-disk evidence vs. the manifest's claims. Never persisted. Re-derived on every cold launch.

**Evidence variables** read by the classifier:

| Symbol | Meaning |
|---|---|
| `T` | `manifest.terminated` (nullable) |
| `R` | `manifest.stopRequested` |
| `Idx_max_manifest` | highest index parsed from `manifest.segments[*].filename` (matched against `^segment_(\d{4})\.mp4$`); `0` if `segments` is empty |
| `S_manifest_valid` | manifest segments whose file exists on disk and passes `validateMediaFile` |
| `S_manifest_missing` | manifest segments whose file is absent from disk |
| `S_manifest_invalid` | manifest segments whose file exists but fails `validateMediaFile` |
| `S_orphan_appendable` | longest contiguous, all-valid prefix of disk files at indices `Idx_max_manifest + 1, +2, ...` |
| `S_orphan_post_gap` | orphan segment-shaped files past the first gap or first invalid |
| `S_orphan_invalid` | orphan segment-shaped files that fail `validateMediaFile` |
| `S_orphan_duplicate` | indices duplicated across two or more files (manifest + disk, or two disk files) |
| `S_unknown` | files in the session dir matching neither `manifest.json[.tmp]` nor the segment regex (legacy filenames, partial merge outputs, leftover debug captures) |
| `startedAt` | `manifest.startedAt` (ms) |

The segment filename pattern is **`segment_NNNN.mp4`**, matching `SessionStore.nextSegmentFilename`. A scan that uses any other regex is broken.

### Decision Matrix

For each session directory not currently owned by a live `ServiceController`:

| `T` | `R` | Manifest+disk shape | Append orphan prefix? | Action | Anomalies |
|---|---|---|---|---|---|
| `COMPLETED` | — | — | **no** | skip terminal write; recompute anomalies | every applicable anomaly; any orphan segment-shaped file is reported, never appended |
| `USER_STOPPED` | — | — | yes | skip terminal write; append validated contiguous orphan prefix; recompute anomalies | every applicable anomaly |
| `KILLED_BY_SYSTEM` | — | — | yes | skip terminal write; append validated contiguous orphan prefix; recompute anomalies | every applicable anomaly |
| `KILLED_FORCE_STOP` | — | — | yes | skip terminal write; append validated contiguous orphan prefix; recompute anomalies | every applicable anomaly |
| null | true | `S_manifest_valid == 0` ∧ `S_manifest_missing == 0` ∧ `S_manifest_invalid == 0` ∧ `S_orphan_*` empty | n/a (no orphans) | `markTerminated(USER_STOPPED)` (Phase 1.3 prep-time stop) | none |
| null | true | otherwise | yes | append validated contiguous orphan prefix, **then** `markTerminated(USER_STOPPED)`, then enumerate evidence | every applicable anomaly |
| null | false | `S_manifest_valid == 0` ∧ `S_manifest_missing == 0` ∧ `S_manifest_invalid == 0` ∧ `S_orphan_*` empty | n/a (no orphans) | `markTerminated(KILLED_FORCE_STOP)` | none |
| null | false | otherwise | yes | append validated contiguous orphan prefix, **then** `markTerminated(KILLED_FORCE_STOP)`, then enumerate evidence | every applicable anomaly |

The terminal-write decision depends only on `T` and `R`. The append decision depends only on `T`: **the contiguous orphan prefix is appended iff `T != COMPLETED`** (i.e., the four cases `USER_STOPPED`, `KILLED_BY_SYSTEM`, `KILLED_FORCE_STOP`, and `null`). For `T == null`, append runs **before** `markTerminated` so the resulting terminal record reflects the final segment set in a single atomic snapshot. The classifier **never** writes `KILLED_BY_SYSTEM` or `COMPLETED` — both are owned upstream (`RovaTickReceiver` and `RovaRecordingService.performMerge` respectively).

**Why `COMPLETED` is excluded from append.** A `COMPLETED` session means `performMerge` finished successfully and the merged output exists (per ADR 0003 and the service contract). Appending segments to its manifest would silently extend the segment set past what was actually merged into the output file — a state desync Phase 1.5 has no mechanism to repair. Stray segment-shaped files in a `COMPLETED` session directory are evidence of a separate fault (e.g., a debug capture, an interrupted post-merge cleanup, an export-tier orphan owned by ADR 0003), and they surface as anomalies for human review rather than being absorbed into a closed session.

### Anomaly Catalog

| Anomaly | Trigger | Effect on auto-discard | Effect on finish-merge |
|---|---|---|---|
| `MissingSegmentAnomaly(missingIndices)` | `S_manifest_missing > 0` | forces `BLOCKED`/`OFFER_DISCARD` (never `AUTO_DISCARD_ELIGIBLE`) | UI must disclose "X of Y survive"; user opts in/out |
| `InvalidManifestSegmentAnomaly(indices)` | `S_manifest_invalid > 0` | forces `OFFER_DISCARD` | UI must disclose; merge proceeds only on user-chosen subset |
| `OrphanSegmentAnomaly(indices)` | `S_orphan_post_gap > 0`, OR `T == COMPLETED` AND any candidate orphan exists (no append step runs) | forces `OFFER_DISCARD` | not auto-appended; user can review |
| `InvalidOrphanAnomaly(filenames)` | `S_orphan_invalid > 0` | forces `OFFER_DISCARD` | never appended |
| `DuplicateSegmentAnomaly(indices)` | `S_orphan_duplicate > 0` | forces `OFFER_DISCARD` | duplicates not appended |
| `UnknownArtifactAnomaly(filenames)` | `S_unknown > 0` | forces `OFFER_DISCARD` | reported only |
| `MalformedManifestRecordAnomaly(filenames)` | manifest contains a `SegmentRecord` whose `filename` does not match `^segment_(\d{4})\.mp4$` | forces `OFFER_DISCARD` | manifest record cannot be reasoned about; surfaces for human review. Distinct from `UnknownArtifactAnomaly` (file on disk) — this is a manifest *record* problem. Both can fire for the same underlying name if the file also exists on disk. |

A session with **no anomalies and no surviving segments** is `AUTO_DISCARD_ELIGIBLE` (per the eligibility table below). A session with any anomaly is `OFFER_DISCARD` at most. **Phase 1.5 emits these flags but never deletes** — see "Deletion Ownership" below.

### Media Validity Rules — `validateMediaFile(File)`

Returns `true` iff **all** hold:

1. `file.exists() && file.length() > 0`
2. `MediaExtractor.setDataSource(file.absolutePath)` succeeds
3. `extractor.trackCount > 0`
4. at least one track has `MediaFormat.KEY_MIME` starting with `"video/"`
5. selecting that track and calling `readSampleData(buffer, 0)` returns `> 0`
6. `extractor.release()` always runs in `finally`

Used for: **every segment file the classifier touches** — both in-manifest segments and orphan segments. The earlier "skip in-manifest validation to avoid flapping" reasoning is dropped: per-session live re-check (concurrency invariant 5 below) excludes any session owned by a live `ServiceController`, so a tick cannot race the scan on a session the scan is processing. Validating all segment files is the only way a corrupt acknowledged segment surfaces as `InvalidManifestSegmentAnomaly` rather than slipping silently into a finish-merge.

**Not** used for: merged output files. Phase 1.5 does not validate merged output (out of scope per "Scope Boundary" above). Any non-segment file is `UnknownArtifactAnomaly`.

### Discard Eligibility (Phase 1.5 emits flags; never deletes)

Phase 1.5 **never deletes a session directory**. The classifier computes a `DiscardEligibility` value per session and publishes it on `RecoveryReport`; physical deletion is owned by other phases (see "Deletion Ownership" below).

`DiscardEligibility` is one of:

| Value | Conditions |
|---|---|
| `AUTO_DISCARD_ELIGIBLE` | `T` is a terminal value (`USER_STOPPED`, `COMPLETED`, `KILLED_BY_SYSTEM`, or `KILLED_FORCE_STOP`) AND `S_manifest_valid == 0` AND `S_manifest_missing == 0` AND `S_manifest_invalid == 0` AND `S_orphan_appendable == 0` AND `S_orphan_post_gap == 0` AND `S_orphan_invalid == 0` AND `S_orphan_duplicate == 0` AND `S_unknown == 0` AND no anomaly is open against the session |
| `OFFER_DISCARD` | `T` is a terminal value but at least one orphan, invalid, missing, duplicate, unknown, or anomaly is present |
| `BLOCKED` | `T == null` (classification didn't happen — caller should retry next scan), OR session is owned by a live `ServiceController` |

**Deletion ownership.** Phase 1.5 emits the flag; deletion is performed only by:

1. **Phase 1.7 cleanup pass** — after `recoverTier1Exports` / `recoverPreQExports` (ADR 0003) have run and confirmed the session has no surviving export state (`pendingUri == null` AND `privateTempPath == null` AND `publicTargetPath == null` AND `exportState == NOT_STARTED`). The Phase 1.7 cleanup pass intersects its own export-clean predicate with Phase 1.5's `AUTO_DISCARD_ELIGIBLE` flag; only sessions in the intersection are deleted automatically.
2. **Explicit user action through Phase 2 UI** — the user reviews the surviving artifacts (per `OFFER_DISCARD`) and confirms.

Phase 1.5 reading export fields is **forbidden** (the export-clean predicate is a Phase 1.7 responsibility); the architectural separation depends on Phase 1.5 not pre-empting Phase 1.7's read of those fields.

The UI surface for `OFFER_DISCARD` must enumerate every surviving artifact (file path + size + validity) so the user knows what they're discarding before confirming.

### Orphan Ordering / Gap Rules

The orphan analysis runs for every session directory the scan touches. The **append step (step 6 below) is gated on `T != COMPLETED`** per the Decision Matrix; for `T == COMPLETED`, the same enumeration runs but every candidate orphan flows directly into anomalies with no `appendSegment` call.

1. Enumerate files matching `Regex("""^segment_(\d{4})\.mp4$""")`.
2. Compute `Idx_max_manifest` by parsing `manifest.segments[*].filename` against the same regex; `0` if `segments` is empty.
3. Partition disk files into "in-manifest" (index ≤ `Idx_max_manifest` AND filename appears in `manifest.segments`) and "candidate orphans" (everything else).
4. Sort candidate orphans ascending by parsed index.
5. Validate each candidate via `validateMediaFile`.
6. **Append (only when `T != COMPLETED`)** — identify the **longest contiguous valid prefix** starting at `Idx_max_manifest + 1`. Append that prefix via `SessionStore.appendSegment` (serial dispatcher) and **only that prefix**. Each appended record uses streaming SHA-1 via the existing `SessionStore.sha1Of` helper. For `T == null`, this step runs before `markTerminated` so the terminal record reflects the appended segments. For `T == COMPLETED`, this step is skipped entirely; every candidate orphan is treated as `OrphanSegmentAnomaly` (or `InvalidOrphanAnomaly` if it failed validation in step 5).
7. Every valid candidate orphan that the scanner did not append → emit `OrphanSegmentAnomaly`. For `T != COMPLETED`, this includes both post-gap orphans above `Idx_max_manifest` AND any orphan at or below `Idx_max_manifest` (whose index sits in a manifest gap, or whose canonical filename collides with a malformed manifest record). For `T == COMPLETED`, the appendable-prefix is empty by gating, so every valid candidate orphan lands here. **Never delete.**
8. Two files claiming the same NNNN (across manifest + disk, or two disk files) → emit `DuplicateSegmentAnomaly`. Neither is appended on top of an existing manifest record.
9. Files in the session dir that match neither `manifest.json[.tmp]` nor the segment regex → emit `UnknownArtifactAnomaly`. Never deleted.

A scan **never** rewrites `manifest.segments` to remove or reorder existing records; the only mutation is `appendSegment` for the validated contiguous prefix, and only when `T != COMPLETED`.

### Missing / Invalid Segment Handling

If `S_manifest_missing > 0` or `S_manifest_invalid > 0`:

- The user-visible segment count `N` shown in the UI stays at `manifest.segments.size`. The scan does not silently shrink the count.
- `MissingSegmentAnomaly` and/or `InvalidManifestSegmentAnomaly` are emitted on the `RecoveryReport`.
- Auto-discard is **blocked** for the session.
- Finish-merge UI **must** disclose "X of Y segments survive" and identify which indices are missing or invalid before proceeding. The user chooses: merge survivors, abandon and offer-discard, or leave the session for later.

A scan **never** rewrites `manifest.segments` to match disk reality. The manifest reflects what the service acknowledged; disk drift is reported, not silently reconciled.

### Scan Trigger Boundary

The **only** invocation of `runRecoveryScan` is:

```
MainActivity.onCreate → RovaApp.triggerRecoveryScanIfNeeded()
```

`triggerRecoveryScanIfNeeded` is idempotent per process via `AtomicBoolean recoveryScanRan`. First call CAS-flips `false → true` and proceeds; subsequent calls return immediately.

The scan is **not** triggered from:

- `RovaApp.onCreate` — process may start for receiver delivery without UI; would race receiver-owned terminal writes (Phase 1.5 design round 2 finding).
- `RovaTickReceiver` / `RovaStopReceiver` — receivers complete their own terminal writes; they do not initiate scanning.
- `RovaRecordingService` — service is the live owner of one session, not the recovery surface.
- Any background `WorkManager` or `JobScheduler` task.

### Concurrency Invariants

1. **Mutex**: `startupMutex: Mutex` is shared between `RovaApp.runRecoveryScan` and `RovaRecordingService.createSession + ServiceController.register`. The scan holds it for its full duration. The service holds it across the create-then-register pair so a session cannot be observed half-registered.

2. **Guard A — crash recovery for the latch.** `triggerRecoveryScanIfNeeded` wraps `runRecoveryScan` in try/catch. On `Throwable`, the catch block logs via `RovaCrashReporter` (do not swallow) and resets `recoveryScanRan` to `false`. A crashed scan must not permanently mute recovery for the rest of the process lifetime.

3. **Guard B — receiver active-work counter.** Both receivers follow this exact pattern:

    ```kotlin
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as RovaApp
        app.activeReceiverWork.incrementAndGet()    // synchronous, before goAsync
        val pending = goAsync()
        scope.launch {
            try { /* receiver work, including any markTerminated call */ }
            finally {
                pending.finish()
                app.activeReceiverWork.decrementAndGet()
            }
        }
    }
    ```

    The increment must be synchronous in the `onReceive` body, **before** `goAsync()`. Incrementing inside the launched coroutine body opens a window where the UI scan can acquire `startupMutex` between `goAsync()` and the launched body's first instruction.

4. **Pre-scan drain.** Inside `startupMutex.withLock { ... }`, scan blocks on `activeReceiverWork.get() == 0` with a 10 s cap (8 s receiver budget + 2 s margin). On timeout: log, reset `recoveryScanRan` to `false`, defer scan. Next foreground entry retries. The UI must surface "scan deferred" rather than spin indefinitely.

5. **Per-session live re-check.** Before any write to session `S`, the classifier re-checks `ServiceController.current()?.sessionId == S`. If true, skip the session entirely — the live service owns it.

6. **Age filter.** Skip sessions with `manifest.startedAt > scanStart - 5_000L`. Catches the case where `createSession` released the mutex but a receiver started just before the scan acquired it. The 5 s window is larger than any single mutex-protected critical section.

7. **Terminal writes.** Always via `SessionStore.markTerminated` (first-writer-wins). The scan never writes a terminal value directly to the manifest.

8. **Segment appends.** Always via `SessionStore.appendSegment` (serial dispatcher). The scan never edits `manifest.segments` directly.

9. **Anomalies are derived.** Recomputed every scan. Never written to disk. Live in `RecoveryReport: StateFlow<RecoveryReport?>` exposed from `RovaApp` for the duration of the process.

### Non-Goals

Explicitly out of scope for Phase 1.5:

- **No `SessionManifest` schema bump.** All recovery decisions rely on existing fields (`terminated`, `stopRequested`, `segments`, `startedAt`, plus the export-tier fields owned by ADR 0003). Schema version stays at `2`.
- **No new `Terminated` enum values.** Recovery uses the existing four (`USER_STOPPED`, `COMPLETED`, `KILLED_BY_SYSTEM`, `KILLED_FORCE_STOP`). Phase 1.5 only ever writes `USER_STOPPED` (when `stopRequested=true`) or `KILLED_FORCE_STOP` (otherwise). It never writes `KILLED_BY_SYSTEM` (receiver-owned) or `COMPLETED` (service-owned).
- **No persisted anomaly storage.** Anomalies live only in `RecoveryReport` and are recomputed each cold launch.
- **No receiver-triggered scan.** Receivers write their own terminal classification; they do not initiate `runRecoveryScan`.
- **No merged-output reasoning.** Merged output recovery is owned by ADR 0003 / Phase 1.7. Any non-segment file becomes `UnknownArtifactAnomaly`.
- **No physical deletion of any file or directory.** Phase 1.5 emits `DiscardEligibility` flags on `RecoveryReport`; it never calls `SessionStore.discardSession`, `File.delete`, `File.deleteRecursively`, `ContentResolver.delete`, or any other deletion API. Deletion is owned by Phase 1.7 (post-export-recovery cleanup pass) or by explicit user action in Phase 2 UI.
- **No silent reconciliation of `manifest.segments`.** Missing or invalid manifest segments surface as anomalies; the scan never edits existing records.
- **No mutation of `COMPLETED` sessions.** A `COMPLETED` terminal record means merge succeeded; the scan never appends to its `manifest.segments`. Stray segment-shaped files in a `COMPLETED` session directory surface as `OrphanSegmentAnomaly`/`InvalidOrphanAnomaly`/`DuplicateSegmentAnomaly`/`UnknownArtifactAnomaly` for human review only.
- **No background scan retry.** Drain-timeout deferral relies on the next user-driven foreground entry, not a scheduled retry.

## Consequences

### Positive

- Receiver-vs-scan ordering is provably safe under crash, restart, and overlap (Guard A + Guard B + drain + age filter + per-session live re-check).
- The four-way `Terminated` split is preserved: `USER_STOPPED` / `COMPLETED` / `KILLED_BY_SYSTEM` / `KILLED_FORCE_STOP` each retain a single owner and a single meaning.
- Anomalies are observable but non-destructive — the user controls all data loss.
- Schema unchanged: no migration risk for existing manifests written in Phase 1.1–1.3.
- One classifier function (`RecoveryScanner.classify(SessionDir)`) localizes the matrix; reviewers and future contributors have one place to read.

### Negative

- Validating every in-manifest segment on every cold launch is more I/O than the v6 §1.5 sketch implied. Mitigated: `MediaExtractor.setDataSource` + one `readSampleData` is sub-millisecond per file on warm storage; per-session live re-check excludes the only segment that could plausibly be in flux.
- Drain timeout (10 s) blocks recovery UI on slow paths. Mitigated: scan defers gracefully (timeout resets the latch and returns; UI shows a "scan deferred — retry from menu" affordance rather than an indefinite spinner).
- Six anomaly types make the classifier output denser than the v6 §1.5 sketch. Mitigated: all logic concentrated in `RecoveryScanner.classify`.

### Neutral

- `RovaApp` becomes the owner of `recoveryReport`, `recoveryScanRan`, `activeReceiverWork`, and `startupMutex`. Phase 2 UI surfaces (recovery dialog, anomaly list) read from `RovaApp` rather than from the service.
- Both existing receivers (`RovaTickReceiver`, `RovaStopReceiver`) require a small refactor to wrap their `goAsync` work in the increment/decrement pattern. Future receivers must follow the same protocol.
- Phase 1.7 (export-tier recovery, ADR 0003) will run **after** Phase 1.5 in cold-launch order: terminal classification + segment recovery first, then tier-specific export recovery. The two scans share `startupMutex` but operate on disjoint manifest fields. The deletion ordering is fixed: no session directory may be deleted until **both** Phase 1.5 has emitted `AUTO_DISCARD_ELIGIBLE` AND Phase 1.7 has confirmed export-clean state — preventing a race where Phase 1.5 strands a pending export row by deleting its manifest first.

## Acceptance Criteria

- ADR file present at `docs/adr/0005-recovery-scan.md`.
- `RovaApp` exposes `recoveryReport: StateFlow<RecoveryReport?>`, `recoveryScanRan: AtomicBoolean`, `activeReceiverWork: AtomicInteger`, `startupMutex: Mutex`.
- `RovaApp.triggerRecoveryScanIfNeeded()` is the sole call site for `runRecoveryScan`; called from `MainActivity.onCreate`.
- `RovaApp.onCreate` does **not** invoke any recovery code.
- `RovaTickReceiver` and `RovaStopReceiver` increment `activeReceiverWork` synchronously in `onReceive` before `goAsync()`, and decrement in the launched coroutine's `finally`.
- `RovaRecordingService.createSession` + `ServiceController.register` are wrapped in `startupMutex.withLock { ... }`.
- `RecoveryScanner.classify(SessionDir): SessionClassification` implements the decision matrix above; unit-tested for every row and every anomaly category.
- `RecoveryScanner` derives the highest acknowledged segment index by parsing `manifest.segments[*].filename` against `^segment_(\d{4})\.mp4$`; no reliance on a non-existent `manifest.lastIndex` field.
- `validateMediaFile(File): Boolean` lives in a single utility location and is used for both in-manifest segments and orphan segments.
- Phase 1.5 code path writes only `USER_STOPPED` or `KILLED_FORCE_STOP` via `markTerminated`; a unit test asserts the scan never writes `KILLED_BY_SYSTEM` or `COMPLETED`.
- A unit test asserts that `RecoveryScanner.classify` calls `SessionStore.appendSegment` exactly zero times when `manifest.terminated == COMPLETED`, even when valid contiguous orphan segment files are present in the session directory.
- A unit test asserts that for `manifest.terminated == null`, `appendSegment` calls happen **before** `markTerminated` (ordering is observable through the serial `persistDispatcher`).
- A static check (grep or lint) asserts that no Phase 1.5 source file (`RovaApp.kt`, `RecoveryScanner.kt`, and any helper under a designated recovery package) calls `SessionStore.discardSession`, `File.delete`, `File.deleteRecursively`, or `ContentResolver.delete`. Phase 1.5 emits `DiscardEligibility` flags only.
- `RecoveryReport` exposes `Map<SessionId, DiscardEligibility>` (or equivalent shape) so Phase 1.7 cleanup and Phase 2 UI can read the per-session flag without re-running the classifier.
- A unit test or static check asserts no reference to `runRecoveryScan` outside `RovaApp` and its sole trigger.
- A unit test asserts that `recoveryScanRan` is reset to `false` when `runRecoveryScan` throws (Guard A) and that the `Throwable` is logged via `RovaCrashReporter`.
- A static check (grep or lint) asserts that every receiver in `service/` either (a) increments `activeReceiverWork` synchronously before `goAsync()` AND decrements it later, or (b) is explicitly opted out via a documented marker. The opt-out marker is a comment containing the literal `guard-b-opt-out:` followed by a reason. Implemented as `:app:checkRecoveryReceiverCounter` and wired into `preBuild`.
- A static check asserts the segment regex used in recovery is `^segment_(\d{4})\.mp4$` exactly — no `seg_` variant slips in.
- A unit test asserts a manifest record whose `filename` does not match the canonical regex emits `MalformedManifestRecordAnomaly` and forces `OFFER_DISCARD` (never `AUTO_DISCARD_ELIGIBLE`). The scan must not silently drop the record.
- A unit test asserts a valid orphan at or below `Idx_max_manifest` (e.g., manifest claims indices 1 and 3, disk has segment_0002.mp4) emits `OrphanSegmentAnomaly` and is not appended.
- The Phase 1.5 scan does not read or write `pendingUri`, `privateTempPath`, `publicTargetPath`, `mediaScanCompleted`, or `exportState` (export-pipeline fields owned by ADR 0003).

## References

- ROADMAP_v6.md §1.5 (Recovery Scanning) — phase scope.
- ROADMAP_v4.md §1.5 — carry-over scope from previous roadmap.
- ADR 0001 (Exact-Alarm Policy) — receiver lifetimes that motivate Guard B.
- ADR 0003 (Tiered Storage / Public Export) — export-pipeline recovery; runs after Phase 1.5 scan and operates on disjoint manifest fields.
- Phase 1.3 carry-over memory `project_phase15_recovery_carryover.md` — the `(stopRequested=true, terminated=null, segments=[])` shape produced by prep-time stops; classified here as `USER_STOPPED`.
- `SessionManifest.kt:166-171` — canonical `Terminated` enum (`USER_STOPPED`, `COMPLETED`, `KILLED_BY_SYSTEM`, `KILLED_FORCE_STOP`).
- `SessionStore.kt:218-219` — canonical segment filename helper (`segment_NNNN.mp4`).
- Android docs: `MediaExtractor`, `BroadcastReceiver.goAsync`, `AtomicBoolean`, `AtomicInteger`, `kotlinx.coroutines.sync.Mutex`.
