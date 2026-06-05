package com.aritr.rova.ui.warnings

/** Default once-per-day window for the rate-limited Record cards (battery-opt + power-save). */
const val BATTERY_CARD_RATE_LIMIT_MILLIS: Long = 24L * 60L * 60L * 1000L

/**
 * True when a rate-limited card was shown within [windowMillis] of [nowMillis]
 * and must therefore be suppressed this session. [lastShownAtMillis] == 0
 * (never shown) or a time >= one window ago means "allow".
 *
 * Pure + JVM-tested generic window predicate; the WarningCenter captures this
 * ONCE per session per card BEFORE writing the new timestamp, so recording
 * "shown now" never self-suppresses. Reused for both the
 * battery-optimization card (WarningId.BATTERY_OPTIMIZATION_ON, #17) and the
 * power-save-mode card (WarningId.POWER_SAVE_MODE, #18) — each with its own
 * persisted `*CardLastShownAt` timestamp.
 */
fun shouldSuppressBatteryCard(
    lastShownAtMillis: Long,
    nowMillis: Long,
    windowMillis: Long = BATTERY_CARD_RATE_LIMIT_MILLIS,
): Boolean = lastShownAtMillis > 0L && (nowMillis - lastShownAtMillis) < windowMillis
