package com.aritr.rova.service.recovery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Milestone 2 — pure-JVM cover for [StoragePreflight]. Validates the
 * `available - accumulated >= FINALIZE_HEADROOM_BYTES (50 MiB)` boundary.
 * Spec §5 #3.
 */
class StoragePreflightTest {

    private val MIB = 1024L * 1024L
    private val HEADROOM = StoragePreflight.FINALIZE_HEADROOM_BYTES   // 50 MiB

    @Test
    fun exact_boundary_passes() {
        // available - accumulated == HEADROOM should pass (>=)
        assertTrue(StoragePreflight.hasHeadroom(
            availableBytes = 200L * MIB,
            accumulatedSessionBytes = 200L * MIB - HEADROOM,
        ))
    }

    @Test
    fun just_below_boundary_fails() {
        assertFalse(StoragePreflight.hasHeadroom(
            availableBytes = 200L * MIB,
            accumulatedSessionBytes = 200L * MIB - HEADROOM + 1L,
        ))
    }

    @Test
    fun just_above_boundary_passes() {
        assertTrue(StoragePreflight.hasHeadroom(
            availableBytes = 200L * MIB,
            accumulatedSessionBytes = 200L * MIB - HEADROOM - 1L,
        ))
    }

    @Test
    fun zero_accumulated_passes_with_sufficient_available() {
        assertTrue(StoragePreflight.hasHeadroom(
            availableBytes = 100L * MIB,
            accumulatedSessionBytes = 0L,
        ))
    }

    @Test
    fun huge_accumulated_exceeds_available_fails() {
        assertFalse(StoragePreflight.hasHeadroom(
            availableBytes = 100L * MIB,
            accumulatedSessionBytes = 10L * 1024L * MIB,   // 10 GiB
        ))
    }

    @Test
    fun zero_available_fails_unconditionally() {
        assertFalse(StoragePreflight.hasHeadroom(
            availableBytes = 0L,
            accumulatedSessionBytes = 0L,
        ))
    }
}
