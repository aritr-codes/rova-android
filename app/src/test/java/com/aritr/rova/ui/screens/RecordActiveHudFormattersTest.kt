package com.aritr.rova.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordActiveHudFormattersTest {

    // ── loopPillContent ─────────────────────────────────────────────

    @Test fun loop_singleClip_isHidden() {
        assertNull(loopPillContent(loopIndex = 0, loopTotal = 1))
    }

    @Test fun loop_zeroClip_isHidden() {
        // Defensive — loopTotal == 0 shouldn't happen through legitimate session config,
        // but the helper hides the pill rather than rendering "0/0 loops done".
        assertNull(loopPillContent(loopIndex = 0, loopTotal = 0))
    }

    @Test fun loop_indefinite_omitsTotal() {
        assertEquals("3 loops done", loopPillContent(loopIndex = 3, loopTotal = -1))
    }

    @Test fun loop_indefinite_clampsNegativeIndex() {
        assertEquals("0 loops done", loopPillContent(loopIndex = -2, loopTotal = -1))
    }

    @Test fun loop_finite_formatsSlash() {
        assertEquals("4/10 loops done", loopPillContent(loopIndex = 4, loopTotal = 10))
    }

    @Test fun loop_finite_clampsOverflowIndex() {
        assertEquals("10/10 loops done", loopPillContent(loopIndex = 12, loopTotal = 10))
    }
}
