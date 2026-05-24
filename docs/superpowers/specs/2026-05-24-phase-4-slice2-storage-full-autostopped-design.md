# Phase 4 Slice 2 — `STORAGE_FULL_AUTOSTOPPED` echo banner (Design)

**Date:** 2026-05-24
**Status:** Approved (pending implementation plan)
**Phase parent:** Phase 4 warning surface (ADR-0007, ADR-0013, ADR-0014)
**Mockup:** none (reuses `WarningTopBannerV3` chrome from ADR-0013)
**Related ADRs:** ADR-0006 (terminal manifest reasons), ADR-0007 (warning sheet model), ADR-0013 (warning re-skin v3 chrome canon), ADR-0014 (snooze persistence)
**Related contract sections:** `docs/WarningCenterContract.md` §4.2 row C2.3, §4.6, §6.1
**Branch:** `phase4-slice2-storage-full-autostopped` (cut from `master @ 3a490bd`)

---

## 1. Problem

When a recording session auto-stops mid-rec because storage runs out, the service already does the right thing: `SegmentGate.compute()` returns `Terminate(StopReason.LOW_STORAGE)`, the manifest is marked `Terminated.KILLED_BY_SYSTEM` + `StopReason.LOW_STORAGE`, and a recovery card appears on Library next launch.

But the user lands on the Record screen confused. The HUD has reverted from `Recording` to `Idle` without explanation. The contract calls for a "read-only echo on `record`" (§4.2 row C2.3) — a banner that surfaces the same information the recovery card carries, but on the Record screen where the user is actually looking. That echo is not yet wired.

This slice ships it as a new `WarningId.STORAGE_FULL_AUTOSTOPPED` flowing through the existing `WarningCenter` aggregator pattern.

## 2. Goal

Surface a top-banner-style echo on Record screen Idle whenever the most-recent terminal session was auto-stopped for `StopReason.LOW_STORAGE` and the user has not yet dismissed it. Provide a "Free up space" primary CTA that opens system storage settings, plus overflow actions for explicit dismiss ("Don't show again") and recovery-card navigation ("Review session").

## 3. Non-goals

- **Multi-session echo stack.** Only the most-recent auto-stop is echoed. If three auto-stops happen before the user dismisses, only the latest shows.
- **Auto-clear on next successful recording.** Explicit dismiss only. A future slice could auto-clear when a `Terminated.COMPLETED` session lands after the echo'd one.
- **TTL / auto-expire** — same reasoning as ADR-0014 (snooze persistence). Reset row pattern is sufficient.
- **Garbage collection of `dismissedAutoStopEchoIds`** — set grows by 1 entry per auto-stop. Acceptable for v1.0 (rare event). Set resets on uninstall.
- **Mid-stop transition flash.** No flash on the active HUD as the session is stopping — the echo appears only after HUD reverts to Idle.
- **Override of `WarningCenterContract.md` §4.6 SD_CARD_EJECTED rejection.** SD card detection is out of scope; that contract section stands.
- **New `StopReason` enum value.** `LOW_STORAGE` already exists (ADR-0006); this slice reuses it.
- **New `Terminated` enum value.** `KILLED_BY_SYSTEM` already exists; this slice reuses it.

## 4. Architecture

### 4.1 Storage

| Aspect | Decision |
|---|---|
| Backend | `SharedPreferences` (matches existing `RovaSettings` pattern). |
| File | `rova_runtime_prefs` (existing — added Phase 4 patch chain). |
| Key | `dismissed_autostop_echo_ids` |
| Type | `Set<String>` via `getStringSet` / `putStringSet`. |
| Value | Each entry is a session id string (`SessionManifest.sessionId`). |
| Backup | Excluded (existing `<exclude>` on `rova_runtime_prefs.xml` covers it). Reinstall resets. |

### 4.2 `RovaSettings` surface

Mirrors the snoozedWarningIds pattern from ADR-0014:

```kotlin
var dismissedAutoStopEchoIds: Set<String>
    get() = runtimePrefs.getStringSet("dismissed_autostop_echo_ids", emptySet()) ?: emptySet()
    set(value) = runtimePrefs.edit { putStringSet("dismissed_autostop_echo_ids", value) }
```

### 4.3 `TerminalEcho` value type

```kotlin
data class TerminalEcho(
    val sessionId: String,
    val stopReason: StopReason,
)
```

Tiny immutable carrier — passed through the signal flow.

### 4.4 `SessionAutoStopEchoSignal`

Pure-JVM testable: I/O lives behind a `terminalEchoSource: () -> TerminalEcho?` seam.

```kotlin
class SessionAutoStopEchoSignal(
    private val terminalEchoSource: () -> TerminalEcho?,
    initialDismissedIds: Set<String> = emptySet(),
) {
    private val _state = MutableStateFlow<TerminalEcho?>(null)
    val state: StateFlow<TerminalEcho?> = _state.asStateFlow()
    private var dismissed = initialDismissedIds

    init { recompute() }

    /** Re-poll source. Called on RecordScreen ON_RESUME + on service terminal transitions. */
    fun refresh() { recompute() }

    /** Add [sessionId] to the dismissed set and re-filter the flow. */
    fun markDismissed(sessionId: String) {
        dismissed = dismissed + sessionId
        recompute()
    }

    private fun recompute() {
        val latest = terminalEchoSource()
        _state.value = latest?.takeIf { it.sessionId !in dismissed }
    }
}
```

**Note:** `markDismissed` updates only the in-memory `dismissed` set; the persistent write happens in the `onAutoStopDismissed` callback wired by the factory (§4.7). Signal is decoupled from `SharedPreferences`.

### 4.5 `RovaApp` wiring

```kotlin
val autoStopEchoSignal: SessionAutoStopEchoSignal by lazy {
    val settings = RovaSettings(this)
    SessionAutoStopEchoSignal(
        terminalEchoSource = {
            recoveryScanner.latestTerminalSession()?.let {
                TerminalEcho(it.sessionId, it.stopReason)
            }
        },
        initialDismissedIds = settings.dismissedAutoStopEchoIds,
    )
}
```

`recoveryScanner.latestTerminalSession()` — if this method does not already exist on `RecoveryScanner`, the implementation plan adds it. Returns the most-recent terminal `SessionManifest` (or null), pulling `sessionId` + `stopReason` only. Read happens once at signal init; subsequent reads happen via `refresh()` triggers.

### 4.6 `WarningId` precedence slot

Insert `STORAGE_FULL_AUTOSTOPPED` at position 12 (between `STORAGE_LOW_MID_REC` and `THERMAL_SEVERE`):

```
11. STORAGE_LOW_MID_REC (ADVISORY · live mid-rec)
12. STORAGE_FULL_AUTOSTOPPED (ADVISORY · echo) ← NEW
13. THERMAL_SEVERE (ADVISORY)
14. MICROPHONE_DENIED (ADVISORY)
15. BATTERY_OPTIMIZATION_ON (ADVISORY)
16. POWER_SAVE_MODE (ADVISORY)
17. THERMAL_MODERATE (ADVISORY)
18. NOTIFICATIONS_DENIED (ADVISORY)
```

Rows 12-17 shift +1. `WarningPrecedence.resolve` uses no ordinal arithmetic; `WarningId.gatesStart` is by-name; persisted `snoozedWarningIds` (ADR-0014) is by-name. No breakage.

`gatesStart`: `false` for `STORAGE_FULL_AUTOSTOPPED`. The echo is informational; user can start a new recording (preflight `STORAGE_INSUFFICIENT` will fire its own block if storage is still tight).

### 4.7 `WarningPrecedence.resolve` extension

Add 11th param `autoStopEcho: TerminalEcho?`. Add a branch in the ADVISORY band:

```kotlin
fun resolve(
    cameraPermissionGranted: Boolean,
    exactAlarmGranted: Boolean,
    storageInsufficient: Boolean,
    thermal: ThermalStatus,
    power: PowerState,
    camera: CameraSignalState,
    microphonePermissionGranted: Boolean,
    notificationsGranted: Boolean,
    batteryOptimizationExempt: Boolean,
    storageLowMidRec: Boolean,
    autoStopEcho: TerminalEcho?,                  // ← NEW
): WarningId? {
    // ... existing HARD_BLOCK + CRITICAL + STORAGE_LOW_MID_REC branches ...
    autoStopEcho?.takeIf { it.stopReason == StopReason.LOW_STORAGE }
        ?.let { return WarningId.STORAGE_FULL_AUTOSTOPPED }
    // ... existing THERMAL_SEVERE + lower advisories ...
}
```

### 4.8 `WarningCenterViewModel` ctor + mutator

Add 11th source-flow ctor param + mutator + callback (mirrors ADR-0014's `snoozeForever` pattern):

```kotlin
class WarningCenterViewModel(
    // ... existing 10 source flows ...
    autoStopEcho: StateFlow<TerminalEcho?>,                      // ← NEW (11th)
    private val scope: CoroutineScope? = null,
    initialSnoozedIds: Set<WarningId> = emptySet(),
    private val onSnoozeChanged: ((Set<WarningId>) -> Unit)? = null,
    private val onAutoStopDismissed: ((String) -> Unit)? = null, // ← NEW (callback)
) : ViewModel() {
    // ... aggregate threads autoStopEcho through to WarningPrecedence ...

    fun dismissAutoStopEcho(sessionId: String) {
        onAutoStopDismissed?.invoke(sessionId)
    }
}
```

VM does not own the dismissed-id set — it just routes the dismiss event to the callback, which the factory wires to `app.autoStopEchoSignal.markDismissed(id)` + `settings.dismissedAutoStopEchoIds += id`.

### 4.9 `aggregate()` combinator extension

Today's `aggregate()` uses a `Bools6` packing pattern because kotlinx-coroutines `combine` has typed overloads only up to 5 flows. Adding `autoStopEcho` as an 11th non-Boolean flow requires extending the combinator. Implementation plan resolves the exact shape — either:
- Wrap `autoStopEcho` into a new pack alongside `thermal`/`power`/`camera` in the outer combine, or
- Use the vararg `combine` overload that returns `Array<Any?>` and cast back.

### 4.10 Factory wiring

In `buildWarningCenterViewModel(app)`:

```kotlin
internal fun buildWarningCenterViewModel(app: RovaApp): WarningCenterViewModel {
    val settings = RovaSettings(app)
    val initialSnoozed: Set<WarningId> = settings.snoozedWarningIds
        .mapNotNull { runCatching { WarningId.valueOf(it) }.getOrNull() }
        .toSet()
    return WarningCenterViewModel(
        // ... existing 10 source flows ...
        autoStopEcho = app.autoStopEchoSignal.state,
        initialSnoozedIds = initialSnoozed,
        onSnoozeChanged = { set ->
            settings.snoozedWarningIds = set.map(WarningId::name).toSet()
        },
        onAutoStopDismissed = { sessionId ->
            settings.dismissedAutoStopEchoIds = settings.dismissedAutoStopEchoIds + sessionId
            app.autoStopEchoSignal.markDismissed(sessionId)
        },
    )
}
```

### 4.11 Surface routing

`warningSurfaceFor(STORAGE_FULL_AUTOSTOPPED) = WarningSurface.TopBanner`. No new `WarningSurface` enum value.

In `WarningCenter.kt` Idle branch, special-case the one id:

```kotlin
if (hudState is RecordHudState.Idle) {
    if (surface == WarningSurface.TopBanner) {
        if (id == WarningId.STORAGE_FULL_AUTOSTOPPED) {
            WarningTopBannerV3(
                content = idleEchoBannerContent(id),
                severityColor = RovaWarnings.advisory,
                onAction = { launchActionTarget(context, ActionTarget.STORAGE_SETTINGS) },
                onOverflow = { target ->
                    when (target) {
                        ActionTarget.DISMISS_AUTOSTOP_ECHO -> {
                            val echoId = resolvedVm.activeAutoStopSessionId()
                                ?: return@WarningTopBannerV3
                            resolvedVm.dismissAutoStopEcho(echoId)
                        }
                        ActionTarget.REVIEW_SESSION -> {
                            // navigate to History — host wires this via existing
                            // onNavigateToHistory lambda already in WarningCenter scope
                        }
                        else -> launchActionTarget(context, target)
                    }
                },
                modifier = modifier,
            )
            return
        }
        return  // other TopBanner ids still suppressed at Idle
    }
    // ... existing sheet/chip path ...
}
```

Reuses `WarningTopBannerV3` composable + chrome canon from ADR-0013. The plan resolves whether `WarningTopBannerV3` needs an `onOverflow` slot today (it shipped with a `Stop` CTA only — overflow may need extending) or whether the echo banner uses a slightly different composable.

### 4.12 `idleEchoBannerContent(id)` helper

New helper in `WarningSheetContent.kt`:

```kotlin
internal fun idleEchoBannerContent(id: WarningId): TopBannerContent = when (id) {
    WarningId.STORAGE_FULL_AUTOSTOPPED -> TopBannerContent(
        icon = Icons.Default.Storage,
        title = "Recording stopped",
        sub = "Storage filled up.",
        cta = "Free up space",
    )
    else -> error("idleEchoBannerContent: unsupported id $id")
}
```

### 4.13 `ActionTarget` extensions

Add three new enum values:

```kotlin
enum class ActionTarget {
    // ... existing values ...
    STORAGE_SETTINGS,
    DISMISS_AUTOSTOP_ECHO,
    REVIEW_SESSION,
}
```

`launchActionTarget`:
- `STORAGE_SETTINGS` → `Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)` with `ACTION_APPLICATION_DETAILS_SETTINGS` fallback on `ActivityNotFoundException`.
- `DISMISS_AUTOSTOP_ECHO` → no-op (handled by overflow router above, mirrors `SNOOZE_FOREVER` pattern).
- `REVIEW_SESSION` → no-op (host-level navigation, mirrors the pattern).

### 4.14 Refresh triggers

Two paths re-poll `autoStopEchoSignal.refresh()`:

1. **RecordScreen `ON_RESUME` lifecycle event** — catches the case where user backgrounds Rova, an auto-stop happens, and user returns. The plan adds a `DisposableEffect` or `LifecycleEventEffect` in `RecordScreen` that calls `app.autoStopEchoSignal.refresh()` on `ON_RESUME`.
2. **Service terminal transition** — `RovaApp` already observes `serviceState` (or can subscribe via the existing connection). On any transition into a terminal state (`Terminated.KILLED_BY_SYSTEM` etc.), the app calls `autoStopEchoSignal.refresh()`. Plan resolves whether to subscribe in `RovaApp.onCreate` or extend an existing observer.

Refresh is cheap — `RecoveryScanner.latestTerminalSession()` is a manifest-directory scan + JSON parse of the single most-recent entry, milliseconds.

## 5. Data flow

```
COLD START
  RovaApp.onCreate
  RovaApp.autoStopEchoSignal (lazy-init):
    settings = RovaSettings(this)
    SessionAutoStopEchoSignal(
      terminalEchoSource = { recoveryScanner.latestTerminalSession()?.let { ... } },
      initialDismissedIds = settings.dismissedAutoStopEchoIds,
    )
    init { recompute() }
      latest = terminalEchoSource()
      _state.value = latest?.takeIf { it.sessionId !in dismissed }

RecordScreen mounts → buildWarningCenterViewModel(app):
  WarningCenterViewModel(
    ... 10 existing source flows ...,
    autoStopEcho = app.autoStopEchoSignal.state,
    onAutoStopDismissed = { id ->
      settings.dismissedAutoStopEchoIds = settings.dismissedAutoStopEchoIds + id
      app.autoStopEchoSignal.markDismissed(id)
    },
    ... other 4.1c params ...
  )

VM aggregate combines 11 flows → WarningPrecedence.resolve:
  autoStopEcho?.takeIf { stopReason == LOW_STORAGE }
    ?.let { return STORAGE_FULL_AUTOSTOPPED }
  → activeWarning emits STORAGE_FULL_AUTOSTOPPED

WarningCenter routing (Idle):
  surface = TopBanner, id = STORAGE_FULL_AUTOSTOPPED
  → WarningTopBannerV3(content = idleEchoBannerContent(id), onAction = ..., onOverflow = ...)

USER TAPS "Free up space"
  launchActionTarget(context, STORAGE_SETTINGS)
    → Intent(ACTION_INTERNAL_STORAGE_SETTINGS) with APPLICATION_DETAILS fallback
  Banner remains visible (CTA does not auto-dismiss)

USER TAPS OVERFLOW → "Don't show again"
  resolvedVm.dismissAutoStopEcho(sessionId)
    → onAutoStopDismissed(sessionId) fires
    → settings.dismissedAutoStopEchoIds += sessionId  (persisted to runtime_prefs)
    → app.autoStopEchoSignal.markDismissed(sessionId)
        → SessionAutoStopEchoSignal.recompute()
        → _state.value = null
    → activeWarning re-resolves to next-highest (or null)
    → banner clears

USER TAPS OVERFLOW → "Review session"
  Navigation to History (host-wired)

RECORDSCREEN RESUME (no UI change happens at this exact moment)
  LifecycleEventEffect(ON_RESUME):
    app.autoStopEchoSignal.refresh()
    → recompute() reads latest manifest
    → if changed: _state.value updates → VM emits → banner appears/clears

SERVICE AUTO-STOPS NEW SESSION (LOW_STORAGE)
  Service writes terminal manifest
  RovaApp observes serviceState terminal transition → autoStopEchoSignal.refresh()
  → new latest manifest reflects new sessionId
  → _state.value = TerminalEcho(newId, LOW_STORAGE)
  → activeWarning emits STORAGE_FULL_AUTOSTOPPED with new id
  → banner shows again
```

## 6. Error handling

| Scenario | Behavior |
|---|---|
| Latest terminal `stopReason ∈ {USER, INIT_FAILED, PERMISSION_REVOKED, NONE}` | `WarningPrecedence` branch returns null for the row. No echo. |
| Latest terminal manifest missing/corrupt | `RecoveryScanner.latestTerminalSession()` returns null (existing scanner already swallows corrupt-manifest errors). `state` = null. No echo. |
| `getStringSet` returns null for the dismissed key | `?: emptySet()` fallback in getter (mirrors snoozedWarningIds). |
| `dismissedAutoStopEchoIds` grows unbounded over many auto-stops | Acceptable for v1.0 (rare event). One entry per auto-stop event. Garbage collection deferred (§3 non-goal). |
| `STORAGE_SETTINGS` Intent has no resolver | `ActivityNotFoundException` caught in `launchActionTarget`, falls back to `APPLICATION_DETAILS_SETTINGS`. User can navigate from there. |
| Backup restore brings back stale `dismissedAutoStopEchoIds` | Cannot happen — `rova_runtime_prefs.xml` is `<exclude>`d from backup. Fresh install always sees an empty set. |
| Two terminal sessions both LOW_STORAGE; user dismisses the most-recent | `latestTerminalSession()` returns the single most-recent. After dismissing, `recompute()` re-reads, sees the same id, filters it → null. Older auto-stops never echoed (UX choice — only most-recent). |
| Service auto-stops while user is on Record Idle | `RecordScreen` `ON_RESUME` doesn't fire (already in foreground). Refresh triggered via the `RovaApp` service-state observer instead. Plan resolves exact wiring. |
| `WarningCenterAggregateTest` existing tests | All ctor calls use named args; new param `autoStopEcho` placed before the 4.1c trailing optional params. Existing 11 tests stay green by adding the new arg to `makeVm()` helper. |
| Empty session list at first launch | `latestTerminalSession()` returns null. `state` = null. No echo. |

## 7. Testing

All pure-JVM JUnit4, no Robolectric / Compose-UI / kotlinx-coroutines-test. Same posture as ADR-0014 / ADR-0007.

### 7.1 `SessionAutoStopEchoSignalTest.kt` — 6 tests

The signal is reason-agnostic: it surfaces ALL terminal echoes filtered only by the dismissed-id set. `WarningPrecedence` (§4.7) filters on `stopReason == LOW_STORAGE`. Tests verify the signal contract independently.

- `state is null when source returns null`
- `state emits TerminalEcho when source returns a terminal session` (reason-agnostic)
- `state is null when sessionId in initialDismissedIds seed`
- `markDismissed flips state to null when current echo matches`
- `markDismissed does nothing when current echo is a different sessionId`
- `refresh re-reads source and emits new TerminalEcho when latest changes`

### 7.2 `WarningPrecedenceTest.kt` — 2 new tests

- `STORAGE_FULL_AUTOSTOPPED fires when autoStopEcho is LOW_STORAGE and no higher-priority signal active`
- `STORAGE_LOW_MID_REC outranks STORAGE_FULL_AUTOSTOPPED when both fire`

### 7.3 `WarningCenterAggregateTest.kt` — 2 new tests

- `autoStopEcho source flow drives STORAGE_FULL_AUTOSTOPPED into activeWarning`
- `dismissAutoStopEcho mutator invokes onAutoStopDismissed callback with sessionId`

The existing `makeVm()` helper gains the new `autoStopEcho` source. All existing 11 tests must continue to pass; the helper change is the only edit they need.

### 7.4 `RovaSettingsTest.kt` — 3 new tests

- `dismissedAutoStopEchoIds default is empty set`
- `dismissedAutoStopEchoIds round-trips a 2-id set`
- `dismissedAutoStopEchoIds setter replaces, does not merge`

### 7.5 Device smoke (owner-driven, post-PR)

1. Fresh install → fill storage to near-empty → start a 60s loop → verify mid-rec auto-stop → Record Idle screen shows the echo banner.
2. Tap "Free up space" → system storage settings opens.
3. Return to Rova Idle → banner still present (CTA doesn't auto-dismiss).
4. Tap overflow → "Don't show again" → banner clears immediately.
5. Force-stop Rova → relaunch → banner does NOT reappear (persistent dismiss).
6. Run another auto-stop session (new sessionId) → banner reappears for the new id.
7. Tap overflow → "Review session" → History screen opens with the relevant recovery card.

## 8. Slice plan

Single PR. 7 tasks (TDD per task):

1. **T1** — `RovaSettings.dismissedAutoStopEchoIds` getter/setter + 3 pure-JVM round-trip tests.
2. **T2** — `TerminalEcho` data class + `SessionAutoStopEchoSignal` (with `terminalEchoSource: () -> TerminalEcho?` seam) + `refresh()` + `markDismissed(id)` + 6 pure-JVM signal tests.
3. **T3** — `WarningId.STORAGE_FULL_AUTOSTOPPED` enum value at precedence slot 12 + `WarningPrecedence.resolve` 11th param + branch + 2 new precedence tests.
4. **T4** — `WarningCenterViewModel` 11th source-flow ctor param `autoStopEcho` + `dismissAutoStopEcho(id)` mutator + `onAutoStopDismissed` callback + 2 new aggregate tests; `aggregate()` combinator extended; existing 11 tests pass.
5. **T5** — `WarningSheetContent.warningSurfaceFor` adds `STORAGE_FULL_AUTOSTOPPED → TopBanner`; new `idleEchoBannerContent(id)` helper with "Free up space" CTA + overflow ("Don't show again", "Review session"); new `ActionTarget.STORAGE_SETTINGS` + `DISMISS_AUTOSTOP_ECHO` + `REVIEW_SESSION` enum values; `launchActionTarget` handles `STORAGE_SETTINGS` with `INTERNAL_STORAGE_SETTINGS` + `APPLICATION_DETAILS` fallback.
6. **T6** — `WarningCenter.kt` Idle-branch special-case routing → `WarningTopBannerV3`; overflow callbacks routed to `vm.dismissAutoStopEcho`; `RovaApp` adds lazy `autoStopEchoSignal`; refresh wired to `RecordScreen` `ON_RESUME` + service-state terminal transitions; `buildWarningCenterViewModel(app)` threads new params; `RecoveryScanner.latestTerminalSession()` added if missing.
7. **T7** — ADR-0015 documenting the echo signal canon + amendment to `WarningCenterContract.md` §4.2 row C2.3 (mark "read-only echo on record" as shipped + cite ADR) + PR push + open.

## 9. Migration risk

- **In-memory → on-disk transition:** existing users have no persisted dismissed-ids (the key never existed). First launch reads empty → first auto-stop after upgrade fires the echo as designed.
- **`WarningId` ordinal shift:** rows 12-17 shift +1. `WarningPrecedence.resolve` uses no ordinal arithmetic. `WarningId.gatesStart` is by-name. `snoozedWarningIds` (ADR-0014) is by-name. No breakage.
- **`WarningCenterViewModel` 11th ctor param:** placed after the 10 source flows but BEFORE the 4.1c trailing optional params (`scope`, `initialSnoozedIds`, `onSnoozeChanged`). Adding a 4th optional param `onAutoStopDismissed = null` at the very end keeps the existing call site in `WarningCenter.kt`'s `buildWarningCenterViewModel` source-compatible — the factory just gets one new named arg.
- **`makeVm()` helper in test file:** existing 11 tests use this helper; helper gains a new arg with a sane default (MutableStateFlow<TerminalEcho?>(null)). Existing tests stay green.
- **`aggregate()` combinator:** today's `Bools6` + 5-arg combine becomes one of: (a) `Bools6` + 6-arg combine that mixes `autoStopEcho` with the other non-Bool sources, or (b) vararg `combine` + cast. Plan resolves. Both are mechanical refactors.

## 10. Out of scope (parked for future slices)

- **Garbage collection of `dismissedAutoStopEchoIds`** — set grows unbounded by 1 entry per auto-stop. v1.0 acceptable; a future slice can prune ids whose session manifests are deleted.
- **Multi-session echo stack** — echoes only the most-recent auto-stop. Stack view would need a different UX surface.
- **Auto-clear on next successful recording** — explicit dismiss only. A future slice could auto-clear when a `Terminated.COMPLETED` session lands after the echo'd one.
- **TTL / auto-expire** — same reasoning as ADR-0014. Reset row pattern (or per-id dismiss in this slice's case) is sufficient.
- **Echo for non-LOW_STORAGE auto-stops** — `KILLED_BY_SYSTEM` with reasons like `INIT_FAILED` or future `THERMAL` are out of scope. The signal surfaces them but precedence filters them out. A future slice could add tier-specific echoes per stopReason.
- **Settings reset row for `dismissedAutoStopEchoIds`** — mirrors the 4.1c reset-snoozes row but for echoes. Parked because the dismissed set typically has 0-3 entries (rare auto-stops); a reset affordance is not high-value at v1.0.

## 11. Open questions

None at this time. All design choices locked via brainstorming Q&A:
1. Drop SD_CARD_EJECTED — honor contract §4.6.
2. Idle echo banner trigger model.
3. New leaf signal reading RecoveryScanner output.
4. Per-session-id persistent dismissal.
5. Single CTA "Free up space" + overflow "Don't show again" / "Review session".
6. Surface reuses TopBanner + hudState gate (Option A).
