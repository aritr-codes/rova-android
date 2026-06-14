# DualSight δ-probe results — RZCYA1VBQ2H (2026-06-14)

**Decision: FAIL** (per `docs/superpowers/plans/2026-06-14-dualsight-delta-probe.md` Task 7 criteria).

Concurrent front+back capture is **not supported** on this device. The limitation is real
hardware/platform truth — **not** a probe artifact.

## Device

- `RZCYA1VBQ2H` — Samsung Galaxy A-class (single-ISP budget tier), `api=36` (Android 16).
- This is the project's primary smoke device. As anticipated in the probe plan, it cannot run DualSight.

## Verdict lines (from `gradle_dualsight_probe.log`)

```
QUERY api=36 featureFlag=false camera2Combos=0
QUERY cameraXCombos=0 combos=[]
VERDICT(query) frontBackBindable=false
ATTEMPT-A bind/record threw
  java.lang.UnsupportedOperationException: Concurrent camera is not supported on the device.
    at androidx.camera.lifecycle.LifecycleCameraProviderImpl.bindToLifecycle(LifecycleCameraProviderImpl.kt:383)
VERDICT(attemptA) recordSucceeded=false (exception)
VERDICT(attemptB) recordSucceeded=false (no front+back combo in getConcurrentCameraIds)
```

(No `VERDICT(torture)` line — by design the rebind-torture loop only runs after a successful
attempt A. No attempt-A clip was produced, so the MIRROR check is N/A on this device.)

## Why this is hardware truth, not a probe defect

Three independent layers agree, and the failure is immediate:

1. **`FEATURE_CAMERA_CONCURRENT = false`** — the platform does not advertise the concurrent feature.
2. **`getConcurrentCameraIds()` returned 0 combos** — raw Camera2 platform query (attempt B): the
   silicon exposes no concurrent front+back set. This is the authoritative hardware arbiter, and it
   is version-independent (not a CameraX-conservatism artifact).
3. **`availableConcurrentCameraInfos` returned 0 combos** — CameraX agrees.
4. **CameraX threw `UnsupportedOperationException` synchronously at `bindToLifecycle`** — a hard
   refusal that fired *before* any recording/timing window. The probe's false-negative hardening
   (onResume gating, watchdog, async-close delays) was therefore never the gate; bind never began.

Attempt B (raw Camera2) confirms it: if the silicon could but CameraX wouldn't, attempt B would have
recorded. It did not — there is no concurrent combo at the platform level.

## Implications for PR-δ

**Route = FAIL branch.** DualSight is still worth building, but:

- **Build it capability-gated.** The pure `ConcurrentCameraCapability.supportsConcurrentFrontAndBack(...)`
  helper (the probe's keeper, already on this branch) is the gate input. On this device it returns
  `false` → DualSight tab ships **disabled with an explainer** (honest UX — the device genuinely can't).
- **Capture device-smoke is deferred to a CameraX-capable flagship** (Samsung S21+/Director's View,
  Pixel 7+, etc.). DualSight cannot be end-to-end verified on RZCYA1VBQ2H.
- **CameraX 1.5.3 bump is clean** (Task 1): zero API-break compile errors across existing Single/DualShot
  capture code → the δ0 mergeable-bump PR is low-risk.
- **The composition API exists and compiles at 1.5.3** (`ConcurrentCamera.SingleCameraConfig`,
  `CompositionSettings`, `setMirrorMode`, `bindToLifecycle(list)`) — so the δ build is unblocked on the
  API surface; only on-device PiP correctness + mirror behavior remain to be proven on capable hardware.

## Next

- **δ0 plan** — mergeable CameraX 1.4.2→1.5.3 bump PR + full regression smoke on RZCYA1VBQ2H (the
  existing capture paths, which the probe already showed compile clean).
- **δ feature plan** — DualSight build, capability-gated, with on-device PiP/mirror verification
  scheduled on a borrowed flagship. The open mirror-under-composition question (codex HIGH) and
  hold-binding-vs-rebind both remain to be answered on capable silicon — they could not be answered here.

This probe branch (`probe/dualsight-concurrent-camera`) is **throwaway** — local-only bump + debug
harness. Only `ConcurrentCameraCapability` graduates into δ.
