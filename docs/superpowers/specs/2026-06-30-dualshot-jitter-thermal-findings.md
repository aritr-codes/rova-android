# DualShot jitter + sustained-thermal — profiling findings

**Date:** 2026-06-30. **Device:** RZCYA1VBQ2H (Exynos). **Build:** CameraX 1.5.3 (δ0, PR #161) debug.
**Method:** on-device profiling — recorded-frame PTS analysis (ffprobe), `dumpsys gfxinfo`, `dumpsys thermalservice`/battery, `dumpsys SurfaceFlinger`, and the in-code `EglRouter.recordRenderPerf` DEBUG probe. codex-reconciled.

> Scope note: the fps/throughput plateau (~22fps) is already closed — Limiter 1 (#155, AE floor) + Limiter 2 (#157, inherent dual-HW-encode contention). It is **out of scope** here. This pass targets (a) perceived jitter/smoothness and (b) sustained-thermal "make lighter."

## Jitter — no fixable render-pacing defect

Three instruments agree the capture/preview render path is well-paced:

1. **Recorded-output frame pacing** (encoder PTS is wall-clock at `eglSwapBuffers`, so recorded inter-frame intervals = encoder pacing). DualShot P+L merged clips, in-segment (excluding boundaries): mean ~46ms (≈21.6fps), **sd ~8-9ms**, p95 ~62ms, p99 ~76ms, hitches >70ms only ~2-4%. The 1.5.3 bump slightly *improved* it (hitch 3.9%→2.1%, p95 69→62ms — the 1.5.x recording-churn fix). ~1 large gap per merged file of ~1.3-1.6s = the **segment-boundary seam** (clip rollover finalize+restart), architectural — once per 20s in the *merged* file only.

2. **`EglRouter.recordRenderPerf`** over a 5-min cold→warm DualShot run (AP 30.7→41.5°C, thermal status 0 throughout) — **flat across the entire run**:

   | metric | t=30s | t=300s |
   |---|---|---|
   | interval avg | 42.7ms | 41.7ms |
   | prevSwapMax (preview swap) | 34.9ms | 34.1ms |
   | blit avg | 0.6ms | 0.6ms |
   | renderTotal avg | 33.7ms | 28.2ms |
   | updateTex avg | 0.9ms | 0.9ms |

   Applying the discriminator (interval↑ = callback collapse; prevSwapMax↑ with stable interval = preview presentation victim; blit↑ = GPU/EGL contention): **none grew → the camera-preview render path is NOT the jank source and does not degrade with sustained load or warming.** `blit`=0.6ms also directly confirms **GPU fill is negligible** (refutes a code-reading hypothesis that the FBO blit was a major cost).

3. **`dumpsys gfxinfo`** (the app HWUI/Compose path, separate from the EGL preview): idle = 0 missed vsync; 60s DualShot = 0 missed vsync, p99 24ms; a 5-min DualShot capture showed 3942 missed vsync / p99 109ms — but that dump window included the post-stop navigation into a large thumbnail grid, and the EGL preview path (instrument 2) is provably steady over the same duration. So the gfxinfo spike is the **Compose chrome / Library-nav HWUI path, not the camera preview**. (A during-recording gfxinfo dump would confirm; the EGL probe already settles the preview question.)

**Conclusion:** The "less smooth than native" is dominated by the inherent **~22-24fps vs 30fps** (Limiter 2, out of scope). There is **no separate, fixable render-pacing defect** in the capture/preview path. The only concrete *recorded-output* smoothness artifact is the ~1.4s segment-boundary seam — noted as a separately-scoped item (an architectural segment-loop change touching gate-pinned terminal/merge/recovery invariants; not pursued this cycle).

## Thermal — real (owner repro), encoder-bound, slow soak

- **No fast spike.** 60s DualShot = no measurable AP rise. Slow heat-soak over minutes.
- **A/B long-soak (5 min each, same SD preset, cool room, USB-charging):**
  - Single (1 encode): AP plateau ~44°C, status 0, no autostop.
  - DualShot (2 encodes): AP 34→45°C from cold, status 0, **no autostop in 5 min** here. Heats faster from cold; similar plateau.
  - → The owner-reported autostop ("after 5+ clips") is **ambient-marginal** — reproduces in warmer / on-battery conditions, not in a cool charging room within 5 min. Trusted per owner; scoped anyway.
- **Dominant contributor = the two hardware video encoders + camera pipeline** (per #157: ~97% `glFinish`/codec-sync; GPU fill microseconds — re-confirmed here by `blit`=0.6ms; muxer 0.5ms). **GPU-render micro-optimization is NOT a thermal lever.** Bitrate is a weak lever on fixed-function encoders (pixels/frame & topology dominate).

## Lever selection (→ fix design)

Merge requires per-side segments to share resolution, which constrains the levers:
- **Frame-rate decimation (chosen)** — merge-safe (variable fps, constant dims), hits encoder duty directly, preview-preservable. → see `2026-06-30-dualshot-thermal-decimation-design.md`.
- Resolution drop + new session — strongest relief, but touches the most load-bearing gate-pinned surface (terminal/recovery/export). Rejected for this cycle.
- Whole-session resolution at start — doesn't solve a mid-session heat-soak. Rejected.

## δ0 (CameraX 1.5.3) verification summary (this session)

Build wall GREEN (`assembleDebug` 47 gates + `testDebugUnitTest`); ffprobe baseline-vs-bumped CLEAN (res/rotation/codec/audio preserved); DualShot force-stop teardown no-crash (only benign `CancellationException` on `SurfaceProcessorNode`); recovery clean. codex: merge behind owner GO. PR #161 open; owner manual checks (A/V sync, rotation-across-boundary, front-cam mirror, long-run thermal) outstanding.
