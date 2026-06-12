# PR-ε — Fixed-Window + Counter-Rotating Record Chrome Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
> **Build discipline:** subagents are EDIT-ONLY — controller runs ALL gradle (broken kotlin-postedit hook corrupts `built_in_kotlinc` cache). Recovery before each build: `gradlew --stop` → kill java → clear `app/build/kotlin` + `built_in_kotlinc` caches. Killing java kills the adb server (re-auth dialog may appear on device).

**Goal:** Record-home chrome that never re-arranges on rotation — window locked portrait on compact screens, designated element contents counter-rotate in place (I-style, owner-ratified) — per spec `docs/superpowers/specs/2026-06-12-rotation-first-chrome-fixed-window-design.md`.

**Architecture:** A `DeviceOrientationSignal` (reusing PR-α `snapOrientation` dwell+dead-band) feeds one `Animatable` spin angle in RecordScreen; chrome composables receive `spinDegrees: Float` and wrap designated content in a `SpinningBox` (graphicsLayer child, interaction on the stable outer container). A `DisposableEffect` lock (route ∧ no-modal ∧ compact) freezes the window; since the window never rotates on compact, all existing β landscape branches go dead there automatically and survive untouched as the sw600dp Adaptive fallback.

**Tech Stack:** Kotlin, Jetpack Compose (graphicsLayer/Animatable), CameraX 1.4.2 untouched, AGP 9.2.1 / Gradle 9.4.1, JVM-only tests.

**Base:** master after PRs #106 (β) + #107 (γ) merge. Branch: `feat/pr-epsilon-fixed-window-chrome`. If executed before the merges, branch off `feat/pr-gamma-capture-topology` and rebase later — file contents are identical.

---

## File map

| File | Role |
|---|---|
| Create `app/src/main/java/com/aritr/rova/ui/screens/chrome/ChromeSpin.kt` | Pure angle math: counter-rotation table, shortest-path delta |
| Create `app/src/test/java/com/aritr/rova/ui/screens/chrome/ChromeSpinTest.kt` | Tests for the above |
| Create `app/src/main/java/com/aritr/rova/ui/screens/chrome/ChromeModePolicy.kt` | Pure: `ChromeMode` enum, `chromeMode(swDp)`, `RecordChromeLockPolicy.shouldLock` |
| Create `app/src/test/java/com/aritr/rova/ui/screens/chrome/ChromeModePolicyTest.kt` | Tests for the above |
| Create `app/src/main/java/com/aritr/rova/ui/signals/DeviceOrientationSignal.kt` | Seam: OrientationEventListener → `StateFlow<Int>` snapped rotation |
| Modify `app/src/main/java/com/aritr/rova/RovaApp.kt` | Lazy signal prop (house pattern) |
| Modify `app/src/main/java/com/aritr/rova/ui/screens/RecordViewModel.kt` | Expose `deviceRotation: StateFlow<Int>` |
| Modify `app/src/main/java/com/aritr/rova/ui/theme/RecordChromeTokens.kt` | `elementSpinMs`, `cellSlot` tokens |
| Modify `app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt` | `SpinningBox`; spin params on SettingsCell/strip, NavItem, RecordFab, camera controls, RecordTopOverlay, RecordActiveHud |
| Modify `app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt` | Lock effect, signal collection, Animatable, thread `spinDegrees` |
| Modify `app/build.gradle.kts` | Gate `checkRecordChromeLockSingleSite` |
| Modify `docs/adr/0029-*.md` | §B″ amendment |

**Threading note:** Tasks 4–7 all touch `RecordChrome.kt`/`RecordScreen.kt` — run Tasks sequentially, never in parallel. Compiles for UI tasks are deferred to Task 9 (controller batch build); pure-helper tasks (1–2) run their own JVM tests immediately (tests only — `testDebugUnitTest --tests` does not trip the broken hook the way full builds do; if it does, defer those runs to Task 9 too and rely on TDD-by-inspection).

---

### Task 1: Pure spin math — `ChromeSpin.kt`

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/screens/chrome/ChromeSpin.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/screens/chrome/ChromeSpinTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.aritr.rova.ui.screens.chrome

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Test

class ChromeSpinTest {

    // Counter-rotation table (spec §2.3). Signs are the empirical convention
    // verified at Task 10 Step 3; if the device shows inverted spin, flip ONLY
    // uiCounterRotationDegrees (single site).
    @Test fun counterRotation_table() {
        assertEquals(0f, uiCounterRotationDegrees(Surface.ROTATION_0))
        assertEquals(90f, uiCounterRotationDegrees(Surface.ROTATION_90))
        assertEquals(180f, uiCounterRotationDegrees(Surface.ROTATION_180))
        assertEquals(-90f, uiCounterRotationDegrees(Surface.ROTATION_270))
    }

    @Test fun counterRotation_garbageDefaultsToZero() {
        assertEquals(0f, uiCounterRotationDegrees(-1))
        assertEquals(0f, uiCounterRotationDegrees(7))
    }

    // Shortest-path delta (research §3): result always in [-180, 180).
    @Test fun shortestPath_simple() {
        assertEquals(90f, shortestPathDelta(0f, 90f))
        assertEquals(-90f, shortestPathDelta(90f, 0f))
    }

    @Test fun shortestPath_crossesSeam() {
        // 270 -> 0 must be +90, NOT -270 (no long-way spins).
        assertEquals(90f, shortestPathDelta(270f, 0f))
        assertEquals(-90f, shortestPathDelta(0f, 270f))
    }

    @Test fun shortestPath_unwrappedAccumulator() {
        // current may be far outside [0,360) after many spins.
        assertEquals(90f, shortestPathDelta(720f, 90f))
        assertEquals(-90f, shortestPathDelta(-630f, 0f))
    }

    @Test fun shortestPath_halfTurnIsPlus180() {
        // ±180 ambiguity resolved to +180 consistently.
        assertEquals(180f, shortestPathDelta(0f, 180f))
    }

    @Test fun accumulator_neverDriftsFromTarget() {
        // applying delta lands exactly on target mod 360
        var angle = 0f
        intArrayOf(Surface.ROTATION_90, Surface.ROTATION_270, Surface.ROTATION_180, Surface.ROTATION_0).forEach { r ->
            angle += shortestPathDelta(angle, uiCounterRotationDegrees(r))
            val mod = (((angle - uiCounterRotationDegrees(r)) % 360f) + 360f) % 360f
            assertEquals(0f, mod)
        }
    }
}
```

- [ ] **Step 2: Run, verify FAIL** —
  `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.screens.chrome.ChromeSpinTest"`
  Expected: compile error, unresolved `uiCounterRotationDegrees` / `shortestPathDelta`.

- [ ] **Step 3: Implement**

```kotlin
package com.aritr.rova.ui.screens.chrome

import android.view.Surface

/**
 * PR-ε (spec §2.3) — UI counter-rotation angle for a snapped Surface.ROTATION_*.
 *
 * NOT the CameraX targetRotation mapping (that one is INVERSE — physical 45-135°
 * maps to ROTATION_270; see research §2). Two named functions, never interchanged;
 * the capture side lives in the service (checkSetTargetRotationBoundaryOnly).
 *
 * Sign convention verified on device (plan Task 10 Step 3). If the spin is
 * inverted on hardware, flip the 90/-90 pair HERE ONLY.
 */
internal fun uiCounterRotationDegrees(snappedRotation: Int): Float = when (snappedRotation) {
    Surface.ROTATION_90 -> 90f
    Surface.ROTATION_180 -> 180f
    Surface.ROTATION_270 -> -90f
    else -> 0f
}

/**
 * PR-ε (research §3) — minimal signed delta from an UNWRAPPED accumulator angle
 * to a target (mod 360). Result in [-180, 180); +180 chosen for the half-turn.
 * Animate `current + shortestPathDelta(current, target)` — never animate to the
 * raw target or a 270°→0° transition spins the long way.
 */
internal fun shortestPathDelta(currentUnwrapped: Float, targetMod360: Float): Float {
    val diff = (((targetMod360 - currentUnwrapped) % 360f) + 360f) % 360f  // [0,360)
    return if (diff >= 180f) diff - 360f else diff
}
```

Note: `shortestPath_halfTurnIsPlus180` expects `+180`; `diff == 180` returns `180 - 360 = -180` with the code above — so the boundary must be `> 180f`, not `>= 180f`. Use:

```kotlin
    return if (diff > 180f) diff - 360f else diff
```

(The test suite pins this: `shortestPath_halfTurnIsPlus180` fails with `>=`.)

- [ ] **Step 4: Run, verify PASS** (7 tests).
- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/chrome/ChromeSpin.kt \
        app/src/test/java/com/aritr/rova/ui/screens/chrome/ChromeSpinTest.kt
git commit -m "feat(epsilon): pure counter-rotation + shortest-path spin math (spec 2.3-2.4)"
```

---

### Task 2: Pure mode + lock policy — `ChromeModePolicy.kt`

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/screens/chrome/ChromeModePolicy.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/screens/chrome/ChromeModePolicyTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.aritr.rova.ui.screens.chrome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChromeModePolicyTest {

    @Test fun compactWidths_getFixedPhysical() {
        assertEquals(ChromeMode.FixedPhysical, chromeMode(smallestScreenWidthDp = 320))
        assertEquals(ChromeMode.FixedPhysical, chromeMode(smallestScreenWidthDp = 411))
        assertEquals(ChromeMode.FixedPhysical, chromeMode(smallestScreenWidthDp = 599))
    }

    @Test fun sw600dpAndUp_getAdaptive() {
        // API 36/37 ignores orientation lock here (spec §9) — Adaptive is mandatory.
        assertEquals(ChromeMode.Adaptive, chromeMode(smallestScreenWidthDp = 600))
        assertEquals(ChromeMode.Adaptive, chromeMode(smallestScreenWidthDp = 840))
    }

    @Test fun lock_requiresAllThreeConditions() {
        assertTrue(RecordChromeLockPolicy.shouldLock(isRecordRoute = true, modalOpen = false, chromeMode = ChromeMode.FixedPhysical))
        assertFalse(RecordChromeLockPolicy.shouldLock(isRecordRoute = false, modalOpen = false, chromeMode = ChromeMode.FixedPhysical))
        // Modal open => unlock (spec §7 — sheet rotates as a normal surface).
        assertFalse(RecordChromeLockPolicy.shouldLock(isRecordRoute = true, modalOpen = true, chromeMode = ChromeMode.FixedPhysical))
        // Large screens never lock (the OS would ignore it anyway).
        assertFalse(RecordChromeLockPolicy.shouldLock(isRecordRoute = true, modalOpen = false, chromeMode = ChromeMode.Adaptive))
    }
}
```

- [ ] **Step 2: Run, verify FAIL** —
  `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.screens.chrome.ChromeModePolicyTest"`

- [ ] **Step 3: Implement**

```kotlin
package com.aritr.rova.ui.screens.chrome

/**
 * PR-ε (spec §9) — which chrome model the record-home runs.
 * FixedPhysical: window locked portrait, contents counter-rotate (compact phones).
 * Adaptive: window rotates, β slot/axis-flip chrome (sw600dp+ — API 36/37 ignores
 * orientation-lock APIs there with NO camera exemption; iPad Camera makes the
 * same choice, so this is correct UX, not just compliance).
 */
internal enum class ChromeMode { FixedPhysical, Adaptive }

/** sw600dp is the platform's published lock-ignore threshold — single source. */
internal const val ADAPTIVE_MIN_SMALLEST_WIDTH_DP = 600

internal fun chromeMode(smallestScreenWidthDp: Int): ChromeMode =
    if (smallestScreenWidthDp >= ADAPTIVE_MIN_SMALLEST_WIDTH_DP) ChromeMode.Adaptive
    else ChromeMode.FixedPhysical

/**
 * PR-ε (spec §2.1) — the ONLY decision point for the record-route orientation
 * lock. Lock iff: on the record route AND no modal surface open AND FixedPhysical.
 * Opening any modal (settings sheet, warning sheet) releases the lock so the
 * window rotates normally and the existing sheet/panel presentations apply
 * (spec §7, owner-ratified "unlock while open").
 */
internal object RecordChromeLockPolicy {
    fun shouldLock(isRecordRoute: Boolean, modalOpen: Boolean, chromeMode: ChromeMode): Boolean =
        isRecordRoute && !modalOpen && chromeMode == ChromeMode.FixedPhysical
}
```

- [ ] **Step 4: Run, verify PASS** (3 tests).
- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/chrome/ChromeModePolicy.kt \
        app/src/test/java/com/aritr/rova/ui/screens/chrome/ChromeModePolicyTest.kt
git commit -m "feat(epsilon): ChromeMode + RecordChromeLockPolicy pure helpers (spec 2.1, 9)"
```

---

### Task 3: `DeviceOrientationSignal` + RovaApp + RecordViewModel exposure

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/signals/DeviceOrientationSignal.kt`
- Modify: `app/src/main/java/com/aritr/rova/RovaApp.kt` (add lazy prop beside the other signal lazies)
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordViewModel.kt` (expose the flow)

No new JVM test: the snap math is already covered by `OrientationSnapTest` (PR-α); this file is a thin framework seam per the house seam pattern (CLAUDE.md). Keep ALL logic in the reused pure functions.

- [ ] **Step 1: Read** `app/src/main/java/com/aritr/rova/ui/signals/ThermalStatusSignal.kt` and `RovaApp.kt`'s signal lazies to copy the exact house construction/injection style.

- [ ] **Step 2: Create the signal**

```kotlin
package com.aritr.rova.ui.signals

import android.content.Context
import android.os.SystemClock
import android.view.OrientationEventListener
import android.view.Surface
import com.aritr.rova.service.orientation.OrientationSnapState
import com.aritr.rova.service.orientation.snapOrientation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * PR-ε (spec §2.2) — UI-side device-orientation seam. Emits the SNAPPED
 * Surface.ROTATION_* (0/1/2/3) for the physical device pose, independent of the
 * window (which is orientation-locked on compact — Display.rotation is frozen
 * there and useless; research §2).
 *
 * Reuses PR-α's [snapOrientation] (dwell ORIENTATION_DWELL_MS + dead-band
 * ORIENTATION_HYSTERESIS_DEG) — ONE snap implementation in the codebase; the
 * service seam and this signal must not drift (spec §2.2).
 *
 * ORIENTATION_UNKNOWN (device flat) is handled inside [snapOrientation]: state
 * unchanged -> hold last value, never spin while flat (research §2).
 *
 * Listener lifecycle follows collection (WhileSubscribed): enabled while the
 * record chrome collects, disabled otherwise — the official enable/disable
 * pattern mapped onto flow subscription.
 */
class DeviceOrientationSignal(
    private val appContext: Context,
    scope: CoroutineScope,
) {
    val snappedRotation: StateFlow<Int> = callbackFlow {
        var state = OrientationSnapState(
            stable = Surface.ROTATION_0, candidate = null, candidateSinceMs = null,
        )
        trySend(state.stable)
        val listener = object : OrientationEventListener(appContext) {
            override fun onOrientationChanged(orientation: Int) {
                state = snapOrientation(
                    degrees = orientation,
                    current = state,
                    nowMs = SystemClock.elapsedRealtime(),
                )
                trySend(state.stable)
            }
        }
        if (listener.canDetectOrientation()) listener.enable()
        awaitClose { listener.disable() }
    }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 2_000), Surface.ROTATION_0)
}
```

- [ ] **Step 3: RovaApp lazy prop** — add beside the existing WarningCenter signal lazies, matching their exact style:

```kotlin
val deviceOrientationSignal: DeviceOrientationSignal by lazy {
    DeviceOrientationSignal(appContext = this, scope = appScope)
}
```

(Use whatever application-scope `CoroutineScope` the neighboring lazies use — read them first; if they construct their own scope, mirror that.)

- [ ] **Step 4: RecordViewModel exposure** — read how RecordViewModel receives other signals (constructor injection from the VM factory wired in RovaApp/MainScreen), then add:

```kotlin
/** PR-ε — snapped physical device rotation for chrome counter-rotation (spec §2.2). */
val deviceRotation: StateFlow<Int> = deviceOrientationSignal.snappedRotation
```

with `deviceOrientationSignal: DeviceOrientationSignal` added to the constructor + factory exactly like the neighboring signal params.

- [ ] **Step 5: Commit** (compile deferred to Task 9)

```bash
git add app/src/main/java/com/aritr/rova/ui/signals/DeviceOrientationSignal.kt \
        app/src/main/java/com/aritr/rova/RovaApp.kt \
        app/src/main/java/com/aritr/rova/ui/screens/RecordViewModel.kt
git commit -m "feat(epsilon): DeviceOrientationSignal seam reusing PR-alpha snapOrientation (spec 2.2)"
```

---

### Task 4: Tokens + `SpinningBox` + config-strip cell spin

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/theme/RecordChromeTokens.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt` (SettingsCell ~514, RecordSettingsCard ~294)

- [ ] **Step 1: Tokens** — read `RecordChromeTokens.kt`, add (matching its object/val style):

```kotlin
/** PR-ε (spec §2.4) — in-place counter-rotation duration. One UI measures ~80-160ms
 *  relayout; Material short-motion is 200-300ms; 180ms between, tune on device. */
val elementSpinMs: Int = 180

/** PR-ε (spec §4) — uniform square slot for config-strip cells, both orientations.
 *  48dp = touch-target floor AND rotation-invariant bound (research §3/§4);
 *  spinning content must fit within slot/√2 ≈ 33.9dp visual diameter. */
val cellSlot: Dp = 48.dp
```

- [ ] **Step 2: SpinningBox in RecordChrome.kt** — add near the top of the file (after imports):

```kotlin
/**
 * PR-ε (spec §3) — in-place counter-rotation wrapper. The OUTER Box is the
 * stable layout/interaction container (clickable/semantics belong on it or on
 * an ancestor — modifiers BEFORE a graphicsLayer are not transformed); only the
 * INNER visual child rotates. graphicsLayer is draw-phase-only for layout, so
 * siblings measure against the unrotated bounds — square containers are
 * rotation-invariant (research §3).
 */
@Composable
internal fun SpinningBox(
    degrees: Float,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(Modifier.graphicsLayer { rotationZ = degrees }) { content() }
    }
}
```

Import `androidx.compose.ui.graphics.graphicsLayer`.

- [ ] **Step 3: SettingsCell square slot + spin** — replace the body of `SettingsCell` (currently RecordChrome.kt:513-531) with:

```kotlin
@Composable
private fun SettingsCell(
    key: String,
    value: String,
    modifier: Modifier,
    readOnly: Boolean,
    compact: Boolean = false,
    spinDegrees: Float = 0f,
) {
    // PR-ε (spec §4, I-style owner-ratified): uniform square slot, content
    // counter-rotates inside it. The slot is the stable bound in BOTH
    // orientations — the pill footprint never changes on rotation.
    SpinningBox(degrees = spinDegrees, modifier = modifier.size(RecordChromeTokens.cellSlot)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
}
```

Note the removed `padding(horizontal = settingsCellPaddingH)` — the square slot replaces per-cell width padding. If the strip then renders too tight/wide (visual check Task 10), tune `cellSlot` or the strip's inter-cell gap, NOT per-cell padding.

- [ ] **Step 4: Thread spin through the strip** — read `RecordSettingsCard` (RecordChrome.kt:294 onward, through its cell row + `ModeCycleChip` + LOCKED cell and the "swipe to edit" caption). Add `spinDegrees: Float = 0f` to `RecordSettingsCard`'s signature; pass it to every `SettingsCell` call, to `ModeCycleChip` (give it the same `spinDegrees: Float = 0f` param wrapping its label content in `SpinningBox`), and wrap the SWIPE-TO-EDIT caption's text in `SpinningBox(degrees = spinDegrees)` (cell-class element, spec §3). Do NOT touch the card's outer tap/swipe gesture handling — gestures stay on stable containers.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/theme/RecordChromeTokens.kt \
        app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt
git commit -m "feat(epsilon): SpinningBox + 48dp square strip cells with counter-rotating content (spec 3-4)"
```

---

### Task 5: Nav + FAB + camera-control glyph spin

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt` (NavItem ~636, RecordFab ~671, RecordCameraControls ~183, GlassCircleButton ~269)

- [ ] **Step 1: NavItem** — read NavItem (636-668). Add `spinDegrees: Float = 0f` param; wrap the icon+label Column's CONTENT in `SpinningBox` while the `clickable`/`semantics`/size modifiers stay on the outer container. The label spins as part of the content block (cell-class, spec §5). Shape:

```kotlin
@Composable
internal fun NavItem(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit, spinDegrees: Float = 0f) {
    Column(
        // keep ALL existing modifiers here (clickable, size, semantics) — stable container
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SpinningBox(degrees = spinDegrees) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // existing Icon(...) and Text(label, ...) exactly as they are today
            }
        }
    }
}
```

(Transcribe the existing Icon/Text/spacing verbatim inside the inner Column; only the wrapper is new.)

- [ ] **Step 2: RecordFab** — read RecordFab (671-723). Wrap ONLY the glyph (the Icon / progress content inside the FAB surface) in `SpinningBox(degrees = spinDegrees)`; the circular surface + click stay stable. Add `spinDegrees: Float = 0f` to its signature.

- [ ] **Step 3: Camera controls** — `RecordCameraControls` (183-210): add `spinDegrees: Float = 0f`, pass to `CamFlashButton`/`CamFlipButton`; in each, wrap the Icon (and the flash OFF slash Box — it rotates WITH the bolt so the slash stays diagonal relative to the glyph) in `SpinningBox(degrees = spinDegrees)` INSIDE `GlassCircleButton`'s content. `GlassCircleButton` itself (IconButton 48dp touch target) is untouched — it is already the stable container.

- [ ] **Step 4: RecordBottomNav** — add `spinDegrees: Float = 0f`, pass to the three leaves (`NavItem` ×2, `RecordFab`). No layout change: the portrait Box/Row branch is what compact always renders (window locked ⇒ `sense == null` there); the landscape Column branch remains for Adaptive (spec §8).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt
git commit -m "feat(epsilon): nav/FAB/camera-control glyphs counter-rotate in stable 48dp containers (spec 5)"
```

---

### Task 6: Info pills — status pill, active HUD, recovery chip

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt` (RecordTopOverlay ~100, StatusPill ~1050, LoopPill ~971, RecordActiveHud ~1111, RecordRecoveryChip ~725)

Info-pill class (spec §3): spin in place with transpose clearance — their placements (top-start / top-center) have only viewfinder below/beside, so the rotated AABB (diagonal mid-spin) may overflow into viewfinder; that is accepted by design. Mid-REC these KEEP spinning (readable timer — deliberate divergence from One UI, spec §6).

- [ ] **Step 1: RecordTopOverlay** — read 100-135. Add `spinDegrees: Float = 0f`; wrap the status pill content (the pill Box and its dot+text) in `SpinningBox(degrees = spinDegrees)`. If the pill is wide (text "Ready to record"), the SpinningBox wraps the WHOLE pill (it spins as a unit about its center).
- [ ] **Step 2: RecordActiveHud** — read 1111 onward. Add `spinDegrees: Float = 0f`; wrap each pill (`StatusPill`, `LoopPill`, `LoopSegmentBar`) individually in `SpinningBox(degrees = spinDegrees)` — pills spin independently in place, the HUD's arrangement (its portrait Column branch) never changes on compact. Leave the existing `orientation` param + Row branch intact (Adaptive).
- [ ] **Step 3: RecordRecoveryChip** — add `spinDegrees: Float = 0f`, wrap the chip content; interaction modifier stays outer.
- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt
git commit -m "feat(epsilon): status/HUD/recovery info pills counter-rotate with clearance (spec 3, 6)"
```

---

### Task 7: RecordScreen wiring — lock, signal, Animatable, threading

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt`

- [ ] **Step 1: Read the three regions:** the DualShot portrait-lock / force-rotate block (~250-270), the orientation/sense derivation (~640-665), and every chrome call site (`RecordTopOverlay`, `RecordCameraControls`, `RecordSettingsCard`, `RecordBottomNav`, `RecordActiveHud`, `RecordRecoveryChip`, SettingsSheet call ~1057-1100). Note the exact name of the sheet-open state (`combinedOpen` or similar) and any warning-sheet-open state.

- [ ] **Step 2: Chrome mode + spin state** — near the existing orientation derivation add:

```kotlin
// PR-ε (spec §2, §9) — chrome model by smallest width (orientation-stable).
val chromeModeNow = chromeMode(LocalConfiguration.current.smallestScreenWidthDp)

// Snapped physical rotation (sensor; works with system auto-rotate OFF — the
// owner's daily configuration — and with the window locked).
val snappedRotation by viewModel.deviceRotation.collectAsState()

// One unwrapped accumulator angle, shortest-path retarget (spec §2.4).
// First composition JUMPS (no spin-on-entry).
val spin = remember { Animatable(uiCounterRotationDegrees(snappedRotation)) }
LaunchedEffect(snappedRotation, chromeModeNow) {
    if (chromeModeNow != ChromeMode.FixedPhysical) return@LaunchedEffect
    val target = spin.value + shortestPathDelta(spin.value, uiCounterRotationDegrees(snappedRotation))
    spin.animateTo(target, animationSpec = tween(RecordChromeTokens.elementSpinMs))
}
val spinDegrees = if (chromeModeNow == ChromeMode.FixedPhysical) spin.value else 0f
```

Imports: `androidx.compose.animation.core.Animatable`, `androidx.compose.animation.core.tween`, `com.aritr.rova.ui.screens.chrome.{ChromeMode, chromeMode, uiCounterRotationDegrees, shortestPathDelta}`.

Reduced-motion note: gate the animation per the existing `ReducedMotion` pattern (`checkA11yAnimationGated` will fail the build otherwise) — when reduced motion is on, `spin.snapTo(target)` instead of `animateTo`.

- [ ] **Step 3: Unified orientation lock** — replace the existing DualShot lock / force-sensor block (~250-270) with one effect:

```kotlin
// PR-ε (spec §2.1) — THE single setRequestedOrientation site for the record
// route (gate: checkRecordChromeLockSingleSite). Lock = route ∧ no-modal ∧
// FixedPhysical; modal open releases it so the sheet rotates as a normal
// surface (spec §7). Restores the prior request on dispose. Subsumes the
// DualShot-only portrait lock. On Adaptive, preserve the pre-ε behavior
// EXACTLY as the block being replaced implemented it (force-sensor for
// Single / portrait for DualShot) — move that logic into the else branch.
val modalOpen = combinedOpen /* || warning-sheet-open state if a separate flag exists — use the names found in Step 1 */
val lock = RecordChromeLockPolicy.shouldLock(
    isRecordRoute = true, modalOpen = modalOpen, chromeMode = chromeModeNow,
)
DisposableEffect(lock, chromeModeNow) {
    val activity = context.findActivity()           // use the file's existing activity accessor
    val previous = activity?.requestedOrientation
    if (lock) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    } else {
        // Adaptive / modal-open: re-apply the pre-ε per-state request here
        // (transcribe from the replaced block).
    }
    onDispose { previous?.let { activity?.requestedOrientation = it } }
}
```

- [ ] **Step 4: Thread `spinDegrees`** into every chrome call found in Step 1: `RecordTopOverlay(...)`, `RecordCameraControls(...)`, `RecordSettingsCard(...)`, `RecordBottomNav(...)`, `RecordActiveHud(...)`, `RecordRecoveryChip(...)` — each gets `spinDegrees = spinDegrees`. Do NOT change their `sense`/`orientation` arguments: under the compact lock the window never reports landscape, so those evaluate to the portrait branch by construction; Adaptive keeps using them (spec §8).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt
git commit -m "feat(epsilon): record-route orientation lock + sensor-driven spin angle wiring (spec 2, 7)"
```

---

### Task 8: Gate + ADR amendment

**Files:**
- Modify: `app/build.gradle.kts` (new `check*` task, wire into preBuild beside the existing 35)
- Modify: `docs/adr/0029-*.md` (locate by glob; §B″ amendment)

- [ ] **Step 1: Gate** — read one existing simple gate in `app/build.gradle.kts` (e.g. `checkScanTriggerSingleSite`) and clone its structure:

```kotlin
// ADR-0029 §B″ — setRequestedOrientation on the UI side ONLY via the
// RecordScreen lock effect (single site). A second writer reintroduces the
// lock/unlock races the §B″ model exists to prevent.
val checkRecordChromeLockSingleSite = tasks.register("checkRecordChromeLockSingleSite") {
    group = "verification"
    doLast {
        val uiDir = file("src/main/java/com/aritr/rova/ui")
        val offenders = uiDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "RecordScreen.kt" }
            .filter { f -> f.readText().contains("requestedOrientation") }
            .map { it.relativeTo(projectDir).path }
            .toList()
        check(offenders.isEmpty()) {
            "ADR-0029 §B″: requestedOrientation written outside RecordScreen.kt: $offenders"
        }
    }
}
```

Wire it into the same `tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(...) }` block as the others (36th gate). If `PreviewActivity.kt` or another legitimate writer trips it, allowlist that file explicitly with a comment citing why.

- [ ] **Step 2: ADR-0029 §B″** — append after §B′: title "§B″ — Fixed window + counter-rotating elements (compact, 2026-06-12)"; record: lock policy (route ∧ no-modal ∧ compact), I-style strip (square cells, frozen order, sense only picks spin direction), info-pill spin incl. mid-REC timer divergence from One UI, unlock-while-open, §B′ chrome demoted to Adaptive (sw600dp+, API-37 lock-ignore, no opt-out), spin math in `ChromeSpin.kt`, gate `checkRecordChromeLockSingleSite`. Mark §B′ as "Adaptive fallback only" rather than Withdrawn.

- [ ] **Step 3: Commit**

```bash
git add app/build.gradle.kts docs/adr/0029-*.md
git commit -m "feat(epsilon): checkRecordChromeLockSingleSite gate (36th) + ADR-0029 B-double-prime"
```

---

### Task 9: Controller build + fix batch

**Files:** none (controller only).

- [ ] **Step 1: Recovery dance** — `./gradlew --stop`; kill stray `java.exe`; delete `app/build/kotlin` and the `built_in_kotlinc` cache dirs. (adb server dies with java — expect device re-auth later.)
- [ ] **Step 2: Build** — `./gradlew :app:testDebugUnitTest :app:assembleDebug 2>&1 | Tee-Object gradle_epsilon_t9.log`. Expected: BUILD SUCCESSFUL, all 36 gates green, full suite green (baseline + 10 new tests).
- [ ] **Step 3:** Any failures → dispatch fix subagent per failure cluster (edit-only), rebuild. Do not edit checks to green them.
- [ ] **Step 4: Commit** any fix-batch changes:

```bash
git add -A app/src docs
git commit -m "fix(epsilon): build/test fix batch (T9)"
```

---

### Task 10: Device verification (controller + owner)

**Files:** none.

- [ ] **Step 1: Install** — `adb install -r app/build/outputs/apk/debug/app-debug.apk` (re-auth if prompted).
- [ ] **Step 2: Smoke matrix** — spec §12, all nine items; owner drives, controller can capture via `adb exec-out screencap` / `screenrecord`:
  1. idle portrait parity (square cells are the only visible delta), 2. both-sense idle spin (nothing moves, ~180ms, no clipping), 3. auto-rotate toggle OFF and ON parity, 4. flat hold + pickup, 5. landscape-grip session: HUD timer upright mid-clip, saved clips correctly oriented, no configChanges on record route, 6. sheet open in landscape grip: unlock → side panel; close → re-lock snap-back, 7. rapid back-and-forth: shortest path, no stuck angles, 8. TalkBack traversal unchanged, 9. (if hardware available) sw600dp Adaptive engages — else emulator note.
- [ ] **Step 3: Spin-sign verification** — if any spin lands content UPSIDE-SIDEWAYS (inverted), flip the `90f`/`-90f` pair in `uiCounterRotationDegrees` (single site, Task 1 KDoc), update `counterRotation_table` expectations, rebuild, re-smoke.
- [ ] **Step 4:** Owner GO → superpowers:finishing-a-development-branch (push `feat/pr-epsilon-fixed-window-chrome`, PR titled "PR-ε: fixed-window + counter-rotating record chrome (ADR-0029 §B″)").

---

## Self-review

- **Spec coverage:** §1/§2 → T1-T3+T7; §3 table → T4-T6; §4 I-style → T4; §5 → T5; §6 → T6; §7 → T2 policy + T7 lock keying on `modalOpen`; §8 → no-deletion approach (branches go dead on compact naturally); §9 → T2 `chromeMode` + T7 gating; §11 → T8; §12 → T10. No gaps.
- **Type consistency:** `spinDegrees: Float = 0f` everywhere; `uiCounterRotationDegrees(Int): Float`; `shortestPathDelta(Float, Float): Float`; `chromeMode(Int): ChromeMode`; `RecordChromeLockPolicy.shouldLock(Boolean, Boolean, ChromeMode): Boolean` — names match across tasks.
- **Placeholders:** read-first steps name exact functions/line anchors and specify the exact transformation; no TBDs. The one deliberate deferral (Adaptive else-branch transcription in T7 Step 3) instructs transcribing the replaced block verbatim — content exists in the file being edited.
- **Known risks:** Animatable retarget under rapid rotation is the one unverified mechanic (spec §13) — covered by smoke item 7 with the fix being `snapTo`+`animateTo` sequencing if retarget misbehaves; `checkA11yAnimationGated` compliance called out in T7 Step 2; `checkNoHardcodedUiStrings` unaffected (no new user copy).
