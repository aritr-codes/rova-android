package com.aritr.rova.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [ReducedMotion.isReduced] — the framework-free decision
 * seam behind reduced-motion gating (WCAG 2.2 AA SC 2.3.3 / 2.2.2, ADR-0020;
 * audit MOT-01..06, REC-14, RECOV-18, WARN-07, HIST-13).
 *
 * The contract mirrors how Android exposes the OS "remove animations" /
 * developer-options animation-scale settings: `Settings.Global`
 * `TRANSITION_ANIMATION_SCALE` and `ANIMATOR_DURATION_SCALE` are `0f` when the
 * user has disabled animations. Either being `0f` means honor reduced motion.
 * A non-zero scale (even a slowed `2f` or a fast `0.5f`) still permits motion.
 */
class ReducedMotionTest {

    @Test
    fun `default scales (both 1) do not reduce motion`() {
        assertFalse(ReducedMotion.isReduced(transitionScale = 1f, animatorScale = 1f))
    }

    @Test
    fun `transition scale 0 reduces motion`() {
        assertTrue(ReducedMotion.isReduced(transitionScale = 0f, animatorScale = 1f))
    }

    @Test
    fun `animator scale 0 reduces motion`() {
        assertTrue(ReducedMotion.isReduced(transitionScale = 1f, animatorScale = 0f))
    }

    @Test
    fun `both scales 0 reduces motion`() {
        assertTrue(ReducedMotion.isReduced(transitionScale = 0f, animatorScale = 0f))
    }

    @Test
    fun `non-zero scaled animations are not reduced`() {
        assertFalse(ReducedMotion.isReduced(transitionScale = 0.5f, animatorScale = 2f))
    }
}
