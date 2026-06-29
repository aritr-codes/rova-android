# DualShot fps-cadence — Findings & Runbook

**Harness:** `perf/dualshot-fps-diagnosis` (CadenceProbe/CadenceStats + DEBUG taps).
**Spec:** `docs/superpowers/specs/2026-06-29-dualshot-fps-cadence-diagnosis-design.md`
**Device:** RZCYA1VBQ2H. Build: `./gradlew :app:assembleDebug` then `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

## Log filters (PowerShell)

```powershell
adb logcat -c
adb logcat -s RovaLog:* | Select-String "EglRouter cadence","EglEncoder","DualShot AE"
```

`EglRouter cadence` → cameraHW vs wallArrival median/p95 (the §4 discriminator).
`EglEncoder[PORTRAIT|LANDSCAPE] cadence` → consume / service / swap / total + mailboxDrops (logged at record stop).
`DualShot AE` → exposure ceiling / frameDuration / aeTargetFpsRange / aeState (best-effort).

## Test matrix (run each ≥2 segments, discard first ~30 warm-up frames)

| # | Light | Preview | AE pin | Purpose |
|---|-------|---------|--------|---------|
| 1 | Bright | Foreground | none | baseline + does cadence rise when bright? |
| 2 | Dim | Foreground | none | AE light-dependence |
| 3 | Bright | Headless | none | isolate callback-thread preview-swap overrun |
| 4 | Dim | Foreground | [30,30] one-off | direct AE test (probe-only, reverted) |

Capture merged-MP4 `stts` fps each run as the external cross-check (same method as #152).

## Discriminator (spec §4)

- cameraHW median ≈ 50ms AND wallArrival ≈ 50ms → **capture/AE-side**.
- cameraHW ≈ 33ms BUT wallArrival ≈ 50ms → **consumer-side** (callback overrun or encoder backpressure).
  - Then: encoder `total(take→swap)` ≈ 50ms with high mailboxDrops → **encoder backpressure**; else callback service time dominates → **callback overrun**.
- Report per-stage distributions; call out the dominant limiter AND any near-tie (compound cause), do not force one bucket.

## Findings (fill after measurement)

### Raw data per matrix cell

**Run 1 — Cell 1-ish: SD · DualShot · foreground (preview attached) · indoor/dim · 2×10s, RZCYA1VBQ2H, 2026-06-29 14:34**

```
EglRouter cadence [60f]: cameraHW median=60.0 p95=60.0 (~16.7fps) | wallArrival median=58.8 p95=65.7 (~17.0fps) (ms)
  (49 windows, steady; cameraHW median pinned at 60.0ms every window)

EglEncoder[PORTRAIT]  cadence [167f]: consume median=60.5 p95=67.2 (~16.5fps) | service(take→finish) median=38.1 p95=46.3 | swap(finish→swap) median=1.3 p95=2.2 | total(take→swap) median=39.4 p95=47.0 | mailboxDrops=0
EglEncoder[LANDSCAPE] cadence [167f]: consume median=60.0 p95=67.4 (~16.7fps) | service(take→finish) median=37.5 p95=45.6 | swap(finish→swap) median=1.3 p95=2.5 | total(take→swap) median=39.0 p95=47.0 | mailboxDrops=0
  (2 segments × 2 sides = 4 dumps, all near-identical)

DualShot AE: exposure=60000000ns (~16.7fps ceiling) frameDuration=60037000ns (~16.7fps) aeTargetFpsRange=[15, 30] aeState=2(CONVERGED)
  (52 samples, stable throughout)

EglRouter perf [60f]: interval avg=60.1 max=73.1 | updateTex avg=1.6 | renderTotal avg=1.6 max=2.9 | drawMax=0.0 blit avg=0.0 | encoders=2 targets=0 (ms)
```

**Run 2 — Bright (falsification test): SD · DualShot · foreground · torch ON + daylight window + lit screen · 2×10s, RZCYA1VBQ2H, 2026-06-29 14:44**

```
EglRouter cadence [60f]: cameraHW median=33.3 p95=33.3 (~30.0fps) | wallArrival median=32.4 p95=39.6 (~30.9fps) (ms)
  (50 windows, steady; cameraHW median pinned at 33.3ms = the 30fps cap)

EglEncoder[PORTRAIT]  cadence [~211f]: consume median=46.9 p95=65.8 (~21.3fps) | service(take→finish) median=45.5 p95=65 | swap median=1.3 | total(take→swap) median=46.8 p95=66 | mailboxDrops=25–33
EglEncoder[LANDSCAPE] cadence [~213f]: consume median=47.2 p95=65.1 (~21.2fps) | service(take→finish) median=45.8 p95=64 | swap median=1.3 | total(take→swap) median=47.2 p95=65 | mailboxDrops=25

DualShot AE: exposure=3269000ns (3.27ms, ~306fps ceiling) frameDuration=33333000ns (~30.0fps) aeTargetFpsRange=[15,30] aeState=4
  (early frames exposure=40ms while converging, settling to 3.27ms once bright-converged)
```

### Per-stage breakdown (Run 1)

| Stage | Median | Implied fps | Note |
|---|---|---|---|
| Camera HW (`getTimestamp` delta) | **60.0 ms** | **16.7** | the ceiling — sensor cadence |
| Wall arrival (`nanoTime` delta) | 58.8 ms | 17.0 | equals HW delta → no consumer stretch |
| Callback render (`renderTotal`) | 1.6 ms | (cheap) | idle-bound, not a limiter |
| Encoder consume | 60.0 ms | 16.7 | matches camera exactly |
| Encoder service (`take→finish`) | 38 ms | (~26 cap) | could do ~26fps; waits idle for frames |
| Encoder swap (`finish→swap`) | 1.3 ms | — | no MediaCodec backpressure |
| Encoder total (`take→swap`) | 39 ms | (~25 cap) | ≪ 60 ms arrival |
| Mailbox drops | **0** | — | no frames dropped per side |
| AE exposure time | **60.0 ms** | **16.7** | = frame duration; AE CONVERGED |

### Verdict — COMPOUND (two stacked limiters), proven by intervention

The dim run named AE; the bright run (falsification test) **confirmed AE AND uncovered a second ceiling that AE had been masking.**

**Limiter 1 — CAPTURE-SIDE / AUTO-EXPOSURE (primary, binds in typical/dim light).**
- Run 1 (dim): cameraHW delta 60ms == wallArrival 59ms == encoder consume 60ms == AE `SENSOR_EXPOSURE_TIME` 60ms, `AE_STATE=CONVERGED`, range `[15,30]`. Four independent clocks agree to ≤1ms → the sensor delivers 16.7fps because AE stretched exposure to 60ms to gather light.
- Run 2 (bright): identical binding, scene flooded with light → AE dropped exposure **60ms → 3.27ms**, frameDuration fell to the 30fps cap (33.3ms), and **cameraHW rose to 33.3ms = 30.0fps**. The fps moved with light exactly as predicted. This is a controlled-intervention proof that capture cadence is exposure-bound, not a fixed pipeline limit.

**Limiter 2 — ENCODER SERVICE TIME (secondary, was hidden behind Limiter 1).**
- In Run 1 the encoder was idle-bound (`total=39ms ≪ 60ms` arrival, `mailboxDrops=0`) — it could easily keep up with a starved 16.7fps camera.
- In Run 2, with the camera genuinely delivering 30fps, the encoder's GPU service time (`take→finish ≈ 45ms`, dominated by `drawFrame`+`glFinish`) caps it at **~21–22fps**, and the latest-wins mailbox **drops 25–33 frames per side per segment**. Swap (`finish→swap ≈ 1.3ms`) is NOT the stall — it's the GPU sample/draw (`glFinish`).

**Net:** the historical `~18–20fps` plateau is **AE-limited in normal/dim light** (the usual case), but once AE is lifted the **encoder's ~45ms service time becomes the next wall at ~22fps**. They are stacked, not either/or — and the harness's per-stage distributions made the second one visible the moment the first was removed (spec §7: report compound causes, don't collapse to one bucket).

**Confidence:** High for both. Limiter 1 is proven by direct AE metadata + a light intervention that moved fps 16.7→30. Limiter 2 is proven by 30fps camera input + 45ms encoder service + 25–33 mailbox drops in the same run.

### Candidate fix → follow-up spec

This is now a **two-part** fix, and order matters — fixing only AE would surface the encoder gate (output would land ~22fps with frame drops, not 30):

1. **Floor the AE fps range** (`Camera2Interop … CONTROL_AE_TARGET_FPS_RANGE`, e.g. `[30,30]` or `[24,30]` vs the device `[15,30]`). Lifts Limiter 1. **Quantified tradeoff:** caps exposure at ~33ms vs the 60ms AE wants in dim scenes → ~½ the light per frame → darker/noisier low-light video. Options: flat floor, brightness-adaptive floor, or a user "smoothness vs low-light" preference.
2. **Reduce encoder service time** (Limiter 2). The ~45ms `take→finish` is the GPU draw + `glFinish` per side. Candidates for the fix spec to investigate: removing/relaxing the per-frame `glFinish` (rely on the existing fence-sync chain instead of a hard CPU stall), cheaper per-side draw, or revisiting the single-`CameraEffect` fan-out topology (the spec's gated escalation). **NOTE:** the encoder `service` rose 38ms (Run 1) → 45ms (Run 2); confirm whether that's the higher frame rate saturating the GPU vs thermal accumulation across back-to-back sessions before committing a fix.

Fix = **separate brainstorm → spec → plan**, gated on this verdict. No code change in this slice. The probe should stay (DEBUG-gated) through the fix cycle to measure each step, then be removed before the fix PR per spec §5.
