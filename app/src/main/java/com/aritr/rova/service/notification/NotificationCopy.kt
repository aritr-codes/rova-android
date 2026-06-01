package com.aritr.rova.service.notification

import com.aritr.rova.R
import com.aritr.rova.ui.text.UiText

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
data class NotificationCopy(val title: UiText, val body: UiText)

private fun formatMmSs(totalSeconds: Int): String {
    val s = totalSeconds.coerceAtLeast(0)
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

// B3 i18n task 9b: resource-backed UiText tokens (same precedent as
// RecordHudFormatters / Player Unavailable / recovery mapper). The mm:ss seam
// (formatMmSs) is pure number formatting — NOT localizable copy — so its result
// is passed as a %1$s string arg, never externalized. English verified once at
// the resource layer (strings.xml / plurals.xml).
fun NotificationState.toCopy(): NotificationCopy = when (this) {
    is NotificationState.ClipRecording -> NotificationCopy(
        title = if (total != null) {
            UiText.StrArgs(R.string.notification_clip_title_total, listOf(current, total))
        } else {
            UiText.StrArgs(R.string.notification_clip_title, listOf(current))
        },
        body = if (etaSecondsRemaining != null) {
            UiText.StrArgs(R.string.notification_clip_body_eta, listOf(formatMmSs(etaSecondsRemaining)))
        } else {
            UiText.Str(R.string.notification_clip_body_static)
        }
    )
    is NotificationState.GapWaiting -> NotificationCopy(
        title = if (total != null) {
            UiText.StrArgs(R.string.notification_gap_title_total, listOf(nextNumber, total))
        } else {
            UiText.StrArgs(R.string.notification_gap_title, listOf(nextNumber))
        },
        body = UiText.StrArgs(R.string.notification_gap_body, listOf(nextInLabel))
    )
    is NotificationState.Merging -> NotificationCopy(
        title = UiText.StrArgs(R.string.notification_merging_title, listOf(done, total)),
        body = UiText.Str(R.string.notification_merging_body)
    )
    is NotificationState.MergeComplete -> NotificationCopy(
        title = UiText.Str(R.string.notification_complete_title),
        body = if (totalDurationSeconds != null) {
            UiText.Plural(
                R.plurals.notification_complete_body_dur,
                clipCount,
                listOf(clipCount, formatMmSs(totalDurationSeconds))
            )
        } else {
            UiText.Plural(
                R.plurals.notification_complete_body_nodur,
                clipCount,
                listOf(clipCount)
            )
        }
    )
}
