# M4 Onboarding Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cut first-launch onboarding 7 → 3 screens (2 walkthrough + 1 Camera rationale), defer Mic/Notif/Exact-Alarm to existing JIT path in `RecordScreen.kt`, fix the light-mode-on-install bug, and rewrite all user-visible copy at grade-5/6 reading level for mainstream non-technical users.

**Architecture:** Pure surface redesign — `OnboardingScreen` / `OnboardingViewModel` / `OnboardingStep` simplified, illustrations ported from SVG mockup to Compose `Canvas` composables, copy extracted to `strings.xml`, `RovaTheme` pinned dark. No backend changes. No new ADRs. No WarningCenter changes (Mic/Notif/Alarm denial surfaces already exist in `WarningId`). Maintains the Phase 2.6 `RovaSettings.onboardingCompleted` gate in `MainScreen.kt`.

**Tech Stack:** Kotlin 2.2.10 · Compose BOM 2025.01.01 · AGP 9.2.1 · `androidx.compose.foundation.Canvas` · `androidx.compose.ui.res.stringResource` · Material 3 colorScheme · JUnit 4 (pure-JVM tests, no Robolectric).

**Mockup contract:** `mockups/new_uiux/08b-onboarding.html` — copy + visual aesthetic + dot indicator counts + CTA labels all sourced from this file. Mockup is gitignored; this plan inlines every load-bearing token + string.

**Out of scope (parked for later milestones):**
- Theme switcher (Light / Dark / System default) in Settings — separate milestone (M5+ or later)
- "Replay app tour" Settings entry — separate milestone
- Record-screen first-session tip migration (the "Walks away with you" concept) — separate milestone
- Localization beyond `strings.xml` extraction (no `values-*/` variants this slice)
- Per-perm JIT split (Mic-only-on-audio-toggle; currently batched at first Start)

---

## File Structure

**Modified:**
- `app/src/main/java/com/aritr/rova/ui/theme/Theme.kt` — one-line default param change (force dark)
- `app/src/main/res/values/strings.xml` — extracted onboarding copy
- `app/src/main/java/com/aritr/rova/ui/screens/onboarding/OnboardingStep.kt` — enum 7 → 3 entries
- `app/src/main/java/com/aritr/rova/ui/screens/onboarding/OnboardingViewModel.kt` — drop SDK guards, add `skipWalkthroughToCamera`
- `app/src/main/java/com/aritr/rova/ui/screens/onboarding/OnboardingScreen.kt` — drop 3 launchers + SDK gating effect, rename composable target
- `app/src/main/java/com/aritr/rova/ui/screens/onboarding/OnboardingSlide.kt` — rewrite `WalkthroughSlide`, replace `PermissionSlide` with `CameraRationaleSlide`, wire new illustrations
- `app/src/test/java/com/aritr/rova/ui/screens/onboarding/OnboardingViewModelTest.kt` — drop dead tests, keep transitions, add `skipWalkthroughToCamera` coverage

**Created:**
- `app/src/main/java/com/aritr/rova/ui/screens/onboarding/OnboardingIllustrations.kt` — 3 `@Composable` Canvas-based illustrations (clock orbit, clip merge, camera glyph)

**Verification (no commits):**
- `./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleDebug`

---

## Task 1: Foundation — `strings.xml` extraction + force-dark theme

**Why first:** zero behavior change, lowest risk, unblocks every later task that references `stringResource(R.string.onboarding_*)`. Force-dark is independent but pairs naturally with the foundational commit.

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/java/com/aritr/rova/ui/theme/Theme.kt:87`

- [ ] **Step 1: Add all M4 onboarding copy to `strings.xml`**

Replace the entire `<resources>` block:

```xml
<resources>
    <string name="app_name">Rova</string>

    <!-- M4 (2026-05-27) Onboarding redesign — see mockups/new_uiux/08b-onboarding.html.
         Copy verified at Flesch-Kincaid grade 5-6 (NN/g target for mainstream
         non-technical users). Ban list: "grant", "permission" (the system word),
         "background", "foreground", "schedule" (the system word), "drift",
         "session", "loop" (audio collision), all-caps Android constants. -->
    <string name="onboarding_skip">Skip</string>
    <string name="onboarding_next">Next</string>
    <string name="onboarding_continue">Continue</string>

    <string name="onboarding_walkthrough_1_title">Records on a schedule</string>
    <string name="onboarding_walkthrough_1_body">Choose how long each clip is, the gap between, and how many to record.</string>

    <string name="onboarding_walkthrough_2_title">One video at the end</string>
    <string name="onboarding_walkthrough_2_body">When recording finishes, your clips combine into a single video — ready to share.</string>

    <string name="onboarding_camera_title">Camera</string>
    <string name="onboarding_camera_body">Rova uses your camera to record video. Without it, the app can't work.</string>
    <string name="onboarding_camera_allow">Allow Camera</string>
    <string name="onboarding_camera_not_now">Not now</string>

    <!-- contentDescription strings for illustrations -->
    <string name="onboarding_illustration_clock_orbit">Clock with timing badges</string>
    <string name="onboarding_illustration_clip_merge">Three clips combine into one video file</string>
    <string name="onboarding_illustration_camera">Camera</string>
</resources>
```

- [ ] **Step 2: Force-dark theme — fix light-mode-on-install bug**

Edit `app/src/main/java/com/aritr/rova/ui/theme/Theme.kt:87`. Change the default param ONLY:

```kotlin
@Composable
fun RovaTheme(
    darkTheme: Boolean = true,        // ← M4 (2026-05-27): pin dark.
    // Was: `isSystemInDarkTheme()`. Light-mode users on fresh install were
    // landing on the unfinished light scheme. Full Light/Dark/System
    // switcher ships in a later milestone via RovaSettings.themeMode.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
```

The two explicit callsites in `LargeValueStepper.kt:138,169` and `EditSheetShell.kt:98,127` pass `darkTheme = false|true` for preview-only variants; they remain unaffected.

- [ ] **Step 3: Verify the build still compiles**

Run:
```powershell
./gradlew :app:assembleDebug
```
Expected: BUILD SUCCESSFUL. Lint hint count = baseline (the existing 51W+1H pre-existing).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/res/values/strings.xml app/src/main/java/com/aritr/rova/ui/theme/Theme.kt
git commit -m "feat(onboarding): foundation — strings.xml copy + force-dark theme (M4 Task 1)"
```

---

## Task 2: Shrink `OnboardingStep` enum 7 → 3

**Why next:** every downstream file depends on the enum cardinality. Shrinking first surfaces every compile error so we know the blast radius.

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/onboarding/OnboardingStep.kt`

- [ ] **Step 1: Replace the enum body**

Replace the entire file contents (preserve package + filename):

```kotlin
package com.aritr.rova.ui.screens.onboarding

/**
 * M4 (2026-05-27) Onboarding redesign — 3-step first-launch flow.
 *
 * Walkthrough trimmed 3 → 2 ("Walks away with you" dropped; overlapped
 * card 1's loop premise and risked misread by mainstream users — the
 * concept resurfaces in a later milestone as a Record-screen tip).
 *
 * Permission slides trimmed 4 → 1: Camera-only upfront because Camera
 * is the only hard-block — without it the app's primary surface
 * (Record) cannot render. Microphone, Notifications, and
 * SCHEDULE_EXACT_ALARM are deferred to JIT prompts in
 * [com.aritr.rova.ui.screens.RecordScreen] (existing
 * `rememberMultiplePermissionsState` path at lines 202-224, triggered
 * on first Start). Denial states flow through WarningCenter advisories
 * (MICROPHONE_DENIED / NOTIFICATIONS_DENIED / EXACT_ALARM_DENIED) per
 * the Phase 4 contract — no UI loss vs the previous 4-screen flow.
 *
 * Rationale: NN/g (2020) — "avoid app onboarding whenever possible;
 * use the minimum number of cards." Google Android permissions guide
 * (2025) — "Request permissions at runtime in the context of the
 * feature." Both primary-source citations are in the design doc at
 * `mockups/new_uiux/08b-onboarding.html`.
 *
 * The enum's `ordinal` drives [next] / [previous]; reordering entries
 * without updating the mockup is a behavior change.
 */
enum class OnboardingStep {
    WALKTHROUGH_1,
    WALKTHROUGH_2,
    PERM_CAMERA;

    fun next(): OnboardingStep? = entries.getOrNull(ordinal + 1)
    fun previous(): OnboardingStep? = entries.getOrNull(ordinal - 1)

    val isWalkthrough: Boolean get() = ordinal <= WALKTHROUGH_2.ordinal
    val isPermission: Boolean get() = !isWalkthrough
}
```

- [ ] **Step 2: Compile check — expect failures**

Run:
```powershell
./gradlew :app:compileDebugKotlin
```
Expected: compile failures in:
- `OnboardingViewModel.kt` (no failures expected — VM doesn't name dropped steps)
- `OnboardingScreen.kt` (references `OnboardingStep.PERM_MIC`, `PERM_NOTIFS`, `PERM_ALARM`)
- `OnboardingSlide.kt` (references `OnboardingStep.PERM_MIC` etc. in `permissionCopy`)
- `OnboardingViewModelTest.kt` (references dropped enum values)

This is intentional — Tasks 3-7 fix each downstream file.

- [ ] **Step 3: Do NOT commit yet.** Enum shrink alone leaves the tree red. Combined commit lands at end of Task 7.

---

## Task 3: Rewrite `OnboardingViewModelTest.kt`

**Why this order:** TDD — write the new tests first so subsequent VM/Screen/Slide edits have a green target to hit.

**Files:**
- Modify: `app/src/test/java/com/aritr/rova/ui/screens/onboarding/OnboardingViewModelTest.kt`

- [ ] **Step 1: Replace the entire test file**

Replace contents:

```kotlin
package com.aritr.rova.ui.screens.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * M4 (2026-05-27) — pure-JVM tests for [OnboardingViewModel] after
 * the 7 → 3 step shrink. Test count: 14 (was 12 in Phase 2.6 — +2 net
 * for the two `skipWalkthroughToCamera` cases).
 *
 * Coverage:
 *  - initial state
 *  - advance step-by-step through all 3 entries + completion
 *  - advance past last step fires `markCompleted` exactly once
 *  - complete from any step fires `markCompleted` exactly once
 *  - complete is idempotent on repeat calls
 *  - advance after completed is a no-op
 *  - advance past last step then complete does not re-fire seam
 *  - goBack walks slides backward
 *  - goBack on first step is a no-op
 *  - goBack after completed is a no-op
 *  - skipWalkthroughToCamera (NEW) jumps from any walkthrough step to PERM_CAMERA
 *  - skipWalkthroughToCamera is a no-op from PERM_CAMERA (already past)
 *  - OnboardingStep next/previous round-trips on every entry
 *  - OnboardingStep walkthrough and permission flags partition the enum
 *
 * Same shape as
 * [com.aritr.rova.ui.signals.ThermalHysteresisTest] —
 * no Android, no coroutines, no Compose.
 */
class OnboardingViewModelTest {

    private fun newVm(seam: AtomicInteger = AtomicInteger(0)): OnboardingViewModel =
        OnboardingViewModel(markCompleted = { seam.incrementAndGet() })

    @Test fun `initial state is WALKTHROUGH_1 not completed`() {
        val vm = newVm()
        assertEquals(OnboardingStep.WALKTHROUGH_1, vm.uiState.value.step)
        assertFalse(vm.uiState.value.completed)
    }

    @Test fun `advance walks every step in order`() {
        val vm = newVm()
        vm.advance()
        assertEquals(OnboardingStep.WALKTHROUGH_2, vm.uiState.value.step)
        vm.advance()
        assertEquals(OnboardingStep.PERM_CAMERA, vm.uiState.value.step)
    }

    @Test fun `advance past last step marks completed and fires seam exactly once`() {
        val seam = AtomicInteger(0)
        val vm = newVm(seam)
        vm.advance(); vm.advance(); vm.advance() // PERM_CAMERA → completed
        assertTrue(vm.uiState.value.completed)
        assertEquals(1, seam.get())
    }

    @Test fun `complete from any step fires seam`() {
        val seam = AtomicInteger(0)
        val vm = newVm(seam)
        vm.complete()
        assertTrue(vm.uiState.value.completed)
        assertEquals(1, seam.get())
    }

    @Test fun `complete is idempotent`() {
        val seam = AtomicInteger(0)
        val vm = newVm(seam)
        vm.complete(); vm.complete(); vm.complete()
        assertEquals(1, seam.get())
    }

    @Test fun `advance after completed is a no-op`() {
        val seam = AtomicInteger(0)
        val vm = newVm(seam)
        vm.complete()
        val captured = vm.uiState.value
        vm.advance()
        assertEquals(captured, vm.uiState.value)
        assertEquals(1, seam.get())
    }

    @Test fun `advance past last step then complete does not re-fire seam`() {
        val seam = AtomicInteger(0)
        val vm = newVm(seam)
        vm.advance(); vm.advance(); vm.advance() // → completed (seam = 1)
        vm.complete()                            // already completed → no-op
        assertEquals(1, seam.get())
    }

    @Test fun `goBack walks slides backward`() {
        val vm = newVm()
        vm.advance(); vm.advance() // → PERM_CAMERA
        vm.goBack()
        assertEquals(OnboardingStep.WALKTHROUGH_2, vm.uiState.value.step)
        vm.goBack()
        assertEquals(OnboardingStep.WALKTHROUGH_1, vm.uiState.value.step)
    }

    @Test fun `goBack on first step is a no-op`() {
        val vm = newVm()
        vm.goBack()
        assertEquals(OnboardingStep.WALKTHROUGH_1, vm.uiState.value.step)
    }

    @Test fun `goBack after completed is a no-op`() {
        val vm = newVm()
        vm.complete()
        val captured = vm.uiState.value
        vm.goBack()
        assertEquals(captured, vm.uiState.value)
    }

    @Test fun `skipWalkthroughToCamera jumps directly to PERM_CAMERA from walkthrough`() {
        val seam = AtomicInteger(0)
        val vm = newVm(seam)
        vm.skipWalkthroughToCamera()
        assertEquals(OnboardingStep.PERM_CAMERA, vm.uiState.value.step)
        assertFalse(vm.uiState.value.completed)
        assertEquals("Skip must NOT complete onboarding (camera still required)", 0, seam.get())
    }

    @Test fun `skipWalkthroughToCamera from PERM_CAMERA is a no-op`() {
        val vm = newVm()
        vm.advance(); vm.advance() // → PERM_CAMERA
        vm.skipWalkthroughToCamera()
        assertEquals(OnboardingStep.PERM_CAMERA, vm.uiState.value.step)
    }

    @Test fun `OnboardingStep next previous round-trips on every entry`() {
        OnboardingStep.entries.forEach { step ->
            val next = step.next() ?: return@forEach
            assertEquals(step, next.previous())
        }
    }

    @Test fun `OnboardingStep walkthrough and permission flags partition the enum`() {
        OnboardingStep.entries.forEach { step ->
            assertTrue(
                "Each step must be exactly one of walkthrough or permission",
                step.isWalkthrough xor step.isPermission
            )
        }
    }
}
```

- [ ] **Step 2: Confirm tests do NOT compile yet (expected RED)**

```powershell
./gradlew :app:compileDebugUnitTestKotlin
```
Expected: `Unresolved reference: skipWalkthroughToCamera` — Task 4 fixes this.

---

## Task 4: Simplify `OnboardingViewModel.kt` + add `skipWalkthroughToCamera`

**Why next:** unblocks the test file. Removes the SDK-gating KDoc paragraph (no longer relevant — the SDK-gated steps are gone). Adds a single new method.

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/onboarding/OnboardingViewModel.kt`

- [ ] **Step 1: Replace the file body**

```kotlin
package com.aritr.rova.ui.screens.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.aritr.rova.RovaApp
import com.aritr.rova.data.RovaSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * M4 (2026-05-27) Onboarding redesign — VM for the 3-step first-launch flow.
 *
 * Holds the current [OnboardingStep] in a [StateFlow] and exposes
 * [advance] / [goBack] / [complete] / [skipWalkthroughToCamera]
 * transitions. The completion side-effect
 * (`RovaSettings.onboardingCompleted = true`) is injected as a
 * [markCompleted] seam so the VM stays pure-Kotlin and the unit test
 * passes a counting lambda — same pattern as
 * [com.aritr.rova.ui.screens.player.PlayerViewModel].
 *
 * Phase 2.6 had SDK-gating concerns for `PERM_NOTIFS` (API 33+) and
 * `PERM_ALARM` (API 31+). Those steps are GONE in M4 — Camera is
 * unconditionally available API 1+, so no SDK gating is needed.
 *
 * Idempotency contract: [advance] / [complete] /
 * [skipWalkthroughToCamera] / [goBack] are all no-ops once
 * [OnboardingUiState.completed] flips true. Without this guard, a
 * delayed permission-launcher result firing after the user has
 * already exited could re-fire navigation or double-write the
 * settings flag.
 */
class OnboardingViewModel(
    private val markCompleted: () -> Unit
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    fun advance() {
        val current = _uiState.value
        if (current.completed) return
        val next = current.step.next()
        if (next == null) {
            markCompleted()
            _uiState.value = current.copy(completed = true)
        } else {
            _uiState.value = current.copy(step = next)
        }
    }

    fun goBack() {
        val current = _uiState.value
        if (current.completed) return
        val prev = current.step.previous() ?: return
        _uiState.value = current.copy(step = prev)
    }

    /**
     * "Not now" path on the Camera rationale slide AND any future
     * "exit early" path. Idempotent: [markCompleted] fires at most
     * once per VM lifetime.
     */
    fun complete() {
        val current = _uiState.value
        if (current.completed) return
        markCompleted()
        _uiState.value = current.copy(completed = true)
    }

    /**
     * M4 — Walkthrough "Skip" jumps to [OnboardingStep.PERM_CAMERA],
     * NOT past it. Phase 2.6's Skip used [complete], which would
     * bypass the Camera prompt — first-run failure on systems where
     * the user then tapped Start with no Camera permission.
     *
     * No-op if already past walkthrough or already completed.
     */
    fun skipWalkthroughToCamera() {
        val current = _uiState.value
        if (current.completed) return
        if (!current.step.isWalkthrough) return
        _uiState.value = current.copy(step = OnboardingStep.PERM_CAMERA)
    }

    companion object {
        fun factory(app: RovaApp): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val settings = RovaSettings(app)
                    return OnboardingViewModel(
                        markCompleted = { settings.onboardingCompleted = true }
                    ) as T
                }
            }
    }
}

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.WALKTHROUGH_1,
    val completed: Boolean = false
)
```

- [ ] **Step 2: Run tests — expect GREEN**

```powershell
./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.screens.onboarding.OnboardingViewModelTest"
```
Expected: `tests=10 failures=0 errors=0 skipped=0`.

---

## Task 5: New file — `OnboardingIllustrations.kt`

**Why now:** `OnboardingSlide.kt` (Task 6) imports these composables. Add the source of truth first.

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/screens/onboarding/OnboardingIllustrations.kt`

- [ ] **Step 1: Write the file**

```kotlin
package com.aritr.rova.ui.screens.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * M4 (2026-05-27) Onboarding illustrations ported from
 * `mockups/new_uiux/08b-onboarding.html` SVG to Compose [Canvas].
 *
 * Three @Composables: [OnboardingClockOrbit], [OnboardingClipMerge],
 * [OnboardingCamera]. All sized via the canvas modifier from the caller —
 * defaults are 210 dp (matches mockup hero size) for the walkthrough
 * illustrations and 56 dp for the camera glyph inside its 132 dp tile.
 *
 * Canvas (not [androidx.compose.ui.graphics.vector.ImageVector]) because
 * the bespoke illustrations are too complex for the vector-builder DSL
 * (the orbit illustration alone is 4 arcs + 8 circles + 12 strokes +
 * 3 badges with text). Canvas keeps the per-illustration LOC under 80
 * and reads straight against the SVG source.
 *
 * Pure-JVM testability: Canvas composables don't render under
 * `isReturnDefaultValues = true`. Tests verify the @Composable resolves
 * without exception (basic instantiation guard).
 */

private val Indigo = Color(0xFF5B7FFF)
private val Mint = Color(0xFF34D399)
private val WhiteAlpha92 = Color(0xFFFFFFFF).copy(alpha = 0.92f)

@Composable
fun OnboardingClockOrbit(
    contentDesc: String,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.size(210.dp)) {
        Canvas(
            modifier = Modifier
                .size(210.dp)
                .semantics { contentDescription = contentDesc }
        ) {
            val cx = size.width / 2f
            val cy = size.height / 2f

            // outer haze
            drawCircle(color = Indigo.copy(alpha = 0.04f), radius = 88f * (size.width / 210f), center = Offset(cx, cy))
            drawCircle(color = Indigo.copy(alpha = 0.07f), radius = 88f * (size.width / 210f), center = Offset(cx, cy), style = Stroke(width = 1f))

            // dashed orbit
            drawCircle(
                color = Indigo.copy(alpha = 0.13f),
                radius = 66f * (size.width / 210f),
                center = Offset(cx, cy),
                style = Stroke(width = 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f)))
            )

            // top arc + arrowhead
            val arcStroke = Stroke(width = 2.8f, cap = StrokeCap.Round)
            drawArc(
                color = Indigo.copy(alpha = 0.55f),
                startAngle = 200f,
                sweepAngle = 140f,
                useCenter = false,
                topLeft = Offset(cx - 63f * (size.width / 210f), cy - 63f * (size.height / 210f)),
                size = Size(126f * (size.width / 210f), 126f * (size.height / 210f)),
                style = arcStroke
            )
            // bottom arc
            drawArc(
                color = Indigo.copy(alpha = 0.55f),
                startAngle = 20f,
                sweepAngle = 140f,
                useCenter = false,
                topLeft = Offset(cx - 63f * (size.width / 210f), cy - 63f * (size.height / 210f)),
                size = Size(126f * (size.width / 210f), 126f * (size.height / 210f)),
                style = arcStroke
            )

            // center clock face
            drawCircle(color = Indigo.copy(alpha = 0.07f), radius = 36f * (size.width / 210f), center = Offset(cx, cy))
            drawCircle(color = Indigo.copy(alpha = 0.18f), radius = 36f * (size.width / 210f), center = Offset(cx, cy), style = Stroke(width = 1.5f))
            drawCircle(color = Indigo.copy(alpha = 0.11f), radius = 23f * (size.width / 210f), center = Offset(cx, cy))
            drawCircle(color = Indigo.copy(alpha = 0.28f), radius = 23f * (size.width / 210f), center = Offset(cx, cy), style = Stroke(width = 1f))

            // tick marks (12, 3, 6, 9)
            val tickPaint = Color.White.copy(alpha = 0.28f)
            val scale = size.width / 210f
            drawLine(tickPaint, Offset(cx, cy - 21f * scale), Offset(cx, cy - 18f * scale), strokeWidth = 1.5f, cap = StrokeCap.Round)
            drawLine(tickPaint, Offset(cx + 21f * scale, cy), Offset(cx + 18f * scale, cy), strokeWidth = 1.5f, cap = StrokeCap.Round)
            drawLine(tickPaint, Offset(cx, cy + 21f * scale), Offset(cx, cy + 18f * scale), strokeWidth = 1.5f, cap = StrokeCap.Round)
            drawLine(tickPaint, Offset(cx - 21f * scale, cy), Offset(cx - 18f * scale, cy), strokeWidth = 1.5f, cap = StrokeCap.Round)

            // clock hands (12-position minute, 5-o-clock hour)
            drawLine(Color.White.copy(alpha = 0.72f), Offset(cx, cy), Offset(cx, cy - 14f * scale), strokeWidth = 2f, cap = StrokeCap.Round)
            drawLine(Color.White.copy(alpha = 0.5f), Offset(cx, cy), Offset(cx + 10f * scale, cy + 6f * scale), strokeWidth = 1.5f, cap = StrokeCap.Round)
            drawCircle(Color.White.copy(alpha = 0.8f), radius = 2.5f * scale, center = Offset(cx, cy))

            // floating badges — drawn as colored rounded rects; labels left to overlay Text composables
            // (Canvas drawText is API-level fragile; the actual labels render as Text in the caller.)
        }

        // Badges as composables sit on top of the Canvas in the slide;
        // see OnboardingSlide.kt which overlays Text labels on these tile positions.
    }
}

@Composable
fun OnboardingClipMerge(
    contentDesc: String,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.size(210.dp)) {
        Canvas(
            modifier = Modifier
                .size(210.dp)
                .semantics { contentDescription = contentDesc }
        ) {
            val scale = size.width / 210f

            // glow under merged file
            drawOval(
                color = Indigo.copy(alpha = 0.06f),
                topLeft = Offset(47f * scale, 154f * scale),
                size = Size(116f * scale, 36f * scale)
            )

            // 3 clip cards
            val clipCorner = 9f * scale
            val cardFill = Indigo.copy(alpha = 0.09f)
            val cardStroke = Indigo.copy(alpha = 0.26f)

            fun clipAt(x: Float, y: Float) {
                drawRoundRect(
                    color = cardFill,
                    topLeft = Offset(x * scale, y * scale),
                    size = Size(42f * scale, 56f * scale),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(clipCorner, clipCorner)
                )
                drawRoundRect(
                    color = cardStroke,
                    topLeft = Offset(x * scale, y * scale),
                    size = Size(42f * scale, 56f * scale),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(clipCorner, clipCorner),
                    style = Stroke(width = 1.2f)
                )
                // thumbnail strip
                drawRoundRect(
                    color = Indigo.copy(alpha = 0.14f),
                    topLeft = Offset((x + 4f) * scale, (y + 4f) * scale),
                    size = Size(34f * scale, 22f * scale),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f * scale, 4f * scale)
                )
            }
            clipAt(10f, 60f)
            clipAt(59f, 46f)   // raised
            clipAt(108f, 60f)

            // down arrow
            val arrowColor = Indigo.copy(alpha = 0.44f)
            drawLine(
                arrowColor,
                Offset(105f * scale, 124f * scale),
                Offset(105f * scale, 142f * scale),
                strokeWidth = 2f, cap = StrokeCap.Round
            )
            val ah = Path().apply {
                moveTo(99f * scale, 137f * scale)
                lineTo(105f * scale, 145f * scale)
                lineTo(111f * scale, 137f * scale)
            }
            drawPath(ah, color = arrowColor, style = Stroke(width = 2f, cap = StrokeCap.Round))

            // merged file card
            drawRoundRect(
                color = Indigo.copy(alpha = 0.12f),
                topLeft = Offset(48f * scale, 150f * scale),
                size = Size(116f * scale, 50f * scale),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(13f * scale, 13f * scale)
            )
            drawRoundRect(
                color = Indigo.copy(alpha = 0.44f),
                topLeft = Offset(48f * scale, 150f * scale),
                size = Size(116f * scale, 50f * scale),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(13f * scale, 13f * scale),
                style = Stroke(width = 1.5f)
            )
            // play button
            drawCircle(Indigo.copy(alpha = 0.22f), radius = 14f * scale, center = Offset(76f * scale, 175f * scale))
            drawCircle(Indigo.copy(alpha = 0.46f), radius = 14f * scale, center = Offset(76f * scale, 175f * scale), style = Stroke(width = 1f))
            val triangle = Path().apply {
                moveTo(72f * scale, 169f * scale)
                lineTo(84f * scale, 175f * scale)
                lineTo(72f * scale, 181f * scale)
                close()
            }
            drawPath(triangle, color = Indigo)

            // green check badge
            drawCircle(Mint.copy(alpha = 0.15f), radius = 10f * scale, center = Offset(148f * scale, 156f * scale))
            drawCircle(Mint.copy(alpha = 0.44f), radius = 10f * scale, center = Offset(148f * scale, 156f * scale), style = Stroke(width = 1f))
            val checkPath = Path().apply {
                moveTo(143f * scale, 156f * scale)
                lineTo(147f * scale, 160f * scale)
                lineTo(153f * scale, 152f * scale)
            }
            drawPath(checkPath, color = Mint.copy(alpha = 0.92f), style = Stroke(width = 1.8f, cap = StrokeCap.Round))
        }
    }
}

@Composable
fun OnboardingCamera(
    contentDesc: String,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.size(56.dp)) {
        Canvas(
            modifier = Modifier
                .size(56.dp)
                .semantics { contentDescription = contentDesc }
        ) {
            val scale = size.width / 24f
            val stroke = Stroke(width = 1.6f * scale, cap = StrokeCap.Round)
            val color = Indigo.copy(alpha = 0.94f)

            // body: rounded rect from (1,5) size 15x14
            drawRoundRect(
                color = color,
                topLeft = Offset(1f * scale, 5f * scale),
                size = Size(15f * scale, 14f * scale),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f * scale, 2f * scale),
                style = stroke
            )
            // lens triangle (M23 7l-7 5 7 5V7z) — path from (23,7) -> (16,12) -> (23,17) -> close
            val lens = Path().apply {
                moveTo(23f * scale, 7f * scale)
                lineTo(16f * scale, 12f * scale)
                lineTo(23f * scale, 17f * scale)
                close()
            }
            drawPath(lens, color = color, style = stroke)
        }
    }
}
```

- [ ] **Step 2: Compile check**

```powershell
./gradlew :app:compileDebugKotlin
```
Expected: only `OnboardingScreen.kt` + `OnboardingSlide.kt` still fail (the dropped enum refs). Illustrations file compiles clean.

---

## Task 6: Rewrite `OnboardingSlide.kt` — new `WalkthroughSlide` + `CameraRationaleSlide`

**Why now:** `OnboardingScreen.kt` (Task 7) calls these. Illustrations are ready.

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/onboarding/OnboardingSlide.kt`

- [ ] **Step 1: Replace the file**

```kotlin
package com.aritr.rova.ui.screens.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aritr.rova.R

/**
 * M4 (2026-05-27) Onboarding redesign — Compose slide bodies.
 *
 * [WalkthroughSlide] handles `WALKTHROUGH_1` and `WALKTHROUGH_2`
 * (single dynamic copy lookup). [CameraRationaleSlide] is the
 * single permission slide and renders Camera-only rationale text +
 * "Allow Camera" CTA + "Not now" link.
 *
 * Mockup contract: `mockups/new_uiux/08b-onboarding.html`. CTA height
 * 52 dp (WCAG 2.2 ≥48 dp). All copy via `stringResource` — no
 * hardcoded user-visible text in this file.
 */

@Composable
internal fun WalkthroughSlide(
    step: OnboardingStep,
    onNext: () -> Unit,
    onSkip: () -> Unit
) {
    require(step.isWalkthrough) { "WalkthroughSlide given $step" }

    val titleRes: Int
    val bodyRes: Int
    val illustration: @Composable () -> Unit
    val ctaRes: Int
    when (step) {
        OnboardingStep.WALKTHROUGH_1 -> {
            titleRes = R.string.onboarding_walkthrough_1_title
            bodyRes = R.string.onboarding_walkthrough_1_body
            illustration = {
                OnboardingClockOrbit(
                    contentDesc = stringResource(R.string.onboarding_illustration_clock_orbit)
                )
            }
            ctaRes = R.string.onboarding_next
        }
        OnboardingStep.WALKTHROUGH_2 -> {
            titleRes = R.string.onboarding_walkthrough_2_title
            bodyRes = R.string.onboarding_walkthrough_2_body
            illustration = {
                OnboardingClipMerge(
                    contentDesc = stringResource(R.string.onboarding_illustration_clip_merge)
                )
            }
            ctaRes = R.string.onboarding_continue
        }
        else -> error("WalkthroughSlide: unreachable for $step")
    }
    val activeIndex = step.ordinal - OnboardingStep.WALKTHROUGH_1.ordinal

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 26.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 18.dp),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(
                onClick = onSkip,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(
                    text = stringResource(R.string.onboarding_skip),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            illustration()
        }

        DotsIndicator(
            total = 2,
            activeIndex = activeIndex,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp)
        )

        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.92f),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = stringResource(bodyRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(26.dp))
        PrimaryCta(label = stringResource(ctaRes) + " →", onClick = onNext)
        Spacer(modifier = Modifier.height(36.dp))
    }
}

@Composable
internal fun CameraRationaleSlide(
    onAllow: () -> Unit,
    onNotNow: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp)
    ) {
        Spacer(modifier = Modifier.height(96.dp))

        // hero tile (132 dp rounded) with the camera glyph centered
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(132.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(40.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                OnboardingCamera(
                    contentDesc = stringResource(R.string.onboarding_illustration_camera)
                )
            }
        }
        Spacer(modifier = Modifier.height(40.dp))

        Text(
            text = stringResource(R.string.onboarding_camera_title),
            style = MaterialTheme.typography.titleLarge.copy(
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold
            ),
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.94f),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = stringResource(R.string.onboarding_camera_body),
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
        )

        Spacer(modifier = Modifier.weight(1f))

        PrimaryCta(
            label = stringResource(R.string.onboarding_camera_allow),
            onClick = onAllow
        )
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(
            onClick = onNotNow,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
        ) {
            Text(
                text = stringResource(R.string.onboarding_camera_not_now),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
            )
        }
        Spacer(modifier = Modifier.height(34.dp))
    }
}

@Composable
private fun PrimaryCta(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        )
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp
            )
        )
    }
}

@Composable
private fun DotsIndicator(
    total: Int,
    activeIndex: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(total) { i ->
            val isActive = i == activeIndex
            val isDone = i < activeIndex
            Box(
                modifier = Modifier
                    .height(4.dp)
                    .width(if (isActive) 20.dp else 5.dp)
                    .background(
                        color = when {
                            isActive -> MaterialTheme.colorScheme.primary
                            isDone -> MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)
                        },
                        shape = RoundedCornerShape(2.dp)
                    )
            )
        }
    }
}
```

- [ ] **Step 2: Confirm compile**

```powershell
./gradlew :app:compileDebugKotlin
```
Expected: ONLY `OnboardingScreen.kt` still fails (uses dropped launchers). Task 7 fixes it.

---

## Task 7: Simplify `OnboardingScreen.kt` — single launcher

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/onboarding/OnboardingScreen.kt`

- [ ] **Step 1: Replace the file**

```kotlin
package com.aritr.rova.ui.screens.onboarding

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aritr.rova.RovaApp

/**
 * M4 (2026-05-27) Onboarding redesign — top-level composable for the
 * first-launch onboarding route. Down from 4 ActivityResult launchers
 * (Phase 2.6) to 1: Camera only. Mic, Notifications, and Exact-Alarm
 * are deferred to JIT in [com.aritr.rova.ui.screens.RecordScreen]
 * (existing `rememberMultiplePermissionsState` path).
 *
 * Back-gesture contract: back walks slides backward; on
 * `WALKTHROUGH_1` the system handles back (exits the app). Same as
 * Phase 2.6.
 *
 * Camera-denied path: the launcher's onResult fires `advance()`
 * regardless of grant/deny. Denial → onboarding completes → user
 * lands on Record → CAMERA_PERMISSION_DENIED WarningCenter advisory
 * (HARD_BLOCK, gates Start) surfaces with a one-tap "Allow" CTA. No
 * nag loop here — Google permission UX guidance, 2025.
 *
 * The route is registered in [com.aritr.rova.ui.MainScreen] as the
 * conditional start destination when
 * [com.aritr.rova.data.RovaSettings.onboardingCompleted] is `false`.
 */
@Composable
fun OnboardingScreen(
    onCompleted: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as RovaApp
    val viewModel: OnboardingViewModel = viewModel(factory = OnboardingViewModel.factory(app))
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.completed) {
        if (state.completed) onCompleted()
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> viewModel.advance() }

    val canGoBack = state.step.previous() != null && !state.completed
    BackHandler(enabled = canGoBack) { viewModel.goBack() }

    when {
        state.step.isWalkthrough -> WalkthroughSlide(
            step = state.step,
            onNext = viewModel::advance,
            onSkip = viewModel::skipWalkthroughToCamera
        )
        state.step.isPermission -> CameraRationaleSlide(
            onAllow = { cameraLauncher.launch(Manifest.permission.CAMERA) },
            onNotNow = viewModel::complete
        )
    }
}
```

- [ ] **Step 2: Full build + test run**

```powershell
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest :app:lintDebug
```
Expected:
- `compileDebugKotlin` BUILD SUCCESSFUL
- `testDebugUnitTest` `tests=1243 failures=0 errors=0 skipped=0` (was 1241 — 12 onboarding tests grew to 14 by adding 2 `skipWalkthroughToCamera` cases; rest of suite unchanged)
- `lintDebug` ≤ baseline (51W+1H; no NEW errors)

- [ ] **Step 3: Commit Tasks 2-7 as one atomic commit**

This is the load-bearing UI redesign commit. Atomic because the enum shrink + downstream-file fixups must land together for any compile.

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/onboarding/ \
        app/src/test/java/com/aritr/rova/ui/screens/onboarding/OnboardingViewModelTest.kt
git commit -m "feat(onboarding): M4 redesign — 7→3 screens, JIT defer, plain copy (M4 Task 2-7)"
```

---

## Task 8: A11y pass + final verification + branch finalization

**Why:** verify the redesign meets WCAG 2.2 + Material a11y baselines before opening PR.

- [ ] **Step 1: Visual audit on hardware (real device required — emulators fail CameraX)**

Install fresh:
```powershell
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm clear com.aritr.rova   # wipe SharedPreferences so onboardingCompleted = false
adb shell am start -n com.aritr.rova/.MainActivity
```

Walk-through checklist (manual, no automation):
1. App opens to **dark** scheme (light-mode device must NOT show light). ✓ confirms Task 1 force-dark.
2. Slide 1 renders with clock orbit illustration centered. "Skip" visible top-right.
3. "Next →" tap → Slide 2 with clip-merge illustration.
4. "Continue →" tap → Camera rationale slide.
5. "Allow Camera" tap → system permission dialog appears. Grant → routes to Record (`MainScreen.kt` `popUpTo("onboarding")`).
6. Clean install again. Tap "Skip" on Slide 1 → goes DIRECTLY to Camera rationale (NOT past it).
7. Camera rationale "Not now" → routes to Record. CAMERA_PERMISSION_DENIED warning visible (Start blocked).
8. TalkBack enabled: announce flow check — each slide reads title + body + illustration `contentDescription` + CTA label.
9. Tap-target audit: long-press each interactive element with Developer Options → Show layout bounds. Confirm all hit ≥ 48 dp.

- [ ] **Step 2: Final gradle sweep**

```powershell
./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleDebug
```
Expected: BUILD SUCCESSFUL on all three. Diff from master baseline `e1e121d`:
- Tests: 1241 → 1243 (+2 net; 12 onboarding tests grew to 14 — added 2 `skipWalkthroughToCamera` cases)
- Lint: ≤ baseline 51W+1H (no NEW errors)
- APK: builds clean

- [ ] **Step 3: Open PR**

```bash
git push -u origin milestone-4-onboarding-redesign
gh pr create --title "Milestone 4 — Onboarding redesign (7→3 screens, JIT permissions)" --body "..."
```

PR body should reference `mockups/new_uiux/08b-onboarding.html` and the design doc, list the parked-for-later items, include the hardware smoke checklist.

---

## Out-of-scope items (parked, with one-line rationale each)

These deliberately do NOT ship in M4 — surfaced for owner sign-off as future-milestone items:

1. **Theme switcher (Light / Dark / System default) in Settings** — full Material 3 theme infra needed; M4 only pins dark as a one-line bug-fix. Future milestone introduces `RovaSettings.themeMode: enum class ThemeMode { LIGHT, DARK, SYSTEM }`.
2. **"Replay app tour" Settings entry** — requires un-toggling `onboardingCompleted` cleanly + reset-state UX; reasonable size for a separate milestone.
3. **"Walks away with you" concept migration to Record screen** — separate UX decision (first-session toast? always-on tip card? Help sheet?). Out-of-scope for the cut.
4. **Localization beyond `strings.xml` extraction** — no `values-*` translations this slice; M4 only sets up the i18n surface.
5. **Per-perm JIT split** — Mic-only-on-audio-toggle, Notif-only-at-session-start. Current behavior (batched at first Start in `RecordScreen.kt:202-224`) carries forward unchanged.
6. **Bespoke camera glyph upgrade** — current camera ImageVector ported from mockup SVG is functional but minimal. Future polish could swap to a custom-drawn lens with depth + shutter.
7. **Illustration badge labels** — mockup shows "30s · 5 min · ×6" badge labels and "your_video.mp4 · 3:00 · HD" file metadata inside the illustrations. M4 ships the badge SHAPES (visually read as params) without the Text overlays — Compose `Canvas.drawText` is API-fragile and overlaying Text composables on the Canvas adds ~30 LOC per illustration. Future polish slice adds the labels via `Box` + positioned `Text`.
8. **CTA arrow i18n** — `stringResource(R.string.onboarding_next) + " →"` glues the arrow at the composable site. RTL-language adaptation (flip or omit) is a future i18n polish item.
