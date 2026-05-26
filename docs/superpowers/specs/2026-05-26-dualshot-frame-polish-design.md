# DualShot Frame Polish — Milestone 1 Design

**Date:** 2026-05-26
**Status:** Draft — pending owner review
**Master tip baseline:** `7f64650` (post-PR #49 Phase 4.2)
**Owner approvals on file:** Milestone order 1→2→3→4→5 approved; DualShot blur "approach 2" approved; Variant D piece-1 spike deferred.

## 1. Context

Phase 6.1c shipped `DualPreviewZone` for the P+L recording mode in PR #24 (`ba252c3`). The composable renders two stacked `TextureView` surfaces (portrait 9:16 on top, landscape 16:9 below) with a `RecordingFrameGuide` overlay that marks each side's recorded sub-rectangle.

The current `RecordingFrameGuide` ([DualPreviewZone.kt:154-201](../../app/src/main/java/com/aritr/rova/ui/screens/DualPreviewZone.kt#L154-L201)) draws two visual elements over the non-recorded camera margins:

1. A 1.dp gray outline (`Color(0xFFB0B4BC)` @ 38% alpha, `recordingFrameStrokeWidth = 1.dp`) around the recorded rectangle.
2. A solid black scrim (`Color.Black` @ 22% alpha, `recordingFrameScrim`) over the non-recorded margin regions.

Owner feedback (2026-05-26): the 1.dp outline reads as "visually amateurish." The dark scrim is acceptable but the combination feels unrefined. Preferred direction: replace hard outline with a subtle blurred-glass treatment that preserves the capture-bounds cue without the hard-line visual.

## 2. Goal

Replace the 1.dp outline and solid dark scrim with an API-gated frosted-glass treatment that:

- Drops the hard outline entirely.
- Layers a Gaussian blur over the non-recorded margins on API 31+ (where `RenderEffect.createBlurEffect` is available).
- Halves the dark-scrim alpha (`0.22f → 0.11f`) for a softer baseline that reads consistently across API levels.
- Softens the `cam-split-divider` between portrait and landscape zones (`0.14f → 0.06f`).
- Preserves the always-on functional-indicator contract (the recording-zone boundary remains discoverable without any user toggle).

## 3. Out of scope

- Mode picker (Drill / Vlog / Custom) preset seed source — Milestone 5+.
- `CameraGuides` decorative grid + focus brackets — unchanged.
- `cam-zone-tag` micro-text — unchanged.
- Multi-device blur performance measurement — single-device empirical smoke on Samsung SM-A176B only.
- Variant D Liquid Glass spike — deferred per owner decision.
- Snooze / dismissal of the frame indicator — always-on by design.
- Single-mode (Portrait-only / Landscape-only) recording surfaces — those do not host `DualPreviewZone` and are not affected.

## 4. Hard invariants preserved (verbatim per ADR-0012 §Hard invariants)

- No `service/dualrecord/**`, `EglRouter`, `AspectFitMath`, `DualVideoRecorder`, muxer, or recording-pipeline behaviour changes. Recorded files byte-identical.
- ADR-0009 + ADR-0010 + ADR-0011 outputs unchanged. `buildCropMatrix` / `buildSideAspectCrop` / `buildPreviewCropMatrix` and all `AspectFitMathTest` assertions untouched.
- `WarningId` / `WarningPrecedence` / `WarningCenterViewModel` / Start-gate / recovery flow untouched.
- `DualPreviewZone` `TextureView` / `SurfaceTexture` / `registerPreviewSurface` lifecycle untouched. P+L `352f` / `225f` zone weights untouched.
- `MainActivity.kt` `enableEdgeToEdge()` + `values-v29/themes.xml` + `Theme.kt` transparent-bar writes (Slice A) untouched.
- `RecordTopOverlay` / `RecordCameraControls` / `RecordRecoveryChip` / `RecordActiveHud` untouched.

## 5. Design decisions (owner-approved during brainstorm)

| # | Decision | Value |
|---|---|---|
| 1 | Blur intensity on API 31+ | Subtle (~12.dp radius) |
| 2 | Composition stack | Blur + 50% of current scrim alpha (`0.22f → 0.11f`) |
| 3 | Fallback on API <31 | Same 50% scrim alpha universally; blur layered only on API 31+ |
| 4 | `cam-split-divider` between zones | Reduced alpha (`0.14f → 0.06f`) |
| 5 | `guidesEnabled` toggle interaction | Always-on (current behaviour preserved) |

## 6. Architecture

### 6.1 Component decomposition

The current `RecordingFrameGuide` uses a single `Canvas` to draw scrim rectangles plus the outline. To apply `RenderEffect.createBlurEffect` cleanly, the scrim regions must live in a `graphicsLayer` that wraps the camera content beneath them. This requires switching from a Canvas-only model to a Box-composition model.

**New component tree (inside `PreviewZone`):**

```
PreviewZone (Box)
 ├── AndroidView { TextureView } (camera feed)
 ├── CameraGuides (existing, unchanged)
 ├── RecordingFrameGuide (REFACTORED)
 │    ├── (computed) RecordingFrameLayout via pure helper
 │    ├── Box(margin region 1) { Modifier.graphicsLayer { renderEffect = ... } } + Background(scrim)
 │    ├── Box(margin region 2) { ... } + Background(scrim)
 │    └── (no outline — deleted)
 └── Text (zone-tag, existing)
```

### 6.2 Pure-helper extraction

Following project precedent (`AspectFitMath`, `loopPillContent`, `cycleModeNext`, `effectiveIdleTopBannerId`, `recordFabState`), the layout math is extracted into a pure JVM-testable function.

```kotlin
// app/src/main/java/com/aritr/rova/ui/screens/RecordingFrameLayout.kt
internal data class RecordingFrameLayout(
    val recordingRect: RectF,
    val scrimRegions: List<RectF>,
)

internal fun recordingFrameLayout(
    zoneWidth: Float,
    zoneHeight: Float,
    recordingAspect: Float,
): RecordingFrameLayout
```

The Compose-side `RecordingFrameGuide` becomes a thin renderer over the data class. All branching logic (portrait vs landscape, zero-size guard, scrim region computation) lives in the helper and is unit-tested without Compose.

### 6.3 API gate strategy

`RenderEffect.createBlurEffect` is API 31+ (`Build.VERSION_CODES.S`). Project `minSdk = 24`.

```kotlin
@Composable
private fun ScrimRegion(rect: RectF, modifier: Modifier = Modifier) {
    val blurModifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Modifier.graphicsLayer {
            renderEffect = RenderEffect
                .createBlurEffect(
                    RecordChromeTokens.recordingFrameBlurRadius.toPx(),
                    RecordChromeTokens.recordingFrameBlurRadius.toPx(),
                    Shader.TileMode.CLAMP,
                )
                .asComposeRenderEffect()
        }
    } else {
        Modifier
    }
    Box(
        modifier = modifier
            .then(blurModifier)
            .background(RecordChromeTokens.recordingFrameScrim),
    )
}
```

`Shader.TileMode.CLAMP` prevents edge-darkening artifacts at zone boundaries. The blur input is live camera frames from the underlying `TextureView` — the `graphicsLayer` blurs whatever pixels are beneath the Box at composition time.

### 6.4 Files touched

| File | Action | Notes |
|---|---|---|
| `app/src/main/java/com/aritr/rova/ui/theme/RecordChromeTokens.kt` | Modify | Update `recordingFrameScrim`, add `recordingFrameBlurRadius`, add `camSplitDividerAlpha`, delete `recordingFrameStrokeWidth` + `recordingFrameOutline` |
| `app/src/main/java/com/aritr/rova/ui/screens/DualPreviewZone.kt` | Modify | Refactor `RecordingFrameGuide` to Box-composition; reference `camSplitDividerAlpha` token in divider Box |
| `app/src/main/java/com/aritr/rova/ui/screens/RecordingFrameLayout.kt` | Create | Pure layout helper |
| `app/src/test/java/com/aritr/rova/ui/screens/RecordingFrameLayoutTest.kt` | Create | JVM unit tests for the helper |

## 7. Visual tokens (concrete values)

```kotlin
// RecordChromeTokens.kt — final state

// REMOVED:
//   val recordingFrameOutline = Color(0xFFB0B4BC).copy(alpha = 0.38f)
//   val recordingFrameStrokeWidth = 1.dp

// MODIFIED:
val recordingFrameScrim = Color.Black.copy(alpha = 0.11f)   // was 0.22f

// ADDED:
val recordingFrameBlurRadius: Dp = 12.dp                    // API 31+ only
val camSplitDividerAlpha: Float = 0.06f                     // was literal 0.14f at DualPreviewZone.kt:66
```

**Rationale:**

- **0.11f scrim** — halves perceived darkness while keeping the recorded zone visibly "brighter" than the margins. Reliable contrast cue across lighting conditions, with the blur on top adding depth on API 31+.
- **0.06f divider** — barely visible but still structurally present. Catches ~5-10% contrast against any preview content. Honours the "softer chrome" direction without removing the structural cue.
- **12.dp blur radius** — subtle frosted-glass effect that abstracts camera content into soft color but keeps general scene structure readable. Matches the "subtle, not strong" decision from the brainstorm.

## 8. Testing strategy

### 8.1 Pure-helper unit tests (JVM)

File: `app/src/test/java/com/aritr/rova/ui/screens/RecordingFrameLayoutTest.kt`

Test cases:

| # | Name | Input | Assertion |
|---|---|---|---|
| 1 | `portrait zone produces side scrims` | zone 352×625, recordingAspect 9/16 | scrimRegions.size == 2, recordingRect height == zoneHeight |
| 2 | `landscape zone produces top-bottom scrims` | zone 352×225, recordingAspect 16/9 | scrimRegions.size == 2, recordingRect width == zoneWidth |
| 3 | `zone matching recording aspect produces no scrims` | zone 16×9, recordingAspect 16/9 | scrimRegions.isEmpty(), recordingRect == zoneRect |
| 4 | `zero-size zone produces empty layout` | zone 0×0, recordingAspect 9/16 | scrimRegions.isEmpty(), recordingRect.isEmpty() |
| 5 | `negative-size zone produces empty layout` | zone -10×100, recordingAspect 9/16 | scrimRegions.isEmpty(), recordingRect.isEmpty() (defensive) |
| 6 | `portrait scrim regions sum to non-recorded area` | zone 352×625, recordingAspect 9/16 | sum of scrim region areas equals (zoneArea - recordingRect.area) |
| 7 | `recording rect is centred horizontally for portrait` | zone 352×625, recordingAspect 9/16 | abs(recordingRect.centerX - zoneWidth/2) < 0.5f |
| 8 | `recording rect is centred vertically for landscape` | zone 352×225, recordingAspect 16/9 | abs(recordingRect.centerY - zoneHeight/2) < 0.5f |

### 8.2 No Compose render tests

Skipped per project precedent. `AspectFitMath` has 16 JVM tests with no Compose render coverage; `RecordChrome` chip composables follow the same pattern. Robolectric setup cost exceeds value for this scope.

### 8.3 Manual smoke (hardware)

Device: Samsung SM-A176B (per `project_dualshot_stability_stack` memory — primary QA device).

Steps:
1. Launch app, switch to P+L mode.
2. Verify no 1.dp outline visible around either zone's recording rectangle.
3. Verify scrim margins are softer than current master.
4. Verify divider between portrait and landscape zones is barely visible.
5. Verify (API 31+ device) that camera content in scrim regions appears blurred.
6. Toggle "Camera guides" app-setting — verify decorative grid + focus brackets respond, recording-frame scrim does NOT respond (always-on contract).
7. Frame-rate check: enable developer-options "Profile GPU rendering" or equivalent overlay. Confirm preview rendering remains ≥28fps in P+L mode during ~30 seconds of normal preview.

Photo-capture results for owner sign-off.

### 8.4 Regression suite

Must remain green and byte-identical:

- `gradlew test` — full suite. Hard invariants: `AspectFitMathTest`, `WarningPrecedenceTest`, `WarningCenterAggregateTest`, `WarningIdOrderTest`, `RecoveryScannerTest`.
- `gradlew lint` — zero-delta vs `7f64650` baseline (51 W + 0 H + 0 E).
- `gradlew assembleDebug` — succeeds.

## 9. Performance plan

### 9.1 Risk

`RenderEffect.createBlurEffect` over a `graphicsLayer` containing live camera content runs the blur per preview frame at 30fps. On lower-end devices this could drop preview frame rate below the 30fps target.

### 9.2 Mitigation

1. Blur layer wraps **scrim regions only** (not the recording rectangle), bounding the per-frame blur cost to the margin area (smaller than the full zone).
2. `RenderEffect.createBlurEffect` is GPU-accelerated. API 31+ devices (Android 12 minimum) have hardware blur acceleration.
3. Target device (Samsung SM-A176B, Snapdragon-tier 2022) is expected to handle 12.dp blur at 30fps without frame drops.
4. Manual smoke includes frame-rate verification (step 7 in §8.3).

### 9.3 Fallback levers if perf is unacceptable

In priority order:

1. **Reduce blur radius** to 8.dp in `RecordChromeTokens.recordingFrameBlurRadius`. Single-line change.
2. **Downsample blur source** via `Modifier.graphicsLayer { scaleX = 0.5f; scaleY = 0.5f; ... }` on the blur layer, then upscale on render. ~3-line change.
3. **Disable blur entirely** — remove the API-gate conditional, fall back to flat 0.11f scrim universally. Acceptable because the blur is purely cosmetic; the scrim alone provides the functional cue. ~5-line revert.

### 9.4 Rollback plan

If post-ship field reports show frame drops on common devices:

```kotlin
// RecordChromeTokens.kt — full rollback
val recordingFrameOutline = Color(0xFFB0B4BC).copy(alpha = 0.38f)  // restore
val recordingFrameStrokeWidth = 1.dp                                // restore
val recordingFrameScrim = Color.Black.copy(alpha = 0.22f)           // restore
val camSplitDividerAlpha: Float = 0.14f                             // restore (or delete + literal)
// delete recordingFrameBlurRadius
```

Plus revert `RecordingFrameGuide` to Canvas-based outline + scrim. Single commit.

## 10. Risks summary

| # | Risk | Likelihood | Mitigation |
|---|---|---|---|
| 1 | Blur drops preview frame rate on low-end devices | Medium | §9.3 three-lever fallback; manual smoke validates target device |
| 2 | 0.11f scrim too light → boundary unclear in bright outdoor scenes | Low | Tunable in tokens; manual smoke validates |
| 3 | `RenderEffect` API misuse (e.g. wrong TileMode → edge artifacts) | Low | `Shader.TileMode.CLAMP` is the standard pick; documented in §6.3 |
| 4 | Pure-helper extraction breaks ADR-0010 (preview-crop-divergence) math | None | Helper only computes scrim layout; does not touch `buildPreviewCropMatrix` or any AspectFitMath function |
| 5 | Regression in `WarningCenterAggregateTest` / `AspectFitMathTest` / other invariants | None | Files untouched; plan gates on byte-identical results |
| 6 | `graphicsLayer` blur over TextureView underlay causes compositing artifact | Low-Medium | Project precedent: edge-to-edge slice (PR #41/#42) and ADR-0011 successfully composite Compose overlays over TextureView. Same model. |

## 11. Acceptance criteria

- 1.dp outline removed from `RecordingFrameGuide` in P+L mode.
- Scrim alpha halved from `0.22f → 0.11f`.
- Divider alpha reduced from `0.14f → 0.06f`.
- API 31+ devices show blur over scrim regions; <31 devices show flat 0.11f scrim.
- Pure-helper `recordingFrameLayout(...)` has 8 unit tests passing.
- Full test suite (1192 tests minimum, per `7f64650` baseline) passes green.
- Lint zero-delta vs baseline.
- `assembleDebug` succeeds.
- Manual smoke on Samsung SM-A176B confirms all visual + frame-rate criteria.
- ADR-0009, ADR-0010, ADR-0011, ADR-0012 hard invariants verified untouched (recorded MP4 byte-identical to pre-change baseline on a 5-second test recording).

## 12. ADR implication

This change does not warrant a new ADR. It is a refinement of an existing surface (ADR-0010 + ADR-0011 + ADR-0012 §Hard invariants) within already-documented architectural decisions. Token-level deltas + pure-helper extraction are routine refactor patterns established in prior slices.

If the manual smoke step reveals an unforeseen issue requiring an architectural change (e.g. moving blur into the GL pipeline), a follow-up ADR would be needed. Default assumption: no ADR.

## 13. Next step

After owner approval of this spec:

1. Invoke `superpowers:writing-plans` skill to produce an implementation plan at `docs/superpowers/plans/2026-05-26-dualshot-frame-polish.md`.
2. Plan execution gated on `/karpathy-guidelines` workflow (owner directive for Milestone 1 dev phase).
3. Execution via `superpowers:subagent-driven-development` (preferred mode per standing constraint #10).

## Appendix A — Mockup reference

No mockup file exists for this specific refinement. The change is derived from owner verbal feedback (2026-05-26 chat). The visual contract is described in §6.1 (component decomposition) and §7 (token values).

If a mockup is desired before plan-writing, owner can request — but the scope is small enough that the spec values + manual smoke step likely suffice.
