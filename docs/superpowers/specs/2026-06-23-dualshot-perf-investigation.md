# DualShot Performance Investigation — Frame Jitter + Thermal Buildup

**Status**: Investigation plan (research-only, no code change)
**Date**: 2026-06-23
**Device**: RZCYA1VBQ2H (Samsung Galaxy A-class, single-ISP), Android 14
**Owner report**: 2026-06-17 DualShot smoke
**Backlog item**: `docs/BACKLOG.md` "Capture / Mode" — *DualShot performance — frame jitter + thermal buildup (P2)*
**Related**: ADR-0009 (4:3 source), ADR-0008 (dual-record arch), ADR-0016 / ADR-0019 (thermal autostop), `memory/project_dualshot_stability_stack.md`, δ0 CameraX 1.5.3 bump (BACKLOG, P3)

> Scope guard: this is a **plan to investigate**, not a fix. Every optimization in §5 is gated on the §4 device measurement that confirms its hypothesis. Do **not** change capture math, crop matrices, or the rotation pinning (ADR-0009 invariants) as part of this work.

---

## 1. Symptom restatement + implicated code paths

### Symptom A — frame jitter / stutter
DualShot preview + recorded footage is visibly less smooth than the native single-encode camera. The single-encode path (`RovaRecordingService.recordSegment`, CameraX `VideoCapture` → one HW encoder, no app-side GL) is smooth; DualShot (`recordSegmentDual` → `DualVideoRecorder`) is not. The delta is everything DualShot adds on top of single-encode:

- **The camera-callback render pump** — `EglRouter.renderFrame()` (`service/dualrecord/internal/EglRouter.kt`), fired from `DualSurfaceProcessor.onInputSurface`'s `setOnFrameAvailableListener { router.renderFrame() }` (`DualSurfaceProcessor.kt:110`). This runs **once per camera frame on the SurfaceTexture callback thread** and does: `updateTexImage` + `getTransformMatrix` + a full-frame OES→FBO **identity blit** + `glFenceSync`/`glFlush` + the inline preview draw.
- **Two per-side encoder render threads** — `EncoderRenderThread.run()` / `drawFrame()` (`EncoderRenderThread.kt:115-244`), each `glWaitSync` → draw cropped quad → **`glFinish()`** → `eglSwapBuffers`.
- **Latest-wins mailbox** — `FrameMailbox` (`FrameMailbox.kt`) drops frames per side when an encoder can't keep up — silent, uneven frame delivery is *expected* here, which is exactly how jitter would manifest.
- **Broadcast audio** — `AudioFanOut` (`AudioFanOut.kt`) feeds one AAC encoder broadcast to two muxers; if its `onSample`/muxer write contends, A/V desync reads as stutter on playback.

### Symptom B — excessive heating + thermal autostop after ~5+ clips
Sustained SoC load trips the thermal warning and the segment-boundary autostop. Implicated:

- **2× AVC encoders @ 8 Mbps each + 1 AAC** running continuously — `EncoderSurface` × 2 (`DualVideoRecorder.kt:106-133`), config `portraitBitrate = landscapeBitrate = 8_000_000`, `fps = 30` (`RovaRecordingService.kt:2111-2118`). 16 Mbps aggregate video vs single-encode's one stream.
- **GL fan-out processing ~2.4× the pixels of the 6.1c baseline** — ADR-0009 §Consequences explicitly flags this: source 2560×1920 = 4.9 Mpx/frame vs 1920×1080 = 2.1 Mpx. The FBO ring is 2560×1920 RGBA8 (`FboRing.FBO_WIDTH/HEIGHT`), ~19.7 MB/slot × 3 = ~59 MB resident.
- **Two `glFinish()` per frame** (`EncoderRenderThread.drawFrame:243`) — busy-spins the GPU pipeline to completion twice per frame, defeating GPU/CPU overlap and raising power draw.
- **Thermal autostop** — `SegmentGateThermal.shouldTerminate` (`SegmentGateThermal.kt`) fires at `THERMAL >= CRITICAL`, checked in `checkSegmentGates()` at segment boundaries only (`RovaRecordingService.kt:2612-2619`), gated through `ThermalHysteresis` (3 s fall-dwell, ADR-0019). The autostop is a *symptom reporter*, not a cause — but its threshold/cadence is worth confirming (see §4 + §6).

---

## 2. Ranked hypotheses — JITTER

> Ranking = (likelihood it's the dominant cause) × (how directly the code points at it). Each is falsifiable by a §4 measurement.

### J1 — `glFinish()` per side per frame stalls the encoder threads (HIGH)
`EncoderRenderThread.drawFrame()` ends with `GLES20.glFinish()` (`EncoderRenderThread.kt:243`). `glFinish` blocks the CPU thread until **all** GPU work for that context completes. Both encoder threads do this every frame. It serializes GPU submission and removes CPU/GPU overlap; under the 4.9 Mpx → cropped-encoder draw load this can push per-side frame time past the 33 ms/frame budget at 30 fps, so the `FrameMailbox` starts dropping frames (latest-wins) → uneven cadence → visible stutter. The KDoc justifies `glFinish` as "must complete before the depth-3 ring wraps" — but a 3-deep ring at 30 fps gives ~100 ms of slack; a fence-based or no-op approach may suffice. **Most suspect: directly observable as encoder-thread wall time.**

### J2 — full-frame OES→FBO identity blit on the callback thread (HIGH)
`EglRouter.renderFrame()` blits the entire 2560×1920 OES frame into an FBO slot **every frame, unconditionally**, before any encoder samples it (`EglRouter.kt`, the `if (liveEncoders.isNotEmpty() && ring != null)` block). The blit is a full-viewport draw at 2560×1920. This is pure overhead the single-encode path does not pay, and it sits on the camera callback thread — if it overruns the inter-frame interval, the SurfaceTexture producer back-pressures and **the camera itself delivers frames late**, which jitters *both* preview and both encoders simultaneously. Measure: the `perfBlit*` counters already instrumented in `EglRouter` (DEBUG-gated, `perfNow()`), plus the camera frame-delivery interval.

### J3 — render pump fully serialized on a single callback thread (MEDIUM-HIGH)
The whole pump (`updateTexImage` → blit → fence → inline preview draw) runs on one SurfaceTexture callback thread. The preview draw (`for (target in snapshot)`) is also inline on this thread. Any one slow step delays the next `onFrameAvailable`. The architecture moved *encoder* work off this thread (the encoder render threads), but the **blit + preview** are still inline. If preview is attached (RecordScreen visible) the cost is higher than headless. Measure: callback-thread total time vs camera frame interval, with preview attached vs detached.

### J4 — `FrameMailbox` latest-wins masks drops as silent jitter (MEDIUM, diagnostic not root cause)
`FrameMailbox.offer` overwrites unread frames (`FrameMailbox.kt:26-32`). This is *correct* back-pressure design but means a slow encoder silently drops frames with no counter. Jitter is the visible result of J1/J2/J3 starving an encoder. Not a root cause on its own, but **add a drop counter here** during profiling so dropped-frame rate per side is a measured number, not an inference.

### J5 — audio broadcast / muxer-write contention (LOW-MEDIUM)
`AudioFanOut.drainLoop` calls `onSample(buf.duplicate(), info)` → `muxer.writeAudio(sides, ...)` to both muxers (`DualVideoRecorder.kt:155`). `MediaMuxer.writeSampleData` is synchronous; two muxers writing audio + video from multiple threads can contend on I/O. Audio PTS is frame-counter-derived (immune to scheduling jitter, per `AudioFanOut` KDoc), so audio *timing* is sound — but muxer write stalls could back-pressure the video drain loops. Lower priority; check only if J1–J3 don't account for the stutter.

### J6 — no display-rate frame pacing; encoders draw every delivered frame (LOW)
There is no VSYNC/`Choreographer` pacing; encoders consume whatever the mailbox holds. Camera delivers at sensor cadence (may be variable under AE/auto-exposure in low light). If the camera's own output is uneven (e.g. 24–30 fps adaptive), DualShot faithfully reproduces it while CameraX's `VideoCapture` may internally smooth/pace. Measure camera frame-interval distribution first — this could be a camera-config issue, not a GL issue.

---

## 3. Ranked hypotheses — HEATING

### H1 — two 8 Mbps AVC encoders running continuously (HIGH)
`portraitBitrate = landscapeBitrate = 8_000_000`, both H.264, `fps=30` (`RovaRecordingService.kt:2111-2118`). 16 Mbps aggregate video encode is roughly double a single FHD stream. HW encoder blocks are a major SoC heat source under sustained load. The bitrate is hard-coded "FHD-locked for v1" (comment `RovaRecordingService.kt:2104`) and not derived from `BitrateTable` (which exists and is anchored to `StorageEstimator`, `BitrateTable.kt`). **Highest-leverage, lowest-risk knob.** Measure: encoder block power/temp contribution; A/B 8→6→4 Mbps and `KEY_I_FRAME_INTERVAL` (currently `1` — one keyframe per second, `EncoderSurface.kt:54`; a larger GOP cuts encode work materially).

### H2 — GL fan-out at 4.9 Mpx/frame (the ADR-0009 pre-flagged contingency) (HIGH)
ADR-0009 §Consequences explicitly predicted this: 4:3 source = 2560×1920 = 4.9 Mpx, ~2.4× the 6.1c baseline, with a **named escape hatch**: drop `DualCameraSizeResolver.PIXEL_PERFECT_SHORT_EDGE` from 1920→1440 (`DualCameraSizeResolver.kt:41`). Every frame: 1 full-res OES→FBO blit + 2 cropped encoder draws + 1 preview draw, all GPU. GPU sustained load = heat. Measure: GPU utilization + GPU rail power over a 6-clip run; A/B the 1440 short-edge.

### H3 — two `glFinish()` per frame defeat GPU power-gating (MEDIUM-HIGH)
Same code as J1 (`EncoderRenderThread.kt:243`), heat angle: `glFinish` forces the GPU to drain and the CPU to busy-wait, preventing the GPU from idling/clock-gating between frames and pinning a CPU thread hot. Removing/replacing the double `glFinish` (with fence-only sync, which the B3 work already introduced for blit→sample ordering) could cut both CPU and GPU power. **Tied to J1 — one fix addresses both jitter and heat.**

### H4 — FBO ring memory + bandwidth (MEDIUM)
3 × 2560×1920 RGBA8 = ~59 MB of GPU textures, written (blit) + read (2 encoders sample) every frame. Memory bandwidth is a real power cost on mobile SoCs. The ring KDoc pre-flags dropping to depth-2 (~39 MB) under memory pressure (`FboRing.kt:168-170`). Measure: only meaningful after H1/H2; bandwidth is hard to isolate but ring-depth A/B is cheap.

### H5 — no thermal-aware degradation; load is constant until autostop (MEDIUM, design gap)
There is no mid-session quality/fps/bitrate backoff. `SegmentGateThermal` only **terminates** at CRITICAL (`SegmentGateThermal.kt`); nothing *reduces* load as temp climbs through SEVERE. The system runs flat-out until it trips the cliff. A graduated response (drop fps 30→24, or bitrate, or short-edge, at SEVERE) would let long sessions survive. This is an architectural gap, not a bug — note for §5/§6, requires ADR-0016/0019 amendment if pursued.

### H6 — per-segment camera/encoder teardown+rebuild churn (LOW, verify)
Confirm whether DualShot tears down and rebuilds the `DualVideoRecorder` / CameraX binding **per segment** or reuses a warm session across the loop. `DualVideoRecorder.release()` is terminal ("cannot be re-used", `DualVideoRecorder.kt:33`), but `start()` allows recorder reuse across segments (`onStopped = { activeRecording = null }`, comment `:169`). Repeated EGL context + encoder + AudioRecord teardown/setup per clip would add CPU spikes and heat. **Trace the segment loop (`recordSegmentDual`, `RovaRecordingService.kt:~3019`) to confirm warm-reuse vs cold-rebuild per segment** — this materially changes the heat profile across "5+ clips."

---

## 4. On-device profiling plan (RZCYA1VBQ2H, Android 14)

> adb MCP is broken on Windows (per `memory`); use **PowerShell-direct `adb`**. Run from repo root. Build a **DEBUG** APK so the `EglRouter` `perfNow()`/`recordRenderPerf()` counters and `RovaLog.d` lambdas are live.

### 4.0 Setup
```powershell
adb devices                                  # confirm RZCYA1VBQ2H attached
adb shell getprop ro.build.version.release   # confirm Android 14
adb logcat -c                                # clear log buffer
```

### 4.1 Perfetto / systrace capture (jitter + thread attribution)
Capture a system trace over a full ~6-clip DualShot run. Categories that matter:

```powershell
# Perfetto config-driven capture (preferred on Android 14):
adb shell perfetto -o /data/misc/perfetto-traces/dualshot.pftrace -t 60s `
  -b 64mb sched freq idle gfx view camera am wm power
# pull it back
adb pull /data/misc/perfetto-traces/dualshot.pftrace .
```
Categories rationale:
- `gfx` + `view` — GL/SurfaceFlinger, FBO blit + swap cost, frame submission.
- `sched` + `freq` + `idle` — CPU scheduling of the callback thread + 2 encoder threads + 2 drain threads; CPU clock/idle states (thermal-throttle clock drops show here).
- `camera` — CameraX/Camera2 frame delivery cadence (the J6/J2 back-pressure question).
- `power` — rail/temp counters where exposed.
- `am`/`wm` — segment-boundary teardown spikes (H6).

In the trace, attribute by thread name (all are explicitly named in source):
`AudioFanOut-capture`, `AudioFanOut-drain`, `EglEncoder-PORTRAIT`, `EglEncoder-LANDSCAPE`, `EncoderSurface-PORTRAIT-drain`, `EncoderSurface-LANDSCAPE-drain`, and the SurfaceTexture callback thread running `EglRouter.renderFrame`.

### 4.2 App-side perf counters (already instrumented)
`EglRouter` accumulates per-frame timings (DEBUG only): interval, `tex` (updateTexImage), `blit` (OES→FBO), `draw`, `prevSwap`, total — summarized ~1 line / 2 s. Capture them:
```powershell
adb logcat -v time | Select-String "EglRouter|EglEncoder|DualSurfaceProcessor" `
  | Tee-Object dualshot_perf.log
```
Key derived metrics:
- **Camera frame-delivery interval** (mean + p95 + max) — from `perfInterval*`. >33 ms p95 at 30 fps ⇒ camera back-pressured (J2/J6).
- **`blit` time** — headline GL callback cost (J2).
- **Per-side encoder-thread frame time** — instrument `EncoderRenderThread.drawFrame` (add a `perfNow()` span around draw+`glFinish`+swap) to isolate the `glFinish` cost (J1/H3).
- **Dropped-frame count per side** — add a counter in `FrameMailbox.offer` (increment when overwriting a non-null slot) (J4).

### 4.3 GPU profiler
- **Android GPU Inspector (AGI)** or the device's Mali/Adreno profiler: capture GPU utilization %, GPU rail power, and a per-frame GPU timeline over one clip. Confirm whether GPU sits near 100% (H2) and whether the two `glFinish` calls show as pipeline bubbles (H3).
- If AGI unavailable, `adb shell dumpsys gfxinfo com.aritr.rova framestats` for frame-time histograms (preview path).

### 4.4 Thermal / temp curve over ~6 clips
```powershell
# Sample SoC temp + thermal status every 2s during the run (loop in PowerShell):
1..180 | ForEach-Object {
  $t = adb shell dumpsys thermalservice 2>$null
  "$(Get-Date -Format o)`n$t" | Add-Content dualshot_thermal.log
  Start-Sleep -Seconds 2
}
# Also raw thermal zones:
adb shell "cat /sys/class/thermal/thermal_zone*/type /sys/class/thermal/thermal_zone*/temp" | Add-Content dualshot_zones.log
# App-side thermal status transitions (ThermalStatusSignal logs through hysteresis):
adb logcat | Select-String "thermal|Thermal|SegmentGate"
```
Plot temp vs wall-clock across the 6 clips; mark each segment boundary and the CRITICAL trip. Compare the curve's slope against an identical-duration single-encode run to quantify DualShot's incremental heat.

### 4.5 Attribution: jitter vs thermal (control runs)
Run the same 6-clip protocol four ways and diff the metrics:
1. **Single-encode** baseline (smooth, cool) — control.
2. **DualShot, default** — the reported-bad config.
3. **DualShot, cool-start, 1 clip** — isolates jitter that exists *before* heat (proves J1/J2/J3 are not merely thermal-throttle artifacts).
4. **DualShot after forced heat-soak** — isolates jitter that only appears once the SoC throttles (would point at H1/H2 → clock drop → J1/J2 overruns).

If clip #1 (cold) already stutters ⇒ jitter is a **steady-state render-cost** problem (J1/J2/J3), independent of heat. If only late clips stutter ⇒ jitter is **thermal-throttle-induced** and fixing heat (H1/H2/H3) fixes both.

---

## 5. Candidate optimizations mapped to hypotheses

> Ranked by (impact / effort / risk). "Measure first" = do **not** commit before the §4 metric confirms the hypothesis.

| # | Optimization | Addresses | Impact | Effort | Risk | Gate |
|---|---|---|---|---|---|---|
| O1 | Replace per-side `glFinish()` with fence-only sync (or single deferred finish per ring-wrap) in `EncoderRenderThread.drawFrame` | J1, H3 | High (both symptoms) | Low | Med — correctness vs ring-wrap reuse; needs A/V + tearing smoke | Measure encoder-thread time first (§4.2) |
| O2 | Lower video bitrate 8→6→4 Mbps and/or raise `KEY_I_FRAME_INTERVAL` past 1 s; drive bitrate from `BitrateTable` instead of the hard-coded 8 Mbps | H1 | High (heat) | Low | Low — quality tradeoff only | A/B in §4.4; pick knee of temp-vs-quality |
| O3 | Drop 4:3 source short-edge 1920→1440 (`DualCameraSizeResolver.PIXEL_PERFECT_SHORT_EDGE`) — the ADR-0009 pre-flagged escape hatch | H2, J2 | High (heat + blit cost) | Trivial (1 const) | Low-Med — PORTRAIT loses pixel-perfectness (ADR-0009 documented) | Measure GPU util + blit time first (§4.3) |
| O4 | Graduated thermal backoff: at SEVERE drop fps 30→24 or bitrate, instead of only terminating at CRITICAL | H5 | High (long-session survival) | Med | Med — needs ADR-0016/0019 amendment + new `check*` if invariant | Confirm temp curve slope (§4.4) |
| O5 | Skip the OES→FBO blit when no encoder needs a new frame / pace the blit to a frame budget | J2, J3, H2, H4 | Med-High | Med | Med — touches the fence-sync contract; regression-prone | Confirm callback-thread overrun (§4.2) |
| O6 | Reduce FBO ring depth 3→2 | H4 | Low-Med | Trivial | Low | Only if bandwidth shows up (§4.3) |
| O7 | Add `FrameMailbox` drop counter + `EncoderRenderThread` timing spans | J1, J4 (diagnostic) | Enables all of the above | Low | None | Do this **first** — it's measurement infra |
| O8 | Confirm + fix per-segment warm-reuse (avoid cold EGL/encoder rebuild per clip) | H6, J3 | Med (if churn exists) | Med | Med | Trace segment loop first (§4.1, H6) |

**Recommended order**: O7 (instrument) → §4 capture → then O2 + O3 (cheap, high-impact heat wins, low risk) → O1 (the double-`glFinish`, biggest combined win but needs careful smoke) → O4/O5/O8 as warranted by data.

---

## 6. Open questions + δ0 (CameraX 1.5.3)

### Open questions
1. **Cold vs thermal jitter** — does clip #1 stutter cold? (Decides whether jitter is render-cost or throttle-induced — §4.5.) This single experiment reprioritizes everything.
2. **Warm-reuse per segment?** — does `recordSegmentDual` keep the `DualVideoRecorder` + CameraX binding warm across the loop, or rebuild per clip? (H6, `RovaRecordingService.kt:~3019`, `~2078` setup.) Materially changes the across-clips heat slope.
3. **Camera frame cadence** — is the camera itself delivering an uneven/adaptive frame rate (low-light AE) that DualShot faithfully reproduces while `VideoCapture` smooths? (J6 — measure before blaming the GL path.)
4. **Thermal threshold/cadence** — autostop checks only at segment boundaries (`checkSegmentGates`), so a long segment can soak past CRITICAL mid-clip. Is the segment length × CRITICAL threshold the right cliff, and is the ADR-0019 3 s fall-dwell interacting with rapid clip cycling? (§4.4.)
5. **Is the preview attached during the reported smoke?** Preview draw adds callback-thread cost (J3); headless recording may jitter less. Confirm the reproduction context.
6. **HEVC vs H.264** — `BitrateTable` reserves codec for "future HEVC tuning" (`BitrateTable.kt:37`); HEVC at lower bitrate could cut both file size and encode heat, but HW HEVC dual-encode support on this A-class SoC is unverified.

### What δ0 (CameraX 1.4.2 → 1.5.3) might / might not help
- **Might help**: 1.5.1 ships a **`SurfaceProcessor`-shutdown crash fix** that is squarely on DualShot's path (`DualSurfaceProcessor`/`EglRouter` teardown). If H6 (per-segment teardown churn) is real, 1.5.x could make teardown cleaner/cheaper and reduce churn spikes. The probe already proved 1.5.3 compiles clean against Single + DualShot (zero API breaks) — so it's a low-friction dependency to adopt for the regression-smoke.
- **Unlikely to help directly**: the 1.5.1 fix is a **crash/shutdown** fix, not a throughput or frame-pacing fix. The jitter (J1/J2/J3) and heat (H1/H2/H3) hypotheses live entirely in **our** app-side GL fan-out + encoder config, which CameraX does not touch. δ0 will not change the double-`glFinish`, the 8 Mbps×2 bitrate, or the 4.9 Mpx blit. **Do not expect δ0 to fix the reported symptoms** — adopt it for reliability hygiene + the cleaner teardown, but pursue O1–O4 independently. δ0 is P3 merge-later (BACKLOG); this perf work should not block on it, and vice versa.

---

## 7. Static resolution of open questions (2026-06-24, NO device)

> Device profiling (§4) is **blocked this cycle**: `RZCYA1VBQ2H` shows `unauthorized` (needs owner to accept the USB-debugging dialog on the phone), and the protocol needs a human to physically drive ~6 DualShot clips. So §4 measurements are deferred. What follows is the **source-only** resolution of the open questions that do not need a device — it narrows the hypothesis set before the device pass.

### Q2 / H6 — Warm-reuse vs per-segment rebuild → **RESOLVED: WARM REUSE. H6 is NOT a contributor.**
The `DualVideoRecorder` is constructed **once outside the loop** in `setupDualCamera()` (`RovaRecordingService.kt:2139`), then reused across every clip via `recorder.start(...)` per segment (`:3068`) with a per-clip stop. `release()` fires **only on session exit / error** (`:1634`, `:2221`, `:3634`), never inside the periodic loop. Evidence: `DualVideoRecorder.kt:169` `// Phase 6.1b smoke-fix — allow recorder reuse across segments. onStopped = { activeRecording = null }`. The EGL context, CameraX binding, and `AudioRecord` persist warm across the loop; only `EncoderSurface` muxers cycle per `start()`. **Implication:** the heat-across-clips slope is **steady-state encode + GPU load (H1/H2/H3)**, not per-clip teardown/rebuild churn. **Drop H6 from the heat ranking** — the "5+ clips" thermal climb is cumulative SoC soak under constant load, not rebuild spikes. The δ0 (1.5.1 SurfaceProcessor-shutdown) angle is therefore **even less relevant to heat** (teardown happens once per session, not per clip).

### Q5 / J3 — Preview attached? → **RESOLVED: preview draw is target-gated; headless = zero preview cost.**
`EglRouter.renderFrame` snapshots the registered targets (`EglRouter.kt:568`) and the per-target draw loop (`:653-763`) only runs for **registered, alive** targets. Preview targets are registered by `attachPreviewInput` (`DualVideoRecorder.kt:181`) when a `RecordScreen` TextureView attaches, and removed on detach. **Headless recording → empty snapshot → zero preview draw on the callback thread.** Foreground (RecordScreen visible) adds **two** preview draw+swap passes (PORTRAIT+LANDSCAPE) to the callback thread per frame. **Implication:** J3 callback-thread overrun is real **only when the screen is on**; the reported smoke almost certainly had preview attached (owner was watching). **The device pass must record both foreground and headless** (§4.5 control set) to split preview cost from the unconditional blit cost.

### Q3 / J6 — Camera frame cadence → **NEW FACT: no camera target-FPS lock is set.**
`fps = 30` (`RovaRecordingService.kt:2118`) is the **encoder** `KEY_FRAME_RATE` (`EncoderSurface.kt:53`), **not** a CameraX/`Camera2Interop` `CONTROL_AE_TARGET_FPS_RANGE` on the capture request. The dual bind (`~:2187`) sets **no** target-FPS range, so the sensor free-runs at whatever AE picks — which in low light can drop to 24/15 fps. DualShot then faithfully reproduces the uneven cadence (J6) while CameraX `VideoCapture` may internally pace the single-encode path. **New, cheap, device-confirmable candidate fix:** pin `CONTROL_AE_TARGET_FPS_RANGE` to `[30,30]` (or `[24,30]`) via `Camera2Interop` on the dual bind — but **only if** the §4.1 camera-frame-interval distribution shows sensor-side variability; if jitter is steady-state GL cost (cold clip #1 stutters), this won't help. Measure first.

### Q6 etc. — config facts **re-confirmed from source** (correcting two stale claims):
- **`BitrateTable` DOES exist** (`service/dualrecord/internal/BitrateTable.kt` + `BitrateTableTest.kt`, "FHD bitrate from StorageEstimator") but is **NOT wired** into the dual encoder config — `portraitBitrate = landscapeBitrate = 8_000_000` are **hard-coded** (`RovaRecordingService.kt:2111-2112`); the only `BitrateTable` reference there is the future-TODO comment (`:2104` "6.1c may lookup BitrateTable per resolution"). O2 (drive bitrate from `BitrateTable`) remains valid and the table is ready to wire.
- **`KEY_I_FRAME_INTERVAL = 1` means 1 keyframe per *second*** (≈30-frame GOP at 30 fps), **not** one keyframe per frame. Raising it past 1 s still materially cuts encode work (O2), but the baseline is not the pathological all-keyframe case.
- `glFinish()` per encoder side confirmed (`EncoderRenderThread.kt:243`, KDoc: "must complete before the depth-3 ring wraps"); blit fence confirmed (`EglRouter.kt:628-629` `glFenceSync`+`glFlush`, encoder `glWaitSync` `:130`). `FrameMailbox.offer` silent overwrite, no counter, confirmed (`FrameMailbox.kt:29`).

### Updated hypothesis posture (post-static, pre-device)
- **Heat**: H1 (2×8 Mbps) + H2 (4.9 Mpx GL) + H3 (double `glFinish`) remain the live candidates; **H6 eliminated**; H5 (no graduated backoff) stands as a design gap. O2 + O3 stay the cheap high-impact heat knobs.
- **Jitter**: J1 (`glFinish`) + J2 (unconditional blit) remain top; J3 is **foreground-only**; J6 now has a concrete cheap fix candidate (FPS-range lock) **gated on** the §4.1 camera-cadence measurement; the cold-clip-#1 experiment (§4.5) is still the single most decisive next step (render-cost vs throttle-induced).
- **Still device-blocked (cannot resolve without §4):** absolute encoder-thread frame time, GPU utilization/rail power, the temp-vs-clip curve + CRITICAL trip timing, the cold-vs-soaked jitter split, and the camera frame-interval distribution. These remain the deliverable of a later authorized-device pass.

**Next action for the device pass:** owner accepts USB-debugging on `RZCYA1VBQ2H`, then runs the §4.5 four-way control protocol (single-encode / DualShot-cold-1-clip / DualShot-default / DualShot-heat-soaked), foreground **and** headless, capturing §4.1 Perfetto + §4.2 `EglRouter` counters + §4.4 thermal loop. The cold-clip-#1 result reprioritizes everything else.

---

## Appendix — measured/confirmed config facts (from source, 2026-06-23; re-confirmed 2026-06-24)
- Video: 2× H.264, **8 Mbps each**, **fps 30**, `KEY_I_FRAME_INTERVAL = 1` (`EncoderSurface.kt:52-54`, `RovaRecordingService.kt:2111-2118`).
- Audio: 1× AAC-LC, 128 kbps, 48 kHz, mono, CAMCORDER source, broadcast to both muxers (`AudioFanOut.kt`, `RovaVideoRecorder.kt:151-156`).
- Source: native 4:3, largest ≥1920 short-edge (typ. 2560×1920 = 4.9 Mpx); escape hatch = `PIXEL_PERFECT_SHORT_EDGE` 1920→1440 (`DualCameraSizeResolver.kt:41`).
- Per-frame GL (callback thread): `updateTexImage` + full-res OES→FBO **identity blit** + `glFenceSync`+`glFlush` + inline preview draw (`EglRouter.renderFrame`).
- Per-frame GL (each of 2 encoder threads): `glWaitSync` → cropped quad draw → **`glFinish()`** → `eglSwapBuffers` (`EncoderRenderThread.drawFrame:193-244`).
- FBO ring: depth 3 × 2560×1920 RGBA8 ≈ 59 MB (`FboRing.kt:163-181`).
- Frame back-pressure: per-side latest-wins, silent drops, **no drop counter** (`FrameMailbox.kt`).
- Thermal: terminate-only at `>= CRITICAL`, segment-boundary check, 3 s fall-dwell hysteresis (`SegmentGateThermal.kt`, `ThermalHysteresis.kt`, `RovaRecordingService.kt:2612-2619`). **No graduated mid-session backoff.**
