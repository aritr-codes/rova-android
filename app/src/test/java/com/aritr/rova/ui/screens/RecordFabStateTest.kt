package com.aritr.rova.ui.screens

import com.aritr.rova.ui.components.RecordHudState
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordFabStateTest {

    @Test fun idle_notBlocked_isStart() {
        assertEquals(RecordFabState.Start, recordFabState(RecordHudState.Idle, sessionLocked = false, hardBlockActive = false))
    }

    @Test fun idle_hardBlocked_isDisabled() {
        assertEquals(RecordFabState.Disabled, recordFabState(RecordHudState.Idle, sessionLocked = false, hardBlockActive = true))
    }

    @Test fun recording_isStop_regardlessOfBlocks() {
        assertEquals(RecordFabState.Stop, recordFabState(RecordHudState.Recording, sessionLocked = true, hardBlockActive = false))
        assertEquals(RecordFabState.Stop, recordFabState(RecordHudState.Recording, sessionLocked = true, hardBlockActive = true))
    }

    @Test fun waiting_isStop() {
        assertEquals(RecordFabState.Stop, recordFabState(RecordHudState.Waiting, sessionLocked = true, hardBlockActive = false))
    }

    @Test fun merging_isDisabled() {
        assertEquals(
            RecordFabState.Disabled,
            recordFabState(RecordHudState.Merging(progress = 0.5f, currentSegment = 1, totalSegments = 2), sessionLocked = true, hardBlockActive = false)
        )
    }
}
