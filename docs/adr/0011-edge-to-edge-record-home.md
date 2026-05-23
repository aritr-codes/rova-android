# ADR-0011 — Edge-to-edge record-home: per-element inset ownership

**Status:** Accepted

**Date:** 2026-05-23

## Context

After ADR-0010 (DualShot preview crop-to-fill, merged 2026-05-22 @ `e07d914`), the record screen's GL-rendered preview surface inside each DualShot zone fills its container with no internal letterbox. Yet on-device screenshots (Samsung SM-A176B, 2026-05-23) showed the screen still reading as three discrete horizontal slabs: an opaque status-bar band on top, the preview itself, and an opaque settings + dock + system-gesture-nav region at the bottom. The seams sat outside the GL-rendered region — at the window level.

Four root causes (all citing `master @ e07d914`):

1. `MainScreen.kt:54` wraps `NavHost` in `Modifier.padding(innerPadding)`, consuming every screen's insets at the root layout.
2. `RecordScreen.kt:436` re-applies the same padding via the inner Box — a second insets layer.
3. `Theme.kt:97-98` writes `colorScheme.background.toArgb()` / `colorScheme.surface.toArgb()` to the system bars every recomposition, undoing `MainActivity.kt:23` `enableEdgeToEdge()`.
4. `themes.xml:4` parent `android:Theme.Material.Light.NoActionBar` doesn't set `enforceNavigationBarContrast=false`, so Android 10+ draws its own translucent scrim behind the gesture-nav region on top of ours.

The result: preview never reached the physical screen edges; status bar and gesture-nav region painted in colors that didn't match RecordScreen's `Color.Black` containerColor. Native Samsung camera renders edge-to-edge with no such seams — that is the target.

## Decision

**Insets are owned by overlay elements, not by layout containers. System bars are transparent. The preview surface gets the full window.**

Four concrete reversals:

- **`Theme.kt` `SideEffect`** writes `Color.Transparent.toArgb()` to both `window.statusBarColor` and `window.navigationBarColor`. Icon-appearance writes (`isAppearanceLightStatusBars` / `isAppearanceLightNavigationBars`) are retained for readability across light/dark themes.
- **`themes.xml`** parent stays `android:Theme.Material.Light.NoActionBar` (this is a Compose-only project — no `com.google.android.material:material` View dep, so `Theme.Material3.DayNight.NoActionBar` is unavailable as an XML style; Compose's `MaterialTheme` handles runtime theming regardless). The `android:enforceNavigationBarContrast=false` attribute is API 29+, so it lives in a new `values-v29/themes.xml` resource-qualifier split to suppress the OS auto-scrim on API 29+ without triggering a NewApi lint on the project's minSdk 24 baseline.
- **`MainScreen.kt` `Scaffold`** gains `contentWindowInsets = WindowInsets(0, 0, 0, 0)`; `NavHost` modifier drops `.padding(innerPadding)`. Container color flips to `Color.Black` for a unified fallback surface.
- **`RecordScreen.kt` `Scaffold`** mirrors the same: `contentWindowInsets = WindowInsets(0)`, inner Box drops `.padding(innerPadding)`. The 6 per-element `windowInsetsPadding(WindowInsets.statusBars/navigationBars)` calls already at lines 525 / 539 / 578 / 615 / 638 / 658, plus the one at `RecordChrome.kt:369`, become the sole inset layer.
- **`DualPreviewZone.kt:95`** drops `.background(RecordChromeTokens.camZoneBackground)`. The unused token is deleted from `RecordChromeTokens.kt` and `docs/UI_DESIGN_TOKENS.md`.

History and Settings screens already handle their own insets — Settings via `Scaffold` + `TopAppBar` (Material 3's default `ScaffoldDefaults.contentWindowInsets` respects `WindowInsets.systemBars`); History wraps its Scaffold in a gradient `Box` whose extension behind the status bar is the intended aesthetic. No per-screen edits needed.

## Consequences

**Accepted:**
- Inset-padding ownership is no longer co-located in one place (NavHost wrapping). Each screen — and within RecordScreen, each overlay element — is responsible for its own inset clearance. Debugging an inset bug requires looking at the element, not the container.
- Compose Material 3 components that depend on `LocalScaffoldDefaults.contentWindowInsets` (e.g., `BottomAppBar`) on the RecordScreen now see `WindowInsets(0)` and must opt into insets explicitly if mounted there. Not currently a problem — `RecordChrome.kt:369` already calls `windowInsetsPadding(WindowInsets.navigationBars)` directly.
- `RecordChromeTokens.camZoneBackground` is gone. Any future feature that wants a non-black fallback behind a TextureView must re-introduce a token or use a different color.
- A new resource-qualifier file (`values-v29/themes.xml`) joins the build. Future API gates on theme attributes can follow the same pattern.

**Rejected:**
- *Camera-screen-only via WindowInsetsController side effect on enter/exit.* Brittle — `SideEffect` timing can flash a colored bar on navigation transitions; entangles theme + screen lifecycle.
- *Keep NavHost padding, override per-screen.* Doesn't fix the root cause (status-bar repaints in Theme.kt) and leaves the double-pad in place for any future screen that forgets the override.
- *`Theme.Material3.DayNight.NoActionBar` XML parent.* Requires the `com.google.android.material:material` View dependency, which this Compose-only project does not ship. Adding that dep just to satisfy an XML parent reference would inflate APK size for zero functional benefit — Compose's runtime `MaterialTheme` already drives all theming. Rejected in favor of the resource-qualifier split.
- *Slice B chrome restructure folded in.* Floating glass dock + pill controls + mode-picker placement = larger blast radius, needs HTML mockup iteration. Split for clean review.

**Hard invariants (preserved by this slice):**
- No `service/dualrecord/**`, `EglRouter`, `AspectFitMath`, `DualVideoRecorder`, muxer, or recording-pipeline behaviour changes — recorded files byte-identical.
- ADR-0009 + ADR-0010 outputs unchanged. `buildCropMatrix` / `buildSideAspectCrop` / `buildPreviewCropMatrix` and all `AspectFitMathTest` assertions untouched.
- `WarningId` / `WarningPrecedence` / `WarningCenterViewModel` / Start-gate / recovery flow untouched.
- `DualPreviewZone` `TextureView` / `SurfaceTexture` / `registerPreviewSurface` lifecycle untouched. P+L `352f` / `225f` zone weights + `cam-split-divider` untouched.
- `MainActivity.kt:23` `enableEdgeToEdge()` untouched.

## Future work

**Slice B** — floating chrome restructure: dock fill becomes a translucent scrim/gradient instead of `bottomNavFill`; settings row + tray restyled as glass pills; mode-picker placement reconsidered (native camera puts it just above the gesture nav). Own brainstorm → spec → plan → PR. Requires HTML mockup iteration; not folded into Slice A to keep the blast radius small.

**OEM rollback path** — some Samsung One UI versions may ignore `android:enforceNavigationBarContrast=false` and continue drawing the auto-scrim. If on-device smoke surfaces a leftover scrim under gesture nav, the rollback is a single-attribute revert in `values-v29/themes.xml`; the rest of the slice still delivers the bar-transparency + double-pad fix.
