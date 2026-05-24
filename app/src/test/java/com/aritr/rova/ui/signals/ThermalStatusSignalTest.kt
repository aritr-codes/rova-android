package com.aritr.rova.ui.signals

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase 3.4 — pure-JVM tests for [ThermalStatusSignal].
 *
 * Covers the contract from the slice brief:
 *  - `fromRaw` maps PowerManager.THERMAL_STATUS_* ints to the enum;
 *    unknown ints map defensively to [ThermalStatus.NONE]
 *  - granted-on-init: initial value reflects mapped seam value
 *  - [ThermalStatusSignal.refresh] flips state when raw changes;
 *    idempotent on unchanged value (MutableStateFlow dedupes equals)
 *  - SDK gate: below API 29 (Q) the signal reports constant
 *    [ThermalStatus.NONE] and never invokes the `currentStatus` seam
 *
 * Constructor seams keep the test pure JVM — no Robolectric, no
 * PowerManager fakes. Same shape as
 * [com.aritr.rova.ui.signals.NotificationPermissionSignalTest].
 */
class ThermalStatusSignalTest {

    @Test fun `fromRaw maps known values to enum and unknowns to NONE`() {
        assertEquals(ThermalStatus.NONE, ThermalStatus.fromRaw(0))
        assertEquals(ThermalStatus.LIGHT, ThermalStatus.fromRaw(1))
        assertEquals(ThermalStatus.MODERATE, ThermalStatus.fromRaw(2))
        assertEquals(ThermalStatus.SEVERE, ThermalStatus.fromRaw(3))
        assertEquals(ThermalStatus.CRITICAL, ThermalStatus.fromRaw(4))
        assertEquals(ThermalStatus.EMERGENCY, ThermalStatus.fromRaw(5))
        assertEquals(ThermalStatus.SHUTDOWN, ThermalStatus.fromRaw(6))
        assertEquals(ThermalStatus.NONE, ThermalStatus.fromRaw(99))
        assertEquals(ThermalStatus.NONE, ThermalStatus.fromRaw(-1))
    }

    @Test fun `NONE on init when raw is 0`() {
        val signal = ThermalStatusSignal(sdkInt = 29, currentStatus = { 0 })
        assertEquals(ThermalStatus.NONE, signal.state.value)
    }

    @Test fun `SEVERE on init when raw is 3`() {
        val signal = ThermalStatusSignal(sdkInt = 29, currentStatus = { 3 })
        assertEquals(ThermalStatus.SEVERE, signal.state.value)
    }

    @Test fun `refresh flips state when raw changes`() {
        val raw = AtomicInteger(0)
        val signal = ThermalStatusSignal(sdkInt = 29, currentStatus = { raw.get() })
        assertEquals(ThermalStatus.NONE, signal.state.value)
        raw.set(4); signal.refresh()
        assertEquals(ThermalStatus.CRITICAL, signal.state.value)
        raw.set(0); signal.refresh()
        assertEquals(ThermalStatus.NONE, signal.state.value)
    }

    @Test fun `idempotent refresh emits exactly once for unchanged value`() = runBlocking {
        val signal = ThermalStatusSignal(sdkInt = 29, currentStatus = { 2 })
        val emissions = mutableListOf<ThermalStatus>()
        val job = launch(Dispatchers.Unconfined) {
            signal.state.collect { emissions += it }
        }
        signal.refresh(); signal.refresh(); signal.refresh()
        yield()
        job.cancelAndJoin()
        assertEquals(listOf(ThermalStatus.MODERATE), emissions)
    }

    @Test fun `refresh re-reads currentStatus on each call (API 29 plus)`() {
        val callCount = AtomicInteger(0)
        val signal = ThermalStatusSignal(
            sdkInt = 29,
            currentStatus = { callCount.incrementAndGet(); 0 }
        )
        assertEquals(1, callCount.get())
        signal.refresh(); assertEquals(2, callCount.get())
        signal.refresh(); assertEquals(3, callCount.get())
    }

    @Test fun `pre-API-29 reports NONE and never invokes currentStatus`() {
        val callCount = AtomicInteger(0)
        val signal = ThermalStatusSignal(
            sdkInt = 28,
            currentStatus = { callCount.incrementAndGet(); 6 }
        )
        assertEquals(ThermalStatus.NONE, signal.state.value)
        signal.refresh(); signal.refresh()
        assertEquals(ThermalStatus.NONE, signal.state.value)
        assertEquals(0, callCount.get())
    }

    @Test fun `pre-API-29 ignores raw flips on refresh`() {
        val raw = AtomicInteger(0)
        val signal = ThermalStatusSignal(sdkInt = 24, currentStatus = { raw.get() })
        assertEquals(ThermalStatus.NONE, signal.state.value)
        raw.set(4); signal.refresh()
        assertEquals(ThermalStatus.NONE, signal.state.value)
        raw.set(6); signal.refresh()
        assertEquals(ThermalStatus.NONE, signal.state.value)
    }

    // ── Phase 4 Slice 3 — push-listener lifecycle ──

    @Test fun `start registers listener once on API 29 plus`() {
        var registerCalls = 0
        val signal = ThermalStatusSignal(
            sdkInt = 29,
            currentStatus = { 0 },
            addListener = { registerCalls++ },
            removeListener = {},
        )
        signal.start()
        assertEquals(1, registerCalls)
        signal.start()
        assertEquals("second start is idempotent", 1, registerCalls)
    }

    @Test fun `start is no-op pre-API-29`() {
        var registerCalls = 0
        val signal = ThermalStatusSignal(
            sdkInt = 28,
            currentStatus = { 0 },
            addListener = { registerCalls++ },
            removeListener = {},
        )
        signal.start()
        assertEquals(0, registerCalls)
    }

    @Test fun `stop unregisters listener and clears reference`() {
        var registerCalls = 0
        var unregisterCalls = 0
        val signal = ThermalStatusSignal(
            sdkInt = 29,
            currentStatus = { 0 },
            addListener = { registerCalls++ },
            removeListener = { unregisterCalls++ },
        )
        signal.start()
        signal.stop()
        assertEquals(1, unregisterCalls)
        signal.stop()
        assertEquals("stop after stop is idempotent", 1, unregisterCalls)
    }

    @Test fun `listener emission updates state flow`() {
        var captured: ((Int) -> Unit)? = null
        val signal = ThermalStatusSignal(
            sdkInt = 29,
            currentStatus = { 0 },
            addListener = { callback -> captured = callback; callback },
            removeListener = {},
        )
        signal.start()
        assertEquals(ThermalStatus.NONE, signal.state.value)
        captured!!.invoke(3)
        assertEquals(ThermalStatus.SEVERE, signal.state.value)
        captured!!.invoke(4)
        assertEquals(ThermalStatus.CRITICAL, signal.state.value)
    }

    @Test fun `listener emission distinctness dedupes equal values`() = runBlocking {
        var captured: ((Int) -> Unit)? = null
        val signal = ThermalStatusSignal(
            sdkInt = 29,
            currentStatus = { 2 },
            addListener = { callback -> captured = callback; callback },
            removeListener = {},
        )
        signal.start()
        val emissions = mutableListOf<ThermalStatus>()
        val job = launch(Dispatchers.Unconfined) {
            signal.state.collect { emissions += it }
        }
        captured!!.invoke(2)
        captured!!.invoke(2)
        captured!!.invoke(2)
        yield()
        job.cancelAndJoin()
        assertEquals(listOf(ThermalStatus.MODERATE), emissions)
    }
}
