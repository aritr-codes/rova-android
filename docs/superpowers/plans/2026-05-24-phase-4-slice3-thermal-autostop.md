# Phase 4 Slice 3 — Thermal Auto-Stop + Echo + Tips Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Detect thermal escalation during active recording via a push listener; auto-stop at `CRITICAL`+ via a new Layer-4 segment gate; surface a `THERMAL_AUTOSTOPPED` echo banner on next Idle whose primary CTA opens a static "Tips to cool down" bottom sheet.

**Architecture:** Direct mirror of Phase 4 Slice 2 (`STORAGE_FULL_AUTOSTOPPED` echo). New `StopReason.THERMAL`; `ThermalStatusSignal` gains a push-listener lifecycle (`start()` / `stop()` + two new ctor seams); `RovaRecordingService.checkSegmentGates` gains a thermal Layer 4 extracted to a pure helper (`SegmentGateThermal`) for JVM testability; `WarningPrecedence` echo arm rewritten as a `when` over `StopReason`; new `WarningId.THERMAL_AUTOSTOPPED`; new `ThermalTipsSheet` composable; `WarningCenter` Idle TopBanner special-case generalized from one-arm to two-arm. Slice 2 plumbing (`SessionAutoStopEchoSignal`, `TerminalEcho`, `dismissedAutoStopEchoIds`, the echo factory) is **reused unchanged** — the signal is already reason-agnostic.

**Tech Stack:** Kotlin, Jetpack Compose Material3, AndroidX Lifecycle, kotlinx-coroutines StateFlow, JUnit4 (pure-JVM per ADR-0007). Android API 24+ (push listener gated to API 29+).

**Spec:** `docs/superpowers/specs/2026-05-24-phase-4-slice3-thermal-autostop-design.md`

---

## File Structure

| Action | File | Responsibility |
|---|---|---|
| Modify | `app/src/main/java/com/aritr/rova/data/SessionManifest.kt` | Add `StopReason.THERMAL` enum value. |
| Modify | `app/src/main/java/com/aritr/rova/ui/signals/ThermalStatusSignal.kt` | Add `start()` / `stop()` lifecycle wrapping `PowerManager.addThermalStatusListener` (API 29+) via two new ctor seams. |
| Modify | `app/src/main/java/com/aritr/rova/ui/warnings/WarningId.kt` | Insert `THERMAL_AUTOSTOPPED` at slot #13. |
| Modify | `app/src/main/java/com/aritr/rova/ui/warnings/WarningPrecedence.kt` | Rewrite Slice-2 echo arm as `when (stopReason)` mapping. |
| Modify | `app/src/main/java/com/aritr/rova/ui/warnings/WarningSheetContent.kt` | Add `ActionTarget.OPEN_THERMAL_TIPS`; defensive `warningSheetContent` arm; `midRecBannerContent` arm; `WarningSurface.TopBanner` mapping. |
| Create | `app/src/main/java/com/aritr/rova/ui/warnings/ThermalTipsSheet.kt` | New ModalBottomSheet composable; 5 static bullets + "Got it". |
| Create | `app/src/main/java/com/aritr/rova/service/SegmentGateThermal.kt` | Pure helper for Layer-4 thermal threshold check. |
| Modify | `app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt` | Add Layer-4 call site in `checkSegmentGates`; add THERMAL arm to notification-copy `when`. |
| Modify | `app/src/main/java/com/aritr/rova/ui/warnings/WarningCenter.kt` | Generalize Idle TopBanner special-case from one-arm to two-arm `when`; add `onOpenThermalTips` param. |
| Modify | `app/src/main/java/com/aritr/rova/RovaApp.kt` | Call `thermalStatusSignal.start()` in `onCreate`. |
| Modify | `app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt` | Host `showTipsSheet` state; pass `onOpenThermalTips` to both WarningCenter mounts. |
| Modify | `app/src/test/java/com/aritr/rova/ui/signals/ThermalStatusSignalTest.kt` | 5 new tests for push-listener lifecycle. |
| Create | `app/src/test/java/com/aritr/rova/service/SegmentGateThermalTest.kt` | 3 tests for the threshold helper. |
| Modify | `app/src/test/java/com/aritr/rova/ui/warnings/WarningPrecedenceTest.kt` | 2 new tests (THERMAL resolves + LOW_STORAGE regression). |
| Modify | `app/src/test/java/com/aritr/rova/ui/warnings/WarningCenterAggregateTest.kt` | 2 new tests (THERMAL_AUTOSTOPPED aggregate + LOW_STORAGE regression). |
| Modify | `app/src/test/java/com/aritr/rova/ui/warnings/WarningIdOrderTest.kt` | Add `THERMAL_AUTOSTOPPED` to ordinal + tier pins. |
| Modify | `app/src/test/java/com/aritr/rova/ui/warnings/WarningSurfaceTest.kt` | Add `THERMAL_AUTOSTOPPED` to TopBanner cluster. |
| Modify | `app/src/test/java/com/aritr/rova/ui/warnings/MidRecBannerContentTest.kt` | Bump count from 11 → 12; add THERMAL_AUTOSTOPPED copy assertion. |
| Create | `docs/adr/0016-thermal-autostop.md` | ADR documenting threshold, between-segment trigger, listener lifecycle. |
| Modify | `docs/WarningCenterContract.md` | Amend §4.3 C3.5 ("missing" → "shipped Slice 3, ADR-0016"); close §7 open question. |

**Branch:** `phase4-slice3-thermal-autostop`. Single PR. Each task = one commit (TDD per file). Pure-JVM JUnit4 tests only — no Robolectric, no Compose-UI, no kotlinx-coroutines-test (matches ADR-0007 + Slice 2 precedent).

---

## Pre-flight (do this first)

- [ ] **Verify clean baseline.** Master is `b9d4773` (Slice 2 merge). Branch from master.

```bash
git fetch origin master
git checkout master
git pull --ff-only
git checkout -b phase4-slice3-thermal-autostop
```

- [ ] **Verify baseline test count + build green.** Dispatch subagent to run `gradlew testDebugUnitTest assembleDebug`. Expected: 1068 tests / 0 failures (1058 baseline + 10 from Slice 2). Record exact number — used to verify additions don't break existing tests.

---

## Task 1: Add `StopReason.THERMAL`

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/data/SessionManifest.kt:359-365`

**Why this task is small:** Pure additive enum change. Compiler will surface every exhaustive `when (StopReason)` site that needs an arm — those are addressed in Task 8 (service notification copy). No new test file; the existing `StopReason` is consumed by tests that pass it through unchanged.

- [ ] **Step 1: Read the current enum.**

```
Read app/src/main/java/com/aritr/rova/data/SessionManifest.kt lines 340-365 to see the
KDoc above the enum and the existing values.
```

- [ ] **Step 2: Add THERMAL value + KDoc row.**

Edit `app/src/main/java/com/aritr/rova/data/SessionManifest.kt`. Find:

```kotlin
enum class StopReason {
    USER,
    LOW_STORAGE,
    PERMISSION_REVOKED,
    INIT_FAILED,
    NONE
}
```

Replace with:

```kotlin
enum class StopReason {
    USER,
    LOW_STORAGE,
    PERMISSION_REVOKED,
    INIT_FAILED,
    /**
     * Phase 4 Slice 3 — service Layer-4 thermal gate fires when
     * `ThermalStatusSignal.state.value` is at or above
     * `ThermalStatus.CRITICAL`. The eager-write contract writes
     * `Terminated.USER_STOPPED` + this reason atomically (B3).
     * See ADR-0016.
     */
    THERMAL,
    NONE
}
```

Update the KDoc above the enum (around line 340-358 — the doc comment that mentions `USER_STOPPED from RovaRecordingService low-storage gate → LOW_STORAGE`). Add one row:

```
 * - [USER_STOPPED] from `RovaRecordingService` thermal gate → [THERMAL].
```

- [ ] **Step 3: Run the full unit-test suite via subagent.**

Dispatch subagent:

```
Run: gradlew testDebugUnitTest
Expected: all existing tests still pass (1068 → still 1068; no new tests
yet). If any pure-JVM exhaustive-when over StopReason fails to compile,
record the file:line and stop — that's an unexpected exhaustive-when
site outside the service.
```

Expected: no compile failures (no pure-JVM consumer of `StopReason` uses an exhaustive `when` today; the service site is handled in Task 8).

- [ ] **Step 4: Commit.**

```bash
git add app/src/main/java/com/aritr/rova/data/SessionManifest.kt
git commit -m "feat(data): add StopReason.THERMAL (Phase 4 Slice 3 T1)

Additive enum value for the upcoming thermal auto-stop path
(see docs/superpowers/specs/2026-05-24-phase-4-slice3-thermal-autostop-design.md §4.1).
Eager-write contract (B3) writes USER_STOPPED + THERMAL atomically.
Notification copy arm + service Layer-4 call site land in T8.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 2: `ThermalStatusSignal` push listener lifecycle

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/signals/ThermalStatusSignal.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/signals/ThermalStatusSignalTest.kt`

**Why:** ON_RESUME-only polling is unusable during recording — the user is staring at the active HUD. Push listener delivers real-time updates so the SEVERE banner reacts during recording and the Layer-4 gate reads the latest value.

- [ ] **Step 1: Write 5 failing tests.**

Append to `app/src/test/java/com/aritr/rova/ui/signals/ThermalStatusSignalTest.kt` (before the closing `}` of the class):

```kotlin
    // ── Phase 4 Slice 3 — push-listener lifecycle ──

    @Test fun `start registers listener once on API 29 plus`() {
        var registerCalls = 0
        val signal = ThermalStatusSignal(
            sdkInt = 29,
            currentStatus = { 0 },
            addListener = { registerCalls++ },
            removeListener = {},
        )
        signal.start()
        assertEquals(1, registerCalls)
        signal.start()
        assertEquals("second start is idempotent", 1, registerCalls)
    }

    @Test fun `start is no-op pre-API-29`() {
        var registerCalls = 0
        val signal = ThermalStatusSignal(
            sdkInt = 28,
            currentStatus = { 0 },
            addListener = { registerCalls++ },
            removeListener = {},
        )
        signal.start()
        assertEquals(0, registerCalls)
    }

    @Test fun `stop unregisters listener and clears reference`() {
        var registerCalls = 0
        var unregisterCalls = 0
        val signal = ThermalStatusSignal(
            sdkInt = 29,
            currentStatus = { 0 },
            addListener = { registerCalls++ },
            removeListener = { unregisterCalls++ },
        )
        signal.start()
        signal.stop()
        assertEquals(1, unregisterCalls)
        signal.stop()
        assertEquals("stop after stop is idempotent", 1, unregisterCalls)
    }

    @Test fun `listener emission updates state flow`() {
        var captured: android.os.PowerManager.OnThermalStatusListener? = null
        val signal = ThermalStatusSignal(
            sdkInt = 29,
            currentStatus = { 0 },
            addListener = { l -> captured = l },
            removeListener = {},
        )
        signal.start()
        assertEquals(ThermalStatus.NONE, signal.state.value)
        captured!!.onThermalStatusChanged(3)
        assertEquals(ThermalStatus.SEVERE, signal.state.value)
        captured!!.onThermalStatusChanged(4)
        assertEquals(ThermalStatus.CRITICAL, signal.state.value)
    }

    @Test fun `listener emission distinctness dedupes equal values`() = runBlocking {
        var captured: android.os.PowerManager.OnThermalStatusListener? = null
        val signal = ThermalStatusSignal(
            sdkInt = 29,
            currentStatus = { 2 },
            addListener = { l -> captured = l },
            removeListener = {},
        )
        signal.start()
        val emissions = mutableListOf<ThermalStatus>()
        val job = launch(Dispatchers.Unconfined) {
            signal.state.collect { emissions += it }
        }
        captured!!.onThermalStatusChanged(2)
        captured!!.onThermalStatusChanged(2)
        captured!!.onThermalStatusChanged(2)
        yield()
        job.cancelAndJoin()
        assertEquals(listOf(ThermalStatus.MODERATE), emissions)
    }
```

- [ ] **Step 2: Run tests — verify they fail.**

Dispatch subagent:

```
Run: gradlew testDebugUnitTest --tests 'com.aritr.rova.ui.signals.ThermalStatusSignalTest'
Expected: 5 new tests fail to compile (the addListener / removeListener
ctor params do not exist yet; start() / stop() methods do not exist).
```

- [ ] **Step 3: Add the ctor seams + lifecycle methods.**

Edit `app/src/main/java/com/aritr/rova/ui/signals/ThermalStatusSignal.kt`. Replace the class header:

```kotlin
class ThermalStatusSignal(
    private val sdkInt: Int,
    private val currentStatus: () -> Int
) {
```

with:

```kotlin
class ThermalStatusSignal(
    private val sdkInt: Int,
    private val currentStatus: () -> Int,
    /**
     * Phase 4 Slice 3 — register a [PowerManager.OnThermalStatusListener].
     * Default no-op preserves pre-Slice-3 call sites (in particular, every
     * existing ThermalStatusSignalTest fixture). Production wiring in
     * [forContext] calls `pm.addThermalStatusListener(handler::run, l)`
     * inside an inner SDK guard.
     */
    private val addListener: (android.os.PowerManager.OnThermalStatusListener) -> Unit = {},
    /**
     * Phase 4 Slice 3 — unregister the listener captured by [start]. Default
     * no-op symmetric with [addListener].
     */
    private val removeListener: (android.os.PowerManager.OnThermalStatusListener) -> Unit = {},
) {
```

Then add the lifecycle methods inside the class, just after the `refresh()` function and before `currentValue()`:

```kotlin
    private var registeredListener: android.os.PowerManager.OnThermalStatusListener? = null

    /**
     * Phase 4 Slice 3 — begin receiving real-time thermal updates.
     * Idempotent; pre-API-29 no-op.
     *
     * Call from `RovaApp.onCreate` (process-scoped). The OS releases the
     * registration on process death, so no explicit [stop] from app
     * teardown is required (Android does not reliably invoke
     * Application.onTerminate on production devices).
     */
    fun start() {
        if (sdkInt < Build.VERSION_CODES.Q) return
        if (registeredListener != null) return
        val l = android.os.PowerManager.OnThermalStatusListener { raw ->
            _state.value = ThermalStatus.fromRaw(raw)
        }
        addListener(l)
        registeredListener = l
    }

    /**
     * Phase 4 Slice 3 — unregister the listener captured by [start].
     * Idempotent. Defensive — production never calls this (process death
     * does the cleanup), but tests use it to assert teardown.
     */
    fun stop() {
        val l = registeredListener ?: return
        removeListener(l)
        registeredListener = null
    }
```

Then update the `forContext` factory at the bottom of the file. Find the `return ThermalStatusSignal(` block, and replace with:

```kotlin
            return ThermalStatusSignal(
                sdkInt = Build.VERSION.SDK_INT,
                currentStatus = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        pm.currentThermalStatus
                    } else {
                        0
                    }
                },
                addListener = { l ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val handler = android.os.Handler(android.os.Looper.getMainLooper())
                        runCatching { pm.addThermalStatusListener(handler::post, l) }
                            .onFailure { com.aritr.rova.utils.RovaLog.w("ThermalStatusSignal.addListener threw; falling back to ON_RESUME poll", it) }
                    }
                },
                removeListener = { l ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        runCatching { pm.removeThermalStatusListener(l) }
                            .onFailure { com.aritr.rova.utils.RovaLog.w("ThermalStatusSignal.removeListener threw", it) }
                    }
                }
            )
```

- [ ] **Step 4: Run tests — verify they pass.**

Dispatch subagent:

```
Run: gradlew testDebugUnitTest --tests 'com.aritr.rova.ui.signals.ThermalStatusSignalTest'
Expected: all 12 tests pass (7 existing + 5 new).
```

- [ ] **Step 5: Commit.**

```bash
git add app/src/main/java/com/aritr/rova/ui/signals/ThermalStatusSignal.kt \
        app/src/test/java/com/aritr/rova/ui/signals/ThermalStatusSignalTest.kt
git commit -m "feat(signals): push listener lifecycle on ThermalStatusSignal (Phase 4 Slice 3 T2)

Adds start()/stop() backed by PowerManager.addThermalStatusListener
(API 29+) via two new ctor seams. Defaults preserve every existing call
site. Production registers on Application.onCreate (T9). Listener fires
on the main thread via Handler.post so _state.value writes are safe.
runCatching wrap on add/remove degrades to ON_RESUME polling if a vendor
SDK throws (spec §6).

5 new pure-JVM tests cover: idempotent start (API 29+), pre-API-29 no-op,
idempotent stop, listener-driven state updates, StateFlow dedupe.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 3: `WarningId.THERMAL_AUTOSTOPPED` insertion

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/warnings/WarningId.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/warnings/WarningIdOrderTest.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/warnings/WarningSurfaceTest.kt`

**Why:** Adds the new banner id. Must update both pin tests in the same commit so the build stays green between commits (the order-pin asserts exact entries; surface-test's exhaustive `when` won't compile without an arm).

- [ ] **Step 1: Update the failing-test expectations first (TDD).**

Edit `app/src/test/java/com/aritr/rova/ui/warnings/WarningIdOrderTest.kt`. In the `declaration order matches the locked precedence table` test, replace:

```kotlin
                "STORAGE_FULL_AUTOSTOPPED",  // #12 — NEW (Phase 4 Slice 2)
                "THERMAL_SEVERE",            // #13
                "MICROPHONE_DENIED",         // #14
                "BATTERY_OPTIMIZATION_ON",   // #15
                "POWER_SAVE_MODE",           // #16
                "THERMAL_MODERATE",          // #17
                "NOTIFICATIONS_DENIED"       // #18
```

with:

```kotlin
                "STORAGE_FULL_AUTOSTOPPED",  // #12 — (Phase 4 Slice 2)
                "THERMAL_AUTOSTOPPED",       // #13 — NEW (Phase 4 Slice 3)
                "THERMAL_SEVERE",            // #14
                "MICROPHONE_DENIED",         // #15
                "BATTERY_OPTIMIZATION_ON",   // #16
                "POWER_SAVE_MODE",           // #17
                "THERMAL_MODERATE",          // #18
                "NOTIFICATIONS_DENIED"       // #19
```

In the `tiers are as specified` test, add one line right after `assertEquals(WarningTier.ADVISORY, WarningId.STORAGE_FULL_AUTOSTOPPED.tier)`:

```kotlin
        assertEquals(WarningTier.ADVISORY, WarningId.THERMAL_AUTOSTOPPED.tier)
```

Edit `app/src/test/java/com/aritr/rova/ui/warnings/WarningSurfaceTest.kt`. In the `expectedSurface` helper, replace the TopBanner arm:

```kotlin
        WarningId.THERMAL_SHUTDOWN, WarningId.THERMAL_EMERGENCY, WarningId.THERMAL_CRITICAL, WarningId.THERMAL_SEVERE, WarningId.THERMAL_MODERATE,
        WarningId.BATTERY_CRITICAL, WarningId.BATTERY_LOW, WarningId.CAMERA_IN_USE, WarningId.CAMERA_DISABLED,
        WarningId.STORAGE_LOW_MID_REC,
        WarningId.STORAGE_FULL_AUTOSTOPPED -> WarningSurface.TopBanner       // ← NEW arm (Phase 4 Slice 2)
```

with:

```kotlin
        WarningId.THERMAL_SHUTDOWN, WarningId.THERMAL_EMERGENCY, WarningId.THERMAL_CRITICAL, WarningId.THERMAL_SEVERE, WarningId.THERMAL_MODERATE,
        WarningId.BATTERY_CRITICAL, WarningId.BATTERY_LOW, WarningId.CAMERA_IN_USE, WarningId.CAMERA_DISABLED,
        WarningId.STORAGE_LOW_MID_REC,
        WarningId.STORAGE_FULL_AUTOSTOPPED,
        WarningId.THERMAL_AUTOSTOPPED -> WarningSurface.TopBanner             // ← NEW arm (Phase 4 Slice 3)
```

- [ ] **Step 2: Run tests — verify they fail.**

Dispatch subagent:

```
Run: gradlew testDebugUnitTest --tests 'com.aritr.rova.ui.warnings.WarningIdOrderTest' \
              --tests 'com.aritr.rova.ui.warnings.WarningSurfaceTest'
Expected: compile failure ("Unresolved reference: THERMAL_AUTOSTOPPED").
```

- [ ] **Step 3: Insert the enum value.**

Edit `app/src/main/java/com/aritr/rova/ui/warnings/WarningId.kt`. Update the KDoc paragraph that says "rows 1..18. As of Phase 4.1b all 16 rows" → "rows 1..19. As of Phase 4 Slice 3 all 17 rows" (the "16 rows" was already stale by Slice 2; let it become "17 rows" now that THERMAL_AUTOSTOPPED ships).

Then in the enum body, find:

```kotlin
    STORAGE_FULL_AUTOSTOPPED(WarningTier.ADVISORY),     // #12  ← NEW (Phase 4 Slice 2 — echo of past auto-stop)
    THERMAL_SEVERE(WarningTier.ADVISORY),               // #13
```

Replace with:

```kotlin
    STORAGE_FULL_AUTOSTOPPED(WarningTier.ADVISORY),     // #12  ← (Phase 4 Slice 2 — echo of past auto-stop)
    THERMAL_AUTOSTOPPED(WarningTier.ADVISORY),          // #13  ← NEW (Phase 4 Slice 3 — echo of thermal auto-stop)
    THERMAL_SEVERE(WarningTier.ADVISORY),               // #14
```

And shift the trailing comments:

```kotlin
    MICROPHONE_DENIED(WarningTier.ADVISORY),            // #15
    BATTERY_OPTIMIZATION_ON(WarningTier.ADVISORY),      // #16
    POWER_SAVE_MODE(WarningTier.ADVISORY),              // #17
    THERMAL_MODERATE(WarningTier.ADVISORY),             // #18
    NOTIFICATIONS_DENIED(WarningTier.ADVISORY)          // #19
```

- [ ] **Step 4: Run tests — verify they pass.**

Dispatch subagent:

```
Run: gradlew testDebugUnitTest --tests 'com.aritr.rova.ui.warnings.WarningIdOrderTest' \
              --tests 'com.aritr.rova.ui.warnings.WarningSurfaceTest'
Expected: pass.
```

- [ ] **Step 5: Commit.**

```bash
git add app/src/main/java/com/aritr/rova/ui/warnings/WarningId.kt \
        app/src/test/java/com/aritr/rova/ui/warnings/WarningIdOrderTest.kt \
        app/src/test/java/com/aritr/rova/ui/warnings/WarningSurfaceTest.kt
git commit -m "feat(warnings): add WarningId.THERMAL_AUTOSTOPPED (Phase 4 Slice 3 T3)

New advisory-tier WarningId at slot #13 between STORAGE_FULL_AUTOSTOPPED
and THERMAL_SEVERE. Mirrors Slice 2 echo-banner pattern for the thermal
auto-stop path landing in T8. WarningSurface = TopBanner.

Pins the new ordinal in WarningIdOrderTest + adds the TopBanner cluster
arm in WarningSurfaceTest.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 4: `WarningPrecedence` echo arm + aggregate tests

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/warnings/WarningPrecedence.kt:79-81`
- Test: `app/src/test/java/com/aritr/rova/ui/warnings/WarningPrecedenceTest.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/warnings/WarningCenterAggregateTest.kt`

**Why:** Rewrites the Slice 2 echo branch to a `when (stopReason)` mapping so both LOW_STORAGE and THERMAL terminal manifests resolve to their respective echo ids. `SessionAutoStopEchoSignal` is already reason-agnostic (spec §4.7) — only the precedence arm needs to change. Aggregate tests also need new coverage since the same code path drives the VM-level `activeWarning` flow.

- [ ] **Step 1: Write failing precedence tests.**

Append to `app/src/test/java/com/aritr/rova/ui/warnings/WarningPrecedenceTest.kt` (before the final closing brace):

```kotlin
    // ── Phase 4 Slice 3 — THERMAL_AUTOSTOPPED (ordinal 12, row #13) ──

    @Test fun `THERMAL_AUTOSTOPPED fires when autoStopEcho is THERMAL and no higher-priority signal active`() {
        val resolved = WarningPrecedence.resolve(
            cameraPermissionGranted = true,
            exactAlarmGranted = true,
            storageInsufficient = false,
            thermal = ThermalStatus.NONE,
            power = PowerState(percent = 80, charging = false, powerSaveMode = false),
            camera = CameraSignalState.OK,
            microphonePermissionGranted = true,
            notificationsGranted = true,
            batteryOptimizationExempt = true,
            storageLowMidRec = false,
            autoStopEcho = TerminalEcho("session-t", StopReason.THERMAL),
        )
        assertEquals(WarningId.THERMAL_AUTOSTOPPED, resolved)
    }

    @Test fun `LOW_STORAGE echo still resolves STORAGE_FULL_AUTOSTOPPED after when-rewrite`() {
        val resolved = WarningPrecedence.resolve(
            cameraPermissionGranted = true,
            exactAlarmGranted = true,
            storageInsufficient = false,
            thermal = ThermalStatus.NONE,
            power = PowerState(percent = 80, charging = false, powerSaveMode = false),
            camera = CameraSignalState.OK,
            microphonePermissionGranted = true,
            notificationsGranted = true,
            batteryOptimizationExempt = true,
            storageLowMidRec = false,
            autoStopEcho = TerminalEcho("session-s", StopReason.LOW_STORAGE),
        )
        assertEquals(WarningId.STORAGE_FULL_AUTOSTOPPED, resolved)
    }
```

- [ ] **Step 2: Run tests — verify they fail.**

Dispatch subagent:

```
Run: gradlew testDebugUnitTest --tests 'com.aritr.rova.ui.warnings.WarningPrecedenceTest'
Expected: 2 new tests fail. The THERMAL one returns `null`; the LOW_STORAGE
regression already passes (sanity).
```

- [ ] **Step 3: Rewrite the precedence echo arm.**

Edit `app/src/main/java/com/aritr/rova/ui/warnings/WarningPrecedence.kt`. Find:

```kotlin
        // #12 — STORAGE_FULL_AUTOSTOPPED (Phase 4 Slice 2; LOW_STORAGE-filtered echo of past auto-stop)
        autoStopEcho?.takeIf { it.stopReason == StopReason.LOW_STORAGE }
            ?.let { return WarningId.STORAGE_FULL_AUTOSTOPPED }
```

Replace with:

```kotlin
        // #12-13 — auto-stop echoes. Slice 2: LOW_STORAGE → STORAGE_FULL_AUTOSTOPPED.
        // Slice 3: THERMAL → THERMAL_AUTOSTOPPED. Other StopReasons (USER,
        // PERMISSION_REVOKED, INIT_FAILED, NONE) do not yield an echo banner —
        // a user-driven stop is not a surprise to surface, and the other
        // reasons either pre-empt the start path or have no banner contract.
        autoStopEcho?.let { echo ->
            when (echo.stopReason) {
                StopReason.LOW_STORAGE -> return WarningId.STORAGE_FULL_AUTOSTOPPED
                StopReason.THERMAL -> return WarningId.THERMAL_AUTOSTOPPED
                StopReason.USER, StopReason.PERMISSION_REVOKED,
                StopReason.INIT_FAILED, StopReason.NONE -> Unit
            }
        }
```

- [ ] **Step 4: Run precedence tests — verify they pass.**

Dispatch subagent:

```
Run: gradlew testDebugUnitTest --tests 'com.aritr.rova.ui.warnings.WarningPrecedenceTest'
Expected: all tests pass (existing Slice-2 regression + 2 new Slice-3 tests).
```

- [ ] **Step 5: Write failing aggregate tests.**

The existing file uses a `sources()` factory returning `ElevenSources` (a `data class` of MutableStateFlows including `autoStopEcho: MutableStateFlow<TerminalEcho?>`), plus a Slice-2 test `autoStopEcho_source_flow_drives_STORAGE_FULL_AUTOSTOPPED_into_activeWarning` (around line 267) that constructs the VM inline with `scope = CoroutineScope(Dispatchers.Unconfined)`.

Append to `app/src/test/java/com/aritr/rova/ui/warnings/WarningCenterAggregateTest.kt`, just before the final closing brace of the class (after the `dismissAutoStopEcho_mutator_invokes_onAutoStopDismissed_callback_with_sessionId` test):

```kotlin
    // ──────────────────────────────────────────────────────────────────
    // Phase 4 Slice 3 — THERMAL_AUTOSTOPPED aggregate
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun autoStopEcho_source_flow_drives_THERMAL_AUTOSTOPPED_into_activeWarning() {
        val s = sources()
        s.autoStopEcho.value = TerminalEcho("session-t", StopReason.THERMAL)
        val vm = WarningCenterViewModel(
            cameraPermissionGranted = s.cameraPerm,
            exactAlarmGranted = s.ea,
            storageInsufficient = s.storage,
            thermal = s.th,
            power = s.pw,
            camera = s.camState,
            microphonePermissionGranted = s.mic,
            notificationsGranted = s.nt,
            batteryOptimizationExempt = s.bo,
            storageLowMidRec = s.storageLowMidRec,
            autoStopEcho = s.autoStopEcho,
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        assertEquals(WarningId.THERMAL_AUTOSTOPPED, vm.activeWarning.value)
    }

    @Test
    fun autoStopEcho_LOW_STORAGE_still_drives_STORAGE_FULL_AUTOSTOPPED_after_when_rewrite() {
        // Regression for the Slice-2 echo behavior after T4's when-arm rewrite.
        // Distinct test from the Slice-2 one above so a failure points at the
        // rewrite, not the original Slice-2 wiring.
        val s = sources()
        s.autoStopEcho.value = TerminalEcho("session-s2", StopReason.LOW_STORAGE)
        val vm = WarningCenterViewModel(
            cameraPermissionGranted = s.cameraPerm,
            exactAlarmGranted = s.ea,
            storageInsufficient = s.storage,
            thermal = s.th,
            power = s.pw,
            camera = s.camState,
            microphonePermissionGranted = s.mic,
            notificationsGranted = s.nt,
            batteryOptimizationExempt = s.bo,
            storageLowMidRec = s.storageLowMidRec,
            autoStopEcho = s.autoStopEcho,
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        assertEquals(WarningId.STORAGE_FULL_AUTOSTOPPED, vm.activeWarning.value)
    }
```

This mirrors the existing Slice-2 fixture verbatim — same VM construction, same `Dispatchers.Unconfined` scope, same direct `activeWarning.value` read (no `.first { ... }` needed because the `Dispatchers.Unconfined` scope makes `stateIn` resolve synchronously on the caller thread — that's the whole point of that scope choice in this test file).

- [ ] **Step 6: Run aggregate tests — verify they fail.**

Dispatch subagent:

```
Run: gradlew testDebugUnitTest --tests 'com.aritr.rova.ui.warnings.WarningCenterAggregateTest'
Expected: the new THERMAL_AUTOSTOPPED aggregate test fails; the LOW_STORAGE
regression passes.
```

- [ ] **Step 7: Run aggregate tests again to verify the precedence change covers both.**

Dispatch subagent:

```
Run: gradlew testDebugUnitTest --tests 'com.aritr.rova.ui.warnings.WarningCenterAggregateTest'
Expected: both new tests pass. (The precedence change from Step 3 already
covers the aggregate path because WarningCenterViewModel.aggregate delegates
to WarningPrecedence.resolve.)
```

- [ ] **Step 8: Commit.**

```bash
git add app/src/main/java/com/aritr/rova/ui/warnings/WarningPrecedence.kt \
        app/src/test/java/com/aritr/rova/ui/warnings/WarningPrecedenceTest.kt \
        app/src/test/java/com/aritr/rova/ui/warnings/WarningCenterAggregateTest.kt
git commit -m "feat(warnings): map StopReason.THERMAL to THERMAL_AUTOSTOPPED (Phase 4 Slice 3 T4)

Rewrites the Slice-2 single-id echo branch as an exhaustive when over
StopReason. LOW_STORAGE keeps mapping to STORAGE_FULL_AUTOSTOPPED;
THERMAL maps to the new THERMAL_AUTOSTOPPED. Other reasons (USER,
PERMISSION_REVOKED, INIT_FAILED, NONE) intentionally yield no echo
banner — user-driven stops are not a surprise.

SessionAutoStopEchoSignal stays unchanged (reason-agnostic per its KDoc).

4 new pure-JVM tests: 2 precedence (THERMAL resolves, LOW_STORAGE
regression) + 2 aggregate (VM-level THERMAL emission, LOW_STORAGE
regression).

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 5: `WarningSheetContent` — action target + content arm + defensive sheet arm

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/warnings/WarningSheetContent.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/warnings/MidRecBannerContentTest.kt`

**Why:** Adds the new banner copy + the OPEN_THERMAL_TIPS action target consumed by the WarningCenter routing in Task 9. Also adds defensive `warningSheetContent` arm so the existing exhaustive `when` over `WarningId` still compiles.

- [ ] **Step 1: Update failing test expectations first.**

Edit `app/src/test/java/com/aritr/rova/ui/warnings/MidRecBannerContentTest.kt`. Replace:

```kotlin
    @Test fun every_mid_rec_id_returns_nonblank_content() {
        assertEquals("expected 11 TopBanner-mapped ids", 11, ids.size)
```

with:

```kotlin
    @Test fun every_mid_rec_id_returns_nonblank_content() {
        assertEquals("expected 12 TopBanner-mapped ids", 12, ids.size)
```

Add a new copy assertion at the bottom of the class (before the closing `}`):

```kotlin
    @Test fun thermal_autostopped_copy() {
        val c = midRecBannerContent(WarningId.THERMAL_AUTOSTOPPED)
        assertEquals("Recording stopped", c.title)
        assertEquals("Device overheated.", c.sub)
        assertEquals("Tips to cool down", c.cta)
        assertEquals(2, c.overflow.size)
        assertEquals(ActionTarget.DISMISS_AUTOSTOP_ECHO, c.overflow[0].target)
        assertEquals(ActionTarget.REVIEW_SESSION, c.overflow[1].target)
    }
```

- [ ] **Step 2: Run tests — verify they fail.**

Dispatch subagent:

```
Run: gradlew testDebugUnitTest --tests 'com.aritr.rova.ui.warnings.MidRecBannerContentTest'
Expected: compile fail (midRecBannerContent has no arm for THERMAL_AUTOSTOPPED;
the when is currently exhaustive and the build fails the moment Task 3 lands.
This task closes that compile gap.) If Task 3's enum value compiles but the
when on WarningId is non-exhaustive in this file, the whole module fails build.
```

**Build-stability note:** Tasks 3 and 5 leave the build red between them (WarningSheetContent's exhaustive `when` won't compile against the new enum value). If running Slice 3 in subagent-driven mode, run Tasks 3 → 5 back-to-back without other work in between; if running inline, treat the (T3, T4, T5) trio as one logical compile unit.

- [ ] **Step 3: Add the ActionTarget.**

Edit `app/src/main/java/com/aritr/rova/ui/warnings/WarningSheetContent.kt`. In the `ActionTarget` enum (currently lines 48-61), append before the closing brace:

```kotlin
    /** Phase 4 Slice 3 — VM-only target: routes to RecordScreen's ThermalTipsSheet host (via WarningCenter's onOpenThermalTips param). NOT an Intent. */
    OPEN_THERMAL_TIPS,
```

- [ ] **Step 4: Add the defensive `warningSheetContent` arm.**

In the same file, find the existing defensive arm for `STORAGE_FULL_AUTOSTOPPED`:

```kotlin
    WarningId.STORAGE_FULL_AUTOSTOPPED -> WarningSheetContent(
        // Defensive — STORAGE_FULL_AUTOSTOPPED is TopBanner-only (rendered
        // on Idle, not as a sheet). This arm keeps warningSheetContent
        // exhaustive over WarningId; never renders as a sheet.
        Icons.Default.Storage, "Recording stopped",
        "Storage filled up.",
        WarningAction("OK", ActionTarget.APP_DETAILS_SETTINGS),
        null,
    )
```

Add right after it (before the next arm):

```kotlin
    WarningId.THERMAL_AUTOSTOPPED -> WarningSheetContent(
        // Defensive — THERMAL_AUTOSTOPPED is TopBanner-only (rendered on
        // Idle, not as a sheet). This arm keeps warningSheetContent
        // exhaustive over WarningId; never renders as a sheet.
        Icons.Default.Thermostat, "Recording stopped",
        "Device overheated.",
        WarningAction("OK", ActionTarget.APP_DETAILS_SETTINGS),
        null,
    )
```

- [ ] **Step 5: Add the `midRecBannerContent` arm.**

In the same file, find the `midRecBannerContent` function's `STORAGE_FULL_AUTOSTOPPED` arm:

```kotlin
    WarningId.STORAGE_FULL_AUTOSTOPPED -> TopBannerContent(
        Icons.Default.Storage, "Recording stopped",
        "Storage filled up.", "Free up space",
        overflow = listOf(
            WarningAction("Don't show again", ActionTarget.DISMISS_AUTOSTOP_ECHO),
            WarningAction("Review session", ActionTarget.REVIEW_SESSION),
        ),
    )
```

Add right after it (before the `// All other WarningIds…` comment):

```kotlin
    WarningId.THERMAL_AUTOSTOPPED -> TopBannerContent(
        Icons.Default.Thermostat, "Recording stopped",
        "Device overheated.", "Tips to cool down",
        overflow = listOf(
            WarningAction("Don't show again", ActionTarget.DISMISS_AUTOSTOP_ECHO),
            WarningAction("Review session", ActionTarget.REVIEW_SESSION),
        ),
    )
```

- [ ] **Step 6: Run tests — verify they pass.**

Dispatch subagent:

```
Run: gradlew testDebugUnitTest --tests 'com.aritr.rova.ui.warnings.MidRecBannerContentTest'
Expected: all tests pass (existing + new thermal_autostopped_copy).
```

- [ ] **Step 7: Commit.**

```bash
git add app/src/main/java/com/aritr/rova/ui/warnings/WarningSheetContent.kt \
        app/src/test/java/com/aritr/rova/ui/warnings/MidRecBannerContentTest.kt
git commit -m "feat(warnings): THERMAL_AUTOSTOPPED banner content + OPEN_THERMAL_TIPS target (Phase 4 Slice 3 T5)

Adds:
- ActionTarget.OPEN_THERMAL_TIPS (VM-only, routed in T9 to the sheet host)
- midRecBannerContent arm: 'Recording stopped' / 'Device overheated.' /
  'Tips to cool down' + 'Don't show again' / 'Review session' overflow
- Defensive warningSheetContent arm (TopBanner-only id; keeps the
  exhaustive when compiling)

Mirrors Slice 2 STORAGE_FULL_AUTOSTOPPED shape; only the icon, body, and
CTA copy differ.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 6: `ThermalTipsSheet` composable

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/warnings/ThermalTipsSheet.kt`

**Why:** Standalone composable. Owner-verified on device — no Compose-UI test (matches the existing project convention for warning sheets; spec §7).

- [ ] **Step 1: Create the file.**

Write to `app/src/main/java/com/aritr/rova/ui/warnings/ThermalTipsSheet.kt`:

```kotlin
package com.aritr.rova.ui.warnings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Phase 4 Slice 3 — bottom sheet opened from the THERMAL_AUTOSTOPPED echo
 * banner's primary CTA ("Tips to cool down"). Static content: 5 bullets +
 * "Got it" dismiss. No live thermal read-out, no nav route — see spec §3
 * non-goals.
 *
 * Hosted from [com.aritr.rova.ui.screens.RecordScreen] alongside the
 * existing SettingsSheet. Visibility is owned by RecordScreen's
 * `rememberSaveable` state; the sheet itself is purely view.
 *
 * Spec: docs/superpowers/specs/2026-05-24-phase-4-slice3-thermal-autostop-design.md §4.10
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThermalTipsSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Tips to cool down",
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(8.dp))
            ThermalTip("Move to shade or a cooler room.")
            ThermalTip("Remove the case while the device cools.")
            ThermalTip("Close other camera-heavy apps.")
            ThermalTip("Avoid charging while recording.")
            ThermalTip("Let the device rest 5 minutes before recording again.")
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text("Got it")
            }
        }
    }
}

@Composable
private fun ThermalTip(text: String) {
    Row(modifier = Modifier.padding(vertical = 4.dp)) {
        Text("•  ", style = MaterialTheme.typography.bodyLarge)
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
```

- [ ] **Step 2: Verify it compiles.**

Dispatch subagent:

```
Run: gradlew assembleDebug
Expected: BUILD SUCCESSFUL. (No callers yet — those land in T9. This step
catches typos and import issues before they pollute later commits.)
```

- [ ] **Step 3: Commit.**

```bash
git add app/src/main/java/com/aritr/rova/ui/warnings/ThermalTipsSheet.kt
git commit -m "feat(warnings): ThermalTipsSheet composable (Phase 4 Slice 3 T6)

Static ModalBottomSheet with 5 cool-down tips and a Got It button.
Hosted from RecordScreen (T9), opened when the THERMAL_AUTOSTOPPED
echo banner's primary CTA fires.

No Compose-UI test — owner-verified on device per existing convention
for warning sheets (matches WarningSheetV3, SettingsSheet).

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 7: `SegmentGateThermal` pure helper + tests

**Files:**
- Create: `app/src/main/java/com/aritr/rova/service/SegmentGateThermal.kt`
- Test: `app/src/test/java/com/aritr/rova/service/SegmentGateThermalTest.kt`

**Why:** Extracts the Layer-4 thermal threshold check into a pure helper so it's JVM-testable without Robolectric (matches the existing `accumulatedSessionBytes` extraction pattern at `RovaRecordingService.kt:1834`). Used by the service Layer-4 call site in Task 8.

- [ ] **Step 1: Write the failing tests first.**

Create `app/src/test/java/com/aritr/rova/service/SegmentGateThermalTest.kt`:

```kotlin
package com.aritr.rova.service

import com.aritr.rova.ui.signals.ThermalStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 4 Slice 3 — pure-JVM tests for [SegmentGateThermal]. Pins the
 * "CRITICAL or above" threshold per ADR-0016. The helper is the only
 * piece of the Layer-4 gate; the service-side call site is dispatch-only.
 */
class SegmentGateThermalTest {

    @Test fun `shouldTerminate true at CRITICAL`() {
        assertTrue(SegmentGateThermal.shouldTerminate(ThermalStatus.CRITICAL))
    }

    @Test fun `shouldTerminate true at EMERGENCY and SHUTDOWN`() {
        assertTrue(SegmentGateThermal.shouldTerminate(ThermalStatus.EMERGENCY))
        assertTrue(SegmentGateThermal.shouldTerminate(ThermalStatus.SHUTDOWN))
    }

    @Test fun `shouldTerminate false below CRITICAL`() {
        assertFalse(SegmentGateThermal.shouldTerminate(ThermalStatus.NONE))
        assertFalse(SegmentGateThermal.shouldTerminate(ThermalStatus.LIGHT))
        assertFalse(SegmentGateThermal.shouldTerminate(ThermalStatus.MODERATE))
        assertFalse(SegmentGateThermal.shouldTerminate(ThermalStatus.SEVERE))
    }
}
```

- [ ] **Step 2: Run tests — verify they fail.**

Dispatch subagent:

```
Run: gradlew testDebugUnitTest --tests 'com.aritr.rova.service.SegmentGateThermalTest'
Expected: compile fail ("Unresolved reference: SegmentGateThermal").
```

- [ ] **Step 3: Create the helper.**

Write `app/src/main/java/com/aritr/rova/service/SegmentGateThermal.kt`:

```kotlin
package com.aritr.rova.service

import com.aritr.rova.ui.signals.ThermalStatus

/**
 * Phase 4 Slice 3 (ADR-0016) — pure helper for Layer 4 of
 * [RovaRecordingService.checkSegmentGates]. Returns true when the gate
 * should terminate with [com.aritr.rova.data.StopReason.THERMAL].
 *
 * Threshold is "CRITICAL or above" — at CRITICAL the OS begins aggressive
 * throttling and encoder output starts degrading. Stopping one segment
 * earlier preserves footage integrity. At SEVERE the active-HUD banner
 * already nudges the user to manual-stop; the gate only fires when the
 * user did not react and the device kept climbing.
 *
 * The Layer-4 call site reads `app.thermalStatusSignal.state.value` (push-
 * listener fed) and passes it here. Extraction mirrors the existing
 * `RovaRecordingService.accumulatedSessionBytes` → `StorageEstimator`
 * pattern so the gate stays pure-JVM testable (ADR-0007).
 */
internal object SegmentGateThermal {
    fun shouldTerminate(status: ThermalStatus): Boolean =
        status.ordinal >= ThermalStatus.CRITICAL.ordinal
}
```

- [ ] **Step 4: Run tests — verify they pass.**

Dispatch subagent:

```
Run: gradlew testDebugUnitTest --tests 'com.aritr.rova.service.SegmentGateThermalTest'
Expected: all 3 tests pass.
```

- [ ] **Step 5: Commit.**

```bash
git add app/src/main/java/com/aritr/rova/service/SegmentGateThermal.kt \
        app/src/test/java/com/aritr/rova/service/SegmentGateThermalTest.kt
git commit -m "feat(service): SegmentGateThermal pure helper (Phase 4 Slice 3 T7)

Pure-Kotlin Layer-4 threshold helper. shouldTerminate(status) returns
true iff status.ordinal >= CRITICAL.ordinal. Extraction pattern matches
StorageEstimator (Layer 3) so the gate stays JVM-testable without
Robolectric (ADR-0007).

3 tests pin the threshold at CRITICAL: returns true for CRITICAL/
EMERGENCY/SHUTDOWN, false for NONE/LIGHT/MODERATE/SEVERE.

Service call site lands in T8.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 8: Service Layer-4 call site + THERMAL notification copy

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt`

**Why:** Wires the Layer-4 thermal gate into the existing `checkSegmentGates` pipeline + adds the notification-copy arm. The gate trigger is between-segment (matches LOW_STORAGE — spec §3 non-goal explains mid-segment is deferred). The notification arm completes the exhaustive `when (reason)` block that `StopReason.THERMAL` (T1) added.

- [ ] **Step 1: Read the current `checkSegmentGates` Layer 3 block to anchor the insertion.**

```
Read app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt lines 1790-1825
to confirm the storage-gate try/catch ends and `return SegmentGateResult.Continue`
is the final statement of the method.
```

- [ ] **Step 2: Insert the Layer-4 thermal gate.**

Edit `app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt`. Find the closing of the storage-gate `catch` and the final `return`:

```kotlin
        } catch (e: IllegalArgumentException) {
            // sessionDir disappeared between layer 1 and StatFs — treat as
            // layer-1-late-failure: no-op abort, NOT terminal write.
            RovaLog.w("checkSegmentGates: StatFs threw on $sdir; treating as layer-1-late-failure", e)
            return SegmentGateResult.AbortNoOp
        }

        return SegmentGateResult.Continue
    }
```

Replace with:

```kotlin
        } catch (e: IllegalArgumentException) {
            // sessionDir disappeared between layer 1 and StatFs — treat as
            // layer-1-late-failure: no-op abort, NOT terminal write.
            RovaLog.w("checkSegmentGates: StatFs threw on $sdir; treating as layer-1-late-failure", e)
            return SegmentGateResult.AbortNoOp
        }

        // Layer 4 — thermal gate (Phase 4 Slice 3, ADR-0016).
        // Between-segment check (parallel to LOW_STORAGE Layer 3 — spec §3
        // non-goal: no mid-segment interrupt). Reads the push-listener-fed
        // thermalStatusSignal value. Triggers at CRITICAL or above; SEVERE
        // is intentionally the active-HUD banner's user-driven Stop affordance
        // (not auto-stop).
        val thermal = (applicationContext as? com.aritr.rova.RovaApp)
            ?.thermalStatusSignal?.state?.value
            ?: com.aritr.rova.ui.signals.ThermalStatus.NONE
        if (com.aritr.rova.service.SegmentGateThermal.shouldTerminate(thermal)) {
            RovaLog.w("checkSegmentGates: thermal=$thermal at or above CRITICAL — terminating")
            return SegmentGateResult.Terminate(com.aritr.rova.data.StopReason.THERMAL)
        }

        return SegmentGateResult.Continue
    }
```

The `applicationContext as? RovaApp` cast is defensive — production always satisfies it; tests / weird harnesses fall through to `NONE` and the gate stays a no-op.

- [ ] **Step 3: Add the THERMAL notification-copy arm.**

In the same file, find the existing `when (reason)` block at the eager-write site (around line 2598-2604):

```kotlin
                            updateNotification(
                                when (reason) {
                                    com.aritr.rova.data.StopReason.PERMISSION_REVOKED ->
                                        "Stopped — required permission revoked. Re-grant in Settings."
                                    com.aritr.rova.data.StopReason.LOW_STORAGE ->
                                        "Stopped — device storage low. Free up space."
                                    else -> "Stopping recording…"
                                }
                            )
```

Add a THERMAL arm before `else`:

```kotlin
                            updateNotification(
                                when (reason) {
                                    com.aritr.rova.data.StopReason.PERMISSION_REVOKED ->
                                        "Stopped — required permission revoked. Re-grant in Settings."
                                    com.aritr.rova.data.StopReason.LOW_STORAGE ->
                                        "Stopped — device storage low. Free up space."
                                    com.aritr.rova.data.StopReason.THERMAL ->
                                        "Stopped — device overheated. Let it cool down."
                                    else -> "Stopping recording…"
                                }
                            )
```

- [ ] **Step 4: Verify build + tests.**

Dispatch subagent:

```
Run: gradlew assembleDebug testDebugUnitTest
Expected: BUILD SUCCESSFUL + all tests pass. The service changes have no
new dedicated test — coverage is at SegmentGateThermal (T7) for the pure
threshold + on-device smoke for the wiring (final task).
```

- [ ] **Step 5: Sweep for any other exhaustive `when (StopReason)` site that needs an arm.**

Dispatch subagent:

```
Grep for `when (reason)` or `when (stopReason)` or `when (it.stopReason)` or
`StopReason.LOW_STORAGE ->` across app/src/main and report each site with
its surrounding else clause. Skip the precedence site already covered in T4.
```

Expected report: one site in `RovaRecordingService.kt` (the one just modified). If others surface (e.g. recovery classifier display strings), add a THERMAL arm there too in this same task before committing.

- [ ] **Step 6: Commit.**

```bash
git add app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt
git commit -m "feat(service): Layer-4 thermal gate + THERMAL notification copy (Phase 4 Slice 3 T8)

Layer 4 sits after Layer 3 (storage) in checkSegmentGates. Reads
RovaApp.thermalStatusSignal.state.value (push-listener fed by T2).
Defers to SegmentGateThermal.shouldTerminate (T7) for the CRITICAL
threshold check. Between-segment cadence matches LOW_STORAGE (spec §3
non-goal: mid-segment interrupt parked).

Notification copy arm completes the exhaustive when after StopReason.THERMAL
landed in T1: 'Stopped — device overheated. Let it cool down.'

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 9: End-to-end UI wiring (WarningCenter + RecordScreen + RovaApp)

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/warnings/WarningCenter.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt`
- Modify: `app/src/main/java/com/aritr/rova/RovaApp.kt`

**Why:** Ties everything together. WarningCenter routes the THERMAL_AUTOSTOPPED idle echo to the new `onOpenThermalTips` callback. RecordScreen hosts the sheet + supplies the callback. RovaApp starts the push listener. Three files touched in one commit because they form a single end-to-end wiring change — splitting them would leave the build with a dangling parameter for one commit.

- [ ] **Step 1: Read RecordScreen WarningCenter call sites.**

```
Read app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt lines 570-630 to
confirm both WarningCenter mounts and their argument shape.
```

- [ ] **Step 2: Generalize WarningCenter's Idle TopBanner special-case + add parameter.**

Edit `app/src/main/java/com/aritr/rova/ui/warnings/WarningCenter.kt`. Find the function signature and add `onOpenThermalTips` parameter:

```kotlin
fun WarningCenter(
    hudState: RecordHudState,
    onStopRecording: () -> Unit,
    modifier: Modifier = Modifier,
    vm: WarningCenterViewModel? = null,
    onNavigateToHistory: (() -> Unit)? = null,
    /**
     * Phase 4 Slice 3 — invoked when the user taps the THERMAL_AUTOSTOPPED
     * echo banner's primary CTA ("Tips to cool down"). Host (RecordScreen)
     * flips its rememberSaveable showTipsSheet state to render
     * [ThermalTipsSheet]. Null = no sheet host wired (previews, legacy
     * callers); the CTA becomes a no-op.
     */
    onOpenThermalTips: (() -> Unit)? = null,
) {
```

Now find the existing Idle TopBanner special-case block (currently around lines 62-86 — the `if (surface == WarningSurface.TopBanner) { ... if (id == WarningId.STORAGE_FULL_AUTOSTOPPED) { ... }; return }` block).

Replace the entire block with:

```kotlin
        if (surface == WarningSurface.TopBanner) {
            // Two TopBanner ids render on Idle (echoes of past auto-stops):
            //  • STORAGE_FULL_AUTOSTOPPED (Slice 2) → CTA opens system storage settings.
            //  • THERMAL_AUTOSTOPPED      (Slice 3) → CTA opens ThermalTipsSheet via host.
            // All other TopBanner ids are active-HUD only and suppress here.
            val autoStopEcho by app.autoStopEchoSignal.state.collectAsStateWithLifecycle()
            when (id) {
                WarningId.STORAGE_FULL_AUTOSTOPPED -> {
                    WarningTopBannerV3(
                        content = midRecBannerContent(id),
                        severityColor = RovaWarnings.advisory,
                        onAction = { launchActionTarget(context, ActionTarget.STORAGE_SETTINGS) },
                        onOverflow = { target ->
                            handleEchoOverflow(target, autoStopEcho, resolvedVm, onNavigateToHistory, context)
                        },
                        modifier = modifier,
                    )
                }
                WarningId.THERMAL_AUTOSTOPPED -> {
                    WarningTopBannerV3(
                        content = midRecBannerContent(id),
                        severityColor = RovaWarnings.advisory,
                        onAction = { onOpenThermalTips?.invoke() },
                        onOverflow = { target ->
                            handleEchoOverflow(target, autoStopEcho, resolvedVm, onNavigateToHistory, context)
                        },
                        modifier = modifier,
                    )
                }
                else -> Unit
            }
            return
        }
```

Then add the `handleEchoOverflow` helper at the bottom of the file (just before the file's final `}` — sibling to `launchActionTarget`):

```kotlin
/**
 * Phase 4 Slice 3 — shared overflow router for the two Idle TopBanner echo
 * arms (STORAGE_FULL_AUTOSTOPPED and THERMAL_AUTOSTOPPED). Factored from the
 * Slice-2 inline lambda so both arms reuse it. `autoStopEcho` may be null
 * if the user dismissed between recompose and tap; in that case
 * DISMISS_AUTOSTOP_ECHO no-ops via the elvis return.
 */
private fun handleEchoOverflow(
    target: ActionTarget,
    autoStopEcho: com.aritr.rova.ui.warnings.TerminalEcho?,
    vm: WarningCenterViewModel,
    onNavigateToHistory: (() -> Unit)?,
    context: Context,
) {
    when (target) {
        ActionTarget.DISMISS_AUTOSTOP_ECHO -> {
            val sid = autoStopEcho?.sessionId ?: return
            vm.dismissAutoStopEcho(sid)
        }
        ActionTarget.REVIEW_SESSION -> onNavigateToHistory?.invoke()
        else -> launchActionTarget(context, target)
    }
}
```

Finally, update `launchActionTarget`'s `when (target)` block to handle `OPEN_THERMAL_TIPS` as a no-op (VM-only target, like SNOOZE_FOREVER). Find the existing guard:

```kotlin
    if (target == ActionTarget.SNOOZE_FOREVER) return
    if (target == ActionTarget.DISMISS_AUTOSTOP_ECHO) return
    if (target == ActionTarget.REVIEW_SESSION) return
```

Add:

```kotlin
    if (target == ActionTarget.OPEN_THERMAL_TIPS) return
```

And add a matching arm in the inner `when (target)` (the one that returns `Intent`):

```kotlin
        ActionTarget.OPEN_THERMAL_TIPS -> return                  // VM-only; routed by Idle TopBanner arm
```

- [ ] **Step 3: Wire RecordScreen.**

Edit `app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt`.

(a) At the top of the file, add imports if not present:

```kotlin
import androidx.compose.runtime.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import com.aritr.rova.ui.warnings.ThermalTipsSheet
```

(b) Inside the composable, just after the `warningVm` hoist (around line 74-80, after `val warningVm: WarningCenterViewModel = viewModel(...)` and `val snoozedSet by warningVm.snoozedForever.collectAsStateWithLifecycle()`), add:

```kotlin
        var showTipsSheet by rememberSaveable { mutableStateOf(false) }
```

(c) At both WarningCenter call sites (around lines 575-580 and 619-624), add `onOpenThermalTips = { showTipsSheet = true }` as a trailing argument. Example for the first call site:

```kotlin
                        WarningCenter(
                            hudState = hudState,
                            onStopRecording = ::onStopRecording,           // existing
                            vm = warningVm,
                            onNavigateToHistory = onNavigateToHistory,
                            onOpenThermalTips = { showTipsSheet = true },  // ← NEW (Phase 4 Slice 3)
                        )
```

(Match the actual existing argument list — read the file first; the exact arguments may differ. The key delta is adding the `onOpenThermalTips` line.) Do the same for the active-HUD WarningCenter mount.

(d) Render the sheet near the end of the composable's content area (next to any existing SettingsSheet etc.):

```kotlin
        if (showTipsSheet) {
            ThermalTipsSheet(onDismiss = { showTipsSheet = false })
        }
```

- [ ] **Step 4: Wire RovaApp.onCreate.**

Edit `app/src/main/java/com/aritr/rova/RovaApp.kt`. Find:

```kotlin
    override fun onCreate() {
        super.onCreate()
        instance = this
        // RovaCrashReporter.setBackend(FirebaseCrashlyticsBackend()) — wired Phase 4.
        // NOTE: Phase 1.5 recovery is NOT triggered here. See class KDoc.
        registerExactAlarmStateReceiverIfSupported()
    }
```

Replace with:

```kotlin
    override fun onCreate() {
        super.onCreate()
        instance = this
        // RovaCrashReporter.setBackend(FirebaseCrashlyticsBackend()) — wired Phase 4.
        // NOTE: Phase 1.5 recovery is NOT triggered here. See class KDoc.
        registerExactAlarmStateReceiverIfSupported()
        // Phase 4 Slice 3 — register the thermal push listener for the
        // process lifetime. Idempotent + pre-API-29 no-op. The OS releases
        // the registration on process death (Application.onTerminate is
        // not reliably invoked on production devices).
        thermalStatusSignal.start()
    }
```

- [ ] **Step 5: Verify build + tests.**

Dispatch subagent:

```
Run: gradlew assembleDebug testDebugUnitTest
Expected: BUILD SUCCESSFUL + all tests pass (no new tests added in this task —
the contract is owner-verified on device).
```

- [ ] **Step 6: Owner smoke instructions (write to commit body — actual smoke happens after final task).**

The commit lands wiring; full smoke runs after Task 10. Document the smoke shape in the commit body so the owner has a ready checklist:

1. Fresh install on API 29+ device.
2. Start a recording.
3. `adb shell cmd thermalservice override-status 4` → expect: between-segment, recording stops; notification "Stopped — device overheated. Let it cool down."
4. Return to Record-Idle → expect: THERMAL_AUTOSTOPPED banner with "Tips to cool down" CTA + overflow ⋯.
5. Tap "Tips to cool down" → ThermalTipsSheet renders with 5 bullets + Got it. Tap Got it → sheet closes; banner remains.
6. Tap overflow "Don't show again" → banner disappears immediately.
7. Force-stop + relaunch the app → banner does NOT reappear (persistence intact via `dismissedAutoStopEchoIds`).
8. Reset thermal with `adb shell cmd thermalservice reset`.

- [ ] **Step 7: Commit.**

```bash
git add app/src/main/java/com/aritr/rova/ui/warnings/WarningCenter.kt \
        app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt \
        app/src/main/java/com/aritr/rova/RovaApp.kt
git commit -m "feat(ui): end-to-end thermal autostop wiring (Phase 4 Slice 3 T9)

WarningCenter: generalize the Slice-2 Idle TopBanner special-case from
a single-arm if to a two-arm when over (STORAGE_FULL_AUTOSTOPPED,
THERMAL_AUTOSTOPPED). Factor the Slice-2 overflow lambda into a shared
handleEchoOverflow helper used by both arms. Add onOpenThermalTips
parameter (default null preserves previews / legacy callers). Mark
OPEN_THERMAL_TIPS as VM-only in launchActionTarget guards.

RecordScreen: host showTipsSheet via rememberSaveable; supply
onOpenThermalTips = { showTipsSheet = true } to both WarningCenter
mounts; render ThermalTipsSheet when visible.

RovaApp.onCreate: call thermalStatusSignal.start() so the push listener
runs process-wide. Idempotent + pre-API-29 no-op.

Smoke (post-T10):
 1. adb shell cmd thermalservice override-status 4 during recording
 2. Verify between-segment stop + notification
 3. Verify THERMAL_AUTOSTOPPED banner on next Idle
 4. Verify Tips sheet opens + dismiss
 5. Verify Don't show again persistence across process death
 6. Reset: adb shell cmd thermalservice reset

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 10: ADR-0016 + WarningCenterContract amendment + PR

**Files:**
- Create: `docs/adr/0016-thermal-autostop.md`
- Modify: `docs/WarningCenterContract.md`

**Why:** Documents the canon (threshold choice, between-segment trigger, listener lifecycle, echo banner reuse). Closes the §7 open question from the original contract. Mirrors ADR-0015's role for Slice 2.

- [ ] **Step 1: Write the ADR.**

Write to `docs/adr/0016-thermal-autostop.md`:

```markdown
# ADR-0016 — Phase 4 Slice 3: Thermal auto-stop + echo + Tips sheet

**Status:** Accepted
**Date:** 2026-05-24
**Supersedes:** none
**Related ADRs:** ADR-0007 (record warning sheets), ADR-0013 (warning re-skin v3 chrome canon), ADR-0015 (storage-full autostopped echo — direct sibling pattern)
**Spec:** `docs/superpowers/specs/2026-05-24-phase-4-slice3-thermal-autostop-design.md`

## Context

`WarningCenterContract.md` §4.3 C3.5 declared the design intent for thermal escalation: state-machine banners on the active HUD plus an auto-stop with reason `THERMAL` at `SEVERE` and above. As of master `b9d4773` (Slice 2 merge), the read path existed (`ThermalStatusSignal`) but only refreshed on `ON_RESUME`; the auto-stop did not exist; the new `THERMAL` `StopReason` did not exist; no post-stop user feedback existed.

## Decision

Three coordinated changes that land in a single PR (Slice 3):

### 1. Threshold choice

Auto-stop fires at **CRITICAL** or above. **SEVERE** stays a banner-only state with the existing "Stop now" CTA already wired to `onStopRecording`.

Rationale:
- The contract's "auto-stop at SEVERE" intent was revised during Slice 3 brainstorming: instant SEVERE stop is too aggressive (false-stop risk on quick thermal spikes) and SEVERE is still in the user-visible "uncomfortable to hold" band where a banner nudge is more respectful than an immediate stop.
- CRITICAL is where the OS begins aggressive throttling. Past CRITICAL the encoder output starts degrading; auto-stopping there protects footage integrity.
- The active-HUD banner at SEVERE already exposes the user's stop affordance — the auto-stop is a safety net for users who didn't react.

### 2. Trigger cadence: between-segment

Layer 4 of `RovaRecordingService.checkSegmentGates` reads `ThermalStatusSignal.state.value` between segments and returns `Terminate(StopReason.THERMAL)` when `>= CRITICAL`. Mirrors the LOW_STORAGE (Layer 3) pattern.

Worst-case delay: one segment duration. Acceptable because:
- The SEVERE banner already nudges manual stop one level earlier.
- A mid-segment interrupt would risk encoder/muxer corruption on the in-progress segment. Out of scope for v1 (spec §3 non-goal).

The pure threshold helper `SegmentGateThermal.shouldTerminate(status)` keeps Layer 4 JVM-testable (matches the existing `StorageEstimator.accumulatedSessionBytes` extraction pattern; preserves ADR-0007 test policy).

### 3. Push listener lifecycle

`ThermalStatusSignal` gains `start()` / `stop()` backed by `PowerManager.addThermalStatusListener` (API 29+) via two new constructor seams (`addListener`, `removeListener`). Default no-ops preserve every existing test fixture.

`RovaApp.onCreate` calls `start()`. The OS releases the registration on process death — no explicit `stop()` from app teardown (`Application.onTerminate` is not reliably invoked on production devices).

Listener fires on the main thread via `Handler.post` so `_state.value` writes are safe without an explicit dispatcher.

Vendor-quirk safety: `addListener` / `removeListener` wrap their `pm.addThermalStatusListener(...)` calls in `runCatching` and log on failure. The signal degrades to `ON_RESUME` polling — banner still works (just lagged), gate still works (it reads `state.value`, no listener required).

### 4. Echo banner — reuses Slice 2 plumbing

`SessionAutoStopEchoSignal` is already reason-agnostic (per its KDoc, written in Slice 2). The Slice-2 `WarningPrecedence` echo branch is rewritten from a single-id if to a `when (stopReason)`: `LOW_STORAGE → STORAGE_FULL_AUTOSTOPPED`; `THERMAL → THERMAL_AUTOSTOPPED`. New `WarningId.THERMAL_AUTOSTOPPED` (ADVISORY tier, slot #13).

`WarningCenter`'s Idle TopBanner special-case generalizes from one-arm to a two-arm `when` over `(STORAGE_FULL_AUTOSTOPPED, THERMAL_AUTOSTOPPED)`. The overflow handler ("Don't show again", "Review session") is factored into a shared `handleEchoOverflow` used by both arms.

Per-session-id persistent dismissal reuses `RovaSettings.dismissedAutoStopEchoIds` (Slice 2 — backup-excluded).

### 5. Tips sheet — static content, no nav route

`THERMAL_AUTOSTOPPED` banner CTA is "Tips to cool down" → `ThermalTipsSheet`, a `ModalBottomSheet` with 5 static bullets + "Got it". Hosted from `RecordScreen` via `rememberSaveable` visibility. No new nav destination — a sheet is sufficient.

## Consequences

- `StopReason.THERMAL` is now part of the manifest contract. Recovery code paths that switch on `StopReason` already default-handle unknown reasons (or do not enumerate it). T8's grep sweep confirms only the service notification copy needed a new arm.
- Worst-case auto-stop latency at CRITICAL is one segment duration. Owner accepts this in exchange for muxer-safety.
- Push listener registered at `Application.onCreate`. Cold-start receiver paths that bypass UI initialization now pay a tiny additional cost (one main-thread listener registration). Negligible.
- Pre-API-29 devices receive no banner, no gate, no echo — slice is invisible. `ThermalStatusSignal.start()` returns early on the SDK gate.
- `WarningCenter`'s Idle TopBanner branch is no longer single-arm. Future echo additions (e.g. a permission-revoked echo) plug into the same `when`.

## Alternatives considered

- **Mid-segment thermal interrupt.** Rejected — risks encoder corruption on the in-progress segment. Re-evaluate if owner reports CRITICAL→OS-kill races in the field.
- **15-second grace-period countdown UI** between SEVERE and auto-stop. Rejected — adds a banner state machine and a new countdown chrome; owner picked "persistent SEVERE banner + auto-stop at CRITICAL" as the simpler product line.
- **Telemetry of thermal-time per session.** Out of scope; defer to a future "session diagnostics" surface.
- **A `/help/thermal` nav route** instead of a sheet. Rejected — a single-topic help surface is over-engineered for one bullet list; a sheet integrates with the existing chrome.
- **A separate `SessionThermalAutoStopEchoSignal`** mirroring `SessionAutoStopEchoSignal`. Rejected — the latter is already reason-agnostic; generalization is one when-arm and a parallel signal would duplicate the dismiss-persistence wiring.
- **Bumping `StopReason.SEVERE` to the auto-stop threshold** (contract's original wording). Revised during brainstorming for the reasons above; this ADR is the canonical record of the revision.
```

- [ ] **Step 2: Amend WarningCenterContract.md §4.3 C3.5.**

Edit `docs/WarningCenterContract.md`. Find the row in §4.3 (around line 124):

```
| C3.5 Overheating (escalating) | "Device Getting Hot" | `Escalating` | **missing** | `PowerManager.getThermalStatus()` + `addThermalStatusListener` (API 29+) | Banner on `record` (mid-session). State machine: warm → hot → severe → emergency. At `severe` and above, recording auto-stops with reason `THERMAL` (new reason — see §7 open question) | until thermal status drops two levels (hysteresis) | **state-driven** — auto-clears on de-escalation | **Phase 3.4** — `ThermalSignal` |
```

Replace with:

```
| C3.5 Overheating (escalating) | "Device Getting Hot" | `Escalating` | **shipped Phase 4 Slice 3 (ADR-0016)** | `PowerManager.getThermalStatus()` + `addThermalStatusListener` (API 29+) | Banner on `record` (mid-session). State machine: warm → hot → severe → emergency. **Auto-stop fires at CRITICAL (revised from SEVERE — see ADR-0016)** with reason `THERMAL`. Echo banner `THERMAL_AUTOSTOPPED` surfaces on next Idle with "Tips to cool down" CTA. | state-driven on push-listener emissions; persistent banner at SEVERE with manual-Stop affordance | **state-driven** — auto-clears on de-escalation | **Phase 4 Slice 3** — `ThermalStatusSignal.start()` push listener + `SegmentGateThermal` Layer-4 gate |
```

Then find §7's open question on thermal (search for "Thermal hysteresis exact thresholds" or "THERMAL reason"):

```
3. **Thermal hysteresis exact thresholds.** `MODERATE` to enter, `LIGHT` to leave is the proposed pattern. Confirm against `PowerManager.THERMAL_STATUS_*` and the device's typical climbing curve before Phase 3.4 ships.
```

Append a closure line below it (do not delete the original — the hysteresis open question itself remains parked):

```
**Update 2026-05-24 (Phase 4 Slice 3, ADR-0016):** the `THERMAL` `StopReason` shipped. Two-level hysteresis is **not** implemented — `MutableStateFlow.distinctUntilChanged` dedupe is judged sufficient for the slow thermal physics. The "exact thresholds" item stays parked as a future tightening if field data shows flap.
```

Also amend the §286 mermaid line (the diagram node label):

```
        THERMAL["PowerManager.addThermalStatusListener<br/>(missing, API 29+ — Phase 3.4)"]:::miss
```

to:

```
        THERMAL["PowerManager.addThermalStatusListener<br/>(shipped Phase 4 Slice 3, API 29+)"]:::ok
```

(Use `:::ok` class instead of `:::miss`.)

- [ ] **Step 3: Push the branch + open the PR.**

Dispatch subagent:

```
Run these commands sequentially:
  git status                               # confirm clean working tree (only the two doc files added/modified)
  git add docs/adr/0016-thermal-autostop.md docs/WarningCenterContract.md
  git commit -m "docs(adr): ADR-0016 thermal auto-stop canon + contract amendment (Phase 4 Slice 3 T10)" -m "" -m "Closes §7 open question on THERMAL StopReason. Amends §4.3 C3.5" -m "from 'missing' to 'shipped Phase 4 Slice 3'. Documents the SEVERE→CRITICAL" -m "threshold revision rationale, the between-segment trigger choice, and" -m "the listener lifecycle decisions." -m "" -m "Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"

  git push -u origin phase4-slice3-thermal-autostop

  gh pr create --base master --head phase4-slice3-thermal-autostop \
    --title "Phase 4 Slice 3 — thermal auto-stop + echo banner + Tips sheet" \
    --body "$(cat <<'EOF'
## Summary
- Adds thermal push listener (`PowerManager.addThermalStatusListener`, API 29+) so SEVERE banner reacts during recording.
- New `StopReason.THERMAL` + `SegmentGateThermal` Layer-4 gate auto-stops recording at CRITICAL or above between segments.
- New `WarningId.THERMAL_AUTOSTOPPED` echo banner on next Idle with "Tips to cool down" CTA opening a static `ThermalTipsSheet`.
- Per-session-id dismissal reuses Slice 2's `dismissedAutoStopEchoIds` (backup-excluded).

Spec: docs/superpowers/specs/2026-05-24-phase-4-slice3-thermal-autostop-design.md
Plan: docs/superpowers/plans/2026-05-24-phase-4-slice3-thermal-autostop.md
ADR: docs/adr/0016-thermal-autostop.md

## Test plan
- [ ] gradlew testDebugUnitTest — full suite green
- [ ] gradlew assembleDebug + lintDebug — no new errors
- [ ] On-device smoke (API 29+ device, app installed):
  - [ ] Start a recording
  - [ ] `adb shell cmd thermalservice override-status 4` → between-segment stop fires; notification reads "Stopped — device overheated. Let it cool down."
  - [ ] Return to Record-Idle → THERMAL_AUTOSTOPPED banner appears with "Tips to cool down" + overflow ⋯
  - [ ] Tap "Tips to cool down" → ThermalTipsSheet renders (5 bullets + Got it); tapping Got it closes sheet, banner remains
  - [ ] Tap overflow "Don't show again" → banner disappears immediately
  - [ ] Force-stop the app then relaunch → banner does NOT reappear (dismissed-set persisted)
  - [ ] Tap overflow "Review session" → navigates to History
  - [ ] `adb shell cmd thermalservice reset` → clean state

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

Capture the PR URL. Report it back.

- [ ] **Step 4: Wait for owner smoke.**

Do NOT merge. Owner runs the smoke checklist and either approves or reports issues.

---

## Self-Review (post-plan)

**Spec coverage check (run mentally before dispatching T1):**

| Spec §  | Plan task |
|---|---|
| §4.1 `StopReason.THERMAL` | T1 |
| §4.2 `ThermalStatusSignal.start()` / `stop()` + seams | T2 |
| §4.3 `RovaApp.onCreate` → `start()` | T9 |
| §4.4 Service Layer-4 gate + `SegmentGateThermal` helper | T7 (helper), T8 (call site) |
| §4.5 Notification copy arm | T8 |
| §4.6 `WarningId.THERMAL_AUTOSTOPPED` | T3 |
| §4.7 `WarningPrecedence` when-arm | T4 |
| §4.8 `WarningSheetContent` (target + content + defensive sheet) | T5 |
| §4.9 `WarningCenter` Idle generalization | T9 |
| §4.10 `ThermalTipsSheet` composable | T6 |
| §4.11 `RecordScreen` sheet host | T9 |
| §5 data flow | exercised by T9 + owner smoke |
| §6 error handling (runCatching wrap, race tolerance) | T2 (start/stop runCatching), T7 (helper purity) |
| §7 tests | T2 (signal), T4 (precedence + aggregate), T5 (content), T7 (gate helper); T3 updates pin tests |
| §8 task split | this whole plan (10 tasks) |
| §9 migration / risk | none surfaced; T8 step-5 sweep is the safeguard |
| §10 non-goals | preserved (no mid-segment, no hysteresis SM, no telemetry, no nav route) |

All spec sections have at least one task. No gaps.

**Type / signature consistency check:**

- `ThermalStatusSignal` ctor — T2's 4-param signature `(sdkInt, currentStatus, addListener, removeListener)` matches every reference in T9 (`forContext` factory call) and in the existing tests (which use only the first two and get defaults for the new pair).
- `SegmentGateThermal.shouldTerminate(ThermalStatus): Boolean` — T7 signature matches the T8 call site `SegmentGateThermal.shouldTerminate(thermal)`.
- `StopReason.THERMAL` — T1 enum value matches every reference in T4 (`when (echo.stopReason)`) and T8 (`StopReason.THERMAL` Terminate + notification arm).
- `WarningId.THERMAL_AUTOSTOPPED` — T3 enum value matches T4 (precedence return), T5 (content arms), T9 (WarningCenter when arm).
- `ActionTarget.OPEN_THERMAL_TIPS` — T5 enum value matches T9 (`launchActionTarget` guards + Idle CTA wiring).
- `WarningCenter(onOpenThermalTips: (() -> Unit)? = null)` — T9 signature matches T9 RecordScreen call site `onOpenThermalTips = { showTipsSheet = true }`.
- `handleEchoOverflow(target, autoStopEcho, vm, onNavigateToHistory, context)` — T9 helper signature matches both call sites in T9.

All consistent.

**Placeholder scan:** Every task has explicit file paths and complete code in every step. No "TBD", no "add appropriate error handling", no "see Task N for similar code" — the THERMAL arms are spelled out independently in each location even where Slice-2 STORAGE arms are the template.

## Execution Handoff

**Plan complete and saved to `docs/superpowers/plans/2026-05-24-phase-4-slice3-thermal-autostop.md`. Two execution options:**

**1. Subagent-Driven (recommended)** — fresh subagent per task, two-stage review (spec compliance + code quality) between tasks, fast iteration. Matches the cadence that worked for Slice 2.

**2. Inline Execution** — execute tasks in this session using executing-plans, batch execution with checkpoints.

**Which approach?**
