package com.aritr.rova.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * B5 / ADR-0025 — pure ref-count + side-effect-firing verification for
 * [SecureFlagController]. The count logic is the load-bearing fix for the
 * FLAG_SECURE nav-overlap bug (vault list onDispose wiping the player's flag);
 * the window side effects are injected as lambdas so the counter is JVM-testable.
 */
class SecureFlagControllerTest {

    private class Probe {
        var firstAcquireFires = 0
        var lastReleaseFires = 0
        val controller = SecureFlagController(
            onFirstAcquire = { firstAcquireFires++ },
            onLastRelease = { lastReleaseFires++ },
        )
    }

    @Test
    fun firstAcquire_firesOnFirstAcquire() {
        val p = Probe()
        p.controller.acquire()
        assertEquals(1, p.firstAcquireFires)
        assertEquals(0, p.lastReleaseFires)
        assertEquals(1, p.controller.activeCount)
    }

    @Test
    fun nestedAcquire_doesNotRefire() {
        val p = Probe()
        p.controller.acquire()
        p.controller.acquire()
        assertEquals(1, p.firstAcquireFires)
        assertEquals(2, p.controller.activeCount)
    }

    @Test
    fun releaseDownToPositive_doesNotFireLastRelease() {
        val p = Probe()
        p.controller.acquire()
        p.controller.acquire()
        p.controller.release()
        assertEquals(0, p.lastReleaseFires)
        assertEquals(1, p.controller.activeCount)
    }

    @Test
    fun releaseToZero_firesLastReleaseOnce() {
        val p = Probe()
        p.controller.acquire()
        p.controller.release()
        assertEquals(1, p.lastReleaseFires)
        assertEquals(0, p.controller.activeCount)
    }

    @Test
    fun overRelease_isNoOp() {
        val p = Probe()
        p.controller.acquire()
        p.controller.release()
        // Already at zero — further releases must not go negative or re-fire.
        p.controller.release()
        p.controller.release()
        assertEquals(1, p.lastReleaseFires)
        assertEquals(0, p.controller.activeCount)
    }

    @Test
    fun overlapSequence_firesEachSideEffectOnce() {
        // The nav-transition overlap: list acquires, player acquires, list's
        // late onDispose releases, player leaves and releases. FLAG_SECURE must
        // be set exactly once and cleared exactly once.
        val p = Probe()
        p.controller.acquire() // vault list
        p.controller.acquire() // player (isVaulted)
        p.controller.release() // vault list delayed onDispose — flag STAYS on
        assertEquals(1, p.controller.activeCount)
        assertEquals(0, p.lastReleaseFires)
        p.controller.release() // player leaves — flag clears
        assertEquals(1, p.firstAcquireFires)
        assertEquals(1, p.lastReleaseFires)
        assertEquals(0, p.controller.activeCount)
    }
}
