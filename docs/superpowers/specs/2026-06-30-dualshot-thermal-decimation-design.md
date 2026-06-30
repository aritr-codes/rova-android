# Design — DualShot thermal-adaptive encode decimation

**Date:** 2026-06-30. **Status:** Draft for owner review.
**Background:** `2026-06-30-dualshot-jitter-thermal-findings.md` (DualShot thermal is encoder-bound; jitter has no separate fixable defect).
**Goal (owner):** DualShot sessions survive **meaningfully longer before the thermal autostop** in warm/on-battery conditions — graceful degradation, accepting a quality drop **only while hot**. Must NOT regress the cool-case Quality presets.

## Problem

`SegmentGateThermal` is binary today: at `ThermalStatus.CRITICAL` it terminates the whole session at the next segment boundary (`StopReason.THERMAL`). DualShot's two hardware encoders are the dominant sustained-heat contributor, so the device climbs to `CRITICAL` and hard-stops. There is no intermediate "lighter" step.

## Approach: single-step encoder frame decimation, thermal-gated

When thermal status crosses an **earlier** rung than the autostop (`SEVERE`), throttle the **rate at which frames are submitted to the two encoders** to ~half (encode every 2nd frame). Hold while thermal stays elevated; revert on cooldown via `ThermalHysteresis` (ADR-0019). `CRITICAL` autostop remains the backstop.

Why this lever (vs resolution / new-session / whole-session — see findings §"Lever selection"):
- **Hits the dominant load directly** — ~halving encoded frames ≈ ~halving encoder duty → slower heat rise → longer to `CRITICAL`.
- **Merge-safe** — variable fps concatenates cleanly (PTS wall-clock; dimensions unchanged). No new-session/manifest/recovery surface.
- **Preserves what matters** — decimate at the *encoder-submit* point, NOT the camera: **preview stays full-rate** (smooth) and the **AE fps floor (#155 / ADR-0034) is untouched** (camera still runs ≥24fps for exposure; we simply don't encode every frame). Resolution preserved.
- **Contained blast radius** — a pure policy helper + one volatile-field read in the EGL broadcast. No FBO/fence/threading change; no terminal/merge/recovery gate surface.

Single step (not a graded ladder) per YAGNI: one threshold + hysteresis; `SEVERE` already shows a HUD nudge, `CRITICAL` already hard-stops — this inserts exactly one graceful rung between them.

## Components

### 1. `ThermalDecimationPolicy` (new, `service/`, pure JVM)
```
internal object ThermalDecimationPolicy {
    // factor 1 = encode every frame; 2 = encode every 2nd frame (~half fps)
    fun decimationFactor(engaged: Boolean): Int = if (engaged) 2 else 1
}
```
"engaged" is driven by hysteresis (below). Kept trivial + pure so the single-step rule is explicit and testable; the threshold/hysteresis decision lives in the engage computation.

**Engage/release (hysteresis, ADR-0019 pattern):** engage when status rises to `SEVERE` (`ordinal >= SEVERE`); release when it falls to a lower rung (e.g. `<= MODERATE`) to avoid flapping at the `SEVERE` boundary. Reuse/extend `ThermalHysteresis`; if its current shape doesn't fit a second consumer cleanly, add a small sibling rather than overloading it (decide at plan time after reading `ThermalHysteresis.kt`).

### 2. `EglRouter` — decimation gate in the encoder broadcast
- New `@Volatile var encodeDecimationFactor: Int = 1` (set from the service thread, read on the callback thread — volatile is sufficient; single writer).
- In the broadcast site (where `EncoderFrame` is sent to `liveEncoders`): maintain a frame counter; submit to encoders only when `frameCounter % encodeDecimationFactor == 0`. **Preview rendering is unconditional** (above/outside the gate) — this is the load-bearing invariant.
- Factor `1` = today's behavior exactly (every frame). Default `1` ⇒ zero behavior change when cool / non-DualShot.

### 3. `RovaRecordingService` — wire thermal → factor
- On the thermal-status change it already observes for `SegmentGateThermal` (DualShot path only), compute engaged via hysteresis → `ThermalDecimationPolicy.decimationFactor` → set `eglRouter.encodeDecimationFactor`.
- Reset to `1` on session end / when leaving elevated thermal.

### 4. UI — none
Rides under the existing `SEVERE` thermal HUD banner. No new strings/glyphs (avoids `checkNoHardcodedUiStrings` surface).

## Data flow
`thermalStatusSignal.state` → service observer → hysteresis(engaged) → `ThermalDecimationPolicy.decimationFactor` → `EglRouter.encodeDecimationFactor` (volatile) → broadcast gate (`frameCounter % factor`) → encoders receive ~half-rate stream → lower encoder duty → slower heat rise → longer to `CRITICAL` (unchanged backstop).

## Invariants
1. **Preview stays full-rate** — decimation applies ONLY to the encoder-submit path, never preview. (Regression-prone → candidate gate, below.)
2. **Factor 1 ⇒ byte-identical to today** (cool case, and all non-DualShot).
3. **AE fps floor unaffected** — no `CONTROL_AE_TARGET_FPS_RANGE` / camera change.
4. **Merge-safe** — no dimension change; PTS remains wall-clock monotonic.
5. **Reverts on cooldown** — hysteresis prevents flap; not a one-way latch.

## ADR + gate
- **New ADR-0035** — "thermal-adaptive encode decimation," extending ADR-0016's thermal ladder (`SEVERE` graceful-lighten → `CRITICAL` hard-stop). Owner sign-off required.
- **Candidate gate `checkDecimationEncoderOnly`** (48th): assert the decimation factor is read only in the encoder-broadcast site, not the preview render path — protects invariant #1. Per house rule (ADR → gate → code). Owner to confirm at ADR review whether to add the gate or rely on tests + ADR prose.

## Testing
**Pure JVM (lands in same PR):**
- `ThermalDecimationPolicy`: engaged→2, not-engaged→1.
- Hysteresis engage/release: `NONE/LIGHT/MODERATE`→released(1); rise to `SEVERE/CRITICAL`→engaged(2); fall to `MODERATE`→released; no flap at the boundary.
- Decimation gate logic (`frameCounter % factor`): factor 1 passes all; factor 2 passes every 2nd; preview-unconditional asserted by structuring the gate as a tiny pure predicate if extractable.

**Device verify (RZCYA1VBQ2H, mandatory):** re-run the soak A/B on the bumped build — confirm (a) time-to-`CRITICAL` extends materially when forced hot, (b) recorded fps ~halves while `SEVERE`+ and restores on cooldown, (c) preview stays smooth throughout (`EglRouter.recordRenderPerf` interval flat), (d) merged per-side files play cleanly across a decimation transition.

## Out of scope
- Resolution / topology changes (rejected levers — findings §"Lever selection").
- The ~1.4s segment-boundary seam (separately-scoped).
- Jitter (no fixable defect; inherent fps).

## Risks
- **EGL broadcast edit** is in the fragile stack — but it's a counter+modulo in the submit path, not a FBO/fence/threading change; factor 1 is identity. Device-verify gates it.
- **Hysteresis tuning** (engage/release rungs) — validate on-device that it doesn't flap and that ~half-rate buys real headroom; tune rungs if the relief is insufficient (could widen to a 2-step ladder later, explicitly deferred).
