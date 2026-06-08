# ADR-0028: Liquid-glass design system — flat-12 themes, role-aware glass, locked semantics

Status: Accepted
Date: 2026-06-07

## Context

Rova is moving its entire UI to a cohesive 2027–28 liquid-glass language with a
12-theme personality system, a defined motion + type scale, and WCAG 2.2 AA held
in every theme. This is a presentation-only program (no reliability/behavior
change) delivered foundation-first: PR1 builds the design system; later PRs
migrate one surface each. Two hard platform facts shape the design: Compose has
no CSS `backdrop-filter`, so a translucent panel cannot blur the live camera
behind it; and `RenderEffect` blur requires API 31+ (minSdk is 24).

## Decision

1. **Themes are a flat selection.** `ThemeSelection { FOLLOW_SYSTEM, AURORA
   (default), TIDE, JADE, DUSK, ECLIPSE, DAYLIGHT, BLOSSOM, CORAL, MEADOW,
   COBALT, ORCHID, GRAPHITE }`. Wave-1 (the first six palettes) ships in the
   picker; all 12 are defined from PR1. Follow-System maps OS light→Daylight,
   OS dark→Aurora. Two-axis palette×mode theming is rejected (combinatorial QA).
2. **Migration from the shipped `ThemeMode` (#80) is pure + deterministic**,
   run in the synchronous SharedPreferences read path (no async DataStore) so
   the first frame never flashes a default: SYSTEM→FOLLOW_SYSTEM, DARK→AURORA,
   LIGHT→DAYLIGHT, missing/unknown→FOLLOW_SYSTEM. The new key `theme_selection`
   is written; the old `theme_mode` is left intact for one release (rollback).
3. **Palettes supply only** background + glass tint + edges + accent gradient +
   text alphas (+ pinned-dark accent companions). **Semantic colors are locked**
   in `RovaSemantics`, identical across all 12: success #34d399, warning #fbbf24,
   error #ef4444, escalating #f97316, rec #ff4d4d. Eclipse (OLED) carries depth
   in its edges because #000 hides elevation. Dynamic color / Material You is OFF.
4. **Glass is role-aware.** `LocalGlassEnvironment {palette, apiLevel,
   reduceTransparency}` is seeded once high in `RovaTheme`; call sites use
   `GlassSurface(role = GlassRole.X)` which resolves a concrete `GlassMaterial`
   via the pure `GlassResolver.resolve(env, role)`. NOT a tree-wide
   `isRecordSurface` flag — sheets/dialogs mount inside `RecordScreen` and must
   keep their own role.
5. **Degradation is first-class in the resolver.** `blurRadius > 0` only when
   `apiLevel >= 31 && !reduceTransparency && role != RecordChrome`; otherwise
   blur = 0 and the fill is bumped to a heavier opaque-enough tint (fully solid
   under Reduce-Transparency). Reduce-Transparency is evaluated FIRST, ahead of
   the RecordChrome branch, so the accessibility opt-out is never overridden.
6. **No backdrop blur over the camera.** Record glass sells depth with fill +
   gradient scrim + edge + an opaque capsule behind critical text — never a
   backdrop blur (impossible in Compose). The existing
   `RenderEffect.createBlurEffect` in `DualPreviewZone.kt` blurs the
   *non-recorded framing margins*, not chrome, and is an explicit carve-out.
   `GlassSurface` applies `Modifier.blur` only to a back fill layer, never to
   the content subtree (Compose's blur is own-content, not a backdrop sample).
7. **Camera/media routes stay pinned dark in every theme** (Record, Player,
   Onboarding): one shared cinematic neutral-dark base; personality shows via a
   contrast-checked `accentOnDark` companion on meaningful controls only. App
   surfaces (Library/Settings/History/warnings/sheets/dialogs) carry the full
   active palette. The route-aware status-bar writer (`isPinnedDarkRoute`) flips
   icon polarity per transition.
8. **Motion + type are tokenized.** `RovaMotion` centralizes spring/duration
   tokens; every looping/auto-playing use site additionally gates on the
   reduced-motion seam (ADR-0020 / `checkA11yAnimationGated`). `RovaType` adds a
   named Inter ramp; de-emphasis is by alpha, never a second hue. The type scale
   is additive in PR1 — the global `Typography` is migrated per surface, not
   wholesale.
9. **Icons (PR11) and launcher/splash (PR12) are separate later PRs** — high
   blast radius. The themed launcher icon is a single static monochrome asset
   (cannot follow the in-app theme).

## Enforcement

- `checkRecordSurfaceNoBlur` (new preBuild gate): record-chrome files
  (`RecordScreen.kt`, `RecordChrome.kt`, `RecordChromeIcons.kt`) apply no
  `Modifier.blur` / `RenderEffect` / `createBlurEffect`; `DualPreviewZone.kt` is
  the preview/carve-out and is deliberately outside the record-chrome scan set.
- `checkGlassSurfaceRoleUsage` (new preBuild gate): `Modifier.blur` is permitted
  only in `GlassSurface.kt` (sanctioned glass), the `DualPreviewZone.kt` carve-out,
  and the two pre-existing decorative icon-glow-bloom sites `WarningSheetV3.kt` /
  `RecoveryCard.kt` (allowlisted with a TODO to migrate when PR6/PR9 land) — all
  new glass goes through `GlassSurface(role=…)`/`GlassResolver`.
- Per-theme contrast tests (`ThemeContrastTest`, JVM): 12 palettes × text /
  selected-control roles × fallback modes, asserting body ≥ 4.5:1 and
  selected-control/border ≥ 3:1 against each palette's own resolved surface,
  including the API<31 solid fallback and the Eclipse OLED surface. Extends
  `ContrastMath` (ADR-0020 reuse).
- `GlassResolver`, `ThemeMigration`, `RovaMotion`, the palette registry, and
  `ThemeSelection` are pure-Kotlin and JVM-tested.
- Existing gates preserved unchanged.

## Consequences

- PR1 introduces no visual regression outside the App Settings proof surface:
  the selection→palette→dark path is wired and `LocalGlassEnvironment` is
  seeded, but only App Settings renders through `GlassSurface`; every other
  surface keeps its current `DarkColorScheme`/`LightColorScheme` rendering until
  its own migration PR.
- Wave-2 themes are a one-line picker change later (no enum work).
- The honest limit "no backdrop blur over the camera" is a permanent platform
  constraint encoded in the resolver and gates, not a temporary shortcut.
