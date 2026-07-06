# ADR-0036: Deletion transactionality — manifest-discard-last

**Status:** Accepted (2026-07-06)

## Context

A Library delete is not one operation. A session owns **N artifacts plus one
manifest**, and the relation is N:1 in two shapes:

- **DualShot** — two public gallery artifacts (portrait + landscape), one
  `sessionId`, one manifest.
- **`MULTI_SEGMENT_KEPT`** — N per-segment Library rows whose files live
  **inside** the app-private `videos/<sessionId>/` directory, next to the
  manifest.

The manifest is the source of Library visibility: finalized rows are resolved
from manifests, and the listing filters each resolved artifact to
`file.exists() && length > 0`. Two consequences anchor this ADR:

1. **No manifest → no row, ever.** A public file whose manifest is gone is
   unreachable from the app — an orphan the user can neither play nor delete
   from Rova.
2. **Manifest with zero surviving artifacts → zero rows.** The listing
   self-heals; a "ghost" manifest is invisible and costs a few KB until the
   retention cleaner collects it.

The pre-2026-07 pipeline ran the delete loop per item: delete the artifact,
and on success immediately `discardSession(sessionId)` (a recursive delete of
the session directory). That places the transaction's **commit point — the
irreversible visibility kill, and for `MULTI_SEGMENT_KEPT` the co-owned
storage itself — inside the per-item loop**, before the transaction's other
members have run. Two shipped defects follow from that one boundary error:

- **Defect A (DualShot orphan, BACKLOG P2, codex PR-B last-pass 2026-07-03):**
  portrait artifact deletes OK → manifest + dir discarded → landscape artifact
  delete then fails → its gallery file survives with no manifest → permanent
  app-unmanaged orphan in the system gallery.
- **Defect B (collateral segment loss, found in the 2026-07-06 analysis):**
  deleting ONE segment row of a `MULTI_SEGMENT_KEPT` session discards the
  session directory and **destroys the sibling segment files** — silent data
  loss of recordings the user explicitly chose to keep. (A `VideoItem.segmentIndex`
  KDoc promised a per-segment handler that updates the manifest's segments
  list; it was never implemented and is unnecessary — see Decision.)

Rova's primary promise is that recordings are safe. Deletion is the one flow
where the app intentionally destroys data, so its partial-failure behavior
must be designed, not incidental.

## Decision

Deletion provides the following transactional guarantee:

> **A session's manifest is discarded last, exactly once, and only when zero
> surviving artifacts of that session remain. Every partial state a failure
> can produce is visible in the Library and converges to fully-deleted on
> retry.**

There is no rollback machinery because no step ever precedes a step it
depends on: aborting anywhere leaves a consistent, retryable state.

### Invariants

- **I1 — No orphan.** A manifest may be discarded only when zero surviving
  artifacts of that session remain. Equivalently: any public artifact that
  still exists is always reachable from the Library.
- **I2 — No collateral.** Deleting item X never deletes bytes belonging to a
  sibling item Y. In particular, `discardSession` never fires while sibling
  segment files still live inside the session directory.
- **I3 — Fail toward visibility.** Every partial state is retryable through
  the Library. The **acceptable** residue of any failure is a *ghost
  manifest* (all artifacts gone, manifest remains): invisible via the
  exists-filter, bounded in size, collected by the retention cleaner. The
  residue that must **never** occur is an *orphan file* (artifact exists,
  manifest gone). Wherever a trade is forced — error handling, stale data,
  process death — the implementation chooses the ghost side.
- **I4 — Idempotent convergence.** Re-running delete on any partial state
  converges to fully-deleted: an already-missing artifact counts as
  successfully deleted; discarding a missing session directory is a no-op.
- **I5 — Recovery non-interference.** The recovery scan path remains
  read-only (ADR-0005, `checkRecoveryNoDeletion`); this ADR adds no deletion
  site outside the single user-driven Library pipeline. A ghost manifest
  classifies exactly as the same terminal state did before deletion started.

### Transaction structure

The transaction has exactly two steps:

1. **Delete artifacts** — every artifact in the batch, per item, recording
   per-item success.
2. **Commit manifest deletion (or retain it)** — discard each session whose
   discard is permitted under I1/I2, once; retain every other manifest.

**The planner is not part of the transaction.** It is the decision procedure
that determines whether the commit in step 2 is permitted for a given
session. The guarantees of this ADR (I1–I5) bind the *transaction* —
artifacts first, manifest-commit last, commit only when no surviving
artifact remains — and stay valid regardless of how that decision is
computed. The planning algorithm is an implementation detail and may change
without amending this ADR, provided any replacement still only ever permits
a commit that satisfies I1/I2 and resolves uncertainty toward retention (I3).

The current decision procedure: a `sessionId` is eligible for discard iff
(a) every batch item of that session succeeded, AND (b) the batch covers
every currently-listed item of that session. It is implemented as a **pure
function** — no filesystem, Android, `SessionStore`, or coroutine
dependency; its sole responsibility is mapping (batch outcomes, current
session membership) → session IDs to discard. All I/O stays in the pipeline
around it.

Membership comes from the ViewModel's current items snapshot. Staleness in
either direction fails toward **not** discarding (I3): a sibling the snapshot
still lists but which is already gone out-of-band merely leaves a ghost
manifest; the exists-filter then shows zero rows.

### Deliberate consequences

- **Subset deletes keep the manifest.** Deleting one side of a DualShot
  session (possible when a lone-side row exists) or a subset of kept segments
  no longer discards the session: the surviving sibling rows stay visible.
  This replaces the previous semantics — where deleting one side silently
  orphaned the sibling — and is forced by I1/I2. Owner-approved 2026-07-06
  (closing the "single-side delete semantics" question left open in the
  BACKLOG entry).
- **No manifest segments-list surgery.** Defect B's promised "update the
  manifest's segments list" handler stays unimplemented by design: the
  exists-filter already drops rows for deleted segment files, so mutating the
  manifest adds a write path (and a new failure mode) for no observable
  benefit. The stale KDoc is corrected instead.
- **Discard failures stay swallowed.** `discardSession` throwing after all
  artifacts are gone leaves a ghost manifest — the acceptable residue —
  and must not be reported as a delete failure (the user-visible deletion
  succeeded).
- **No tombstone / two-phase mark-then-sweep.** Process death between the
  artifact deletes and the discard leaves a ghost manifest, which I3 already
  accepts; persistent "discarding" state would buy nothing and would touch
  the terminal-state machinery (ADR-0005, `checkAtomicTerminalWriteForbiddenPair`).

## Clause

`discardSession` MUST NOT be invoked from per-item delete handling in the
Library pipeline. It executes only at the batch level, after all artifact
outcomes of the batch are known, for sessions the planner marks eligible
under I1/I2. Single-entry discipline: all Library deletion routes through the
one batch pipeline (`HistoryViewModel.deleteItemsKeyed`; count-reducing
wrappers delegate to it).

> **Gate:** a dedicated static gate (`checkDiscardSessionGated`) is
> **deferred** (owner decision 2026-07-06). The ADR, the pure-planner tests,
> and the pipeline tests are the protection for now; revisit if a future
> branch adds another deletion entry point.

## Scope

The user-driven Library delete pipeline only. Out of scope, unchanged:
recovery discard flows (`RecoveryViewModel` — whole-session by design, the
user is explicitly discarding the session), export cleanup
(`ExportCleanupPredicate`, ADR-0003), vault deletion (`VaultAndroidOps`,
ADR-0025), and the retention cleaner.
