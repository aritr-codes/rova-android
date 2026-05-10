package com.aritr.rova.ui.signals

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Phase 3.3 — pure-JVM tests for [PowerSignal].
 *
 * Single composite snapshot seam `() -> PowerState`. No `sdkInt`
 * param: minSdk = 24 ≥ all three required APIs (21). Pattern mirrors
 * [NotificationPermissionSignalTest] / [ThermalStatusSignalTest] —
 * runBlocking + Unconfined collector + cancelAndJoin (no Turbine,
 * no coroutines-test).
 */
class PowerSignalTest {

    @Test fun `initial state mirrors snapshot`() {
        val snap = PowerState(percent = 50, charging = false, powerSaveMode = false)
        val signal = PowerSignal(currentSnapshot = { snap })
        assertEquals(snap, signal.state.value)
    }

    @Test fun `null percent surfaces from snapshot`() {
        val signal = PowerSignal(currentSnapshot = {
            PowerState(percent = null, charging = false, powerSaveMode = false)
        })
        assertEquals(null, signal.state.value.percent)
    }

    @Test fun `refresh flips charging when snapshot changes`() {
        val ref = AtomicReference(PowerState(50, false, false))
        val signal = PowerSignal(currentSnapshot = { ref.get() })
        assertEquals(false, signal.state.value.charging)
        ref.set(PowerState(50, true, false)); signal.refresh()
        assertEquals(true, signal.state.value.charging)
    }

    @Test fun `powerSaveMode flips independently of percent and charging`() {
        val ref = AtomicReference(PowerState(80, false, false))
        val signal = PowerSignal(currentSnapshot = { ref.get() })
        assertEquals(false, signal.state.value.powerSaveMode)
        ref.set(PowerState(80, false, true)); signal.refresh()
        assertEquals(true, signal.state.value.powerSaveMode)
        assertEquals(80, signal.state.value.percent)
        assertEquals(false, signal.state.value.charging)
    }

    @Test fun `refresh re-reads snapshot on each call`() {
        val callCount = AtomicInteger(0)
        val signal = PowerSignal(currentSnapshot = {
            callCount.incrementAndGet()
            PowerState(50, false, false)
        })
        assertEquals(1, callCount.get())
        signal.refresh(); assertEquals(2, callCount.get())
        signal.refresh(); assertEquals(3, callCount.get())
    }

    @Test fun `idempotent refresh emits exactly once for unchanged snapshot`() = runBlocking {
        val snap = PowerState(75, true, false)
        val signal = PowerSignal(currentSnapshot = { snap })
        val emissions = mutableListOf<PowerState>()
        val job = launch(Dispatchers.Unconfined) {
            signal.state.collect { emissions += it }
        }
        signal.refresh(); signal.refresh(); signal.refresh()
        yield()
        job.cancelAndJoin()
        assertEquals(listOf(snap), emissions)
    }

    @Test fun `distinct snapshots emit distinct values`() = runBlocking {
        val ref = AtomicReference(PowerState(50, false, false))
        val signal = PowerSignal(currentSnapshot = { ref.get() })
        val emissions = mutableListOf<PowerState>()
        val job = launch(Dispatchers.Unconfined) {
            signal.state.collect { emissions += it }
        }
        ref.set(PowerState(49, false, false)); signal.refresh()
        ref.set(PowerState(48, false, false)); signal.refresh()
        yield()
        job.cancelAndJoin()
        assertEquals(3, emissions.size)
        assertEquals(50, emissions[0].percent)
        assertEquals(49, emissions[1].percent)
        assertEquals(48, emissions[2].percent)
    }

    @Test fun `PowerState equality is structural (data class contract)`() {
        val a = PowerState(50, charging = true, powerSaveMode = false)
        val b = PowerState(50, charging = true, powerSaveMode = false)
        val c = PowerState(50, charging = true, powerSaveMode = true)
        assertEquals(a, b)
        assertNotEquals(a, c)
    }
}
