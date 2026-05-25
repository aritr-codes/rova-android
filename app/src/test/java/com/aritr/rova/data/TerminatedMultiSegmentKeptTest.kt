package com.aritr.rova.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class TerminatedMultiSegmentKeptTest {

    @Test
    fun `MULTI_SEGMENT_KEPT exists and is the fifth value`() {
        val values = Terminated.values()
        assertEquals("Terminated must have 5 values after Phase 4.3", 5, values.size)
        assertNotNull(Terminated.valueOf("MULTI_SEGMENT_KEPT"))
        assertEquals("MULTI_SEGMENT_KEPT must be last", 4, Terminated.MULTI_SEGMENT_KEPT.ordinal)
    }
}
