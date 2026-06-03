# Completed-session retention contract — design

- **Status:** Approved (owner sign-off 2026-06-03)
- **Date:** 2026-06-03
- **Type:** Test + ADR note (no production code change)
- **Closes:** Smoke-test finding #10 (storage bloat) — as **working-as-designed**

## Goal

Pin the invariant that a **successfully-COMPLETED recording session directory is
retained** by the cold-launch recovery+cleanup pass, and is removed only by an
explicit user delete (`HistoryDeleter` → `SessionStore.discardSession`) or the
opt-in keep-latest-N retention cleaner (`RecordingRetentionCleaner`). The session
dir IS the manifest-backed Library/History index — its persistence is intentional,
not a leak.

## Context — why finding #10 is working-as-designed

Smoke test #10 observed ~249 MB app-data with 5 recordings and asked whether
completed sessions leak. They do not. `HistoryViewModel` enumerates the Library
from `SessionStore.listSessionIds()` over `videos/<sessionId>/` dirs
(`HistoryViewModel.kt:305`), and the in-app player resolves its source from
manifest fields (`PlayerUriResolver.kt:84-105`), not from the session dir. The
session dir (manifest + per-session metadata) is the durable index; deleting it
removes the recording from History. So completed sessions are *meant* to persist.

## The retention mechanism (exact, code-verified)

Retention is **not** carried by the manifest file — `RecoveryScanner` excludes
`manifest.json` / `manifest.json.tmp` from `unknownFilenames`
(`RecoveryScanner.kt:126-132`). A real COMPLETED session always has **≥1 segment
record** in its manifest (you cannot merge zero segments), and retention holds via
**either** of two paths, both ending in `OFFER_DISCARD`:

- **Segments deleted (normal).** `RovaRecordingService.performMerge` deletes the
  on-disk `segment_*.mp4` files on export success
  (`RovaRecordingService.kt:3425-3428`) but leaves the manifest's segment
  **records** intact and writes `Terminated.COMPLETED`. The next scan finds each
  manifest segment key with no disk file → `missingKeys` →
  `Anomaly.MissingSegment` (`RecoveryScanner.kt:181-195`, `:300-301`) →
  `anomalies.isNotEmpty()` → `OFFER_DISCARD`.
- **Segments survive (delete failed).** `performMerge`'s post-success cleanup is
  best-effort and swallows delete exceptions. A surviving segment file makes
  `anySurvivors == true` → `OFFER_DISCARD` with no `MissingSegment`.

Either way, `ExportCleanupPredicate.shouldDelete` gate 1 requires
`AUTO_DISCARD_ELIGIBLE` (`ExportCleanupPredicate.kt:86-87`) → returns `false` → the
dir is retained.

**Degenerate edge (reachable over abnormal persisted data).** A COMPLETED manifest
with **zero** segment records and no disk files → no anomalies, no survivors →
`AUTO_DISCARD_ELIGIBLE` → auto-deleted. The normal merge path can't reach it, but
the late-terminal recovery writer `ExportRecoveryRunner` writes `COMPLETED` for any
`FINALIZED` manifest with a valid artifact **without a segment-count guard**
(`ExportRecoveryRunner.kt:218`, guarded only by artifact validity at `:205`). So a
corrupt/abnormal empty-`segments` `FINALIZED` manifest reaches this state and its
History-index dir would be auto-deleted (its MediaStore artifact survives). This is
a **known boundary**, left as-is (adding the guard touches the guarded recovery
writer — a separate slice); the test fixes the behavior so the boundary can't move
silently.

## Why this needs pinning

The retention rides on the MissingSegment-anomaly path. Two plausible future
refactors silently break it and make completed recordings auto-delete on the next
cold launch:

- Clearing `manifest.segments` when writing `COMPLETED` (a "tidy the manifest" change).
- Exempting COMPLETED sessions from missing-segment anomalies in `RecoveryScanner`.

A focused regression test + an ADR clause document the contract and its mechanism so
either change fails loudly.

## Deliverables

### 1. Test — `CompletedSessionRetentionTest` (JVM, new file)

Path: `app/src/test/java/com/aritr/rova/service/export/CompletedSessionRetentionTest.kt`
(package `com.aritr.rova.service.export` so the `internal ExportCleanupPredicate`
is visible; imports `RecoveryScanner` from `service.recovery`).

End-to-end pin (real `RecoveryScanner` → real `ExportCleanupPredicate`):

- **Primary** — seed a COMPLETED manifest with 2 segment records and **no** disk
  segment files (post-merge-success state). Assert:
  - `classify` → `terminalAction == ALREADY_TERMINAL`, a `MissingSegment` anomaly
    present, `eligibility == OFFER_DISCARD`.
  - `ExportCleanupPredicate.shouldDelete(classification, manifest, null, Swept)
    == false` (gate 1 blocks).
  - `runCleanupPass` returns `[]` and the session dir still exists.
- **Negative control** — a COMPLETED manifest with **zero** segment records
  classifies `AUTO_DISCARD_ELIGIBLE` (documents the one unreachable edge that the
  retention does *not* cover, so the test states the boundary explicitly).
- **Manual-delete path still works** — `SessionStore.discardSession(sid)` removes
  the retained dir (the contract retains against *cleanup*, not against the user).

### 2. ADR note

- **Amend ADR-0005** (`docs/adr/0005-recovery-scan.md`) with a
  "Completed-session retention" clause: the invariant, the exact mechanism
  (MissingSegment → OFFER_DISCARD → cleanup gate-1 block), the degenerate
  zero-segment edge, and the working-as-designed disposition of finding #10.
- **One-line cross-ref** from ADR-0006's cleanup-gate section pointing at the
  ADR-0005 clause.

### 3. No new `check*` gate

The invariant is a semantic cross-unit relationship (scanner eligibility ⇄ cleanup
gate), not a source-text pattern. A regex/AST gate can't express it; the regression
test is the guard. (YAGNI — adding a permanently-approximate gate would diverge from
the house "one invariant, one enforceable check" convention.)

## Out of scope

- Changing retention behavior (it is correct).
- A storage-pressure UI / auto-prune (the keep-latest-N cleaner already exists and is
  opt-in; product decision, separate slice).
- Touching `performMerge`'s segment-deletion or the manifest segment records.
