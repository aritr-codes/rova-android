# ADR-0034: DualShot AE frame-rate floor (capability-gated)

**Status:** Accepted (2026-06-29)

## Context

The fps-cadence diagnosis (PR #154) proved the DualShot ~18–20fps plateau is
compound. Limiter 1 is auto-exposure: the DualShot binding sets no
`CONTROL_AE_TARGET_FPS_RANGE`, so in dim light AE stretches exposure to ~60ms
and sensor cadence collapses to ~16.7fps. A bright-light intervention dropped
exposure to 3.27ms and lifted cadence to 30fps, confirming the cause.

## Decision

The DualShot binding requests a capability-gated AE target fps **floor of
`[24,30]`** (asymmetric — forbids the ~16fps worst case while letting AE relax
to 24fps in dim light for brightness). The range is selected from the device's
`CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES` by the pure `AeFpsRangePolicy`; if no
range with a floor ≥24 is supported, the option is **not** set (the device
default stays). The camera must always open — the apply path is fail-open.

**Tradeoff (accepted):** the 24fps floor caps AE exposure at ~41ms versus the
~60ms unconstrained AE wants in a dim scene, so dim-light DualShot capture will
be measurably darker/noisier than the device-default AE would produce. This is
deliberate — in the worst case we prioritize cadence over brightness (a 24fps
floor over the ~16.7fps collapse). The asymmetric ceiling still lets AE relax to
24fps (not pinned at 30) to claw back light where it can.

**Device fallback (when no `[24,30]` span exists).** Many devices expose only discrete
pins (e.g. `(24,24)`, `(30,30)`) with no true range spanning the band. In that case the
policy prefers the **lowest pinned fps ≥ floor** (`(24,24)` over `(30,30)`) — this still
forbids the ~16fps collapse while giving AE the longest exposure for brightness, honoring
the asymmetric intent. On RZCYA1VBQ2H every back camera lists exactly this set, so the
applied range is `(24,24)`. A device that does expose a real `[24,30]` still gets `[24,30]`.

This addresses Limiter 1 only. The encoder ceiling (Limiter 2, ~22fps output)
is a separate later cycle.

## Clause (enforced by `checkAeFpsRangeCapabilityGated`)

The AE target fps range MUST be capability-gated — chosen from the device's
available ranges via `AeFpsRangePolicy`, never a hard-coded literal. An
unconditional `setCaptureRequestOption(CONTROL_AE_TARGET_FPS_RANGE, Range(x,y))`
is a camera-open footgun on devices that do not list `[x,y]`. The gate forbids a
`Range(` literal on the same line as `CONTROL_AE_TARGET_FPS_RANGE` in
`RovaRecordingService.kt`.

## Scope

DualShot binding only. Single-camera and FrontBack paths are out of scope.
