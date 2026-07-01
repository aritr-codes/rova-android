# ADR-0035: DualShot thermal-adaptive encode decimation

**Status:** Accepted (2026-06-30)

## Context

The 2026-06-30 profiling pass (`docs/superpowers/specs/2026-06-30-dualshot-jitter-thermal-findings.md`,
codex-reconciled) proved the DualShot sustained-thermal climb is **encoder-bound
and a slow soak** — the two hardware video encoders plus the camera pipeline
dominate heat (per #157: ~97% `glFinish`/codec-sync; GPU fill is microseconds —
re-confirmed here by `blit`=0.6ms, muxer 0.5ms). GPU-render micro-optimization
is not a thermal lever; bitrate is weak on fixed-function encoders. The owner
reports the `CRITICAL` thermal autostop firing after 5+ DualShot clips in
warm / on-battery conditions.

`SegmentGateThermal` (ADR-0016) is binary today: at `ThermalStatus.CRITICAL` it
terminates the whole session at the next segment boundary. There is no
intermediate "run lighter" rung — the device climbs to `CRITICAL` and hard-stops.

Merge requires per-side segments to share resolution, which constrains the
levers. Resolution-drop + new-session gives the strongest relief but touches the
most load-bearing gate-pinned surface (terminal/recovery/export) — rejected for
this cycle. Whole-session resolution at start does not address a mid-session
heat soak — rejected.

## Decision

Insert **one graceful rung** between `SEVERE` (already the active-HUD user-stop
nudge) and the `CRITICAL` autostop: when the stable thermal status reaches
`SEVERE` (`ordinal >= SEVERE`), throttle the rate at which frames are submitted
to the two encoders to **half** (encode every 2nd frame) — a single-step
decimation factor of 2. This ~halves encoder duty → slower heat rise → longer to
`CRITICAL` (which remains the backstop). On cooldown the factor returns to 1.

The decision is a pure helper, `ThermalDecimationPolicy`:
- `decimationFactor(status) = if (status.ordinal >= SEVERE.ordinal) 2 else 1`
- `shouldSubmit(frameCounter, factor) = factor <= 1 || frameCounter % factor == 0`

It reads the already-hysteresis'd `thermalStatusSignal.state.value` (ADR-0019
asymmetric hysteresis is applied upstream in `ThermalStatusSignal`), so it needs
**no hysteresis of its own** — the engage/release debounce that prevents flap at
the `SEVERE` boundary is inherited. `RovaRecordingService.checkSegmentGates`
sets the factor each segment boundary (the same stable-status read the terminate
gate uses) through a `DualVideoRecorder` → `DualSurfaceProcessor` → `EglRouter`
passthrough. `EglRouter` reads a `@Volatile var encodeDecimationFactor` and
skips the encoder-feed block on decimated frames.

**What is preserved (deliberately):**
- **Preview stays full-rate.** Decimation applies only at the encoder-submit
  point in `EglRouter.renderFrame()`, never the preview-render loop. Smooth
  preview under load is the load-bearing invariant.
- **AE fps floor untouched** (ADR-0034). The camera still runs ≥24fps for
  exposure; we simply do not encode every frame. No `CONTROL_AE_TARGET_FPS_RANGE`
  change.
- **Resolution unchanged** → **merge-safe.** Variable fps concatenates cleanly
  (PTS is wall-clock monotonic; dimensions constant). No new-session / manifest /
  recovery surface.
- **`factor == 1` is byte-identical to prior behavior** (cool case, and all
  non-DualShot paths — `currentDualRecorder` is null for single mode).

Single step (not a graded ladder) per YAGNI: `SEVERE` already shows a HUD nudge,
`CRITICAL` already hard-stops — this inserts exactly one rung between them. A
second rung can be added later if device soak shows half-rate relief is
insufficient (explicitly deferred).

**Tradeoff (accepted):** while `SEVERE`+, recorded DualShot fps ~halves (a
visible quality drop) — accepted only while the device is hot, in exchange for a
materially longer session before the hard autostop. Cool-case capture is
unaffected.

## Clause

Decimation MUST be read **only in the encoder-feed path**, never the preview
render path. `EglRouter.encodeDecimationFactor` / `ThermalDecimationPolicy.shouldSubmit`
gate the encoder-submit block (FBO blit / fence / `liveEncoders.submit`) and
nothing in the `for (target in snapshot)` preview loop. Violating this decimates
preview — the exact regression this design forbids.

> **Gate status (owner-reserved):** whether to enforce this clause with a 48th
> static gate `checkDecimationEncoderOnly` (assert `encodeDecimationFactor` /
> `shouldSubmit` appears only in the encoder-feed region of `EglRouter.kt`) vs.
> relying on ADR prose + the `ThermalDecimationPolicyTest` unit tests + the Task-2
> code review is a decision the owner reserved for ADR review. Pending that call.

## Scope

DualShot (P+L) binding only. Single-camera and FrontBack paths never set the
factor (default 1, no-op).
