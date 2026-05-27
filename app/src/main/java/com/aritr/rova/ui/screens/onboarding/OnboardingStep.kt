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
