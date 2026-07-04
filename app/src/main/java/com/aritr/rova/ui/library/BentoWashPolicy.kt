package com.aritr.rova.ui.library

/**
 * Pinned-state policy for the ground-wash day headers (ADR-0030 amendment
 * 2026-07-04 §4). Input: (dayEpochMillis, offsetPx) of every HEADER item in
 * visibleItemsInfo, in list order. Compose's sticky mechanism clamps the
 * active header to offset 0 and pushes the outgoing one negative; a header
 * resting below the viewport top has offset > 0 and never washes. Derived
 * synchronously from layout info (frozen v3.2 codex High: the wash must
 * never lag a render).
 */
object BentoWashPolicy {
    fun pinnedDayEpoch(visibleHeaders: List<Pair<Long, Int>>): Long? =
        visibleHeaders.lastOrNull { it.second <= 0 }?.first
}
