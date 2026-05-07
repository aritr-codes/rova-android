# Rova — UI Design Tokens (post-redesign)

> **Status:** Phase 1.B + 1.C authored. Planning artifact. No production code modified by this doc.
> **Source of truth for the design system:** `mockups/new_uiux/PROJECT_CONTEXT.md` §"UI/UX Design Principles" + `mockups/new_uiux/*.html` (CSS in `<style>` blocks).
> **Existing implementation reference (read-only):** `app/src/main/java/com/aritr/rova/ui/theme/{Color,Type,Theme}.kt`.
> **Phase 2 is not yet started.** This document precedes any token implementation; it is the contract a Phase 2.1 PR must follow.

---

## 1. Scope

This document extracts and normalizes the design system implied by the new mockups and decides:
1. Which mockup tokens become **theme-level** Material 3 color/type/shape entries (consumed via `MaterialTheme.colorScheme.*` / `MaterialTheme.typography.*`).
2. Which stay **screen-local** style values (defined alongside the composable that uses them, not in the theme).
3. The typography reconciliation called out in Phase 1.C — drop the serif headlines now, retain the existing `NumericMonoLarge` / `NumericMonoMedium` from Slice 3.

Non-goals:
- No `*.kt` changes in this phase. A Phase 2.1 PR consumes this contract; the contract does not mutate code.
- No theming for light/dark dual-mode work (the mockups are dark-first; light theme stays as-is until a separate slice).
- No animation tokens (deferred to Phase 7 polish).

---

## 2. Token table

### 2.1 Color tokens

The mockups encode color in two orthogonal axes:
1. **Surface depth** — a stack of near-black backgrounds (`#06090f` page → `#000` phone → `#060d18` camera fill → frosted overlays on top).
2. **Semantic accent** — a small palette: primary blue, recording red, plus four warning severities.

Mapping to Material 3 `ColorScheme` slots is straightforward for the dark scheme. The light scheme is left untouched in this doc (the mockups are dark-only; producing a light variant is out of scope here).

| Mockup token | Hex / rgba | Role | Material 3 slot (dark) | Screen-local fallback name |
|---|---|---|---|---|
| Page background | `#06090f` | Lowest surface; outside any card or sheet | `colorScheme.background` | — |
| Phone shell bg | `#000` / `#08090d` | n/a (mockup-only — production has no phone frame) | — | — |
| Camera viewfinder fill | `#060d18` | CameraX preview backdrop, behind the surface stack | — | `RovaTokens.cameraBackdrop` (camera composable only) |
| Frosted surface (sheet) | `rgba(9,13,20,0.97)` + `backdrop-filter: blur(24px) saturate(180%)` | Settings sheet, edit-sheet content | `colorScheme.surface` (use `tonalElevation = 8.dp`) | — |
| Frosted pill (status) | `rgba(0,0,0,0.38–0.45)` + `backdrop-filter: blur(20px) saturate(140%)` | Status pills, loop pills, REC/WAIT badges | — | `RovaTokens.statusPill` |
| Settings card on camera | `rgba(255,255,255,0.065)` + `backdrop-filter: blur(24px) saturate(180%)` | The 5-cell read-only summary card on Record idle | — | `RovaTokens.recordPlanCard` |
| Centered popup | `rgba(14,20,34,0.98)` + `blur(40px) saturate(160%)` | "View Settings" popup on Library | `colorScheme.surfaceContainerHigh` | — |
| **Primary accent** | `#5b7fff` | Export button, active tool, trim scrubber, primary CTA | `colorScheme.primary` | — |
| **Recording red** | `#ef4444` | Stop button, REC dot, aviation lights, hard-block warnings | `colorScheme.error` | — |
| **Warning yellow / trim** | `rgba(255,220,50,0.85)` (trim handles) / `#fbbf24` (warning soft) | Soft warnings (sev-y), trim mode badge | — | `RovaWarnings.soft` (see §2.5) |
| **Warning advisory blue** | `rgba(91,127,255,0.85)` (= primary at 85% alpha) | Advisory warnings (sev-b — "Stay in the Loop") | — | `RovaWarnings.advisory` |
| **Warning escalating orange** | `#f97316` | Thermal escalation, "Can't merge yet" 3-way | — | `RovaWarnings.escalating` |
| Border / outline (subtle) | `rgba(255,255,255,0.07–0.09)` | Pill borders, settings card border | `colorScheme.outlineVariant.copy(alpha = 0.35f)` | matches shipped `MainScreen.kt:127` |
| Text high-emphasis | `rgba(255,255,255,0.88–0.93)` | Headings, primary values | `colorScheme.onBackground` | — |
| Text medium-emphasis | `rgba(255,255,255,0.55–0.65)` | Body, secondary controls | `colorScheme.onSurfaceVariant` | — |
| Text low-emphasis | `rgba(255,255,255,0.38–0.45)` | Sub-labels, helper text | `colorScheme.onSurfaceVariant.copy(alpha = 0.45f)` | — |
| Text disabled / tertiary | `rgba(255,255,255,0.18–0.28)` | Eyebrow labels, disabled controls | M3 disabled token (38% alpha) | — |

**Pure white text is forbidden** outside of "active value at peak emphasis" — the mockups intentionally tint everything via alpha. The implementation must use `MaterialTheme.colorScheme.onBackground` (which is `#FFFFFF` at the M3 default for dark schemes) and apply alpha per the role above; never hard-code `Color.White`.

#### Existing color values — what changes vs what stays

`app/src/main/java/com/aritr/rova/ui/theme/Color.kt` ships:

| Existing token | Hex | Disposition |
|---|---|---|
| `Sand10`–`Sand90` (light surfaces) | `#FFFBF6`–`#C7B8A4` | Stays — light scheme is untouched in this phase |
| `Ink10`–`Ink95` (light text) | `#F5F7FA`–`#172533` | Stays — light scheme |
| `Harbor40` `#29516E` | (light primary today) | **Replaced** as primary in dark scheme by `#5b7fff`. Light scheme keeps Harbor as-is unless a separate slice migrates light too. |
| `Harbor90` `#D1E5F4` | (light primaryContainer) | Stays — light scheme |
| `Copper40` `#97582E` / `Copper90` `#F5DFC8` | (light secondary pair) | Stays — not used in mockups |
| `Sage40` `#4F6E62` / `Sage90` `#D2E8DE` | (light tertiary pair) | Stays — not used in mockups |
| `RecordingRed` `#C64133` | (rec accent — both schemes today) | **Replaced** by `#ef4444` for dark scheme. Light scheme decision deferred (`RecordingRed` is more legible on light). The Phase 2.1 PR may choose to keep `RecordingRed` for light + `#ef4444` for dark via two `ColorScheme` instances, or align both. |
| `RecordingRedContainer` `#FFDAD4` / `OnRecordingRedContainer` `#410704` | (light `errorContainer` pair) | Stays — light scheme |
| `Midnight` `#111922` | (dark background today) | **Replaced** by `#06090f` in dark scheme |
| `MidnightSurface` `#18212B` | (dark surface today) | **Adjusted** — closer to mockup `rgba(9,13,20,0.97)`. Concrete hex: `#0E1216` at full alpha (≈ the mockup color over `#06090f` at 0.97). |
| `MidnightSurfaceAlt` `#202B36` | (dark surfaceVariant today) | **Replaced** by a derived value in line with the mockup's frosted-pill stack |
| `MidnightOutline` `#627181` | (dark outline today) | Stays — close enough to mockup `rgba(255,255,255,0.18)` |

Phase 2.1 PR will translate the "Replaced" rows into concrete `ColorScheme` instances. Phase 1 only commits to the disposition.

### 2.2 Typography tokens

The mockups use **Inter exclusively** at four weights (`300`, `400`, `500`, `600`). The existing `Type.kt` mixes `FontFamily.Serif` (display / headline / titleLarge) and `FontFamily.SansSerif` (everything else). **Phase 1.C decision: drop the serif fields now**, in the same Phase 2.1 re-skin slice that lands the App Settings layout (no separate slice).

Replacement uses `FontFamily.SansSerif` as a temporary stand-in. Bundling Inter as a real font resource is **not** in this phase — `FontFamily.SansSerif` is the default sans on Android (Roboto), which is metrically close enough for v1.0 and avoids adding a font asset + license in a docs-only phase. A future polish slice may wire the actual Inter family.

| Existing `Typography` slot | Today | Phase 2 target |
|---|---|---|
| `displayMedium` | Serif Bold 40sp | SansSerif Bold 40sp (drop serif) |
| `headlineSmall` | Serif SemiBold 26sp | SansSerif SemiBold 26sp (drop serif) |
| `titleLarge` | Serif SemiBold 22sp | SansSerif SemiBold 22sp (drop serif) |
| `titleMedium` | SansSerif Bold 17sp | unchanged |
| `titleSmall` | SansSerif SemiBold 15sp | unchanged |
| `bodyLarge` / `bodyMedium` / `bodySmall` | SansSerif Normal | unchanged |
| `labelLarge` / `labelMedium` / `labelSmall` | SansSerif weighted | unchanged |
| `NumericMonoLarge` (top-level `TextStyle`) | Monospace Medium 64sp `tnum` | unchanged — Slice 3 already shipped this for the HUD timer |
| `NumericMonoMedium` (top-level `TextStyle`) | Monospace Medium 32sp `tnum` | unchanged — countdown |

Mockup-specific typography tokens that the Phase 2.1 PR introduces as **screen-local** (not theme-level):

| Token | Spec | Used in |
|---|---|---|
| `RovaTokens.eyebrow` | `font-size: 9sp` (8.5–10 px in mockup), weight `500`, letter-spacing `2sp`, ALL-CAPS | Page eyebrow on docs/static screens; small section labels |
| `RovaTokens.statusPillLabel` | `font-size: 11sp`, weight `500`, tabular-nums | REC / WAIT badges, loop counter pills |
| `RovaTokens.cellValue` | `font-size: 12sp`, weight `500`, tabular-nums, color = high-emphasis | The 5-cell record summary card values |
| `RovaTokens.cellKey` | `font-size: 8sp`, weight `400`, ALL-CAPS, color = low-emphasis | Cell label below the value |

Tabular figures (`fontFeatureSettings = "tnum"`) apply to every numeric value rendered in the UI — counters, timers, file sizes, dates that contain digits. Slice 3 set the precedent (`NumericMonoLarge`); Phase 2 extends it to the `cellValue` and any other numeric cell.

#### Why `FontFamily.SansSerif` and not bundled Inter

The mockups call out Inter explicitly. The reasons to defer the actual Inter font to a future slice:
1. Adding a font requires a `res/font/inter_*.ttf` asset, a `fontFamily` definition file, and license attribution. Each is a small but non-zero scope item.
2. Roboto (Android default sans on most OEM skins) is metrically close enough that Phase 2 visual review can land without it.
3. Bundling Inter on a docs-only foundation phase is exactly the kind of premature dependency the project's CLAUDE.md warns against.

The Phase 2.1 PR description should call out `// TODO: bundle Inter font asset` next to the `FontFamily.SansSerif` reference so a future polish slice can find it.

### 2.3 Shape / radius tokens

| Mockup element | Radius | Material 3 slot |
|---|---|---|
| Status pill | `20dp` | extra-small / dedicated `RovaTokens.pill` |
| Stepper button | `8dp` | small (matches M3 `Shapes.small`) |
| Cell / settings card | `14dp` | medium-small (between M3 `small` 4dp and `medium` 12dp — use `RoundedCornerShape(14.dp)` literal) |
| Popup | `18dp` | medium |
| Bottom sheet | `24dp 24dp 0 0` | dedicated `RovaTokens.sheetTop` (already implicit in M3 `ModalBottomSheet` defaults) |
| Bottom-nav surface | `32dp` | already shipped at `MainScreen.kt:120` |
| Phone frame | `52dp` | mockup-only — not in production |
| Cam control button | `50%` (circle, 30×30 px) | full circle — `CircleShape` |
| Primary action button | `50%` (circle, 64–72 dp) | `CircleShape` |

The decision: do **not** override `MaterialTheme.shapes` globally. Use M3 `Shapes` defaults for sheets and dialogs; introduce `RovaTokens.pill = RoundedCornerShape(999.dp)` as the canonical pill shape (already used at `MainScreen.kt:88` for the "Locked while recording" hint).

### 2.4 Spacing / sizing tokens

The mockups specify per-element spacing rather than a global step. Codifying them as named constants makes the Phase 2 PR self-checking (`RovaTokens.recordCardBottomInset` reads better than `bottom = 110.dp` literal).

| Token | Value | Used in |
|---|---|---|
| `RovaTokens.screenEdgeMargin` | `16.dp` | overlay elements from screen edges |
| `RovaTokens.recordCardBottomInset` | `110.dp` | record settings card distance above nav |
| `RovaTokens.camControlGap` | `7.dp` | vertical gap between flash + flip controls |
| `RovaTokens.camControlSize` | `30.dp` | flash + flip button diameter |
| `RovaTokens.stepperButtonSize` | `27.dp` | `−` / `+` button size on steppers |
| `RovaTokens.statusDotSize` | `6.dp` | REC / WAIT dot |
| `RovaTokens.settingsRowVerticalPadding` | `13.dp` | settings list rows |
| `RovaTokens.settingsRowDividerAlpha` | `0.046f` | rgba(255,255,255,0.046) divider |
| `RovaTokens.minHitTarget` | `48.dp` | every interactive control |
| `RovaTokens.primaryActionSize` | `64.dp` | center START button on bottom nav |
| `RovaTokens.stopActionSize` | `72.dp` | dominant Stop button on HUD (matches UI_ROADMAP §3 non-negotiable) |

Hit-targets use `Modifier.minimumInteractiveComponentSize()` from M3 — `RovaTokens.minHitTarget = 48.dp` is the **assertion**, not the rendering size.

### 2.5 Glass / frosted surface treatment

The mockups lean heavily on `backdrop-filter: blur(...) saturate(...)`. Compose has no direct equivalent; on Android the closest production-ready substitutes are:
1. `Modifier.blur(...)` — applies a Gaussian blur to the **underlying** content. Renderer support varies pre-API 31 (no-op on older devices). Performance is non-trivial during recording.
2. `RenderEffect.createBlurEffect` — same constraint, API 31+.
3. **Tonal elevation + low-alpha surface fill** (no blur) — the M3 idiomatic answer.

**Phase 2 decision: tonal elevation + low-alpha surface, no blur.** Reasons:
- Pre-31 fallback is mandatory (minSdk = 24) and a "blur on new devices, flat on old" looks inconsistent in screenshots / smoke tests.
- The HUD must not waste GPU budget during an active session; the recording service is the priority.
- M3 `Surface(tonalElevation = 8.dp, color = colorScheme.surface.copy(alpha = 0.92f))` matches the **visual** weight of `rgba(9,13,20,0.97)` + blur on a dark background. The shipped bottom-nav uses this approach (`MainScreen.kt:120-127`); the new screens will too.

The Phase 2.1 PR description must call out that any "frosted glass" on Phase 2 screens lands as **tonal elevation + alpha**, not real blur. If a future slice wants real blur for the camera-overlay-on-preview case (Record idle settings card on top of CameraX preview), that is a separate decision with its own performance bar.

| Mockup spec | Production substitute |
|---|---|
| `backdrop-filter: blur(24px) saturate(180%)` over surface | `Surface(tonalElevation = 8.dp, color = colorScheme.surface.copy(alpha = 0.92f))` |
| `backdrop-filter: blur(20px) saturate(140%)` over pill | `Surface(shape = RovaTokens.pill, tonalElevation = 4.dp, color = Color.Black.copy(alpha = 0.42f), border = BorderStroke(1.dp, colorScheme.outlineVariant.copy(alpha = 0.4f)))` |
| `backdrop-filter: blur(40px) saturate(160%)` over popup | M3 `AlertDialog` defaults + `tonalElevation = 12.dp` |

### 2.6 Elevation / shadow tokens

| Surface | Tonal elevation | Shadow elevation |
|---|---|---|
| Page background | 0 dp | 0 dp |
| Top app bar | 0 dp (surface flush) | 0 dp |
| Bottom nav floating pill | 8 dp (matches `MainScreen.kt:122`) | 10 dp (matches `MainScreen.kt:123`) |
| Edit sheet / settings sheet | 8 dp | M3 default for `ModalBottomSheet` |
| Library card | 1 dp | 2 dp |
| Recovery card (softened) | 2 dp | 2 dp + `error` left stripe (4 dp) |
| Floating popup | 12 dp | M3 default for `AlertDialog` |

### 2.7 Icon style

- **Source.** Material Symbols (filled), via `androidx.compose.material.icons.filled.*`. Already in use throughout the codebase (`MainScreen.kt:11-13`).
- **Stroke width.** N/A for filled icons.
- **Color.** Always inherits from the surrounding `LocalContentColor` — never hard-coded.
- **Size.** 24 dp for nav / list icons, 20 dp for inline icons (chevrons inside cells), 30 dp for camera controls (matches `RovaTokens.camControlSize`).
- **Aviation lights / decorative SVG** in mockups (camera scene rooftop lights, etc.) are mockup-only — production renders the live CameraX preview, not a procedural skyline.

### 2.8 Bottom nav treatment

Already shipped (`MainScreen.kt:118-179`). The token contract:
- Floating pill, `RoundedCornerShape(32.dp)`, `tonalElevation = 8.dp`, `shadowElevation = 10.dp`, `border = BorderStroke(1.dp, outlineVariant @ 35%)`, `color = surface @ 96%`.
- `NavigationBarItemDefaults.colors` per `MainScreen.kt:165-173`.
- **Disabled when** `serviceState.isPeriodicActive` is true. Visual dim from `enabled = false` on each item; `Modifier.semantics { disabled() }` on the parent for TalkBack.
- "Locked while recording" hint pill above the nav while disabled.

**Architectural reality check.** The current `MainScreen.kt` owns one outer `Scaffold` whose `bottomBar` slot wraps the `NavHost` (`MainScreen.kt:72-211`). Route composables run **inside** that NavHost; they do **not** own their own Scaffold and cannot hide the bottom nav by omitting a slot they never had. Hiding the bottom nav for `onboarding` and `player/{sessionId}` therefore requires a **MainScreen shell change** in the implementing slice.

Two acceptable implementation options — Phase 2.5 (Player) and Phase 2.6 (Onboarding) must explicitly choose one before implementation begins:

| Option | Sketch | Trade-off |
|---|---|---|
| **A. Conditional bottom-bar** | Keep one `MainScreen` `Scaffold`. Wrap the existing `bottomBar = { ... }` lambda in a guard — `if (currentRoute in topLevelRoutes) { /* nav pill */ }` — so the slot renders nothing on non-top-level routes. | Smallest diff; preserves the single-Scaffold structure shipped today. The bottom-bar slot still occupies the layout tree (with no visible content); insets behave per `Scaffold` semantics. |
| **B. Split shell** | Top-level tabs (`record`, `history`, `settings`) render inside a "tabs Scaffold" with the bottom-nav. Drill-down / fullscreen routes (`player/{sessionId}`, `onboarding`) render inside their **own** `Scaffold` with no `bottomBar`. The two scaffolds are siblings under `NavHost`, picked by the route's composable. | Cleaner separation; each route fully owns its chrome. Larger diff — moves the existing `bottomBar` block out of `MainScreen`'s top scaffold and re-introduces it under a per-route Scaffold for the three tabs. |

Whichever option lands first wins for the rest of v1.0. Do not adopt different shells for different new routes.

### 2.9 Camera / HUD overlay treatment

| Element | Token / spec |
|---|---|
| Camera viewfinder | CameraX `PreviewView` directly, no scrim |
| Top gradient fade | `Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.82f), 1f to Color.Transparent)` |
| Bottom gradient fade | `Brush.verticalGradient(0.6f to Color.Black.copy(alpha = 0.90f), 1f to Color.Transparent)` (reversed) |
| REC dot | `RovaTokens.statusDotSize` filled with `colorScheme.error`, `Modifier.shadow(0.dp, ambientColor = error)`, blink animation |
| WAIT dot | same shape, color = `colorScheme.onSurfaceVariant.copy(alpha = 0.6f)`, no blink |
| Stop button | `RovaTokens.stopActionSize`, `CircleShape`, `colorScheme.error` fill, `colorScheme.onError` icon |
| Session timer | `Type.NumericMonoLarge` (already shipped) |
| Countdown | `Type.NumericMonoMedium` (already shipped) |

### 2.10 Warning severity colors

The four severity levels from `07-warnings.html` (`sev-r`, `sev-y`, `sev-b`, `sev-o`) become semantic tokens consumed by the `WarningCenter` composables (Phase 4). They are **not** Material 3 `ColorScheme` slots — they are domain tokens in a `RovaWarnings` object. See `WarningCenterContract.md` §3 for the full taxonomy.

| Token | Hex | Severity bucket | M3 mapping |
|---|---|---|---|
| `RovaWarnings.hard` | `#ef4444` | Hard block, danger, recovery error, "Session ended" red banner | `colorScheme.error` (re-uses) |
| `RovaWarnings.soft` | `#fbbf24` | Soft warning, caution, non-blocking banner, 3-way confirmation | dedicated (no M3 slot is a great fit; `colorScheme.tertiary` is misleading) |
| `RovaWarnings.advisory` | `#5b7fff` | Advisory ("Stay in the Loop") | `colorScheme.primary` (re-uses) |
| `RovaWarnings.escalating` | `#f97316` | Escalating thermal, "Can't merge yet" 3-way choice | dedicated |

Severity tints are applied at **12% alpha for fills** (`rgba(R,G,B,0.12)`) and **85% alpha for foregrounds** (per the mockup's `.sev-r{background:rgba(239,68,68,0.12);color:rgba(239,68,68,0.85);}` pattern).

### 2.11 Accessibility constraints

These are token-scoped accessibility rules. Cross-screen accessibility lives in Phase 7.

| Constraint | Spec |
|---|---|
| Body text contrast | ≥ 4.5:1 against the surface it sits on (M3 dark default `onBackground` over `#06090f` clears this) |
| Graphic / non-text contrast | ≥ 3:1 (icons, dot indicators, severity stripes) |
| Touch target | ≥ 48 dp (`RovaTokens.minHitTarget`) — assert via `Modifier.minimumInteractiveComponentSize()` |
| Selection state cue | Always carries a non-color signal (✓ + container fill) — do not rely on color alone |
| Dynamic font scale | Layouts must hold at **100 / 130 / 150 / 200 %**. Tabular-nums (`tnum`) prevents the timer from jiggling under scale |
| TalkBack | Live regions throttled per the Slice 3 precedent (session timer announces per minute, not per second). Sheet dismiss contract (Done commits, Cancel/Back/Scrim discard) is read aloud as the same word in every sheet |
| Color blindness | Severity buckets use **shape / icon / text** in addition to color (e.g. recovery card has the icon + label + body in addition to the left stripe color) |

### 2.12 Material 3 mapping summary

| What becomes theme-level (`MaterialTheme.colorScheme.*` / `.typography.*` / `.shapes.*`) | What stays screen-local (`RovaTokens.*` / inline literals) |
|---|---|
| `primary` = `#5b7fff` | `RovaTokens.cameraBackdrop` |
| `error` = `#ef4444` | `RovaTokens.statusPill` |
| `background` = `#06090f` | `RovaTokens.recordPlanCard` |
| `surface` (and surface-container family) = derived from mockup | `RovaTokens.pill` (= `RoundedCornerShape(999.dp)`) |
| `onBackground` / `onSurface` text emphases via `.copy(alpha = ...)` | All sizing tokens (`recordCardBottomInset`, `camControlGap`, etc.) |
| `outlineVariant` ≈ `rgba(255,255,255,0.18)` | All warning severity tokens (`RovaWarnings.*`) |
| `Typography` slots (with serif → sans replacement) | `Type.NumericMonoLarge` / `Type.NumericMonoMedium` (already top-level) |
| `Shapes` defaults | `RovaTokens.eyebrow` / `cellValue` / `cellKey` text styles |
| | `RovaTokens.minHitTarget` (assertion constant) |

The reasoning: anything Compose components consume implicitly (`MaterialTheme.colorScheme.error` from a Text / Icon / Surface) belongs in the theme. Anything tied to a specific layout piece (the 5-cell record card, the camera-overlay backdrop, the warning severity bucket) stays local — putting it in `MaterialTheme` would invite over-application and pollute the M3 surface roles.

---

## 3. Implementation notes (for Phase 2.1)

A Phase 2.1 PR translating this contract into code should:

1. **Edit `Color.kt`** to add the new dark-scheme entries (`#06090f` background, `#5b7fff` primary, `#ef4444` error, derived surface family). Keep the existing `Sand*` / `Ink*` / `Harbor*` / `Copper*` / `Sage*` palette unchanged (light scheme stays).
2. **Edit `Type.kt`** to drop `FontFamily.Serif` from `displayMedium`, `headlineSmall`, `titleLarge`. Keep `NumericMonoLarge` / `NumericMonoMedium` exactly as shipped (Slice 3).
3. **Add a new file** `app/src/main/java/com/aritr/rova/ui/theme/RovaTokens.kt` containing screen-local tokens (`RovaTokens.pill`, `RovaTokens.eyebrow`, `RovaTokens.minHitTarget`, severity colors as `RovaWarnings.*`, etc.). One file is fine — they all share the `theme` package.
4. **Do not** introduce a font asset for Inter in this PR. Add the `// TODO: bundle Inter font asset` marker next to the `FontFamily.SansSerif` references that replaced serif.
5. **Do not** touch `Theme.kt` beyond the `colorScheme` / `typography` / `shapes` it already wires up (lines 70-95 today). The light scheme stays.
6. **Test gates.** `lintDebug` + `testDebugUnitTest` + `assembleRelease`. Lint baseline must not be expanded to mask new `Lint` warnings (e.g. unused color resources). Add a Compose preview for at least one screen using the new tokens (Settings, idle dock — pick one) and verify dark and light renderings.

The PR is **not** allowed to:
- Implement any new screen layout (that's Phase 2.1's other scope, but token-only changes ship first).
- Bundle a real Inter font.
- Modify `service/`, `data/`, or `RovaApp.kt`.
- Create a "design tokens" Gradle module — single-file `RovaTokens.kt` is enough for v1.0.

---

## 4. NO-GO list (token-specific)

1. **Do not introduce real `Modifier.blur` for frosted-glass effects in Phase 2.** Pre-API-31 fallback inconsistency + GPU cost during recording > visual fidelity. Tonal elevation + alpha is the v1.0 substitute. Re-evaluate post-v1.0 polish only if a measurable user-visible regression surfaces.
2. **Do not bundle Inter font in this phase.** The mockups call out Inter; production substitutes `FontFamily.SansSerif` until a future polish slice. Adding the font asset now is scope creep on a foundation pass.
3. **Do not migrate the light theme to match the new dark direction.** The mockups are dark-only. Light scheme decisions belong to a separate slice with its own design pass.
4. **Do not add severity tokens for warning states beyond the four in §2.10.** Adding a fifth severity bucket invalidates the `WarningCenterContract.md` model; new severities come back as Phase 1.B revisions, not a quick fix in `RovaTokens.kt`.
5. **Do not use raw hex literals (`Color(0xFFEF4444)`) in screen code.** Every color reaches the screen via either `MaterialTheme.colorScheme.*` or `RovaTokens.* / RovaWarnings.*`. The only exception is `Color.Transparent` for over-painting.
6. **Do not override `MaterialTheme.shapes` globally** beyond what `Theme.kt` already does. Pill / sheet-top / popup stay as inline `RoundedCornerShape(...)` at the call site or as `RovaTokens.*` constants.
7. **Do not introduce a separate light-scheme dark-scheme file split** (`Color.dark.kt` etc.). Single `Color.kt` is enough for v1.0; M3 `darkColorScheme(...)` + `lightColorScheme(...)` factories handle the duality.
8. **Do not normalize warning severity alpha values across the codebase.** The mockup's 12% fill / 85% foreground pattern is the contract; do not "round" to 10% / 80% or any other neat number.

---

## 5. Open questions

These should be resolved before the Phase 2.1 PR opens. Defer until then; do not block Phase 1.

1. **Light scheme red.** Keep `RecordingRed = #C64133` for the light scheme (more legible on light) or align both schemes on `#ef4444`? Recommendation: keep light as-is until a separate light-redesign slice opens. The mockups are dark-only; aligning the light scheme to match a dark-only mockup risks regressing legibility.
2. **`MidnightSurface` exact value.** The replacement value approximated `rgba(9,13,20,0.97)` over `#06090f` ≈ `#0E1216`. Confirm against an on-device screenshot before the Phase 2.1 PR commits the exact hex.
3. **Eyebrow style call sites.** Eyebrow labels appear all over the mockup pages. In production, only a few real screens will need them (Settings sections, possibly the player title row). List the call sites in the Phase 2.1 PR description so the token does not become decoration.
4. **Drill / Vlog / Custom mode tabs styling.** The mockup shows tabs at the top of `01-record-home.html`. Tokens: tab radius, selected-state color, font weight. Defer to Phase 2.1 — listed here so the token gap is visible.
5. **Inter font bundling timing.** Decide whether v1.0 ships with `FontFamily.SansSerif` (Roboto on most devices) or a real Inter family. The recommendation in §2.2 is to defer; revisit at v1.1 polish.

---

## 6. References

- Mockup design system source: `mockups/new_uiux/PROJECT_CONTEXT.md` §"UI/UX Design Principles" (lines 86–158).
- Mockup CSS: every `<style>` block in `mockups/new_uiux/*.html`. The 18-state warning palette lives in `07-warnings.html` lines 32–35 (`sev-r`, `sev-y`, `sev-b`, `sev-o`).
- Existing theme: `app/src/main/java/com/aritr/rova/ui/theme/Color.kt`, `Type.kt`, `Theme.kt`.
- Slice 3 typography precedent: `Type.kt:22-42` (`NumericMonoLarge`, `NumericMonoMedium`).
- Bottom-nav surface precedent: `MainScreen.kt:118-179`.
- Replan source: `NEW_UI_BACKEND_REPLAN.md` §5 Phase 1.B + 1.C.

---

*End of document. Phase 2.1 PR consumes this contract; token additions outside it must come back as a Phase 1 doc revision.*
