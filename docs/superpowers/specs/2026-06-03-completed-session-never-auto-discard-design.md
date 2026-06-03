# Completed session is never auto-discarded — design

- **Status:** Approved (owner sign-off 2026-06-03)
- **Date:** 2026-06-03
- **Type:** One-line behavior fix + ADR amendment + tests
- **Closes:** The retention gap codex surfaced during item A (PR #87) — a
  zero-segment `COMPLETED`/`FINALIZED` session with a valid artifact could be
  auto-deleted by the cleanup pass.

## Problem

Item A pinned that real COMPLETED sessions are retained (via the
`MissingSegmentAnomaly` → `OFFER_DISCARD` path), but documented one open
boundary: a `COMPLETED` manifest carrying **zero** segment records classifies
`AUTO_DISCARD_ELIGIBLE` and is auto-deleted on the next cleanup pass. This is
reachable over abnormal persisted data because `ExportRecoveryRunner` writes
`COMPLETED` for any `terminated == null && exportState == FINALIZED` manifest with
a valid artifact, with **no segment-count guard**
(`ExportRecoveryRunner.kt:194-218`). The session dir (History/Library index entry)
would be deleted even though its MediaStore artifact is intact — losing the
recording from History.

The timing: the gap bites on the *subsequent* cold launch. On the launch that
writes the late terminal, the session was `terminated == null && FINALIZED`, so
`RecoveryScanner` returns `SKIPPED_EXPORT_PENDING` / `BLOCKED` (it is export-recovery
owned), and cleanup gate 1 already blocks it. Once `COMPLETED` is persisted, the
export-pending skip no longer applies; the next scan classifies it normally, and an
empty manifest yields `AUTO_DISCARD_ELIGIBLE`.

## Decision — enforce retention at the eligibility layer

A `COMPLETED` session is a finished recording the user owns; it belongs in the
Library and must **never** be auto-deleted by the cleanup pass. Enforce this at the
single decision point the cleanup reads — `RecoveryScanner` eligibility:

```kotlin
val eligibility = when {
    anomalies.isNotEmpty() || anySurvivors -> DiscardEligibility.OFFER_DISCARD
    T == Terminated.COMPLETED              -> DiscardEligibility.OFFER_DISCARD
    else                                   -> DiscardEligibility.AUTO_DISCARD_ELIGIBLE
}
```

Only `COMPLETED` is exempted. `USER_STOPPED` / `KILLED_BY_SYSTEM` /
`KILLED_FORCE_STOP` empty shells remain `AUTO_DISCARD_ELIGIBLE` (correctly
auto-cleaned). This covers **both** COMPLETED writers (live `performMerge` and the
late-terminal `ExportRecoveryRunner`), so the writer itself needs no change.

### Why `OFFER_DISCARD` is safe (no new user-visible behavior)

The recovery UI hard-filters `terminated == COMPLETED` (and `exportState ==
FINALIZED`) **unconditionally**, before consulting eligibility
(`RecoveryUiState.kt:155,178`). So a COMPLETED session never produces a recovery
card regardless of its eligibility value. Real COMPLETED sessions are *already*
`OFFER_DISCARD` today (item A), so this only changes the rare degenerate case and
introduces zero new prompts. The sole downstream effect is the intended one:
`ExportCleanupPredicate` gate 1 (`AUTO_DISCARD_ELIGIBLE` required) stops
auto-deleting the dir.

`DiscardEligibility.OFFER_DISCARD` is consumed in exactly two places — the recovery
UI (which filters COMPLETED out) and `ExportCleanupPredicate` gate 1 — so there is
no other path to perturb.

## Deliverables

### 1. Production change (one site)

`RecoveryScanner.classify` — add the `T == Terminated.COMPLETED -> OFFER_DISCARD`
branch shown above, with an invariant KDoc citing ADR-0005 and the review round
(codex, item A).

### 2. ADR-0005

- **Eligibility table:** remove `COMPLETED` from the `AUTO_DISCARD_ELIGIBLE`
  terminal set; state that `COMPLETED` is unconditionally `OFFER_DISCARD`
  (retained).
- **"Completed-session retention" clause** (added in item A): rewrite the
  degenerate-edge paragraph from *"known boundary, left as-is"* to *"closed —
  `COMPLETED` is never `AUTO_DISCARD_ELIGIBLE`, so the zero-segment case is retained
  too; `ExportRecoveryRunner`'s missing segment-count guard is now moot for
  retention."*

### 3. Tests (TDD)

- **Update** `CompletedSessionRetentionTest`'s zero-segment case: assert
  `eligibility == OFFER_DISCARD` (was `AUTO_DISCARD_ELIGIBLE`) and add
  `ExportCleanupPredicate.shouldDelete(...) == false` + dir-retained assertions.
  Rename it to reflect "retained," and drop the "unreachable/degenerate" framing
  (the case is now simply retained).
- **Add** a `RecoveryScannerTest` case: `COMPLETED` + empty `segments` + empty dir
  → `terminalAction == ALREADY_TERMINAL`, no anomalies, `eligibility ==
  OFFER_DISCARD`.
- All other item-A cases stay green unchanged.

### 4. CHANGELOG

Unreleased / Fixed entry describing the closed retention gap.

### 5. No new `check*` gate

Consistent with item A — the invariant is a semantic eligibility branch, guarded by
the tests. (A source-text gate asserting the `COMPLETED` branch exists would be
over-approximate; YAGNI.)

## Out of scope

- Adding a segment-count guard to `ExportRecoveryRunner` (now moot for retention;
  if ever wanted for hygiene it is a separate slice touching the guarded writer).
- Any change to `ExportCleanupPredicate` gates or a new `DiscardEligibility` value.
- The `MULTI_SEGMENT_KEPT` terminal (already UI-filtered; not auto-discard relevant
  here).
