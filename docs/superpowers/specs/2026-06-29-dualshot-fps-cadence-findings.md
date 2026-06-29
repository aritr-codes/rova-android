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
_(paste the cadence log lines)_

### Per-stage breakdown
_(camera HW · wall arrival · callback service · encoder consume/service/swap/total · mailbox drops)_

### Verdict
_(capture/AE-side · callback overrun · encoder backpressure · compound — with the evidence)_

### Candidate fix → follow-up spec
_(capture→AE_TARGET_FPS_RANGE floor w/ dim-light tradeoff; callback→move preview swap off hot path / headless-during-record; encoder→service-time reduction. Fix = separate brainstorm→spec→plan, gated on this verdict.)_
