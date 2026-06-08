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

## PR2 amendment (2026-06-08) — Record home

Migrating the first pinned-dark surface (Record) refined four points of §2.2 / §2.4:

a. **`GlassRole.RecordChrome` fill is airy (~0.40), not a 0.88 slab.** The
   original `RECORD_ALPHA = 0.88` was tuned for an opaque-ish panel and fought
   the live camera underneath; record chrome reads better as a light translucent
   fill matching the shipped `RecordChromeTokens` panels (Black@0.40). The 0.88
   constant is dropped. `GlassResolverTest` asserts the RecordChrome fill alpha
   over the pinned base lands in `0.36..0.45` and stays opaque under
   Reduce-Transparency.

b. **The RecordChrome resolver scrim is `null`.** The record scrim is owned by
   the dock's `bottomNavBrush` (the edge-to-edge gradient, ADR-0011). A resolver
   scrim would double-stack over it. `GlassSurface(role=RecordChrome)` therefore
   contributes fill + edge only; the dock keeps its brush.

c. **Pinned routes render on a dedicated `NeutralDarkRecordPalette` base.** A
   light active theme (Daylight) must never leak a light `glassTint`/`edge`/
   `isLight` into a route painted over the camera. The pure
   `PinnedGlassEnvironment.forPinnedRoute(env)` swaps the env palette to the
   shared neutral-dark base and copies ONLY the active palette's
   `accentOnDark` / `accentContainerOnDark`. It is seeded via
   `CompositionLocalProvider(LocalGlassEnvironment)` inside `RovaDarkSurface` on
   the record / player / onboarding routes.

d. **Record personality = accent on the selected mode only; record/stop locked
   `rec`.** Theme accent reaches the Record home solely through the
   `ModeCycleChip` (the selected-mode affordance), rendered exactly as the mockup
   `.lpill span.on`: a **solid `accent → accent2` gradient** fill + **white bold
   label**. Using the two-stop gradient (not a flat single accent) is what keeps
   the themes distinct on this chip — Aurora's blue→violet vs Eclipse's
   blue→periwinkle, Tide's teal→cyan vs Jade's emerald→deep-green; a flat
   `accentOnDark` collapsed those to look identical. Because both stops are
   needed, `PinnedGlassEnvironment.forPinnedRoute` carries `accent`/`accent2`
   through to the pinned route (alongside `accentOnDark`/`accentContainerOnDark`).

   **WCAG exception (owner-signed 2026-06-08):** white-on-bright-accent on this
   chip measures ~1.5–3.5:1 — below the ADR-0020 "AA by default" bar. This is the
   single explicit, owner-approved exception, scoped to this one decorative
   selected-state control (the mode is also conveyed by position and by the
   dual-preview zone tags, so the color is not the sole information channel). All
   other text on the Record home remains AA. `RecordAccentContrastTest` therefore
   does NOT assert the (waived) white-on-accent ratio; it instead guards the
   reported regression — every palette is a real two-stop gradient and no two
   themes share an identical `(accent, accent2)` pair.

   The active HUD has no non-semantic progress element, so it carries no accent
   (the mockup's `.m-seg` accent-gradient clip-progress dots are a candidate
   follow-up, not built in PR2). The Start/Stop FAB and recording dot stay the
   locked `rec #ff4d4d` in every theme. `checkRecordSurfaceNoBlur` +
   `checkGlassSurfaceRoleUsage` stay green (RecordChrome resolves blurRadius=0).

   The mockup's edge-hugging landscape *re-layout* and the segmented in-sheet
   mode picker are NOT part of PR2 (candidate PR2b); PR2 themes the existing
   portrait/landscape/P+L layouts.
