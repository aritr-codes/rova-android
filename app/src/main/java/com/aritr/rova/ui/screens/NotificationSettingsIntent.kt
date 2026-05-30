package com.aritr.rova.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Which system screen the Settings "System notification settings" row opens.
 *
 * The recording foreground-service notification is mandatory and per-channel
 * muting is the OS's job, so this row routes the user OUT to the platform
 * rather than faking app-level notification toggles (B1 design §2.3).
 *
 * The API-level decision is split into this pure enum + [notificationSettingsTarget]
 * so it is unit-testable under `isReturnDefaultValues = true`; the actual
 * [Intent] build (android.jar, a no-op under JVM tests) stays in the thin
 * [buildNotificationSettingsIntent] wrapper.
 */
enum class NotifSettingsTarget {
    /** API 26+: per-app channel list. */
    APP_NOTIFICATION_SETTINGS,

    /** API 24-25: app details page (no channel screen exists pre-O). */
    APP_DETAILS,
}

/** Pure decision: per-app channel settings on O+, app-details fallback below. */
fun notificationSettingsTarget(sdkInt: Int): NotifSettingsTarget =
    if (sdkInt >= Build.VERSION_CODES.O) {
        NotifSettingsTarget.APP_NOTIFICATION_SETTINGS
    } else {
        NotifSettingsTarget.APP_DETAILS
    }

/** Builds the platform intent for the resolved [notificationSettingsTarget]. */
fun buildNotificationSettingsIntent(context: Context): Intent =
    when (notificationSettingsTarget(Build.VERSION.SDK_INT)) {
        NotifSettingsTarget.APP_NOTIFICATION_SETTINGS ->
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

        NotifSettingsTarget.APP_DETAILS ->
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            )
    }
