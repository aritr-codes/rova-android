package com.aritr.rova.ui.warnings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryCardRateLimitTest {
    private val day = BATTERY_CARD_RATE_LIMIT_MILLIS
    @Test fun neverShown_allowed() = assertFalse(shouldSuppressBatteryCard(lastShownAtMillis = 0L, nowMillis = day))
    @Test fun shownJustNow_suppressed() = assertTrue(shouldSuppressBatteryCard(lastShownAtMillis = day, nowMillis = day + 1))
    @Test fun shownWithinWindow_suppressed() = assertTrue(shouldSuppressBatteryCard(lastShownAtMillis = day, nowMillis = day + day / 2))
    @Test fun shownExactlyWindowAgo_allowed() = assertFalse(shouldSuppressBatteryCard(lastShownAtMillis = day, nowMillis = day + day))
    @Test fun shownOverWindowAgo_allowed() = assertFalse(shouldSuppressBatteryCard(lastShownAtMillis = day, nowMillis = day * 3))
}
