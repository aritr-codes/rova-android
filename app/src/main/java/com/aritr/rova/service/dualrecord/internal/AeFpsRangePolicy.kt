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
     *  - Pass 1: `upper == ceiling && lower >= floor` → lowest `lower` (max
     *    low-light headroom; prefers [24,30] over [30,30]).
     *  - Pass 2 (pass 1 empty): `lower >= floor && upper <= ceiling` → highest
     *    `upper`, then lowest `lower` (e.g. [24,24]).
     *  - else: null — do not set the option; keep the device default.
     */
    fun choose(available: List<Pair<Int, Int>>, floor: Int, ceiling: Int): Pair<Int, Int>? {
        available.filter { it.second == ceiling && it.first >= floor }
            .minByOrNull { it.first }
            ?.let { return it }
        return available.filter { it.first >= floor && it.second <= ceiling }
            .sortedWith(compareByDescending<Pair<Int, Int>> { it.second }.thenBy { it.first })
            .firstOrNull()
    }
}
