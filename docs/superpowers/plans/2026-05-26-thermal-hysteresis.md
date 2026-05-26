# Thermal Hysteresis Implementation Plan (Milestone 3)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace raw OS thermal passthrough in `ThermalStatusSignal` with asymmetric hysteresis (instant rise, 3s dwell-gated fall, step-down one level per dwell).

**Architecture:** Single pure-helper math file (`ThermalHysteresis.kt`) holds the state-machine. `ThermalStatusSignal` keeps a `HysteresisState` field and calls the helper from its listener callback + `refresh()`. Downstream consumers (`WarningPrecedence`, `SegmentGateThermal`, `RovaRecordingService`) see hysteresis-stable values through the existing `state: StateFlow<ThermalStatus>` API — no consumer changes.

**Tech Stack:** Kotlin 2.2.10, JUnit 4, `kotlinx.coroutines.runBlocking` (no `runTest` — project policy), Android API 29+ `PowerManager.OnThermalStatusChangedListener`, `SystemClock.elapsedRealtime()` for monotonic time, AGP 9.2.1 / Gradle 9.4.1, compileSdk 37.

**Spec:** `docs/superpowers/specs/2026-05-26-thermal-hysteresis-design.md`
**ADR:** `docs/adr/0019-thermal-hysteresis.md` (committed in `f213c73`)
**Baseline:** master tip `f213c73` · 1231 tests / 0F / 0I / 0S · lint 0E / 51W / 1H

---

## Background for the implementer

You are implementing a 1-day milestone that touches **2 production files + 1 test file + 1 doc file**. Read these before starting:

1. **Spec doc** (§6.1, §6.2, §6.3, §6.4, §7.1) — canonical contract for the helper signature, state-machine, edge-case matrix, and test-case list.
2. **ADR-0019** — design rationale + rejected alternatives.
3. **Existing signal file:** `app/src/main/java/com/aritr/rova/ui/signals/ThermalStatusSignal.kt` — note the **seam-pattern constructor** (`sdkInt`, `currentStatus`, `addListener`, `removeListener`). The spec §6.4 pseudocode shows a *simplified* sketch; the real signal already wraps listener registration inside seam closures so tests stay pure-JVM. Your hysteresis goes INSIDE the existing seam closure callback at `start()`, not as a rewrite of the signal class shape.
4. **Existing signal tests:** `app/src/test/java/com/aritr/rova/ui/signals/ThermalStatusSignalTest.kt` — 13 tests must remain green byte-identical. The two listener-path tests (`listener emission updates state flow`, `listener emission distinctness dedupes equal values`) work under hysteresis because their captured `(Int) -> Unit` callback runs through the hysteresis code path — and their raw sequences happen to all go UP or stay EQUAL, never down. No test edits required.

### Project precedent for pure-helper extraction

This is the 6th instance of the pure-helper math pattern in the project. Mirror the layout of the closest peers:

- `AspectFitMath.kt` / `AspectFitMath*Test.kt` (Phase 6.1c)
- `cycleModeNext` in `RovaSettings.kt` (Phase 6)
- `recordingFrameLayout` in `RecordingFrameLayout.kt` (Milestone 1, PR #50)
- `MergeFailureClass.kt` / `MergeRetryPolicy.kt` / `StoragePreflight.kt` (Milestone 2, PR #51)

Key precedent:
- Helper file is `internal` (so JVM tests in same module can call directly, but external consumers can't).
- Pure: no Compose, no Android imports, no coroutines. Just data classes + functions.
- Tests use JUnit 4 with `org.junit.Assert.assertEquals` / `org.junit.Test`. Never `kotlin.test.*`.
- For coroutines in tests use `kotlinx.coroutines.runBlocking` (not `runTest`).

### Standing project constraints

- `gradlew` invocations route through subagents (main controller blocked from long gradle calls — defer to subagent runs).
- Caveman mode active for prose; code/commits/PRs write normal.
- Karpathy-disciplined: no speculation, no abstraction beyond what spec demands.
- ADR-0006 B21 (no `getExternalFilesDir(null)` — use `RovaApp.externalRoot`) does not apply here (no storage paths touched).
- All commits include `Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>` trailer.

---

## File structure

| File | Action | Lines (est.) | Responsibility |
|---|---|---|---|
| `app/src/main/java/com/aritr/rova/ui/signals/ThermalHysteresis.kt` | **Create** | ~50 | Pure helper: `applyThermalHysteresis` + `HysteresisState` + `THERMAL_FALL_DWELL_MS` |
| `app/src/test/java/com/aritr/rova/ui/signals/ThermalHysteresisTest.kt` | **Create** | ~180 | 10 JVM unit tests covering all rise/fall/equal/edge cases |
| `app/src/main/java/com/aritr/rova/ui/signals/ThermalStatusSignal.kt` | Modify | +12 / -3 | Add `HysteresisState` field; wrap listener callback; reset state in `refresh()` |
| `docs/WarningCenterContract.md` | Modify | +4 / -2 | Update §5 "Hysteresis on Escalating" + §10 #3 deferral note to reference ADR-0019 |

Total: 3 source/test files + 1 doc file. ~250 LOC net add.

---

## Task 1 — Pure hysteresis helper + 10 JVM tests

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/signals/ThermalHysteresis.kt`
- Create: `app/src/test/java/com/aritr/rova/ui/signals/ThermalHysteresisTest.kt`

This task lands the entire pure-helper + its full test suite in one TDD round. The helper has 6 distinct code paths (rise / equal-no-dwell / fall-start-dwell / fall-hold-during-dwell / fall-expire-step-down / equal-clears-dwell-during-fall); 10 tests cover them with overlap on multi-level fall + boundary thrash + defensive negative-now.

- [ ] **Step 1: Write the helper skeleton (compiles, all paths return `current` unchanged)**

Create `app/src/main/java/com/aritr/rova/ui/signals/ThermalHysteresis.kt`:

```kotlin
package com.aritr.rova.ui.signals

/**
 * Milestone 3 (ADR-0019) — fall-dwell duration for asymmetric thermal
 * hysteresis. Uniform across all level transitions; multi-level fall
 * = step-down one level per dwell. See ADR-0019 §Decision #2.
 */
internal const val THERMAL_FALL_DWELL_MS: Long = 3_000L

/**
 * Milestone 3 (ADR-0019) — opaque hysteresis state held inside
 * [ThermalStatusSignal]. The `stable` field is what consumers see via
 * `state.value`; `dwellEnteredAtMs` is non-null only while a fall-dwell
 * is in flight.
 */
internal data class HysteresisState(
    val stable: ThermalStatus,
    val dwellEnteredAtMs: Long?,
)

/**
 * Milestone 3 (ADR-0019) — asymmetric thermal hysteresis. Pure
 * function; JVM-testable. Rise (`raw.ordinal > stable.ordinal`):
 * instant transition, clears any in-flight dwell. Equal (`raw == stable`):
 * clears any in-flight dwell (raw bounced back). Fall
 * (`raw.ordinal < stable.ordinal`): starts a [fallDwellMs] timer; on
 * expiry steps stable DOWN exactly ONE level. Multi-event lower-raw
 * during dwell does NOT restart the timer.
 */
internal fun applyThermalHysteresis(
    raw: ThermalStatus,
    current: HysteresisState,
    nowMs: Long,
    fallDwellMs: Long = THERMAL_FALL_DWELL_MS,
): HysteresisState {
    return current
}
```

`ThermalStatus` is already in scope (same package). No new imports needed.

- [ ] **Step 2: Write all 10 failing tests**

Create `app/src/test/java/com/aritr/rova/ui/signals/ThermalHysteresisTest.kt`:

```kotlin
package com.aritr.rova.ui.signals

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Milestone 3 (ADR-0019) — pure-JVM tests for [applyThermalHysteresis].
 * Covers the 10 cases enumerated in spec §7.1. No Android, no coroutines,
 * no Compose — same shape as
 * [com.aritr.rova.service.recovery.MergeFailureClassTest],
 * [com.aritr.rova.service.recovery.MergeRetryPolicyTest], and
 * [com.aritr.rova.ui.screens.RecordingFrameLayoutTest].
 */
class ThermalHysteresisTest {

    @Test fun `rise_instant_no_dwell — raw above stable transitions immediately`() {
        val current = HysteresisState(stable = ThermalStatus.MODERATE, dwellEnteredAtMs = null)
        val result = applyThermalHysteresis(
            raw = ThermalStatus.SEVERE,
            current = current,
            nowMs = 1000L,
        )
        assertEquals(ThermalStatus.SEVERE, result.stable)
        assertNull("rise clears any pending dwell", result.dwellEnteredAtMs)
    }

    @Test fun `rise_clears_inflight_dwell — raw above stable during fall-dwell discards dwell`() {
        val current = HysteresisState(stable = ThermalStatus.CRITICAL, dwellEnteredAtMs = 500L)
        val result = applyThermalHysteresis(
            raw = ThermalStatus.EMERGENCY,
            current = current,
            nowMs = 1500L,
        )
        assertEquals(ThermalStatus.EMERGENCY, result.stable)
        assertNull("in-flight dwell discarded on rise", result.dwellEnteredAtMs)
    }

    @Test fun `fall_starts_dwell — lower raw with no prior dwell starts timer`() {
        val current = HysteresisState(stable = ThermalStatus.SEVERE, dwellEnteredAtMs = null)
        val result = applyThermalHysteresis(
            raw = ThermalStatus.MODERATE,
            current = current,
            nowMs = 2000L,
        )
        assertEquals("stable unchanged during dwell", ThermalStatus.SEVERE, result.stable)
        assertEquals("dwell timer recorded", 2000L, result.dwellEnteredAtMs)
    }

    @Test fun `fall_holds_during_dwell — same lower raw mid-dwell holds timer`() {
        val current = HysteresisState(stable = ThermalStatus.SEVERE, dwellEnteredAtMs = 1000L)
        val result = applyThermalHysteresis(
            raw = ThermalStatus.MODERATE,
            current = current,
            nowMs = 2500L,
            fallDwellMs = 3_000L,
        )
        assertEquals(ThermalStatus.SEVERE, result.stable)
        assertEquals("timer not restarted on further lower-raw events", 1000L, result.dwellEnteredAtMs)
    }

    @Test fun `fall_holds_at_lower_raw_during_dwell — even lower raw mid-dwell still holds`() {
        val current = HysteresisState(stable = ThermalStatus.CRITICAL, dwellEnteredAtMs = 1000L)
        val result = applyThermalHysteresis(
            raw = ThermalStatus.LIGHT,
            current = current,
            nowMs = 2500L,
            fallDwellMs = 3_000L,
        )
        assertEquals("multi-level drop during dwell still holds at stable", ThermalStatus.CRITICAL, result.stable)
        assertEquals("original timer preserved", 1000L, result.dwellEnteredAtMs)
    }

    @Test fun `fall_completes_after_dwell_one_step_down — dwell expiry transitions exactly one level`() {
        val current = HysteresisState(stable = ThermalStatus.SEVERE, dwellEnteredAtMs = 1000L)
        val result = applyThermalHysteresis(
            raw = ThermalStatus.LIGHT,
            current = current,
            nowMs = 4000L,
            fallDwellMs = 3_000L,
        )
        assertEquals("step down ONE level (SEVERE -> MODERATE), not all the way to LIGHT",
            ThermalStatus.MODERATE, result.stable)
        assertNull("dwell timer cleared after expiry", result.dwellEnteredAtMs)
    }

    @Test fun `fall_completes_step_down_multi_drop_raw — multi-level raw stays one-step per dwell`() {
        // Simulate dwell expire with raw still at very low level — only ONE step transitions.
        val current = HysteresisState(stable = ThermalStatus.CRITICAL, dwellEnteredAtMs = 100L)
        val result = applyThermalHysteresis(
            raw = ThermalStatus.NONE,
            current = current,
            nowMs = 3100L,
            fallDwellMs = 3_000L,
        )
        assertEquals("CRITICAL -> SEVERE on first dwell, NOT CRITICAL -> NONE",
            ThermalStatus.SEVERE, result.stable)
        assertNull(result.dwellEnteredAtMs)
    }

    @Test fun `equal_raw_during_dwell_clears_dwell — raw equal to stable mid-dwell aborts dwell`() {
        val current = HysteresisState(stable = ThermalStatus.SEVERE, dwellEnteredAtMs = 1000L)
        val result = applyThermalHysteresis(
            raw = ThermalStatus.SEVERE,
            current = current,
            nowMs = 2500L,
        )
        assertEquals(ThermalStatus.SEVERE, result.stable)
        assertNull("equal-to-stable mid-dwell clears the dwell (raw bounced back)", result.dwellEnteredAtMs)
    }

    @Test fun `boundary_thrash_stays_stable — MOD-SEV-MOD-SEV flap collapses to stable plus restarted dwell`() {
        // Initial state: stable=SEVERE, no dwell.
        var s = HysteresisState(stable = ThermalStatus.SEVERE, dwellEnteredAtMs = null)
        // t=0: raw=MODERATE -> starts dwell.
        s = applyThermalHysteresis(ThermalStatus.MODERATE, s, nowMs = 0L)
        assertEquals(ThermalStatus.SEVERE, s.stable)
        assertEquals(0L, s.dwellEnteredAtMs)
        // t=500: raw=SEVERE -> clears dwell (equal-to-stable).
        s = applyThermalHysteresis(ThermalStatus.SEVERE, s, nowMs = 500L)
        assertEquals(ThermalStatus.SEVERE, s.stable)
        assertNull(s.dwellEnteredAtMs)
        // t=1000: raw=MODERATE -> starts NEW dwell (timer reset to 1000).
        s = applyThermalHysteresis(ThermalStatus.MODERATE, s, nowMs = 1000L)
        assertEquals(ThermalStatus.SEVERE, s.stable)
        assertEquals("new dwell starts after clear", 1000L, s.dwellEnteredAtMs)
    }

    @Test fun `defensive_negative_now_does_not_crash — negative nowMs is recorded as-is`() {
        val current = HysteresisState(stable = ThermalStatus.CRITICAL, dwellEnteredAtMs = null)
        val result = applyThermalHysteresis(
            raw = ThermalStatus.SEVERE,
            current = current,
            nowMs = -1L,
        )
        assertEquals(ThermalStatus.CRITICAL, result.stable)
        assertEquals(-1L, result.dwellEnteredAtMs)
    }
}
```

- [ ] **Step 3: Run tests to verify expected failures**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.signals.ThermalHysteresisTest"`

Expected: 9 of 10 tests FAIL (the only one that passes incidentally is `fall_holds_at_lower_raw_during_dwell`, because the skeleton returns `current` unchanged and that test expects `current` unchanged. All other tests expect state mutation.).

If MORE than one passes, the skeleton is wrong — recheck Step 1.

- [ ] **Step 4: Implement the state machine**

Replace the body of `applyThermalHysteresis` in `ThermalHysteresis.kt`:

```kotlin
internal fun applyThermalHysteresis(
    raw: ThermalStatus,
    current: HysteresisState,
    nowMs: Long,
    fallDwellMs: Long = THERMAL_FALL_DWELL_MS,
): HysteresisState {
    if (raw.ordinal > current.stable.ordinal) {
        return HysteresisState(stable = raw, dwellEnteredAtMs = null)
    }
    if (raw.ordinal == current.stable.ordinal) {
        return HysteresisState(stable = current.stable, dwellEnteredAtMs = null)
    }
    if (current.dwellEnteredAtMs == null) {
        return HysteresisState(stable = current.stable, dwellEnteredAtMs = nowMs)
    }
    if (nowMs - current.dwellEnteredAtMs >= fallDwellMs) {
        return HysteresisState(
            stable = ThermalStatus.entries[current.stable.ordinal - 1],
            dwellEnteredAtMs = null,
        )
    }
    return current
}
```

- [ ] **Step 5: Run tests to verify all 10 pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.signals.ThermalHysteresisTest"`

Expected: `10 tests completed, 0 failed`. If any fail, re-read spec §6.2 pseudocode and §6.3 edge-case table; align implementation.

- [ ] **Step 6: Run full unit-test suite (sanity — no regressions)**

Run: `./gradlew :app:testDebugUnitTest`

Expected: `1241 tests completed, 0 failed, 0 skipped` (1231 baseline + 10 new). If count is wrong, you may have accidentally added or skipped tests.

- [ ] **Step 7: Run lint (zero-delta check)**

Run: `./gradlew :app:lintDebug`

Expected: 0 errors, 51 warnings, 1 hint (matches `f213c73` baseline). The new pure-helper file should produce ZERO new lint findings (no Android imports, no UI, no Compose).

If new warnings appear, fix them in the helper file before commit (do not suppress unless you understand exactly which rule and why).

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/signals/ThermalHysteresis.kt \
        app/src/test/java/com/aritr/rova/ui/signals/ThermalHysteresisTest.kt
git commit -m "$(cat <<'EOF'
feat(thermal): pure-helper applyThermalHysteresis + 10 JVM tests (Milestone 3 Task 1)

Adds the pure state-machine for ADR-0019 asymmetric thermal hysteresis:
instant rise (safety), 3s dwell-gated fall (UX stability), step-down one
level per dwell. Pure JVM — no Android, Compose, or coroutines. Helper is
internal so JVM tests in the same module call directly.

10 tests cover the 6 code paths (rise / equal-no-dwell / fall-start-dwell /
fall-hold / fall-expire-step-down / equal-clears-dwell) plus boundary
thrash + multi-level fall (single step) + defensive negative-now.

No consumer yet — Task 2 wires this into ThermalStatusSignal.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2 — Wire helper into `ThermalStatusSignal`

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/signals/ThermalStatusSignal.kt`

This task makes the helper load-bearing. The signal's existing constructor seam pattern (`sdkInt`, `currentStatus`, `addListener`, `removeListener`) is preserved — hysteresis lives INSIDE the listener callback closure registered in `start()` plus a state-sync in `refresh()`. No constructor signature change. No new seams.

**Reference:** spec §6.4 shows a *simplified* signal sketch; the real signal already wraps `PowerManager` access in seam closures. Modify the existing class — do NOT replace it with the spec's simplified shape.

- [ ] **Step 1: Add `HysteresisState` field after the `_state` / `state` properties**

In `app/src/main/java/com/aritr/rova/ui/signals/ThermalStatusSignal.kt`, locate this block (currently at line 84-85):

```kotlin
    private val _state: MutableStateFlow<ThermalStatus> = MutableStateFlow(currentValue())
    val state: StateFlow<ThermalStatus> = _state.asStateFlow()
```

Add this line immediately after `val state = ...`:

```kotlin

    /**
     * Milestone 3 (ADR-0019) — hysteresis state held in-signal. `stable`
     * mirrors `_state.value` (invariant); `dwellEnteredAtMs` is non-null
     * only while a fall-dwell is in flight. Initial snapshot uses the
     * same [currentValue] read as `_state` to avoid NONE-flash on hot
     * launch.
     */
    private var hysteresisState: HysteresisState =
        HysteresisState(stable = _state.value, dwellEnteredAtMs = null)
```

Property initializers run top-to-bottom — `_state` is initialized first, then `hysteresisState` reads `_state.value`. Do not reorder these.

- [ ] **Step 2: Modify the listener callback inside `start()` to apply hysteresis**

Locate this block in `start()` (currently at lines 107-114):

```kotlin
    fun start() {
        if (sdkInt < Build.VERSION_CODES.Q) return
        if (registeredListenerToken != null) return
        val callback: (Int) -> Unit = { raw ->
            _state.value = ThermalStatus.fromRaw(raw)
        }
        registeredListenerToken = addListener(callback)
    }
```

Replace the inner `callback` lambda body. The new body calls `applyThermalHysteresis` and publishes the stable field:

```kotlin
    fun start() {
        if (sdkInt < Build.VERSION_CODES.Q) return
        if (registeredListenerToken != null) return
        val callback: (Int) -> Unit = { rawInt ->
            val raw = ThermalStatus.fromRaw(rawInt)
            val now = android.os.SystemClock.elapsedRealtime()
            hysteresisState = applyThermalHysteresis(raw, hysteresisState, now)
            _state.value = hysteresisState.stable
        }
        registeredListenerToken = addListener(callback)
    }
```

Add the import at the top of the file (alphabetical order — slot it between `android.os.PowerManager` and `androidx.annotation.RequiresApi`):

```kotlin
import android.os.SystemClock
```

After that import is added, change `android.os.SystemClock.elapsedRealtime()` in the callback to `SystemClock.elapsedRealtime()`.

`SystemClock.elapsedRealtime()` is the project standard for monotonic time (unaffected by wall-clock adjustments) — same choice as the rest of the codebase.

- [ ] **Step 3: Modify `refresh()` to keep hysteresis state in sync**

Locate `refresh()` (currently at lines 91-93):

```kotlin
    fun refresh() {
        _state.value = currentValue()
    }
```

Replace with:

```kotlin
    /**
     * Re-read the thermal status and publish if changed. Call from
     * `ON_RESUME`. Idempotent on unchanged status. Milestone 3 (ADR-0019):
     * resets the in-signal hysteresis state to match — refresh represents
     * the authoritative "current OS truth" and clears any in-flight dwell.
     */
    fun refresh() {
        val current = currentValue()
        _state.value = current
        hysteresisState = HysteresisState(stable = current, dwellEnteredAtMs = null)
    }
```

This preserves the existing refresh contract (re-read + publish) and adds a state-reset so the next listener event sees a hysteresis state consistent with `_state.value`. Without this, refresh would set `_state.value = NONE` while `hysteresisState.stable = CRITICAL` (or similar), and the next listener event would compare raw against a stale stable.

- [ ] **Step 4: Run the existing signal test suite to verify zero regressions**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.signals.ThermalStatusSignalTest"`

Expected: `13 tests completed, 0 failed`. Cross-check that ALL 13 existing tests pass without modification.

Why they stay green:
- `fromRaw` tests — unchanged, no signal interaction.
- `NONE on init when raw is 0` / `SEVERE on init when raw is 3` — initial value via `currentValue()` unchanged.
- `refresh flips state when raw changes` — refresh sets `_state.value = current` regardless of hysteresis (no dwell ever recorded because refresh resets dwell to null each call).
- `idempotent refresh emits exactly once for unchanged value` — `MutableStateFlow` dedupes equals.
- `refresh re-reads currentStatus on each call` — refresh still calls `currentValue()`.
- `pre-API-29 reports NONE and never invokes currentStatus` — early return in `currentValue()` for `sdkInt < Q`.
- `pre-API-29 ignores raw flips on refresh` — same.
- `start registers listener once on API 29 plus` — `start()` logic unchanged.
- `start is no-op pre-API-29` — `start()` early return unchanged.
- `stop unregisters listener and clears reference` — `stop()` unchanged.
- `listener emission updates state flow` — captured callback runs raw=3 (SEVERE) > stable=NONE → instant rise → SEVERE. Then raw=4 (CRITICAL) > stable=SEVERE → instant rise → CRITICAL. Both go through hysteresis but only via the rise branch.
- `listener emission distinctness dedupes equal values` — captured callback runs raw=2 (MODERATE) == stable=MODERATE three times. Each invocation hits the equal-clears-dwell branch; `_state.value` stays MODERATE. `MutableStateFlow` dedupes; collector sees `[MODERATE]`.

If ANY test fails, do not proceed — re-read the test, recheck the modification, and fix.

- [ ] **Step 5: Run full unit-test suite**

Run: `./gradlew :app:testDebugUnitTest`

Expected: `1241 tests completed, 0 failed, 0 skipped`. Test count unchanged from Task 1 (no new tests added in Task 2; signal-class tests stay at 13 per spec §7.2).

- [ ] **Step 6: Run lint (zero-delta vs Task 1)**

Run: `./gradlew :app:lintDebug`

Expected: `0 errors, 51 warnings, 1 hint`. Same as the `f213c73` baseline.

If a new warning appears (likely candidates: unused-import if `android.os.SystemClock` was misplaced, or NewApi if a thermal API is used outside an SDK guard), fix the underlying issue. Do not `@Suppress` unless you understand exactly which rule fires and why.

- [ ] **Step 7: Build assembleDebug (compile sanity)**

Run: `./gradlew :app:assembleDebug`

Expected: `BUILD SUCCESSFUL`. Pure Kotlin change in a single class — should compile cleanly.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/signals/ThermalStatusSignal.kt
git commit -m "$(cat <<'EOF'
feat(thermal): apply hysteresis inside ThermalStatusSignal listener + refresh (Milestone 3 Task 2)

Wires applyThermalHysteresis into the signal's existing seam-pattern
listener callback. State now emits hysteresis-stable values (ADR-0019).
Initial-state snapshot via the same currentValue() read used for _state —
no NONE-flash on hot launch. refresh() resets dwell to keep invariant
hysteresisState.stable == _state.value.

API surface unchanged. All 13 existing ThermalStatusSignalTest cases
remain green byte-identical (rise/equal sequences pass through the
hysteresis branches without behavior change).

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3 — Update `WarningCenterContract.md` references

**Files:**
- Modify: `docs/WarningCenterContract.md`

The contract was last updated 2026-05-24 with the deferral note "MutableStateFlow.distinctUntilChanged dedupe is sufficient." That assessment is now superseded by ADR-0019 (committed `f213c73`). Two spots reference the deferred design — both need updating to reflect the asymmetric model and link to the ADR.

Note: spec §6.5 listed "`WarningCenterContract.md §7`" — the actual deferral note is in **§10 #3** (line 431-432), and an earlier two-level-pattern paragraph lives in **§5** (line 192). The spec's "§7" cross-reference was a section-number drift; this task updates the correct two locations.

- [ ] **Step 1: Update the §5 "Hysteresis on Escalating" paragraph**

Locate this paragraph in `docs/WarningCenterContract.md` (currently line 192):

```markdown
**Hysteresis on Escalating.** Thermal status uses a two-level hysteresis to prevent flapping: enter banner at `MODERATE`, leave banner only when status is `LIGHT` or below. Same rule applies to "Storage running low" → "Storage full" transitions (different threshold each direction).
```

Replace with:

```markdown
**Hysteresis on Escalating.** Thermal status uses **asymmetric hysteresis** (ADR-0019, shipped Milestone 3): rise is instant (safety); fall is gated by a 3-second dwell per level (UX stability). Boundary flap (e.g. `MODERATE → SEVERE → MODERATE`) does NOT cause banner flicker — stable level holds until raw stays below for the full dwell. Storage thresholds use a separate, threshold-based mechanism (no shared design with thermal).
```

- [ ] **Step 2: Update the §10 #3 deferral note**

Locate this paragraph in `docs/WarningCenterContract.md` (currently lines 431-432):

```markdown
3. **Thermal hysteresis exact thresholds.** `MODERATE` to enter, `LIGHT` to leave is the proposed pattern. Confirm against `PowerManager.THERMAL_STATUS_*` and the device's typical climbing curve before Phase 3.4 ships.
**Update 2026-05-24 (Phase 4 Slice 3, ADR-0016):** the `THERMAL` `StopReason` shipped. Two-level hysteresis is **not** implemented — `MutableStateFlow.distinctUntilChanged` dedupe is judged sufficient for the slow thermal physics. The "exact thresholds" item stays parked as a future tightening if field data shows flap.
```

Replace with:

```markdown
3. **Thermal hysteresis exact thresholds.** `MODERATE` to enter, `LIGHT` to leave was the original proposed pattern. Superseded — see Milestone 3 below.
**Update 2026-05-24 (Phase 4 Slice 3, ADR-0016):** the `THERMAL` `StopReason` shipped. Two-level hysteresis was deferred — `MutableStateFlow.distinctUntilChanged` dedupe judged sufficient at the time.
**Update 2026-05-26 (Milestone 3, ADR-0019):** asymmetric hysteresis SHIPPED. Instant rise (safety-critical — auto-stop fires immediately on first CRITICAL event); 3-second dwell-gated fall (UX stability — boundary flap no longer flickers the banner). Step-down model: each level transition takes one dwell. Implementation in `app/src/main/java/com/aritr/rova/ui/signals/ThermalHysteresis.kt` (pure helper, 10 JVM tests). The "exact thresholds" question is moot — design is now ordinal-level transitions, not specific raw-status entry/exit thresholds.
```

- [ ] **Step 3: Verify edits via diff**

Run: `git diff docs/WarningCenterContract.md`

Expected: exactly the two paragraph replacements above (one in §5, one in §10 #3). No other lines modified. No trailing whitespace introduced.

- [ ] **Step 4: Commit**

```bash
git add docs/WarningCenterContract.md
git commit -m "$(cat <<'EOF'
docs(thermal): update WarningCenterContract to reference ADR-0019 (Milestone 3 Task 3)

Supersedes prior deferral note ("distinctUntilChanged sufficient") in
§10 #3 and the two-level-hysteresis description in §5. Both now point at
ADR-0019 asymmetric hysteresis (instant rise + 3s dwell-gated fall +
step-down one level per dwell) shipped in this milestone.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## End-of-milestone verification

After all three tasks commit cleanly, run the full suite one more time and inspect git log:

- [ ] **Final Step 1: Full test suite**

Run: `./gradlew :app:testDebugUnitTest`

Expected: `1241 tests completed, 0 failed, 0 skipped`.

- [ ] **Final Step 2: Lint**

Run: `./gradlew :app:lintDebug`

Expected: `0 errors, 51 warnings, 1 hint` (zero-delta vs `f213c73` baseline).

- [ ] **Final Step 3: Debug assemble**

Run: `./gradlew :app:assembleDebug`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Final Step 4: Commit log inspection**

Run: `git log --oneline f213c73..HEAD`

Expected: exactly 3 commits, in order:
1. `feat(thermal): pure-helper applyThermalHysteresis + 10 JVM tests (Milestone 3 Task 1)`
2. `feat(thermal): apply hysteresis inside ThermalStatusSignal listener + refresh (Milestone 3 Task 2)`
3. `docs(thermal): update WarningCenterContract to reference ADR-0019 (Milestone 3 Task 3)`

---

## Acceptance criteria (mirrors spec §10)

- ✅ `applyThermalHysteresis` pure helper: all 10 JVM tests pass.
- ✅ `ThermalStatusSignal._state.value` emits hysteresis-stable values (verified indirectly via existing listener-path tests + manual smoke on hardware later).
- ✅ Initial snapshot via existing `currentValue()` on construction — no NONE-flash on hot-device launch.
- ✅ Full test suite ≥1241 (1231 baseline + 10 new), 0 failed / 0 ignored / 0 skipped.
- ✅ Lint zero-delta vs `f213c73` baseline (0E / 51W / 1H).
- ✅ `gradlew assembleDebug` succeeds.
- ✅ ADR-0019 committed alongside implementation (already committed in `f213c73`).
- ✅ `WarningCenterContract.md` §5 + §10 #3 updated to reference ADR-0019.

## Hardware smoke checklist (deferred per owner pref — include in PR body)

Target device: Samsung SM-A176B (primary QA device).

1. Boot device with sustained CPU workload OR warm ambient. Launch app. Confirm initial banner reflects current OS thermal state, not NONE-flash. (Validates §5 #5 — initial-state snapshot.)
2. Cool device (rest 60s after heavy workload). Confirm banner steps down through levels with ~3s spacing per step. (Validates §5 #1 + #4 — asymmetric + step-down.)
3. Force boundary thrash (alternate workload bursts at temperature boundary). Confirm banner does NOT flicker — stays at higher level until 3s of sustained cooling. (Validates §5 #1 — dwell gating.)
4. Force fast spike (sudden heavy workload → instant CRITICAL). Confirm auto-stop fires immediately, no rise lag. (Validates §5 #1 — instant rise; §5 #3 — `SegmentGateThermal` sees stable status which transitions instantly on rise.)
