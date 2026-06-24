# ADR-0033: Recording interval unit — minutes → seconds (30 s minimum)

**Status:** Accepted (2026-06-24)

## Context

The recording interval (idle WAIT between clips) was modeled as
`intervalMinutes: Int` — whole minutes only, so the smallest non-zero wait was
1 minute. Product wants a sub-minute option. `Int` minutes cannot express it,
and fractional-minute `Double` truncates on the `M_MINUTES.toLong()` intent path
and reads poorly. The scheduler already converts to seconds (`mMinutes * 60`),
so seconds is the natural canonical unit.

## Decision

1. The interval source-of-truth becomes **`intervalSeconds: Int`** everywhere it
   is persisted or crosses a process boundary: `SessionManifest.SessionConfig`,
   `RovaSettings`, `RovaPreset`/`BuiltInPresets`/`PresetJson`, the service intent
   extra, and the scheduler.
2. The smallest non-zero interval is **30 s**. 15 s is rejected: the start cue
   (`rova_cue_start`, ~3.5 s) plus reminder would consume too much of a 15 s gap.
   30 s accommodates the existing cues unchanged.
3. The wait picker is a **stepper over an ordered allowed-values list**
   `[0, 30, 60, 120, …, 3600]` (`0` = None/Continuous, then 30 s, then whole
   minutes to 60 min). It steps by index; direct/typed sets snap to the nearest
   allowed value.
4. Manifest schema **12 → 13**. `fromJson` reads the legacy `intervalMinutes`
   key × 60 when `intervalSeconds` is absent — old manifests load losslessly.
5. Prefs migrate to a **new** key `interval_seconds` (the old `interval` minutes
   key is NOT reinterpreted — `5` minutes must not become 5 seconds), via a
   one-shot guarded migration at `RovaSettings` construction. Custom presets
   migrate `PresetJson` v2 → v3 (× 60 on read); built-in preset interval values
   are × 60. Custom-preset ids are preserved (the `custom.` short-circuit never
   re-hashes a migrated value); a stored custom that lacks a valid `custom.*` id
   has no identity guarantee and may be re-derived (acceptable).

## Consequences

- Old manifests, prefs, and presets migrate losslessly; display of historical
  recordings is unchanged (5 min → 300 s → "5 m").
- No interval-vs-duration guard: the interval is end-to-start idle wait.
- Cue behavior is unchanged at the 30 s floor; cue-timing rework is deferred.
- No new static gate (the unit is not an enforced invariant).
