package com.aritr.rova.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoplayPolicyTest {
    @Test fun emptyInput_emptyOut() =
        assertEquals(emptySet<String>(), AutoplayPolicy.select(emptyList(), 3))

    @Test fun fewerThanCap_allSelected() =
        assertEquals(setOf("a", "b"), AutoplayPolicy.select(listOf("a", "b"), 3))

    @Test fun moreThanCap_firstCapByViewportOrder() =
        assertEquals(setOf("a", "b"), AutoplayPolicy.select(listOf("a", "b", "c", "d"), 2))

    @Test fun capZero_emptyOut() =
        assertEquals(emptySet<String>(), AutoplayPolicy.select(listOf("a", "b"), 0))

    @Test fun capNegative_emptyOut() =
        assertEquals(emptySet<String>(), AutoplayPolicy.select(listOf("a", "b"), -2))

    // hero consumes one decoder of the MAX_CONCURRENT budget.
    @Test fun cardCap_heroVisible_reservesOne() = assertEquals(2, AutoplayPolicy.cardCap(heroVisible = true))
    @Test fun cardCap_heroHidden_fullBudget() = assertEquals(3, AutoplayPolicy.cardCap(heroVisible = false))
    @Test fun maxConcurrentIsDecoderSafe() = assertEquals(3, AutoplayPolicy.MAX_CONCURRENT)

    // visible-fraction gate (don't let a sliver-visible edge card take a decoder).
    @Test fun mostlyVisible_fullyInside() =
        assertTrue(AutoplayPolicy.isMostlyVisible(top = 100, size = 200, vpStart = 0, vpEnd = 1000))

    @Test fun mostlyVisible_exactlyHalf() = // 100/200 = .5
        assertTrue(AutoplayPolicy.isMostlyVisible(top = -100, size = 200, vpStart = 0, vpEnd = 1000))

    @Test fun mostlyVisible_underHalf() = // 50/200 = .25
        assertFalse(AutoplayPolicy.isMostlyVisible(top = -150, size = 200, vpStart = 0, vpEnd = 1000))

    @Test fun mostlyVisible_zeroSize() =
        assertFalse(AutoplayPolicy.isMostlyVisible(top = 0, size = 0, vpStart = 0, vpEnd = 1000))

    @Test fun mostlyVisible_fullyOffscreen() =
        assertFalse(AutoplayPolicy.isMostlyVisible(top = 2000, size = 200, vpStart = 0, vpEnd = 1000))
}
