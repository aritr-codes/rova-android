# Deletion Transactionality (ADR-0036) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Library/retention deletion satisfy ADR-0036: a session's manifest is discarded last, exactly once, only when zero surviving artifacts of that session remain — fixing Defect A (DualShot partial-batch orphan, BACKLOG P2) and Defect B (`MULTI_SEGMENT_KEPT` sibling collateral loss) at their shared transaction-boundary root cause.

**Architecture:** A new pure decision procedure `SessionDiscardPlanner` (no filesystem / Android / SessionStore / coroutine dependency) maps batch outcomes + current session membership → sessionIds eligible for discard. `HistoryDeleter` is reshaped from a per-item delete-then-discard orchestrator into a batch orchestrator: delete all artifacts first, then discard only planner-eligible sessions, once each. Both production entry points — `deleteItemsKeyed` (manual delete) and `applyRetentionCleanupIfEnabled` (keep-latest-N retention) — route through it. `RecordingRetentionCleaner` shrinks to a pure surplus selector.

**Tech Stack:** Kotlin, JVM-only JUnit4 unit tests (`isReturnDefaultValues = true`), Gradle 9.4.1 wrapper on Windows PowerShell.

**Authority:** `docs/adr/0036-deletion-transactionality.md` (Accepted 2026-07-06). Invariants I1–I5 are the contract; the planner algorithm is an implementation detail (ADR §Transaction structure).

## Global Constraints

- JVM unit tests only — no Robolectric, no instrumented tests (CLAUDE.md test policy).
- Backtick test names must not contain `.` (Kotlin gotcha, memory 2026-07-06).
- `./gradlew :app:lintDebug` is RED on a pre-existing `VaultAndroidOps:267 NewApi` — verify gates via `:app:assembleDebug` (fires all 48 `check*` on preBuild) + `:app:testDebugUnitTest`.
- NO UI changes, NO Library redesign, NO metadata redesign, NO unrelated refactors (owner scope directive).
- `deleteItemsKeyed` / `deleteItems` public signatures must NOT change (LibraryScreen deferred-delete calls them positionally).
- Retention `Result` semantics preserved: `deleted`/`failed` count artifacts; NoOp when nothing to do; notices logic unchanged.
- KDoc invariant comments cite the review/analysis that caught the bug (house convention `memory/feedback_invariant_kdoc_style.md`): Defect A = codex PR-B last-pass 2026-07-03; Defect B = 2026-07-06 branch analysis.
- Never write a bare `/*` or `*/` in KDoc prose (self-nesting comment gotcha).
- Commits end with `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.
- codex MCP review: owner-waived for this branch (config error).

## File Structure

| File | Action | Responsibility |
|---|---|---|
| `docs/adr/0036-deletion-transactionality.md` | Commit (already written) | Transactional guarantees I1–I5 |
| `app/src/main/java/com/aritr/rova/ui/screens/SessionDiscardPlanner.kt` | Create | Pure discard-eligibility decision procedure |
| `app/src/test/java/com/aritr/rova/ui/screens/SessionDiscardPlannerTest.kt` | Create | Planner contract tests |
| `app/src/main/java/com/aritr/rova/ui/screens/HistoryDeleter.kt` | Rewrite | Batch orchestrator: artifacts → plan → discard-once |
| `app/src/test/java/com/aritr/rova/ui/screens/HistoryDeleterTest.kt` | Rewrite | Batch contract incl. Defect A/B regressions |
| `app/src/main/java/com/aritr/rova/ui/screens/RecordingRetentionCleaner.kt` | Rewrite | Pure surplus selector (`surplus()` + `Result`) |
| `app/src/test/java/com/aritr/rova/ui/screens/RecordingRetentionCleanerTest.kt` | Rewrite | Selector tests (same fixtures/policy) |
| `app/src/main/java/com/aritr/rova/ui/screens/HistoryViewModel.kt` | Modify | Rewire `deleteItemsKeyed` (~L1129) + `applyRetentionCleanupIfEnabled` (~L730); fix `VideoItem.segmentIndex` KDoc (~L118) |
| `docs/BACKLOG.md` | Modify | Close the P2 orphan entry |
| `CLAUDE.md` | Modify | ADR count 35→36 + tail status sentence |

Dependency order: Task 1 (planner) → Task 2 (deleter) → Task 3 (cleaner) → Task 4 (VM rewire) → Task 5 (docs) → Task 6 (device) → Task 7 (PR).

---

### Task 1: ADR commit + `SessionDiscardPlanner` (pure decision procedure)

**Files:**
- Commit: `docs/adr/0036-deletion-transactionality.md` (exists on branch, uncommitted)
- Create: `app/src/main/java/com/aritr/rova/ui/screens/SessionDiscardPlanner.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/screens/SessionDiscardPlannerTest.kt`

**Interfaces:**
- Consumes: nothing (pure, primitives only — deliberately NOT `VideoItem`).
- Produces (Task 2 relies on these exact signatures):
  - `internal object SessionDiscardPlanner`
  - `data class Outcome(val stableKey: String, val sessionId: String?, val deleted: Boolean)` (nested in the object)
  - `fun plan(outcomes: List<Outcome>, listedKeysBySession: Map<String, Set<String>>): Set<String>`

- [ ] **Step 1: Commit the ADR**

```powershell
git add docs/adr/0036-deletion-transactionality.md
git commit -m @'
docs(adr): ADR-0036 deletion transactionality — manifest-discard-last

Codifies the transactional guarantee for recording deletion (invariants
I1-I5): artifacts first, manifest-commit last, commit only when zero
surviving artifacts of the session remain; every partial state visible
and retry-convergent. Owner-approved 2026-07-06; gate #49 deferred.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
'@
```

- [ ] **Step 2: Write the failing test**

Create `app/src/test/java/com/aritr/rova/ui/screens/SessionDiscardPlannerTest.kt`:

```kotlin
package com.aritr.rova.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-0036 — decision-procedure contract for the deletion transaction's
 * commit step. The planner permits `discardSession(sid)` iff
 *   (a) every batch outcome of `sid` succeeded, AND
 *   (b) the batch covers every currently-listed stableKey of `sid`.
 * Any uncertainty resolves toward retention (I3 fail-toward-visibility).
 *
 * Pure: primitives in, sessionIds out. No filesystem, no Android types,
 * no SessionStore, no coroutines.
 */
class SessionDiscardPlannerTest {

    private fun o(key: String, sid: String?, ok: Boolean) =
        SessionDiscardPlanner.Outcome(stableKey = key, sessionId = sid, deleted = ok)

    // ---- Defect A (DualShot orphan) core cases ----

    @Test
    fun `dualshot both sides succeed and batch covers session - discard`() {
        val eligible = SessionDiscardPlanner.plan(
            outcomes = listOf(o("/p.mp4", "s1", true), o("/l.mp4", "s1", true)),
            listedKeysBySession = mapOf("s1" to setOf("/p.mp4", "/l.mp4")),
        )
        assertEquals(setOf("s1"), eligible)
    }

    @Test
    fun `dualshot one side fails - no discard (I1 no orphan)`() {
        val eligible = SessionDiscardPlanner.plan(
            outcomes = listOf(o("/p.mp4", "s1", true), o("/l.mp4", "s1", false)),
            listedKeysBySession = mapOf("s1" to setOf("/p.mp4", "/l.mp4")),
        )
        assertTrue(eligible.isEmpty())
    }

    @Test
    fun `lone-side batch that covers all listed items - discard`() {
        // A single surviving side (its sibling already gone out-of-band)
        // is the whole session now — deleting it discards the manifest.
        val eligible = SessionDiscardPlanner.plan(
            outcomes = listOf(o("/l.mp4", "s1", true)),
            listedKeysBySession = mapOf("s1" to setOf("/l.mp4")),
        )
        assertEquals(setOf("s1"), eligible)
    }

    @Test
    fun `batch misses a listed sibling - no discard (I2 no collateral)`() {
        // Defect B shape: 1 of 3 kept segments in the batch. Discarding
        // would deleteRecursively the sibling segment files.
        val eligible = SessionDiscardPlanner.plan(
            outcomes = listOf(o("/seg0.mp4", "s1", true)),
            listedKeysBySession = mapOf(
                "s1" to setOf("/seg0.mp4", "/seg1.mp4", "/seg2.mp4"),
            ),
        )
        assertTrue(eligible.isEmpty())
    }

    @Test
    fun `all listed segments in batch and all succeed - discard`() {
        val eligible = SessionDiscardPlanner.plan(
            outcomes = listOf(
                o("/seg0.mp4", "s1", true),
                o("/seg1.mp4", "s1", true),
                o("/seg2.mp4", "s1", true),
            ),
            listedKeysBySession = mapOf(
                "s1" to setOf("/seg0.mp4", "/seg1.mp4", "/seg2.mp4"),
            ),
        )
        assertEquals(setOf("s1"), eligible)
    }

    // ---- Boundary + composition ----

    @Test
    fun `legacy null sessionId outcomes are never eligible`() {
        val eligible = SessionDiscardPlanner.plan(
            outcomes = listOf(o("/legacy.mp4", null, true)),
            listedKeysBySession = emptyMap(),
        )
        assertTrue(eligible.isEmpty())
    }

    @Test
    fun `mixed multi-session batch - verdicts are independent`() {
        val eligible = SessionDiscardPlanner.plan(
            outcomes = listOf(
                o("/a-p.mp4", "sA", true), o("/a-l.mp4", "sA", true),   // full success
                o("/b-p.mp4", "sB", true), o("/b-l.mp4", "sB", false),  // partial fail
                o("/c-seg0.mp4", "sC", true),                            // subset of listed
                o("/legacy.mp4", null, true),
            ),
            listedKeysBySession = mapOf(
                "sA" to setOf("/a-p.mp4", "/a-l.mp4"),
                "sB" to setOf("/b-p.mp4", "/b-l.mp4"),
                "sC" to setOf("/c-seg0.mp4", "/c-seg1.mp4"),
                "sD" to setOf("/d.mp4"),  // listed but not in batch: never eligible
            ),
        )
        assertEquals(setOf("sA"), eligible)
    }

    @Test
    fun `session absent from snapshot - eligible iff all batch outcomes succeeded`() {
        // By construction batch items come from the same listing snapshot,
        // so an absent session means the listing (exists-filter) no longer
        // knows any artifact of it — nothing visible survives to orphan.
        val eligible = SessionDiscardPlanner.plan(
            outcomes = listOf(o("/x.mp4", "s1", true), o("/y.mp4", "s2", false)),
            listedKeysBySession = emptyMap(),
        )
        assertEquals(setOf("s1"), eligible)
    }

    @Test
    fun `empty batch - empty result`() {
        assertTrue(
            SessionDiscardPlanner.plan(emptyList(), emptyMap()).isEmpty()
        )
    }

    @Test
    fun `duplicate outcomes for the same key are tolerated`() {
        val eligible = SessionDiscardPlanner.plan(
            outcomes = listOf(
                o("/p.mp4", "s1", true), o("/p.mp4", "s1", true),
                o("/l.mp4", "s1", true),
            ),
            listedKeysBySession = mapOf("s1" to setOf("/p.mp4", "/l.mp4")),
        )
        assertEquals(setOf("s1"), eligible)
    }
}
```

- [ ] **Step 3: Run to verify it fails**

```powershell
./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.screens.SessionDiscardPlannerTest"
```
Expected: compilation FAILURE — `Unresolved reference: SessionDiscardPlanner`.

- [ ] **Step 4: Minimal implementation**

Create `app/src/main/java/com/aritr/rova/ui/screens/SessionDiscardPlanner.kt`:

```kotlin
package com.aritr.rova.ui.screens

/**
 * ADR-0036 — the decision procedure for the deletion transaction's commit
 * step. Given the per-artifact outcomes of a delete batch and the current
 * session membership of the Library listing, decides which sessionIds are
 * permitted to have their manifest + session directory discarded.
 *
 * A `sessionId` is eligible iff:
 *  1. every batch outcome belonging to it succeeded (I1 — no orphan:
 *     a surviving public artifact must stay reachable, and the manifest
 *     is its only path to visibility), AND
 *  2. the batch covers every currently-listed stableKey of the session
 *     (I2 — no collateral: for MULTI_SEGMENT_KEPT sessions the sibling
 *     segment files live INSIDE the session directory, so a premature
 *     discard destroys recordings the user chose to keep — found in the
 *     2026-07-06 branch analysis; the DualShot variant was flagged by
 *     the codex PR-B last-pass review, 2026-07-03).
 *
 * A session absent from [plan]'s `listedKeysBySession` has no listed
 * artifacts left (batch items are resolved from the same listing
 * snapshot), so rule 2 is vacuously satisfied. Any other snapshot
 * staleness fails toward NOT discarding — a ghost manifest, the
 * acceptable residue under I3 (fail toward visibility).
 *
 * Pure by owner mandate: primitives in, sessionIds out. No filesystem,
 * no Android dependency, no SessionStore, no coroutines. The planner is
 * NOT part of the transaction (ADR-0036 §Transaction structure) — it
 * only decides whether the commit is permitted.
 */
internal object SessionDiscardPlanner {

    /** One artifact-delete outcome from the batch. */
    data class Outcome(
        val stableKey: String,
        val sessionId: String?,
        val deleted: Boolean,
    )

    fun plan(
        outcomes: List<Outcome>,
        listedKeysBySession: Map<String, Set<String>>,
    ): Set<String> {
        val bySession = outcomes
            .filter { it.sessionId != null }
            .groupBy { it.sessionId!! }
        return bySession.filterTo(mutableMapOf()) { (sid, sessionOutcomes) ->
            val allSucceeded = sessionOutcomes.all { it.deleted }
            val batchKeys = sessionOutcomes.mapTo(mutableSetOf()) { it.stableKey }
            allSucceeded && batchKeys.containsAll(listedKeysBySession[sid].orEmpty())
        }.keys
    }
}
```

- [ ] **Step 5: Run to verify it passes**

```powershell
./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.screens.SessionDiscardPlannerTest"
```
Expected: BUILD SUCCESSFUL, 10 tests pass.

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/java/com/aritr/rova/ui/screens/SessionDiscardPlanner.kt app/src/test/java/com/aritr/rova/ui/screens/SessionDiscardPlannerTest.kt
git commit -m @'
feat(library): SessionDiscardPlanner — pure discard-eligibility decision (ADR-0036)

Permits manifest discard only when every batch outcome of the session
succeeded AND the batch covers all currently-listed artifacts of the
session. Uncertainty resolves toward retention (I3).

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
'@
```

---

### Task 2: `HistoryDeleter` → batch orchestrator

**Files:**
- Rewrite: `app/src/main/java/com/aritr/rova/ui/screens/HistoryDeleter.kt`
- Rewrite: `app/src/test/java/com/aritr/rova/ui/screens/HistoryDeleterTest.kt`

**Interfaces:**
- Consumes: `SessionDiscardPlanner.plan(outcomes, listedKeysBySession)` and `SessionDiscardPlanner.Outcome(stableKey, sessionId, deleted)` from Task 1; `VideoItem.stableKey: String`, `VideoItem.sessionId: String?` (existing).
- Produces (Task 4 relies on this exact signature):
  - `internal class HistoryDeleter(deleteArtifact: (VideoItem) -> Boolean, discardSession: (String) -> Unit, onDiscardError: (sessionId: String, t: Throwable) -> Unit = { _, _ -> })` — constructor unchanged.
  - `fun deleteAll(batch: Collection<VideoItem>, listedItems: Collection<VideoItem>): Set<String>` — returns stableKeys whose ARTIFACT delete failed. The per-item `delete(item)` method is REMOVED.

- [ ] **Step 1: Rewrite the test file (failing)**

Replace the full contents of `app/src/test/java/com/aritr/rova/ui/screens/HistoryDeleterTest.kt`:

```kotlin
package com.aritr.rova.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * JVM-only tests for the [HistoryDeleter] batch orchestration
 * (ADR-0036). Pins the two-step transaction — delete artifacts, then
 * commit (or retain) each session's manifest — without an
 * `AndroidViewModel`, a real `ContentResolver`, or a real
 * `SessionStore`.
 *
 * The order contract matters because the manifest is the only handle
 * the Library has on a session's artifacts: discarding it while any
 * artifact survives strands that artifact (Defect A, codex PR-B
 * last-pass 2026-07-03), and for MULTI_SEGMENT_KEPT sessions the
 * sibling files live inside the session directory, so a premature
 * discard destroys them (Defect B, 2026-07-06 branch analysis).
 */
class HistoryDeleterTest {

    private fun item(
        sessionId: String? = null,
        name: String = sessionId ?: "legacy",
    ): VideoItem = VideoItem(
        file = File("/tmp/rova/$name.mp4"),
        thumbnail = null,
        resolution = "FHD",
        shareUri = null,
        sessionId = sessionId,
    )

    // ---- Carried-over single-item contract (pre-ADR-0036 suite) ----

    @Test
    fun `single item covering its session - artifact success triggers one discard`() {
        val discardCalls = mutableListOf<String>()
        val deleter = HistoryDeleter(
            deleteArtifact = { true },
            discardSession = { sid -> discardCalls += sid },
        )
        val target = item(sessionId = "s-1")
        val failed = deleter.deleteAll(listOf(target), listedItems = listOf(target))
        assertTrue(failed.isEmpty())
        assertEquals(listOf("s-1"), discardCalls)
    }

    @Test
    fun `artifact delete failure does NOT trigger discardSession`() {
        val discardCalls = mutableListOf<String>()
        val deleter = HistoryDeleter(
            deleteArtifact = { false },
            discardSession = { sid -> discardCalls += sid },
        )
        val target = item(sessionId = "s-2")
        val failed = deleter.deleteAll(listOf(target), listedItems = listOf(target))
        assertEquals(setOf(target.stableKey), failed)
        assertTrue("discardSession must not run on artifact failure", discardCalls.isEmpty())
    }

    @Test
    fun `legacy item - no sessionId means no discardSession`() {
        val discardCalls = mutableListOf<String>()
        val deleter = HistoryDeleter(
            deleteArtifact = { true },
            discardSession = { sid -> discardCalls += sid },
        )
        val target = item(sessionId = null)
        val failed = deleter.deleteAll(listOf(target), listedItems = listOf(target))
        assertTrue(failed.isEmpty())
        assertTrue("legacy items must skip discardSession", discardCalls.isEmpty())
    }

    @Test
    fun `discardSession failure is swallowed and does NOT mark the batch failed`() {
        // The artifacts are gone from the user's gallery — the visible
        // outcome is success. A ghost manifest is the acceptable
        // residue (ADR-0036 I3); reporting it as a delete failure would
        // claim a visible file survived when none did.
        var loggedSid: String? = null
        var loggedError: Throwable? = null
        val deleter = HistoryDeleter(
            deleteArtifact = { true },
            discardSession = { error("disk gone") },
            onDiscardError = { sid, t ->
                loggedSid = sid
                loggedError = t
            },
        )
        val target = item(sessionId = "s-3")
        val failed = deleter.deleteAll(listOf(target), listedItems = listOf(target))
        assertTrue(failed.isEmpty())
        assertEquals("s-3", loggedSid)
        assertEquals("disk gone", loggedError?.message)
    }

    @Test
    fun `error sink stays silent when the discard step is never reached`() {
        var loggedSid: String? = null
        val deleter = HistoryDeleter(
            deleteArtifact = { false },
            discardSession = { error("should not be called") },
            onDiscardError = { sid, _ -> loggedSid = sid },
        )
        val target = item(sessionId = "s-4")
        deleter.deleteAll(listOf(target), listedItems = listOf(target))
        assertNull(loggedSid)
    }

    // ---- ADR-0036 batch transaction ----

    @Test
    fun `defect A regression - dualshot partial failure retains the manifest`() {
        // Portrait artifact deletes OK, landscape fails. Pre-ADR-0036
        // the portrait success discarded the shared manifest and the
        // surviving landscape file became an unreachable orphan in the
        // system gallery (codex PR-B last-pass, 2026-07-03).
        val discardCalls = mutableListOf<String>()
        val portrait = item(sessionId = "s-dual", name = "s-dual-p")
        val landscape = item(sessionId = "s-dual", name = "s-dual-l")
        val deleter = HistoryDeleter(
            deleteArtifact = { it.stableKey == portrait.stableKey },
            discardSession = { sid -> discardCalls += sid },
        )
        val failed = deleter.deleteAll(
            listOf(portrait, landscape),
            listedItems = listOf(portrait, landscape),
        )
        assertEquals(setOf(landscape.stableKey), failed)
        assertTrue("manifest must be retained (I1)", discardCalls.isEmpty())
    }

    @Test
    fun `dualshot full success - discard fires exactly once per session`() {
        val discardCalls = mutableListOf<String>()
        val portrait = item(sessionId = "s-dual", name = "s-dual-p")
        val landscape = item(sessionId = "s-dual", name = "s-dual-l")
        val deleter = HistoryDeleter(
            deleteArtifact = { true },
            discardSession = { sid -> discardCalls += sid },
        )
        val failed = deleter.deleteAll(
            listOf(portrait, landscape),
            listedItems = listOf(portrait, landscape),
        )
        assertTrue(failed.isEmpty())
        assertEquals(listOf("s-dual"), discardCalls)
    }

    @Test
    fun `defect B regression - segment subset never discards the session`() {
        // 1 of 3 kept segments in the batch. Pre-ADR-0036 the discard
        // ran deleteRecursively on the session dir and destroyed the
        // sibling segment files (2026-07-06 branch analysis).
        val discardCalls = mutableListOf<String>()
        val seg0 = item(sessionId = "s-multi", name = "s-multi-seg0")
        val seg1 = item(sessionId = "s-multi", name = "s-multi-seg1")
        val seg2 = item(sessionId = "s-multi", name = "s-multi-seg2")
        val deleter = HistoryDeleter(
            deleteArtifact = { true },
            discardSession = { sid -> discardCalls += sid },
        )
        val failed = deleter.deleteAll(
            listOf(seg0),
            listedItems = listOf(seg0, seg1, seg2),
        )
        assertTrue(failed.isEmpty())
        assertTrue("sibling segments survive → no discard (I2)", discardCalls.isEmpty())
    }

    @Test
    fun `discard executes only after ALL artifact deletes have run`() {
        // Pins the transaction shape itself: step 1 (artifacts) fully
        // precedes step 2 (commit). Pre-ADR-0036 the discard fired
        // inside the per-item loop.
        val events = mutableListOf<String>()
        val portrait = item(sessionId = "s-dual", name = "s-dual-p")
        val landscape = item(sessionId = "s-dual", name = "s-dual-l")
        val deleter = HistoryDeleter(
            deleteArtifact = { events += "artifact:${it.stableKey}"; true },
            discardSession = { sid -> events += "discard:$sid" },
        )
        deleter.deleteAll(
            listOf(portrait, landscape),
            listedItems = listOf(portrait, landscape),
        )
        assertEquals(
            listOf(
                "artifact:${portrait.stableKey}",
                "artifact:${landscape.stableKey}",
                "discard:s-dual",
            ),
            events,
        )
    }

    @Test
    fun `one artifact failure does not abort the rest of the batch`() {
        // Best-effort artifact pass (retention relies on this): a
        // failure is recorded and the remaining items are still
        // attempted.
        val attempted = mutableListOf<String>()
        val a = item(sessionId = "sA")
        val b = item(sessionId = "sB")
        val c = item(sessionId = "sC")
        val deleter = HistoryDeleter(
            deleteArtifact = { attempted += it.stableKey; it.sessionId != "sB" },
            discardSession = { },
        )
        val failed = deleter.deleteAll(
            listOf(a, b, c),
            listedItems = listOf(a, b, c),
        )
        assertEquals(listOf(a.stableKey, b.stableKey, c.stableKey), attempted)
        assertEquals(setOf(b.stableKey), failed)
    }

    @Test
    fun `retention boundary - one side in batch while sibling stays listed keeps the manifest`() {
        // The retention keep-window slices per item, so the surplus can
        // contain one DualShot side while its sibling is KEPT. The kept
        // side's visibility must survive.
        val discardCalls = mutableListOf<String>()
        val surplusSide = item(sessionId = "s-dual", name = "s-dual-l")
        val keptSide = item(sessionId = "s-dual", name = "s-dual-p")
        val deleter = HistoryDeleter(
            deleteArtifact = { true },
            discardSession = { sid -> discardCalls += sid },
        )
        val failed = deleter.deleteAll(
            listOf(surplusSide),
            listedItems = listOf(surplusSide, keptSide),
        )
        assertTrue(failed.isEmpty())
        assertTrue("kept sibling still listed → no discard", discardCalls.isEmpty())
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```powershell
./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.screens.HistoryDeleterTest"
```
Expected: compilation FAILURE — `Unresolved reference: deleteAll` (the old class has only `delete`). Note: `HistoryViewModel` still calls `deleter.delete(item)` at this point, so full compilation of `main` also breaks if attempted — this task's Step 3 keeps a deprecated bridge OFF by design; Step 3 and Task 4 land in one compilable sequence per commit below. To keep every commit green, Step 3 rewrites `HistoryDeleter` AND the two call sites in the same commit is NOT done here — instead Step 3 includes a temporary private bridge used by nothing, and the call-site rewire happens in Task 4. To avoid a broken interim `main` compile, Step 3's rewrite RETAINS a `delete(item)` method delegating to `deleteAll` until Task 4 removes the callers, then Task 4 deletes it.

- [ ] **Step 3: Rewrite the implementation (with temporary `delete` bridge)**

Replace the full contents of `app/src/main/java/com/aritr/rova/ui/screens/HistoryDeleter.kt`:

```kotlin
package com.aritr.rova.ui.screens

/**
 * Batch orchestrator for the Library delete pipeline (ADR-0036).
 * Pluggable seams so the transaction can be JVM-unit-tested without an
 * `AndroidViewModel`, a real `ContentResolver`, or a real
 * `SessionStore`.
 *
 * Transaction (ADR-0036 §Transaction structure):
 *   1. Delete every artifact in the batch via [deleteArtifact],
 *      recording per-item success. A failure never aborts the pass —
 *      cleanup stays best-effort.
 *   2. Commit manifest deletion — or retain it: [discardSession] runs
 *      once per session that [SessionDiscardPlanner] marks eligible
 *      (every batch outcome of the session succeeded AND the batch
 *      covers all of the session's listed artifacts).
 *
 * Never call [discardSession] per item: the artifact→session relation
 * is N:1 (DualShot sides; MULTI_SEGMENT_KEPT segments whose files live
 * INSIDE the session directory). A per-item discard orphaned the
 * surviving DualShot side on partial failure (codex PR-B last-pass,
 * 2026-07-03) and destroyed sibling kept segments outright (2026-07-06
 * branch analysis).
 *
 * If [discardSession] throws, the exception is logged via
 * [onDiscardError] and swallowed — every artifact of that session is
 * already gone, so the visible operation succeeded; the residue is a
 * ghost manifest, the acceptable failure under ADR-0036 I3. It never
 * marks the batch failed.
 *
 * The helper is `internal` so tests in the same package can access it
 * without exposing it to consumer modules.
 */
internal class HistoryDeleter(
    private val deleteArtifact: (VideoItem) -> Boolean,
    private val discardSession: (String) -> Unit,
    private val onDiscardError: (sessionId: String, t: Throwable) -> Unit = { _, _ -> }
) {
    /**
     * Runs the two-step deletion transaction over [batch].
     * [listedItems] is the current Library listing snapshot the batch
     * was resolved from; it supplies the session-membership input to
     * the eligibility decision. Returns the [VideoItem.stableKey]s
     * whose ARTIFACT delete failed (discard outcomes never affect it).
     */
    fun deleteAll(
        batch: Collection<VideoItem>,
        listedItems: Collection<VideoItem>,
    ): Set<String> {
        val outcomes = batch.map { item ->
            SessionDiscardPlanner.Outcome(
                stableKey = item.stableKey,
                sessionId = item.sessionId,
                deleted = deleteArtifact(item),
            )
        }
        val listedKeysBySession = listedItems
            .filter { it.sessionId != null }
            .groupBy({ it.sessionId!! }, { it.stableKey })
            .mapValues { (_, keys) -> keys.toSet() }
        val eligible = SessionDiscardPlanner.plan(outcomes, listedKeysBySession)
        for (sid in eligible) {
            try {
                discardSession(sid)
            } catch (t: Throwable) {
                onDiscardError(sid, t)
            }
        }
        return outcomes.filterNot { it.deleted }.mapTo(mutableSetOf()) { it.stableKey }
    }

    /**
     * TEMPORARY bridge for the pre-ADR-0036 call sites; removed in the
     * same branch once HistoryViewModel routes through [deleteAll].
     * Treats the single item as covering its whole session — the OLD
     * (defective) semantics, kept only so intermediate commits compile.
     */
    @Deprecated("ADR-0036: route batches through deleteAll")
    fun delete(item: VideoItem): Boolean =
        deleteAll(listOf(item), listedItems = listOf(item)).isEmpty()
}
```

- [ ] **Step 4: Run to verify tests pass**

```powershell
./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.screens.HistoryDeleterTest" --tests "com.aritr.rova.ui.screens.SessionDiscardPlannerTest"
```
Expected: BUILD SUCCESSFUL, 11 + 10 tests pass. (Deprecation warnings on the two `HistoryViewModel` call sites are expected and temporary.)

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/aritr/rova/ui/screens/HistoryDeleter.kt app/src/test/java/com/aritr/rova/ui/screens/HistoryDeleterTest.kt
git commit -m @'
feat(library): HistoryDeleter batch orchestrator — artifacts first, discard once (ADR-0036)

deleteAll runs the two-step transaction: best-effort artifact pass,
then one discardSession per planner-eligible session. Defect A and
Defect B regression tests. Temporary delete(item) bridge keeps the
old call sites compiling until the pipeline rewire.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
'@
```

---

### Task 3: `RecordingRetentionCleaner` → pure surplus selector

**Files:**
- Rewrite: `app/src/main/java/com/aritr/rova/ui/screens/RecordingRetentionCleaner.kt`
- Rewrite: `app/src/test/java/com/aritr/rova/ui/screens/RecordingRetentionCleanerTest.kt`

**Interfaces:**
- Consumes: `VideoItem.sessionId` (existing).
- Produces (Task 4 relies on these exact signatures):
  - `internal object RecordingRetentionCleaner`
  - `fun surplus(enabled: Boolean, keepLatest: Int, items: List<VideoItem>): List<VideoItem>`
  - `data class Result(val deleted: Int, val failed: Int)` with `companion object { val NoOp = Result(0, 0) }` — unchanged shape (the `RetentionCleanupNotice` emit logic in the VM keeps using it).
  - The `clean(...)` method and the `deleteItem` constructor seam are REMOVED (deletion now happens in the shared `HistoryDeleter.deleteAll` pipeline — which is what this class's own KDoc always claimed it wanted: "the same audited code path").

- [ ] **Step 1: Rewrite the test file (failing)**

Replace the full contents of `app/src/test/java/com/aritr/rova/ui/screens/RecordingRetentionCleanerTest.kt`:

```kotlin
package com.aritr.rova.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * JVM-only tests for the retention SELECTION policy (ADR-0036 reshape:
 * the cleaner now only selects the surplus; deletion runs through the
 * shared HistoryDeleter batch transaction). Pins the contract the
 * History refresh path relies on:
 *
 *   * disabled / non-positive keepLatest → empty selection
 *   * keepLatest >= eligible.size → empty selection
 *   * newest-first input → the tail beyond the keep window is selected
 *   * legacy file-only entries (`sessionId == null`) are never selected
 */
class RecordingRetentionCleanerTest {

    private fun item(sessionId: String?, name: String = sessionId ?: "legacy") = VideoItem(
        file = File("/tmp/rova/$name.mp4"),
        thumbnail = null,
        resolution = "FHD",
        shareUri = null,
        sessionId = sessionId,
    )

    @Test
    fun `disabled - selects nothing regardless of count`() {
        val items = (1..20).map { item(sessionId = "s$it") }
        val surplus = RecordingRetentionCleaner.surplus(enabled = false, keepLatest = 5, items = items)
        assertTrue("no selection when disabled", surplus.isEmpty())
    }

    @Test
    fun `keep zero - treated as disabled to avoid wiping the library`() {
        val items = (1..3).map { item(sessionId = "s$it") }
        assertTrue(RecordingRetentionCleaner.surplus(enabled = true, keepLatest = 0, items = items).isEmpty())
    }

    @Test
    fun `keep negative - treated as disabled`() {
        val items = (1..3).map { item(sessionId = "s$it") }
        assertTrue(RecordingRetentionCleaner.surplus(enabled = true, keepLatest = -7, items = items).isEmpty())
    }

    @Test
    fun `enabled keep ten with twelve items - selects two oldest`() {
        // Items are passed in newest-first per the History refresh
        // contract; the selector trims the tail.
        val items = (1..12).map { item(sessionId = "s%02d".format(it)) }
        val surplus = RecordingRetentionCleaner.surplus(enabled = true, keepLatest = 10, items = items)
        assertEquals(listOf("s11", "s12"), surplus.map { it.sessionId })
    }

    @Test
    fun `keep count respects newest-first ordering - first ten survive`() {
        val items = (1..15).map { item(sessionId = "s%02d".format(it)) }
        val surplus = RecordingRetentionCleaner.surplus(enabled = true, keepLatest = 10, items = items)
        val selected = surplus.map { it.sessionId }
        items.take(10).forEach { kept ->
            assertTrue("newest survivor ${kept.sessionId} must not be selected", kept.sessionId !in selected)
        }
        assertEquals(5, surplus.size)
    }

    @Test
    fun `library at exactly keepLatest selects nothing`() {
        val items = (1..10).map { item(sessionId = "s%02d".format(it)) }
        assertTrue(RecordingRetentionCleaner.surplus(enabled = true, keepLatest = 10, items = items).isEmpty())
    }

    @Test
    fun `legacy null-session entries are never selected`() {
        val items = (1..15).map { item(sessionId = null, name = "legacy_$it") }
        assertTrue(RecordingRetentionCleaner.surplus(enabled = true, keepLatest = 5, items = items).isEmpty())
    }

    @Test
    fun `mixed legacy and finalized - keep window counted on finalized only`() {
        // Library: 4 finalized + 3 legacy. keepLatest = 2 → surplus is
        // f3, f4. Legacy entries are skipped regardless of position.
        val items = listOf(
            item(sessionId = "f1"),
            item(sessionId = null, name = "legacy_a"),
            item(sessionId = "f2"),
            item(sessionId = null, name = "legacy_b"),
            item(sessionId = "f3"),
            item(sessionId = "f4"),
            item(sessionId = null, name = "legacy_c"),
        )
        val surplus = RecordingRetentionCleaner.surplus(enabled = true, keepLatest = 2, items = items)
        assertEquals(listOf("f3", "f4"), surplus.map { it.sessionId })
    }

    @Test
    fun `empty library selects nothing`() {
        assertTrue(RecordingRetentionCleaner.surplus(enabled = true, keepLatest = 10, items = emptyList()).isEmpty())
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```powershell
./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.screens.RecordingRetentionCleanerTest"
```
Expected: compilation FAILURE — `Unresolved reference: surplus`.

- [ ] **Step 3: Rewrite the implementation**

Replace the full contents of `app/src/main/java/com/aritr/rova/ui/screens/RecordingRetentionCleaner.kt`.
NOTE: `applyRetentionCleanupIfEnabled` in `HistoryViewModel` still calls the removed `clean(...)` at this point, so `main` will not compile until Task 4 Step 1 — this task and Task 4 Steps 1–3 land as ONE commit at Task 4 Step 5. Do NOT commit at the end of this task.

```kotlin
package com.aritr.rova.ui.screens

/**
 * Pure SELECTION policy for the "keep latest N finalized recordings"
 * retention rule wired in Settings (ADR-0036 reshape). This class no
 * longer deletes anything: it names the surplus, and the caller routes
 * it through the same [HistoryDeleter.deleteAll] batch transaction
 * that powers the manual Library delete — so the retention path gets
 * the manifest-discard-last guarantee (I1/I2) instead of the per-item
 * discard that could orphan a KEPT DualShot side whose sibling fell
 * outside the keep window (2026-07-06 branch analysis).
 *
 * Inputs:
 *  * [enabled] — settings toggle. When `false` the selector returns
 *    empty without touching anything.
 *  * [keepLatest] — number of finalized recordings to keep. Values
 *    `<= 0` are treated as "off" too, so a misconfigured persistence
 *    read can never delete the user's entire library.
 *  * [items] — full History list, **already sorted newest-first**.
 *    Only entries with a non-null [VideoItem.sessionId] are eligible;
 *    legacy file-only entries are skipped (their discard path is not
 *    plumbed).
 *
 * The helper is `internal` so tests in the same package can access it
 * without exposing it to consumer modules.
 */
internal object RecordingRetentionCleaner {

    /** Batch outcome summary surfaced via RetentionCleanupNotice. */
    data class Result(val deleted: Int, val failed: Int) {
        companion object {
            val NoOp = Result(deleted = 0, failed = 0)
        }
    }

    /** Names the surplus tail beyond the keep window; deletes nothing. */
    fun surplus(
        enabled: Boolean,
        keepLatest: Int,
        items: List<VideoItem>,
    ): List<VideoItem> {
        if (!enabled) return emptyList()
        if (keepLatest <= 0) return emptyList()
        val finalized = items.filter { it.sessionId != null }
        if (finalized.size <= keepLatest) return emptyList()
        return finalized.drop(keepLatest)
    }
}
```

- [ ] **Step 4: Verify the selector tests pass in isolation**

The `main` source set does not compile yet (the VM still references `clean`). Proceed directly to Task 4 — its Step 4 runs these tests together with the rewired pipeline. Do not commit here.

---

### Task 4: Pipeline rewire in `HistoryViewModel` + KDoc corrections

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/HistoryViewModel.kt` — `deleteItemsKeyed` (~L1123–1159), `applyRetentionCleanupIfEnabled` (~L714–772 incl. KDoc), `VideoItem.segmentIndex` KDoc (~L118–127)
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/HistoryDeleter.kt` — remove the temporary `delete` bridge

**Interfaces:**
- Consumes: `HistoryDeleter.deleteAll(batch, listedItems): Set<String>` (Task 2); `RecordingRetentionCleaner.surplus(enabled, keepLatest, items): List<VideoItem>` + `Result` (Task 3).
- Produces: unchanged public surface — `deleteItemsKeyed(items: Collection<VideoItem>): Set<String>`, `deleteItems(items: Collection<VideoItem>): DeleteResult`, `retentionNotices` behavior.

- [ ] **Step 1: Rewire `deleteItemsKeyed`**

In `HistoryViewModel.kt`, replace the body after the `deleter` construction (keep the existing KDoc at ~L1123, the `withContext(Dispatchers.IO)`, the empty-batch early return, and the resolver/sessionStore/deleter setup EXACTLY as they are). Replace only this block:

```kotlin
            val failed = mutableSetOf<String>()
            items.forEach { item ->
                if (!deleter.delete(item)) failed += item.stableKey
            }
            refresh()
            failed
```

with:

```kotlin
            // ADR-0036 — two-step transaction: all artifact deletes run
            // first; discardSession fires once per session the planner
            // marks eligible. The listing snapshot supplies session
            // membership so a surviving sibling (other DualShot side,
            // other kept segment) retains the manifest.
            val failed = deleter.deleteAll(items, listedItems = _items.value)
            refresh()
            failed
```

- [ ] **Step 2: Rewire `applyRetentionCleanupIfEnabled`**

Replace the function (keep its KDoc position; update the KDoc sentence that says each surplus entry is delegated per-item). The full new function body:

```kotlin
    /**
     * Slice 13B — opt-in "keep latest N" retention cleanup, applied at
     * most once per refresh pass on the freshly loaded item list.
     * ADR-0036: the surplus is selected by the pure
     * [RecordingRetentionCleaner.surplus] and deleted through the same
     * [HistoryDeleter.deleteAll] batch transaction as the manual
     * Library delete, so a surplus side whose DualShot sibling is KEPT
     * can no longer discard the shared manifest (the kept side stays
     * visible). Failures are logged via [RovaLog]; they do not
     * propagate to the UI snackbar because retention cleanup is a
     * background-y maintenance step the user did not request
     * synchronously.
     */
    private fun applyRetentionCleanupIfEnabled(
        items: List<VideoItem>
    ): RecordingRetentionCleaner.Result {
        val app = getApplication<Application>()
        val settings = RovaSettings(app)
        if (!settings.autoDeleteEnabled) return RecordingRetentionCleaner.Result.NoOp
        val surplus = RecordingRetentionCleaner.surplus(
            enabled = true,
            keepLatest = settings.autoDeleteKeepLatest,
            items = items
        )
        if (surplus.isEmpty()) return RecordingRetentionCleaner.Result.NoOp
        val resolver = app.contentResolver
        val sessionStore = (app as? RovaApp)?.let {
            runCatching { it.sessionStore }.getOrNull()
        }
        val deleter = HistoryDeleter(
            deleteArtifact = { item -> deleteOne(resolver, item) },
            discardSession = { sid -> sessionStore?.discardSession(sid) },
            onDiscardError = { sid, t ->
                RovaLog.w(
                    "HistoryViewModel.retention: discardSession failed for $sid",
                    t
                )
            }
        )
        // `items` is the freshly loaded listing this pass — the most
        // current membership snapshot available.
        val failedKeys = deleter.deleteAll(surplus, listedItems = items)
        val result = RecordingRetentionCleaner.Result(
            deleted = surplus.size - failedKeys.size,
            failed = failedKeys.size
        )
        if (result.deleted > 0) {
            RovaLog.d {
                "HistoryViewModel.retention: deleted ${result.deleted} surplus" +
                    " recording(s); ${result.failed} failure(s)"
            }
        }
        // Slice 13B — surface a single concise snackbar when cleanup
        // did real work or failed. NoOp passes are silent so the
        // common case (refresh on a library already inside the
        // keep-latest window) does not spam.
        if (result.deleted > 0 || result.failed > 0) {
            _retentionNotices.tryEmit(
                RetentionCleanupNotice(deleted = result.deleted, failed = result.failed)
            )
        }
        return result
    }
```

- [ ] **Step 3: Remove the temporary bridge + fix the stale `segmentIndex` KDoc**

In `HistoryDeleter.kt`, delete the entire deprecated `delete(item)` method (KDoc included).

In `HistoryViewModel.kt` (~L118–127), replace the `segmentIndex` KDoc paragraph:

```kotlin
     * Delete handler uses this to remove the per-segment file
     * (`sessionDir/segment_$segmentIndex.mp4`) and update the manifest's
     * segments list.
```

with:

```kotlin
     * Deleting a segment row removes only its own file
     * (`sessionDir/segment_$segmentIndex.mp4`); the manifest's segments
     * list is deliberately NOT rewritten — the listing's
     * exists-and-non-empty filter drops the row, and the session
     * directory is discarded only when the LAST listed segment goes
     * (ADR-0036 I2; a premature discard destroyed sibling kept
     * segments — 2026-07-06 branch analysis).
```

- [ ] **Step 4: Full test suite + gates**

```powershell
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```
Expected: all tests pass (baseline ≈2200+ plus the new planner/deleter suites, 0 failures), `assembleDebug` BUILD SUCCESSFUL with all 48 `check*` gates green. (`lintDebug` stays RED on the pre-existing `VaultAndroidOps:267 NewApi` — not this branch.)

- [ ] **Step 5: Commit (Tasks 3+4 together — one compilable unit)**

```powershell
git add app/src/main/java/com/aritr/rova/ui/screens/RecordingRetentionCleaner.kt app/src/test/java/com/aritr/rova/ui/screens/RecordingRetentionCleanerTest.kt app/src/main/java/com/aritr/rova/ui/screens/HistoryViewModel.kt app/src/main/java/com/aritr/rova/ui/screens/HistoryDeleter.kt
git commit -m @'
fix(library): route manual delete + retention through the ADR-0036 batch transaction

deleteItemsKeyed and applyRetentionCleanupIfEnabled now call
HistoryDeleter.deleteAll: artifacts first, one discardSession per
planner-eligible session. RecordingRetentionCleaner shrinks to a pure
surplus selector. Removes the temporary per-item bridge. Fixes
Defect A (DualShot partial-batch orphan, BACKLOG P2) and Defect B
(MULTI_SEGMENT_KEPT sibling collateral loss); corrects the stale
segmentIndex delete-handler KDoc.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
'@
```

---

### Task 5: Docs sync

**Files:**
- Modify: `docs/BACKLOG.md` (~L231–232 — the DualShot delete orphan entry)
- Modify: `CLAUDE.md` (ADR count sentence: "**35 ADRs** in `docs/adr/` (0001–0035, …)")

**Interfaces:** none — documentation only.

- [ ] **Step 1: Close the BACKLOG entry**

In `docs/BACKLOG.md`, change the entry's checkbox from `- [ ]` to `- [x]` and append to the end of its body text (same indentation as the entry body):

```markdown
  **CLOSED 2026-07-06** — fixed by ADR-0036 (deletion transactionality, branch `fix/library-discard-session-orphan`): `HistoryDeleter.deleteAll` runs all artifact deletes first, then `discardSession` once per session the pure `SessionDiscardPlanner` marks eligible (all batch outcomes succeeded + batch covers every listed artifact of the session). The same fix closed a second, previously-unfiled defect found during the branch analysis: deleting ONE `MULTI_SEGMENT_KEPT` segment row discarded the session dir and destroyed the sibling kept-segment files. Retention (`applyRetentionCleanupIfEnabled`) routes through the same batch transaction.
```

- [ ] **Step 2: Update the CLAUDE.md ADR inventory**

In `CLAUDE.md`, in the "Architecture Decision Records" section, change:

```
**35 ADRs** in `docs/adr/` (0001–0035,
```
to:
```
**36 ADRs** in `docs/adr/` (0001–0036,
```

and append this sentence at the very end of the ADR paragraph (after the playback-hairline sentence ending "`memory/project_library_playback_hairline.md`."):

```
**(0036, Accepted 2026-07-06) Deletion transactionality — manifest-discard-last:** the Library delete + retention pipelines run a two-step transaction (all artifact deletes first, then `discardSession` once per session the pure `SessionDiscardPlanner` marks eligible — every batch outcome succeeded AND the batch covers all listed artifacts of the session; invariants I1–I5, ghost-manifest acceptable / orphan-file never). Fixes the DualShot partial-batch orphan (BACKLOG P2) and the worse, previously-unfiled `MULTI_SEGMENT_KEPT` sibling collateral loss. `HistoryDeleter` = batch orchestrator; `RecordingRetentionCleaner` = pure surplus selector; subset deletes now KEEP the manifest (owner-approved). Dedicated gate deferred.
```

- [ ] **Step 3: Commit**

```powershell
git add docs/BACKLOG.md CLAUDE.md
git commit -m @'
docs: sync BACKLOG + CLAUDE.md for ADR-0036 deletion transactionality

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
'@
```

---

### Task 6: Device verification (main session, RZCYA1VBQ2H)

Not subagent work — the main session drives adb. Build + install:

```powershell
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **6.1 Defect B (device-reproducible):** record a multi-segment session, force-kill mid-session (or use a session that fails merge) so recovery offers Keep-as-raw → creates a `MULTI_SEGMENT_KEPT` session with ≥2 segment rows. Delete ONE segment row from the Library. **PASS:** the sibling segment rows survive, still play; `adb shell run-as com.aritr.rova ls files/videos/<sid>/` still shows the sibling `segment_*.mp4` + `manifest.json`. Then delete the remaining segment rows. **PASS:** session dir gone after the last one.
- [ ] **6.2 DualShot full delete (regression):** record a short DualShot session, select the session row, delete, let the snackbar expire. **PASS:** both gallery files gone, session dir gone, no Library row.
- [ ] **6.3 Idempotency / out-of-band:** record DualShot; `adb shell rm` ONE side's public file out-of-band; refresh Library (lone-side row appears); delete it. **PASS:** row gone, session dir gone (lone side covered the whole listed session).
- [ ] **6.4 Undo window regression:** start a delete, tap UNDO. **PASS:** rows restore, files intact.
- [ ] **6.5 Retention regression:** enable keep-latest-N with N below current count. **PASS:** surplus trimmed, notice snackbar appears, kept rows intact.
- [ ] **6.6 Single-mode + legacy delete regression:** delete a single-mode recording. **PASS:** file + session dir gone.

True mid-batch artifact failure is not forceable on-device for app-owned rows — that path is covered by the JVM seams (Task 2 tests); state this honestly in the PR.

- [ ] **6.7 Record results** in the PR body (per-check PASS/FAIL).

---

### Task 7: PR preparation

- [ ] **Step 1:** `pwsh scripts/preflight.ps1` — resolve any flags.
- [ ] **Step 2:** Push and open PR:

```powershell
git push -u origin fix/library-discard-session-orphan
gh pr create --title "fix(library): ADR-0036 deletion transactionality — manifest-discard-last" --body-file <generated body>
```

PR body must include: the two defects + shared root cause, ADR-0036 invariants summary, the retention-path exposure, test evidence (suite count, gates), device-verification checklist results, the honest note that mid-batch artifact failure is JVM-seam-verified only, and the codex-waiver note. End with the standard generated-with footer.

- [ ] **Step 3:** Request review per `superpowers:requesting-code-review` (peer review gate as used on recent branches).

---

## Self-Review

1. **Spec coverage:** ADR transaction structure → Tasks 1–2; both entry points → Task 4 (manual) + Tasks 3–4 (retention); Defect A/B regressions → Task 2 tests; KDoc corrections → Task 4 Step 3; docs → Task 5; device plan → Task 6. I5 (recovery untouched) — no recovery file modified anywhere. ✓
2. **Placeholder scan:** every code step has full code; PR body step describes required content (prose deliverable). ✓
3. **Type consistency:** `SessionDiscardPlanner.Outcome(stableKey, sessionId, deleted)` and `plan(outcomes, listedKeysBySession)` used identically in Tasks 1/2; `deleteAll(batch, listedItems)` identical in Tasks 2/4; `surplus(enabled, keepLatest, items)` identical in Tasks 3/4. ✓
4. **Compile-greenness per commit:** Task 2 keeps the deprecated bridge so `main` compiles; Tasks 3+4 are one commit because removing `clean` breaks the VM until the rewire lands. ✓
