# DualShot Preview Crop-to-Fill + Recording-Frame Guide — Design

**Date:** 2026-05-22
**Status:** Approved (brainstorm)
**Scope:** P+L / DualShot record mode only — the `EglRouter` preview render path. Single Portrait/Landscape modes (CameraX `PreviewView`) are out of scope.

---

## 1. Problem

In DualShot (P+L) mode the record screen stacks two viewfinder zones — portrait (`weight 352`) over landscape (`weight 225`). Each zone hosts a `TextureView`; `EglRouter` renders the per-side camera frame into it.

The preview render **aspect-fits** (letterboxes) the recorded frame into the zone: `EglRouter.addTarget` computes the preview viewport via `AspectFitMath.computeFitViewport(width, height, contentAspect)`, and `renderFrame` clears the whole surface black before drawing into that restricted viewport. Because the zone aspect ≠ the recording aspect, this leaves black bars:

- **Portrait zone** ≈ 0.9 : 1, recording 9:16 = 0.5625 → wide left/right **pillar bars** (~19 % of zone width each side).
- **Landscape zone** ≈ 1.4 : 1, recording 16:9 = 1.778 → thin top/bottom **letterbox bars** (~10 % of zone height each).

The bars are the same near-black (`#060d18`) as the zone background and the `cam-split-divider`, so they read as one thick dead band — the divider looks far heavier than the mockup's clean 2 dp line.

The HTML mockup (`mockups/new_uiux/01-record-home.html`) shows zero bars — but its viewfinder is a static `<svg>` placeholder stretched to fill 100 %; it does not represent a real 9:16 / 16:9 camera feed.

## 2. Goal

Fill each preview zone edge-to-edge — no bars — while keeping the user aware of what is actually captured. The **recorded video files must be byte-identical** to today; this changes the preview only.

## 3. Chosen behaviour — cover + recording-frame guide

The preview samples a **wider region** of the camera than the recording: it crops the 4:3 source to the **preview zone's aspect** (fills the zone exactly, no bars) and therefore shows context *beyond* the recorded frame. A **recording-frame guide** — a thin outline plus a faint scrim over the non-recorded margin — marks the true capture bounds.

**Geometric guarantee:** both the preview crop (zone aspect) and the recording crop (9:16 / 16:9) are centred sub-rectangles of the same 4:3 source. The recording aspect is always "inside" the zone aspect for both zones, so the recording region is always a centred sub-rectangle of the preview region — the guide rectangle always lands fully inside the zone.

- **Portrait:** zone ≈ 0.9, recording 0.5625 → guide is a centred vertical band, full zone height, ~62 % zone width.
- **Landscape:** zone ≈ 1.4, recording 1.778 → guide is a centred horizontal band, full zone width, ~79 % zone height.

Rejected alternatives:

- **Overscan "cover" viewport, shared crop** — keep one crop, oversize the viewport so content overflows and clips. Fills the zone but shows *less* than the recording (a centre-crop). Rejected: the user wants to see context beyond the recording, not less of it.
- **Per-fragment crop uniform in the GLSL shader** — no matrix change, but invasive to the shader and offers no pure-JVM test seam. Rejected on testability.

## 4. Architecture

The preview and encoder paths share exactly one decision point — the crop matrix built in `EglRouter.addTarget`. This change forks that point:

- `PREVIEW` targets crop the 4:3 source to the **preview zone aspect** (`width / height` already passed to `addTarget`). The viewport becomes the full surface — the crop now matches the surface aspect, so there is nothing to letterbox.
- `ENCODER` targets keep the existing side-fixed 9:16 / 16:9 crop and viewport. **Untouched.**

The recording-frame guide is a Compose overlay in `DualPreviewZone` — no GL involvement.

**Render path:** the work targets the **legacy** path (`AspectFitMath.buildCropMatrix`). The `useFirstPrinciplesRender` V2 path defaults `false` everywhere and is parked dead-code; the spec defines a parallel V2 hook for the eventual migration but ships the legacy path only.

## 5. Components

### 5.1 `AspectFitMath.buildPreviewCropMatrix` — new

```
buildPreviewCropMatrix(
    displayRotation: Int,      // Surface.ROTATION_* 0..3
    sensorOrientation: Int,    // 0 | 90 | 180 | 270
    targetAspectW: Int,        // preview zone width
    targetAspectH: Int,        // preview zone height
    out: FloatArray,           // 16-element column-major mat4
)
```

Parallel to legacy `buildCropMatrix`. Crops the 4:3 source (`SOURCE_ASPECT_W` / `SOURCE_ASPECT_H`) to the arbitrary aspect `targetAspectW / targetAspectH`:

- target **narrower** than source (`A < 4/3`) → `pivot-scale(A / (4/3), 1, 1)` — crop sides.
- target **wider** than source (`A > 4/3`) → `pivot-scale(1, (4/3) / A, 1)` — crop top/bottom.
- target **equal** → identity scale.

Composed through the same `× displayRotationCorrection × sideCorrection` chain as `buildCropMatrix`, with the empirical **+270° per-side correction preserved verbatim** — the preview must rotate identically to the encoder; only the crop scale differs. The sensor-orientation axis-swap (90° / 270° swap the X/Y crop axes) applies exactly as in `buildSideAspectCrop`.

`buildCropMatrix` and `buildSideAspectCrop` are **not modified** — the encoder path is frozen and its ADR-0009 regression tests stay green. If the implementer finds it cleaner to extract a shared private `pivot-scale` helper that both consume, that is acceptable provided every existing `AspectFitMathTest` assertion is unchanged and still passes.

### 5.2 `EglRouter.addTarget` — `PREVIEW` branch

In the `side != null` block, when `kind == TargetKind.PREVIEW`:

- build the crop with `buildPreviewCropMatrix(displayRotation, sensorOrientation, width, height, crop)` instead of `buildCropMatrix`;
- set the viewport to the full surface `intArrayOf(0, 0, width, height)` — no `computeFitViewport` letterbox.

The `kind == TargetKind.ENCODER` path is unchanged. The legacy `side == null` path is unchanged. `RenderTarget` already carries `viewportX/Y/W/H` as mutable fields for size-changed re-registration — no struct change needed.

### 5.3 `RecordingFrameGuide` — new Compose composable

Lives with `DualPreviewZone` (same file or a sibling). Rendered once per `PreviewZone`, **always on in P+L mode** — independent of the decorative "Camera guides" app-setting (it is functional, not decorative: without it the wider preview is misleading).

Takes the `VideoSide` → recording aspect (`9f / 16f` PORTRAIT, `16f / 9f` LANDSCAPE). Draws:

- a thin **1 dp solid white outline** (~0.55 alpha) around the recording sub-rectangle;
- a **faint scrim** (~black 0.22 alpha) over the non-recorded margin — the band between the zone edge and the guide outline — signalling "this part isn't captured".

Layout uses `Modifier.aspectRatio(recordingAspect)` on a centre-aligned `Box` inside the zone-filling `Box`; `aspectRatio` fits the recording rectangle inside the zone with no manual geometry. The scrim is drawn as the inverse region (zone minus the guide rect).

### 5.4 `RecordChromeTokens` — new tokens

Add tokens for the guide outline (colour ~white 0.55, width 1 dp) and the scrim colour (~black 0.22). Mockup-derived where possible; net-new otherwise (the mockup has no guide). Documented in `docs/UI_DESIGN_TOKENS.md`.

## 6. Data flow

`DualPreviewZone` already supplies `side` and the `TextureView` `width / height` through `registerPreviewSurface` → `EglRouter.addTarget`. **No new plumbing** — `addTarget` simply selects `buildPreviewCropMatrix` for `PREVIEW` targets. The guide composable needs only the `side`, already in `PreviewZone` scope.

On a `TextureView` size change the listener re-registers the surface; `addTarget` recomputes the preview crop and viewport from the new dimensions — the guide, being Compose layout, reflows automatically.

## 7. Edge cases & error handling

- **Degenerate `width / height`** (0 or negative during `TextureView` setup) — `buildPreviewCropMatrix` returns identity (mirrors the existing guard in `computeFitViewport` / `buildCropMatrix`); `addTarget` falls back to a full-surface viewport. No crash, no divide-by-zero.
- **Extreme device aspect ratios** — cropping a 4:3 source to any target aspect always yields a valid centred sub-rectangle; there is no unsatisfiable case.
- **Encoder unaffected** — no `ENCODER` code path is touched; recorded files are byte-identical. This is the load-bearing invariant.

## 8. Testing

- **`AspectFitMath.buildPreviewCropMatrix`** — pure-JVM unit tests in `AspectFitMathTest` (the project's only viewfinder-math test seam): target narrower than source, wider than source, equal; all four sensor orientations (0/90/180/270); all four display rotations; degenerate dimensions. Assert exact matrix values, mirroring the existing `buildSideAspectCrop` / `buildCropMatrix` test style.
- **`buildCropMatrix` ADR-0009 regression tests** — must remain green, unmodified (encoder path frozen).
- **`EglRouter` runtime layer and the Compose guide** — no unit layer (project precedent: EGL/GL is integration-tested only; pixel Compose UI is not unit-tested). Verified by on-device smoke on the Samsung SM-A176B.

## 9. Files

| File | Action |
|---|---|
| `app/.../service/dualrecord/internal/AspectFitMath.kt` | Modify — add `buildPreviewCropMatrix` |
| `app/.../service/dualrecord/internal/EglRouter.kt` | Modify — `PREVIEW` branch in `addTarget` |
| `app/src/test/.../AspectFitMathTest.kt` | Modify — tests for `buildPreviewCropMatrix` |
| `app/.../ui/screens/DualPreviewZone.kt` | Modify — add `RecordingFrameGuide` overlay |
| `app/.../ui/theme/RecordChromeTokens.kt` | Modify — guide outline + scrim tokens |
| `docs/UI_DESIGN_TOKENS.md` | Modify — document the new tokens |
| `docs/adr/0010-dualshot-preview-crop-divergence.md` | Create — ADR for the preview-vs-encoder crop divergence |

## 10. Hard invariants

- No `ENCODER` render path, `DualVideoRecorder`, muxer, or recording-pipeline behaviour changes — recorded files byte-identical.
- `buildCropMatrix` / `buildSideAspectCrop` outputs unchanged — ADR-0009 regression tests stay green.
- `RecordScreen` Start-gate, `WarningId` / `WarningPrecedence`, service binding — untouched.
- `DualPreviewZone` `TextureView` / `SurfaceTexture` / `registerPreviewSurface` lifecycle and the `352f` / `225f` zone weights + `cam-split-divider` — untouched (the guide is an additive overlay).
- Single Portrait / Landscape modes — untouched.

## 11. Owner follow-up (not implementer scope)

On-device smoke on the Samsung SM-A176B: in DualShot mode both zones fill edge-to-edge with no black bars; the recording-frame guide outline + margin scrim mark the capture bounds in each zone; a test recording confirms the saved file framing is unchanged from before this change.
