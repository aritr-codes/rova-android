# Camera Warm Across In-App Navigation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep the CameraX idle preview bound across in-app tab switches (no ~1.5s "Initializing Camera" cold-rebind), releasing it only when the whole app backgrounds or during recording teardown.

**Architecture:** Move the camera-release trigger off the per-screen `NavBackStackEntry` lifecycle (which fires `ON_STOP` on tab hops while the app is still foreground) and onto the process lifecycle (`ProcessLifecycleOwner`), owned by `RovaRecordingService` (the camera owner). The per-screen observer becomes acquire-only. A cancellable, foreground-gated acquire prevents a suspended start coroutine from binding the camera after the app has backgrounded.

**Tech Stack:** Kotlin, Jetpack Compose, CameraX, AndroidX Lifecycle (`androidx.lifecycle:lifecycle-process`), kotlinx.coroutines. JVM-only unit tests (`testOptions.unitTests.isReturnDefaultValues = true`); device verification mandatory.

**Spec:** `docs/superpowers/specs/2026-05-31-camera-warm-across-nav-design.md`

---

## File Structure

| File | Responsibility | Change |
|---|---|---|
| `app/src/main/java/com/aritr/rova/service/CameraReleasePolicy.kt` | Pure decision rule for idle-camera release on background | **Create** |
| `app/src/test/java/com/aritr/rova/service/CameraReleasePolicyTest.kt` | JVM tests for the rule | **Create** |
| `app/build.gradle.kts` | Explicit `lifecycle-process` dependency | Modify (~line 1611) |
| `app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt` | Process-lifecycle observer + `appForeground` flag + cancellable/gated acquire | Modify (imports; fields ~158; `onCreate` 682; `stopCameraPreview` 452; `startCameraPreview` 470; `onDestroy` 2994) |
| `app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt` | Per-screen observer → acquire-only | Modify (260) |
| `docs/adr/0021-camera-warm-across-nav.md` | ADR record | **Create** |

The spec is already committed on this branch (`73b611a`). Tasks below are ordered so the codebase compiles after each commit.

---

### Task 1: Pure release-policy helper (TDD)

**Files:**
- Create: `app/src/main/java/com/aritr/rova/service/CameraReleasePolicy.kt`
- Test: `app/src/test/java/com/aritr/rova/service/CameraReleasePolicyTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/aritr/rova/service/CameraReleasePolicyTest.kt`:

```kotlin
package com.aritr.rova.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraReleasePolicyTest {
    @Test
    fun `releases idle preview on background`() {
        assertTrue(shouldReleaseCameraOnBackground(isRecording = false))
    }

    @Test
    fun `keeps camera bound while recording on background`() {
        assertFalse(shouldReleaseCameraOnBackground(isRecording = true))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.service.CameraReleasePolicyTest"`
Expected: FAIL — compile error, unresolved reference `shouldReleaseCameraOnBackground`.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/com/aritr/rova/service/CameraReleasePolicy.kt`:

```kotlin
package com.aritr.rova.service

/**
 * Pure decision rule for releasing the idle camera preview on an
 * app-background event.
 *
 * Mirrors the in-service guard in [RovaRecordingService.stopCameraPreview]:
 * a live recording is FGS-owned and must never be torn down by a background
 * event; only the idle preview is released. Extracted as a pure function so
 * the rule is unit-testable under the project's JVM-only test policy
 * (isReturnDefaultValues = true), where Android framework calls are stubbed.
 *
 * ADR-0021 — Camera warm across in-app navigation.
 */
fun shouldReleaseCameraOnBackground(isRecording: Boolean): Boolean = !isRecording
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.service.CameraReleasePolicyTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/service/CameraReleasePolicy.kt app/src/test/java/com/aritr/rova/service/CameraReleasePolicyTest.kt
git commit -m "feat(camera): pure shouldReleaseCameraOnBackground helper + tests

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: Explicit lifecycle-process dependency

**Files:**
- Modify: `app/build.gradle.kts` (~line 1611, next to `lifecycle-viewmodel-compose`)

`lifecycle-process:2.9.4` already resolves transitively on `debugRuntimeClasspath` (via `lifecycle-runtime-ktx`). Declaring it explicitly documents the intent (we use `ProcessLifecycleOwner` directly) and pins it. No version bump.

- [ ] **Step 1: Add the dependency line**

In `app/build.gradle.kts`, find:

```kotlin
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
```

Add immediately below it:

```kotlin
    // ProcessLifecycleOwner — app foreground/background for camera-warm-across-nav (ADR-0021)
    implementation("androidx.lifecycle:lifecycle-process:2.9.4")
```

- [ ] **Step 2: Verify it resolves / compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/build.gradle.kts
git commit -m "build: explicit androidx.lifecycle:lifecycle-process dependency

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: Process-lifecycle observer + appForeground flag (Change 2)

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt`
  - imports (after line 39, with the other `androidx.lifecycle.*` imports)
  - fields (after line 158, `private val setupMutex = ...`)
  - `onCreate` (682)
  - `onDestroy` (2994)

This task wires the observer and declares the two new fields (`appForeground`, `previewStartJob`) and the observer instance. `previewStartJob` is *declared* here (grouped with the other coroutine-job fields) but only *used* in Task 4 — declaring it now keeps `processObserver.onStop → stopCameraPreview()` self-consistent across tasks.

No JVM unit test: `ProcessLifecycleOwner` / `DefaultLifecycleObserver` are Android-framework types stubbed to no-ops under `isReturnDefaultValues = true`, so an observer-wiring test would be tautological. The rule itself is covered by Task 1; the wiring is covered by `compileDebugKotlin` here and on-device verification in Task 6.

- [ ] **Step 1: Add imports**

In `app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt`, the block at lines 37-40 currently reads:

```kotlin
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.Observer
```

Add two imports so it reads:

```kotlin
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.Observer
import androidx.lifecycle.ProcessLifecycleOwner
```

- [ ] **Step 2: Add fields**

Find (line 158):

```kotlin
    private val setupMutex = kotlinx.coroutines.sync.Mutex()
```

Add immediately below it:

```kotlin
    // ADR-0021 — camera warm across in-app navigation.
    // `appForeground` tracks the process foreground/background state
    // (NOT the per-screen NavBackStackEntry lifecycle, which fires ON_STOP
    // on in-app tab switches while the app is still foreground). Written on
    // the main thread by `processObserver`; read on the main thread inside
    // the `startCameraPreview` coroutine (serviceScope is Dispatchers.Main).
    // @Volatile is belt-and-suspenders against future dispatcher changes.
    @Volatile private var appForeground = true
    // In-flight idle-preview acquisition; cancelled on release so a coroutine
    // suspended in the surface-grace window cannot bind after the app has
    // backgrounded (see startCameraPreview / stopCameraPreview, Task 4).
    private var previewStartJob: Job? = null
    // Process-global foreground/background observer. ON_START only flips the
    // flag (acquisition stays screen-driven so foregrounding onto a non-camera
    // tab does not wake the camera); ON_STOP releases the idle preview
    // (stopCameraPreview is isPeriodicActive-guarded — recording is untouched).
    // Stored instance (not anonymous) so onDestroy removes this exact observer.
    private val processObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            appForeground = true
        }
        override fun onStop(owner: LifecycleOwner) {
            appForeground = false
            stopCameraPreview()
        }
    }
```

- [ ] **Step 3: Register the observer in onCreate**

Find (lines 682-687):

```kotlin
    override fun onCreate() {
        super.onCreate()
        currentMode = RovaSettings(this).mode
        lifecycleRegistry = LifecycleRegistry(this)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        createNotificationChannel()
```

Add the registration as the last statement of `onCreate`, immediately after `createNotificationChannel()` and before the existing `// C12:` comment:

```kotlin
    override fun onCreate() {
        super.onCreate()
        currentMode = RovaSettings(this).mode
        lifecycleRegistry = LifecycleRegistry(this)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        createNotificationChannel()
        // ADR-0021 — release idle camera preview when the whole app
        // backgrounds (not on in-app tab switches).
        ProcessLifecycleOwner.get().lifecycle.addObserver(processObserver)
```

(Leave the existing `// C12:` block and the rest of `onCreate` unchanged.)

- [ ] **Step 4: Remove the observer in onDestroy**

Find (lines 2994-2998):

```kotlin
    override fun onDestroy() {
        super.onDestroy()
        releaseResources()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }
```

Change to:

```kotlin
    override fun onDestroy() {
        super.onDestroy()
        // ADR-0021 — symmetric removal of the process-lifecycle observer.
        ProcessLifecycleOwner.get().lifecycle.removeObserver(processObserver)
        releaseResources()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }
```

- [ ] **Step 5: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt
git commit -m "feat(camera): release idle preview via ProcessLifecycleOwner (ADR-0021)

Move the camera-release trigger off the per-screen NavBackStackEntry
lifecycle onto the process lifecycle. ON_STOP releases the idle preview
(recording-guarded); ON_START only updates the appForeground flag.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 4: Cancellable, foreground-gated acquire (Change 3 — race fix)

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt`
  - `stopCameraPreview` (452-468)
  - `startCameraPreview` (470-502)

Closes the race where a `startCameraPreview` coroutine, suspended in the ≤500ms surface-grace window, resumes and binds the camera *after* a process `ON_STOP` already released — binding the camera while the app is backgrounded. Job-cancellation kills the suspended coroutine on release; the `appForeground` re-check before `setupCamera()` covers the `ProcessLifecycleOwner` `ON_STOP`-dispatch delay (~700ms).

- [ ] **Step 1: Cancel the in-flight start in stopCameraPreview**

Find (lines 452-454):

```kotlin
    fun stopCameraPreview() {
        if (_serviceState.value.isPeriodicActive) return // Don't stop if recording
        RovaLog.d("stopCameraPreview: Unbinding camera for background")
```

Insert the cancel line between the guard and the log (so a recording session — which early-returns — never cancels anything):

```kotlin
    fun stopCameraPreview() {
        if (_serviceState.value.isPeriodicActive) return // Don't stop if recording
        previewStartJob?.cancel()
        previewStartJob = null
        RovaLog.d("stopCameraPreview: Unbinding camera for background")
```

(The rest of `stopCameraPreview` — unbindAll, dual teardown, markCameraUnbound, state update — is unchanged.)

- [ ] **Step 2: Track the job and re-check foreground in startCameraPreview**

Find (lines 470-502):

```kotlin
    fun startCameraPreview() {
        if (lifecycleRegistry.currentState < Lifecycle.State.STARTED) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        }
        if (!_serviceState.value.isCameraActive) {
            serviceScope.launch {
                // Smoke-test fix: brief grace window for the UI's
                // SurfaceProvider to attach before falling back to the
                // headless DUMMY surface. ServiceConnection.onServiceConnected
                // typically fires before RecordScreen's DisposableEffect runs
                // setSurfaceProvider, so without this window the cold-start
                // first loop binds to DUMMY and the later UI swap leaves
                // PreviewView black on devices where CameraX does not
                // re-cycle the SurfaceRequest. Background-only startup (no
                // UI ever) still proceeds via DUMMY after the timeout — the
                // existing 3 s `surfaceProviderReady.await()` in
                // startPeriodicRecording is the headless ceiling, so this
                // 500 ms window cannot delay a true headless launch by more
                // than a frame.
                if (currentSurfaceProvider == null) {
                    val waited = withTimeoutOrNull(500) {
                        while (currentSurfaceProvider == null) delay(20)
                    }
                    RovaLog.d(
                        "startCameraPreview: surface grace ${if (waited != null) "UI arrived" else "expired -> DUMMY"}"
                    )
                }
                setupCamera()
            }
        } else {
            RovaLog.d("startCameraPreview: Camera already active, skipping setup")
        }
    }
```

Replace with (assign the launch to `previewStartJob`, cancel any prior in-flight start, and re-check `appForeground` after the suspend point before binding):

```kotlin
    fun startCameraPreview() {
        if (lifecycleRegistry.currentState < Lifecycle.State.STARTED) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        }
        if (!_serviceState.value.isCameraActive) {
            previewStartJob?.cancel()
            previewStartJob = serviceScope.launch {
                // Smoke-test fix: brief grace window for the UI's
                // SurfaceProvider to attach before falling back to the
                // headless DUMMY surface. ServiceConnection.onServiceConnected
                // typically fires before RecordScreen's DisposableEffect runs
                // setSurfaceProvider, so without this window the cold-start
                // first loop binds to DUMMY and the later UI swap leaves
                // PreviewView black on devices where CameraX does not
                // re-cycle the SurfaceRequest. Background-only startup (no
                // UI ever) still proceeds via DUMMY after the timeout — the
                // existing 3 s `surfaceProviderReady.await()` in
                // startPeriodicRecording is the headless ceiling, so this
                // 500 ms window cannot delay a true headless launch by more
                // than a frame.
                if (currentSurfaceProvider == null) {
                    val waited = withTimeoutOrNull(500) {
                        while (currentSurfaceProvider == null) delay(20)
                    }
                    RovaLog.d(
                        "startCameraPreview: surface grace ${if (waited != null) "UI arrived" else "expired -> DUMMY"}"
                    )
                }
                // ADR-0021 — the app may have backgrounded while we waited in
                // the grace window. Do NOT bind the camera after a background
                // event (privacy/policy). stopCameraPreview also cancels this
                // job on ON_STOP; this re-check covers the ProcessLifecycleOwner
                // ON_STOP dispatch delay.
                if (!appForeground) {
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

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt
git commit -m "fix(camera): cancellable, foreground-gated preview acquire (ADR-0021)

Prevent a start coroutine suspended in the surface-grace window from
binding the camera after the app has backgrounded: cancel previewStartJob
on release and re-check appForeground before setupCamera().

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 5: RecordScreen observer → acquire-only (Change 1)

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt` (260)

Remove the per-screen `ON_STOP → stopCameraPreview()` so an in-app tab switch (which drives the Record `NavBackStackEntry` to `STOPPED`) no longer releases the camera. Release is now owned by the process observer (Task 3). Keep `ON_START` (re-acquire on real resume onto the Record tab) and `ON_RESUME` (refresh WarningCenter signals).

- [ ] **Step 1: Remove the ON_STOP release line**

Find (lines 258-261):

```kotlin
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> viewModel.stopCameraPreview()
                Lifecycle.Event.ON_START -> viewModel.startCameraPreview()
```

Delete the `ON_STOP` branch so it reads:

```kotlin
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                // ADR-0021 — camera release is owned by the process lifecycle
                // (RovaRecordingService's ProcessLifecycleOwner observer), NOT
                // this per-screen NavBackStackEntry lifecycle, which fires
                // ON_STOP on in-app tab switches while the app is still
                // foreground. This observer is acquire-only.
                Lifecycle.Event.ON_START -> viewModel.startCameraPreview()
```

(Leave the `ON_RESUME` branch and everything after it unchanged.)

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt
git commit -m "feat(camera): RecordScreen observer is acquire-only (ADR-0021)

Drop ON_STOP -> stopCameraPreview() so in-app tab switches keep the camera
warm. Release is owned by the process lifecycle. Keeps the ~1.5s
'Initializing Camera' overlay from re-showing on every tab return.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 6: ADR-0021 + full gate + device verification

**Files:**
- Create: `docs/adr/0021-camera-warm-across-nav.md`

- [ ] **Step 1: Confirm the next ADR number**

Run: `ls docs/adr/ | tail -5`
Expected: highest existing is `0020-*`. If `0021-*` already exists, use the next free number and adjust the filename + references in `CameraReleasePolicy.kt` / code comments accordingly.

- [ ] **Step 2: Write the ADR**

Create `docs/adr/0021-camera-warm-across-nav.md` (match the heading style of an existing ADR, e.g. `docs/adr/0020-*`):

```markdown
# ADR-0021: Camera warm across in-app navigation

**Status:** Accepted
**Date:** 2026-05-31
**Relates to:** ADR-0006 (recording lifecycle robustness)

## Context

CameraX is owned by the foreground `RovaRecordingService`. `RecordScreen`
supplies a `PreviewView` surface and previously released the camera via a
per-screen `DisposableEffect`/`LifecycleEventObserver`
(`ON_STOP -> stopCameraPreview()`). Tab navigation uses
`navigate(route){ launchSingleTop = true }` with no `popUpTo`, so the Record
`NavBackStackEntry` survives but moves to `STOPPED` on every tab switch —
even though the app is still in the foreground. The result: CameraX unbinds
and cold-rebinds on every tab hop, showing a ~1.5s "Initializing Camera"
overlay on each return (smoke-test finding #5, Samsung A17 5G / Android 14).

The observer conflated "app went to background" (camera should release) with
"navigated to another in-app tab" (camera should stay warm).

## Decision

Release the idle camera preview based on the **process** lifecycle, not the
per-screen `NavBackStackEntry` lifecycle:

1. `RecordScreen`'s observer becomes **acquire-only** (keeps `ON_START`
   re-acquire and `ON_RESUME` signal refresh; drops `ON_STOP` release).
2. `RovaRecordingService` registers a `ProcessLifecycleOwner` observer in
   `onCreate` (removed in `onDestroy`). `ON_STOP` (whole app backgrounded)
   calls `stopCameraPreview()` — already `isPeriodicActive`-guarded, so a
   live recording is untouched. `ON_START` only updates an `appForeground`
   flag; acquisition stays screen-driven so foregrounding onto a non-camera
   tab does not wake the camera.
3. Idle-preview acquisition is cancellable and foreground-gated: the start
   coroutine is tracked as `previewStartJob`, cancelled on release, and
   re-checks `appForeground` after the surface-grace suspend point before
   calling `setupCamera()`. This prevents binding the camera after the app
   has backgrounded.

## Consequences

- In-app tab switches keep the camera warm — instant return, no overlay.
- The OS camera indicator stays lit while the user is briefly on
  Settings/Library (the camera device remains bound). Accepted trade-off
  (owner-confirmed 2026-05-31): matches "initialize once, reuse".
- Closes a prior hole: backgrounding the app *from a non-Record tab* now
  releases the camera (the old per-screen observer could not, as RecordScreen
  was not composed).
- ADR-0006 invariants preserved: recovery scan still only from
  `MainActivity.onCreate`; FGS-start still guarded; no `getService` in the
  stop path; recovery never deletes. The `isPeriodicActive` guard in
  `stopCameraPreview` is unchanged.
```

- [ ] **Step 3: Commit the ADR**

```bash
git add docs/adr/0021-camera-warm-across-nav.md
git commit -m "docs(adr): ADR-0021 camera warm across in-app navigation

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

- [ ] **Step 4: Full gate — tests, lint, static check\* gates**

Run each and confirm green:

```bash
./gradlew :app:testDebugUnitTest        # full unit suite — expect 0 failures (1327+ tests + 2 new)
./gradlew :app:lintDebug                # expect 0 errors
./gradlew :app:compileDebugAndroidTest  # androidTest still compiles
./gradlew :app:assembleDebug            # APK builds
```

The project's static `check*` gates (`checkFGSStartGuarded`, `checkStopNoGetService`, `checkRecoveryNoDeletion`) run as part of the build/verification — confirm none regressed. **Do NOT edit any `check*` gate to make it pass** (project rule); if one fails, fix the code or escalate.

- [ ] **Step 5: On-device verification (MANDATORY before "done")**

Install on the Samsung A17 5G / Android 14 device and verify the spec's test scenarios:

```bash
./gradlew :app:installDebug
```

1. Launch → Record shows preview. Switch Record → Settings → back to Record: **no "Initializing Camera" overlay**, preview is instant.
2. Same for Record → Library → back.
3. From Settings, press Home (background the app): confirm the OS camera indicator extinguishes (camera released). Re-open the app onto Record → camera re-acquires (overlay here is acceptable — real resume).
4. Start a recording → background the app: recording continues (FGS), camera not torn down. Return → recording HUD intact.
5. Stress the race: rapidly tab-hop while backgrounding the app → the camera indicator must NOT remain lit while the app is in the background.

Capture results. If any scenario fails, fix before marking the branch done.

---

## Self-Review

**1. Spec coverage:**
- Change 1 (acquire-only screen observer) → Task 5 ✅
- Change 2 (ProcessLifecycleOwner observer + appForeground) → Task 3 ✅
- Change 3 (cancellable + foreground-gated acquire) → Task 4 ✅
- Change 4 (explicit lifecycle-process dep) → Task 2 ✅
- Pure `shouldReleaseCameraOnBackground` test seam → Task 1 ✅
- ADR-0021 → Task 6 ✅
- On-device verification (project mandate) → Task 6 Step 5 ✅
- ADR-0006 invariants preserved (no `check*` edit) → Task 6 Step 4 ✅

**2. Placeholder scan:** No TBD/TODO/"handle edge cases". All code steps show complete code. ADR number contingency handled in Task 6 Step 1.

**3. Type/symbol consistency:** `appForeground` (field, Task 3) — read in Task 4. `previewStartJob: Job?` (field, Task 3) — used in Task 4 start/stop. `processObserver` (Task 3) — added in `onCreate`, removed in `onDestroy` (same instance). `shouldReleaseCameraOnBackground(isRecording: Boolean)` consistent between Task 1 impl and test. Imports `DefaultLifecycleObserver`, `ProcessLifecycleOwner` added in Task 3 Step 1. `Job` already imported (line 63). Consistent.
