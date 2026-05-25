package com.aritr.rova.ui.signals

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryMergeOutcomeSignalTest {

    @Test
    fun `initial state is Idle`() {
        val signal = RecoveryMergeOutcomeSignal()
        assertEquals(RecoveryMergeOutcomeSignal.State.Idle, signal.state.value)
    }

    @Test
    fun `emitInProgress carries sessionId and progress`() = runBlocking {
        val signal = RecoveryMergeOutcomeSignal()
        signal.emitInProgress("sess-1", 0.25f)
        val s = signal.state.value
        assertTrue(s is RecoveryMergeOutcomeSignal.State.InProgress)
        s as RecoveryMergeOutcomeSignal.State.InProgress
        assertEquals("sess-1", s.sessionId)
        assertEquals(0.25f, s.progress, 0f)
    }

    @Test
    fun `emitOutcome InsufficientStorage carries required and available bytes`() = runBlocking {
        val signal = RecoveryMergeOutcomeSignal()
        signal.emitOutcome(
            sessionId = "sess-2",
            outcome = RecoveryMergeOutcomeSignal.RecoveryMergeOutcome.InsufficientStorage(
                requiredBytes = 105_000,
                availableBytes = 50_000,
            ),
        )
        val s = signal.state.value
        assertTrue(s is RecoveryMergeOutcomeSignal.State.Outcome)
        s as RecoveryMergeOutcomeSignal.State.Outcome
        assertEquals("sess-2", s.sessionId)
        val o = s.outcome
        assertTrue(o is RecoveryMergeOutcomeSignal.RecoveryMergeOutcome.InsufficientStorage)
        o as RecoveryMergeOutcomeSignal.RecoveryMergeOutcome.InsufficientStorage
        assertEquals(105_000L, o.requiredBytes)
    }

    @Test
    fun `acknowledge resets state to Idle`() = runBlocking {
        val signal = RecoveryMergeOutcomeSignal()
        signal.emitOutcome("sess-3", RecoveryMergeOutcomeSignal.RecoveryMergeOutcome.Succeeded)
        signal.acknowledge("sess-3")
        assertEquals(RecoveryMergeOutcomeSignal.State.Idle, signal.state.value)
    }

    @Test
    fun `acknowledge with wrong sessionId is a no-op`() = runBlocking {
        val signal = RecoveryMergeOutcomeSignal()
        signal.emitOutcome("sess-4", RecoveryMergeOutcomeSignal.RecoveryMergeOutcome.Succeeded)
        signal.acknowledge("sess-different")
        val s = signal.state.value
        assertTrue(s is RecoveryMergeOutcomeSignal.State.Outcome)
        s as RecoveryMergeOutcomeSignal.State.Outcome
        assertEquals("sess-4", s.sessionId)
    }
}
