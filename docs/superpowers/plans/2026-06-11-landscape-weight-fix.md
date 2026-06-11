# Landscape Weight-Fix (spec §11 D1–D5) + Phase B Cleanup — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **BUILD-ENV CONSTRAINT (load-bearing):** subagents are **EDIT-ONLY** — they must NOT run any
> gradle command (a broken `kotlin-postedit` hook corrupts the `built_in_kotlinc` cache when
> gradle runs per-save). The controller runs ONE build per batch, after `./gradlew --stop`,
> killing stray `java` processes, and clearing `app/build/kotlin` + the `built_in_kotlinc`
> cache dirs if a previous build corrupted them. Steps below that say "Run:" are executed by
> the CONTROLLER at the batch points marked, not by the implementing subagent.

**Goal:** Apply the owner-approved density fixes (slim landscape config cells, 380dp-capped settings side panel, slimmer Save CTA) to the Phase-A "rotate, don't redesign" landscape chrome, then delete the dead ADR-0029 §B code and amend the ADR.

**Architecture:** Topology is UNCHANGED from Phase A (commit `838d279`) — same `LandscapeRotation` mapping, same cluster edges, same rail orders. Only *density* changes, all token-driven. Spec: `docs/superpowers/specs/2026-06-10-landscape-rotate-not-redesign-design.md` §11 (amendment, owner-approved via `landscape_record_mockup.html`).

**Tech Stack:** Kotlin / Jetpack Compose (BOM 2025.01.01), single `:app` module, JVM-only unit tests, 28 `check*` preBuild gates.

**Branch:** `feat/liquid-glass-landscape-chrome` (already checked out; do NOT create a new branch).

**Testing note:** Tasks 1–3 are Compose-UI density changes and dead-code deletion — no new pure logic, so **no new JVM tests** (project policy: JVM-only tests; Compose layout is device-verified at Task 5). The full existing suite must stay GREEN; `ChromeSlotPlacementTest` and `LandscapeRotationTest` pin the placement/mapping invariants and must NOT be edited.

---

## File map

| File | Task | Change |
|------|------|--------|
| `app/src/main/java/com/aritr/rova/ui/theme/RovaTokens.kt` | 1 | add `cellValueCompact`, `cellKeyCompact` text styles |
| `app/src/main/java/com/aritr/rova/ui/theme/RecordChromeTokens.kt` | 1 | add `landscapeCellGap` |
| `app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt` | 1, 3 | compact density in landscape branch; delete `RecordConfigCardLandscape`, `RecordNavRail` |
| `app/src/main/java/com/aritr/rova/ui/theme/SettingsSheetTokens.kt` | 2, 3 | re-document `sideSheetWidth` as cap; add `ctaPaddingVCompact`; delete 4 dead tokens |
| `app/src/main/java/com/aritr/rova/ui/screens/SettingsSheet.kt` | 2, 3 | cap panel width; slim CTA; delete `CompactSteppers`/`CompactStepperCell` + `compact` param |
| `docs/adr/0029-capture-topology-orientation-policy.md` | 4 | supersede §B (B1–B6) with §B′ |

---

### Task 1: D1 — compact config-cell density in landscape

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/theme/RovaTokens.kt` (after `cellKey`, ~line 71)
- Modify: `app/src/main/java/com/aritr/rova/ui/theme/RecordChromeTokens.kt` (~line 182, near `settingsWrapGap`)
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt` (`RecordSettingsCard` landscape branch ~line 375-393, `ModeCycleChip` ~line 466, `SettingsCell` ~line 532)

- [ ] **Step 1: Add compact text styles to RovaTokens.kt**

Insert directly after the `cellKey` style (line 71's closing paren):

```kotlin

    /**
     * Landscape compact config-cell pair (rotate-spec §11 D1, 2026-06-11). The
     * landscape config column renders the SAME 5 cells at reduced density so the
     * vertical strip doesn't visually dominate the nav rail (the Phase-A NO-GO).
     * ~0.875 × the portrait cellValue/cellKey scale, per the approved
     * landscape_record_mockup.html "Slim" weight.
     */
    val cellValueCompact: TextStyle = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Medium,
        fontSize = 10.5.sp,
        fontFeatureSettings = "tnum"
    )

    /** Compact sibling of [cellKey] — see [cellValueCompact]. */
    val cellKeyCompact: TextStyle = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Normal,
        fontSize = 7.sp,
        letterSpacing = 0.7.sp
    )
```

- [ ] **Step 2: Add the landscape cell gap token to RecordChromeTokens.kt**

Insert directly after `val settingsWrapGap = 7.dp` (line 182):

```kotlin
    /**
     * Rotate-spec §11 D1 — vertical gap between compact landscape config cells.
     * Tighter than the previous hardcoded 10.dp so the column reads as one slim
     * chip stack, not a tower (portrait uses weights + dividers instead).
     */
    val landscapeCellGap = 6.dp
```

- [ ] **Step 3: Thread `compact` through SettingsCell**

In `RecordChrome.kt`, replace the whole `SettingsCell` composable (~lines 531–549):

```kotlin
@Composable
private fun SettingsCell(key: String, value: String, modifier: Modifier, readOnly: Boolean, compact: Boolean = false) {
    Column(modifier = modifier.padding(horizontal = RecordChromeTokens.settingsCellPaddingH), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = if (compact) RovaTokens.cellValueCompact else RovaTokens.cellValue,
            color = if (readOnly) RecordChromeTokens.cellValueReadOnlyText else RecordChromeTokens.cellValueText,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        Text(
            key.uppercase(),
            style = if (compact) RovaTokens.cellKeyCompact else RovaTokens.cellKey,
            color = RecordChromeTokens.cellKeyText,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}
```

- [ ] **Step 4: Thread `compact` through ModeCycleChip**

In `ModeCycleChip` (~line 466): add a parameter `compact: Boolean = false,` after `enabled: Boolean,`. Then inside its `Column`, swap the two text styles:
- `Text(mode, style = RovaTokens.cellValue, ...)` → `style = if (compact) RovaTokens.cellValueCompact else RovaTokens.cellValue`
- `Text(stringResource(R.string.record_cell_mode), style = RovaTokens.cellKey, ...)` → `style = if (compact) RovaTokens.cellKeyCompact else RovaTokens.cellKey`

Nothing else in the chip changes (gradient, glyph, combinedClickable stay).

- [ ] **Step 5: Use compact density in the landscape branch of RecordSettingsCard**

Replace the landscape `else` branch body (~lines 375–393) with:

```kotlin
            } else {
                // LANDSCAPE — the SAME pill rotated to a vertical column on the cluster
                // edge: identical 5 cells in rotation-mapped order, same SettingsCell /
                // ModeCycleChip widgets, no weights (vertical). All 5 cells incl. Wait.
                // COMPACT density (rotate-spec §11 D1): slimmer type + gap so the column
                // doesn't dominate the rail — owner NO-GO 2026-06-11 on full density.
                val cells = listOf<@Composable () -> Unit>(
                    { SettingsCell(stringResource(R.string.record_cell_clip), recordClipValue(durationSeconds), Modifier, readOnly = false, compact = true) },
                    { SettingsCell(stringResource(R.string.record_cell_repeats), recordRepeatsValue(loopCount), Modifier, readOnly = false, compact = true) },
                    { SettingsCell(stringResource(R.string.record_cell_wait), recordWaitValue(intervalMinutes), Modifier, readOnly = false, compact = true) },
                    { SettingsCell(stringResource(R.string.record_cell_quality), quality, Modifier, readOnly = false, compact = true) },
                    { ModeCycleChip(mode = mode, onCycleMode = onCycleMode, onLongPress = onOpenSheet, enabled = !dimmed, compact = true) },
                )
                Column(
                    modifier = interaction,
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(RecordChromeTokens.landscapeCellGap),
                ) {
                    railOrder(cells, sense).forEach { it() }
                }
            }
```

(Diff vs current: `compact = true` on all 5 cells; `Arrangement.spacedBy(10.dp)` → `Arrangement.spacedBy(RecordChromeTokens.landscapeCellGap)`; two comment lines added.)

- [ ] **Step 6 (CONTROLLER, batched after Task 3): compile check** — see Task 5 Step 1.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/theme/RovaTokens.kt app/src/main/java/com/aritr/rova/ui/theme/RecordChromeTokens.kt app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt
git commit -m "feat(record): compact landscape config-cell density (rotate-spec §11 D1)"
```

---

### Task 2: D2 + D3 — settings side panel: 380dp cap + slim Save CTA

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/theme/SettingsSheetTokens.kt` (~lines 56–72)
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/SettingsSheet.kt` (`SettingsSidePanel` ~lines 387–410 and CTA ~line 466)

- [ ] **Step 1: Re-document the side-sheet tokens**

In `SettingsSheetTokens.kt`, replace the block from the `// ── Landscape side-anchored sheet` comment (line 56) through `val sidePanelPaddingV = 10.dp` (line 72) with:

```kotlin
    // ── Landscape side-anchored sheet (rotate-spec §11 D2, 2026-06-11) ──
    /**
     * Landscape side-panel width CAP — the portrait sheet's silhouette. The Phase-A
     * derived width (availableWidth − peekHeight ≈ 76% of a 2.16:1 window) was
     * owner-rejected as "desktop panel"; what transfers across the aspect swap is
     * portrait's SILHOUETTE, not its proportion. Floor below = split-screen guard.
     */
    val sideSheetWidth = 380.dp
    /** Floor for pathologically narrow windows (split-screen); loses to no cap. */
    val sideSheetMinWidth = 360.dp
```

(This deletes `sideSheetRailInset` and `sidePanelPaddingV`, keeps `sideSheetWidth` + `sideSheetMinWidth` with new docs. Compile stays green because their only consumers die in this task / are already gone.)

- [ ] **Step 2: Add the compact CTA token**

In the `// ── Save CTA ──` block (after `val ctaPaddingV = 16.dp`):

```kotlin
    /** Landscape Save CTA vertical padding (rotate-spec §11 D3) — slimmed to scale
     *  with the 380dp panel; portrait keeps [ctaPaddingV]. */
    val ctaPaddingVCompact = 12.dp
```

- [ ] **Step 3: Cap the panel width**

In `SettingsSheet.kt` `SettingsSidePanel`, replace (~lines 387–395):

```kotlin
        // PR-β′ (spec 2026-06-10) — the portrait bottom sheet, ROTATED to the cluster
        // edge: full height, portrait-DERIVED width (availableWidth − peek, mirroring
        // portrait's "peek strip + sheet fills the rest"), so the same screen-
        // proportion + visual weight as portrait. Live preview shows in the far-side
        // gap — no added scrim (§6.6). Same composition as portrait: grip + title +
        // stacked rows + Save CTA; SettingsContent scrolls when it exceeds the height.
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val panelWidth = (maxWidth - SettingsSheetTokens.peekHeight)
                .coerceAtLeast(SettingsSheetTokens.sideSheetMinWidth)
```

with:

```kotlin
        // Rotate-spec §11 D2 (2026-06-11) — the portrait bottom sheet, ROTATED to the
        // cluster edge: full height, width = the portrait SILHOUETTE (sideSheetWidth
        // cap; the Phase-A availableWidth − peek derivation read as a desktop panel —
        // owner NO-GO). Live preview fills the far side — no added scrim (§6.6). Same
        // composition as portrait: grip + title + stacked rows + Save CTA;
        // SettingsContent scrolls (Repeats/Wait/Quality/Save reachable below the fold).
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val panelWidth = (maxWidth - SettingsSheetTokens.peekHeight)
                .coerceAtMost(SettingsSheetTokens.sideSheetWidth)
                .coerceAtLeast(SettingsSheetTokens.sideSheetMinWidth)
```

(Normal landscape phone: `maxWidth − 212dp` ≫ 380 ⇒ cap 380 wins. Split-screen: floor 360 wins. The derivation term only matters between the two bounds.)

- [ ] **Step 4: Slim the CTA**

Still in `SettingsSidePanel`, in the Save CTA `Box` (~line 466), change:

```kotlin
                        .padding(vertical = SettingsSheetTokens.ctaPaddingV),
```

to:

```kotlin
                        .padding(vertical = SettingsSheetTokens.ctaPaddingVCompact),
```

⚠️ There are TWO `.padding(vertical = SettingsSheetTokens.ctaPaddingV)` occurrences in this file — line ~342 is the PORTRAIT bottom sheet (do NOT touch); line ~466 is inside `SettingsSidePanel` (change this one only).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/theme/SettingsSheetTokens.kt app/src/main/java/com/aritr/rova/ui/screens/SettingsSheet.kt
git commit -m "feat(record): cap landscape settings panel at portrait silhouette + slim Save (rotate-spec §11 D2-D3)"
```

---

### Task 3: Phase B cleanup — delete dead §B code

`ChromeSlotPlacement` is NOT dead (6 live slots in RecordScreen) — leave it and its test alone.

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/SettingsSheet.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/theme/SettingsSheetTokens.kt`

- [ ] **Step 1: Verify dead-ness before deleting** (Grep, no gradle):
  - `RecordConfigCardLandscape(` and `RecordNavRail(` → only their definitions in `RecordChrome.kt` (no call sites).
  - `CompactSteppers(`/`CompactStepperCell(` → only definition + the `if (compact)` call inside `SettingsContent`.
  - `compact = true` → zero hits in `app/src` (no caller ever passes it).
  If any unexpected caller appears, STOP and report (status: BLOCKED) instead of deleting.

- [ ] **Step 2: Delete `RecordConfigCardLandscape`** in `RecordChrome.kt` — the full composable INCLUDING its KDoc (the block starting `/** * PR-β (ADR-0029 §B4) — compact landscape config-summary card…` through the composable's closing brace, ~lines 398–446).

- [ ] **Step 3: Delete `RecordNavRail`** in `RecordChrome.kt` — full composable + KDoc (block starting `/** * PR-β (ADR-0029 §B2) — landscape grouped nav rail…`, ~lines 653–679).

- [ ] **Step 4: Delete `CompactSteppers` + `CompactStepperCell`** in `SettingsSheet.kt` — both composables + the shared KDoc (`/** * Landscape height-fit variant of the three steppers…`, ~lines 942–1032).

- [ ] **Step 5: Remove the dead `compact` path from `SettingsContent`** in `SettingsSheet.kt`:
  - Delete the parameter `compact: Boolean = false,` (~line 583).
  - Replace the `if (compact) { CompactSteppers(...) SheetRowDivider() QualityRow(...) } else { …four StepperRow/QualityRow rows… }` block (~lines 633–674) with just the `else` branch body (the four `StepperRow`s + `SheetRowDivider()`s + `QualityRow`), un-indented one level.

- [ ] **Step 6: Delete dead tokens** in `SettingsSheetTokens.kt`: the `// ── Landscape compact 3-up stepper group (PR-β5b) ──` block — `compactCellGap`, `compactCellLabelGap`, `sidePanelContentMaxHeight` (+ their KDocs, ~lines 143–149). (`sideSheetRailInset`/`sidePanelPaddingV` already went in Task 2.)

- [ ] **Step 7: Sweep for now-unused imports** in both Kotlin files (e.g. anything only the deleted code used). Grep each suspicious import's symbol before removing.

- [ ] **Step 8: Verify zero dangling references** (Grep, no gradle): `RecordConfigCardLandscape|RecordNavRail|CompactStepper|compactCellGap|compactCellLabelGap|sidePanelContentMaxHeight|sideSheetRailInset|sidePanelPaddingV` over `app/src` → expect 0 hits.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt app/src/main/java/com/aritr/rova/ui/screens/SettingsSheet.kt app/src/main/java/com/aritr/rova/ui/theme/SettingsSheetTokens.kt
git commit -m "refactor(record): delete dead ADR-0029 §B landscape chrome (rail, docked card, 3-up steppers)"
```

---

### Task 4: ADR-0029 — supersede §B with §B′

**Files:**
- Modify: `docs/adr/0029-capture-topology-orientation-policy.md`

- [ ] **Step 1: Retitle and preface the old section**

Change the heading `## PR-β amendment (2026-06-10, owner-ratified): landscape Record-home chrome` to:

```markdown
## §B — PR-β amendment (2026-06-10): landscape Record-home chrome — **WITHDRAWN, superseded by §B′**
```

and insert directly under it:

```markdown
> **WITHDRAWN 2026-06-11.** Shipped as Phase β-interim, then owner-rejected: it reads as a
> *separate landscape layout*, not the portrait UI rotated. §B′ below replaces it. The §B code
> (grouped `RecordNavRail`, docked `RecordConfigCardLandscape`, `CompactSteppers` 3-up,
> `sideSheetRailInset`-band side panel) is deleted. B1–B6 are kept verbatim below for history
> only — do not implement against them.
```

- [ ] **Step 2: Append §B′ after the historical B1–B6 text** (before `## Enforcement`):

```markdown
## §B′ — "Rotate, don't redesign" (2026-06-11, owner-ratified; supersedes §B)

Authoritative spec: `docs/superpowers/specs/2026-06-10-landscape-rotate-not-redesign-design.md`
(+ its §11 weight amendment). Approved mock: `landscape_record_mockup.html`. Native reference:
the stock camera app (RZCYA1VBQ2H).

**B′1. Principle.** Portrait is the source of truth. Landscape is the identical widget set —
same hierarchy, grouping, order, and interaction model — re-anchored by the device rotation,
every widget kept upright (rotation-aware placement; `graphicsLayer` rotation is forbidden on
interactive chrome). There is no landscape information architecture.

**B′2. Anchor classes.** The bottom cluster (config strip · Library/FAB/Settings nav · settings
sheet) is **device-anchored**: glued to the physical portrait-bottom edge — right for
`ROTATION_90` (sense A, rail order reversed), left for `ROTATION_270` (sense B, identity order),
per the pure `LandscapeRotation` mapping (`landscapeSense` / `clusterEdge` / `railOrder`,
JVM-pinned). The status chip and flash/lens controls are **screen-anchored**: top-start /
top-end in BOTH senses, exactly like the native camera's indicators. Nav stays edge-most,
config inboard — portrait's depth order, rotated.

**B′3. Density, not relocation (the §11 weight amendment).** The landscape config column renders
the same 5 cells (Clip · Repeats · Wait · Quality · Mode) at **compact density**
(`cellValueCompact`/`cellKeyCompact`, `landscapeCellGap`); the settings side panel is the
portrait sheet's **silhouette** (`sideSheetWidth` 380dp cap, full height, slimmed landscape Save
via `ctaPaddingVCompact`, body scroll) sliding from the cluster edge with portrait scrim parity.
A top-center config dock / three-zone landscape composition was explicitly rejected.

**B′4. Correctness invariant.** `VideoCapture.targetRotation` keeps being driven by the PR-α
`effectiveTargetRotation` + `OrientationEventListener` seam — chrome placement must never
acquire responsibility for recorded-MP4 orientation. Device acceptance: rotate both senses;
nav/cluster handedness matches the stock camera; recorded MP4 orientation correct in both.
```

- [ ] **Step 3: Commit**

```bash
git add docs/adr/0029-capture-topology-orientation-policy.md docs/superpowers/specs/2026-06-10-landscape-rotate-not-redesign-design.md docs/superpowers/plans/2026-06-11-landscape-weight-fix.md
git commit -m "docs(adr): ADR-0029 §B withdrawn -> §B' rotate-don't-redesign + spec §11 weight amendment"
```

---

### Task 5: Build, device smoke, finish (CONTROLLER ONLY)

- [ ] **Step 1: Clean build environment, then full verify**

```powershell
./gradlew --stop
# kill stray daemons if CPU-pinned: Get-Process java -ErrorAction SilentlyContinue | Stop-Process -Force
./gradlew :app:testDebugUnitTest 2>&1 | Tee-Object gradle_weightfix_tests.log
./gradlew :app:assembleDebug 2>&1 | Tee-Object gradle_weightfix_apk.log
```

Expected: tests GREEN (≥1241, 0 failures); assemble GREEN (all 28 `check*` gates pass). If the kotlinc incremental cache MD5-corrupts: `./gradlew --stop`, kill `java`, delete `app/build/kotlin` + the `built_in_kotlinc` cache dirs, rebuild.

- [ ] **Step 2: Install + device smoke (owner has the device)**

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Smoke matrix (owner, physical rotation — One UI ignores `user_rotation`):
1. Portrait idle + settings — byte-identical to master behavior.
2. Sense A (ROTATION_90): slim config column inboard-right, nav rail edge-right (Settings top / FAB / Library bottom), status top-left, cam top-right.
3. Sense B (ROTATION_270): mirror (identity order), same screen-anchored top chrome.
4. Settings open in both senses: 380dp panel from cluster edge, preview visible on far side, **scroll reaches Repeats/Wait/Quality/Save**, slim Save.
5. Record a clip in each sense → MP4 orientation correct (spec §6.2).

- [ ] **Step 3: A6′ checkpoint** — present screenshots; owner GO required before PR.

- [ ] **Step 4: On GO** — use `superpowers:finishing-a-development-branch` (expect option 2: push + PR to master).

---

## Self-review

- **Spec coverage:** D1→Task 1, D2→Task 2 (cap), D3→Task 2 (CTA + scroll note; scroll already exists in `SettingsContent`), D4→no code (Phase A already screen-anchors status/cam; ADR §B′2 records it), D5→no code. §7 ADR impact→Task 4. §8 revert list→Task 3 (minus `ChromeSlotPlacement`, verified live).
- **Placeholders:** none — every code step carries exact code/text.
- **Type consistency:** `compact` param naming matches across `SettingsCell`/`ModeCycleChip`; token names (`cellValueCompact`, `cellKeyCompact`, `landscapeCellGap`, `ctaPaddingVCompact`, `sideSheetWidth`, `sideSheetMinWidth`) consistent across Tasks 1–2 and ADR §B′3.
