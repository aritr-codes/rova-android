package com.aritr.rova.ui.signals

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phase 4.1b — pure-JVM tests for [MicrophonePermissionSignal]. RECORD_AUDIO
 * grant as a StateFlow; ctor seam keeps the test off a real Context.
 */
class MicrophonePermissionSignalTest {

    @Test fun `granted on init exposes true`() {
        assertTrue(MicrophonePermissionSignal(isGranted = { true }).state.value)
    }

    @Test fun `denied on init exposes false`() {
        assertFalse(MicrophonePermissionSignal(isGranted = { false }).state.value)
    }

    @Test fun `refresh flips state when the grant changes`() {
        val granted = AtomicBoolean(false)
        val signal = MicrophonePermissionSignal(isGranted = { granted.get() })
        assertFalse(signal.state.value)
        granted.set(true); signal.refresh()
        assertTrue(signal.state.value)
        granted.set(false); signal.refresh()
        assertFalse(signal.state.value)
    }

    @Test fun `idempotent refresh emits exactly once for unchanged value`() = runBlocking {
        val signal = MicrophonePermissionSignal(isGranted = { true })
        val emissions = mutableListOf<Boolean>()
        val job = launch(Dispatchers.Unconfined) { signal.state.collect { emissions += it } }
        signal.refresh(); signal.refresh()
        yield()
        job.cancelAndJoin()
        assertEquals(listOf(true), emissions)
    }
}
