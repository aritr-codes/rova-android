# ADR-0009: DualShot 4:3 source aspect

**Status**: Accepted
**Date**: 2026-05-18
**Supersedes**: nothing (refines the Phase 6.1c forced-16:9 choice).
**Related**: [ADR-0008 — Dual-recording architecture](0008-dual-recording-architecture.md).

## Context

Phase 6.1c (PR #24, squash-merged @ `ba252c3` on 2026-05-18) shipped true per-side rendering for the Portrait+Landscape (P+L) capture mode via a single CameraX session, a GL fan-out in `EglRouter`, and per-target crop matrices in `AspectFitMath`. Three on-device smoke-fix rounds resolved orientation correctness on both sides.

One architectural defect was knowingly parked at merge time: **the PORTRAIT zone is vertically stretched 1.78×.**

Root cause:

- `setupDualCamera()` forces `ResolutionSelector` to `Size(1920, 1080)` (16:9) so CameraX doesn't apply its default ~44% center-crop FOV loss.
- `AspectFitMath.buildSideAspectCrop(PORTRAIT) = pivot-scale(9/16, 1, 1)` samples a centered 9/16-width × full-height region from the 16:9 source = **1080×1080 (1:1 square)**.
- That square is packed into the 1080×1920 (9:16) PORTRAIT encoder = **1.78× vertical stretch**.

The bug is not in the matrix — `pivot-scale(9/16, 1, 1)` does exactly what it claims. The bug is in the **choice of source aspect**: forcing 16:9 capture amputates the vertical FOV before per-side crop math runs, leaving no pixels left to crop into a 9:16 strip.

A 2026-05-18 brainstorm round (owner-driven, informed by a competitive-architecture brief covering iOS DualShot Recorder + the new `com.dual.shot` Android app) surfaced the iOS pattern: **capture at native 4:3, per-side crop on the long edge of each output ratio.** Both sides full-FOV. Neither side stretched. No bars.

## Decision

Switch the dual-camera source aspect from forced 16:9 (`Size(1920, 1080)`) to native 4:3 (largest available landscape 4:3 mode with shortEdge ≥ 1920, with a graceful fallback chain for weak devices). Re-derive `AspectFitMath.buildSideAspectCrop` constants for both sides:

- **PORTRAIT**: `pivot-scale((9/16) / (4/3), 1, 1) = pivot-scale(27/64, 1, 1)` — center-crops a 9:16 strip from a 4:3 source.
- **LANDSCAPE**: `pivot-scale(1, (4/3) / (16/9), 1) = pivot-scale(1, 3/4, 1)` — center-crops top+bottom of a 4:3 source to a 16:9 strip.

A new pure-JVM `DualCameraSizeResolver` owns the fallback chain (largest 4:3 ≥ 1920×1440 → largest 4:3 → hint `Size(1920, 1440)` if no 4:3 modes exist). The Android-facing wrapper queries `CameraManager.getCameraCharacteristics(...).get(SCALER_STREAM_CONFIGURATION_MAP)` directly (bypassing CameraX's `Camera2CameraInfo` to avoid the experimental-opt-in dependency and the bind-then-query chicken-and-egg).

## Consequences

**Wins:**
- PORTRAIT zone fills the 1080×1920 encoder edge-to-edge with no stretch and no bars; vertical FOV is the full source short edge.
- Matches the iOS DualShot Recorder / `com.dual.shot` Android reference architecture — user expectation alignment.
- Localized footprint: ~5–6 files, single source-aspect change point (`setupDualCamera`'s `ResolutionSelector`), matrix constants self-documenting via `AspectFitMath.SOURCE_ASPECT_W` / `_H`.

**Tradeoffs accepted:**
- LANDSCAPE side no longer source-pixel-perfect: source 2560×1920 → LANDSCAPE crop 2560×1440 → 1920×1080 encoder = **1.33× downscale**. Mild and matches the iOS pattern.
- GL pipeline processes ~2.4× more pixels per frame than the 6.1c 1920×1080 baseline (e.g., 2560×1920 = 4.9 Mpx vs. 1920×1080 = 2.1 Mpx). Mid-range device thermal-throttle is a pre-flagged smoke-fix-round-1 contingency — drop `DualCameraSizeResolver.PIXEL_PERFECT_SHORT_EDGE` from 1920 to 1440 if Samsung SM-A176B drops frames.
- `~1%` of devices may expose no 4:3 modes at all. Those fall back to the `Size(1920, 1440)` hint and let CameraX pick its closest — likely a 16:9 mode, which means the PORTRAIT crop math will subtly under-fill or over-crop. Acceptable risk given the device-share; smoke-fix follow-up if it surfaces.

## Alternatives rejected

1. **Letterbox PORTRAIT via `EglRouter` viewport** (option (a) parked in 6.1c). `EglRouter.renderFrame` would call `computeFitViewport(contentAspect = 1f)` for the PORTRAIT side → black bars top+bottom. Smallest scope, but contradicts the iOS-reference framing (no competing app ships PORTRAIT clips with bars; IG/TikTok reject letterboxed uploads).
2. **Redesign `sideAspectCrop(PORTRAIT)` to a rotation** (option (b) parked in 6.1c). The PORTRAIT encoder file would be landscape-content rotated 90°. Fills the encoder, no bars, no scene loss — but content reads sideways unless playback rotates. No competing app does this; awkward UX.
3. **Dual capture sessions** (PORTRAIT gets its own CameraX `VideoCapture` pinned to 4:3 while LANDSCAPE keeps the 16:9 session). Keeps LANDSCAPE pixel-perfect, fixes PORTRAIT. Breaks the 6.1c single-source GL fan-out architecture; doubles encoder/HAL load; may exceed the device's max simultaneous stream count (the brief flags 2–3 streams as a common cap, with mid-range Samsung A17 in the "partial/limited multi-camera" tier).
4. **Debug feature flag toggling 4:3-vs-16:9 source**. The whole change is ~15 LOC + 6 tests; gating it would just add cleanup debt later. If smoke fails, iterate the resolver threshold inside the same PR.
