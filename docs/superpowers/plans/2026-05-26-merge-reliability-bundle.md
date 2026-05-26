# Merge Reliability Bundle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn `Terminated.MULTI_SEGMENT_KEPT` from "first-failure terminal" into "exhausted-recovery terminal" by bundling retry classifier + tiered InsufficientStorage flow + preflight gate + FGS notification progress + library row enumeration into a single PR over `RovaRecordingService::performMerge` surface.

**Architecture:** Three layers. (1) Pure-JVM helpers (`MergeFailureClass`, `MergeRetryPolicy`, `StoragePreflight`) extracted as test seams. (2) `RecoveryMerger.run` embeds a retry loop that operates on raw `ExportResult` BEFORE the existing `toRecoveryOutcome` compression; `RovaRecordingService.handleRecoveryMergeStart` adds the preflight gate before `startForegroundForRecoveryMerge`. (3) `HistoryArtifactMapper` gains `resolveArtifactsPerSegment` mirroring the existing `resolveArtifactsPerSide` P+L fanout; `HistoryViewModel`'s resolution pipeline branches on `Terminated.MULTI_SEGMENT_KEPT`.

**Tech Stack:** Kotlin 2.2.10, kotlinx.coroutines (project uses `runBlocking` NOT `runTest`), AGP 9.2.1, JUnit 4, NotificationCompat, StorageManager.getAllocatableBytes (API 26+), Compose (read-only — no UI changes in scope).

**Spec:** [docs/superpowers/specs/2026-05-26-merge-reliability-bundle-design.md](../specs/2026-05-26-merge-reliability-bundle-design.md)
**ADR:** [docs/adr/0018-recovery-merge-retry-classifier-preflight.md](../../adr/0018-recovery-merge-retry-classifier-preflight.md)

**Baseline commit:** `f3a3e6d` (spec + ADR committed; no code changes yet).

**Owner directive:** Execute under `/karpathy-guidelines` (subagent-driven, fresh implementer per task, two-stage review per `feedback-review-gate-cycle`).

---

## Plan-vs-spec drift note (important for implementers)

The spec's §6.4 names invented types (`HistoryArtifact.SegmentClip`, `manifestDrivenArtifacts(manifest)`) that do **not** exist in the codebase. The spec intent is correct (flat 1-per-segment row enumeration for `MULTI_SEGMENT_KEPT`); this plan implements that intent using actual codebase types:

- **`HistoryArtifactMapper`** already has `resolveArtifactsPerSide(manifest)` returning `List<PerSideArtifact>` for P+L sessions. The Milestone 2 addition follows the same pattern: `resolveArtifactsPerSegment(manifest)` returning `List<PerSegmentArtifact>` for `MULTI_SEGMENT_KEPT` sessions.
- **`VideoItem`** is the existing library-row data class (lives in `HistoryViewModel.kt`). It gains an optional `segmentIndex: Int? = null` field — non-null only for `MULTI_SEGMENT_KEPT`-derived rows.
- The "manifest → row" branching lives in **`HistoryViewModel`** (the existing resolution pipeline), not in a new `manifestDrivenArtifacts` helper.

The spec's design intent is preserved verbatim; only the type names align to reality.

---

## File Structure

10 production files + 5 test files in scope.

| File | Role | Action |
|---|---|---|
| `app/src/main/java/com/aritr/rova/service/recovery/MergeFailureClass.kt` | Sealed classifier over `ExportResult` | **Create** (Task 1) |
| `app/src/main/java/com/aritr/rova/service/recovery/MergeRetryPolicy.kt` | Exponential backoff helper | **Create** (Task 1) |
| `app/src/main/java/com/aritr/rova/service/recovery/StoragePreflight.kt` | Headroom check helper | **Create** (Task 1) |
| `app/src/main/java/com/aritr/rova/service/recovery/RecoveryMerger.kt` | Embed retry loop + classifier + storage-poll seam | **Modify** (Task 2) |
| `app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt` | Preflight gate + notification progress subscription | **Modify** (Task 2) |
| `app/src/main/java/com/aritr/rova/ui/screens/HistoryArtifactMapper.kt` | Add `resolveArtifactsPerSegment` + `PerSegmentArtifact` | **Modify** (Task 3) |
| `app/src/main/java/com/aritr/rova/ui/screens/HistoryViewModel.kt` | Branch resolution pipeline on `MULTI_SEGMENT_KEPT`; add `segmentIndex` to `VideoItem` | **Modify** (Task 3) |
| `app/src/test/java/com/aritr/rova/service/recovery/MergeFailureClassTest.kt` | 9 classification cases | **Create** (Task 1) |
| `app/src/test/java/com/aritr/rova/service/recovery/MergeRetryPolicyTest.kt` | 5 backoff-math cases | **Create** (Task 1) |
| `app/src/test/java/com/aritr/rova/service/recovery/StoragePreflightTest.kt` | 6 boundary-math cases | **Create** (Task 1) |
| `app/src/test/java/com/aritr/rova/service/recovery/RecoveryMergerRetryTest.kt` | 6 integration scenarios (`runBlocking`) | **Create** (Task 2) |
| `app/src/test/java/com/aritr/rova/ui/screens/HistoryArtifactMapperSegmentRowsTest.kt` | 4 segment-row cases | **Create** (Task 3) |

---

## Pre-flight (subagent must verify before Task 1)

- [ ] **Step 0.1: Confirm baseline commit**

Run: `git log --oneline -1`
Expected: `f3a3e6d docs(spec+adr): Milestone 2 merge reliability bundle + ADR-0018`

- [ ] **Step 0.2: Confirm no unstaged `app/` changes**

Run: `git status --short`
Expected: untracked files in repo root (`.claude/`, `.github/`, `gradle_*_out.log`, etc.) and `M NEW_UI_BACKEND_REPLAN.md` (pre-existing). No unstaged or staged changes in `app/`.

If `app/` has unstaged changes: STOP. Surface to owner.

- [ ] **Step 0.3: Confirm `ExportResult` arm list unchanged from spec assumption**

Run (PowerShell): `Select-String -Path app/src/main/java/com/aritr/rova/service/export/ExportTypes.kt -Pattern "^\s+(data class|object) " | Select-Object Line`
Expected: 9 arms listed — `Success`, `MuxFailed`, `CopyFailed`, `RenameFailed`, `PendingInsertFailed`, `FinalizeFailed`, `ManifestWriteFailed`, `UnknownSession`, `InsufficientStorage`. If arm count differs from 9 or names differ, STOP — classifier exhaustiveness will break.

- [ ] **Step 0.4: Confirm `MULTI_SEGMENT_KEPT` ordinal pinning**

Run (PowerShell): `Select-String -Path app/src/main/java/com/aritr/rova/data/SessionManifest.kt -Pattern "MULTI_SEGMENT_KEPT"`
Expected: at least one match listing `MULTI_SEGMENT_KEPT` as ordinal 4 in the `Terminated` enum. If ordinal differs, STOP — Phase 4.3 invariant violated.

---

## Task 1: Pure helpers bundle (3 files + 3 test files)

**Files:**
- Create: `app/src/main/java/com/aritr/rova/service/recovery/MergeFailureClass.kt`
- Create: `app/src/main/java/com/aritr/rova/service/recovery/MergeRetryPolicy.kt`
- Create: `app/src/main/java/com/aritr/rova/service/recovery/StoragePreflight.kt`
- Create: `app/src/test/java/com/aritr/rova/service/recovery/MergeFailureClassTest.kt`
- Create: `app/src/test/java/com/aritr/rova/service/recovery/MergeRetryPolicyTest.kt`
- Create: `app/src/test/java/com/aritr/rova/service/recovery/StoragePreflightTest.kt`

**TDD discipline:** Each helper written test-first. Six steps total per helper (write test → fail → implement → pass → next helper). Three helpers are independent; can be implemented in any order. Single commit at the end.

### 1A — `MergeFailureClass`

- [ ] **Step 1.1: Write the failing test file `MergeFailureClassTest.kt`**

Create `app/src/test/java/com/aritr/rova/service/recovery/MergeFailureClassTest.kt`:

```kotlin
package com.aritr.rova.service.recovery

import com.aritr.rova.service.export.ExportResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Milestone 2 — pure-JVM cover for [classifyMergeFailure]. Pins the
 * 4-transient / 4-permanent / 1-terminal taxonomy approved during the
 * 2026-05-26 brainstorm. Spec
 * `docs/superpowers/specs/2026-05-26-merge-reliability-bundle-design.md` §5 #1.
 */
class MergeFailureClassTest {

    @Test
    fun success_classifies_as_terminal() {
        val result = ExportResult.Success(mediaScanCompleted = true, privateTempRetained = false)
        assertEquals(MergeFailureClass.Terminal, classifyMergeFailure(result))
    }

    @Test
    fun mux_failed_classifies_as_transient() {
        val cause = IllegalStateException("mux")
        val classified = classifyMergeFailure(ExportResult.MuxFailed(cause))
        assertTrue(classified is MergeFailureClass.Transient)
        assertEquals(cause, ((classified as MergeFailureClass.Transient).cause as ExportResult.MuxFailed).cause)
    }

    @Test
    fun copy_failed_classifies_as_transient() {
        val cause = java.io.IOException("copy")
        assertTrue(classifyMergeFailure(ExportResult.CopyFailed(cause)) is MergeFailureClass.Transient)
    }

    @Test
    fun rename_failed_classifies_as_transient() {
        assertTrue(classifyMergeFailure(ExportResult.RenameFailed) is MergeFailureClass.Transient)
    }

    @Test
    fun insufficient_storage_classifies_as_insufficient_storage() {
        val classified = classifyMergeFailure(
            ExportResult.InsufficientStorage(requiredBytes = 100L, availableBytes = 50L)
        )
        assertTrue(classified is MergeFailureClass.InsufficientStorage)
        assertEquals(100L, (classified as MergeFailureClass.InsufficientStorage).requiredBytes)
    }

    @Test
    fun pending_insert_failed_classifies_as_permanent() {
        assertTrue(classifyMergeFailure(ExportResult.PendingInsertFailed(cause = null)) is MergeFailureClass.Permanent)
    }

    @Test
    fun finalize_failed_classifies_as_permanent() {
        assertTrue(classifyMergeFailure(ExportResult.FinalizeFailed(cause = null)) is MergeFailureClass.Permanent)
    }

    @Test
    fun manifest_write_failed_classifies_as_permanent() {
        val classified = classifyMergeFailure(
            ExportResult.ManifestWriteFailed(phase = "test", cause = RuntimeException("boom"))
        )
        assertTrue(classified is MergeFailureClass.Permanent)
    }

    @Test
    fun unknown_session_classifies_as_permanent() {
        assertTrue(classifyMergeFailure(ExportResult.UnknownSession(sessionId = "abc")) is MergeFailureClass.Permanent)
    }
}
```

- [ ] **Step 1.2: Run test to verify it fails (compile-fail)**

Run (subagent gradle access):
```
.\gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.service.recovery.MergeFailureClassTest"
```
Expected: COMPILATION ERROR — `Unresolved reference: classifyMergeFailure`, `MergeFailureClass`.

- [ ] **Step 1.3: Write the minimal implementation**

Create `app/src/main/java/com/aritr/rova/service/recovery/MergeFailureClass.kt`:

```kotlin
package com.aritr.rova.service.recovery

import com.aritr.rova.service.export.ExportResult

/**
 * Milestone 2 — sealed classifier over [ExportResult]. The retry loop in
 * [RecoveryMerger] dispatches on this class to decide retry-or-terminate
 * for each export attempt. Pure JVM; no Android, no coroutines.
 *
 * Taxonomy approved during 2026-05-26 brainstorm:
 *  - 4 transient (retry per [MergeRetryPolicy]): `MuxFailed`, `CopyFailed`, `RenameFailed`.
 *    `InsufficientStorage` is also transient at the user level but routes to a
 *    separate flow (notification action + storage poll), so it's its own class.
 *  - 4 permanent (no retry, write `MULTI_SEGMENT_KEPT, NONE`):
 *    `PendingInsertFailed`, `FinalizeFailed`, `ManifestWriteFailed`, `UnknownSession`.
 *  - 1 terminal: `Success`.
 *
 * Spec: `docs/superpowers/specs/2026-05-26-merge-reliability-bundle-design.md` §5 #1.
 * ADR: `docs/adr/0018-recovery-merge-retry-classifier-preflight.md`.
 */
internal sealed class MergeFailureClass {
    /** [ExportResult.Success] — exit loop, write `COMPLETED, NONE`. */
    object Terminal : MergeFailureClass()

    /** Mux / Copy / Rename — retry per [MergeRetryPolicy]. */
    data class Transient(val cause: ExportResult) : MergeFailureClass()

    /** [ExportResult.InsufficientStorage] — notification action + storage poll. */
    data class InsufficientStorage(val requiredBytes: Long, val availableBytes: Long) : MergeFailureClass()

    /** PendingInsertFailed / FinalizeFailed / ManifestWriteFailed / UnknownSession — no retry. */
    data class Permanent(val cause: ExportResult) : MergeFailureClass()
}

internal fun classifyMergeFailure(result: ExportResult): MergeFailureClass = when (result) {
    is ExportResult.Success -> MergeFailureClass.Terminal
    is ExportResult.MuxFailed -> MergeFailureClass.Transient(result)
    is ExportResult.CopyFailed -> MergeFailureClass.Transient(result)
    is ExportResult.RenameFailed -> MergeFailureClass.Transient(result)
    is ExportResult.InsufficientStorage ->
        MergeFailureClass.InsufficientStorage(result.requiredBytes, result.availableBytes)
    is ExportResult.PendingInsertFailed -> MergeFailureClass.Permanent(result)
    is ExportResult.FinalizeFailed -> MergeFailureClass.Permanent(result)
    is ExportResult.ManifestWriteFailed -> MergeFailureClass.Permanent(result)
    is ExportResult.UnknownSession -> MergeFailureClass.Permanent(result)
}
```

- [ ] **Step 1.4: Run test to verify it passes**

Run:
```
.\gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.service.recovery.MergeFailureClassTest"
```
Expected: BUILD SUCCESSFUL, 9 tests, 0 failures.

### 1B — `MergeRetryPolicy`

- [ ] **Step 1.5: Write the failing test file `MergeRetryPolicyTest.kt`**

Create `app/src/test/java/com/aritr/rova/service/recovery/MergeRetryPolicyTest.kt`:

```kotlin
package com.aritr.rova.service.recovery

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Milestone 2 — pure-JVM cover for [MergeRetryPolicy]. Exponential
 * backoff schedule 1s / 4s / 16s for attempts 1..3; defensive
 * clamping for attempts outside the expected range. Spec §5 #2.
 */
class MergeRetryPolicyTest {

    @Test
    fun max_attempts_is_three() {
        assertEquals(3, MergeRetryPolicy.MAX_ATTEMPTS)
    }

    @Test
    fun backoff_attempt_1_is_one_second() {
        assertEquals(1_000L, MergeRetryPolicy.backoffMillisFor(1))
    }

    @Test
    fun backoff_attempt_2_is_four_seconds() {
        assertEquals(4_000L, MergeRetryPolicy.backoffMillisFor(2))
    }

    @Test
    fun backoff_attempt_3_is_sixteen_seconds() {
        assertEquals(16_000L, MergeRetryPolicy.backoffMillisFor(3))
    }

    @Test
    fun backoff_out_of_range_clamps_at_sixteen_seconds() {
        // Defensive — should not be reached in production (MAX_ATTEMPTS gates).
        assertEquals(16_000L, MergeRetryPolicy.backoffMillisFor(0))
        assertEquals(16_000L, MergeRetryPolicy.backoffMillisFor(99))
        assertEquals(16_000L, MergeRetryPolicy.backoffMillisFor(-1))
    }
}
```

- [ ] **Step 1.6: Run test to verify it fails (compile-fail)**

Run:
```
.\gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.service.recovery.MergeRetryPolicyTest"
```
Expected: COMPILATION ERROR — `Unresolved reference: MergeRetryPolicy`.

- [ ] **Step 1.7: Write the minimal implementation**

Create `app/src/main/java/com/aritr/rova/service/recovery/MergeRetryPolicy.kt`:

```kotlin
package com.aritr.rova.service.recovery

/**
 * Milestone 2 — backoff schedule for transient merge failures. Three
 * attempts with exponential delays. Pure JVM; suspending `delay(...)`
 * by the caller is the actual sleep mechanism.
 *
 * Spec: `docs/superpowers/specs/2026-05-26-merge-reliability-bundle-design.md` §5 #2.
 */
internal object MergeRetryPolicy {
    const val MAX_ATTEMPTS: Int = 3

    /**
     * 1-indexed attempt → millis to wait BEFORE issuing that attempt.
     * Out-of-range inputs clamp at 16s defensively; callers that respect
     * [MAX_ATTEMPTS] never produce them.
     */
    fun backoffMillisFor(attempt: Int): Long = when (attempt) {
        1 -> 1_000L
        2 -> 4_000L
        3 -> 16_000L
        else -> 16_000L
    }
}
```

- [ ] **Step 1.8: Run test to verify it passes**

Run:
```
.\gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.service.recovery.MergeRetryPolicyTest"
```
Expected: BUILD SUCCESSFUL, 5 tests, 0 failures.

### 1C — `StoragePreflight`

- [ ] **Step 1.9: Write the failing test file `StoragePreflightTest.kt`**

Create `app/src/test/java/com/aritr/rova/service/recovery/StoragePreflightTest.kt`:

```kotlin
package com.aritr.rova.service.recovery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Milestone 2 — pure-JVM cover for [StoragePreflight]. Validates the
 * `available - accumulated >= FINALIZE_HEADROOM_BYTES (50 MiB)` boundary.
 * Spec §5 #3.
 */
class StoragePreflightTest {

    private val MIB = 1024L * 1024L
    private val HEADROOM = StoragePreflight.FINALIZE_HEADROOM_BYTES   // 50 MiB

    @Test
    fun exact_boundary_passes() {
        // available - accumulated == HEADROOM should pass (>=)
        assertTrue(StoragePreflight.hasHeadroom(
            availableBytes = 200L * MIB,
            accumulatedSessionBytes = 200L * MIB - HEADROOM,
        ))
    }

    @Test
    fun just_below_boundary_fails() {
        assertFalse(StoragePreflight.hasHeadroom(
            availableBytes = 200L * MIB,
            accumulatedSessionBytes = 200L * MIB - HEADROOM + 1L,
        ))
    }

    @Test
    fun just_above_boundary_passes() {
        assertTrue(StoragePreflight.hasHeadroom(
            availableBytes = 200L * MIB,
            accumulatedSessionBytes = 200L * MIB - HEADROOM - 1L,
        ))
    }

    @Test
    fun zero_accumulated_passes_with_sufficient_available() {
        assertTrue(StoragePreflight.hasHeadroom(
            availableBytes = 100L * MIB,
            accumulatedSessionBytes = 0L,
        ))
    }

    @Test
    fun huge_accumulated_exceeds_available_fails() {
        assertFalse(StoragePreflight.hasHeadroom(
            availableBytes = 100L * MIB,
            accumulatedSessionBytes = 10L * 1024L * MIB,   // 10 GiB
        ))
    }

    @Test
    fun zero_available_fails_unconditionally() {
        assertFalse(StoragePreflight.hasHeadroom(
            availableBytes = 0L,
            accumulatedSessionBytes = 0L,
        ))
    }
}
```

- [ ] **Step 1.10: Run test to verify it fails (compile-fail)**

Run:
```
.\gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.service.recovery.StoragePreflightTest"
```
Expected: COMPILATION ERROR — `Unresolved reference: StoragePreflight`.

- [ ] **Step 1.11: Write the minimal implementation**

Create `app/src/main/java/com/aritr/rova/service/recovery/StoragePreflight.kt`:

```kotlin
package com.aritr.rova.service.recovery

/**
 * Milestone 2 — preflight headroom check for recovery merge. Called from
 * [com.aritr.rova.service.RovaRecordingService.handleRecoveryMergeStart]
 * BEFORE `startForegroundForRecoveryMerge`; on failure, the existing
 * `CANT_MERGE` signal (Phase 4.3, [com.aritr.rova.ui.warnings.WarningId.CANT_MERGE])
 * is raised and the manifest stays non-terminal.
 *
 * `FINALIZE_HEADROOM_BYTES` is the merge-output safety margin — covers
 * filesystem block-aligned overhead, MediaMuxer's own scratch needs,
 * and concurrent-app drift between the check and the muxer open.
 *
 * Spec: `docs/superpowers/specs/2026-05-26-merge-reliability-bundle-design.md` §5 #3.
 */
internal object StoragePreflight {
    /** 50 MiB headroom. */
    const val FINALIZE_HEADROOM_BYTES: Long = 50L * 1024L * 1024L

    fun hasHeadroom(availableBytes: Long, accumulatedSessionBytes: Long): Boolean =
        availableBytes - accumulatedSessionBytes >= FINALIZE_HEADROOM_BYTES
}
```

- [ ] **Step 1.12: Run test to verify it passes**

Run:
```
.\gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.service.recovery.StoragePreflightTest"
```
Expected: BUILD SUCCESSFUL, 6 tests, 0 failures.

### 1D — Full suite + commit

- [ ] **Step 1.13: Run full test suite to verify zero regression**

Run:
```
.\gradlew.bat :app:testDebugUnitTest
```
Expected: BUILD SUCCESSFUL, ≥1221 tests (1201 baseline + 9 + 5 + 6 = 1221), 0 failed / 0 ignored / 0 skipped.

If any existing test fails: STOP. Helpers are additive — nothing they touch could legitimately break existing tests. Investigate.

- [ ] **Step 1.14: Commit**

```
git add app/src/main/java/com/aritr/rova/service/recovery/MergeFailureClass.kt app/src/main/java/com/aritr/rova/service/recovery/MergeRetryPolicy.kt app/src/main/java/com/aritr/rova/service/recovery/StoragePreflight.kt app/src/test/java/com/aritr/rova/service/recovery/MergeFailureClassTest.kt app/src/test/java/com/aritr/rova/service/recovery/MergeRetryPolicyTest.kt app/src/test/java/com/aritr/rova/service/recovery/StoragePreflightTest.kt
git commit -m "feat(recovery): MergeFailureClass + MergeRetryPolicy + StoragePreflight (Milestone 2 Task 1)

Three pure-JVM helpers for the upcoming RecoveryMerger retry refactor.
MergeFailureClass classifies 9 ExportResult arms into Terminal /
Transient / InsufficientStorage / Permanent buckets. MergeRetryPolicy
returns 1s/4s/16s exponential backoff for attempts 1..3, defensive
clamp at 16s outside that range. StoragePreflight.hasHeadroom checks
50 MiB headroom over accumulated session bytes.

20 new JVM tests (9 + 5 + 6); no production consumers wired yet
(Task 2). Full suite green (1221 tests / 0 failures).

Spec: docs/superpowers/specs/2026-05-26-merge-reliability-bundle-design.md
ADR: docs/adr/0018-recovery-merge-retry-classifier-preflight.md

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

Verify: `git log --oneline -2` shows the new commit on top of `f3a3e6d`.

---

## Task 2: Merge surface refactor (RecoveryMerger retry loop + Service preflight + notification progress)

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/service/recovery/RecoveryMerger.kt` — embed retry loop using Task 1 helpers; add storage-poll seam
- Modify: `app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt:693-770` — add preflight gate; subscribe to progress signal for notification updates; provide storage-poll lambda
- Create: `app/src/test/java/com/aritr/rova/service/recovery/RecoveryMergerRetryTest.kt` — 6 integration scenarios

**Atomicity rationale:** `RecoveryMerger.run` is changed (signature gains parameters); `RovaRecordingService.handleRecoveryMergeStart` is the sole caller (verified Pre-flight Step 0.3). Both files MUST move together or compile breaks.

**TDD discipline:** Tests first (integration test against the new retry-aware `RecoveryMerger`), then implementation, then Service-side wiring.

### 2A — `RecoveryMerger` retry refactor

- [ ] **Step 2.1: Write the failing integration test `RecoveryMergerRetryTest.kt`**

Create `app/src/test/java/com/aritr/rova/service/recovery/RecoveryMergerRetryTest.kt`:

```kotlin
package com.aritr.rova.service.recovery

import com.aritr.rova.service.export.ExportResult
import com.aritr.rova.ui.signals.RecoveryMergeOutcomeSignal
import com.aritr.rova.ui.signals.RecoveryMergeOutcomeSignal.RecoveryMergeOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.ArrayDeque

/**
 * Milestone 2 — integration test for [RecoveryMerger]'s retry loop. Six
 * scenarios verify the contract approved during 2026-05-26 brainstorm:
 * Success first try, Transient×3 exhausted, Transient×2 then Success,
 * Permanent first, InsufficientStorage + poll resume, InsufficientStorage
 * + poll timeout. Spec §7.1.
 *
 * Uses kotlinx.coroutines.runBlocking — project convention; NOT runTest
 * (no kotlinx-coroutines-test dependency).
 */
class RecoveryMergerRetryTest {

    /** Fake signal — captures emissions for assertion. */
    private class CapturingSignal : RecoveryMergeOutcomeSignal() {
        val emittedOutcomes = mutableListOf<Pair<String, RecoveryMergeOutcome>>()
        val emittedProgress = mutableListOf<Pair<String, Float>>()
        override fun emitOutcome(sessionId: String, outcome: RecoveryMergeOutcome) {
            emittedOutcomes += sessionId to outcome
        }
        override fun emitInProgress(sessionId: String, progress: Float) {
            emittedProgress += sessionId to progress
        }
    }

    private val FAKE_FILE = File("/fake/segment.mp4")

    /** Builds a RecoveryMerger with programmable per-attempt ExportResults and storage-poll outcomes. */
    private fun buildMerger(
        results: List<ExportResult>,
        pollOutcomes: List<Boolean> = emptyList(),   // true == space freed; false == not yet
        backoffOverride: (Int) -> Long = { 0L },     // fast-forward in tests
    ): Pair<RecoveryMerger, CapturingSignal> {
        val signal = CapturingSignal()
        val queue = ArrayDeque(results)
        val pollQueue = ArrayDeque(pollOutcomes)
        val merger = RecoveryMerger(
            loadSegments = { listOf(FAKE_FILE) },
            sessionDirOf = { File("/fake/session_dir") },
            exportRecovered = { _, _, _ ->
                queue.pollFirst() ?: error("test ran out of programmed ExportResults")
            },
            signal = signal,
            backoffMillisOverride = backoffOverride,
            pollForStorage = { _, _ -> pollQueue.pollFirst() ?: false },
        )
        return merger to signal
    }

    @Test
    fun success_first_try_writes_succeeded() = runBlocking {
        val (merger, signal) = buildMerger(
            results = listOf(ExportResult.Success(mediaScanCompleted = true, privateTempRetained = false))
        )
        val outcome = merger.run("session-1")
        assertEquals(RecoveryMergeOutcome.Succeeded, outcome)
        assertEquals(1, signal.emittedOutcomes.size)
        assertEquals("session-1" to RecoveryMergeOutcome.Succeeded, signal.emittedOutcomes[0])
    }

    @Test
    fun three_transient_failures_exhausts_to_mux_failed() = runBlocking {
        val cause = IllegalStateException("flake")
        val (merger, signal) = buildMerger(
            results = listOf(
                ExportResult.MuxFailed(cause),
                ExportResult.MuxFailed(cause),
                ExportResult.MuxFailed(cause),
            )
        )
        val outcome = merger.run("session-2")
        assertTrue("expected MuxFailed terminal, got $outcome", outcome is RecoveryMergeOutcome.MuxFailed)
    }

    @Test
    fun two_transients_then_success_completes() = runBlocking {
        val cause = IllegalStateException("flake")
        val (merger, signal) = buildMerger(
            results = listOf(
                ExportResult.MuxFailed(cause),
                ExportResult.CopyFailed(cause),
                ExportResult.Success(mediaScanCompleted = true, privateTempRetained = false),
            )
        )
        val outcome = merger.run("session-3")
        assertEquals(RecoveryMergeOutcome.Succeeded, outcome)
    }

    @Test
    fun permanent_failure_no_retry() = runBlocking {
        val (merger, signal) = buildMerger(
            results = listOf(ExportResult.UnknownSession(sessionId = "session-4")),
        )
        val outcome = merger.run("session-4")
        assertEquals(RecoveryMergeOutcome.UnknownSession, outcome)
    }

    @Test
    fun insufficient_storage_then_poll_freed_then_success() = runBlocking {
        val (merger, signal) = buildMerger(
            results = listOf(
                ExportResult.InsufficientStorage(requiredBytes = 100L, availableBytes = 50L),
                ExportResult.Success(mediaScanCompleted = true, privateTempRetained = false),
            ),
            pollOutcomes = listOf(true),
        )
        val outcome = merger.run("session-5")
        assertEquals(RecoveryMergeOutcome.Succeeded, outcome)
    }

    @Test
    fun insufficient_storage_then_poll_timeout_writes_insufficient_storage() = runBlocking {
        val (merger, signal) = buildMerger(
            results = listOf(
                ExportResult.InsufficientStorage(requiredBytes = 100L, availableBytes = 50L),
            ),
            pollOutcomes = listOf(false),   // never freed
        )
        val outcome = merger.run("session-6")
        assertTrue(
            "expected InsufficientStorage terminal, got $outcome",
            outcome is RecoveryMergeOutcome.InsufficientStorage
        )
    }
}
```

- [ ] **Step 2.2: Run test to verify it fails (compile-fail)**

Run:
```
.\gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.service.recovery.RecoveryMergerRetryTest"
```
Expected: COMPILATION ERROR — `RecoveryMerger` constructor doesn't accept `backoffMillisOverride` or `pollForStorage`; method `emitInProgress` may not be `open`. This confirms the retry seams don't exist yet.

If `emitInProgress` is not `open` in `RecoveryMergeOutcomeSignal`, the test's `override` line will fail — Step 2.3 must make those methods overridable (add `open` keyword in `RecoveryMergeOutcomeSignal`). Note this in the implementation; do NOT change signal subscribers.

- [ ] **Step 2.3: Modify `RecoveryMergeOutcomeSignal` to allow test overrides**

Before changing `RecoveryMerger`, confirm `RecoveryMergeOutcomeSignal` allows `open` overrides on `emitOutcome` and `emitInProgress`. Read the file:

Run (PowerShell): `Select-String -Path app/src/main/java/com/aritr/rova/ui/signals/RecoveryMergeOutcomeSignal.kt -Pattern "(class|fun emit)"`

If both methods are not already `open`, edit `RecoveryMergeOutcomeSignal.kt` to add `open` to the class declaration and to `emitOutcome` and `emitInProgress` function declarations. Verify no other production caller relies on the current finality (subclassing was previously test-only).

- [ ] **Step 2.4: Modify `RecoveryMerger.kt` — embed retry loop**

Replace the entire body of `app/src/main/java/com/aritr/rova/service/recovery/RecoveryMerger.kt` with:

```kotlin
package com.aritr.rova.service.recovery

import com.aritr.rova.service.export.ExportResult
import com.aritr.rova.ui.signals.RecoveryMergeOutcomeSignal
import com.aritr.rova.ui.signals.RecoveryMergeOutcomeSignal.RecoveryMergeOutcome
import kotlinx.coroutines.delay
import java.io.File

/**
 * Phase 4.3 + Milestone 2 — wraps the `ExportPipeline.exportRecovered` call
 * for a single recovery merge session. Owns:
 *  - Segment list lookup (via [loadSegments]) from `SessionStore`.
 *  - Session-directory resolution (via [sessionDirOf]).
 *  - Milestone 2 retry loop: classifier-driven transient/permanent
 *    dispatch over [ExportResult] with [MergeRetryPolicy] backoff.
 *  - Milestone 2 separate InsufficientStorage flow: storage-poll via
 *    [pollForStorage] callback, 30-second polling cadence, 10-minute cap.
 *  - Translation from terminal [ExportResult] → user-surface
 *    [RecoveryMergeOutcome] (existing Phase 4.3 contract).
 *  - Push to [RecoveryMergeOutcomeSignal] for both progress + final.
 *
 * Constructor seams keep this JVM-testable: production wires
 * `ExportPipeline.exportRecovered` + real polling lambdas; tests inject
 * pure-function fakes plus a `backoffMillisOverride` to fast-forward
 * exponential delays.
 *
 * Hosted by `RovaRecordingService.handleRecoveryMergeStart`.
 *
 * Spec: `docs/superpowers/specs/2026-05-26-merge-reliability-bundle-design.md` §6.
 * ADR: `docs/adr/0018-recovery-merge-retry-classifier-preflight.md`.
 */
class RecoveryMerger(
    private val loadSegments: (sessionId: String) -> List<File>,
    private val sessionDirOf: (sessionId: String) -> File,
    private val exportRecovered: suspend (sessionDir: File, segments: List<File>, onProgress: (Float) -> Unit) -> ExportResult,
    private val signal: RecoveryMergeOutcomeSignal,
    /**
     * Storage-poll callback. Called per 30-second tick during the
     * InsufficientStorage flow. Returns `true` when sufficient space is
     * available (the merge can retry from `attempt = 1`). The merger
     * stops polling after this returns true OR after the 10-minute cap.
     *
     * Production wiring (Service): query
     * `StorageManager.getAllocatableBytes(externalRoot)` and compare
     * against `requiredBytes`.
     */
    private val pollForStorage: suspend (requiredBytes: Long, availableBytesEstimate: Long) -> Boolean = { _, _ -> false },
    /**
     * Override [MergeRetryPolicy.backoffMillisFor] for tests. Production
     * call sites omit this argument so the real exponential schedule
     * applies. Tests pass `{ 0L }` to fast-forward.
     */
    private val backoffMillisOverride: ((attempt: Int) -> Long)? = null,
    /**
     * Poll cap in milliseconds. Production: 10 minutes. Tests can override
     * to bound runtime.
     */
    private val pollCapMillis: Long = 10L * 60L * 1000L,
    /**
     * Poll cadence in milliseconds. Production: 30 seconds. Tests pass 0L.
     */
    private val pollIntervalMillis: Long = 30L * 1000L,
    private val onProgress: (String, Float) -> Unit = signal::emitInProgress,
) {

    suspend fun run(sessionId: String): RecoveryMergeOutcome {
        val segments = loadSegments(sessionId)
        if (segments.isEmpty()) {
            val outcome = RecoveryMergeOutcome.UnknownSession
            signal.emitOutcome(sessionId, outcome)
            return outcome
        }
        val sessionDir = sessionDirOf(sessionId)

        var attempt = 1
        while (true) {
            val result = exportRecovered(sessionDir, segments) { progress ->
                onProgress(sessionId, progress)
            }
            when (val classified = classifyMergeFailure(result)) {
                is MergeFailureClass.Terminal -> {
                    val outcome = RecoveryMergeOutcome.Succeeded
                    signal.emitOutcome(sessionId, outcome)
                    return outcome
                }
                is MergeFailureClass.Transient -> {
                    if (attempt < MergeRetryPolicy.MAX_ATTEMPTS) {
                        val backoff = backoffMillisOverride?.invoke(attempt)
                            ?: MergeRetryPolicy.backoffMillisFor(attempt)
                        delay(backoff)
                        attempt += 1
                        continue
                    }
                    // Exhausted — translate the LAST transient ExportResult to user surface.
                    val outcome = classified.cause.toRecoveryOutcome()
                    signal.emitOutcome(sessionId, outcome)
                    return outcome
                }
                is MergeFailureClass.InsufficientStorage -> {
                    val freed = waitForStorage(classified.requiredBytes, classified.availableBytes)
                    if (freed) {
                        // Reset attempt counter and re-enter the retry loop.
                        attempt = 1
                        continue
                    }
                    val outcome = RecoveryMergeOutcome.InsufficientStorage(
                        classified.requiredBytes, classified.availableBytes
                    )
                    signal.emitOutcome(sessionId, outcome)
                    return outcome
                }
                is MergeFailureClass.Permanent -> {
                    val outcome = classified.cause.toRecoveryOutcome()
                    signal.emitOutcome(sessionId, outcome)
                    return outcome
                }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable — retry loop always returns")
    }

    private suspend fun waitForStorage(requiredBytes: Long, lastAvailable: Long): Boolean {
        val deadline = pollCapMillis
        var elapsed = 0L
        while (elapsed < deadline) {
            if (pollForStorage(requiredBytes, lastAvailable)) return true
            delay(pollIntervalMillis)
            elapsed += pollIntervalMillis
        }
        return false
    }

    private fun ExportResult.toRecoveryOutcome(): RecoveryMergeOutcome = when (this) {
        is ExportResult.Success -> RecoveryMergeOutcome.Succeeded
        is ExportResult.InsufficientStorage ->
            RecoveryMergeOutcome.InsufficientStorage(requiredBytes, availableBytes)
        is ExportResult.MuxFailed -> RecoveryMergeOutcome.MuxFailed(cause)
        is ExportResult.UnknownSession -> RecoveryMergeOutcome.UnknownSession
        is ExportResult.CopyFailed -> RecoveryMergeOutcome.MuxFailed(cause)
        is ExportResult.RenameFailed -> RecoveryMergeOutcome.MuxFailed(IllegalStateException("rename failed"))
        is ExportResult.PendingInsertFailed -> RecoveryMergeOutcome.MuxFailed(cause ?: IllegalStateException("pending insert failed"))
        is ExportResult.FinalizeFailed -> RecoveryMergeOutcome.MuxFailed(cause ?: IllegalStateException("finalize failed"))
        is ExportResult.ManifestWriteFailed -> RecoveryMergeOutcome.MuxFailed(cause)
    }
}
```

- [ ] **Step 2.5: Run integration test to verify it passes**

Run:
```
.\gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.service.recovery.RecoveryMergerRetryTest"
```
Expected: BUILD SUCCESSFUL, 6 tests, 0 failures.

If any test fails, the bug is in `RecoveryMerger`, not the test. Read the failure trace; the assertions encode the spec contract. Common pitfalls:
- `delay(0)` still suspends — fine.
- Storage-poll loop should NOT call `pollForStorage` after returning `true` (test asserts via queue exhaustion implicitly).

### 2B — `RovaRecordingService` preflight + notification progress

- [ ] **Step 2.6: Add preflight gate in `handleRecoveryMergeStart`**

In `app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt`, locate `handleRecoveryMergeStart` (line ~693). After the existing `recoveryMergeStartGate` dispatch resolves to `RecoveryMergeStartDecision.Accept` but BEFORE `startForegroundForRecoveryMerge` is called, insert the preflight gate.

Replace the Accept arm of the `when (decision)` block with:

```kotlin
            is RecoveryMergeStartDecision.Accept -> {
                // Milestone 2 — preflight headroom gate. Spec §5 #3.
                val manifest = sessionStore.loadManifest(decision.sessionId)
                if (manifest != null) {
                    val available = queryAllocatableBytes()
                    val accumulated = com.aritr.rova.data.StorageEstimator.accumulatedSessionBytes(
                        sessionDir = sessionStore.sessionDir(decision.sessionId),
                        segmentCount = manifest.segments.size,
                        durationSeconds = manifest.segments.sumOf { it.durationMs } / 1000L,
                        resolution = manifest.config.resolution,
                    )
                    if (!com.aritr.rova.service.recovery.StoragePreflight.hasHeadroom(available, accumulated)) {
                        signal.emitOutcome(
                            decision.sessionId,
                            RecoveryMergeOutcomeSignal.RecoveryMergeOutcome.InsufficientStorage(
                                requiredBytes = accumulated + com.aritr.rova.service.recovery.StoragePreflight.FINALIZE_HEADROOM_BYTES,
                                availableBytes = available,
                            ),
                        )
                        stopSelf(startId)
                        return START_NOT_STICKY
                    }
                }
                if (!startForegroundForRecoveryMerge(decision.sessionId)) {
                    // FGS start blocked (e.g. ForegroundServiceStartNotAllowedException) —
                    // surface to consumer so it doesn't hang waiting for an outcome.
                    signal.emitOutcome(
                        decision.sessionId,
                        RecoveryMergeOutcomeSignal.RecoveryMergeOutcome.ServiceBusy,
                    )
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                recoveryMergeInFlight = true
                serviceScope.launch {
                    val merger = com.aritr.rova.service.recovery.RecoveryMerger(
                        loadSegments = { sid ->
                            val mf = sessionStore.loadManifest(sid)
                            val dir = sessionStore.sessionDir(sid)
                            mf?.segments
                                ?.map { java.io.File(dir, it.filename) }
                                ?.filter { it.exists() && it.length() > 0 }
                                ?: emptyList()
                        },
                        sessionDirOf = { sid -> sessionStore.sessionDir(sid) },
                        exportRecovered = { dir, segs, onProgress ->
                            com.aritr.rova.service.export.ExportPipeline.exportRecovered(
                                context = this@RovaRecordingService,
                                sessionStore = sessionStore,
                                sessionId = decision.sessionId,
                                sessionDir = dir,
                                segments = segs,
                                onProgress = onProgress,
                            )
                        },
                        signal = signal,
                        pollForStorage = { required, _ ->
                            val current = queryAllocatableBytes()
                            current >= required
                        },
                    )
                    try {
                        merger.run(decision.sessionId)
                    } finally {
                        recoveryMergeInFlight = false
                        @Suppress("DEPRECATION")
                        stopForeground(true)
                        stopSelf(startId)
                    }
                }
                START_NOT_STICKY
            }
```

- [ ] **Step 2.7: Add `queryAllocatableBytes` helper to the service**

Locate the private-helpers section of `RovaRecordingService.kt` (near `startForegroundForRecoveryMerge`). Add this helper method:

```kotlin
    /**
     * Milestone 2 — query free bytes on the external storage root. Uses
     * `StorageManager.getAllocatableBytes` on API 26+ (project minSdk is
     * 24 so this version check is needed) with `usableSpace` fallback for
     * API 24-25. Same pattern as `exportRecovered`'s post-merge check.
     */
    private fun queryAllocatableBytes(): Long {
        val externalRoot = getExternalFilesDir(null) ?: return 0L
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val sm = getSystemService(android.content.Context.STORAGE_SERVICE) as android.os.storage.StorageManager
                val uuid = sm.getUuidForPath(externalRoot)
                sm.getAllocatableBytes(uuid)
            } catch (t: Throwable) {
                externalRoot.usableSpace
            }
        } else {
            externalRoot.usableSpace
        }
    }
```

- [ ] **Step 2.8: Wire FGS notification progress updates**

In `RovaRecordingService.kt`, add a private throttle-state field near other service-state fields:

```kotlin
    private var lastMergeNotifyMillis: Long = 0L
    private val MERGE_NOTIFY_THROTTLE_MS = 500L
```

Add a notification-builder helper method:

```kotlin
    /**
     * Milestone 2 — progress-aware merge notification update. Throttled
     * to 500ms; final tick (`fraction >= 1f`) bypasses throttle. Reuses
     * the existing channel + NOTIFICATION_ID_RECOVERY_MERGE.
     */
    private fun updateMergeNotification(sessionId: String, fraction: Float) {
        val now = SystemClock.elapsedRealtime()
        val isFinal = fraction >= 1f
        if (!isFinal && now - lastMergeNotifyMillis < MERGE_NOTIFY_THROTTLE_MS) return
        lastMergeNotifyMillis = now
        val percent = (fraction.coerceIn(0f, 1f) * 100f).toInt()
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Merging recovered clips")
            .setContentText("Merging $percent%")
            .setProgress(100, percent, false)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
        notificationManager.notify(NOTIFICATION_ID_RECOVERY_MERGE, notif)
    }
```

If `notificationManager` is not already a private field in the service, locate the line where notifications are issued in the existing code (likely via `NotificationManagerCompat.from(this).notify(...)` somewhere) and use the same accessor — e.g. replace `notificationManager.notify(...)` with `NotificationManagerCompat.from(this).notify(...)` if `notificationManager` is not pre-existing. Pick the matching pattern used in `startForegroundForRecoveryMerge`'s siblings.

- [ ] **Step 2.9: Subscribe the service to progress emissions for notification updates**

Inside the `serviceScope.launch { ... merger.run(...) ... }` block from Step 2.6, the existing `signal::emitInProgress` already receives progress. To route those into the notification helper, subscribe to `signal.inProgressFlow` (or equivalent — name to confirm at edit-time via the existing `RecoveryMergeOutcomeSignal` source).

The simplest non-flow approach: pass the notification update lambda as a constructor arg to `RecoveryMerger` via the existing `onProgress` parameter override:

In Step 2.6's `RecoveryMerger(...)` constructor call, ADD this line as the last constructor argument (after `pollForStorage = ...`):

```kotlin
                        onProgress = { sid, fraction ->
                            signal.emitInProgress(sid, fraction)
                            updateMergeNotification(sid, fraction)
                        },
```

This routes per-progress emission to both the existing signal subscribers (UI / RecoveryEchoBanner) AND the new notification updater. No new Flow plumbing.

- [ ] **Step 2.10: Compile to verify the atomic refactor is consistent**

Run:
```
.\gradlew.bat :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL. If `Unresolved reference: RecoveryMergeStartDecision` or similar appears, the file imports may need updating — the existing imports section already references the recovery package.

If compile fails: investigate. Common issues:
- `signal::emitInProgress` reference fails if the method is now `open` and you missed a callsite that returned a specific override — re-check Step 2.3.
- `notificationManager` not in scope — use `NotificationManagerCompat.from(this)` inline (replace the field reference).

- [ ] **Step 2.11: Run full test suite**

Run:
```
.\gradlew.bat :app:testDebugUnitTest
```
Expected: BUILD SUCCESSFUL, ≥1227 tests (1221 from Task 1 + 6 from Task 2A), 0 failed / 0 ignored / 0 skipped.

If any test fails outside `RecoveryMergerRetryTest` and the new helpers' tests: STOP. Hard invariants violated.

- [ ] **Step 2.12: Run lint**

Run:
```
.\gradlew.bat :app:lintDebug
```
Expected: BUILD SUCCESSFUL, `0 errors, 51 warnings, 1 hint` (zero-delta vs `b3fad71` baseline).

If a new `NewApi` warning fires on `getAllocatableBytes` despite the SDK check — verify the `if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)` form is exact; lint requires this specific pattern.

- [ ] **Step 2.13: Build debug APK**

Run:
```
.\gradlew.bat :app:assembleDebug
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2.14: Commit**

```
git add app/src/main/java/com/aritr/rova/service/recovery/RecoveryMerger.kt app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt app/src/main/java/com/aritr/rova/ui/signals/RecoveryMergeOutcomeSignal.kt app/src/test/java/com/aritr/rova/service/recovery/RecoveryMergerRetryTest.kt
git commit -m "feat(recovery): retry loop + preflight gate + notification progress (Milestone 2 Task 2)

RecoveryMerger.run embeds a retry loop using MergeFailureClass +
MergeRetryPolicy (Task 1). Transient ExportResult arms (Mux/Copy/
Rename) retry 3 times with 1s/4s/16s backoff; InsufficientStorage
flows into a separate poll-resume path (30s interval, 10-min cap);
Permanent arms exit immediately. Terminal Success writes COMPLETED
as before.

RovaRecordingService.handleRecoveryMergeStart adds preflight headroom
check via StoragePreflight.hasHeadroom BEFORE startForeground; on
failure raises InsufficientStorage outcome (existing Phase 4.3 CANT_MERGE
wiring) with no FGS start. Notification progress wired via the existing
onProgress callback through a throttled (500ms) updater; final tick
bypasses throttle.

RecoveryMergeOutcomeSignal methods made open for test-side override.

Hard invariants preserved: ExportResult arm list unchanged; Terminated
enum unchanged; WarningId/WarningPrecedence/CANT_MERGE row #14
unchanged; service/dualrecord/** and EglRouter/AspectFitMath untouched.

Full suite: 1227 tests / 0 failures. Lint zero-delta.

Spec: docs/superpowers/specs/2026-05-26-merge-reliability-bundle-design.md
ADR: docs/adr/0018-recovery-merge-retry-classifier-preflight.md

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

Verify: `git log --oneline -3` shows Task 2 + Task 1 commits on top of `f3a3e6d`.

---

## Task 3: Library row enumeration for `MULTI_SEGMENT_KEPT`

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/HistoryArtifactMapper.kt` — add `resolveArtifactsPerSegment` + `PerSegmentArtifact` data class
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/HistoryViewModel.kt` — branch resolution pipeline; add `VideoItem.segmentIndex`
- Create: `app/src/test/java/com/aritr/rova/ui/screens/HistoryArtifactMapperSegmentRowsTest.kt` — 4 cases

**Atomicity rationale:** `HistoryViewModel` consumes `HistoryArtifactMapper`. Adding the mapper API + the VM call-site happens together so compile stays green; `VideoItem` field addition is part of the same change.

### 3A — Pure-helper test + mapper extension

- [ ] **Step 3.1: Write the failing test file `HistoryArtifactMapperSegmentRowsTest.kt`**

Create `app/src/test/java/com/aritr/rova/ui/screens/HistoryArtifactMapperSegmentRowsTest.kt`:

```kotlin
package com.aritr.rova.ui.screens

import com.aritr.rova.data.SegmentRecord
import com.aritr.rova.data.SessionConfig
import com.aritr.rova.data.SessionManifest
import com.aritr.rova.data.Terminated
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Milestone 2 — pure-JVM cover for [HistoryArtifactMapper.resolveArtifactsPerSegment].
 * Verifies the flat 1-per-segment row enumeration for MULTI_SEGMENT_KEPT sessions;
 * other terminal states emit emptyList (caller falls back to the existing
 * single-artifact path or the per-side P+L path). Spec §5 #4 + §6.4.
 */
class HistoryArtifactMapperSegmentRowsTest {

    private fun manifest(
        sessionId: String,
        terminated: Terminated,
        segmentCount: Int,
        mode: String = "Portrait",
    ): SessionManifest {
        val segs = (0 until segmentCount).map { i ->
            SegmentRecord(filename = "segment_$i.mp4", durationMs = 1_000L, bitrate = 8_000_000, size = 1024L * (i + 1))
        }
        return SessionManifest(
            sessionId = sessionId,
            config = SessionConfig(mode = mode, resolution = "1080p"),
            segments = segs,
            terminated = terminated,
        )
    }

    @Test
    fun completed_session_with_segments_emits_empty_list() {
        val m = manifest("s1", Terminated.COMPLETED, segmentCount = 3)
        assertTrue(HistoryArtifactMapper.resolveArtifactsPerSegment(m).isEmpty())
    }

    @Test
    fun multi_segment_kept_with_five_segments_emits_five_rows() {
        val m = manifest("s2", Terminated.MULTI_SEGMENT_KEPT, segmentCount = 5)
        val artifacts = HistoryArtifactMapper.resolveArtifactsPerSegment(m)
        assertEquals(5, artifacts.size)
        assertEquals(listOf(0, 1, 2, 3, 4), artifacts.map { it.segmentIndex })
        assertEquals("segment_0.mp4", artifacts[0].filename)
        assertEquals("segment_4.mp4", artifacts[4].filename)
    }

    @Test
    fun multi_segment_kept_with_zero_segments_emits_empty_list() {
        val m = manifest("s3", Terminated.MULTI_SEGMENT_KEPT, segmentCount = 0)
        assertTrue(HistoryArtifactMapper.resolveArtifactsPerSegment(m).isEmpty())
    }

    @Test
    fun multi_segment_kept_with_one_segment_emits_one_row() {
        val m = manifest("s4", Terminated.MULTI_SEGMENT_KEPT, segmentCount = 1)
        val artifacts = HistoryArtifactMapper.resolveArtifactsPerSegment(m)
        assertEquals(1, artifacts.size)
        assertEquals(0, artifacts[0].segmentIndex)
    }
}
```

- [ ] **Step 3.2: Run test to verify it fails (compile-fail)**

Run:
```
.\gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.screens.HistoryArtifactMapperSegmentRowsTest"
```
Expected: COMPILATION ERROR — `Unresolved reference: resolveArtifactsPerSegment` and `PerSegmentArtifact`.

If the `SessionManifest` or `SegmentRecord` constructor signatures don't match what the test uses, you'll see additional errors — verify by reading `SessionManifest.kt` (`SegmentRecord` declaration) and `SessionConfig.kt`. If field names differ from the test (`bitrate` vs `bitrateBps`, `size` vs `sizeBytes`), update the test to match the real names before proceeding.

- [ ] **Step 3.3: Add `resolveArtifactsPerSegment` + `PerSegmentArtifact` to `HistoryArtifactMapper`**

In `app/src/main/java/com/aritr/rova/ui/screens/HistoryArtifactMapper.kt`, add this function and data class inside the `HistoryArtifactMapper` object (after `resolveArtifactsPerSide` and its `PerSideArtifact`):

```kotlin
    /**
     * Milestone 2 — per-segment artifact fanout for `MULTI_SEGMENT_KEPT` sessions.
     *
     * Phase 4.3 introduced `Terminated.MULTI_SEGMENT_KEPT` (ordinal 4) for
     * recovery-merge sessions whose merge failed and whose individual segments
     * are kept on disk. Milestone 2 surfaces those segments as flat 1-per-segment
     * library rows (each independently playable + deletable).
     *
     * For sessions terminated as `MULTI_SEGMENT_KEPT`: emits N entries where
     * N = `manifest.segments.size`. Each entry carries the segment file path
     * (resolved against the session directory by the caller) and metadata
     * already persisted in the segment record.
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
                sizeBytes = segment.size,
                bitrate = segment.bitrate,
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
        val bitrate: Int,
    )
```

- [ ] **Step 3.4: Run test to verify it passes**

Run:
```
.\gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.screens.HistoryArtifactMapperSegmentRowsTest"
```
Expected: BUILD SUCCESSFUL, 4 tests, 0 failures.

### 3B — `HistoryViewModel` resolution pipeline branch

- [ ] **Step 3.5: Add `segmentIndex` field to `VideoItem`**

In `app/src/main/java/com/aritr/rova/ui/screens/HistoryViewModel.kt`, locate the `VideoItem` data class (lines 30-74). Add a new field with default `null` (back-compat):

```kotlin
    /**
     * Milestone 2 — non-null for rows derived from a `MULTI_SEGMENT_KEPT`
     * session's per-segment fanout. Identifies which segment of the session
     * this row represents. Null for all other rows (single-mode, P+L, legacy).
     *
     * Delete handler uses this to remove the per-segment file
     * (`sessionDir/segment_$segmentIndex.mp4`) and update the manifest's
     * segments list.
     */
    val segmentIndex: Int? = null,
```

Insert this AFTER the existing `side: VideoSide? = null` field and BEFORE the closing `)` of the data class. Trailing-comma + default-arg keeps every existing call site compiling unchanged.

- [ ] **Step 3.6: Add `ResolvedRecording.segmentIndex` and the MULTI_SEGMENT_KEPT branch in resolution**

In `HistoryViewModel.kt`, locate the `ResolvedRecording` private data class (around line 275). Add `segmentIndex: Int? = null` field with default null (same pattern as `VideoItem`).

Then locate the place where manifests are turned into `ResolvedRecording`s. The existing pipeline likely calls `HistoryArtifactMapper.finalizedManifests` then either `resolveArtifactFile` (single-mode) or `resolveArtifactsPerSide` (P+L). Find that branching point. (PowerShell aid: `Select-String -Path app/src/main/java/com/aritr/rova/ui/screens/HistoryViewModel.kt -Pattern "resolveArtifact"` to find call sites.)

Before the existing single-mode / P+L branch, add a MULTI_SEGMENT_KEPT branch:

```kotlin
                // Milestone 2 — MULTI_SEGMENT_KEPT fanout. Each kept segment
                // becomes a standalone library row. Composition with the
                // existing single-mode / P+L branches: the per-segment helper
                // returns emptyList() for non-MULTI_SEGMENT_KEPT terminals,
                // so the existing single/P+L paths are unaffected.
                val perSegment = HistoryArtifactMapper.resolveArtifactsPerSegment(manifest)
                if (perSegment.isNotEmpty()) {
                    perSegment.forEach { seg ->
                        val sessionDir = sessionStore.sessionDir(seg.sessionId)
                        val file = java.io.File(sessionDir, seg.filename)
                        if (file.exists()) {
                            resolved += ResolvedRecording(
                                file = file,
                                shareUri = null,                  // segments are app-private; no MediaStore URI
                                sessionId = seg.sessionId,
                                side = null,
                                segmentIndex = seg.segmentIndex,
                            )
                        }
                    }
                    continue   // skip the existing single-mode / P+L branches for this manifest
                }
```

Replace `continue` if you're inside a `for` loop or `forEach { ... }`; use `return@forEach` if it's a lambda. Pick the form matching the surrounding control flow.

Then in `buildItem`:

```kotlin
    private fun buildItem(rec: ResolvedRecording): VideoItem {
        val cached = metadataCache[rec.file.absolutePath]
        return VideoItem(
            file = rec.file,
            thumbnail = cached?.first,
            resolution = cached?.second ?: VideoMetadataUtils.UNKNOWN_RESOLUTION,
            shareUri = rec.shareUri,
            sessionId = rec.sessionId,
            side = rec.side,
            segmentIndex = rec.segmentIndex,
        )
    }
```

- [ ] **Step 3.7: Compile to verify VM + mapper changes integrate**

Run:
```
.\gradlew.bat :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL.

If compile fails:
- `Unresolved reference: Terminated` in `HistoryArtifactMapper.kt` — add `import com.aritr.rova.data.Terminated`.
- `Unresolved reference: sessionStore` in `HistoryViewModel.kt` — the resolution pipeline already has a `sessionStore` reference somewhere (used by `HistoryDeleter` per the file's imports); use the same accessor pattern (`(app as? RovaApp)?.let { it.sessionStore }`).

- [ ] **Step 3.8: Run full test suite**

Run:
```
.\gradlew.bat :app:testDebugUnitTest
```
Expected: BUILD SUCCESSFUL, ≥1231 tests (1227 + 4), 0 failed / 0 ignored / 0 skipped.

- [ ] **Step 3.9: Run lint**

Run:
```
.\gradlew.bat :app:lintDebug
```
Expected: BUILD SUCCESSFUL, `0 errors, 51 warnings, 1 hint` zero-delta.

- [ ] **Step 3.10: Build debug APK**

Run:
```
.\gradlew.bat :app:assembleDebug
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3.11: Commit**

```
git add app/src/main/java/com/aritr/rova/ui/screens/HistoryArtifactMapper.kt app/src/main/java/com/aritr/rova/ui/screens/HistoryViewModel.kt app/src/test/java/com/aritr/rova/ui/screens/HistoryArtifactMapperSegmentRowsTest.kt
git commit -m "feat(history): flat 1-per-segment library rows for MULTI_SEGMENT_KEPT (Milestone 2 Task 3)

HistoryArtifactMapper.resolveArtifactsPerSegment(manifest) mirrors the
existing resolveArtifactsPerSide P+L fanout pattern. For MULTI_SEGMENT_KEPT
sessions: emits N PerSegmentArtifact entries (1 per kept segment). For all
other terminals: emits emptyList — existing single-mode / P+L branches
unchanged.

HistoryViewModel resolution pipeline gains a MULTI_SEGMENT_KEPT branch
that constructs N ResolvedRecording entries before falling through to
the existing branches. VideoItem gains an optional segmentIndex: Int?
field (default null) for delete-handler disambiguation.

Hard invariants preserved: Terminated enum unchanged; existing
single-mode + P+L resolution paths byte-identical; HistoryDeleter
contract intact.

Full suite: 1231 tests / 0 failures. Lint zero-delta.

Spec: docs/superpowers/specs/2026-05-26-merge-reliability-bundle-design.md
ADR: docs/adr/0018-recovery-merge-retry-classifier-preflight.md

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

Verify: `git log --oneline -4` shows Task 1 / Task 2 / Task 3 commits on top of `f3a3e6d`.

---

## Task 4: Manual smoke + acceptance sign-off (owner-driven)

**Owner-driven.** No code changes. Acceptance gate before declaring Milestone 2 done and opening a PR.

Per spec §7.5 manual smoke checklist:

- [ ] **Step 4.1: Confirm three Milestone 2 commits land on local master**

Run: `git log --oneline -4`
Expected:
- `<sha3>` `feat(history): flat 1-per-segment library rows for MULTI_SEGMENT_KEPT (Milestone 2 Task 3)`
- `<sha2>` `feat(recovery): retry loop + preflight gate + notification progress (Milestone 2 Task 2)`
- `<sha1>` `feat(recovery): MergeFailureClass + MergeRetryPolicy + StoragePreflight (Milestone 2 Task 1)`
- `f3a3e6d docs(spec+adr): Milestone 2 merge reliability bundle + ADR-0018`

- [ ] **Step 4.2: Install debug APK on Samsung SM-A176B (primary QA device)**

Run:
```
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Expected: `Success`.

- [ ] **Step 4.3: Smoke scenario 1 — Normal recovery merge progress notification**

Trigger a normal recovery merge (via prior session that left segments + non-terminal manifest). Observe FGS notification:

- Title: "Merging recovered clips"
- Body: "Merging X%" where X increases
- Progress bar: 0–100, determinate
- Updates smoothly (no jank, no 60Hz spam)

Photo-capture mid-merge.

- [ ] **Step 4.4: Smoke scenario 2 — InsufficientStorage flow + poll-resume**

Pre-condition: fill device storage to within ~30 MiB of full (use a fixture clip or `dd`-equivalent fill).

Trigger recovery merge. Observe:

- FGS notification updates to "Waiting for free space" body (or equivalent — current Step 2.8 implementation shows progress %; if not surfacing a distinct mode, owner notes the gap as a follow-up).
- Free ~100 MiB.
- Within 30s, merge resumes from `attempt = 1`; notification returns to "Merging X%".
- Merge completes; library shows merged artifact.

- [ ] **Step 4.5: Smoke scenario 3 — Preflight failure → CANT_MERGE**

Pre-condition: device storage starved (< ~50 MiB free) before triggering merge.

Trigger recovery merge. Observe:

- No FGS notification appears.
- `CANT_MERGE` warning surfaces (top-banner per Phase 4.3 wiring, sheet content per C2.4).
- Manifest stays non-terminal (verify via app library list — session still listed as "Recoverable" or absence-from-library, not as `MULTI_SEGMENT_KEPT`).

- [ ] **Step 4.6: Smoke scenario 4 — `MULTI_SEGMENT_KEPT` library rows**

Force a `MULTI_SEGMENT_KEPT` outcome (e.g. corrupt manifest mid-merge via `adb shell` write to manifest JSON; OR rely on a fixture pre-staged in test storage). Open Library.

Observe:

- Session that previously appeared as 1 row now appears as N rows (one per kept segment).
- Each row is independently tappable → plays the segment in the in-app player or external viewer.
- Each row is independently deletable → swipe-to-delete (or long-press delete, depending on UI) removes that one segment.

Photo-capture the library list pre- and post-delete.

- [ ] **Step 4.7: Smoke scenario 5 — 3-retry transient sequence (optional, simulator-friendly)**

If a debug build hook exists to inject a transient `ExportResult.MuxFailed` sequence: trigger via that hook and verify the notification shows progress percentage smoothly across attempts.

If no debug hook: skip; this scenario is verified by the integration test `RecoveryMergerRetryTest.three_transient_failures_exhausts_to_mux_failed`.

- [ ] **Step 4.8: Acceptance checklist (spec §10)**

Confirm each:

- [ ] `MergeFailureClass.classifyMergeFailure` 9-arm contract (verified Step 1.4).
- [ ] `MergeRetryPolicy.backoffMillisFor` 1s/4s/16s (verified Step 1.8).
- [ ] `StoragePreflight.hasHeadroom` boundary correct (verified Step 1.12).
- [ ] `RecoveryMerger` retry loop 6 scenarios pass (verified Step 2.5).
- [ ] `HistoryArtifactMapper.resolveArtifactsPerSegment` 4 cases (verified Step 3.4).
- [ ] FGS notification shows progress + throttling (verified Step 4.3).
- [ ] Pre-flight failure raises `CANT_MERGE` before any FGS start (verified Step 4.5).
- [ ] Manual smoke scenarios 1-4 pass; scenario 5 optional (verified Steps 4.3-4.7).
- [ ] Full test suite ≥1231, 0 failed / 0 ignored / 0 skipped (verified Step 3.8).
- [ ] Lint zero-delta vs `b3fad71` baseline (verified Step 3.9).
- [ ] `gradlew assembleDebug` succeeds (verified Step 3.10).
- [ ] ADR-0018 committed alongside spec (verified at baseline `f3a3e6d`).

- [ ] **Step 4.9: Owner sign-off + push + PR**

Surface to owner: "Milestone 2 acceptance checklist complete. Photo-captures attached. Ready for PR?"

Owner explicit GO required per standing constraint #11 before `git push -u origin <branch>` + `gh pr create`. Suggested branch name: `milestone-2-merge-reliability-bundle`.

---

## Spec coverage check

Self-review against `docs/superpowers/specs/2026-05-26-merge-reliability-bundle-design.md`:

| Spec section | Covered by |
|---|---|
| §1 Context | N/A (background) |
| §2 Goal | Tasks 1-3 |
| §3 Out of scope | Honoured — no new ADR, no "Continue without saving", no Mode picker, no thermal hysteresis, etc. |
| §4 Hard invariants | Tasks 2 + 3 (test suite + targeted assertions) |
| §5 Design decisions (5) | All implemented; #1 Task 1A, #2 Task 1B + 2A, #3 Task 1C + 2B, #4 Task 3, #5 Task 2B |
| §6 Architecture | Task 1 (pure helpers) + Task 2 (merge surface) + Task 3 (library row) |
| §7 Testing strategy | Task 1 + Task 2 (RecoveryMergerRetryTest) + Task 3 (mapper test) |
| §8 Performance plan | Task 2 throttle (Step 2.8) + Task 3 list expansion (verified via test data sizes) |
| §9 Risks (8) | All addressed: Risk 1 (no thread block — suspending delay only); Risk 2 (final-tick exemption in Step 2.8); Risk 3 (10-min cap in Step 2.4); Risk 4 (delete handler covered via existing infrastructure); Risk 5 (poll callback idempotent); Risk 6 (value-class abandoned in favor of plain Float fraction — simpler); Risk 7 (no forbidden-pair write); Risk 8 (verified via grep — only RecoveryMerger consumes ExportResult). |
| §10 Acceptance (11) | Task 4 §8 checklist |
| §11 ADR implication | ADR-0018 already committed at baseline `f3a3e6d` |

**Note on §6.1 `MergeProgress` value class:** the spec offered a value-class option. This plan uses a plain `Float` fraction passed through the existing `onProgress` callback — simpler and avoids the value-class shift/packing complexity. The notification updater computes `percent = (fraction * 100f).toInt()`. Equivalent behaviour; simpler code.

**Note on §3 Library row delete cascade:** the plan defers the "last-segment delete cascades to manifest deletion" detail to the existing `HistoryDeleter` infrastructure. The `HistoryDeleter` already handles `discardSession(sessionId)` on file delete; that path remains the same. If a per-segment delete should NOT cascade until N=0 segments remain, that's a future refinement — out of scope for Milestone 2.

---

## Plan self-review

**1. Spec coverage:** Above table. All 11 spec sections mapped. Two implementation choices explicitly recorded above.

**2. Placeholder scan:**
- No "TBD", "TODO", "implement later", "fill in details" — clean.
- No "Add appropriate error handling" — defensive cases enumerated (Step 1.9 zero/huge inputs; Step 2.4 poll-cap loop).
- All code blocks complete (no `...` ellipsis).
- All commands have exact expected output.

**3. Type consistency:**
- `MergeFailureClass.Transient(cause: ExportResult)` — same shape across Step 1.3 declaration, Step 1.1 tests, Step 2.4 consumer.
- `MergeRetryPolicy.backoffMillisFor(attempt: Int): Long` — same signature across Step 1.7 + Step 1.5 + Step 2.4.
- `StoragePreflight.hasHeadroom(availableBytes: Long, accumulatedSessionBytes: Long): Boolean` — same across Step 1.11 + Step 1.9 + Step 2.6.
- `PerSegmentArtifact(sessionId, segmentIndex, filename, durationMs, sizeBytes, bitrate)` — same across Step 3.3 declaration, Step 3.1 tests, Step 3.6 consumer.
- `VideoItem.segmentIndex: Int? = null` — same across Step 3.5 + Step 3.6 + Task 4 smoke assertions.
- `RecoveryMerger` constructor params — order consistent across Step 2.4 declaration, Step 2.6 production wiring, Step 2.1 test wiring. Production omits `backoffMillisOverride`; test passes `{ 0L }`.

No inconsistencies found.

---

## Execution handoff

Plan complete and saved to [docs/superpowers/plans/2026-05-26-merge-reliability-bundle.md](2026-05-26-merge-reliability-bundle.md).

**Owner-approved execution mode:** Subagent-Driven (per pre-flight directive).

**Required sub-skill on execute:** `superpowers:subagent-driven-development` — fresh implementer subagent per task + two-stage review (spec-compliance + code-quality).

**Workflow overlay:** `/karpathy-guidelines` during dev.

**Standing constraints:**
- Constraint #3: gradle invocations route through subagents only (main controller blocked from long `.\gradlew.bat` calls).
- Constraint #9: review-gate cycle, strict NO-GO / GO per task; do not start next task without explicit GO.
- Constraint #11: push + PR creation require explicit owner consent per slice. Per-task commits in Tasks 1-3 fire under "execute the plan" consent; Task 4 §9 owner gate handles push + PR.

**Estimated total time:** Task 1 ~2 hours (six TDD cycles for three helpers); Task 2 ~3-4 hours (heaviest task, retry loop + Service-side wiring); Task 3 ~1.5-2 hours; Task 4 ~30 minutes hardware. Sub-day milestone if attempted in one session; more realistically spread across two sessions with owner review between Tasks 2 and 3.
