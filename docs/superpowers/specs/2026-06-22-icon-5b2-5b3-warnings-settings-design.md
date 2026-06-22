# Icon 5b-2 / 5b-3 — Warnings + Settings glyph wiring (ADR-0031)

**Date:** 2026-06-22
**Track:** A (icon-system completion). Closes the surface-wiring backlog for ADR-0031 except deferred 5b-4 (Onboarding) / 5b-5 (record-chrome+nav).
**Goal:** Consume the 5b-1 authored System-D glyphs (`RovaIcons` map: 27 concept + 2 status) on the Warnings and Settings surfaces. The glyphs are dead weight until a surface renders them.

## Scope split (two sub-PRs, sequential on owner GO)

- **5b-2 Warnings** — first. Routes warning-cause icons to System-D glyphs.
- **5b-3 Settings** — rebased on 5b-2 after merge. Routes settings-row icons; authors the 2 last missing glyphs.

Both surfaces' icons are **decorative** (a leading icon beside an existing label/title that already carries the meaning). Correct a11y is `contentDescription = null` (avoid double-announce). **Therefore neither sub-PR adds new user-facing strings** — `checkNoHardcodedUiStrings` is not engaged, and there is no shared `strings.xml` edit. The two sub-PRs are fully disjoint Kotlin and may be authored concurrently in separate worktrees; the sequence is for clean review only.

## House rules (unchanged)

- Pattern = PR-4 (`LibraryIconSpec`): a pure, Compose/Android-free `*IconSpec` holds any state→glyph/status decision so it is JVM-unit-testable under `isReturnDefaultValues`. Identity-only fence: nav/utility icons (Back/Close/Play/chevrons/overflow) stay stock Material.
- `IconRole.Accent` for favorite/select; `IconStatus` for locked status. Settings rows are identity → `IconRole.Default`.
- All bespoke glyphs live in `ui/theme/RovaGlyphs.kt` (`checkRovaGlyphHome`). The board (`board-3-semantic.html`, gitignored) is glyph SSOT — round-trip any new glyph from its `d`-string, do not eyeball.
- 46 gates + full JVM green at every commit. Never edit a `check*` to pass.
- codex MCP peer review for the spec-changes and the ADR amendment.

---

## 5b-2 — Warnings

### Current state (inventory)

- `WarningId` enum = **21 values** (precedence order), `ui/warnings/WarningId.kt`.
- Two pure-ish content builders in `ui/warnings/WarningSheetContent.kt` each pick an icon inline per `when` arm:
  - `warningSheetContent(id): WarningSheetContent` (`.icon: ImageVector`) — exhaustive over 21.
  - `midRecBannerContent(id): TopBannerContent` (`.icon: ImageVector`) — top-banner subset (12).
- Render sites tint with theme-derived severity colors (gate-legal — `checkSemanticIconNoRawAlpha` explicitly whitelists `tint = severityColor`):
  - `WarningSheetV3.kt` → `IconWithGlow` (line ~278) `Icon(icon, tint = accent)`.
  - `WarningTopBannerV3.kt` (line ~90) `Icon(content.icon, tint = severityColor.copy(alpha = 0.95f))`.
- All cause icons currently pass `contentDescription = null` (decorative — correct).

### Design

1. **New pure helper** `ui/warnings/WarningIconSpec.kt`:
   ```kotlin
   internal object WarningIconSpec {
       /** One concept→glyph for every WarningId, surface-independent (sheet and banner agree). */
       fun glyphFor(id: WarningId): RovaGlyph = when (id) { ... }
   }
   ```
   Both `warningSheetContent` and `midRecBannerContent` call `WarningIconSpec.glyphFor(id)` — a given `WarningId` resolves to ONE glyph regardless of surface. JVM test `WarningIconSpecTest` pins every one of the 21 ids (exhaustiveness guard + explicit asserts on the concept splits below).

2. **Concept map** (every target already exists in `RovaIcons`; **zero new glyphs**):

   | WarningId | Glyph |
   |---|---|
   | `CAMERA_PERMISSION_DENIED` | `RovaIcons.CameraPermission` (no slash — "needs access") |
   | `CAMERA_IN_USE`, `CAMERA_DISABLED` | `RovaIcons.CameraOff` (slashed — "blocked") |
   | `EXACT_ALARM_DENIED` | `RovaIcons.AlarmOff` |
   | `STORAGE_INSUFFICIENT`, `STORAGE_LOW_MID_REC`, `STORAGE_FULL_AUTOSTOPPED`, `CANT_MERGE` | `RovaIcons.Storage` |
   | `SAVE_FOLDER_UNAVAILABLE` | `RovaIcons.Folder` |
   | `THERMAL_SHUTDOWN`, `THERMAL_EMERGENCY`, `THERMAL_CRITICAL`, `THERMAL_SEVERE`, `THERMAL_MODERATE`, `THERMAL_AUTOSTOPPED` | `RovaIcons.Thermal` |
   | `BATTERY_CRITICAL`, `BATTERY_LOW` | `RovaIcons.BatteryLow` |
   | `MICROPHONE_DENIED` | `RovaIcons.MicOff` |
   | `NOTIFICATIONS_DENIED` | `RovaIcons.NotificationsOff` |
   | `BATTERY_OPTIMIZATION_ON` | `RovaIcons.BatterySaver` |
   | `POWER_SAVE_MODE` | `RovaIcons.PowerMode` |

   The camera split (`CameraPermission` for permission-denied vs `CameraOff` for in-use/disabled) and the storage-vs-folder split are the only non-obvious choices — both are explicitly asserted in the test.

3. **Data-class change**: `WarningSheetContent.icon: ImageVector` → `glyph: RovaGlyph`; same for `TopBannerContent`. The `when` arms drop the inline `Icons.Default.*` (icon now comes from `glyphFor(id)`), keeping only their string/severity content.

4. **Render-site change** — draw the glyph **monochrome in the existing severity color**, both layers same tint, glow preserved:
   - `IconWithGlow` takes `glyph: RovaGlyph`; renders `Icon(glyph.outline, …, tint = accent)` then `glyph.accent?.let { Icon(it, …, tint = accent) }`.
   - Banner: same two-layer render with `severityColor.copy(alpha = …)`.
   - This is **not** routed through `SemanticIcon` — warnings keep their severity-tint system. Tints are theme-derived (`accent`, `severityColor`) so `checkSemanticIconNoRawAlpha` passes. No raw `Color` literal, no `RovaSemantics.*.copy(...)` (so `checkStatusColorLocked` passes).

5. **ADR-0031 §4 amendment** (one clause): document the warnings surface as the standing **severity-tint exception** — glyph *identity* comes from `RovaIcons`/the System-D family, but glyph *color* is the warning-severity system (per-tier `RovaWarnings` + glow), deliberately not theme-driven. Rationale: severity color is the warning's primary signal and is finer-grained (5 thermal tiers) than `IconStatus`'s flat Warning/Danger. The gate already permits this (whitelists `tint = severityColor`); the amendment records *why* so a future reader does not "fix" it into `SemanticIcon`.

6. Chrome icons unchanged (identity-only fence): overflow (`MoreHoriz`/`MoreVert`), expander chevrons (`ExpandLess`/`ExpandMore`), the "why this matters" `Info`. These keep their existing `contentDescription` (the overflow buttons already have `R.string.warning_more_*_cd`).

### Files touched (5b-2)

- **new** `ui/warnings/WarningIconSpec.kt`
- **new** `src/test/.../ui/warnings/WarningIconSpecTest.kt`
- `ui/warnings/WarningSheetContent.kt` (data classes + both builders)
- `ui/warnings/WarningSheetV3.kt` (`IconWithGlow`)
- `ui/warnings/WarningTopBannerV3.kt` (banner render)
- `docs/adr/0031-icon-glyph-system.md` (§4 exception clause)
- No `strings.xml`, no new gate.

---

## 5b-3 — Settings

### Current state (inventory)

- Surfaces: `ui/screens/SettingsScreen.kt` (full screen, ~24 rows with leading icons), `ui/screens/SettingsSheet.kt` (already on `SemanticIcon` for mode/orientation tabs), `ui/screens/FloatingSettingsPanel.kt` (chrome only: Close/ExpandMore).
- Every `SettingsScreen` row uses a raw `Icons.*` leading icon with `contentDescription = null`.
- Three rows have **no existing glyph**: Interval (`HourglassEmpty`), Loop Count (`Repeat`), Sound Cues (`VolumeUp`).
- State rows: Hide-in-Vault (`hideInVault` on/off) and Battery-Optimization (`isExempt`) — state already surfaced by a `Switch` / `BatteryStatusBadge`.

### Design

1. **Author the 2 last glyphs** by round-tripping the board `d`-strings into `RovaGlyphs.kt`:
   - `loop_interval` (board line ~147) → `RovaGlyphs.LoopInterval` (two-layer: outline repeat-arrows + `ac2` accent core).
   - `volume` (board line ~161) → `RovaGlyphs.Volume` (outline speaker + `ac2` accent waves).
   Both are board-P2-future concepts; their `d`-strings already exist, so this is a faithful SSOT round-trip, not a new design. Add `RovaIcons.LoopInterval`, `RovaIcons.Volume`, and a `RovaIcons.Interval` alias (= `RovaGlyphs.Waiting`, the existing hourglass) with concept KDoc.

2. **Wire the rows** through `SemanticIcon(glyph = …, role = IconRole.Default)` (settings icons are theme-tinted identity glyphs → correctly through the seam):

   | Row | Glyph |
   |---|---|
   | Theme / Dark mode | `RovaIcons.DarkMode` |
   | Language | `RovaIcons.Language` |
   | Resolution / quality | `RovaIcons.Quality` |
   | Clip duration | `RovaIcons.Timer` |
   | Interval (between clips) | `RovaIcons.Interval` (Waiting hourglass) |
   | Loop count | `RovaIcons.LoopInterval` |
   | Keep screen on | `RovaIcons.Device` |
   | Camera guides | `RovaIcons.GridLayout` |
   | Library preview | `RovaIcons.Video` |
   | Sound cues | `RovaIcons.Volume` |
   | Vibrate alerts | `RovaIcons.Vibration` |
   | Hide in vault | `RovaIcons.Vault` |
   | Schedule master / start / stop | `RovaIcons.Schedule` |
   | Exact alarm | `RovaIcons.AlarmOff` |
   | System notifications | `RovaIcons.NotificationsSetting` |
   | Auto-delete | `RovaIcons.DeleteAll` |
   | Save location / use internal | `RovaIcons.Folder` |
   | Clear cache | `RovaIcons.Cleanup` |
   | Battery optimization | `RovaIcons.PowerMode` |
   | Version / about | `RovaIcons.Info` |
   | Privacy policy | `RovaIcons.Privacy` |

3. **State rows** (Hide-in-Vault, Battery-opt): neutral glyph at `IconRole.Default`; the existing `Switch` / `BatteryStatusBadge` conveys on/off. No status tint, no glyph swap (identity-only fence). Per owner decision 2026-06-22.

4. **Utility/chrome unchanged**: leading chevron (`KeyboardArrowRight`), `FloatingSettingsPanel` Close/ExpandMore, `SettingsSheet` PresetTile Add/Check/Close. Identity-only fence.

5. **No pure decision-spec needed** — after the neutral-state choice, every row is a static concept→glyph map, so wiring is direct `RovaIcons.X` at the call-site (matches the simplest Library rows). JVM coverage = `SettingsGlyphWiringTest`: asserts the 2 new `RovaIcons` entries resolve to the new `RovaGlyphs` (non-null outline; `LoopInterval`/`Volume` have an accent layer) — a regression guard that the round-trip landed and the map points at the right glyph.

### Files touched (5b-3)

- `ui/theme/RovaGlyphs.kt` (LoopInterval + Volume authored)
- `ui/theme/RovaIcons.kt` (LoopInterval, Volume, Interval map entries)
- **new** `src/test/.../ui/theme/SettingsGlyphWiringTest.kt`
- `ui/screens/SettingsScreen.kt` (≈24 rows → `SemanticIcon`)
- No `strings.xml`, no new gate, no ADR change.

---

## Verification (both)

- `:app:assembleDebug` (warm) + `:app:testDebugUnitTest` green; all 46 gates green (esp. `checkSemanticIconNoRawAlpha`, `checkStatusColorLocked`, `checkRovaGlyphHome`).
- Device smoke on RZCYA1VBQ2H (Warnings + Settings are NOT FLAG_SECURE → `screencap` works). Owner visual confirm is final.
- **Owner board-eyeball still pending from 5b-1** (confirm during smoke @18dp): BatteryLow, Cleanup, Info, Thermal, Storage, Quality — all consumed by these two slices.

## Out of scope (deferred)

5b-4 Onboarding + `rec_clipcheck`/`interrupted` status wiring · 5b-5 record-chrome `FlipCam`/`FlashBolt` + nav glass (device-verify unknowns: torch-on tint vs `checkSemanticIconNoRawAlpha`).
