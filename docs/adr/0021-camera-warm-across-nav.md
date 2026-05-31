# ADR-0021 — Camera warm across in-app navigation

**Status:** Accepted

**Date:** 2026-05-31

**Relates to:** ADR-0006 (recording lifecycle robustness)

**Origin:** On-device smoke test 2026-05-31 (Samsung Galaxy A17 5G / Android 14), finding #5.

## Context

CameraX is owned by the foreground `RovaRecordingService` — the service is
itself the `LifecycleOwner` and calls `bindToLifecycle`. `RecordScreen` only
supplies a `PreviewView` surface; it does not own the camera.

Previously, `RecordScreen` released the camera through a per-screen
`DisposableEffect` / `LifecycleEventObserver`:

```
ON_STOP  -> viewModel.stopCameraPreview()
ON_START -> viewModel.startCameraPreview()
ON_RESUME -> refresh WarningCenter signals
```

That observer is bound to `LocalLifecycleOwner`, which in Compose Navigation is
the `NavBackStackEntry`. Tab navigation uses
`navigate(route){ launchSingleTop = true }` with **no `popUpTo`**, so the Record
entry survives (its `RecordViewModel` is not destroyed) but moves to `STOPPED`
on every tab switch — **even though the app is still in the foreground**. The
result: CameraX unbinds and cold-rebinds on every Record ↔ Settings ↔ Library
hop, re-showing the ~1.5s "Initializing Camera" overlay (keyed on
`isCameraActive` flipping `false`→`true`) on each return.

The root mistake: the observer conflated two distinct events —
**"the app went to the background"** (camera *should* release, to free the
device for other apps) and **"the user navigated to another in-app tab"**
(app still foreground — camera *should* stay warm).

`stopCameraPreview()` already early-returns
`if (_serviceState.value.isPeriodicActive) return`, so live recordings were
never torn down. The cold-rebind only ever degraded the **idle preview**.

## Decision

Drive idle-camera release from the **process** lifecycle, not the per-screen
`NavBackStackEntry` lifecycle. Three interlocking changes:

### 1. RecordScreen observer becomes acquire-only

Remove `ON_STOP -> stopCameraPreview()`. Keep `ON_START -> startCameraPreview()`
(re-acquire on a real resume onto the Record tab) and `ON_RESUME` (refresh
WarningCenter signals). The per-screen lifecycle no longer releases the camera.

### 2. Service owns release via ProcessLifecycleOwner

`RovaRecordingService` registers a **stored** `DefaultLifecycleObserver` on
`ProcessLifecycleOwner.get().lifecycle` in `onCreate`, removed in `onDestroy`:

- `onStop` (whole app backgrounded) → `appForeground = false`, then
  `stopCameraPreview()` (still `isPeriodicActive`-guarded — recording untouched).
- `onStart` → `appForeground = true` **only**. It does *not* acquire the camera;
  acquisition stays screen-driven (change 1), so foregrounding the app onto a
  non-camera tab (Settings/Library) does not wake the camera.

A stored instance (not an anonymous registration) is required so `onDestroy`
removes the exact same observer.

### 3. Cancellable, foreground-gated acquire

`startCameraPreview()`'s acquisition coroutine is tracked as `previewStartJob`,
cancelled on release, and re-checks `appForeground` after the ≤500ms
surface-grace suspend point, before calling `setupCamera()`. This prevents a
start coroutine suspended in the grace window from binding the camera after the
app has backgrounded.

**Rejected alternatives:**
- **Distinguish nav-type inside RecordScreen** — the `NavBackStackEntry` `ON_STOP`
  fires identically for a tab switch and a real background, so the screen would
  have to re-derive process state; fragile. Rejected.
- **Never unbind; only detach the surface** — keeps the camera bound even when the
  app is in the background unless separate background handling is added anyway;
  worse privacy. Rejected.
- **Extra unconditional `!appForeground` re-check inside `setupSingleCamera`** —
  that bind path is shared with the recording path, so an ungated re-check could
  abort a legitimate background recording bind. For the `startCameraPreview`
  path, analysis (Main-thread confinement + `withContext`/`Mutex.lock`
  prompt-cancellation) shows the cancellable acquire (`previewStartJob` cancel +
  the `!appForeground` re-check) already prevents that path from leaving the
  camera bound after background, so the extra check there is unnecessary.
  Rejected.

  Scope note: the cancel + re-check guard only the `startCameraPreview`
  acquisition. Two other idle-bind launches — `setSurfaceProvider`'s
  `forceReconfigureCamera()` / `setupCamera()` coroutines, fired when the UI
  surface arrives — are *not* `previewStartJob`-tracked or foreground-gated.
  If one is in flight at the instant the app backgrounds it can bind the camera
  after `appForeground` flips, but that is **transient, not a leak**: the same
  `onStop → stopCameraPreview() → unbindAll()` releases it within the inherent
  `ProcessLifecycleOwner` `ON_STOP` window (next bullet). All such launches run
  on `Dispatchers.Main`, so `unbindAll()` (also Main) cannot interleave with an
  in-progress `bindToLifecycle()`; the end state after `onStop` is always
  unbound.

## Consequences

- In-app tab switches keep the camera warm — instant return, no "Initializing
  Camera" overlay.
- **Privacy trade-off (owner-confirmed 2026-05-31):** the OS camera indicator
  stays lit while the user is briefly on Settings/Library, because the camera
  device remains bound. Accepted; it matches the "initialize once, reuse" intent
  and is honest (the camera *is* still bound).
- Closes a prior hole: backgrounding the app *from a non-Record tab* now releases
  the camera (the old per-screen observer could not — `RecordScreen` was not
  composed on those tabs).
- The only remaining bound-while-background window is the inherent
  `ProcessLifecycleOwner` `ON_STOP` dispatch delay (~700ms), after which the
  camera releases.
- The pure rule `shouldReleaseCameraOnBackground(isRecording)` (in
  `CameraReleasePolicy.kt`) mirrors the in-service guard and is unit-tested.

### ADR-0006 invariants preserved

- Recovery scan still only from `MainActivity.onCreate`.
- FGS-start still guarded (`checkFGSStartGuarded`).
- No `getService` in the stop path (`checkStopNoGetService`).
- Recovery never deletes (`checkRecoveryNoDeletion`).
- The `isPeriodicActive` guard in `stopCameraPreview` is unchanged — recording
  sessions are immune to background-driven release (FGS keeps them alive).
