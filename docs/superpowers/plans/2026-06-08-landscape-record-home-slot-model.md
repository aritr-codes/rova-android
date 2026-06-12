# PR-β — Landscape Record-home Slot Model Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Re-place Record-home chrome in landscape (record action right, quiet nav left, params centered & capped) instead of stretching the portrait stack sideways, driven by a pure JVM-testable placement helper.

**Architecture:** A pure `ChromeSlotPlacement.placementFor(slot, orientation)` returns an explicit-edge `SlotPlacement`. `RecordScreen` reads one `ChromeOrientation` from `LocalConfiguration` and wraps each region in `slotModifier(placement)`. Portrait placements equal today's literals exactly (zero portrait regression, pinned by tests). The bottom nav is the only region that structurally decomposes: portrait keeps the intact `RecordBottomNav` bar; landscape renders `RecordNavRail` (left) + `RecordFab` (right-center) from the same leaves. Two composables (`RecordCameraControls`, `RecordActiveHud`) flip their internal stacking axis (Column→Row) in landscape; their slot and semantics are unchanged.

**Tech Stack:** Kotlin, Jetpack Compose, JVM unit tests (`isReturnDefaultValues=true`).

**Spec:** `docs/superpowers/specs/2026-06-08-landscape-record-home-slot-model-design.md`. One plan-stage refinement vs the spec: `SlotPlacement` uses **explicit edge insets** (`startDp/topDp/endDp/bottomDp/maxWidthDp`) rather than the spec's main/cross-inset pair — explicit edges reproduce the params card's horizontal margins + 120dp dock clearance exactly, which the main/cross model could not express. Placement dp values live as named `const val`s in the helper file (the pure-Int placement contract); no Dp tokens added to `RecordChromeTokens`.

**Branch:** `feat/liquid-glass-landscape-chrome` (already created off master `e9a2f39`, spec committed `d4613bf`).

**Build discipline (load-bearing):** The controller (main session) runs ALL gradle. The post-edit kotlin hook is broken (calls non-existent `:app:detekt`) and corrupts the incremental cache. Before each controller build run the recovery sequence:
```powershell
.\gradlew.bat --stop
Get-Process java -ErrorAction SilentlyContinue | Stop-Process -Force
Remove-Item -Recurse -Force app\build\kotlin -ErrorAction SilentlyContinue
Remove-Item -Recurse -Force "$env:USERPROFILE\.gradle\caches\*\kotlin-dsl" -ErrorAction SilentlyContinue 2>$null
```
Verify with `:app:assembleDebug` (NOT `lintDebug` — pre-existing B5 `VaultAndroidOps` NewApi lint is RED and unrelated) and `:app:testDebugUnitTest`.

**Scope guards:** No new `check*` gate. No new user-facing strings. No `SCHEMA_VERSION` bump. No manifest change. No camera/service/rotation/recovery path touched. DualShot/P+L preview untouched.

---

## Reference — current portrait placements (the regression baseline)

Verified in source; the helper's PORTRAIT column must equal these exactly.

| Slot | Region composable | Today's modifier (RecordScreen.kt) | Inset |
|------|-------------------|-------------------------------------|-------|
| STATUS_OVERLAY | `RecordTopOverlay` (816-819) | `align(TopStart).windowInsetsPadding(statusBars).padding(start=16,top=16)` | statusBars |
| RECOVERY_CHIP | `RecordRecoveryChip` (653-656) | `align(TopStart).windowInsetsPadding(statusBars).padding(start=16,top=70)` | statusBars |
| WARNING_CENTER | idle `WarningCenter` Box (666-670) | `align(TopStart).windowInsetsPadding(statusBars).padding(start=16,top=110)` | statusBars |
| CAMERA_CONTROLS | `RecordCameraControls` (850-855) | `align(TopEnd).windowInsetsPadding(statusBars).padding(end=7,top=7)` | statusBars |
| ACTIVE_HUD | active-state Column (736-741) | `align(TopCenter).windowInsetsPadding(statusBars).padding(top=16).fillMaxWidth()` | statusBars |
| PARAMS_CARD | settings-card Column (772-785) | `align(BottomCenter).windowInsetsPadding(navigationBars).padding(bottom=90+30,start=16,end=16).fillMaxWidth()` | navigationBars |
| NAV | `RecordBottomNav` (858-865) | `align(BottomCenter)` (bar owns its own internal nav-bar inset) | — |

`RecordChromeMetrics.bottomNavClearance = 90.dp`, `settingsCardLift = 30.dp` (RecordChrome.kt:465,475) → PARAMS_CARD portrait bottom = `120`.

---

## Task 1: Pure placement helper + enums + tests

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/screens/chrome/ChromeSlotPlacement.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/screens/chrome/ChromeSlotPlacementTest.kt`

- [ ] **Step 1: Write the failing test**

`ChromeSlotPlacementTest.kt`:

```kotlin
package com.aritr.rova.ui.screens.chrome

import com.aritr.rova.ui.screens.RecordChromeMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** PR-β (ADR-0029 §B) — pins every slot×orientation placement. */
class ChromeSlotPlacementTest {

    private fun p(slot: ChromeSlot, o: ChromeOrientation) = placementFor(slot, o)

    @Test fun status_portrait_matches_today() {
        assertEquals(SlotPlacement(SlotAnchor.TOP_START, startDp = 16, topDp = 16), p(ChromeSlot.STATUS_OVERLAY, ChromeOrientation.PORTRAIT))
    }
    @Test fun status_landscape_unchanged() {
        assertEquals(SlotPlacement(SlotAnchor.TOP_START, startDp = 16, topDp = 16), p(ChromeSlot.STATUS_OVERLAY, ChromeOrientation.LANDSCAPE))
    }

    @Test fun recovery_portrait_matches_today() {
        assertEquals(SlotPlacement(SlotAnchor.TOP_START, startDp = 16, topDp = 70), p(ChromeSlot.RECOVERY_CHIP, ChromeOrientation.PORTRAIT))
    }
    @Test fun recovery_landscape_pulled_up() {
        assertEquals(SlotPlacement(SlotAnchor.TOP_START, startDp = 16, topDp = 54), p(ChromeSlot.RECOVERY_CHIP, ChromeOrientation.LANDSCAPE))
    }

    @Test fun warning_portrait_matches_today() {
        assertEquals(SlotPlacement(SlotAnchor.TOP_START, startDp = 16, topDp = 110), p(ChromeSlot.WARNING_CENTER, ChromeOrientation.PORTRAIT))
    }
    @Test fun warning_landscape_pulled_up() {
        assertEquals(SlotPlacement(SlotAnchor.TOP_START, startDp = 16, topDp = 92), p(ChromeSlot.WARNING_CENTER, ChromeOrientation.LANDSCAPE))
    }

    @Test fun camControls_portrait_matches_today() {
        assertEquals(SlotPlacement(SlotAnchor.TOP_END, topDp = 7, endDp = 7), p(ChromeSlot.CAMERA_CONTROLS, ChromeOrientation.PORTRAIT))
    }
    @Test fun camControls_landscape() {
        assertEquals(SlotPlacement(SlotAnchor.TOP_END, topDp = 12, endDp = 12), p(ChromeSlot.CAMERA_CONTROLS, ChromeOrientation.LANDSCAPE))
    }

    @Test fun activeHud_portrait_matches_today() {
        assertEquals(SlotPlacement(SlotAnchor.TOP_CENTER, topDp = 16), p(ChromeSlot.ACTIVE_HUD, ChromeOrientation.PORTRAIT))
    }
    @Test fun activeHud_landscape_capped() {
        assertEquals(SlotPlacement(SlotAnchor.TOP_CENTER, topDp = 12, maxWidthDp = 360), p(ChromeSlot.ACTIVE_HUD, ChromeOrientation.LANDSCAPE))
    }

    @Test fun paramsCard_portrait_matches_today() {
        assertEquals(SlotPlacement(SlotAnchor.BOTTOM_CENTER, startDp = 16, endDp = 16, bottomDp = 120), p(ChromeSlot.PARAMS_CARD, ChromeOrientation.PORTRAIT))
    }
    @Test fun paramsCard_portrait_bottom_tracks_dock_metrics() {
        // Drift guard: the 120 literal must equal the live dock metric sum.
        val expected = (RecordChromeMetrics.bottomNavClearance + RecordChromeMetrics.settingsCardLift).value.toInt()
        assertEquals(expected, p(ChromeSlot.PARAMS_CARD, ChromeOrientation.PORTRAIT).bottomDp)
    }
    @Test fun paramsCard_landscape_capped_and_centered() {
        assertEquals(SlotPlacement(SlotAnchor.BOTTOM_CENTER, bottomDp = 12, maxWidthDp = 360), p(ChromeSlot.PARAMS_CARD, ChromeOrientation.LANDSCAPE))
    }

    @Test fun navRail_landscape_left() {
        assertEquals(SlotPlacement(SlotAnchor.CENTER_START, startDp = 12), p(ChromeSlot.NAV_RAIL, ChromeOrientation.LANDSCAPE))
    }
    @Test fun recordAction_landscape_right() {
        assertEquals(SlotPlacement(SlotAnchor.CENTER_END, endDp = 14), p(ChromeSlot.RECORD_ACTION, ChromeOrientation.LANDSCAPE))
    }

    @Test fun simpleSlots_have_no_maxWidth_in_portrait() {
        listOf(ChromeSlot.STATUS_OVERLAY, ChromeSlot.RECOVERY_CHIP, ChromeSlot.WARNING_CENTER, ChromeSlot.CAMERA_CONTROLS)
            .forEach { assertNull(placementFor(it, ChromeOrientation.PORTRAIT).maxWidthDp) }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.screens.chrome.ChromeSlotPlacementTest"`
Expected: FAIL — unresolved reference `ChromeSlot` / `placementFor` (file not created yet).

- [ ] **Step 3: Write minimal implementation**

`ChromeSlotPlacement.kt`:

```kotlin
package com.aritr.rova.ui.screens.chrome

/**
 * PR-β (ADR-0029 §B) — pure, JVM-testable placement decisions for Record-home
 * chrome. The ONLY decision point for where a slot lives per orientation. No
 * Compose / Android imports — runs under isReturnDefaultValues=true. The call
 * site (RecordScreen via slotModifier) maps [SlotAnchor] -> Compose Alignment
 * and the dp ints -> .dp. Portrait values equal today's literals exactly
 * (regression-pinned by ChromeSlotPlacementTest).
 */

/** Device configuration orientation, as Record-home cares about it. */
enum class ChromeOrientation { PORTRAIT, LANDSCAPE }

/** Re-placeable Record-home regions. */
enum class ChromeSlot {
    STATUS_OVERLAY,   // idle status pill
    CAMERA_CONTROLS,  // flash + flip
    RECOVERY_CHIP,    // idle recovery nudge
    WARNING_CENTER,   // idle warning surface
    ACTIVE_HUD,       // recording HUD wrapper (warning + loop/status/bar)
    PARAMS_CARD,      // settings/params card (holds the mode chip; PR-γ's picker slot)
    NAV_RAIL,         // Library + Settings destinations (landscape only)
    RECORD_ACTION,    // Start/Stop FAB (landscape standalone placement)
}

/** Semantic anchor; mapped to a Compose Alignment at the call site. */
enum class SlotAnchor {
    TOP_START, TOP_END, TOP_CENTER,
    BOTTOM_CENTER, CENTER_START, CENTER_END,
}

/**
 * Explicit edge insets (dp ints) + optional width cap. maxWidthDp non-null =>
 * constrain width (the anchor's horizontal centering then centers it).
 */
data class SlotPlacement(
    val anchor: SlotAnchor,
    val startDp: Int = 0,
    val topDp: Int = 0,
    val endDp: Int = 0,
    val bottomDp: Int = 0,
    val maxWidthDp: Int? = null,
)

// ── Placement constants (the pure-Int placement contract) ────────────────────
private const val EDGE = 16              // screen edge margin (RecordChromeTokens.screenEdgeMargin)
private const val RECOVERY_TOP_P = 70    // portrait: just below the status pill
private const val WARNING_TOP_P = 110    // portrait: below the recovery chip
private const val RECOVERY_TOP_L = 54    // landscape: shorter viewport, pulled up
private const val WARNING_TOP_L = 92
private const val CAM_INSET_P = 7        // portrait cam-control edge inset (today's literal)
private const val CAM_INSET_L = 12
private const val HUD_TOP_P = 16
private const val HUD_TOP_L = 12
private const val CARD_BOTTOM_P = 120    // = RecordChromeMetrics.bottomNavClearance(90)+settingsCardLift(30); pinned by test
private const val CARD_BOTTOM_L = 12
private const val LAND_MAX_WIDTH = 360   // landscape cap for HUD + params card
private const val NAV_RAIL_INSET = 12
private const val RECORD_ACTION_INSET = 14

/** Pure. The single placement decision per (slot, orientation). */
fun placementFor(slot: ChromeSlot, orientation: ChromeOrientation): SlotPlacement {
    val landscape = orientation == ChromeOrientation.LANDSCAPE
    return when (slot) {
        ChromeSlot.STATUS_OVERLAY ->
            SlotPlacement(SlotAnchor.TOP_START, startDp = EDGE, topDp = EDGE)
        ChromeSlot.RECOVERY_CHIP ->
            SlotPlacement(SlotAnchor.TOP_START, startDp = EDGE, topDp = if (landscape) RECOVERY_TOP_L else RECOVERY_TOP_P)
        ChromeSlot.WARNING_CENTER ->
            SlotPlacement(SlotAnchor.TOP_START, startDp = EDGE, topDp = if (landscape) WARNING_TOP_L else WARNING_TOP_P)
        ChromeSlot.CAMERA_CONTROLS ->
            SlotPlacement(SlotAnchor.TOP_END, topDp = if (landscape) CAM_INSET_L else CAM_INSET_P, endDp = if (landscape) CAM_INSET_L else CAM_INSET_P)
        ChromeSlot.ACTIVE_HUD ->
            if (landscape) SlotPlacement(SlotAnchor.TOP_CENTER, topDp = HUD_TOP_L, maxWidthDp = LAND_MAX_WIDTH)
            else SlotPlacement(SlotAnchor.TOP_CENTER, topDp = HUD_TOP_P)
        ChromeSlot.PARAMS_CARD ->
            if (landscape) SlotPlacement(SlotAnchor.BOTTOM_CENTER, bottomDp = CARD_BOTTOM_L, maxWidthDp = LAND_MAX_WIDTH)
            else SlotPlacement(SlotAnchor.BOTTOM_CENTER, startDp = EDGE, endDp = EDGE, bottomDp = CARD_BOTTOM_P)
        ChromeSlot.NAV_RAIL ->
            // Advisory in portrait (portrait renders the intact bottom bar, not this slot).
            SlotPlacement(SlotAnchor.CENTER_START, startDp = NAV_RAIL_INSET)
        ChromeSlot.RECORD_ACTION ->
            SlotPlacement(SlotAnchor.CENTER_END, endDp = RECORD_ACTION_INSET)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.screens.chrome.ChromeSlotPlacementTest"`
Expected: PASS (16 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/chrome/ChromeSlotPlacement.kt app/src/test/java/com/aritr/rova/ui/screens/chrome/ChromeSlotPlacementTest.kt
git commit -m "feat(record): pure ChromeSlotPlacement helper for landscape slot model (ADR-0029 §B)"
```

---

## Task 2: `slotModifier` call-site mapper + `ChromeOrientation` source

This task wires NO behavior change yet — it adds the Compose mapper and the orientation read so later tasks consume them. Verified by compile only.

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt` (add `slotModifier` BoxScope extension near the bottom of the file)
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt` (read `chromeOrientation` at the top of the composable)

- [ ] **Step 1: Add the `slotModifier` BoxScope extension to RecordChrome.kt**

Add these imports to `RecordChrome.kt` (top import block):
```kotlin
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets as LayoutWindowInsets
import com.aritr.rova.ui.screens.chrome.ChromeSlot
import com.aritr.rova.ui.screens.chrome.ChromeOrientation
import com.aritr.rova.ui.screens.chrome.SlotAnchor
import com.aritr.rova.ui.screens.chrome.SlotPlacement
import com.aritr.rova.ui.screens.chrome.placementFor
```
(`Box`, `padding`, `windowInsetsPadding`, `Alignment` are already imported.)

Add at the end of the file (after the last composable):
```kotlin
/**
 * PR-β — maps a pure [SlotPlacement] to a Compose Modifier inside a Box: align +
 * (optional) window inset + edge padding + (optional) width cap, in that order
 * (matches today's `align().windowInsetsPadding().padding()` order, so portrait
 * is byte-identical). [insets] is the region's existing inset (statusBars for
 * top regions, navigationBars for bottom/side regions); pass null for none.
 */
@Composable
fun BoxScope.slotModifier(p: SlotPlacement, insets: LayoutWindowInsets? = null): Modifier {
    val alignment = when (p.anchor) {
        SlotAnchor.TOP_START     -> Alignment.TopStart
        SlotAnchor.TOP_END       -> Alignment.TopEnd
        SlotAnchor.TOP_CENTER    -> Alignment.TopCenter
        SlotAnchor.BOTTOM_CENTER -> Alignment.BottomCenter
        SlotAnchor.CENTER_START  -> Alignment.CenterStart
        SlotAnchor.CENTER_END    -> Alignment.CenterEnd
    }
    var m = Modifier.align(alignment)
    if (insets != null) m = m.windowInsetsPadding(insets)
    m = m.padding(start = p.startDp.dp, top = p.topDp.dp, end = p.endDp.dp, bottom = p.bottomDp.dp)
    if (p.maxWidthDp != null) m = m.widthIn(max = p.maxWidthDp.dp)
    return m
}
```
Note: `BoxScope` is needed for `.align`. Add `import androidx.compose.foundation.layout.BoxScope` if not present.

- [ ] **Step 2: Read `chromeOrientation` at the top of RecordScreen**

Add imports to `RecordScreen.kt`:
```kotlin
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import com.aritr.rova.ui.screens.chrome.ChromeOrientation
import com.aritr.rova.ui.screens.chrome.ChromeSlot
import com.aritr.rova.ui.screens.chrome.placementFor
```

Just before the `Scaffold(` call (RecordScreen.kt:543), add:
```kotlin
val chromeOrientation =
    if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE)
        ChromeOrientation.LANDSCAPE else ChromeOrientation.PORTRAIT
```

- [ ] **Step 3: Build to verify it compiles (unused warnings are fine)**

Run the cache-recovery sequence, then:
`.\gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL (the new symbols are unused so far — acceptable; do not silence with usage yet).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt
git commit -m "feat(record): slotModifier mapper + chromeOrientation source (no behavior change)"
```

---

## Task 3: Wire the four top-anchored regions through `slotModifier`

Portrait output must be identical. Each region swaps its hardcoded `.align().windowInsetsPadding().padding()` for `slotModifier(placementFor(SLOT, chromeOrientation), WindowInsets.statusBars)`.

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt:653-656, 666-670, 816-819, 850-855`

- [ ] **Step 1: STATUS_OVERLAY — RecordTopOverlay (816-819)**

Replace:
```kotlin
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .windowInsetsPadding(WindowInsets.statusBars)
                            .padding(start = 16.dp, top = 16.dp),
```
with:
```kotlin
                        modifier = slotModifier(
                            placementFor(ChromeSlot.STATUS_OVERLAY, chromeOrientation),
                            WindowInsets.statusBars,
                        ),
```

- [ ] **Step 2: RECOVERY_CHIP — RecordRecoveryChip (653-656)**

Replace:
```kotlin
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .windowInsetsPadding(WindowInsets.statusBars)
                            .padding(start = 16.dp, top = 70.dp),   // sits just below the status pill (~16 + pill height)
```
with:
```kotlin
                        modifier = slotModifier(
                            placementFor(ChromeSlot.RECOVERY_CHIP, chromeOrientation),
                            WindowInsets.statusBars,
                        ),
```

- [ ] **Step 3: WARNING_CENTER — idle WarningCenter Box (666-670)**

Replace:
```kotlin
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .windowInsetsPadding(WindowInsets.statusBars)
                            .padding(start = 16.dp, top = 110.dp)
                    ) {
```
with:
```kotlin
                    Box(
                        modifier = slotModifier(
                            placementFor(ChromeSlot.WARNING_CENTER, chromeOrientation),
                            WindowInsets.statusBars,
                        )
                    ) {
```

- [ ] **Step 4: CAMERA_CONTROLS — RecordCameraControls (850-855)**

Replace:
```kotlin
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .windowInsetsPadding(WindowInsets.statusBars)
                            // 7.dp + the 9.dp the 30.dp glass circle is inset within its
                            // 48.dp touch box ≈ the mockup's 16.dp from the safe-area edge.
                            .padding(end = 7.dp, top = 7.dp),
```
with:
```kotlin
                        modifier = slotModifier(
                            placementFor(ChromeSlot.CAMERA_CONTROLS, chromeOrientation),
                            WindowInsets.statusBars,
                        ),
```

- [ ] **Step 5: Build + full unit tests**

Cache-recovery sequence, then:
`.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL; all tests green (no behavior change in portrait; ChromeSlotPlacementTest already green).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt
git commit -m "feat(record): route top-anchored chrome regions through slotModifier"
```

---

## Task 4: Wire PARAMS_CARD and ACTIVE_HUD wrapper Columns

These wrap content (settings card / warning+HUD) and keep `fillMaxWidth()` + their vertical arrangement; only the outer align/inset/padding/cap moves to `slotModifier`. Landscape caps width to 360 (centered).

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt:736-741, 772-785`

- [ ] **Step 1: ACTIVE_HUD wrapper Column (736-741)**

Replace:
```kotlin
                        Column(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .windowInsetsPadding(WindowInsets.statusBars)
                                .padding(top = 16.dp)
                                .fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
```
with:
```kotlin
                        Column(
                            modifier = slotModifier(
                                placementFor(ChromeSlot.ACTIVE_HUD, chromeOrientation),
                                WindowInsets.statusBars,
                            ).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
```

- [ ] **Step 2: PARAMS_CARD wrapper Column (772-785)**

Replace:
```kotlin
                    Column(
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
                            )
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
```
with:
```kotlin
                    Column(
                        // PR-β — portrait placement equals the prior
                        // bottomNavClearance(90)+settingsCardLift(30)=120 bottom +
                        // 16 dp side margins (pinned by ChromeSlotPlacementTest);
                        // landscape caps to 360 dp centered.
                        modifier = slotModifier(
                            placementFor(ChromeSlot.PARAMS_CARD, chromeOrientation),
                            WindowInsets.navigationBars,
                        ).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
```

- [ ] **Step 3: Build + full unit tests**

Cache-recovery sequence, then:
`.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL; all tests green.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt
git commit -m "feat(record): route params card + active HUD wrappers through slotModifier"
```

---

## Task 5: Internal-axis flip for RecordCameraControls + RecordActiveHud

Landscape stacks these horizontally instead of vertically. Slot and semantics unchanged. Both gain a `ChromeOrientation` parameter (default PORTRAIT so the change is opt-in at the call sites).

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt:172-237` (RecordCameraControls), `981-1023` (RecordActiveHud)
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt` (pass `chromeOrientation` to both)

- [ ] **Step 1: Extract the two camera buttons, then branch Row/Column in RecordCameraControls**

In `RecordCameraControls` (RecordChrome.kt:172), add the orientation param and replace the single `Column { flash; flip }` body with two extracted button composables placed in a Row (landscape) or Column (portrait). Change the signature:
```kotlin
@Composable
fun RecordCameraControls(
    flashMode: Int,
    onCycleFlash: () -> Unit,
    onFlip: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    flipEnabled: Boolean = enabled,
    isFrontCamera: Boolean = false,
    orientation: ChromeOrientation = ChromeOrientation.PORTRAIT,
) {
    val tint = if (enabled) Color.White.copy(alpha = 0.75f) else Color.White.copy(alpha = 0.3f)
    val flipTint = if (flipEnabled) Color.White.copy(alpha = 0.75f) else Color.White.copy(alpha = 0.3f)
    val flash = @Composable { CamFlashButton(flashMode, onCycleFlash, enabled, tint) }
    val flip = @Composable { CamFlipButton(onFlip, flipEnabled, isFrontCamera, flipTint) }
    if (orientation == ChromeOrientation.LANDSCAPE) {
        Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(RecordChromeTokens.camControlGap)) {
            flash(); flip()
        }
    } else {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(RecordChromeTokens.camControlGap)) {
            flash(); flip()
        }
    }
}

@Composable
private fun CamFlashButton(flashMode: Int, onCycleFlash: () -> Unit, enabled: Boolean, tint: Color) {
    GlassCircleButton(onClick = onCycleFlash, enabled = enabled) {
        val isOff = flashMode != RovaRecordingService.FLASH_MODE_ON &&
            flashMode != RovaRecordingService.FLASH_MODE_AUTO
        val contentTint = when {
            flashMode == RovaRecordingService.FLASH_MODE_ON && enabled -> Color.Yellow
            else -> tint
        }
        Box(contentAlignment = Alignment.Center) {
            Icon(
                RecordChromeIcons.flashBolt,
                contentDescription = stringResource(R.string.record_flash_cd),
                tint = contentTint,
                modifier = Modifier.size(15.dp),
            )
            if (isOff) {
                Box(
                    Modifier
                        .size(width = 20.dp, height = 1.5.dp)
                        .rotate(-45f)
                        .background(contentTint),
                )
            }
        }
    }
}

@Composable
private fun CamFlipButton(onFlip: () -> Unit, flipEnabled: Boolean, isFrontCamera: Boolean, flipTint: Color) {
    GlassCircleButton(onClick = onFlip, enabled = flipEnabled) {
        val flipIcon = if (isFrontCamera) RecordChromeIcons.cameraRear else RecordChromeIcons.cameraFront
        val flipCd = stringResource(
            if (isFrontCamera) R.string.record_switch_to_rear_cd
            else R.string.record_switch_to_front_cd,
        )
        Icon(
            flipIcon,
            contentDescription = flipCd,
            tint = flipTint,
            modifier = Modifier.size(16.dp),
        )
    }
}
```
(`Color` is already imported. Add `import com.aritr.rova.ui.screens.chrome.ChromeOrientation` if Task 2 didn't already add it to this file — it did.)

- [ ] **Step 2: Branch Row/Column for the loop+status pair in RecordActiveHud**

In `RecordActiveHud` (RecordChrome.kt:981), add the orientation param and wrap LoopPill+StatusPill in a Row when landscape. The outer Column (with the liveRegion semantics) is unchanged. Change the signature and body:
```kotlin
@Composable
internal fun RecordActiveHud(
    state: RecordHudState,
    loopIndex: Int,
    loopTotal: Int,
    clipSecondsLeft: Int,
    waitSecondsLeft: Int,
    rotatingNextClip: Boolean = false,
    orientation: ChromeOrientation = ChromeOrientation.PORTRAIT,
    modifier: Modifier = Modifier,
) {
    val announcement = hudActiveAnnouncement(state, loopIndex, loopTotal)?.resolve() ?: ""
    Column(
        modifier = modifier.semantics(mergeDescendants = true) {
            liveRegion = LiveRegionMode.Polite
            contentDescription = announcement
        },
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // PR-β — landscape places the loop pill beside the status pill (a vertical
        // stack reads badly in the short landscape top band). Same composables,
        // same semantics on the outer Column.
        if (orientation == ChromeOrientation.LANDSCAPE) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LoopPill(loopIndex = loopIndex, loopTotal = loopTotal)
                StatusPill(content = hudStatusPillContent(state, clipSecondsLeft, waitSecondsLeft))
            }
        } else {
            LoopPill(loopIndex = loopIndex, loopTotal = loopTotal)
            StatusPill(content = hudStatusPillContent(state, clipSecondsLeft, waitSecondsLeft))
        }
        if (rotatingNextClip) {
            Text(
                text = stringResource(R.string.record_orientation_rotating_next),
                style = RovaTokens.statusTime,
                color = RecordChromeTokens.statusTimeText,
            )
        }
        LoopSegmentBar(loopIndex = loopIndex, loopTotal = loopTotal)
    }
}
```
(`Row` is already imported.)

- [ ] **Step 3: Pass `chromeOrientation` from RecordScreen**

In RecordScreen.kt, add `orientation = chromeOrientation,` to the `RecordCameraControls(...)` call (after `isFrontCamera = serviceState.isFrontCamera,`, before `modifier =`) and to the `RecordActiveHud(...)` call (after `rotatingNextClip = ...,`).

`RecordActiveHud(...)` becomes:
```kotlin
                            RecordActiveHud(
                                state = hudState,
                                loopIndex = serviceState.currentLoop,
                                loopTotal = serviceState.totalLoops,
                                clipSecondsLeft = (duration - clipElapsedSeconds).coerceAtLeast(0),
                                waitSecondsLeft = displayedCountdownSeconds.toInt().coerceAtLeast(0),
                                rotatingNextClip = serviceState.pendingNextRotation != serviceState.currentSegmentRotation,
                                orientation = chromeOrientation,
                            )
```

- [ ] **Step 4: Build + full unit tests**

Cache-recovery sequence, then:
`.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL; all tests green (no helper test touches these; portrait default unchanged).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt
git commit -m "feat(record): landscape internal-axis flip for cam-controls + active HUD"
```

---

## Task 6: Nav decomposition — portrait bar vs landscape rail + FAB

Portrait keeps the intact `RecordBottomNav`. Landscape renders a `RecordNavRail` (Library+Settings, left) + the existing `RecordFab` (right-center). Expose `RecordFab` and `NavItem` for reuse.

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt` (make `RecordFab` and `NavItem` non-private; add `RecordNavRail`)
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt:858-865` (branch portrait/landscape nav)

- [ ] **Step 1: Make `RecordFab` and `NavItem` reusable + add `RecordNavRail`**

In RecordChrome.kt, change `private fun RecordFab(` (line 566) to `internal fun RecordFab(` and `private fun NavItem(` (line 531) to `internal fun NavItem(`.

Add a new composable (place after `RecordBottomNav`, ~line 528):
```kotlin
/**
 * PR-β — landscape nav rail: the Library + Settings destinations as a quiet
 * vertical stack on the leading edge. The record FAB is placed separately
 * (RECORD_ACTION slot) on the trailing edge, so this rail is navigation-only.
 * Reuses [NavItem] — same leaves as the portrait [RecordBottomNav] bar.
 */
@Composable
fun RecordNavRail(
    navItemsLocked: Boolean,
    onLibrary: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(RecordChromeTokens.bottomNavPaddingH),
    ) {
        NavItem(icon = RecordChromeIcons.library, label = stringResource(R.string.record_nav_library), enabled = !navItemsLocked, onClick = onLibrary)
        NavItem(icon = RecordChromeIcons.settings, label = stringResource(R.string.record_nav_settings), enabled = !navItemsLocked, onClick = onSettings)
    }
}
```

- [ ] **Step 2: Branch the nav in RecordScreen**

Replace the `RecordBottomNav(...)` call (RecordScreen.kt:858-865):
```kotlin
                RecordBottomNav(
                    fabState = fabState,
                    navItemsLocked = isUiLocked,
                    onLibrary = onNavigateToHistory,
                    onSettings = onNavigateToSettings,
                    onFabClick = onFabClick,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
```
with:
```kotlin
                if (chromeOrientation == ChromeOrientation.LANDSCAPE) {
                    // PR-β — landscape decomposes the bar: quiet nav rail (left) +
                    // record FAB (right-center). Same leaves as the portrait bar.
                    RecordNavRail(
                        navItemsLocked = isUiLocked,
                        onLibrary = onNavigateToHistory,
                        onSettings = onNavigateToSettings,
                        modifier = slotModifier(
                            placementFor(ChromeSlot.NAV_RAIL, chromeOrientation),
                            WindowInsets.navigationBars,
                        ),
                    )
                    Box(
                        modifier = slotModifier(
                            placementFor(ChromeSlot.RECORD_ACTION, chromeOrientation),
                            WindowInsets.navigationBars,
                        ),
                    ) {
                        RecordFab(state = fabState, onClick = onFabClick)
                    }
                } else {
                    RecordBottomNav(
                        fabState = fabState,
                        navItemsLocked = isUiLocked,
                        onLibrary = onNavigateToHistory,
                        onSettings = onNavigateToSettings,
                        onFabClick = onFabClick,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
```
Add `import com.aritr.rova.ui.screens.RecordNavRail` is NOT needed (same package). Ensure `RecordFab` and `RecordNavRail` resolve (same package `com.aritr.rova.ui.screens`).

- [ ] **Step 3: Build + full unit tests**

Cache-recovery sequence, then:
`.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL; all tests green.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt
git commit -m "feat(record): decompose nav into landscape rail + FAB (ADR-0029 §B slot model)"
```

---

## Task 7: Final verification + APK for device smoke

**Files:** none (verification only).

- [ ] **Step 1: Full clean build + complete unit suite**

Cache-recovery sequence, then:
`.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL; full suite green (baseline + new `ChromeSlotPlacementTest`). Confirm all 35 `check*` gates pass (they run in `preBuild`); no new gate was added.

- [ ] **Step 2: Confirm portrait is byte-identical by code inspection**

Re-read the "Reference — current portrait placements" table against the diff: each `placementFor(..., PORTRAIT)` value must equal the prior literal. `ChromeSlotPlacementTest` already pins this; this step is a human/diff sanity check that no region lost its `windowInsetsPadding` or `fillMaxWidth`.

- [ ] **Step 3: Install on device for landscape smoke**

```powershell
& "C:\Program Files\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk
& "C:\Program Files\platform-tools\adb.exe" shell am start -n com.aritr.rova/.MainActivity
```
Smoke checklist (owner, on device — rotate the phone to landscape on the Record tab):
- Idle landscape: status pill top-left, flash/flip top-right (a row), params card centered + not stretched, nav rail (Library/Settings) left-center, Start FAB right-center. Nothing stretched edge-to-edge.
- Rotate back to portrait: layout identical to before this PR (status TL, cam-controls TR column, settings card bottom, bottom nav bar).
- Start a recording, rotate to landscape: active HUD top-center capped, loop+status side by side; Stop FAB right-center; rotate back — no crash, no chrome drift.
- DualShot / P+L: preview unchanged in both orientations.

- [ ] **Step 4: No commit** (verification only). Await owner device-GO before finishing the branch.

---

## Self-Review

**Spec coverage:**
- §1 goal (slot-based landscape) → Tasks 1-6. ✓
- §3 zone map (record right / nav left / params centered / top semantics) → Task 1 values + Tasks 4-6. ✓
- §4.1 pure helper → Task 1. ✓
- §4.2 placement table → Task 1 constants (explicit-edge refinement noted in header). ✓
- §4.3 nav decomposition → Task 6. ✓
- §4.4 internal-axis flip → Task 5. ✓
- §4.5 call-site mapper → Task 2. ✓
- §6 a11y (HUD live region stays on same Column) → Task 5 Step 2 (outer Column untouched). ✓
- §7 tests → Task 1 (16 assertions incl. portrait regression pin + dock-metric drift guard). ✓
- §8 scope guards (no gate/strings/schema) → honored; reuses existing strings, no manifest/schema change. ✓
- §9 γ relationship (mode chip stays in PARAMS_CARD slot) → PARAMS_CARD wired Task 4, content unchanged. ✓

**Placeholder scan:** none — every step has concrete code/commands.

**Type consistency:** `ChromeSlot`, `ChromeOrientation`, `SlotAnchor`, `SlotPlacement(startDp/topDp/endDp/bottomDp/maxWidthDp)`, `placementFor`, `slotModifier(p, insets)`, `RecordNavRail`, `RecordFab` (internal), `NavItem` (internal), `RecordCameraControls(... orientation)`, `RecordActiveHud(... orientation)` consistent across tasks. `RecordChromeMetrics.bottomNavClearance/settingsCardLift` referenced for the drift-guard test. ✓
