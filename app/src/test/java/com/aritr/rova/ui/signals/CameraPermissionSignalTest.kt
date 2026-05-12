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
 * Phase 4.1b — pure-JVM tests for [CameraPermissionSignal]. The ctor seam
 * keeps the test off a real Context — same shape as [NotificationPermissionSignalTest].
 */
class CameraPermissionSignalTest {

    @Test fun `granted on init exposes true`() {
        assertTrue(CameraPermissionSignal(isGranted = { true }).state.value)
    }

    @Test fun `denied on init exposes false`() {
        assertFalse(CameraPermissionSignal(isGranted = { false }).state.value)
    }

    @Test fun `refresh flips state when the grant changes`() {
        val granted = AtomicBoolean(false)
        val signal = CameraPermissionSignal(isGranted = { granted.get() })
        assertFalse(signal.state.value)
        granted.set(true); signal.refresh()
        assertTrue(signal.state.value)
        granted.set(false); signal.refresh()
        assertFalse(signal.state.value)
    }

    @Test fun `idempotent refresh emits exactly once for unchanged value`() = runBlocking {
        val signal = CameraPermissionSignal(isGranted = { true })
        val emissions = mutableListOf<Boolean>()
        val job = launch(Dispatchers.Unconfined) { signal.state.collect { emissions += it } }
        signal.refresh(); signal.refresh()
        yield()
        job.cancelAndJoin()
        assertEquals(listOf(true), emissions)
    }
}
