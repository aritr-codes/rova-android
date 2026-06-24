package com.aritr.rova.ui.screens.player

import com.aritr.rova.service.dualrecord.VideoSide
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerViewModelResumeTest {

    @Test fun resolveStart_appliesResumePolicy() {
        // 50s saved into a 100s recording → resume at 50s.
        assertEquals(50_000L, PlayerResumeMath.startPositionMs(saved = 50_000L, durationMs = 100_000L))
        // null saved → start at 0.
        assertEquals(0L, PlayerResumeMath.startPositionMs(saved = null, durationMs = 100_000L))
        // near-end → restart at 0.
        assertEquals(0L, PlayerResumeMath.startPositionMs(saved = 99_900L, durationMs = 100_000L))
    }

    @Test fun sideSlotIsThreaded_singleVsPL() {
        assertEquals("", com.aritr.rova.ui.library.RecordingIdentity.sideSlot(null))
        assertEquals("LANDSCAPE", com.aritr.rova.ui.library.RecordingIdentity.sideSlot(VideoSide.LANDSCAPE))
    }
}
