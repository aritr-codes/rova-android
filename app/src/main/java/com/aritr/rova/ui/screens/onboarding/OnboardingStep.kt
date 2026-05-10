package com.aritr.rova.ui.screens.onboarding

/**
 * Phase 2.6 — discrete steps in the first-launch onboarding flow.
 *
 * Three walkthrough slides per `mockups/new_uiux/08-onboarding.html`
 * (Record on repeat / Walks away with you / One session, one file)
 * followed by four permission requests in the mandated order.
 *
 * Permission ordering is a hard NO-GO if reordered (NEW_UI_BACKEND_REPLAN
 * §Phase 2 row 2.6): camera-first because the production surface is
 * camera-driven; mic next per ADR 0006 B18 (soft-gates VIDEO_ONLY); then
 * notifications (recommended); then exact-alarm (opt-in per ADR 0001
 * with inexact fallback if denied).
 *
 * The enum's `ordinal` drives [next] / [previous]; do not reorder
 * entries without updating the mockup + replan.
 */
enum class OnboardingStep {
    WALKTHROUGH_1,
    WALKTHROUGH_2,
    WALKTHROUGH_3,
    PERM_CAMERA,
    PERM_MIC,
    PERM_NOTIFS,
    PERM_ALARM;

    fun next(): OnboardingStep? = entries.getOrNull(ordinal + 1)
    fun previous(): OnboardingStep? = entries.getOrNull(ordinal - 1)

    val isWalkthrough: Boolean get() = ordinal <= WALKTHROUGH_3.ordinal
    val isPermission: Boolean get() = !isWalkthrough
}
