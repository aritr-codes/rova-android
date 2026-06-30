# SingleVideoRecorder Extraction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract the single-mode CameraX recording lifecycle out of `RovaRecordingService.kt` into a new `service/singlerecord/SingleVideoRecorder` collaborator, mirroring `service/dualrecord/DualVideoRecorder`, with zero behavior change.

**Architecture:** A narrow seam. `SingleVideoRecorder` builds & owns `VideoCapture<Recorder>` and the per-segment `Recording` start; `SingleRecording` is the idempotent active-segment handle. The service keeps the segment loop, watchdog, finalize-coordination deferreds, persistence, `performMerge`, terminal writes, wakelock, and recovery flags — exactly as the dual path keeps `recordSegmentDual`/`performMergeDual`. CameraX `VideoRecordEvent` is passed straight through (no event remap).

**Tech Stack:** Kotlin, CameraX 1.4.2 (`VideoCapture`/`Recorder`/`Recording`/`QualitySelector`), JUnit (JVM unit tests), Gradle 9.4.1 / AGP 9.2.1, build-logic included build (`SourceCheckTask` + `RovaGateRules`).

## Global Constraints

- **Zero behavior change.** Segment cadence, both STOP paths (user-stop vs loop-exhaust), drift policy, terminal-write ordering, recovery handoff stay byte-equivalent.
- **Verify via `:app:assembleDebug` (fires all 47 gates on `preBuild`) + `:app:testDebugUnitTest`.** NOT `:app:lintDebug` (pre-existing `VaultAndroidOps:267` NewApi RED is unrelated).
- **Gate rule:** change only WHERE the boundary is declared, never WHAT it enforces; message byte-identical; prove the gate still RED-fires on a real violation. Amend ADR → regenerate/extend gate → move code. Never edit a gate green.
- **Untouched gates** (must stay green, no changes): `checkExportPipelineSingleEntry`, `checkCompletedWriteOnlyFromPerformMerge`, `checkUserStoppedBeforeMerge`, `checkAtomicTerminalWriteForbiddenPair`, `checkWakeLock*`, `checkStopNoGetService`.
- **Single gate amended:** `checkSetTargetRotationBoundaryOnly` (ADR-0029 §3) — add `service/singlerecord/` to the allowlist only.
- **Device (RZCYA1VBQ2H) mandatory** — emulators fail CameraX video. Verify single AND DualShot both record.
- **Execution model:** subagents EDIT-ONLY on Windows; the controller runs all gradle/git on the shared daemon.
- **Baseline:** 1241 tests / 0-0-0 on master, plus the new build-logic golden cases and the new `SingleQualitySelector` test.
- Branch: `feat/single-video-recorder-extraction` (already created; spec committed `fc59860`).

**Spec:** `docs/superpowers/specs/2026-06-30-single-video-recorder-extraction-design.md`

---

## File structure

| File | Responsibility | Task |
|------|----------------|------|
| `docs/adr/0029-capture-topology-orientation-policy.md` | Amend §3 to name `service/singlerecord/` a rotation-boundary package | 1 |
| `build-logic/src/main/kotlin/com/aritr/rova/gradle/RovaGateRules_ModeRotation.kt` | Add `service/singlerecord/` to `ruleSetTargetRotationBoundaryOnly` allowlist | 1 |
| `build-logic/src/test/kotlin/com/aritr/rova/gradle/RovaGateRules_ModeRotationTest.kt` | Golden: new GREEN (singlerecord allowed) + retained RED (non-boundary) | 1 |
| `app/src/main/java/com/aritr/rova/service/singlerecord/SingleQuality.kt` | Pure enum + `SingleQualitySelector.forResolution` (resolutionStr → SingleQuality) | 2 |
| `app/src/test/java/com/aritr/rova/service/singlerecord/SingleQualitySelectorTest.kt` | JVM test for the quality map | 2 |
| `app/src/main/java/com/aritr/rova/service/singlerecord/SingleVideoRecorderConfig.kt` | Immutable config (resolutionStr, buildTimeTargetRotation) | 2 |
| `app/src/main/java/com/aritr/rova/service/singlerecord/SingleRecording.kt` | Idempotent active-segment handle wrapping CameraX `Recording` | 2 |
| `app/src/main/java/com/aritr/rova/service/singlerecord/SingleVideoRecorder.kt` | Builds/owns `VideoCapture<Recorder>`; `start(...)`/`release()` | 2 |
| `app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt` | Delegate build (`setupSingleCamera`) + start/stop (`recordSegment`); swap `currentRecording`→`currentSingleRecording` | 3, 4 |

---

## Task 1: ADR + gate allowlist + golden test (build-logic only)

Establishes the rotation-boundary amendment **before** any `setTargetRotation(` line moves into the new package. TDD on the gate rule itself.

**Files:**
- Modify: `docs/adr/0029-capture-topology-orientation-policy.md` (§3 boundary clause)
- Modify: `build-logic/src/main/kotlin/com/aritr/rova/gradle/RovaGateRules_ModeRotation.kt:109-125`
- Test: `build-logic/src/test/kotlin/com/aritr/rova/gradle/RovaGateRules_ModeRotationTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `service/singlerecord/` is an allowlisted rotation-boundary path for `ruleSetTargetRotationBoundaryOnly`. Message unchanged: `"ADR-0029 §3: setTargetRotation outside boundary-owning files:\n"`.

- [ ] **Step 1: Locate the existing golden test for this rule.** Open `build-logic/src/test/kotlin/com/aritr/rova/gradle/` and find the test file covering `ruleSetTargetRotationBoundaryOnly` (e.g. `RovaGateRules_ModeRotationTest.kt`). Confirm there is an existing RED case asserting a non-boundary `setTargetRotation(` returns a non-null message, and an existing GREEN case for `service/RovaRecordingService.kt` / `service/dualrecord/`. If the file does not exist, create it following the sibling `RovaGateRules_*Test.kt` style (construct `SourceFile(relPath, lines, text)` and call `ruleSetTargetRotationBoundaryOnly(listOf(...))`).

- [ ] **Step 2: Add the new failing GREEN test case (singlerecord must be allowed).**

```kotlin
@Test
fun setTargetRotation_inSingleRecordPackage_isAllowed() {
    val src = SourceFile(
        relPath = "com/aritr/rova/service/singlerecord/SingleVideoRecorder.kt",
        lines = listOf("        videoCapture = VideoCapture.Builder(recorder).setTargetRotation(rot).build()"),
        text = "        videoCapture = VideoCapture.Builder(recorder).setTargetRotation(rot).build()\n",
    )
    assertNull(ruleSetTargetRotationBoundaryOnly(listOf(src)))
}

@Test
fun setTargetRotation_outsideBoundary_stillRedFires() {
    val src = SourceFile(
        relPath = "com/aritr/rova/ui/screens/RecordScreen.kt",
        lines = listOf("    preview.setTargetRotation(rot)"),
        text = "    preview.setTargetRotation(rot)\n",
    )
    val msg = ruleSetTargetRotationBoundaryOnly(listOf(src))
    assertNotNull(msg)
    assertTrue(msg!!.startsWith("ADR-0029 §3: setTargetRotation outside boundary-owning files:"))
}
```

(If a `setTargetRotation_outsideBoundary` RED case already exists, keep it and add only the new GREEN case; do not duplicate.)

- [ ] **Step 3: Run the golden test — verify the new GREEN case FAILS.**

Run: `./gradlew :build-logic:test --tests "com.aritr.rova.gradle.RovaGateRules_ModeRotationTest"`
Expected: `setTargetRotation_inSingleRecordPackage_isAllowed` FAILS (returns the ADR-0029 §3 message, expected null) because the allowlist does not yet include `service/singlerecord/`. `setTargetRotation_outsideBoundary_stillRedFires` PASSES.

- [ ] **Step 4: Amend the gate allowlist.** In `RovaGateRules_ModeRotation.kt`, `ruleSetTargetRotationBoundaryOnly`, change:

```kotlin
        val allowed = rel.endsWith("service/RovaRecordingService.kt") ||
            rel.contains("service/dualrecord/")
```

to:

```kotlin
        val allowed = rel.endsWith("service/RovaRecordingService.kt") ||
            rel.contains("service/dualrecord/") ||
            rel.contains("service/singlerecord/")
```

Also update the rule's KDoc line 105 from `Forbid setTargetRotation( outside service/RovaRecordingService.kt and service/dualrecord/.` to `Forbid setTargetRotation( outside service/RovaRecordingService.kt, service/dualrecord/, and service/singlerecord/.`

- [ ] **Step 5: Run the golden test — verify BOTH cases PASS.**

Run: `./gradlew :build-logic:test --tests "com.aritr.rova.gradle.RovaGateRules_ModeRotationTest"`
Expected: PASS (GREEN allows singlerecord; RED still fires on `ui/screens/RecordScreen.kt`).

- [ ] **Step 6: Amend ADR-0029 §3.** In `docs/adr/0029-capture-topology-orientation-policy.md`, find the §3 clause that enumerates the rotation-boundary-owning files (the clause the gate message cites). Add `service/singlerecord/` to the list, with a one-line note: "`service/singlerecord/SingleVideoRecorder` owns the single-mode `VideoCapture` use case and its build-time `setTargetRotation`, symmetric with `service/dualrecord/` for the dual pipeline (2026-06-30)." Do not change the invariant wording — only extend the boundary membership.

- [ ] **Step 7: Commit.**

```bash
git add docs/adr/0029-capture-topology-orientation-policy.md build-logic/src/main/kotlin/com/aritr/rova/gradle/RovaGateRules_ModeRotation.kt build-logic/src/test/kotlin/com/aritr/rova/gradle/RovaGateRules_ModeRotationTest.kt
git commit -m "feat(gate): allowlist service/singlerecord/ for setTargetRotation boundary (ADR-0029 §3)"
```

---

## Task 2: New `service/singlerecord/` package (config, quality helper, recording handle, recorder)

Creates the collaborator. New files only — no service wiring yet, so the app is behavior-identical (new class unused). The package's build-time `setTargetRotation(` is now gate-legal (Task 1).

**Files:**
- Create: `app/src/main/java/com/aritr/rova/service/singlerecord/SingleQuality.kt`
- Create: `app/src/main/java/com/aritr/rova/service/singlerecord/SingleVideoRecorderConfig.kt`
- Create: `app/src/main/java/com/aritr/rova/service/singlerecord/SingleRecording.kt`
- Create: `app/src/main/java/com/aritr/rova/service/singlerecord/SingleVideoRecorder.kt`
- Test: `app/src/test/java/com/aritr/rova/service/singlerecord/SingleQualitySelectorTest.kt`

**Interfaces:**
- Consumes: `com.aritr.rova.data.QualityPresets` (string labels `UHD`/`FHD`/`HD`/`SD`); CameraX `Quality`, `QualitySelector`, `FallbackStrategy`, `Recorder`, `VideoCapture`, `Recording`, `FileOutputOptions`, `VideoRecordEvent`; `androidx.core.util.Consumer`; `com.aritr.rova.utils.RovaLog`.
- Produces:
  - `enum class SingleQuality { UHD, FHD, HD, SD }`
  - `object SingleQualitySelector { fun forResolution(resolutionStr: String): SingleQuality }`
  - `data class SingleVideoRecorderConfig(val resolutionStr: String, val buildTimeTargetRotation: Int)`
  - `class SingleRecording internal constructor(recording: Recording) { fun stop() }`
  - `class SingleVideoRecorder(config: SingleVideoRecorderConfig) { val videoCapture: VideoCapture<Recorder>; fun start(context: Context, outputOptions: FileOutputOptions, segmentRotation: Int, enableAudio: Boolean, executor: Executor, callback: Consumer<VideoRecordEvent>): SingleRecording; fun release() }`

- [ ] **Step 1: Write the failing test for `SingleQualitySelector`.**

Create `app/src/test/java/com/aritr/rova/service/singlerecord/SingleQualitySelectorTest.kt`:

```kotlin
package com.aritr.rova.service.singlerecord

import com.aritr.rova.data.QualityPresets
import org.junit.Assert.assertEquals
import org.junit.Test

class SingleQualitySelectorTest {
    @Test fun maps_uhd() = assertEquals(SingleQuality.UHD, SingleQualitySelector.forResolution(QualityPresets.UHD))
    @Test fun maps_fhd() = assertEquals(SingleQuality.FHD, SingleQualitySelector.forResolution(QualityPresets.FHD))
    @Test fun maps_hd() = assertEquals(SingleQuality.HD, SingleQualitySelector.forResolution(QualityPresets.HD))
    @Test fun maps_sd() = assertEquals(SingleQuality.SD, SingleQualitySelector.forResolution(QualityPresets.SD))
    @Test fun unknown_fallsBackToFhd() = assertEquals(SingleQuality.FHD, SingleQualitySelector.forResolution("garbage"))
}
```

- [ ] **Step 2: Run the test to verify it fails (does not compile / unresolved).**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.service.singlerecord.SingleQualitySelectorTest"`
Expected: FAIL — `SingleQuality`/`SingleQualitySelector` unresolved.

- [ ] **Step 3: Create `SingleQuality.kt` (pure enum + selector).**

```kotlin
package com.aritr.rova.service.singlerecord

import com.aritr.rova.data.QualityPresets

/**
 * Pure, JVM-testable representation of the single-mode quality choice.
 * D-deviation (house pattern): the CameraX `androidx.camera.video.Quality`
 * type is constructed only at the recorder edge ([SingleVideoRecorder]); this
 * enum carries the decision so the resolution→quality map is unit-testable
 * under `isReturnDefaultValues = true` without touching the camera AAR.
 *
 * Verbatim lift of the `when (resolutionStr)` map formerly inline in
 * `RovaRecordingService.setupSingleCamera` (the strict-match-then-FHD-fallback
 * contract: non-canonical labels fall through to FHD; we deliberately do NOT
 * route through QualityPresets.canonicalize).
 */
enum class SingleQuality { UHD, FHD, HD, SD }

object SingleQualitySelector {
    fun forResolution(resolutionStr: String): SingleQuality = when (resolutionStr) {
        QualityPresets.UHD -> SingleQuality.UHD
        QualityPresets.FHD -> SingleQuality.FHD
        QualityPresets.HD -> SingleQuality.HD
        QualityPresets.SD -> SingleQuality.SD
        else -> SingleQuality.FHD
    }
}
```

- [ ] **Step 4: Run the test to verify it passes.**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.service.singlerecord.SingleQualitySelectorTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Create `SingleVideoRecorderConfig.kt`.**

```kotlin
package com.aritr.rova.service.singlerecord

/**
 * Immutable construction config for [SingleVideoRecorder]. Cheap to build.
 *
 * @param resolutionStr canonical QualityPresets label driving the QualitySelector.
 * @param buildTimeTargetRotation the Identity-normalised display rotation
 *   (`computeTargetRotation(displayRotation)`) applied to the VideoCapture use
 *   case at build (ADR-0029 §3 boundary). Per-segment rotation is re-applied at
 *   [SingleVideoRecorder.start].
 */
data class SingleVideoRecorderConfig(
    val resolutionStr: String,
    val buildTimeTargetRotation: Int,
)
```

- [ ] **Step 6: Create `SingleRecording.kt`.**

```kotlin
package com.aritr.rova.service.singlerecord

import androidx.camera.video.Recording

/**
 * Active-segment handle returned by [SingleVideoRecorder.start]. Mirror of
 * `service/dualrecord/DualRecording`. Wraps the CameraX [Recording] for one
 * segment. [stop] is idempotent and NON-BLOCKING — it delegates to
 * `Recording.stop()` (finalize is delivered asynchronously on the callback
 * executor afterwards; the service performs its bounded finalize-await AFTER
 * calling stop). The CameraX `stop()` exception (if any) is intentionally NOT
 * swallowed here, preserving the service's existing throw-propagation at each
 * stop site.
 */
class SingleRecording internal constructor(private val recording: Recording) {

    private val stopped = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Idempotent. Subsequent calls are no-ops. */
    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        recording.stop()
    }
}
```

- [ ] **Step 7: Create `SingleVideoRecorder.kt`.**

```kotlin
package com.aritr.rova.service.singlerecord

import android.content.Context
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.util.Consumer
import com.aritr.rova.utils.RovaLog
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single-mode (non-DualShot) recording collaborator. Mirror of
 * `service/dualrecord/DualVideoRecorder`: construction is cheap (config only);
 * it builds & OWNS the CameraX `VideoCapture<Recorder>` use case (incl. its
 * build-time `setTargetRotation` — ADR-0029 §3 boundary; this package is an
 * allowlisted rotation boundary, symmetric with `service/dualrecord/`).
 *
 * The service binds [videoCapture] via `bindToLifecycle` (mirror of
 * `DualVideoRecorder.asCameraEffect()`), then drives one [start] per segment.
 * The per-segment loop, watchdog, finalize-coordination deferreds, persistence,
 * merge, and terminal writes stay in the service (mirror of `recordSegmentDual`
 * / `performMergeDual`). CameraX `VideoRecordEvent` is passed straight through
 * to the service's callback — no event remap.
 *
 * `release()` is idempotent. After release the recorder cannot be reused.
 */
class SingleVideoRecorder(config: SingleVideoRecorderConfig) {

    private val released = AtomicBoolean(false)
    @Volatile private var active: SingleRecording? = null

    /** The owned video use case; the service binds this to the camera lifecycle. */
    val videoCapture: VideoCapture<Recorder>

    init {
        // Strict match against QualityPresets canonical labels: any
        // non-canonical resolutionStr falls through to Quality.FHD exactly as
        // the former inline setupSingleCamera body. (SingleQualitySelector is
        // the pure, tested decision; mapped to the CameraX type here at the edge.)
        val quality = when (SingleQualitySelector.forResolution(config.resolutionStr)) {
            SingleQuality.UHD -> Quality.UHD
            SingleQuality.FHD -> Quality.FHD
            SingleQuality.HD -> Quality.HD
            SingleQuality.SD -> Quality.SD
        }
        val qualitySelector = QualitySelector.fromOrderedList(
            listOf(quality, Quality.FHD, Quality.HD, Quality.SD),
            FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
        )
        val recorder = Recorder.Builder()
            .setQualitySelector(qualitySelector)
            .build()
        videoCapture = VideoCapture.Builder(recorder)
            .setTargetRotation(config.buildTimeTargetRotation)
            .build()
    }

    /**
     * Start one segment. Applies the per-segment rotation, prepares + (optionally)
     * audio-enables the recording, and starts it on [executor], delivering events
     * to [callback]. The `withAudioEnabled()` `SecurityException` is propagated
     * unchanged — the caller maps it to the PERMISSION_REVOKED termination path.
     */
    fun start(
        context: Context,
        outputOptions: FileOutputOptions,
        segmentRotation: Int,
        enableAudio: Boolean,
        executor: Executor,
        callback: Consumer<VideoRecordEvent>,
    ): SingleRecording {
        check(!released.get()) { "SingleVideoRecorder is released" }
        // Per-segment boundary rotation (ADR-0029 §3). Property-set form, kept
        // verbatim incl. the defensive try/catch from the former inline body.
        try { videoCapture.targetRotation = segmentRotation } catch (_: Exception) {}
        var pending = videoCapture.output.prepareRecording(context, outputOptions)
        if (enableAudio) {
            // SecurityException intentionally propagated (RECORD_AUDIO revoked
            // between gate and CameraX config) → caller routes to PERMISSION_REVOKED.
            pending = pending.withAudioEnabled()
        }
        val recording = pending.start(executor, callback)
        return SingleRecording(recording).also { active = it }
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        try { active?.stop() } catch (e: Throwable) { RovaLog.w("SingleVideoRecorder.release stop", e) }
        active = null
    }
}
```

- [ ] **Step 8: Build to verify the package compiles + gates pass.**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. `checkSetTargetRotationBoundaryOnly` passes (singlerecord allowlisted). New class is unused — no behavior change.

- [ ] **Step 9: Commit.**

```bash
git add app/src/main/java/com/aritr/rova/service/singlerecord/ app/src/test/java/com/aritr/rova/service/singlerecord/
git commit -m "feat(singlerecord): add SingleVideoRecorder + SingleRecording + quality helper (unused)"
```

---

## Task 3: Wire the build into `setupSingleCamera`

Delegate VideoCapture construction to the recorder. Behavior-identical: `recordSegment` still reads the `videoCapture` field (now assigned from the recorder), so the segment path is untouched and compiles standalone.

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt` (field decl ~290; `setupSingleCamera` 1707-1741, 1800; `releaseResources` 3768-area)

**Interfaces:**
- Consumes: `SingleVideoRecorder`, `SingleVideoRecorderConfig` (Task 2).
- Produces: `private var currentSingleRecorder: SingleVideoRecorder?` field (mirror of `currentDualRecorder`), assigned in `setupSingleCamera`, released in `releaseResources` + bind-failure.

- [ ] **Step 1: Add imports + the `currentSingleRecorder` field.** Near the top imports add:

```kotlin
import com.aritr.rova.service.singlerecord.SingleVideoRecorder
import com.aritr.rova.service.singlerecord.SingleVideoRecorderConfig
```

Directly below the `currentDualRecorder` field declaration (around line 290-293), add:

```kotlin
    // Single-mode recorder collaborator (mirror of currentDualRecorder).
    // Owns the VideoCapture<Recorder> use case + per-segment Recording start.
    private var currentSingleRecorder: SingleVideoRecorder? = null
```

- [ ] **Step 2: Replace the inline build in `setupSingleCamera`.** Replace lines 1707-1741 — the block from the `RovaLog.d { "setupCamera: Initializing UseCases..." }` comment through `videoCapture = VideoCapture.Builder(recorder).setTargetRotation(targetRot).build()` — i.e. the `quality` `when`, `qualitySelector`, `recorder`, `displayRotation`, `targetRot`, and the inline `videoCapture =` build.

Old (verbatim, the lines to remove start at the `val quality = when (resolutionStr)` and end at the `videoCapture = VideoCapture.Builder(...)` line; KEEP the `displayRotation`/`targetRot`/ADR comment lines 1729-1740 since `targetRot` is still needed for `preview` and `enableOrientationTracking`):

Remove:
```kotlin
            val quality = when (resolutionStr) {
                QualityPresets.UHD -> Quality.UHD
                QualityPresets.FHD -> Quality.FHD
                QualityPresets.HD -> Quality.HD
                QualityPresets.SD -> Quality.SD
                else -> Quality.FHD
            }

            val qualitySelector = QualitySelector.fromOrderedList(
                listOf(quality, Quality.FHD, Quality.HD, Quality.SD),
                FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
            )

            val recorder = Recorder.Builder()
                .setQualitySelector(qualitySelector)
                .build()
```
and the single line:
```kotlin
            videoCapture = VideoCapture.Builder(recorder).setTargetRotation(targetRot).build()
```

After the kept `val targetRot = computeTargetRotation(displayRotation)` line (1740), insert the delegation (replacing the removed `videoCapture =` line):

```kotlin
            // Single-mode recorder owns the VideoCapture<Recorder> build (incl.
            // build-time setTargetRotation — ADR-0029 §3 boundary). Mirror of the
            // dual path's DualVideoRecorder. The service caches the use case in
            // `videoCapture` for binding + idle-preview live rotation tracking.
            currentSingleRecorder?.release()
            currentSingleRecorder = SingleVideoRecorder(
                SingleVideoRecorderConfig(
                    resolutionStr = resolutionStr,
                    buildTimeTargetRotation = targetRot,
                )
            )
            videoCapture = currentSingleRecorder!!.videoCapture
```

(The `displayRotation` block and ADR-0029 comment at 1729-1739 stay exactly as-is; `preview = Preview.Builder().setTargetRotation(targetRot).build()` at 1746 stays — preview rotation remains service-owned, mirror of dual.)

- [ ] **Step 3: Clear the recorder on bind failure.** In the `catch (e: Exception)` of the bind block, find line 1800 `videoCapture = null` and add the recorder release immediately after it:

```kotlin
                videoCapture = null
                currentSingleRecorder?.release()
                currentSingleRecorder = null
```

- [ ] **Step 4: Release the recorder in `releaseResources`.** After the dual release lines (3768-3769 `currentDualRecorder?.release(); currentDualRecorder = null`), add:

```kotlin
        currentSingleRecorder?.release()
        currentSingleRecorder = null
```

- [ ] **Step 5: Remove now-unused imports if the compiler flags them.** After the build moves, `Quality`, `QualitySelector`, `FallbackStrategy`, `Recorder` may be unused in the service (note: `VideoCapture` is still used by the `videoCapture` field type; keep it). Remove any import the Kotlin compiler reports as unused; leave the rest. (Do not remove `VideoCapture`.)

- [ ] **Step 6: Build — verify compile + gates + behavior-identical segment path.**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. `recordSegment` still reads `videoCapture` (assigned from recorder) so the segment path is unchanged. All 47 gates pass.

- [ ] **Step 7: Run unit tests.**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS (baseline 1241 + new singlerecord tests; 0 failures).

- [ ] **Step 8: Commit.**

```bash
git add app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt
git commit -m "refactor(service): build single-mode VideoCapture via SingleVideoRecorder"
```

---

## Task 4: Wire start/stop into `recordSegment` + swap `currentRecording` → `currentSingleRecording`

Move the per-segment start sequence (rotation/prepare/audio/start) into `recorder.start()`. The large Finalize callback stays in `recordSegment` (it closes over ~12 service members) and is passed as the `callback`. Swap the `Recording?` field for `SingleRecording?` at all 8 sites.

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt` (field 212; `recordSegment` 2809, 2856, 2861-2900, 2906, 3032-3033, 3059-3060; `releaseResources` 3764-3765; `stopPeriodicRecordingAndMerge` 3813-3814)

**Interfaces:**
- Consumes: `SingleVideoRecorder.start(context, outputOptions, segmentRotation, enableAudio, executor, callback)`, `SingleRecording.stop()`, `currentSingleRecorder` field (Task 3).
- Produces: `private var currentSingleRecording: SingleRecording?` field replacing `currentRecording: Recording?`.

- [ ] **Step 1: Swap the field declaration.** At line 212 replace:

```kotlin
    private var currentRecording: Recording? = null
```

with:

```kotlin
    private var currentSingleRecording: com.aritr.rova.service.singlerecord.SingleRecording? = null
```

(Leave the `Recording` import — `SingleVideoRecorder.start` is typed via the package; the service no longer names `Recording` directly. Remove the `androidx.camera.video.Recording` import if the compiler flags it unused.)

- [ ] **Step 2: Replace the encoder-ready guard at 2809.** Old:

```kotlin
            val videoCap = videoCapture ?: run {
                return failRecording("Camera encoder is not ready")
            }
```

New (guard on the recorder; `videoCap` local is no longer needed — its only uses, the targetRotation set and prepareRecording, move into `recorder.start()`):

```kotlin
            val recorder = currentSingleRecorder ?: run {
                return failRecording("Camera encoder is not ready")
            }
```

- [ ] **Step 3: Remove the per-segment rotation property-set at 2856.** Delete the line:

```kotlin
            try { videoCap.targetRotation = segmentRotation } catch (_: Exception) {}
```

(It moves into `SingleVideoRecorder.start`. The `_serviceState.update { ... currentSegmentRotation/pendingNextRotation ... }` block at 2857-2859 STAYS — it is service state, not a use-case write.)

- [ ] **Step 4: Replace the prepare/audio/start block (2863-2900) with the recorder call.** Old block (from `var pendingRecording = ...` through the `currentRecording = pendingRecording.start(...) { event ->` opening):

```kotlin
            var pendingRecording = videoCap.output.prepareRecording(this, outputOptions)

            // ADR 0006 B18: audio-mode is locked at session start ... [comment block] ...
            if (currentAudioMode == com.aritr.rova.data.AudioMode.VIDEO_AUDIO) {
                try {
                    pendingRecording = pendingRecording.withAudioEnabled()
                } catch (se: SecurityException) {
                    RovaLog.w(
                        "recordSegment: RECORD_AUDIO revoked between gate and CameraX config — terminating",
                        se
                    )
                    currentStopReason = com.aritr.rova.data.StopReason.PERMISSION_REVOKED
                    return SegmentResult.Terminated
                }
            }

            // R2: Fresh deferred for this segment's finalize event
            recordingFinalized = CompletableDeferred()
            val recordingResult = CompletableDeferred<Boolean>()

            // PR-6b (ADR-0032): per-iteration wall-clock stamp ...
            val segmentStartWallClock = System.currentTimeMillis()

            currentRecording = pendingRecording.start(ContextCompat.getMainExecutor(this)) { event ->
```

New — keep `outputOptions`, deferreds, and `segmentStartWallClock` in `recordSegment` (the callback closes over them); build the callback as a lambda passed to `recorder.start`; keep the `SecurityException → PERMISSION_REVOKED` mapping at the call site (it must short-circuit `recordSegment` with `return`, which `start()` propagating the exception preserves):

```kotlin
            // R2: Fresh deferred for this segment's finalize event
            recordingFinalized = CompletableDeferred()
            val recordingResult = CompletableDeferred<Boolean>()

            // PR-6b (ADR-0032): per-iteration wall-clock stamp captured immediately
            // before start so it is isolated to this segment's closure. A service-level
            // var would be clobbered by the next iteration before finalize fires.
            val segmentStartWallClock = System.currentTimeMillis()

            // SingleVideoRecorder owns the VideoCapture write surface: it applies
            // the per-segment rotation, prepares + (audio-)enables, and starts the
            // Recording. The finalize callback below stays here — it closes over
            // service state (sessionStore, deferreds, _serviceState, persist jobs).
            // ADR 0006 B18: audio-mode is locked at session start and verified by
            // the per-segment gate; the gate-then-config window is not atomic, so
            // a RECORD_AUDIO revocation surfaces as SecurityException from
            // withAudioEnabled() inside start() — route it to the same
            // PERMISSION_REVOKED termination path (the FGS-type bitfield is
            // immutable mid-session, so we cannot fall back to video-only).
            currentSingleRecording = try {
                recorder.start(
                    context = this,
                    outputOptions = outputOptions,
                    segmentRotation = segmentRotation,
                    enableAudio = currentAudioMode == com.aritr.rova.data.AudioMode.VIDEO_AUDIO,
                    executor = ContextCompat.getMainExecutor(this),
                ) { event ->
```

(The `outputOptions` declaration `val outputOptions = FileOutputOptions.Builder(requireNotNull(videoFile)).build()` at 2861 STAYS in `recordSegment`, immediately before this block.)

- [ ] **Step 5: Close the lambda + the try, and map the SecurityException.** The existing callback body (`when (event) { is VideoRecordEvent.Start -> ...; is VideoRecordEvent.Finalize -> ...; is VideoRecordEvent.Status -> ... }`) is UNCHANGED except the one line inside it. The lambda currently closes at line 2998 with `}` then continues. After the closing `}` of the lambda, add the `catch` for the wrapping `try`. Locate (≈2998):

```kotlin
                    is VideoRecordEvent.Status -> { /* no-op */ }
                }
            }

            RovaLog.d { "recordSegment: Recording initialized, waiting ${nSeconds}s" }
```

Change to:

```kotlin
                    is VideoRecordEvent.Status -> { /* no-op */ }
                }
            }
            } catch (se: SecurityException) {
                RovaLog.w(
                    "recordSegment: RECORD_AUDIO revoked between gate and CameraX config — terminating",
                    se
                )
                currentStopReason = com.aritr.rova.data.StopReason.PERMISSION_REVOKED
                return SegmentResult.Terminated
            }

            RovaLog.d { "recordSegment: Recording initialized, waiting ${nSeconds}s" }
```

- [ ] **Step 6: Update the in-callback null-assignment at 2906.** Inside the `is VideoRecordEvent.Finalize` branch, change:

```kotlin
                        currentRecording = null
```

to:

```kotlin
                        currentSingleRecording = null
```

- [ ] **Step 7: Update the normal stop at 3032-3033.** Change:

```kotlin
            currentRecording?.stop()
            currentRecording = null
```

to:

```kotlin
            currentSingleRecording?.stop()
            currentSingleRecording = null
```

- [ ] **Step 8: Update the cancellation stop at 3059-3060.** Change:

```kotlin
            try { currentRecording?.stop() } catch (e2: Exception) {}
            currentRecording = null
```

to:

```kotlin
            try { currentSingleRecording?.stop() } catch (e2: Exception) {}
            currentSingleRecording = null
```

- [ ] **Step 9: Update `releaseResources` 3764-3765.** Change:

```kotlin
        try { currentRecording?.stop() } catch (e: Exception) {}
        currentRecording = null
```

to:

```kotlin
        try { currentSingleRecording?.stop() } catch (e: Exception) {}
        currentSingleRecording = null
```

- [ ] **Step 10: Update `stopPeriodicRecordingAndMerge` 3813-3814.** Change:

```kotlin
        try { currentRecording?.stop() } catch (e: Exception) { e.printStackTrace() }
        currentRecording = null
```

to:

```kotlin
        try { currentSingleRecording?.stop() } catch (e: Exception) { e.printStackTrace() }
        currentSingleRecording = null
```

- [ ] **Step 11: Verify no `currentRecording` / `videoCap` references remain.**

Run (controller): `grep -nE "\bcurrentRecording\b|\bvideoCap\b" app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt`
Expected: no matches (all migrated to `currentSingleRecording` / `recorder`). Note the `videoCapture` field (with the `ture` suffix) legitimately remains — only bare `videoCap` (the deleted local) must be gone.

- [ ] **Step 12: Build — compile + all 47 gates.**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. Confirm `checkUserStoppedBeforeMerge`, `checkCompletedWriteOnlyFromPerformMerge`, `checkExportPipelineSingleEntry`, `checkSetTargetRotationBoundaryOnly`, `checkWakeLock*` all green (performMerge/terminal/wakelock untouched; the only moved `setTargetRotation(` is allowlisted).

- [ ] **Step 13: Run unit tests.**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS (1241 baseline + singlerecord tests; 0 failures).

- [ ] **Step 14: Commit.**

```bash
git add app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt
git commit -m "refactor(service): drive single-mode Recording via SingleVideoRecorder.start"
```

---

## Task 5: Gate RED-fire proof + device verification

Proves the amended gate still catches real violations, and that both capture paths record end-to-end with byte-equivalent behavior.

**Files:** none (verification only; any temp edit is reverted).

- [ ] **Step 1: Prove `checkSetTargetRotationBoundaryOnly` still RED-fires.** Temporarily add a `setTargetRotation(0)` line to a non-boundary file (e.g. inside a function in `app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt`). Run `./gradlew :app:assembleDebug`. Expected: BUILD FAILS citing `ADR-0029 §3: setTargetRotation outside boundary-owning files:` with the `ui/screens/RecordScreen.kt:<line>` offender. Then revert the temp edit and re-run `./gradlew :app:assembleDebug` → SUCCESSFUL. (The build-logic golden test already encodes this; this is the end-to-end gate-wiring proof.)

- [ ] **Step 2: Install on device.**

Run (controller): `adb -s RZCYA1VBQ2H install -r app/build/outputs/apk/debug/app-debug.apk`
Expected: `Success`.

- [ ] **Step 3: Single-mode end-to-end (audio ON).** Portrait topology, VIDEO_AUDIO mode, short interval (e.g. 30s) + small loop count. Start → let ≥2 segments record → loop-exhaust stop. Verify: segments merge, a Library entry appears with the correct clip count and audible audio, duration matches actual recording (early-stop shows real length, not configured). No crash; notification lifecycle normal.

- [ ] **Step 4: Single-mode user-stop path.** Start a session, manually STOP mid-segment. Verify: merge of valid prior segments succeeds, no false "No video data" snackbar (FinalizeErrorPolicy suppression intact), manifest terminal state = USER_STOPPED.

- [ ] **Step 5: Single-mode audio OFF + rotation.** VIDEO-only mode: verify records without audio. With orientation Lock (e.g. landscape) and FollowDevice: verify the encoded output rotation matches the policy at each segment boundary (Lock pins; FollowDevice follows device tilt at boundary only).

- [ ] **Step 6: DualShot regression.** Switch to P+L (DualShot) topology. Record ≥2 segments → stop → merge. Verify both portrait + landscape outputs land in the Library and play. (Confirms no dual regression while refactoring single.)

- [ ] **Step 7: Force-kill recovery.** Start a single-mode session; force-stop the app mid-segment (`adb shell am force-stop com.aritr.rova`). Relaunch. Verify recovery classifies the interrupted session as before (KILLED_FORCE_STOP / offer per existing behavior) and does not delete segments.

- [ ] **Step 8: Record device-verify results** in the spec or a handoff note (device RZCYA1VBQ2H, pass/fail per step). Do NOT push/PR — await explicit owner GO.

---

## Self-review

**Spec coverage:**
- §3 boundary (narrow seam, new package) → Tasks 2-4. ✓
- §3.2 `start(...)` signature → Task 2 Step 7 + Task 4 Step 4. ✓
- §3.3 setupSingleCamera delegates build, keeps preview rotation + bind → Task 3. ✓
- §3.4 recordSegment keeps loop/persistence/callback → Task 4 (callback unchanged; only start sequence moves). ✓
- §4 untouched gates → asserted in Task 4 Step 12. ✓
- §5 VideoRecordEvent passthrough → callback lambda kept in recordSegment, `Consumer<VideoRecordEvent>` param. ✓
- §6 gate + ADR amendment + RED proof → Task 1 + Task 5 Step 1. ✓
- §7 stop idempotent/non-blocking, SecurityException→PERMISSION_REVOKED, recovery flags stay in service, release idempotent → Task 2 (SingleRecording/SingleVideoRecorder) + Task 4 Steps 4-5. ✓
- §8 SingleQualitySelector pure helper + test → Task 2 Steps 1-4. ✓
- §9 verification commands → Tasks 1-5. ✓

**Placeholder scan:** No TBD/TODO; every code step shows full code. ✓

**Type consistency:** `currentSingleRecorder: SingleVideoRecorder?`, `currentSingleRecording: SingleRecording?`, `recorder` (local in recordSegment = `currentSingleRecorder`), `SingleVideoRecorder.start(context, outputOptions, segmentRotation, enableAudio, executor, callback)`, `SingleQualitySelector.forResolution(resolutionStr): SingleQuality` — consistent across Tasks 2-4. ✓
