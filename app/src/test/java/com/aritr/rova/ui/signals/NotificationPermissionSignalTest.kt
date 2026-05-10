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
 * Phase 3.2 — pure-JVM tests for [NotificationPermissionSignal].
 *
 * Covers the contract from the slice brief:
 *  - granted-on-init / denied-on-init: initial value reflects the
 *    injected `isGranted` at construction
 *  - [NotificationPermissionSignal.refresh] flips state when the
 *    underlying flag changes; idempotent on unchanged value
 *    ([kotlinx.coroutines.flow.MutableStateFlow] dedupes equals)
 *  - SDK gate: below API 33 (TIRAMISU) the signal reports constant
 *    `true` and never invokes the `isGranted` seam
 *
 * Constructor seams keep the test pure JVM — no Robolectric, no
 * Context fakes. Same shape as
 * [com.aritr.rova.ui.screens.onboarding.OnboardingViewModelTest].
 */
class NotificationPermissionSignalTest {

    @Test fun `granted on init exposes true`() {
        val signal = NotificationPermissionSignal(sdkInt = 33, isGranted = { true })
        assertTrue(signal.state.value)
    }

    @Test fun `denied on init exposes false`() {
        val signal = NotificationPermissionSignal(sdkInt = 33, isGranted = { false })
        assertFalse(signal.state.value)
    }

    @Test fun `refresh flips state when permission grant changes`() {
        val granted = AtomicBoolean(false)
        val signal = NotificationPermissionSignal(sdkInt = 33, isGranted = { granted.get() })
        assertFalse(signal.state.value)
        granted.set(true); signal.refresh()
        assertTrue(signal.state.value)
        granted.set(false); signal.refresh()
        assertFalse(signal.state.value)
    }

    @Test fun `idempotent refresh emits exactly once for unchanged value`() = runBlocking {
        val signal = NotificationPermissionSignal(sdkInt = 33, isGranted = { true })
        val emissions = mutableListOf<Boolean>()
        val job = launch(Dispatchers.Unconfined) {
            signal.state.collect { emissions += it }
        }
        signal.refresh(); signal.refresh(); signal.refresh()
        yield()
        job.cancelAndJoin()
        assertEquals(listOf(true), emissions)
    }

    @Test fun `refresh re-reads isGranted on each call (API 33 plus)`() {
        val callCount = AtomicInteger(0)
        val signal = NotificationPermissionSignal(
            sdkInt = 33,
            isGranted = { callCount.incrementAndGet(); true }
        )
        assertEquals(1, callCount.get())
        signal.refresh(); assertEquals(2, callCount.get())
        signal.refresh(); assertEquals(3, callCount.get())
    }

    @Test fun `pre-API-33 reports true and never invokes isGranted`() {
        val callCount = AtomicInteger(0)
        val signal = NotificationPermissionSignal(
            sdkInt = 32,
            isGranted = { callCount.incrementAndGet(); false }
        )
        assertTrue(signal.state.value)
        signal.refresh(); signal.refresh()
        assertTrue(signal.state.value)
        assertEquals(0, callCount.get())
    }

    @Test fun `pre-API-33 ignores grant flips on refresh`() {
        val granted = AtomicBoolean(false)
        val signal = NotificationPermissionSignal(sdkInt = 30, isGranted = { granted.get() })
        assertTrue(signal.state.value)
        granted.set(true); signal.refresh(); assertTrue(signal.state.value)
        granted.set(false); signal.refresh(); assertTrue(signal.state.value)
    }
}
