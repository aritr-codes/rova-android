# Edge-to-Edge Record-Home Slice B — Gradient Scrim Dock + Pill Settings + Mode Tap-Cycle — Design

**Date:** 2026-05-23
**Status:** Approved (brainstorm)
**Scope:** Record screen idle/active chrome — `RecordBottomNav` fill, `RecordSettingsCard` shape, Mode cell interaction. UI-only re-skin.
**Predecessor:** Slice A merged 2026-05-23 @ `aa337d2` (PR #41). System bars transparent, double-pad removed, preview fills edge-to-edge.
**Successor:** Slice C — "Ambient Liquid Glass" (Variant D in the brainstorm mockup `mockups/new_uiux/01d-record-home-slice-b.html`). Parked for 2027 when RenderEffect API 31+ floor + stable Compose Material 3 Expressive APIs land.

---

## 1. Problem

Slice A removed the opaque status-bar band, the navigation-bar auto-scrim, and the `camZoneBackground` letterbox. Three chrome elements still read as a discrete dock zone separated from the camera surface:

1. **`RecordBottomNav`** paints `RecordChromeTokens.bottomNavFill = #0c1218` opaque + 1 px `bottomNavTopStroke` at `rgba(255,255,255,0.04)`. The dock reads as a hard rectangle bolted to the bottom edge — out of place against the now-edge-to-edge camera.
2. **`RecordSettingsCard`** uses a tight 14 dp corner radius and a uniform "tap = open sheet" gesture. Mode swap requires opening the sheet, scrolling to the Mode row, tapping, and dismissing — four interactions for a frequent operation.
3. **Settings-wrap → dock overlap.** `RecordChromeMetrics.bottomNavClearance = 90.dp` puts the settings card's lower edge inside the dock's 0–18 dp top zone. Today this is invisible because the dock fill is uniform opaque; with any gradient fill it becomes a visible alpha-curve clash.

Native Samsung / iOS 26 Camera apps both ship gradient-scrim or floating-chrome bottom regions — the dock is suggested, not bolted. That is the target.

## 2. Goal

Dock visually dissolves into the camera through a vertical gradient. Settings card rounds to a pill. Mode swap becomes a one-tap inline cycle. **Recorded video files must be byte-identical** to today; this is chrome only.

## 3. Chosen behaviour

### 3.1 Dock = vertical gradient brush

`RecordBottomNav` paints `Brush.verticalGradient` with 5 stops instead of the flat `bottomNavFill`:

```
0.00f to Color.Transparent
0.35f to Color.Transparent
0.55f to Color.Black.copy(alpha = 0.20f)
0.80f to Color.Black.copy(alpha = 0.45f)
1.00f to Color.Black.copy(alpha = 0.55f)
```

The top 35 % of the dock zone is fully transparent — the preview reads continuously through. The gradient ramps to 0.55 black at the bottom, providing readable contrast behind Library/FAB/Settings glyphs without forming a hard band edge.

The 1 px `bottomNavTopStroke` is **deleted** — the gradient has no top, so a stroke would re-introduce the band edge we're killing.

**Gesture-nav continuity (critical):** the gradient brush must paint through the `windowInsetsPadding(navigationBars)` zone so that the dock blends seamlessly into the OS-transparent gesture-nav region (Slice A turned that transparent). Current modifier chain paints `.background` before `.windowInsetsPadding`, which bounds the background to the *padded* layout — gradient stops at the inset boundary. Slice B refactors `RecordBottomNav` so the gradient is on an outer `Box` that wraps the inset-padded inner Row:

```
Box(modifier = Modifier
    .fillMaxWidth()
    .background(RecordChromeTokens.bottomNavBrush)   // paints full extent incl. behind gesture nav
) {
    Row(modifier = Modifier
        .windowInsetsPadding(WindowInsets.navigationBars)
        .padding(...)
        ...
    ) { /* nav items */ }
}
```

The outer Box's height = inner Row's natural height + the navigation-bar inset; Compose's intrinsic measurement handles this with no fixed-height token. The 90 dp `bottomNavClearance` in `RecordChromeMetrics` continues to clear above the dock for callers that mount content over it (settings card, R2 active HUD); this clearance is independent of the gesture-nav inset, which is handled by `windowInsetsPadding` at the call site.

### 3.2 Settings card = pill

`RecordChromeTokens.settingsCardRadius` value of 13 dp stays as the *idle* shape token. A new `settingsCardRadiusPill = 22.dp` replaces it as the active radius in `RecordSettingsCard`. The card's 1 dp border stroke uses a slightly lighter `settingsCardStroke` (existing token unchanged; reuses 0.075 alpha which already reads correctly with pill curvature).

### 3.3 Mode cell = tap-cycle chip

The Mode `SettingsCell` is replaced by a `ModeCycleChip` composable inside the same Row. Visual treatment:

- Outlined chip background — `RecordChromeTokens.modeChipFill = Color.White.copy(alpha = 0.07f)`
- Border — `RecordChromeTokens.modeChipStroke = Color.White.copy(alpha = 0.10f)`
- Corner radius — 11 dp (matches `cellSep` height for visual anchoring)
- Top-right glyph — `↻` at `Color.White.copy(alpha = 0.35f)`, 7 sp text (`RecordChromeTokens.modeChipGlyphAlpha`, `modeChipGlyphSize`)
- Internal padding — 4 dp / 4 dp; negative outer margins to keep the chip visually aligned with adjacent cells

Interaction (uses `combinedClickable`):

- **Tap** → `onCycleMode()` (lambda passed in from `RecordScreen`, calls `viewModel.cycleMode()`)
- **Long-press** → `onOpenSheet()` (gesture redundancy — long-press is the discoverability fallback)
- **Dimmed/`enabled=false`** when the card is in `dimmed=true` mode (= active session) — chip non-interactive, glyph fades to alpha 0.12

Other cells (Clip / Repeats / Wait / Quality) keep their existing pass-through behaviour: tap on those cells bubbles to the card's outer `clickable { onOpenSheet() }`. The chip absorbs tap events within its bounds so the outer handler doesn't fire for Mode taps.

### 3.4 Settings-wrap lift

`RecordChromeMetrics` gains `settingsCardLift = 30.dp`. `RecordScreen.kt` updates the settings-card bottom padding from `bottomNavClearance` (= 90 dp) to `bottomNavClearance + settingsCardLift` (= 120 dp). This pushes the card's lower edge 30 dp above the dock's top edge — clearing the gradient's transparent zone with an 8 dp buffer.

### 3.5 `cycleMode()` in `RecordViewModel`

```
fun cycleMode() {
    if (sessionLocked.value) return        // no-op during active session
    val next = when (mode.value) {
        "Portrait" -> "Landscape"
        "Landscape" -> "PortraitLandscape"
        "PortraitLandscape" -> "Portrait"
        else -> "Portrait"                  // defensive: unknown → Portrait
    }
    setMode(next)
}
```

Delegates to the existing `setMode(String)` path. No new persistence wire-up — `RovaSettings.mode`, `SessionConfig.mode`, schema 4 manifest, CameraX rotation override are all unchanged.

`sessionLocked` is the existing derived `StateFlow<Boolean>` (= `isPeriodicActive || isMerging`) consumed by `recordFabState`. The no-op guard prevents mid-recording mode swap via the chip (matches the existing sheet behaviour — Mode row is hidden when session active).

## 4. Architecture

```
[ Camera preview surface — full window (Slice A) ]
       │
       │   ┌───────────────────────────────────────────┐
       │   │  Status pill, cam-controls (Slice A)      │
       │   │  Top-anchored, glass on camera            │
       │   └───────────────────────────────────────────┘
       │
       │   ┌───────────────────────────────────────────┐
       │   │  RecordSettingsCard — pill shape (NEW)    │
       │   │  Bottom-aligned, bottom padding =         │
       │   │    bottomNavClearance + settingsCardLift  │
       │   │  Mode cell = ModeCycleChip (NEW)          │
       │   └───────────────────────────────────────────┘
       │
       │   ┌───────────────────────────────────────────┐
       │   │  RecordBottomNav (RESTYLED)               │
       │   │  Outer Box: vertical-gradient brush       │
       │   │  Inner Row: windowInsetsPadding + content │
       │   └───────────────────────────────────────────┘
       │
       ↓
[ OS gesture-nav region — transparent (Slice A); gradient blends through ]
```

No new screens, no new view-models, no new producers. The gradient brush is a token; tap-cycle is a one-line ViewModel method.

## 5. Components

### 5.1 `RecordChromeTokens` — modifications

| Token | Change |
|---|---|
| `bottomNavFill: Color` | **DELETE** — no consumers after this slice. |
| `bottomNavTopStroke: Color` | **DELETE** — gradient has no top. |
| `bottomNavBrush: Brush` | **NEW** — the 5-stop vertical gradient from §3.1. |
| `settingsCardRadiusPill: Dp` | **NEW** — `22.dp`. |
| `modeChipFill: Color` | **NEW** — `Color.White.copy(alpha = 0.07f)`. |
| `modeChipStroke: Color` | **NEW** — `Color.White.copy(alpha = 0.10f)`. |
| `modeChipGlyphAlpha: Float` | **NEW** — `0.35f` (enabled); `0.12f` (disabled). |
| `modeChipGlyphSize: TextUnit` | **NEW** — `7.sp`. |
| `settingsCardRadius: Dp` | **DELETE** — sole consumer is `RecordChrome.kt`'s `SettingsCardShape`, which switches to `settingsCardRadiusPill`. Zero remaining consumers. (Verified by grep at spec time.) |

### 5.2 `RecordChromeMetrics` — modifications

```kotlin
internal object RecordChromeMetrics {
    val bottomNavClearance = 90.dp
    val settingsCardLift = 30.dp                  // NEW
}
```

KDoc: "`settingsCardLift` is the additional padding above `bottomNavClearance` for the settings card. Required by Slice B's gradient dock: the gradient's transparent top zone (35 % of dock height ≈ 31 dp) would otherwise overlap the card's lower edge, producing a visible alpha-curve seam. The lift clears the gradient with an 8 dp buffer."

### 5.3 `RecordBottomNav` — refactor

Outer `Box` paints brush; inner `Row` consumes navigation-bar inset. The `.border(...)` line is removed. No other behaviour changes — Library / FAB / Settings layout and props are unchanged.

### 5.4 `RecordSettingsCard` — modifications

- Corner radius → `settingsCardRadiusPill`
- Background alpha tweak — `RecordChromeTokens.settingsCardFill` stays unchanged (already `Color.White.copy(alpha = 0.065f)`); pill curvature reads correctly with it
- Mode `SettingsCell(...)` call replaced by `ModeCycleChip(...)` (new local composable in `RecordChrome.kt`)
- New parameter: `onCycleMode: () -> Unit` — threaded from `RecordScreen`

### 5.5 `ModeCycleChip` — new composable

```kotlin
@Composable
private fun ModeCycleChip(
    mode: String,
    onCycleMode: () -> Unit,
    onLongPress: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
)
```

Uses `Modifier.combinedClickable` for tap + long-press. Internal layout: a Box with the outlined chip background, containing the Mode value + key text vertically stacked (mirroring `SettingsCell`'s text layout), plus the `↻` glyph absolutely positioned top-right via `Box(contentAlignment = TopEnd)`.

### 5.6 `RecordViewModel.cycleMode()` — new method

§3.5 spec. One file change, ~12 lines including KDoc.

### 5.7 `docs/UI_DESIGN_TOKENS.md` — new §2.13.X

Documents:
- `bottomNavBrush` gradient stops table (offset → alpha)
- `settingsCardRadiusPill` value + when to use vs `settingsCardRadius`
- `modeChip*` family (fill / stroke / glyph alpha / glyph size)
- `settingsCardLift` metric

Deletes the `bottomNavFill` and `bottomNavTopStroke` rows from §2.13.

### 5.8 `docs/adr/0012-gradient-scrim-dock.md` — new ADR

Records:
- Decision: gradient brush over flat fill; pill over rect; tap-cycle Mode over sheet-only
- Rejected alternatives: Variant A (Floating Glass — backdrop-blur API 31+ gate), Variant B (Native-Camera — settings card removal QoL regression)
- Future-work: Variant D (Ambient Liquid Glass / Slice C — 2027 target)
- Hard invariants preserved (cross-referenced to Slice A's ADR-0011)

## 6. Data flow

```
ModeCycleChip [tap]
   → onCycleMode() lambda (passed in by RecordSettingsCard from RecordScreen)
   → RecordViewModel.cycleMode()
   → setMode("Landscape")  (existing path)
   → RovaSettings.mode = "Landscape"  (persisted)
   → RecordViewModel.mode StateFlow emits
   → CameraX rotation override re-binds (existing path)
   → RecordSettingsCard recomposes with new mode value
```

No new collectors. No new producers. The chip is a presentation-only seam on top of the existing mode-write pipeline.

## 7. Edge cases & error handling

- **Mode value is an unknown string** — `cycleMode()` defaults to `"Portrait"`. Defensive: should not happen (set by `setMode` which only accepts the three valid strings), but the `else` arm keeps the cycle deterministic if persisted state is corrupted.
- **Tap during active session** — `cycleMode()` early-returns on `sessionLocked`. The chip's `enabled` is also `false` via `dimmed` propagation, so the tap shouldn't fire — but the VM-side guard is the load-bearing check.
- **Long-press during active session** — same `enabled = false` gate disables `combinedClickable` entirely. No tap, no long-press.
- **`combinedClickable` event consumption** — the chip's clickable absorbs the tap inside its bounds, preventing the outer card's `clickable { onOpenSheet() }` from firing. Compose's hit-testing handles this by default; verified in `RecordChrome.kt` smoke at implementation time. No defensive `propagationCheck` needed.
- **Brush painting under gesture-nav inset on devices that re-introduce auto-scrim** (Samsung One UI variant from Slice A's known unknown) — the gradient still paints, but the OS scrim overlays. Owner-flagged in Slice A; rollback path is the `values-v29/themes.xml` revert from ADR-0011, unchanged.
- **Three-mode cycle when P+L is disabled** (CameraX dual-stream unsupported, etc.) — current code path: `cycleMode()` doesn't introspect device capability. Phase 6.1c shipped DualShot render with `RovaApp`-level dual-camera enablement; if P+L is force-disabled at the device level, the cycle still writes `"PortraitLandscape"` and the camera-bind logic at `RovaRecordingService.setupCamera` handles the fallback (existing behaviour). Out of scope for Slice B.

## 8. Testing

- **`RecordViewModelTest.cycleMode_advances_through_three_modes`** — pure-JVM unit test. Asserts: Portrait → Landscape → PortraitLandscape → Portrait → Landscape (6 assertions covering one full cycle plus wrap-around verification).
- **`RecordViewModelTest.cycleMode_no_op_when_session_active`** — pure-JVM unit test. Sets `sessionLocked` to true via the existing producer (or a `@VisibleForTesting` seam if needed; preference is to drive it through the existing isPeriodicActive/isMerging path), calls `cycleMode()`, asserts `mode.value` unchanged.
- **No UI/Compose test layer** — project precedent: every prior chrome slice (Phase 4.1, R1, R2, Phase 6, ADR-0010 preview crop, ADR-0011 edge-to-edge) deferred Compose-pixel tests to owner on-device smoke. Slice B follows the same convention.
- **Slice A regression cover** — `AspectFitMathTest` stays green (no `service/dualrecord/**` changes), `WarningPrecedenceTest` / `WarningCenterAggregateTest` / `WarningIdOrderTest` stay green (no warning-precedence changes), `SegmentPathBuilderTest` / `RotationCalculatorTest` / `DualMuxerStateMachineTest` etc. stay green.

## 9. Files

| File | Action |
|---|---|
| `app/.../ui/theme/RecordChromeTokens.kt` | Modify — delete `bottomNavFill` + `bottomNavTopStroke`; add `bottomNavBrush`, `settingsCardRadiusPill`, `modeChip*` family. |
| `app/.../ui/screens/RecordChrome.kt` | Modify — `RecordBottomNav` outer-Box brush refactor; `RecordSettingsCard` corner radius swap + Mode cell → `ModeCycleChip`; new private `ModeCycleChip` composable; new `RecordChromeMetrics.settingsCardLift`. |
| `app/.../ui/screens/RecordScreen.kt` | Modify — settings-card bottom padding → `bottomNavClearance + settingsCardLift`; pass `onCycleMode = viewModel::cycleMode` to `RecordSettingsCard`. |
| `app/.../ui/screens/RecordViewModel.kt` | Modify — add `cycleMode()` (~12 lines incl. KDoc). |
| `app/src/test/.../RecordViewModelTest.kt` | Modify — add 2 test methods. |
| `docs/UI_DESIGN_TOKENS.md` | Modify — add new tokens to §2.13; delete `bottomNavFill` + `bottomNavTopStroke` rows. |
| `docs/adr/0012-gradient-scrim-dock.md` | Create — ADR for Slice B decisions + Slice C future work. |

7 files. ~50 lines added / ~30 lines deleted. Spec-allowlist file count = exactly 7.

## 10. Hard invariants

- No `service/dualrecord/**`, `EglRouter`, `AspectFitMath`, `DualVideoRecorder`, muxer, or recording-pipeline behaviour changes. Recorded files byte-identical.
- ADR-0009 + ADR-0010 + ADR-0011 outputs unchanged. `buildCropMatrix` / `buildSideAspectCrop` / `buildPreviewCropMatrix` and all `AspectFitMathTest` assertions untouched.
- `WarningId` / `WarningPrecedence` / `WarningCenterViewModel` / Start-gate / recovery flow untouched.
- `DualPreviewZone` `TextureView` / `SurfaceTexture` / `registerPreviewSurface` lifecycle untouched. P+L `352f` / `225f` zone weights + `cam-split-divider` untouched.
- `MainActivity.kt:23` `enableEdgeToEdge()` + `values-v29/themes.xml` + Theme.kt transparent-bar writes (Slice A) untouched.
- `RecordTopOverlay` / `RecordCameraControls` / `RecordRecoveryChip` / `RecordActiveHud` (R2) untouched. The slice touches only the dock + settings-card layer.

## 11. Owner follow-up (not implementer scope)

On-device smoke on the Samsung SM-A176B:

1. **Idle Portrait**: dock dissolves from top — no visible band edge against camera preview. Settings card sits cleanly above with no alpha-curve seam. Tap Mode chip — cycles Portrait → Landscape → P+L → Portrait. Long-press Mode chip — opens settings sheet.
2. **Idle Landscape**: same as Portrait. Confirm gradient also reads correctly with 16:9 preview.
3. **Idle DualShot (P+L)**: same as Portrait. Confirm the gradient zone overlays the bottom of the Landscape zone without obscuring the recording-frame guide (ADR-0010).
4. **Active recording**: settings card dims to 75 % alpha (existing behaviour); chip is non-interactive. R2 active HUD top-anchored, unaffected.
5. **Merge state**: same as active.
6. **Gesture-nav transparency continuity**: the gradient blends into the OS gesture-nav region with no hard boundary. Validates the outer-Box-brush refactor §3.1.
7. **Eye-test the 30 dp lift**: settings card is comfortably above the gradient band; not floating awkwardly high.
