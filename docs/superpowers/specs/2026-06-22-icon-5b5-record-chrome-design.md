# 5b-5 Record-chrome Icon Polish — Design

**Date:** 2026-06-22
**Track:** UI Phase 2, ADR-0031 in-app icon & glyph system. Follows 5b-2 (Warnings, PR #130) and 5b-3 (Settings, PR #131).

## Goal

Three record-screen + history visual fixes:
1. The flip-camera control reads as **"switch camera"** (rotation arrows) instead of a generic camera.
2. The record-nav **Library/Settings** icons get the **glass-circle background** the cam-controls (flash/flip) already have, so they stop looking like bare glyphs beside the accent FAB.
3. **History/Library card glyphs** are sized up for legibility.

## Context (current state)

- `RecordChrome.kt` `CamFlipButton` (~297–308) renders camera-**body** glyphs (`RovaGlyphs.CameraFront` / `CameraRear`) → reads as "camera," not "flip." `RovaIcons.FlipCam` (rotation-arrows duotone glyph) is **already authored, mapped, and tested** (`RovaIconsTest`), but **unused** at the flip site. `RovaGlyphs.kt:328` and `RovaIcons.kt:67` comments pre-commit this migration to "5b-2…5b-5" — this slice is that migration.
- `RecordChrome.kt` `NavItem` (~722–757) renders the glyph in a clipped **square Box with no fill** → the "bare boxes." `GlassCircleButton` (~314–329, used by flash/flip) wraps its content in `GlassSurface(role = RecordChrome, shape = CircleShape)` sized to `RecordChromeTokens.camControlSize`.
- History/Library cards render glyphs at the `LibraryDimens` family: `actionIcon = 20.dp`, card `Select` 22–24.dp, `statusDotSize = 6.dp` (a dot, not a glyph).
- `CameraFront`/`CameraRear` are used **only** at the flip site (not in `RovaIcons`, not in any test).

## Changes

### Change 1 — Flip glyph → FlipCam

**File:** `RecordChrome.kt` `CamFlipButton` (~297–308); `RovaGlyphs.kt` (remove dead defs).

- Replace `val flipIcon = if (isFrontCamera) RovaGlyphs.CameraRear else RovaGlyphs.CameraFront` with `RovaIcons.FlipCam` (single glyph for both lens states). Switch the render to the `SemanticIcon` **glyph overload**:
  `SemanticIcon(glyph = RovaIcons.FlipCam, contentDescription = flipCd, role = role, modifier = Modifier.size(16.dp))`.
- `flipCd` (the target-lens contentDescription, `record_switch_to_rear_cd` / `record_switch_to_front_cd`) is **unchanged** → a11y / WCAG naming preserved even though the glyph no longer differs per lens.
- Remove the now-dead `RovaGlyphs.CameraFront` and `RovaGlyphs.CameraRear` defs; update the stale comments at `RovaGlyphs.kt:328` and `:346`.
- **SSOT:** round-trip `FlipCam` through `board-3-semantic.html` @16dp to confirm legibility; adjust d-strings in `RovaGlyphs.kt` **only** if needed (`checkRovaGlyphHome` keeps bespoke-glyph edits in that file).
- **Test:** composable, visual-only → no new JVM test. `RovaIconsTest` already pins `RovaIcons.FlipCam == RovaGlyphs.FlipCam`; removing `CameraFront`/`CameraRear` breaks no test.

### Change 2 — Nav glass-circle background

**File:** `RecordChrome.kt` `NavItem` (~735–751).

- Wrap the glyph in `GlassSurface(role = GlassRole.RecordChrome, shape = CircleShape, modifier = Modifier.size(RecordChromeTokens.navIconBoxSize))` with the glyph centered (`Box(Modifier.fillMaxSize(), contentAlignment = Center)`), mirroring `GlassCircleButton`'s inner structure. Glyph stays at `navIconGlyphSize * rememberChromeScale()`.
- The `clickable` + `focusHighlight` stay on the **Column** (unchanged) → the 48dp-equivalent touch target is preserved. Switch the `focusHighlight` shape from `RoundedCornerShape(navIconCornerRadius)` to `CircleShape` so the focus ring tracks the new circle.
- Applies to **both** Library and Settings (single `NavItem`) and **both** orientations (portrait bottom bar + landscape rail) automatically.
- **Gates:** `checkGlassSurfaceRoleUsage` (RecordChrome role — precedent is `GlassCircleButton`), `checkRecordSurfaceNoBlur` (GlassSurface is not `Modifier.blur`), `checkRecordChromeLockSingleSite` (confirm no second chrome-lock site introduced).
- **Test:** visual → device smoke (both orientations).

### Change 3 — History/Library card glyph size (#3)

**Files:** `LibraryDimens.kt` + affected card components (`LibraryGridCard`, `LibraryListRow`, `LibraryHeroCard` as applicable).

- Bump the card status/type/action glyph size **token(s)**. Current: `actionIcon = 20.dp`, card `Select` 22–24.dp. Proposed starting point: `actionIcon` 20→24, card glyphs +2–4dp — but the **final value is set by on-device A/B**, since "reads too small" is a perceptual judgment. Controller captures before/after on RZCYA1VBQ2H; owner picks.
- **Icon-size tokens only** — no layout reflow, no container/thumbnail resize.
- **Test:** token change, visual → device A/B.

## Global Constraints

- Branch `feat/icon-5b5-record-chrome` off **latest master** (after #130/#131 merge).
- **46 gates + full JVM green at EVERY commit.** Never edit a `check*` to pass — fix the source.
- **No new user-facing strings** (all changes are decorative / sizing).
- `board-3-semantic.html` (gitignored) is the glyph **SSOT** — round-trip any glyph d-string change.
- **EDIT-only subagents**; the controller runs all gradle/tests/commits/smoke. Build **WARM** (no cache wipe).
- **codex** peer review for the flip + nav-glass edits (>5 lines / chrome behavior).
- Device smoke on **RZCYA1VBQ2H** (Android 14): record screen (flip glyph + nav circles, both orientations) + history (card glyph size). **Push/PR/merge only on explicit owner GO.**

## Out of Scope (deferred)

- Flash glyph restyle (owner: leave as-is).
- 5b-4 (Onboarding + `rec_clipcheck`/`interrupted` status glyphs); bottom-nav glass beyond `NavItem`.
- Player resume-persistence (Track B — separate spec, already written).

## Testing Summary

Visual-heavy chrome slice → light JVM (no new pure-logic seam to extract). `RovaIconsTest` already covers `FlipCam` identity. Verification per commit = 46 static gates + full `testDebugUnitTest` + on-device smoke + board round-trip for `FlipCam`.
