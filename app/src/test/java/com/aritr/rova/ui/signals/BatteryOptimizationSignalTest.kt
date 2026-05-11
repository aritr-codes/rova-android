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
 * Phase 4.1 — pure-JVM tests for [BatteryOptimizationSignal]. The
 * constructor seam keeps the test off any real Context — same shape as
 * [NotificationPermissionSignalTest].
 */
class BatteryOptimizationSignalTest {

    @Test fun `exempt on init exposes true`() {
        val signal = BatteryOptimizationSignal(isExemptNow = { true })
        assertTrue(signal.isExempt.value)
    }

    @Test fun `not exempt on init exposes false`() {
        val signal = BatteryOptimizationSignal(isExemptNow = { false })
        assertFalse(signal.isExempt.value)
    }

    @Test fun `refresh flips state when exemption changes`() {
        val exempt = AtomicBoolean(false)
        val signal = BatteryOptimizationSignal(isExemptNow = { exempt.get() })
        assertFalse(signal.isExempt.value)
        exempt.set(true); signal.refresh()
        assertTrue(signal.isExempt.value)
        exempt.set(false); signal.refresh()
        assertFalse(signal.isExempt.value)
    }

    @Test fun `idempotent refresh emits exactly once for unchanged value`() = runBlocking {
        val signal = BatteryOptimizationSignal(isExemptNow = { true })
        val emissions = mutableListOf<Boolean>()
        val job = launch(Dispatchers.Unconfined) { signal.isExempt.collect { emissions += it } }
        signal.refresh(); signal.refresh()
        yield()
        job.cancelAndJoin()
        assertEquals(listOf(true), emissions)
    }
}
