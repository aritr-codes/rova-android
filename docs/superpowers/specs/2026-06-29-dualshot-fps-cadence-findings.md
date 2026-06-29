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

### Verdict

**CAPTURE-SIDE / AUTO-EXPOSURE.** Not consumer-side; not a render/topology limit. Triply corroborated:

1. **Same-clock discriminator (§4):** cameraHW delta (60ms) ≈ wallArrival delta (59ms). The camera hardware itself delivers ~16.7fps; the consumer does not stretch frames.
2. **Direct AE metadata:** `SENSOR_EXPOSURE_TIME = 60ms`, `SENSOR_FRAME_DURATION = 60ms`, `CONTROL_AE_STATE = CONVERGED`, `CONTROL_AE_TARGET_FPS_RANGE = [15,30]`. In the dim indoor scene the AE stretched exposure to 60ms (≈1/16.7s) to gather light, and frame duration is clamped to the exposure → ~16.7fps sensor output. The whole pipeline is starved upstream.
3. **Downstream excluded:** encoder `total=39ms ≪ 60ms` inter-frame arrival, `mailboxDrops=0`, render `1.6ms`. Both encoder threads are idle-waiting on the mailbox, not backpressuring. The encoder could sustain ~25fps; it only receives 16.7.

This matches spec §2 hypothesis (a) exactly. The `~18–20fps` historical plateau is the AE exposure ceiling moving with ambient light (16.7fps here in a dimmer scene; ~18–20 in the brighter prior sessions).

**Confidence:** High. The AE state is CONVERGED and exposure == frame duration == HW cadence == consume cadence — four independent measurements agree to within 1ms. The one remaining falsification test (not yet run): a **bright-light** session should show AE pick a shorter exposure and fps rise toward 30 — recommended as a one-tap confirmation but not required to name the cause.

### Candidate fix → follow-up spec

**Floor the AE fps range** via `Camera2Interop … CONTROL_AE_TARGET_FPS_RANGE` on the DualShot binding — e.g. `[30,30]` or `[24,30]` instead of the device default `[15,30]`. This forbids the sensor from stretching exposure past ~1/30s, holding ≥30fps.

**The deferred tradeoff is now quantified:** forcing 30fps caps exposure at ~33ms vs the 60ms the AE currently wants in this scene — roughly **half the light per frame** → darker/noisier video in dim conditions. Options for the fix spec to weigh: a flat floor (simplest), a brightness-adaptive floor, or a user preference (smoothness vs low-light). Fix = **separate brainstorm → spec → plan**, gated on this verdict. No code change in this slice.
