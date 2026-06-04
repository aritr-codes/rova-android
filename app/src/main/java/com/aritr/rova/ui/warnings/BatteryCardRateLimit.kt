package com.aritr.rova.ui.warnings

/** Default once-per-day window for the battery-optimization card. */
const val BATTERY_CARD_RATE_LIMIT_MILLIS: Long = 24L * 60L * 60L * 1000L

/**
 * True when the battery-optimization card was shown within [windowMillis] of
 * [nowMillis] and must therefore be suppressed this session. [lastShownAtMillis]
 * == 0 (never shown) or a time >= one window ago means "allow".
 * Pure + JVM-tested; the WarningCenter captures this ONCE per session before
 * writing the new timestamp, so recording "shown now" never self-suppresses.
 */
fun shouldSuppressBatteryCard(
    lastShownAtMillis: Long,
    nowMillis: Long,
    windowMillis: Long = BATTERY_CARD_RATE_LIMIT_MILLIS,
): Boolean = lastShownAtMillis > 0L && (nowMillis - lastShownAtMillis) < windowMillis
