package com.aritr.rova.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationSettingsIntentTest {

    @Test
    fun api26AndAbove_usesAppNotificationSettings() {
        assertEquals(NotificationSettingsTarget.APP_NOTIFICATION_SETTINGS, notificationSettingsTarget(26))
        assertEquals(NotificationSettingsTarget.APP_NOTIFICATION_SETTINGS, notificationSettingsTarget(34))
    }

    @Test
    fun api24And25_fallBackToAppDetails() {
        assertEquals(NotificationSettingsTarget.APP_DETAILS, notificationSettingsTarget(24))
        assertEquals(NotificationSettingsTarget.APP_DETAILS, notificationSettingsTarget(25))
    }
}
