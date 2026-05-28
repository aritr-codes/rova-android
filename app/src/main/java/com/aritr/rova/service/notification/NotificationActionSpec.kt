package com.aritr.rova.service.notification

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.aritr.rova.R

/**
 * M5 §5 — typed notification action.
 *
 * The spec carries only intent metadata; the service binds it to a
 * real `PendingIntent` (the service is the only owner of the launch
 * surface, by ADR 0001 + ADR 0005 §"Scan Trigger Boundary" — receivers
 * and the activity are tightly coupled to existing wiring there).
 *
 * Icon defaults are intentionally generic system icons; the small-icon
 * channel-tinted vector ([NotificationIconRes]) dominates the visual.
 */
enum class NotificationActionKey { STOP, STOP_EARLY, OPEN, VIEW_IN_LIBRARY, SHARE }

data class NotificationActionSpec(
    val key: NotificationActionKey,
    @StringRes val labelRes: Int,
    @StringRes val contentDescriptionRes: Int,
    @DrawableRes val iconRes: Int,
    val sessionIdExtra: String? = null
)

fun NotificationState.toActionSpecs(): List<NotificationActionSpec> = when (this) {
    is NotificationState.ClipRecording -> listOf(
        stopSpec(NotificationActionKey.STOP, R.string.notification_action_stop, R.string.notification_action_stop_cd),
        openSpec()
    )
    is NotificationState.GapWaiting -> listOf(
        stopSpec(NotificationActionKey.STOP_EARLY, R.string.notification_action_stop_early, R.string.notification_action_stop_early_cd),
        openSpec()
    )
    is NotificationState.Merging -> emptyList()
    is NotificationState.MergeComplete -> listOf(
        NotificationActionSpec(
            key = NotificationActionKey.VIEW_IN_LIBRARY,
            labelRes = R.string.notification_action_view_in_library,
            contentDescriptionRes = R.string.notification_action_view_in_library_cd,
            iconRes = android.R.drawable.ic_menu_view,
            sessionIdExtra = sessionId
        ),
        NotificationActionSpec(
            key = NotificationActionKey.SHARE,
            labelRes = R.string.notification_action_share,
            contentDescriptionRes = R.string.notification_action_share_cd,
            iconRes = android.R.drawable.ic_menu_share,
            sessionIdExtra = sessionId
        )
    )
}

private fun stopSpec(key: NotificationActionKey, @StringRes labelRes: Int, @StringRes cdRes: Int) =
    NotificationActionSpec(
        key = key,
        labelRes = labelRes,
        contentDescriptionRes = cdRes,
        iconRes = android.R.drawable.ic_media_pause,
        sessionIdExtra = null
    )

private fun openSpec() = NotificationActionSpec(
    key = NotificationActionKey.OPEN,
    labelRes = R.string.notification_action_open,
    contentDescriptionRes = R.string.notification_action_open_cd,
    iconRes = android.R.drawable.ic_menu_view,
    sessionIdExtra = null
)
