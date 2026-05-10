package com.aritr.rova.ui.screens.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase 2.6 — pure-JVM unit tests for [OnboardingViewModel].
 *
 * Covers the contract from the slice brief:
 *  - slide nav forward / back across all 7 steps
 *  - completion transition (advance past the last step + explicit
 *    [OnboardingViewModel.complete] from any step)
 *  - idempotent completion: calling [OnboardingViewModel.complete] or
 *    [OnboardingViewModel.advance] after the flag has flipped does
 *    not re-fire the markCompleted seam (which would re-fire the
 *    navigation callback in production)
 *
 * The VM accepts an injected [markCompleted] seam so the test counts
 * invocations directly via [AtomicInteger] — no Robolectric, no
 * Context fakes (those live in [com.aritr.rova.data.RovaSettingsTest]
 * and are not needed at the VM layer).
 */
class OnboardingViewModelTest {

    @Test fun `initial state is WALKTHROUGH_1 not completed`() {
        val (vm, _) = newVm()
        assertEquals(OnboardingStep.WALKTHROUGH_1, vm.uiState.value.step)
        assertFalse(vm.uiState.value.completed)
    }

    @Test fun `advance walks every step in order`() {
        val (vm, marks) = newVm()
        val seen = mutableListOf(vm.uiState.value.step)
        repeat(OnboardingStep.entries.size - 1) {
            vm.advance()
            seen += vm.uiState.value.step
        }
        assertEquals(OnboardingStep.entries.toList(), seen)
        assertFalse("advance() to last step should NOT mark completed yet", vm.uiState.value.completed)
        assertEquals(0, marks.get())
    }

    @Test fun `advance past last step marks completed and fires seam exactly once`() {
        val (vm, marks) = newVm()
        repeat(OnboardingStep.entries.size) { vm.advance() }
        assertTrue(vm.uiState.value.completed)
        assertEquals(1, marks.get())
    }

    @Test fun `complete from any step marks completed and fires seam`() {
        val (vm, marks) = newVm()
        vm.advance() // WALKTHROUGH_2
        vm.advance() // WALKTHROUGH_3
        vm.advance() // PERM_CAMERA
        vm.complete()
        assertTrue(vm.uiState.value.completed)
        assertEquals(1, marks.get())
    }

    @Test fun `complete is idempotent across repeated calls`() {
        val (vm, marks) = newVm()
        vm.complete()
        vm.complete()
        vm.complete()
        assertEquals(1, marks.get())
        assertTrue(vm.uiState.value.completed)
    }

    @Test fun `advance after completed is a no-op`() {
        val (vm, marks) = newVm()
        vm.complete()
        val afterComplete = vm.uiState.value
        vm.advance()
        assertEquals(afterComplete, vm.uiState.value)
        assertEquals(1, marks.get())
    }

    @Test fun `advance past last step then complete does not re-fire seam`() {
        val (vm, marks) = newVm()
        repeat(OnboardingStep.entries.size) { vm.advance() }
        vm.complete()
        assertEquals(1, marks.get())
    }

    @Test fun `goBack walks slides backward`() {
        val (vm, _) = newVm()
        vm.advance(); vm.advance(); vm.advance() // PERM_CAMERA
        assertEquals(OnboardingStep.PERM_CAMERA, vm.uiState.value.step)
        vm.goBack()
        assertEquals(OnboardingStep.WALKTHROUGH_3, vm.uiState.value.step)
        vm.goBack()
        assertEquals(OnboardingStep.WALKTHROUGH_2, vm.uiState.value.step)
    }

    @Test fun `goBack on first step is a no-op`() {
        val (vm, _) = newVm()
        vm.goBack()
        assertEquals(OnboardingStep.WALKTHROUGH_1, vm.uiState.value.step)
        assertFalse(vm.uiState.value.completed)
    }

    @Test fun `goBack after completed is a no-op`() {
        val (vm, marks) = newVm()
        vm.complete()
        val snapshot = vm.uiState.value
        vm.goBack()
        assertEquals(snapshot, vm.uiState.value)
        assertEquals(1, marks.get())
    }

    @Test fun `OnboardingStep next previous round-trips on every entry`() {
        for (step in OnboardingStep.entries) {
            val n = step.next()
            if (n != null) assertEquals(step, n.previous())
        }
        assertNull(OnboardingStep.WALKTHROUGH_1.previous())
        assertNull(OnboardingStep.PERM_ALARM.next())
        assertNotNull(OnboardingStep.WALKTHROUGH_3.next())
    }

    @Test fun `OnboardingStep walkthrough and permission flags partition the enum`() {
        val walkthroughs = OnboardingStep.entries.filter { it.isWalkthrough }
        val permissions = OnboardingStep.entries.filter { it.isPermission }
        assertEquals(3, walkthroughs.size)
        assertEquals(4, permissions.size)
        assertEquals(OnboardingStep.entries.toSet(), (walkthroughs + permissions).toSet())
    }

    private fun newVm(): Pair<OnboardingViewModel, AtomicInteger> {
        val marks = AtomicInteger(0)
        val vm = OnboardingViewModel(markCompleted = { marks.incrementAndGet() })
        return vm to marks
    }
}
