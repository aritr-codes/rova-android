package com.aritr.rova.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BentoWashPolicyTest {

    @Test fun `no visible headers - nothing pinned`() {
        assertNull(BentoWashPolicy.pinnedDayEpoch(emptyList()))
    }

    @Test fun `header resting below the top is not pinned`() {
        assertNull(BentoWashPolicy.pinnedDayEpoch(listOf(100L to 240)))
    }

    @Test fun `header clamped at the top is pinned`() {
        assertEquals(100L, BentoWashPolicy.pinnedDayEpoch(listOf(100L to 0, 200L to 480)))
    }

    @Test fun `push-off keeps the outgoing wash until the incoming header reaches the top`() {
        // outgoing pushed to -12, incoming still 25px below the top → outgoing owns the wash
        assertEquals(100L, BentoWashPolicy.pinnedDayEpoch(listOf(100L to -12, 200L to 25)))
        // incoming reaches the top → it takes over
        assertEquals(200L, BentoWashPolicy.pinnedDayEpoch(listOf(100L to -37, 200L to 0)))
    }
}
