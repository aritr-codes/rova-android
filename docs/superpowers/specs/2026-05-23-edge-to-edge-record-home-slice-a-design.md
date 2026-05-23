# Edge-to-Edge Immersive Record-Home (Slice A) — Design

**Date:** 2026-05-23
**Status:** Approved (brainstorm)
**Scope:** Window-level + RecordScreen + DualPreviewZone — system-bar transparency and inset-ownership shift. **No chrome restructure** (floating dock, pill controls, mode-picker placement, scrim gradients deferred to Slice B).

---

## 1. Problem

Per `screenshots/Screenshot_20260523_103836.png` (PORTRAIT), `103926.png` (LANDSCAPE), `103942.png` (P+L), the record screen reads as three discrete horizontal slabs: opaque status-bar band on top, letterboxed preview in the middle, opaque settings + dock + system gesture-nav region at the bottom. The native camera reference (`104011.png`) shows zero slabs — preview fills the whole screen, controls float, gesture-nav tone matches the app's bottom scrim.

Four concrete root causes (cited file:line from current `master @ e07d914`):

1. **Double-pad — insets consumed at the NavHost layer.** `MainScreen.kt:54` wraps NavHost in `Modifier.padding(innerPadding)`, shifting every screen inward by status + nav-bar heights. RecordScreen then pads its inner Box again at `RecordScreen.kt:436`. The preview never reaches the screen edge.

2. **Theme paints bars with wrong colors.** `Theme.kt:97-98` forces `window.statusBarColor = colorScheme.background.toArgb()` + `window.navigationBarColor = colorScheme.surface.toArgb()`. `MainActivity.kt:23` correctly calls `enableEdgeToEdge()`, but the theme's `SideEffect` immediately repaints both bars on every recomposition. Light theme = tan/beige bands; dark theme = a slightly different black than `Color.Black` used by RecordScreen — the seam visible at the bottom of the screenshots.

3. **Theme parent is old Material, not Material 3.** `themes.xml:4` — `parent="android:Theme.Material.Light.NoActionBar"`. Android 10+ defaults `enforceNavigationBarContrast=true`, so the OS adds its own translucent scrim behind the gesture-nav region on top of ours — doubling the dark band.

4. **Preview is letterboxed inside opaque zone backgrounds.** `DualPreviewZone.kt:95` paints each zone with `RecordChromeTokens.camZoneBackground` (dark fill) before the `TextureView` attaches. Where preview aspect ≠ zone aspect, this token shows as a hard rectangle around the preview — read as part of the slab.

## 2. Goal

RecordScreen previews fill the physical screen edge-to-edge in all 3 modes (Portrait / Landscape / DualShot P+L). System bars become transparent overlays. Gesture-nav region visually merges with the bottom of the app. No seams.

**Visual reference:** `screenshots/Screenshot_20260523_104011.png` (Samsung native camera). Preview extends under the status-bar icons; bottom dock scrim and gesture-nav region share one tone.

**Hard invariant — this is a removal patch.** No new components, no GL changes, no encoder/muxer/recording behavior changes. Recorded files byte-identical.

## 3. Chosen behaviour

Stop fighting the edge-to-edge the system already enforces (`targetSdk 37` triggers Android 15+ edge-to-edge enforcement; `MainActivity.kt:23` already calls `enableEdgeToEdge()`). Three precise removals + one swap:

- **Bars truly transparent.** Theme.kt `SideEffect` sets both `statusBarColor` and `navigationBarColor` to `Color.Transparent.toArgb()`. Light/dark icon appearance retained.
- **NavHost stops consuming insets.** `MainScreen.kt` Scaffold gains `contentWindowInsets = WindowInsets(0, 0, 0, 0)`; NavHost wrapping drops `.padding(innerPadding)`. RecordScreen Scaffold mirrors the same — its inner Box also drops `.padding(innerPadding)`. The 6 per-element `windowInsetsPadding(WindowInsets.statusBars/navigationBars)` calls already in RecordScreen.kt (lines 525/539/578/615/638/658) and the one in RecordChrome.kt:369 become the **only** inset-padding layer.
- **enforceNavigationBarContrast disabled.** New attribute in themes.xml suppresses the OS auto-scrim that doubles ours.
- **camZoneBackground deleted.** `DualPreviewZone.kt:95` drops the `.background(...)` call; the token is removed from `RecordChromeTokens.kt`. Preview zones render against the `Color.Black` Scaffold containerColor by default — identical to the current TextureView-pre-attach visual.

Rejected alternatives:

- **Camera-screen-only via WindowInsetsController SideEffect.** Brittle — SideEffect timing can flash a colored bar on navigation transitions; tangles theme + screen lifecycle.
- **Per-screen escape hatches.** Adding more `windowInsetsPadding` overrides leaves the broken NavHost double-pad in place; doesn't fix the root cause.
- **Slice B chrome restructure folded in.** Floating glass dock + pill controls + mode-picker placement = larger blast radius, needs HTML mockup iteration. Split for clean review.

## 4. Architecture (layer cake)

**Before:**

```
Window  enableEdgeToEdge() ✓
  └─ Theme.kt SideEffect repaints bars ✗
       └─ MainScreen Scaffold(containerColor=background)
            └─ NavHost(padding(innerPadding)) ✗ consumes all insets here
                 └─ RecordScreen Scaffold(containerColor=Black)
                      └─ Box(padding(innerPadding)) ✗ double-pad
                           └─ DualPreviewZone
                                └─ PreviewZone(background=camZoneBackground) ✗ opaque
                                     └─ TextureView (letterboxed by container)
```

**After:**

```
Window  enableEdgeToEdge() ✓
  ├─ Theme.kt SideEffect → bars Color.Transparent ✓
  ├─ themes.xml M3 parent + enforceNavigationBarContrast=false ✓
  └─ MainScreen Scaffold(containerColor=Black, contentWindowInsets=WindowInsets(0))
       └─ NavHost(fillMaxSize) ✓ no inset consumption
            └─ RecordScreen Scaffold(containerColor=Black, contentWindowInsets=WindowInsets(0))
                 └─ Box(fillMaxSize) ✓ no inset consumption
                      └─ DualPreviewZone
                           └─ PreviewZone (no background) ✓ transparent
                                └─ TextureView (fills full window)
                      [overlays use windowInsetsPadding per-element ✓ already in place]
```

Single inversion: insets owned by overlay elements, not layout containers. Preview is the only thing that gets the full window.

**Render path:** This slice is window/layout/insets only — does not touch `service/dualrecord/**`, `EglRouter`, `AspectFitMath`, `useFirstPrinciplesRender`, or any GL code. ADR-0010 preview-crop matrices stay verbatim.

## 5. Components

### 5.1 Theme.kt — bar colors → transparent

`app/src/main/java/com/aritr/rova/ui/theme/Theme.kt:97-100`

Replace:
```kotlin
window.statusBarColor = colorScheme.background.toArgb()
window.navigationBarColor = colorScheme.surface.toArgb()
```
With:
```kotlin
window.statusBarColor = Color.Transparent.toArgb()
window.navigationBarColor = Color.Transparent.toArgb()
```

Lines 99-100 (`isAppearanceLightStatusBars` / `isAppearanceLightNavigationBars`) **untouched** — preserves icon contrast across light/dark themes.

### 5.2 themes.xml — M3 parent + suppress OS auto-scrim

`app/src/main/res/values/themes.xml`:

```xml
<style name="Theme.Rova" parent="Theme.Material3.DayNight.NoActionBar">
    <item name="android:enforceNavigationBarContrast">false</item>
</style>
```

If `app/src/main/res/values-night/themes.xml` exists, mirror the change. If it does not exist, no action — `DayNight` parent handles both modes via the Compose colorScheme.

`android:enforceNavigationBarContrast` is API 29+. minSdk is well above (project ships modern Compose). No `InlinedApi` lint hit expected.

### 5.3 MainScreen.kt — drop NavHost double-pad

`app/src/main/java/com/aritr/rova/ui/screens/MainScreen.kt:48-55`

Change Scaffold to:
```kotlin
Scaffold(
    containerColor = Color.Black,
    contentWindowInsets = WindowInsets(0, 0, 0, 0)
) { innerPadding ->
    NavHost(
        navController = navController,
        startDestination = ...,
        modifier = Modifier.fillMaxSize()  // was: .padding(innerPadding)
    ) { ... }
}
```

`innerPadding` is intentionally unused — kept in the lambda signature because the Scaffold API requires it. (Alternative: rename to `_` to silence the unused-parameter warning — implementer call.)

`containerColor = Color.Black` unifies the fallback surface across all routes (RecordScreen already black; History/Settings have their own backgrounds via TopAppBar/list surfaces).

### 5.4 RecordScreen.kt — drop inner Box double-pad

`app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt:429-437`

Change Scaffold to:
```kotlin
Scaffold(
    snackbarHost = { SnackbarHost(...) },
    containerColor = Color.Black,
    contentWindowInsets = WindowInsets(0, 0, 0, 0)
) { _ ->
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) { ... }
}
```

Inner Box drops `.padding(innerPadding)`. The 6 per-element `windowInsetsPadding` calls already in place (lines 525, 539, 578, 615, 638, 658) become the sole inset layer.

### 5.5 DualPreviewZone.kt — drop opaque zone bg

`app/src/main/java/com/aritr/rova/ui/screens/DualPreviewZone.kt:95`

Change:
```kotlin
Box(modifier = modifier.background(RecordChromeTokens.camZoneBackground)) { ... }
```
To:
```kotlin
Box(modifier = modifier) { ... }
```

`RecordChromeTokens.camZoneBackground` has only one consumer (verified by investigator). Token is deleted in §5.6.

### 5.6 RecordChromeTokens.kt — delete camZoneBackground

`app/src/main/java/com/aritr/rova/ui/theme/RecordChromeTokens.kt`

Remove the `camZoneBackground` declaration. If `docs/UI_DESIGN_TOKENS.md` documents the token, remove that block too.

### 5.7 HistoryScreen.kt / SettingsScreen.kt — verify inset handling

`app/src/main/java/com/aritr/rova/ui/screens/HistoryScreen.kt`
`app/src/main/java/com/aritr/rova/ui/screens/SettingsScreen.kt`

**Verification step**, not a guaranteed edit. Each screen must either:
- (a) Use its own `Scaffold` + `TopAppBar` (Material 3 handles status-bar inset automatically), OR
- (b) Apply `.windowInsetsPadding(WindowInsets.systemBars)` to its root composable.

If verification finds either screen is a bare `Column` with no inset handling, add the one-line `.windowInsetsPadding(WindowInsets.systemBars)` on the root. No further changes.

### 5.8 ADR-0011 — new

`docs/adr/0011-edge-to-edge-record-home.md`

Document the four changes: bar transparency, inset ownership shift NavHost → per-element, Material 3 parent + enforceNavigationBarContrast=false, camZoneBackground deletion. Reference ADR-0010 (DualShot preview crop) as the most recent neighbor. Mark Slice B (chrome restructure) as future work.

## 6. Data flow

No new flows. Inset propagation is the only change:

- `WindowInsets` provided by system at the window level.
- `MainScreen Scaffold` with `contentWindowInsets = WindowInsets(0)` → does not consume.
- `NavHost(fillMaxSize)` → does not consume.
- `RecordScreen Scaffold` with `contentWindowInsets = WindowInsets(0)` → does not consume.
- Per-element overlays inside RecordScreen consume what they need:
  - recovery chip / idle WarningCenter / active HUD / top overlay / camera controls → `WindowInsets.statusBars`
  - settings card / RecordBottomNav → `WindowInsets.navigationBars`

## 7. Edge cases & error handling

- **Display cutout (notch / punch-hole).** `WindowInsets.statusBars` includes cutout insets on API 28+; chrome above the cutout is safe. Camera preview fills under the cutout — desired (matches native). `WindowInsets.displayCutout` not added in this slice; deferrable if a future device places the cutout problematically.
- **Gesture nav vs button nav.** `WindowInsets.navigationBars` returns the correct height for both. Smoke under both nav modes.
- **Light theme.** `isAppearanceLightStatusBars = !darkTheme` retained → status-bar icons dark on light themes, light on dark.
- **Soft keyboard / IME.** RecordScreen idle/active does not show text fields — out of scope.
- **TextureView attach race.** During the ~50ms TextureView attach window, the `Scaffold` containerColor (`Color.Black`) shows through the now-transparent zone. Visually identical to current behavior (both black).
- **SnackbarHost.** Material 3 `SnackbarHost` respects `WindowInsets.systemBars` by default; verify at smoke.
- **History / Settings.** If neither has its own inset-aware root, content slides under the status bar. §5.7 covers this — one-line fix per screen if needed.
- **enforceNavigationBarContrast on OEM skins.** Some Samsung One UI versions ignore the attribute. If smoke shows a leftover OS scrim under gesture nav, that's an OEM behavior we accept; rollback is single-attribute revert.

## 8. Testing

**Unit tests:** None. Window/insets/layout work; Compose UI is not unit-tested in this project (precedent: Phase 4.1, R1, R2, ADR-0010 all smoke-only).

**Automated gates:**
- `gradlew assembleDebug` — must compile clean
- `gradlew testDebugUnitTest` — stays at 1054/83/0-0-0 (no test changes)
- `gradlew lintDebug` — stays at 51 findings (no new InlinedApi/NewApi)

**On-device smoke (Samsung SM-A176B, owner):**

| Mode | Check |
|---|---|
| Portrait | Status bar transparent over preview. Preview fills full height including under status icons. Bottom dock + gesture-nav region share one continuous tone. No visible seam. |
| Landscape | Same checks, rotated. |
| DualShot P+L | Both zones full-bleed. cam-split-divider visible as 2dp line (untouched). Status bar transparent over top zone. Gesture-nav region merges with dock. |
| History screen | Title still readable (not under status bar). Back button reachable. |
| Settings screen | Same. |
| Light theme | Status-bar icons dark, readable. |
| Dark theme | Status-bar icons light. |
| Recording start | Active HUD + status pills sit correctly above gesture nav. |
| Recovery banner / Snackbar | Sits above gesture nav, readable. |

## 9. Files

| File | Action |
|---|---|
| `app/src/main/res/values/themes.xml` | Modify — parent → M3, add `enforceNavigationBarContrast=false` |
| `app/src/main/res/values-night/themes.xml` | Verify exists; if so, mirror the change |
| `app/src/main/java/com/aritr/rova/ui/theme/Theme.kt` | Modify — `SideEffect` → bars `Color.Transparent` |
| `app/src/main/java/com/aritr/rova/ui/screens/MainScreen.kt` | Modify — `Scaffold` `contentWindowInsets=WindowInsets(0)` + NavHost drops `.padding(innerPadding)` |
| `app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt` | Modify — `Scaffold` `contentWindowInsets=WindowInsets(0)` + inner Box drops `.padding(innerPadding)` |
| `app/src/main/java/com/aritr/rova/ui/screens/DualPreviewZone.kt` | Modify — drop `.background(camZoneBackground)` on PreviewZone Box |
| `app/src/main/java/com/aritr/rova/ui/theme/RecordChromeTokens.kt` | Modify — delete `camZoneBackground` |
| `app/src/main/java/com/aritr/rova/ui/screens/HistoryScreen.kt` | Audit — add `.windowInsetsPadding(WindowInsets.systemBars)` only if root is bare |
| `app/src/main/java/com/aritr/rova/ui/screens/SettingsScreen.kt` | Audit — same |
| `docs/UI_DESIGN_TOKENS.md` | Modify — remove `camZoneBackground` documentation if present |
| `docs/adr/0011-edge-to-edge-record-home.md` | Create — ADR for the slice |

## 10. Hard invariants

- No `service/dualrecord/**`, `EglRouter`, `AspectFitMath`, `DualVideoRecorder`, muxer, or recording-pipeline behaviour changes — recorded files byte-identical.
- ADR-0009 + ADR-0010 outputs unchanged. `buildCropMatrix` / `buildSideAspectCrop` / `buildPreviewCropMatrix` unchanged. All AspectFitMathTest assertions stay green.
- `WarningId` / `WarningPrecedence` / `WarningCenterViewModel` / Start-gate / recovery flow — untouched.
- `DualPreviewZone` `TextureView` / `SurfaceTexture` / `registerPreviewSurface` lifecycle and the `352f` / `225f` zone weights + `cam-split-divider` — untouched.
- `RecordChrome.kt` bottom-nav fill / settings card layout — untouched (chrome restructure is Slice B).
- `MainActivity.kt:23` `enableEdgeToEdge()` — untouched.

## 11. Owner follow-up (not implementer scope)

On-device smoke per §8 on Samsung SM-A176B. If smoke shows a leftover gesture-nav scrim under One UI (OEM ignores `enforceNavigationBarContrast=false`), accept as OEM behavior or revert that single attribute.

Slice B brainstorm: floating glass dock, pill zoom/mode controls, mode-picker placement, scrim gradients. Own spec → plan → PR when owner dispatches.
