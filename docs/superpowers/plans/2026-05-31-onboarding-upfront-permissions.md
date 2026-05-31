# Up-front Onboarding Permissions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Request Camera, Microphone, and Notifications up-front during onboarding via per-permission rationale cards (reversing the M4 camera-only reduction); exact-alarm stays JIT.

**Architecture:** A pure `visibleOnboardingSteps(sdkInt)` seam returns the ordered steps for the device (Notifications only on API ≥ 33). It drives both the `HorizontalPager` and a list-based `OnboardingViewModel`, eliminating the API-33 page-jump and making step sequencing unit-testable. A generic `PermissionSlide` (restored/adapted from pre-M4 commit `21842f9`) renders all three cards, reusing the existing `PrimaryCta`/`DotsIndicator` and the `stringResource` i18n pattern. Each card has Allow + "Skip for now"; skipping advances. Camera remains enforced only at the existing WarningCenter Start-gate.

**Tech Stack:** Kotlin, Jetpack Compose (Material3), `androidx.activity.compose` ActivityResult launchers, JUnit4 JVM unit tests (`isReturnDefaultValues = true`).

**Spec:** `docs/superpowers/specs/2026-05-31-onboarding-upfront-permissions-design.md`
**Branch:** `feat/onboarding-upfront-permissions` (already created off `master`).

**Conventions reminder:**
- Commit messages end with `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.
- Pure-helper extraction pattern; JVM unit tests only (no Robolectric/instrumented).
- Do NOT edit any `check*` gate. Full gate is `./gradlew.bat :app:testDebugUnitTest :app:lintDebug`.
- Untracked `gradle_*.log` files and `nul` in repo root are ephemeral — never commit them.

---

### Task 1: Step enum + pure flow helpers

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/onboarding/OnboardingStep.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/screens/onboarding/OnboardingFlowTest.kt` (create)

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/aritr/rova/ui/screens/onboarding/OnboardingFlowTest.kt`:

```kotlin
package com.aritr.rova.ui.screens.onboarding

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure-JVM tests for the onboarding step list + SDK gating (no Android, no Compose). */
class OnboardingFlowTest {

    @Test fun `visibleOnboardingSteps on API 33+ includes notifications, in order`() {
        assertEquals(
            listOf(
                OnboardingStep.WALKTHROUGH_1,
                OnboardingStep.WALKTHROUGH_2,
                OnboardingStep.PERM_CAMERA,
                OnboardingStep.PERM_MIC,
                OnboardingStep.PERM_NOTIFS,
            ),
            visibleOnboardingSteps(33),
        )
    }

    @Test fun `visibleOnboardingSteps below API 33 omits notifications`() {
        assertEquals(
            listOf(
                OnboardingStep.WALKTHROUGH_1,
                OnboardingStep.WALKTHROUGH_2,
                OnboardingStep.PERM_CAMERA,
                OnboardingStep.PERM_MIC,
            ),
            visibleOnboardingSteps(30),
        )
    }

    @Test fun `permissionStepsOf filters to permission steps preserving order`() {
        assertEquals(
            listOf(OnboardingStep.PERM_CAMERA, OnboardingStep.PERM_MIC, OnboardingStep.PERM_NOTIFS),
            permissionStepsOf(visibleOnboardingSteps(33)),
        )
        assertEquals(
            listOf(OnboardingStep.PERM_CAMERA, OnboardingStep.PERM_MIC),
            permissionStepsOf(visibleOnboardingSteps(30)),
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.screens.onboarding.OnboardingFlowTest"`
Expected: FAIL — unresolved references `PERM_MIC`, `PERM_NOTIFS`, `visibleOnboardingSteps`, `permissionStepsOf`.

- [ ] **Step 3: Implement the enum + helpers**

Replace the whole body of `app/src/main/java/com/aritr/rova/ui/screens/onboarding/OnboardingStep.kt` with:

```kotlin
package com.aritr.rova.ui.screens.onboarding

import android.os.Build

/**
 * Steps in the first-launch onboarding flow.
 *
 * 2026-05-31 — Mic + Notifications restored as up-front permission cards
 * (reverses the M4 camera-only reduction; see
 * docs/superpowers/specs/2026-05-31-onboarding-upfront-permissions-design.md).
 * SCHEDULE_EXACT_ALARM stays JIT (no in-app dialog) — no PERM_ALARM step.
 *
 * Permission order is fixed per NEW_UI_BACKEND_REPLAN §Phase 2 row 2.6:
 * camera → mic → notifications. [visibleOnboardingSteps] is the single
 * source of truth for which steps render on a given device; the enum
 * `ordinal` only orders entries, navigation walks the visible list.
 */
enum class OnboardingStep {
    WALKTHROUGH_1,
    WALKTHROUGH_2,
    PERM_CAMERA,
    PERM_MIC,
    PERM_NOTIFS;

    fun next(): OnboardingStep? = entries.getOrNull(ordinal + 1)
    fun previous(): OnboardingStep? = entries.getOrNull(ordinal - 1)

    val isWalkthrough: Boolean get() = ordinal <= WALKTHROUGH_2.ordinal
    val isPermission: Boolean get() = !isWalkthrough
}

/**
 * Ordered onboarding steps visible on a device at [sdkInt].
 * PERM_NOTIFS is omitted below API 33 (POST_NOTIFICATIONS did not exist).
 * Pure seam — unit-tested in OnboardingFlowTest.
 */
fun visibleOnboardingSteps(sdkInt: Int): List<OnboardingStep> = buildList {
    add(OnboardingStep.WALKTHROUGH_1)
    add(OnboardingStep.WALKTHROUGH_2)
    add(OnboardingStep.PERM_CAMERA)
    add(OnboardingStep.PERM_MIC)
    if (sdkInt >= Build.VERSION_CODES.TIRAMISU) add(OnboardingStep.PERM_NOTIFS)
}

/** The permission steps within [visible], preserving order — drives "Step N of M" + dots. */
fun permissionStepsOf(visible: List<OnboardingStep>): List<OnboardingStep> =
    visible.filter { it.isPermission }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.screens.onboarding.OnboardingFlowTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/onboarding/OnboardingStep.kt app/src/test/java/com/aritr/rova/ui/screens/onboarding/OnboardingFlowTest.kt
git commit -m "feat(onboarding): restore mic+notifications steps + visibleOnboardingSteps seam"
```

---

### Task 2: List-based OnboardingViewModel

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/onboarding/OnboardingViewModel.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/screens/onboarding/OnboardingViewModelTest.kt:36-177` (rewrite for injected step list)

The VM currently walks the enum `ordinal`. It must walk an injected visible-step `List` so SDK-gated flows (4 vs 5 steps) and the pager agree. The existing 16 tests construct `OnboardingViewModel(markCompleted = …)` and assume a 3-step flow — they must be updated.

- [ ] **Step 1: Update the tests first (they will fail to compile, then fail, then pass)**

Replace the `newVm` helper and the step-count-dependent tests in `OnboardingViewModelTest.kt`. Set the helper to inject the API-33 list and add a `steps` parameter:

```kotlin
    private val fullSteps = listOf(
        OnboardingStep.WALKTHROUGH_1,
        OnboardingStep.WALKTHROUGH_2,
        OnboardingStep.PERM_CAMERA,
        OnboardingStep.PERM_MIC,
        OnboardingStep.PERM_NOTIFS,
    )

    private fun newVm(
        seam: AtomicInteger = AtomicInteger(0),
        steps: List<OnboardingStep> = fullSteps,
    ): OnboardingViewModel =
        OnboardingViewModel(steps = steps, markCompleted = { seam.incrementAndGet() })
```

Then update these specific tests to the 5-step flow (replace the matching existing methods verbatim):

```kotlin
    @Test fun `advance walks every step in order`() {
        val vm = newVm()
        vm.advance()
        assertEquals(OnboardingStep.WALKTHROUGH_2, vm.uiState.value.step)
        vm.advance()
        assertEquals(OnboardingStep.PERM_CAMERA, vm.uiState.value.step)
        vm.advance()
        assertEquals(OnboardingStep.PERM_MIC, vm.uiState.value.step)
        vm.advance()
        assertEquals(OnboardingStep.PERM_NOTIFS, vm.uiState.value.step)
    }

    @Test fun `advance past last step marks completed and fires seam exactly once`() {
        val seam = AtomicInteger(0)
        val vm = newVm(seam)
        repeat(fullSteps.size) { vm.advance() } // last advance completes
        assertTrue(vm.uiState.value.completed)
        assertEquals(1, seam.get())
    }

    @Test fun `advance past last step then complete does not re-fire seam`() {
        val seam = AtomicInteger(0)
        val vm = newVm(seam)
        repeat(fullSteps.size) { vm.advance() } // → completed (seam = 1)
        vm.complete()                           // already completed → no-op
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

    @Test fun `below API 33 the flow has no notifications step`() {
        val shortSteps = listOf(
            OnboardingStep.WALKTHROUGH_1,
            OnboardingStep.WALKTHROUGH_2,
            OnboardingStep.PERM_CAMERA,
            OnboardingStep.PERM_MIC,
        )
        val seam = AtomicInteger(0)
        val vm = newVm(seam, shortSteps)
        repeat(shortSteps.size) { vm.advance() }
        assertTrue(vm.uiState.value.completed)
        assertEquals(1, seam.get())
    }
```

Leave the other tests as-is (`complete`, `complete is idempotent`, `advance after completed is a no-op`, `goBack on first step`, `goBack after completed`, `skipWalkthroughToCamera` ×2, `setStep` ×2, `next previous round-trips`, `partition`). They remain valid — `skipWalkthroughToCamera` still lands on `PERM_CAMERA`, and the enum partition/round-trip tests cover all 5 entries.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.screens.onboarding.OnboardingViewModelTest"`
Expected: COMPILE FAIL — `OnboardingViewModel` has no `steps` parameter.

- [ ] **Step 3: Implement list-based VM**

Replace the class body in `OnboardingViewModel.kt` (keep the package + imports; add no new imports). Replace the constructor and the five transition methods + state init:

```kotlin
class OnboardingViewModel(
    private val steps: List<OnboardingStep>,
    private val markCompleted: () -> Unit
) : ViewModel() {

    /** Visible steps for this device — exposed so OnboardingScreen's pager agrees with the VM. */
    val visibleSteps: List<OnboardingStep> get() = steps

    private val _uiState = MutableStateFlow(OnboardingUiState(step = steps.first()))
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    fun advance() {
        val current = _uiState.value
        if (current.completed) return
        val idx = steps.indexOf(current.step)
        if (idx == steps.lastIndex) {
            markCompleted()
            _uiState.value = current.copy(completed = true)
        } else {
            _uiState.value = current.copy(step = steps[idx + 1])
        }
    }

    fun goBack() {
        val current = _uiState.value
        if (current.completed) return
        val idx = steps.indexOf(current.step)
        if (idx <= 0) return
        _uiState.value = current.copy(step = steps[idx - 1])
    }

    /**
     * Walkthrough "Skip" jumps to the first permission step (Camera), NOT past it —
     * camera is still enforced at the WarningCenter Start-gate. No-op if already past
     * walkthrough or completed.
     */
    fun skipWalkthroughToCamera() {
        val current = _uiState.value
        if (current.completed) return
        if (!current.step.isWalkthrough) return
        val firstPerm = steps.firstOrNull { it.isPermission } ?: return
        _uiState.value = current.copy(step = firstPerm)
    }

    /** Pager swipe-settle write-back. Idempotent; ignores unknown steps and post-completion swipes. */
    fun setStep(step: OnboardingStep) {
        val current = _uiState.value
        if (current.completed) return
        if (current.step == step) return
        if (step !in steps) return
        _uiState.value = current.copy(step = step)
    }

    fun complete() {
        val current = _uiState.value
        if (current.completed) return
        markCompleted()
        _uiState.value = current.copy(completed = true)
    }

    companion object {
        fun factory(app: RovaApp): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val settings = RovaSettings(app)
                    return OnboardingViewModel(
                        steps = visibleOnboardingSteps(android.os.Build.VERSION.SDK_INT),
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

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.screens.onboarding.OnboardingViewModelTest"`
Expected: PASS (17 tests — 16 existing minus the removed assumptions, plus the new below-API-33 test).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/onboarding/OnboardingViewModel.kt app/src/test/java/com/aritr/rova/ui/screens/onboarding/OnboardingViewModelTest.kt
git commit -m "feat(onboarding): walk injected visible-step list in OnboardingViewModel"
```

---

### Task 3: String resources for the new cards

**Files:**
- Modify: `app/src/main/res/values/strings.xml:19-22` (camera block — add siblings after it)

- [ ] **Step 1: Add the resources**

In `app/src/main/res/values/strings.xml`, immediately after the existing `onboarding_camera_*` block (line ~22), add:

```xml
    <!-- 2026-05-31 — up-front permission cards (mic + notifications) -->
    <string name="onboarding_perm_step_label">Step %1$d of %2$d</string>
    <string name="onboarding_perm_required">Required</string>
    <string name="onboarding_perm_optional">Optional</string>
    <string name="onboarding_perm_recommended">Recommended</string>
    <string name="onboarding_perm_skip">Skip for now</string>

    <string name="onboarding_mic_title">Microphone</string>
    <string name="onboarding_mic_body">Records sound alongside your video. Skip and sessions will be video-only — no audio.</string>
    <string name="onboarding_mic_allow">Allow Microphone</string>
    <string name="onboarding_mic_callout">You can change this any time in Settings.</string>
    <string name="onboarding_illustration_mic">Microphone</string>

    <string name="onboarding_notifs_title">Stay in control</string>
    <string name="onboarding_notifs_body">A live notification lets you stop or check a recording without opening the app — even with the screen off.</string>
    <string name="onboarding_notifs_allow">Allow Notifications</string>
    <string name="onboarding_illustration_notifications">Notification bell</string>
```

The existing `onboarding_camera_not_now` string is no longer referenced after Task 5 (the camera card uses the shared `onboarding_perm_skip`). Leave it in place for now — removing it is unnecessary and a dangling unused string is not a lint error in this project. The camera card reuses `onboarding_camera_title`, `onboarding_camera_body`, `onboarding_camera_allow`, `onboarding_illustration_camera` (already present).

- [ ] **Step 2: Verify it compiles (resource processing)**

Run: `./gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (resources parse; no Kotlin references them yet).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/res/values/strings.xml
git commit -m "feat(onboarding): strings for mic + notifications permission cards"
```

---

### Task 4: Generic PermissionSlide

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/onboarding/OnboardingSlide.kt` (replace `CameraRationaleSlide` with generic `PermissionSlide`; restore `PermissionIconTile` + `CalloutCard`; reuse existing `PrimaryCta` + `DotsIndicator`)

- [ ] **Step 1: Replace `CameraRationaleSlide` with `PermissionSlide` + helpers**

In `OnboardingSlide.kt`, delete the entire `CameraRationaleSlide` composable (current lines 157-236) and insert the following. Also add the imports listed below to the existing import block.

Add imports:

```kotlin
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.aritr.rova.ui.theme.RovaTokens
import com.aritr.rova.ui.theme.RovaWarnings
```

Insert composables (replacing `CameraRationaleSlide`):

```kotlin
/**
 * 2026-05-31 — generic permission rationale card for PERM_CAMERA / PERM_MIC /
 * PERM_NOTIFS (replaces the M4 camera-only CameraRationaleSlide). Reuses the
 * file's [PrimaryCta] + [DotsIndicator]. [permIndex] / [permTotal] are the
 * card's position among the device's visible permission steps (so the eyebrow
 * + dots read "Step 2 of 3" on API 33+, "Step 2 of 2" below 33).
 *
 * Every card has Allow + "Skip for now"; skipping advances. Camera carries a
 * "Required" eyebrow but is still skippable here — it is enforced at the
 * WarningCenter Start-gate, not by trapping the user (spec §Decisions).
 * WCAG 2.2 AA (ADR-0020): title is a heading; CTA ≥48 dp; skip ≥48 dp.
 */
@Composable
internal fun PermissionSlide(
    step: OnboardingStep,
    permIndex: Int,
    permTotal: Int,
    onAllow: () -> Unit,
    onSkip: () -> Unit
) {
    require(step.isPermission) { "PermissionSlide given $step" }

    val titleRes: Int
    val bodyRes: Int
    val allowRes: Int
    val iconDescRes: Int
    val icon: ImageVector
    val accent: Color
    val severityWordRes: Int
    val calloutRes: Int?
    val calloutSeverity: Color
    when (step) {
        OnboardingStep.PERM_CAMERA -> {
            titleRes = R.string.onboarding_camera_title
            bodyRes = R.string.onboarding_camera_body
            allowRes = R.string.onboarding_camera_allow
            iconDescRes = R.string.onboarding_illustration_camera
            icon = Icons.Filled.Videocam
            accent = MaterialTheme.colorScheme.primary
            severityWordRes = R.string.onboarding_perm_required
            calloutRes = null
            calloutSeverity = RovaWarnings.soft
        }
        OnboardingStep.PERM_MIC -> {
            titleRes = R.string.onboarding_mic_title
            bodyRes = R.string.onboarding_mic_body
            allowRes = R.string.onboarding_mic_allow
            iconDescRes = R.string.onboarding_illustration_mic
            icon = Icons.Filled.Mic
            accent = Color(0xFF34D399)
            severityWordRes = R.string.onboarding_perm_optional
            calloutRes = R.string.onboarding_mic_callout
            calloutSeverity = RovaWarnings.soft
        }
        OnboardingStep.PERM_NOTIFS -> {
            titleRes = R.string.onboarding_notifs_title
            bodyRes = R.string.onboarding_notifs_body
            allowRes = R.string.onboarding_notifs_allow
            iconDescRes = R.string.onboarding_illustration_notifications
            icon = Icons.Filled.Notifications
            accent = RovaWarnings.soft
            severityWordRes = R.string.onboarding_perm_recommended
            calloutRes = null
            calloutSeverity = RovaWarnings.soft
        }
        else -> error("PermissionSlide: unreachable for $step")
    }

    val eyebrow = (stringResource(R.string.onboarding_perm_step_label, permIndex + 1, permTotal) +
        " · " + stringResource(severityWordRes)).uppercase()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 24.dp)
    ) {
        Spacer(modifier = Modifier.height(58.dp))
        PermissionIconTile(icon = icon, accent = accent, contentDesc = stringResource(iconDescRes))
        Spacer(modifier = Modifier.height(26.dp))
        Text(
            text = eyebrow,
            style = RovaTokens.eyebrow.copy(letterSpacing = 1.6.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.titleLarge.copy(
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            ),
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.92f),
            modifier = Modifier.semantics { heading() }
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(bodyRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
        )
        if (calloutRes != null) {
            Spacer(modifier = Modifier.height(18.dp))
            CalloutCard(text = stringResource(calloutRes), severity = calloutSeverity)
        }

        Spacer(modifier = Modifier.weight(1f))

        DotsIndicator(
            total = permTotal,
            activeIndex = permIndex,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 22.dp)
        )

        PrimaryCta(label = stringResource(allowRes), onClick = onAllow)
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(
            onClick = onSkip,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = RovaTokens.minHitTarget)
        ) {
            Text(
                text = stringResource(R.string.onboarding_perm_skip),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
            )
        }
        Spacer(modifier = Modifier.height(34.dp))
    }
}

@Composable
private fun PermissionIconTile(icon: ImageVector, accent: Color, contentDesc: String) {
    Box(
        modifier = Modifier
            .size(70.dp)
            .background(
                color = accent.copy(alpha = 0.09f),
                shape = RoundedCornerShape(22.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDesc,
            tint = accent.copy(alpha = 0.90f),
            modifier = Modifier.size(34.dp)
        )
    }
}

@Composable
private fun CalloutCard(text: String, severity: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = severity.copy(alpha = 0.07f),
                shape = RoundedCornerShape(12.dp)
            )
            .padding(PaddingValues(horizontal = 14.dp, vertical = 12.dp))
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.WarningAmber,
                contentDescription = null,
                tint = severity.copy(alpha = 0.75f),
                modifier = Modifier
                    .size(14.dp)
                    .padding(top = 1.dp)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = severity.copy(alpha = 0.85f)
            )
        }
    }
}
```

Also update the file KDoc header (lines ~35-49): replace the sentence describing `CameraRationaleSlide` with: "[PermissionSlide] renders the Camera / Microphone / Notifications rationale cards (generic, driven by step)."

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew.bat :app:compileDebugKotlin`
Expected: FAIL — `CameraRationaleSlide` is still referenced by `OnboardingScreen.kt` (fixed in Task 5). Confirm the ONLY error is the unresolved `CameraRationaleSlide` reference in `OnboardingScreen.kt`, not anything inside `OnboardingSlide.kt`. If `OnboardingSlide.kt` itself has errors, fix them before proceeding.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/onboarding/OnboardingSlide.kt
git commit -m "feat(onboarding): generic PermissionSlide for camera/mic/notifications"
```

---

### Task 5: Wire OnboardingScreen — 3 launchers, list-driven pager

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/onboarding/OnboardingScreen.kt`

- [ ] **Step 1: Implement the multi-permission screen**

Replace the body of `OnboardingScreen.kt` from the launcher declaration through the `HorizontalPager` block. Add imports: `android.os.Build`, `androidx.compose.runtime.remember`. Full replacement of the function internals (keep the existing immersive-chrome `DisposableEffect` and `LaunchedEffect(state.completed)` exactly as they are):

```kotlin
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> viewModel.advance() }
    val micLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> viewModel.advance() }
    val notifLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> viewModel.advance() }

    val steps = viewModel.visibleSteps
    val permSteps = remember(steps) { permissionStepsOf(steps) }

    val pagerState = rememberPagerState(
        initialPage = steps.indexOf(state.step).coerceAtLeast(0),
        pageCount = { steps.size }
    )

    // Sync 1: VM step → pager.
    LaunchedEffect(state.step) {
        val target = steps.indexOf(state.step)
        if (target >= 0 && pagerState.currentPage != target) {
            pagerState.animateScrollToPage(target)
        }
    }

    // Sync 2: user swipe → VM (idempotent on equal step).
    LaunchedEffect(pagerState.currentPage, pagerState.isScrollInProgress) {
        if (!pagerState.isScrollInProgress) {
            viewModel.setStep(steps[pagerState.currentPage])
        }
    }

    val canGoBack = steps.indexOf(state.step) > 0 && !state.completed
    BackHandler(enabled = canGoBack) { viewModel.goBack() }

    HorizontalPager(state = pagerState) { pageIndex ->
        val pageStep = steps[pageIndex]
        when {
            pageStep.isWalkthrough -> WalkthroughSlide(
                step = pageStep,
                onNext = viewModel::advance,
                onSkip = viewModel::skipWalkthroughToCamera
            )
            pageStep.isPermission -> PermissionSlide(
                step = pageStep,
                permIndex = permSteps.indexOf(pageStep),
                permTotal = permSteps.size,
                onAllow = {
                    when (pageStep) {
                        OnboardingStep.PERM_CAMERA ->
                            cameraLauncher.launch(Manifest.permission.CAMERA)
                        OnboardingStep.PERM_MIC ->
                            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        OnboardingStep.PERM_NOTIFS ->
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                viewModel.advance()
                            }
                        else -> viewModel.advance()
                    }
                },
                onSkip = viewModel::advance
            )
        }
    }
```

Update the file KDoc header (lines ~24-28): replace "Down from 4 ActivityResult launchers (Phase 2.6) to 1: Camera only. Mic, Notifications, and Exact-Alarm are deferred to JIT…" with: "Three ActivityResult launchers (Camera, Mic, Notifications) requested up-front via per-permission cards (2026-05-31). SCHEDULE_EXACT_ALARM stays JIT — no in-app dialog exists. Step set is `viewModel.visibleSteps` (Notifications only on API 33+)."

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (no more `CameraRationaleSlide` reference; `PERM_MIC`/`PERM_NOTIFS`/`PermissionSlide`/`visibleSteps`/`permissionStepsOf` all resolve).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/onboarding/OnboardingScreen.kt
git commit -m "feat(onboarding): request camera+mic+notifications up-front via cards"
```

---

### Task 6: Full gate + CHANGELOG

**Files:**
- Modify: `CHANGELOG.md` (Unreleased → Changed)

- [ ] **Step 1: Run the full gate**

Run: `./gradlew.bat :app:testDebugUnitTest :app:lintDebug --no-daemon`
Expected: BUILD SUCCESSFUL. All JVM unit tests pass (incl. `OnboardingFlowTest`, `OnboardingViewModelTest`); all 25 custom `check*` tasks pass; lint clean. If lint flags a new issue in the touched files, fix the source (never edit a `check*` task).

- [ ] **Step 2: Add CHANGELOG entry**

Under `## [Unreleased]` → `### Changed` in `CHANGELOG.md`, add:

```markdown
- **Onboarding requests Camera, Microphone, and Notifications up-front** via per-permission rationale cards, reversing the M4 camera-only reduction. Each card has Allow + "Skip for now"; skipping still finishes onboarding (camera stays enforced at the WarningCenter Start-gate). Notifications card shows only on API 33+. Exact-alarm remains just-in-time (no in-app dialog exists). New pure `visibleOnboardingSteps(sdkInt)` seam drives the pager + ViewModel. Spec: `docs/superpowers/specs/2026-05-31-onboarding-upfront-permissions-design.md`.
```

- [ ] **Step 3: Commit**

```bash
git add CHANGELOG.md
git commit -m "docs(onboarding): CHANGELOG for up-front permissions"
```

- [ ] **Step 4: Manual device verification (record results; do not automate)**

On a real device, API 33+ (the project mandates real-device testing):
1. `adb shell pm clear com.aritr.rova` (true first-run; clears prior permission decisions).
2. Launch → confirm onboarding shows Walkthrough×2 → Camera → Mic → Notifications, each as a card.
3. Tap "Allow Camera" → confirm the **system permission dialog** appears (not App Settings).
4. Repeat for Mic and Notifications — each shows a system dialog.
5. Reach Record screen → confirm no scattered JIT permission dialogs for the granted ones; only `EXACT_ALARM` may surface (flat banner) and battery-optimization advisory.
6. Re-launch app → onboarding does NOT show again (`onboardingCompleted` persisted).
7. On a pre-API-33 device/emulator (if available): confirm the Notifications card is absent (4-step flow).

Note: idle preview being black after grant (#3) and camera re-init on tab switch (#5) are KNOWN, out-of-scope for this slice — do not treat as regressions here.

---

## Self-Review

**Spec coverage:**
- Flow 3→5 steps, notifications API-33-gated → Task 1 (`visibleOnboardingSteps`) + Task 2 (VM) + Task 5 (pager). ✓
- Per-permission cards, restore `PermissionSlide` → Task 4. ✓
- Allow + "Skip for now", skip advances, no onboarding trap → Task 4 (UI) + Task 5 (`onSkip = advance`). ✓
- Camera enforced only at Start-gate → unchanged WarningCenter; camera card skippable. ✓
- Exact-alarm stays JIT → no `PERM_ALARM`; documented in Task 1 enum KDoc + screen KDoc. ✓
- Pure seam testable → Task 1 `OnboardingFlowTest`; VM Task 2 tests. ✓
- "Step N of M" + dots from visible perm steps → `permissionStepsOf` + `permIndex`/`permTotal` in Tasks 4/5. ✓
- WCAG AA (ADR-0020) → CTA/skip ≥48 dp (`RovaTokens.minHitTarget`), title `heading()` semantics, icon contentDescription. ✓
- i18n via stringResource → Task 3. ✓
- No ADR/`check*` edits; full gate green → Task 6. ✓

**Placeholder scan:** No TBD/TODO; every code step shows complete code; every command has expected output. ✓

**Type consistency:** `visibleOnboardingSteps(sdkInt: Int): List<OnboardingStep>`, `permissionStepsOf(visible): List<OnboardingStep>`, `OnboardingViewModel(steps, markCompleted)` with `val visibleSteps`, `PermissionSlide(step, permIndex, permTotal, onAllow, onSkip)` — names/signatures identical across Tasks 1, 2, 4, 5. ✓
