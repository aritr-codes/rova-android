# Icon-System Completion (PR-5b track) — Design

> Branch `feat/icon-pr5b-surfaces`. Off master `3798845`.
> Brainstormed 2026-06-20. Owner expanded the original "PR-5b remaining surfaces" into a **full icon-
> system completion**: author new board-family glyphs for gaps (and round-trip them into the board so
> it stays the single source of truth), migrate the remaining surfaces, fix Record-chrome flip/flash,
> audit the nav-tab treatment, and document every legitimate stock exception.

## 1. System recap

System D visual language: outlined soft-monoline, **duotone accent channel** (`.ac2`, retinted per
palette by the theme engine), **locked semantic status colours**. Bespoke glyphs live ONLY in
`ui/theme/RovaGlyphs.kt` (gate `checkRovaGlyphHome`); the concept→glyph map is `ui/theme/RovaIcons.kt`;
everything renders through `ui/components/SemanticIcon.kt` — the single tint seam (`IconRole` identity
tints, `IconStatus` locked status colours, `IconRole.OnAccent` for filled-accent). Gates
`checkSemanticIconNoRawAlpha` + `checkStatusColorLocked` must stay green. Board SSOT =
`.superpowers/brainstorm/1234-1781611237/content/board-3-semantic.html` (E1 semantic map / E3 rules).

## 2. Audit result (app-wide, 117 refs / 30 files)

| Class | Count | Action |
|---|---|---|
| **BOARD-EXISTS** | 15 | migrate to `RovaGlyph` via `SemanticIcon` |
| **NEEDS-NEW-GLYPH** | 69 (25 unique concepts) | author new System-D glyphs + round-trip to board |
| **LEGIT-STOCK** | 30 | keep stock (nav/utility identity-only fence) — documented |
| **LEGACY-ONEOFF** | 0 | (none stray; all bespoke vectors already in `RovaGlyphs`) |

Hotspots: `WarningSheetContent.kt` (23) and `SettingsScreen.kt` (25).

## 3. Locked decisions (owner, 2026-06-20)

1. **`IconStatus.Interrupted` → `RovaSemantics.escalating` (orange #f97316)** — was amber/`warning`.
   Board-exact; distinguishes "interrupted" from generic "warning". One-line `SemanticIconSpec` change
   + test update (`Recovered` stays green, already correct).
2. **`RovaIcons.Play`** re-authored to the board d-string `M8 6.3 17.4 12 8 17.7z` (was the legacy
   ex-FAB triangle `M9 7 L17 12 L9 17 z`). Fixes the transport pair board-exactness; player center-play
   consumes it.
3. **Camera-flip → board `flip_cam`** (rotation arcs around a lens core). Author `RovaGlyphs.FlipCam`
   from the board d-string; the Record flip button stops drawing lens-identity `CameraFront/CameraRear`.
   (`CameraFront`/`CameraRear` remain authored for any future lens-identity use.)
4. **Icon work ships as a focused sub-PR sequence** (§7), not one mega-diff.

## 4. New glyphs to author (~25 concepts)

Author in `RovaGlyphs.kt` using the existing helpers (`glyph`/`strokePath`/`fillPath`/`svgStroke`/
`svgFill`/`circle`/`roundRect`/`seg`), matching System-D geometry: **24-grid, 1.9px stroke, round
caps+joins, soft radii, duotone `.ac2` accent where it adds meaning, mono-safe (meaning survives
without colour)**. Each new glyph is **added back into `board-3-semantic.html`** (the `G={}` dictionary
+ E1 map row) so the board remains SSOT.

Warnings/permissions: `Thermal`, `Storage`, `BatteryLow`, `BatterySaver`, `PowerMode`, `AlarmOff`,
`CameraOff` (in-use/disabled), `CameraPermission` (denied), `MicOff` (denied/disabled). Settings:
`DarkMode`, `Language`, `Quality`, `Timer`, `Schedule`, `Lock`, `Vibration`, `Device`, `GridLayout`,
`Video`, `Folder`, `Cleanup`, `DeleteAll`, `Privacy`, `Info`. Onboarding (affirmative — the permission
being *introduced*, distinct from the off/denied warning variants): `CameraAccess`, `MicAccess`
(+ reuse `NotifBell` + `WarnTriangle`). Flash: re-author `FlashBolt` to clean System-D and **add it to
the board** (Flash is absent from E1).

> Glyph d-strings are extracted board-exact at authoring time via `ctx_execute_file` against the board
> `G={}` dictionary for board-defined ones; net-new concepts (thermal/storage/etc.) are designed in the
> family then written back to the board. **Never eyeball.**

## 5. Per-surface migration (pure `*IconSpec` per surface)

Mirror `LibraryIconSpec`/`PlayerIconSpec`: a pure object mapping state→`RovaGlyph`/`IconStatus?`,
JVM-tested; identity-only fence; `IconStatus` for status; `IconRole.Accent` for favourite/select.
Param `iconRole` (never `semantics{role=}` — PR-4 shadow-bug).

- **Warnings** — `WarningSheetContent.kt` (Thermal/Storage/Battery*/CameraOff/MicOff/Alarm/Power/Notif),
  `WarningSheetV3.kt` (Info → new `Info` glyph; Expand/MoreHoriz stay stock-utility),
  `WarningTopBannerV3.kt` (MoreVert stays stock), `HistoryWarningStrip.kt` (Close stays stock).
  `WarningIconSpec` resolves each `WarningId`/state → glyph + `IconStatus`.
- **Settings** — `SettingsScreen.kt` (DarkMode/Language/Quality/Timer/Repeat→`Loop`/Device/Grid/Video/
  Volume/Vibration/Lock/Schedule/Notifications/DeleteAll/Folder/Cleanup/Battery/Info/Privacy);
  `SettingsSheet.kt`/`FloatingSettingsPanel.kt`/`SettingsOptionSheet.kt` (Check/Close/Add/Expand stay
  stock-utility). `SettingsIconSpec` maps each row key → glyph. `HourglassEmpty` → board `Waiting`.
- **Onboarding** — `OnboardingSlide.kt` (Videocam→`CameraAccess`, Mic→`MicAccess`,
  Notifications→`NotifBell`, WarningAmber→`WarnTriangle`). Affirmative glyphs, not the off/denied
  warning variants.

## 6. Status-glyph wiring · Record-chrome · Nav

- **`rec_clipcheck` (Recovered, green) + `interrupted` (escalating orange)** added to `RovaIcons`
  (status-bearing) and wired into Recovery card / History status chips / `NotificationCopy` recovered-
  vs-interrupted surfaces. Decision #1 flips the Interrupted colour at the same time.
- **Record chrome** (`RecordChrome.kt`): Flip → `RovaGlyphs.FlipCam` via `SemanticIcon` (replaces
  CameraFront/CameraRear at the call-site). Flash → re-authored `FlashBolt`; routed through
  `SemanticIcon` with an **explicit torch-ON override** (torch-ON = a hardware-state indicator, kept as
  a deliberate, documented colour override — not a theme/status tint; preserves the existing intent
  without a raw `Icon()` opt-out). Both glyphs added to the board.
- **Bottom-nav tabs** (`RecordChrome.NavItem`): currently **bare** (no container). **Owner intent
  (clarified 2026-06-20): ADD a circular tinted/glass container backing to the Library + Settings tab
  icons** so they stay visually anchored, discoverable, and consistent with the established navigation
  visual system — matching its sizing, opacity, tint, blur/tint behaviour, spacing and motion. (This is
  additive; nothing is removed.) Implementation:
  - **Persistent glass-circle container** on both tabs, matching the existing nav/camera-control glass
    treatment (the camera-control Flip/gear buttons already sit in glass circles — the tabs should
    read as the same family).
  - **Active** state additionally accent-tinted per board E3 (`active = filled glyph + accent + Liquid-
    Glass chip, FILL 0→1 animatable`): add an `isActive` param; active = `IconRole.Accent` glyph + accent
    tint over the glass; inactive = neutral glass. `RovaMotion`/ReducedMotion-gated FILL transition.
  - **Component reuse decision:** prefer reusing the existing glass-circle primitive (the camera-control
    button container / `GlassSurface`) if its nav sizing/spec fits; otherwise a thin nav-specific
    `NavGlassChip` wrapper over the same tokens. Evaluate at implementation; document the choice.
  - Owner to confirm the exact look on-device during 5b-5 (the camera-control glass circle is the
    reference treatment to match).

## 7. Sub-PR sequence (off `feat/icon-pr5b-surfaces`)

1. **5b-1 Glyph foundation + board SSOT** — author the ~25 new glyphs + re-authored `FlashBolt`/
   `FlipCam` in `RovaGlyphs`; round-trip into `board-3-semantic.html`; extend `RovaIcons` map; small
   fixes that belong to the foundation (Play d-string; `Interrupted→escalating`); JVM specs
   (`RovaIconsTest`/glyph presence, `SemanticIconSpecTest` interrupted hue). No surface churn.
2. **5b-2 Warnings** — `WarningIconSpec` + the 4 warning files.
3. **5b-3 Settings** — `SettingsIconSpec` + the 4 settings files.
4. **5b-4 Onboarding + status wiring** — onboarding glyphs + `rec_clipcheck`/`interrupted` into
   Recovery/History/notification.
5. **5b-5 Record-chrome + nav chip** — Flip→`flip_cam`, Flash re-author + torch-ON override, nav
   active-chip; device smoke (Record screen visible surface).

Each sub-PR: pure `*IconSpec` + JVM tests in the same PR, all 46 gates + JVM green, codex-review the
glyph set + seam wiring, new strings en+es. No new `check*` gate (glyphs stay in `RovaGlyphs`; seam
unchanged).

## 8. LEGIT-STOCK (kept, documented)
Back/ArrowBack, Close/Clear (dismiss), `KeyboardArrowRight`/Expand chevrons, `MoreVert`/`MoreHoriz`
(overflow), stepper +/−, `RadioButtonUnchecked`, `Check`/`SelectAll`. These are pure nav/utility where
a bespoke glyph adds no identity (board: "raster/launcher untouched; stock keeps Material names behind
the `RovaIcons` alias"). They remain stock `Icon(...)` and are listed here as the explicit exception
set.

## 9. Risks
- **Gate `checkSemanticIconNoRawAlpha`/`checkStatusColorLocked`** — the Flash torch-ON override must be
  expressed without a raw alpha on a SemanticIcon; use a documented explicit-tint path the gate allows
  (or a dedicated hardware-state seam). Verify the gate before relying on it.
- **Board round-trip drift** — the board is gitignored under `.superpowers/`; editing it must keep the
  `G={}`/E1 structures parseable (the authoring pulls d-strings from it).
- **New-glyph contrast** — meaningful glyphs ≥3:1 non-text (SC 1.4.11) over worst-case substrate; rely
  on the glass-chip substrate, not the stroke (board E3). Re-check via existing contrast tests where a
  glyph carries status colour.
