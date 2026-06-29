# DualShot fps-cadence diagnosis — Design Spec

**Date:** 2026-06-29
**Status:** Approved (brainstorm), pending implementation plan
**Type:** Investigation slice (diagnosis only — NO fix lands in this slice)
**Builds on:** `memory/project_dualshot_preset_honoring.md` (open follow-up), `memory/project_dualshot_stability_stack.md` (#25–#35 render stack — must not regress)

---

## 1. Problem

DualShot (P+L dual-encode) ground-truth fps — measured from merged MP4 `stts` boxes — plateaus at **~18–20 fps across every Quality preset, including SD**, while the single-camera record path reaches ~30 fps on the same device (RZCYA1VBQ2H). The preset-honoring slice (#152) proved the plateau is **not** bitrate/resolution-bound: SD (540×960) plateaus at the same ~20 fps as FHD (1080×1920).

Prior profiling showed the GL render step is cheap (~1.3 ms/frame) and the EglRouter callback loop is **idle-bound** with a steady inter-frame interval of **~50 ms (= 20 fps)**. That points away from GPU/render cost and toward a cadence limiter — but the *location* of that limiter is unproven.

**This slice does not fix the plateau. It proves where the cadence is lost**, with on-device evidence, and emits a verdict that gates a separate fix spec.

## 2. Why diagnosis-first (the Iron Law)

There are at least three plausible, mutually-non-exclusive root causes, and the cheap "obvious" fix for each is wrong if the cause is one of the others:

- **(a) Capture-side / auto-exposure.** No `CONTROL_AE_TARGET_FPS_RANGE` is set anywhere on the DualShot CameraX binding. Indoor light can drive AE to a long exposure (~1/20 s) → the sensor caps at ~20 fps → the whole pipeline is starved regardless of downstream capability. Candidate fix: pin/floor the AE fps range (with a brightness tradeoff in dim light).
- **(b) Callback-thread overrun.** The GL callback thread runs `updateTexImage` + FBO blit + **inline preview `eglSwapBuffers`** per frame. If that work overruns, it backpressures the SurfaceTexture producer — which *looks identical to capture starvation* if measured naively. Candidate fix: move preview swap off the hot path / run headless during record.
- **(c) Encoder-loop backpressure.** Each per-side `EncoderRenderThread` does `glFinish()` then `eglSwapBuffers` into a MediaCodec input surface; either can stall, and the latest-wins mailbox silently drops frames per side. Candidate fix: reduce encoder service time.

A single device session with the right instrumentation discriminates all three. Guessing does not.

**Scope decision (owner, 2026-06-29):** diagnose-first; render topology is in-bounds to change *only if* the evidence proves the single-CameraEffect fan-out is the hard ceiling. No topology change in this slice. The AE brightness tradeoff (smoothness vs low-light brightness) is explicitly deferred to "decide after measuring" — the verdict re-surfaces it with real numbers.

## 3. Pipeline reference (file:line anchors)

All paths under `app/src/main/java/com/aritr/rova/service/dualrecord/internal/` unless noted.

| Stage | Anchor | Role |
|---|---|---|
| Frame ingress | `DualSurfaceProcessor.kt:110` | `setOnFrameAvailableListener { router.renderFrame() }` — GL callback trigger |
| Callback render | `EglRouter.kt:556` | `renderFrame()` entry (listener dispatch — **not** pure camera arrival) |
| Texture pull | `EglRouter.kt:575` | `updateTexImage()` — sample point for `SurfaceTexture.getTimestamp()` |
| FBO blit + fence + broadcast | `EglRouter.kt:579–646` | depth-3 ring blit, `glFenceSync`+`glFlush`, broadcast `EncoderFrame` to mailboxes |
| Inline preview swap | `EglRouter.kt:754` | per-preview-target `eglSwapBuffers` on the callback thread (overrun confounder) |
| Mailbox offer | `FrameMailbox.kt:26` | latest-wins `offer()` — unread slot overwritten (per-side silent drop) |
| Encoder consume | `EncoderRenderThread.kt:122` | `mailbox.take()` (blocking) |
| Encoder GPU sample | `EncoderRenderThread.kt:243` | `glFinish()` — blocks until GPU finishes sampling FBO slot |
| Encoder submit | `EncoderRenderThread.kt:140` | `eglSwapBuffers` into MediaCodec input (backpressure stall) |
| CameraX binding | `RovaRecordingService.kt:~2190–2207` | `UseCaseGroup` Preview + CameraEffect bind (Camera2Interop seam) |

**Stability mechanisms that must not regress:** FBO depth-3 ring (`FboRing.kt`), fence-sync chain (`glFenceSync`+`glFlush`+`glWaitSync`), two-tier per-target locking (EglRouter ANR fix), mailbox latest-wins frame-drop.

## 4. The core discriminator

`renderFrame()` entry timing alone **cannot** separate cause (a) from (b): callback-thread overrun backpressures the producer and inflates entry deltas, masquerading as capture starvation.

The decisive signal is **`SurfaceTexture.getTimestamp()` (camera hardware timestamp), sampled immediately after `updateTexImage()`**, compared against `System.nanoTime()` wall-clock arrival — **delta-vs-delta within each clock**, never cross-clock absolutes:

| HW-timestamp delta | Wall-clock arrival delta | Verdict |
|---|---|---|
| ~50 ms | ~50 ms | **Capture-side** — camera genuinely delivering ~20 fps |
| ~33 ms | ~50 ms | **Consumer-side** — frames arrive at 30 fps; callback/encoder backpressure stretches them |

`getTimestamp()` and `nanoTime()` may use different clock sources (sensor timestamp source may be `UNKNOWN`/`REALTIME`/`BOOTTIME`). Because only same-clock deltas are compared, the absolute offset is irrelevant.

**Guards:** skip frames where `ts <= 0`; on `ts <= prevTs` mark a discontinuity and reset the delta chain; discard warm-up frames after each bind/rebind.

## 5. Instrumentation — `CadenceProbe`

Debug-gated, purely additive, **removed before any fix PR**. No behavioral change to the pipeline.

### 5.1 Observer-effect-safe capture (hard requirement)

The probe taps run on the GL callback thread and the two encoder threads, **per frame**. Per-frame `Log.d` / String formatting / allocation on those threads would perturb the very intervals being measured. Therefore:

- **One writer per thread**, each owning a **preallocated `LongArray` ring buffer** (power-of-two capacity + bitmask index — no modulo).
- Hot path does **raw `long` stores only**: no boxing, no locks, no `Atomic*`, no I/O, no `Log` call.
- Aggregation (median / p95 / min / max + per-side drop counts) and formatting happen **once, at segment stop / record stop** — off the hot path.
- Optional secondary `Trace.beginSection`/Perfetto markers are permitted as a timeline aid but are **never** the primary metric source.

### 5.2 Taps (the five stages)

Callback (GL) thread:
1. `renderFrame()` entry — wall-clock arrival.
2. After `updateTexImage()` — `SurfaceTexture.getTimestamp()` (camera HW cadence).
3. `renderFrame()` return — callback service time = (3) − (1).

Each `EncoderRenderThread` (per side, P and L):
4. After `mailbox.take()` — consume timestamp.
5. After `glFinish()` — GPU sample-complete timestamp.
6. After `eglSwapBuffers` — submit-complete timestamp. (`take→swap` = full encoder service time; codex: `glFinish` alone misses MediaCodec backpressure on swap.)

Mailbox (`FrameMailbox.offer()`): per-side overwrite/drop counter (incremented when an unread slot is overwritten).

### 5.3 Best-effort AE metadata (NOT a slice dependency)

If reachable, attach `Camera2Interop.Extender(Preview.Builder).setSessionCaptureCallback` at the existing `UseCaseGroup` bind and read per-`TotalCaptureResult`: `SENSOR_EXPOSURE_TIME`, `SENSOR_FRAME_DURATION`, effective `CONTROL_AE_TARGET_FPS_RANGE`, `CONTROL_AE_STATE`. This is the direct, unambiguous AE verdict and is preferred when available. The CameraEffect lives downstream of Preview, so session callbacks should remain available — but if interop proves unreachable or noisy, the slice still concludes on §4 (`getTimestamp` cadence) + the §6 bright/dim control. The slice must not block on this.

### 5.4 `CadenceStats` — pure JVM-testable helper

Per the house pure-helper convention, the delta/statistics math lives in a framework-free `CadenceStats` object: given a `LongArray` (+ valid length / warm-up offset), it returns median, p95, min, max, and count, with deterministic handling of discontinuity resets and `<=0` skips. This is the unit-tested seam (baseline 1241/0-0-0; new tests land in the same PR). Only the timestamp taps and the stop-time dump touch framework code.

## 6. Test matrix (RZCYA1VBQ2H)

Hold the DualShot graph constant; vary only one factor at a time. Foreground attaches preview targets (preview swaps on the callback thread — a confounder), so preview-attachment is an explicit axis.

Controls (all on the **same DualShot graph**):
1. Cold, **bright** light.
2. Cold, **dim** light.
3. **Foreground (preview attached)** vs **headless** (during record).
4. One-off **AE range pinned** via Camera2Interop (probe-only, reverted) — direct test of the AE hypothesis.

Single-camera 30 fps is retained only as a **directional sanity check** (the device *can* do 30 in *some* topology), not as an apples-to-apples control — the single path likely binds VideoCapture directly without the GL fan-out and may select a different sensor stream config.

**Measurement protocol:** discard the first ~30 warm-up frames after bind/rebind; measure a steady-state window of ≥ 300 frames (~10 s at a 30 fps target) over ≥ 2 segments (the first-segment teardown flakiness noted in the stability-stack memory makes single-segment runs unreliable); report **medians/p95**, not means (robust to GC pauses). The `LongArray` ring is sized to a power of two ≥ that window (e.g. 512) per thread. The merged-MP4 `stts` ground-truth fps is captured alongside the probe dump each run as the external cross-check.

## 7. Verdict (the slice's only deliverable)

A findings write-up that reports the **per-stage steady-state interval distribution** (camera HW delta · callback arrival delta · callback service time · per-encoder `take→swap` · mailbox overwrites) for each matrix cell. The bottleneck is named as the stage whose service time **approaches or exceeds its upstream arrival interval**, corroborated by downstream backlog/overwrites. Compound causes (e.g. AE ~24 fps *and* encoder ~20 fps) are reported as visible near-ties, **not collapsed into a single bucket**.

Each dominant-stage outcome names its candidate fix for the follow-up spec:

- **Capture/AE-side** → AE `TARGET_FPS_RANGE` floor/pin, with the deferred dim-light brightness tradeoff re-surfaced with measured exposure numbers.
- **Callback-thread overrun** → move preview swap off the hot path / headless-during-record.
- **Encoder-loop backpressure** → encoder service-time reduction (and only here is render-topology change reconsidered, per the scope gate).

The fix is a **separate brainstorm → spec → plan cycle**, gated on this verdict.

## 8. Constraints / non-regression

- Probe is additive logging only; no behavioral change; removed before the fix PR.
- Stability stack (FBO ring, fence-sync chain, two-tier locking, mailbox latest-wins) untouched.
- ADR-0009 crop geometry untouched (this slice changes no output dims).
- All 46 static-check gates pass byte-identically; none added or edited.
- Pure-helper rule: `CadenceStats` is JVM-unit-tested; framework taps stay thin seams.
- Build WARM; verify via `:app:assembleDebug` (fires 46 gates on preBuild) + `:app:testDebugUnitTest`, not `:app:lintDebug` (pre-existing `VaultAndroidOps:267` NewApi RED is unrelated).
- Device-verify on RZCYA1VBQ2H. No PR/merge without explicit owner GO; never push master directly.

## 9. Out of scope

- Any fps fix (capture, callback, or encoder).
- Render-topology change (gated behind the verdict + a new GO).
- Pause/resume (remains DEFERRED).
- CameraX version bump (1.5.x) — may surface as a candidate in the follow-up fix spec, not here.
- Permanent/shipping perf telemetry — this probe is a throwaway diagnostic harness.
