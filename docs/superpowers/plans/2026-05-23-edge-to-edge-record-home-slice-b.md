# Edge-to-Edge Record-Home Slice B Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Re-skin the Record screen's dock + settings card per Slice B's "Gradient Scrim" design — vertical-gradient dock brush, pill settings card, inline Mode tap-cycle chip — without touching the recording pipeline.

**Architecture:** Pure-helper test seam (`cycleModeNext`) backs a thin `RecordViewModel.cycleMode()`; `RecordChromeTokens` swaps the dock flat-fill for a 5-stop `Brush.verticalGradient`; `RecordBottomNav` refactors into outer-Box-paints-brush + inner-Row-consumes-inset so the gradient blends through the OS-transparent gesture-nav region; `RecordSettingsCard` swaps the Mode `SettingsCell` for a new `ModeCycleChip` with `combinedClickable` tap/long-press behaviour; `RecordChromeMetrics` gains `settingsCardLift = 30.dp` so the card clears the gradient band cleanly.

**Tech Stack:** Kotlin 2.2.10, Jetpack Compose, Material 3, JUnit 4, Gradle 9.4.1 (`./gradlew.bat`), Windows PowerShell shell. AndroidViewModel (no Robolectric needed because the only new logic is the pure helper).

**Spec:** `docs/superpowers/specs/2026-05-23-edge-to-edge-record-home-slice-b-design.md` @ `9b3e1e5`.

**Branch base:** `master` @ `aa337d2` (Slice A merged). Create `feat/edge-to-edge-record-home-slice-b` from there.

---

## Pre-flight (one-time, before Task 1)

- [ ] **Branch off master**

```bash
git checkout master
git pull --ff-only
git checkout -b feat/edge-to-edge-record-home-slice-b
```

Expected: `Switched to a new branch 'feat/edge-to-edge-record-home-slice-b'`; HEAD == `aa337d2` plus the spec commit `9b3e1e5` on master.

- [ ] **Baseline gates (sanity — must pass before any task)**

```powershell
./gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Expected: BUILD SUCCESSFUL. Test count baseline carried from master @ `aa337d2`. If anything fails on `master`, STOP and escalate — the slice cannot land on a broken baseline.

---

## Task 1: Pure helper `cycleModeNext` + JVM test (TDD)

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/screens/RecordModeCycle.kt`
- Test:   `app/src/test/java/com/aritr/rova/ui/screens/RecordModeCycleTest.kt`

The mode-cycle is project-precedent's "pure helper, ViewModel thin-delegates" pattern (see `loopPillContent`, `hudStatusPillContent`, `recordFabState` in `RecordChrome.kt`). Test the helper, not the VM (`RecordViewModel` is `AndroidViewModel(Application)` with a `ServiceConnection` in `init` — pure-JVM test would need Robolectric, which the project does not ship).

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/aritr/rova/ui/screens/RecordModeCycleTest.kt`:

```kotlin
package com.aritr.rova.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Slice B — pure-JVM cover for the Mode tap-cycle helper. The chip in
 * RecordSettingsCard calls [cycleModeNext] (via RecordViewModel.cycleMode)
 * to advance one step. The cycle order matches the segmented Mode strip:
 * Portrait → Landscape → P+L → Portrait. The defensive `else` arm maps
 * any unknown / corrupted persisted value to "Portrait" so the cycle
 * stays deterministic.
 */
class RecordModeCycleTest {

    @Test
    fun cycleModeNext_portrait_to_landscape() {
        assertEquals("Landscape", cycleModeNext("Portrait"))
    }

    @Test
    fun cycleModeNext_landscape_to_portrait_landscape() {
        assertEquals("PortraitLandscape", cycleModeNext("Landscape"))
    }

    @Test
    fun cycleModeNext_portrait_landscape_wraps_to_portrait() {
        assertEquals("Portrait", cycleModeNext("PortraitLandscape"))
    }

    @Test
    fun cycleModeNext_unknown_string_defaults_to_portrait() {
        assertEquals("Portrait", cycleModeNext(""))
        assertEquals("Portrait", cycleModeNext("garbage"))
        assertEquals("Portrait", cycleModeNext("portrait"))   // case-sensitive
    }
}
```

- [ ] **Step 2: Run test to verify it fails (helper not defined)**

```powershell
./gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.screens.RecordModeCycleTest"
```

Expected: `BUILD FAILED` — `error: unresolved reference: cycleModeNext` (compilation error in the test file). This is the "red" of the TDD red/green cycle.

- [ ] **Step 3: Create the helper file**

Create `app/src/main/java/com/aritr/rova/ui/screens/RecordModeCycle.kt`:

```kotlin
package com.aritr.rova.ui.screens

/**
 * Slice B — pure helper for the Mode tap-cycle chip (RecordSettingsCard ·
 * mockups/new_uiux/01d-record-home-slice-b.html · Variant C). Advances
 * one step through the segmented Mode strip's order. The unknown-string
 * else-arm keeps the cycle deterministic if `RovaSettings.mode` ever
 * holds a value outside the three known strings — defensive only;
 * `setMode(String)` is the only writer and only writes the three values.
 *
 * Tested by [com.aritr.rova.ui.screens.RecordModeCycleTest] (pure JVM).
 *
 * Project precedent for pure-helper test seams: [loopPillContent],
 * [hudStatusPillContent], [recordFabState] in RecordChrome.kt.
 */
internal fun cycleModeNext(current: String): String = when (current) {
    "Portrait" -> "Landscape"
    "Landscape" -> "PortraitLandscape"
    "PortraitLandscape" -> "Portrait"
    else -> "Portrait"
}
```

- [ ] **Step 4: Run tests to verify they pass**

```powershell
./gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.screens.RecordModeCycleTest"
```

Expected: `BUILD SUCCESSFUL`. 4 tests pass.

- [ ] **Step 5: Run full test suite to confirm no regression**

```powershell
./gradlew.bat :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`. Test count = baseline + 4.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/RecordModeCycle.kt app/src/test/java/com/aritr/rova/ui/screens/RecordModeCycleTest.kt
git commit -m "feat(ui): cycleModeNext pure helper + JVM tests (Slice B Task 1)"
```

---

## Task 2: Wire `cycleMode()` in `RecordViewModel`

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordViewModel.kt:236` (just after the existing `setMode` declaration)

`cycleMode()` is a 5-line method that reads the current mode + session-lock state, computes the next mode via `cycleModeNext`, and delegates to the existing `setMode(String)` path. No new persistence wire-up; no new producer; no test (the helper is the test seam).

- [ ] **Step 1: Read the existing `setMode` block**

```powershell
# Just to confirm the call site before edit. Read directly with your editor or:
# grep -n "fun setMode" app/src/main/java/com/aritr/rova/ui/screens/RecordViewModel.kt
```

Expected: a `setMode(mode: String)` method around line 236 with three side-effects — `settings.mode`, `this.mode.value`, `serviceBinder?.getService()?.setMode(mode)`.

- [ ] **Step 2: Add `cycleMode()` after `setMode`**

Insert the following BLOCK immediately after the closing `}` of `setMode(...)`:

```kotlin

    /**
     * Slice B — Mode tap-cycle. Reads the current mode + session-lock
     * state and writes the next mode via [setMode]. No-op during an
     * active session (matches the existing sheet behaviour: Mode row
     * hidden / non-interactive when periodic active or merging).
     *
     * Cycle order is delegated to [cycleModeNext] (pure helper) so the
     * VM stays a thin shim around the existing persistence pipeline.
     *
     * Called from [RecordSettingsCard]'s ModeCycleChip via
     * `onCycleMode = viewModel::cycleMode` in [RecordScreen].
     */
    fun cycleMode() {
        val s = _serviceState.value
        if (s.isPeriodicActive || s.isMerging) return
        setMode(cycleModeNext(mode.value))
    }
```

NOTE: `mode` is the existing `MutableStateFlow<String>` on the VM (referenced as `this.mode.value` in `setMode`). `_serviceState` is the private MutableStateFlow defined at line 44. The lock check mirrors `RecordScreen.kt:234` (`val isUiLocked = serviceState.isPeriodicActive || serviceState.isMerging`) but reads directly from `_serviceState.value` so it's self-contained.

- [ ] **Step 3: Compile-gate**

```powershell
./gradlew.bat :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`. (No tests added at this step — the helper is the seam.)

- [ ] **Step 4: Re-run full test suite (regression check)**

```powershell
./gradlew.bat :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`. Same test count as Task 1 step 5.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/RecordViewModel.kt
git commit -m "feat(ui): RecordViewModel.cycleMode() delegates to cycleModeNext (Slice B Task 2)"
```

---

## Task 3: Add new tokens to `RecordChromeTokens.kt`

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/theme/RecordChromeTokens.kt`

This task only ADDS — it does NOT delete the old `bottomNavFill` / `bottomNavTopStroke` / `settingsCardRadius` tokens yet, so the project still compiles at the end of this task. Old tokens get deleted in Tasks 4 (dock) and 5 (settings card) as their last consumer flips to the new token. Order matters: adding-then-flipping-then-deleting keeps every intermediate commit green.

- [ ] **Step 1: Add the `Brush` import**

At the top of `RecordChromeTokens.kt`, add this import alphabetically alongside the existing `import androidx.compose.ui.graphics.Color`:

```kotlin
import androidx.compose.ui.graphics.Brush
```

- [ ] **Step 2: Add the new tokens block**

Locate the `// ── Surface fills & strokes ──` block (line 20). At the END of that block (just before `// ── Status dots ──`), append:

```kotlin

    /**
     * Slice B — dock fill is now a vertical gradient brush. The top
     * 35% is fully transparent so the camera preview reads continuously
     * through the dock zone; the gradient ramps to 0.55 black at the
     * bottom to provide readable contrast behind Library/FAB/Settings.
     * Paint on an outer Box that extends through `windowInsetsPadding`
     * so the brush dissolves into the OS-transparent gesture-nav
     * region (Slice A) with no band edge. See
     * `docs/superpowers/specs/2026-05-23-edge-to-edge-record-home-slice-b-design.md`
     * §3.1.
     */
    val bottomNavBrush: Brush = Brush.verticalGradient(
        colorStops = arrayOf(
            0.00f to Color.Transparent,
            0.35f to Color.Transparent,
            0.55f to Color.Black.copy(alpha = 0.20f),
            0.80f to Color.Black.copy(alpha = 0.45f),
            1.00f to Color.Black.copy(alpha = 0.55f),
        )
    )

    /** Slice B — Mode tap-cycle chip background. */
    val modeChipFill = Color.White.copy(alpha = 0.07f)
    /** Slice B — Mode tap-cycle chip stroke. */
    val modeChipStroke = Color.White.copy(alpha = 0.10f)
    /** Slice B — Mode tap-cycle chip's `↻` glyph alpha when enabled. */
    val modeChipGlyphAlphaEnabled = 0.35f
    /** Slice B — Mode tap-cycle chip's `↻` glyph alpha when dimmed. */
    val modeChipGlyphAlphaDisabled = 0.12f
```

- [ ] **Step 3: Add the new `settingsCardRadiusPill` token**

Locate the `// ── Settings card ──` block (line 129). At the start (just before `/** \`.settings-card\` corner radius. */`), append:

```kotlin
    /**
     * Slice B — pill corner radius for the settings card. Supersedes
     * [settingsCardRadius] (which is deleted in Task 5 once
     * `SettingsCardShape` flips to this token).
     */
    val settingsCardRadiusPill = 22.dp
```

- [ ] **Step 4: Add the new `modeChipGlyphSize` + `modeChipCornerRadius`**

In the `// ── Settings card ──` block, after the `settingsCardRadiusPill` line just added, append:

```kotlin
    /** Slice B — Mode chip corner radius (matches the cell-divider visual anchor). */
    val modeChipCornerRadius = 11.dp
    /** Slice B — Mode chip `↻` glyph text size. */
    val modeChipGlyphSize = 7.sp
```

- [ ] **Step 5: Add the `sp` import**

The `.sp` unit needs an import. Add at the top of the file (alongside `import androidx.compose.ui.unit.dp`):

```kotlin
import androidx.compose.ui.unit.sp
```

- [ ] **Step 6: Compile-gate**

```powershell
./gradlew.bat :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`. (Old tokens still present; new tokens additive; no consumer changes yet.)

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/theme/RecordChromeTokens.kt
git commit -m "feat(ui): add bottomNavBrush + pill radius + Mode chip tokens (Slice B Task 3)"
```

---

## Task 4: Refactor `RecordBottomNav` to gradient brush + outer-Box pattern; delete `bottomNavFill` + `bottomNavTopStroke`

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt:355` (the existing `RecordBottomNav` composable, lines 355-383)
- Modify: `app/src/main/java/com/aritr/rova/ui/theme/RecordChromeTokens.kt` (delete `bottomNavFill` + `bottomNavTopStroke`)

`RecordBottomNav` currently chains `.background(bottomNavFill).border(...).windowInsetsPadding(navigationBars).padding(...)` on a single Row. That paints the flat fill on the Row's bounds ONLY — the brush would stop at the gesture-nav inset boundary, re-introducing the band edge Slice A killed. Refactor: wrap the Row in an outer `Box` that paints the brush, with the inset-padding moving to the inner Row.

- [ ] **Step 1: Read the current `RecordBottomNav` block**

```powershell
# Inspect lines 355-383. The current shape:
#   @Composable
#   fun RecordBottomNav(...) {
#       Row(
#           modifier = modifier
#               .fillMaxWidth()
#               .background(RecordChromeTokens.bottomNavFill)
#               .border(width = 1.dp, color = RecordChromeTokens.bottomNavTopStroke, shape = RoundedCornerShape(0.dp))
#               .windowInsetsPadding(WindowInsets.navigationBars)
#               .padding(start = ..., end = ..., top = 14.dp, bottom = ...),
#           verticalAlignment = Alignment.CenterVertically,
#           horizontalArrangement = Arrangement.SpaceAround,
#       ) { ... }
#   }
```

- [ ] **Step 2: Replace the `RecordBottomNav` body**

Replace the entire body of `RecordBottomNav` (everything inside the curly braces of the `@Composable fun RecordBottomNav(...) { ... }`) with:

```kotlin
    // Slice B — gradient brush replaces the flat `bottomNavFill`. The
    // outer Box paints the brush across the Box's full intrinsic height,
    // INCLUDING the navigation-bar inset zone that the inner Row consumes
    // via `windowInsetsPadding`. This is what makes the gradient dissolve
    // into the OS-transparent gesture-nav region (Slice A — see ADR-0011)
    // with no band edge. Painting the brush on the Row directly would
    // bound it to the inset-padded layout, breaking the seamless blend.
    // The 1 dp top stroke is deleted — a gradient has no top, so a stroke
    // would re-introduce the edge we're killing.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(RecordChromeTokens.bottomNavBrush)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(
                    start = RecordChromeTokens.bottomNavPaddingH,
                    end = RecordChromeTokens.bottomNavPaddingH,
                    top = 14.dp,
                    bottom = RecordChromeTokens.bottomNavPaddingBottom,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceAround,
        ) {
            NavItem(icon = RecordChromeIcons.library, label = "Library", enabled = !navItemsLocked, onClick = onLibrary)
            RecordFab(state = fabState, onClick = onFabClick)
            NavItem(icon = RecordChromeIcons.settings, label = "Settings", enabled = !navItemsLocked, onClick = onSettings)
        }
    }
```

- [ ] **Step 3: Remove the now-unused imports**

In `RecordChrome.kt`, the `.border` modifier is no longer used in this composable but may be referenced elsewhere (e.g., `GlassCircleButton`'s border). Leave the `import androidx.compose.foundation.border` alone — it's still consumed.

The `RoundedCornerShape(0.dp)` argument to `.border` is gone; `RoundedCornerShape` is also still consumed elsewhere (`StatusPillShape`, etc.). Leave the import.

No imports change in this step.

- [ ] **Step 4: Delete `bottomNavFill` + `bottomNavTopStroke` from `RecordChromeTokens.kt`**

Delete these two lines (around line 42-45 of `RecordChromeTokens.kt`):

```kotlin
    /** `.bottom-nav` background — `rgba(0,0,0,0.50)`. */
    val bottomNavFill = Color.Black.copy(alpha = 0.50f)
    /** `.bottom-nav` top border — `rgba(255,255,255,0.055)`. */
    val bottomNavTopStroke = Color.White.copy(alpha = 0.055f)
```

- [ ] **Step 5: Compile-gate**

```powershell
./gradlew.bat :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`. If compilation fails with "unresolved reference: bottomNavFill" or "bottomNavTopStroke", grep for the offending caller and confirm it's not a missed consumer — only `RecordChrome.kt` should have used these.

```powershell
# Sanity grep (should return zero hits):
./gradlew.bat -q --console=plain :app:assembleDebug 2>&1 | Select-String "bottomNav(Fill|TopStroke)"
```

Expected: no output.

- [ ] **Step 6: Run full test suite (regression check)**

```powershell
./gradlew.bat :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`. Same test count as Task 1 step 5.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt app/src/main/java/com/aritr/rova/ui/theme/RecordChromeTokens.kt
git commit -m "feat(ui): RecordBottomNav gradient brush + outer-Box refactor (Slice B Task 4)"
```

---

## Task 5: Refactor `RecordSettingsCard` to pill + add `ModeCycleChip`; add `settingsCardLift`; delete `settingsCardRadius`

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt` (lines 63-65 + lines 234-306 + lines 342-344)
- Modify: `app/src/main/java/com/aritr/rova/ui/theme/RecordChromeTokens.kt` (delete `settingsCardRadius`)

This task is the meat of the visual change. Four sub-changes:
1. `SettingsCardShape` consumes `settingsCardRadiusPill` instead of `settingsCardRadius`.
2. `RecordSettingsCard` gains an `onCycleMode` lambda parameter.
3. The Mode `SettingsCell(...)` call is replaced by a new `ModeCycleChip` composable.
4. `RecordChromeMetrics` gains `settingsCardLift = 30.dp`.

- [ ] **Step 1: Flip `SettingsCardShape` to the pill radius**

In `RecordChrome.kt` line 65:

Replace:
```kotlin
private val SettingsCardShape = RoundedCornerShape(RecordChromeTokens.settingsCardRadius)
```

With:
```kotlin
private val SettingsCardShape = RoundedCornerShape(RecordChromeTokens.settingsCardRadiusPill)
```

- [ ] **Step 2: Add `onCycleMode` parameter to `RecordSettingsCard`**

Locate the `@Composable fun RecordSettingsCard(...)` signature (around line 234-244). Add `onCycleMode: () -> Unit,` as a parameter just after `onOpenSheet: () -> Unit,`. The signature becomes:

```kotlin
@Composable
fun RecordSettingsCard(
    durationSeconds: Int,
    loopCount: Int,
    intervalMinutes: Int,
    quality: String,
    mode: String,
    onOpenSheet: () -> Unit,
    onCycleMode: () -> Unit,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
) {
```

- [ ] **Step 3: Replace the Mode `SettingsCell(...)` call with `ModeCycleChip`**

Inside the inner `Row` of `RecordSettingsCard` (around lines 296-298), locate the final `SettingsCell` for Mode:

```kotlin
            SettingsCell("Mode", mode, Modifier.weight(1f), readOnly = true)
```

Replace it with:

```kotlin
            ModeCycleChip(
                mode = mode,
                onCycleMode = onCycleMode,
                onLongPress = onOpenSheet,
                enabled = !dimmed,
                modifier = Modifier.weight(1f),
            )
```

- [ ] **Step 4: Remove the preceding `CellSep()` so the chip doesn't sit immediately against the Quality divider**

The current Row has `CellSep()` between Quality and Mode (around line 296). Delete THAT one `CellSep()` call only — the chip's own outline replaces the divider as the visual separator. The Row now has 4 dividers (between Clip/Repeats, Repeats/Wait, Wait/Quality, NONE between Quality/ModeChip).

Before:
```kotlin
            SettingsCell("Quality", quality, Modifier.weight(1f), readOnly = false)
            CellSep()
            SettingsCell("Mode", mode, Modifier.weight(1f), readOnly = true)
```

After:
```kotlin
            SettingsCell("Quality", quality, Modifier.weight(1f), readOnly = false)
            ModeCycleChip(
                mode = mode,
                onCycleMode = onCycleMode,
                onLongPress = onOpenSheet,
                enabled = !dimmed,
                modifier = Modifier.weight(1f),
            )
```

- [ ] **Step 5: Add the `ModeCycleChip` composable**

After `RecordSettingsCard`'s closing brace and BEFORE the `@Composable private fun SettingsCell(...)` block (around line 308), insert:

```kotlin

/**
 * Slice B — Mode tap-cycle chip. Replaces the read-only Mode `SettingsCell`
 * in [RecordSettingsCard]. Tap advances the mode one step (Portrait →
 * Landscape → P+L → Portrait) via [onCycleMode]; long-press opens the
 * settings sheet via [onLongPress] (gesture redundancy + discoverability
 * fallback for the inline cycle). Disabled while [enabled] is false (=
 * card-dimmed during an active session — the existing card behaviour).
 *
 * Visual: outlined chip with a faint `↻` glyph top-right. The chip
 * absorbs tap events within its bounds, so taps inside the chip do NOT
 * bubble to the outer card's `clickable { onOpenSheet() }`.
 *
 * The cycle order itself lives in [cycleModeNext] (RecordModeCycle.kt) —
 * RecordViewModel.cycleMode() reads the current mode, calls the helper,
 * and writes via the existing setMode path.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ModeCycleChip(
    mode: String,
    onCycleMode: () -> Unit,
    onLongPress: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val glyphAlpha = if (enabled) RecordChromeTokens.modeChipGlyphAlphaEnabled
                     else RecordChromeTokens.modeChipGlyphAlphaDisabled
    val chipShape = RoundedCornerShape(RecordChromeTokens.modeChipCornerRadius)
    Box(
        modifier = modifier
            .padding(horizontal = 2.dp)
            .clip(chipShape)
            .background(RecordChromeTokens.modeChipFill)
            .border(1.dp, RecordChromeTokens.modeChipStroke, chipShape)
            .then(
                if (enabled) {
                    Modifier.combinedClickable(
                        onClick = onCycleMode,
                        onLongClick = onLongPress,
                    )
                } else {
                    Modifier
                }
            )
            .padding(horizontal = RecordChromeTokens.settingsCellPaddingH, vertical = 4.dp),
    ) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                mode,
                style = RovaTokens.cellValue,
                color = RecordChromeTokens.cellValueText,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
            Text(
                "MODE",
                style = RovaTokens.cellKey,
                color = RecordChromeTokens.cellKeyText,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
        Text(
            "↻",
            modifier = Modifier.align(Alignment.TopEnd),
            color = Color.White.copy(alpha = glyphAlpha),
            fontSize = RecordChromeTokens.modeChipGlyphSize,
        )
    }
}
```

- [ ] **Step 6: Add required imports**

At the top of `RecordChrome.kt`, add (alphabetically sorted into the existing import block):

```kotlin
import androidx.compose.foundation.combinedClickable
```

The `Column`, `Text`, `Alignment.Center`, `Alignment.TopEnd`, `TextAlign.Center`, `RoundedCornerShape`, `Color`, `Modifier.padding`, `clip`, `background`, `border` are all already imported (used by neighbouring composables). Verify by inspection — do NOT add duplicates.

`fontSize` parameter on `Text` is already supported by the existing import of `androidx.compose.material3.Text`. No additional import needed.

- [ ] **Step 7: Add `settingsCardLift` to `RecordChromeMetrics`**

Locate `RecordChromeMetrics` (lines 342-344 of `RecordChrome.kt`). Replace the whole object body:

```kotlin
internal object RecordChromeMetrics {
    val bottomNavClearance = 90.dp
    /**
     * Slice B — additional padding above [bottomNavClearance] for the
     * settings card. The dock's gradient has a fully-transparent top
     * zone (35% of dock height ≈ 31 dp); without this lift the
     * settings card's lower edge would overlap the gradient's
     * mid-darkness band, producing a visible alpha-curve seam between
     * two semi-transparent layers. 30 dp lift clears the gradient
     * with an 8 dp buffer.
     */
    val settingsCardLift = 30.dp
}
```

- [ ] **Step 8: Delete `settingsCardRadius` from `RecordChromeTokens.kt`**

In `RecordChromeTokens.kt`, locate (around line 130-131):

```kotlin
    /** `.settings-card` corner radius. */
    val settingsCardRadius = 14.dp
```

Delete both lines.

- [ ] **Step 9: Compile-gate**

```powershell
./gradlew.bat :app:assembleDebug
```

Expected: `BUILD FAILED` with one error in `RecordScreen.kt` — the call site `RecordSettingsCard(...)` is missing the new required `onCycleMode` parameter. Task 6 fixes that. If any OTHER compile error appears (e.g., a stray `settingsCardRadius` reference somewhere unexpected), STOP and grep — only `RecordChrome.kt` should have referenced it.

Sanity grep:

```powershell
./gradlew.bat :app:assembleDebug 2>&1 | Select-String "settingsCardRadius[^P]"
```

Expected: at most one error, and it's the call-site mismatch fixed by Task 6.

- [ ] **Step 10: Commit (deliberately a partial commit — call site fixed in Task 6)**

Even though the build is red, this commit captures the chrome refactor cleanly. The very next task (6) fixes the call site, so the broken build window is one task wide. Project precedent for atomic-compile-gate splits: Phase 6 mode picker Task 6+7 atomic split. Tag the message accordingly:

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt app/src/main/java/com/aritr/rova/ui/theme/RecordChromeTokens.kt
git commit -m "feat(ui): RecordSettingsCard pill + ModeCycleChip; settingsCardLift (Slice B Task 5)

Compile-gate-RED at this commit — RecordScreen.kt call site fixed by
the next commit (Task 6). Two-task atomic split for atomic-with-Task-6
review."
```

If the implementer prefers to skip the red intermediate commit, they MAY combine the Task 5 + Task 6 file changes into one commit at Task 6's commit step. The plan keeps them separate to honour the per-file boundary discipline.

---

## Task 6: Wire `RecordScreen.kt` — settings-card padding lift + `onCycleMode` thread

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt:605` (the existing `RecordSettingsCard(...)` call site)

The settings card's bottom padding currently is `RecordChromeMetrics.bottomNavClearance` (= 90 dp). Slice B's lift adds `settingsCardLift` (= 30 dp) so the card clears the gradient zone. The call site must also pass `onCycleMode = viewModel::cycleMode`.

- [ ] **Step 1: Read the current call site**

```powershell
# grep -n -A 20 "if (!showCompleteCard) {" app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt
```

Current shape (around lines 604-621):

```kotlin
                if (!showCompleteCard) {
                    RecordSettingsCard(
                        durationSeconds = duration,
                        loopCount = loopCount,
                        intervalMinutes = interval,
                        quality = resolution,
                        mode = if (mode == "PortraitLandscape") "P + L" else mode,
                        onOpenSheet = { viewModel.openSettingsSheet() },
                        dimmed = hudState != RecordHudState.Idle,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .windowInsetsPadding(WindowInsets.navigationBars)
                            .padding(
                                bottom = RecordChromeMetrics.bottomNavClearance,
                                start = 16.dp,
                                end = 16.dp,
                            ),
                    )
                }
```

- [ ] **Step 2: Replace the call site**

Replace the entire `RecordSettingsCard(...)` call block above with:

```kotlin
                if (!showCompleteCard) {
                    RecordSettingsCard(
                        durationSeconds = duration,
                        loopCount = loopCount,
                        intervalMinutes = interval,
                        quality = resolution,
                        mode = if (mode == "PortraitLandscape") "P + L" else mode,
                        onOpenSheet = { viewModel.openSettingsSheet() },
                        onCycleMode = { viewModel.cycleMode() },
                        dimmed = hudState != RecordHudState.Idle,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .windowInsetsPadding(WindowInsets.navigationBars)
                            .padding(
                                // Slice B — bottomNavClearance clears above the dock;
                                // settingsCardLift adds the 30 dp the gradient's
                                // transparent top zone needs. See
                                // RecordChromeMetrics.settingsCardLift KDoc.
                                bottom = RecordChromeMetrics.bottomNavClearance + RecordChromeMetrics.settingsCardLift,
                                start = 16.dp,
                                end = 16.dp,
                            ),
                    )
                }
```

NOTE: the display-name `"P + L"` mapping for `mode` is preserved verbatim — `cycleMode()` operates on the raw `RovaSettings.mode` value (`"PortraitLandscape"`), not the display name. The chip's displayed value uses the mapped string; the underlying cycle is on the raw value via VM.

- [ ] **Step 3: Compile-gate**

```powershell
./gradlew.bat :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`. The Task 5 red window closes here.

- [ ] **Step 4: Run full test suite (regression check)**

```powershell
./gradlew.bat :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`. Same test count as Task 1 step 5 (= baseline + 4).

- [ ] **Step 5: Lint gate**

```powershell
./gradlew.bat :app:lintDebug
```

Expected: `BUILD SUCCESSFUL`. Lint count = baseline (no new InlinedApi / NewApi findings — `combinedClickable` is API 21+ stable, `Brush.verticalGradient` is API 21+ stable).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt
git commit -m "feat(ui): RecordScreen wires onCycleMode + settings-card lift (Slice B Task 6)"
```

---

## Task 7: Update `docs/UI_DESIGN_TOKENS.md`

**Files:**
- Modify: `docs/UI_DESIGN_TOKENS.md`

Documents the new tokens (gradient stops, pill radius, Mode chip family, `settingsCardLift`) and removes the rows for the deleted tokens (`bottomNavFill`, `bottomNavTopStroke`, `settingsCardRadius`).

- [ ] **Step 1: Locate §2.13 in `docs/UI_DESIGN_TOKENS.md`**

```powershell
# grep -n "^##" docs/UI_DESIGN_TOKENS.md | Select-String "2\.13"
# Or search the file for "RecordChromeTokens".
```

The section documents `RecordChromeTokens` with one row per token in a markdown table.

- [ ] **Step 2: Delete the three deleted-token rows**

Delete the rows for:
- `bottomNavFill`
- `bottomNavTopStroke`
- `settingsCardRadius`

- [ ] **Step 3: Add new-token rows**

Add, in the appropriate sub-section (Surface fills & strokes / Settings card):

```markdown
| `bottomNavBrush` | `Brush.verticalGradient(0→Transparent, 0.35→Transparent, 0.55→Black 0.20, 0.80→Black 0.45, 1.0→Black 0.55)` | Dock vertical gradient (Slice B); transparent top 35% lets preview read through |
| `modeChipFill` | `Color.White.copy(alpha=0.07f)` | Mode tap-cycle chip background (Slice B) |
| `modeChipStroke` | `Color.White.copy(alpha=0.10f)` | Mode tap-cycle chip border (Slice B) |
| `modeChipGlyphAlphaEnabled` | `0.35f` | `↻` glyph alpha when chip enabled (Slice B) |
| `modeChipGlyphAlphaDisabled` | `0.12f` | `↻` glyph alpha when chip dimmed (Slice B) |
| `modeChipGlyphSize` | `7.sp` | `↻` glyph text size (Slice B) |
| `modeChipCornerRadius` | `11.dp` | Mode tap-cycle chip corner radius (Slice B) |
| `settingsCardRadiusPill` | `22.dp` | Settings card pill corner radius (Slice B; supersedes `settingsCardRadius`) |
```

Also add a `RecordChromeMetrics.settingsCardLift = 30.dp` entry to the metrics sub-section (the same file documents metrics if present; if not, add a 1-row metrics table after the tokens).

- [ ] **Step 4: Add a "Slice B note" callout above §2.13**

Just above the section header, add a short note:

```markdown
> **Slice B (2026-05-23, PR pending):** Dock fill flipped from `bottomNavFill` flat-alpha to `bottomNavBrush` vertical gradient; settings card from `settingsCardRadius=14.dp` to `settingsCardRadiusPill=22.dp`; new `modeChip*` token family for the Mode tap-cycle chip. See spec `docs/superpowers/specs/2026-05-23-edge-to-edge-record-home-slice-b-design.md` and ADR-0012.
```

- [ ] **Step 5: Verify rendering by eyeballing the markdown**

```powershell
# Just open the file in your editor and confirm table alignment.
```

- [ ] **Step 6: Commit**

```bash
git add docs/UI_DESIGN_TOKENS.md
git commit -m "docs(tokens): Slice B token changes — gradient brush + pill + Mode chip"
```

---

## Task 8: Create `docs/adr/0012-gradient-scrim-dock.md`

**Files:**
- Create: `docs/adr/0012-gradient-scrim-dock.md`

Records the architectural decision: gradient brush over flat fill; pill over rect; inline Mode tap-cycle over sheet-only; outer-Box-paints-brush pattern (the load-bearing trick for gesture-nav continuity); rejection of Variant A (Floating Glass — backdrop-blur API gate) and Variant B (Native-Camera — settings card removal QoL regression); Variant D (Ambient Liquid Glass) parked as Slice C — 2027.

- [ ] **Step 1: Read ADR-0011 as the format reference**

```powershell
# Open docs/adr/0011-edge-to-edge-record-home.md and skim its structure:
#   Status / Date / Context (numbered root causes) / Decision (reversals) /
#   Consequences (Accepted / Rejected / Hard invariants) / Future work.
```

- [ ] **Step 2: Create `docs/adr/0012-gradient-scrim-dock.md`**

Write the file with this content:

```markdown
# ADR-0012 — Gradient-scrim dock + pill settings card + Mode tap-cycle chip

**Status:** Accepted

**Date:** 2026-05-23

## Context

Slice A (ADR-0011, merged 2026-05-23 @ `aa337d2`) made the record-screen preview edge-to-edge: system bars transparent, NavHost stops consuming insets, DualPreviewZone background dropped. Three chrome elements still read as a separate dock zone bolted onto the bottom edge:

1. `RecordBottomNav` paints `RecordChromeTokens.bottomNavFill = #0c1218` opaque + 1 dp `bottomNavTopStroke` at `rgba(255,255,255,0.04)`. The dock reads as a hard rectangle against the now-edge-to-edge preview.
2. `RecordSettingsCard` uses a tight 14 dp corner radius and a uniform "tap = open sheet" gesture model. Mode swap requires sheet → row → tap → dismiss (4 interactions) for a frequently-used operation.
3. `RecordChromeMetrics.bottomNavClearance = 90.dp` puts the settings card lower edge inside the dock's top 18 dp zone. With the dock as a flat opaque fill this is invisible; with any gradient it produces a visible alpha-curve seam.

The brainstorm mockup (`mockups/new_uiux/01d-record-home-slice-b.html`) evaluated four candidates: **A** (Floating Glass — flat translucent dock), **B** (Native-Camera — chrome dissolved to floating chips, settings card removed from idle), **C** (Gradient Scrim — vertical-gradient dock, pill card, Mode tap-cycle chip), **D** (Ambient Liquid Glass — Liquid Glass refractive material + Material 3 Expressive 72 dp FAB + ambient summary chip + Hub pop-out — 2027-28 forward look).

## Decision

**Ship Variant C — Gradient Scrim — as Slice B.** Five concrete changes:

- **`RecordChromeTokens` dock fill** flips from `bottomNavFill: Color` (deleted) to `bottomNavBrush: Brush.verticalGradient` with stops `0% Transparent → 35% Transparent → 55% Black 0.20 → 80% Black 0.45 → 100% Black 0.55`. The top 35 % is fully transparent — preview reads continuously through. The 1 dp `bottomNavTopStroke` is deleted; a gradient has no top, a stroke would re-introduce the band edge we're killing.

- **`RecordBottomNav` refactor** wraps the inner Row in an outer Box. The Box paints `bottomNavBrush` across its full intrinsic height — INCLUDING the navigation-bar inset zone that the inner Row consumes via `windowInsetsPadding(navigationBars)`. This is the load-bearing pattern: painting the brush on the Row directly would bound it to the inset-padded layout, breaking the seamless blend into Slice A's OS-transparent gesture-nav region.

- **`RecordSettingsCard` corner radius** flips from `settingsCardRadius = 14.dp` (deleted) to `settingsCardRadiusPill = 22.dp`. Stroke and fill alpha unchanged.

- **`RecordSettingsCard` Mode cell** changes from a read-only `SettingsCell` to a new `ModeCycleChip` composable using `combinedClickable`: **tap** advances Portrait → Landscape → P+L → Portrait via `RecordViewModel.cycleMode()` (which delegates to the pure helper `cycleModeNext` for the cycle order); **long-press** opens the settings sheet (gesture redundancy + discoverability fallback). Disabled while the card is `dimmed = true` (active session — existing behaviour). Visual: outlined chip + faint `↻` glyph top-right.

- **`RecordChromeMetrics.settingsCardLift = 30.dp`** is added; `RecordScreen.kt` uses `bottomNavClearance + settingsCardLift = 120 dp` as the settings-card bottom padding. This clears the gradient's transparent zone with an 8 dp buffer — no more alpha-curve seam.

## Consequences

**Accepted:**
- The dock fill is no longer a flat Color token — `bottomNavBrush` is a `Brush`. Any future caller wanting a flat dock has to introduce a new token (or use a different surface model).
- `RecordBottomNav` now has a 2-element widget tree (outer Box + inner Row). Callers that mounted custom content over the old single-Row tree would need adjusting — there are no such callers today.
- The settings card's "tap anywhere = open sheet" uniformity has one exception (the Mode chip absorbs tap events within its bounds). Discoverability hit is mitigated by the chip's outlined chip + `↻` glyph treatment, which visually flags the affordance, plus the long-press fallback.
- `cycleModeNext` is a top-level `internal fun` in `RecordModeCycle.kt`. The function is pure and test-covered (project precedent: `loopPillContent`, `hudStatusPillContent`, `recordFabState`). `RecordViewModel.cycleMode()` reads `_serviceState.value` directly (rather than via a new derived StateFlow) for the lock check — no new state on the VM.

**Rejected:**
- **Variant A — Floating Glass** (`rgba(0,0,0,0.50)` + CSS `backdrop-filter: blur(36px)`). Compose has no backdrop-blur primitive before API 31 (`RenderEffect.createBlurEffect`). The project's minSdk is 24; A's "glass" promise would render as flat alpha for the ~30 % of installs below API 31, defeating the visual. Rejected on rendering fidelity + API gate.
- **Variant B — Native-Camera** (chrome dissolved to floating chips, settings card removed from idle, always-on mode strip above FAB). Removing the settings card from idle is a quality-of-life regression — users glance Clip/Repeats/Wait/Quality at idle today. Rejected on UX cost. The always-on mode strip is also a second ModeTabsPicker surface, doubling the display paths to sync with the sheet.
- **Variant D — Ambient Liquid Glass** (refractive chrome material, M3 Expressive 72 dp FAB with shape-morphing, ambient summary chip, Hub pop-out for less-frequent toggles, top-anchored persistent mode strip, scene-tint adaptive chrome). Three blockers prevent Slice B from carrying this: (1) `RenderEffect.createBlurEffect` API 31+ gate (same as A); (2) Material 3 Expressive Compose APIs (shape-morphing, motion physics) are at `alpha → beta` through 2026, production-ready 2027; (3) ambient scene-tint requires sampling preview frame color, touching the hard-invariant frozen `EglRouter` output path. Parked as Slice C — 2027 target when minSdk floor naturally rises to 28-29 and Compose ships stable spring physics. See "Future work".

**Hard invariants (preserved by this slice):**
- No `service/dualrecord/**`, `EglRouter`, `AspectFitMath`, `DualVideoRecorder`, muxer, or recording-pipeline behaviour changes. Recorded files byte-identical.
- ADR-0009 + ADR-0010 + ADR-0011 outputs unchanged. `buildCropMatrix` / `buildSideAspectCrop` / `buildPreviewCropMatrix` and all `AspectFitMathTest` assertions untouched.
- `WarningId` / `WarningPrecedence` / `WarningCenterViewModel` / Start-gate / recovery flow untouched.
- `DualPreviewZone` `TextureView` / `SurfaceTexture` / `registerPreviewSurface` lifecycle untouched. P+L `352f` / `225f` zone weights + `cam-split-divider` untouched.
- `MainActivity.kt` `enableEdgeToEdge()` + `values-v29/themes.xml` + `Theme.kt` transparent-bar writes (Slice A) untouched.
- `RecordTopOverlay` / `RecordCameraControls` / `RecordRecoveryChip` / `RecordActiveHud` (R2) untouched.

## Future work

**Slice C — "Ambient Liquid Glass"** (Variant D from the brainstorm mockup). Three pieces:

1. **Liquid Glass material** approximated via Compose `RenderEffect.createBlurEffect` (API 31+) on chrome surfaces with inset-highlight + cool tint + depth shadow — matches iOS 26 Liquid Glass aesthetic (Apple, June 2025) and Android 17's Material 3 Expressive translucent surfaces (Google, 2026).
2. **Material 3 Expressive shape-morphing** on the FAB (Start ↔ Stop via spring physics) and chip backgrounds. Requires Jetpack Compose Material 3 Expressive stable APIs (currently alpha-beta; production-stable target 2027).
3. **Ambient adaptive chrome** — scene-tint sampling (preview frame dominant hue → chrome border colour) + scene-brightness-driven chrome alpha. Requires a new producer wired into the chrome layer (sampling, NOT touching the frozen `EglRouter` output).

Ship sequence: ride out the API 31+ floor + Compose Material 3 Expressive maturation through 2026-27, then revisit when minSdk can naturally rise to 28-29 (or 31 if the install base allows).

**Mockup file:** `mockups/new_uiux/01d-record-home-slice-b.html` (local — `mockups/` is gitignored). Contains all four variants (A · B · C · D) side-by-side with the full trade-off table.
```

- [ ] **Step 3: Commit**

```bash
git add docs/adr/0012-gradient-scrim-dock.md
git commit -m "docs(adr): ADR-0012 — gradient-scrim dock + pill card + Mode tap-cycle"
```

---

## Task 9: Final gates + PR

**Files:** none (gates + PR creation)

- [ ] **Step 1: Run all gates unfiltered (replays Task 6's gates but on the full diff)**

```powershell
./gradlew.bat :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
```

Expected: all three BUILD SUCCESSFUL. Test count = baseline + 4 (the four `cycleModeNext` tests added in Task 1). Lint count = baseline (no new InlinedApi / NewApi / SDK gate findings).

- [ ] **Step 2: Confirm the diff matches the spec's file allowlist**

```powershell
git diff master --name-only
```

Expected output (exactly 9 files):

```
app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt
app/src/main/java/com/aritr/rova/ui/screens/RecordModeCycle.kt
app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt
app/src/main/java/com/aritr/rova/ui/screens/RecordViewModel.kt
app/src/main/java/com/aritr/rova/ui/theme/RecordChromeTokens.kt
app/src/test/java/com/aritr/rova/ui/screens/RecordModeCycleTest.kt
docs/UI_DESIGN_TOKENS.md
docs/adr/0012-gradient-scrim-dock.md
docs/superpowers/plans/2026-05-23-edge-to-edge-record-home-slice-b.md
```

NOTE: spec §9 lists 7 implementation files; Task 1's helper file (`RecordModeCycle.kt`) + its test file are the +2 over the spec count (the helper is the spec's "extract pure helper" pattern, not a new file in the spec's table — that's an implementation refinement). Plan file is +1. Total 7+2+1 = 10... but `RecordChromeMetrics` is inside `RecordChrome.kt`, not a separate file, so the spec's 7 lines up with these 9 minus the test (1) minus the helper (1) minus the plan (1) = 9 - 2 - 1 = 6 source files + 1 plan + 2 test/helper = 9 matches the expected listing. Sanity-check by inspection that no `service/dualrecord/**`, no `EglRouter`, no `AspectFitMath` file appears.

- [ ] **Step 3: Push branch**

```bash
git push -u origin feat/edge-to-edge-record-home-slice-b
```

Expected: branch published to remote with all 8-9 commits (1 per task; Task 5 + Task 6 may merge into 1 if implementer chose to skip the red intermediate commit).

- [ ] **Step 4: Open PR**

```bash
gh pr create --title "feat(ui): edge-to-edge record-home — Slice B (gradient scrim dock + Mode tap-cycle)" --body "$(cat <<'EOF'
## Summary
- Dock fill flips from flat `bottomNavFill` → vertical gradient brush (transparent top 35% → 0.55 black bottom). Dissolves into the OS-transparent gesture-nav region from Slice A with no band edge.
- `RecordSettingsCard` corner radius 14 dp → 22 dp pill. Mode cell becomes a `ModeCycleChip` with `combinedClickable`: tap cycles Portrait → Landscape → P+L → Portrait; long-press opens the settings sheet (gesture redundancy).
- `RecordChromeMetrics.settingsCardLift = 30.dp` added; settings card bottom padding becomes `bottomNavClearance + settingsCardLift = 120 dp`, clearing the gradient's transparent zone.

Spec: `docs/superpowers/specs/2026-05-23-edge-to-edge-record-home-slice-b-design.md`
Plan: `docs/superpowers/plans/2026-05-23-edge-to-edge-record-home-slice-b.md`
ADR: `docs/adr/0012-gradient-scrim-dock.md`

## Hard invariants (preserved)
- No `service/dualrecord/**`, `EglRouter`, `AspectFitMath`, `DualVideoRecorder`, muxer, or recording-pipeline behaviour changes. Recorded files byte-identical.
- ADR-0009 + ADR-0010 + ADR-0011 outputs unchanged. AspectFitMathTest stays green.
- `WarningId` / `WarningPrecedence` / `WarningCenterViewModel` / Start-gate / recovery untouched.
- `DualPreviewZone` TextureView lifecycle + `352f` / `225f` zone weights + `cam-split-divider` untouched.
- R2 active-HUD (loop-pill / status-pill / Stop FAB) unaffected — top-anchored.

## Test plan (owner — Samsung SM-A176B)
- [ ] **Idle Portrait**: dock dissolves from top — no visible band edge against camera. Settings card sits cleanly above with no alpha-curve seam. Tap Mode chip — cycles Portrait → Landscape → P+L → Portrait. Long-press Mode chip — opens settings sheet.
- [ ] **Idle Landscape**: same as Portrait.
- [ ] **Idle DualShot (P+L)**: same as Portrait. Confirm gradient overlays the bottom of the Landscape zone without obscuring the recording-frame guide (ADR-0010).
- [ ] **Active recording**: settings card dims to 75 % alpha; Mode chip non-interactive. R2 active HUD top-anchored, unaffected.
- [ ] **Merge state**: same as active.
- [ ] **Gesture-nav transparency continuity**: gradient blends into OS gesture-nav region with no hard boundary.
- [ ] **30 dp lift eyeball**: settings card comfortably above the gradient band; not floating awkwardly high.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

Expected: PR URL printed. Owner reviews + opens on-device smoke per the test plan checklist.

- [ ] **Step 5: Post the done-report comment on the PR**

```bash
gh pr comment $(gh pr view --json number --jq .number) --body "Slice B implementation complete. All gates green: assembleDebug OK · lintDebug = baseline · testDebugUnitTest = baseline + 4 (the four \`cycleModeNext\` tests). Diff is exactly the 9 files in plan Task 9 Step 2. Awaiting owner on-device smoke (Samsung SM-A176B) + merge."
```

- [ ] **Step 6: Update memory if owner directs (NOT automatic — owner-only)**

Memory files in `C:\Users\HP\.claude\projects\g--Books-Python-ACTUAL-CODES-PROJECTS-rova-android\memory\` are owner-only per project convention. Do NOT edit them from this task. If owner asks for a memory update after merge, that is a separate explicit ask.
