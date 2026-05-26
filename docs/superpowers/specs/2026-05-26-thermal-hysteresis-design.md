# Milestone 3 — Thermal Hysteresis Design

**Date:** 2026-05-26
**Status:** Draft — pending owner review
**Master tip baseline:** `0347880` (post-PR #51 Milestone 2)
**Owner approvals on file:** Milestone order 1→2→3→4→5 approved; 3 design decisions locked during brainstorm (asymmetric instant-rise + dwell-fall semantics, 3s uniform dwell, hybrid A+B transform site).

## 1. Context

`ThermalStatusSignal` (Phase 4 Slice 3 ADR-0016 partner) is a raw passthrough from Android's `PowerManager.OnThermalStatusChangedListener` (API 29+) to the project's `ThermalStatus` enum. Every OS thermal event triggers `_state.value = ThermalStatus.fromRaw(rawInt)` directly.

At boundary temperatures (e.g. around the SEVERE↔CRITICAL transition), the OS reports rapid alternation — observed pattern `MODERATE → SEVERE → MODERATE → SEVERE` within hundreds of milliseconds. `MutableStateFlow` deduplicates adjacent identical values natively, but does NOT suppress two-step flap because each emitted value differs from the prior.

Real consequences:
1. **Banner flicker** — `WarningPrecedence.allActive(...)` reports different `WarningId` sets per emission, causing the Record-screen banner to repaint several times per second during thrash.
2. **False-positive auto-stop** — `SegmentGateThermal.shouldTerminate(status)` returns `true` when `status.ordinal >= CRITICAL.ordinal`. A transient CRITICAL spike fires recording auto-stop even when sustained temperature never exceeded threshold; the user loses an in-flight recording over a boundary tick.
3. **Wasted work** — every emission propagates through all WarningCenter subscribers; flap multiplies the work.

`WarningCenterContract.md §7` (added 2026-05-24) deferred hysteresis with the note "`distinctUntilChanged` dedupe is sufficient." That assessment held until field-flap concerns surfaced during the 2026-05-26 roadmap reassessment, which promoted hysteresis from §Parked → Milestone 3.

## 2. Goal

Replace the raw OS passthrough in `ThermalStatusSignal` with **asymmetric hysteresis**: instant rise (going UP — safety-critical), dwell-gated fall (going DOWN — UX stability). Step-down model: each level transition takes one 3-second dwell; rapid OS flap collapses into a single stable level.

Specifically:

- Going UP (`raw.ordinal > stable.ordinal`): immediate transition to `raw`. Banner reflects new (higher) thermal level instantly; auto-stop fires on first CRITICAL event with no lag.
- Going DOWN (`raw.ordinal < stable.ordinal`): start a 3-second dwell timer. If raw stays below stable for the full 3 seconds, step down ONE level. If raw returns to stable (or above) during dwell, clear dwell and hold.
- Equal raw mid-dwell (`raw == stable`): clear in-flight dwell; raw bounced back to stable, no real cooling.
- Multi-level fall sequence: each level transition takes its own 3s dwell. Going from CRITICAL → LIGHT requires 3 dwells = 9 seconds total. Coherent banner sequence preserved.

## 3. Out of scope

- **Per-level dwell scaling** — locked uniform 3s per brainstorm. Future Milestone 3.1 candidate if field reports show specific flicker patterns at a level boundary that the uniform 3s doesn't handle.
- **Settings-UI control of dwell duration** — `THERMAL_FALL_DWELL_MS` is a code constant, not a user preference.
- **Hysteresis on rise** — locked instant per brainstorm. Symmetric option rejected on safety grounds.
- **Temperature deadband** — impossible per OS constraint. `OnThermalStatusChangedListener` exposes discrete status values, not raw temperatures.
- **Persistence of hysteresis state across process restart** — lost dwell-in-progress = 3s timer reset = acceptable. Initial state snapshot via `getCurrentThermalStatus()` on `start()` avoids NONE-flash.
- **G — "Continue without saving" outcome distinct from Discard** — parked.
- **Mid-recording (not mid-merge) thermal interrupt** — parked.
- **Onboarding / notification sheet / in-app player / editor** — Milestones 4-6+.
- **Mockup files** — none new. Hysteresis is invisible to UI surface; only affects timing of existing banners.

## 4. Hard invariants preserved

- `WarningId` enum (20 entries, ordinal pinning) — unchanged.
- `WarningPrecedence.resolve == allActive.firstOrNull()` invariant — unchanged.
- `ThermalStatus` enum (7 values: NONE / LIGHT / MODERATE / SEVERE / CRITICAL / EMERGENCY / SHUTDOWN; ordinal 0..6) — unchanged.
- `ThermalStatusSignal.state: StateFlow<ThermalStatus>` API signature — unchanged at the signature level. Emitted values shift from raw-OS-passthrough to hysteresis-stable (the documented semantic upgrade per ADR-0019).
- `Terminated` (5 values) / `StopReason` (6 values) enums — unchanged.
- `SessionStore::markTerminated` 3-arg atomic API — unchanged.
- `ExportResult` sealed-class arm list (9 arms) — unchanged.
- Forbidden pair B16 (`USER_STOPPED + NONE`) — unaffected.
- ADR-0009 / ADR-0010 / ADR-0011 / ADR-0012 / ADR-0017 / ADR-0018 outputs — byte-identical.
- `EglRouter` / `AspectFitMath` / `DualVideoRecorder` / muxer / preview surfaces / recording pipeline — UNTOUCHED.
- `SegmentGateThermal.shouldTerminate(status)` source — unchanged. Reads `ThermalStatusSignal.state` as today; now sees hysteresis-stable values, which is the safety win — transient CRITICAL spikes no longer fire auto-stop.
- Milestone 2 recovery surface (`RecoveryMerger`, `RovaRecordingService::performMerge`, `MergeFailureClass`, `MergeRetryPolicy`, `StoragePreflight`, `HistoryArtifactMapper`) — UNTOUCHED.
- `WarningCenterAggregateTest` / `WarningIdOrderTest` / `WarningPrecedenceTest` / `RecoveryScannerTest` / `RecoveryMergerRetryTest` / `AspectFitMath*Test` / `RecordingFrameLayoutTest` / `HistoryArtifactMapperSegmentRowsTest` — byte-identical green.

## 5. Design decisions (owner-approved during 2026-05-26 brainstorm)

| # | Decision | Value |
|---|---|---|
| 1 | Hysteresis semantics | Asymmetric: instant rise; dwell-gated fall. Rejected: symmetric (safety), confirmation-count-only (gameable). |
| 2 | Fall-dwell duration | Uniform 3 seconds. Single constant `THERMAL_FALL_DWELL_MS = 3_000L`. Rejected: 1s (too short — matches flap window), 5s (too laggy for low-severity), per-level scaling (YAGNI today). |
| 3 | Transform site | Hybrid: pure-helper math (test seam) called from inside `ThermalStatusSignal` (single source of truth). Rejected: per-consumer (duplication risk), pure-downstream wrapper (risks divergent consumers). |
| 4 | Multi-level fall behavior | Step-down (one level per dwell). Rejected: jump-to-raw (loses intermediate banner visibility), step-down-with-raw-floor (marginal complexity). |
| 5 | Initial state on `start()` | Snapshot via `PowerManager.getCurrentThermalStatus()` to avoid NONE-flash on hot-device launch. |

## 6. Architecture

### 6.1 New abstractions

```kotlin
// app/src/main/java/com/aritr/rova/ui/signals/ThermalHysteresis.kt

internal const val THERMAL_FALL_DWELL_MS: Long = 3_000L

internal data class HysteresisState(
    val stable: ThermalStatus,
    val dwellEnteredAtMs: Long?,   // null when not in fall-dwell window
)

internal fun applyThermalHysteresis(
    raw: ThermalStatus,
    current: HysteresisState,
    nowMs: Long,
    fallDwellMs: Long = THERMAL_FALL_DWELL_MS,
): HysteresisState
```

The helper is `internal` so JVM tests in the same module can exercise it directly. Pure: no Compose, no Android imports, no coroutines.

### 6.2 State-machine semantics

Pseudocode (canonical contract):

```
On (raw, current, now):
    if raw.ordinal > current.stable.ordinal:
        # RISE — instant; clear any in-flight dwell.
        return HysteresisState(stable = raw, dwellEnteredAtMs = null)

    if raw.ordinal == current.stable.ordinal:
        # NO CHANGE — clear any in-flight dwell (raw bounced back to stable).
        return HysteresisState(stable = current.stable, dwellEnteredAtMs = null)

    # raw.ordinal < current.stable.ordinal — FALL candidate.
    if current.dwellEnteredAtMs == null:
        # Start new fall-dwell timer.
        return HysteresisState(stable = current.stable, dwellEnteredAtMs = now)

    if now - current.dwellEnteredAtMs >= fallDwellMs:
        # Dwell expired — step DOWN ONE level toward raw. Restart timer for the next level
        # (the next raw event will either cancel via rise/equal, or restart dwell at lower raw).
        return HysteresisState(
            stable = ThermalStatus.entries[current.stable.ordinal - 1],
            dwellEnteredAtMs = null,
        )

    # Dwell still in flight — hold current. Multi-event lower-raw drops during dwell
    # do NOT restart the timer; they just continue the existing wait.
    return current
```

### 6.3 Edge cases handled

| Case | Setup | Action | Expected |
|---|---|---|---|
| Boundary thrash | stable=CRITICAL, dwell=null | raw=SEVERE @ t=0 → raw=CRITICAL @ t=500 → raw=SEVERE @ t=1000 | stable=CRITICAL, dwell=t=1000 (third event restarts dwell because dwell was cleared by equal-to-stable second event) |
| Multi-level raw drop | stable=CRITICAL, dwell=null | raw=LIGHT @ t=0 → dwell expires @ t=3000 | stable=SEVERE @ t=3000 (step-down one level; raw still LIGHT but next dwell pending) |
| Equal-raw mid-dwell | stable=SEVERE, dwell=t=0 | raw=SEVERE @ t=1500 | stable=SEVERE, dwell=null (cleared — no real cooling) |
| Lower-raw mid-dwell | stable=CRITICAL, dwell=t=0 | raw=MODERATE @ t=1500 | stable=CRITICAL, dwell=t=0 (unchanged — same wait continues) |
| Rise-during-dwell | stable=CRITICAL, dwell=t=0 | raw=EMERGENCY @ t=1000 | stable=EMERGENCY, dwell=null (instant rise; dwell discarded) |
| Defensive negative-now | stable=CRITICAL, dwell=null | raw=SEVERE @ t=-1 | stable=CRITICAL, dwell=t=-1 (records as-is; no crash; downstream dwell-expire still works correctly when next event arrives with positive `now`) |

### 6.4 Signal-side change

`app/src/main/java/com/aritr/rova/ui/signals/ThermalStatusSignal.kt` modified:

```kotlin
class ThermalStatusSignal(private val context: Context) {
    private val _state = MutableStateFlow(ThermalStatus.NONE)
    val state: StateFlow<ThermalStatus> = _state.asStateFlow()

    // Milestone 3: hysteresis state held in-signal.
    private var hysteresisState = HysteresisState(stable = ThermalStatus.NONE, dwellEnteredAtMs = null)

    @RequiresApi(Build.VERSION_CODES.Q)
    private val listener = PowerManager.OnThermalStatusChangedListener { rawInt ->
        val raw = ThermalStatus.fromRaw(rawInt)
        val now = SystemClock.elapsedRealtime()
        hysteresisState = applyThermalHysteresis(raw, hysteresisState, now)
        _state.value = hysteresisState.stable
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun start() {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        // Initial-state snapshot — avoids NONE-flash on app launch with hot device.
        val initial = ThermalStatus.fromRaw(pm.currentThermalStatus)
        hysteresisState = HysteresisState(stable = initial, dwellEnteredAtMs = null)
        _state.value = initial
        pm.addThermalStatusListener(listener)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun stop() {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.removeThermalStatusListener(listener)
    }
}
```

Existing `fromRaw(rawInt: Int): ThermalStatus` defensive mapping retained as-is.

`SystemClock.elapsedRealtime()` chosen over `System.currentTimeMillis()` — monotonic, unaffected by wall-clock adjustments. Standard project pattern.

### 6.5 Files touched

| File | Action | Notes |
|---|---|---|
| `app/src/main/java/com/aritr/rova/ui/signals/ThermalHysteresis.kt` | **Create** | Pure helper — `applyThermalHysteresis` + `HysteresisState` + `THERMAL_FALL_DWELL_MS` |
| `app/src/main/java/com/aritr/rova/ui/signals/ThermalStatusSignal.kt` | Modify | Add hysteresis state field; wrap listener callback; initial-state snapshot in `start()` |
| `app/src/test/java/com/aritr/rova/ui/signals/ThermalHysteresisTest.kt` | **Create** | 10 JVM unit tests covering case matrix §7.1 |
| `docs/WarningCenterContract.md §7` | Modify | Update deferral note to reference ADR-0019 (supersedes prior "distinctUntilChanged sufficient" rationale) |

## 7. Testing strategy

### 7.1 Pure-helper unit tests (10 cases)

| # | Name | Coverage |
|---|---|---|
| 1 | `rise_instant_no_dwell` | Rise transitions immediately, no dwell |
| 2 | `rise_clears_inflight_dwell` | Rise during in-flight dwell discards dwell |
| 3 | `fall_starts_dwell` | Lower raw with no prior dwell starts new dwell timer |
| 4 | `fall_holds_during_dwell` | Lower raw equal to existing dwell-target holds dwell |
| 5 | `fall_holds_at_lower_raw_during_dwell` | Even lower raw during dwell holds (no restart) |
| 6 | `fall_completes_after_dwell_one_step_down` | Dwell expire transitions exactly one level |
| 7 | `fall_completes_step_down_multi_drop_raw` | Multi-level drop only steps down ONE level per dwell |
| 8 | `equal_raw_during_dwell_clears_dwell` | Raw equal to stable mid-dwell clears dwell |
| 9 | `boundary_thrash_stays_stable` | Three-event flap returns to stable with restarted dwell |
| 10 | `defensive_negative_now_does_not_crash` | Defensive: negative `now` records as-is; no exception |

JUnit 4 (`org.junit.*`). Pure helper invocations only. No Compose / coroutines / Android.

### 7.2 No signal-class tests

Per project precedent (Milestone 1 `recordingFrameLayout` has no Compose UI tests; Milestone 2 `MergeRetryPolicy` has no coroutine flow tests on the consumer side). Math is JVM-tested; the signal class wires the helper to a flow without testing the flow plumbing.

### 7.3 Regression suite (must remain green, byte-identical)

- `WarningPrecedenceTest`, `WarningCenterAggregateTest`, `WarningIdOrderTest` — thermal `when` branches unchanged; tests pass `ThermalStatus.*` values directly to `WarningPrecedence`, bypassing the signal layer; hysteresis transparent.
- `RecoveryScannerTest`, `RecoveryMergerRetryTest` — Milestone 2 untouched.
- `AspectFitMath*Test`, `RecordingFrameLayoutTest`, `HistoryArtifactMapperSegmentRowsTest`, `MergeFailureClassTest`, `MergeRetryPolicyTest`, `StoragePreflightTest` — unrelated; unchanged.
- Static checks in `app/build.gradle.kts` — pass unchanged.

### 7.4 Manual smoke (deferred per owner pref; checklist in PR body)

Target device: Samsung SM-A176B (primary QA per memory).

1. Boot device with sustained CPU workload OR warm ambient. Launch app. Confirm initial banner reflects current OS thermal state, not NONE-flash. (Validates §5 #5 — initial-state snapshot.)
2. Cool device (rest 60s after heavy workload). Confirm banner steps down through levels with ~3s spacing per step. (Validates §5 #1 + #4 — asymmetric + step-down.)
3. Force boundary thrash (alternate workload bursts at temperature boundary). Confirm banner does NOT flicker — stays at higher level until 3s of sustained cooling. (Validates §5 #1 — dwell gating.)
4. Force fast spike (sudden heavy workload → instant CRITICAL). Confirm auto-stop fires immediately, no rise lag. (Validates §5 #1 — instant rise; §5 #3 — `SegmentGateThermal` sees stable status which transitions instantly on rise.)

## 8. Performance plan

- **Pure helper CPU cost:** single `when` evaluation + 1-2 integer comparisons per OS thermal event. Microsecond-scale. OS thermal events fire at most a few per second under typical workloads; negligible.
- **Signal-side memory:** one `HysteresisState` field per `ThermalStatusSignal` instance (2 fields). Constant. Negligible.
- **GC pressure:** each event creates a new `HysteresisState` data-class instance. Allocation rate matches event rate (a few per second). Negligible.
- **No threading impact:** listener callback runs on the registered executor (per `PowerManager` doc) — same threading model as today.

## 9. Risks summary

| # | Risk | Likelihood | Mitigation |
|---|---|---|---|
| 1 | `PowerManager.getCurrentThermalStatus()` throws on some Android skins | Low | Wrap in try-catch with `ThermalStatus.NONE` fallback if needed; existing `fromRaw` pattern handles unknown raws |
| 2 | OS event firing rate exceeds 3s window (genuine cooling slower than dwell) | Low | Step-down model: each level takes 3s; multi-level fall (CRITICAL→NONE) = 12s. Matches realistic cooling. |
| 3 | Existing `WarningPrecedence` tests detect timing change | None | Tests pass `ThermalStatus.*` directly to `WarningPrecedence.allActive(...)`, bypassing signal layer. Hysteresis transparent. |
| 4 | `SegmentGateThermal.shouldTerminate` no longer fires on transient CRITICAL — perceived as "slower auto-stop" | Low | This is the intended safety win. Transient CRITICAL was a false-positive. Documented in commit + PR. |
| 5 | Hysteresis state lost on process restart | Low | Initial-snapshot on `start()` captures current OS state. Lost dwell-in-progress = 3s timer reset. Acceptable. |
| 6 | Pre-API-29 devices (minSdk 24-28) — hysteresis applies? | None | Pre-API-29 has no thermal listener; signal is no-op. Hysteresis only runs where listener fires. |
| 7 | `ThermalStatus.entries[current.stable.ordinal - 1]` out-of-bounds when stable is NONE | None | NONE is ordinal 0; if `stable == NONE` then any `raw.ordinal < 0` is impossible (raw is also `ThermalStatus`). Fall branch is unreachable when stable=NONE. |

## 10. Acceptance criteria

- `applyThermalHysteresis` pure helper: all 10 JVM tests pass.
- `ThermalStatusSignal._state.value` emits hysteresis-stable values (manual smoke scenarios 1-4).
- Initial snapshot via `getCurrentThermalStatus()` on `start()` — no NONE-flash on hot-device launch.
- Full test suite ≥1241 (1231 baseline + 10 new), 0 failed / 0 ignored / 0 skipped.
- Lint zero-delta vs `0347880` baseline (`0 errors, 51 warnings, 1 hint`).
- `gradlew assembleDebug` succeeds.
- ADR-0019 committed alongside implementation.
- `WarningCenterContract.md §7` updated to reference ADR-0019 (supersedes prior "distinctUntilChanged sufficient" deferral note).

## 11. ADR implication

ADR-0019 warranted. Hysteresis changes a documented contract (signal emits raw OS values vs hysteresis-stable values). Even though `state: StateFlow<ThermalStatus>` API signature is unchanged, the SEMANTIC CONTRACT shifts. Future maintainers reading the signal need to know they see stable, not raw. Outline in §12. Full ADR text in `docs/adr/0019-thermal-hysteresis.md`.

## 12. ADR-0019 outline

**Title:** Asymmetric thermal hysteresis (instant rise, dwell-gated fall)

**Status:** Proposed.

**Context:**
- `ThermalStatusSignal` (Phase 4 Slice 3, ADR-0016 partner) is a raw OS passthrough.
- Boundary-temperature OS event flap causes banner flicker + false-positive auto-stop.
- `WarningCenterContract.md §7` (2026-05-24) deferred hysteresis with "distinctUntilChanged sufficient." Owner reassessment 2026-05-26 promoted to Milestone 3.

**Decision:**
- Asymmetric model: instant rise; dwell-gated fall (3 seconds uniform; step-down one level per dwell).
- Pure-helper math seam `applyThermalHysteresis(raw, current, nowMs, fallDwellMs)` returns new `HysteresisState`. JVM-testable.
- Hysteresis state held inside `ThermalStatusSignal`; consumers (WarningPrecedence, SegmentGateThermal, RecordingService) read stable status via existing `state: StateFlow<ThermalStatus>` API.
- Initial state on `start()`: snapshot via `getCurrentThermalStatus()` to avoid NONE-flash.
- Multi-level fall = step-down (one level per dwell), not jump-to-raw.

**Consequences:**

*Accepted:*
- `ThermalStatusSignal.state` semantic shifts from raw to hysteresis-stable. API signature unchanged.
- `SegmentGateThermal.shouldTerminate` reads stable — transient CRITICAL spikes no longer fire auto-stop. Safety win.
- Banner behavior settles — boundary thrash no longer flickers.
- Adds 3s lag on FALL transitions only. Rise stays instant.

*Rejected:*
- Symmetric dwell (both rise + fall) — delays heat-detection.
- Confirmation-count only — gameable by fast-burst.
- Per-level dwell scaling — YAGNI; promote to Milestone 3.1 if justified.
- Pure-downstream wrapper — adds new component; risks divergent consumers.
- Per-consumer hysteresis — duplication.
- Jump-to-raw multi-level fall — banner doesn't gradient down.

*Out-of-scope (future):*
- Per-level dwell scaling.
- Settings-UI control of dwell.
- Temperature-based deadband (impossible per OS).

**Hard invariants preserved:** see §4 of spec.

**Supersedes:** `WarningCenterContract.md §7` deferral note (updated in this milestone).

**Implementation reference:** this spec doc.

**Mockup files:** none.

## 13. Next step

After owner approval:

1. Commit spec + ADR-0019.
2. Invoke `superpowers:writing-plans` for implementation plan at `docs/superpowers/plans/2026-05-26-thermal-hysteresis.md`.
3. Plan execution under `/karpathy-guidelines`.
4. Execution via `superpowers:subagent-driven-development`.
5. Codex MCP consult available for genuinely contested decisions during dev; not for clear-precedent calls (per `feedback_codex_consult_policy.md`).
