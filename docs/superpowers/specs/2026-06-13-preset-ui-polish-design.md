# Preset UI Polish — Design Spec

**Date:** 2026-06-13
**Status:** Draft — awaiting owner spec-review
**Scope discipline:** Refinement, **not** redesign. Rova's hands-free, set-and-forget recording workflow is the binding constraint. No new interaction model, no new controls. (The "Control Dock" interaction-model exploration earlier this session was deliberately **dropped** — it drifted Rova toward a tap-happy camera app it isn't. Mockups archived under `mockups/brainstorm/`, gitignored.)

**Goal:** Make the preset picker feel cohesive and intentional, fix the ragged-chip chaos, and present built-in vs user-created presets clearly even as customs grow — plus two small adjacent refinements (popup scroll discoverability, control-strip type polish).

**Owner-ratified decisions (this session):**
- Preset layout → **A · Uniform tiles** (2-column, equal size, with a quiet summary line).
- Popup scroll → subtle **bottom fade + peeking half-row** (no scrollbar, no clutter).
- Strip → **sentence-case labels** + verify type rhythm (keep the 5-cell strip).

---

## 1. Problem statement (root cause)

`PresetSheetChip` (SettingsSheet.kt) is an **outline pill that sizes to its text**, laid out in a wrapping `FlowRow` (`PresetChipFlow`). Consequences visible in `Screenshot_20260613_205501.png`:

- Every chip is a **different width** (name-length driven) → ragged right edge.
- `FlowRow` wrapping produces **orphans** ("Continuous" alone, "+ Save" alone).
- No real visual structure separating built-in from custom beyond a tiny `BUILT-IN` label.
- **Scales badly:** each new custom adds another differently-sized pill to the cloud.

`PresetGroups` is the **single shared composable** rendered by *both* surfaces — the floating square (`FloatingSettingsPanel`) and the full sheet (`SettingsContent`/`SettingsSidePanel` in `SettingsSheet.kt`). Fixing `PresetGroups` fixes both at once.

---

## 2. Refinement A — Uniform preset tiles (priority)

### 2.1 Layout
Replace `PresetChipFlow` (FlowRow) + `PresetSheetChip` (content-width pill) with a **uniform 2-column tile grid**:

- **`PresetTileGrid`** — chunks a preset list into rows of 2; each cell is `Modifier.weight(1f)` so the two columns are **always equal width**, independent of name length. A trailing odd cell pairs with a `Spacer(weight(1f))`. Fixed inter-tile gap (reuse `SettingsSheetTokens.chipGroupGap`). This is a plain `Column { Row { … } }` construction — **not** `LazyVerticalGrid` (which can't nest inside the existing `verticalScroll` column without nested-scroll conflicts).
- **`PresetTile`** — fixed min-height (≥48dp a11y target; design ≈58dp), two-line content:
  - **Name** — 1 line, `RovaTokens.sheetChip`-ish weight ~620, `maxLines=1`, `TextOverflow.Ellipsis` (long names like "Long stakeout 4h" truncate, never wrap or widen the tile).
  - **Summary** — quiet single line, e.g. `30s · ×20 · FHD`. Built by composing the **existing** record value helpers (`recordClipValue(duration)`, `recordRepeatsValue(loopCount)`) + the resolution code, joined by a non-translatable `·` glyph. Continuous (`loopCount == RecordSettingBounds.REPEATS_CONTINUOUS == -1`) renders as `∞` (reuse the existing "until stop" representation). No-wait (`interval == 0`) is simply omitted from the compact summary (clip · repeats · quality only), matching the approved mockup. *If a unit-testable seam is wanted, extract a pure `PresetTileSummary.format(...)`; otherwise compose inline.*

### 2.2 Grouping & scaling
- Two grouped grids under existing section labels: **Built-in** (the 4 read-only built-ins) and **My presets** (growing custom list), preserving the current `builtIns` / `customs` partition and the **Edit** toggle on the My-presets header (reveals inline `×` on custom tiles).
- Scales by adding rows downward inside the panel's existing vertical scroll → works with the Refinement-B scroll-fade for "more below".
- The **`+ New` tile** is the existing **Save-current** affordance (`SavePresetChip` today) restyled as a dashed/`+` tile. **Behavior unchanged:** it appears only when the live config matches no preset (`activePresetId == null`) and the sheet is editable — it saves the *current* config (ADR-0026), it is not a blank-create. (Open item 4.1 — confirm wording.)

### 2.3 Selected state
- Selected tile = accent-gradient ring + faint accent fill (`palette.accent`→`palette.accent2`, reuse the existing `selectedBrush`) **plus a check badge** — selection must be conveyed by **more than colour** (WCAG 1.4.1, ADR-0020), exactly as the current chip's leading check does.

### 2.4 Accessibility — must be preserved 1:1 from `PresetSheetChip`
The refactor must carry over **every** existing a11y property (these already passed codex a11y review and the `checkA11yClickableHasRole` gate):
- `combinedClickable(role = Role.Button, …)` on each tile — **the new gate `checkA11yClickableHasRole` requires the `Role.`**.
- `semantics { selected = …; contentDescription = presetSpokenDescription(preset); if(!enabled) disabled() }` — keep the **spoken** description (full "Standard preset, 30 second clips, every… ×20, FHD") distinct from the visible compact summary.
- Long-press-to-delete on customs with `onLongClickLabel` (WCAG 2.5.1/2.1.1 non-gesture equivalent) **and** the inline `×` (Edit mode) as the sighted-touch equivalent, each `Role.Button` + labelled.
- `focusHighlight(shape)`, `heightIn(min = 48.dp)`, `alpha 0.5` when disabled.

### 2.5 Tokens
Add tile tokens to `SettingsSheetTokens` (tile min-height, radius, fill `chipOff`-equivalent, stroke, selected fill/stroke, summary text color). Reuse existing chip color tokens where they already fit to avoid token sprawl.

---

## 3. Refinement B — Popup scroll discoverability

The floating square (`FloatingSettingsPanel`, `verticalScroll` at ~line 241) and the full sheet (`SettingsContent`, owns its own scroll) hide their lower content (Quality / Orientation / **Preset**) below the fold with **zero** scroll cue.

- Add a **bottom fade-out gradient** overlay at the base of the scroll viewport: `Brush.verticalGradient(transparent → sheetFill)`, ~40–48dp tall, `pointerInput`-transparent, drawn above the scrolled content.
- **Truthful, not decorative:** gate its visibility on `scrollState.canScrollForward` — the fade shows only while there's more below and disappears at the bottom, so it never lies. (A purely static fade is the fallback if `canScrollForward` proves awkward in the square's `weight(1f, fill=false)` column.)
- A small downward chevron `⌄` cue centered in the fade is optional; the gradient alone is usually enough.
- **Peek:** ensure bottom content padding leaves the next row partially visible rather than ending flush at the fold (layout/padding tweak, best-effort).
- **a11y / reduce-motion:** the fade is **static** (alpha toggles with scroll position, no animation) → not subject to `checkA11yAnimationGated`; it is decorative → no semantics, must not intercept touch or focus.
- Apply to **both** scroll surfaces for consistency (floating panel is the primary reported case).

---

## 4. Refinement C — Control-strip type polish

The strip (`RecordSettingsCard` / `SettingsCell` in RecordChrome.kt) is already largely AA-tuned (divider 22dp@7%, chevron `settingsArrow` 0.60, `cellKeyText` 0.65, `cellValueText` 0.88). So this is **small and honest**:

- **Sentence-case labels:** remove the `key.uppercase()` call at `RecordChrome.kt:598` so labels render as the resource value's natural case (e.g. "Length" not "LENGTH"), dropping the ALL-CAPS "shout". Confirm the `record_cell_*` resource strings read as sentence-case (en + es); adjust the resource *values* if any are authored upper-case. Keep the existing `cellKey` letter-spacing.
- **Type rhythm:** verify `RovaTokens.cellValue` (≈13sp) / `cellKey` (≈8sp) are consistent across all 5 cells incl. the read-only Mode/Locked cells; no change unless an inconsistency surfaces.
- **No structural change** — same 5 cells, same counter-rotation (`spinDegrees`), same gesture contract. Divider/chevron/colors untouched (already tuned).

---

## 5. Gate, ADR & i18n impact

- **`checkA11yClickableHasRole`** — new `PresetTile` / `+New` / inline-`×` clickables **must** carry `role = Role.Button`. Preserved by design (§2.4). No gate edit.
- **`checkNoHardcodedUiStrings`** — tile summary reuses existing localized value helpers + the `·`/`∞` glyphs (non-translatable symbols); strip labels stay resource-backed (en + es). No new translatable literals beyond any that need an es entry.
- **`checkA11yAnimationGated`** — scroll-fade is static; no gating needed. If any fade transition is animated, gate via `rememberReduceMotion()`.
- **`checkRecordChromeLockSingleSite` / `checkGlassSurfaceRoleUsage`** — strip change is cosmetic (label case); does not read lock-state or alter `GlassSurface` roles. No impact.
- **ADR:** **No new ADR and no amendment.** ADR-0026 governs preset *behavior* (config-bundle, orientation-orthogonal, built-in/custom partition, save-when-custom) — all **unchanged**. This spec changes only *presentation*. (If the owner wants it recorded, a one-line presentation note can be appended to ADR-0026, but it is not required by the invariant→check convention.)

---

## 6. Testing

- **JVM unit tests only** (project policy). If `PresetTileSummary` is extracted as a pure helper, cover: each built-in (Quick Sample/Standard/Long Session/**Continuous→∞**), a no-wait custom, a long-name ellipsis case, HD/FHD/UHD resolutions. If composed inline from existing helpers, those helpers already have coverage.
- Existing `PresetMatcher`, `BuiltInPresets`, `PresetSaveValidator`, `PresetJson` tests must stay green (no behavior change).
- No new instrumented/Compose tests (policy); manual a11y check that TalkBack still announces selected state + spoken description + delete action on the new tiles.
- Baseline 1241 tests / 0-0-0 preserved; any new pure helper lands its tests in the same PR.

---

## 7. Files touched (units & responsibilities)

| File | Change |
|------|--------|
| `ui/screens/SettingsSheet.kt` | Replace `PresetChipFlow`/`PresetSheetChip` with `PresetTileGrid`/`PresetTile`; restyle `SavePresetChip` → `+ New` tile; keep `PresetGroups` as the single shared entry (both surfaces). |
| `ui/screens/FloatingSettingsPanel.kt` | Add bottom scroll-fade overlay on its `verticalScroll` column. |
| `ui/screens/RecordChrome.kt` | Drop `key.uppercase()` (sentence-case strip labels); verify cell type tokens. |
| `ui/theme/SettingsSheetTokens.kt` | Add tile tokens (min-height/radius/fill/stroke/selected/summary color). |
| `ui/theme/RovaTokens.kt` | Tile name/summary text styles **if** existing chip styles don't fit. |
| `data/PresetTileSummary.kt` (+ test) | **Optional** pure summary formatter seam (house pattern) if inline composition isn't clean. |
| `res/values/strings.xml` + `values-es/strings.xml` | Only if a label resource needs a case fix or a new symbol/string is introduced. |

**One PR, three cohesive commits** (tiles → scroll-fade → strip), `:app:lintDebug`-gated build + `testDebugUnitTest` green, then owner device-smoke. codex review at the **implementation/diff** stage (a11y-preservation of the chip→tile refactor is the one thing worth a second set of eyes).

---

## 8. Open items for owner spec-review

1. **`+ New` tile semantics** — keep today's behavior (appears only when config is unsaved/Custom, saves the current config) and just restyle it? Or always show a persistent create tile? *Lean: keep existing behavior; restyle only.*
2. **Summary content** — `clip · repeats · quality` (approved mockup). Include **wait** too, or keep it to three? *Lean: three (omit wait) for compactness; wait is usually "None".*
3. **Scroll-fade reach** — both scroll surfaces, or floating panel only? *Lean: both, for consistency.*
4. **Strip labels** — confirm sentence-case is the desired direction (drop ALL-CAPS). *Lean: yes.*
