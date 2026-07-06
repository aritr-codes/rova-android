# Retrospective — Deletion transactionality (ADR-0036, PR #174)

**Branch:** `fix/library-discard-session-orphan` · **Merged:** 2026-07-06 · **Type:** correctness / data-integrity

This retrospective is about *process*, not the diff. It records what the branch
taught us so the next data-integrity cycle starts further along.

## The shape of the work

A single filed P2 bug ("DualShot delete can orphan the surviving side") was the
entry point. The instruction that set the tone: *treat it as a mini-ADR answering
"what transactional guarantees should Rova provide when deleting recordings?"* —
not "patch the orphan." That reframing is the whole story of this branch.

## Lessons

### 1. Transactional architecture: name the commit point, then move it
The bug was never really "DualShot." It was a **commit point in the wrong place** —
`discardSession` (the irreversible visibility kill, and for `MULTI_SEGMENT_KEPT`
the co-owned storage delete) fired *per item inside the batch loop*, under a silent
1:1 artifact→session assumption. The relation is N:1. Every symptom fell out of that
one boundary error. The fix was not "handle the failure" — it was "delay the commit
until all members of the transaction have run." When a delete/write/finalize misbehaves,
the first question is **where is the commit point and what does it assume about
cardinality**, not "which branch threw."

### 2. System invariants outlive algorithms
ADR-0036 is written as five invariants (I1 no-orphan, I2 no-collateral, I3
fail-toward-visibility, I4 idempotent-convergence, I5 recovery-read-only), and it
**explicitly demotes the planner to a swappable decision procedure** — "the planning
algorithm may change without amending this ADR, provided any replacement still only
permits a commit that satisfies I1/I2 and resolves uncertainty toward retention."
That separation (guarantee vs. implementation) is why the adversarial review could
reason about correctness by checking the predicate against the invariants rather than
tracing every code path. Write the guarantee at the level that survives a rewrite.

### 3. Failure-mode analysis finds the bug you weren't sent to fix
The brief demanded an explicit failure-mode sweep *before any code* — deletion
failure, filesystem error, interruption, cancellation, concurrency, process death,
partial success, duplicates, stale metadata, missing files, recovery interaction.
That sweep is what surfaced **Defect B** (deleting one kept segment destroyed sibling
kept files — silent data loss, never filed, strictly worse than the reported bug) and
the **retention exposure** (a second production wiring of the same defective per-item
deleter whose keep-window could orphan a KEPT side). Neither was in the ticket. The
discipline of enumerating failure modes on paper, before touching code, is what turned
a one-symptom fix into an architectural correction.

### 4. Correctness-first development: no code in the first response
The first deliverable was analysis — architecture summary, root cause, failure modes,
invariants, candidate approaches with trade-offs — and *nothing else*. Choosing the
invariants before the implementation meant the candidates could be judged against them
("does this preserve I1 under process death?") instead of against intuition. For a
data-integrity change, the cost of designing the guarantees up front is trivial next to
the cost of discovering a missing invariant in the field.

### 5. Architectural ADRs are the unit of institutional memory
ADR-0036 is a *stronger* class of ADR than a design decision (e.g. ADR-0030's IA):
it codifies **system invariants** with a stated fail-safe direction. Because the
guarantee is fail-*toward-visibility* (a ghost manifest is acceptable, self-healing
residue; an orphan file is never), every downstream trade-off — error handling, stale
snapshots, process death — has a pre-decided correct side. New contributors don't have
to re-derive "which way do we round when uncertain"; the ADR already answered it. Keep
writing ADRs at the guarantee level, and make the fail-safe direction explicit.

### 6. Testing strategy: pure planner + honest device boundaries
The planner was extracted as a **pure function** (no FS/Android/coroutine/SessionStore),
which made both defect shapes and the vacuous-coverage edge case unit-testable without
a device. That is the house pure-helper pattern paying off exactly as intended. Equally
important was **naming what the device could and couldn't prove**: Defect B was
genuinely device-reproducible (force-kill → kept-raw → delete one segment → sibling
survives, sha1-verified); a mid-batch artifact-delete failure is *not* forceable for
app-owned rows, so it stays JVM-seam-covered and the PR says so plainly. Don't claim
device coverage you can't actually exercise.

### 7. Data-safety judgment during verification is itself a deliverable
Retention verification (6.5) was **deliberately not run on device**: retention has no
undo, and the owner's real ~49-recording library would have lost dozens of real videos
to a low keep-N test. The correct call was to lean on the byte-identical selection
policy + 9 JVM tests and document the skip honestly, rather than destroy real user data
to tick a checklist. "Recordings are safe" is a rule that binds the *verifier*, not just
the code.

### 8. Release stewardship closes the loop the merge doesn't
The device pass surfaced a **pre-existing, orthogonal** bug — `MULTI_SEGMENT_KEPT` rows
are visible but can't be played (`PlayerUriResolver` rejects `exportState != FINALIZED`).
The delete fix didn't cause it; it made the kept-raw rows reliably *survive*, which is
what exposed that they were never playable. Stewardship (not the implementer) is where
that gets filed to BACKLOG, the docs get swept for staleness, and the one imprecise KDoc
gets corrected. The branch isn't done when the tests pass; it's done when the repository
tells one consistent story and every discovered-but-out-of-scope finding has a home.

## Reusable playbook for the next data-integrity branch

1. Reframe the ticket as a guarantee question before reading code.
2. Enumerate failure modes on paper; expect to find an unfiled sibling defect.
3. Write invariants + fail-safe direction as an ADR; demote the algorithm.
4. Extract the decision as a pure function; unit-test every failure shape + the vacuous edge.
5. On device, prove what's provable, name what isn't, and never destroy real data to test.
6. Steward: sweep docs for staleness, file orthogonal findings, correct imprecise comments, then merge.
