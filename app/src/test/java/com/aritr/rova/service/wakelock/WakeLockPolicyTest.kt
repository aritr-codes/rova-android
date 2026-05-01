package com.aritr.rova.service.wakelock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1.8 / C17 — [WakeLockPolicy] regression suite.
 *
 * Pure-JVM coverage of the two contract surfaces extracted from
 * [com.aritr.rova.service.RovaRecordingService]:
 *  - bounded acquire (timeout constant)
 *  - exception-safe release (RuntimeException swallow)
 *
 * No Robolectric, no Android framework — the helper is pure on
 * purpose so the JVM suite can carry the regression without a
 * service-wide DI seam. The static `checkWakeLockBoundedAcquire` lint
 * (preBuild) covers the call-site discipline that this test cannot.
 */
class WakeLockPolicyTest {

    // ─── Bounded acquire ────────────────────────────────────────────

    @Test
    fun `acquire timeout is at least 10 minutes`() {
        val tenMinutes = 10L * 60L * 1000L
        assertTrue(
            "C17 contract: WakeLock acquire timeout must be >= 10 min so a single segment cannot expire it.",
            WakeLockPolicy.ACQUIRE_TIMEOUT_MS >= tenMinutes
        )
    }

    @Test
    fun `acquire timeout has explicit value`() {
        assertEquals(10L * 60L * 1000L, WakeLockPolicy.ACQUIRE_TIMEOUT_MS)
    }

    // ─── Exception-safe release ─────────────────────────────────────

    @Test
    fun `safeRelease invokes block exactly once on the happy path`() {
        var calls = 0
        WakeLockPolicy.safeRelease { calls++ }
        assertEquals(1, calls)
    }

    @Test
    fun `safeRelease swallows WakeLock under-locked RuntimeException`() {
        // No assertThrows — the call must complete normally.
        WakeLockPolicy.safeRelease {
            throw RuntimeException("WakeLock under-locked")
        }
    }

    @Test
    fun `safeRelease swallows IllegalStateException as a RuntimeException subtype`() {
        WakeLockPolicy.safeRelease {
            throw IllegalStateException("simulated platform drift")
        }
    }

    @Test
    fun `safeRelease propagates Error subtypes`() {
        // Errors are NOT RuntimeExceptions; the helper must not swallow
        // them. This guards against accidentally widening the catch.
        assertThrows(OutOfMemoryError::class.java) {
            WakeLockPolicy.safeRelease {
                throw OutOfMemoryError("simulated OOM")
            }
        }
    }

    @Test
    fun `safeRelease invokes block once even when block throws`() {
        var calls = 0
        WakeLockPolicy.safeRelease {
            calls++
            throw RuntimeException("WakeLock under-locked")
        }
        assertEquals(1, calls)
    }
}
