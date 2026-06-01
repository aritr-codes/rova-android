# Camera Warm Across In-App Navigation — Design Spec

**Date:** 2026-05-31
**Branch:** `feat/camera-warm-across-nav` (off master `99eecf8`)
**Smoke-test finding:** #5 (Samsung A17 5G / Android 14)
**Touches:** ADR-0006 (recording lifecycle robustness) → new **ADR-0021**

---

## Problem

Switching between in-app tabs (Record ↔ Settings ↔ Library) tears down and cold-rebinds
CameraX every time, producing a ~1.5s "Initializing Camera" overlay on every return to
the Record screen. The smoke test flagged this as the most jarring idle-state defect.

### Root cause

CameraX is owned entirely by the foreground `RovaRecordingService` (it is the
`LifecycleOwner`; `bindToLifecycle`). `RecordScreen` only supplies a `PreviewView` surface.

Tab navigation uses Compose Navigation `navigate(route){ launchSingleTop = true }` with **no
`popUpTo`** — so the Record `NavBackStackEntry` survives (the `RecordViewModel` is **not**
destroyed). The teardown is driven purely by a per-screen `DisposableEffect` /
`LifecycleEventObserver` in `RecordScreen.kt:257-281`:

```
ON_STOP  -> viewModel.stopCameraPreview()   // <- the culprit
ON_START -> viewModel.startCameraPreview()
ON_RESUME -> refresh WarningCenter signals
```

The observer is bound to `LocalLifecycleOwner` — which in Compose Navigation is the
`NavBackStackEntry`. Switching to a sibling tab moves that entry to `STOPPED` **even though
the app is still in the foreground**, firing `ON_STOP` → `stopCameraPreview()` →
`cameraProvider.unbindAll()`. The return fires `ON_START` → `startCameraPreview()` →
`setupCamera()` (full unbind + rebind), and the overlay (keyed on `isCameraActive`
flipping `false`→`true` in `RecordScreen.kt:~405`) re-shows for ~1.5s.

The core mistake: this observer conflates **"app went to background"** (camera *should*
release — good citizenship, frees the device for other apps) with **"navigated to another
in-app tab"** (app still foreground — camera *should* stay warm).

`stopCameraPreview()` (`RovaRecordingService.kt:452`) already early-returns
`if (_serviceState.value.isPeriodicActive) return`, so **live recordings are never torn
down** — the rebind only ever hurt the *idle preview*.

---

## Goal

Camera is bound once after first acquire and **stays warm across in-app tab switches**
(instant return, no overlay). It is released only when:

- the whole app goes to the **background**, or
- recording teardown (unchanged — handled elsewhere).

**Accepted trade-off (owner-confirmed 2026-05-31):** the OS camera indicator stays lit
while the user is briefly on Settings/Library, because the camera device remains bound.
This matches the "initialize once, reuse" intent.

---

## Approach (A — process-lifecycle-owned release)

Two halves: **stop releasing on in-app nav**, and **still release on true background**.

### Change 1 — RecordScreen observer becomes acquire-only

`RecordScreen.kt:257-281`. **Remove** the `ON_STOP -> viewModel.stopCameraPreview()` line.
Keep `ON_START -> viewModel.startCameraPreview()` (re-acquire on resume onto the Record tab)
and `ON_RESUME -> refresh signals`. The per-screen `NavBackStackEntry` lifecycle no longer
releases the camera — it only re-acquires and refreshes WarningCenter signals.

### Change 2 — Service owns release via ProcessLifecycleOwner

`RovaRecordingService` registers a **stored** observer on the process-global lifecycle in
`onCreate` and removes it in `onDestroy`:

```kotlin
@Volatile private var appForeground = true   // service is normally created while foreground

private val processObserver = object : DefaultLifecycleObserver {
    override fun onStart(owner: LifecycleOwner) {
        appForeground = true                  // flag ONLY — does not acquire the camera
    }
    override fun onStop(owner: LifecycleOwner) {
        appForeground = false
        stopCameraPreview()                   // isPeriodicActive-guarded (recording untouched)
    }
}
```

- `onCreate`: `ProcessLifecycleOwner.get().lifecycle.addObserver(processObserver)`
- `onDestroy`: `ProcessLifecycleOwner.get().lifecycle.removeObserver(processObserver)`

**`ON_START` updates the flag only — it does not call `startCameraPreview()`.** Acquisition
stays screen-driven (Change 1), so foregrounding the app onto Settings/Library does *not*
wake the camera. Using a **stored** `DefaultLifecycleObserver` (not an anonymous lambda
registration) is required so `onDestroy` can remove the exact instance.

`ProcessLifecycleOwner` is the correct idiom for process-wide foreground/background. Its
`ON_STOP` is intentionally **delayed (~700ms)** after the last activity stops — fine here
(we are not doing millisecond-accurate privacy gating), but it motivates the race fix below.

### Change 3 — Cancellable, foreground-gated acquire (race fix)

**Why:** `startCameraPreview()` (`RovaRecordingService.kt:470`) launches a coroutine that
waits up to 500ms for the UI surface, then calls `setupCamera()`. Without protection, a
process `ON_STOP` can fire *while that coroutine is suspended in the grace delay* —
`stopCameraPreview()` unbinds, then the coroutine resumes and `setupCamera()` **re-binds the
camera after the app is already backgrounded** (privacy/policy bug, flagged in design review).

Fix — store the job, cancel it on release, and re-check foreground before binding:

```kotlin
private var previewStartJob: Job? = null

fun startCameraPreview() {
    if (lifecycleRegistry.currentState < Lifecycle.State.STARTED) {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    }
    if (!_serviceState.value.isCameraActive) {
        previewStartJob?.cancel()
        previewStartJob = serviceScope.launch {
            if (currentSurfaceProvider == null) {
                val waited = withTimeoutOrNull(500) {
                    while (currentSurfaceProvider == null) delay(20)
                }
                RovaLog.d("startCameraPreview: surface grace ${if (waited != null) "UI arrived" else "expired -> DUMMY"}")
            }
            if (!appForeground) {                 // re-check AFTER the suspend point
                RovaLog.d("startCameraPreview: app backgrounded mid-grace, aborting setupCamera")
                return@launch
            }
            setupCamera()
        }
    } else {
        RovaLog.d("startCameraPreview: Camera already active, skipping setup")
    }
}
```

In `stopCameraPreview()`, after the `isPeriodicActive` guard, cancel any in-flight start:

```kotlin
fun stopCameraPreview() {
    if (_serviceState.value.isPeriodicActive) return // Don't stop if recording
    previewStartJob?.cancel(); previewStartJob = null
    RovaLog.d("stopCameraPreview: Unbinding camera for background")
    // ... existing unbind / teardown unchanged ...
}
```

Job cancellation kills the suspended coroutine on release; the `appForeground` re-check
covers the `ProcessLifecycleOwner` `ON_STOP`-delay window. Belt-and-suspenders — either
alone closes the common case, both together close the delayed-dispatch edge.

### Change 4 — Explicit dependency

`lifecycle-process:2.9.4` already resolves on `debugRuntimeClasspath` transitively (via
`lifecycle-runtime-ktx`). Add an **explicit** `implementation("androidx.lifecycle:lifecycle-process:2.9.4")`
in `app/build.gradle.kts` for intent — no version bump.

---

## Behavior matrix

| Event | Before | After |
|---|---|---|
| Record → Settings (tab) | unbind → rebind on return (~1.5s + overlay) | stays warm, instant return |
| App → background (from Record) | released (screen `ON_STOP`) | released (process `ON_STOP`) |
| App → background (from Settings) | **camera left bound (hole)** | released (process `ON_STOP`) ✅ |
| Background mid-grace-delay | could bind after background (latent race) | aborted by job-cancel + `appForeground` re-check ✅ |
| Foreground → Record | rebind | rebind (real resume — expected) |
| Foreground → Settings | no camera | no camera (unchanged) |
| During recording | guard returns | guard returns (unchanged) |

---

## Testing

**Pure-JVM (project policy — `isReturnDefaultValues = true`, no instrumentation):**

Extract a pure decision helper mirroring the in-service release rule so it is unit-asserted:

```kotlin
/** True when an app-background event should release the idle camera preview. */
fun shouldReleaseCameraOnBackground(isRecording: Boolean): Boolean = !isRecording
```

Tests: `isRecording = false` → `true` (release); `isRecording = true` → `false` (keep, FGS owns).
The `onStop` handler calls `stopCameraPreview()` whose `isPeriodicActive` guard *is* this rule;
the helper makes the rule independently testable.

**On-device (mandatory before "done" — project mandate):** Samsung A17 5G / Android 14.
1. Launch → Record shows preview. Switch to Settings, back to Record → **no "Initializing
   Camera" overlay**, instant.
2. From Settings, press Home (background app) → verify camera indicator extinguishes (camera
   released). Re-open → onto Record re-acquires.
3. Start a recording → background the app → recording continues (FGS), camera not torn down.
4. Rapidly tab-hop while backgrounding (stress the grace-delay race) → camera never stays
   lit in background.

---

## Invariants preserved

- **ADR-0006:** recovery scan still only from `MainActivity.onCreate`; FGS-start still
  guarded (`checkFGSStartGuarded`); no `getService` in stop path (`checkStopNoGetService`);
  recovery never deletes (`checkRecoveryNoDeletion`). None touched.
- `stopCameraPreview()`'s `isPeriodicActive` guard unchanged — recording sessions immune.
- No `check*` static gate edited.

## New ADR

**ADR-0021 — Camera warm across in-app navigation.** Documents: release trigger moved from
the per-screen `NavBackStackEntry` lifecycle to the process lifecycle
(`ProcessLifecycleOwner`); idle preview stays warm during in-app nav (privacy trade-off
accepted); recording guard preserved; the cancellable/foreground-gated acquire that prevents
binding the camera after background.

## File touch list

- `app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt` — drop `ON_STOP` release line (Change 1)
- `app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt` — process observer + flag + cancellable acquire (Changes 2, 3)
- `app/build.gradle.kts` — explicit lifecycle-process dep (Change 4)
- `app/src/main/java/com/aritr/rova/service/CameraReleasePolicy.kt` (new, small) — `shouldReleaseCameraOnBackground` helper
- `app/src/test/java/com/aritr/rova/service/CameraReleasePolicyTest.kt` (new) — JVM tests
- `docs/adr/0021-camera-warm-across-nav.md` (new)
