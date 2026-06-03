# ADR 0006 — Recording Lifecycle Robustness

- **Status:** Proposed (Draft 7 — Codex Round 6 patches applied) — pending review-gate
- **Date:** 2026-04-29
- **Phase:** 1.4
- **Supersedes:** none
- **Depends on:** ADR 0001 (alarms), ADR 0002 (surface), ADR 0003 (storage),
  ADR 0005 (recovery scan)
- **Related risks:** C1, C9, C11, C12, C14, C15, C18, C19

---

## Context

Phase 1.4 is the runtime-lifecycle phase. ROADMAP_v4/v5/v6 deferred its scope
unchanged — every assumption is examined here for the first time. Phase 1.3
defined the STOP path. Phase 1.5 (ADR 0005) froze recovery-scan semantics. The
gap between them is **what the live service does between user-initiated start
and orderly stop**, and how every non-orderly exit is classified by Phase 1.5
without ambiguity.

The goal of 1.4 is to:
1. Enumerate every path by which a `RovaRecordingService` instance terminates.
2. Define the manifest state transition for each path.
3. Lock the invariants that connect those paths to the existing
   `ServiceController` / Phase 1.5 contract. (Note: `stopNeedsRecovery` is
   in-memory only and is **not** a durable recovery signal — see §"Recovery
   Signal Source of Truth" below.)
4. Add the missing mid-session safety gates (permission revocation, dynamic
   low-storage) that today fire only at session start.
5. Define how init-window failures roll back or commit a partially-created
   manifest so Phase 1.5 sees an unambiguous state.

## Scope Boundary

**1.4 owns:**
- Service start/restart/destroy semantics on Android 12+.
- Foreground-service notification lifecycle.
- Camera-state machine: setup, teardown, cancellation.
- WakeLock ownership and release.
- Mid-session permission and storage gates.
- The termination matrix that hands off to ADR 0005.

**1.4 does NOT own:**
- Cold-launch recovery classification — owned by ADR 0005.
- Tier-1/2/3 export pipeline — owned by ADR 0003 (Phase 1.6/1.7).
- Drift-budget rearm/abandon mechanics — owned by ADR 0001/0004.
- Physical session deletion — owned by Phase 1.7.
- Headless surface lifecycle — owned by ADR 0002.

## State Vocabulary

### ServiceLifecycle

```
INIT ── startForeground OK ──▶ RUNNING ── stop intent ──▶ STOPPING ──▶ DESTROYED
   │                                ▲
   │                                │
   └── startForeground FAIL ────────┘── stopSelf ──▶ DESTROYED (no manifest)
```

- `INIT`: from `onStartCommand` entry until `startForeground` returns.
- `RUNNING`: foreground notification posted, recording loop active.
- `STOPPING`: stop requested (USER, COMPLETED, PERMISSION, LOW_STORAGE,
  TASK_REMOVED). Camera teardown + merge in flight.
- `DESTROYED`: `onDestroy` reached; `releaseResources()` ran exactly once.

### CameraState (mid-session)

```
IDLE ── setupCamera ──▶ SETTING_UP ── bind OK ──▶ READY
                            │                       │
                            │                  recordSegment
                            │                       │
                            ▼                       ▼
                       SETUP_FAILED            RECORDING ── finalize ──▶ READY
                                                   │
                                                   └── cancellation ──▶ FINALIZING ──▶ READY
```

`SETTING_UP → SETUP_FAILED` is terminal for the session: service transitions
`RUNNING → STOPPING` with `stop_reason = INIT_FAILED` if it happens during
init window, or `PERMISSION_REVOKED` if camera permission was the cause.

### WakeLockState

- `HELD` — partial wakelock active.
- `RELAXED` — wakelock released for an inter-segment wait that crossed
  `WAKELOCK_RELAX_THRESHOLD_MS` AND battery-optimization is exempt
  (existing `shouldRelaxWakeLock` predicate).

`releaseResources()` always transitions to `RELEASED` (terminal).

## Termination Matrix

Every service-end path. Phase 1.5 classification depends on manifest state at
process-end, so each row's manifest write is load-bearing.

The matrix splits "init failure" by whether the manifest exists yet. Two
sub-windows of init:
- **Pre-manifest init window**: `onStartCommand` entry → `createSession`
  begins. No manifest exists; nothing to roll back.
- **Post-manifest init window**: `createSession` returned → `RUNNING` reached
  (camera bound, first segment started). Manifest exists on disk;
  `ServiceController` may or may not be registered.

| # | Trigger | Window | Manifest `terminated` | `stop_reason` | Merge runs? | Cleanup path |
|---|---|---|---|---|---|---|
| 1 | Notification STOP / UI stop | RUNNING | `USER_STOPPED` | `USER` | yes (if ≥1 segment) | orderly: stopForeground + stopSelf |
| 2 | Loop count reached | RUNNING | `COMPLETED` (post-merge only) | `NONE` | yes | run merge FIRST; `COMPLETED` written only on merge success commit point |
| 3 | Init Phase 2 fail: external-root null (B21) OR storage pre-flight insufficient. Runs AFTER `startForeground`, BEFORE `createSession`. | pre-manifest, post-FGS | `null` (no manifest) | n/a | no | stopForeground + stopSelf |
| 4a | `ForegroundServiceStartNotAllowedException` at **caller** site (`startForegroundService` from UI/receiver) | none (service never starts) | `null` (no manifest) | n/a | no | UI surfaces `StartBlocked.FGS_RESTRICTED`; no service exists to clean up |
| 4b | `ForegroundServiceStartNotAllowedException` at **service** site (inner `startForeground`) | pre-manifest | `null` (no manifest) | n/a | no | stopSelf in init window |
| 5 | Pre-flight CAMERA permission denied | pre-manifest | `null` (no manifest) | n/a | no | stopSelf in init window |
| 6 | `ServiceController.register` collision | post-manifest | `USER_STOPPED` | `INIT_FAILED` | no | atomic terminal write + manifest stays on disk; stopSelf |
| 7 | Camera setup / `bindToLifecycle` fail in post-manifest init | post-manifest | `USER_STOPPED` | `INIT_FAILED` | no | atomic terminal write + stopForeground + stopSelf |
| 8 | Permission revoked mid-session (CAMERA, or RECORD_AUDIO when `audioMode = VIDEO_AUDIO` per B18) | RUNNING | `USER_STOPPED` | `PERMISSION_REVOKED` | yes (if ≥1 valid segment) | atomic terminal write FIRST, then finish current segment, then merge |
| 9 | Low-storage gate fail mid-session | RUNNING | `USER_STOPPED` | `LOW_STORAGE` | yes (if ≥1 valid segment) | atomic terminal write FIRST, abort next segment, merge survivors |
| 10 | `onTaskRemoved` with no active session | n/a (no session) | `null` (no manifest written this run) | n/a | no | stopSelf |
| 11 | OS kills service (memory pressure, GC) | RUNNING | `null` at kill time → Phase 1.5 writes | n/a (Phase 1.5 sets `NONE`) | no | Phase 1.5 → `KILLED_BY_SYSTEM` (alarm fires next launch) |
| 12 | OS kills process (force-stop / Settings) | any | `null` at kill time → Phase 1.5 writes | n/a (Phase 1.5 sets `NONE`) | no | Phase 1.5 → `KILLED_FORCE_STOP` |
| 13a | Process death during merge — **user-stop flavor** (rows 1, 8, 9 path) | post-eager-terminal, mid-merge | `USER_STOPPED` (already eager-written per B3) | preserved (`USER`/`PERMISSION_REVOKED`/`LOW_STORAGE`) | no | Phase 1.7 export-recovery; manifest carries `exportState ∈ {MUXING, COPYING}` |
| 13b | Process death during merge — **loop-completion flavor** (row 2 path) | mid-merge, BEFORE merge-success commit | `null` (B7: `COMPLETED` not yet written) | n/a | no | Phase 1.7 export-recovery + Phase 1.5 fallback (`stopRequested` may or may not be set) |
| 13c | Process death AFTER merge artifact commit but BEFORE `COMPLETED` write | post-artifact-commit | `null` | n/a | no | Phase 1.7 reconciliation: sees `exportState = FINALIZED` + non-`COMPLETED` manifest, finalizes manifest then |
| 13d | Successful merge + successful `COMPLETED` write | terminal | `COMPLETED` | `NONE` | already done | session is closed; recovery scan ignores per ADR 0005 (`T == COMPLETED` skip) |

Rows 6–9 are **NEW in 1.4** (or reclassified). Rows 11–13d hand off to
Phase 1.5/1.7; 1.4 just guarantees the manifest is left in a state those
phases recognize.

### Why row 13 split (B13)

Draft 3 listed `COMPLETED` as a possible mid-merge value, contradicting
B7's "`COMPLETED` written ONLY at merge-success commit" rule. The split
makes each combination of pre-merge terminal state × merge progress ×
artifact commit explicit:

- **13a** (user-stop) — terminal already written eagerly (B3); merge in
  flight when process died. Phase 1.7 sees a terminal manifest with
  `exportState` indicating in-flight merge; export-recovery owns
  reconciliation per ADR 0003.
- **13b** (loop-completion, pre-artifact) — manifest is non-terminal
  because B7 forbids early `COMPLETED` write. `exportState ∈ {MUXING,
  COPYING}` indicates merge was in flight. Phase 1.5 + Phase 1.7
  cooperate: 1.7 sees `exportState` and may finalize the artifact; 1.5
  classifies the residual session per its matrix (likely `KILLED_*`
  since `stopRequested=false` for natural loop completion).
- **13c** (loop-completion, artifact-committed, terminal-write-not-yet)
  — the merged file exists on disk / in `MediaStore` pending row, but
  no `COMPLETED` write happened. Phase 1.7 sees `exportState =
  FINALIZED` + `terminated = null` and finalizes the manifest with a
  fresh `markTerminated(COMPLETED, NONE)`. ADR 0005's `T == COMPLETED`
  invariant is restored.
- **13d** is the happy path — listed for completeness so the matrix
  closes over all merge states.

## Cross-Phase Ordering Invariant (B14)

Row 13c (loop-completion, artifact committed, terminal-write-pending)
exposes a race between Phase 1.5 (cold-launch recovery scan) and
Phase 1.7 (export recovery). Phase 1.5's classifier (per ADR 0005 §
"Decision Matrix") does NOT read `exportState`. Without an explicit
ordering rule, Phase 1.5 runs first on cold launch, sees
`stopRequested=false, terminated=null` for a 13c session, and writes
`KILLED_FORCE_STOP` (or `KILLED_BY_SYSTEM` if the alarm is still
pending) via first-writer-wins. Phase 1.7 then tries
`markTerminated(COMPLETED, NONE)` and gets `AlreadyTerminal` —
permanently locking the terminal at `KILLED_*` even though a fully
merged artifact exists on disk. **Production data-state corruption.**

### Required invariant

> Phase 1.5 recovery scan MUST NOT write a terminal value for any
> session with `exportState ∈ {MUXING, COPYING, FINALIZED}` AND
> `terminated == null`. Such sessions are owned by Phase 1.7 export
> recovery, which runs first on cold launch (or interleaved with
> Phase 1.5 such that 1.7 reaches `markTerminated` before 1.5 does).

### Implementation: Phase 1.5 amendment

Phase 1.5's `RecoveryScanner.classify(...)` adds an export-state guard
**before** the existing terminal-write decision branch. The skip set is
explicit (B17 — `FAILED` and `NOT_STARTED` are NOT skipped):

```kotlin
private val EXPORT_IN_FLIGHT_OR_FINALIZED = setOf(
    ExportState.MUXING,
    ExportState.COPYING,
    ExportState.FINALIZED
)

// New first check after live-session/age-filter guards.
if (manifest.terminated == null &&
    manifest.exportState in EXPORT_IN_FLIGHT_OR_FINALIZED) {
    // Phase 1.7 owns this session. Skip terminal write entirely.
    return SessionClassification(
        sessionId = sessionId,
        terminalAction = TerminalAction.SKIPPED_EXPORT_PENDING,  // NEW variant
        eligibility = DiscardEligibility.BLOCKED,                 // never auto-discard
        anomalies = emptyList(),                                  // 1.7 owns anomaly view too
        appendedSegmentFilenames = emptyList()
    )
}
```

### Ownership table for `exportState` (B17)

| `exportState` | Owner of cold-launch terminal-write decision | Why |
|---|---|---|
| `NOT_STARTED` | **Phase 1.5** | No export was attempted; classify per ADR 0005 matrix as usual. |
| `MUXING` | **Phase 1.7** | Mid-mux process death. 1.7 finalizes-or-rolls-back per ADR 0003 tier rules. |
| `COPYING` | **Phase 1.7** | Mid-copy process death (Tier 2/3). 1.7 finalizes-or-rolls-back. |
| `FINALIZED` | **Phase 1.7** | Artifact committed; manifest needs late `markTerminated(COMPLETED, NONE)`. Row 13c. |
| `FAILED` | **Phase 1.5** | 1.7 already ran in a prior cold launch and gave up; the session has no merged artifact. 1.5 classifies the residual session per its matrix (likely `KILLED_*` if `stopRequested=false` since the `FAILED` write itself was a 1.7 result, not a user stop). |

Rule: `FAILED` is a **Phase 1.5 input**, not a Phase 1.7 input. Once 1.7
records `FAILED`, that session leaves 1.7's responsibility set. The user
sees it in History as "Recording finished — export failed; tap to retry"
(UI surface in Phase 2). 1.7 will re-attempt only on explicit user
action, NOT automatically on next cold launch.

This locks the cross-phase ownership: each `exportState` value has
exactly one owner at cold-launch time. No state strands.

**Completed-session retention.** Once a `FINALIZED` session receives its late
`markTerminated(COMPLETED, NONE)` (Row 13c), the session dir is **retained
permanently** by the automatic cleanup pass — it is the manifest-backed
Library/History index, removed only by explicit user delete or the opt-in
keep-latest-N retention cleaner. The retention rides on the `MissingSegmentAnomaly`
path (post-merge the on-disk segments are deleted but the manifest segment records
survive → `OFFER_DISCARD` → `ExportCleanupPredicate` gate 1 blocks). Full mechanism
and the degenerate zero-segment edge: ADR 0005 §"Completed-session retention"
(guarded by `CompletedSessionRetentionTest`).

`TerminalAction` gains a new variant:

```kotlin
enum class TerminalAction {
    WROTE_USER_STOPPED,
    WROTE_KILLED_FORCE_STOP,
    ALREADY_TERMINAL,
    SKIPPED,
    SKIPPED_EXPORT_PENDING   // NEW (1.4) — exportState in {MUXING, COPYING, FINALIZED} && T == null (B17/B22)
}
```

`DiscardEligibility.BLOCKED` is correct: 1.5 cannot decide eligibility
without knowing whether the merged artifact is intact; that's 1.7's
job. UI surfaces "export pending" until 1.7 reconciles.

### What Phase 1.7 does (forward-looking)

This ADR does NOT define Phase 1.7 export recovery (that is Phase 1.7's
ADR amendment to ADR 0003). It states the contract Phase 1.7 must
honor:

1. On cold launch, Phase 1.7 examines every manifest with
   `exportState ∈ {MUXING, COPYING, FINALIZED}` AND
   `terminated == null`.
2. For `exportState = FINALIZED` (row 13c): verify artifact integrity
   per ADR 0003 tier rules; if intact, call
   `markTerminated(COMPLETED, NONE)`.
3. For `exportState ∈ {MUXING, COPYING}` (rows 13a/13b in their
   loop-completion variant): per ADR 0003, finalize-or-roll-back. On
   finalize: write `COMPLETED` (or leave existing `USER_STOPPED` as-is
   for 13a). On roll-back: clear `exportState` to `FAILED` (not
   `NOT_STARTED` — preserves the audit trail that an export was
   attempted). Per the ownership table above, `FAILED` then becomes
   1.5's input on a future cold launch (only if `terminated == null`
   when 1.7 finished its pass; otherwise the session is already
   terminal and ADR 0005 ignores it).

For 13a (user-stop-flavor mid-merge), the manifest is already
`USER_STOPPED`. Phase 1.7 does not call `markTerminated` for these;
ADR 0005's matrix ignores them too. Phase 1.7 only touches
`exportState` in this branch.

### Trigger ordering

Both Phase 1.5 and Phase 1.7 are cold-launch passes. Two acceptable
orderings:

- **Sequential, 1.7 → 1.5.** 1.7's pass runs first; on completion it
  triggers 1.5. Simpler reasoning. Recommended.
- **Interleaved per-session.** A combined scan classifies each session
  exactly once, choosing 1.7-or-1.5 ownership based on `exportState`.
  Risk of duplicating logic across phases.

**Decision: sequential, 1.7 → 1.5.** When Phase 1.7 lands, the
`triggerRecoveryScanIfNeeded` site in `RovaApp` (Phase 1.5) extends to
run 1.7 first. Until 1.7 ships, 1.5 alone is correct PROVIDED the
amendment above is implemented — that way 1.5 leaves 13c sessions
alone, and they sit `BLOCKED` in the recovery report. UI surfaces
them as "Export pending" until Phase 1.7 ships and finalizes them on a
subsequent cold launch.

### Coordinated implementation: 1.4 includes 1.5 amendment

This ADR's implementation PR MUST also amend `RecoveryScanner.kt`,
`RecoveryReport.kt`, and `RecoveryScannerTest.kt` per the new
`SKIPPED_EXPORT_PENDING` branch. ADR 0005 gets a "Amended by ADR 0006
§Cross-Phase Ordering Invariant" footnote. Without this co-shipped
amendment, ADR 0006's row 13c is unsafe (per Codex Round 4 verdict).

### `exportState` schema

`SessionManifest.exportState: ExportState` is defined per ADR 0003
(Phase 1.6/1.7). Today's manifest may not yet carry the field — this
ADR's implementation requires the schema to include it (default
`NOT_STARTED` for read-tolerance on legacy manifests). The
`ExportState` enum itself stays Phase 1.6's responsibility; ADR 0006
relies only on the `NOT_STARTED` distinction.

### Why `USER_STOPPED` for permission/low-storage/init-failed termination

ADR 0005 froze `Terminated` to four values. Adding `STOPPED_LOW_STORAGE`,
`STOPPED_PERMISSION_REVOKED`, or `STOPPED_INIT_FAILED` would force every
recovery-scan consumer to update. Instead: these are externally-forced or
self-detected **explicit** stops (user revoked, disk full, controller
collision, camera bind error) — semantically a stop, not a system kill.
Encoded as `Terminated.USER_STOPPED` plus a sibling `stopReason` field.

The recovery scan (ADR 0005) reads only `terminated`; it ignores
`stopReason`. UI consumes `stopReason` for the user-facing message
("Stopped — camera permission revoked"). Diagnostics consume `stopReason`
for triage.

## Init-Window Manifest Lifecycle

The current code creates the manifest before camera setup (inside
`startupMutex.withLock { createSession + register }`). That fact rules out a
"no manifest" outcome for any failure detected after `createSession` returns.
This ADR adopts the **commit-and-mark** policy: once `createSession` succeeds,
the manifest stays on disk; init failures write a terminal `USER_STOPPED +
INIT_FAILED` rather than deleting.

### Rationale for commit-and-mark over rollback-delete
1. **No deletion races with Phase 1.5.** Recovery scan ownership of
   "what's on disk" stays clean. Phase 1.5 lint
   (`checkRecoveryNoDeletion`) does not block this — that lint covers
   recovery sources, not the writer service — but introducing a writer-side
   delete path doubles the surfaces Phase 1.5 must reason about.
2. **First-writer-wins remains correct.** The init-failure thread is the
   only writer in the post-manifest init window; no other thread can race
   it to the terminal write.
3. **User-visible artifact policy is uniform.** Every init failure produces
   a session row visible in History (Phase 2 UI) with a clear "INIT_FAILED"
   reason, rather than silently disappearing. Phase 1.7 cleanup will
   discard auto-eligible empty sessions later.
4. **No segment files in init failures.** The session directory contains
   only the manifest; recovery scan classifies as `OFFER_DISCARD` (terminal
   + no segments + no orphans + no anomalies → actually
   `AUTO_DISCARD_ELIGIBLE` per ADR 0005 §"Discard Eligibility"). Phase 1.7
   cleans up automatically without user intervention.

### Init-window write order (B15 + B19 — two-phase preflight)

Android 12+ requires `startForeground(...)` within ~5 seconds of the
caller's `startForegroundService(...)`. Disk I/O (`createSession`,
`StatFs` on adopted/removable storage) before `startForeground` risks
ANR and FGS-deadline violations.

Preflight splits into two phases (B19):
- **Phase 1 (RAM-only) — runs BEFORE `startForeground`:** synchronous
  permission checks and audio-mode decision. RAM-only operations,
  guaranteed sub-millisecond. Cannot stall.
- **Phase 2 (filesystem) — runs AFTER `startForeground`, BEFORE
  `createSession`:** `StatFs` storage probe. May stall on adopted /
  removable / OEM storage; safe now because FGS is already established.

Both phases are pre-manifest; failure in either path stops the service
without writing a manifest.

The canonical order is:

```
[CALLER, e.g. MainActivity / RovaController]
  ↓
[Phase 0] caller-side audio-mode policy (mic permission UI prompt if needed)
  ↓
ContextCompat.startForegroundService(intent)  (row 4a guard: see §"Site A")
  ↓
================================================================
[SERVICE, RovaRecordingService.onStartCommand]
  ↓
[Phase 1, RAM-only — runs BEFORE startForeground, sub-ms]
  - CAMERA permission check                  (row 5: stopSelf, no manifest)
  - RECORD_AUDIO check → audioMode decision  (B18, no row — never blocks)
  - POST_NOTIFICATIONS check on 33+ (warn-only)
  - notification + FGS type bitfield computed from audioMode
  ↓
startForeground(NOTIFICATION_ID, notification, fgsType)  (row 4b guard: see §"Site B")
  ↓                                          — fires within 5s of startForegroundService
                                             — IllegalStateException → row 4b cleanup
                                             — SecurityException (FGS-type/perm) → cleanup
  ↓
acquireWakeLock()                            — service is now in foreground; safe to acquire
  ↓
[Phase 2, filesystem — runs AFTER startForeground, BEFORE createSession]
  - resolveExternalRoot()                    — getExternalFilesDir(null);
                                             null → row 3 cleanup (B21)
  - hasEnoughStorage(estimatedBytes, externalRoot)
                                             via StatFs(externalRoot.absolutePath)
                                             (row 3: stopForeground, stopSelf, no manifest)
  - videosRoot = File(externalRoot, "videos") — passed to SessionStore (B21)
  ↓
serviceScope.launch {                        — switches to background dispatcher
    [startupMutex.withLock]
      ↓
    createSession(audioMode = audioMode)     ──── manifest now on disk ────
                                             — manifest persists audioMode (B18)
      ↓
    [detect collision or any post-manifest failure here →
        markTerminated(sessionId, USER_STOPPED, INIT_FAILED), stopForeground, stopSelf]
      ↓
    ServiceController.register()             (row 6 if returns false → terminal write, stopForeground, stopSelf)
      ↓
    [release startupMutex]
      ↓
    setupCamera() / bindToLifecycle()        (row 7 if throws → terminal write, stopForeground, stopSelf)
      ↓
    recording loop → RUNNING
}
```

Key ordering rules:
1. **Caller-side `startForegroundService` guard (Site A)** runs FIRST.
   Failure here means the service never starts; no row beyond 4a is
   reachable.
2. **Phase 1 (RAM-only) preflight** runs SECOND. Permission checks and
   audio-mode decision; FGS type bitfield computed. RAM-only —
   guaranteed sub-millisecond. CAMERA missing → row 5 cleanup.
3. **Service-side `startForeground` guard (Site B)** runs THIRD,
   IMMEDIATELY after Phase 1. Sub-5s latency from Site A is critical on
   Android 12+. FGS-type security failure (`SecurityException`, B18) →
   row 4b cleanup.
4. **`acquireWakeLock`** runs FOURTH — the service must be in the
   foreground state to safely hold a `PARTIAL_WAKE_LOCK` without
   `WAKE_LOCK_DETECTED` warnings.
5. **Phase 2 (filesystem) preflight** runs FIFTH, AFTER `startForeground`
   but BEFORE `createSession`. `StatFs` may stall on adopted/removable
   storage; safe now because FGS is already established. Failure → row
   3 cleanup (stopForeground + stopSelf, no manifest).
6. **`createSession` and `register`** run inside `startupMutex` AND
   inside `serviceScope.launch` (off the main thread) AFTER all
   preflight phases. `createSession` persists `audioMode` to the
   manifest (B18). Manifest is post-FGS, post-wake-lock, post-storage-
   check, pre-camera.
7. **Camera setup** is the LAST step before steady-state RUNNING.
   Failure here is row 7 (post-manifest, terminal-write required).

Every post-manifest failure path (rows 6, 7, 8, 9) uses the SAME
terminal-write API: `markTerminated(sessionId, USER_STOPPED,
<stopReason>)`.

Every pre-manifest failure path (rows 3, 4a, 4b, 5) writes NO manifest.
Pre-FGS failures (4a, 5): `stopSelf` only. Post-FGS pre-manifest failures
(3, 4b's SecurityException variant): `stopForeground + stopSelf`.

## Manifest Schema Delta

Add to `SessionManifest`:

```kotlin
enum class StopReason {
    USER,                  // notification STOP or UI stop
    LOW_STORAGE,           // mid-session free-space drop
    PERMISSION_REVOKED,    // CAMERA or RECORD_AUDIO revoked mid-session (B18)
    INIT_FAILED,           // post-manifest init failure (rows 6, 7)
    NONE                   // not yet stopped, COMPLETED, or non-user terminal (KILLED_*)
}

val stopReason: StopReason = StopReason.NONE

// B18 — locked at session start, immutable for session lifetime.
enum class AudioMode {
    VIDEO_AUDIO,           // RECORD_AUDIO granted at start; FGS started with CAMERA|MICROPHONE
    VIDEO_ONLY             // RECORD_AUDIO denied at start; FGS started with CAMERA only
}

val audioMode: AudioMode = AudioMode.VIDEO_ONLY  // safest legacy default
```

- `stopReason` is written by every terminal-write call site — see the
  migration table below for the per-site value.
- Phase 1.5 scan does not read `stopReason` for classification — it is
  descriptive, not load-bearing for the recovery decision matrix.
- Default `NONE` is correct for legacy manifests (read-tolerant). New
  writes always pass an explicit value; `NONE` in new code means
  "system-driven terminal, no user intent" (`KILLED_*` or merge-success
  `COMPLETED`).

### Atomic terminal-write API (replaces existing `markTerminated`)

The current `SessionStore.markTerminated(sessionId, terminated)` cannot
carry `stopReason`. Splitting that into two writes (terminated, then
stopReason) creates a window where a crash leaves the manifest with a
terminal `Terminated` but a stale `stopReason`. The fix is a single atomic
write site:

```kotlin
/**
 * Atomically writes terminated + stopReason in one manifest commit.
 * First-writer-wins: if `terminated` is already non-null, this is a no-op
 * AND returns the existing (terminated, stopReason) pair without rewriting.
 * The previously-written stopReason is preserved on no-op (race-loser does
 * not overwrite the winner's reason).
 *
 * Atomicity: implemented via the existing manifest temp-file write +
 * Files.move ATOMIC_MOVE rename (API 26+). Single fsync, single rename.
 *
 * Caller contract: callers MUST pass a non-null `stopReason` for every
 * non-KILLED terminal. Callers writing KILLED_BY_SYSTEM or KILLED_FORCE_STOP
 * (Phase 1.5 receivers + scanner) pass `StopReason.NONE`.
 */
suspend fun markTerminated(
    sessionId: String,
    terminated: Terminated,
    stopReason: StopReason = StopReason.NONE
): MarkTerminatedResult
```

### Migration table (per call site, B8 corrected)

The `stopReason` argument is dictated by **the meaning of the terminal
write**, not by which file does the writing. `StopReason.NONE` is
reserved for system-driven terminals (`KILLED_*`); user-driven and
explicit-stop terminals always carry a real `StopReason`.

| Call site | `Terminated` written | `StopReason` to pass |
|---|---|---|
| `RovaStopReceiver` — registry-live branch (delegates to service) | `USER_STOPPED` (via service) | `StopReason.USER` |
| `RovaStopReceiver` — registry-dead branch (writes manifest directly) | `USER_STOPPED` | `StopReason.USER` |
| `RovaTickReceiver` — empty-controller branch | `KILLED_BY_SYSTEM` | `StopReason.NONE` |
| `RecoveryScanner` — `stopRequested=true, T=null` branch | `USER_STOPPED` | `StopReason.USER` |
| `RecoveryScanner` — no-surviving-signal branch | `KILLED_FORCE_STOP` | `StopReason.NONE` |
| `RovaRecordingService.stopPeriodicRecordingAndMerge` — user STOP path | `USER_STOPPED` | `StopReason.USER` |
| `RovaRecordingService` — loop completion (post-merge success) | `COMPLETED` | `StopReason.NONE` |
| `RovaRecordingService` — `ServiceController.register` collision | `USER_STOPPED` | `StopReason.INIT_FAILED` |
| `RovaRecordingService` — post-manifest camera bind failure | `USER_STOPPED` | `StopReason.INIT_FAILED` |
| `RovaRecordingService` — permission gate fires | `USER_STOPPED` | `StopReason.PERMISSION_REVOKED` |
| `RovaRecordingService` — low-storage gate fires | `USER_STOPPED` | `StopReason.LOW_STORAGE` |

Rule of thumb: `StopReason.NONE` ⇔ no human intent (system kill or
merge-success completion). Every other terminal carries a precise reason.

This contradicts a previous draft's claim that all Phase 1.5 sites pass
`NONE`. `RovaStopReceiver` writes `USER_STOPPED` because the user pressed
STOP — that intent must be recorded as `StopReason.USER`. The recovery
scanner's `USER_STOPPED` branch likewise inherits the user's prior intent
(`stopRequested=true` on the manifest is the recorded user intent that
the scanner is honoring).

Migration impact:
- ADR 0005 contract preserved: the `Terminated` enum is unchanged, the
  classification matrix is unchanged, only the manifest carries an extra
  diagnostic field.
- Implementation MUST hold the per-session manifest write mutex (already
  serialized by `Dispatchers.IO.limitedParallelism(1)` per Phase 1.3) so
  the first-writer-wins decision is race-free.

### `MarkTerminatedResult`
```kotlin
sealed class MarkTerminatedResult {
    data class Wrote(val terminated: Terminated, val stopReason: StopReason) : MarkTerminatedResult()
    data class AlreadyTerminal(val existingTerminated: Terminated, val existingStopReason: StopReason) : MarkTerminatedResult()
    data class Failed(val cause: Throwable, val attempts: Int) : MarkTerminatedResult()
}
```

### Caller contract (B9 — failure handling)

Manifest writes are not free of failure. Under low-storage in particular,
writing the manifest temp file (`manifest.json.tmp`) can throw `IOException`
("No space left on device"). The atomic ATOMIC_MOVE rename can also fail
on adopted-storage if the target volume becomes unavailable mid-write.

Internal retry policy (inside `markTerminated`):
1. On `IOException` from temp-file write or rename, retry up to **2 more
   times** with 50ms backoff. Total attempt cap: 3.
2. On `Throwable` (kotlinx serialization, etc.), do not retry — wrap and
   return `Failed(cause, attempts=1)` immediately.
3. If all 3 attempts fail, return `Failed(cause, attempts=3)`.

Caller contract on each result:

| Result | Caller (USER_STOPPED stop paths) action |
|---|---|
| `Wrote` | Proceed: update notification, cancel alarms, attempt merge (per ordering rules above). |
| `AlreadyTerminal` | Proceed idempotently: existing terminal stays; loser logs the suppressed write; merge runs once via the controller-coordinated path so we don't double-merge. |
| `Failed` | **DO NOT proceed with merge.** Merge would consume more disk and almost certainly fail under the same conditions. Surface a degraded-state notification ("Stopped — recording state could not be saved. Will recover on next launch."). Cancel alarms. Call `RovaCrashReporter.recordException(cause)`. Then `stopForeground + stopSelf`. The session is left for Phase 1.5 cold-launch classification — manifest disk state will be whatever the partial write left, and ADR 0005's evidence-based classification handles it (likely treats it as `stopRequested=true, T=null` → `USER_STOPPED` if the receiver wrote `stopRequested` first, else `KILLED_FORCE_STOP`). |

Caller contract on `Failed` for `COMPLETED` (loop completion):

- `Failed` here means merge succeeded but the terminal-write failed.
  Treat as recovery-deferred: surface notification ("Recording finished
  but state save failed — will reconcile on next launch."). Phase 1.5
  scan plus Phase 1.7 export-recovery handle reconciliation. The merged
  artifact already exists (Tier 1: in `MediaStore` pending row, Tier 2/3:
  on disk); Phase 1.7 sees a non-`COMPLETED` manifest with a finalized
  `pendingUri`/output path and finalizes the manifest then.

Caller contract on `Failed` for Phase 1.5 receivers / scanner:

- `KILLED_*` writers do not retry caller-side beyond the API's internal
  3-attempt cap. On terminal `Failed`, log via `RovaCrashReporter`. The
  next cold launch retries classification — Phase 1.5 is naturally
  idempotent on first-writer-wins.

### Why not "retry forever"

The service must terminate cleanly even on disk-full. Indefinite retry
would hold the foreground service alive while the user is trying to free
space, defeating the purpose of low-storage termination. 3 attempts
covers transient I/O blips; persistent failure is correctly handed off
to the next cold launch.

## Per-Segment Safety Gates (NEW)

### Gate ordering (B12 — prerequisites first)

Three layers, in this strict order at the top of `recordSegment()`:

```
recordSegment() {
    // LAYER 1 — service-state prerequisites (existing guards, MUST run first)
    //   - isPeriodicActive == true
    //   - currentSessionId != null
    //   - currentSessionDir != null AND .exists()
    //   - sessionController != null AND ServiceController.isLive(currentSessionId)
    //   - recordingFinalized not yet completed for this entry
    //   → if any fail: return early (no-op abort, NO terminal write)

    // LAYER 2 — permission gate (NEW, B5)
    //   - checkSegmentPermissions()
    //   → CAMERA missing: terminal write USER_STOPPED + PERMISSION_REVOKED, return

    // LAYER 3 — storage gate (NEW, B6)
    //   - checkSegmentStorage(currentSessionDir, segmentDurationSec, bitrate)
    //   → fails: terminal write USER_STOPPED + LOW_STORAGE, return

    // RECORDING — actually record
    videoCapture.output.prepareRecording(...).start(executor, listener)
}
```

Layer 1 (prerequisites) runs FIRST because it answers the question "does
a session exist to record into?" Without a valid session, neither
permission nor storage failure is the right cause. A null `currentSessionDir`
or invalid `sessionController` is **invalid service state**, not a user-
or environment-driven termination.

Layer 1 failures are no-op aborts: `recordSegment` returns without writing
to the manifest. Reasons:
- The recording loop's outer state machine handles "session ended" via
  `isPeriodicActive` going false; if that's the cause, the orderly stop
  path is already running.
- If state is corrupt (e.g., `currentSessionId == null` but loop still
  running), there is no manifest to write to. `markTerminated` requires
  a `sessionId`. Crashing or writing to a wrong session is worse than
  silent abort.
- An invalid layer-1 state with a valid manifest on disk is a Phase 1.5
  recovery problem on next launch — not a service-runtime problem.

Layer 2 / 3 failures DO write terminal state (per §"Terminal-Write
Ordering"). They run only AFTER layer 1 confirms a valid session.

The recording-loop call site does not also gate. Gating in `recordSegment`
itself ensures every entry — including the C18 retry path that re-enters
`recordSegment` after a cancelled finalize — is checked uniformly through
all three layers.

If a layer-2 or layer-3 gate returns `Terminate(...)`:
1. The **terminal manifest write** runs immediately against
   `currentSessionId` (see §"Terminal-Write Ordering" below). This
   happens BEFORE any merge attempt and BEFORE the gate returns.
2. `recordSegment` returns without calling `prepareRecording` — no
   segment file is created on this entry.
3. Recording loop observes the terminated state via the manifest, exits
   the loop, and the merge path runs on already-collected segments
   (which may be empty — that's allowed, see B3).

### 1. Permission gate (audio-mode-aware per B18)
```kotlin
fun checkSegmentPermissions(audioMode: AudioMode): SegmentPermissionResult {
    val camera = checkSelfPermission(CAMERA) == GRANTED
    val audio = checkSelfPermission(RECORD_AUDIO) == GRANTED
    val notif = if (SDK_INT >= 33) checkSelfPermission(POST_NOTIFICATIONS) == GRANTED else true
    return when {
        !camera -> Terminate(PERMISSION_REVOKED)
        // B18: VIDEO_AUDIO session with mic revoked → terminate.
        // FGS type bitfield is immutable; cannot drop MIC type mid-stream.
        !audio && audioMode == AudioMode.VIDEO_AUDIO -> Terminate(PERMISSION_REVOKED)
        // VIDEO_ONLY session: mic revocation is a no-op.
        !notif -> ContinueWithLog
        else -> Continue
    }
}
```

The previous draft's `ContinueVideoOnly` mid-session degrade is removed —
see §"Audio Mode and FGS Type Coupling" for rationale.

CAMERA missing forces session termination. RECORD_AUDIO missing in a
`VIDEO_AUDIO` session also terminates (B18). The existing in-flight
segment (if any) finalizes via C18; the gate fires on the NEXT
`recordSegment` call, so a recording in progress when permission is
revoked completes its current file before termination.

### 2. Low-storage gate
```kotlin
/**
 * Layer 1 (prerequisites) has already verified `currentSessionDir != null
 * && currentSessionDir.exists()` before this is called. Therefore
 * `sessionDir` here is non-null and on the correct filesystem.
 */
fun checkSegmentStorage(sessionDir: File, segmentDurationSec: Int, bitrate: Int): Boolean {
    val estimate = (bitrate.toLong() * segmentDurationSec) / 8
    val headroom = FINALIZE_HEADROOM_MIB * 1024 * 1024
    // sessionDir lives under getExternalFilesDir(null)/videos/{sessionId}.
    // On adopted-storage / OEM-split layouts this can be a different
    // filesystem than filesDir, so we must measure the volume that
    // segments actually land on.
    val free = StatFs(sessionDir.absolutePath).availableBytes
    return free >= estimate + headroom
}

const val FINALIZE_HEADROOM_MIB = 50L  // muxer trailer + manifest write + filesystem overhead
```

Path correctness:
- The check measures the explicit `sessionDir` argument — guaranteed
  non-null by layer 1, guaranteed to be the actual segment-target
  filesystem (`getExternalFilesDir(null)/videos/{sessionId}` per
  `SessionStore`).
- `StatFs(filesDir.path)` is **wrong**: on adopted-storage and OEM-split
  layouts, `filesDir` and `getExternalFilesDir(null)` resolve to
  different mount points with independent free-space accounting.
- A null/missing session dir cannot reach this function (layer 1 caught
  it). If somehow it does (e.g., concurrent deletion), `StatFs(...)`
  itself throws `IllegalArgumentException` — the outer caller treats
  that as layer-1-failure-after-the-fact (no-op abort), NOT a low-
  storage event.

Failure terminates session with `stopReason = LOW_STORAGE`. Headroom
covers muxer trailer fsync + manifest atomic write + filesystem
overhead during merge.

### 3. Layer 1 prerequisites (clarified, B12)

These already exist in the current `recordSegment` body. 1.4 promotes
them to **layer 1** — the first thing that runs in `recordSegment`,
before permission and storage gates. They short-circuit `recordSegment`
without writing terminal state when state is invalid.

Required prerequisites:
- `_serviceState.value.isPeriodicActive == true` — session still active.
- `currentSessionId != null` — manifest target exists.
- `currentSessionDir != null && currentSessionDir.exists()` — session
  directory accessible.
- `sessionController != null` — process-singleton ServiceController
  still owns this session.
- `recordingFinalized` is NOT yet completed for this entry's iteration.

If any prerequisite fails, `recordSegment` returns immediately. NO
terminal write. The recording loop's outer state machine handles loop
exit via `isPeriodicActive` going false, or — if state is genuinely
corrupt — Phase 1.5 cold-launch recovery resolves on next launch.

## Terminal-Write Ordering (B3)

The eager-terminal rule applies to **`USER_STOPPED` flavors only**
(matrix rows 1, 6, 7, 8, 9 — `USER`, `INIT_FAILED`, `INIT_FAILED`,
`PERMISSION_REVOKED`, `LOW_STORAGE`). For these rows, the manifest
terminal write happens BEFORE any merge attempt; merge outcome does not
modify `terminated` or `stopReason`.

### `COMPLETED` is NOT eager (B7 carve-out)

Row 2 — loop count reached — does NOT participate in eager terminal-
write. ADR 0005 defines `Terminated.COMPLETED` as "merge succeeded": the
recovery scan refuses to append orphans on `T == COMPLETED` because the
merged artifact is the canonical recording. Writing `COMPLETED` before
merge would lie to the recovery scan: a process death between the eager
write and merge completion would surface a "complete" session with no
merged file. ADR 0005's append-on-non-COMPLETED rule would also skip
recovering trailing orphans.

For row 2:
1. Loop completion → recording loop exits.
2. Manifest stays `terminated = null`.
3. Merge runs.
4. **Only on merge-success commit point** does `markTerminated(sessionId,
   COMPLETED, NONE)` write.
5. If merge fails or process dies before step 4: manifest is still
   `terminated = null`. Phase 1.5 cold-launch sees the session and
   classifies via the existing matrix rules (`stopRequested` may or may
   not be set; receiver-driven `KILLED_BY_SYSTEM` may apply if the
   recording-window WATCHDOG was still pending).

This preserves ADR 0005's invariant: `COMPLETED` ⇔ merged artifact exists
on disk (or in `MediaStore` pending row, per Phase 1.6/1.7 tier rules).

### `KILLED_BY_SYSTEM` / `KILLED_FORCE_STOP` are not eager either

Rows 11 and 12 are written by Phase 1.5 receivers and recovery scanner
on cold launch, not by `RovaRecordingService` stop paths. Their ordering
is governed by ADR 0005, unchanged.

### Stop-path skeleton (USER_STOPPED flavors only)

```
1. detectStopCondition()       — user STOP, gate fires, init-fail
2. markTerminated(sid, USER_STOPPED, stopReason)   — atomic, first-writer-wins
3. updateNotification(...)     — driven by markTerminated result
4. cancelAlarmsForSession()    — alarms scheduled for this session no longer fire
5. mergeIfPossible()           — best-effort, may be skipped or fail (skipped on segments.isEmpty)
6. stopForeground + stopSelf
7. onDestroy → releaseResources
```

### Loop-completion path (`COMPLETED`)

```
1. recordingLoopExitsNaturally()
2. cancelAlarmsForSession()
3. mergeSegments()             — must succeed for COMPLETED
4. markTerminated(sid, COMPLETED, NONE)   — only on merge-success commit
5. updateNotification("Recording finished")
6. stopForeground + stopSelf
7. onDestroy → releaseResources
```

If step 3 fails: leave `terminated = null`, surface error to user, defer
to Phase 1.5 / Phase 1.7 recovery on next launch. Do NOT fall back to
`USER_STOPPED + LOW_STORAGE`/etc — the failure was not user-driven.

Step 2 happens BEFORE step 5 in the USER_STOPPED skeleton. Critical
properties (USER_STOPPED only):

1. **Zero-segment terminal is valid.** If the session has zero usable
   segments (e.g., gate fired before the first segment finalized),
   `markTerminated(USER_STOPPED, stopReason)` still writes. Merge is
   skipped — there is nothing to merge — but the manifest is terminal.
   Phase 1.5 sees `terminated=USER_STOPPED, segments=[]` and classifies
   per the ADR 0005 matrix (`AUTO_DISCARD_ELIGIBLE` if no orphans).

2. **Merge failure does not roll back terminal state.** If
   `VideoMerger.mergeSegments` throws or the muxer fails, the manifest
   stays `terminated=USER_STOPPED, stopReason=...`. Phase 1.7 export-
   recovery owns the merge-failure cleanup; 1.4 does not retry merge.

3. **Process death between step 2 and step 5.** Phase 1.5 sees a fully
   terminal manifest plus orphan segments on disk. ADR 0005 rules:
   `T != COMPLETED` → append valid contiguous orphan prefix. The merge
   never happened, but no data is lost.

4. **First-writer-wins on race.** If two stop conditions fire near-
   simultaneously (e.g., user STOP + low-storage), `markTerminated`
   returns `AlreadyTerminal` to the loser. The loser's `stopReason` is
   discarded (winner's stays). Both threads then proceed to merge / stop
   idempotently.

5. **Init-failure paths use the same API.** Rows 6 and 7 call
   `markTerminated(sid, USER_STOPPED, INIT_FAILED)` directly; no merge is
   attempted (zero segments by definition).

### Why not lazy-write terminal at end of merge

Today's code calls `markTerminated` only after merge success
(`COMPLETED`) or in receiver paths. That makes mid-flight terminal writes
fragile: any code path that does not reach merge-success leaves
`terminated=null`. Phase 1.5 then classifies via fallback rules. Eager
terminal writing in 1.4 means the manifest is the source of truth at all
times, and Phase 1.5's fallback rules become true edge cases (only
process-kill paths) rather than common.

## Recovery Signal Source of Truth (B4)

`stopNeedsRecovery` is a **process-local in-memory flag** in
`RovaRecordingService`. After process death it is gone. Phase 1.5
recovery scan CANNOT see it — recovery classifies only from manifest
fields and disk evidence (per ADR 0005).

### Consequences for 1.4

1. `stopNeedsRecovery` is **not** a durable invariant. Whether
   `releaseResources` clears it or preserves it has no effect on Phase 1.5
   classification across process boundaries. The previous draft's Open
   Question #4 and Invariant #6 are withdrawn.

2. The flag's only purpose is **in-process steering** of the current
   service instance: which retry path to take, whether to skip merge, etc.
   Its lifecycle is a local concern of the recording loop.

3. The "OS-killed-after-stop ambiguity" the previous draft worried about
   is already handled by ADR 0005's matrix. Specifically: a manifest with
   `stopRequested=true, terminated=null` on cold launch is classified as
   `USER_STOPPED` by Phase 1.5's pre-flight stop branch. No durable flag
   is needed.

4. With the eager terminal-write rule from §"Terminal-Write Ordering",
   the post-stop process-kill case is unambiguous regardless: the manifest
   is already terminal before any kill could matter.

### What 1.4 does about `stopNeedsRecovery`

Left as-is, scoped to its current in-process retry-steering role.
1.4 introduces no new uses, no new clearing rules, no new static checks
on it. The previous draft's `checkStopNeedsRecoveryReset` is dropped.

## Foreground-Service Lifecycle (Android 12+)

### Start path (B10 + B11 — two guard sites)

`ForegroundServiceStartNotAllowedException` is an `IllegalStateException`
subclass introduced in **API 31 (Android 12)**. App `minSdk` is 24.

Two separate throw sites on Android 12+:

| Site | Caller | Throws when | Service state at throw |
|---|---|---|---|
| **A. Caller-side** | `Context.startForegroundService(...)` from UI / receiver | App not in approved foreground/exemption window | Service NOT instantiated; `onCreate`/`onStartCommand` never run |
| **B. Service-side** | `Service.startForeground(...)` inside `onStartCommand` | Service started but FGS-type missing or restriction tripped after start | Service exists; manifest does NOT yet (pre-manifest init window) |

**Site A is the primary path on Android 12+.** Site B is defense-in-
depth. Draft 3 guarded only B; Draft 4 guards both.

#### Site A — caller-side guard (PRIMARY, B11)

Lives at every call site of `ContextCompat.startForegroundService(...)` —
today: `MainActivity` start button, `RovaController` programmatic start,
any other app-internal start trigger. Pattern:

```kotlin
fun startRecordingSession(context: Context, intent: Intent): StartResult {
    return try {
        ContextCompat.startForegroundService(context, intent)
        StartResult.Started
    } catch (e: IllegalStateException) {
        val isFgsRestricted =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            e is android.app.ForegroundServiceStartNotAllowedException
        if (isFgsRestricted) {
            RovaCrashReporter.recordException(e, "FGS start not allowed (caller, API 31+)")
            StartResult.Blocked(reason = StartBlocked.FGS_RESTRICTED)
        } else {
            RovaCrashReporter.recordException(e, "FGS start ISE (caller)")
            StartResult.Blocked(reason = StartBlocked.UNKNOWN_ISE)
        }
    }
}

sealed class StartResult {
    data object Started : StartResult()
    data class Blocked(val reason: StartBlocked) : StartResult()
}
enum class StartBlocked { FGS_RESTRICTED, UNKNOWN_ISE }
```

Caller-side cleanup is trivial: no service instance exists, no manifest
exists, no notification was posted. UI surfaces the blocked reason
directly to the user ("Cannot start recording — app must be in
foreground"). No matrix-row classification applies (the session never
existed).

#### Site B — service-side guard (DEFENSE-IN-DEPTH, B10 + B18 + B20)

Inside `RovaRecordingService.onStartCommand` (matrix row 4b cleanup
path). Reachable only if Site A succeeded but the platform throws on
the inner `startForeground(...)` call (FGS-deadline miss, FGS-type
mismatch on Android 14+, OEM quirks).

The `fgsType` argument is computed from `audioMode` (decided in Phase 1
preflight per B18 + B19). Hardcoding `CAMERA | MICROPHONE` would crash
under Android 14+ when `RECORD_AUDIO` is denied (B20).

```kotlin
val notification = createNotification(initialText)

// B18: FGS type bitfield from pre-FGS audio-mode decision.
val fgsType = when (audioMode) {
    AudioMode.VIDEO_AUDIO -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                              ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
    AudioMode.VIDEO_ONLY  -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
}

try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        startForeground(NOTIFICATION_ID, notification, fgsType)
    } else {
        startForeground(NOTIFICATION_ID, notification)
    }
} catch (e: IllegalStateException) {
    val isFgsRestricted =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        e is android.app.ForegroundServiceStartNotAllowedException
    // Pre-manifest init window. startForeground runs immediately in
    // onStartCommand (B15: required for Android 12+ FGS deadline) —
    // BEFORE createSession. No manifest to roll back.
    // See §"Init-window write order".
    val tag = if (isFgsRestricted) "FGS start not allowed (service, API 31+)" else "FGS start ISE (service)"
    RovaCrashReporter.recordException(e, tag)
    stopSelf()
    return START_NOT_STICKY
} catch (e: SecurityException) {
    // B18 + B20: Android 14+ throws SecurityException when FGS type
    // does not match granted permissions (e.g., MICROPHONE type without
    // RECORD_AUDIO). Pre-FGS audio-mode decision should prevent this,
    // but defense-in-depth catch is required.
    RovaCrashReporter.recordException(e, "FGS type SecurityException (audioMode=$audioMode)")
    stopSelf()
    return START_NOT_STICKY
}
```

#### Why the SDK-gated `is`-check is safe on pre-S

- Class `ForegroundServiceStartNotAllowedException` referenced ONLY
  inside `Build.VERSION.SDK_INT >= S` branch. ART/dex verifier does
  lazy class loading; symbol resolved only when branch executes.
- Kotlin `e is X` compiles to single `instanceof` op using class
  constant. R8 + Android dex emit reference; runtime loads class only
  when bytecode runs — by construction only on S+.
- Parent `IllegalStateException` exists at all API levels; pre-S devices
  throwing generic ISE fall through `isFgsRestricted = false` branch.

#### Lint

`checkFGSStartGuarded` (updated) enforces:

1. Every `Context.startForegroundService` / `ContextCompat.startForegroundService`
   reference appears inside a `try { ... } catch (e: IllegalStateException)`
   block that contains both `Build.VERSION.SDK_INT >= Build.VERSION_CODES.S`
   and `e is ForegroundServiceStartNotAllowedException` references, with
   the SDK gate appearing on a lower line than the `is` check.
2. Same predicate for every `startForeground` reference inside service
   classes.

Both checks share the line-ordering scanner from
`checkRecoveryReceiverCounter`.

### START_NOT_STICKY rationale

Confirmed: every `onStartCommand` returns `START_NOT_STICKY`. We never want
the OS to redeliver an Intent-less restart with no `EXTRA_SESSION_ID` —
that would create an empty foreground service.

### `onTaskRemoved` (existing, unchanged)
- Active work present → keep service alive (UI surface only is detached).
- No active work → `stopSelf`.

### Notification update path (existing)
- `notify(SAME_ID)` does not extend FGS deadline.
- `POST_NOTIFICATIONS` revocation on 33+ → notify silently no-ops; service
  continues. UI re-prompts on next foreground.

## Audio Mode and FGS Type Coupling (B18)

Android 14 (API 34) tightened FGS-type validation: a service that
declares `FOREGROUND_SERVICE_TYPE_MICROPHONE` and lacks `RECORD_AUDIO`
permission throws `SecurityException` at `startForeground` time. The
FGS-type bitfield is committed at `startForeground` and is effectively
immutable for the service lifetime. Audio-mode decision must therefore
happen **before** `startForeground` is called.

### Audio mode is locked at session start

Audio mode is decided once, in the pre-FGS preflight phase (see B19):

```kotlin
val audioGranted = checkSelfPermission(RECORD_AUDIO) == GRANTED
val audioMode = if (audioGranted) AudioMode.VIDEO_AUDIO else AudioMode.VIDEO_ONLY
```

`audioMode` is then:
1. Used to compute the `startForeground` type bitfield (see below).
2. Used to configure the CameraX `Recorder` / `Recording` builder
   (with-audio vs without-audio).
3. **Persisted** in the session manifest at `createSession` time.
   Immutable for the session lifetime.

### FGS type selection

```kotlin
val fgsType = when (audioMode) {
    AudioMode.VIDEO_AUDIO -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                              ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
    AudioMode.VIDEO_ONLY  -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
}

if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
    startForeground(NOTIFICATION_ID, notification, fgsType)
} else {
    startForeground(NOTIFICATION_ID, notification)
}
```

Pre-Q (API ≤ 28) doesn't accept the type argument; mic permission
check still applies in CameraX.

### Mid-session permission revocation (interaction with audio mode)

The per-segment permission gate (§"Per-Segment Safety Gates" layer 2)
becomes audio-mode-aware:

```kotlin
fun checkSegmentPermissions(audioMode: AudioMode): SegmentPermissionResult {
    val camera = checkSelfPermission(CAMERA) == GRANTED
    val audio = checkSelfPermission(RECORD_AUDIO) == GRANTED
    val notif = if (SDK_INT >= 33) checkSelfPermission(POST_NOTIFICATIONS) == GRANTED else true
    return when {
        !camera -> Terminate(PERMISSION_REVOKED)
        !audio && audioMode == AudioMode.VIDEO_AUDIO -> {
            // Session was started with MIC FGS type. Cannot drop the FGS
            // type mid-stream; cannot legitimately hold MIC type without
            // permission. Must terminate. (B18.)
            Terminate(PERMISSION_REVOKED)
        }
        !audio && audioMode == AudioMode.VIDEO_ONLY -> Continue
        // ^ session started without mic; revocation is a no-op.
        !notif -> ContinueWithLog
        else -> Continue
    }
}
```

Behavior:
- **`VIDEO_ONLY` session, mic revoked mid-session:** no-op. The session
  was never recording audio; no FGS-type lie is possible.
- **`VIDEO_AUDIO` session, mic revoked mid-session:** terminate with
  `USER_STOPPED + PERMISSION_REVOKED`. This is a NEW termination cause
  (Draft 5 only triggered on CAMERA loss). Justification: the FGS type
  bitfield is immutable; degrading to video-only mid-stream would leave
  the FGS holding `FOREGROUND_SERVICE_TYPE_MICROPHONE` while not
  actively recording audio, which Android 14+ may flag as a misuse.
  Safer to terminate cleanly.
- **`VIDEO_AUDIO` session, camera revoked mid-session:** terminate
  (existing, unchanged).
- **Audio newly granted mid-session for a `VIDEO_ONLY` session:** no-op.
  Audio mode is immutable; the user can stop and restart for a new
  `VIDEO_AUDIO` session.

### Replaces the previous `ContinueVideoOnly` mid-session degrade

Draft 5's "RECORD_AUDIO revoked → degrade to video-only" behavior was
unsafe under Android 14+ FGS-type rules: the type bitfield was fixed at
`startForeground` time. B18 corrects this by deciding audio mode ONCE,
pre-FGS, and treating mid-session mic loss in a `VIDEO_AUDIO` session
as a hard termination.

The old code path that called CameraX without audio when mic was missing
inside `recordSegment` is REMOVED in 1.4. CameraX configuration is
driven by `audioMode` from the manifest, set once at session start.

### Caller-side audio-mode policy (UI)

UI (MainActivity / record button) checks RECORD_AUDIO permission before
calling `startForegroundService`. Product policy options:
- **Audio optional (recommended):** if mic missing, show a "Record
  without audio?" prompt; on user OK, start session in `VIDEO_ONLY`
  mode.
- **Audio required:** if mic missing, block start; show "Audio
  permission required" and request grant via runtime permission flow.

This ADR adopts "audio optional" to align with existing degrade
behavior. UI surfaces the chosen mode to the user before the service
starts.

### Static check

`checkAudioModeFgsTypeMatch` — the FGS-type bitfield computation site
must reference `audioMode` (or the `AudioMode` enum) before any
`startForeground` call within the same function. Forbidden literal:
`FOREGROUND_SERVICE_TYPE_MICROPHONE` not gated by an audio-mode
condition. Lint scans for the gate-before-`startForeground` line
ordering.

### `SecurityException` defense-in-depth

Even with the pre-FGS audio decision, wrap `startForeground` in a
`try/catch (SecurityException)` for OEM/version edge cases. On catch,
treat as row-4b cleanup (pre-manifest, stopSelf, no manifest).
`SecurityException` does not extend `IllegalStateException`, so it
needs its own catch arm:

```kotlin
} catch (e: SecurityException) {
    RovaCrashReporter.recordException(e, "FGS type SecurityException (audioMode=$audioMode)")
    stopSelf()
    return START_NOT_STICKY
}
```

## Camera Setup Cancellation & Teardown

### Mid-flight cancellation
`setupCamera()` already runs under `setupMutex`. Cancellation paths:
- `serviceScope.cancel()` (in `releaseResources`) → coroutine cancelled mid-
  await on `ProcessCameraProvider.getInstance()`.
- `CameraX` callback may fire AFTER cancellation. ADR 0002 §"Lifecycle rule"
  governs surface release — callback owns surface.close().

### Invariant
Every `setupCamera` exit path must:
1. Either reach `bindToLifecycle` AND set `CameraState=READY`,
2. Or reach `cameraProvider?.unbindAll()` in a `finally`/catch.

### Teardown
`releaseResources()` is the single teardown site:
- `cameraProvider?.unbindAll()` — never throws (try/catch already in place).
- Dummy surface released.
- Wakelock released (see below).
- Alarms cancelled, ServiceController unregistered.
- `serviceScope.cancel()` — propagates to all child jobs.

### Force-reconfigure path
`forceReconfigureCamera` already unbinds before re-bind. Existing pattern;
no change.

## WakeLock Ownership

### Single-instance invariant
At any time the service holds **at most one** `PARTIAL_WAKE_LOCK` instance.
Re-acquire via `wakeLock?.takeIf { it.isHeld } ?: powerManager.newWakeLock(...)` —
existing `acquireWakeLock` already idempotent on null/held check.

### Release guarantees
1. `releaseResources()` calls `releaseWakeLock()` unconditionally — must be
   exception-safe (try/catch around `release()`; Android occasionally throws
   `RuntimeException("WakeLock under-locked")`).
2. Inter-segment relax path: `shouldRelaxWakeLock` → release; tick-receiver
   wakes the device; `acquireWakeLock` on tick callback. If the process dies
   between release and re-acquire, ADR 0005 owns recovery.
3. Receivers (`RovaTickReceiver`, `RovaStopReceiver`) do NOT acquire app
   wakelocks — broadcast `goAsync()` extends the implicit broadcast wakelock
   long enough for receiver work.

### Non-leak invariant
On every `releaseResources` path, `wakeLock` ends up `null`. Static check
candidate: every `acquireWakeLock` reference in service code has a paired
`releaseWakeLock` reachable on every exit (currently 4 acquire/4 release —
verifiable with grep+counter).

## Permission Revocation Mid-Session

### Detection
Per-segment via `checkSegmentPermissions()` (above). No callback-driven
detection — Android does not push permission revocation events to the app;
the only signal is failed `checkSelfPermission` or thrown `SecurityException`
from CameraX. Polling per segment is sufficient because:
- Segment cadence = session cadence; user expects up-to-segment latency.
- CameraX SecurityException is handled in existing recordSegment try/catch.

### CAMERA revoked
1. `checkSegmentPermissions` returns `Terminate(PERMISSION_REVOKED)`.
2. Service transitions `RUNNING → STOPPING`.
3. `terminated = USER_STOPPED`, `stopReason = PERMISSION_REVOKED` written via
   first-writer-wins markTerminated.
4. Notification updated: "Stopped — camera permission revoked. Re-grant in
   Settings."
5. Existing collected segments merged via existing path.
6. Service stops orderly.

### RECORD_AUDIO revoked (B18-aware)
- **`VIDEO_AUDIO` session:** terminate with `USER_STOPPED +
  PERMISSION_REVOKED`. FGS-type bitfield was committed at start with
  `MICROPHONE`; cannot legitimately hold it without permission.
  Notification: "Stopped — microphone permission revoked. Re-grant in
  Settings."
- **`VIDEO_ONLY` session:** no-op. Mic was never granted at start; the
  session is already audio-less. No notification update.

The previous draft's "degrade to video-only mid-session" is removed
(B18). Audio mode is locked at session start.

### POST_NOTIFICATIONS revoked (33+)

**Locked policy (per Round-7 GO):** POST_NOTIFICATIONS is treated as
**hard-block at session start** on API 33+. The service cannot legitimately
hold an FGS without a visible notification — a session that runs invisibly
is a UX trap and contradicts FGS-policy expectations.

- **Pre-flight (caller side, MainActivity):** if `POST_NOTIFICATIONS` is
  denied on API 33+, UI requests permission via the runtime flow. On
  permanent deny ("Don't allow"), UI shows a settings-deep-link dialog
  and does NOT call `startForegroundService`. The session never starts.
- **Mid-session revocation (33+):** rare but possible if user toggles
  via Settings during a session. Notification update calls silently
  no-op (Android behavior). Service continues; the session does NOT
  terminate (segment data is more valuable than visibility-policy
  pedantry once recording is in progress). UI re-prompts on next
  foreground entry. Open Question #3 closed: this matches the
  recommendation "yes — block at start" for the new-session path
  while keeping mid-session tolerant.

The per-segment gate (§"Per-Segment Safety Gates") still emits
`ContinueWithLog` for mid-session POST_NOTIFICATIONS denial — no new
matrix row.

### Pre-flight CAMERA revocation
At `onStartCommand` entry: if `CAMERA` not granted, abort init window
without writing manifest. UI is responsible for not requesting start when
permissions are missing — service-side guard is belt-and-braces.

## Low-Storage Behavior

### External-storage root resolution (B21)

`Context.getExternalFilesDir(null)` can return `null` when external app
storage is mounted read-only or unavailable (ejected SD, USB OTG, OEM
quirk during boot). Both the storage preflight (Phase 2) AND
`SessionStore` derive their roots from the same call; if they resolve
differently or one resolves to `null`, the storage check measures the
wrong volume or crashes.

A single root-resolution helper runs before storage preflight and is
shared with `SessionStore`:

```kotlin
/**
 * Returns the external app-private storage root, or null if unavailable.
 * Used by both the Phase 2 storage preflight and SessionStore to
 * guarantee they reason about the same volume (B21).
 */
fun resolveExternalRoot(context: Context): File? =
    context.getExternalFilesDir(null)

// In onStartCommand Phase 2 (post-FGS, pre-manifest):
val externalRoot = resolveExternalRoot(this)
if (externalRoot == null) {
    RovaLog.e("Phase 2: external storage unavailable")
    // Row 3 cleanup: post-FGS, pre-manifest. Cannot create a session.
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
    return START_NOT_STICKY
}

val videosRoot = File(externalRoot, "videos")
if (!hasEnoughStorage(externalRoot, estimatedBytes)) {
    // Row 3 cleanup as before.
    ...
}

// SessionStore is constructed with the resolved root, NOT a fresh
// getExternalFilesDir(null) call. Both reason about the same File.
val sessionStore = (application as RovaApp).sessionStore
require(sessionStore.videosRoot == videosRoot) {
    "SessionStore videosRoot mismatch with preflight resolved root"
}
```

`SessionStore` already accepts a root via its `internal constructor(rootDirArg: File)`
(Phase 1.5 refactor). The 1.4 implementation passes the resolved
`videosRoot` through `RovaApp` so all consumers see the same File.

Failure modes covered:
- `getExternalFilesDir(null)` returns `null` → row 3 cleanup, no
  manifest, FGS released cleanly.
- Storage preflight measures one root; SessionStore writes to a
  different root → impossible by construction (single source).

### Pre-flight (existing, B21-aware)
At session start: `hasEnoughStorage(externalRoot, estimatedBytes)` via
`StatFs(externalRoot.absolutePath)`. Aborts init window with no
manifest write. Row 3 path. Always uses the resolved non-null root.

### Mid-session (NEW)
Per `checkSegmentStorage` above. Threshold:
`(bitrate * segmentDurationSec / 8) + FINALIZE_HEADROOM_MIB`.

### App-kill before/during finalize
- Before `MediaMuxer.stop()`: muxer state lost; segment file may be partial.
  C18 finalize-timeout path applies — segment treated as orphan, defer to
  recovery (ADR 0005).
- During merge: Phase 1.7 export-recovery owns. Manifest carries
  `exportState`; cold launch finishes export from segments if export was
  Tier 1 + reachable.

## Concurrency Invariants

Carrying forward from prior phases. 1.4 must preserve:

1. `ServiceController` is process-singleton via `AtomicReference + CAS`
   (Phase 1.3).
2. Receivers increment `activeReceiverWork` synchronously **before**
   `goAsync()` (Guard B, Phase 1.5; static-checked).
3. `RovaApp.startupMutex` serializes `createSession + register` against
   recovery-scan drain (Phase 1.5).
4. `recoveryScanRan` latch resets on `runRecoveryScan` throw (Guard A,
   Phase 1.5).
5. **NEW (1.4):** `releaseResources()` is the single site that calls
   `cancelAlarmsAndUnregister` + `ServiceController.unregister` + wakelock
   release. Static-checked via line-count predicate (one call site each).
6. **NEW (1.4):** Every non-system terminal write goes through
   `markTerminated(sessionId, terminated, stopReason)` in a SINGLE atomic
   manifest commit. Static-checked: no call site writes `terminated`
   independently from `stopReason`. (Replaces the previous draft's
   `stopNeedsRecovery` invariant — that flag is in-memory only and is not
   a durable recovery signal; see §"Recovery Signal Source of Truth".)
7. **NEW (1.4):** `serviceScope = SupervisorJob` (verify) — child failure
   does not cancel scope until `releaseResources` calls `serviceScope.cancel()`.
8. **NEW (1.4):** `setupMutex` excludes from `setupCamera`, `forceReconfigureCamera`,
   and (NEW) `stopCameraPreview` to prevent racing teardown vs. setup.
9. **NEW (1.4):** Every `USER_STOPPED` terminal write happens BEFORE the
   merge attempt for that session. `COMPLETED` is the exception (B7
   carve-out): it is written ONLY at the merge-success commit point so
   ADR 0005's `T == COMPLETED ⇔ merged artifact exists` invariant holds.
   `KILLED_BY_SYSTEM` and `KILLED_FORCE_STOP` are written by Phase 1.5
   receivers/scanner on cold launch and are not subject to this rule.

## Acceptance Criteria

### Static checks (Gradle / `preBuild`)
1. `checkSingleReleaseResourcesCallSites` — exactly one
   `cancelAlarmsAndUnregister`, one `ServiceController.unregister`, one
   `releaseWakeLock` reference outside of `releaseResources()` body (each
   appears at most once each in the function and zero elsewhere in the
   service file).
2. `checkSegmentGates` — `recordSegment` body contains all three layers
   in the required order (B12):
   - Layer 1 marker: at least one reference to each of `currentSessionId`,
     `currentSessionDir`, `isPeriodicActive`, `sessionController` BEFORE
     line of `checkSegmentPermissions`.
   - Layer 2: `checkSegmentPermissions` reference BEFORE
     `checkSegmentStorage`.
   - Layer 3: `checkSegmentStorage` reference BEFORE any
     `prepareRecording` reference.
   Line-index ordering scanner mirrors the Phase 1.5
   `checkRecoveryReceiverCounter` style.
3. `checkFGSStartGuarded` — guards both call sites (B10 + B11 + B20):
   - **Caller side:** every `Context.startForegroundService` /
     `ContextCompat.startForegroundService` reference inside a
     `try { ... } catch (e: IllegalStateException) { ... }` block,
     with `Build.VERSION.SDK_INT >= Build.VERSION_CODES.S` and
     `e is ForegroundServiceStartNotAllowedException` references in
     that line order.
   - **Service side:** every `startForeground(` reference inside service
     classes wrapped in **two** catch arms:
     - `catch (e: IllegalStateException)` (FGS-deadline / FGS-restricted),
     - `catch (e: SecurityException)` (Android 14+ FGS-type/permission
       mismatch, B18 + B20).
     Both arms must be present; lint flags missing
     `SecurityException` arm.
   All checks share the line-ordering scanner from
   `checkRecoveryReceiverCounter`. Documented opt-out marker
   (`// fgs-guard-opt-out:`) for any rare exempt case.
4. `checkAtomicTerminalWrite` — replaces the previous draft's
   `checkStopReasonOnTerminalWrite`. Every `markTerminated(...)` call
   site passes exactly three arguments (`sessionId`, `terminated`,
   `stopReason`). Two-arg form is forbidden after migration. The
   `stopReason` value is dictated by the migration table in §"Migration
   table", NOT by which file does the writing:
   - `KILLED_BY_SYSTEM` / `KILLED_FORCE_STOP` writers (`RovaTickReceiver`
     empty-controller branch; `RecoveryScanner`'s no-surviving-signal
     branch) pass `StopReason.NONE`.
   - `COMPLETED` writer (loop-completion post-merge in
     `RovaRecordingService`) passes `StopReason.NONE`.
   - All `USER_STOPPED` writers — including `RovaStopReceiver` (both
     branches) and `RecoveryScanner`'s `stopRequested=true` branch —
     pass a non-`NONE` value (`USER`, `INIT_FAILED`,
     `PERMISSION_REVOKED`, or `LOW_STORAGE` per the migration table).
   Static check: regex match for `markTerminated\(` followed by a
   triple-arg form, AND a forbidden-pair scan that flags every
   `markTerminated(..., Terminated.USER_STOPPED, StopReason.NONE)`
   literal pair (any source file). The forbidden-pair scan is the
   load-bearing check — earlier drafts of this lint contradicted the
   migration table by saying "Phase 1.5 sites pass NONE".
5. `checkUserStoppedBeforeMerge` — within `RovaRecordingService`, every
   `markTerminated(...)` call site whose `terminated` argument is
   `Terminated.USER_STOPPED` places that call on a lower line number than
   any reachable `mergeSegments` / `VideoMerger` reference in the same
   function or stop-path. Captures B3 ordering for `USER_STOPPED` only.
   The `Terminated.COMPLETED` call site (loop-completion path) is the
   inverse: lint requires `COMPLETED` write to appear AFTER the merge
   call, on the merge-success branch (B7). Documented opt-out marker
   (`// terminal-ordering-opt-out:`) for any rare exempt case.
6. `checkStorageGateUsesSessionDir` — `checkSegmentStorage` body must
   reference `currentSessionDir` and must NOT reference `filesDir`.
   Captures B6 at compile time.
7. `checkInitWindowOrdering` — within `RovaRecordingService.onStartCommand`,
   line ordering MUST be (B19):
   - `checkSelfPermission(...CAMERA...)` BEFORE `startForeground(`
   - `checkSelfPermission(...RECORD_AUDIO...)` BEFORE `startForeground(`
     (Phase 1 audio-mode decision)
   - any `audioMode` reference BEFORE `startForeground(`
   - `startForeground(` BEFORE `acquireWakeLock(`
   - `acquireWakeLock(` BEFORE `hasEnoughStorage(` / `StatFs(`
     (Phase 2 storage check post-FGS)
   - `hasEnoughStorage(` / `StatFs(` BEFORE `createSession(`
     (still pre-manifest)
   - `createSession(` BEFORE `ServiceController.register(`
   - `ServiceController.register(` BEFORE `setupCamera(`
   Lint scans the line indices; opt-out marker
   `// init-window-ordering-opt-out:` for any rare exempt case.
8. `checkAudioModeFgsTypeMatch` — every `startForeground(...)` call site
   that passes a type-bitfield argument must reference `audioMode` (or
   the `AudioMode` enum) on a lower line within the same function.
   Forbidden: a `FOREGROUND_SERVICE_TYPE_MICROPHONE` literal on a line
   not preceded by an `audioMode == AudioMode.VIDEO_AUDIO` (or
   equivalent) gate. Captures B18 at compile time.
9. `checkExternalRootShared` — the only `getExternalFilesDir(null)`
   reference in production code lives inside `resolveExternalRoot(...)`.
   Every other call site reads its root via `resolveExternalRoot` /
   `RovaApp.videosRoot` / `SessionStore.videosRoot`. Lint flags any
   `getExternalFilesDir(null)` reference outside the helper. Captures
   B21 at compile time and prevents drift between preflight and
   `SessionStore`.

### Unit tests
1. `ForegroundServiceStartNotAllowedException` thrown by `startForeground`
   stub → no manifest written, service stops cleanly (matrix row 4).
2. `ServiceController.register` returns false (collision) →
   `markTerminated(USER_STOPPED, INIT_FAILED)` written atomically; manifest
   on disk; no merge attempted; service stops (matrix row 6).
3. Camera bind throws in post-manifest init → `markTerminated(USER_STOPPED,
   INIT_FAILED)` written; manifest stays on disk; service stops (row 7).
4. CAMERA permission denied at `recordSegment` entry →
   `markTerminated(USER_STOPPED, PERMISSION_REVOKED)` written BEFORE merge;
   merge runs on whatever segments exist (possibly zero) (row 8).
5. Storage drops below threshold mid-session →
   `markTerminated(USER_STOPPED, LOW_STORAGE)` written BEFORE merge;
   existing segments merged (row 9).
6. **Atomic-write race:** two threads call `markTerminated` simultaneously
   with different `stopReason` values. First-writer's pair persists;
   second-writer gets `AlreadyTerminal` with the first writer's pair.
7. **Zero-segment terminal:** session with `segments=[]` reaches a stop
   gate. `markTerminated` writes terminal; merge skipped (no segments);
   manifest is `terminated=USER_STOPPED, stopReason=...`. Phase 1.5 scan
   classifies as expected (terminal + no orphans →
   `AUTO_DISCARD_ELIGIBLE`).
8. **Process death between terminal-write and merge:** simulate by
   stopping after `markTerminated` returns. Cold launch via Phase 1.5
   classifies the manifest as already-terminal; orphan-segment append
   rules from ADR 0005 apply unchanged.
9. WakeLock acquired/released balance: simulate 100 acquire/release cycles
   under cancellation; final state `wakeLock == null`.
10. `onTaskRemoved` with active session → service alive; without → stopSelf
    (matrix row 10).
11. **Storage-gate filesystem correctness:** `checkSegmentStorage` is
    called when `currentSessionDir` and `filesDir` resolve to different
    `StatFs` results (mocked). The decision must be driven by
    `currentSessionDir` free space, not `filesDir`. (Captures B6 at
    runtime in addition to the lint check.)
12. ADR 0005 regression: `KILLED_BY_SYSTEM` and `KILLED_FORCE_STOP`
    classifications still produced for matrix rows 11/12. Verify the
    per-call-site migration table in §"Migration table" — `KILLED_*`
    writers pass `NONE`; `RovaStopReceiver` and `RecoveryScanner`'s
    `USER_STOPPED` branch pass `USER`. Atomicity verified via crash-
    injection between the two would-be writes.
13. **Migration regression:** existing Phase 1.5 unit tests
    (`RecoveryScannerTest`) pass after the `markTerminated` signature
    widens. The default `StopReason.NONE` parameter or explicit pass-
    through must keep `terminated` value semantics identical.

### Runtime tests (instrumented or manual)
1. Force-stop from Settings during recording → next launch → Phase 1.5
   classifies KILLED_FORCE_STOP.
2. Revoke CAMERA permission via Settings during recording → service stops
   within one segment boundary.
3. Fill device storage during 4K session → service stops on next storage
   gate.
4. Background app for 30 min in Doze → segments captured at expected drift
   (ADR 0001 budgets respected).
5. Notification swipe (where dismissible) → no-op; FGS notification is
   sticky on Android 8+.

## Open Questions

1. **Permission revocation: kill in-flight segment or finish?**
   Decision: finish current segment (existing C18 path). Justification:
   already-captured frames are user content; truncating mid-segment loses
   data with no safety benefit.

2. **Low-storage termination: keep partial segment?**
   Decision: defer to next segment boundary. The current segment may
   finalize via C18 path. Truncating mid-segment risks losing the trailer.

3. **POST_NOTIFICATIONS pre-flight on 33+ — RESOLVED:**
   Locked at hard-block at start, tolerant mid-session.
   See §"POST_NOTIFICATIONS revoked (33+)".

4. **`stopNeedsRecovery` (withdrawn from previous draft):**
   Resolved by §"Recovery Signal Source of Truth". The flag is in-memory
   only and is not a recovery invariant. With eager terminal-write (§B3),
   the OS-killed-after-stop case has the manifest already terminal, so
   no flag is needed. Left as-is in current code, scoped to in-process
   retry steering.

5. **`SupervisorJob` confirmation:**
   Verify `serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())`.
   If not, child failure cancels scope unexpectedly. Audit before
   implementation.

6. **Zero-segment merge skip semantics:**
   Stop paths skip merge when `manifest.segments` is empty after the
   terminal write. Confirm: is "zero segments" the right skip predicate,
   or should it also include "no valid segments after `validateMediaFile`
   filtering"? Recommendation: skip when `segments.isEmpty()`; let
   `VideoMerger` itself handle invalid-segment filtering (existing
   behavior). Lock in review.

7. **Atomic-write race — loser's stopReason discarded:**
   The B2 design says first-writer-wins keeps the winner's `stopReason`.
   Practical case: low-storage gate fires, marks `LOW_STORAGE`; user STOP
   intent arrives 10ms later, gets `AlreadyTerminal`. UI sees
   `LOW_STORAGE`, not `USER`. Acceptable? Recommendation: yes — the
   diagnostic that fired first is the more precise reason, since
   user-initiated stop after a low-storage event is essentially a
   confirmation, not a separate cause. Lock in review.

## Non-Goals

- BOOT_COMPLETED auto-start: out of scope. User-driven session start only.
- Mid-session resolution change: out of scope. Tier/resolution locked at
  session start.
- Cross-tier export switching: ADR 0003 owns.
- Drift budget tuning: ADR 0001/0004 owns.
- Recovery scan classification matrix: ADR 0005 owns.
- Physical session deletion: Phase 1.7 owns.

## Consequences

### Positive
- Every service-end path has a defined manifest transition; no dark corners.
- Mid-session safety gates close the gap between init-time pre-flight and
  ADR 0005 cold-launch recovery.
- `stop_reason` field gives UI material to explain non-user terminations.
- Static checks enforce single-call-site invariants for resource-release
  paths.

### Negative
- `SessionManifest` schema delta (StopReason enum + field) — read-tolerant
  default `NONE`, but every write site must be audited.
- Per-segment permission/storage checks add ~5–20 ms overhead per segment.
  Acceptable given segment durations are seconds-to-minutes.
- New static-check tasks add to `preBuild` time. Mitigated by the
  line-scanning approach already proven in Phase 1.5.

### Neutral
- ADR 0005 contract preserved (Terminated enum unchanged).
- Existing C18 finalize-timeout path remains canonical for segment-finalize
  failures.

## References

- ROADMAP_v6.md §1.4 (unchanged from v5/v4 — scope frozen at v4 risk catalog).
- ADR 0001 (alarm policy).
- ADR 0002 (headless camera surface).
- ADR 0003 (tiered storage / public export).
- ADR 0005 (cold-launch recovery scan).
- Risks C1, C9, C11, C12, C14, C15, C18, C19 in ROADMAP risk register.
- Android docs: `ForegroundServiceStartNotAllowedException`,
  `Service.START_NOT_STICKY`, `PowerManager.PARTIAL_WAKE_LOCK`,
  `MediaMuxer` finalize semantics, `StatFs.availableBytes`.

---

## Review Notes (Codex Gate — Round 6 patches applied)

This is **design only**. No implementation has begun.

### Round 1 verdict (NO-GO) blockers — applied in Draft 2:
- B1: Init-failure paths formalized as commit-and-mark with `INIT_FAILED`.
  Matrix split pre-manifest vs post-manifest. (§"Init-Window Manifest
  Lifecycle".)
- B2: Atomic `markTerminated(sessionId, terminated, stopReason)` API.
  (§"Atomic terminal-write API".)
- B3: USER_STOPPED terminals write BEFORE merge; merge outcome does not
  change terminal state. (§"Terminal-Write Ordering".)
- B4: `stopNeedsRecovery` reclassified as in-memory-only; withdrawn from
  durable invariants. (§"Recovery Signal Source of Truth".)
- B5: Gates run at top of `recordSegment` before `prepareRecording`,
  not before `bindToLifecycle`. (§"Gate location".)
- B6: Storage gate uses `currentSessionDir`, not `filesDir`.

### Round 2 verdict (NO-GO) blockers — applied in Draft 3:
- B7: `COMPLETED` carved out of eager-terminal rule. Loop-completion
  writes `COMPLETED` ONLY after merge success commit point. ADR 0005's
  `T == COMPLETED ⇔ merged artifact exists` invariant preserved. Lint
  `checkUserStoppedBeforeMerge` enforces the inverse ordering for
  `COMPLETED` (post-merge) vs `USER_STOPPED` (pre-merge). (§"`COMPLETED`
  is NOT eager".)
- B8: Migration table corrected. `RovaStopReceiver` and `RecoveryScanner`
  `USER_STOPPED` branch pass `StopReason.USER`. Only `KILLED_*` writers
  and merge-success `COMPLETED` pass `NONE`. (§"Migration table".)
- B9: `MarkTerminatedResult.Failed` caller contract specified: 3-attempt
  internal retry with backoff; on terminal `Failed`, skip merge,
  surface degraded-state notification, log via `RovaCrashReporter`,
  defer to Phase 1.5 cold-launch. (§"Caller contract".)
- B10: API-safe FGS exception catch pattern locked: parent
  `IllegalStateException` catch, then `SDK_INT >= S` gate, then
  `e is ForegroundServiceStartNotAllowedException`. Lint
  `checkFGSStartGuarded` enforces the gate-before-`is` line ordering.
  (§"Start path".)

### Round 3 verdict (NO-GO) blockers — applied in Draft 4:
- B11: FGS guard added at caller site
  (`ContextCompat.startForegroundService`) as PRIMARY Android 12+ start-
  restriction path. Service-side `startForeground` guard kept as
  defense-in-depth. Matrix row 4 split into 4a (caller) / 4b (service).
  Lint `checkFGSStartGuarded` covers both sites. (§"Start path".)
- B12: Gate ordering layered: layer 1 (service-state prerequisites:
  `currentSessionId`, `currentSessionDir.exists()`, `sessionController`,
  `isPeriodicActive`) runs FIRST. Layer-1 failures are no-op aborts —
  NO terminal write. Permission (layer 2) and storage (layer 3) gates
  run only after layer 1 confirms valid session. `null currentSessionDir`
  cannot reach the storage gate. Lint `checkSegmentGates` enforces all
  three layers in line order. (§"Gate ordering".)
- B13: Matrix row 13 split into 13a (user-stop mid-merge → `USER_STOPPED`
  + in-flight `exportState`), 13b (loop-completion mid-merge, pre-
  artifact → `T = null`), 13c (loop-completion, artifact committed,
  terminal-write-pending → `T = null`, `exportState = FINALIZED`),
  13d (happy path → `T = COMPLETED`). Restores ADR 0005's
  `T == COMPLETED ⇔ merged artifact exists` invariant. (§"Why row 13
  split".)

### Round 4 verdict (NO-GO) blockers — applied in Draft 5:
- B14: Cross-phase ordering invariant defined: Phase 1.5 MUST skip
  sessions with `exportState != NOT_STARTED && terminated == null`.
  New `TerminalAction.SKIPPED_EXPORT_PENDING` variant added to ADR
  0005's contract. Implementation PR for ADR 0006 co-ships an ADR 0005
  amendment + `RecoveryScanner.kt` + `RecoveryReport.kt` +
  `RecoveryScannerTest.kt` changes. Cold-launch sequencing locked at
  "1.7 → 1.5" once Phase 1.7 ships. Until 1.7 ships, 1.5 leaves 13c
  sessions `BLOCKED` in the recovery report. (§"Cross-Phase Ordering
  Invariant".)
- B15: Single canonical init-window ordering locked. `startForeground`
  runs IMMEDIATELY in `onStartCommand` (Android 12+ FGS deadline ~5s),
  BEFORE any disk I/O. `acquireWakeLock` after `startForeground`.
  `createSession + register` inside `serviceScope.launch +
  startupMutex.withLock` AFTER FGS is established. Camera setup last.
  Row 4b's "pre-manifest" classification preserved. Diagram inside
  §"Init-window write order" rewritten to match. Site B catch comment
  re-aligned. (§"Init-window write order".)
- B16: `checkAtomicTerminalWrite` lint text corrected to match the B8
  migration table. `KILLED_*` writers and merge-success `COMPLETED`
  writer pass `NONE`; `RovaStopReceiver` (both branches) and
  `RecoveryScanner`'s `stopRequested=true` branch pass a non-`NONE`
  value. Forbidden-pair scanner now flags
  `markTerminated(..., Terminated.USER_STOPPED, StopReason.NONE)` as
  the load-bearing predicate.

### Round 5 verdict (NO-GO) blockers — applied in Draft 6:
- B17: B14 skip set made explicit:
  `EXPORT_IN_FLIGHT_OR_FINALIZED = {MUXING, COPYING, FINALIZED}`. New
  ownership table assigns each `exportState` value to exactly one
  cold-launch owner: `NOT_STARTED` and `FAILED` → Phase 1.5; the in-
  flight set → Phase 1.7. `FAILED` is now a Phase 1.5 INPUT, not a
  re-entry into Phase 1.7. Stranding eliminated. (§"Ownership table
  for `exportState`".)
- B18: Audio mode locked at session start. New `AudioMode` enum
  (`VIDEO_AUDIO` / `VIDEO_ONLY`) persisted in manifest. FGS type bitfield
  computed from `audioMode` BEFORE `startForeground`. Mid-session mic
  revocation in `VIDEO_AUDIO` session terminates with
  `USER_STOPPED + PERMISSION_REVOKED` (FGS-type immutability). The
  previous `ContinueVideoOnly` mid-session degrade is removed. New
  static check `checkAudioModeFgsTypeMatch`. New defensive
  `SecurityException` catch around `startForeground`. (§"Audio Mode and
  FGS Type Coupling".)
- B19: Two-phase preflight: Phase 1 (RAM-only, runs BEFORE
  `startForeground`, sub-ms) — permission checks + audio-mode decision +
  FGS-type computation. Phase 2 (filesystem, runs AFTER
  `startForeground`, BEFORE `createSession`) — `StatFs` storage probe.
  Both phases are pre-manifest. Row 3 (init storage pre-flight)
  reclassified as post-FGS pre-manifest. New static check
  `checkInitWindowOrdering`. (§"Init-window write order".)

### Round 6 verdict (NO-GO) blockers — applied in Draft 7:
- B20: Site B `startForeground` snippet replaced with `audioMode`-driven
  `fgsType = when(audioMode) {...}` form. Hardcoded
  `CAMERA | MICROPHONE` removed. `SecurityException` catch arm added
  alongside `IllegalStateException` (Android 14+ FGS-type/permission
  mismatch).  `checkFGSStartGuarded` lint upgraded to require both
  catch arms on service-side. (§"Site B".)
- B21: External-storage root resolution centralized via
  `resolveExternalRoot(...)` helper. Null root → row 3 cleanup
  (post-FGS, pre-manifest). `SessionStore` and preflight share the
  same resolved `File`. New static check `checkExternalRootShared`
  forbids `getExternalFilesDir(null)` references outside the helper.
  Row 3 description updated. (§"External-storage root resolution".)
- B22: `TerminalAction.SKIPPED_EXPORT_PENDING` enum comment fixed to
  match B17 explicit skip set
  (`exportState in {MUXING, COPYING, FINALIZED} && T == null`).

### Round 7 review questions:
1. Loop-completion path on merge failure: today's behavior is `terminated
   = null, stopRequested = true`. Phase 1.5 classifies as `USER_STOPPED`,
   which is wrong-but-tolerable. Should 1.4 introduce a new `MERGE_FAILED`
   `StopReason` to distinguish? Recommendation: NO. Keep manifest non-
   terminal; export-recovery (Phase 1.7) is the right owner of merge-
   retry and post-failure UI. The `stopReason` ride-along is for stops,
   not non-stops.
2. `Failed` retry backoff: is 3 attempts × 50ms backoff sufficient for
   transient I/O? Or should we use exponential backoff (50ms, 200ms,
   1000ms) to better tolerate brief flash-storage stalls? Lock in
   review.
3. Static-check fragility: `checkUserStoppedBeforeMerge` and
   `checkTerminalBeforeMerge` (now renamed) rely on line-index ordering
   in source. Is a runtime assertion in
   `stopPeriodicRecordingAndMerge` (e.g., `require(terminated != null
   || isCompletedPath)` before `mergeSegments` call) a cleaner invariant
   guard, with the lint as a redundancy?
4. Are matrix rows 6 (controller-register collision) and 7 (post-manifest
   camera bind fail) actually reachable today? Audit needed before
   implementation: if today's `ServiceController.register` collision
   path already rolls back via `SessionStore.delete(sid)`, this ADR is
   changing that behavior to commit-and-mark — explicitly call out the
   behavior change in the implementation PR.
5. `Failed` on `KILLED_*` writes by Phase 1.5 receivers: receivers run
   inside `goAsync()` with bounded broadcast deadlines. Does the
   3-attempt internal retry (up to ~150ms total) fit within typical
   broadcast budgets? Worst-case broadcast wall-clock is ~10s on
   modern Android, so yes, but document the budget in the
   implementation.
6. **Row 13c reconciliation owner.** Phase 1.7 export-recovery is
   responsible for finalizing manifest from `exportState = FINALIZED` +
   `T = null` to `T = COMPLETED`. ADR 0003 (Phase 1.7) does not yet
   spell this out explicitly — it covers Tier 1 `IS_PENDING=0` + manifest
   FINALIZED, but the late `markTerminated(COMPLETED)` write is implied,
   not stated. Should ADR 0006 trigger an ADR 0003 amendment, or is the
   1.7-side wording deferred until Phase 1.7 is built?
7. **Layer-1 `sessionController` check.** Confirms the process-singleton
   ServiceController still owns this session. Race: between layer 1 and
   `prepareRecording` start, ServiceController could (in theory) be
   replaced. Mitigation: ServiceController is process-singleton via
   AtomicReference + CAS (Phase 1.3); replacement only happens through
   `unregister + register`, both gated by `startupMutex`. Layer 1 is
   correct because the recording loop also runs under the same process,
   and `startupMutex` is not held while `recordSegment` runs — so a
   theoretical race exists but is bounded by user explicitly stopping
   one session and starting another within milliseconds. Lock in review.
8. **`StatFs` throwing on disappeared sessionDir.** Layer 1 verifies
   `currentSessionDir.exists()`, but FS state can change between layer
   1 and layer 3 evaluation. Storage gate must catch
   `IllegalArgumentException` from `StatFs(...)` and treat as layer-1-
   late-failure: no-op abort, NOT terminal write. Confirm wrapping in
   the implementation.
9. **`exportState` field availability for B14.** Phase 1.5 amendment
   relies on `manifest.exportState`. Today's `SessionManifest` may not
   yet carry the field — Phase 1.6/1.7 owns the `ExportState` enum.
   Should ADR 0006's implementation PR add `ExportState` (with only the
   `NOT_STARTED` variant defined) as a Phase 1.6 prefactor, or rely on
   a string field "exportState" with `NOT_STARTED` default until 1.6
   formalizes it? Recommendation: add the enum stub now (single
   variant) so the field is type-safe; 1.6 extends the enum. Lock in
   review.
10. **Sequential 1.7 → 1.5 trigger.** Until Phase 1.7 ships, 1.5 runs
    alone with the new `SKIPPED_EXPORT_PENDING` branch. When 1.7 lands,
    `RovaApp.triggerRecoveryScanIfNeeded` extends to invoke 1.7 first.
    Confirm: is "1.7 first, then 1.5" reachable from the existing
    `startupMutex` + `activeReceiverWork` drain semantics, or does 1.7
    need its own drain protocol?
11. **`SKIPPED_EXPORT_PENDING` UI surface.** Recovery report consumers
    (Phase 2 UI) get a new classification value to surface as "Export
    pending — recovering on next launch". Confirm UI ownership: does
    Phase 2 UI accept the new variant before 1.7 ships, or do we hide
    `SKIPPED_EXPORT_PENDING` sessions from History until 1.7 lands?
    Recommendation: hide until 1.7. Lock in review.
12. **Audio mode UI flow.** B18 specifies "audio optional" product
    policy with a UI prompt ("Record without audio?") when mic is
    missing at start. Implementation point: where does this prompt
    live? `MainActivity.startRecording` → check perm → if denied,
    request via runtime flow → on permanent deny, show dialog with
    "Record without audio" / "Cancel" buttons. The "Record without
    audio" button proceeds with `AudioMode.VIDEO_ONLY`.
    Confirm UX before implementation.
13. **`audioMode` legacy default.** Manifest read-tolerance: legacy
    sessions (no `audioMode` field) default to `VIDEO_ONLY`. Is that
    the safest default for back-compat (won't accidentally infer
    `VIDEO_AUDIO` for sessions whose actual FGS-type is unknown), or
    should it be a third `UNKNOWN` value that triggers a soft warning
    in recovery? Recommendation: `VIDEO_ONLY` default. Lock in review.
14. **`checkInitWindowOrdering` brittleness.** The lint enforces 8
    line-ordering predicates. Refactors that reorder code break the
    lint. Mitigation: opt-out marker per-call, plus the runtime
    correctness is still enforced by `startForeground` itself (FGS-
    deadline-violation throws `ForegroundServiceDidNotStartInTimeException`
    on Android 12+). The lint is belt-and-braces; consider downgrading
    to `WARNING` for the inner predicates while keeping `startForeground
    BEFORE acquireWakeLock` at `ERROR`.
15. **Pre-FGS CAMERA check race.** Phase 1's `checkSelfPermission(CAMERA)`
    runs before `startForeground`. If user revokes between the check and
    `setupCamera`, CameraX bind throws `SecurityException`. That hits
    row 7 (post-manifest camera bind failure) → terminal write
    `USER_STOPPED + INIT_FAILED`. Acceptable? Or should the check be
    re-done immediately before bind, with a separate
    `INIT_PERMISSION_REVOKED` reason? Recommendation: the row-7 path is
    sufficient; revocation between `checkSelfPermission` and `bind` is
    a microsecond-scale race that's effectively impossible from user
    UI. Lock in review.
16. **`resolveExternalRoot` lifetime.** Resolved once per service start
    and stored on the service / `RovaApp`. If external storage is
    ejected mid-session, every subsequent `StatFs(externalRoot)` call
    in `checkSegmentStorage` will throw `IllegalArgumentException`. Per
    Round 5 §Q8 we already treat that as layer-1-late-failure (no-op
    abort, NOT terminal write). Confirm: does that still produce the
    right user outcome for "SD card ejected during 4K session", or
    should ejection trigger an explicit `STORAGE_UNAVAILABLE` terminal?
    Recommendation: layer-1-late no-op for now; revisit if user reports
    indicate confusion.
17. **`SessionStore` constructor migration.** B21 requires
    `SessionStore` to be constructed with a resolved `File` root, not
    via the `Context` secondary constructor. Phase 1.5 added
    `internal constructor(rootDirArg: File)`; Phase 1.4 deprecates the
    `Context` secondary constructor and updates `RovaApp` to pass the
    resolved root. Confirm: any non-`RovaApp` consumers of
    `SessionStore(context)`? Audit before implementation.
