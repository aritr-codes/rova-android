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

---

## AE floor — device verification (Limiter 1 fix, ADR-0034)

**Shipped:** `AeFpsRangePolicy` (capability-gated, brightness-preferring fallback) + `applyAeFpsFloor` (intersect AE ranges across matched back cameras) + 47th gate `checkAeFpsRangeCapabilityGated`. SDD slice on `perf/dualshot-fps-diagnosis`.

**Device:** RZCYA1VBQ2H, SD · DualShot, dim/indoor, 2×10s.

### First device run (pre-fix) — FAILED, feature inert
- `AE floor: selector resolved 2 cameras (need 1), skipping` (logged every binding). `DEFAULT_BACK_CAMERA` resolves to multiple physical back cameras (dumpsys: 6 back), so the original `matches.size != 1` identity guard skipped the apply.
- Effective `aeTargetFpsRange` stayed `[15, 30]`.
- Device fps-range data (every back camera, identical): `(15,15)(15,20)(20,20)(24,24)(15,30)(30,30)` — **no true `[24,30]`**. The original "prefer highest upper" policy would have returned `(30,30)` (pins 30fps, darkest dim) even once applied.

### Fix (codex-reconciled, owner-approved)
1. Identity guard → **intersect** `CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES` across all matched back cameras (no pre-bind API names the bound winner; the intersection is safe whichever binds). Skip only on 0 matches / empty intersection.
2. Policy → prefer a **true ceiling-spanning range** (`upper==ceiling && lower>=floor && lower<upper`); else the **lowest pinned fps ≥ floor** (brightness). On this device → `(24,24)`; on a device exposing real `[24,30]` → `[24,30]`.

### Second device run (post-fix) — PASS
```
AE floor: requested CONTROL_AE_TARGET_FPS_RANGE=[24, 24]    (both bindings, no "skipping")
DualShot AE: exposure=40000000ns (~25fps ceiling) frameDuration=41666000ns (~24fps) aeTargetFpsRange=[24, 24] aeState=2(CONVERGED)
EglRouter cadence [60f]: cameraHW median=41.7 p95=41.7 (~24.0fps) | wallArrival median≈40.5 (~24.8fps)
EglEncoder[PORTRAIT/LANDSCAPE] cadence: consume median≈45.9 (~21.8fps) | service(take→finish)≈43ms | swap≈1.3ms | mailboxDrops=2
```

**Outcome:**
- **Floor applied + honored:** effective `aeTargetFpsRange=[24,24]` (was `[15,30]`). Concern C resolved — the `Preview.Builder` Camera2Interop option **propagates through the `CameraEffect(PREVIEW)` fan-out** (the live session reflects the requested range).
- **Limiter 1 fixed:** dim `cameraHW` held at **41.7ms / 24.0fps** (steady). The same class of dim scene collapsed to ~60ms / 16.7fps pre-fix (diagnosis Run 1). AE wanted longer exposure (40ms, would have gone lower-fps) but the floor pinned 24fps — the intended cadence-over-brightness tradeoff.
- **Limiter 2 unchanged (expected):** encoder service ~43ms still caps **output ~22fps**; merged-file fps stays encoder-gated until the deferred Limiter 2 cycle. Camera-side success criterion met.
- Camera opened cleanly (preview visible, no session-config error).

---

## Limiter-2 encoder micro-diagnosis — service-time split (2026-06-29, branch `perf/dualshot-encoder-limiter2-diagnosis`)

**Instrumentation:** DEBUG-only edit to `EncoderRenderThread` — the lumped `service(take→finish)` bucket split into `{glWaitSync, drawSubmit, glFinish}` by moving the load-bearing `glFinish()` out of `drawFrame()` into `run()` (semantically identical: same thread/context, after every draw command, before swap; codex-reconciled). `glWaitSync` is server-side, so `wait` measures only CPU call overhead — the GPU-side fence wait retires inside `glFinish`. Device RZCYA1VBQ2H, SD · DualShot, dim/indoor (AE floor pins **24fps** → camera input already ≥ the encoder ceiling, so a bright scene is unnecessary to expose Limiter 2). Thermal read adb-side (`dumpsys thermalservice`), no extra code.

### Data (medians, both sides near-identical)

| Run | Thermal | `wait` | `draw` | **`finish`** | service | `finish` p95 | mailboxDrops |
|---|---|---|---|---|---|---|---|
| COLD | status 0 (AP ~46°C) | 0.1 ms | 0.7 ms | **43.2–43.4 ms** | 44.1–44.4 ms | ~51–53 ms | 5–8 |
| WARM | **status 2 throttling** (AP 48.9°C, SKIN 43°C) | 0.1 ms | 0.7 ms | **42.1–43.0 ms** | 43.1–44.2 ms | 60.9–63.3 ms | 18–20 |

(Camera side held `cameraHW=41.7ms`/24fps, `aeTargetFpsRange=[24,24]` throughout — AE floor working.)

### Verdict — the ~45ms service is ~97% `glFinish`, and the median is THERMAL-INVARIANT (saturation, not heat)

- **`draw` ruled out (0.7ms).** CPU GL submission of the single SD quad is negligible → candidate direction #2 ("cheaper per-side draw") has **no headroom** and is dead.
- **`wait` ruled out (0.1ms).** The fence chain costs nothing on the CPU; any real wait is inside `finish`.
- **Thermal ruled out as the median driver.** Intervention proof: SoC moved from status 0 → status 2 (actively throttling, thermal warning card shown) and the **median `finish` did not move (~43ms both)**. Thermal inflated only the **tail** (p95 51→62ms) and **mailboxDrops** (6→19). Heat worsens variance/drops, not the floor.
- **`glFinish` GPU-drain (43ms) is the limiter** — structurally pinned near the 41.7ms (24fps) frame period, identical cold/warm. One SD quad is sub-millisecond of genuine shading, so 43ms is **serialization/backpressure**, not pixel work: per camera frame the GPU runs 1 OES→FBO blit (callback thread) + 2 encoder full-frame draws through one GPU, and each encoder's `glFinish` drains the whole queue.

### Open sub-fork for the fix brainstorm (NOT yet resolved)

`finish ≈ frame period` is consistent with **two** mechanisms; one more discriminator is needed before picking a fix:
1. **GPU 3-pass serialization** — the three full-frame passes + per-`glFinish` full drains can't overlap, so wall-clock ≈ sum.
2. **MediaCodec input-buffer backpressure surfacing inside `glFinish`** (codex flag) — rendering into the codec producer surface stalls until the encoder frees an input buffer; if MediaCodec encodes ~22fps at the configured size/bitrate, `glFinish` blocks ~45ms even though `eglSwapBuffers` is 1.3ms. Discriminator candidates: measure MediaCodec dequeue/output rate directly, or drop bitrate/resolution and watch whether `finish` falls (codec-bound) or holds (GPU-bound).

**Probe stays in-tree (DEBUG) through the fix cycle; removed before the Limiter-2 PR (spec §5).**

---

## Limiter-2 RESOLUTION — sub-fork closed, no-fix decision (2026-06-30)

Two further DEBUG probes + an experiment resolved the open sub-fork and the fix question. Device RZCYA1VBQ2H, SD · DualShot, dim/indoor, AE floor pinning camera `cameraHW=41.7ms` (24fps) throughout.

### Probe 1 — drain-path (codec output cadence + muxer write cost)

Added `dequeueOutputBuffer` cadence + `onSample` duration probes to `EncoderSurface.drainLoop`.

| metric | DUAL (baseline) |
|---|---|
| codec dequeue cadence (TRUE delivered output) | 44.6 ms (22.4 fps) |
| muxer `write(onSample)` | **0.5 ms median, 15.3 ms max** |

→ **Muxer backpressure RULED OUT.** The synchronous `DualMuxer.writeSampleData` costs 0.5ms — it does not starve `releaseOutputBuffer`. The codec's true delivered output is ~22fps, matching the render-side `glFinish` cadence.

### Experiment — `KEY_OPERATING_RATE=120` + `KEY_PRIORITY=0`

DVFS/scheduling hints on both `EncoderSurface` formats. Result: **NULL** — `glFinish` 42.7→42.3 / 40.2ms (within noise), cadence unchanged. The Exynos codec ignored the hints or is not clock-starved. No cheap software lever. (Reverted.)

### Probe 2 — single-side discriminator (codex-preferred over pbuffer)

Ran only the PORTRAIT encoder (one HW codec + one render thread); LANDSCAPE skipped.

| metric | DUAL | SINGLE-SIDE |
|---|---|---|
| `glFinish` | 42.7 ms | **37.1–37.7 ms** |
| render consume cadence | 45.3 ms (22 fps) | 40.9–43.2 ms (23–24 fps) |
| codec dequeue (true output) | 44.6 ms (22.4 fps) | **40.7–41.7 ms (24.0–24.6 fps)** |
| mailboxDrops (~470 f) | ~40 | **2–9** |

### Verdict (codex-reconciled)

- **`glFinish` did NOT collapse single-side** (37ms, not <5ms) → **not** pure dual-encoder contention, and **not** GPU fill/shader cost (a quad is microseconds). The base ~37ms is a shared downstream sync stall — codec input-surface producer-queue **or** EGL-context/driver serialization (the single-side cut removed both the 2nd codec **and** the 2nd EGL context, so the two cannot be separated from this data alone; a pbuffer probe would refine the wording but not the decision).
- **Running both encoders adds ~5ms/frame** of structural dual-path contention, tipping each side from `≤41.7ms` (keeps up with the 24fps camera) to `42.7ms` → **~22fps + ~10% drops**.
- **The entire recoverable prize is ~2fps (22→24).** Every remaining lever is structural (stagger/serialize encodes, shared-encode + muxer crop, or lower per-side resolution/bitrate — the last a regression vs the just-shipped user-chosen Quality presets) and sits in the **load-bearing `glFinish`/FBO/fence stability stack** (#25–#35).

### Decision — DOCUMENT & CLOSE, no fix (owner-approved 2026-06-30)

Limiter 2 is an **inherent structural dual-HW-encode contention limit** on this Exynos config: a single encode sustains 24fps; two concurrent encodes settle at ~22fps. No cheap lever exists (operating-rate/priority = null), and the ~2fps ceiling does not justify the stability-stack risk of a topology rework. **~22fps is shipped and accepted** for a hands-free periodic background recorder.

**Caveats (codex, scope discipline):** one device / one codec / one preset / one camera floor; do not generalize. "Not GPU" means "not shader/fill," not "not driver sync." operating-rate null = "no cheap lever on THIS driver," not "no scheduler lever in principle."

**Probe removed** (this slice): the DEBUG fps-cadence probe (`CadenceProbe`, `CadenceStats`, `EglRouter`/`EglEncoder` taps, `FrameMailbox.overwriteCount` + tests) is reverted — its diagnostic purpose is complete. The Camera2Interop **AE-metadata log** in `RovaRecordingService` is **kept** (it verifies the shipped Limiter-1 floor and is outside the cadence-probe scope per the handoff enumeration).
