package com.aritr.rova.ui.library

import org.junit.Assert.assertEquals
import org.junit.Test

class PressFeedbackTest {
    @Test fun pressed_scalesDown() {
        assertEquals(0.97f, PressFeedback.targetScale(pressed = true, reduceMotion = false), 0.0001f)
    }

    @Test fun released_isUnity() {
        assertEquals(1f, PressFeedback.targetScale(pressed = false, reduceMotion = false), 0.0001f)
    }

    @Test fun reduceMotion_neverScales() {
        assertEquals(1f, PressFeedback.targetScale(pressed = true, reduceMotion = true), 0.0001f)
    }
}
