package com.aritr.rova.ui.signals

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B4b (ADR-0024) — unit tests for [SaveFolderSignal].
 * Pure seam: no Android context. Same shape as BatteryOptimizationSignal tests.
 */
class SaveFolderSignalTest {

    @Test fun `initial value false when folder is available`() {
        val signal = SaveFolderSignal { false }
        assertFalse(signal.state.value)
    }

    @Test fun `initial value true when folder is unavailable`() {
        val signal = SaveFolderSignal { true }
        assertTrue(signal.state.value)
    }

    @Test fun `refresh flips false to true when flag changes`() {
        var unavailable = false
        val signal = SaveFolderSignal { unavailable }
        assertFalse(signal.state.value)

        unavailable = true
        signal.refresh()
        assertTrue(signal.state.value)
    }

    @Test fun `refresh flips true to false when flag changes`() {
        var unavailable = true
        val signal = SaveFolderSignal { unavailable }
        assertTrue(signal.state.value)

        unavailable = false
        signal.refresh()
        assertFalse(signal.state.value)
    }

    @Test fun `refresh is idempotent when value unchanged`() {
        var unavailable = false
        val signal = SaveFolderSignal { unavailable }
        signal.refresh()
        signal.refresh()
        // MutableStateFlow dedupes — value stays false
        assertFalse(signal.state.value)
    }
}
