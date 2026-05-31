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

    @Test fun `visibleOnboardingSteps at API 32 (boundary) omits notifications`() {
        assertEquals(
            listOf(
                OnboardingStep.WALKTHROUGH_1,
                OnboardingStep.WALKTHROUGH_2,
                OnboardingStep.PERM_CAMERA,
                OnboardingStep.PERM_MIC,
            ),
            visibleOnboardingSteps(32),
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
