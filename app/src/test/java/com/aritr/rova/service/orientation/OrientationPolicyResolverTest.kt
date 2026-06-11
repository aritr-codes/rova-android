package com.aritr.rova.service.orientation

import org.junit.Assert.assertEquals
import org.junit.Test

class OrientationPolicyResolverTest {

    @Test
    fun followDevice_passesSnappedRotationThrough() {
        for (snapped in 0..3) {
            assertEquals(snapped, OrientationPolicyResolver.resolve("FollowDevice", -1, snapped))
        }
    }

    @Test
    fun lock_pinsToStoredRotation_ignoringSnapped() {
        assertEquals(0, OrientationPolicyResolver.resolve("Lock", 0, 3))
        assertEquals(1, OrientationPolicyResolver.resolve("Lock", 1, 0))
        assertEquals(3, OrientationPolicyResolver.resolve("Lock", 3, 1))
    }

    @Test
    fun lockWithInvalidStoredRotation_fallsBackToSnapped() {
        // Defensive: a "Lock" policy with rotation outside 0..3 must not pin garbage.
        assertEquals(2, OrientationPolicyResolver.resolve("Lock", -1, 2))
        assertEquals(2, OrientationPolicyResolver.resolve("Lock", 7, 2))
    }

    @Test
    fun unknownPolicyString_behavesAsFollowDevice() {
        assertEquals(1, OrientationPolicyResolver.resolve("Auto", -1, 1))
        assertEquals(1, OrientationPolicyResolver.resolve("", -1, 1))
    }
}
