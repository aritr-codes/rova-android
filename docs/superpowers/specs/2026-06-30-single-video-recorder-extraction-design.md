# SingleVideoRecorder extraction — design

**Date:** 2026-06-30
**Status:** Proposed (awaiting owner review)
**Driver:** 2026-06-28 service-split audit (`memory/project_service_decomp_audit.md`, PR #151) — the one genuinely-valuable structural target.
**Type:** Structural move, **zero behavior change**.
**Peer review:** codex-reconciled 2026-06-30 (narrow seam + `service/singlerecord/` + own-the-use-case + no event remap + no gate-dodge). Web-confirmed `setTargetRotation` is a valid runtime call on a bound use case.

---

## 1. Problem

Single-mode CameraX recording lifecycle is **inline** in `RovaRecordingService.kt` (`setupSingleCamera`, the recording-start block inside `recordSegment`, `performMerge`), while the dual path is already a self-contained class (`service/dualrecord/DualVideoRecorder` + `DualRecording` + `DualRecordEvent`). This asymmetry is the only structural debt the split audit found worth paying down: dual has an ownership/testability seam for its capture pipeline; single does not.

## 2. Goal & non-goals

**Goal:** Extract a `SingleVideoRecorder` collaborator that owns the single-mode CameraX `VideoCapture<Recorder>` use case and the per-segment `Recording` lifecycle, mirroring `DualVideoRecorder`'s ownership boundary exactly. Net: `singlerecord/SingleVideoRecorder` ↔ `dualrecord/DualVideoRecorder`; service shrinks; single ↔ dual symmetric.

**Non-goals (explicit):**
- No change to segment cadence, drift policy, both STOP paths (user-stop vs loop-exhaust), terminal-write ordering, or recovery handoff. Byte-equivalent behavior.
- The recorder does **not** own: the per-segment loop, watchdog arm/cancel, `CompletableDeferred` finalize coordination, segment persistence, `pendingPersistJobs` snapshot, `stopNeedsRecovery`, `performMerge`, terminal manifest writes, `ExportPipeline.export`, wakelock, recording mutex. These stay in the service exactly as `recordSegmentDual`/`performMergeDual`/`handleDualVideoEvent` keep them on the dual side.
- No event-type remap (see §5). No feature work. No DualShot changes.

## 3. Boundary (the load-bearing decision)

**Narrow seam, mirroring `DualVideoRecorder`, owning the entire `VideoCapture` write surface.** The recorder owns every write to the CameraX video use case so the object is never half-owned (codex correction).

### 3.1 New package `service/singlerecord/` (symmetric with `service/dualrecord/`)

| Type | Responsibility |
|------|----------------|
| `SingleVideoRecorderConfig` | Immutable config: `resolutionStr: String` (drives `QualitySelector`), `buildTimeTargetRotation: Int` (the `computeTargetRotation(displayRotation)` value). ctor is cheap. |
| `SingleVideoRecorder(config)` | Builds & holds `VideoCapture<Recorder>` (build-time `setTargetRotation` lives here). Exposes the built `videoCapture` for the service to `bindToLifecycle` (mirror of `DualVideoRecorder.asCameraEffect()`). `start(...)` → `SingleRecording`. Idempotent `release()`. |
| `SingleRecording` | Active-segment handle wrapping CameraX `Recording`. Idempotent, **non-blocking** `stop()` (delegates to `Recording.close()`; finalize arrives async). Mirror of `DualRecording`. |

### 3.2 `SingleVideoRecorder.start(...)` signature

```
fun start(
    context: Context,               // for prepareRecording
    outputOptions: FileOutputOptions,
    segmentRotation: Int,           // per-segment resolved rotation (ADR-0029 boundary)
    enableAudio: Boolean,           // currentAudioMode == VIDEO_AUDIO
    executor: Executor,             // ContextCompat.getMainExecutor(service)
    callback: Consumer<VideoRecordEvent>,  // service's existing inline when(event) block, moved verbatim
): SingleRecording
```

`start()` body (all verbatim-moved from `recordSegment` @2856–@2900):
1. `videoCapture.targetRotation = segmentRotation` (the per-segment property-set @2856 — **not** gate-relevant; see §6).
2. `var pendingRecording = videoCapture.output.prepareRecording(context, outputOptions)`.
3. if `enableAudio`: `pendingRecording = pendingRecording.withAudioEnabled()` — **propagates `SecurityException` unchanged** (the service keeps the `try/catch → currentStopReason = PERMISSION_REVOKED; return SegmentResult.Terminated` policy at the call site; see §7).
4. `val recording = pendingRecording.start(executor, callback)`; return `SingleRecording(recording)`.

The service's existing `when (event) { Start / Finalize { persist, notify, deferreds } }` block is the `callback` — **moved verbatim**, still owned by the service (it does service concerns: notification, `submitPersistFinalizedSegment`, `recordingFinalized`/`recordingResult` completion, `recordedSegmentDurationMs`).

### 3.3 What `setupSingleCamera` (stays in service) does after extraction

Identical to today except VideoCapture construction is delegated:
- builds `SingleVideoRecorder(config)` (replaces the inline `Recorder.Builder()...` + `VideoCapture.Builder()...` @1726–1741);
- `preview = Preview.Builder().setTargetRotation(targetRot).build()` (@1746 — **preview rotation stays in the service**, mirror of dual where preview `setTargetRotation` @2284 stays in `setupDualCamera`);
- `bindToLifecycle(this, currentCameraSelector, preview, singleRecorder.videoCapture)`;
- all error-handling, front→rear fallback, `enableOrientationTracking`, mutex, cold-acquire flag — **unchanged**.

A `currentSingleRecorder: SingleVideoRecorder?` field mirrors `currentDualRecorder`; `releaseResources` calls `currentSingleRecorder?.release()` alongside the dual release.

### 3.4 What `recordSegment` (stays in service) keeps

The whole loop: gate checks, mutex, `videoFile`/filename, snapped-rotation resolution (`OrientationPolicyResolver.resolve` → `segmentRotation`), the `_serviceState` rotation update, fresh deferreds, `segmentStartWallClock`, watchdog arm/cancel, `delay`, the bounded finalize-await + 3 catch blocks (incl. `CancellationException`), `SegmentResult` returns. The only change: instead of inline `videoCap.targetRotation = …; prepareRecording; withAudioEnabled; .start(...)`, it calls `currentSingleRecorder.start(...)` and holds the returned `SingleRecording` in a `currentSingleRecording` field (mirror `currentDualRecording`); stop becomes `currentSingleRecording?.stop()`.

## 4. What stays untouched (gate-safe by construction)

`performMerge` (with `ExportPipeline.export` @4024, `COMPLETED` writes @4184/4429, `USER_STOPPED` ordering @4180/3845), `stopPeriodicRecordingAndMerge`, wakelock acquire/refresh/release, `PendingIntent` paths. Therefore **untouched, zero gate change:** `checkExportPipelineSingleEntry`, `checkCompletedWriteOnlyFromPerformMerge`, `checkUserStoppedBeforeMerge`, `checkAtomicTerminalWriteForbiddenPair`, `checkWakeLockBoundedAcquire`/`HeldRefresh`/`ZeroGapRefresh`, `checkStopNoGetService`.

## 5. Event type — passthrough, no remap

Pass CameraX `VideoRecordEvent` **straight through** to the service callback. No `SingleRecordEvent` sealed type.

Rationale (codex-reconciled): dual needed `DualRecordEvent` because its custom EGL/encoder pipeline emits no framework events — it had to synthesize them, and its `Finalize` legitimately lacks timing stats. Single mode receives real `VideoRecordEvent.Finalize` carrying `recordingStats.recordedDurationNanos`, which the service already consumes via `recordedSegmentDurationMs(...)`. A sealed mirror would be pure ceremony plus lossy-translation risk in a zero-behavior refactor. Passthrough keeps `recordedSegmentDurationMs` and the finalize-success predicate (`!hasError() && file exists && length > 0`) **exactly where they are**.

## 6. Gate amendment — `checkSetTargetRotationBoundaryOnly` (the only one)

**One `setTargetRotation(` call crosses the file boundary:** the build-time `VideoCapture.Builder(recorder).setTargetRotation(targetRot)` @1741, which moves into `service/singlerecord/SingleVideoRecorder.kt`.
The per-segment rotation @2856 is `videoCap.targetRotation = segmentRotation` — **property-set form, which the gate regex `setTargetRotation(` does not match** — so moving it is gate-invisible.

**Rule:** `RovaGateRules_ModeRotation.kt` → `ruleSetTargetRotationBoundaryOnly`. Current allowlist:
```kotlin
val allowed = rel.endsWith("service/RovaRecordingService.kt") ||
    rel.contains("service/dualrecord/")
```
**Amendment (add the symmetric clause):**
```kotlin
val allowed = rel.endsWith("service/RovaRecordingService.kt") ||
    rel.contains("service/dualrecord/") ||
    rel.contains("service/singlerecord/")
```
Failure message unchanged (`"ADR-0029 §3: setTargetRotation outside boundary-owning files:\n"`).

**This changes WHERE the boundary is declared, not WHAT is enforced.** The invariant — `setTargetRotation` may appear only at a declared capture boundary — is preserved: `service/singlerecord/` is a capture boundary in the identical sense `service/dualrecord/` already is. The gate still RED-fires on `setTargetRotation(` in any non-boundary file.

**ADR:** amend **ADR-0029 §3** (boundary-only rotation) to name `service/singlerecord/` as a rotation-boundary package alongside `service/RovaRecordingService.kt` and `service/dualrecord/`. Amend ADR first, then the gate, then move code.

**Gate proof obligations (build-logic golden test `RovaGateRules_ModeRotation` test):**
1. New GREEN case: `setTargetRotation(` in a `service/singlerecord/Foo.kt` file → rule returns `null`.
2. RED still fires: `setTargetRotation(` in a non-boundary file (e.g. `ui/Foo.kt`) → rule returns the ADR-0029 §3 message. (Keep/confirm the existing RED case.)

## 7. Error handling & cancellation safety (codex landmines)

- **`stop()` idempotent + non-blocking.** `SingleRecording.stop()` = `AtomicBoolean` guard → `recording.close()` once. `close()` is non-blocking; the service keeps its bounded finalize-await (`withTimeoutOrNull(FINALIZE_TIMEOUT_MS)`) *after* calling `stop()`, exactly as today (recordSegment @3041, stop flow @3826).
- **Finalize exactly once / callback executor.** The recorder passes the service callback + `getMainExecutor` straight into `Recording.start` — no change to delivery thread or count.
- **`withAudioEnabled` SecurityException → `PERMISSION_REVOKED`.** Preserved by propagating the exception from `start()`; the service's existing `try/catch` at the call site sets `currentStopReason = PERMISSION_REVOKED` and returns `SegmentResult.Terminated`. (The recorder must not swallow it.)
- **Recovery flags stay in the service.** `stopNeedsRecovery`, `pendingPersistJobs` snapshot (@3928), file deletion, manifest reads — never in the recorder.
- **`release()` idempotent.** `AtomicBoolean` guard; stops any active `SingleRecording`; mirror of `DualVideoRecorder.release()`.

## 8. Testability

The recorder is framework-bound (`VideoCapture`/`Recording`) → no-op under `isReturnDefaultValues=true`, so the class itself is not JVM-runnable — same as `DualVideoRecorder`. Optional pure helper (mirrors dual's `BitrateTable`/`SegmentPathBuilder` pattern): `SingleQualitySelector.forResolution(resolutionStr): Quality` extracting the `when (resolutionStr) { UHD/FHD/HD/SD → … else FHD }` map @1713–1719, JVM-tested. Low value but cheap; include only if it falls out cleanly. No behavior helper is moved (duration policy stays as `recordedSegmentDurationMs`).

## 9. Verification

- **Build/gates:** `:app:assembleDebug` (fires all 47 gates on `preBuild`) + `:app:testDebugUnitTest` (incl. new build-logic golden cases). **Not** `:app:lintDebug` (pre-existing `VaultAndroidOps:267` NewApi RED is unrelated).
- **Gate RED-proof:** before merge, demonstrate `ruleSetTargetRotationBoundaryOnly` still RED-fires on a non-boundary `setTargetRotation(` (golden test + a throwaway local edit reverted).
- **Device (RZCYA1VBQ2H, mandatory — emulators fail CameraX video):** single-mode records end-to-end (start → segments → stop → merge → Library entry, audio on & off); **and DualShot still records** (no dual regression); rotation correct (Lock + FollowDevice); user-stop and loop-exhaust STOP paths both terminate cleanly; force-kill mid-segment → recovery classifies as before.
- **Baseline:** 1241 tests / 0-0-0 plus the new golden cases.

## 10. Process

Design-first (this doc) → owner review → `writing-plans` → subagent-driven implementation (Windows **EDIT-ONLY**; controller runs all gradle/git on the shared daemon) → review-gate → codex boundary review → device-verify → **owner GO** before any push/PR. Never push master directly.

## 11. Risk register

| Risk | Mitigation |
|------|------------|
| Gate amendment weakens the invariant | Add-only allowlist clause; message unchanged; golden RED case retained + proven. |
| Behavior drift in finalize/cancellation | Callback + deferreds + bounded-await stay in service verbatim; recorder only owns build + start + close. |
| Half-owned `VideoCapture` (split rotation/audio writes) | Recorder owns the full write surface (build rotation, segment rotation, audio enable, start, close). |
| Dual regression while refactoring single | Device-verify both paths; no `service/dualrecord/` files touched. |
| Kotlin can't reach service private fields | Recorder is self-contained (config in, handle out); no service-field access needed — mirror of dual. |
