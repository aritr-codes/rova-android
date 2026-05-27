package com.aritr.rova.service.notification

import android.app.NotificationManager
import androidx.annotation.ColorInt

/**
 * M5 §4 + §5 — channel topology, accent colors, dismissibility and
 * progress-bar config per [NotificationState]. All pure data; the
 * service consumes these and feeds them to `NotificationCompat.Builder`.
 *
 * Two channels:
 *  - [SESSION_CHANNEL_ID] — LOW importance, ongoing, silent FGS spine
 *    (ClipRecording / GapWaiting / Merging).
 *  - [COMPLETE_CHANNEL_ID] — DEFAULT importance, one-shot, dismissible
 *    (MergeComplete). User can opt sound off per-channel without
 *    silencing recording.
 *
 * The old [LEGACY_CHANNEL_ID] is intentionally kept registered (and
 * unused) so user importance/sound overrides are not nuked silently
 * on first install of M5. A follow-on cleanup slice can delete it.
 */
object NotificationChannelConfig {
    const val SESSION_CHANNEL_ID = "rova_recording_session"
    const val COMPLETE_CHANNEL_ID = "rova_recording_complete"
    const val LEGACY_CHANNEL_ID = "RovaRecordingChannel"

    @ColorInt val ACCENT_RECORDING: Int = 0xFF5B7FFF.toInt()
    @ColorInt val ACCENT_COMPLETE: Int = 0xFF34D399.toInt()

    fun importanceFor(channelId: String): Int = when (channelId) {
        SESSION_CHANNEL_ID -> NotificationManager.IMPORTANCE_LOW
        COMPLETE_CHANNEL_ID -> NotificationManager.IMPORTANCE_DEFAULT
        else -> NotificationManager.IMPORTANCE_LOW
    }
}

data class NotificationProgress(
    val max: Int,
    val current: Int,
    val indeterminate: Boolean
)

fun NotificationState.toChannelId(): String = when (this) {
    is NotificationState.ClipRecording,
    is NotificationState.GapWaiting,
    is NotificationState.Merging -> NotificationChannelConfig.SESSION_CHANNEL_ID
    is NotificationState.MergeComplete -> NotificationChannelConfig.COMPLETE_CHANNEL_ID
}

@ColorInt
fun NotificationState.toAccent(): Int = when (this) {
    is NotificationState.ClipRecording,
    is NotificationState.GapWaiting,
    is NotificationState.Merging -> NotificationChannelConfig.ACCENT_RECORDING
    is NotificationState.MergeComplete -> NotificationChannelConfig.ACCENT_COMPLETE
}

fun NotificationState.isDismissible(): Boolean = when (this) {
    is NotificationState.MergeComplete -> true
    else -> false
}

fun NotificationState.toProgress(): NotificationProgress? = when (this) {
    is NotificationState.ClipRecording -> null
    is NotificationState.GapWaiting -> {
        if (nextStartsInSeconds != null && gapTotalSeconds != null) {
            NotificationProgress(
                max = gapTotalSeconds,
                current = (gapTotalSeconds - nextStartsInSeconds).coerceIn(0, gapTotalSeconds),
                indeterminate = false
            )
        } else null
    }
    is NotificationState.Merging -> {
        if (mergeProgressPercent != null) {
            NotificationProgress(max = 100, current = mergeProgressPercent.coerceIn(0, 100), indeterminate = false)
        } else {
            NotificationProgress(max = 0, current = 0, indeterminate = true)
        }
    }
    is NotificationState.MergeComplete -> null
}
