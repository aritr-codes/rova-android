package com.aritr.rova.service.notification

/**
 * Phase 3.1 (NEW_UI_BACKEND_REPLAN §5 row 3.1, §11.5) — typed copy
 * states for the foreground recording notification. Mockup
 * source-of-truth: `mockups/new_uiux/09-notification-export.html`
 * (rendered sibling under `UI_SCREENSHOTS/`).
 *
 * Sealed interface (not enum) chosen because each happy-path state
 * carries DIFFERENT params:
 *  - [ClipRecording]: current clip number + optional total
 *  - [GapWaiting]: next clip number + countdown label + optional total
 *  - [Merging]: done / total integers
 *  - [MergeComplete]: clip count for the saved video
 *
 * `total` is nullable on [ClipRecording] / [GapWaiting] because
 * unlimited periodic mode (`limitLoops == -1`) has no bound — the
 * mapper drops the `of N` suffix when total is unknown rather than
 * fabricating "of ∞" or "of -1". `total` on [Merging] is required:
 * once the merge starts, the segment count is concrete.
 *
 * NO additional state may be added without amending §11.5 (Phase 3.1
 * NO-GO list explicitly forbids a 5th notification surface). Error /
 * init / transient strings continue to flow through the existing
 * String-based `updateNotification(contentText)` overload — they are
 * NOT a 5th state, just legacy free-form copy retained for parity.
 */
sealed interface NotificationState {
    data class ClipRecording(val current: Int, val total: Int? = null) : NotificationState
    data class GapWaiting(val nextNumber: Int, val nextInLabel: String, val total: Int? = null) : NotificationState
    data class Merging(val done: Int, val total: Int) : NotificationState
    data class MergeComplete(val clipCount: Int) : NotificationState
}

/**
 * Pure (title, body) pair consumed by the service's
 * `NotificationCompat.Builder` chain. Builder calls (channel id,
 * ongoing flag, FGS type, action buttons, content intent) stay in
 * the service per Phase 3.1 fence (the `service/` tree is partially
 * open; `service/notification/` is the only new package).
 */
data class NotificationCopy(val title: String, val body: String)

/**
 * Map a typed [NotificationState] to the verbatim mockup copy. Pure
 * data → pure strings: testable without Robolectric / Context.
 *
 * Body text divergences from the mockup (intentional, documented):
 *  - [NotificationState.ClipRecording]: mockup shows "0:18 remaining
 *    in this clip", which requires a per-clip elapsed timer the
 *    service does not currently surface. Body falls back to a static
 *    "Recording in progress" string. Wiring elapsed-clip-time is a
 *    follow-on slice.
 *  - [NotificationState.Merging]: mockup shows "About 15 seconds
 *    remaining", which requires an ETA the export pipeline does not
 *    expose. Body is a static "Processing — please wait".
 *  - [NotificationState.MergeComplete]: mockup shows
 *    "6 clips · 5:00 total · saved to Library"; total duration is
 *    not in scope at the call site (would require summing segment
 *    durations from the manifest). Body is "$N clips saved to Library"
 *    with singular handling for `clipCount == 1`.
 */
fun NotificationState.toCopy(): NotificationCopy = when (this) {
    is NotificationState.ClipRecording -> NotificationCopy(
        title = if (total != null) "Recording · Clip $current of $total" else "Recording · Clip $current",
        body = "Recording in progress"
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
        body = if (clipCount == 1) "1 clip saved to Library" else "$clipCount clips saved to Library"
    )
}
