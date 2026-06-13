# Rova — UI Design Tokens (post-redesign)

> **Status:** All phases shipped and merged to master (`d54e051`). `RovaTokens.kt`, `RecordChromeTokens.kt`, `Type.kt`, `Font.kt` (Inter downloadable font), `SettingsSheetTokens.kt`, `RovaWarningsV3.kt`, and `RovaTokensPreview.kt` are live on master. The feature branch `feat/record-skin-phase-1-foundation` no longer exists as a branch; its work is on master. Phases 3+ (edge-to-edge, warning re-skin, notification re-skin) are fully implemented.
> **Source of truth for the design system:** `mockups/new_uiux/PROJECT_CONTEXT.md` §"UI/UX Design Principles" + `mockups/new_uiux/*.html` (CSS in `<style>` blocks). Note: `mockups/` is gitignored and not present in a fresh checkout. The mockup files are the **frozen design origin** — all derived values have been inlined into `RecordChromeTokens.kt` and `RovaTokens.kt`, which are the **live inline contracts** for any future token work.
> **Existing implementation reference:** `app/src/main/java/com/aritr/rova/ui/theme/{Color,Font,RecordChromeTokens,RovaTokens,RovaTokensPreview,RovaWarningsV3,SettingsSheetTokens,Theme,Type}.kt`.

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

The mockups use **Inter exclusively** at four weights (`300`, `400`, `500`, `600`). **Phase 1 Foundation (implemented)** delivers Inter via the **AndroidX Google Fonts downloadable provider** (`Font.kt`). Both the M3 `Typography` slots in `Type.kt` and all `RovaTokens` type styles use `Inter` directly — `FontFamily.SansSerif` is no longer a stand-in. The old "TODO: bundle Inter font asset" is **resolved**; Inter ships without bundled `.ttf` assets.

#### Decision note — Inter via AndroidX Google Fonts downloadable provider

| Aspect | Detail |
|---|---|
| **Delivery** | `Font.kt` declares `val Inter = FontFamily(...)` using `Font(GoogleFont("Inter"), provider)` at weights 300 / 400 / 500 / 600. No `.ttf` resource in `res/font/`. |
| **Rationale** | No bundled asset, no licence-asset scope, no APK size cost. |
| **Trade-off** | Requires Google Play services on device. On first cold launch the font is fetched asynchronously; Android falls back to `FontFamily.SansSerif` (Roboto) for approximately one frame until the download completes. |

#### M3 `Typography` slots (all Inter — live in `Type.kt`)

| `Typography` slot | Size | Weight | Letter-spacing | Notes |
|---|---|---|---|---|
| `displayMedium` | 40sp | Bold (700) | −0.5sp | serif dropped (Phase 1.C) |
| `headlineSmall` | 26sp | SemiBold (600) | −0.2sp | serif dropped (Phase 1.C) |
| `titleLarge` | 22sp | SemiBold (600) | 0sp | serif dropped (Phase 1.C) |
| `titleMedium` | 17sp | Bold (700) | +0.1sp | |
| `titleSmall` | 15sp | SemiBold (600) | +0.1sp | |
| `bodyLarge` | 16sp | Normal (400) | +0.15sp | |
| `bodyMedium` | 14sp | Normal (400) | +0.2sp | |
| `bodySmall` | 12sp | Normal (400) | +0.25sp | |
| `labelLarge` | 13sp | Bold (700) | +0.4sp | |
| `labelMedium` | 12sp | Medium (500) | +0.35sp | |
| `labelSmall` | 11sp | SemiBold (600) | +0.5sp | |
| `NumericMonoLarge` *(top-level `TextStyle`)* | 64sp | Medium (500) | 0sp | `FontFamily.Monospace`; `tnum`; Slice 3 HUD timer — unchanged |
| `NumericMonoMedium` *(top-level `TextStyle`)* | 32sp | Medium (500) | 0sp | `FontFamily.Monospace`; `tnum`; countdown — unchanged |

#### Screen-local type styles — `RovaTokens` (all Inter)

These are **not** M3 `Typography` slots. They live in `RovaTokens.kt` and are consumed directly by the composables that need them.

| Token | Size | Weight | Letter-spacing | Feature | Used in |
|---|---|---|---|---|---|
| `eyebrow` | 9sp | Medium (500) | +2.0sp | — | Section eyebrows, small labels; ALL-CAPS at call site |
| `statusPillLabel` | 11sp | Medium (500) | 0sp | `tnum` | REC / WAIT badges, loop counter pills |
| `cellValue` | 12sp | Medium (500) | 0sp | `tnum` | 5-cell settings card values |
| `cellKey` | 8sp | Normal (400) | +0.8sp | — | Cell label below the value; ALL-CAPS at call site |
| `loopCount` | 21sp | SemiBold (600) | −0.6sp | `tnum` | `.loop-count` — big "4/10" numeral in the loop pill |
| `loopUnit` | 10sp | Normal (400) | +1.0sp | — | `.loop-unit` — "LOOPS DONE" caption beside `loopCount` |
| `statusMain` | 11sp | Normal (400) | +0.1sp | — | `.status-main` — status-pill primary label ("Recording") |
| `statusTime` | 11sp | Light (300) | 0sp | `tnum` | `.status-time` — status-pill trailing time ("· 0:18 left") |
| `swipeLabel` | 8sp | Normal (400) | +1.4sp | — | `.swipe-label` — "SWIPE TO EDIT" hint above settings card |
| `navTxt` | 9sp | Normal (400) | +0.6sp | — | `.nav-txt` — Library / Settings / Start labels in bottom nav |
| `zoneTag` | 7.5sp | Medium (500) | +1.5sp | — | `.cam-zone-tag` — "PORTRAIT · 9:16" tag in dual mode |

Tabular figures (`fontFeatureSettings = "tnum"`) apply to every numeric value rendered in the UI — counters, timers, file sizes, dates that contain digits. `NumericMonoLarge` set the precedent (Slice 3); Phase 1 extends it to `cellValue`, `statusPillLabel`, `loopCount`, `statusTime`.

**Settings-sheet type scale (added with the Phase 3 settings-sheet re-skin).** Seven additional `RovaTokens` styles cover the settings sheet (sourced from `mockups/new_uiux/02-settings-sheet.html`): `sheetSectionLabel` (section headings inside the sheet), `sheetRowLabel` (row label / left-side key), `sheetStepValue` (stepper current value), `sheetChip` (quality-chip text), `sheetModeTab` (mode-tab label), `sheetCta` (Save CTA button label), and `peekStatus` (camera-peek strip status line). These are settings-sheet-scoped and consumed by `SettingsSheet.kt`; they are not M3 `Typography` slots.

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
| `Typography` slots (serif dropped; all Inter via `Font.kt`) | `Type.NumericMonoLarge` / `Type.NumericMonoMedium` (already top-level) |
| `Shapes` defaults | `RovaTokens` type styles (`eyebrow`, `cellValue`, `cellKey`, `loopCount`, `loopUnit`, `statusMain`, `statusTime`, `swipeLabel`, `navTxt`, `zoneTag`) |
| | `RovaTokens.minHitTarget` (assertion constant) |
| | `RecordChromeTokens.*` (all record-screen colour/dimension constants — see §2.13) |

The reasoning: anything Compose components consume implicitly (`MaterialTheme.colorScheme.error` from a Text / Icon / Surface) belongs in the theme. Anything tied to a specific layout piece (the 5-cell record card, the camera-overlay backdrop, the warning severity bucket) stays local — putting it in `MaterialTheme` would invite over-application and pollute the M3 surface roles.

> **Slice B (2026-05-23, PR pending):** Dock fill flipped from `bottomNavFill` flat-alpha to `bottomNavBrush` vertical gradient; settings card from `settingsCardRadius=14.dp` to `settingsCardRadiusPill=22.dp`; new `modeChip*` token family for the Mode tap-cycle chip. See spec `docs/superpowers/specs/2026-05-23-edge-to-edge-record-home-slice-b-design.md` and ADR-0012.

### 2.13 Record-chrome constants (`RecordChromeTokens`)

`RecordChromeTokens.kt` is a **record-screen-scoped** constants object. It contains every colour and dimension value extracted pixel-faithfully from `mockups/new_uiux/01-record-home.html`. It is separate from `RovaTokens` by the same rationale: values that are meaningful only to the record screen cannot over-apply to unrelated UI. **As of Phase 2, the record-screen shared chrome (`RecordChrome.kt`) consumes all of these tokens** — they are no longer unused declarations. Six custom chrome vector glyphs (flash, flip, start, stop, library, settings icons) are co-located in `ui/screens/RecordChromeIcons.kt`. Phase 3+ composables will consume any tokens not yet referenced by `RecordChrome.kt`.

CSS `backdrop-filter` blur is deliberately **not** tokenised here — Compose has no backdrop-blur API; the semi-transparent fills are the production approximation (see §2.5).

#### Surface fills & strokes

| Token | Value | Source CSS |
|---|---|---|
| `glassFill` | `Color.Black.copy(alpha = 0.40f)` | `.status-pill` / `.loop-pill` background |
| `glassStroke` | `Color.White.copy(alpha = 0.07f)` | `.status-pill` / `.loop-pill` border |
| `camControlFill` | `Color.Black.copy(alpha = 0.38f)` | `.cam-ctrl-btn` background |
| `camControlStroke` | `Color.White.copy(alpha = 0.09f)` | `.cam-ctrl-btn` border |
| `settingsCardFill` | `Color.White.copy(alpha = 0.065f)` | `.settings-card` background |
| `settingsCardStroke` | `Color.White.copy(alpha = 0.09f)` | `.settings-card` border |
| `cellDivider` | `Color.White.copy(alpha = 0.07f)` | `.s-cell + .s-cell` divider |
| `bottomNavBrush` | `Brush.verticalGradient(0→Transparent, 0.35→Transparent, 0.55→Black 0.20, 0.80→Black 0.45, 1.0→Black 0.55)` | Dock vertical gradient (Slice B); transparent top 35% lets preview read through |
| `modeChipFill` | `Color.White.copy(alpha=0.07f)` | Mode tap-cycle chip background (Slice B) |
| `modeChipStroke` | `Color.White.copy(alpha=0.10f)` | Mode tap-cycle chip border (Slice B) |
| `modeChipGlyphAlphaEnabled` | `0.35f` | `↻` glyph alpha when chip enabled (Slice B) |
| `modeChipGlyphAlphaDisabled` | `0.12f` | `↻` glyph alpha when chip dimmed (Slice B) |
| `modeChipGlyphSize` | `7.sp` | `↻` glyph text size (Slice B) |
| `modeChipCornerRadius` | `11.dp` | Mode tap-cycle chip corner radius (Slice B) |

#### Status dots

| Token | Value | Source CSS |
|---|---|---|
| `dotIdle` | `Color.White.copy(alpha = 0.25f)` | `.dot-idle` |
| `dotRecording` | `Color(0xFFEF4444)` | `.dot-recording` — recording red |
| `dotBreak` | `Color(0xFF94A3B8)` | `.dot-break` — slate (corrects today's amber) |

#### FAB (`.center-btn`)

| Token | Value | Source CSS |
|---|---|---|
| `fabStartFill` | `Color.White.copy(alpha = 0.07f)` | `.btn-start` background |
| `fabStartStroke` | `Color.White.copy(alpha = 0.15f)` | `.btn-start` border |
| `fabStopFill` | `Color(0xFFEF4444).copy(alpha = 0.12f)` | `.btn-stop` background |
| `fabStopStroke` | `Color(0xFFEF4444).copy(alpha = 0.30f)` | `.btn-stop` border |
| `fabStopRing` | `Color(0xFFEF4444).copy(alpha = 0.10f)` | `.btn-stop::after` outer ring |
| `stopSquare` | `Color(0xFFEF4444)` | `.stop-sq` glyph |

#### Camera-zone framing (dual mode)

| Token | Value | Source CSS |
|---|---|---|
| `splitDivider` | `Color.White.copy(alpha = 0.14f)` | `.cam-split-divider` |
| `cameraGridLine` | `Color.White.copy(alpha = 0.018f)` | `.camera-grid` line |
| `focusFrameStroke` | `Color.White.copy(alpha = 0.20f)` | `.focus-frame` bracket |

#### Text-fill colours (white at mockup alpha)

| Token | Alpha | Source CSS |
|---|---|---|
| `loopCountText` | 0.93f | `.loop-count` |
| `loopUnitText` | 0.32f | `.loop-unit` |
| `statusMainText` | 0.65f | `.status-main` |
| `statusTimeText` | 0.32f | `.status-time` |
| `cellValueText` | 0.88f | `.s-val` |
| `cellKeyText` | 0.28f | `.s-key` |
| `cellValueReadOnlyText` | 0.50f | Mode cell read-only |
| `settingsArrow` | 0.18f | `.settings-arrow` |
| `swipeHint` | 0.22f | `.swipe-hint` container |
| `navIcon` | 0.35f | `.nav-ico` glyph |
| `navText` | 0.30f | `.nav-txt` |
| `zoneTagText` | 0.32f | `.cam-zone-tag` |

#### Pills

| Token | Value | Notes |
|---|---|---|
| `statusPillRadius` | 20.dp | `.status-pill` corner radius |
| `loopPillRadius` | 11.dp | `.loop-pill` corner radius |
| `statusPillPaddingH` | 11.dp | `.status-pill` horizontal padding |
| `statusPillPaddingV` | 6.dp | `.status-pill` vertical padding |
| `loopPillPaddingH` | 13.dp | `.loop-pill` horizontal padding |
| `loopPillPaddingV` | 8.dp | `.loop-pill` vertical padding |
| `pillContentGap` | 7.dp | `.status-pill` inner gap |
| `loopPillContentGap` | 6.dp | `.loop-pill` inner gap |
| `topOverlayGap` | 8.dp | gap between loop pill and status pill |
| `dotSize` | 6.dp | `.dot` diameter |

#### Camera controls

| Token | Value | Notes |
|---|---|---|
| `camControlSize` | 30.dp | `.cam-ctrl-btn` diameter |
| `camControlGap` | 7.dp | `.cam-controls` vertical gap |

#### Settings card

| Token | Value | Notes |
|---|---|---|
| `settingsCardRadiusPill` | `22.dp` | Settings card pill corner radius (Slice B; supersedes `settingsCardRadius`) |
| `settingsCardPaddingH` | 12.dp | horizontal padding |
| `settingsCardPaddingV` | 6.dp | vertical padding (trimmed 7→6, 2026-06-13 slim-strip) |
| `settingsCellPaddingH` | 3.dp | `.s-cell` horizontal padding |
| `settingsWrapGap` | 7.dp | `.settings-wrap` vertical gap |
| `settingsCardBottomInset` | 110.dp | `.settings-wrap` bottom offset above nav |
| `swipeBarWidth` | 30.dp | `.swipe-bar` width |
| `swipeBarHeight` | 2.dp | `.swipe-bar` height |

#### Settings card metrics (Slice B)

| Metric | Value | Notes |
|---|---|---|
| `RecordChromeMetrics.settingsCardLift` | `30.dp` | Upward offset applied to the settings card in Slice B edge-to-edge layout |

#### Bottom nav

| Token | Value | Notes |
|---|---|---|
| `bottomNavHeight` | 106.dp | full bar height |
| `bottomNavPaddingH` | 28.dp | horizontal padding |
| `bottomNavPaddingBottom` | 18.dp | bottom padding |
| `navItemGap` | 5.dp | `.nav-item` / `.center-btn-wrap` inner gap |
| `navIconBoxSize` | 42.dp | `.nav-ico` rounded container |
| `navIconGlyphSize` | 20.dp | inner SVG glyph size |
| `navIconCornerRadius` | 12.dp | `.nav-ico` corner radius |
| `fabSize` | 56.dp | `.center-btn` diameter |
| `fabStopRingInset` | 5.dp | outer ring extension on stop state |
| `stopSquareSize` | 18.dp | `.stop-sq` size |
| `stopSquareRadius` | 4.dp | `.stop-sq` corner radius |

#### Shared geometry

| Token | Value | Notes |
|---|---|---|
| `screenEdgeMargin` | 16.dp | overlay / controls / settings-wrap edge offset |
| `splitDividerHeight` | 2.dp | `.cam-split-divider` height |
| `zoneTagPaddingEnd` | 13.dp | `.cam-zone-tag` end offset |
| `zoneTagPaddingBottom` | 9.dp | `.cam-zone-tag` bottom offset |
| `focusFrameSize` | 60.dp | `.focus-frame` bounding square |

#### Camera-guide overlay constants (decorative framing)

Five tokens define the mockup-exact dimensions and styling for decorative camera-framing overlays (`01-record-home.html` `.camera-grid`, `.focus-frame`), consumed by `CameraGuides.kt`. These overlays are gated by the "Camera guides" app-setting (enabled by default) and do not affect the live CameraX preview or recording output — they are purely decorative UI elements layered atop the viewfinder.

| Token | Value | Source CSS |
|---|---|---|
| `cameraGridCellWidth` | 105.3.dp | `.camera-grid` cell width, CSS `background-size` X |
| `cameraGridCellHeight` | 228.3.dp | `.camera-grid` cell height, CSS `background-size` Y |
| `cameraGridLineWidth` | 1.dp | `.camera-grid` line stroke width |
| `focusFrameCornerArm` | 14.dp | `.focus-frame` corner bracket arm length |
| `focusFrameStrokeWidth` | 1.5.dp | `.focus-frame` bracket stroke |

#### Recording-frame guide constants (functional framing — P+L mode)

Three tokens define the mockup-exact styling for the recording-frame guide overlay (spec §2.3, ADR-0010). Consumed by `DualPreviewZone.kt` in P+L (DualShot) mode only — the guide is functional, not decorative; the single Portrait / Landscape modes are out of scope. Unlike the decorative camera-guide overlays above (gated by the "Camera guides" app-setting), the recording-frame guide is always on in P+L mode regardless of the setting. It outlines the recorded region of the dual-camera preview and scrims the margins that fall outside the encoder's crop window.

| Token | Value | Source CSS / Purpose |
|---|---|---|
| `recordingFrameOutline` | `Color(0xFFB0B4BC).copy(alpha = 0.38f)` | faint light-gray outline; the color of the recording-frame guide rectangle |
| `recordingFrameStrokeWidth` | `1.dp` | outline thickness; renders ~2 device pixels on the SM-A176B reference device |
| `recordingFrameScrim` | `Color.Black.copy(alpha = 0.22f)` | faint scrim over the non-recorded preview margin (areas outside the crop rectangle) |

### 2.14 Settings-sheet constants (`SettingsSheetTokens`)

`SettingsSheetTokens.kt` is a **settings-sheet-scoped** constants object, carrying every colour and dimension value extracted pixel-faithfully from `mockups/new_uiux/02-settings-sheet.html`. It follows the same rationale as `RecordChromeTokens`: values that are meaningful only to the settings sheet must not bleed into unrelated UI via `RovaTokens`. The object is consumed exclusively by `SettingsSheet.kt`. Constants cover the camera-peek strip (fill, border, radius, peek height), the sheet panel surface (fill, stroke, corner radius), the mode-tab row (selected/unselected fills and strokes), row steppers (button size, radius, fill, label colours), quality chips (selected/idle fills, stroke, radius), the Save CTA button (fill, text colour, height, radius), and shared row geometry (horizontal padding, row height, divider alpha, section-label spacing).

CSS `backdrop-filter` blurs present in `02-settings-sheet.html` are deliberately **not** tokenised — Compose has no backdrop-blur API, and the panel's near-opaque fill (`rgba(9,13,20,0.97)`) means the no-blur approximation is visually faithful (see §2.5 for the production substitute rationale).

---

## 3. Implementation notes (for Phase 2.1)

**Phase 1 Foundation status (implemented on `feat/record-skin-phase-1-foundation`):**

| Item | Status |
|---|---|
| `Font.kt` — Inter downloadable font declaration | Done |
| `Type.kt` — all M3 `Typography` slots wired to `Inter`; serif dropped | Done |
| `RovaTokens.kt` — shape, sizing/spacing, and all type styles (including Phase 1 mockup scale) | Done |
| `RecordChromeTokens.kt` — full record-screen pixel constants | Done |

**All Phase 2.1 items shipped** (master `d54e051`). The `Color.kt` dark-scheme entries (`#06090f` background, `#5b7fff` primary, `#ef4444` error, derived surface family) landed in the Phase 2.1 PR. The TODOs listed here are historical artifacts; see git log for the implementing commits.

---

## 4. NO-GO list (token-specific)

1. **Do not introduce real `Modifier.blur` for frosted-glass effects in Phase 2.** Pre-API-31 fallback inconsistency + GPU cost during recording > visual fidelity. Tonal elevation + alpha is the v1.0 substitute. Re-evaluate post-v1.0 polish only if a measurable user-visible regression surfaces.
2. ~~**Do not bundle Inter font in this phase.**~~ **Resolved (Phase 1).** Inter is delivered via the AndroidX Google Fonts downloadable provider (`Font.kt`). No `.ttf` asset was bundled. The `FontFamily.SansSerif` stand-in and `// TODO: bundle Inter font asset` marker are both removed.
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
5. ~~**Inter font bundling timing.**~~ **Resolved (Phase 1).** Inter is live via the AndroidX Google Fonts provider (`Font.kt`). No `.ttf` bundle; first-cold-launch falls back to Roboto for ~1 frame. No further action needed.

---

## 6. References

- Mockup design system source: `mockups/new_uiux/PROJECT_CONTEXT.md` §"UI/UX Design Principles" (lines 86–158). Note: `mockups/` is gitignored.
- Mockup CSS: every `<style>` block in `mockups/new_uiux/*.html`. The 18-state warning palette lives in `07-warnings.html` lines 32–35 (`sev-r`, `sev-y`, `sev-b`, `sev-o`).
- Theme files (all on master): `Color.kt`, `Font.kt`, `RecordChromeTokens.kt`, `RovaTokens.kt`, `RovaTokensPreview.kt`, `RovaWarningsV3.kt`, `SettingsSheetTokens.kt`, `Theme.kt`, `Type.kt` — all under `app/src/main/java/com/aritr/rova/ui/theme/`.
- `RovaWarningsV3.kt` — Phase 4 warning re-skin v3 token object (ADR-0013). Also provides notification re-skin state tokens added in Milestone 5 (PR #54).
- Slice 3 typography precedent: `Type.kt:22-42` (`NumericMonoLarge`, `NumericMonoMedium`).
- Bottom-nav surface precedent: `MainScreen.kt:118-179`.
- Replan source: `NEW_UI_BACKEND_REPLAN.md` §5 Phase 1.B + 1.C.

---

*End of document. Phase 2.1 PR consumes this contract; token additions outside it must come back as a Phase 1 doc revision.*
