package com.aritr.rova.ui.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeroUnderlayPolicyTest {
    @Test fun showsUnderlay_beforeFirstFrame() {
        assertTrue(HeroUnderlayPolicy.showStaticUnderlay(firstFrameRendered = false))
    }

    @Test fun hidesUnderlay_afterFirstFrame() {
        assertFalse(HeroUnderlayPolicy.showStaticUnderlay(firstFrameRendered = true))
    }
}
