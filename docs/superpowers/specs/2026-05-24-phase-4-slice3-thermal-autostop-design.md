# Phase 4 Slice 3 — Thermal Auto-Stop + Echo + Tips (Design)

**Date:** 2026-05-24
**Status:** Approved (pending implementation plan)
**Phase parent:** Phase 4 warning surface (ADR-0007, ADR-0013, ADR-0015)
**Mockup:** none new (extends shipped Phase 4 v3 chrome — banner + ModalBottomSheet patterns already on screen)
**Related ADRs:** ADR-0007 (record warning sheets), ADR-0013 (warning re-skin v3 chrome canon), ADR-0015 (storage-full autostopped echo — direct sibling pattern)
**Related slices:** Phase 4 Slice 2 (STORAGE_FULL_AUTOSTOPPED — shipped 2026-05-24, PR #46, master `b9d4773`)
**Related contract sections:** `docs/WarningCenterContract.md` §4.3 C3.5 (Overheating escalating, "missing" tag), §7 (open question on `THERMAL` StopReason)

---

## 1. Problem

Three gaps in the thermal surface today:

1. **No real-time updates during recording.** `ThermalStatusSignal` only re-reads `PowerManager.currentThermalStatus` on the host Activity's `ON_RESUME`. While the user is in the active recording HUD, the OS never re-prompts the signal — the banner can read `NONE` while the device is at `SEVERE`.

2. **No auto-stop on dangerous escalation.** The five thermal `WarningId` rows (`THERMAL_MODERATE` / `_SEVERE` / `_CRITICAL` / `_EMERGENCY` / `_SHUTDOWN`) are banner-only. `WarningCenterContract.md` §4.3 C3.5 states the design intent — "at `severe` and above, recording auto-stops with reason `THERMAL` (new reason — see §7 open question)" — but no code wires this. At `CRITICAL` the OS begins aggressive throttling; encoder output degrades; left running, the session either corrupts or the OS kills the process.

3. **No post-stop user feedback.** If recording were already auto-stopping on thermal (it isn't), the user would land on Idle with no explanation. Slice 2 established the echo-banner pattern for `LOW_STORAGE`; the thermal equivalent does not exist.

Source pointers:
- `app/src/main/java/com/aritr/rova/ui/signals/ThermalStatusSignal.kt:36-58` (poll-only, ON_RESUME contract).
- `app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt:1750-1825` (`checkSegmentGates` — Layer 1/2/3 only; no thermal gate).
- `app/src/main/java/com/aritr/rova/data/SessionManifest.kt:359-365` (`StopReason` enum — no `THERMAL` value).

## 2. Goal

Close all three gaps in a single slice that mirrors Slice 2's auto-stop + echo pattern:

| Layer | Behavior |
|---|---|
| Active rec, MODERATE | Existing advisory banner. No change. |
| Active rec, SEVERE | Existing escalating-color banner now updates in real time via push listener. CTA is "Stop now" — already wired to `onStopRecording` at `WarningCenter.kt:128`. No auto-stop. |
| Active rec, CRITICAL / EMERGENCY / SHUTDOWN | Auto-stop at the next segment-gate check. Writes `Terminated.USER_STOPPED` + `StopReason.THERMAL` via existing `stopPeriodicRecordingAndMerge` eager-write path. |
| Next Idle | `THERMAL_AUTOSTOPPED` echo banner. Primary CTA "Tips to cool down" opens a ModalBottomSheet. Overflow: "Don't show again", "Review session" (same as `STORAGE_FULL_AUTOSTOPPED`). |
| Tips sheet | 5 static bullets + "Got it" close button. |
| Dismissal | Per-session-id via reused `RovaSettings.dismissedAutoStopEchoIds` (Slice 2 plumbing). |

## 3. Non-goals

- **No hysteresis state machine** beyond what `MutableStateFlow` deduplication already provides. Thermal status changes slowly (physical process) and the contract's two-level hysteresis idea is parked. The dedupe is sufficient for v1.
- **No mid-segment auto-stop trigger.** Layer 4 of `checkSegmentGates` runs between segments (matches `LOW_STORAGE` pattern). Worst-case delay at CRITICAL = one segment duration (typically 5–30 seconds depending on user-configured segment length). Acceptable because the SEVERE banner already nudges the user to manual-stop one level earlier.
- **No grace-period countdown UI** on the active-rec banner. SEVERE relies on user reaction. CRITICAL is silent-until-segment-boundary then stops.
- **No telemetry / thermal-time accumulator.** Out of scope.
- **No new help nav route.** Tips is a sheet, not a screen — keeps the slice contained.
- **No new `RovaSettings` keys.** All persistence reuses Slice 2's `dismissedAutoStopEchoIds`.
- **Pre-API-29:** `ThermalStatusSignal` already maps to `ThermalStatus.NONE` for `Build.VERSION.SDK_INT < Q`. The push-listener `start()` returns early on the same gate. Slice is invisible on old devices.
- **No migration of existing `StopReason` callers** beyond the additive enum value. Exhaustive-when sites will fail to compile and get one new arm each — the compiler enumerates them.

## 4. Architecture

### 4.1 New `StopReason` value

`app/src/main/java/com/aritr/rova/data/SessionManifest.kt` — add one enum value:

```kotlin
enum class StopReason {
    USER,
    LOW_STORAGE,
    PERMISSION_REVOKED,
    INIT_FAILED,
    THERMAL,        // ← new
    NONE,
}
```

KDoc above the enum already enumerates each value's trigger; add a row: `THERMAL` = service Layer-4 gate fires when `ThermalStatusSignal.state.value >= ThermalStatus.CRITICAL`.

### 4.2 `ThermalStatusSignal` — add push listener lifecycle

Two new constructor seams (same shape as the existing `currentStatus` closure):

```kotlin
class ThermalStatusSignal(
    private val sdkInt: Int,
    private val currentStatus: () -> Int,
    private val addListener: (PowerManager.OnThermalStatusListener) -> Unit = {},
    private val removeListener: (PowerManager.OnThermalStatusListener) -> Unit = {},
) {
    // ... existing _state / state / refresh / currentValue ...

    private var registeredListener: PowerManager.OnThermalStatusListener? = null

    fun start() {
        if (sdkInt < Build.VERSION_CODES.Q) return
        if (registeredListener != null) return
        val l = PowerManager.OnThermalStatusListener { raw ->
            _state.value = ThermalStatus.fromRaw(raw)
        }
        addListener(l)
        registeredListener = l
    }

    fun stop() {
        val l = registeredListener ?: return
        removeListener(l)
        registeredListener = null
    }
}
```

`forContext()` factory wires production:

```kotlin
return ThermalStatusSignal(
    sdkInt = Build.VERSION.SDK_INT,
    currentStatus = { /* existing */ },
    addListener = { l ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            pm.addThermalStatusListener(handler::run, l)
        }
    },
    removeListener = { l ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            pm.removeThermalStatusListener(l)
        }
    },
)
```

`handler` = `Handler(Looper.getMainLooper())` captured in the closure. Listener fires on main thread so the `_state.value = ...` write is safe without an explicit dispatcher.

The defaults (`{}`) on the new seams preserve every existing call site in tests — no test fixture compiles broken.

### 4.3 `RovaApp` — wire `start()` on `onCreate`

`app/src/main/java/com/aritr/rova/RovaApp.kt` — after `thermalStatusSignal` is constructed (existing lazy field), call `start()` in `onCreate`:

```kotlin
override fun onCreate() {
    super.onCreate()
    // ... existing init ...
    thermalStatusSignal.start()
}
```

No `stop()` from `onTerminate` — Android does not reliably invoke `onTerminate` on production devices. Process death releases the listener via OS reclaim. The class is idempotent on `start()` (early-return on already-registered) so a future restart-after-zombie scenario is safe.

### 4.4 Service Layer-4 thermal gate

`checkSegmentGates` currently has Layers 1 (prerequisites), 2 (permission), 3 (storage). Add Layer 4 (thermal):

```kotlin
// Layer 4 — thermal gate (Slice 3).
// Reads the live thermalStatusSignal value (push-listener fed).
// Triggers Terminate(THERMAL) at CRITICAL or above — see ADR-0016.
// At SEVERE the active-HUD banner already prompts manual stop; the
// gate fires only when the user did NOT react and the device kept
// climbing. Hysteresis is implicit: a brief spike that drops back
// to SEVERE/MODERATE before the next segment-gate check does not
// terminate.
val thermal = thermalStatusFn()
if (thermal.ordinal >= ThermalStatus.CRITICAL.ordinal) {
    RovaLog.w("checkSegmentGates: thermal=$thermal at or above CRITICAL — terminating")
    return SegmentGateResult.Terminate(com.aritr.rova.data.StopReason.THERMAL)
}
```

To keep the gate pure-JVM testable, extract Layer 4 into a free helper (parallel to `accumulatedSessionBytes` at `RovaRecordingService.kt:1834`):

```kotlin
// New file: app/src/main/java/com/aritr/rova/service/SegmentGateThermal.kt
internal object SegmentGateThermal {
    /**
     * Pure helper for Layer 4 of [RovaRecordingService.checkSegmentGates].
     * Returns true when the gate should terminate with [StopReason.THERMAL].
     * Threshold is "CRITICAL or above" — see ADR-0016 §"Threshold choice".
     */
    fun shouldTerminate(status: ThermalStatus): Boolean =
        status.ordinal >= ThermalStatus.CRITICAL.ordinal
}
```

Layer-4 call site becomes:

```kotlin
if (SegmentGateThermal.shouldTerminate(app.thermalStatusSignal.state.value)) {
    return SegmentGateResult.Terminate(com.aritr.rova.data.StopReason.THERMAL)
}
```

(`app` already accessible — `RovaApp` reference held in service.)

### 4.5 Notification copy

`stopPeriodicRecordingAndMerge` `when (reason)` block at `RovaRecordingService.kt:2598-2604` gains one arm:

```kotlin
com.aritr.rova.data.StopReason.THERMAL ->
    "Stopped — device overheated. Let it cool down."
```

### 4.6 New `WarningId.THERMAL_AUTOSTOPPED`

Insert between `STORAGE_FULL_AUTOSTOPPED` and `THERMAL_SEVERE`:

```kotlin
// row #13 — Slice 3
STORAGE_FULL_AUTOSTOPPED(WarningTier.ADVISORY),
THERMAL_AUTOSTOPPED(WarningTier.ADVISORY),        // ← new
THERMAL_SEVERE(WarningTier.ADVISORY),
// ... existing rows shift +1 ...
```

Update KDoc range `1..18 → 1..19`. Bump ordinal-pin asserts in `WarningIdOrderTest.kt`. Add to TopBanner cluster in `WarningSurfaceTest.kt`.

### 4.7 `WarningPrecedence` — extend echo branch

`WarningPrecedence.kt:79-81` currently:

```kotlin
// #12 — STORAGE_FULL_AUTOSTOPPED
autoStopEcho?.takeIf { it.stopReason == StopReason.LOW_STORAGE }
    ?.let { return WarningId.STORAGE_FULL_AUTOSTOPPED }
```

Becomes (one when-arm):

```kotlin
// #12-13 — auto-stop echoes (Slice 2: LOW_STORAGE; Slice 3: THERMAL)
autoStopEcho?.let { echo ->
    when (echo.stopReason) {
        StopReason.LOW_STORAGE -> return WarningId.STORAGE_FULL_AUTOSTOPPED
        StopReason.THERMAL -> return WarningId.THERMAL_AUTOSTOPPED
        else -> Unit   // other reasons don't yield an echo banner
    }
}
```

Insertion position in the precedence chain stays at the same slot — both echoes are advisory and equivalent priority.

### 4.8 `WarningSheetContent` — content + new action target

Add to `ActionTarget` enum:

```kotlin
OPEN_THERMAL_TIPS,   // VM-only, routed by WarningCenter to RecordScreen sheet host
```

Add `midRecBannerContent` arm:

```kotlin
WarningId.THERMAL_AUTOSTOPPED -> TopBannerContent(
    icon = Icons.Default.Thermostat,
    title = "Recording stopped",
    body = "Device overheated.",
    cta = "Tips to cool down",
    overflow = listOf(
        WarningAction("Don't show again", ActionTarget.DISMISS_AUTOSTOP_ECHO),
        WarningAction("Review session", ActionTarget.REVIEW_SESSION),
    ),
)
```

`Icons.Default.Thermostat` is in the standard Material Icons set already used by the project (verified in WarningSheetContent existing imports — Storage / Battery / Warning all from the same family).

### 4.9 `WarningCenter` — generalize Idle TopBanner special-case

Today's Idle TopBanner branch hard-codes `STORAGE_FULL_AUTOSTOPPED` (`WarningCenter.kt:66-86`). Generalize to a two-arm `when`:

```kotlin
if (surface == WarningSurface.TopBanner) {
    val autoStopEcho by app.autoStopEchoSignal.state.collectAsStateWithLifecycle()
    when (id) {
        WarningId.STORAGE_FULL_AUTOSTOPPED -> {
            WarningTopBannerV3(
                content = midRecBannerContent(id),
                severityColor = RovaWarnings.advisory,
                onAction = { launchActionTarget(context, ActionTarget.STORAGE_SETTINGS) },
                onOverflow = { target -> handleEchoOverflow(target, autoStopEcho, resolvedVm, onNavigateToHistory) },
                modifier = modifier,
            )
        }
        WarningId.THERMAL_AUTOSTOPPED -> {
            WarningTopBannerV3(
                content = midRecBannerContent(id),
                severityColor = RovaWarnings.advisory,
                onAction = { onOpenThermalTips?.invoke() },
                onOverflow = { target -> handleEchoOverflow(target, autoStopEcho, resolvedVm, onNavigateToHistory) },
                modifier = modifier,
            )
        }
        else -> Unit  // other TopBanner ids are active-HUD only
    }
    return
}
```

`handleEchoOverflow` is a small private helper factored from the existing Slice 2 lambda — same body, both arms reuse it. New `WarningCenter` parameter:

```kotlin
onOpenThermalTips: (() -> Unit)? = null,
```

Default `null` keeps all existing previews / call sites compiling.

### 4.10 `ThermalTipsSheet` — new composable

`app/src/main/java/com/aritr/rova/ui/warnings/ThermalTipsSheet.kt`:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThermalTipsSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(24.dp).fillMaxWidth()) {
            Text("Tips to cool down", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            ThermalTip("Move to shade or a cooler room.")
            ThermalTip("Remove the case while the device cools.")
            ThermalTip("Close other camera-heavy apps.")
            ThermalTip("Avoid charging while recording.")
            ThermalTip("Let the device rest 5 minutes before recording again.")
            Spacer(Modifier.height(24.dp))
            Button(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text("Got it")
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ThermalTip(text: String) {
    Row(modifier = Modifier.padding(vertical = 6.dp)) {
        Text("• ", style = MaterialTheme.typography.bodyLarge)
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
```

Reuses the project's existing `ModalBottomSheet` pattern (see `SettingsSheet.kt`). No animation, no scroll (5 short bullets fit on every supported screen size).

### 4.11 `RecordScreen` — host the Tips sheet

Add `var showTipsSheet by rememberSaveable { mutableStateOf(false) }`. `rememberSaveable` (not plain `remember`) so the sheet survives configuration change / backgrounding — see §6 error-handling row. Pass to `WarningCenter`:

```kotlin
WarningCenter(
    hudState = hudState,
    onStopRecording = ::onStopRecording,
    vm = warningVm,
    onNavigateToHistory = onNavigateToHistory,
    onOpenThermalTips = { showTipsSheet = true },  // ← new
)
```

After the WarningCenter call:

```kotlin
if (showTipsSheet) {
    ThermalTipsSheet(onDismiss = { showTipsSheet = false })
}
```

Both `WarningCenter` mounts (Idle + Active per Slice 2) pass the same callback. Only the Idle mount can actually trigger it (only Idle renders `THERMAL_AUTOSTOPPED`), but passing both keeps the call sites symmetric.

## 5. Data flow

### 5.1 Cold path — escalation during recording

```
Recording active, segment N in progress
  Device temperature rises
  OS dispatches onThermalStatusChanged(3 = SEVERE)
  ThermalStatusSignal listener: _state.value = SEVERE
  Active-HUD WarningCenter recomputes activeWarning via combine()
  WarningPrecedence resolves THERMAL_SEVERE (no echo, in-rec banner)
  WarningTopBannerV3 paints "Device hot — Stop now" (escalating color,
    onAction = onStopRecording)

User does NOT tap Stop. Temperature continues climbing.
  OS dispatches onThermalStatusChanged(4 = CRITICAL)
  ThermalStatusSignal: _state.value = CRITICAL
  Active-HUD banner copy flips to THERMAL_CRITICAL row
  Segment N completes normally (between-segment boundary)
  recordingLoop calls checkSegmentGates()
  Layer 4: SegmentGateThermal.shouldTerminate(CRITICAL) → true
  Returns Terminate(StopReason.THERMAL)
  Service sets currentStopReason = THERMAL
  Service calls stopPeriodicRecordingAndMerge
  Eager write: sessionStore.markTerminated(sid, USER_STOPPED, THERMAL)
  Notification updateNotification("Stopped — device overheated. Let it cool down.")
  Merge proceeds normally (B3 contract — eager USER_STOPPED before merge)
  Service stopForeground + stopSelf

User returns to Record-Idle (or app cold-launches)
  RecordScreen mount: DisposableEffect on ON_RESUME → autoStopEchoSignal.refresh()
  latestTerminalSession() walks manifests, returns TerminalEcho(sid, THERMAL)
  signal._state.value = TerminalEcho(sid, THERMAL) (not in dismissed set)
  WarningCenterViewModel.aggregate() combines flows
  WarningPrecedence.resolve(autoStopEcho = TerminalEcho(sid, THERMAL))
    → THERMAL_AUTOSTOPPED
  WarningCenter Idle branch, surface = TopBanner, id = THERMAL_AUTOSTOPPED
  Renders WarningTopBannerV3:
    title:   "Recording stopped"
    body:    "Device overheated."
    cta:     "Tips to cool down"
    overflow: ["Don't show again", "Review session"]
```

### 5.2 User actions on the echo banner

```
Tap "Tips to cool down" (primary CTA)
  onAction → onOpenThermalTips.invoke()
  RecordScreen: showTipsSheet = true
  ThermalTipsSheet renders. 5 bullets + Got it button.
  User taps "Got it" → onDismiss → showTipsSheet = false
  Sheet closes. Banner remains on Idle until session id is dismissed
    (per-session-id persistence — banner does NOT auto-dismiss on tip view).

Tap overflow "Don't show again"
  WarningTopBannerV3 invokes onOverflow(DISMISS_AUTOSTOP_ECHO)
  handleEchoOverflow → vm.dismissAutoStopEcho(autoStopEcho.sessionId)
  VM: onAutoStopDismissed.invoke(sid)
  Factory callback: settings.dismissedAutoStopEchoIds += sid
                    app.autoStopEchoSignal.markDismissed(sid)
  Signal re-recomputes: TerminalEcho is now in dismissed set → state.value = null
  WarningCenter activeWarning re-resolves: no more THERMAL_AUTOSTOPPED
  Banner disappears.

Tap overflow "Review session"
  onOverflow(REVIEW_SESSION) → onNavigateToHistory.invoke()
  Host navigates to History tab (existing wiring from Slice 2).
```

### 5.3 Cold-start dismissal persistence

```
Process cold start
  RovaApp.onCreate → settings.dismissedAutoStopEchoIds read once
                  → autoStopEchoSignal seeded with the persisted set
                  → thermalStatusSignal.start() registers listener
  RecordScreen mounts → autoStopEchoSignal.refresh()
    latestTerminalSession returns the same TerminalEcho
    BUT it's already in the seeded dismissed set
    state.value stays null
  Banner does NOT reappear. Honors the user's "Don't show again."
```

## 6. Error handling

| Scenario | Behavior |
|---|---|
| Pre-API-29 device | `ThermalStatusSignal.start()` early-returns; listener never registered; state stays `NONE`; gate never fires; banner never shows; echo never resolves. Slice is invisible. |
| `addListener` throws (vendor SDK quirk) | Treat the same as the existing `runCatching` pattern in `SessionAutoStopEchoSignal.recompute`. Wrap `start()` body in `runCatching { ... }.onFailure { RovaLog.w(...) }`. Signal degrades to ON_RESUME polling — the existing fallback. |
| `removeListener` throws | Swallow + log via `runCatching`. Process death will release any leak. |
| Listener fires after `stop()` raced past unregister | Benign: `_state.value =` is idempotent on equal values; even if the value changes, no consumer crash — the next gate-check will read the latest. |
| Multiple `start()` calls | Idempotent — early-return on `registeredListener != null`. |
| `stop()` before `start()` | Idempotent — early-return on `registeredListener == null`. |
| Layer-4 gate reads thermalStatusSignal mid-write race | `MutableStateFlow.value` is volatile-read; worst case is one-cycle staleness. Next gate check picks up the latest. |
| `THERMAL` terminal manifest with a stale dismissed set | Cold-start seeds dismissed from disk; banner suppressed. Identical to Slice 2 behavior. |
| User taps Stop on SEVERE banner one frame before CRITICAL gate fires | Race ends in stop either way. Whichever sets `currentStopReason` first wins; the eager-write `when` in `stopPeriodicRecordingAndMerge` reads the field, so either `USER` or `THERMAL` is what lands in the manifest. Either is correct — both reflect user intent. |
| Tips sheet open while user backgrounds the app | Sheet state lives in Compose; sheet is restored on ON_RESUME via `rememberSaveable` (`mutableStateOf` survives configuration change). Acceptable for v1. |

## 7. Testing

All tests pure-JVM JUnit4 (no Robolectric / Compose-UI / kotlinx-coroutines-test), matching ADR-0007 precedent for this surface.

### 7.1 `ThermalStatusSignalTest.kt` — 5 new tests

- `start registers listener once on API 29+` — fake `addListener` seam counts invocations; assert 1 after `start()`, still 1 after second `start()`.
- `start is no-op pre-API-29` — `sdkInt = 28`; assert `addListener` never called.
- `stop unregisters listener and clears reference` — call `start()` then `stop()`; assert `removeListener` invoked with the same instance; `stop()` again is no-op.
- `listener emission updates state flow` — capture the registered listener via the fake seam, invoke `onThermalStatusChanged(3)`; assert `state.value == SEVERE`.
- `listener emission distinctness — equal values do not re-emit` — invoke twice with same int; assert `state` collector sees one emission (StateFlow dedupe contract).

### 7.2 `SegmentGateThermalTest.kt` — NEW file, 3 tests

- `shouldTerminate true at CRITICAL` — assert `SegmentGateThermal.shouldTerminate(ThermalStatus.CRITICAL) == true`.
- `shouldTerminate true at EMERGENCY and SHUTDOWN` — both return true.
- `shouldTerminate false below CRITICAL` — assert false for `NONE`, `LIGHT`, `MODERATE`, `SEVERE`.

### 7.3 `WarningPrecedenceTest.kt` — 2 new tests

- `THERMAL terminal echo resolves THERMAL_AUTOSTOPPED` — construct `TerminalEcho("sid-1", StopReason.THERMAL)`; pass as `autoStopEcho`; assert `resolve(...) == THERMAL_AUTOSTOPPED`.
- `LOW_STORAGE echo regression` — existing behavior still returns `STORAGE_FULL_AUTOSTOPPED` (guard against the when-rewrite breaking Slice 2).

### 7.4 `WarningCenterAggregateTest.kt` — 2 new tests

- `aggregate emits THERMAL_AUTOSTOPPED when autoStopEcho carries THERMAL reason` — seed `autoStopEcho` StateFlow with `TerminalEcho(sid, THERMAL)`; assert `activeWarning.value == THERMAL_AUTOSTOPPED`.
- `LOW_STORAGE aggregate regression` — same as Slice 2 baseline, still passes.

### 7.5 Updates to existing tests

- `WarningIdOrderTest.kt` — extend ordinal-pin list with `THERMAL_AUTOSTOPPED` at its new slot.
- `WarningSurfaceTest.kt` — add `THERMAL_AUTOSTOPPED` to the TopBanner cluster expectation.
- `MidRecBannerContentTest.kt` — assert content arm exists and `cta.isNotBlank()` and `overflow.size == 2`.

### 7.6 RovaSettings

No new keys. No new round-trip tests.

### 7.7 Owner-verified on device

- ThermalTipsSheet visual rendering + dismiss.
- End-to-end: thermal-injection or warm-room flow to confirm CRITICAL → auto-stop → echo banner → tips sheet.

Test injection on emulator: `adb shell cmd thermalservice override-status <0..6>` triggers the listener and the gate without a real heat source.

## 8. Slice plan

Single PR. 8 tasks (TDD per file). Each commits after green test + spec-compliance review + code-quality review.

1. **T1:** `StopReason.THERMAL` enum value + KDoc row. No new tests (existing `StopReason` tests stay green; exhaustive-when sites in service get one new arm each, surfaced by compiler).
2. **T2:** `ThermalStatusSignal.start()` / `stop()` + 2 new ctor seams + 5 new pure-JVM tests.
3. **T3:** `WarningId.THERMAL_AUTOSTOPPED` insertion + KDoc bump + `WarningIdOrderTest` + `WarningSurfaceTest` updates.
4. **T4:** `WarningPrecedence` when-arm rewrite + 2 new tests (THERMAL resolves + LOW_STORAGE regression) + `WarningCenterAggregateTest` 2 new tests.
5. **T5:** `WarningSheetContent` — `ActionTarget.OPEN_THERMAL_TIPS` + midRecBannerContent arm + `MidRecBannerContentTest` extension.
6. **T6:** `ThermalTipsSheet.kt` new file. Owner-verified on device (no test).
7. **T7:** Wiring task — `SegmentGateThermal` helper + 3 tests + service Layer-4 call site + notification copy arm + `RovaApp.onCreate` thermalStatusSignal.start() + `RecordScreen` sheet host + `WarningCenter` generalized echo special-case + `onOpenThermalTips` parameter.
8. **T8:** ADR-0016 (thermal auto-stop canon) + `WarningCenterContract.md` §4.3 C3.5 amendment ("missing" → "shipped in Slice 3, see ADR-0016") + close §7 open question (`THERMAL` StopReason landed) + PR push + open.

## 9. Migration risk

| Risk | Mitigation |
|---|---|
| Adding `StopReason.THERMAL` breaks exhaustive-when in service | Compiler surfaces all sites; T7 adds the notification arm; other when-sites (e.g. `RecoveryScanner`) default-handle unknown reasons. Sweep with `Grep StopReason.LOW_STORAGE` and ensure each `when` over `StopReason` gets reviewed in T7. |
| Slice 2 echo plumbing changes | Only `WarningPrecedence` arm extension + `WarningCenter` Idle branch generalization. Signal layer (`SessionAutoStopEchoSignal`) untouched — already reason-agnostic per its KDoc. Slice 2 tests run as regression in T4. |
| Push listener leaks across process reboot | `RovaApp.onCreate` runs once per process. `start()` is idempotent. Process death releases the OS-side registration. No leak. |
| Vendor `PowerManager` throws on `addThermalStatusListener` | `start()` wraps in `runCatching`; signal degrades to ON_RESUME polling. Banner still works (just lagged); auto-stop gate still works (it reads `state.value`, no thermal change reads still as last-known). |
| ON_RESUME fallback now duplicates push updates | Benign — `_state.value = X` is a no-op when equal, and `MutableStateFlow` dedupes. |
| `Layer 4` extraction (`SegmentGateThermal`) breaks the existing service test | Layer 4 is new code; no existing test references it. Extraction is the first time it appears. |
| ADR-0015 vs new ADR-0016 | New ADR. ADR-0015 documented the STORAGE_FULL_AUTOSTOPPED echo. ADR-0016 documents thermal as a sibling. Cross-link both. Single ADR-0015 amendment would conflate two slices; separate ADRs preserve traceability per the existing project convention. |
| Tips sheet rotated / backgrounded mid-display | Compose `mutableStateOf` + `rememberSaveable` survives configuration changes. Backgrounding then re-resuming preserves the sheet. Acceptable. |
| Echo banner persistence after a `Don't show again` racing with a fresh THERMAL auto-stop on the SAME session id | Cannot happen: each session has a unique id, and a session can only be in `Terminated.USER_STOPPED` once. Dismissed-set entry is keyed on the session id, so a freshly-terminated NEW session would have a different id and the banner would resurface. Correct behavior — dismiss is per-session, not per-cause. |

## 10. Out of scope (parked for future slices)

- **Mid-segment thermal interrupt.** Would require pausing the muxer mid-encode and either finalizing the in-progress segment cleanly or discarding it. Substantial new failure modes vs. the between-segment delay we accept.
- **Thermal hysteresis state machine.** Two-level enter/leave thresholds. The contract §5 idea. Parked — `MutableStateFlow` dedupe handles the trivial case; the loud case (rapid oscillation across CRITICAL) is rare and self-resolving in physical thermal dynamics.
- **Grace-period countdown UI** on SEVERE banner. Decided against in design (Q3 picked the persistent-banner option, not the countdown option).
- **Telemetry of thermal time per session.** Out of scope; could land in a later "session diagnostics" surface.
- **Live thermal read-out inside the Tips sheet** (Q5 option C). Decided against — picked the static-bullets option.
- **A help-center route** with multiple help topics. Tips sheet is single-purpose; if Rova grows multiple help topics later, the sheet can graduate to a route.
- **Auto-resume after cool-down.** "Recording will resume when device cools" is a different product, and the user explicitly stopped — auto-resume would be surprising.

## 11. Open questions

None at this stage. All decisions locked via brainstorming Q1-Q5 + Approach A pick.

The only "verify-on-implementation" item is whether `Icons.Default.Thermostat` is in the version of `material-icons-extended` bundled by the project. If it isn't, fall back to `Icons.Default.Warning` with the same severity-color treatment — surfaced in T5 if it bites.
