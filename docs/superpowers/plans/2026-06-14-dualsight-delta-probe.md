# DualSight δ-probe Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Empirically prove or disprove whether concurrent front+back **video recording** (the real DualSight workload, not just bindability) works on real hardware, and produce evidence that informs the δ feature plan.

**Architecture:** A throwaway probe branch that bumps CameraX to latest stable locally and ships a debug-only `DualSightProbeActivity`. The probe runs four investigations and logs a verdict: (1) capability queries via CameraX **and** raw Camera2, (2) the real CameraX `CompositionSettings` record workload, (3) a raw-Camera2 concurrent record (version-independent hardware truth), (4) mirror + repeated-rebind torture. The **one keeper** is a pure `ConcurrentCameraCapability` helper (TDD'd; reused verbatim in δ); everything else is throwaway scaffolding.

**Tech Stack:** Kotlin, CameraX 1.5.x (concurrent camera + `CompositionSettings`), Camera2 (`getConcurrentCameraIds`), JUnit (JVM unit tests, `isReturnDefaultValues=true`), adb/logcat. Windows + PowerShell; `gradlew.bat`.

---

## Context the engineer needs

- **Why a probe:** The project is pinned at **CameraX 1.4.2**, which has only the 1.3-era concurrent API (dual *Preview*, no composition). `CompositionSettings` (single-file PiP) needs **1.5.0**; robust concurrent `VideoCapture` needs **1.5.1**. Latest stable is **1.5.3** (1.5.2 fixed an Android-17 dynamic-range crash — relevant at targetSdk 37). The probe branch bumps **locally only**; the mergeable bump is a later separate PR (δ0).
- **Why "real workload":** capability queries and preview-bind success do **not** prove recording works (ISP bandwidth, encoder denial, thermal). The probe records actual short clips.
- **Device:** owner's smoke device is **RZCYA1VBQ2H** (A17-class, single-ISP budget — expected to FAIL concurrent video, but we measure rather than assume). adb MCP wrapper is broken on Windows → drive adb from **PowerShell directly**.
- **Reference implementation:** `google/jetpack-camera-app` → `ConcurrentCameraSession.kt` (the canonical CameraX concurrent-composition recorder). Read it before writing Task 4.
- **House pattern:** framework-touching code gets a pure-Kotlin sibling (JVM-testable). Only `ConcurrentCameraCapability`'s pure core is unit-tested; the device harness is verified on-device by reading logcat.
- **Build:** WARM — `gradlew.bat :app:assembleDebug` (no `--stop`, no cache wipe). Clean only on a real kotlinc/MD5 fault.
- **This is a throwaway branch.** Do NOT wire the probe into `preBuild`, do NOT add a `check*` gate, do NOT touch `CaptureModes.DUALSIGHT_ENABLED`. The probe Activity is debug-only and is deleted before δ.

---

## File Structure

- **Keeper (survives into δ):**
  - `app/src/main/java/com/aritr/rova/service/dualsight/ConcurrentCameraCapability.kt` — pure capability decision over a combo-info list + feature flag. No CameraX imports in the pure core.
  - `app/src/test/java/com/aritr/rova/service/dualsight/ConcurrentCameraCapabilityTest.kt` — JVM unit tests.
- **Throwaway scaffolding (deleted before δ):**
  - `app/src/debug/java/com/aritr/rova/probe/DualSightProbeActivity.kt` — debug-only launcher that runs all probes and logs.
  - `app/src/debug/java/com/aritr/rova/probe/CameraXConcurrentProbe.kt` — attempt A (CameraX `CompositionSettings` record + mirror + rebind torture).
  - `app/src/debug/java/com/aritr/rova/probe/Camera2ConcurrentProbe.kt` — attempt B (raw Camera2 concurrent record).
  - `app/src/debug/AndroidManifest.xml` — registers the debug Activity (debug source set only).
- **Modified (local, throwaway):**
  - `gradle/libs.versions.toml` — bump `camerax` to latest stable on the probe branch.

---

## Task 1: Probe branch + local CameraX bump

**Files:**
- Modify: `gradle/libs.versions.toml` (the `camerax` version line)

- [ ] **Step 1: Create the throwaway probe branch off the spec branch**

```bash
cd "g:/Books/Python/ACTUAL CODES/PROJECTS/rova-android"
git checkout feat/pr-delta-dualsight
git checkout -b probe/dualsight-concurrent-camera
```

- [ ] **Step 2: Find the current CameraX version pin**

Run: `grep -n "camerax" gradle/libs.versions.toml`
Expected: a line like `camerax = "1.4.2"` (and `androidx-camera-*` library lines referencing `camerax`).

- [ ] **Step 3: Bump to latest stable (probe-local only)**

Edit `gradle/libs.versions.toml`: change the `camerax` version value from `1.4.2` to `1.5.3`. Do not touch any other versions.

- [ ] **Step 4: Verify it resolves and compiles**

Run: `gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL. If there are API-break compile errors in existing Single/DualShot code, **record them in the commit message** (they are δ0's regression surface) and apply the minimal fixes to compile — do not refactor.

- [ ] **Step 5: Commit**

```bash
git add gradle/libs.versions.toml
git commit -m "probe: bump CameraX 1.4.2->1.5.3 (throwaway probe branch only)"
```

---

## Task 2: Pure `ConcurrentCameraCapability` (keeper, TDD)

This is the only unit-tested, δ-surviving artifact. It answers "is a front+back concurrent combo bindable?" from data, with no CameraX imports in the pure core so it runs under `isReturnDefaultValues=true`.

**Files:**
- Create: `app/src/main/java/com/aritr/rova/service/dualsight/ConcurrentCameraCapability.kt`
- Test: `app/src/test/java/com/aritr/rova/service/dualsight/ConcurrentCameraCapabilityTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.aritr.rova.service.dualsight

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConcurrentCameraCapabilityTest {

    // A combo is a list of lens facings reported as concurrently-bindable together.
    private val FRONT = LensFacing.FRONT
    private val BACK = LensFacing.BACK

    @Test
    fun featureFlagFalse_neverSupported() {
        val combos = listOf(listOf(FRONT, BACK))
        assertFalse(ConcurrentCameraCapability.supportsFrontBack(hasConcurrentFeature = false, combos = combos))
    }

    @Test
    fun noCombos_notSupported() {
        assertFalse(ConcurrentCameraCapability.supportsFrontBack(hasConcurrentFeature = true, combos = emptyList()))
    }

    @Test
    fun frontBackPairInOneCombo_supported() {
        val combos = listOf(listOf(FRONT, BACK))
        assertTrue(ConcurrentCameraCapability.supportsFrontBack(hasConcurrentFeature = true, combos = combos))
    }

    @Test
    fun onlySameDirectionCombos_notSupported() {
        val combos = listOf(listOf(BACK, BACK), listOf(FRONT, FRONT))
        assertFalse(ConcurrentCameraCapability.supportsFrontBack(hasConcurrentFeature = true, combos = combos))
    }

    @Test
    fun frontOnly_notSupported() {
        assertFalse(ConcurrentCameraCapability.supportsFrontBack(hasConcurrentFeature = true, combos = listOf(listOf(FRONT))))
    }

    @Test
    fun pairAcrossSeparateCombos_notSupported() {
        // A front-only combo and a back-only combo do NOT make a concurrent front+back pair;
        // both facings must appear in the SAME combo.
        val combos = listOf(listOf(FRONT), listOf(BACK))
        assertFalse(ConcurrentCameraCapability.supportsFrontBack(hasConcurrentFeature = true, combos = combos))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.service.dualsight.ConcurrentCameraCapabilityTest"`
Expected: FAIL — `ConcurrentCameraCapability` / `LensFacing` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.aritr.rova.service.dualsight

/** Lens direction, mirrored from CameraX's LENS_FACING_* so the pure core needs no CameraX import. */
enum class LensFacing { FRONT, BACK, OTHER }

/**
 * Pure capability decision for DualSight (ADR-0029 §5). A device supports DualSight only if
 * the platform advertises the concurrent feature AND a single concurrent combo contains BOTH a
 * front- and a back-facing camera. The combo list is sourced at the call site from
 * ProcessCameraProvider.availableConcurrentCameraInfos (mapped to LensFacing) — never a static
 * device allowlist.
 */
object ConcurrentCameraCapability {
    fun supportsFrontBack(hasConcurrentFeature: Boolean, combos: List<List<LensFacing>>): Boolean {
        if (!hasConcurrentFeature) return false
        return combos.any { combo ->
            combo.contains(LensFacing.FRONT) && combo.contains(LensFacing.BACK)
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.service.dualsight.ConcurrentCameraCapabilityTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/service/dualsight/ConcurrentCameraCapability.kt app/src/test/java/com/aritr/rova/service/dualsight/ConcurrentCameraCapabilityTest.kt
git commit -m "feat(dualsight): pure ConcurrentCameraCapability helper + JVM tests (keeper)"
```

> **Gate note:** `service/dualsight/` now references nothing named `FrontBack`, so `checkFrontBackCapabilityGated` stays green. Do not add the module to the gate allowlist on the probe branch — that happens in δ alongside the ADR-0029 §5 amendment.

---

## Task 3: Capability-query harness (CameraX + Camera2) + debug Activity

Logs the authoritative capability evidence. No recording yet — just what the two APIs report, side by side.

**Files:**
- Create: `app/src/debug/java/com/aritr/rova/probe/DualSightProbeActivity.kt`
- Create: `app/src/debug/AndroidManifest.xml`

- [ ] **Step 1: Create the debug manifest registering the probe Activity**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application>
        <activity
            android:name="com.aritr.rova.probe.DualSightProbeActivity"
            android:exported="true"
            android:label="DualSight Probe" />
    </application>
</manifest>
```

- [ ] **Step 2: Create the probe Activity with the query phase**

```kotlin
package com.aritr.rova.probe

import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.aritr.rova.service.dualsight.ConcurrentCameraCapability
import com.aritr.rova.service.dualsight.LensFacing

/**
 * THROWAWAY debug-only probe. Launch with:
 *   adb -s RZCYA1VBQ2H shell am start -n com.aritr.rova/com.aritr.rova.probe.DualSightProbeActivity
 * Read results: adb -s RZCYA1VBQ2H logcat -s DualSightProbe:I
 */
class DualSightProbeActivity : ComponentActivity() {
    private val tag = "DualSightProbe"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runQueries()
    }

    private fun runQueries() {
        val pm = packageManager
        val hasFlag = pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_CONCURRENT)

        // Camera2 platform truth.
        val cm = getSystemService(CameraManager::class.java)
        val camera2Combos: Set<Set<String>> = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) cm.concurrentCameraIds else emptySet()
        } catch (t: Throwable) {
            Log.e(tag, "Camera2 getConcurrentCameraIds threw", t); emptySet()
        }
        Log.i(tag, "QUERY api=${Build.VERSION.SDK_INT} featureFlag=$hasFlag camera2Combos=${camera2Combos.size}")
        camera2Combos.forEach { combo -> Log.i(tag, "  camera2Combo=$combo") }

        // CameraX arbiter.
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val combos = provider.availableConcurrentCameraInfos.map { infoList ->
                infoList.map { info ->
                    when (info.lensFacing) {
                        CameraSelector.LENS_FACING_FRONT -> LensFacing.FRONT
                        CameraSelector.LENS_FACING_BACK -> LensFacing.BACK
                        else -> LensFacing.OTHER
                    }
                }
            }
            val supported = ConcurrentCameraCapability.supportsFrontBack(hasFlag, combos)
            Log.i(tag, "QUERY cameraXCombos=${combos.size} combos=$combos")
            Log.i(tag, "VERDICT(query) frontBackBindable=$supported")
            Log.i(tag, "Next: see CameraXConcurrentProbe (attempt A) + Camera2ConcurrentProbe (attempt B)")
        }, ContextCompat.getMainExecutor(this))
    }
}
```

- [ ] **Step 3: Build**

Run: `gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/debug/
git commit -m "probe: capability-query harness (CameraX + Camera2) + debug Activity"
```

---

## Task 4: Attempt A — CameraX `CompositionSettings` real-record workload

Binds front+back concurrently into ONE `VideoCapture` with a PiP `CompositionSettings`, records ~5 s, and logs success/failure. **Read `google/jetpack-camera-app`'s `ConcurrentCameraSession.kt` first** — match its bind shape; the exact `SingleCameraConfig` overload and `CompositionSettings` builder calls below are the documented 1.5.x API but must be reconciled against the resolved 1.5.3 sources.

**Files:**
- Create: `app/src/debug/java/com/aritr/rova/probe/CameraXConcurrentProbe.kt`
- Modify: `app/src/debug/java/com/aritr/rova/probe/DualSightProbeActivity.kt` (invoke attempt A after the query phase)

- [ ] **Step 1: Write the CameraX concurrent record probe**

```kotlin
package com.aritr.rova.probe

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.CompositionSettings
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import android.content.ContentValues
import android.provider.MediaStore

/**
 * THROWAWAY. Attempt A: the exact intended DualSight pipeline — two concurrent SingleCameraConfigs
 * (rear primary full-frame + front secondary inset) sharing ONE UseCaseGroup (Preview + one
 * VideoCapture) with CompositionSettings → one composited MP4 from one encoder.
 */
class CameraXConcurrentProbe(private val context: Context, private val owner: LifecycleOwner) {
    private val tag = "DualSightProbe"

    @SuppressLint("MissingPermission", "RestrictedApi")
    fun run(preview: Preview, onDone: () -> Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            try {
                val recorder = Recorder.Builder().build()
                val videoCapture = VideoCapture.withOutput(recorder)
                // ATTEMPT mirror-on-front; visually verify in §run-protocol (may be ignored under composition).
                // videoCapture.mirrorMode = MirrorMode.MIRROR_MODE_ON_FRONT_ONLY  // reconcile API name in 1.5.3

                val group = UseCaseGroup.Builder()
                    .addUseCase(preview)
                    .addUseCase(videoCapture)
                    .build()

                // Rear primary: full frame (offset 0,0; scale 1,1).
                val rear = ProcessCameraProvider.SingleCameraConfig(
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    group,
                    CompositionSettings.Builder().setOffset(0f, 0f).setScale(1f, 1f).build(),
                    owner,
                )
                // Front secondary: bottom-right inset (NDC center-origin, Y-up; offset applied AFTER scale).
                val front = ProcessCameraProvider.SingleCameraConfig(
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    group,
                    CompositionSettings.Builder().setOffset(0.5f, -0.5f).setScale(0.3f, 0.3f).build(),
                    owner,
                )

                val concurrent = provider.bindToLifecycle(listOf(rear, front))
                Log.i(tag, "ATTEMPT-A bind OK cameras=${concurrent.cameras.size}")

                val name = "dualsight_probe_${System.currentTimeMillis()}"
                val cv = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                }
                val out = MediaStoreOutputOptions.Builder(
                    context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                ).setContentValues(cv).build()

                var recording: Recording? = null
                recording = videoCapture.output
                    .prepareRecording(context, out)
                    .start(ContextCompat.getMainExecutor(context)) { event ->
                        when (event) {
                            is VideoRecordEvent.Start -> Log.i(tag, "ATTEMPT-A recording started")
                            is VideoRecordEvent.Finalize -> {
                                if (event.hasError()) {
                                    Log.e(tag, "ATTEMPT-A FINALIZE error=${event.error} cause=${event.cause}")
                                    Log.i(tag, "VERDICT(attemptA) recordSucceeded=false")
                                } else {
                                    Log.i(tag, "ATTEMPT-A file=${event.outputResults.outputUri}")
                                    Log.i(tag, "VERDICT(attemptA) recordSucceeded=true (visually verify PiP + front mirror)")
                                }
                                provider.unbindAll()
                                onDone()
                            }
                            else -> {}
                        }
                    }

                preview.let { } // keep ref
                // Stop after ~5s.
                ContextCompat.getMainExecutor(context).let { exec ->
                    android.os.Handler(android.os.Looper.getMainLooper())
                        .postDelayed({ recording?.stop() }, 5_000)
                }
            } catch (t: Throwable) {
                Log.e(tag, "ATTEMPT-A bind/record threw", t)
                Log.i(tag, "VERDICT(attemptA) recordSucceeded=false (exception)")
                onDone()
            }
        }, ContextCompat.getMainExecutor(context))
    }
}
```

- [ ] **Step 2: Wire attempt A into the Activity after the query phase**

In `DualSightProbeActivity`, after the `VERDICT(query)` log, create a `Preview` (with a `SurfaceProvider` from a `PreviewView` set as the content view) and call `CameraXConcurrentProbe(this, this).run(preview) { /* then trigger Task 5 attempt B */ }`. Use a `PreviewView` content view so the surface is real.

- [ ] **Step 3: Build**

Run: `gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL. **If `ProcessCameraProvider.SingleCameraConfig` / `CompositionSettings` symbols don't resolve**, reconcile against the resolved 1.5.3 sources (class may be `ConcurrentCamera.SingleCameraConfig`) and the JCA reference — record the corrected signatures in the commit.

- [ ] **Step 4: Commit**

```bash
git add app/src/debug/
git commit -m "probe: attempt A — CameraX CompositionSettings concurrent record workload"
```

---

## Task 5: Attempt B — raw Camera2 concurrent record (version-independent)

Hardware truth: if CameraX refuses but Camera2 records, the limit is CameraX conservatism, not silicon. Open a concurrent session from `getConcurrentCameraIds` with two recording-capable surfaces and record ~5 s.

**Files:**
- Create: `app/src/debug/java/com/aritr/rova/probe/Camera2ConcurrentProbe.kt`
- Modify: `DualSightProbeActivity.kt` (run attempt B in attempt A's `onDone`)

- [ ] **Step 1: Write the Camera2 concurrent probe**

Open both `CameraDevice`s (front + back from a `getConcurrentCameraIds` pair), give each a `MediaRecorder` surface, create both capture sessions, start both recorders, stop after 5 s. Log each stage and any `CameraAccessException` / `onError` code. Full skeleton:

```kotlin
package com.aritr.rova.probe

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.File

@RequiresApi(Build.VERSION_CODES.R)
@SuppressLint("MissingPermission")
class Camera2ConcurrentProbe(private val context: Context) {
    private val tag = "DualSightProbe"
    private val cm = context.getSystemService(CameraManager::class.java)

    fun run(onDone: () -> Unit) {
        val pair = cm.concurrentCameraIds.firstOrNull { combo ->
            combo.any { facing(it) == "FRONT" } && combo.any { facing(it) == "BACK" }
        }
        if (pair == null) {
            Log.i(tag, "VERDICT(attemptB) recordSucceeded=false (no front+back combo in getConcurrentCameraIds)")
            onDone(); return
        }
        Log.i(tag, "ATTEMPT-B trying combo=$pair")
        // For each id in pair: create a MediaRecorder (720p), openCamera, build a single-target
        // session against the recorder surface, then setRepeatingRequest with RECORD template and
        // start the recorder. Open BOTH before configuring either (concurrent ordering).
        // On any CameraAccessException/onError: log code, set VERDICT(attemptB) recordSucceeded=false.
        // On both recording 5s then stopping cleanly with non-empty files:
        //   Log.i(tag, "VERDICT(attemptB) recordSucceeded=true")
        // (Implement the open/configure/start sequence here; keep it linear and log every transition.)
        onDone()
    }

    private fun facing(id: String): String {
        val c = cm.getCameraCharacteristics(id)
        return when (c.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)) {
            android.hardware.camera2.CameraMetadata.LENS_FACING_FRONT -> "FRONT"
            android.hardware.camera2.CameraMetadata.LENS_FACING_BACK -> "BACK"
            else -> "OTHER"
        }
    }

    private fun newRecorder(file: File): MediaRecorder {
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        r.setVideoSize(1280, 720)
        r.setVideoFrameRate(30)
        r.setOutputFile(file.absolutePath)
        r.prepare()
        return r
    }
}
```

> The engineer fills the open/configure/start sequence (Step 1's commented block) following the Camera2 multi-camera guide (developer.android.com/media/camera/camera2/multi-camera). The **logging contract is fixed**: emit exactly one `VERDICT(attemptB) recordSucceeded=<bool>` line.

- [ ] **Step 2: Run attempt B from attempt A's `onDone` in the Activity (guard `SDK_INT >= R`)**

- [ ] **Step 3: Build**

Run: `gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/debug/
git commit -m "probe: attempt B — raw Camera2 concurrent record (hardware truth)"
```

---

## Task 6: Mirror + repeated-rebind torture (extend attempt A)

**Files:**
- Modify: `app/src/debug/java/com/aritr/rova/probe/CameraXConcurrentProbe.kt`

- [ ] **Step 1: Add a rebind-torture loop**

Add a `runTorture(preview, iterations = 20)` that, only if attempt A succeeded, repeats bind→record-2s→`unbindAll`→rebind `iterations` times, logging `TORTURE iter=N bind=<ok|FAIL code>`. This mimics the segment-boundary rebind under accumulated heat. Log `VERDICT(torture) failedIter=<n or -1>`.

- [ ] **Step 2: Mirror verification is manual** — the front inset is recorded with attempt A's clip; the run protocol (Task 7) inspects it. Add a log reminder: `Log.i(tag, "MIRROR check: open attempt-A clip; front inset text must read correctly")`.

- [ ] **Step 3: Build + commit**

Run: `gradlew.bat :app:assembleDebug` (expect SUCCESS)
```bash
git add app/src/debug/
git commit -m "probe: rebind torture loop + mirror-check reminder"
```

---

## Task 7: Run protocol + evidence capture + decision (manual, on device)

**Files:** none (device run + a results note).

- [ ] **Step 1: Install the debug APK on the device**

```bash
adb -s RZCYA1VBQ2H install -r app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 2: Grant camera + mic + start logcat capture**

```bash
adb -s RZCYA1VBQ2H shell pm grant com.aritr.rova android.permission.CAMERA
adb -s RZCYA1VBQ2H shell pm grant com.aritr.rova android.permission.RECORD_AUDIO
adb -s RZCYA1VBQ2H logcat -c
adb -s RZCYA1VBQ2H logcat -s DualSightProbe:I *:E > gradle_dualsight_probe.log
```

- [ ] **Step 3: Launch the probe Activity**

```bash
adb -s RZCYA1VBQ2H shell am start -n com.aritr.rova/com.aritr.rova.probe.DualSightProbeActivity
```

- [ ] **Step 4: Collect the five verdict lines** from `gradle_dualsight_probe.log`:
`VERDICT(query)`, `VERDICT(attemptA)`, `VERDICT(attemptB)`, `VERDICT(torture)`, and the `MIRROR check` outcome (pull the attempt-A clip, eyeball the front inset).

- [ ] **Step 5: Pull and eyeball the attempt-A clip**

```bash
adb -s RZCYA1VBQ2H shell ls -t /sdcard/Movies | grep dualsight_probe | head -1
adb -s RZCYA1VBQ2H pull /sdcard/Movies/<that-file> .
```
Confirm: rear full-frame + front inset bottom-right; front inset text reads correctly (mirror OK).

- [ ] **Step 6: Record the decision** in a short results note appended to the spec or a new `docs/superpowers/specs/2026-06-14-dualsight-probe-results.md`:

  - **PASS** = `attemptA.recordSucceeded=true` (the intended pipeline works) → δ proceeds, device-smoke locally on RZCYA1VBQ2H. Note mirror result + torture `failedIter`.
  - **PARTIAL** = attemptA false but `attemptB.recordSucceeded=true` → hardware can, CameraX can't on this device/version → δ proceeds but device-smoke needs a CameraX-capable device; capture path may need Camera2 fallback (escalate as a δ design question).
  - **FAIL** = both false → limitation is real on RZCYA1VBQ2H → DualSight builds + ships capability-gated (disabled-tab+explainer is honest here); DualSight-capture device-smoke deferred to borrowed flagship (S21+/Pixel 7+).

- [ ] **Step 7: Commit the results note**

```bash
git add docs/superpowers/specs/2026-06-14-dualsight-probe-results.md gradle_dualsight_probe.log
git commit -m "probe: DualSight concurrent-camera results on RZCYA1VBQ2H"
```

> The `gradle_dualsight_probe.log` here is a deliberate evidence artifact (committed), distinct from the ephemeral root `gradle_*.log` files.

---

## What this plan does NOT cover (separate plans, post-evidence)

- **δ0 plan** — the mergeable CameraX bump PR + full regression smoke (spec §3 δ0). Concrete but device-dependent; write after the probe confirms the target version.
- **δ plan** — the DualSight feature build. **Contingent on probe evidence** (mirror-path resolution, hold-binding-vs-rebind, capable-device venue). Write `docs/superpowers/plans/<date>-dualsight-delta-feature.md` once Task 7 returns a verdict.

---

## Self-Review

- **Spec coverage:** This plan implements spec §3 "Phase δ-probe" in full (capability queries CameraX+Camera2, real-workload attempt A, raw-Camera2 attempt B, mirror probe, rebind torture, run protocol, decision criteria) + the keeper `ConcurrentCameraCapability` from §4.1/§6. Spec §3 δ0 and §4 δ are explicitly deferred to their own plans (scope decomposition). No δ-probe requirement is unaddressed.
- **Placeholder scan:** The only intentionally-unfilled block is Task 5 Step 1's Camera2 open/configure/start sequence — bounded by a fixed logging contract and a cited reference, appropriate for a throwaway hardware probe (the exact Camera2 session wiring is device-discovery work, not a stable API to pre-write). All keeper code (Task 2) is complete. No "TBD/add error handling/similar-to" placeholders.
- **Type consistency:** `LensFacing` enum + `ConcurrentCameraCapability.supportsFrontBack(hasConcurrentFeature, combos)` signature is consistent across Tasks 2 and 3. `VERDICT(...)` log contract consistent across Tasks 3–7. CameraX symbol names (`SingleCameraConfig`, `CompositionSettings`) flagged for reconciliation against resolved 1.5.3 sources in Task 4 Step 3 (honest — the precise package is version-pinned and verified at build).
