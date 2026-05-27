package com.aritr.rova.ui.screens.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * M4 (2026-05-27) — pure-JVM tests for [OnboardingViewModel] after
 * the 7 → 3 step shrink. Test count: 16 (was 12 in Phase 2.6 — +2 for
 * `skipWalkthroughToCamera`, +2 for `setStep` HorizontalPager seam).
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
 *  - setStep (M4 owner-feedback) transitions to the specified step
 *  - setStep is a no-op once completed (HorizontalPager swipe-settle
 *    after onCompleted cannot regress state)
 *  - OnboardingStep next/previous round-trips on every entry
 *  - OnboardingStep walkthrough and permission flags partition the enum
 *
 * Same shape as [com.aritr.rova.ui.signals.ThermalHysteresisTest] —
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
        // Codex review 2026-05-27: advance to PERM_CAMERA before completing,
        // so the no-op assertion isn't trivially satisfied by also being on
        // WALKTHROUGH_1 (which has no previous step regardless of the guard).
        val vm = newVm()
        vm.advance(); vm.advance() // → PERM_CAMERA
        vm.complete()
        val captured = vm.uiState.value
        assertEquals(OnboardingStep.PERM_CAMERA, captured.step)
        assertTrue(captured.completed)
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

    // M4 (2026-05-27) owner-feedback follow-up — HorizontalPager seam.
    // Pager swipe settle drives state back into VM via setStep.

    @Test fun `setStep transitions to specified step`() {
        val vm = newVm()
        vm.setStep(OnboardingStep.PERM_CAMERA)
        assertEquals(OnboardingStep.PERM_CAMERA, vm.uiState.value.step)
        assertFalse(vm.uiState.value.completed)
    }

    @Test fun `setStep is no-op when completed`() {
        // A delayed swipe-settle that fires after onCompleted has already
        // navigated away must not regress state — guard mirrors advance/
        // goBack/skipWalkthroughToCamera/complete idempotency.
        val vm = newVm()
        vm.complete()
        val captured = vm.uiState.value
        vm.setStep(OnboardingStep.WALKTHROUGH_1)
        assertEquals(captured, vm.uiState.value)
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
