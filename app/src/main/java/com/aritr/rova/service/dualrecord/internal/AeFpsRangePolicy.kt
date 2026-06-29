package com.aritr.rova.service.dualrecord.internal

/**
 * DualShot AE frame-rate floor (2026-06-29, ADR-0034) — pure selection over the
 * device's discrete CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES. Capability-gated:
 * returns a SUPPORTED range to request, or null to leave the AE default in place
 * (never request an unlisted range — that can fail camera-open). Framework-free
 * (Int Pairs; android.util.Range is wrapped only at the RovaRecordingService
 * edge — D-deviation) so it is unit-tested directly (AeFpsRangePolicyTest). See
 * the 2026-06-29 AE-floor design spec §4.
 */
internal object AeFpsRangePolicy {

    /**
     * Choose the AE target fps range from [available] (each `(lower, upper)`),
     * honoring [floor] (min acceptable lower) and [ceiling] (preferred upper).
     *  - Pass 1 (true span): `upper == ceiling && lower >= floor && lower < upper`
     *    → lowest `lower` (max low-light headroom). Excludes ceiling pins like
     *    `(30,30)` because `lower < upper` is false; prefers `[24,30]` over `[30,30]`.
     *  - Pass 2 (brightness pin): no true span found — `lower >= floor && upper <= ceiling`
     *    → lowest `upper` (longest exposure = brightest dim light; ADR-0034 intent),
     *    tie-break lowest `lower`. On a device with only `(24,24)` and `(30,30)` this
     *    picks `(24,24)` (lower fps cap → longer exposure).
     *  - else: null — do not set the option; keep the device default.
     */
    fun choose(available: List<Pair<Int, Int>>, floor: Int, ceiling: Int): Pair<Int, Int>? {
        // Pass 1: a real range that REACHES the ceiling and starts at/above the floor
        // (lower < upper → a true span giving AE room; excludes a ceiling pin like (30,30)).
        // Prefer the lowest lower for maximum low-light headroom (e.g. [24,30] over [26,30]).
        available.filter { it.second == ceiling && it.first >= floor && it.first < it.second }
            .minByOrNull { it.first }
            ?.let { return it }
        // Pass 2: no true span — only pins or sub-ceiling ranges remain. Prefer the LOWEST
        // fps cap >= floor (longest exposure → brightest dim light; ADR-0034 intent), then
        // the lowest lower. On a device with only (24,24) and (30,30) this picks (24,24).
        return available.filter { it.first >= floor && it.second <= ceiling }
            .sortedWith(compareBy<Pair<Int, Int>> { it.second }.thenBy { it.first })
            .firstOrNull()
    }
}
