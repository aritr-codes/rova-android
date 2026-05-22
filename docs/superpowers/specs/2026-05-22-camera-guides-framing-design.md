# Camera-Guides Framing — Design

**Date:** 2026-05-22
**Status:** Approved (owner, 2026-05-22)
**Sub-project:** 2 of the record-screen re-skin to `mockups/new_uiux/`.

## Goal

Re-skin the camera viewfinder to pixel-match the decorative framing in
`mockups/new_uiux/01-record-home.html` — the `#060d18` zone background, a faint
composition grid, an edge vignette, centred focus brackets, and (P+L only) the
plain-text zone tags — across **all three modes** (single Portrait, single
Landscape, P+L split). A new app-settings toggle turns the grid + focus brackets
+ vignette on and off; it is **on by default**.

## Scope & Decomposition

This is **sub-project 2**. The owner also wants the whole app-settings screen
re-skinned to `mockups/new_uiux/06-app-settings.html` — that is **sub-project 3**,
a separate brainstorm → spec → plan cycle (a 7-section full-screen re-skin,
independent of this work).

The two intersect at exactly one point: the camera-guides toggle. This
sub-project adds that toggle row to the **current** `SettingsScreen.kt` in its
existing style. The data layer it introduces (`RovaSettings.cameraGuidesEnabled`
+ the `SettingsViewModel` flow) survives sub-project 3 untouched — only the
~10-line toggle row will be restyled when the screen is re-skinned.

### Out of scope

- The `06-app-settings.html` full-screen re-skin (sub-project 3).
- Tap-to-focus / autofocus interaction — the focus brackets are **static
  decorative** chrome, matching the static mockup. No touch handling.
- Any change to the recording pipeline, EGL/preview surface plumbing, or the
  P+L split-zone proportions (`352:225` weights stay).

## Architecture

A new stateless, token-driven `CameraGuides.kt` holds the three decorative
overlay layers. A single `cameraGuidesEnabled` boolean threads from
`RovaSettings` through the shared `SettingsViewModel` to both the settings
screen (write) and the record screen (read):

```
SettingsScreen Switch ─▶ SettingsViewModel.cameraGuidesEnabled (MutableStateFlow)
                                  │  write-through
                                  ▼
                          RovaSettings (SharedPreferences)

RecordScreen ─reads─▶ SettingsViewModel.cameraGuidesEnabled ─▶ CameraGuides(visible=…)
```

`SettingsViewModel` is **Activity-scoped** — instantiated once in `MainScreen`
and passed to both `RecordScreen` and `SettingsScreen` (its KDoc: "changes in
one are immediately visible in the other without a restart"). `RecordScreen`
already receives it and reads `keepScreenOn` off it. Therefore the camera-guides
flag needs **no `RecordViewModel` change and no ON_RESUME re-read** — the shared
`MutableStateFlow` is the live cross-screen state. A toggle in settings updates
the same flow the record screen collects; the overlays recompose immediately.

`CameraGuides` is **non-interactive** (no `clickable` / `pointerInput`) — it
cannot intercept camera-area touches.

## Components

| File | Action | Responsibility |
|---|---|---|
| `ui/screens/CameraGuides.kt` | Create | `CameraGuides(visible, modifier)` wrapper + private `CameraGrid` / `CameraVignette` / `FocusFrame`. `visible = false` → renders nothing. |
| `ui/theme/RecordChromeTokens.kt` | Modify | Add the grid-cell, vignette, and focus-bracket tokens (see Tokens). |
| `ui/screens/DualPreviewZone.kt` | Modify | Zone bg → `camZoneBackground`; zone tag chip → plain uppercase micro-text; overlay `CameraGuides` per zone; new `guidesEnabled` param. |
| `ui/screens/RecordScreen.kt` | Modify | Read `settingsViewModel.cameraGuidesEnabled`; overlay `CameraGuides` on the single-mode `AndroidView` preview; pass `guidesEnabled` to `DualPreviewZone`. |
| `data/RovaSettings.kt` | Modify | `cameraGuidesEnabled: Boolean`, default `true`, key `camera_guides_enabled`. |
| `ui/screens/SettingsViewModel.kt` | Modify | `cameraGuidesEnabled: MutableStateFlow<Boolean>` + write-through `collect` (mirrors `keepScreenOn`). |
| `ui/screens/SettingsScreen.kt` | Modify | `SettingsRow` + `Switch` in the "Recording behavior" section. |
| `docs/UI_DESIGN_TOKENS.md` | Modify | Document the new tokens. |

`RecordViewModel.kt` is **not** modified — the flag is read off the shared
`SettingsViewModel`.

### `CameraGuides.kt`

```
@Composable
fun CameraGuides(visible: Boolean, modifier: Modifier = Modifier)
```

When `visible` is false it emits nothing. When true it stacks, in a
`Box(modifier)` filling its parent:

- `CameraGrid` — the faint composition grid.
- `CameraVignette` — the edge-darkening radial gradient.
- `FocusFrame` — the centred corner brackets.

The caller sizes the `Box` (a preview zone or the single-mode preview area);
`CameraGuides` fills it. All three sub-composables are `private` and stateless.

## Data flow

- **Write:** the settings `Switch`'s `onCheckedChange` sets
  `settingsViewModel.cameraGuidesEnabled.value`; an `init` `collect` writes
  through to `RovaSettings.cameraGuidesEnabled` — identical to `keepScreenOn`.
- **Read:** `RecordScreen` does
  `val cameraGuidesEnabled by settingsViewModel.cameraGuidesEnabled.collectAsStateWithLifecycle()`
  beside the existing `keepScreenOn` collection. It passes
  `CameraGuides(visible = cameraGuidesEnabled)` over the single-mode preview and
  `DualPreviewZone(guidesEnabled = cameraGuidesEnabled, …)` for P+L.
- `DualPreviewZone` forwards `guidesEnabled` into each `PreviewZone`, which
  emits `CameraGuides(visible = guidesEnabled)` above its `TextureView`.

## Rendering detail (mockup-exact)

All CSS px map to `Dp` 1:1 (project precedent — "keep dp-exact").

### Zone background
`.cam-zone { background: #060d18 }` → the `PreviewZone` `Box` background changes
from `Color.Black` to `RecordChromeTokens.camZoneBackground` (`Color(0xFF060D18)`,
already declared). The single-mode preview keeps whatever it renders beneath the
camera; no background change there (the live `AndroidView` fills it).

### Zone tag (P+L only)
`.cam-zone-tag { bottom: 9px; right: 13px; font-size: 7.5px; font-weight: 500;
letter-spacing: 1.5px; text-transform: uppercase; color: rgba(255,255,255,0.32) }`.

Today `DualPreviewZone` renders the tag as a rounded `M3Surface` chip with a
black-45% fill and `MaterialTheme.typography.labelSmall`. Re-skin: drop the chip
wrapper — render a bare `Text`, bottom-end aligned with
`padding(end = zoneTagPaddingEnd, bottom = zoneTagPaddingBottom)` (13 / 9 dp,
already declared), style `RovaTokens.zoneTag` (Inter Medium 7.5 sp, 1.5 sp
tracking — already an exact match), colour `RecordChromeTokens.zoneTagText`
(white-0.32, already declared), text `.uppercase()`. Tag strings stay
`"Portrait · 9:16"` / `"Landscape · 16:9"`.

Single Portrait / single Landscape modes get **no** tag — one full-frame preview
has nothing to disambiguate.

### Grid
`.camera-grid` — `linear-gradient` 1 px lines at `rgba(255,255,255,0.018)`,
`background-size: 105.3px 228.3px` (one vertical + one horizontal line per cell).

`CameraGrid` draws with `Canvas` / `drawBehind`: vertical lines every
`cameraGridCellWidth` (105.3 dp) and horizontal lines every
`cameraGridCellHeight` (228.3 dp), 1 dp (`cameraGridLineWidth`), colour
`cameraGridLine` (white-0.018, already declared). Lines start from the
top-left origin of the zone.

### Vignette
`.camera-vignette` — `radial-gradient(ellipse 90% 80% at 50% 50%,
transparent 35%, rgba(0,0,0,0.6) 100%)`.

`CameraVignette` is a `Box.fillMaxSize().background(Brush.radialGradient(...))`
with colour stops `0.35f → Color.Transparent`, `1.0f → cameraVignetteEdge`
(black-0.6). Compose `radialGradient` is circular; the mockup's `ellipse
90% 80%` is approximated by a circular gradient sized to the zone — the same
class of faithful approximation as the settings-sheet's no-blur substitution.
The radius is left at the `radialGradient` default (covers to the farthest
corner) so the darkening reaches all four edges.

### Focus frame
`.focus-frame` — 60×60 px, `top:50%; left:50%; transform: translate(-50%,-60%)`,
`opacity: 0.25`; four L-shaped corners, each 14×14 px, border 1.5 px,
`border-color: rgba(255,255,255,0.8)`. Effective stroke alpha = 0.8 × 0.25 =
**0.20**.

`FocusFrame` is a `Canvas` of `focusFrameSize` (60 dp, already declared),
positioned by the parent `Box` as centred horizontally and centred vertically
then offset **up** by `focusFrameSize * 0.1` (≈ 6 dp — the CSS `-60%` vs `-50%`
translate puts the frame's centre 10 % of its height above the parent centre).
It draws four corner brackets: each corner is two line segments of length
`focusFrameCornerArm` (14 dp), stroke `focusFrameStrokeWidth` (1.5 dp), colour
`focusFrameStroke` (white-0.20, already declared — the pre-multiplied effective
alpha, so no extra `opacity` layer is needed).

## Tokens

Already declared in `RecordChromeTokens` (Phase 1) and now consumed:
`camZoneBackground`, `cameraGridLine`, `focusFrameStroke`, `focusFrameSize`,
`zoneTagText`, `zoneTagPaddingEnd`, `zoneTagPaddingBottom`, `splitDivider`,
`splitDividerHeight`.

New tokens to add to `RecordChromeTokens`:

| Token | Value | Mockup source |
|---|---|---|
| `cameraGridCellWidth` | `105.3.dp` | `.camera-grid background-size` X |
| `cameraGridCellHeight` | `228.3.dp` | `.camera-grid background-size` Y |
| `cameraGridLineWidth` | `1.dp` | grid line thickness |
| `cameraVignetteEdge` | `Color.Black.copy(alpha = 0.6f)` | `.camera-vignette` outer stop |
| `cameraVignetteInnerStop` | `0.35f` | `.camera-vignette` `transparent 35%` |
| `focusFrameCornerArm` | `14.dp` | `.focus-frame` corner size |
| `focusFrameStrokeWidth` | `1.5.dp` | `.focus-frame` corner `border-width` |

`RovaTokens.zoneTag` already matches the mockup tag type (Inter Medium 7.5 sp,
1.5 sp tracking) — no new text style.

## Hard invariants (must stay byte-identical)

- `DualPreviewZone`'s `TextureView` / `SurfaceTexture` / `registerPreviewSurface`
  / `unregisterPreviewSurface` lifecycle — the recording-preview plumbing.
- `service/**`, `dualrecord/**`, the recording pipeline.
- `WarningId` / `WarningPrecedence` / `WarningCenter` / the `RecordScreen`
  Start-gate region (`startBlocked`, `onStart`).
- `RecordChrome.kt` / `RecordChromeTokens.kt` existing token values (only
  additions).
- The P+L split-zone weights (`352f` / `225f`) and the `cam-split-divider`.

## Error handling

No new failure modes. The overlays are decorative and draw regardless of
camera-ready state — if the preview is black/initialising, the guides simply
draw over black. `CameraGuides(visible = false)` emits nothing, so the toggle
has no partial/error state.

## Testing

Pure Compose UI — colour, geometry, gradient, Canvas drawing. The project has
**no Robolectric / Compose-UI-test layer**; pixel UI is not unit-tested
(Phase 1 / 2 / R1 / R2 precedent). `RovaSettings.cameraGuidesEnabled` is a
trivial SharedPreferences get/set with no branching — no testable seam.

Verification: `:app:assembleDebug` builds, `:app:lintDebug` does not regress,
`:app:testDebugUnitTest` stays green (the existing suite is unaffected), plus
owner on-device smoke against `01-record-home.html`.

## Owner follow-up (not implementer scope)

- On-device smoke (Samsung SM-A176B): all three modes show the grid + vignette +
  focus brackets when the toggle is on; toggling it off in Settings clears them
  immediately on return to Record; P+L zone tags read as plain uppercase
  micro-text bottom-right; compare to `01-record-home.html`.
- Sub-project 3 — `06-app-settings.html` full-screen re-skin.
