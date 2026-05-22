# Settings-Sheet Re-skin Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Re-skin the session settings sheet to pixel-match `mockups/new_uiux/02-settings-sheet.html` — a custom camera-peek panel with inline `−`/value/`+` steppers and HD/FHD/4K-style quality chips, replacing today's tap-row → secondary-edit-sheet flow.

**Architecture:** A new `SettingsSheet.kt` custom overlay replaces the Material `ModalBottomSheet` in `SessionSettingsSheet.kt`. The live camera "peeks" through the translucent top 212 dp; an opaque panel below holds the mode tabs, inline steppers, quality chips, and a Save-=-dismiss CTA. The 4 secondary edit sheets (`RecordEditSheets.kt`) and the `SheetTarget` router are retired. Edits write through immediately (unchanged persistence).

**Tech Stack:** Kotlin · Jetpack Compose (BOM `2025.01.01`) · `androidx.compose.animation` (AnimatedVisibility) · JUnit 4 (the bounds helper).

**Spec:** `docs/superpowers/specs/2026-05-22-settings-sheet-reskin-design.md`

---

## Spec-Gap Resolution — Repeats "continuous" (READ FIRST)

The app supports a **continuous / "until you stop"** repeats setting (`loopCount = -1`). The `02-settings-sheet.html` mockup shows the Repeats row as a plain `−`/value/`+` stepper with no continuous affordance. Dropping continuous would be a feature loss (spec §9: settings semantics must not change).

**Resolution:** the inline Repeats stepper keeps continuous reachable as a position **one step below the minimum (1)**. Stepping `−` from `1` lands on continuous; `+` from continuous returns to `1`. The stepper value slot shows **`∞`** for continuous (the full "Until you stop" string is too wide for the 34 dp value slot — the Phase-2 settings *card* keeps showing "Until you stop" via the unchanged `recordRepeatsValue`).

If the owner wants different behaviour (e.g. continuous unreachable from this sheet, or a separate toggle), raise it at plan-review — every other task is independent of this choice except Task 3 and Task 6.

---

## Testing Policy — Read First

The settings sheet is Compose UI — layout, colour, typography. The project has **no Robolectric / Compose-UI-test layer**; pixel UI is not unit-tested (Phase 1/2/R1/R2 precedent).

**The one testable seam:** `RecordSettingBounds` (Task 3) is pure Kotlin (clamp / step math) — it gets JVM tests. Everything else is verified by build + lint + the existing suite staying green.

**Gradle is subagent-routed** — the implementer subagent runs `.\gradlew.bat` directly; the controller does not.

---

## Branch & Baseline

- **Branch:** `feat/settings-sheet-reskin`, cut from `master` @ `8193feb` (PR #36 merged).
- **Baseline @ `8193feb`:**
  - `:app:testDebugUnitTest` — **1026 tests / 82 classes / 0-0-0**
  - `:app:lintDebug` — **53 issues (50 W + 3 H + 0 E)**
  - `:app:assembleDebug` — BUILD SUCCESSFUL
- **Predicted after:** test count **1035 / 83** (`RecordSettingBoundsTest` adds 9 tests / 1 class); lint **≤ 53**; `assembleDebug` OK.

**Diff allowlist — `git diff master..HEAD --name-only` must equal exactly:**
```
app/src/main/java/com/aritr/rova/ui/screens/SettingsSheet.kt
app/src/main/java/com/aritr/rova/ui/screens/RecordSettingsFormat.kt
app/src/main/java/com/aritr/rova/ui/screens/RecordSettingBounds.kt
app/src/main/java/com/aritr/rova/ui/theme/SettingsSheetTokens.kt
app/src/test/java/com/aritr/rova/ui/screens/RecordSettingBoundsTest.kt
app/src/main/java/com/aritr/rova/ui/theme/RovaTokens.kt
app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt
app/src/main/java/com/aritr/rova/ui/screens/RecordViewModel.kt
docs/UI_DESIGN_TOKENS.md
app/src/main/java/com/aritr/rova/ui/screens/RecordEditSheets.kt
app/src/main/java/com/aritr/rova/ui/screens/RecordSheetTarget.kt
app/src/main/java/com/aritr/rova/ui/screens/SessionSettingsSheet.kt
```
(The last three appear as **deletions**.)

---

## File Structure

| File | Action | Responsibility |
|---|---|---|
| `ui/screens/RecordSettingsFormat.kt` | Create | The 3 display formatters moved out of `SessionSettingsSheet.kt` (so `RecordChrome.kt` keeps compiling) + `recordRepeatsStepperValue`. |
| `ui/screens/RecordSettingBounds.kt` | Create | Pure clamp/step math for the inline steppers. JVM-tested. |
| `ui/theme/SettingsSheetTokens.kt` | Create | `02-settings-sheet.html`-exact colour/dimension constants. |
| `ui/screens/SettingsSheet.kt` | Create | The custom peek-panel sheet + all its private sub-composables. |
| `ui/theme/RovaTokens.kt` | Modify | Add the 7 sheet text styles. |
| `ui/screens/RecordViewModel.kt` | Modify | Delete the `editingField` / `openSheet` / `closeSheet` API. |
| `ui/screens/RecordScreen.kt` | Modify | Delete the edit-sheet router; emit `SettingsSheet`; suppress chrome while open. |
| `docs/UI_DESIGN_TOKENS.md` | Modify | Document `SettingsSheetTokens` + the new `RovaTokens` styles. |
| `ui/screens/RecordEditSheets.kt` | Delete | The 4 secondary edit sheets — retired (inline editing). |
| `ui/screens/RecordSheetTarget.kt` | Delete | The `SheetTarget` enum — retired with the router. |
| `ui/screens/SessionSettingsSheet.kt` | Delete | Replaced by `SettingsSheet.kt`. |

`RecordChrome.kt` is **not modified** — the 3 formatters it calls stay top-level `internal` in the same package (`com.aritr.rova.ui.screens`), so its call sites resolve unchanged after the move.

---

## Task 1: Recon — confirm the deletions are safe

No code change — a verification gate that the three files can be deleted without an orphaned consumer.

**Files:** none (verification only).

- [ ] **Step 1: Grep for every consumer**

Run, from the repo root:
```
git grep -n "SheetTarget\|ClipLengthEditSheet\|RepeatsEditSheet\|WaitEditSheet\|QualityEditSheet\|SessionSettingsSheet\|recordClipValue\|recordRepeatsValue\|recordWaitValue\|openSheet\|closeSheet\|editingField" -- app/src
```

- [ ] **Step 2: Confirm the expected consumer set**

Expected — and ONLY these — outside the three files being deleted:
- `RecordChrome.kt` — calls `recordClipValue` / `recordRepeatsValue` / `recordWaitValue` (the Phase-2 settings card). Handled by Task 2.
- `RecordScreen.kt` — the `when (editingField)` router + the `SessionSettingsSheet(...)` call. Handled by Task 9.
- `RecordViewModel.kt` — `_editingField` / `editingField` / `openSheet` / `closeSheet`. Handled by Task 8.

If grep finds `SheetTarget` / the edit-sheet composables referenced anywhere **else**, STOP and report — the plan's deletion scope is wrong and must be revised.

- [ ] **Step 3: Report**

Report the grep output and an explicit "deletions are safe — only the 3 expected consumers" (or the blocker). No commit.

---

## Task 2: Move the display formatters out of `SessionSettingsSheet.kt`

`SessionSettingsSheet.kt` will be deleted (Task 10), but `RecordChrome.kt` calls its 3 `internal` formatters. Move them to a new file in the same package **now** so nothing breaks in the interim. Also add the stepper-only `∞` repeats formatter.

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/screens/RecordSettingsFormat.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/SessionSettingsSheet.kt`

- [ ] **Step 1: Create `RecordSettingsFormat.kt`**

```kotlin
package com.aritr.rova.ui.screens

// ── Settings-card / settings-sheet display-value formatters (sentinel-blind).
// Moved here from SessionSettingsSheet.kt (which is being retired) so the
// Phase-2 RecordChrome settings card and the new SettingsSheet share one
// source. Top-level `internal`, same package — call sites need no import.

internal fun recordClipValue(seconds: Int): String = when {
    seconds <= 0 -> "0 s"
    seconds < 60 -> "$seconds s"
    seconds == 60 -> "1 m"
    seconds % 60 == 0 -> "${seconds / 60} m"
    else -> "$seconds s"
}

internal fun recordRepeatsValue(loopCount: Int): String =
    if (loopCount < 0) "Until you stop" else loopCount.toString()

internal fun recordWaitValue(intervalMinutes: Int): String = when {
    intervalMinutes <= 0 -> "None"
    intervalMinutes == 60 -> "1 h"
    intervalMinutes % 60 == 0 -> "${intervalMinutes / 60} h"
    else -> "$intervalMinutes m"
}

/**
 * Compact repeats value for the inline stepper's narrow value slot — the
 * continuous sentinel renders as `∞` (the full "Until you stop" string from
 * [recordRepeatsValue] is too wide for the 34 dp slot; the Phase-2 settings
 * card still uses [recordRepeatsValue]).
 */
internal fun recordRepeatsStepperValue(loopCount: Int): String =
    if (loopCount < 0) "∞" else loopCount.toString()
```

- [ ] **Step 2: Delete the 3 formatters from `SessionSettingsSheet.kt`**

In `SessionSettingsSheet.kt`, delete lines ~38-58 — the comment block and the three functions `recordClipValue`, `recordRepeatsValue`, `recordWaitValue`. Leave the rest of the file (it still references them; same package, resolves to the new file).

- [ ] **Step 3: Compile-gate**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`. (Proves no duplicate-declaration and that `RecordChrome.kt` + `SessionSettingsSheet.kt` still resolve the formatters.)

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/RecordSettingsFormat.kt app/src/main/java/com/aritr/rova/ui/screens/SessionSettingsSheet.kt
git commit -m "refactor(ui): extract record-settings display formatters to their own file"
```

---

## Task 3: `RecordSettingBounds` — the stepper math (TDD)

Pure clamp/step logic for the inline steppers. The ranges/step are transcribed verbatim from the retired `RecordEditSheets.kt` (`CLIP_MIN/MAX`, `clipStep`, `REPEATS_MIN/MAX`, `WAIT_MIN/MAX`).

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/screens/RecordSettingBounds.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/screens/RecordSettingBoundsTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/aritr/rova/ui/screens/RecordSettingBoundsTest.kt`:

```kotlin
package com.aritr.rova.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordSettingBoundsTest {

    @Test fun clipStep_is5BelowAMinute_and15AtOrAbove() {
        assertEquals(5, RecordSettingBounds.clipStep(30))
        assertEquals(5, RecordSettingBounds.clipStep(59))
        assertEquals(15, RecordSettingBounds.clipStep(60))
        assertEquals(15, RecordSettingBounds.clipStep(120))
    }

    @Test fun stepClip_incrementsByClipStep_clampedToMax() {
        assertEquals(35, RecordSettingBounds.stepClip(30, +1))
        assertEquals(25, RecordSettingBounds.stepClip(30, -1))
        assertEquals(75, RecordSettingBounds.stepClip(60, +1))
        assertEquals(300, RecordSettingBounds.stepClip(300, +1))
        assertEquals(300, RecordSettingBounds.stepClip(295, +1))
    }

    @Test fun stepClip_clampsToMin() {
        assertEquals(1, RecordSettingBounds.stepClip(1, -1))
        assertEquals(1, RecordSettingBounds.stepClip(3, -1))
    }

    @Test fun stepRepeats_minStepsDownToContinuous() {
        assertEquals(
            RecordSettingBounds.REPEATS_CONTINUOUS,
            RecordSettingBounds.stepRepeats(1, -1),
        )
    }

    @Test fun stepRepeats_continuousStepsUpToMin() {
        assertEquals(1, RecordSettingBounds.stepRepeats(RecordSettingBounds.REPEATS_CONTINUOUS, +1))
    }

    @Test fun stepRepeats_continuousStaysOnFurtherDecrement() {
        assertEquals(
            RecordSettingBounds.REPEATS_CONTINUOUS,
            RecordSettingBounds.stepRepeats(RecordSettingBounds.REPEATS_CONTINUOUS, -1),
        )
    }

    @Test fun stepRepeats_incrementsAndClampsToMax() {
        assertEquals(11, RecordSettingBounds.stepRepeats(10, +1))
        assertEquals(9, RecordSettingBounds.stepRepeats(10, -1))
        assertEquals(999, RecordSettingBounds.stepRepeats(999, +1))
    }

    @Test fun stepWait_incrementsByOne_clamped() {
        assertEquals(6, RecordSettingBounds.stepWait(5, +1))
        assertEquals(4, RecordSettingBounds.stepWait(5, -1))
        assertEquals(0, RecordSettingBounds.stepWait(0, -1))
        assertEquals(60, RecordSettingBounds.stepWait(60, +1))
    }

    @Test fun atBound_helpers() {
        assertTrue(RecordSettingBounds.clipAtMin(1))
        assertTrue(RecordSettingBounds.clipAtMax(300))
        assertFalse(RecordSettingBounds.clipAtMin(30))
        assertTrue(RecordSettingBounds.repeatsAtMin(RecordSettingBounds.REPEATS_CONTINUOUS))
        assertTrue(RecordSettingBounds.repeatsAtMax(999))
        assertFalse(RecordSettingBounds.repeatsAtMin(1))
        assertTrue(RecordSettingBounds.waitAtMin(0))
        assertTrue(RecordSettingBounds.waitAtMax(60))
    }
}
```

- [ ] **Step 2: Run the test — verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.screens.RecordSettingBoundsTest"`
Expected: FAIL — `RecordSettingBounds` is unresolved.

- [ ] **Step 3: Write `RecordSettingBounds.kt`**

Create `app/src/main/java/com/aritr/rova/ui/screens/RecordSettingBounds.kt`:

```kotlin
package com.aritr.rova.ui.screens

/**
 * Pure clamp/step math for the inline settings-sheet steppers
 * (`SettingsSheet.kt`). Ranges + `clipStep` are transcribed verbatim from the
 * retired `RecordEditSheets.kt`. `dir` is `-1` (decrement) or `+1` (increment).
 *
 * Repeats has a continuous position ([REPEATS_CONTINUOUS], the app's
 * `loopCount = -1` "until you stop" sentinel) one step below [REPEATS_MIN]:
 * `−` from 1 lands on continuous; `+` from continuous returns to 1.
 */
internal object RecordSettingBounds {

    const val CLIP_MIN = 1
    const val CLIP_MAX = 300
    const val REPEATS_MIN = 1
    const val REPEATS_MAX = 999
    const val REPEATS_CONTINUOUS = -1
    const val WAIT_MIN = 0
    const val WAIT_MAX = 60

    /** 5 s steps below a minute, 15 s at/above — matches the old ClipLengthEditSheet. */
    fun clipStep(seconds: Int): Int = if (seconds < 60) 5 else 15

    fun stepClip(current: Int, dir: Int): Int {
        val c = current.coerceIn(CLIP_MIN, CLIP_MAX)
        return (c + dir * clipStep(c)).coerceIn(CLIP_MIN, CLIP_MAX)
    }

    fun stepRepeats(current: Int, dir: Int): Int = when {
        current == REPEATS_CONTINUOUS -> if (dir > 0) REPEATS_MIN else REPEATS_CONTINUOUS
        current <= REPEATS_MIN && dir < 0 -> REPEATS_CONTINUOUS
        else -> (current + dir).coerceIn(REPEATS_MIN, REPEATS_MAX)
    }

    fun stepWait(current: Int, dir: Int): Int =
        (current.coerceIn(WAIT_MIN, WAIT_MAX) + dir).coerceIn(WAIT_MIN, WAIT_MAX)

    fun clipAtMin(v: Int): Boolean = v.coerceIn(CLIP_MIN, CLIP_MAX) <= CLIP_MIN
    fun clipAtMax(v: Int): Boolean = v.coerceIn(CLIP_MIN, CLIP_MAX) >= CLIP_MAX
    fun repeatsAtMin(v: Int): Boolean = v == REPEATS_CONTINUOUS
    fun repeatsAtMax(v: Int): Boolean = v >= REPEATS_MAX
    fun waitAtMin(v: Int): Boolean = v.coerceIn(WAIT_MIN, WAIT_MAX) <= WAIT_MIN
    fun waitAtMax(v: Int): Boolean = v.coerceIn(WAIT_MIN, WAIT_MAX) >= WAIT_MAX
}
```

- [ ] **Step 4: Run the test — verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.screens.RecordSettingBoundsTest"`
Expected: PASS — 9 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/RecordSettingBounds.kt app/src/test/java/com/aritr/rova/ui/screens/RecordSettingBoundsTest.kt
git commit -m "feat(ui): add RecordSettingBounds — pure stepper clamp/step math"
```

---

## Task 4: `SettingsSheetTokens` — the mockup constants

`02-settings-sheet.html`-exact colour/dimension constants. CSS px → `Dp`, CSS `rgba` → `Color`.

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/theme/SettingsSheetTokens.kt`

- [ ] **Step 1: Create the file**

```kotlin
package com.aritr.rova.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Phase — settings-sheet re-skin. Mockup-exact pixel constants for the custom
 * settings sheet (`mockups/new_uiux/02-settings-sheet.html`). Settings-sheet-
 * scoped on purpose (same rationale as [RecordChromeTokens]). CSS px → `Dp`,
 * CSS `rgba` → `Color`. The CSS `backdrop-filter` blurs are NOT tokenised —
 * Compose has no backdrop-blur API; the panel fill is near-opaque (`0.97`)
 * so the no-blur approximation is faithful here.
 */
object SettingsSheetTokens {

    // ── Camera peek (top of the sheet) ──────────────────────────────────
    /** `.camera-peek` height. */
    val peekHeight = 212.dp
    /** `.peek-scrim` gradient — `rgba(0,0,0,0.18)` (top) → `rgba(0,0,0,0.62)` (bottom). */
    val peekScrimTop = Color.Black.copy(alpha = 0.18f)
    val peekScrimBottom = Color.Black.copy(alpha = 0.62f)
    /** `.peek-status` pill. */
    val peekStatusInsetStart = 16.dp
    val peekStatusInsetBottom = 14.dp
    val peekStatusPaddingH = 10.dp
    val peekStatusPaddingV = 5.dp
    val peekStatusFill = Color.Black.copy(alpha = 0.38f)
    val peekStatusStroke = Color.White.copy(alpha = 0.07f)
    val peekStatusRadius = 20.dp
    /** `.peek-dot`. */
    val peekDotSize = 5.dp
    val peekDotIdle = Color.White.copy(alpha = 0.22f)
    /** `.peek-txt` colour. */
    val peekStatusText = Color.White.copy(alpha = 0.5f)
    /** `.cam-controls` end inset (`right: 10px`). Top inset = the status-bar inset. */
    val camControlsInsetEnd = 10.dp

    // ── Sheet panel ─────────────────────────────────────────────────────
    /** `.settings-sheet` background — `rgba(9,13,20,0.97)`. */
    val sheetFill = Color(0xF7090D14)
    /** `.settings-sheet` top border — `rgba(255,255,255,0.085)`. */
    val sheetTopStroke = Color.White.copy(alpha = 0.085f)
    val sheetCornerRadius = 24.dp
    val sheetPaddingH = 18.dp
    val sheetPaddingBottom = 26.dp

    // ── Sheet-top handle ────────────────────────────────────────────────
    val handleWidth = 32.dp
    val handleHeight = 4.dp
    val handleColor = Color.White.copy(alpha = 0.16f)
    val handleRadius = 2.dp
    val sheetTopPaddingTop = 12.dp
    val sheetTopPaddingBottom = 14.dp

    // ── Section labels ──────────────────────────────────────────────────
    /** `.sheet-section-label` colour. */
    val sectionLabelColor = Color.White.copy(alpha = 0.2f)
    /** Gap between a section label and the content below it. */
    val sectionLabelGap = 8.dp

    // ── Mode tabs ───────────────────────────────────────────────────────
    val modeTabsTrackFill = Color.White.copy(alpha = 0.05f)
    val modeTabsRadius = 13.dp
    val modeTabsPadding = 3.dp
    val modeTabsGap = 2.dp
    val modeTabsBottomMargin = 20.dp
    val modeTabPaddingH = 4.dp
    val modeTabPaddingV = 8.dp
    val modeTabRadius = 10.dp
    val modeTabActiveFill = Color.White.copy(alpha = 0.11f)
    val modeTabActiveText = Color.White.copy(alpha = 0.90f)
    val modeTabIdleText = Color.White.copy(alpha = 0.26f)
    val modeTabDisabledText = Color.White.copy(alpha = 0.16f)

    // ── Setting rows ────────────────────────────────────────────────────
    /** `.s-row` vertical padding. */
    val rowPaddingV = 13.dp
    /** `.s-row` divider — `rgba(255,255,255,0.046)`. */
    val rowDivider = Color.White.copy(alpha = 0.046f)
    /** `.s-row-label` colour. */
    val rowLabelText = Color.White.copy(alpha = 0.46f)

    // ── Stepper ─────────────────────────────────────────────────────────
    val stepBtnSize = 27.dp
    val stepBtnRadius = 8.dp
    val stepBtnFill = Color.White.copy(alpha = 0.07f)
    val stepBtnStroke = Color.White.copy(alpha = 0.1f)
    val stepBtnGlyph = Color.White.copy(alpha = 0.55f)
    val stepperGap = 10.dp
    val stepValText = Color.White.copy(alpha = 0.88f)
    val stepValMinWidth = 34.dp

    // ── Quality chips ───────────────────────────────────────────────────
    val chipPaddingH = 12.dp
    val chipPaddingV = 5.dp
    val chipRadius = 20.dp
    val chipGroupGap = 5.dp
    val chipOnFill = Color.White.copy(alpha = 0.13f)
    val chipOnStroke = Color.White.copy(alpha = 0.18f)
    val chipOnText = Color.White.copy(alpha = 0.90f)
    val chipOffStroke = Color.White.copy(alpha = 0.09f)
    val chipOffText = Color.White.copy(alpha = 0.28f)

    // ── Save CTA ────────────────────────────────────────────────────────
    val ctaTopMargin = 18.dp
    val ctaPaddingV = 16.dp
    val ctaFill = Color.White.copy(alpha = 0.07f)
    val ctaStroke = Color.White.copy(alpha = 0.10f)
    val ctaRadius = 16.dp
    val ctaText = Color.White.copy(alpha = 0.72f)
}
```

> The `.peek-grid` (white `0.016` lines) from the mockup is intentionally
> omitted — `0.016` alpha is imperceptible over live camera footage (YAGNI).

- [ ] **Step 2: Compile-gate**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/theme/SettingsSheetTokens.kt
git commit -m "feat(ui): add SettingsSheetTokens — 02-settings-sheet.html pixel constants"
```

---

## Task 5: `RovaTokens` — settings-sheet text styles

Add the 7 sheet text styles to the existing `RovaTokens` object.

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/theme/RovaTokens.kt`

- [ ] **Step 1: Add the styles**

In `RovaTokens.kt`, immediately before the closing `}` of `object RovaTokens` (after the existing `zoneTag` style, ~line 132), insert:

```kotlin

    // ── Settings-sheet type scale (mockups/new_uiux/02-settings-sheet.html) ──

    /** `.sheet-section-label` — "RECORDING MODE" / "SETTINGS". */
    val sheetSectionLabel: TextStyle = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Medium,
        fontSize = 8.5.sp,
        letterSpacing = 2.sp,
    )

    /** `.s-row-label` — the setting-row label ("Clip Duration"). */
    val sheetRowLabel: TextStyle = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        letterSpacing = 0.1.sp,
    )

    /** `.step-val` — the stepper's numeric value. */
    val sheetStepValue: TextStyle = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        letterSpacing = (-0.2).sp,
        fontFeatureSettings = "tnum",
    )

    /** `.chip` — the quality chip label. */
    val sheetChip: TextStyle = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        letterSpacing = 0.4.sp,
    )

    /** `.mode-tab` — the recording-mode tab label. */
    val sheetModeTab: TextStyle = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 0.1.sp,
    )

    /** `.sheet-cta` — the "Save" button label. */
    val sheetCta: TextStyle = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        letterSpacing = 0.1.sp,
    )

    /** `.peek-txt` — the camera-peek mini status pill text. */
    val peekStatus: TextStyle = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Normal,
        fontSize = 10.sp,
        letterSpacing = 0.1.sp,
    )
```

(`Inter`, `TextStyle`, `FontWeight`, `sp` are already imported in `RovaTokens.kt`.)

- [ ] **Step 2: Compile-gate**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/theme/RovaTokens.kt
git commit -m "feat(ui): add settings-sheet text styles to RovaTokens"
```

---

## Task 6: `SettingsSheet.kt` — the custom peek-panel sheet

The whole sheet — public `SettingsSheet` + every private sub-composable — in one focused file.

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/screens/SettingsSheet.kt`

- [ ] **Step 1: Create the file**

```kotlin
package com.aritr.rova.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aritr.rova.data.QualityPresets
import com.aritr.rova.ui.theme.RovaTokens
import com.aritr.rova.ui.theme.SettingsSheetTokens

/**
 * Settings sheet — re-skin of `mockups/new_uiux/02-settings-sheet.html`.
 *
 * A custom bottom-anchored panel (NOT a Material `ModalBottomSheet`): the live
 * camera "peeks" through the translucent top [SettingsSheetTokens.peekHeight]
 * behind a scrim; the opaque panel below holds inline mode tabs, `−`/value/`+`
 * steppers, quality chips, and a Save CTA. Edits write through immediately;
 * "Save", the handle drag-down, and system-back all just dismiss.
 *
 * The caller emits this composable unconditionally and toggles [visible] — the
 * slide animation owns its own mount lifetime. The caller suppresses the record
 * chrome while [visible] so only the camera shows through the peek.
 */
@Composable
fun SettingsSheet(
    visible: Boolean,
    durationSeconds: Int,
    loopCount: Int,
    intervalMinutes: Int,
    quality: String,
    currentMode: String,
    editable: Boolean,
    statusText: String,
    flashMode: Int,
    flipEnabled: Boolean,
    onCycleFlash: () -> Unit,
    onFlip: () -> Unit,
    onDurationChange: (Int) -> Unit,
    onLoopCountChange: (Int) -> Unit,
    onIntervalChange: (Int) -> Unit,
    onQualityChange: (String) -> Unit,
    onModePick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it }),
    ) {
        BackHandler(enabled = true, onBack = onDismiss)
        Box(Modifier.fillMaxSize()) {
            SettingsPeek(
                statusText = statusText,
                flashMode = flashMode,
                flipEnabled = flipEnabled,
                controlsEnabled = editable,
                onCycleFlash = onCycleFlash,
                onFlip = onFlip,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .height(SettingsSheetTokens.peekHeight),
            )
            SettingsPanel(
                durationSeconds = durationSeconds,
                loopCount = loopCount,
                intervalMinutes = intervalMinutes,
                quality = quality,
                currentMode = currentMode,
                editable = editable,
                onDurationChange = onDurationChange,
                onLoopCountChange = onLoopCountChange,
                onIntervalChange = onIntervalChange,
                onQualityChange = onQualityChange,
                onModePick = onModePick,
                onDismiss = onDismiss,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .padding(top = SettingsSheetTokens.peekHeight),
            )
        }
    }
}

/* ── Camera peek ──────────────────────────────────────────────────────── */

@Composable
private fun SettingsPeek(
    statusText: String,
    flashMode: Int,
    flipEnabled: Boolean,
    controlsEnabled: Boolean,
    onCycleFlash: () -> Unit,
    onFlip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        // Scrim over the live camera (which RecordScreen renders beneath this overlay).
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            SettingsSheetTokens.peekScrimTop,
                            SettingsSheetTokens.peekScrimBottom,
                        ),
                    ),
                ),
        )
        // Mini status pill — bottom-start.
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(
                    start = SettingsSheetTokens.peekStatusInsetStart,
                    bottom = SettingsSheetTokens.peekStatusInsetBottom,
                )
                .clip(RoundedCornerShape(SettingsSheetTokens.peekStatusRadius))
                .background(SettingsSheetTokens.peekStatusFill)
                .border(
                    1.dp,
                    SettingsSheetTokens.peekStatusStroke,
                    RoundedCornerShape(SettingsSheetTokens.peekStatusRadius),
                )
                .padding(
                    horizontal = SettingsSheetTokens.peekStatusPaddingH,
                    vertical = SettingsSheetTokens.peekStatusPaddingV,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Box(
                Modifier
                    .size(SettingsSheetTokens.peekDotSize)
                    .clip(CircleShape)
                    .background(SettingsSheetTokens.peekDotIdle),
            )
            Text(statusText, style = RovaTokens.peekStatus, color = SettingsSheetTokens.peekStatusText)
        }
        // Flash + flip — reuse the Phase-2 shared chrome control.
        RecordCameraControls(
            flashMode = flashMode,
            onCycleFlash = onCycleFlash,
            onFlip = onFlip,
            enabled = controlsEnabled,
            flipEnabled = flipEnabled,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(end = SettingsSheetTokens.camControlsInsetEnd, top = 7.dp),
        )
    }
}

/* ── Sheet panel ──────────────────────────────────────────────────────── */

@Composable
private fun SettingsPanel(
    durationSeconds: Int,
    loopCount: Int,
    intervalMinutes: Int,
    quality: String,
    currentMode: String,
    editable: Boolean,
    onDurationChange: (Int) -> Unit,
    onLoopCountChange: (Int) -> Unit,
    onIntervalChange: (Int) -> Unit,
    onQualityChange: (String) -> Unit,
    onModePick: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val panelShape = RoundedCornerShape(
        topStart = SettingsSheetTokens.sheetCornerRadius,
        topEnd = SettingsSheetTokens.sheetCornerRadius,
    )
    Column(
        modifier = modifier
            .clip(panelShape)
            .background(SettingsSheetTokens.sheetFill)
            .border(1.dp, SettingsSheetTokens.sheetTopStroke, panelShape)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = SettingsSheetTokens.sheetPaddingH)
            .padding(bottom = SettingsSheetTokens.sheetPaddingBottom),
    ) {
        // Handle — drag down to dismiss.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    top = SettingsSheetTokens.sheetTopPaddingTop,
                    bottom = SettingsSheetTokens.sheetTopPaddingBottom,
                )
                .pointerInput(Unit) {
                    detectVerticalDragGestures { _, dragAmount ->
                        if (dragAmount > 8f) onDismiss()
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(
                        width = SettingsSheetTokens.handleWidth,
                        height = SettingsSheetTokens.handleHeight,
                    )
                    .clip(RoundedCornerShape(SettingsSheetTokens.handleRadius))
                    .background(SettingsSheetTokens.handleColor),
            )
        }

        SheetSectionLabel("Recording mode")
        Spacer(Modifier.height(SettingsSheetTokens.sectionLabelGap))
        ModeTabs(currentMode = currentMode, enabled = editable, onPick = onModePick)
        Spacer(Modifier.height(SettingsSheetTokens.modeTabsBottomMargin))

        SheetSectionLabel("Settings")
        Spacer(Modifier.height(SettingsSheetTokens.sectionLabelGap))

        StepperRow(
            label = "Clip Duration",
            value = recordClipValue(durationSeconds),
            enabled = editable,
            atMin = RecordSettingBounds.clipAtMin(durationSeconds),
            atMax = RecordSettingBounds.clipAtMax(durationSeconds),
            onStep = { dir -> onDurationChange(RecordSettingBounds.stepClip(durationSeconds, dir)) },
        )
        SheetRowDivider()
        StepperRow(
            label = "Repeats",
            value = recordRepeatsStepperValue(loopCount),
            enabled = editable,
            atMin = RecordSettingBounds.repeatsAtMin(loopCount),
            atMax = RecordSettingBounds.repeatsAtMax(loopCount),
            onStep = { dir -> onLoopCountChange(RecordSettingBounds.stepRepeats(loopCount, dir)) },
        )
        SheetRowDivider()
        StepperRow(
            label = "Wait Between",
            value = recordWaitValue(intervalMinutes),
            enabled = editable,
            atMin = RecordSettingBounds.waitAtMin(intervalMinutes),
            atMax = RecordSettingBounds.waitAtMax(intervalMinutes),
            onStep = { dir -> onIntervalChange(RecordSettingBounds.stepWait(intervalMinutes, dir)) },
        )
        SheetRowDivider()
        QualityRow(quality = quality, enabled = editable, onPick = onQualityChange)

        // Push the CTA to the bottom of the panel.
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.height(SettingsSheetTokens.ctaTopMargin))
        val ctaShape = RoundedCornerShape(SettingsSheetTokens.ctaRadius)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ctaShape)
                .background(SettingsSheetTokens.ctaFill)
                .border(1.dp, SettingsSheetTokens.ctaStroke, ctaShape)
                .clickable { onDismiss() }
                .padding(vertical = SettingsSheetTokens.ctaPaddingV),
            contentAlignment = Alignment.Center,
        ) {
            Text("Save", style = RovaTokens.sheetCta, color = SettingsSheetTokens.ctaText)
        }
    }
}

/* ── Pieces ───────────────────────────────────────────────────────────── */

@Composable
private fun SheetSectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = RovaTokens.sheetSectionLabel,
        color = SettingsSheetTokens.sectionLabelColor,
    )
}

@Composable
private fun SheetRowDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(SettingsSheetTokens.rowDivider),
    )
}

private enum class SheetModeTab(val label: String, val value: String) {
    Portrait("Portrait", "Portrait"),
    Landscape("Landscape", "Landscape"),
    PortraitLandscape("P + L", "PortraitLandscape"),
}

@Composable
private fun ModeTabs(currentMode: String, enabled: Boolean, onPick: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(SettingsSheetTokens.modeTabsRadius))
            .background(SettingsSheetTokens.modeTabsTrackFill)
            .padding(SettingsSheetTokens.modeTabsPadding),
        horizontalArrangement = Arrangement.spacedBy(SettingsSheetTokens.modeTabsGap),
    ) {
        SheetModeTab.entries.forEach { tab ->
            val isActive = currentMode == tab.value
            val tabShape = RoundedCornerShape(SettingsSheetTokens.modeTabRadius)
            val tabModifier = Modifier
                .weight(1f)
                .clip(tabShape)
                .let {
                    if (isActive) {
                        it.shadow(1.dp, tabShape).background(SettingsSheetTokens.modeTabActiveFill)
                    } else {
                        it
                    }
                }
                .let { if (enabled && !isActive) it.clickable { onPick(tab.value) } else it }
                .padding(
                    horizontal = SettingsSheetTokens.modeTabPaddingH,
                    vertical = SettingsSheetTokens.modeTabPaddingV,
                )
            val textColor = when {
                isActive -> SettingsSheetTokens.modeTabActiveText
                !enabled -> SettingsSheetTokens.modeTabDisabledText
                else -> SettingsSheetTokens.modeTabIdleText
            }
            Box(modifier = tabModifier, contentAlignment = Alignment.Center) {
                Text(tab.label, style = RovaTokens.sheetModeTab, color = textColor)
            }
        }
    }
}

@Composable
private fun StepperRow(
    label: String,
    value: String,
    enabled: Boolean,
    atMin: Boolean,
    atMax: Boolean,
    onStep: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = SettingsSheetTokens.rowPaddingV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = RovaTokens.sheetRowLabel,
            color = SettingsSheetTokens.rowLabelText,
            modifier = Modifier.weight(1f),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SettingsSheetTokens.stepperGap),
        ) {
            StepButton("−", enabled = enabled && !atMin, onClick = { onStep(-1) })
            Text(
                value,
                style = RovaTokens.sheetStepValue,
                color = SettingsSheetTokens.stepValText,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(min = SettingsSheetTokens.stepValMinWidth),
            )
            StepButton("+", enabled = enabled && !atMax, onClick = { onStep(+1) })
        }
    }
}

@Composable
private fun StepButton(glyph: String, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(SettingsSheetTokens.stepBtnRadius)
    Box(
        modifier = Modifier
            .size(SettingsSheetTokens.stepBtnSize)
            .clip(shape)
            .background(SettingsSheetTokens.stepBtnFill)
            .border(1.dp, SettingsSheetTokens.stepBtnStroke, shape)
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
            .alpha(if (enabled) 1f else 0.4f),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            glyph,
            color = SettingsSheetTokens.stepBtnGlyph,
            fontSize = 16.sp,
            fontWeight = FontWeight.Light,
        )
    }
}

@Composable
private fun QualityRow(quality: String, enabled: Boolean, onPick: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = SettingsSheetTokens.rowPaddingV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Quality",
            style = RovaTokens.sheetRowLabel,
            color = SettingsSheetTokens.rowLabelText,
            modifier = Modifier.weight(1f),
        )
        val current = QualityPresets.canonicalizeOrDefault(quality)
        Row(horizontalArrangement = Arrangement.spacedBy(SettingsSheetTokens.chipGroupGap)) {
            QualityPresets.PICKER_ORDER.forEach { option ->
                QualityChip(
                    label = option,
                    selected = option == current,
                    enabled = enabled,
                    onClick = { onPick(option) },
                )
            }
        }
    }
}

@Composable
private fun QualityChip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(SettingsSheetTokens.chipRadius)
    val fill = if (selected) SettingsSheetTokens.chipOnFill else Color.Transparent
    val stroke = if (selected) SettingsSheetTokens.chipOnStroke else SettingsSheetTokens.chipOffStroke
    val textColor = if (selected) SettingsSheetTokens.chipOnText else SettingsSheetTokens.chipOffText
    Box(
        modifier = Modifier
            .clip(shape)
            .background(fill)
            .border(1.dp, stroke, shape)
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
            .alpha(if (enabled) 1f else 0.5f)
            .padding(
                horizontal = SettingsSheetTokens.chipPaddingH,
                vertical = SettingsSheetTokens.chipPaddingV,
            ),
    ) {
        Text(label, style = RovaTokens.sheetChip, color = textColor)
    }
}
```

> `RecordCameraControls` is the Phase-2 composable in `RecordChrome.kt` (same
> package — no import). Its signature is
> `RecordCameraControls(flashMode: Int, onCycleFlash: () -> Unit, onFlip: () -> Unit, enabled: Boolean, flipEnabled: Boolean, modifier: Modifier)`.
> If the parameter names differ, match the actual signature and report it.

- [ ] **Step 2: Compile-gate**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`. (`SettingsSheet` is not yet referenced — this proves it compiles standalone.)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/SettingsSheet.kt
git commit -m "feat(ui): add SettingsSheet — custom camera-peek settings panel"
```

---

## Task 7: `RecordViewModel` — delete the edit-sheet-router API

The `editingField` / `openSheet` / `closeSheet` API drove the retired secondary sheets. The combined-sheet API (`combinedSettingsOpen` / `openSettingsSheet` / `closeSettingsSheet`) stays.

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordViewModel.kt`

- [ ] **Step 1: Delete the `editingField` block**

In `RecordViewModel.kt`, delete the block at ~lines 107-120 — the comment, the `_editingField` / `editingField` properties, and the `openSheet` / `closeSheet` functions:

```kotlin
    // --- Slice 2: which idle-dock cell currently has its focused edit
    // sheet mounted. null = no sheet open. Lives on the VM so the sheet
    // visibility survives configuration changes. Single-cell mode by
    // construction — only one sheet can be open at a time.
    private val _editingField = MutableStateFlow<SheetTarget?>(null)
    val editingField: StateFlow<SheetTarget?> = _editingField.asStateFlow()

    fun openSheet(target: SheetTarget) {
        _editingField.value = target
    }

    fun closeSheet() {
        _editingField.value = null
    }
```

Leave the `combinedSettingsOpen` block immediately below it intact.

- [ ] **Step 2: Drop the now-unused `SheetTarget` import**

If `RecordViewModel.kt` has an `import ...SheetTarget` line (it may not — `SheetTarget` is same-package), remove it only if the compiler flags it unused. `SheetTarget` is in `com.aritr.rova.ui.screens` (same package as the VM) — most likely there is no import line. Do not add one.

- [ ] **Step 3: Compile-gate**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: **FAIL** — `RecordScreen.kt` still calls `viewModel.openSheet` / `viewModel.closeSheet` / `viewModel.editingField`. This is expected; Task 8 fixes `RecordScreen.kt`. Confirm the *only* errors are in `RecordScreen.kt` referencing those three members; if anything else breaks, report it.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/RecordViewModel.kt
git commit -m "refactor(ui): drop RecordViewModel edit-sheet-router API (editingField/openSheet/closeSheet)"
```

(The branch does not build at this commit — Task 8 completes the pair. This is an intentional two-commit unit.)

---

## Task 8: `RecordScreen` — wire the new sheet, retire the router

Delete the `when (editingField)` router; replace the `SessionSettingsSheet` call with the always-emitted `SettingsSheet`; suppress the record chrome while the sheet is open.

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt`

**Hard invariant:** do NOT touch the Start-gate region (`startBlocked` derivation ~L108-123, `onStart`, `WarningId`, `WarningPrecedence`). This task edits only the camera-preview-area `Box` body and the sheet-rendering tail.

- [ ] **Step 1: Read the file**

Read `RecordScreen.kt` in full. Locate four regions:
1. The chrome block — from the `RecordRecoveryChip` `if` (~L506) through the `RecordBottomNav` call (~L658), inside the main `Box`.
2. The `MergeCompleteCard` block (~L666-675).
3. The `when (editingField)` edit-sheet router (~L682-710), after the `Scaffold` content lambda.
4. The `SessionSettingsSheet` block (~L712-728).

- [ ] **Step 2: Hoist the `combinedSettingsOpen` collection**

The `SessionSettingsSheet` block currently reads `val combinedOpen by viewModel.combinedSettingsOpen.collectAsStateWithLifecycle()` at ~L715. Move that line UP so it sits with the other `collectAsStateWithLifecycle` calls near the top of the composable body (alongside e.g. the `serviceState` collection), so `combinedOpen` is in scope for the chrome block. Use the existing import for `collectAsStateWithLifecycle` (already present).

- [ ] **Step 3: Suppress the chrome while the sheet is open**

Wrap the chrome block — the `RecordRecoveryChip` `if`, the idle `WarningCenter` `if`, the `when (hudState)`, the `RecordSettingsCard` `if (!showCompleteCard)` block, the idle `RecordTopOverlay` / `RecordCameraControls` `if`, and the `RecordBottomNav` call — in a single `if (!combinedOpen) { ... }`.

Concretely: insert `if (!combinedOpen) {` immediately before the `// Slice 2 / Phase 2.4 — read-only recovery echo` comment (~L503), and the matching `}` immediately after the `RecordBottomNav(...)` call's closing `)` (~L658, before the `MergeCompleteCard` comment). The camera-preview `Box` (~L439-479), the loading overlay (~L488-501), and the `MergeCompleteCard` block stay OUTSIDE the gate — the camera must keep rendering (it is the peek), and the merge-complete grace card is a critical overlay.

- [ ] **Step 4: Delete the edit-sheet router**

Delete the entire `when (editingField)` block (~L682-710) including its `// Edit sheets — single-target router` comment header (~L682-687). Also delete the line that collects `editingField` into a local (search for `editingField` — there is a `val ... by viewModel.editingField.collectAsStateWithLifecycle()` somewhere above; delete it).

- [ ] **Step 5: Replace the `SessionSettingsSheet` block with `SettingsSheet`**

Replace the entire `SessionSettingsSheet` block (~L712-728, the `// Task 12/14` comment + `if (combinedOpen) { SessionSettingsSheet(...) }`) with:

```kotlin
                // Settings sheet — the custom camera-peek panel
                // (mockups/new_uiux/02-settings-sheet.html). Always emitted;
                // SettingsSheet owns its slide animation via `visible`. Edits
                // write through immediately; Save / handle-drag / back dismiss.
                SettingsSheet(
                    visible = combinedOpen,
                    durationSeconds = duration,
                    loopCount = loopCount,
                    intervalMinutes = interval,
                    quality = resolution,
                    currentMode = mode,
                    editable = !isUiLocked,
                    statusText = statusText,
                    flashMode = flashMode,
                    flipEnabled = !isUiLocked && mode != "PortraitLandscape",
                    onCycleFlash = { if (!isUiLocked) viewModel.setFlashMode((flashMode + 1) % 3) },
                    onFlip = {
                        if (!isUiLocked && mode != "PortraitLandscape") viewModel.flipCamera()
                    },
                    onDurationChange = { viewModel.duration.value = it },
                    onLoopCountChange = { viewModel.loopCount.value = it },
                    onIntervalChange = { viewModel.interval.value = it },
                    onQualityChange = { viewModel.resolution.value = it },
                    onModePick = { viewModel.setMode(it) },
                    onDismiss = { viewModel.closeSettingsSheet() },
                )
```

Placement: this `SettingsSheet(...)` call must sit **inside the main `Box`** (so it overlays the camera preview + the chrome) — emit it as the **last child of that `Box`**, after the `MergeCompleteCard` block, before the `Box`'s closing `}`. It must NOT be left where the old `SessionSettingsSheet` was (outside the `Scaffold` lambda). Move it inside.

The values `duration`, `loopCount`, `interval`, `resolution`, `mode`, `isUiLocked`, `statusText`, `flashMode` are already locals in the composable (used by the existing chrome). `viewModel.duration` / `loopCount` / `interval` / `resolution` are public `MutableStateFlow`s — assigning `.value` is the same write path the deleted edit sheets used. `viewModel.setMode` / `setFlashMode` / `flipCamera` / `closeSettingsSheet` already exist.

- [ ] **Step 6: Remove now-unused imports**

After Steps 4-5, these become unused — remove any that the compiler/lint flags: `ClipLengthEditSheet`, `RepeatsEditSheet`, `WaitEditSheet`, `QualityEditSheet`, `SessionSettingsSheet`, `SheetTarget` (all same-package — likely no import lines exist; remove only real unused imports). Add no new imports — `SettingsSheet` is same-package.

- [ ] **Step 7: Compile-gate**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`. The branch builds again (Task 7 + Task 8 are the completed pair).

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt
git commit -m "feat(ui): wire the SettingsSheet overlay; retire the edit-sheet router"
```

---

## Task 9: Delete the retired files

With every consumer rerouted, delete the three retired files.

**Files:**
- Delete: `app/src/main/java/com/aritr/rova/ui/screens/RecordEditSheets.kt`
- Delete: `app/src/main/java/com/aritr/rova/ui/screens/RecordSheetTarget.kt`
- Delete: `app/src/main/java/com/aritr/rova/ui/screens/SessionSettingsSheet.kt`

- [ ] **Step 1: Delete the files**

```bash
git rm app/src/main/java/com/aritr/rova/ui/screens/RecordEditSheets.kt app/src/main/java/com/aritr/rova/ui/screens/RecordSheetTarget.kt app/src/main/java/com/aritr/rova/ui/screens/SessionSettingsSheet.kt
```

- [ ] **Step 2: Compile-gate**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`. If anything fails to resolve, a consumer was missed in Task 1's recon — report it.

> The `com.aritr.rova.ui.components` edit helpers (`EditSheetShell`,
> `LargeValueStepper`, `FixedContinuousSelector`, `QualityOptionSelector`,
> `QuickSetChipRow`, `QuickSetOption`, `RepeatsDraft`) are intentionally left
> in place — they are outside this spec's scope, `assembleDebug` proves nothing
> references the deleted code, and Android lint does not flag unused `public`
> composables. A future cleanup PR can remove any that are now orphaned.

- [ ] **Step 3: Commit**

```bash
git commit -m "refactor(ui): delete the retired settings sheets (RecordEditSheets, SheetTarget, SessionSettingsSheet)"
```

---

## Task 10: Update `docs/UI_DESIGN_TOKENS.md`

**Files:**
- Modify: `docs/UI_DESIGN_TOKENS.md`

- [ ] **Step 1: Read the doc**

Read `docs/UI_DESIGN_TOKENS.md` in full. Locate the §2.13 area (`RecordChromeTokens`) and the §2.2 area (`RovaTokens` type scale).

- [ ] **Step 2: Document the new tokens**

Add a short subsection (a sibling of the §2.13 `RecordChromeTokens` block — match the doc's existing numbering/heading style) describing `SettingsSheetTokens`: mockup-exact constants for the settings sheet (`02-settings-sheet.html`), settings-sheet-scoped, consumed by `SettingsSheet.kt`. In the `RovaTokens` type-scale section, note the 7 new sheet styles (`sheetSectionLabel`, `sheetRowLabel`, `sheetStepValue`, `sheetChip`, `sheetModeTab`, `sheetCta`, `peekStatus`). Keep it to a short paragraph each; do not restructure the doc.

- [ ] **Step 3: Commit**

```bash
git add docs/UI_DESIGN_TOKENS.md
git commit -m "docs(ui): document SettingsSheetTokens + the settings-sheet text styles"
```

---

## Task 11: Full-suite gate & invariant verification

**Files:** none — verification only.

- [ ] **Step 1: assembleDebug**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Unit-test suite**

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL. Sum the JUnit XMLs under `app/build/test-results/testDebugUnitTest/*.xml`: expect **1035 tests / 83 classes / 0 failures / 0 errors / 0 skipped** (baseline 1026/82 + `RecordSettingBoundsTest` 9/1). Any other drift means a tested helper changed — investigate.

- [ ] **Step 3: Lint**

Run: `.\gradlew.bat :app:lintDebug`
Expected: **≤ 53 issues**. If it rose, read the new findings (likely an unused import in `RecordScreen.kt`) and resolve them.

- [ ] **Step 4: Diff allowlist**

Run: `git diff master..HEAD --name-only`
Expected: **exactly** the 12 paths from the "Diff allowlist" section (9 modified/created + 3 deleted). Any extra path — especially a `service/**`, `dualrecord/**`, `RecordChrome.kt`, or warning/Start-gate file — is a hard-invariant violation; revert it.

- [ ] **Step 5: Report**

Report the three gate results, the exact test totals, the diff file list, and any deviations. No commit.

---

## Hard Invariants (verify untouched)

- `service/**`, `dualrecord/**`, the recording pipeline.
- `WarningId` / `WarningPrecedence` / `WarningCenter` / the `RecordScreen.kt` Start-gate region (`startBlocked`, `onStart`).
- `RecordChrome.kt` / `RecordChromeTokens.kt` (Phase 2) — not modified.
- `RovaSettings` persistence — the existing write-through path is reused, not changed.
- The mode-switch enable rules (P+L gating) — `editable = !isUiLocked` mirrors the old `modeEnabled`.

## Owner Follow-Up (not implementer scope)

- **On-device smoke** — install on the Samsung SM-A176B; open the settings sheet from the record settings card; compare to `02-settings-sheet.html`; confirm the camera peeks above with the scrim + mini status pill + flash/flip; the steppers / quality chips edit live; `−` at a bound disables; the Repeats `−` reaches `∞`; Save / handle-drag / back all dismiss; the record chrome is hidden while the sheet is open.
- **Sub-project 2** — P+L record-screen mode framing (`01-record-home.html` row 3) — own spec → plan → PR.
- After merge-ready: push, open the PR (base `master`).

## Self-Review

- **Spec coverage:** inline steppers + chips (Task 6) · custom peek panel (Task 6) · `SettingsSheetTokens` (Task 4) · `RovaTokens` styles (Task 5) · bounds helper + tests (Task 3) · formatters move (Task 2) · `RecordEditSheets`/`SheetTarget`/`SessionSettingsSheet` retired (Tasks 7-9) · RecordScreen wiring + chrome suppression (Task 8) · docs (Task 10) · gate + allowlist (Task 11). Spec §1-§11 all covered.
- **Placeholders:** none — every code step shows complete code. The one judgement call (Repeats continuous) is resolved explicitly at the top of the plan.
- **Type consistency:** `RecordSettingBounds.{stepClip,stepRepeats,stepWait,clipAtMin,clipAtMax,repeatsAtMin,repeatsAtMax,waitAtMin,waitAtMax,REPEATS_CONTINUOUS}` defined in Task 3 are exactly the symbols Task 6 consumes. `SettingsSheetTokens` members in Task 4 match every reference in Task 6. `RovaTokens.{sheetSectionLabel,sheetRowLabel,sheetStepValue,sheetChip,sheetModeTab,sheetCta,peekStatus}` from Task 5 match Task 6's uses. `recordClipValue`/`recordRepeatsValue`/`recordWaitValue`/`recordRepeatsStepperValue` from Task 2 match Task 6. `SettingsSheet`'s parameter list (Task 6) matches the call site (Task 8). Task 7's deletions are exactly the members Task 8 stops calling.
