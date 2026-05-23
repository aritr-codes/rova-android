# ADR-0010 — DualShot preview crop diverges from encoder crop

**Status:** Accepted

**Date:** 2026-05-22

## Context

After ADR-0009 (4:3-source-aspect, merged 2026-05-20) the DualShot recording pipeline crops the 4:3 sensor source to fixed per-side aspects — 9:16 for PORTRAIT, 16:9 for LANDSCAPE — and `EglRouter` renders the same per-side crop into both the encoder surface and the preview `TextureView`. Because the preview zone aspects (≈ 0.9 portrait, ≈ 1.4 landscape) do not match the recording aspects, the existing `AspectFitMath.computeFitViewport` letterboxes the preview — wide pillar bars in the portrait zone, thinner top/bottom bars in the landscape zone. The bars are the same near-black as the `cam-split-divider` and the zone background, so they read as one thick dead band; on the Samsung SM-A176B reference device this is the dominant visual complaint about the DualShot record screen.

The HTML mockup (`mockups/new_uiux/01-record-home.html`) shows zero bars — but its viewfinder is a static `<svg>` placeholder stretched to fill 100 %; it does not represent the live 9:16 / 16:9 camera feed.

## Decision

**Preview targets crop the 4:3 source to the preview zone's aspect (fills the surface, no letterbox). Encoder targets keep the existing side-fixed crop — recorded files byte-identical.**

A new `AspectFitMath.buildPreviewCropMatrix` parallels legacy `buildCropMatrix`. It shares the same `× displayRotationCorrection × sideCorrection` chain — including the empirical +270° per-side fudge from the Phase 6.1c smoke-fix series — and substitutes a generalized `buildAspectCrop(targetAspectW, targetAspectH, sensorOrientation)` for the side-fixed `buildSideAspectCrop`. `buildSideAspectCrop` and `buildCropMatrix` are not modified — the encoder path is frozen and its ADR-0009 regression tests stay green.

In `EglRouter.addTarget` the `kind == TargetKind.PREVIEW && side != null` branch calls `buildPreviewCropMatrix(displayRotation, sensorOrientation, side, width, height, crop)` and assigns a full-surface viewport (`[0, 0, width, height]`) — the crop now matches the surface aspect, so there is nothing to letterbox. The `ENCODER` branch is unchanged.

A Compose `RecordingFrameGuide` overlay in `DualPreviewZone` draws the recorded sub-rectangle as a faint 1 dp gray outline plus a low-alpha black scrim over the non-recorded margin, unconditionally in P+L mode (the guide is functional — without it the wider preview is misleading).

## Consequences

**Accepted:**
- The preview UV transform and the encoder UV transform are no longer identical — debugging tools that assume they match must look at the appropriate per-target matrix. The `RenderTarget.uvTransform` field already varies per target; this only widens the variation.
- A small additive surface area in `AspectFitMath` (≈ 80 lines) and a new Compose overlay (≈ 40 lines) — both pure-JVM-testable on the math side and trivial layout on the UI side.
- `RecordingFrameGuide` is always on in P+L mode, independent of the existing decorative "Camera guides" app-setting. It is not theming — it is a capture-bounds indicator.

**Rejected:**
- *Overscan "cover" viewport, shared crop* — keep one crop, oversize the viewport so content overflows and clips. Fills the zone but shows *less* than the recording (a centre-crop) — contradicts the goal of showing context beyond the recording.
- *Per-fragment crop uniform in the GLSL shader* — no matrix change, but invasive to the shader and offers no pure-JVM test seam.
- *Reshape zone proportions to match recording aspect* — would break the `352:225` mockup ratio and push letterbox bars to the outer record-screen layout.

**Hard invariants:**
- No `ENCODER` render path, `DualVideoRecorder`, muxer, or recording-pipeline behaviour changes — recorded files byte-identical.
- `buildCropMatrix` / `buildSideAspectCrop` outputs unchanged — ADR-0009 regression tests stay green.
- `RecordScreen` Start-gate, `WarningId` / `WarningPrecedence`, service binding — untouched.

## Future work

The V2 first-principles path (`useFirstPrinciplesRender = true`) is parallel dead-code at runtime. When that migration completes, a `buildPreviewUvTransformV2` companion will mirror this decision; until then preview always uses the legacy path even if V2 is flipped on for encoders.
