package com.aritr.rova.service.recovery

/**
 * Milestone 2 — backoff schedule for transient merge failures. Three
 * attempts with exponential delays. Pure JVM; suspending `delay(...)`
 * by the caller is the actual sleep mechanism.
 *
 * Spec: `docs/superpowers/specs/2026-05-26-merge-reliability-bundle-design.md` §5 #2.
 */
internal object MergeRetryPolicy {
    const val MAX_ATTEMPTS: Int = 3

    /**
     * 1-indexed attempt → millis to wait BEFORE issuing that attempt.
     * Out-of-range inputs clamp at 16s defensively; callers that respect
     * [MAX_ATTEMPTS] never produce them.
     */
    fun backoffMillisFor(attempt: Int): Long = when (attempt) {
        1 -> 1_000L
        2 -> 4_000L
        3 -> 16_000L
        else -> 16_000L
    }
}
