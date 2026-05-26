package com.aritr.rova.service.recovery

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Milestone 2 — pure-JVM cover for [MergeRetryPolicy]. Exponential
 * backoff schedule 1s / 4s / 16s for attempts 1..3; defensive
 * clamping for attempts outside the expected range. Spec §5 #2.
 */
class MergeRetryPolicyTest {

    @Test
    fun max_attempts_is_three() {
        assertEquals(3, MergeRetryPolicy.MAX_ATTEMPTS)
    }

    @Test
    fun backoff_attempt_1_is_one_second() {
        assertEquals(1_000L, MergeRetryPolicy.backoffMillisFor(1))
    }

    @Test
    fun backoff_attempt_2_is_four_seconds() {
        assertEquals(4_000L, MergeRetryPolicy.backoffMillisFor(2))
    }

    @Test
    fun backoff_attempt_3_is_sixteen_seconds() {
        assertEquals(16_000L, MergeRetryPolicy.backoffMillisFor(3))
    }

    @Test
    fun backoff_out_of_range_clamps_at_sixteen_seconds() {
        // Defensive — should not be reached in production (MAX_ATTEMPTS gates).
        assertEquals(16_000L, MergeRetryPolicy.backoffMillisFor(0))
        assertEquals(16_000L, MergeRetryPolicy.backoffMillisFor(99))
        assertEquals(16_000L, MergeRetryPolicy.backoffMillisFor(-1))
    }
}
