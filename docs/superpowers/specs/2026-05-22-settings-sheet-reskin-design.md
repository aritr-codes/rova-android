# Settings-Sheet Re-skin — Design Spec

**Date:** 2026-05-22
**Status:** Approved (brainstorming) — awaiting spec review
**Mockup:** `mockups/new_uiux/02-settings-sheet.html`
**Scope:** Re-skin the session settings sheet to pixel-match `02-settings-sheet.html`, replacing the tap-row → secondary-edit-sheet flow with inline steppers + inline quality chips, presented as a custom camera-peek panel.

This is sub-project 1 of 2. Sub-project 2 (P+L record-screen mode framing, `01-record-home.html` row 3) gets its own spec → plan → PR and is **out of scope here**.

---

## 1. Goal

The session settings sheet today (`SessionSettingsSheet.kt`) is a Material `ModalBottomSheet` of *tap-rows*: each row (Clip / Repeats / Wait / Quality) opens a **separate** secondary edit sheet (`RecordEditSheets.kt`). The `02-settings-sheet.html` mockup edits every setting **inline** — `−`/value/`+` steppers and HD/FHD/4K chips directly in the sheet — and presents the sheet as a panel pinned to the bottom with the live camera "peeking" above it.

Re-skin to match the mockup exactly: inline editing, the camera-peek panel layout, mockup-exact colours/typography/dimensions.

## 2. Decisions (from brainstorming)

| Decision | Choice |
|---|---|
| Edit model | **Inline** — steppers + quality chips in the sheet; retire `RecordEditSheets.kt` entirely. |
| Sheet presentation | **Full peek panel** — custom overlay; camera peek above (212 dp) with scrim + mini status pill + flash/flip; opaque panel below. |
| "Save" semantics | **Live edits, Save = labelled dismiss.** Steppers/chips apply immediately (write-through, unchanged). Handle-drag-down + system-back also dismiss. No draft state, no discard path. |
| Branch base | `master` @ `8193feb` (PR #36 merged). |

## 3. Architecture

### 3.1 Custom overlay, not ModalBottomSheet

Material `ModalBottomSheet` covers the screen with its own scrim and cannot express a "camera peek with a custom scrim + chrome inside the peek". So the sheet becomes a **full-size overlay composable** rendered inside RecordScreen's root `Box` when `combinedSettingsOpen` is true:

- **Top 212 dp — peek region.** Translucent. RecordScreen's *existing* camera preview (the bottom z-layer) shows through. Drawn on top: the `peek-scrim` vertical gradient, the `peek-grid` faint lines, a mini status pill (bottom-left), and the flash/flip controls (top-right, reusing `RecordCameraControls`).
- **Below 212 dp — sheet panel.** Opaque (`rgba(9,13,20,0.97)`), rounded top 24 dp, 1 dp top border. Holds the handle, mode tabs, setting rows, Save CTA.

Slide-in / out via `AnimatedVisibility` (vertical slide).

### 3.2 Chrome suppression

While the sheet is open, the normal record chrome (top overlay, settings card, bottom nav, active HUD) must NOT render — the mockup peek is clean (camera + peek chrome only). RecordScreen gates the chrome blocks with `if (!combinedSettingsOpen)`. The camera preview itself keeps rendering (it is the peek).

### 3.3 No new camera surface

The peek shows RecordScreen's existing `previewView` / `DualPreviewZone`, unchanged. The overlay's top 212 dp simply does not paint anything opaque over it.

## 4. Components & Files

### 4.1 New files

| File | Responsibility |
|---|---|
| `app/src/main/java/com/aritr/rova/ui/screens/SettingsSheet.kt` | The custom peek-panel sheet composable + its private sub-composables (peek region, panel, section label, setting row). |
| `app/src/main/java/com/aritr/rova/ui/theme/SettingsSheetTokens.kt` | `object SettingsSheetTokens` — `02-settings-sheet.html`-exact colour/dimension constants (§7). Record-screen-scoped object, same rationale as `RecordChromeTokens`. |

`SettingsStepper`, `QualityChips`, and the re-skinned `ModeTabsPicker` are all private composables inside `SettingsSheet.kt` — they have no other consumer, and `ModeTabsPicker`'s current home (`SessionSettingsSheet.kt`) is being deleted, so it must move. Keeping them file-local avoids a premature shared-component split.

### 4.2 Modified files

| File | Change |
|---|---|
| `SessionSettingsSheet.kt` | Deleted — replaced by `SettingsSheet.kt`. Its `ModeTabsPicker` + `SectionLabel` + `SettingRow` helpers move into `SettingsSheet.kt` (re-skinned). |
| `RecordScreen.kt` | Swap the `ModalBottomSheet` call for the `SettingsSheet` overlay; suppress record chrome when `combinedSettingsOpen`; wire stepper/chip callbacks straight to the `viewModel` setters. |
| `RovaTokens.kt` | Add the sheet text styles (§7). |
| `RecordViewModel.kt` | Add direct setters (`setDuration` / `setLoopCount` / `setIntervalMinutes` / `setResolution`) **only if** they do not already exist — the inline steppers/chips call them directly. (The secondary edit sheets already mutated these StateFlows; reuse whatever path they used.) |
| `docs/UI_DESIGN_TOKENS.md` | Document `SettingsSheetTokens` + the new `RovaTokens` styles. |

### 4.3 Deleted files

| File | Reason |
|---|---|
| `RecordEditSheets.kt` | The 4 secondary edit sheets (ClipLength / Repeats / Wait / Quality) are retired — editing is now inline. The `SheetTarget` enum and the `onPickRow` plumbing go with it. |

**Risk gate:** before deletion, confirm nothing outside `SessionSettingsSheet.kt` / `RecordScreen.kt` imports `RecordEditSheets.kt` or `SheetTarget`. The plan's first task verifies this; if another consumer exists, escalate.

### 4.4 Stepper-bounds helper

The inline steppers need min / max / step per setting (clip duration, repeats, wait). That clamp/step logic currently lives in `RecordEditSheets.kt`. Extract it — verbatim, same ranges — into a small **pure** helper (top-level `internal` functions, e.g. `RecordSettingBounds.kt` or functions in `SettingsSheet.kt`) so the stepper `−`/`+` handlers call `stepClipDuration(current, +1)` etc. This pure helper is the one **JVM-testable seam** (§6).

## 5. Layout — `02-settings-sheet.html`

### 5.1 Peek region (top 212 dp)
- Background: camera preview (existing), visible through.
- `peek-grid` — faint grid lines, white `0.016` alpha, cell ~105×71 dp. Decorative; cheap `drawBehind`.
- `peek-scrim` — vertical gradient, black `0.18` (top) → black `0.62` (bottom).
- `peek-status` — bottom-left (14 dp / 16 dp insets): a pill, padding `5×10`, bg black `0.38`, 1 dp border white `0.07`, radius 20 dp; a 5 dp dot (white `0.22` idle) + status text (10 sp, white `0.5`). Text mirrors the current record state ("Ready to record" idle; the active-state label when a session is running).
- `cam-controls` — top-right (50 dp / 10 dp insets), the existing flash + flip `RecordCameraControls`, vertical, 7 dp gap.

### 5.2 Sheet panel (below the peek)
- Background `rgba(9,13,20,0.97)` (near-opaque — no blur needed, unlike the Phase-2 card), 1 dp top border white `0.085`, top corners 24 dp. Padding `18 dp` horizontal, `26 dp` bottom.
- **Sheet top** — padding `12 dp` top / `14 dp` bottom; a centered handle 32×4 dp, white `0.16`, radius 2 dp. (No "Done" text — the mockup `sheet-top` is handle-only.)
- **"RECORDING MODE"** section label — 8.5 sp, weight 500, 2 sp tracking, uppercase, white `0.2`; 8 dp bottom margin.
- **Mode tabs** — segmented control: track bg white `0.05`, radius 13 dp, 3 dp padding, 2 dp gap, 20 dp bottom margin. 3 tabs (Portrait / Landscape / P + L), each `flex 1`, padding `8×4`, radius 10 dp, 11 sp weight 500. Active tab: bg white `0.11`, text white `0.90`, shadow `0 1 4 black-0.35`. Inactive: text white `0.26` (disabled: white `0.16`).
- **"SETTINGS"** section label — same style.
- **Setting rows** — 4 rows, each padding `13 dp` vertical, 1 dp bottom divider white `0.046` (last row no divider):
  - "Clip Duration" → stepper
  - "Repeats" → stepper
  - "Wait Between" → stepper
  - "Quality" → chip group
  - Row label: 12 sp, weight 400, white `0.46`.
- **Save CTA** — 18 dp top margin, full width, padding `16 dp` vertical, bg white `0.07`, 1 dp border white `0.10`, radius 16 dp, centered text "Save" 13 sp weight 500 white `0.72`.

### 5.3 Stepper
- Row: `−` button · value · `+` button, 10 dp gaps.
- Button: 27×27 dp, radius 8 dp, bg white `0.07`, 1 dp border white `0.1`, glyph (`−` / `+`) 16 sp weight 300, white `0.55`.
- Value: 13 sp weight 500, white `0.88`, tabular figures, min-width 34 dp, centered.
- `−` / `+` clamp at the setting's min / max (helper, §4.4); a button at its bound renders disabled (dimmed) and is non-interactive.

### 5.4 Quality chips
- Group of chips, 5 dp gap. Chip: padding `5×12`, radius 20 dp, 10 sp weight 500, 0.4 sp tracking.
- Selected: bg white `0.13`, 1 dp border white `0.18`, text white `0.90`.
- Unselected: transparent, 1 dp border white `0.09`, text white `0.28`.
- Chip count/labels follow the app's actual quality options (HD / FHD / 4K, mapped to whatever `resolution` strings the app uses — implementer maps; the QualityEditSheet being deleted is the source of truth for the option list).

## 6. Behaviour

- **Live edits.** Stepper `−`/`+` and chip selection call the `viewModel` setters immediately; the existing `RovaSettings` write-through persists them. No draft.
- **Save / dismiss.** "Save" dismisses (`viewModel.closeSettingsSheet()` or equivalent — reuse whatever toggles `combinedSettingsOpen`). Handle drag-down and system-back also dismiss. All three are equivalent; nothing to discard.
- **Editable gate.** When a recording session is active, the controls (mode tabs, steppers, chips) are disabled — display values still show — consistent with today's `modeEnabled` gate, generalised to all controls. Mode-switching stays idle-only.
- **Mode switch.** Tapping a mode tab calls the existing `onModePick` path; the existing P+L enable rules are unchanged.

## 7. Tokens

New `object SettingsSheetTokens` (`com.aritr.rova.ui.theme`) — `02-settings-sheet.html`-exact. CSS px → dp; CSS `rgba` → `Color`. Members (geometry `Dp`, colours `Color`):

- Peek: `peekHeight` 212, `peekBackground` #060D18, `peekGridLine` white-0.016, `peekScrimTop` black-0.18, `peekScrimBottom` black-0.62, `peekStatusInsetBottom` 14, `peekStatusInsetStart` 16, `peekStatusPaddingH` 10, `peekStatusPaddingV` 5, `peekStatusFill` black-0.38, `peekStatusStroke` white-0.07, `peekStatusRadius` 20, `peekDotSize` 5, `peekDotIdle` white-0.22, `camControlsInsetTop` 50, `camControlsInsetEnd` 10.
- Panel: `sheetFill` Color(0xF7090D14) (`rgba(9,13,20,0.97)`), `sheetTopStroke` white-0.085, `sheetCornerRadius` 24, `sheetPaddingH` 18, `sheetPaddingBottom` 26.
- Sheet-top: `handleWidth` 32, `handleHeight` 4, `handleColor` white-0.16, `handleRadius` 2, `sheetTopPaddingTop` 12, `sheetTopPaddingBottom` 14.
- Section label: `sectionLabelColor` white-0.2, `sectionLabelGap` 8.
- Mode tabs: `modeTabsTrackFill` white-0.05, `modeTabsRadius` 13, `modeTabsPadding` 3, `modeTabsGap` 2, `modeTabsBottomMargin` 20, `modeTabPaddingH` 4, `modeTabPaddingV` 8, `modeTabRadius` 10, `modeTabActiveFill` white-0.11, `modeTabActiveText` white-0.90, `modeTabIdleText` white-0.26, `modeTabDisabledText` white-0.16.
- Rows: `rowPaddingV` 13, `rowDivider` white-0.046, `rowLabelText` white-0.46.
- Stepper: `stepBtnSize` 27, `stepBtnRadius` 8, `stepBtnFill` white-0.07, `stepBtnStroke` white-0.1, `stepBtnGlyph` white-0.55, `stepperGap` 10, `stepValText` white-0.88, `stepValMinWidth` 34.
- Chips: `chipPaddingH` 12, `chipPaddingV` 5, `chipRadius` 20, `chipGroupGap` 5, `chipOnFill` white-0.13, `chipOnStroke` white-0.18, `chipOnText` white-0.90, `chipOffStroke` white-0.09, `chipOffText` white-0.28.
- CTA: `ctaTopMargin` 18, `ctaPaddingV` 16, `ctaFill` white-0.07, `ctaStroke` white-0.10, `ctaRadius` 16, `ctaText` white-0.72.

New `RovaTokens` text styles (Inter): `sheetSectionLabel` (8.5sp/500/2sp), `sheetRowLabel` (12sp/400/0.1sp), `sheetStepValue` (13sp/500/tnum/-0.2sp), `sheetChip` (10sp/500/0.4sp), `sheetModeTab` (11sp/500/0.1sp), `sheetCta` (13sp/500/0.1sp), `peekStatus` (10sp/400/0.1sp).

## 8. Testing

Per the Phase-1/2/R1/R2 precedent, pixel-level Compose UI has **no Robolectric / Compose-UI-test layer** — no new UI unit tests. Verification = build + lint + the existing suite staying green.

The **one** new testable seam: the stepper-bounds helper (§4.4) is pure Kotlin — gets JVM tests (clamp at min/max, step increments, the clip/repeats/wait ranges). Predicted: one new test class.

Gates: `:app:assembleDebug` OK · `:app:testDebugUnitTest` = baseline + the new bounds tests, 0 failures · `:app:lintDebug` ≤ baseline.

## 9. Hard Invariants (must stay untouched)

- `service/**`, `dualrecord/**`, the recording pipeline.
- `WarningId` / `WarningPrecedence` / `WarningCenter` / the Start-gate region in `RecordScreen.kt`.
- `RovaSettings` persistence — the write-through path is reused, not changed.
- `RecordChromeTokens` / `RecordChrome.kt` (Phase 2) — not modified by this work.
- The mode-switch enable rules (P+L gating).

## 10. Out of Scope

- **P+L mode framing** on the record screen (`01-record-home.html` row 3) — sub-project 2, its own spec → plan → PR.
- Decorative camera overlays on the *record* screen (rule-of-thirds grid, vignette, focus reticle) — Phase 3.
- Any change to recording behaviour, the dual-camera pipeline, or settings semantics beyond the inline-edit UI.

## 11. Open Risks

1. `RecordEditSheets.kt` / `SheetTarget` may have a consumer outside the two known files — verified in the plan's first task.
2. The app's `resolution` string set vs the mockup's HD/FHD/4K labels — the deleted `QualityEditSheet` is the source of truth for the mapping.
3. Sheet open during an active session — controls disabled, peek-status reflects the live state; confirmed against today's `modeEnabled` behaviour.
