package com.aritr.rova.service.notification

import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.annotation.LayoutRes
import androidx.annotation.StringRes
import com.aritr.rova.R

/**
 * M5 Phase 2 — bind-plan emitted by [toBindPlan]. Pure data; the
 * service consumes it to inflate + bind a real RemoteViews tree. No
 * Android RemoteViews / Context calls in this file — the boundary
 * mirrors Phase 1's pure-helper / service-as-seam pattern.
 *
 * Title + body + progress are forwarded from Phase 1 helpers
 * ([toCopy], [toProgress]) — never duplicated. Accent + icon + chip
 * CD + collapsedTail are Phase-2-specific.
 *
 * Spec: docs/superpowers/specs/2026-05-27-notification-redesign-v1-design.md §7
 */
data class NotificationBindPlan(
    @LayoutRes val layoutCollapsedRes: Int,
    @LayoutRes val layoutExpandedRes: Int,
    val title: String,
    val body: String,
    val collapsedTail: String?,
    @ColorInt val accent: Int,
    @DrawableRes val iconRes: Int,
    @StringRes val chipContentDescriptionRes: Int,
    val progress: NotificationProgress?,
    val isComplete: Boolean
)

fun NotificationState.toBindPlan(): NotificationBindPlan {
    val copy = toCopy()
    return NotificationBindPlan(
        layoutCollapsedRes = R.layout.notif_collapsed,
        layoutExpandedRes = R.layout.notif_expanded,
        title = copy.title,
        body = copy.body,
        collapsedTail = collapsedTailFor(this),
        accent = toAccent(),
        iconRes = toIconRes(),
        chipContentDescriptionRes = chipCdFor(this),
        progress = toProgress(),
        isComplete = this is NotificationState.MergeComplete
    )
}

@StringRes
private fun chipCdFor(state: NotificationState): Int = when (state) {
    is NotificationState.ClipRecording -> R.string.notification_chip_cd_recording
    is NotificationState.GapWaiting -> R.string.notification_chip_cd_waiting
    is NotificationState.Merging -> R.string.notification_chip_cd_merging
    is NotificationState.MergeComplete -> R.string.notification_chip_cd_complete
}

private fun collapsedTailFor(state: NotificationState): String? = when (state) {
    is NotificationState.ClipRecording ->
        state.etaSecondsRemaining?.let { "${formatMmSsForTail(it)} remaining" }
    is NotificationState.GapWaiting -> state.nextInLabel
    is NotificationState.Merging ->
        state.mergeProgressPercent?.let { "$it%" } ?: "${state.done} of ${state.total}"
    is NotificationState.MergeComplete ->
        if (state.clipCount == 1) "1 clip" else "${state.clipCount} clips"
}

private fun formatMmSsForTail(totalSeconds: Int): String {
    val s = totalSeconds.coerceAtLeast(0)
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}
