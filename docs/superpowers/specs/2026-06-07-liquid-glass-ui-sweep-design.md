# Liquid Glass UI Sweep — Design Spec

> **Status:** Draft for owner review (2026-06-07). Foundation-first program spec.
> **Type:** Program (multi-PR). PR1 = design-system foundation; subsequent PRs migrate one surface each.
> **Supersedes scope of:** the original "preset UI polish" slice (folded in as one migrated surface).

## Goal

Move Rova's entire UI to a cohesive 2027–28 **liquid-glass** design language with a **12-theme personality system**, refined iconography, a defined motion + type scale, and WCAG 2.2 AA held in every theme — without regressing reliability, the existing static-gate invariants, or the shipped theme switcher.

## Architecture (one sentence each)

- **Themes** are a flat selection: `FOLLOW_SYSTEM` + 12 named palettes; each palette supplies only background + glass surface tint + accent gradient + text alphas; **semantic colors are locked** (identical across all 12).
- **Glass** is **role-aware**: a high-in-the-tree `LocalGlassEnvironment` carries `{palette, apiLevel, reduceTransparency}`; call sites resolve a concrete material via `GlassResolver.resolve(env, role)` and render through a `GlassSurface(role=…)` wrapper.
- **Degradation** is first-class: API < 31 (no `RenderEffect`) and the record-surface no-blur rule and the OS *Reduce Transparency* setting all resolve to a heavier solid tint with no blur.

This document references the approved mockups in repo root (`rova_design_system.html`, `rova_design_system_round2.html`, `rova_design_system_round3.html`, `rova_icon_system.html`, `preset_theme_gallery.html`) — these are gitignored visual references; measurements that matter are inlined here.

---

## 1. Theme system

### 1.1 Selection model (codex-reconciled: flat-12 + Follow System)

```kotlin
enum class ThemeSelection {
    FOLLOW_SYSTEM,
    AURORA, TIDE, JADE, DUSK, ECLIPSE, DAYLIGHT,   // Wave 1 (Signature 6)
    BLOSSOM, CORAL, MEADOW, COBALT, ORCHID, GRAPHITE // Wave 2 (Extended 6)
}
```

- **Default:** `AURORA`. **Follow system:** maps OS light/dark to a palette pair — `DAYLIGHT` when OS=light, `AURORA` when OS=dark. The picker row reads "Follow system" with subtext "Daylight / Aurora".
- **No** `System-light` / `System-dark` entries — those are runtime states, not user choices.
- **Wave gating:** all 12 defined in the enum from PR1; the picker exposes only Wave 1 initially. A later PR unlocks Wave 2 (one-line picker change; no new enum work).

### 1.2 Migration from shipped `ThemeMode` (#80)

`RovaSettings` currently persists `theme_mode ∈ {LIGHT, DARK, SYSTEM}` in synchronous SharedPreferences. Migration is **pure + deterministic**, executed in the synchronous read path **before** the theme StateFlow initializes (no async DataStore — avoids first-frame default flash):

| Old `theme_mode` | New `ThemeSelection` |
|---|---|
| `SYSTEM` | `FOLLOW_SYSTEM` |
| `DARK` | `AURORA` |
| `LIGHT` | `DAYLIGHT` |
| missing / unknown | `FOLLOW_SYSTEM` |

Write the new key (`theme_selection`); leave the old key untouched for one release (rollback safety). A pure `ThemeMigration.migrate(oldValue): ThemeSelection` helper with JVM tests.

### 1.3 Palette tokens

Each palette is an `@Immutable` data object:

```kotlin
@Immutable data class RovaPalette(
  val id: ThemeSelection,
  val background: Brush,           // page/scene fill
  val glassTint: Color,            // base surface tint (before role/fallback adjustment)
  val edge: Color, val edgeTop: Color,
  val accent: Color, val accent2: Color,   // gradient stops
  val textHigh: Color, val textDim: Color, val textFaint: Color,
  val isLight: Boolean             // drives status-bar icon polarity, etc.
)
```

**Locked semantic colors** live OUTSIDE the palette in a single `RovaSemantics` object, identical for all themes: `success #34d399`, `warning #fbbf24`, `error #ef4444`, `escalating #f97316`, `rec #ff4d4d`. (Sourced from the existing warnings v2/v3 token set — `07b/07c`.)

Eclipse (pure-black/OLED) gets **special edge tokens**: because `#000` hides elevation, its `edge`/`edgeTop` carry the depth that shadow normally would.

Dynamic color / Material You stays **OFF** — it conflicts with fixed named palettes and locked semantics.

---

## 2. Glass material

### 2.1 Role-aware resolution (codex blocker fix)

Do **not** model glass as a single tree-wide resolved surface keyed on `isRecordSurface` — the settings/warning sheets are mounted *inside* `RecordScreen`, so a tree-wide record flag would wrongly strip their blur.

```kotlin
@Immutable data class GlassEnvironment(
  val palette: RovaPalette, val apiLevel: Int, val reduceTransparency: Boolean
)
enum class GlassRole { RecordChrome, BottomSheet, Dialog, Card, NavBar, Banner }

@Immutable data class GlassMaterial(
  val fill: Color, val blurRadius: Dp, val edge: Color, val edgeTop: Color, val scrim: Brush?
)

object GlassResolver {              // pure → JVM-testable
  fun resolve(env: GlassEnvironment, role: GlassRole): GlassMaterial
}
```

- `LocalGlassEnvironment` is seeded **once** high in `RovaTheme` (stable; recomposes only on theme / reduce-transparency / config change — all rare). Tokens are `@Immutable`.
- Call sites use `GlassSurface(role = GlassRole.BottomSheet) { … }` which reads the local env, resolves the material, and applies fill/edge/blur/scrim.

### 2.2 Effective-surface rules (the resolver's logic)

`blurRadius > 0` **only when** `apiLevel >= 31` **and** `!reduceTransparency` **and** `role != RecordChrome`. Otherwise blur = 0 and `fill` is bumped to a heavier opaque-enough tint (≥ ~0.82 alpha, or fully solid for `reduceTransparency`).

| Condition | Result |
|---|---|
| API ≥ 31, not reduced, non-record role | translucent fill + real blur + edge highlight |
| API < 31 (any role) | heavier translucent/solid fill, **no blur**, edge highlight |
| `RecordChrome` (any API) | heavier fill + **gradient scrim** + edge, **no blur**, opaque capsule behind critical text |
| Reduce Transparency ON | fully solid high-contrast surface, no blur, no scrim shimmer |

### 2.3 Blur honesty + DualPreviewZone carve-out

`Modifier.blur` blurs the *composable's own content*, not the live camera behind a translucent panel — Compose has no CSS-`backdrop-filter` equivalent over a `SurfaceView`/CameraX preview. So **record-surface "glass" never attempts backdrop blur**; it sells depth with fill + scrim + edge + opaque text capsules.

The existing `RenderEffect.createBlurEffect` in `DualPreviewZone.kt` (blurring the *non-recorded* framing margins, not chrome) is an explicit **documented exception** in ADR-0028 — it is not "record chrome" and is preserved. The `checkRecordSurfaceNoBlur` gate must allowlist it.

### 2.4 Pinned-dark contract for camera/media routes (interaction with #80)

**Rule (me + codex):** Record, Player, and Onboarding stay **pinned dark in every theme** — they are content-first surfaces (live camera / video / branded full-bleed) where light chrome over unpredictable luminance fails more than it delights, and dark chrome is the universal camera/video convention.

- Pinned routes render on **one shared cinematic neutral-dark base** (not 12 per-palette media variants — avoids QA sprawl and keeps the camera/player feel consistent).
- **Personality still shows via accent only.** The active palette's accent is applied to *meaningful controls* on pinned routes — record button, selected mode pill, scrubber/timeline, progress, focus/active states — through a **contrast-checked `accentOnDark` token** (a darker/saturated companion derived per palette so pale accents like Daylight's stay legible on dark). Accent is NOT sprayed across the whole surface.
- App surfaces (Library, Settings, History, warnings, sheets, dialogs) carry the **full** active palette (light or dark).
- `MainActivity` resolves theme above `RovaTheme`; `MainScreen` pins via `RovaDarkSurface`. The `LocalGlassEnvironment` for pinned routes is seeded with the neutral-dark base + the active palette's `accentOnDark`. The route-aware system-bar writer (`isPinnedDarkRoute`) flips icon polarity predictably on every transition (e.g. light Library → dark Player → light icons immediately).

Add to `RovaPalette`: `accentOnDark: Color`, `accentContainerOnDark: Color` (contrast-validated against the shared neutral-dark base) so pinned-route accent usage is token-driven, not raw palette accent reused at full strength.

---

## 3. Type, motion, icons, launcher

### 3.1 Typography
Inter ramp mapped to Compose `Typography` roles: Display 34/800/-0.5 → Headline 26/700 → Title 20/600 → Subtitle 17/600 → Body-L 15/400 → Body 13/400 → Label 12/600 → Caption 10/600/+1.4 caps. Hierarchy by weight+size; de-emphasis by **alpha**, never a second hue. Counters/timers use `FontFeature` `tnum`. All `sp`, no hard heights (dynamic type).

### 3.2 Motion
Spring container transitions `stiffness≈380, damping≈30`; chip/toggle 120ms standard-decelerate + shape-morph; dock shrink-on-scroll 200ms; record/glow pulse 1.8–2.2s loop. **All gated by `ReducedMotion`** (existing seam) → instant cross-fade / static; enforced by `checkA11yAnimationGated`. No motion conveys meaning alone. Motion tokens centralized (`RovaMotion`).

### 3.3 Icons — **own PR, not PR1** (blast-radius)
A custom `ImageVector` set (24dp grid, 1.7 stroke, round joins; outline-default, accent-fill-active) replaces Material icons across ~36 glyphs. Decisions: Settings = sliders; Vault = shield+keyhole; Interval = loop/swap; Orientation = phone. Stable singleton `val`s; content descriptions stay string-resource driven (`checkNoHardcodedUiStrings`). Ships as **one dedicated PR** after the surface migrations stabilize.

### 3.4 Launcher icon + splash — **own PR, last**
Adaptive icon: aperture-ring + record-dot + loop-arc on the Aurora gradient (fg/bg layers for masks). **Cannot follow the in-app theme** — Android 13 themed icon is a single static monochrome asset. Splash via `core-splashscreen`: conservative/neutral on API 24–30 (Activity theme chosen before startup) to avoid first-frame mismatch.

---

## 4. Accessibility (ADR-0020 held per theme)

- **Per-theme contrast tests** assert, for each of the 12 palettes × semantic **roles** × fallback modes (NOT every screen × theme): body text ≥ 4.5:1 and selected-chip/border ≥ 3:1 against that palette's own resolved surface — including the API<31 solid fallback and the Eclipse OLED surface. Extends `TokenContrastTest.kt` via `ContrastMath`.
- Reduce-transparency + reduced-motion fallbacks are part of the resolver and tested.
- Record-surface text contrast cannot be guaranteed by alpha over arbitrary camera footage → critical text sits on an opaque/near-opaque capsule; tests assert the capsule backing, not the camera.
- All new strings en + es.

---

## 5. New static gates + ADR

ADR-0028 "Liquid-glass design system" documents: theme model, glass role model, blur/fallback rules, DualPreviewZone carve-out, locked semantics, dynamic-color-off.

New gates (invariant → `check*` → `preBuild`):
- **`checkRecordSurfaceNoBlur`** — narrowly scoped to record-chrome files (`RecordScreen.kt`, `RecordChrome.kt`, record chrome components): no `Modifier.blur`; allowlists `DualPreviewZone`. Invariant: "record glass uses `GlassRole.RecordChrome`, whose resolver returns blurRadius=0."
- **`checkGlassSurfaceRoleUsage`** — migrated glass surfaces render through `GlassSurface(role=…)`/`GlassResolver`; gate migrated files for forbidden direct `Surface(color=Color…)`, `.background(Color…)`, `.blur(…)`, with a token-file allowlist. (Reframed from a raw-color ban to avoid false positives on scrims/semantics/previews.)
- **Per-theme contrast tests** as above (JVM).

---

## 6. PR sequence

1. **PR1 — Foundation (no visual regression).** `ThemeSelection` + migration; `RovaPalette` ×12 + `RovaSemantics`; `GlassEnvironment`/`GlassResolver`/`GlassRole`/`GlassSurface`; `RovaMotion`; type scale; ADR-0028; new gates; per-theme contrast tests. **Proof surface = App Settings (non-record)** flipped to `GlassSurface` + the new theme picker exposing Wave 1. Record route untouched this PR (avoids camera + pinned-dark + no-blur all at once).
2. **PR2** — Record home (the no-blur/scrim path, landscape + P+L).
3. **PR3** — Settings/presets sheet (folds in the original preset polish: grouping, contrast, Edit-mode delete, a11y).
4. **PR4** — Library + empty state. **PR5** — Player (portrait + landscape). **PR6** — Warnings (glow-bloom). **PR7** — Onboarding. **PR8** — Notification + merge states. **PR9** — Recovery + recovery card. **PR10** — Video editor.
5. **PR11** — Icon set swap. **PR12** — Launcher icon + splash. **PR(unlock)** — Wave 2 themes in picker.

Each PR: lands its own JVM tests, holds all gates, device-smoke before merge.

---

## 7. Out of scope / deferred
- Two-axis (palette × mode) theming — rejected (combinatorial QA, doesn't match approved flat gallery).
- Backdrop blur over the live camera — impossible in Compose; not attempted.
- Per-theme launcher icons — impossible (static themed icon only).
- Behavioral/reliability changes — none; this is presentation-layer only.

## 8. Decisions (resolved with owner, 2026-06-07)
1. **Foundation proof surface = App Settings** (non-record), flipped to `GlassSurface` + theme picker (Wave 1) in PR1.
2. **Follow-system pair = Daylight (OS light) / Aurora (OS dark).**
3. **Camera/media routes (Record/Player/Onboarding) stay pinned dark in every theme** — shared neutral-dark base + per-palette `accentOnDark` on meaningful controls (see §2.4).
4. **Icon swap (PR11) and launcher/splash (PR12) are separate later PRs** after surfaces stabilize.
