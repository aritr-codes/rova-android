# Preset UI Polish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the ragged content-width preset chips with a tidy uniform 2-column tile grid, add a truthful scroll-fade cue to the settings panel, and sentence-case the control-strip labels — all presentation-only, behavior unchanged.

**Architecture:** `PresetGroups` is the single shared composable rendered by both the floating square (`FloatingSettingsPanel`) and the full sheet (`SettingsContent`). We swap its internals (FlowRow of pills → chunked 2-col grid of equal-width tiles) so both surfaces fix at once, carrying over every a11y property 1:1. A pure `presetTileSummary` formatter (TDD) feeds each tile's summary line. A shared `ScrollFadeBottom` overlay, gated on `ScrollState.canScrollForward`, communicates "more below". The strip change is a one-line `.uppercase()` removal plus one resource case-fix.

**Tech stack:** Kotlin, Jetpack Compose, JUnit4 (JVM unit tests only — `testOptions.unitTests.isReturnDefaultValues = true`).

**Build/verify reality (house rules):**
- Subagents are **EDIT-ONLY**; the **controller runs all gradle**. Intermediate compile checks use `:app:compileDebugKotlin`; the authoritative gate-run is `:app:assembleDebug` at Task 6.
- `:app:lintDebug` is RED on a **pre-existing** `VaultAndroidOps` NewApi finding — use `assembleDebug`, not lint.
- Build-env recovery if the Kotlin incremental cache corrupts: `./gradlew --stop`, kill stray java, delete `app/build/kotlin` + `.gradle/kotlin`, rebuild.
- Commit only the files each task names. Leave untracked `gradle_*.log` alone.

---

## File Structure

| File | Responsibility | Action |
|------|----------------|--------|
| `app/src/main/java/com/aritr/rova/ui/screens/RecordSettingsFormat.kt` | Pure display formatters | **Add** `presetTileSummary(...)` |
| `app/src/test/java/com/aritr/rova/ui/screens/RecordSettingsFormatTest.kt` | JVM tests for the formatters | **Create** |
| `app/src/main/java/com/aritr/rova/ui/theme/SettingsSheetTokens.kt` | Sheet/tile color+metric tokens | **Add** tile tokens |
| `app/src/main/java/com/aritr/rova/ui/theme/RovaTokens.kt` | Text styles | **Add** `tileSummary` style |
| `app/src/main/java/com/aritr/rova/ui/screens/SettingsSheet.kt` | Preset tiles + scroll-fade overlay | **Refactor** `PresetGroups`; add `PresetTile`/`PresetTileGrid`/`NewPresetTile`/`ScrollFadeBottom`; remove `PresetChipFlow`/`PresetSheetChip`/`SavePresetChip`; wrap `SettingsContent` body scroll |
| `app/src/main/java/com/aritr/rova/ui/screens/FloatingSettingsPanel.kt` | Floating-square scroll-fade | Hoist scroll state + add fade overlay |
| `app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt` | Strip cells | Drop `key.uppercase()` |
| `app/src/main/res/values/strings.xml` | English labels | `record_cell_mode` MODE→Mode |
| `app/src/main/res/values-es/strings.xml` | Spanish labels | `record_cell_mode` MODO→Modo |

---

## Task 1: Pure tile-summary formatter (TDD)

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordSettingsFormat.kt` (append after `recordRepeatsStepperValue`, ~line 33)
- Create: `app/src/test/java/com/aritr/rova/ui/screens/RecordSettingsFormatTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/aritr/rova/ui/screens/RecordSettingsFormatTest.kt`:

```kotlin
package com.aritr.rova.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordSettingsFormatTest {
    @Test fun tileSummary_secondsClipFiniteLoop() {
        assertEquals("30 s · ×20 · FHD", presetTileSummary(30, 20, "FHD"))
    }

    @Test fun tileSummary_minuteClip() {
        assertEquals("1 m · ×60 · FHD", presetTileSummary(60, 60, "FHD"))
    }

    @Test fun tileSummary_continuousShowsInfinity() {
        assertEquals("1 m · ∞ · HD", presetTileSummary(60, -1, "HD"))
    }

    @Test fun tileSummary_quickSample() {
        assertEquals("10 s · ×3 · HD", presetTileSummary(10, 3, "HD"))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run (controller): `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.screens.RecordSettingsFormatTest"`
Expected: FAIL — `Unresolved reference: presetTileSummary`.

- [ ] **Step 3: Implement the formatter**

Append to `RecordSettingsFormat.kt` (after the `recordRepeatsStepperValue` function, ~line 33):

```kotlin

/**
 * Compact, glanceable summary for a preset TILE — `clip · repeats · quality`,
 * e.g. "30 s · ×20 · FHD" or "1 m · ∞ · HD". Wait is intentionally omitted
 * (usually "None") to keep the tile to one tidy line (preset-ui-polish spec
 * §2.1). Repeats render as "∞" for continuous (loopCount < 0) else "×N".
 * Reuses [recordClipValue] so the clip vocabulary matches the strip exactly.
 */
internal fun presetTileSummary(durationSeconds: Int, loopCount: Int, resolution: String): String {
    val repeats = if (loopCount < 0) "∞" else "×$loopCount"
    return "${recordClipValue(durationSeconds)} · $repeats · $resolution"
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run (controller): `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.screens.RecordSettingsFormatTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/RecordSettingsFormat.kt app/src/test/java/com/aritr/rova/ui/screens/RecordSettingsFormatTest.kt
git commit -m "feat(preset): pure presetTileSummary formatter + tests"
```

---

## Task 2: Tile tokens

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/theme/SettingsSheetTokens.kt` (after the "Quality chips" block, ~line 126)
- Modify: `app/src/main/java/com/aritr/rova/ui/theme/RovaTokens.kt` (after `sheetChip`, ~line 189)

- [ ] **Step 1: Add tile metric/color tokens**

In `SettingsSheetTokens.kt`, after the `chipOffText` line (~126), add:

```kotlin

    // ── Preset tiles (preset-ui-polish) ─────────────────────────────────
    val tileMinHeight = 56.dp
    val tileRadius = 16.dp
    val tileGap = 8.dp
    val tileFill = Color.White.copy(alpha = 0.045f)
    val tileStroke = Color.White.copy(alpha = 0.08f)
    /** Faint accent wash behind a selected tile (alpha applied to palette accent at call site). */
    val tileSelFillAlpha = 0.14f
```

(`Color` and `dp` are already imported in this file.)

- [ ] **Step 2: Add the summary text style**

In `RovaTokens.kt`, after the `sheetChip` style block (~line 189), add:

```kotlin

    val tileSummary: TextStyle = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Normal,
        fontSize = 9.sp,
        letterSpacing = 0.2.sp,
    )
```

(`TextStyle`, `Inter`, `FontWeight`, `sp` are already imported in this file.)

- [ ] **Step 3: Compile check (controller)**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (new tokens are unused so far — fine).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/theme/SettingsSheetTokens.kt app/src/main/java/com/aritr/rova/ui/theme/RovaTokens.kt
git commit -m "feat(preset): tile tokens (metrics + summary text style)"
```

---

## Task 3: Uniform tile grid + PresetGroups refactor

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/SettingsSheet.kt`
  - Rewrite `PresetGroups` body (currently ~1146-1215)
  - Replace `PresetChipFlow` (~1218-1227) + `PresetSheetChip` (~1237-1319) + `SavePresetChip` (~1326-1355) with `PresetTileGrid` + `PresetTile` + `NewPresetTile`
  - Keep `presetSpokenDescription` unchanged

This task is presentation-only and must preserve **every** a11y property from `PresetSheetChip` (the new `checkA11yClickableHasRole` gate requires `role = Role.Button`; `selected` semantics + `presetSpokenDescription` + long-press delete + 48dp min target are all WCAG/ADR-0020 obligations).

- [ ] **Step 1: Add the `TextOverflow` import**

In `SettingsSheet.kt` imports, add (if not present):

```kotlin
import androidx.compose.ui.text.style.TextOverflow
```

(`Box`, `fillMaxWidth`, `Spacer`, `Alignment`, `Brush`, `combinedClickable`, `Icons.Filled.Check/Close/Add`, `RoundedCornerShape`, `LocalGlassEnvironment`, `focusHighlight` are already imported — they're used by the code being replaced.)

- [ ] **Step 2: Replace the body of `PresetGroups`**

Replace the body inside `PresetGroups(...) { ... }` (the `Column { … }` from ~1146 to ~1215) with:

```kotlin
    val builtIns = presets.filter { it.isBuiltIn }
    val customs = presets.filter { !it.isBuiltIn }
    var editMode by remember { mutableStateOf(false) }
    // Auto-exit edit mode once the last custom is gone, so a re-grown list can't
    // reappear already in delete state. Done in an effect (not a composition-time
    // state write) per codex review 019eb252.
    LaunchedEffect(customs.isEmpty()) {
        if (customs.isEmpty()) editMode = false
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SettingsSheetTokens.sectionLabelGap),
    ) {
        SheetSectionLabel(stringResource(R.string.settings_sheet_section_builtin))
        PresetTileGrid(
            builtIns.map { preset ->
                @Composable {
                    PresetTile(
                        preset = preset,
                        selected = preset.id == activePresetId,
                        enabled = enabled,
                        onClick = { onApply(preset) },
                        onLongClick = null,
                    )
                }
            },
        )

        if (customs.isNotEmpty()) {
            Spacer(Modifier.height(SettingsSheetTokens.sectionLabelGap))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SheetSectionLabel(stringResource(R.string.settings_sheet_section_my_presets))
                Spacer(Modifier.weight(1f))
                if (enabled) {
                    val editShape = RoundedCornerShape(SettingsSheetTokens.chipRadius)
                    val editLabel = if (editMode) {
                        stringResource(R.string.settings_edit_sheet_done)
                    } else {
                        stringResource(R.string.settings_presets_edit)
                    }
                    Text(
                        editLabel,
                        style = RovaTokens.sheetSectionLabel,
                        color = SettingsSheetTokens.chipOffText,
                        modifier = Modifier
                            .clip(editShape)
                            .focusHighlight(editShape)
                            .clickable(role = Role.Button) { editMode = !editMode }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
            val customCells: List<@Composable () -> Unit> =
                customs.map { preset ->
                    @Composable {
                        PresetTile(
                            preset = preset,
                            selected = preset.id == activePresetId,
                            enabled = enabled,
                            onClick = { onApply(preset) },
                            // Long-press deletes user customs (built-ins are read-only).
                            onLongClick = { onRequestDelete(preset) },
                            deletable = editMode,
                            onDelete = { onRequestDelete(preset) },
                        )
                    }
                } + if (activePresetId == null && enabled) {
                    listOf<@Composable () -> Unit> { { NewPresetTile(onClick = onRequestSave) } }
                } else {
                    emptyList()
                }
            PresetTileGrid(customCells)
        } else if (activePresetId == null && enabled) {
            // No customs yet, but the live config is unsaved → offer the New tile.
            PresetTileGrid(listOf<@Composable () -> Unit> { { NewPresetTile(onClick = onRequestSave) } })
        }
    }
```

> Note: the `listOf<@Composable () -> Unit> { { NewPresetTile(...) } }` uses `listOf`'s vararg with a single lambda element — the inner `{ NewPresetTile(...) }` is the composable. If the compiler complains about lambda-as-vararg ambiguity, write it explicitly as `listOf<@Composable () -> Unit>({ NewPresetTile(onClick = onRequestSave) })`.

- [ ] **Step 3: Replace `PresetChipFlow` / `PresetSheetChip` / `SavePresetChip` with `PresetTileGrid` / `PresetTile` / `NewPresetTile`**

Delete the three old composables (`PresetChipFlow`, `PresetSheetChip`, `SavePresetChip`) and insert:

```kotlin
/**
 * Equal-width 2-column tile grid. Chunks [cells] into rows of two, each cell
 * `weight(1f)` so columns are identical width regardless of name length; a
 * trailing odd cell pairs with a Spacer. Plain Column/Row (NOT LazyVerticalGrid)
 * so it nests safely inside the panel's existing verticalScroll.
 */
@Composable
private fun PresetTileGrid(cells: List<@Composable () -> Unit>) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SettingsSheetTokens.tileGap),
    ) {
        cells.chunked(2).forEach { rowCells ->
            Row(horizontalArrangement = Arrangement.spacedBy(SettingsSheetTokens.tileGap)) {
                rowCells.forEach { cell ->
                    Box(Modifier.weight(1f)) { cell() }
                }
                if (rowCells.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * Uniform preset tile — fixed min-height, name (1 line, ellipsis) over a quiet
 * [presetTileSummary]. Selected = faint accent wash + gradient ring + a check
 * badge (selection by more than colour, WCAG 1.4.1). Custom tiles support
 * long-press-to-delete ([onLongClick] + TalkBack label) and, in Edit mode, an
 * inline × ([deletable]/[onDelete]). a11y carried over 1:1 from the old chip:
 * Role.Button, `selected` semantics + spoken [presetSpokenDescription], 48dp
 * min target, disabled state.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresetTile(
    preset: RovaPreset,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    deletable: Boolean = false,
    onDelete: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(SettingsSheetTokens.tileRadius)
    val palette = LocalGlassEnvironment.current.palette
    val ringBrush = remember(palette) { Brush.linearGradient(listOf(palette.accent, palette.accent2)) }
    val cd = presetSpokenDescription(preset)
    val deleteLabel = stringResource(R.string.preset_chip_delete_action)
    val summary = presetTileSummary(preset.duration, preset.loopCount, preset.resolution)
    val nameColor = if (selected) SettingsSheetTokens.chipOnText else SettingsSheetTokens.chipOffText
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SettingsSheetTokens.tileMinHeight)
            .clip(shape)
            .background(
                if (selected) palette.accent.copy(alpha = SettingsSheetTokens.tileSelFillAlpha)
                else SettingsSheetTokens.tileFill,
            )
            .then(
                if (selected) Modifier.border(1.5.dp, ringBrush, shape)
                else Modifier.border(1.dp, SettingsSheetTokens.tileStroke, shape),
            )
            .then(
                if (enabled) {
                    Modifier
                        .focusHighlight(shape)
                        .combinedClickable(
                            role = Role.Button,
                            onClick = onClick,
                            onLongClick = onLongClick,
                            onLongClickLabel = if (onLongClick != null) deleteLabel else null,
                        )
                } else {
                    Modifier
                },
            )
            .alpha(if (enabled) 1f else 0.5f)
            .semantics {
                this.selected = selected
                contentDescription = cd
                if (!enabled) disabled()
            }
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                preset.name,
                style = RovaTokens.sheetChip,
                color = nameColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // leave room for the top-end badge so a long name doesn't run under it
                modifier = Modifier.padding(end = 18.dp),
            )
            Text(
                summary,
                style = RovaTokens.tileSummary,
                color = SettingsSheetTokens.summaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Edit mode prioritises the delete affordance; otherwise show the check.
        if (deletable && onDelete != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .clip(CircleShape)
                    .focusHighlight(CircleShape)
                    .clickable(role = Role.Button, onClickLabel = deleteLabel) { onDelete() }
                    .size(20.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = deleteLabel,
                    tint = SettingsSheetTokens.chipOnText,
                    modifier = Modifier.size(13.dp),
                )
            }
        } else if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = palette.accent,
                modifier = Modifier.align(Alignment.TopEnd).size(15.dp),
            )
        }
    }
}

/**
 * The "+ Save" affordance as a uniform tile — same footprint as [PresetTile],
 * solid (not dashed) stroke to avoid a custom PathEffect. Shown only when the
 * live config matches no preset; tapping opens the naming dialog (ADR-0026,
 * behavior unchanged).
 */
@Composable
private fun NewPresetTile(onClick: () -> Unit) {
    val shape = RoundedCornerShape(SettingsSheetTokens.tileRadius)
    val cd = stringResource(R.string.preset_save_chip_cd)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SettingsSheetTokens.tileMinHeight)
            .clip(shape)
            .border(1.dp, SettingsSheetTokens.tileStroke, shape)
            .focusHighlight(shape)
            .clickable(role = Role.Button) { onClick() }
            .semantics { contentDescription = cd }
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = null,
                tint = SettingsSheetTokens.chipOffText,
                modifier = Modifier.size(16.dp),
            )
            Text(
                stringResource(R.string.preset_save_chip),
                style = RovaTokens.sheetChip,
                color = SettingsSheetTokens.chipOffText,
            )
        }
    }
}
```

- [ ] **Step 4: Compile check (controller)**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. If the vararg-lambda note in Step 2 bites, switch to the explicit `listOf<@Composable () -> Unit>({ … })` form and recompile.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/SettingsSheet.kt
git commit -m "feat(preset): uniform tile grid replaces ragged chip flow (both surfaces)"
```

---

## Task 4: Scroll-fade discoverability cue

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/SettingsSheet.kt` (add `ScrollFadeBottom`; wrap `SettingsContent` body scroll at ~630-631)
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/FloatingSettingsPanel.kt` (hoist scroll state ~238-242; add overlay)

- [ ] **Step 1: Add the shared `ScrollFadeBottom` composable**

In `SettingsSheet.kt`, add near the other internal helpers (e.g. after `SheetRowDivider`):

```kotlin
/**
 * Decorative bottom scroll cue — a short transparent→[fill] gradient that
 * appears only while there's more content below ([visible] = canScrollForward),
 * so it never lies. Static (alpha toggles, no animation → not gated by
 * checkA11yAnimationGated); no pointerInput, so it doesn't intercept the scroll
 * drag underneath. (preset-ui-polish spec §3.)
 */
@Composable
internal fun ScrollFadeBottom(visible: Boolean, fill: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .alpha(if (visible) 1f else 0f)
            .background(Brush.verticalGradient(listOf(Color.Transparent, fill))),
    )
}
```

(`Color`, `Brush`, `alpha`, `height`, `fillMaxWidth`, `Box` are already imported in this file.)

- [ ] **Step 2: Wrap the full-sheet body scroll**

In `SettingsContent` (~630-631), change:

```kotlin
    val bodyScroll = rememberScrollState()
    Column(modifier = modifier.verticalScroll(bodyScroll)) {
```

to:

```kotlin
    val bodyScroll = rememberScrollState()
    Box(modifier) {
        Column(modifier = Modifier.verticalScroll(bodyScroll)) {
```

Then find the **matching closing brace** of that `Column { … }` and, immediately after it (still inside the new `Box`), add the overlay and the `Box`'s closing brace:

```kotlin
        ScrollFadeBottom(
            visible = bodyScroll.canScrollForward,
            fill = SettingsSheetTokens.sheetFill,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
```

> The original `Column` took `modifier.verticalScroll(...)`; the outer `Box` now owns the passed-in `modifier` and the inner `Column` owns only `verticalScroll`. Verify the brace move with the Step 5 compile.

- [ ] **Step 3: Add the fade to the floating square**

In `FloatingSettingsPanel.kt`, the scroll column at ~238-242:

```kotlin
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                    ) {
```

Change to hoist the scroll state and wrap in a Box:

```kotlin
                    val panelScroll = rememberScrollState()
                    Box(modifier = Modifier.weight(1f, fill = false)) {
                        Column(
                            modifier = Modifier.verticalScroll(panelScroll),
                        ) {
```

Find the matching closing brace of that inner `Column` and, immediately after it, add:

```kotlin
                        ScrollFadeBottom(
                            visible = panelScroll.canScrollForward,
                            fill = SettingsSheetTokens.sheetFill,
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }
```

Add imports to `FloatingSettingsPanel.kt` if missing:

```kotlin
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
```

(`rememberScrollState`, `verticalScroll`, `weight` are already imported.)

- [ ] **Step 4: Compile check (controller)**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. The most likely failure is a mismatched brace from the Box-wrap — fix the brace pairing and recompile.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/SettingsSheet.kt app/src/main/java/com/aritr/rova/ui/screens/FloatingSettingsPanel.kt
git commit -m "feat(settings): bottom scroll-fade cue on both settings surfaces"
```

---

## Task 5: Sentence-case strip labels

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt:598`
- Modify: `app/src/main/res/values/strings.xml:178`
- Modify: `app/src/main/res/values-es/strings.xml:179`

- [ ] **Step 1: Drop the forced uppercase in `SettingsCell`**

In `RecordChrome.kt`, line ~598, change:

```kotlin
                    key.uppercase(),
```

to:

```kotlin
                    key,
```

This makes Clip/Repeats/Wait/Quality/Locked render in the resource's natural sentence-case ("Length", "Clips", "Wait", "Quality", "Locked").

- [ ] **Step 2: Sentence-case the Mode label (en)**

`record_cell_mode` bypasses `SettingsCell` and is authored upper-case. In `app/src/main/res/values/strings.xml:178`, change:

```xml
    <string name="record_cell_mode">MODE</string>
```

to:

```xml
    <string name="record_cell_mode">Mode</string>
```

- [ ] **Step 3: Sentence-case the Mode label (es)**

In `app/src/main/res/values-es/strings.xml:179`, change:

```xml
    <string name="record_cell_mode">MODO</string>
```

to:

```xml
    <string name="record_cell_mode">Modo</string>
```

- [ ] **Step 4: Verify no other cell label is authored upper-case**

Read `record_cell_repeats`, `record_cell_wait`, `record_cell_quality` in **both** `values/strings.xml` and `values-es/strings.xml`. They should already be sentence-case (en: "Clips"/"Wait"/"Quality"). If any es value is upper-case, lower-case it to match. (Known sentence-case already: en clip "Length", locked "Locked"; es clip "Duración", locked "Bloqueado".)

- [ ] **Step 5: Compile check (controller)**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt app/src/main/res/values/strings.xml app/src/main/res/values-es/strings.xml
git commit -m "polish(strip): sentence-case cell labels (drop ALL-CAPS)"
```

---

## Task 6: Full gate-run, device-smoke, integration

**Files:** none (verification only)

- [ ] **Step 1: Unit tests (controller)**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS, baseline + 4 new `RecordSettingsFormatTest` cases, 0 failures.

- [ ] **Step 2: Full assemble + all static gates (controller)**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. This runs all 40 `check*` gates. Pay attention to:
- `checkA11yClickableHasRole` — every new clickable (`PresetTile`, inline `×`, `NewPresetTile`) carries `role = Role.Button`. Must pass.
- `checkNoHardcodedUiStrings` — no new translatable literals (summary uses formatters + `·`/`∞`/`×` glyphs; labels are resources). Must pass.
- `checkNoLegacyModeStrings` — confirm the "Mode"/"Modo" rename doesn't trip it. If it does, that's a real signal — inspect the gate's clause before any change (never edit a gate to pass).

If the Kotlin incremental cache corrupts: `./gradlew --stop`, kill stray java, delete `app/build/kotlin` + `.gradle/kotlin`, re-run.

- [ ] **Step 3: codex review of the diff**

Have codex review the `PresetSheetChip` → `PresetTile` refactor specifically for **a11y-preservation** (selected semantics, spoken description, long-press + inline-× delete equivalence, Role, 48dp). Fold any findings.

- [ ] **Step 4: Install + device-smoke (controller + owner)**

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Owner smoke checklist (real device — emulators fail CameraX, but this is settings UI so a device is still preferred):
- Open the settings panel → Preset section: tiles are **uniform width**, two columns, built-in vs My-presets grouped, summaries read correctly (incl. Continuous → ∞), long names ellipsize.
- Create several customs → grid stays tidy; `+ New` tile appears only when config is unsaved; Edit toggles inline ×; long-press deletes; TalkBack announces selected + description + delete.
- Settings panel scrolls → bottom **fade cue** shows when more is below, gone at the bottom.
- Record strip labels read **sentence-case** (Length/Clips/Wait/Quality/Mode), both orientations (counter-rotation intact).

- [ ] **Step 5: Open PR (after owner GO)**

Branch off master, push, open PR summarizing the three refinements. Title e.g. `polish(preset): uniform tiles + scroll cue + sentence-case strip`. Do **not** open before the owner's device-smoke GO (PRs are outward-facing).

---

## Self-Review (completed by author)

- **Spec coverage:** §2 tiles → Tasks 1–3; §3 scroll-fade → Task 4; §4 strip → Task 5; §5 gates / §6 tests → Tasks 1 & 6. All four spec open-items resolved (owner "go"): +New = restyle-only (Task 3), summary = clip·repeats·quality (Task 1), scroll-fade both surfaces (Task 4), strip sentence-case (Task 5). No gaps.
- **Placeholder scan:** none — every code step shows full code; the one conditional (vararg-lambda form) gives the exact fallback.
- **Type consistency:** `presetTileSummary(Int,Int,String)` defined in Task 1, called identically in Task 3; `ScrollFadeBottom(visible,fill,modifier)` defined and called identically in Task 4; token names (`tileMinHeight`/`tileRadius`/`tileGap`/`tileFill`/`tileStroke`/`tileSelFillAlpha`/`tileSummary`) defined in Task 2, used verbatim in Task 3/4.
