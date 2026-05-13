package com.aritr.rova.ui.signals

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageLowMidRecSignalTest {

    @Test fun pollAboveThreshold_isFalse() {
        val s = StorageLowMidRecSignal(computeIsLow = { _, _ -> false })
        s.poll(durationSeconds = 30, resolution = "1080p")
        assertFalse(s.isLow.value)
    }

    @Test fun pollBelowThreshold_isTrue() {
        val s = StorageLowMidRecSignal(computeIsLow = { _, _ -> true })
        s.poll(durationSeconds = 30, resolution = "1080p")
        assertTrue(s.isLow.value)
    }

    @Test fun computeLambda_receivesCallerSettings() {
        var lastDur = -1
        var lastRes = ""
        val s = StorageLowMidRecSignal(computeIsLow = { d, r ->
            lastDur = d; lastRes = r; false
        })
        s.poll(durationSeconds = 45, resolution = "4K")
        assertEquals(45, lastDur)
        assertEquals("4K", lastRes)
    }

    @Test fun clear_resetsToFalse() {
        val s = StorageLowMidRecSignal(computeIsLow = { _, _ -> true })
        s.poll(durationSeconds = 30, resolution = "1080p")
        assertTrue(s.isLow.value)
        s.clear()
        assertFalse(s.isLow.value)
    }

    @Test fun pollTransitions_bothDirections() {
        var low = true
        val s = StorageLowMidRecSignal(computeIsLow = { _, _ -> low })
        s.poll(30, "1080p"); assertTrue(s.isLow.value)
        low = false
        s.poll(30, "1080p"); assertFalse(s.isLow.value)
        low = true
        s.poll(30, "1080p"); assertTrue(s.isLow.value)
    }

    @Test fun initialState_isFalse() {
        val s = StorageLowMidRecSignal(computeIsLow = { _, _ -> true })
        // No poll() yet — the StateFlow seed is always false (the recording isn't running).
        assertFalse(s.isLow.value)
    }
}
