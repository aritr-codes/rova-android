package com.aritr.rova.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FinalizeErrorPolicyTest {

    @Test fun suppressNoValidDataWhenStopInFlight() {
        // Aborted final segment of a user/loop stop -> expected, suppress.
        assertTrue(FinalizeErrorPolicy.shouldSuppress(stopInFlight = true, hasError = true, isNoValidData = true))
    }

    @Test fun suppressEmptyNoErrorWhenStopInFlight() {
        // Empty file, no error code (notification_empty_segment) during a stop -> expected.
        assertTrue(FinalizeErrorPolicy.shouldSuppress(stopInFlight = true, hasError = false, isNoValidData = false))
    }

    @Test fun doNotSuppressNoValidDataDuringNormalRecording() {
        // No stop in flight -> a genuine mid-session no-data failure must surface.
        assertFalse(FinalizeErrorPolicy.shouldSuppress(stopInFlight = false, hasError = true, isNoValidData = true))
    }

    @Test fun doNotSuppressOtherErrorEvenDuringStop() {
        // A real error code (e.g. insufficient storage) during a stop still surfaces.
        assertFalse(FinalizeErrorPolicy.shouldSuppress(stopInFlight = true, hasError = true, isNoValidData = false))
    }

    @Test fun doNotSuppressEmptyNoErrorDuringNormalRecording() {
        assertFalse(FinalizeErrorPolicy.shouldSuppress(stopInFlight = false, hasError = false, isNoValidData = false))
    }
}
