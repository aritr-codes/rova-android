package com.aritr.rova.service

import com.aritr.rova.ui.signals.RecoveryMergeOutcomeSignal
import org.junit.Test
import org.junit.Assert.assertEquals

class RovaRecordingServiceRecoveryGateTest {

    @Test
    fun `gate refuses when recording active`() {
        val outcome = recoveryMergeStartGate(isRecordingActive = true, sessionId = "sess-1")
        assertEquals(
            RecoveryMergeStartDecision.Reject(
                outcome = RecoveryMergeOutcomeSignal.RecoveryMergeOutcome.ServiceBusy,
            ),
            outcome,
        )
    }

    @Test
    fun `gate accepts when idle`() {
        val outcome = recoveryMergeStartGate(isRecordingActive = false, sessionId = "sess-2")
        assertEquals(RecoveryMergeStartDecision.Accept(sessionId = "sess-2"), outcome)
    }

    @Test
    fun `gate rejects when sessionId blank`() {
        val outcome = recoveryMergeStartGate(isRecordingActive = false, sessionId = "")
        assertEquals(
            RecoveryMergeStartDecision.Reject(
                outcome = RecoveryMergeOutcomeSignal.RecoveryMergeOutcome.UnknownSession,
            ),
            outcome,
        )
    }
}
