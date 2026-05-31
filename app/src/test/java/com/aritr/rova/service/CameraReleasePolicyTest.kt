package com.aritr.rova.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraReleasePolicyTest {
    @Test
    fun `releases idle preview on background`() {
        assertTrue(shouldReleaseCameraOnBackground(isRecording = false))
    }

    @Test
    fun `keeps camera bound while recording on background`() {
        assertFalse(shouldReleaseCameraOnBackground(isRecording = true))
    }
}
