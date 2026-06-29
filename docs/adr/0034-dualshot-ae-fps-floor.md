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
