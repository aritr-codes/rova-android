package com.aritr.rova.service.notification

import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.annotation.LayoutRes
import com.aritr.rova.R

/**
 * M5 Phase 3.1 §6 — countdown spec for Chronometer binding.
 *
 * [baseElapsedMs] is the absolute `SystemClock.elapsedRealtime()` value
 * at which the countdown hits zero. The service binds it via
 * `RemoteViews.setChronometer(viewId, base = baseElapsedMs, format = null, started = true)`
 * + `setBoolean(viewId, "setCountDown", true)`. The widget then free-ticks
 * per second without service round-trips.
 *
 * `null` plan → service hides Chronometer + shows static body TextView.
 */
data class ChronoSpec(val baseElapsedMs: Long)

/**
 * M5 Phase 3 §7 — bind-plan emitted by [toBindPlan]. Pure data; the
 * service consumes it to inflate + bind a real RemoteViews tree. No
 * Android RemoteViews / Context calls in this file.
 *
 * Phase 3 deltas vs Phase 2:
 *   - REMOVED chipContentDescriptionRes (chip dropped from layouts)
 *   - ADDED dots: DotsPlan (clip-progress row, per spec §4.3)
 *   - ADDED titleColor: Int? (null = use Compat.Notification.Title default,
 *                              non-null = MergeComplete green)
 *   - ADDED surfaceRes: DrawableRes (notif_surface vs notif_surface_complete)
 *
 * Phase 3.1 deltas vs Phase 3:
 *   - ADDED chrono: ChronoSpec? (countdown Chronometer spec; null = static body)
 *
 * Spec: docs/superpowers/specs/2026-05-28-notification-redesign-phase3-design.md
 */
data class NotificationBindPlan(
    @LayoutRes val layoutCollapsedRes: Int,
    @LayoutRes val layoutExpandedRes: Int,
    val title: String,
    val body: String,
    val collapsedTail: String?,
    @ColorInt val accent: Int,
    @DrawableRes val iconRes: Int,
    val progress: NotificationProgress?,
    val isComplete: Boolean,
    val dots: DotsPlan,
    @ColorInt val titleColor: Int?,
    @DrawableRes val surfaceRes: Int,
    val chrono: ChronoSpec?
)

fun NotificationState.toBindPlan(): NotificationBindPlan {
    val copy = toCopy()
    val complete = this is NotificationState.MergeComplete
    return NotificationBindPlan(
        layoutCollapsedRes = R.layout.notif_collapsed,
        layoutExpandedRes = R.layout.notif_expanded,
        title = copy.title,
        body = copy.body,
        collapsedTail = collapsedTailFor(this),
        accent = toAccent(),
        iconRes = toIconRes(),
        progress = toProgress(),
        isComplete = complete,
        dots = toDotsPlan(),
        titleColor = if (complete) NotificationChannelConfig.ACCENT_COMPLETE else null,
        surfaceRes = if (complete) R.drawable.notif_surface_complete else R.drawable.notif_surface,
        chrono = toChronoSpec()
    )
}

internal fun NotificationState.toChronoSpec(
    now: () -> Long = { android.os.SystemClock.elapsedRealtime() }
): ChronoSpec? = when (this) {
    is NotificationState.ClipRecording ->
        etaSecondsRemaining?.let { ChronoSpec(now() + it * 1000L) }
    is NotificationState.GapWaiting ->
        nextStartsInSeconds?.let { ChronoSpec(now() + it * 1000L) }
    is NotificationState.Merging,
    is NotificationState.MergeComplete -> null
}

private fun collapsedTailFor(state: NotificationState): String? = when (state) {
    is NotificationState.ClipRecording -> state.etaSecondsRemaining?.let { "${formatMmSsForTail(it)} remaining" }
    is NotificationState.GapWaiting -> state.nextInLabel
    is NotificationState.Merging -> state.mergeProgressPercent?.let { "$it%" } ?: "${state.done} of ${state.total}"
    is NotificationState.MergeComplete -> if (state.clipCount == 1) "1 clip" else "${state.clipCount} clips"
}

private fun formatMmSsForTail(totalSeconds: Int): String {
    val s = totalSeconds.coerceAtLeast(0)
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}
