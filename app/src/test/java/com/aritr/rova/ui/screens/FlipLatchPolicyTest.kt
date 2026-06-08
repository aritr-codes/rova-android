package com.aritr.rova.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlipLatchPolicyTest {

    @Test
    fun `does not clear while no flip is latched`() {
        // generation advanced, but no flip in flight — nothing to clear.
        assertFalse(FlipLatchPolicy.shouldClear(flipInFlight = false, startGeneration = 5, currentGeneration = 6))
    }

    @Test
    fun `does not clear before the rebind reports a new generation`() {
        // flip latched, generation unchanged — the rebind has not completed yet.
        assertFalse(FlipLatchPolicy.shouldClear(flipInFlight = true, startGeneration = 5, currentGeneration = 5))
    }

    @Test
    fun `clears once the generation advances past the captured value`() {
        // the bind-success increment is the conflation-proof signal.
        assertTrue(FlipLatchPolicy.shouldClear(flipInFlight = true, startGeneration = 5, currentGeneration = 6))
    }

    @Test
    fun `clears even if multiple binds advanced the generation (conflation dropped intermediates)`() {
        // StateFlow may drop every intermediate emission; only the end value is
        // observed. It is still strictly greater than the captured start.
        assertTrue(FlipLatchPolicy.shouldClear(flipInFlight = true, startGeneration = 5, currentGeneration = 8))
    }

    @Test
    fun `clears from a zero start generation`() {
        // cold default: first-ever flip captured generation 0.
        assertTrue(FlipLatchPolicy.shouldClear(flipInFlight = true, startGeneration = 0, currentGeneration = 1))
    }
}
