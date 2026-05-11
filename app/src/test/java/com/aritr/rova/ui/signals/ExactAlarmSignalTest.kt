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
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase 3.6 — pure-JVM tests for [ExactAlarmSignal].
 *
 * Covers the contract from the slice brief:
 *  - granted-on-init / denied-on-init: initial value reflects the
 *    injected `canScheduleExactAlarms` at construction
 *  - [ExactAlarmSignal.refresh] flips state when the underlying grant
 *    changes; idempotent on unchanged value
 *    ([kotlinx.coroutines.flow.MutableStateFlow] dedupes equals)
 *  - SDK gate: below API 31 (S) the signal reports constant `true`
 *    and never invokes the `canScheduleExactAlarms` seam (the OS has
 *    no concept of exact-alarm revocation pre-S — always granted).
 *
 * Constructor seams keep the test pure JVM — no Robolectric, no
 * Context fakes. Same shape as [NotificationPermissionSignalTest].
 */
class ExactAlarmSignalTest {

    @Test fun `granted on init exposes true`() {
        val signal = ExactAlarmSignal(sdkInt = 31, canScheduleExactAlarms = { true })
        assertTrue(signal.state.value)
    }

    @Test fun `denied on init exposes false`() {
        val signal = ExactAlarmSignal(sdkInt = 31, canScheduleExactAlarms = { false })
        assertFalse(signal.state.value)
    }

    @Test fun `refresh flips state when grant changes`() {
        val granted = AtomicBoolean(false)
        val signal = ExactAlarmSignal(sdkInt = 31, canScheduleExactAlarms = { granted.get() })
        assertFalse(signal.state.value)
        granted.set(true); signal.refresh()
        assertTrue(signal.state.value)
        granted.set(false); signal.refresh()
        assertFalse(signal.state.value)
    }

    @Test fun `idempotent refresh emits exactly once for unchanged value`() = runBlocking {
        val signal = ExactAlarmSignal(sdkInt = 31, canScheduleExactAlarms = { true })
        val emissions = mutableListOf<Boolean>()
        val job = launch(Dispatchers.Unconfined) {
            signal.state.collect { emissions += it }
        }
        signal.refresh(); signal.refresh(); signal.refresh()
        yield()
        job.cancelAndJoin()
        assertEquals(listOf(true), emissions)
    }

    @Test fun `refresh re-reads canScheduleExactAlarms on each call (API 31 plus)`() {
        val callCount = AtomicInteger(0)
        val signal = ExactAlarmSignal(
            sdkInt = 31,
            canScheduleExactAlarms = { callCount.incrementAndGet(); true }
        )
        assertEquals(1, callCount.get())
        signal.refresh(); assertEquals(2, callCount.get())
        signal.refresh(); assertEquals(3, callCount.get())
    }

    @Test fun `pre-API-31 reports true and never invokes canScheduleExactAlarms`() {
        val callCount = AtomicInteger(0)
        val signal = ExactAlarmSignal(
            sdkInt = 30,
            canScheduleExactAlarms = { callCount.incrementAndGet(); false }
        )
        assertTrue(signal.state.value)
        signal.refresh(); signal.refresh()
        assertTrue(signal.state.value)
        assertEquals(0, callCount.get())
    }

    @Test fun `pre-API-31 ignores grant flips on refresh`() {
        val granted = AtomicBoolean(false)
        val signal = ExactAlarmSignal(sdkInt = 28, canScheduleExactAlarms = { granted.get() })
        assertTrue(signal.state.value)
        granted.set(true); signal.refresh(); assertTrue(signal.state.value)
        granted.set(false); signal.refresh(); assertTrue(signal.state.value)
    }
}
