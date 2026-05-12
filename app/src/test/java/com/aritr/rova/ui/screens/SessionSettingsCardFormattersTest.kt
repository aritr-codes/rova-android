package com.aritr.rova.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionSettingsCardFormattersTest {

    @Test fun clipValues() {
        assertEquals("0 s", recordClipValue(0))
        assertEquals("10 s", recordClipValue(10))
        assertEquals("1 m", recordClipValue(60))
        assertEquals("2 m", recordClipValue(120))
        assertEquals("90 s", recordClipValue(90))
    }

    @Test fun repeatsValues() {
        assertEquals("10", recordRepeatsValue(10))
        assertEquals("1", recordRepeatsValue(1))
        assertEquals("Until you stop", recordRepeatsValue(-1))
    }

    @Test fun waitValues() {
        assertEquals("None", recordWaitValue(0))
        assertEquals("1 m", recordWaitValue(1))
        assertEquals("45 m", recordWaitValue(45))
        assertEquals("1 h", recordWaitValue(60))
        assertEquals("2 h", recordWaitValue(120))
    }

    @Test fun modeValue() {
        assertEquals("Portrait", recordModeValue())
    }
}
