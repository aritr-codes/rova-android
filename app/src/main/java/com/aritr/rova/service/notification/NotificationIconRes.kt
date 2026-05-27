package com.aritr.rova.service.notification

import androidx.annotation.DrawableRes
import com.aritr.rova.R

/**
 * M5 §7 — per-state notification small-icon resource.
 *
 * Pure mapping. Drawables are single-path monochrome white vectors;
 * the system tints them per status-bar / panel surface. The colored
 * accent comes from `setColor()` (see [NotificationChannelConfig]).
 */
@DrawableRes
fun NotificationState.toIconRes(): Int = when (this) {
    is NotificationState.ClipRecording -> R.drawable.ic_notif_recording
    is NotificationState.GapWaiting -> R.drawable.ic_notif_waiting
    is NotificationState.Merging -> R.drawable.ic_notif_merging
    is NotificationState.MergeComplete -> R.drawable.ic_notif_complete
}
