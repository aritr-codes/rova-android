# ADR-0012 — Gradient-scrim dock + pill settings card + Mode tap-cycle chip

**Status:** Accepted

**Date:** 2026-05-23

## Context

Slice A (ADR-0011, merged 2026-05-23 @ `aa337d2`) made the record-screen preview edge-to-edge: system bars transparent, NavHost stops consuming insets, DualPreviewZone background dropped. Three chrome elements still read as a separate dock zone bolted onto the bottom edge:

1. `RecordBottomNav` paints `RecordChromeTokens.bottomNavFill = #0c1218` opaque + 1 dp `bottomNavTopStroke` at `rgba(255,255,255,0.04)`. The dock reads as a hard rectangle against the now-edge-to-edge preview.
2. `RecordSettingsCard` uses a tight 14 dp corner radius and a uniform "tap = open sheet" gesture model. Mode swap requires sheet → row → tap → dismiss (4 interactions) for a frequently-used operation.
3. `RecordChromeMetrics.bottomNavClearance = 90.dp` puts the settings card lower edge inside the dock's top 18 dp zone. With the dock as a flat opaque fill this is invisible; with any gradient it produces a visible alpha-curve seam.

The brainstorm mockup (`mockups/new_uiux/01d-record-home-slice-b.html`) evaluated four candidates: **A** (Floating Glass — flat translucent dock), **B** (Native-Camera — chrome dissolved to floating chips, settings card removed from idle), **C** (Gradient Scrim — vertical-gradient dock, pill card, Mode tap-cycle chip), **D** (Ambient Liquid Glass — Liquid Glass refractive material + Material 3 Expressive 72 dp FAB + ambient summary chip + Hub pop-out — 2027-28 forward look).

## Decision

**Ship Variant C — Gradient Scrim — as Slice B.** Five concrete changes:

- **`RecordChromeTokens` dock fill** flips from `bottomNavFill: Color` (deleted) to `bottomNavBrush: Brush.verticalGradient` with stops `0% Transparent → 35% Transparent → 55% Black 0.20 → 80% Black 0.45 → 100% Black 0.55`. The top 35 % is fully transparent — preview reads continuously through. The 1 dp `bottomNavTopStroke` is deleted; a gradient has no top, a stroke would re-introduce the band edge we're killing.

- **`RecordBottomNav` refactor** wraps the inner Row in an outer Box. The Box paints `bottomNavBrush` across its full intrinsic height — INCLUDING the navigation-bar inset zone that the inner Row consumes via `windowInsetsPadding(navigationBars)`. This is the load-bearing pattern: painting the brush on the Row directly would bound it to the inset-padded layout, breaking the seamless blend into Slice A's OS-transparent gesture-nav region.

- **`RecordSettingsCard` corner radius** flips from `settingsCardRadius = 14.dp` (deleted) to `settingsCardRadiusPill = 22.dp`. Stroke and fill alpha unchanged.

- **`RecordSettingsCard` Mode cell** changes from a read-only `SettingsCell` to a new `ModeCycleChip` composable using `combinedClickable`: **tap** advances Portrait → Landscape → P+L → Portrait via `RecordViewModel.cycleMode()` (which delegates to the pure helper `cycleModeNext` for the cycle order); **long-press** opens the settings sheet (gesture redundancy + discoverability fallback). Disabled while the card is `dimmed = true` (active session — existing behaviour). Visual: outlined chip + faint `↻` glyph top-right.

- **`RecordChromeMetrics.settingsCardLift = 30.dp`** is added; `RecordScreen.kt` uses `bottomNavClearance + settingsCardLift = 120 dp` as the settings-card bottom padding. This clears the gradient's transparent zone with an 8 dp buffer — no more alpha-curve seam.

## Consequences

**Accepted:**
- The dock fill is no longer a flat Color token — `bottomNavBrush` is a `Brush`. Any future caller wanting a flat dock has to introduce a new token (or use a different surface model).
- `RecordBottomNav` now has a 2-element widget tree (outer Box + inner Row). Callers that mounted custom content over the old single-Row tree would need adjusting — there are no such callers today.
- The settings card's "tap anywhere = open sheet" uniformity has one exception (the Mode chip absorbs tap events within its bounds). Discoverability hit is mitigated by the chip's outlined chip + `↻` glyph treatment, which visually flags the affordance, plus the long-press fallback.
- `cycleModeNext` is a top-level `internal fun` in `RecordModeCycle.kt`. The function is pure and test-covered (project precedent: `loopPillContent`, `hudStatusPillContent`, `recordFabState`). `RecordViewModel.cycleMode()` reads `_serviceState.value` directly (rather than via a new derived StateFlow) for the lock check — no new state on the VM.

**Rejected:**
- **Variant A — Floating Glass** (`rgba(0,0,0,0.50)` + CSS `backdrop-filter: blur(36px)`). Compose has no backdrop-blur primitive before API 31 (`RenderEffect.createBlurEffect`). The project's minSdk is 24; A's "glass" promise would render as flat alpha for the ~30 % of installs below API 31, defeating the visual. Rejected on rendering fidelity + API gate.
- **Variant B — Native-Camera** (chrome dissolved to floating chips, settings card removed from idle, always-on mode strip above FAB). Removing the settings card from idle is a quality-of-life regression — users glance Clip/Repeats/Wait/Quality at idle today. Rejected on UX cost. The always-on mode strip is also a second ModeTabsPicker surface, doubling the display paths to sync with the sheet.
- **Variant D — Ambient Liquid Glass** (refractive chrome material, M3 Expressive 72 dp FAB with shape-morphing, ambient summary chip, Hub pop-out for less-frequent toggles, top-anchored persistent mode strip, scene-tint adaptive chrome). Three blockers prevent Slice B from carrying this: (1) `RenderEffect.createBlurEffect` API 31+ gate (same as A); (2) Material 3 Expressive Compose APIs (shape-morphing, motion physics) are at `alpha → beta` through 2026, production-ready 2027; (3) ambient scene-tint requires sampling preview frame color, touching the hard-invariant frozen `EglRouter` output path. Parked as Slice C — 2027 target when minSdk floor naturally rises to 28-29 and Compose ships stable spring physics. See "Future work".

**Hard invariants (preserved by this slice):**
- No `service/dualrecord/**`, `EglRouter`, `AspectFitMath`, `DualVideoRecorder`, muxer, or recording-pipeline behaviour changes. Recorded files byte-identical.
- ADR-0009 + ADR-0010 + ADR-0011 outputs unchanged. `buildCropMatrix` / `buildSideAspectCrop` / `buildPreviewCropMatrix` and all `AspectFitMathTest` assertions untouched.
- `WarningId` / `WarningPrecedence` / `WarningCenterViewModel` / Start-gate / recovery flow untouched.
- `DualPreviewZone` `TextureView` / `SurfaceTexture` / `registerPreviewSurface` lifecycle untouched. P+L `352f` / `225f` zone weights + `cam-split-divider` untouched.
- `MainActivity.kt` `enableEdgeToEdge()` + `values-v29/themes.xml` + `Theme.kt` transparent-bar writes (Slice A) untouched.
- `RecordTopOverlay` / `RecordCameraControls` / `RecordRecoveryChip` / `RecordActiveHud` (R2) untouched.

## Future work

**Slice C — "Ambient Liquid Glass"** (Variant D from the brainstorm mockup). Three pieces:

1. **Liquid Glass material** approximated via Compose `RenderEffect.createBlurEffect` (API 31+) on chrome surfaces with inset-highlight + cool tint + depth shadow — matches iOS 26 Liquid Glass aesthetic (Apple, June 2025) and Android 17's Material 3 Expressive translucent surfaces (Google, 2026).
2. **Material 3 Expressive shape-morphing** on the FAB (Start ↔ Stop via spring physics) and chip backgrounds. Requires Jetpack Compose Material 3 Expressive stable APIs (currently alpha-beta; production-stable target 2027).
3. **Ambient adaptive chrome** — scene-tint sampling (preview frame dominant hue → chrome border colour) + scene-brightness-driven chrome alpha. Requires a new producer wired into the chrome layer (sampling, NOT touching the frozen `EglRouter` output).

Ship sequence: ride out the API 31+ floor + Compose Material 3 Expressive maturation through 2026-27, then revisit when minSdk can naturally rise to 28-29 (or 31 if the install base allows).

**Mockup file:** `mockups/new_uiux/01d-record-home-slice-b.html` (local — `mockups/` is gitignored). Contains all four variants (A · B · C · D) side-by-side with the full trade-off table.
