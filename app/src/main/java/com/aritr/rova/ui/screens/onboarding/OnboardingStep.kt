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
