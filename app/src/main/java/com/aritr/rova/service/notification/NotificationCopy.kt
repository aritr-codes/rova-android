package com.aritr.rova.service.notification

/**
 * Phase 3.1 (NEW_UI_BACKEND_REPLAN §5 row 3.1) + M5 redesign
 * (docs/superpowers/specs/2026-05-27-notification-redesign-v1-design.md)
 * — typed copy states for the foreground recording notification.
 * Mockup source-of-truth: `mockups/new_uiux/09-notification-export.html`.
 *
 * Sealed interface (not enum) — each happy-path state carries DIFFERENT
 * params. M5 added optional numeric fields (eta / countdown / merge-% /
 * total-duration / sessionId) wired from existing service state. Null
 * defaults preserve back-compat with the Phase-3.1 callers.
 *
 * **M5 field-wiring map** (so the unwired fields don't read as oversight):
 *  - [ClipRecording.etaSecondsRemaining] → [toCopy] body
 *  - [GapWaiting.nextStartsInSeconds] + [GapWaiting.gapTotalSeconds] →
 *    `NotificationChannelConfig.toProgress` (countdown progress bar)
 *  - [Merging.mergeProgressPercent] →
 *    `NotificationChannelConfig.toProgress` (determinate merge bar)
 *  - [MergeComplete.totalDurationSeconds] → [toCopy] body
 *  - [MergeComplete.sessionId] →
 *    `NotificationActionSpec.toActionSpecs` (deep-link extras for
 *    `VIEW_IN_LIBRARY` and `SHARE` actions)
 *
 * NO additional state may be added without amending the replan §11.5
 * (the 5th-state NO-GO holds). Error / init / transient strings continue
 * to flow through the existing String-based `updateNotification(contentText)`
 * overload.
 */
sealed interface NotificationState {
    data class ClipRecording(
        val current: Int,
        val total: Int? = null,
        val etaSecondsRemaining: Int? = null
    ) : NotificationState

    data class GapWaiting(
        val nextNumber: Int,
        val nextInLabel: String,
        val total: Int? = null,
        val nextStartsInSeconds: Int? = null,
        val gapTotalSeconds: Int? = null
    ) : NotificationState

    data class Merging(
        val done: Int,
        val total: Int,
        val mergeProgressPercent: Int? = null
    ) : NotificationState

    data class MergeComplete(
        val clipCount: Int,
        val totalDurationSeconds: Int? = null,
        val sessionId: String? = null
    ) : NotificationState
}

/**
 * Pure (title, body) pair consumed by the service's
 * `NotificationCompat.Builder` chain. Builder calls (channel id, color,
 * icon, action buttons, ongoing flag, FGS type, content intent) stay in
 * the service; the only escapes from this file are the four `to*()`
 * helpers in sibling files.
 */
data class NotificationCopy(val title: String, val body: String)

private fun formatMmSs(totalSeconds: Int): String {
    val s = totalSeconds.coerceAtLeast(0)
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

fun NotificationState.toCopy(): NotificationCopy = when (this) {
    is NotificationState.ClipRecording -> NotificationCopy(
        title = if (total != null) "Recording · Clip $current of $total" else "Recording · Clip $current",
        body = if (etaSecondsRemaining != null) "${formatMmSs(etaSecondsRemaining)} remaining in this clip"
        else "Recording in progress"
    )
    is NotificationState.GapWaiting -> NotificationCopy(
        title = if (total != null) "Waiting · Clip $nextNumber of $total next" else "Waiting · Clip $nextNumber next",
        body = "Next clip starts in $nextInLabel"
    )
    is NotificationState.Merging -> NotificationCopy(
        title = "Merging clips · $done of $total",
        body = "Processing — please wait"
    )
    is NotificationState.MergeComplete -> NotificationCopy(
        title = "Merge complete",
        body = when {
            totalDurationSeconds != null && clipCount == 1 ->
                "1 clip · ${formatMmSs(totalDurationSeconds)} total · saved to Library"
            totalDurationSeconds != null ->
                "$clipCount clips · ${formatMmSs(totalDurationSeconds)} total · saved to Library"
            clipCount == 1 -> "1 clip saved to Library"
            else -> "$clipCount clips saved to Library"
        }
    )
}
