package com.aritr.rova.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-policy tests for [ReducedTransparency.reduceTransparency]. The framework
 * reads (Settings.Secure / animation scales) are not unit-tested — under
 * `isReturnDefaultValues = true` they return defaults; the OR policy is the seam
 * that carries the behavior and is what must not regress.
 */
class ReducedTransparencyTest {

    @Test
    fun neither_signal_keeps_glass() {
        assertFalse(ReducedTransparency.reduceTransparency(highContrastText = false, reduceMotion = false))
    }

    @Test
    fun highContrastText_alone_collapses_glass() {
        // The gap this seam closes: high-contrast users with motion ON still get solid.
        assertTrue(ReducedTransparency.reduceTransparency(highContrastText = true, reduceMotion = false))
    }

    @Test
    fun reduceMotion_alone_still_collapses_glass() {
        // No regression for existing reduce-motion users.
        assertTrue(ReducedTransparency.reduceTransparency(highContrastText = false, reduceMotion = true))
    }

    @Test
    fun both_signals_collapse_glass() {
        assertTrue(ReducedTransparency.reduceTransparency(highContrastText = true, reduceMotion = true))
    }
}
