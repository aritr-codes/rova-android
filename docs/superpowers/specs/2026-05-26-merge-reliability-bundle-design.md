# Milestone 2 — Merge Reliability Bundle Design

**Date:** 2026-05-26
**Status:** Draft — pending owner review
**Master tip baseline:** `b3fad71` (post-PR #50 Milestone 1)
**Owner approvals on file:** Milestone order 1→2→3→4→5 approved; 5 design decisions locked during brainstorm (failure taxonomy, retry strategy, preflight gate, library row contract, notification progress).

## 1. Context

Phase 4.3 (PR #48, `1485cf5`, 2026-05-25) shipped service-hosted recovery merge with binary outcomes: `Terminated.COMPLETED` on `ExportResult.Success`, `Terminated.MULTI_SEGMENT_KEPT` (ordinal 4) on any other `ExportResult`. The C2.4 "Can't merge yet" sheet + `WarningId.CANT_MERGE` (row #14) wire a user-facing path back from `MULTI_SEGMENT_KEPT` to a manual retry, but only triggered when pre-flight detects insufficient bytes via existing Storage signals.

Five reliability gaps surfaced as out-of-scope for Phase 4.3 (per ADR-0017 §Consequences):

1. **Library row enumeration** — `MULTI_SEGMENT_KEPT` sessions render as a single library row, hiding the individual kept segments from the user.
2. **Retry / auto-retry** — every `ExportResult` failure (mux jitter, copy race, rename TOCTOU, transient I/O stalls) writes terminal `MULTI_SEGMENT_KEPT` on the first attempt, requiring the user to manually retrigger via the C2.4 sheet.
3. **Mid-recording "Can't merge" prevention** — preflight only fires at the recovery-merge intent boundary; mid-segment storage exhaustion still produces `MULTI_SEGMENT_KEPT` after the fact.
4. **FGS notification progress** — the merge foreground-service notification shows a static body (`Session ${sessionId.take(8)}…`) with no progress indicator while the merge runs.
5. **"Continue without saving" outcome distinct from Discard** — both today route through `discardSession`; user UX intent is unclear.

Owner reprioritised the parked-backlog item set after Milestone 1 ship: items 1+2+3+4 bundle into a single milestone because all four mutate the same `RovaRecordingService::performMerge` surface; item 5 stays parked (owner-determined). This spec covers the bundle.

## 2. Goal

Land a single PR that turns `MULTI_SEGMENT_KEPT` from "first-failure terminal" into "exhausted-recovery terminal" by adding:

- **Retry classifier** — `MergeFailureClass` sealed mapper over `ExportResult` (Transient | Permanent | InsufficientStorage | Terminal).
- **Tiered retry loop** — 3 attempts with exponential backoff (1s / 4s / 16s) for Mux/Copy/Rename failures; separate flow for `InsufficientStorage` with notification action + poll-resume.
- **Pre-flight storage gate** — `StoragePreflight.hasHeadroom` invoked before `startForegroundForRecoveryMerge`; on insufficient headroom, raise existing `CANT_MERGE` and keep manifest non-terminal.
- **FGS notification progress** — direct `onProgress(Float)` callback wired to `NotificationCompat.Builder.setProgress(total, current, false)` + "Merging X of Y" body, throttled to 500ms (final-tick exempt).
- **Library row enumeration** — `HistoryArtifactMapper.manifestDrivenArtifacts(manifest)` emits N rows per kept segment for `MULTI_SEGMENT_KEPT` sessions; existing terminal states unchanged.

## 3. Out of scope

- **G — "Continue without saving" outcome distinct from `Discard`** — parked per owner. Today both route through `discardSession`; behavioural difference undefined.
- **Mid-recording (not mid-merge) thermal interrupt** — Milestone 3+ candidate.
- **Two-level thermal hysteresis** — Milestone 3.
- **Onboarding flow** — Milestone 4.
- **Notification + export sheet polish (top-level FGS recording notification, not merge notification)** — Milestone 5.
- **In-app video player, CapCut-style editor, Mode preset seed** — larger features, later.
- **Collapsible-group rendering** for `MULTI_SEGMENT_KEPT` sessions — kept as flat 1-per-segment per owner decision; collapsible model deferred unless field reports show row-explosion problems.
- **Mockup files** — `mockups/` is gitignored. This milestone uses no new mockups; notification + library row visual patterns derive from existing `mockups/new_uiux/09-notification-export.html` and `mockups/new_uiux/03-history-library.html`.

## 4. Hard invariants preserved

- `WarningId` enum — 20 entries, ordinal pinning unchanged. `CANT_MERGE` row #14 (Phase 4.3) reused as-is.
- `WarningPrecedence.resolve == allActive.firstOrNull()` invariant from Phase 4.2 — preserved.
- `Terminated` enum — 5 values, `MULTI_SEGMENT_KEPT` at ordinal 4 — unchanged.
- `StopReason` enum — 6 values — unchanged.
- `SessionStore::markTerminated` 3-arg atomic API — unchanged.
- `ExportResult` sealed-class arm list — unchanged (no new arms; new classifier reads existing arms).
- Forbidden pair B16 (`USER_STOPPED + NONE`) — retry loop never writes this combination. Retry exhaustion writes `(MULTI_SEGMENT_KEPT, NONE)`.
- ADR-0009 / ADR-0010 / ADR-0011 / ADR-0012 / ADR-0017 outputs — recording-pipeline / preview-render / chrome paint byte-identical.
- `EglRouter` / `AspectFitMath` / `DualVideoRecorder` / muxer / preview surfaces — untouched.
- Single-site recovery-scan trigger (`MainActivity.onCreate` → `RovaApp.triggerRecoveryScanIfNeeded()`) — untouched.
- `WarningCenterAggregateTest` / `WarningIdOrderTest` / `RecoveryScannerTest` / `AspectFitMath*Test` — byte-identical green.

## 5. Design decisions (owner-approved during brainstorm)

| # | Decision | Value |
|---|---|---|
| 1 | Failure-mode taxonomy | 4 transient (`MuxFailed`, `CopyFailed`, `RenameFailed`, `InsufficientStorage`) + 4 permanent (`PendingInsertFailed`, `FinalizeFailed`, `ManifestWriteFailed`, `UnknownSession`) + 1 terminal (`Success`) |
| 2 | Retry strategy | Tiered: 3 attempts × exponential backoff (1s / 4s / 16s) for Mux/Copy/Rename; separate flow for `InsufficientStorage` (notification action + 30s storage-poll, 10-min cap) |
| 3 | Pre-flight gate | Block merge before FGS start. Compute `StorageManager.getAllocatableBytes` ≥ `accumulatedSessionBytes + FINALIZE_HEADROOM_BYTES`. On failure: raise `CANT_MERGE` (row #14, existing), no FGS start, manifest non-terminal. User retriggers from C2.4 sheet. |
| 4 | Library row contract for `MULTI_SEGMENT_KEPT` | Flat 1-per-segment. `HistoryArtifactMapper` emits N rows where N = `manifest.segments.size`. Each row independently playable / deletable. |
| 5 | FGS notification progress mechanism | Direct `onProgress(Float)` callback (already plumbed in `exportRecovered`) → `NotificationCompat.Builder.setProgress(100, current, false)` + `setContentText("Merging X of Y")`. Throttled to 1 update/500ms. Final tick (`current == total`) always renders. |

## 6. Architecture

### 6.1 New abstractions

```kotlin
// service/recovery/MergeFailureClass.kt
internal sealed class MergeFailureClass {
    object Terminal : MergeFailureClass()
    data class Transient(val cause: ExportResult) : MergeFailureClass()
    data class InsufficientStorage(val requiredBytes: Long) : MergeFailureClass()
    data class Permanent(val cause: ExportResult) : MergeFailureClass()
}

internal fun classifyMergeFailure(result: ExportResult): MergeFailureClass
```

```kotlin
// service/recovery/MergeRetryPolicy.kt
internal object MergeRetryPolicy {
    const val MAX_ATTEMPTS: Int = 3
    fun backoffMillisFor(attempt: Int): Long  // 1s / 4s / 16s; clamp at 16s for attempts > 3
}
```

```kotlin
// service/recovery/StoragePreflight.kt
internal object StoragePreflight {
    const val FINALIZE_HEADROOM_BYTES: Long = 50L * 1024L * 1024L
    fun hasHeadroom(availableBytes: Long, accumulatedSessionBytes: Long): Boolean
}
```

```kotlin
// service/recovery/MergeProgress.kt (value class — keeps notification helper signature small)
@JvmInline
internal value class MergeProgress(val raw: Long) {
    constructor(current: Int, total: Int) : this((current.toLong() shl 32) or (total.toLong() and 0xFFFFFFFFL))
    val current: Int get() = (raw shr 32).toInt()
    val total: Int get() = (raw and 0xFFFFFFFFL).toInt()
}
```

(`MergeProgress` could be a plain `data class` if value-class packing is judged unnecessary — owner pick at plan time. Both work; value class avoids GC pressure during 500ms-throttled high-rate updates.)

### 6.2 Retry loop control flow (inside `RecoveryMerger.run`)

```
attempt = 1
forever:
    result = exportRecovered(sessionId, onProgress = updateNotificationCallback)
    when classifyMergeFailure(result):
        Terminal → markTerminated(sessionId, COMPLETED, NONE); return
        Transient(_):
            if attempt < MAX_ATTEMPTS:
                updateNotificationRetrying(attempt, MAX_ATTEMPTS)
                delay(backoffMillisFor(attempt))
                attempt += 1
                continue
            else:
                markTerminated(sessionId, MULTI_SEGMENT_KEPT, NONE); return
        InsufficientStorage(req):
            updateNotificationWaitingForSpace(req)
            pollResult = pollForStorage(req, capMillis = 10 * 60_000)
            when pollResult:
                SpaceFreed → attempt = 1; continue   // re-enter loop from scratch
                Timeout → markTerminated(sessionId, MULTI_SEGMENT_KEPT, NONE); return
        Permanent(_) → markTerminated(sessionId, MULTI_SEGMENT_KEPT, NONE); return
```

`pollForStorage` polls `StorageManager.getAllocatableBytes` every 30s; emits `SpaceFreed` when `available >= req`; emits `Timeout` after cap. Uses suspending `delay(30_000)` — no thread-blocking sleeps.

### 6.3 Pre-flight gate (inside `RovaRecordingService::handleRecoveryMergeStart`)

```kotlin
private fun handleRecoveryMergeStart(intent: Intent) {
    val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return
    val manifest = sessionStore.read(sessionId) ?: return
    val available = storageManager.getAllocatableBytes(externalRoot.toUuid())
    val accumulated = StorageEstimator.accumulatedSessionBytes(
        sessionDir = sessionDirFor(sessionId),
        segmentCount = manifest.segments.size,
        durationSeconds = manifest.totalDurationSeconds,
        resolution = manifest.resolution,
    )
    if (!StoragePreflight.hasHeadroom(available, accumulated)) {
        cantMergeSignal.fireOnce(sessionId)   // existing Phase 4.3 wiring (WarningId.CANT_MERGE)
        return                                 // no FGS start, manifest non-terminal
    }
    startForegroundForRecoveryMerge(sessionId)
    recoveryMerger.run(sessionId, ::updateMergeNotification)
}
```

### 6.4 Library row enumeration

`HistoryArtifactMapper.kt` — change return type from `HistoryArtifact` (single) to `List<HistoryArtifact>`:

```kotlin
internal fun manifestDrivenArtifacts(manifest: SessionManifest): List<HistoryArtifact> =
    when (manifest.terminated) {
        Terminated.MULTI_SEGMENT_KEPT -> manifest.segments.mapIndexed { index, segment ->
            HistoryArtifact.SegmentClip(
                sessionId = manifest.sessionId,
                segmentIndex = index,
                filename = segment.filename,
                durationMs = segment.durationMs,
                sizeBytes = segment.size,
                bitrate = segment.bitrate,
            )
        }
        else -> listOf(manifestDrivenArtifact(manifest))   // existing path, wrapped
    }
```

`HistoryArtifact` sealed class gains a `SegmentClip` variant (existing `FullSession` variant unchanged).

`HistoryViewModel.buildItems` call-site update: `mapNotNull { manifestDrivenArtifact(it).toViewItem() }` → `flatMap { manifestDrivenArtifacts(it).mapNotNull(::toViewItem) }`.

**Delete semantics:** today's per-row delete handler is keyed by `HistoryArtifact`. `SegmentClip` carries `sessionId + segmentIndex`; the handler removes the segment file (`sessionDir/segment_<index>.mp4`) and updates the manifest's `segments` list. On last-segment delete: cascade-delete the manifest itself. Defensive: if `segments.isEmpty()` post-delete, mark session for cleanup via existing recovery-scan path.

### 6.5 FGS notification helper

```kotlin
private var lastNotifyMillis: Long = 0L
private val NOTIFY_THROTTLE_MS = 500L

private fun updateMergeNotification(progress: MergeProgress) {
    val now = SystemClock.elapsedRealtime()
    val isFinalTick = progress.current >= progress.total
    if (!isFinalTick && now - lastNotifyMillis < NOTIFY_THROTTLE_MS) return
    lastNotifyMillis = now
    val notif = mergeNotificationBuilder(
        title = "Merging recovered clips",
        body = "Merging ${progress.current} of ${progress.total}",
        progressMax = progress.total,
        progressCurrent = progress.current,
    ).build()
    notificationManager.notify(NOTIFICATION_ID_RECOVERY_MERGE, notif)
}
```

Mid-retry state uses a different builder ("Retrying (attempt 2 of 3)…"). InsufficientStorage poll state uses a third builder ("Waiting for free space"). All three share `mergeNotificationBuilder(...)` helper.

### 6.6 Files touched

| File | Action | Notes |
|---|---|---|
| `app/src/main/java/com/aritr/rova/service/recovery/MergeFailureClass.kt` | Create | Sealed classifier (pure JVM) |
| `app/src/main/java/com/aritr/rova/service/recovery/MergeRetryPolicy.kt` | Create | Backoff helper (pure JVM) |
| `app/src/main/java/com/aritr/rova/service/recovery/StoragePreflight.kt` | Create | Preflight helper (pure JVM) |
| `app/src/main/java/com/aritr/rova/service/recovery/MergeProgress.kt` | Create | Value class (pure JVM) |
| `app/src/main/java/com/aritr/rova/service/recovery/RecoveryMerger.kt` | Modify | Wire retry loop, classifier, progress callback |
| `app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt` | Modify | `handleRecoveryMergeStart` preflight, notification helpers, throttle state |
| `app/src/main/java/com/aritr/rova/ui/screens/HistoryArtifact.kt` (or sealed class location) | Modify | Add `SegmentClip` variant |
| `app/src/main/java/com/aritr/rova/ui/screens/HistoryArtifactMapper.kt` | Modify | Add `manifestDrivenArtifacts` plural emitter |
| `app/src/main/java/com/aritr/rova/ui/screens/HistoryViewModel.kt` | Modify | `buildItems` call-site flatMap |
| `app/src/test/java/com/aritr/rova/service/recovery/MergeFailureClassTest.kt` | Create | 9 cases |
| `app/src/test/java/com/aritr/rova/service/recovery/MergeRetryPolicyTest.kt` | Create | 5 cases |
| `app/src/test/java/com/aritr/rova/service/recovery/StoragePreflightTest.kt` | Create | 6 cases |
| `app/src/test/java/com/aritr/rova/service/recovery/RecoveryMergerRetryTest.kt` | Create | 6 integration scenarios (runBlocking) |
| `app/src/test/java/com/aritr/rova/ui/screens/HistoryArtifactMapperSegmentRowsTest.kt` | Create | 4 cases |

## 7. Testing strategy

### 7.1 Pure-JVM unit tests (5 new test files, ~30 cases total)

| Test file | Cases | Coverage |
|---|---|---|
| `MergeFailureClassTest` | 9 | One per `ExportResult` arm → expected `MergeFailureClass` |
| `MergeRetryPolicyTest` | 5 | `backoffMillisFor(1) == 1_000L`, `(2) == 4_000L`, `(3) == 16_000L`, `(0)` defensive, `(99)` clamp |
| `StoragePreflightTest` | 6 | Exact-boundary, just-below (false), just-above (true), zero accumulated, huge accumulated, negative defensive coerce |
| `RecoveryMergerRetryTest` | 6 | (a) Success first try (b) Transient×3 exhausted (c) Transient×2 then Success (d) Permanent first (e) InsufficientStorage + poll-resume → Success (f) InsufficientStorage + poll-timeout → MULTI_SEGMENT_KEPT |
| `HistoryArtifactMapperSegmentRowsTest` | 4 | COMPLETED+3segs→1 row; MULTI_SEGMENT_KEPT+5segs→5 rows; MULTI_SEGMENT_KEPT+0segs→emptyList; MULTI_SEGMENT_KEPT+1seg→1 row |

### 7.2 Coroutine convention

`kotlinx.coroutines.runBlocking` only. **No `kotlinx-coroutines-test` — project does not depend on it (Phase 4.3 lesson).**

`RecoveryMergerRetryTest` uses a fake `exportRecovered` that returns from a pre-programmed `Queue<ExportResult>` per attempt, plus a fake `pollForStorage` that returns from a pre-programmed sequence. Suspending `delay(...)` calls inside the retry loop use a `TestCoroutineScope`-free approach: real `delay` with very small backoff overrides via test-only `MergeRetryPolicy.backoffMillisFor` injection (a `TestableRetryPolicy` with parameterised values), keeping tests bounded to < 1 second total.

### 7.3 No Compose render tests

Per project precedent (`RecordingFrameLayoutTest`, `AspectFitMathTest`, `RecordModeCycleTest`). Library row visual change verified via manual smoke + pure mapper test.

### 7.4 Regression suite (must remain green, byte-identical)

- `WarningPrecedenceTest`, `WarningCenterAggregateTest`, `WarningIdOrderTest` — `CANT_MERGE` semantics unchanged
- `RecoveryScannerTest` — Phase 1.5 classification unchanged
- `AspectFitMath*Test`, `RecordingFrameLayoutTest`, `RecordModeCycleTest` — render math unchanged
- Static checks in `app/build.gradle.kts` (`checkAtomicTerminalWriteForbiddenPair`, `checkUserStoppedBeforeMerge`, etc.) — pass

### 7.5 Manual smoke (Samsung SM-A176B)

1. Normal recovery merge (Phase 4.3 path) → notification progress bar + "Merging X of Y" + smooth throttled updates.
2. Force `InsufficientStorage` (fill device storage) → notification action "Free space" appears, poll resumes after space freed, merge succeeds.
3. Force preflight failure (extreme storage starvation before merge intent) → `CANT_MERGE` banner + C2.4 sheet, no FGS notification.
4. Force `MULTI_SEGMENT_KEPT` outcome (e.g. simulated permanent fail via debug intent) → library shows N rows per kept segment, each independently playable + deletable.
5. Confirm retry copy survives 3-retry transient sequence ("Retrying (attempt 2 of 3)…" → "Merging X of Y").

## 8. Performance plan

- **Retry loop CPU/I/O:** the retry loop is dominated by `exportRecovered` (existing cost); the only new cost is `delay(backoff)` (suspending, no CPU) and the `classifyMergeFailure(result)` `when` branch (constant time).
- **Storage-poll overhead:** `getAllocatableBytes` every 30s for up to 10 minutes = max 20 calls. `StatFs` is microsecond-level. Negligible.
- **Notification throttle:** 500ms cadence + final-tick exemption = max 121 updates over a 60-second merge. `NotificationManagerCompat.notify` is microsecond-level. Negligible.
- **Library row enumeration:** for a session with N=10 kept segments, replaces 1 row with 10. `HistoryViewModel` already builds a list — a per-session O(N) flatMap with N typically ≤30 is well below any UX threshold. No virtualisation change needed.

## 9. Risks summary

| # | Risk | Likelihood | Mitigation |
|---|---|---|---|
| 1 | Retry loop introduces new ANR window | Low | All work on `RecoveryMerger`'s IO dispatcher; `delay` is suspending, not blocking. |
| 2 | Notification throttle drops final progress | Low | Final-tick (`current >= total`) bypass explicit in helper. |
| 3 | Storage-poll holds FGS alive 10 minutes | Medium | Hard 10-min cap. If user closes app, manifest non-terminal; recovery scan picks up next launch. |
| 4 | 1-per-segment row explosion if user has many `MULTI_SEGMENT_KEPT` sessions | Low | Edge case is rare. Collapsible-group rendering can fold in on same data model if field reports show problem. |
| 5 | `InsufficientStorage` poll races with concurrent file deletion | Low | `getAllocatableBytes` sampled fresh each iteration; eventually-consistent. |
| 6 | `MergeProgress` value-class packing yields wrong shift on 32-bit JVMs | None | Kotlin/JVM is 64-bit safe; value class uses `Long` raw. Tests cover boundary. |
| 7 | Static check `checkAtomicTerminalWriteForbiddenPair` flags retry-exhaustion write | None | Retry exhaustion writes `(MULTI_SEGMENT_KEPT, NONE)`, not `(USER_STOPPED, NONE)`. |
| 8 | `ExportResult` sealed-when consumer broken by new classifier | None | Verified via Axis 1 grep — only `performMerge` consumes `ExportResult`. Classifier is a new consumer; existing consumer is also updated. |

## 10. Acceptance criteria

- `MergeFailureClass.classifyMergeFailure` classifies all 9 `ExportResult` arms (9 tests pass).
- `MergeRetryPolicy.backoffMillisFor` returns 1s/4s/16s for attempts 1/2/3 (5 tests pass).
- `StoragePreflight.hasHeadroom` correct at boundary (6 tests pass).
- `RecoveryMerger` retry loop scenarios pass (6 tests).
- `HistoryArtifactMapper.manifestDrivenArtifacts` returns N rows per kept segment for `MULTI_SEGMENT_KEPT` (4 tests pass).
- FGS notification shows "Merging X of Y" with progress bar; throttled at 500ms; final tick always renders.
- Pre-flight failure raises `CANT_MERGE` (row #14, existing) before any FGS start.
- Manual smoke on Samsung SM-A176B passes all 5 scenarios in §7.5.
- Full test suite ≥1226 (1201 baseline + ~25 new), 0 failed / 0 ignored / 0 skipped.
- Lint zero-delta vs `b3fad71` baseline (`0 errors, 51 warnings, 1 hint`).
- `gradlew assembleDebug` succeeds.
- ADR-0018 created alongside implementation (architecture: retry classifier + tiered InsufficientStorage flow + preflight gate).

## 11. ADR implication

A new ADR is warranted. The retry classifier + tiered InsufficientStorage flow + preflight gate are architectural decisions extending ADR-0017 (recovery merge architecture). Draft outline in §12.

## 12. ADR-0018 draft outline

**Title:** Recovery merge retry, classifier, and preflight

**Status:** Proposed

**Context:**
- Phase 4.3 (ADR-0017) shipped service-hosted merge with binary outcomes: `COMPLETED` on `ExportResult.Success`, `MULTI_SEGMENT_KEPT` on any failure.
- Field-quality reliability needs: distinguish transient from permanent failures, retry transients with bounded backoff, handle storage exhaustion with user-actionable recovery (not auto-retry spin), surface progress in FGS notification, gate intent on storage headroom.

**Decision:**
- Introduce `MergeFailureClass` sealed mapper over `ExportResult` (4 transient / 4 permanent / 1 terminal — see §5).
- Retry transient (Mux/Copy/Rename) up to 3 attempts with exponential backoff (1s / 4s / 16s).
- Separate `InsufficientStorage` flow: notification action + 30s poll, 10-min cap, retry on space freed.
- Preflight at `handleRecoveryMergeStart` boundary: `getAllocatableBytes >= accumulatedSessionBytes + 50 MiB`. On failure, raise existing `CANT_MERGE` and keep manifest non-terminal.
- FGS notification carries progress via existing `onProgress` callback, throttled to 500ms, final-tick exempt.

**Consequences:**

*Accepted:*
- Retry loop adds attempt-state to merge surface; `RecoveryMerger.run` becomes a coroutine with embedded `delay`.
- `MULTI_SEGMENT_KEPT` semantics shift: was "first-failure terminal," now "exhausted-recovery terminal."
- `ExportResult` sealed-class consumers now include a classifier indirection.
- New surface for failure-mode injection in tests (`RecoveryMergerRetryTest`).

*Rejected:*
- Fixed retry (no special storage flow) — treats `InsufficientStorage` like a flake; users blame the app.
- Adaptive jitter + circuit-break — added resilience not measurably needed; harder to test.
- Warn-and-proceed preflight — doubles `InsufficientStorage` code paths.
- Silent block + auto-poll without warning — user confusion.
- Collapsible-group library rendering — premature UI scope for rare edge case.
- Coroutine Channel for progress wire-up — direct callback simpler, no benefit measured.
- Discrete segment-boundary progress only — direct fraction is finer-grained and existing callback supports it.

*Out-of-scope (future):*
- Distinct "Continue without saving" outcome from `Discard` (owner-parked).
- Mid-recording (not mid-merge) thermal interrupt.
- Collapsible-group library rendering (foldable on top of same data model if field shows row-explosion).

**Hard invariants preserved:** see §4.

**Mockup file:** none (no new visual surface; reuses existing `CANT_MERGE` C2.4 sheet + `mockups/new_uiux/09-notification-export.html` progress notification pattern + `mockups/new_uiux/03-history-library.html` row pattern).

## 13. Next step

After owner approval of this spec:

1. Commit spec + ADR-0018 draft.
2. Invoke `superpowers:writing-plans` skill to produce an implementation plan at `docs/superpowers/plans/2026-05-26-merge-reliability-bundle.md`.
3. Plan execution gated on `/karpathy-guidelines` workflow (owner directive — same as Milestone 1).
4. Execution via `superpowers:subagent-driven-development` (preferred mode per standing constraint #10).
