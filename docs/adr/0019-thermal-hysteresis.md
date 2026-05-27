# ADR-0019 — Asymmetric thermal hysteresis (instant rise, dwell-gated fall)

**Status:** Proposed (Milestone 3)

**Date:** 2026-05-26

**Supersedes:** `docs/WarningCenterContract.md §7` deferral note (2026-05-24) which stated "MutableStateFlow.distinctUntilChanged dedupe is sufficient." Owner reassessment 2026-05-26 promoted hysteresis from §Parked → Milestone 3 on the grounds that boundary-temperature OS flap is a real edge case the dedupe heuristic doesn't catch.

## Context

`ThermalStatusSignal` (Phase 4 Slice 3 partner, ADR-0016) consumes `PowerManager.OnThermalStatusChangedListener` (API 29+) and maps raw OS thermal status ints (0..6) to `ThermalStatus` enum values (NONE / LIGHT / MODERATE / SEVERE / CRITICAL / EMERGENCY / SHUTDOWN). Today's implementation is a raw passthrough — every OS event updates `_state.value` directly.

At boundary temperatures (e.g. around the SEVERE↔CRITICAL transition), Android's thermal sensor + status mapping reports rapid alternation. Observed pattern: `MODERATE → SEVERE → MODERATE → SEVERE` within hundreds of milliseconds. `MutableStateFlow` natively deduplicates adjacent identical emissions, but does NOT suppress two-step flap because each emission differs from its predecessor.

Two real failure modes today:

1. **Banner flicker on Record screen.** `WarningPrecedence.allActive(...)` emits different `WarningId` lists per `ThermalStatusSignal.state` emission. Boundary thrash → banner repaints multiple times per second.
2. **False-positive auto-stop.** `SegmentGateThermal.shouldTerminate(status)` returns `true` when `status.ordinal >= CRITICAL.ordinal`. A transient CRITICAL spike (even if sustained temperature never hit threshold) fires recording auto-stop. User loses an in-flight recording over a single boundary event.

A platform constraint shapes the solution: Android's `OnThermalStatusChangedListener` exposes discrete status values, not raw temperatures. Temperature-based deadband ("hysteresis = 2°C") is impossible. The hysteresis layer must work in the discrete-event domain — time-dwell or event-count, applied to status transitions.

## Decision

Five interlocking choices form the hysteresis design. All five locked during 2026-05-26 owner brainstorm.

### 1. Asymmetric semantics — instant rise, dwell-gated fall

Going UP (`raw.ordinal > stable.ordinal`): immediate transition. Banner reflects higher thermal level instantly; auto-stop fires on first CRITICAL event with no lag.

Going DOWN (`raw.ordinal < stable.ordinal`): start a fall-dwell timer. If raw stays below stable for the full dwell, step down one level. If raw returns to stable (or above) during dwell, clear the dwell and hold.

**Rejected:**
- **Symmetric dwell** — delays heat-detection. Real heat accumulates with sustained workload; delayed response = device hotter when finally detected; OS-kill more likely.
- **Confirmation-count only** — K consecutive lower-raw events with no time component. Susceptible to fast-burst flap (3 events in 50ms still satisfies K=3); doesn't reflect real cooling.

### 2. Uniform 3-second dwell

`THERMAL_FALL_DWELL_MS = 3_000L`. Single code constant.

**Rejected:**
- **1 second** — too short. Boundary flap window is itself ~1-2 seconds; 1s dwell still flickers.
- **5 seconds** — too laggy. User notices banner lingering 5s after device clearly cooled.
- **Per-level scaling** (e.g. 1s for MODERATE→LIGHT, 5s for CRITICAL→SEVERE) — encodes stakes-asymmetry but is speculative configurability without field data justifying. YAGNI today. Promote to a Milestone 3.1 follow-up if field reports show specific flicker patterns at level boundaries.

### 3. Transform site — hybrid pure-helper + signal-side state

Pure-helper math (`applyThermalHysteresis(raw, current, nowMs, fallDwellMs)`) provides the JVM test seam. The signal class (`ThermalStatusSignal`) holds the in-flight `HysteresisState` (current stable + dwell-entered timestamp) and calls the helper from its listener callback. Consumers (`WarningPrecedence`, `SegmentGateThermal`, `RovaRecordingService`) read the stable status via the existing `state: StateFlow<ThermalStatus>` API — no consumer changes required.

**Rejected:**
- **Pure-downstream wrapper component** — adds new layer (e.g. `ThermalHysteresisSignal` wrapping `ThermalStatusSignal`). Two-consumer risk: one reads raw, one reads stable, behaviors diverge.
- **Per-consumer hysteresis** — `WarningPrecedence` and `SegmentGateThermal` each apply hysteresis independently. Duplication; risk of inconsistent state.

Project precedent for the hybrid pattern: `AspectFitMath`, `cycleModeNext`, `recordingFrameLayout`, `MergeFailureClass`, `MergeRetryPolicy`, `StoragePreflight` — every recent helper extraction uses pure-helper math + stateful caller.

### 4. Multi-level fall — step-down one level per dwell

When raw drops multiple levels in a single event (e.g. `CRITICAL → LIGHT`), the helper transitions to `SEVERE` after the first 3s dwell. Next raw event (still `LIGHT`) restarts dwell against the new stable (`SEVERE`); after another 3s, transitions to `MODERATE`. Total CRITICAL→LIGHT = 9 seconds via 3 dwells.

**Rejected:**
- **Jump-to-raw** — single dwell, transition all the way from CRITICAL to LIGHT. Banner doesn't gradient down; loses intermediate-state visibility; user sees abrupt CRITICAL → LIGHT.
- **Step-down with raw-floor** (track lowest-seen raw during dwell; transition further if raw dropped) — marginal complexity gain over plain step-down; reset-on-rise already handles the safety case.

### 5. Initial state — snapshot on `start()`

In `ThermalStatusSignal.start()`, before registering the listener, snapshot current OS state via `PowerManager.getCurrentThermalStatus()` and initialize `HysteresisState(stable = <current>, dwellEnteredAtMs = null)`. Avoids NONE-flash on hot-device app launch.

**Rejected:**
- **Clean cold start (`HysteresisState(stable = NONE, dwellEnteredAtMs = null)`)** — would emit `NONE → CRITICAL` transition via the listener's first event. Incorrect — device was already CRITICAL; the app should reflect that immediately.

## Consequences

### Accepted

- `ThermalStatusSignal.state` semantic shifts from raw OS passthrough to hysteresis-stable. API signature unchanged at the Kotlin level; emitted-value timing changes. Documented here so future maintainers reading `state.value` know they see stable, not raw.
- `SegmentGateThermal.shouldTerminate` source unchanged. It now reads hysteresis-stable status, which means transient CRITICAL spikes no longer fire auto-stop. **This is the safety win** — transient CRITICAL was a false-positive auto-stop; the user no longer loses recordings to boundary ticks.
- Banner-display behavior settles. Boundary thrash no longer triggers banner flicker. `WarningPrecedence.allActive(...)` emits a stable list once raw stays below stable for 3 seconds.
- 3-second lag added to FALL transitions only. Rise remains instant. Acceptable UX cost; matches platform conventions (Android's own CPU thermal-throttling exhibits similar asymmetric behavior).
- Adds `currentState: HysteresisState` field to `ThermalStatusSignal`. Two extra fields per signal instance; negligible memory.
- Tests gain `ThermalHysteresisTest.kt` with 10 cases covering rise-instant, fall-dwell, dwell-reset, dwell-expire, multi-level fall, equal-raw mid-dwell, boundary thrash, defensive negative-now.

### Rejected (consolidated)

- Symmetric dwell. (Safety.)
- Confirmation-count only. (Gameable.)
- Per-level dwell scaling. (YAGNI.)
- Pure-downstream wrapper. (Divergent consumers.)
- Per-consumer hysteresis. (Duplication.)
- Jump-to-raw multi-level fall. (Loses visibility.)
- Step-down with raw-floor. (Marginal complexity gain.)
- Clean-cold-start initial state. (NONE-flash on hot launch.)

### Out-of-scope (future work)

- **Per-level dwell scaling** — Milestone 3.1 if field data justifies. Owner unpark trigger: specific banner-flicker reports at a level boundary that uniform 3s doesn't handle.
- **Settings-UI control of dwell duration** — `THERMAL_FALL_DWELL_MS` is a code constant; no plan to expose as a preference. User does not benefit from tuning thermal hysteresis manually.
- **Temperature-based deadband** — impossible per OS constraint. Would require raw temperature exposure that the public Android API doesn't provide.
- **Persistence of hysteresis state across process restart** — out of scope. Lost dwell-in-progress on process death = 3s timer reset = acceptable. Initial-snapshot via `getCurrentThermalStatus()` recaptures the device-level state. Persisting dwell across restarts would add SharedPreferences IO for negligible UX gain.

## Hard invariants preserved

- `WarningId` enum (20 entries, ordinal pinning) — unchanged.
- `WarningPrecedence.resolve == allActive.firstOrNull()` invariant — unchanged.
- `ThermalStatus` enum (7 values, ordinal 0..6) — unchanged.
- `ThermalStatusSignal.state: StateFlow<ThermalStatus>` API signature — unchanged.
- `Terminated` (5 values) / `StopReason` (6 values) enums — unchanged.
- `SessionStore::markTerminated` 3-arg atomic API — unchanged.
- `ExportResult` sealed-class arm list (9 arms) — unchanged.
- Forbidden pair B16 (`USER_STOPPED + NONE`) — unaffected.
- ADR-0009 / ADR-0010 / ADR-0011 / ADR-0012 / ADR-0017 / ADR-0018 outputs — byte-identical.
- `EglRouter` / `AspectFitMath` / `DualVideoRecorder` / muxer / preview surfaces — UNTOUCHED.
- `SegmentGateThermal.shouldTerminate` source — unchanged.
- Milestone 2 recovery surface (`RecoveryMerger`, `RovaRecordingService::performMerge`, `MergeFailureClass`, `MergeRetryPolicy`, `StoragePreflight`, `HistoryArtifactMapper`) — UNTOUCHED.

## Implementation reference

See `docs/superpowers/specs/2026-05-26-thermal-hysteresis-design.md` for the full design, test cases, and acceptance criteria. Implementation plan to be drafted via `superpowers:writing-plans` after this ADR + spec are committed.

## Mockup files

None. Hysteresis is invisible to the UI surface; only affects timing of existing banners.
