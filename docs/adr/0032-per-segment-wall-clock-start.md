# ADR-0032 — Per-segment wall-clock start

**Status**: Accepted (2026-06-24)

## Context
The in-app player needs a wall-clock playhead ("what time of day was this moment
recorded"). `config.intervalMinutes` spaces clips over wall-clock hours, but the
merged MP4 plays them back-to-back, so footage-time ≠ real-time. Deriving
capture-time as `startedAt + footageOffset` is wrong across inter-segment gaps.
`SegmentRecord` stored `durationMs` but no per-segment wall-start.

## Decision
Add `SegmentRecord.startedAtWallClock: Long?` (epoch ms), sampled at SEGMENT
START and threaded to the finalize handler that builds the record. Schema
11→12, emit-when-set (legacy/`null` records keep byte-shape). DualShot's
PORTRAIT and LANDSCAPE records of the same loop share the single start stamp
(they record simultaneously). The field is **informational only** — never a
recovery deletion/classification input, mirroring `effectiveTargetRotation`
(ADR-0029 PR-α).

## Consequences
- Legacy (schema <12) sessions and recovered orphan segments (appended by
  `RecoveryScanner` with no start source) have `null` stamps; the player
  synthesizes an approximate start per clip and surfaces it as "approx".
- No new `check*` gate: no schema-invariant gate exists, and recovery
  neutrality is upheld by existing `checkRecoveryNoDeletion` semantics + KDoc.
- Per the amend-first convention this ADR lands before the code.
