package com.aritr.rova.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RovaMotionTest {

    @Test
    fun `container spring constants match the spec`() {
        assertEquals(380f, RovaMotion.containerStiffness, 0f)
        assertEquals(30f, RovaMotion.containerDamping, 0f)
    }

    @Test
    fun `standard durations match the spec`() {
        assertEquals(120, RovaMotion.chipToggleMs)
        assertEquals(200, RovaMotion.dockShrinkMs)
    }

    @Test
    fun `record pulse is in the 1800-2200ms band`() {
        assertTrue(RovaMotion.recordPulseMs in 1800..2200)
    }

    // ── Trust System V1 ladder additions (M1) ────────────────────────
    // Frozen spec `docs/design/warnings-recovery.html` :111–:112 and the §12
    // motion table (:900). Additive: no existing consumer changes. M6 spends
    // these on the sheet enter/exit and chip collapse/restore so that motion
    // never lands as an inline duration.

    @Test
    fun `container and exit durations match the frozen ladder`() {
        // `--t-container:300ms; --t-exit:220ms` — asymmetric by design: the
        // sheet leaves faster than it arrives.
        assertEquals(300, RovaMotion.containerMs)
        assertEquals(220, RovaMotion.exitMs)
    }

    @Test
    fun `exit is faster than enter`() {
        assertTrue(RovaMotion.exitMs < RovaMotion.containerMs)
    }

    @Test
    fun `the whole duration ladder is ordered micro to container`() {
        // `--t-micro:120ms; --t-small:200ms; --t-exit:220ms; --t-container:300ms`
        assertTrue(
            RovaMotion.chipToggleMs < RovaMotion.dockShrinkMs &&
                RovaMotion.dockShrinkMs < RovaMotion.exitMs &&
                RovaMotion.exitMs < RovaMotion.containerMs
        )
    }

    @Test
    fun `standard easing is the frozen cubic bezier`() {
        // `--ease-std:cubic-bezier(.2,.8,.2,1)` — all motion, adopted from bento.
        assertEquals(CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f), RovaMotion.easeStandard)
    }

    @Test
    fun `standard easing is anchored at both endpoints`() {
        // Guards against a transposed control point silently changing the curve.
        assertEquals(0f, RovaMotion.easeStandard.transform(0f), 1e-4f)
        assertEquals(1f, RovaMotion.easeStandard.transform(1f), 1e-4f)
    }

    @Test
    fun `standard easing decelerates - it is past halfway at the midpoint`() {
        // The .2,.8 control point front-loads travel: an ease-out curve.
        assertTrue(RovaMotion.easeStandard.transform(0.5f) > 0.5f)
    }
}
