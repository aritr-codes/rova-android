# Liquid Glass Theme Engine — Slice 1 (palette propagation) — Design

Date: 2026-06-18
Status: Approved (owner, brainstorming) — pending spec review → writing-plans
Relates to: ADR-0028 (liquid-glass design system), ADR-0020 (WCAG 2.2 AA), ADR-0022
(strings in resources), ADR-0031 (icon/glyph system — its color seam is the precedent)

## Problem

The 12-palette theme system is half-wired. `MainActivity` resolves the active
`RovaPalette` from the persisted `ThemeSelection` and passes it to `RovaTheme`
([MainActivity.kt:58-78]), but `RovaTheme` only seeds the palette into
`LocalGlassEnvironment`; **`MaterialTheme.colorScheme` is still built from a single
frozen `DarkColorScheme`/`LightColorScheme`** ([Theme.kt:107-138]). Per ADR-0028
§Consequences this was deliberate for PR1: only App Settings + Library + Record-home
render through `GlassSurface`/palette; every other surface keeps the static scheme
"until its own migration PR".

Net effect today: a theme swap restyles ~3 surfaces. All six dark palettes look
**identical** on History, Player, onboarding, warnings, sheets, and dialogs because
those read `MaterialTheme.colorScheme.*` off the one shared `DarkColorScheme`. The
12 palettes carry no app-wide weight.

This slice builds the **propagation engine**: the active palette drives color on
every surface, so picking Jade vs Dusk visibly restyles the whole app.

## Owner-ratified decisions (brainstorming 2026-06-18)

1. **Slice-1 spine = palette propagation engine** (not breadth-first picker, not icon depth).
2. **Hybrid architecture**: derive `MaterialTheme.colorScheme` from the palette
   app-wide, AND keep the locked-contract seams (`RovaSemantics`, `CaptionScrim`,
   `LibraryColorSpec`) authoritative for semantic + over-media colors.
3. **Picker = all 12 + swatch grid** (drop `wave1Picker`, premium swatch tiles).
4. **Enforcement = a single-color-scheme-source preBuild gate** + extended JVM contrast test.

## Architecture

### Unit 1 — `PaletteColorScheme` (pure mapper, the spine)

New file `app/src/main/java/com/aritr/rova/ui/theme/PaletteColorScheme.kt`.
Framework-free pure object so it unit-tests under `isReturnDefaultValues = true`
(house pure-helper pattern, mirrors `GlassResolver`/`ThemeMigration`/`DialogActionColors`).

```
object PaletteColorScheme {
    fun from(palette: RovaPalette): ColorScheme
}
```

#### Construction: factory base + `.copy()` (NOT hand-built)

Material3's `ColorScheme` has **far more than the ~22 classic slots** — current M3
adds `surfaceBright`, `surfaceDim`, `surfaceContainerLowest/Low/Container/High/
Highest`, `scrim`, `surfaceTint`, and version-dependent fixed color roles. Calling the
`ColorScheme(...)` constructor directly requires supplying **every** parameter and
breaks on any M3 version bump that adds a slot (codex).

Therefore `from` builds on the pure factory:
```
val base = if (palette.isLight) lightColorScheme() else darkColorScheme()
return base.copy( /* identity + locked overrides below */ )
```
`lightColorScheme()`/`darkColorScheme()` are **pure, non-`@Composable`** factories
that fill every current slot (incl. the new surface-container ladder, scrim,
surfaceTint, fixed roles) with valid M3 defaults; `.copy()` overrides only the slots
Rova drives. New M3 slots we don't override keep a sane neutral default. (These two
factory calls live in `PaletteColorScheme.kt`, which the single-source gate allowlists
alongside `Theme.kt` — see Unit 5.)

`ColorScheme`/`Color` are value-like Compose classes; construction needs no
composition, so `from` runs under JVM unit tests (confirmed by codex; the first plan
test asserts it).

#### Mapping rules — overridden slots

Identity slots (retint per palette):

| Material slot            | Source                                                        |
|--------------------------|---------------------------------------------------------------|
| `primary`                | **resolved accent fill** from `DialogActionColors` (see below) — NOT raw `accent` |
| `onPrimary`              | the matching AA label from the SAME `DialogActionColors` resolution |
| `primaryContainer`       | `accentContainerOnDark` (accent @ ~0.22) composited over `surfaceBase` → opaque |
| `onPrimaryContainer`     | `palette.textHigh`                                            |
| `secondary`              | resolved `accent2` fill (same pairing rule)                  |
| `onSecondary`            | matching AA label for the resolved `secondary`              |
| `secondaryContainer`     | `accent2 @ ~0.22` composited over `surfaceBase`              |
| `onSecondaryContainer`   | `palette.textHigh`                                           |
| `tertiary`               | resolved `accent2` fill (Rova has a 2-stop accent, no 3rd hue — de-emphasis is by alpha per ADR-0028 §8, never a 3rd hue) |
| `onTertiary`             | matching AA label                                            |
| `tertiaryContainer`      | `accent2 @ ~0.22` over `surfaceBase`                         |
| `onTertiaryContainer`    | `palette.textHigh`                                           |
| `background`             | `palette.surfaceBase` (NEW field — see Unit 2)              |
| `onBackground`           | `palette.textHigh`                                          |
| `surface`               | `palette.surfaceBase`                                        |
| `onSurface`             | `palette.textHigh`                                          |
| `surfaceVariant`        | a **subdued neutral** step off `surfaceBase` — NOT accent-tinted |
| `onSurfaceVariant`      | `palette.textDim`                                           |
| `surfaceDim`/`surfaceBright` + `surfaceContainerLowest/Low/Container/High/Highest` | a **neutral monotonic opaque ladder** around `surfaceBase` via `surfaceLadder(surfaceBase, isLight)` (cards/sheets/menus/app-bars use these — they must read as neutral elevation, never accent) |
| `outline`               | `palette.edge` promoted to an opaque visible tone over `surfaceBase` (Material outline is opaque; `edge` is ~0x1A alpha) |
| `outlineVariant`        | `palette.edge` promoted lower (hairline, opaque)            |
| `surfaceTint`           | `primary` (resolved accent) — standard M3 elevation tint    |
| `inverseSurface`        | derived opposite-polarity of `surfaceBase`                  |
| `inverseOnSurface`      | derived opposite-polarity of `textHigh`                     |
| `inversePrimary`        | `palette.accent` (lightened toward container)              |
| fixed color roles (`primaryFixed*` etc., if present) | left at factory default (low blast radius; Rova UI does not use the fixed roles) |

Locked slots (NEVER palette-derived — sourced from `RovaSemantics`, the
identity-vs-locked contract, exactly like `LibraryColorSpec`):

| Material slot     | Source                          |
|-------------------|---------------------------------|
| `error`           | `RovaSemantics.error` (#EF4444) |
| `onError`         | near-black (white-on-#EF4444 is ~3.95:1 < AA; black is ~5.0:1) |
| `errorContainer`  | M3 factory default — the locked `RovaSemantics.error` must NOT be mutated (`.copy`) per ADR-0031 §3 / `checkStatusColorLocked` |
| `onErrorContainer`| M3 factory default (pairs with the factory errorContainer) |
| `scrim`           | locked `Color.Black` (not palette identity) |

Light vs dark polarity comes from `palette.isLight` (only Daylight is light) — it
selects the factory base AND the ladder direction. `RovaTheme`'s `darkTheme` parameter
is derived from `!palette.isLight` (already the case in `MainActivity`).

**`primary`/`onPrimary` consistency (codex bug fix).** `DialogActionColors` may
**deepen** the accent fill within a budget to let a white label clear AA. If `primary
= raw accent` but `onPrimary = label-for-the-deepened-fill`, a Material `Button`
(which paints raw `primary` under `onPrimary`) can fail AA. So the mapper resolves the
`(fill, onColor)` pair **together** via `DialogActionColors.resolve(start, end): Cta`.
That helper takes a gradient pair in sRGB `IntArray`s and returns the (possibly
deepened) endpoints + `contentWhite`. For a SOLID Material slot, call it with the
accent as **both** endpoints — `resolve(accentRgb, accentRgb)` — then assign
`primary = Color(cta.start)` and `onPrimary = if (cta.contentWhite) White else
near-black`. Same for `secondary`/`tertiary` over `accent2`. (The mapper does the
`Color`↔`IntArray` 0..255 conversion at the boundary.) `primary` may therefore differ
slightly from raw `accent` (within the existing deepen budget). Surfaces needing the **raw two-stop accent gradient** (e.g. the Record
`ModeCycleChip`, Library accents) already read `palette.accent`/`accent2` **directly**
from `LocalGlassEnvironment`, not `colorScheme.primary`, so this resolution does not
flatten their gradient. The pairing rule is JVM-tested per palette.

#### `surfaceLadder` (pure helper)

`surfaceLadder(base: Color, isLight: Boolean): SurfaceSteps` — returns the 7
opaque neutral surface-container values as monotonic luminance steps off `base` (raise
for dark palettes, lower for Daylight). Pure, JVM-tested for monotonicity + opacity.
Keeps the container family neutral (codex: do NOT collapse them onto one `surfaceBase`
or accent-tint them). A small palette-neutral tint toward `surfaceBase` is allowed so
Jade/Aurora sheets differ subtly, but hue stays neutral.

#### `compositeOver` (pure helper)

Container/surface/outline rules composite a translucent palette color over the solid
`surfaceBase`. A pure `compositeOver(fg: Color, bg: Color): Color` (alpha-blend; or
reuse Compose's `Color.compositeOver`) yields the opaque result Material slots expect.
Pure, JVM-testable.

### Unit 2 — `RovaPalette.surfaceBase` (new field)

`MaterialTheme.background`/`surface` are flat `Color`s, but `palette.background` is a
`Brush` (gradient). Add one field:

```
@Immutable data class RovaPalette(
    ...
    val surfaceBase: Color,   // NEW — flat base under the gradient; Material bg/surface source
    ...
)
```

Values (mechanical, one per palette): dark palettes = the gradient's **bottom
(darker) stop**; Daylight = its light bottom stop `#F4F1EA`; Eclipse = `#000000`;
`NeutralDarkRecordPalette` = `#05070B`. The `darkPalette(...)` factory takes `bgBottom`
already, so `surfaceBase = Color(bgBottom)` falls out for the 10 factory palettes;
Eclipse/Daylight/NeutralDark are hand-built and get the field added inline.

This field is additive — no existing reader breaks; `GlassSurface` and explicit
gradient call sites keep using `palette.background` (the Brush).

### Unit 3 — `RovaTheme` + `RovaDarkSurface` wiring

`RovaTheme` ([Theme.kt:88-140]):
```
val colorScheme = PaletteColorScheme.from(palette)   // was: if (darkTheme) DarkColorScheme else LightColorScheme
```
The `darkTheme` parameter stays for status-bar polarity (`lightStatusBarIcons`) but no
longer selects the scheme. `LocalGlassEnvironment` seeding unchanged.

`RovaDarkSurface` ([Theme.kt:152-158]) — pinned camera/media routes (Record/Player/
Onboarding) stay cinematic neutral-dark per ADR-0028 §2.4, but pick up theme
personality through accent only:
```
fun RovaDarkSurface(content) {
    val env = LocalGlassEnvironment.current            // already swapped to pinned palette by PinnedGlassEnvironment
    val scheme = PaletteColorScheme.from(env.palette)  // neutral-dark base + active accentOnDark
    MaterialTheme(colorScheme = scheme, typography = Typography, content = content)
}
```
Because `PinnedGlassEnvironment.forPinnedRoute` already swaps the env palette to
`NeutralDarkRecordPalette` carrying the active `accentOnDark`/`accent`/`accent2`,
feeding that palette through the same mapper yields a neutral-dark scheme whose
`primary` = the active theme accent. Surfaces stay dark; accent controls pick up the
theme. (Previously `RovaDarkSurface` hardcoded `DarkColorScheme` with `primary =
InfraBlue` regardless of theme.)

The static `DarkColorScheme`/`LightColorScheme` vals in `Theme.kt` are **deleted**
(no remaining reader) unless a preview/edit-mode fallback needs them — if so, they
stay in `Theme.kt` only (the gate allowlists that file).

### Unit 4 — 12-swatch picker

Replace the text radio sheet (`SettingsScreen.kt` ~740-770) with a swatch grid:
- One tile per `ThemeSelection` (Follow-System + all 12). `wave1Picker` dropped;
  iterate `ThemeSelection.entries` (or a new `allPicker` list with Follow-System first).
- Tile = the palette's `background` gradient as the tile fill + an accent
  `accent→accent2` gradient swatch chip + the localized name; selected tile = accent
  ring + check. Follow-System tile shows a split light/dark preview.
- Tap = `settingsViewModel.themeSelection.value = it` (live restyle, no Activity
  recreate — already how the current sheet works).
- Built with `GlassSurface(role = Card/Dialog)` so the sheet itself is themed.

New strings (en + es), 6 Wave-2 names: `settings_theme_selection_blossom`,
`_coral`, `_meadow`, `_cobalt`, `_orchid`, `_graphite`. The
`themeSelectionLabel`/sheet `optionLabel` `when` blocks extend to all 12 (exhaustive).

Tile name contrast: the label sits on the tile's own palette surface → assert AA via
the contrast test (the tile reuses the palette's `textHigh`, already AA over its own
`surfaceBase`).

### Unit 5 — Enforcement

**New gate `checkSingleColorSchemeSource`** (46th gate; ADR-0028 amendment clause →
`check*` → `preBuild`, per house convention):
- Invariant: the Material color scheme is constructed in exactly one place — the two
  engine files. Forbid `darkColorScheme(`, `lightColorScheme(`, and `MaterialTheme(`
  with a `colorScheme =` argument **outside the allowlist `{Theme.kt,
  PaletteColorScheme.kt}`**. (`MaterialTheme(` without an explicit `colorScheme`
  inherits the ambient scheme and is allowed everywhere.) `PaletteColorScheme.kt` is
  allowlisted because the factory base lives there (codex).
- **Robust scan (codex — the naive regex is brittle):**
  1. Strip BOTH line/block comments AND string literals from the file text first
     (a `colorScheme =` inside a comment or string must not match).
  2. `darkColorScheme(`/`lightColorScheme(` → simple token match on the stripped text:
     `Regex("\\b(dark|light)ColorScheme\\s*\\(")`.
  3. `MaterialTheme(...colorScheme=...)` → **do NOT** use `MaterialTheme\s*\([^)]*colorScheme\s*=`
     — it false-negatives on nested parens before the arg
     (`MaterialTheme(typography = Typography.copy(...), colorScheme = x)`) and can
     over-match across calls. Instead: find each `MaterialTheme(` token, **scan
     balanced parentheses** to the matching close, and test only that call's argument
     slice for `\bcolorScheme\s*=`.
  Allowlist by `canonicalFile`. A `// colorscheme-source-opt-out` hatch mirrors the
  other gates' escape comment.
- "Prove-it-bites" transient steps in the plan (BOTH must bite): (i) add a stray
  `darkColorScheme(` in a non-engine file → gate FAILS → revert; (ii) add a
  `MaterialTheme(typography = X.copy(...), colorScheme = y)` (nested parens before the
  arg) in a non-engine file → gate FAILS → revert (proves the balanced-paren scanner,
  not the brittle regex, is in force).

**Extend `ThemeContrastTest`** (JVM): for each of the 12 palettes, build
`PaletteColorScheme.from(palette)` and assert AA on **every `onX` against its `X`
fill** (codex — not just body text):
- `onBackground`/`background`, `onSurface`/`surface`, `onSurfaceVariant`/
  `surfaceVariant`, and `onSurface` against **each rung** of the surfaceContainer
  ladder (`surfaceDim`…`surfaceContainerHighest`) ≥ 4.5:1,
- `onPrimary`/`primary`, `onSecondary`/`secondary`, `onTertiary`/`tertiary`,
  `onPrimaryContainer`/`primaryContainer` (+ secondary/tertiary containers) ≥ 4.5:1,
- `onErrorContainer`/`errorContainer` ≥ 4.5:1; `onError`/`error` ≥ 4.5:1,
- selected-control/outline (`primary` on `surface`, `outline` on `surface`) ≥ 3:1,
- incl. the API<31 solid fallback and Eclipse OLED surface (existing test structure).
Reuses `ContrastMath` (ADR-0020).

**New `PaletteColorSchemeTest`** (JVM):
- purity: `from` returns deterministic schemes; same palette → equal scheme.
- locked-slot guard: `from(p).error == RovaSemantics.error` AND `from(p).scrim ==
  Color.Black` for all 12 (palette never leaks into the locked slots).
- opacity: every container/surface/outline/surfaceContainer* slot is fully opaque
  (alpha == 1f) — Material expects opaque slots; `compositeOver` must not leave alpha < 1.
- ladder monotonicity: the surfaceContainer ladder steps move monotonically in
  luminance off `surfaceBase` (raise for dark, lower for Daylight) and stay neutral
  (no accent hue bleed).
- `primary`/`onPrimary` pairing: `onPrimary` is the AA label for the assigned
  `primary` fill (the codex consistency bug — test that the pair, not raw accent, is AA).

**New `SingleColorSchemeSourceGateTest`** (JVM, optional but recommended): feed the
gate's scanner a string containing a nested-paren `MaterialTheme(typography =
X.copy(...), colorScheme = y)` and assert it is flagged (proves the balanced-paren
scanner over the brittle regex). Mirrors the "prove-it-bites" step but as a permanent
guard.

## Scope boundary

In slice 1:
- `PaletteColorScheme` mapper + `surfaceBase` field + `RovaTheme`/`RovaDarkSurface` wiring.
- 12-swatch picker + 6 strings (en/es).
- `checkSingleColorSchemeSource` gate + extended `ThemeContrastTest` + `PaletteColorSchemeTest`.
- Device smoke of every app surface for regressions (the whole point — verify no
  contrast/readability break when each palette drives the full app).
- ADR-0028 amendment documenting the propagation flip.

Deferred (NOT slice 1):
- Icon glass-chip active-state containers (FILL 0→1) + animated icon states (Processing
  arc, Merging) — ADR-0031 P2.
- Deep per-surface color seams (à la `LibraryColors`) — only built where the
  ColorScheme mapping proves too coarse for a surface during device smoke; filed as
  targeted follow-ups, not blockers.
- Additional per-route palette overrides beyond the existing pinned-dark path
  (`PinnedGlassEnvironment` already covers Record/Player/Onboarding).

## ADR-0028 amendment (carried in this spec, applied in the plan)

§Consequences (PR1) said only App Settings renders through the palette; every other
surface keeps `DarkColorScheme`/`LightColorScheme` until per-surface migration. This
engine **supersedes that**: the active palette derives `MaterialTheme.colorScheme`
app-wide via `PaletteColorScheme.from`, so all surfaces restyle from one layer. The
locked-semantic and over-media contracts (§1.3, `RovaSemantics`/over-media seams) are
unchanged — those slots are sourced from the locked seams inside the mapper, never
from the palette. Pinned camera/media routes (§2.4) stay neutral-dark; theme reaches
them only through the accent slot. New gate `checkSingleColorSchemeSource` enforces
the single construction site.

## Risks

- **Blast radius**: every unmigrated surface restyles at once. Mitigation: the
  extended `ThemeContrastTest` proves AA on the derived slots across 12 palettes
  before any device build; device smoke walks every surface. Light palette (Daylight)
  path already existed (`LightColorScheme`), so light/dark polarity is not new — only
  per-palette accent/bg variation is.
- **Slot derivation ambiguity**: 13 fields → ~30 slots requires invented rules for
  container/inverse slots. Mitigation: rules are explicit, pure, and tested; container
  slots are low-salience (de-emphasis by alpha per ADR-0028 §8) so small inaccuracies
  are cosmetic, not AA-breaking.
- **Surfaces hardcoding `.copy(alpha=)` on `colorScheme` colors assuming the old
  Midnight hex**: these now compute against a per-palette base. Device smoke is the
  catch; any genuine break becomes a targeted per-surface seam follow-up.
- **`ColorScheme` construction in a pure object**: confirmed safe (codex) — built from
  the pure `darkColorScheme()/lightColorScheme()` factory + `.copy()`, no composition.
  The plan's first test asserts it runs under JVM.

## Codex review (folded in)

Codex reviewed the propagation architecture + gate regex. Verdict: sound engine shape,
required tightening — all folded into this spec:
1. **surfaceContainer family must be a neutral tonal ladder**, not accent-tinted / not
   collapsed onto one base → added `surfaceLadder` helper + ladder rules + tests.
2. **M3 has many more slots than the classic ~22** (surfaceBright/Dim/Container*,
   scrim, surfaceTint, fixed roles) and the `ColorScheme(...)` constructor requires all
   of them → build from the `darkColorScheme()/lightColorScheme()` factory base +
   `.copy()`; future slots keep valid defaults.
3. **`primary`/`onPrimary` AA bug**: deepened CTA fill vs raw `primary` → resolve the
   `(fill, label)` pair together and assign the resolved fill to `primary`.
4. **Gate regex brittle** (nested parens, strings) → balanced-paren scan + strip
   strings AND comments; allowlist `PaletteColorScheme.kt` too; two prove-it-bites
   steps incl. the nested-paren case.
5. **`scrim` is locked black**, `surfaceTint = primary`; fixed roles left at default.
6. Test EVERY `onX`/`X` pair, ladder rungs, locked slots, opacity, pairing.
