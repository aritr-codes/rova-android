# DualShot Frame Polish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the 1.dp outline + 0.22f dark scrim in `RecordingFrameGuide` (P+L mode) with an API-gated blurred-glass treatment + halved scrim (0.11f), preserving the always-on capture-bounds indicator without the hard-line visual.

**Architecture:** Extract layout math into a pure-JVM helper `recordingFrameLayout(...)` (project precedent: `AspectFitMath`, `cycleModeNext`). Refactor `RecordingFrameGuide` from Canvas-only to Box-composition so a conditional `Modifier.graphicsLayer { renderEffect = RenderEffect.createBlurEffect(...) }` can be applied to scrim regions on API 31+, with flat 0.11f scrim as the universal baseline. No `EglRouter` / muxer / recording-pipeline changes.

**Tech Stack:** Kotlin 2.2.10, Jetpack Compose, AGP 9.2.1, JUnit 4, minSdk 24 / compileSdk 37, `android.graphics.RenderEffect` (API 31+).

**Spec:** [docs/superpowers/specs/2026-05-26-dualshot-frame-polish-design.md](../specs/2026-05-26-dualshot-frame-polish-design.md)

**Baseline commit:** `0690fe2` (after spec commit; master + spec doc only).

**Owner directive:** Execute under `/karpathy-guidelines` workflow (subagent-driven, fresh implementer per task, two-stage review per `feedback-review-gate-cycle`).

---

## File Structure

Three files in scope. Files that change together (Task 2 atomic refactor) live together; the pure-helper (Task 1) has zero coupling to the Compose refactor and lands independently.

| File | Role | Action |
|---|---|---|
| `app/src/main/java/com/aritr/rova/ui/screens/RecordingFrameLayout.kt` | Pure-JVM layout math (recording rect + scrim region computation) | **Create** (Task 1) |
| `app/src/test/java/com/aritr/rova/ui/screens/RecordingFrameLayoutTest.kt` | 8 JVM unit tests for the helper | **Create** (Task 1) |
| `app/src/main/java/com/aritr/rova/ui/theme/RecordChromeTokens.kt:225-239` | Recording-frame design tokens | **Modify** (Task 2) — delete outline tokens, halve scrim, add blur radius + divider alpha |
| `app/src/main/java/com/aritr/rova/ui/screens/DualPreviewZone.kt:62-66, 154-202` | `RecordingFrameGuide` composable + zone-divider Box | **Modify** (Task 2) — refactor Canvas → Box composition + API-gated blur |

**No other files touched.** Verified via grep: `recordingFrameOutline` / `recordingFrameStrokeWidth` / `recordingFrameScrim` are used **only** in `RecordChromeTokens.kt` (declaration) and `DualPreviewZone.kt` (usage); no other consumers.

---

## Pre-flight (subagent must verify before Task 1)

- [ ] **Step 0.1: Confirm baseline commit**

Run: `git log --oneline -1`
Expected: `0690fe2 docs(spec): Milestone 1 DualShot frame polish design`

- [ ] **Step 0.2: Confirm no other consumers of the recording-frame tokens**

Run (PowerShell): `Select-String -Path app/src -Pattern "recordingFrameOutline|recordingFrameStrokeWidth|recordingFrameScrim" -Recurse | Select-Object Path -Unique`
Expected: two file paths only — `RecordChromeTokens.kt` and `DualPreviewZone.kt`.

- [ ] **Step 0.3: Confirm working tree clean (or only carries permitted untracked files)**

Run: `git status --short`
Expected: untracked files allowed (`.claude/`, `.github/`, `.mcp.json`, `gradle_*_out.log`, `nul`) and `M NEW_UI_BACKEND_REPLAN.md` (pre-existing, must not be staged). No staged or unstaged changes in `app/`.

If `app/` has unstaged changes: STOP. Surface to owner.

---

## Task 1: Pure helper `recordingFrameLayout` + JVM tests

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/screens/RecordingFrameLayout.kt`
- Create: `app/src/test/java/com/aritr/rova/ui/screens/RecordingFrameLayoutTest.kt`

**TDD discipline:** Tests written before implementation. Compilation gates the discipline — the test file references `recordingFrameLayout` and `RecordingFrameLayout` types that do not yet exist; the first compile run will fail with unresolved-reference errors. That confirms the test is genuine. Then add the helper. Compile + run = green.

- [ ] **Step 1.1: Write the test file**

Create `app/src/test/java/com/aritr/rova/ui/screens/RecordingFrameLayoutTest.kt`:

```kotlin
package com.aritr.rova.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Milestone 1 — pure-JVM cover for [recordingFrameLayout]. The function
 * computes the centred recording rectangle inside a P+L preview zone
 * plus the surrounding non-recorded scrim regions for the given recording
 * aspect ratio. No Compose, no Android — straight math.
 *
 * Pattern matches [com.aritr.rova.ui.screens.RecordModeCycleTest] (pure
 * JVM, JUnit 4). Spec:
 * `docs/superpowers/specs/2026-05-26-dualshot-frame-polish-design.md` §8.1.
 */
class RecordingFrameLayoutTest {

    private val EPS = 0.5f

    @Test
    fun portrait_zone_produces_side_scrims() {
        // Portrait zone is taller than recording rect → side scrims.
        val layout = recordingFrameLayout(
            zoneWidth = 352f, zoneHeight = 625f, recordingAspect = 9f / 16f,
        )
        assertEquals(2, layout.scrimRegions.size)
        assertEquals(625f, layout.recordingRect.height, EPS)
    }

    @Test
    fun landscape_zone_produces_top_bottom_scrims() {
        // Landscape zone is wider than recording rect → top/bottom scrims.
        val layout = recordingFrameLayout(
            zoneWidth = 352f, zoneHeight = 225f, recordingAspect = 16f / 9f,
        )
        assertEquals(2, layout.scrimRegions.size)
        assertEquals(352f, layout.recordingRect.width, EPS)
    }

    @Test
    fun zone_matching_recording_aspect_produces_no_scrims() {
        val layout = recordingFrameLayout(
            zoneWidth = 16f, zoneHeight = 9f, recordingAspect = 16f / 9f,
        )
        assertTrue("expected no scrim regions, got ${layout.scrimRegions.size}",
            layout.scrimRegions.isEmpty())
        assertEquals(16f, layout.recordingRect.width, EPS)
        assertEquals(9f, layout.recordingRect.height, EPS)
    }

    @Test
    fun zero_size_zone_produces_empty_layout() {
        val layout = recordingFrameLayout(
            zoneWidth = 0f, zoneHeight = 0f, recordingAspect = 9f / 16f,
        )
        assertTrue(layout.scrimRegions.isEmpty())
        assertEquals(0f, layout.recordingRect.width, EPS)
        assertEquals(0f, layout.recordingRect.height, EPS)
    }

    @Test
    fun negative_size_zone_produces_empty_layout() {
        // Defensive: layout pass may briefly report a non-positive size before
        // the first real measure; the helper must not crash or compute garbage.
        val layout = recordingFrameLayout(
            zoneWidth = -10f, zoneHeight = 100f, recordingAspect = 9f / 16f,
        )
        assertTrue(layout.scrimRegions.isEmpty())
        assertEquals(0f, layout.recordingRect.width, EPS)
        assertEquals(0f, layout.recordingRect.height, EPS)
    }

    @Test
    fun portrait_scrim_regions_sum_to_non_recorded_area() {
        val layout = recordingFrameLayout(
            zoneWidth = 352f, zoneHeight = 625f, recordingAspect = 9f / 16f,
        )
        val zoneArea = 352f * 625f
        val recArea = layout.recordingRect.width * layout.recordingRect.height
        val scrimArea = layout.scrimRegions.sumOf {
            (it.width * it.height).toDouble()
        }.toFloat()
        assertEquals(zoneArea - recArea, scrimArea, 1.0f)
    }

    @Test
    fun recording_rect_centred_horizontally_for_portrait() {
        val layout = recordingFrameLayout(
            zoneWidth = 352f, zoneHeight = 625f, recordingAspect = 9f / 16f,
        )
        val centreX = layout.recordingRect.left + layout.recordingRect.width / 2f
        assertTrue("recording rect not centred horizontally: left=${layout.recordingRect.left}, width=${layout.recordingRect.width}, expected centreX≈176f",
            abs(centreX - 352f / 2f) < EPS)
    }

    @Test
    fun recording_rect_centred_vertically_for_landscape() {
        val layout = recordingFrameLayout(
            zoneWidth = 352f, zoneHeight = 225f, recordingAspect = 16f / 9f,
        )
        val centreY = layout.recordingRect.top + layout.recordingRect.height / 2f
        assertTrue("recording rect not centred vertically: top=${layout.recordingRect.top}, height=${layout.recordingRect.height}, expected centreY≈112.5f",
            abs(centreY - 225f / 2f) < EPS)
    }
}
```

- [ ] **Step 1.2: Run the test file to verify it fails (compile-fail)**

Run (delegate to subagent — main controller blocked from long gradle calls per constraint #3):
```
.\gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.screens.RecordingFrameLayoutTest"
```

Expected: COMPILATION ERROR — `Unresolved reference: recordingFrameLayout` (and `RecordingFrameLayout` data class). This confirms the test is genuine; helper does not exist yet.

If compile passes: STOP. Test is not exercising what we think. Check that the test file actually references `recordingFrameLayout(...)`.

- [ ] **Step 1.3: Write the minimal implementation**

Create `app/src/main/java/com/aritr/rova/ui/screens/RecordingFrameLayout.kt`:

```kotlin
package com.aritr.rova.ui.screens

/**
 * Milestone 1 — pure-JVM math seam for [RecordingFrameGuide]. Given a
 * P+L preview zone size and the side's recording aspect ratio (9:16 for
 * portrait, 16:9 for landscape), returns the centred recording rectangle
 * plus the surrounding non-recorded margin regions (scrim targets).
 *
 * Project precedent for pure-helper test seams:
 * [com.aritr.rova.service.dualrecord.internal.AspectFitMath],
 * [com.aritr.rova.ui.screens.cycleModeNext],
 * [com.aritr.rova.ui.warnings.effectiveIdleTopBannerId].
 *
 * Tested by [com.aritr.rova.ui.screens.RecordingFrameLayoutTest] (JVM).
 *
 * Spec:
 * `docs/superpowers/specs/2026-05-26-dualshot-frame-polish-design.md` §6.2.
 */
internal data class FrameRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)

internal data class RecordingFrameLayout(
    val recordingRect: FrameRect,
    val scrimRegions: List<FrameRect>,
)

private val EMPTY_RECT = FrameRect(0f, 0f, 0f, 0f)
private val EMPTY_LAYOUT = RecordingFrameLayout(EMPTY_RECT, emptyList())

internal fun recordingFrameLayout(
    zoneWidth: Float,
    zoneHeight: Float,
    recordingAspect: Float,
): RecordingFrameLayout {
    if (zoneWidth <= 0f || zoneHeight <= 0f || recordingAspect <= 0f) {
        return EMPTY_LAYOUT
    }
    val zoneAspect = zoneWidth / zoneHeight
    val (recW, recH) = if (recordingAspect < zoneAspect) {
        // Recording narrower than zone → fit by height, side scrims.
        zoneHeight * recordingAspect to zoneHeight
    } else {
        // Recording wider than (or equal to) zone → fit by width, top/bottom scrims.
        zoneWidth to zoneWidth / recordingAspect
    }
    val recLeft = (zoneWidth - recW) / 2f
    val recTop = (zoneHeight - recH) / 2f
    val recordingRect = FrameRect(recLeft, recTop, recW, recH)

    val scrims = buildList {
        if (recW < zoneWidth) {
            // Side scrims.
            add(FrameRect(0f, 0f, recLeft, zoneHeight))
            add(FrameRect(recLeft + recW, 0f, zoneWidth - recLeft - recW, zoneHeight))
        }
        if (recH < zoneHeight) {
            // Top/bottom scrims.
            add(FrameRect(0f, 0f, zoneWidth, recTop))
            add(FrameRect(0f, recTop + recH, zoneWidth, zoneHeight - recTop - recH))
        }
    }
    return RecordingFrameLayout(recordingRect, scrims)
}
```

- [ ] **Step 1.4: Run the test file to verify it passes**

Run (subagent):
```
.\gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.screens.RecordingFrameLayoutTest"
```

Expected: BUILD SUCCESSFUL, 8 tests run, 0 failed, 0 ignored.

If any test fails: STOP. Read the failure message; check math against the corresponding test case in Step 1.1. Do not adjust the test to match the implementation — adjust the implementation. The tests encode the spec contract.

- [ ] **Step 1.5: Run the full test suite to confirm zero regression**

Run (subagent):
```
.\gradlew.bat :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL, ≥1192 tests run (baseline `7f64650` was 1176; Phase 4.2 PR #49 added 16 → 1192), 0 failed, 0 ignored. Exact count may be 1200 ±2 depending on how Phase 4.2 tests were aggregated — the floor is "zero failures."

If any test outside `RecordingFrameLayoutTest` fails: STOP. Helper extraction must not change other behaviour.

- [ ] **Step 1.6: Commit**

```
git add app/src/main/java/com/aritr/rova/ui/screens/RecordingFrameLayout.kt app/src/test/java/com/aritr/rova/ui/screens/RecordingFrameLayoutTest.kt
git commit -m "feat(ui): RecordingFrameLayout pure helper for DualShot polish (Milestone 1 Task 1)

Pure-JVM math seam for the upcoming RecordingFrameGuide refactor.
Given a P+L preview zone size + recording aspect, returns the centred
recording rectangle + non-recorded scrim regions. Defensive on
zero/negative input. 8 JVM unit tests cover portrait/landscape/equal/
zero/negative cases, area conservation, and centering.

Project precedent: AspectFitMath, cycleModeNext, effectiveIdleTopBannerId.

Spec: docs/superpowers/specs/2026-05-26-dualshot-frame-polish-design.md §6.2.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

Verify: `git log --oneline -1` shows the new commit (one SHA after `0690fe2`).

---

## Task 2: Token deltas + `RecordingFrameGuide` refactor (atomic compile-gated)

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/theme/RecordChromeTokens.kt:225-239`
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/DualPreviewZone.kt:62-66, 154-202`

**Atomicity rationale:** Deleting `recordingFrameOutline` and `recordingFrameStrokeWidth` from `RecordChromeTokens.kt` breaks `DualPreviewZone.kt:159, 161, 195-200` (which reads them). The two files must move together or compile breaks. One commit, two files.

**TDD note:** No new tests in this task. The refactor preserves spec contract via:
1. Compile-gate (deleted tokens have no consumers post-refactor).
2. Full test suite stays green (no behavioural test exercises Canvas vs Box rendering at the JVM unit-test layer per project pattern).
3. Manual smoke (Task 3) validates visual contract on hardware.

- [ ] **Step 2.1: Update `RecordChromeTokens.kt`**

Open `app/src/main/java/com/aritr/rova/ui/theme/RecordChromeTokens.kt`. Replace the block from line 225 through line 239 with:

```kotlin
    // ── Recording-frame guide (P+L mode — always on, ADR-0010 + Milestone 1) ─────────
    /**
     * Recording-frame scrim — faint black over the non-recorded preview
     * margin. Signals "this part isn't captured". Halved from the prior
     * 0.22f baseline as part of Milestone 1 polish (subtle scrim + API 31+
     * blur replaces the prior 1.dp outline + 0.22f scrim combination).
     * Spec: `docs/superpowers/specs/2026-05-26-dualshot-frame-polish-design.md` §7.
     */
    val recordingFrameScrim = Color.Black.copy(alpha = 0.11f)
    /**
     * Recording-frame blur radius — applied via `RenderEffect.createBlurEffect`
     * on API 31+ only. The blur runs over scrim regions only (live camera
     * content beneath the scrim). Subtle frosted-glass effect; see spec §5 #1.
     */
    val recordingFrameBlurRadius = 12.dp
    /**
     * `.cam-split-divider` alpha — soft separator between portrait and
     * landscape zones in P+L mode. Reduced from the prior 0.14f (literal in
     * `DualPreviewZone`) to honour the softer-chrome direction. Spec §5 #4.
     */
    val camSplitDividerAlpha = 0.06f
}
```

**Removed lines:** the `recordingFrameOutline = Color(0xFFB0B4BC).copy(alpha = 0.38f)` and `recordingFrameStrokeWidth = 1.dp` declarations + their KDoc blocks (lines 226-234). The KDoc preamble for "Recording-frame guide" header comment is preserved (rewritten above) but no longer references the deleted outline tokens.

- [ ] **Step 2.2: Update `DualPreviewZone.kt` — divider Box**

In `app/src/main/java/com/aritr/rova/ui/screens/DualPreviewZone.kt`, locate the divider Box (lines 62-67). Replace:

```kotlin
        // Divider per mockup .cam-split-divider — 2 dp, white 14% alpha.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(Color.White.copy(alpha = 0.14f))
        )
```

with:

```kotlin
        // Divider per mockup .cam-split-divider — softened to alpha 0.06f
        // in Milestone 1 (spec §5 #4). Token: [RecordChromeTokens.camSplitDividerAlpha].
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(Color.White.copy(alpha = RecordChromeTokens.camSplitDividerAlpha))
        )
```

- [ ] **Step 2.3: Update `DualPreviewZone.kt` — replace `RecordingFrameGuide`**

In `app/src/main/java/com/aritr/rova/ui/screens/DualPreviewZone.kt`, find the `RecordingFrameGuide` composable (lines 154-202). Replace **the entire `RecordingFrameGuide` function body** (from `@Composable` line through closing `}` at line 202) with:

```kotlin
/**
 * Recording-frame guide — overlay drawn unconditionally in each P+L zone.
 * Marks the recorded sub-rectangle by darkening the non-recorded margins
 * with a subtle scrim (alpha 0.11f) and, on API 31+, applying a 12.dp
 * Gaussian blur to the scrim regions so the camera content beneath reads
 * as soft frosted glass. Independent of the decorative "Camera guides"
 * app-setting: this is functional (capture-bounds indicator), not
 * decorative — always-on per spec §5 #5.
 *
 * Stateless and non-interactive. Pure layout math via
 * [recordingFrameLayout]; recomposes only when the host zone resizes.
 *
 * Milestone 1 (spec `docs/superpowers/specs/2026-05-26-dualshot-frame-polish-design.md`):
 * the prior 1.dp gray outline (`recordingFrameOutline` / `recordingFrameStrokeWidth`)
 * is removed; the prior 0.22f scrim is halved to 0.11f; API 31+ devices
 * additionally render a frosted-glass blur over the scrim regions.
 *
 * See also ADR-0010 and the original
 * `docs/superpowers/specs/2026-05-22-dualshot-preview-crop-fill-design.md` §5.3.
 */
@Composable
private fun RecordingFrameGuide(side: VideoSide, modifier: Modifier = Modifier) {
    val recordingAspect = when (side) {
        VideoSide.PORTRAIT -> 9f / 16f
        VideoSide.LANDSCAPE -> 16f / 9f
    }

    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val zoneWidthPx = with(density) { maxWidth.toPx() }
        val zoneHeightPx = with(density) { maxHeight.toPx() }
        val layout = recordingFrameLayout(zoneWidthPx, zoneHeightPx, recordingAspect)
        layout.scrimRegions.forEach { region ->
            ScrimRegion(region = region)
        }
    }
}

/**
 * Single scrim region — a Box positioned with absolute offset over the
 * non-recorded margin area. On API 31+ applies a `RenderEffect.createBlurEffect`
 * via `Modifier.graphicsLayer` so the live camera content beneath blurs.
 * On API <31 (Build.VERSION_CODES.S = 31, project minSdk = 24) the modifier
 * is a no-op pass-through; the flat 0.11f scrim alone provides the cue.
 *
 * `Shader.TileMode.CLAMP` prevents edge-darkening at the recording-rect
 * boundary (per spec §6.3).
 */
@Composable
private fun ScrimRegion(region: FrameRect) {
    val density = LocalDensity.current
    val offsetX = with(density) { region.left.toDp() }
    val offsetY = with(density) { region.top.toDp() }
    val widthDp = with(density) { region.width.toDp() }
    val heightDp = with(density) { region.height.toDp() }
    val blurRadiusPx = with(density) { RecordChromeTokens.recordingFrameBlurRadius.toPx() }

    val blurModifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Modifier.graphicsLayer {
            renderEffect = RenderEffect
                .createBlurEffect(blurRadiusPx, blurRadiusPx, Shader.TileMode.CLAMP)
                .asComposeRenderEffect()
        }
    } else {
        Modifier
    }

    Box(
        modifier = Modifier
            .offset(x = offsetX, y = offsetY)
            .size(width = widthDp, height = heightDp)
            .then(blurModifier)
            .background(RecordChromeTokens.recordingFrameScrim),
    )
}
```

**Imports to add** at the top of `DualPreviewZone.kt` (after the existing imports):

```kotlin
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
```

**Imports to remove** (no longer used after Canvas → Box composition):

```kotlin
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
```

If any of those imports are still used by other code in the same file (e.g. `CameraGuides` elsewhere in the module imports `Canvas` separately — verify via grep before removing), retain the import. Best safety check: after editing, run `gradlew compileDebugKotlin` — the Kotlin compiler flags unused imports as warnings, and missing imports as errors.

- [ ] **Step 2.4: Compile to verify atomic refactor is consistent**

Run (subagent):
```
.\gradlew.bat :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL. No compile errors. The 0 compile errors confirm:
- Deleted tokens (`recordingFrameOutline`, `recordingFrameStrokeWidth`) have no remaining consumers.
- New tokens (`recordingFrameBlurRadius`, `camSplitDividerAlpha`) resolve from `RecordChromeTokens`.
- New composable structure compiles against Compose APIs at the project's pinned versions.

If compile fails with `Unresolved reference: recordingFrameOutline` or `recordingFrameStrokeWidth`: a usage was missed. Grep the codebase: `Select-String -Path app/src -Pattern "recordingFrameOutline|recordingFrameStrokeWidth" -Recurse`. Update the missed site or restore the token if it has a legitimate non-Milestone-1 consumer.

If compile fails with `Unresolved reference: RenderEffect` or `Shader`: imports missing. Add the `android.graphics.RenderEffect` and `android.graphics.Shader` imports from Step 2.3.

- [ ] **Step 2.5: Run the full test suite**

Run (subagent):
```
.\gradlew.bat :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL, ≥1192 tests run (1184 baseline + 8 new from Task 1), 0 failed, 0 ignored.

If `WarningPrecedenceTest`, `WarningCenterAggregateTest`, `WarningIdOrderTest`, `RecoveryScannerTest`, or any `AspectFitMath*Test` fails: STOP. Hard invariants violated. Refactor must be reverted; the spec invariant contract (§4) is broken.

- [ ] **Step 2.6: Run lint**

Run (subagent):
```
.\gradlew.bat :app:lintDebug
```

Expected: BUILD SUCCESSFUL with **51 W + 0 H + 0 E** (zero-delta vs `7f64650` baseline). Lint runs the project's static-check task list registered in `app/build.gradle.kts`.

If new warnings appear:
- `ObsoleteSdkInt` — if lint flags the `Build.VERSION.SDK_INT >= Build.VERSION_CODES.S` check as obsolete because minSdk is changing, suppress with `@Suppress("ObsoleteSdkInt")` on the function. Project minSdk is 24, so this lint should not fire.
- `NewApi` — if lint flags `RenderEffect.createBlurEffect` as API 31+ without the guard, the SDK_INT check is not being detected. Re-check the conditional shape: `if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)` is the lint-recognized pattern.
- Other new warnings: surface to owner; do not silently accept new lint warnings, the baseline is zero-delta per spec §11.

If hard errors appear: STOP. Fix before proceeding.

- [ ] **Step 2.7: Build debug APK**

Run (subagent):
```
.\gradlew.bat :app:assembleDebug
```

Expected: BUILD SUCCESSFUL. APK at `app/build/outputs/apk/debug/app-debug.apk`.

If build fails: STOP. Address before committing.

- [ ] **Step 2.8: Commit**

```
git add app/src/main/java/com/aritr/rova/ui/theme/RecordChromeTokens.kt app/src/main/java/com/aritr/rova/ui/screens/DualPreviewZone.kt
git commit -m "feat(ui): RecordingFrameGuide blur + halved scrim (Milestone 1 Task 2)

Drop the 1.dp outline and halve the dark scrim (0.22f → 0.11f) in P+L
mode's RecordingFrameGuide. Add API 31+ frosted-glass blur over scrim
regions via RenderEffect.createBlurEffect (12.dp radius, CLAMP tile mode).
Soften cam-split-divider (0.14f → 0.06f) for consistency.

RecordingFrameGuide refactored from Canvas-only to Box composition so
the blur RenderEffect can wrap scrim regions only — the recording
rectangle remains unblurred. Layout math delegated to the pure
recordingFrameLayout helper (Task 1).

Hard invariants preserved: no EglRouter / muxer / recording-pipeline
touch; ADR-0009/0010/0011/0012 unchanged; full test suite green;
lint 51W+0H+0E zero-delta.

Spec: docs/superpowers/specs/2026-05-26-dualshot-frame-polish-design.md §6, §7.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

Verify: `git log --oneline -2` shows Task 1 + Task 2 commits on top of `0690fe2`.

---

## Task 3: Manual smoke + acceptance sign-off

**Owner-driven.** This task is not a code change; it is the acceptance gate before declaring Milestone 1 done and opening a PR.

**Required device:** Samsung SM-A176B (primary QA device per `project_dualshot_stability_stack` memory) OR any API 31+ device for blur validation + any API 24-30 device for fallback validation (one device is acceptable if API 31+; the fallback path is exercised by code inspection alone).

**Pre-flight check:**

- [ ] **Step 3.1: Confirm both commits land on local master**

Run: `git log --oneline -3`
Expected (top to bottom):
- `<sha2>` `feat(ui): RecordingFrameGuide blur + halved scrim (Milestone 1 Task 2)`
- `<sha1>` `feat(ui): RecordingFrameLayout pure helper for DualShot polish (Milestone 1 Task 1)`
- `0690fe2 docs(spec): Milestone 1 DualShot frame polish design`

- [ ] **Step 3.2: Install debug APK on QA device**

Run:
```
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Expected: `Success`.

- [ ] **Step 3.3: Launch app, navigate to Record screen, switch to P+L mode**

Tap the Mode cycle chip in the settings card until it reads "P+L" (Portrait + Landscape). Two preview zones now show — portrait on top, landscape below.

- [ ] **Step 3.4: Visual checks (compare against `7f64650` master baseline if needed via screenshots)**

Verify each of the following. Photo-capture each step for owner sign-off.

| # | Check | Pass criterion |
|---|---|---|
| a | No 1.dp gray outline around either recording rectangle | Outline absent; recording rect is implicit from the scrim margins |
| b | Scrim margins softer than current master | Margins still visibly darker than recording rect but less so than the prior 0.22f baseline |
| c | Divider between portrait and landscape zones barely visible | Divider present (structural cue) but reads as a very subtle separator, not a hard line |
| d | API 31+ device — blur over scrim regions | Camera content in margins appears as soft, frosted color blobs; the recording rect remains crisp. Detail in the scrim regions abstracted but general scene structure still readable |
| e | API <31 device (if available) — flat 0.11f scrim, no blur | Margins show flat darkening with no blur artifact; recording rect crisp |
| f | "Camera guides" app-setting toggle behaves correctly | Toggling the setting affects the decorative grid + focus brackets BUT does NOT affect the recording-frame scrim (always-on contract preserved) |

- [ ] **Step 3.5: Frame-rate check**

Enable Developer Options → "Profile GPU rendering" → "On screen as bars" (or equivalent on the QA device).

Hold the device steady in P+L mode for ~30 seconds with normal preview motion. Observe the GPU rendering bars.

Pass criterion: bars stay below the green 16.7ms line (= 60fps target) most of the time; brief spikes above acceptable. Critical floor: rendering remains ≥28fps (1000ms / 35ms per frame) sustained.

If frame rate drops below 28fps sustained in P+L mode:
1. Try fallback lever 1 (reduce blur radius to 8.dp): edit `RecordChromeTokens.recordingFrameBlurRadius = 8.dp`, rebuild, retest. If acceptable, commit the tuning. If still failing, lever 2.
2. Fallback lever 2 (downsample blur source): apply `Modifier.graphicsLayer { scaleX = 0.5f; scaleY = 0.5f }` to the blur layer + compensate by doubling Box size and clipping. This is a non-trivial code change — STOP and surface to owner before applying.
3. Fallback lever 3 (disable blur): remove the `if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)` branch; let all API levels use the flat 0.11f scrim. STOP and surface to owner before applying.

- [ ] **Step 3.6: Recording-pipeline byte-identity smoke**

Record a 5-second test clip in P+L mode. Pull the output MP4 from the device:
```
adb pull /sdcard/Movies/Rova/<latest_session>/portrait.mp4 ./smoke_portrait.mp4
adb pull /sdcard/Movies/Rova/<latest_session>/landscape.mp4 ./smoke_landscape.mp4
```

Run `ffprobe` (or inspect via any video tool) on each. Pass criterion: identical codec, resolution, bitrate, and duration profile to a similar pre-change recording. The byte-level content cannot be compared (camera content differs frame-to-frame), but the file shape and encoder parameters must match.

If file shape differs (e.g. resolution changed, codec changed, duration off): STOP. Spec §4 hard invariant violated — recording pipeline was unintentionally touched. Investigate via `git diff 0690fe2..HEAD -- app/src/main/java/com/aritr/rova/service/`. Expected: empty diff. If non-empty: revert immediately.

- [ ] **Step 3.7: Acceptance checklist (spec §11)**

Confirm each item:

- [ ] 1.dp outline removed from `RecordingFrameGuide` (Step 3.4 #a).
- [ ] Scrim alpha halved from `0.22f → 0.11f` (Step 2.1 token, Step 3.4 #b).
- [ ] Divider alpha reduced from `0.14f → 0.06f` (Step 2.2 token, Step 3.4 #c).
- [ ] API 31+ shows blur (Step 3.4 #d); API <31 shows flat scrim (Step 3.4 #e or code inspection).
- [ ] `recordingFrameLayout(...)` has 8 unit tests passing (Step 1.4).
- [ ] Full test suite green (Step 2.5).
- [ ] Lint zero-delta (Step 2.6, 51 W + 0 H + 0 E).
- [ ] `assembleDebug` succeeds (Step 2.7).
- [ ] Manual smoke on Samsung SM-A176B confirms all visual + frame-rate criteria (Steps 3.4-3.5).
- [ ] Recording pipeline byte-identity confirmed (Step 3.6).

- [ ] **Step 3.8: Owner sign-off**

Surface to owner: "Milestone 1 acceptance checklist complete. Photo-captures attached. Ready for PR?" Owner explicit GO required per standing constraint #11 before push + PR creation.

---

## Spec coverage check

Self-review against `docs/superpowers/specs/2026-05-26-dualshot-frame-polish-design.md`:

| Spec section | Covered by |
|---|---|
| §1 Context | N/A (background only) |
| §2 Goal | Task 2 + Task 3 |
| §3 Out of scope | Honoured throughout — no Mode picker, no CameraGuides change, no zone-tag change, no multi-device perf measurement, no Variant D, no toggle, no single-mode surfaces |
| §4 Hard invariants | Task 2 Step 2.5 (test suite) + Task 3 Step 3.6 (pipeline byte-identity) |
| §5 Design decisions | All 5 implemented in Task 2 Steps 2.1, 2.3 |
| §6 Architecture | Task 1 (pure helper) + Task 2 (Box-composition refactor + API gate) |
| §7 Visual tokens | Task 2 Step 2.1 |
| §8.1 Pure-helper unit tests | Task 1 Step 1.1 (all 8 cases) |
| §8.2 No Compose render tests | Honoured — no Compose UI tests added |
| §8.3 Manual smoke | Task 3 Steps 3.4-3.5 |
| §8.4 Regression suite | Task 2 Steps 2.5-2.7 |
| §9 Performance plan | Task 3 Step 3.5 (frame-rate check + fallback levers) |
| §10 Risks | Risk 1 → Step 3.5; Risk 2 → Step 3.4 #b; Risk 3 → Step 2.3 CLAMP; Risks 4-5 → Step 2.5; Risk 6 → Step 3.4 #d + #f |
| §11 Acceptance | Task 3 Step 3.7 |
| §12 No ADR | Honoured — no new ADR file created |
| §13 Next step | This plan IS the next step |

All spec sections covered.

---

## Plan self-review

Per writing-plans skill self-review checklist:

**1. Spec coverage:** Above table. No gaps.

**2. Placeholder scan:**
- No "TBD", "TODO", "implement later", "fill in details" — clean.
- No "Add appropriate error handling" — defensive cases enumerated (zero-size, negative-size in Task 1 Step 1.1 cases 4-5).
- No "Write tests for the above" without code — all 8 tests written out in full (Step 1.1).
- No "Similar to Task N" — each task is self-contained.
- All code blocks complete (no `...` ellipsis).
- All commands have exact expected output.

**3. Type consistency:**
- `FrameRect(left, top, width, height)` — same shape across Task 1 implementation (Step 1.3), Task 1 tests (Step 1.1 uses `recordingRect.width`, `.height`, `.left`, `.top`), and Task 2 Compose consumer (Step 2.3 uses `region.left`, `region.top`, `region.width`, `region.height`).
- `RecordingFrameLayout(recordingRect, scrimRegions)` — same shape across Task 1 declaration, Task 1 tests, and Task 2 Compose consumer.
- `recordingFrameLayout(zoneWidth, zoneHeight, recordingAspect)` — same signature in Task 1 declaration + tests + Task 2 consumer.
- Token names: `recordingFrameScrim`, `recordingFrameBlurRadius`, `camSplitDividerAlpha` — consistent across Task 2 Steps 2.1, 2.2, 2.3.

No inconsistencies found.

---

## Execution handoff

Plan complete and saved to [docs/superpowers/plans/2026-05-26-dualshot-frame-polish.md](2026-05-26-dualshot-frame-polish.md).

**Owner-approved execution mode:** Subagent-Driven (per pre-flight directive from chat).

**Required sub-skill on execution:** `superpowers:subagent-driven-development` — fresh implementer subagent per task + two-stage review (spec-compliance + code-quality) between tasks.

**Workflow overlay:** `/karpathy-guidelines` during dev (owner directive).

**Standing constraints in effect throughout:**
- Constraint #3: gradle invocations route through subagents only (main controller blocked from long `.\gradlew.bat` calls).
- Constraint #9: review-gate cycle, strict NO-GO / GO per task; do not start next task without explicit GO.
- Constraint #11: push + PR creation require explicit owner consent per slice. Commits OK under "execute the plan" consent (interpreted as: per-task commits in Tasks 1-2 fire under this plan's authorisation; push + PR sit at Task 3 Step 3.8 owner gate).

**Estimated total time:** 4-6 hours across Tasks 1-2 (subagent dispatches + reviews), plus ~30 minutes hardware smoke in Task 3. Sub-day milestone.
